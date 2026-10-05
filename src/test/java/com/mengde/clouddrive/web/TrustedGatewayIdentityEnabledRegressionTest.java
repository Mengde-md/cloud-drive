package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.upload.UploadSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day25 权限回归（真实形态：单体端口不外露，一切流量必经网关）。
 *
 * <p>与默认形态那一组是**对照实验**：同样的伪造身份头，开关打开后就生效了。
 * 把两组放在一起，"这个开关到底管什么"就不是文档里的一句话，而是两条相反的断言。
 *
 * <p>这也解释了为什么它默认必须关：开关一开，X-User-Id 就等同于身份证明，
 * 此时任何绕过网关直连 8080 的路径都是越权通道。开关的前提是网络边界，不是代码。
 */
@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD}",
                "clouddrive.gateway.trusted-headers.enabled=true"
        })
class TrustedGatewayIdentityEnabledRegressionTest {

    @Autowired TestRestTemplate rest;
    @Autowired DriveEntryRepository entries;
    @Autowired UserAccountMapper users;
    @Autowired FileShareMapper shares;
    @Autowired UploadSessionRepository sessions;

    @BeforeEach
    @AfterEach
    void clean() {
        shares.deleteAll();
        sessions.deleteAll();
        entries.deleteAll();
        users.deleteAll();
    }

    /** 走真实注册接口建一个用户，拿到它真实的自增 id——不猜 id，也不写死 1。 */
    private long registerUser(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthController.UserResponse> created = rest.exchange(
                "/api/auth/register", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"correct-horse\"}", headers),
                AuthController.UserResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());
        return created.getBody().id();
    }

    private HttpHeaders identityOf(long id, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", Long.toString(id));
        headers.set("X-User-Name", username);
        headers.set("X-User-Role", "USER");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("开关打开后，网关注入的身份头等价于一次成功的本地验签")
    void identityHeadersAuthenticateWhenTrustIsEnabled() {
        String username = "alice" + System.nanoTime();
        long id = registerUser(username);

        ResponseEntity<String> listed = rest.exchange(
                "/api/entries", HttpMethod.GET, new HttpEntity<>(identityOf(id, username)), String.class);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
    }

    @Test
    @DisplayName("透传身份与数据归属一致：A 的头只能看到 A 的目录，看不到 B 的")
    void identityHeadersScopeDataToTheRightOwner() {
        String alice = "alice" + System.nanoTime();
        String bob = "bob" + System.nanoTime();
        long aliceId = registerUser(alice);
        long bobId = registerUser(bob);

        ResponseEntity<String> created = rest.exchange(
                "/api/entries", HttpMethod.POST,
                new HttpEntity<>("{\"displayName\":\"alice-only\",\"kind\":\"DIRECTORY\"}", identityOf(aliceId, alice)),
                String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        ResponseEntity<String> aliceSees = rest.exchange(
                "/api/entries", HttpMethod.GET, new HttpEntity<>(identityOf(aliceId, alice)), String.class);
        ResponseEntity<String> bobSees = rest.exchange(
                "/api/entries", HttpMethod.GET, new HttpEntity<>(identityOf(bobId, bob)), String.class);

        assertTrue(aliceSees.getBody().contains("alice-only"));
        // 这一条才是"身份透传正确"的真正证据：透传错了人，隔离就没了。
        assertTrue(!bobSees.getBody().contains("alice-only"),
                "B 不应看到 A 的目录，说明透传的身份被用错了");
    }

    @Test
    @DisplayName("开关打开也不等于放弃认证：不带任何身份头仍然 401")
    void noHeadersStillMeansUnauthenticated() {
        ResponseEntity<String> response = rest.exchange(
                "/api/entries", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
