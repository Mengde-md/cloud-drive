package com.mengde.clouddrive.observability;

import com.mengde.clouddrive.auth.FileSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(properties = {"spring.cloud.nacos.config.import-check.enabled=false",
        "clouddrive.internal.service-token=day28-test-only-internal-token-over-32-bytes"})
@ContextConfiguration(classes = {MetricsSecurityTest.Probe.class, MetricsSecurityConfiguration.class,
        FileSecurityConfiguration.class, RequestTraceFilter.class})
class MetricsSecurityTest {
    private static final String TOKEN = "day28-test-only-internal-token-over-32-bytes";
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @RestController static class Probe {
        @GetMapping({"/actuator/metrics", "/actuator/metrics/clouddrive.remote.calls"})
        String metrics() { return "metric-probe"; }
        @GetMapping("/api/entries") String entries() { return "private"; }
    }
    @Test void missingWrongBearerAndMultipleTokensCannotReadMetrics() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"));
        mvc.perform(get("/actuator/metrics").header("X-CloudDrive-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer jwt-does-not-authorize-metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("X-CloudDrive-Internal-Token", TOKEN, TOKEN)).andExpect(status().isUnauthorized());
    }
    @Test void internalTokenAllowsOnlyGetAndDoesNotAuthorizeBusinessPaths() throws Exception {
        mvc.perform(get("/actuator/metrics").header("X-CloudDrive-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(content().string("metric-probe"));
        mvc.perform(get("/actuator/metrics/clouddrive.remote.calls").header("X-CloudDrive-Internal-Token", TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/actuator/metrics").header("X-CloudDrive-Internal-Token", TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/entries").header("X-CloudDrive-Internal-Token", TOKEN)).andExpect(status().isUnauthorized());
    }
}
