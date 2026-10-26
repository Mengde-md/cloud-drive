package com.mengde.clouddrive.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalysisSettingsService {
    private final JdbcTemplate jdbc;
    private final AnalysisProperties properties;

    public AnalysisSettingsService(JdbcTemplate jdbc, AnalysisProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public boolean reviewEnabled() {
        return jdbc.query("SELECT settings_value FROM analysis_settings WHERE settings_key = 'review_enabled'",
                (rs, row) -> Boolean.parseBoolean(rs.getString(1))).stream().findFirst()
                .orElse(properties.isReviewEnabled());
    }

    public void setReviewEnabled(boolean enabled) {
        jdbc.update("""
                INSERT INTO analysis_settings (settings_key, settings_value) VALUES ('review_enabled', ?)
                ON DUPLICATE KEY UPDATE settings_value = VALUES(settings_value)
                """, Boolean.toString(enabled));
    }
}
