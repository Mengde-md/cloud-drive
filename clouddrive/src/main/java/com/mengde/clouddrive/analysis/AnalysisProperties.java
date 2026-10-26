package com.mengde.clouddrive.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clouddrive.analysis")
public class AnalysisProperties {
    private String deepseekApiKey = "";
    private String deepseekBaseUrl = "https://api.deepseek.com";
    private String deepseekModel = "deepseek-flash";
    private int requestTimeoutSeconds = 30;
    private int maxTextBytes = 24000;
    private boolean reviewEnabled = false;

    public String getDeepseekApiKey() { return deepseekApiKey; }
    public void setDeepseekApiKey(String value) { deepseekApiKey = value; }
    public String getDeepseekBaseUrl() { return deepseekBaseUrl; }
    public void setDeepseekBaseUrl(String value) { deepseekBaseUrl = value; }
    public String getDeepseekModel() { return deepseekModel; }
    public void setDeepseekModel(String value) { deepseekModel = value; }
    public int getRequestTimeoutSeconds() { return Math.max(1, Math.min(120, requestTimeoutSeconds)); }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public int getMaxTextBytes() { return Math.max(1024, Math.min(65536, maxTextBytes)); }
    public void setMaxTextBytes(int value) { maxTextBytes = value; }
    public boolean isReviewEnabled() { return reviewEnabled; }
    public void setReviewEnabled(boolean value) { reviewEnabled = value; }
}
