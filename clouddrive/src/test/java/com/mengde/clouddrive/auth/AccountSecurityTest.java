package com.mengde.clouddrive.auth;

import com.mengde.clouddrive.error.InvalidCredentialsException;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.error.RegistrationConflictException;
import com.mengde.clouddrive.application.QuotaGuard;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.web.GlobalExceptionHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.annotations.Mapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real database transactions and MyBatis statements; SMTP is captured at the delivery boundary. */
@SpringJUnitConfig(AccountSecurityTest.Config.class)
class AccountSecurityTest {
    @Autowired JdbcTemplate db;
    @Autowired UserAccountMapper users;
    @Autowired UserAccountService accounts;
    @Autowired AdminAccountService admins;
    @Autowired EmailVerificationService codes;
    @Autowired JwtTokenService tokens;
    @Autowired SmtpCodeMailer mailer;
    @Autowired PlatformTransactionManager transactions;
    private final Map<String, String> delivered = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        db.execute("DELETE FROM email_verification");
        db.execute("DELETE FROM user_account");
        delivered.clear();
        reset(mailer);
        doAnswer(call -> {
            delivered.put(call.getArgument(0) + ":" + call.getArgument(1), call.getArgument(2));
            return null;
        }).when(mailer).sendVerification(anyString(), any(), anyString());
    }

    @Test
    void registrationRequiresEmailProofAndCodeCanOnlyBeUsedOnce() {
        status(HttpStatus.BAD_REQUEST, () -> accounts.register("alice", "password123", "alice@example.com", "000000"));
        assertEquals(0L, users.count("%"));
        codes.send("ALICE@example.com", EmailVerificationService.Purpose.REGISTER);
        String code = delivered.get("alice@example.com:REGISTER");
        UserAccountRow alice = accounts.register("alice", "password123", "ALICE@example.com", code);
        assertEquals("alice@example.com", alice.getEmail());
        assertEquals("USER", alice.getRole());
        assertNotEquals("password123", alice.getPasswordHash());
        assertNotEquals(code, db.queryForObject("SELECT code_hash FROM email_verification", String.class));
        status(HttpStatus.BAD_REQUEST, () -> accounts.register("alice2", "password123", "alice@example.com", code));
        assertEquals(1L, users.count("%"));
    }

    @Test
    void wrongAttemptsCommitEvenWhenRegistrationIsRejectedAndLockAfterFiveFailures() {
        codes.send("alice@example.com", EmailVerificationService.Purpose.REGISTER);
        String valid = delivered.get("alice@example.com:REGISTER");
        String wrong = valid.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            status(HttpStatus.BAD_REQUEST, () -> accounts.register("alice", "password123", "alice@example.com", wrong));
        }
        assertEquals(5, db.queryForObject("SELECT attempts FROM email_verification", Integer.class));
        status(HttpStatus.BAD_REQUEST, () -> accounts.register("alice", "password123", "alice@example.com", valid));
        assertEquals(0L, users.count("%"));
    }

    @Test
    void usernameConflictReturns409WithoutConsumingAValidCodeAndRetryNeedsNoNewEmail() throws Exception {
        register("taken");
        String email = "fresh@example.com";
        codes.send(email, EmailVerificationService.Purpose.REGISTER);
        String code = delivered.get(email + ":REGISTER");
        String wrong = code.equals("000000") ? "000001" : "000000";
        status(HttpStatus.BAD_REQUEST, () -> accounts.register("fresh", "password123", email, wrong));

        authMvc().perform(post("/api/auth/register").contentType("application/json").content(
                        "{\"username\":\"taken\",\"password\":\"password123\",\"email\":\"" + email
                                + "\",\"code\":\"" + code + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("用户名或邮箱已注册"))
                .andExpect(jsonPath("$.cause").doesNotExist());

        assertFalse(db.queryForObject("SELECT consumed FROM email_verification WHERE email = ? AND purpose = 'REGISTER'",
                Boolean.class, email));
        assertEquals(1, db.queryForObject("SELECT attempts FROM email_verification WHERE email = ? AND purpose = 'REGISTER'",
                Integer.class, email));
        assertEquals("fresh", accounts.register("fresh", "password123", email, code).getUsername());
        verify(mailer, times(1)).sendVerification(eq(email), eq(EmailVerificationService.Purpose.REGISTER), anyString());
    }

    @Test
    void anEmailClaimedAfterCodeDeliveryStillUsesTheUniqueKeyAndRollsBackConsumption() {
        codes.send("fresh@example.com", EmailVerificationService.Purpose.REGISTER);
        String code = delivered.get("fresh@example.com:REGISTER");
        UserAccountRow conflicting = new UserAccountRow();
        conflicting.setUsername("claimed");
        conflicting.setEmail("fresh@example.com");
        conflicting.setRole("USER");
        conflicting.setPasswordHash(new BCryptPasswordEncoder(4).encode("password123"));
        users.insert(conflicting);

        RegistrationConflictException conflict = assertThrows(RegistrationConflictException.class,
                () -> accounts.register("fresh", "password123", "fresh@example.com", code));
        assertInstanceOf(org.springframework.dao.DuplicateKeyException.class, conflict.getCause());
        assertFalse(db.queryForObject("SELECT consumed FROM email_verification", Boolean.class));
        assertEquals(0, db.queryForObject("SELECT attempts FROM email_verification", Integer.class));
        db.update("DELETE FROM user_account WHERE id = ?", conflicting.getId());
        assertEquals("fresh@example.com", accounts.register("fresh", "password123", "fresh@example.com", code).getEmail());
    }

    @Test
    void concurrentUsernameClaimsLeaveTheLosingApplicantsCodeReusable() throws Exception {
        String first = "first@example.com";
        String second = "second@example.com";
        codes.send(first, EmailVerificationService.Purpose.REGISTER);
        codes.send(second, EmailVerificationService.Purpose.REGISTER);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstClaim = executor.submit(() -> { start.await(); return claimSharedUsername(first); });
            var secondClaim = executor.submit(() -> { start.await(); return claimSharedUsername(second); });
            start.countDown();
            boolean firstWon = firstClaim.get(10, TimeUnit.SECONDS);
            boolean secondWon = secondClaim.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            assertEquals(1L, users.count("%"));
            String losingEmail = firstWon ? second : first;
            assertFalse(db.queryForObject("SELECT consumed FROM email_verification WHERE email = ?",
                    Boolean.class, losingEmail));
            assertEquals(0, db.queryForObject("SELECT attempts FROM email_verification WHERE email = ?",
                    Integer.class, losingEmail));
            accounts.register("alternative", "password123", losingEmail, delivered.get(losingEmail + ":REGISTER"));
            assertEquals(2L, users.count("%"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void emailCodeHttpResponseAndCooldownDoNotRevealRegistrationOrResetEligibility() throws Exception {
        admins.bootstrap("known", "password123", "known@example.com");
        admins.bootstrap("disabled", "password123", "disabled@example.com");
        db.update("UPDATE user_account SET enabled = FALSE WHERE email = 'disabled@example.com'");
        MockMvc mvc = authMvc();
        String firstBody = null;
        String cooldownBody = null;
        for (var request : java.util.List.of(
                new String[]{"known@example.com", "REGISTER"},
                new String[]{"fresh@example.com", "REGISTER"},
                new String[]{"known@example.com", "RESET_PASSWORD"},
                new String[]{"missing@example.com", "RESET_PASSWORD"},
                new String[]{"disabled@example.com", "RESET_PASSWORD"})) {
            String json = "{\"email\":\"" + request[0] + "\",\"purpose\":\"" + request[1] + "\"}";
            String body = mvc.perform(post("/api/auth/email-code").contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(jsonPath("$.retryAfterSeconds").value(60))
                    .andExpect(jsonPath("$.message").value("若该邮箱符合条件，验证码已发送，请检查收件箱及垃圾邮件"))
                    .andReturn().getResponse().getContentAsString();
            String repeated = mvc.perform(post("/api/auth/email-code").contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                    .andReturn().getResponse().getContentAsString();
            if (firstBody == null) {
                firstBody = body;
                cooldownBody = repeated;
            } else {
                assertEquals(firstBody, body);
                assertEquals(cooldownBody, repeated);
            }
        }
        verify(mailer, times(2)).sendVerification(anyString(), any(), anyString());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM email_verification WHERE consumed = TRUE AND code_hash IS NULL",
                Integer.class));
        status(HttpStatus.BAD_REQUEST,
                () -> accounts.register("another", "password123", "known@example.com", "000000"));
    }

    @Test
    void sendingHasCooldownAndSmtpFailureRollsBackNewCode() {
        codes.send("alice@example.com", EmailVerificationService.Purpose.REGISTER);
        status(HttpStatus.TOO_MANY_REQUESTS, () -> codes.send("alice@example.com", EmailVerificationService.Purpose.REGISTER));
        String originalHash = db.queryForObject("SELECT code_hash FROM email_verification", String.class);
        // Codes use UTC wall-clock values; database CURRENT_TIMESTAMP follows the host/session zone.
        db.update("UPDATE email_verification SET sent_at = ?", LocalDateTime.now(Clock.systemUTC()).minusMinutes(2));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SMTP failed"))
                .when(mailer).sendVerification(anyString(), any(), anyString());
        status(HttpStatus.SERVICE_UNAVAILABLE, () -> codes.send("alice@example.com", EmailVerificationService.Purpose.REGISTER));
        assertEquals(originalHash, db.queryForObject("SELECT code_hash FROM email_verification", String.class));
    }

    @Test
    void codesExpireAndCannotBeUsedForAnotherPurpose() {
        codes.send("alice@example.com", EmailVerificationService.Purpose.REGISTER);
        String code = delivered.get("alice@example.com:REGISTER");
        status(HttpStatus.BAD_REQUEST, () -> accounts.resetPassword("alice@example.com", code, "newpassword123"));
        db.update("UPDATE email_verification SET expires_at = ?", LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        status(HttpStatus.BAD_REQUEST, () -> accounts.register("alice", "password123", "alice@example.com", code));
    }

    @Test
    void emailPasswordResetImmediatelyRevokesExistingTokens() {
        UserAccountRow alice = register("alice");
        String oldToken = accounts.login("alice", "password123").value();
        assertEquals(alice.getId(), tokens.verify(oldToken).id());
        codes.send(alice.getEmail(), EmailVerificationService.Purpose.RESET_PASSWORD);
        accounts.resetPassword(alice.getEmail(), delivered.get(alice.getEmail() + ":RESET_PASSWORD"), "newpassword123");
        assertThrows(JwtTokenService.InvalidTokenException.class, () -> tokens.verify(oldToken));
        assertThrows(InvalidCredentialsException.class, () -> accounts.login("alice", "password123"));
        assertEquals(alice.getId(), tokens.verify(accounts.login("alice", "newpassword123").value()).id());
        status(HttpStatus.BAD_REQUEST, () -> accounts.resetPassword(alice.getEmail(),
                delivered.get(alice.getEmail() + ":RESET_PASSWORD"), "anotherpassword"));
    }

    @Test
    void administratorsCanResetDisableAndAssignRolesButOrdinaryUsersCannot() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        UserAccountRow admin = users.findByUsername("admin").orElseThrow();
        UserAccountRow alice = register("alice");
        status(HttpStatus.FORBIDDEN, () -> admins.list(alice.getId(), 0, 20, ""));
        status(HttpStatus.FORBIDDEN, () -> admins.resetPassword(alice.getId(), admin.getId(), "newpassword123"));
        String token = accounts.login("alice", "password123").value();
        admins.resetPassword(admin.getId(), alice.getId(), "newpassword123");
        assertThrows(JwtTokenService.InvalidTokenException.class, () -> tokens.verify(token));
        final String resetToken = accounts.login("alice", "newpassword123").value();
        admins.setEnabled(admin.getId(), alice.getId(), false);
        assertThrows(JwtTokenService.InvalidTokenException.class, () -> tokens.verify(resetToken));
        assertThrows(InvalidCredentialsException.class, () -> accounts.login("alice", "newpassword123"));
        admins.setEnabled(admin.getId(), alice.getId(), true);
        String userToken = accounts.login("alice", "newpassword123").value();
        admins.setRole(admin.getId(), alice.getId(), "ADMIN");
        assertThrows(JwtTokenService.InvalidTokenException.class, () -> tokens.verify(userToken));
        assertTrue(tokens.verify(accounts.login("alice", "newpassword123").value()).roles().contains("ADMIN"));
        assertEquals(2L, admins.list(admin.getId(), 0, 20, "").total());
    }

    @Test
    void lastAdminAndSelfDisableAreProtectedAndBootstrapDoesNotElevateExistingUsers() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        UserAccountRow admin = users.findByUsername("admin").orElseThrow();
        status(HttpStatus.CONFLICT, () -> admins.setEnabled(admin.getId(), admin.getId(), false));
        status(HttpStatus.CONFLICT, () -> admins.setRole(admin.getId(), admin.getId(), "USER"));
        UserAccountRow alice = register("alice");
        assertThrows(IllegalStateException.class, () -> admins.bootstrap("alice", "adminpassword123", "another@example.com"));
        assertEquals("USER", users.findById(alice.getId()).orElseThrow().getRole());
        admins.bootstrap("admin", "ignoredpassword123", "admin@example.com");
        assertDoesNotThrow(() -> accounts.login("admin", "adminpassword123"));
    }

    @Test
    void tokenUsesCurrentDatabaseRoleAndLegacyAccountsWithoutEmailStillLogIn() {
        UserAccountRow legacy = new UserAccountRow();
        legacy.setUsername("legacy");
        legacy.setRole("USER");
        legacy.setPasswordHash(new BCryptPasswordEncoder(4).encode("password123"));
        users.insert(legacy);
        String token = accounts.login("legacy", "password123").value();
        db.update("UPDATE user_account SET role = 'ADMIN' WHERE id = ?", legacy.getId());
        assertEquals(java.util.List.of("ADMIN"), tokens.verify(token).roles());
        assertNull(users.findById(legacy.getId()).orElseThrow().getEmail());
    }

    @Test
    void missingSmtpConfigurationFailsExplicitly() {
        SmtpCodeMailer unconfigured = new SmtpCodeMailer("", 587, "", "", "", true, true, false);
        status(HttpStatus.SERVICE_UNAVAILABLE, unconfigured::requireConfigured);
        status(HttpStatus.SERVICE_UNAVAILABLE, () -> unconfigured.sendVerification("alice@example.com",
                EmailVerificationService.Purpose.REGISTER, "123456"));
    }

    @Test
    void smtpSupportsImplicitSslAndRejectsConflictingTransportModes() {
        assertDoesNotThrow(() -> new SmtpCodeMailer("smtp.example.com", 465, "user@example.com",
                "smtp-password", "user@example.com", true, false, true));
        assertThrows(IllegalArgumentException.class, () -> new SmtpCodeMailer("smtp.example.com", 465,
                "user@example.com", "smtp-password", "user@example.com", true, true, true));
    }

    @Test
    void quotaExpansionPersistsWithoutChangingIdentityPasswordOrExistingTokens() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        UserAccountRow admin = users.findByUsername("admin").orElseThrow();
        UserAccountRow alice = register("alice");
        String token = accounts.login("alice", "password123").value();
        long fiveGiB = 5L * 1024 * 1024 * 1024;

        var updated = admins.expandQuota(admin.getId(), alice.getId(), fiveGiB);

        UserAccountRow stored = users.findById(alice.getId()).orElseThrow();
        assertEquals(fiveGiB, updated.quotaBytes());
        assertEquals(fiveGiB, stored.getQuotaBytes());
        assertEquals(alice.getRole(), stored.getRole());
        assertEquals(alice.getEmail(), stored.getEmail());
        assertEquals(alice.getPasswordHash(), stored.getPasswordHash());
        assertEquals(alice.getTokenVersion(), stored.getTokenVersion());
        assertTrue(stored.isEnabled());
        assertEquals(alice.getId(), tokens.verify(token).id());
    }

    @Test
    void repeatingTheSameQuotaIsIdempotentAndShrinkingIsRejected() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        long targetId = register("alice").getId();
        long fiveGiB = 5L * 1024 * 1024 * 1024;
        admins.expandQuota(adminId, targetId, fiveGiB);
        assertEquals(fiveGiB, admins.expandQuota(adminId, targetId, fiveGiB).quotaBytes());
        status(HttpStatus.CONFLICT, () -> admins.expandQuota(adminId, targetId, fiveGiB - 1));
        assertEquals(fiveGiB, users.findById(targetId).orElseThrow().getQuotaBytes());
    }

    @Test
    void quotaExpansionRejectsNonPositiveOrExcessiveCapacityAndAcceptsTheCeiling() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        long targetId = register("alice").getId();
        for (long invalid : new long[]{-1, 0, AdminAccountService.MAX_QUOTA_BYTES + 1, Long.MAX_VALUE}) {
            status(HttpStatus.BAD_REQUEST, () -> admins.expandQuota(adminId, targetId, invalid));
        }
        assertEquals(1073741824L, users.findById(targetId).orElseThrow().getQuotaBytes());
        assertEquals(AdminAccountService.MAX_QUOTA_BYTES,
                admins.expandQuota(adminId, targetId, AdminAccountService.MAX_QUOTA_BYTES).quotaBytes());
    }

    @Test
    void ordinaryAndDisabledAdministratorsCannotExpandEvenTheirOwnAccounts() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        long aliceId = register("alice").getId();
        long fiveGiB = 5L * 1024 * 1024 * 1024;
        status(HttpStatus.FORBIDDEN, () -> admins.expandQuota(aliceId, aliceId, fiveGiB));
        status(HttpStatus.FORBIDDEN, () -> admins.expandQuota(aliceId, adminId, fiveGiB));
        db.update("UPDATE user_account SET enabled = FALSE WHERE id = ?", adminId);
        status(HttpStatus.FORBIDDEN, () -> admins.expandQuota(adminId, aliceId, fiveGiB));
        assertEquals(1073741824L, users.findById(aliceId).orElseThrow().getQuotaBytes());
    }

    @Test
    void expandingAMissingAccountReturns404() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        status(HttpStatus.NOT_FOUND, () -> admins.expandQuota(adminId, Long.MAX_VALUE, 5L * 1024 * 1024 * 1024));
    }

    @Test
    void administratorsCanExpandTheirOwnQuotaWithoutBeingLoggedOut() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        String token = accounts.login("admin", "adminpassword123").value();
        assertEquals(5L * 1024 * 1024 * 1024, admins.expandQuota(adminId, adminId, 5L * 1024 * 1024 * 1024).quotaBytes());
        assertEquals(adminId, tokens.verify(token).id());
    }

    @Test
    void concurrentExpansionsCannotOverwriteALargerQuotaWithASmallerOne() throws Exception {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        long targetId = register("alice").getId();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var small = executor.submit(() -> {
                start.await();
                try { admins.expandQuota(adminId, targetId, 5L * 1024 * 1024 * 1024); return HttpStatus.OK; }
                catch (ResponseStatusException error) {
                    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
                    return HttpStatus.CONFLICT;
                }
            });
            var large = executor.submit(() -> {
                start.await();
                return admins.expandQuota(adminId, targetId, 10L * 1024 * 1024 * 1024);
            });
            start.countDown();
            assertEquals(10L * 1024 * 1024 * 1024, large.get(10, TimeUnit.SECONDS).quotaBytes());
            assertTrue(java.util.Set.of(HttpStatus.OK, HttpStatus.CONFLICT).contains(small.get(10, TimeUnit.SECONDS)));
            assertEquals(10L * 1024 * 1024 * 1024, users.findById(targetId).orElseThrow().getQuotaBytes());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingQuotaGuardReadsTheExpandedCapacityWithoutChangingUsedSpace() {
        admins.bootstrap("admin", "adminpassword123", "admin@example.com");
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        long targetId = register("alice").getId();
        long giB = 1024L * 1024 * 1024;
        DriveEntryRepository entries = mock(DriveEntryRepository.class);
        when(entries.usedBytesByOwner(targetId)).thenReturn(giB);
        QuotaGuard guard = new QuotaGuard(users, entries);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThrows(QuotaExceededException.class, () -> tx.executeWithoutResult(ignored -> guard.require(targetId, 1)));
        admins.expandQuota(adminId, targetId, 5 * giB);
        assertDoesNotThrow(() -> tx.executeWithoutResult(ignored -> guard.require(targetId, 4 * giB)));
        assertThrows(QuotaExceededException.class, () -> tx.executeWithoutResult(ignored -> guard.require(targetId, 4 * giB + 1)));
        assertEquals(giB, entries.usedBytesByOwner(targetId));
    }

    private UserAccountRow register(String username) {
        String email = username + "@example.com";
        codes.send(email, EmailVerificationService.Purpose.REGISTER);
        return accounts.register(username, "password123", email, delivered.get(email + ":REGISTER"));
    }

    private boolean claimSharedUsername(String email) {
        try {
            accounts.register("shared", "password123", email, delivered.get(email + ":REGISTER"));
            return true;
        } catch (RegistrationConflictException conflict) {
            assertInstanceOf(org.springframework.dao.DuplicateKeyException.class, conflict.getCause());
            return false;
        }
    }

    private MockMvc authMvc() {
        AuthController controller = new AuthController(accounts, mock(DriveEntryRepository.class), codes,
                mock(EmailRequestRateLimiter.class));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static void status(HttpStatus expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = UserAccountMapper.class, annotationClass = Mapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:account_security;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE user_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL, "
                    + "email VARCHAR(254) UNIQUE, password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL, "
                    + "enabled BOOLEAN DEFAULT TRUE NOT NULL, token_version BIGINT DEFAULT 0 NOT NULL, "
                    + "quota_bytes BIGINT DEFAULT 1073741824 NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
            jdbc.execute("CREATE TABLE email_verification (email VARCHAR(254), purpose VARCHAR(24), code_hash VARCHAR(64), "
                    + "sent_at TIMESTAMP(6), expires_at TIMESTAMP(6), attempts INT DEFAULT 0 NOT NULL, "
                    + "consumed BOOLEAN DEFAULT TRUE NOT NULL, PRIMARY KEY(email, purpose))");
            jdbc.execute("CREATE TABLE account_security_guard (id INT PRIMARY KEY)");
            jdbc.execute("INSERT INTO account_security_guard VALUES (1)");
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean JwtProperties jwtProperties() {
            JwtProperties properties = new JwtProperties();
            properties.setSecret("test-account-security-secret-at-least-32-bytes");
            return properties;
        }
        @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(4); }
        @Bean SmtpCodeMailer mailer() { return mock(SmtpCodeMailer.class); }
        @Bean JwtTokenService tokens(JwtProperties jwt, UserAccountMapper users) { return new JwtTokenService(jwt, users); }
        @Bean EmailVerificationService codes(EmailVerificationMapper codes, UserAccountMapper users,
                                              SmtpCodeMailer mailer, JwtProperties jwt) {
            return new EmailVerificationService(codes, users, mailer, jwt);
        }
        @Bean UserAccountService accounts(UserAccountMapper users, PasswordEncoder passwords,
                                           JwtTokenService tokens, EmailVerificationService codes) {
            return new UserAccountService(users, passwords, tokens, codes);
        }
        @Bean AdminAccountService admins(UserAccountMapper users, PasswordEncoder passwords) {
            return new AdminAccountService(users, passwords);
        }
    }
}
