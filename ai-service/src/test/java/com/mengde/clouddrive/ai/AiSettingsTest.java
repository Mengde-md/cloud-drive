package com.mengde.clouddrive.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiSettingsTest {
    private AiSettings settings(boolean enabled, String key, String url, boolean fixture) {
        return new AiSettings(enabled, key, url, "deepseek-flash", fixture, 1000, 512, 2);
    }
    @Test void disabledModeNeedsNoApiKeyAndNeverBuildsAnExternalClient() {
        var settings = settings(false, "", "https://api.deepseek.com/v1", false);
        try (var service = new AiConfiguration().summaryService(settings)) {
            assertEquals("AI_DISABLED", assertThrows(AiFailure.class, () -> service.summarize(1, "text")).getMessage());
        }
    }
    @Test void enabledModeRejectsMissingShortAndTemplateKeys() {
        for (String key : List.of("", "short", "change_me_placeholder")) {
            assertThrows(IllegalArgumentException.class, () -> settings(true, key, "https://api.deepseek.com/v1", false));
        }
    }
    @Test void endpointAndFixtureFlagAreBothRequiredForLocalTesting() {
        assertDoesNotThrow(() -> settings(true, "test-only-api-key", "http://127.0.0.1:12345/v1", true));
        for (String url : List.of("http://127.0.0.1:12345/v1", "https://attacker.invalid/v1",
                "https://api.deepseek.com/v1?key=secret", "https://user@api.deepseek.com/v1")) {
            assertThrows(IllegalArgumentException.class, () -> settings(true, "test-only-api-key", url, false));
        }
        assertThrows(IllegalArgumentException.class, () -> settings(true, "test-only-api-key", "http://192.168.0.2:12345/v1", true));
        assertThrows(IllegalArgumentException.class, () -> settings(true, "test-only-api-key", "https://api.deepseek.com/v1", true));
    }
    @Test void limitsAndModelAreFrozenAndSettingsAreRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new AiSettings(false, "", "https://api.deepseek.com/v1", "deepseek-chat", false, 1000, 512, 2));
        assertThrows(IllegalArgumentException.class, () -> new AiSettings(false, "", "https://api.deepseek.com/v1", "deepseek-flash", false, 0, 512, 2));
        assertThrows(IllegalArgumentException.class, () -> new AiSettings(false, "", "https://api.deepseek.com/v1", "deepseek-flash", false, 1000, 2000, 2));
        assertThrows(IllegalArgumentException.class, () -> new AiSettings(false, "", "https://api.deepseek.com/v1", "deepseek-flash", false, 1000, 512, 5));
        assertFalse(settings(true, "private-key-canary", "https://api.deepseek.com/v1", false).toString().contains("private-key-canary"));
    }
}
