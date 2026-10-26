package com.mengde.clouddrive.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailRequestRateLimiterTest {
    @Test
    void redisCounterAllowsLimitThenRejectsTheNextRequest() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(20L, 21L);
        EmailRequestRateLimiter limiter = new EmailRequestRateLimiter(redis, 20, 600);
        assertDoesNotThrow(() -> limiter.requirePermit("127.0.0.1"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> limiter.requirePermit("127.0.0.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatusCode());
    }
}
