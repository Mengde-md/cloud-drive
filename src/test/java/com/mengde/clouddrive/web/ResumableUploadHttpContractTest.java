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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}",
                "clouddrive.storage.root=target/day18-object-test-storage",
                "clouddrive.chunk-storage.root=target/day18-chunk-test-storage"
        })
class ResumableUploadHttpContractTest {
    private static final Path CHUNK_ROOT = Path.of("target/day18-chunk-test-storage");
    private static final Path OBJECT_ROOT = Path.of("target/day18-object-test-storage");

    @Autowired TestRestTemplate rest;
    @Autowired UploadSessionRepository sessions;
    @Autowired DriveEntryRepository entries;
    @Autowired UserAccountMapper users;
    @Autowired FileShareMapper shares;

    @BeforeEach @AfterEach
    void cleanFixtures() throws IOException {
        shares.deleteAll();
        sessions.deleteAll();
        entries.deleteAll();
        users.deleteAll();
        deleteTree(CHUNK_ROOT);
        deleteTree(OBJECT_ROOT);
    }

    @Test
    void missingChunkCannotCompleteAndOutOfOrderChunkIsRejected() {
        Session alice = session("alice" + System.nanoTime());
        String id = start(alice, "movie.bin", 2).id();

        ResponseEntity<ApiError> missing = complete(alice, id, ApiError.class);
        ResponseEntity<ApiError> wrongOrder = putChunk(alice, id, 1, "world".getBytes(), ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.CONFLICT, missing.getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, wrongOrder.getStatusCode()),
                () -> assertFalse(Files.exists(CHUNK_ROOT.resolve(id).resolve("1.part"))));
    }

    @Test
    void orderedChunksMergeOnceAndRepeatedChunkUsesSimpleIdempotency() throws IOException {
        Session alice = session("alice" + System.nanoTime());
        String id = start(alice, "hello.txt", 2).id();

        ResponseEntity<UploadSessionController.ChunkResponse> first = putChunk(alice, id, 0, "hello ".getBytes(), UploadSessionController.ChunkResponse.class);
        ResponseEntity<UploadSessionController.ChunkResponse> repeated = putChunk(alice, id, 0, "ignored".getBytes(), UploadSessionController.ChunkResponse.class);
        ResponseEntity<UploadSessionController.ChunkResponse> second = putChunk(alice, id, 1, "world".getBytes(), UploadSessionController.ChunkResponse.class);
        ResponseEntity<DocumentUploadController.UploadResponse> completed = complete(alice, id, DocumentUploadController.UploadResponse.class);

        assertAll(
                () -> assertEquals(HttpStatus.CREATED, first.getStatusCode()),
                () -> assertFalse(first.getBody().alreadyReceived()),
                () -> assertEquals(HttpStatus.OK, repeated.getStatusCode()),
                () -> assertTrue(repeated.getBody().alreadyReceived()),
                () -> assertEquals(HttpStatus.CREATED, second.getStatusCode()),
                () -> assertEquals(HttpStatus.CREATED, completed.getStatusCode()),
                () -> assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", completed.getBody().md5()),
                () -> assertFalse(Files.exists(CHUNK_ROOT.resolve(id))));
    }

    @Test
    void anotherOwnerCannotAppendOrCompleteKnownSession() {
        Session alice = session("alice" + System.nanoTime());
        String id = start(alice, "private.bin", 1).id();
        Session bob = session("bob" + System.nanoTime());

        ResponseEntity<ApiError> append = putChunk(bob, id, 0, "secret".getBytes(), ApiError.class);
        ResponseEntity<ApiError> complete = complete(bob, id, ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.NOT_FOUND, append.getStatusCode()),
                () -> assertEquals(HttpStatus.NOT_FOUND, complete.getStatusCode()));
    }

    private UploadSessionController.SessionResponse start(Session session, String name, int totalChunks) {
        return rest.postForEntity("/api/upload-sessions",
                new HttpEntity<>(new UploadSessionController.StartUploadRequest(null, name, totalChunks), session.headers()),
                UploadSessionController.SessionResponse.class).getBody();
    }

    private <T> ResponseEntity<T> putChunk(Session session, String id, int index, byte[] content, Class<T> responseType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return "part-" + index + ".bin"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(session.headers());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/upload-sessions/{id}/chunks/{index}", new HttpEntity<>(body, headers), responseType, id, index);
    }

    private <T> ResponseEntity<T> complete(Session session, String id, Class<T> responseType) {
        return rest.postForEntity("/api/upload-sessions/{id}/complete", new HttpEntity<>(null, session.headers()), responseType, id);
    }

    private Session session(String username) {
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), AuthController.UserResponse.class);
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity("/api/auth/login", new AuthController.Credentials(username, "correct-password"), AuthController.TokenResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new Session(registered.getBody().id(), headers);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            });
        }
    }

    private record Session(long id, HttpHeaders headers) { }
}
