package com.mengde.clouddrive.web;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day25 权限回归（默认形态）。
 *
 * <p>本类回答一个具体问题：**在今天的默认部署形态下，伪造 X-User-Id 能不能越权？**
 * 答案必须是不能。单体的 8080 端口目前在本机直接可达，如果默认信任身份头，
 * 任何能连上 8080 的人都可以自称任意用户——所以 clouddrive.gateway.trusted-headers.enabled
 * 默认为 false，而这一条必须由真实 HTTP 请求证明，不能只由单元测试证明。
 */
@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD}"
        })
class GatewayIdentityHeaderPermissionRegressionTest {

    @Autowired TestRestTemplate rest;
    @Autowired DriveEntryRepository entries;
    @Autowired UserAccountMapper users;
    @Autowired FileShareMapper shares;
    @Autowired UploadSessionRepository sessions;
    @LocalServerPort int port;

    private HttpResponse<String> post(String path, String body, HttpHeaders headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    @AfterEach
    void clean() {
        // 按外键顺序清理，顺序来自 Day23 那次真实失败的修复（file_shares → upload_sessions
        // → drive_entries → user_account）。顺序写错会抛外键约束异常、清理半途而废，
        // 脏数据跨测试类传播。
        shares.deleteAll();
        sessions.deleteAll();
        entries.deleteAll();
        users.deleteAll();
    }

    private HttpHeaders forged() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "1");
        headers.set("X-User-Name", "root");
        headers.set("X-User-Role", "ADMIN");
        return headers;
    }

    @Test
    @DisplayName("默认形态下伪造身份头访问受保护接口必须 401")
    void forgedIdentityHeadersDoNotGrantAccessByDefault() {
        ResponseEntity<String> response = rest.exchange(
                "/api/entries", HttpMethod.GET, new HttpEntity<>(forged()), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "信任开关默认关闭，身份头不应产生任何效果");
    }

    @Test
    @DisplayName("伪造身份头也不能创建目录——写接口与读接口走同一条认证链，一并回归")
    void forgedIdentityHeadersCannotWrite() throws IOException, InterruptedException {
        HttpHeaders headers = forged();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpResponse<String> response = post("/api/entries",
                "{\"displayName\":\"forged\",\"kind\":\"DIRECTORY\"}", headers);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode());
    }

    @Test
    @DisplayName("匿名白名单路径不受影响：/api/auth/login 仍然可达（凭据错误返回 401 而非 403）")
    void anonymousPathsStillReachable() throws IOException, InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpResponse<String> response = post("/api/auth/login",
                "{\"username\":\"nobody\",\"password\":\"correct-horse\"}", headers);

        // 到达了业务逻辑（凭据校验失败），而不是被安全链在更早的地方拦掉。
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode());
        assertTrue(response.body().contains("invalid credentials"),
                "必须是登录业务返回的 401，而非安全过滤链提前拒绝");
    }
}
