package com.mengde.clouddrive.web;

import com.mengde.clouddrive.auth.FileSecurityConfiguration;
import com.mengde.clouddrive.search.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = "spring.cloud.nacos.config.import-check.enabled=false")
@ContextConfiguration(classes = {FileSearchController.class, GlobalExceptionHandler.class, FileSecurityConfiguration.class})
class FileSearchHttpTest {
    @Autowired MockMvc mvc;
    @MockBean FileSearchService search;
    @MockBean JwtDecoder decoder;
    private void authenticated() {
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "HS256")
                .subject("9").claim("username", "alice").claim("roles", List.of("USER")).build());
    }
    @Test void forgedHeadersCannotSearchOrReindexWithoutJwt() throws Exception {
        mvc.perform(get("/api/files/search").param("q", "report").header("X-User-Id", "9")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/files/search/reindex").header("X-User-Id", "9")).andExpect(status().isUnauthorized());
        verifyNoInteractions(search);
    }
    @Test void authenticatedIdentityWinsAndDegradedResponseRemains200() throws Exception {
        authenticated();
        when(search.search(eq(9L), any())).thenReturn(new FileSearchService.SearchPage(List.of(), 0, 0, 20, "MYSQL", true, "ES_UNAVAILABLE"));
        mvc.perform(get("/api/files/search").param("q", "report").param("ownerId", "1")
                        .header("X-User-Id", "1").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("MYSQL"))
                .andExpect(jsonPath("$.degraded").value(true)).andExpect(jsonPath("$.reason").value("ES_UNAVAILABLE"));
        verify(search).search(9, new SearchQuery("report", 0, 20));
    }
    @Test void invalidWindowIs400AndFailedExplicitReindexIs503() throws Exception {
        authenticated();
        mvc.perform(get("/api/files/search").param("q", "report").param("page", "2147483647")
                        .header("Authorization", "Bearer valid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/files/search").param("q", "\u0000")
                        .header("Authorization", "Bearer valid")).andExpect(status().isBadRequest());
        verifyNoInteractions(search);
        when(search.reindex(9)).thenThrow(new SearchUnavailableException("ES_UNAVAILABLE"));
        mvc.perform(post("/api/files/search/reindex").header("Authorization", "Bearer valid"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.reason").value("ES_UNAVAILABLE"));
    }
}
