package com.mengde.clouddrive.deployment;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatabaseMigrationSettingsTest {
    private Map<String, String> valid() {
        return new HashMap<>(Map.of("CLOUDDRIVE_DB_URL", "jdbc:mysql://127.0.0.1:13306/ci",
                "CLOUDDRIVE_DB_USERNAME", "ci_app", "CLOUDDRIVE_DB_PASSWORD", "public-test-canary"));
    }

    @Test void acceptsExplicitSettingsWithoutPrintingThem() {
        var settings = DatabaseMigrationApplication.Settings.from(valid());
        assertThat(settings.username()).isEqualTo("ci_app");
        assertThat(settings.toString()).doesNotContain("public-test-canary", "13306", "ci_app");
    }

    @Test void missingAndPlaceholderSettingsFailClosed() {
        for (String key : valid().keySet()) {
            var input = valid();
            input.remove(key);
            assertThatThrownBy(() -> DatabaseMigrationApplication.Settings.from(input))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
            input.put(key, "change-me-public-template");
            assertThatThrownBy(() -> DatabaseMigrationApplication.Settings.from(input))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
    }

    @Test void rejectsOtherDatabasesAndCredentialsInJdbcUrl() {
        for (String url : new String[] {"jdbc:h2:mem:test", "jdbc:mysql://localhost/ci?password=canary",
                "jdbc:mysql://localhost/ci?USER=ci_app"}) {
            var input = valid();
            input.put("CLOUDDRIVE_DB_URL", url);
            assertThatThrownBy(() -> DatabaseMigrationApplication.Settings.from(input))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("canary");
        }
    }
}
