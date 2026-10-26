package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.auth.EmailVerificationMapper;
import com.mengde.clouddrive.auth.SmtpCodeMailer;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.persistence.UploadSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * HTTP 合同测试：注册/登录 → 建条目 → 用户隔离 → 回收站 → 恢复（Day15/16/22）。
 *
 * <p><b>运行前置</b>：需要真实的 MySQL 与 Redis。
 * 因此打上 {@code @Tag("integration")}，默认构建会跳过它，
 * 用 {@code mvn test -DexcludedGroups=} 才会执行。
 * 这样做的原因是：没有外部依赖的机器上也应该能 {@code mvn test} 通过，
 * 否则"测试全绿"就变成了"看环境心情"。</p>
 *
 * <p>数据库连接信息全部走环境变量，不在代码里写任何个人凭据。</p>
 */
@Tag("integration")
@SpringBootTest(classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DriveEntryHttpContractTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    DriveEntryRepository entries;
    @Autowired
    UserAccountMapper users;
    @Autowired
    FileShareMapper shares;
    @Autowired
    UploadSessionRepository sessions;
    @Autowired
    EmailVerificationMapper emailCodes;
    @MockBean
    SmtpCodeMailer mailer;

    @BeforeEach
    @AfterEach
    void clean() {
        // 删除顺序必须先子后父，否则外键约束会拒绝删除账号。
        sessions.deleteAll();
        shares.deleteAll();
        entries.deleteAll();
        users.deleteAll();
        emailCodes.deleteAll();
    }

    @Test
    void ownerCanManageEntriesAndOtherUsersCannotSeeThem() {
        Session alice = session("alice" + System.nanoTime());
        Session bob = session("bob" + System.nanoTime());

        ResponseEntity<EntryResponse> created = rest.postForEntity("/api/entries",
                new HttpEntity<>(new EntryCreateRequest(null, "draft.txt", "DOCUMENT", 16L),
                        alice.headers()),
                EntryResponse.class);
        long id = created.getBody().id();

        ResponseEntity<EntryResponse[]> aliceList = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), EntryResponse[].class);
        ResponseEntity<EntryResponse[]> bobList = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(bob.headers()), EntryResponse[].class);
        ResponseEntity<ApiError> bobDetail = rest.exchange("/api/entries/{id}", HttpMethod.GET,
                new HttpEntity<>(bob.headers()), ApiError.class, id);
        ResponseEntity<ApiError> anonymous = rest.getForEntity("/api/entries", ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.CREATED, created.getStatusCode()),
                () -> assertEquals(1, aliceList.getBody().length),
                () -> assertEquals(0, bobList.getBody().length),
                // 越权返回 404 而不是 403：不泄露"这个 id 是否存在"
                () -> assertEquals(HttpStatus.NOT_FOUND, bobDetail.getStatusCode()),
                () -> assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode()));
    }

    @Test
    void duplicateNameInSameDirectoryIsRejectedButAllowedAfterDeletion() {
        Session alice = session("dup" + System.nanoTime());
        HttpEntity<EntryCreateRequest> request = new HttpEntity<>(
                new EntryCreateRequest(null, "same.txt", "DOCUMENT", 1L), alice.headers());

        ResponseEntity<EntryResponse> first = rest.postForEntity("/api/entries", request, EntryResponse.class);
        ResponseEntity<ApiError> duplicate = rest.postForEntity("/api/entries", request, ApiError.class);

        rest.exchange("/api/entries/{id}", HttpMethod.DELETE,
                new HttpEntity<>(alice.headers()), Void.class, first.getBody().id());
        // 删除（进回收站）之后同名应该可以再建：活跃唯一键只约束未删除的条目。
        ResponseEntity<EntryResponse> afterDelete =
                rest.postForEntity("/api/entries", request, EntryResponse.class);

        assertAll(
                () -> assertEquals(HttpStatus.CREATED, first.getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode()),
                () -> assertEquals(HttpStatus.CREATED, afterDelete.getStatusCode()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletedEntryGoesToTrashAndCanBeRestored() {
        Session alice = session("trash" + System.nanoTime());
        Session bob = session("other" + System.nanoTime());
        long id = rest.postForEntity("/api/entries",
                new HttpEntity<>(new EntryCreateRequest(null, "gone.txt", "DOCUMENT", 1L),
                        alice.headers()), EntryResponse.class).getBody().id();

        ResponseEntity<DriveEntryController.AffectedResponse> deleted = rest.exchange("/api/entries/{id}", HttpMethod.DELETE,
                new HttpEntity<>(alice.headers()), DriveEntryController.AffectedResponse.class, id);
        ResponseEntity<ApiError> afterDelete = rest.exchange("/api/entries/{id}", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), ApiError.class, id);
        Map<String, Object> aliceTrash = rest.exchange("/api/entries/trash", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), Map.class).getBody();
        Map<String, Object> bobTrash = rest.exchange("/api/entries/trash", HttpMethod.GET,
                new HttpEntity<>(bob.headers()), Map.class).getBody();
        ResponseEntity<DriveEntryController.AffectedResponse> restored = rest.postForEntity("/api/entries/trash/{id}/restore",
                new HttpEntity<>(alice.headers()), DriveEntryController.AffectedResponse.class, id);
        ResponseEntity<EntryResponse> afterRestore = rest.exchange("/api/entries/{id}", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), EntryResponse.class, id);

        assertAll(
                () -> assertEquals(HttpStatus.OK, deleted.getStatusCode()),
                () -> assertEquals(1, deleted.getBody().affectedCount()),
                // 进了回收站的条目在正常路径上必须查不到
                () -> assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode()),
                () -> assertEquals(1, ((List<?>) aliceTrash.get("items")).size()),
                // 回收站同样受用户隔离约束
                () -> assertEquals(0, ((List<?>) bobTrash.get("items")).size()),
                () -> assertEquals(HttpStatus.OK, restored.getStatusCode()),
                () -> assertEquals(1, restored.getBody().affectedCount()),
                () -> assertEquals("gone.txt", afterRestore.getBody().displayName()));
    }

    @Test
    void invalidPayloadAndMissingTokenReturnUnifiedErrorBody() {
        Session alice = session("valid" + System.nanoTime());

        ResponseEntity<ApiError> blankName = rest.postForEntity("/api/entries",
                new HttpEntity<>(new EntryCreateRequest(null, "  ", "DOCUMENT", 1L), alice.headers()),
                ApiError.class);
        HttpHeaders badToken = new HttpHeaders();
        badToken.setBearerAuth("not-a-real-token");
        badToken.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ApiError> rejected = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(badToken), ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.BAD_REQUEST, blankName.getStatusCode()),
                () -> assertEquals(400, blankName.getBody().status()),
                () -> assertTrue(blankName.getBody().message().contains("displayName")),
                () -> assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode()),
                () -> assertEquals(401, rejected.getBody().status()));
    }

    /** 注册 + 登录，返回带 Bearer 头的会话。 */
    private Session session(String username) {
        String email = username + "@example.com";
        AtomicReference<String> code = new AtomicReference<>();
        doAnswer(invocation -> { code.set(invocation.getArgument(2)); return null; })
                .when(mailer).sendVerification(anyString(), any(), anyString());
        ResponseEntity<Map> sent = rest.postForEntity("/api/auth/email-code",
                Map.of("email", email, "purpose", "REGISTER"), Map.class);
        assertEquals(HttpStatus.OK, sent.getStatusCode());
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity(
                "/api/auth/register", new AuthController.Registration(username, "correct-password", email, code.get()),
                AuthController.UserResponse.class);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode());
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity(
                "/api/auth/login", new AuthController.Credentials(username, "correct-password"),
                AuthController.TokenResponse.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new Session(registered.getBody().id(), headers);
    }

    private record Session(long id, HttpHeaders headers) {
    }
}
