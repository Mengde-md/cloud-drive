package com.mengde.clouddrive.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Day14 的 Redis 基础实验，不缓存任何真实网盘条目。
 *
 * <p>所有键都限定在 {@code clouddrive:day14:lab:*} 命名空间。它演示
 * Cache Aside 的正值缓存、短 TTL 空值标记和显式失效；Day20 才允许将这一
 * 模式用于经过认证和权限边界保护的元数据热点。</p>
 */
@Service
public class RedisCacheAsideLab {

    static final String KEY_PREFIX = "clouddrive:day14:lab:";
    private static final String NEGATIVE_SUFFIX = ":absent";

    private final StringRedisTemplate redis;

    public RedisCacheAsideLab(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Reads a lab value by Cache Aside. A present source value is cached with
     * {@code valueTtl}; an absent source value gets a separate short negative
     * marker so repeated misses do not repeatedly call the source.
     */
    public CacheRead readThrough(String requestedKey,
                                 Duration valueTtl,
                                 Duration negativeTtl,
                                 Supplier<Optional<String>> sourceReader) {
        String key = dataKey(requestedKey);
        Duration checkedValueTtl = requirePositive(valueTtl, "valueTtl");
        Duration checkedNegativeTtl = requirePositive(negativeTtl, "negativeTtl");
        Objects.requireNonNull(sourceReader, "sourceReader");

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return CacheRead.cacheHit(cached);
        }
        if (Boolean.TRUE.equals(redis.hasKey(negativeKey(key)))) {
            return CacheRead.negativeCacheHit();
        }

        Optional<String> loaded = Objects.requireNonNull(sourceReader.get(),
                "sourceReader must return Optional, not null");
        if (loaded.isPresent()) {
            redis.opsForValue().set(key, loaded.get(), checkedValueTtl);
            redis.delete(negativeKey(key));
            return CacheRead.sourceValue(loaded.get());
        }

        redis.opsForValue().set(negativeKey(key), "1", checkedNegativeTtl);
        return CacheRead.sourceMiss();
    }

    /** Explicit write-side invalidation for the lab key and its absence marker. */
    public void evict(String requestedKey) {
        String key = dataKey(requestedKey);
        redis.delete(key);
        redis.delete(negativeKey(key));
    }

    String dataKey(String requestedKey) {
        String key = Objects.requireNonNull(requestedKey, "requestedKey").trim();
        if (!key.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("lab key must contain only letters, digits, _ or -");
        }
        return KEY_PREFIX + key;
    }

    private static String negativeKey(String dataKey) {
        return dataKey + NEGATIVE_SUFFIX;
    }

    private static Duration requirePositive(Duration ttl, String name) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return ttl;
    }

    public record CacheRead(Optional<String> value, Source source) {
        static CacheRead cacheHit(String value) {
            return new CacheRead(Optional.of(value), Source.VALUE_CACHE);
        }

        static CacheRead negativeCacheHit() {
            return new CacheRead(Optional.empty(), Source.NEGATIVE_CACHE);
        }

        static CacheRead sourceValue(String value) {
            return new CacheRead(Optional.of(value), Source.SOURCE);
        }

        static CacheRead sourceMiss() {
            return new CacheRead(Optional.empty(), Source.SOURCE);
        }
    }

    public enum Source {
        VALUE_CACHE,
        NEGATIVE_CACHE,
        SOURCE
    }
}
