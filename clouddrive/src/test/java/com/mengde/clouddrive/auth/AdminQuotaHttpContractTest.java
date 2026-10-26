package com.mengde.clouddrive.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengde.clouddrive.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC validation, JWT filter and URL authorization; no production database is accessed. */
@SpringJUnitWebConfig(AdminQuotaHttpContractTest.Config.class)
class AdminQuotaHttpContractTest {
    private static final long FIVE_GIB = 5L * 1024 * 1024 * 1024;
    @Autowired WebApplicationContext context;
    @Autowired AdminAccountService accounts;
    @Autowired JwtTokenService tokens;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(accounts, tokens);
        when(tokens.verify("admin-token")).thenReturn(new AuthenticatedUser(7, "admin", List.of("ADMIN")));
        when(tokens.verify("user-token")).thenReturn(new AuthenticatedUser(8, "alice", List.of("USER")));
        when(tokens.verify("invalid-token")).thenThrow(new JwtTokenService.InvalidTokenException(new IllegalArgumentException("invalid")));
        when(accounts.expandQuota(7, 42, FIVE_GIB)).thenReturn(new AdminAccountService.ManagedUser(
                42, "target", "target@example.test", "USER", true, FIVE_GIB, LocalDateTime.of(2026, 10, 16, 12, 0)));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void administratorUsesTrustedPrincipalAndResponseContainsTheAbsoluteCapacity() throws Exception {
        mvc.perform(patch("/api/admin/users/42/quota").header("Authorization", "Bearer admin-token")
                        .param("actorId", "99").contentType("application/json").content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.quotaBytes").value(FIVE_GIB))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.tokenVersion").doesNotExist());
        verify(accounts).expandQuota(7, 42, FIVE_GIB);
    }

    @Test
    void ordinaryUserCannotBypassTheAdminUrlGuard() throws Exception {
        mvc.perform(patch("/api/admin/users/8/quota").header("Authorization", "Bearer user-token")
                        .contentType("application/json").content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(accounts);
    }

    @Test
    void anonymousAndInvalidTokensCannotExpandQuota() throws Exception {
        mvc.perform(patch("/api/admin/users/42/quota").contentType("application/json")
                        .content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/admin/users/42/quota").header("Authorization", "Bearer invalid-token")
                        .contentType("application/json").content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts);
    }

    @Test
    void missingNullNonPositiveExcessiveAndMalformedCapacitiesReturn400() throws Exception {
        for (String body : List.of("{}", "{\"quotaBytes\":null}", "{\"quotaBytes\":0}", "{\"quotaBytes\":-1}",
                "{\"quotaBytes\":" + (AdminAccountService.MAX_QUOTA_BYTES + 1) + "}",
                "{\"quotaBytes\":\"not-a-number\"}", "{\"quotaBytes\":9223372036854775808}")) {
            mvc.perform(patch("/api/admin/users/42/quota").header("Authorization", "Bearer admin-token")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void missingAccountsAndShrinkConflictsKeepTheStandardErrorShape() throws Exception {
        when(accounts.expandQuota(7, 404, FIVE_GIB)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        when(accounts.expandQuota(7, 43, FIVE_GIB)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "仅支持扩容"));
        mvc.perform(patch("/api/admin/users/404/quota").header("Authorization", "Bearer admin-token")
                        .contentType("application/json").content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("账号不存在"));
        mvc.perform(patch("/api/admin/users/43/quota").header("Authorization", "Bearer admin-token")
                        .contentType("application/json").content("{\"quotaBytes\":" + FIVE_GIB + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean JwtTokenService tokens() { return mock(JwtTokenService.class); }
        @Bean JwtAuthenticationFilter jwtFilter(JwtTokenService tokens) { return new JwtAuthenticationFilter(tokens); }
        @Bean RestAuthenticationFailureHandler failures(ObjectMapper json) { return new RestAuthenticationFailureHandler(json); }
        @Bean AdminAccountService accounts() { return mock(AdminAccountService.class); }
        @Bean AdminController controller(AdminAccountService accounts) { return new AdminController(accounts); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
    }
}
