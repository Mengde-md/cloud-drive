package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.upload.UploadSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}"
        })
class PrivateEntryCacheHttpContractTest {
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DriveEntryRepository entries;
    @Autowired
    UserAccountMapper users;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    FileShareMapper shares;
    @Autowired
    UploadSessionRepository sessions;

    private long ownerId;

    @BeforeEach
    @AfterEach
    void clean() {
        if (ownerId > 0) {
            redis.delete(listKey(ownerId));
        }
        shares.deleteAll();
        sessions.deleteAll();
        entries.deleteAll();
        users.deleteAll();
    }

    @Test
    void ownerListIsCachedAndRenameInvalidatesPrivateMetadata() {
        Session alice = session("alice" + System.nanoTime());
        ownerId = alice.id();
        EntryResponse created = rest.postForEntity("/api/entries",
                new HttpEntity<>(new EntryCreateRequest(null, "draft.txt", "DOCUMENT", 1L), alice.headers()),
                EntryResponse.class).getBody();

        ResponseEntity<EntryResponse[]> first = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), EntryResponse[].class);
        String key = listKey(ownerId);
        String firstJson = redis.opsForValue().get(key);
        ResponseEntity<Void> renamed = rest.exchange("/api/entries/{id}/name", HttpMethod.PUT,
                new HttpEntity<>(new RenameRequest("final.txt"), alice.headers()), Void.class, created.id());
        ResponseEntity<EntryResponse[]> second = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), EntryResponse[].class);

        assertAll(
                () -> assertEquals(HttpStatus.OK, first.getStatusCode()),
                () -> assertNotNull(firstJson),
                () -> assertEquals(HttpStatus.NO_CONTENT, renamed.getStatusCode()),
                () -> assertEquals("final.txt", second.getBody()[0].displayName()),
                () -> assertTrue(redis.opsForValue().get(key).contains("final.txt")));
    }

    @Test
    void differentOwnersNeverReadEachOthersListKey() {
        Session alice = session("alice" + System.nanoTime());
        ownerId = alice.id();
        Session bob = session("bob" + System.nanoTime());
        rest.postForEntity("/api/entries",
                new HttpEntity<>(new EntryCreateRequest(null, "alice.txt", "DOCUMENT", 1L), alice.headers()),
                EntryResponse.class);

        ResponseEntity<EntryResponse[]> bobs = rest.exchange("/api/entries", HttpMethod.GET,
                new HttpEntity<>(bob.headers()), EntryResponse[].class);

        assertAll(
                () -> assertEquals(0, bobs.getBody().length),
                () -> assertEquals("[]", redis.opsForValue().get(listKey(bob.id()))),
                () -> assertNotEquals(listKey(alice.id()), listKey(bob.id())));
    }

    private Session session(String name) {
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity("/api/auth/register",
                new AuthController.Credentials(name, "correct-password"), AuthController.UserResponse.class);
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity("/api/auth/login",
                new AuthController.Credentials(name, "correct-password"), AuthController.TokenResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new Session(registered.getBody().id(), headers);
    }

    private static String listKey(long owner) {
        return "clouddrive:day20:owner:" + owner + ":entries";
    }

    private record Session(long id, HttpHeaders headers) {
    }
}
