package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.OwnedDriveEntryApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.auth.FileSecurityConfiguration;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.remote.QuotaGateway;
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
@ContextConfiguration(classes = {DriveEntryController.class, GlobalExceptionHandler.class, FileSecurityConfiguration.class})
class FileHttpContractTest {
    @Autowired MockMvc mvc;
    @MockBean OwnedDriveEntryApplicationService entries;
    @MockBean PrivateEntryMetadataCache cache;
    @MockBean JwtDecoder decoder;
    private void authenticated() {
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "HS256")
                .subject("9").claim("username", "alice").claim("roles", List.of("USER")).build());
    }
    @Test void forgedIdentityWithoutJwtIs401() throws Exception {
        mvc.perform(get("/api/entries").header("X-User-Id", "9")).andExpect(status().isUnauthorized());
        verifyNoInteractions(entries, cache);
    }
    @Test void publicSnapshotEndpointIsNotAccidentallyMigrated() throws Exception {
        mvc.perform(post("/api/snapshots")).andExpect(status().isUnauthorized());
    }
    @Test void quotaFailureIs409AndIdentityComesFromJwt() throws Exception {
        authenticated();
        when(entries.create(any(), any())).thenThrow(new QuotaGateway.QuotaRejectedException());
        mvc.perform(post("/api/entries").header("Authorization", "Bearer valid").header("X-User-Id", "1")
                        .contentType("application/json").content("{\"displayName\":\"note\",\"kind\":\"DOCUMENT\",\"byteSize\":10}"))
                .andExpect(status().isConflict());
        verify(entries).create(eq(new AuthenticatedUser(9, "alice", List.of("USER"))), any());
    }
    @Test void serviceTimeoutIs503Not401() throws Exception {
        authenticated();
        when(entries.create(any(), any())).thenThrow(new QuotaGateway.QuotaUnavailableException());
        mvc.perform(post("/api/entries").header("Authorization", "Bearer valid")
                        .contentType("application/json").content("{\"displayName\":\"folder\",\"kind\":\"DIRECTORY\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("quota service unavailable"));
    }
}
