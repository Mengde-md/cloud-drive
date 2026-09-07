package com.mengde.clouddrive.web;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day06 HTTP 契约测试：真实启动内嵌 Tomcat（随机端口），
 * 用 TestRestTemplate 发真实 HTTP 请求，逐一验证 2xx/4xx 响应。
 * 快照成功路径（200）与 5xx 路径分别在 SnapshotHttpFailureTest 中覆盖。
 *
 * <p>所有方法共享同一个应用上下文（同一个内存目录），因此“空列表”用
 * {@code @Order(1)} 固定最先执行，其余方法只依赖自己创建的条目，
 * 不对全局列表大小做断言。</p>
 */
@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "clouddrive.snapshot.path=target/day06-http-contract-snapshot.txt")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DriveEntryHttpContractTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @Order(1)
    void listOnFreshApplicationReturns200WithEmptyArray() {
        ResponseEntity<List> response = rest.getForEntity("/api/entries", List.class);

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value()),
                () -> assertNotNull(response.getBody()),
                () -> assertTrue(response.getBody().isEmpty())
        );
    }

    @Test
    void createDocumentReturns201AndEchoesEntry() {
        String body = """
                {"id": 31, "ownerId": 7, "parentId": null,
                 "displayName": "HTTP 契约笔记", "kind": "DOCUMENT", "byteSize": 2048}
                """;
        ResponseEntity<Map> response =
                rest.postForEntity("/api/entries", new HttpEntity<>(body, json()), Map.class);

        assertAll(
                () -> assertEquals(201, response.getStatusCode().value()),
                () -> assertEquals(31, ((Number) response.getBody().get("id")).intValue()),
                () -> assertEquals(7, ((Number) response.getBody().get("ownerId")).intValue()),
                () -> assertNull(response.getBody().get("parentId")),
                () -> assertEquals("HTTP 契约笔记", response.getBody().get("displayName")),
                () -> assertEquals("DOCUMENT", response.getBody().get("kind")),
                () -> assertEquals(2048, ((Number) response.getBody().get("byteSize")).intValue())
        );
    }

    @Test
    void createDirectoryThenListReturns200WithBothKinds() {
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 41, "ownerId": 7, "parentId": null,
                 "displayName": "Day06 目录", "kind": "DIRECTORY"}
                """, json()), Map.class);
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 42, "ownerId": 7, "parentId": 41,
                 "displayName": "Day06 文档", "kind": "DOCUMENT", "byteSize": 100}
                """, json()), Map.class);

        ResponseEntity<List> response = rest.getForEntity("/api/entries", List.class);

        assertEquals(200, response.getStatusCode().value());
        List<?> entries = response.getBody();
        Map<?, ?> directory = entries.stream()
                .map(entry -> (Map<?, ?>) entry)
                .filter(entry -> Integer.valueOf(41).equals(((Number) entry.get("id")).intValue()))
                .findFirst().orElseThrow();
        Map<?, ?> document = entries.stream()
                .map(entry -> (Map<?, ?>) entry)
                .filter(entry -> Integer.valueOf(42).equals(((Number) entry.get("id")).intValue()))
                .findFirst().orElseThrow();
        assertAll(
                () -> assertEquals("DIRECTORY", directory.get("kind")),
                () -> assertNull(directory.get("byteSize")),
                () -> assertNull(directory.get("parentId")),
                () -> assertEquals("DOCUMENT", document.get("kind")),
                () -> assertEquals(100, ((Number) document.get("byteSize")).intValue())
        );
    }

    @Test
    void findExistingEntryReturns200() {
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 51, "ownerId": 7, "parentId": null,
                 "displayName": "详情查询目标", "kind": "DOCUMENT", "byteSize": 1}
                """, json()), Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/api/entries/51", Map.class);

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value()),
                () -> assertEquals("详情查询目标", response.getBody().get("displayName"))
        );
    }

    @Test
    void findMissingEntryReturns404WithErrorBody() {
        ResponseEntity<Map> response = rest.getForEntity("/api/entries/9999", Map.class);

        assertAll(
                () -> assertEquals(404, response.getStatusCode().value()),
                () -> assertEquals(404, ((Number) response.getBody().get("status")).intValue()),
                () -> assertEquals("Not Found", response.getBody().get("error")),
                () -> assertTrue(String.valueOf(response.getBody().get("message")).contains("9999"))
        );
    }

    @Test
    void createDuplicateIdReturns409() {
        String body = """
                {"id": 61, "ownerId": 7, "parentId": null,
                 "displayName": "第一次", "kind": "DIRECTORY"}
                """;
        rest.postForEntity("/api/entries", new HttpEntity<>(body, json()), Map.class);

        ResponseEntity<Map> response =
                rest.postForEntity("/api/entries", new HttpEntity<>(body, json()), Map.class);

        assertAll(
                () -> assertEquals(409, response.getStatusCode().value()),
                () -> assertEquals("Conflict", response.getBody().get("error"))
        );
    }

    @Test
    void createBlankDisplayNameReturns400() {
        ResponseEntity<Map> response = rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 71, "ownerId": 7, "parentId": null,
                 "displayName": "   ", "kind": "DIRECTORY"}
                """, json()), Map.class);

        assertAll(
                () -> assertEquals(400, response.getStatusCode().value()),
                () -> assertEquals("Bad Request", response.getBody().get("error"))
        );
    }

    @Test
    void createUnknownKindReturns400() {
        ResponseEntity<Map> response = rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 72, "ownerId": 7, "parentId": null,
                 "displayName": "未知类型", "kind": "SYMLINK"}
                """, json()), Map.class);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void createNonPositiveIdReturns400() {
        ResponseEntity<Map> response = rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 0, "ownerId": 7, "parentId": null,
                 "displayName": "非法 id", "kind": "DIRECTORY"}
                """, json()), Map.class);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void createMalformedJsonReturns400() {
        ResponseEntity<Map> response =
                rest.postForEntity("/api/entries", new HttpEntity<>("{ not json", json()), Map.class);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void renameToBlankReturns400AndKeepsOldName() {
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 81, "ownerId": 7, "parentId": null,
                 "displayName": "原名", "kind": "DOCUMENT", "byteSize": 5}
                """, json()), Map.class);

        ResponseEntity<Map> rejected = rest.exchange(
                "/api/entries/81/name", HttpMethod.PUT,
                new HttpEntity<>("{\"displayName\": \"\"}", json()), Map.class);
        ResponseEntity<Map> after = rest.getForEntity("/api/entries/81", Map.class);

        assertAll(
                () -> assertEquals(400, rejected.getStatusCode().value()),
                () -> assertEquals("原名", after.getBody().get("displayName"))
        );
    }

    @Test
    void renameMissingEntryReturns404() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/entries/9999/name", HttpMethod.PUT,
                new HttpEntity<>("{\"displayName\": \"新名\"}", json()), Map.class);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void renameValidEntryReturns204AndIsVisible() {
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 82, "ownerId": 7, "parentId": null,
                 "displayName": "改名前", "kind": "DOCUMENT", "byteSize": 9}
                """, json()), Map.class);

        ResponseEntity<Void> response = rest.exchange(
                "/api/entries/82/name", HttpMethod.PUT,
                new HttpEntity<>("{\"displayName\": \"改名后\"}", json()), Void.class);
        ResponseEntity<Map> after = rest.getForEntity("/api/entries/82", Map.class);

        assertAll(
                () -> assertEquals(204, response.getStatusCode().value()),
                () -> assertEquals("改名后", after.getBody().get("displayName"))
        );
    }

    @Test
    void findNonNumericIdReturns400() {
        ResponseEntity<Map> response = rest.getForEntity("/api/entries/abc", Map.class);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void snapshotWithSuccessfulSinkReturns200WithCount() {
        rest.postForEntity("/api/entries", new HttpEntity<>("""
                {"id": 91, "ownerId": 7, "parentId": null,
                 "displayName": "快照条目", "kind": "DIRECTORY"}
                """, json()), Map.class);

        ResponseEntity<Map> response = rest.postForEntity(
                URI.create("/api/snapshots"), new HttpEntity<>(json()), Map.class);

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value()),
                () -> assertNotNull(response.getBody().get("entryCount"))
        );
    }
}
