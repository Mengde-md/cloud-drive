package com.mengde.clouddrive.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisCapabilitiesControllerTest {
    @Test
    void exposesDisabledStateRatherThanClaimingSuffixClassificationIsAi() throws Exception {
        AnalysisProperties properties = new AnalysisProperties();
        AnalysisSettingsService settings = mock(AnalysisSettingsService.class);
        MockMvcBuilders.standaloneSetup(new AnalysisCapabilitiesController(properties, settings)).build()
                .perform(get("/api/analysis/capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("DeepSeek"))
                .andExpect(jsonPath("$.aiEnabled").value(false)).andExpect(jsonPath("$.reviewEnabled").value(false));
    }

    @Test
    void reportsConfiguredStateWithoutExposingKeyOrEndpoint() throws Exception {
        AnalysisProperties properties = new AnalysisProperties();
        properties.setDeepseekApiKey("private-test-key-not-for-response");
        properties.setDeepseekBaseUrl("https://private.example.test");
        AnalysisSettingsService settings = mock(AnalysisSettingsService.class);
        when(settings.reviewEnabled()).thenReturn(true);
        MockMvcBuilders.standaloneSetup(new AnalysisCapabilitiesController(properties, settings)).build()
                .perform(get("/api/analysis/capabilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.aiEnabled").value(true))
                .andExpect(jsonPath("$.reviewEnabled").value(true))
                .andExpect(jsonPath("$.deepseekApiKey").doesNotExist())
                .andExpect(jsonPath("$.deepseekBaseUrl").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-key"))));
    }
}
