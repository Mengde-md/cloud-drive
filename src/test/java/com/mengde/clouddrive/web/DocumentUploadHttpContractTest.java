package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.AuthController;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = DriveEntryWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}", "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}", "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}", "clouddrive.storage.root=target/day17-upload-test-storage"})
class DocumentUploadHttpContractTest {
    private static final Path ROOT = Path.of("target/day17-upload-test-storage");
    @Autowired TestRestTemplate rest; @Autowired DriveEntryRepository entries; @Autowired UserAccountMapper users;

    @BeforeEach @AfterEach
    void cleanFixtures() throws IOException {
        entries.deleteAll(); users.deleteAll();
        if (Files.exists(ROOT)) try (var paths = Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }

    @Test
    void multipartUploadComputesMd5AndSameOwnerCanInstantReuse() {
        Session alice = session("alice" + System.nanoTime());
        ResponseEntity<DocumentUploadController.UploadResponse> uploaded = upload(alice, "notes.txt", "hello-day17".getBytes());
        assertEquals(HttpStatus.CREATED, uploaded.getStatusCode());
        assertFalse(uploaded.getBody().instant()); assertEquals("c6973c299874592a250a2c5fc288f3b0", uploaded.getBody().md5());

        DocumentUploadController.InstantUploadRequest instant = new DocumentUploadController.InstantUploadRequest(null, "copy.txt", uploaded.getBody().md5());
        ResponseEntity<DocumentUploadController.UploadResponse> copied = rest.postForEntity("/api/files/instant", new HttpEntity<>(instant, alice.headers()), DocumentUploadController.UploadResponse.class);
        assertAll(() -> assertEquals(HttpStatus.CREATED, copied.getStatusCode()), () -> assertTrue(copied.getBody().instant()), () -> assertEquals(uploaded.getBody().md5(), copied.getBody().md5()), () -> assertEquals(uploaded.getBody().byteSize(), copied.getBody().byteSize()));
    }

    @Test
    void anotherOwnerCannotUseKnownMd5AsInstantUploadCandidate() {
        Session alice = session("alice" + System.nanoTime());
        String md5 = upload(alice, "private.txt", "same bytes".getBytes()).getBody().md5();
        Session bob = session("bob" + System.nanoTime());
        ResponseEntity<ApiError> response = rest.postForEntity("/api/files/instant", new HttpEntity<>(new DocumentUploadController.InstantUploadRequest(null, "stolen.txt", md5), bob.headers()), ApiError.class);
        assertAll(() -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()), () -> assertEquals(404, response.getBody().status()));
    }

    @Test
    void duplicateMetadataCompensatesNewlyWrittenLocalObject() throws IOException {
        Session alice = session("alice" + System.nanoTime());
        assertEquals(HttpStatus.CREATED, upload(alice, "only-one.txt", "first".getBytes()).getStatusCode());
        ResponseEntity<ApiError> duplicate = upload(alice, "only-one.txt", "second".getBytes(), ApiError.class);
        long objectCount;
        try (var files = Files.list(ROOT)) { objectCount = files.count(); }
        assertAll(() -> assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode()), () -> assertEquals(1L, objectCount));
    }

    private Session session(String username) {
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), AuthController.UserResponse.class);
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity("/api/auth/login", new AuthController.Credentials(username, "correct-password"), AuthController.TokenResponse.class);
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(login.getBody().accessToken()); headers.setContentType(MediaType.APPLICATION_JSON);
        return new Session(registered.getBody().id(), headers);
    }
    private ResponseEntity<DocumentUploadController.UploadResponse> upload(Session user, String displayName, byte[] content) { return upload(user, displayName, content, DocumentUploadController.UploadResponse.class); }
    private <T> ResponseEntity<T> upload(Session user, String displayName, byte[] content, Class<T> response) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>(); body.add("displayName", displayName);
        body.add("file", new ByteArrayResource(content) { @Override public String getFilename() { return displayName; } });
        HttpHeaders headers = new HttpHeaders(); headers.putAll(user.headers()); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/files/upload", new HttpEntity<>(body, headers), response);
    }
    private record Session(long id, HttpHeaders headers) { }
}
