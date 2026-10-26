package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.error.EntryNotFoundException;
import com.mengde.clouddrive.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileAnalysisHttpContractTest {
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void metadataAndReanalysisUsePrincipalOwnerAndPreserveResponseContract() throws Exception {
        identity("USER");
        FileAnalysisService service = mock(FileAnalysisService.class);
        FileAnalysisResponse result = response();
        when(service.list(7, List.of(31L, 32L))).thenReturn(List.of(result));
        when(service.find(7, 31)).thenReturn(result);
        when(service.schedule(7, 31)).thenReturn(result);
        MockMvc mvc = mvc(new FileAnalysisController(service));
        mvc.perform(get("/api/analysis").param("entryIds", "31,32").param("ownerId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].entryId").value(31))
                .andExpect(jsonPath("$[0].reviewStatus").value("UNREVIEWED"));
        mvc.perform(get("/api/analysis/31")).andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("DOCUMENT"));
        mvc.perform(post("/api/analysis/31/reanalyze").param("ownerId", "99"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.entryId").value(31));
        verify(service).list(7, List.of(31L, 32L));
        verify(service).schedule(7, 31);
    }

    @Test
    void missingOtherOwnerAndTrashedFilesUseTheSame404() throws Exception {
        identity("USER");
        FileAnalysisService service = mock(FileAnalysisService.class);
        when(service.find(7, 99)).thenThrow(new EntryNotFoundException(99));
        when(service.schedule(7, 99)).thenThrow(new EntryNotFoundException(99));
        mvc(new FileAnalysisController(service)).perform(get("/api/analysis/99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc(new FileAnalysisController(service)).perform(post("/api/analysis/99/reanalyze"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordinaryUsersCannotReadReviewQueueChangeSettingOrDecideReviews() {
        try (var context = new AnnotationConfigApplicationContext(AdminConfig.class)) {
            identity("USER");
            var controller = context.getBean(AdminReviewController.class);
            assertThrows(AccessDeniedException.class, controller::settings);
            assertThrows(AccessDeniedException.class, () -> controller.update(new AdminReviewController.ReviewSettings(true)));
            assertThrows(AccessDeniedException.class, () -> controller.list("PENDING_REVIEW", 0, 20));
            assertThrows(AccessDeniedException.class, () -> controller.review(new AuthenticatedUser(7, "u", List.of("USER")),
                    31, new AdminReviewController.ReviewDecision("APPROVED", "人工确认")));
            verifyNoInteractions(context.getBean(FileAnalysisRepository.class), context.getBean(AnalysisSettingsService.class));
            identity("ADMIN");
            when(context.getBean(AnalysisSettingsService.class).reviewEnabled()).thenReturn(false);
            assertFalse(controller.settings().reviewEnabled());
        }
    }

    @Test
    void adminDecisionRequiresExplicitAllowedDecisionAndReason() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(AdminConfig.class)) {
            identity("ADMIN");
            var repository = context.getBean(FileAnalysisRepository.class);
            when(repository.review(31, 7, "APPROVED", "人工确认")).thenReturn(response());
            MockMvc mvc = mvc(context.getBean(AdminReviewController.class));
            mvc.perform(post("/api/admin/reviews/31").contentType("application/json")
                    .content("{\"decision\":\"APPROVED\",\"reason\":\"人工确认\"}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/admin/reviews/31").contentType("application/json")
                    .content("{\"decision\":\"AUTO_PASS\",\"reason\":\"人工确认\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/admin/reviews/31").contentType("application/json")
                    .content("{\"decision\":\"APPROVED\",\"reason\":\" \"}"))
                    .andExpect(status().isBadRequest());
            verify(repository, times(1)).review(31, 7, "APPROVED", "人工确认");
        }
    }

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private static void identity(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7, "user", List.of(role)), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static FileAnalysisResponse response() {
        return new FileAnalysisResponse(31, "notes.txt", "DOCUMENT", "EXTENSION", "FALLBACK", "后缀分类",
                "UNREVIEWED", "尚未人工审核", null, null, null, null);
    }

    @Configuration
    @EnableMethodSecurity
    static class AdminConfig {
        @Bean FileAnalysisRepository repository() { return mock(FileAnalysisRepository.class); }
        @Bean AnalysisSettingsService settings() { return mock(AnalysisSettingsService.class); }
        @Bean AdminReviewController controller(FileAnalysisRepository repository, AnalysisSettingsService settings) {
            return new AdminReviewController(repository, settings);
        }
    }
}
