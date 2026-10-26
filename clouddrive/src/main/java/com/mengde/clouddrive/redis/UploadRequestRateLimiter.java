package com.mengde.clouddrive.redis;

import com.mengde.clouddrive.error.UploadRateLimitExceededException;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 按用户 + 操作维度的上传限流（Day21）。
 *
 * <p>为什么限流器建在 Redis 而不是进程内：进程内计数器在多实例部署时形同虚设——
 * 限 5 次/分钟、部署 3 个实例，实际就是 15 次/分钟。
 * RateType.OVERALL 表示这个配额是所有实例共享的。</p>
 *
 * <p>分片接口的额度明显更高（20 次/分钟）：一次大文件上传本来就要发很多次分片请求，
 * 用和普通上传一样的额度会把正常用户挡在门外。限流值必须贴合真实调用形态。</p>
 *
 * <p>expire 是为了让长期不活跃用户的限流键自己消失，不无限占用 Redis 内存。</p>
 */
@Component
public class UploadRequestRateLimiter {

    private static final String RATE_PREFIX = "clouddrive:rate:owner:";

    private final RedissonClient redisson;

    public UploadRequestRateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public void requirePermit(long ownerId, Operation operation) {
        RRateLimiter limiter = redisson.getRateLimiter(
                RATE_PREFIX + ownerId + ":" + operation.name().toLowerCase(Locale.ROOT));
        limiter.trySetRate(RateType.OVERALL,
                operation.permitsPerMinute(), 1, RateIntervalUnit.MINUTES);
        limiter.expire(java.time.Duration.ofHours(1));
        if (!limiter.tryAcquire()) {
            throw new UploadRateLimitExceededException(operation.name().toLowerCase(Locale.ROOT));
        }
    }

    public enum Operation {
        DIRECT_UPLOAD(5),
        INSTANT_UPLOAD(5),
        START_SESSION(5),
        PUT_CHUNK(20),
        COMPLETE_SESSION(5);

        private final int permitsPerMinute;

        Operation(int permitsPerMinute) {
            this.permitsPerMinute = permitsPerMinute;
        }

        public int permitsPerMinute() {
            return permitsPerMinute;
        }
    }
}
