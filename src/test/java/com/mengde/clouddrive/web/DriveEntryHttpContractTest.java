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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day16 HTTP contract: the caller never supplies ownerId or entry id. The
 * token supplies ownership and MySQL supplies the generated id.
 */
@SpringBootTest(
        classes = DriveEntryWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}",
                "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME}",
                "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD}"
        })
class DriveEntryHttpContractTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private DriveEntryRepository entries;
    @Autowired private UserAccountMapper users;
    @Autowired private FileShareMapper shares;
    @Autowired private UploadSessionRepository sessions;

    // FK-safe, self-healing cleanup: shares -> sessions -> entries -> users,
    // both before and after each test (real persistent MySQL DB shared across
    // the whole mvn test run and across separate runs).
    @BeforeEach
    @AfterEach
    void clearPersistentFixtures() {
        shares.deleteAll();
        sessions.deleteAll();
        entries.deleteAll();
        users.deleteAll();
    }

    @Test
    void anonymousEntryRequestGets401BeforeController() {
        assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity("/api/entries", ApiError.class).getStatusCode());
    }

    @Test
    void tokenIdentityOwnsGeneratedEntryAndOwnerCannotBeSpoofedInJson() {
        Session alice = session("alice" + System.nanoTime());
        ResponseEntity<Map> created = post(alice, """
                {"displayName":"我的根目录","kind":"DIRECTORY"}
                """);

        assertAll(
                () -> assertEquals(HttpStatus.CREATED, created.getStatusCode()),
                () -> assertNotNull(created.getBody()),
                () -> assertTrue(((Number) created.getBody().get("id")).longValue() > 0),
                () -> assertEquals(alice.id(), ((Number) created.getBody().get("ownerId")).longValue()),
                () -> assertFalse(created.getBody().containsKey("requestedOwnerId"))
        );
    }

    @Test
    void listAndDetailAreOwnerScopedAndDoNotRevealAnotherUsersEntry() {
        Session alice = session("alice" + System.nanoTime());
        Session bob = session("bob" + System.nanoTime());
        long id = entryId(post(alice, """
                {"displayName":"Alice only","kind":"DOCUMENT","byteSize":12}
                """));

        ResponseEntity<List> bobList = rest.exchange("/api/entries", HttpMethod.GET, new HttpEntity<>(bob.headers()), List.class);
        ResponseEntity<ApiError> bobDetail = rest.exchange("/api/entries/" + id, HttpMethod.GET, new HttpEntity<>(bob.headers()), ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.OK, bobList.getStatusCode()),
                () -> assertTrue(bobList.getBody().isEmpty()),
                () -> assertEquals(HttpStatus.NOT_FOUND, bobDetail.getStatusCode()),
                () -> assertEquals(404, bobDetail.getBody().status())
        );
    }

    @Test
    void parentMustBeCallersDirectoryAndCannotCrossOwnerBoundary() {
        Session alice = session("alice" + System.nanoTime());
        Session bob = session("bob" + System.nanoTime());
        long aliceDirectory = entryId(post(alice, """
                {"displayName":"Alice folder","kind":"DIRECTORY"}
                """));

        ResponseEntity<ApiError> response = post(bob, """
                {"parentId":%d,"displayName":"illegal child","kind":"DOCUMENT","byteSize":3}
                """.formatted(aliceDirectory), ApiError.class);

        assertAll(
                () -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()),
                () -> assertEquals(404, response.getBody().status()),
                () -> assertTrue(rest.exchange("/api/entries", HttpMethod.GET, new HttpEntity<>(bob.headers()), List.class).getBody().isEmpty())
        );
    }

    @Test
    void databaseRejectsSameNameInSameDirectoryButAllowsSameNameInDifferentDirectories() {
        Session alice = session("alice" + System.nanoTime());
        long firstDirectory = entryId(post(alice, "{\"displayName\":\"one\",\"kind\":\"DIRECTORY\"}"));
        long secondDirectory = entryId(post(alice, "{\"displayName\":\"two\",\"kind\":\"DIRECTORY\"}"));
        assertEquals(HttpStatus.CREATED, post(alice, "{\"parentId\":%d,\"displayName\":\"readme\",\"kind\":\"DOCUMENT\",\"byteSize\":1}".formatted(firstDirectory)).getStatusCode());

        ResponseEntity<ApiError> duplicate = post(alice, "{\"parentId\":%d,\"displayName\":\"readme\",\"kind\":\"DOCUMENT\",\"byteSize\":1}".formatted(firstDirectory), ApiError.class);
        ResponseEntity<Map> sibling = post(alice, "{\"parentId\":%d,\"displayName\":\"readme\",\"kind\":\"DOCUMENT\",\"byteSize\":1}".formatted(secondDirectory));

        assertAll(
                () -> assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode()),
                () -> assertEquals(409, duplicate.getBody().status()),
                () -> assertEquals(HttpStatus.CREATED, sibling.getStatusCode())
        );
    }

    @Test
    void quotaAndNonEmptyDirectoryFailuresLeaveConsistentMetadata() {
        Session alice = session("alice" + System.nanoTime());
        ResponseEntity<ApiError> tooLarge = post(alice, """
                {"displayName":"too-large","kind":"DOCUMENT","byteSize":1073741825}
                """, ApiError.class);
        long directory = entryId(post(alice, "{\"displayName\":\"parent\",\"kind\":\"DIRECTORY\"}"));
        long child = entryId(post(alice, "{\"parentId\":%d,\"displayName\":\"child\",\"kind\":\"DOCUMENT\",\"byteSize\":1}".formatted(directory)));

        ResponseEntity<ApiError> blockedDelete = rest.exchange("/api/entries/" + directory, HttpMethod.DELETE, new HttpEntity<>(alice.headers()), ApiError.class);
        ResponseEntity<Void> deleteChild = rest.exchange("/api/entries/" + child, HttpMethod.DELETE, new HttpEntity<>(alice.headers()), Void.class);
        ResponseEntity<Void> deleteDirectory = rest.exchange("/api/entries/" + directory, HttpMethod.DELETE, new HttpEntity<>(alice.headers()), Void.class);

        assertAll(
                () -> assertEquals(HttpStatus.CONFLICT, tooLarge.getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, blockedDelete.getStatusCode()),
                () -> assertEquals(HttpStatus.NO_CONTENT, deleteChild.getStatusCode()),
                () -> assertEquals(HttpStatus.NO_CONTENT, deleteDirectory.getStatusCode())
        );
    }

    @Test
    void renameAndDeleteAreAlsoOwnerScoped() {
        Session alice = session("alice" + System.nanoTime());
        Session bob = session("bob" + System.nanoTime());
        long id = entryId(post(alice, "{\"displayName\":\"before\",\"kind\":\"DIRECTORY\"}"));

        ResponseEntity<ApiError> rename = rest.exchange("/api/entries/" + id + "/name", HttpMethod.PUT, new HttpEntity<>("{\"displayName\":\"stolen\"}", bob.headers()), ApiError.class);
        ResponseEntity<ApiError> delete = rest.exchange("/api/entries/" + id, HttpMethod.DELETE, new HttpEntity<>(bob.headers()), ApiError.class);
        ResponseEntity<Map> ownerRead = rest.exchange("/api/entries/" + id, HttpMethod.GET, new HttpEntity<>(alice.headers()), Map.class);

        assertAll(
                () -> assertEquals(HttpStatus.NOT_FOUND, rename.getStatusCode()),
                () -> assertEquals(HttpStatus.NOT_FOUND, delete.getStatusCode()),
                () -> assertEquals("before", ownerRead.getBody().get("displayName"))
        );
    }

    private Session session(String username) {
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), AuthController.UserResponse.class);
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity("/api/auth/login", new AuthController.Credentials(username, "correct-password"), AuthController.TokenResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(login.getBody().accessToken());
        return new Session(registered.getBody().id(), headers);
    }

    private ResponseEntity<Map> post(Session session, String body) {
        return post(session, body, Map.class);
    }

    private <T> ResponseEntity<T> post(Session session, String body, Class<T> responseType) {
        return rest.postForEntity("/api/entries", new HttpEntity<>(body, session.headers()), responseType);
    }

    private long entryId(ResponseEntity<Map> response) {
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return ((Number) response.getBody().get("id")).longValue();
    }

    private record Session(long id, HttpHeaders headers) { }
}
