package com.mengde.clouddrive.analysis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports usable product capabilities without returning credentials or private endpoint settings. */
@RestController
public class AnalysisCapabilitiesController {
    private final AnalysisProperties properties;
    private final AnalysisSettingsService settings;

    public AnalysisCapabilitiesController(AnalysisProperties properties, AnalysisSettingsService settings) {
        this.properties = properties;
        this.settings = settings;
    }

    @GetMapping("/api/analysis/capabilities")
    public Capabilities capabilities() {
        String key = properties.getDeepseekApiKey();
        return new Capabilities("DeepSeek", key != null && !key.isBlank(), settings.reviewEnabled(), "TEXT_ONLY");
    }

    public record Capabilities(String provider, boolean aiEnabled, boolean reviewEnabled, String contentSupport) {}
}
