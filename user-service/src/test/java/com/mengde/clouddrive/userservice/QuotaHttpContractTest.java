package com.mengde.clouddrive.userservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(properties = {"clouddrive.internal.service-token=day26-test-only-service-token-over-32-bytes", "spring.cloud.nacos.config.import-check.enabled=false"})
@ContextConfiguration(classes = {QuotaController.class, InternalTokenFilter.class})
class QuotaHttpContractTest {
    @Autowired MockMvc mvc;
    @MockBean QuotaService service;
    private static final String TOKEN = "day26-test-only-service-token-over-32-bytes";
    @Test void anonymousAndUserBearerAreNotInternalAuthentication() throws Exception {
        mvc.perform(post("/internal/users/9/quota/reserve")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/users/9/quota/reserve").header("Authorization", "Bearer user-jwt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void commitAndReleaseBindExplicitReservationId() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        mvc.perform(post("/internal/users/9/quota/" + id + "/commit").header("X-CloudDrive-Internal-Token", TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/internal/users/9/quota/" + id + "/release").header("X-CloudDrive-Internal-Token", TOKEN)).andExpect(status().isOk());
        verify(service).commit(9, id); verify(service).release(9, id);
    }
    @Test void alternatePathSpellingsCannotBypassInternalAuthentication() throws Exception {
        for (String path : new String[]{"/internal;x/users/9/quota/reserve", "/%69nternal/users/9/quota/reserve", "/unknown"}) {
            mvc.perform(post(java.net.URI.create(path))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/quota/internal/users/9/quota/reserve").contextPath("/quota"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void onlyExactOperationalPathsArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/quota/actuator/info").contextPath("/quota")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health;x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/extra")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void conflictIs409NotAnAuthenticationFailure() throws Exception {
        doThrow(new IllegalStateException()).when(service).commit(9, "reservation");
        mvc.perform(post("/internal/users/9/quota/reservation/commit").header("X-CloudDrive-Internal-Token", TOKEN))
                .andExpect(status().isConflict()).andExpect(content().string("reservation conflict"));
    }
}
