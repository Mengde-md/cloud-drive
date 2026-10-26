package com.mengde.clouddrive.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Atomic shared counter also limits callers that rotate destination email addresses. */
@Component
public class EmailRequestRateLimiter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int limit;
    private final int windowSeconds;

    public EmailRequestRateLimiter(StringRedisTemplate redis,
                                    @Value("${clouddrive.mail.ip-limit:20}") int limit,
                                    @Value("${clouddrive.mail.ip-window-seconds:600}") int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) throw new IllegalArgumentException("Email request limit must be positive");
        this.redis = redis;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public void requirePermit(String remoteAddress) {
        try {
            String addressHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(remoteAddress.getBytes(StandardCharsets.UTF_8)));
            Long count = redis.execute(COUNT, List.of("clouddrive:email-request:" + addressHash),
                    Integer.toString(windowSeconds));
            if (count == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务暂不可用，请稍后重试");
            }
            if (count > limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码请求过于频繁，请稍后重试");
            }
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱服务暂不可用，请稍后重试");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
