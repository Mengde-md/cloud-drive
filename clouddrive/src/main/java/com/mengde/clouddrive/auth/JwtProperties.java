package com.mengde.clouddrive.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（Day11 的 @ConfigurationProperties 落点 + Day15 的认证需要）。
 * 密钥不写在代码里，由配置/环境变量注入。
 */
@ConfigurationProperties(prefix = "clouddrive.jwt")
public class JwtProperties {

    /** 对称签名密钥，HS256 要求至少 32 字节。 */
    private String secret = "change-me-cloud-drive-learning-secret-key-32bytes";

    /** 令牌有效期（分钟）。 */
    private long ttlMinutes = 120;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }
}
