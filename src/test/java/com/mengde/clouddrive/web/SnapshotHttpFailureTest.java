package com.mengde.clouddrive.web;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day06 5xx 契约测试：用 @TestConfiguration 把 SnapshotSink 换成
 * 必定抛 IOException 的失败实现，验证快照写入失败被映射为真实的 500 响应
 * 与统一错误体，而不是 200 或连接中断。
 */
@SpringBootTest(
        classes = {DriveEntryWebApplication.class, SnapshotHttpFailureTest.FailingSinkConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}"
        })
class SnapshotHttpFailureTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DriveEntryRepository repository;

    @BeforeEach
    void clearDatabaseBeforeEveryHttpContract() {
        repository.deleteAll();
    }

    @TestConfiguration
    static class FailingSinkConfiguration {

        @Bean
        @Primary
        SnapshotSink failingSnapshotSink() {
            return entries -> {
                throw new IOException("simulated snapshot failure");
            };
        }
    }

    @Test
    void snapshotFailureReturns500WithErrorBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response =
                rest.postForEntity("/api/snapshots", new HttpEntity<>(headers), Map.class);

        assertAll(
                () -> assertEquals(500, response.getStatusCode().value()),
                () -> assertEquals(500, ((Number) response.getBody().get("status")).intValue()),
                () -> assertEquals("Internal Server Error", response.getBody().get("error")),
                () -> assertTrue(String.valueOf(response.getBody().get("message"))
                        .contains("simulated snapshot failure"))
        );
    }
}
