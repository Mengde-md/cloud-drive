package com.mengde.clouddrive.auth;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.FileShareMapper;
import com.mengde.clouddrive.upload.UploadSessionRepository;
import com.mengde.clouddrive.web.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = com.mengde.clouddrive.web.DriveEntryWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=${CLOUDDRIVE_DB_URL:jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}", "spring.datasource.username=${CLOUDDRIVE_DB_USERNAME:root}", "spring.datasource.password=${CLOUDDRIVE_DB_PASSWORD:123456}"})
class AuthHttpContractTest {
    @Autowired TestRestTemplate rest;
    @Autowired UserAccountMapper users;
    @Autowired AuthService service;
    @Autowired FileShareMapper shares;
    @Autowired UploadSessionRepository sessions;
    @Autowired DriveEntryRepository entries;
    // FK-safe, self-healing cleanup: this class only creates users, but a
    // stray file_shares/upload_sessions row left by another class (or an
    // earlier mvn test run against this same persistent MySQL database) would
    // otherwise make a bare users.deleteAll() fail with a FK violation.
    @BeforeEach @AfterEach void clean() { shares.deleteAll(); sessions.deleteAll(); entries.deleteAll(); users.deleteAll(); }
    @Test void registerLoginAndCurrentUserFormOneAuthenticationChain() {
        String username = "user" + System.nanoTime();
        ResponseEntity<AuthController.UserResponse> registered = rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), AuthController.UserResponse.class);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode()); assertNotNull(registered.getBody()); assertEquals(username, registered.getBody().username());
        ResponseEntity<AuthController.TokenResponse> login = rest.postForEntity("/api/auth/login", new AuthController.Credentials(username, "correct-password"), AuthController.TokenResponse.class);
        assertEquals(HttpStatus.OK, login.getStatusCode()); assertNotNull(login.getBody()); assertFalse(login.getBody().accessToken().isBlank());
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(login.getBody().accessToken());
        ResponseEntity<AuthController.CurrentUserResponse> me = rest.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), AuthController.CurrentUserResponse.class);
        assertEquals(HttpStatus.OK, me.getStatusCode()); assertEquals(username, me.getBody().username()); assertEquals(java.util.List.of("USER"), me.getBody().roles());
    }
    @Test void missingOrTamperedTokenGetsUniform401InsteadOfEnteringController() {
        ResponseEntity<ApiError> missing = rest.getForEntity("/api/auth/me", ApiError.class);
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode()); assertNotNull(missing.getBody()); assertEquals(401, missing.getBody().status());
        HttpHeaders headers = new HttpHeaders(); headers.set("Authorization", "Bearer not.a.valid.token");
        ResponseEntity<ApiError> tampered = rest.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), ApiError.class);
        assertEquals(HttpStatus.UNAUTHORIZED, tampered.getStatusCode()); assertEquals(401, tampered.getBody().status());
    }
    @Test void duplicateUsernameAndWrongPasswordDoNotIssueToken() {
        String username = "dupe" + System.nanoTime();
        assertEquals(HttpStatus.CREATED, rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), AuthController.UserResponse.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, rest.postForEntity("/api/auth/register", new AuthController.Credentials(username, "correct-password"), ApiError.class).getStatusCode());
        assertThrows(InvalidCredentialsException.class, () -> service.login(username, "wrong-password"));
    }
}
