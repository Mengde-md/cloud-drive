package com.mengde.clouddrive.redis;

import com.mengde.clouddrive.error.UploadRateLimitExceededException;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Day21: per-user limits stored in Redis, so every application node consumes the same allowance. */
@Component
public class UploadRequestRateLimiter {
    public enum Operation {
        DIRECT_UPLOAD(5), INSTANT_UPLOAD(5), START_SESSION(5), PUT_CHUNK(20), COMPLETE_SESSION(5);
        private final int permitsPerMinute;
        Operation(int permitsPerMinute) { this.permitsPerMinute = permitsPerMinute; }
    }

    private final RedissonClient redisson;
    public UploadRequestRateLimiter(RedissonClient redisson) { this.redisson = redisson; }

    public void requirePermit(long ownerId, Operation operation) {
        RRateLimiter limiter = redisson.getRateLimiter("clouddrive:day21:rate:owner:" + ownerId + ":" + operation.name().toLowerCase());
        limiter.trySetRate(RateType.OVERALL, operation.permitsPerMinute, 1, RateIntervalUnit.MINUTES);
        limiter.expire(1, TimeUnit.HOURS);
        if (!limiter.tryAcquire()) throw new UploadRateLimitExceededException(operation.name().toLowerCase());
    }
}
