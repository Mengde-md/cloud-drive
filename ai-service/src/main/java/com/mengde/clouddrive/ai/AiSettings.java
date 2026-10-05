package com.mengde.clouddrive.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

@ConfigurationProperties("clouddrive.ai")
public record AiSettings(boolean enabled, String apiKey, String baseUrl, String model,
                         boolean protocolFixture, int timeoutMillis,
                         int maxOutputTokens, int maxConcurrentCalls) {
    public AiSettings {
        if (timeoutMillis < 100 || timeoutMillis > 20000 || maxOutputTokens < 32
                || maxOutputTokens > 1024 || maxConcurrentCalls < 1 || maxConcurrentCalls > 4) {
            throw new IllegalArgumentException("AI limits are outside the supported range");
        }
        if (!"deepseek-flash".equals(model)) {
            throw new IllegalArgumentException("Day30 model is frozen to deepseek-flash");
        }
        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.length() < 16
                || apiKey.contains("change_me") || apiKey.contains("<"))) {
            throw new IllegalArgumentException("An external AI API key is required");
        }
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid AI endpoint"); }
        boolean local = protocolFixture && "http".equals(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0
                && "/v1".equals(uri.getPath());
        if ((protocolFixture ? !local : !"https://api.deepseek.com/v1".equals(baseUrl))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI endpoint must be the frozen provider or a local protocol fixture");
        }
    }

    @Override public String toString() {
        return "AiSettings[enabled=" + enabled + ", model=" + model + ", apiKey=REDACTED]";
    }
}
