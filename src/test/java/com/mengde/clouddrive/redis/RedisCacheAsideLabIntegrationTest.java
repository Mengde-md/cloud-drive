package com.mengde.clouddrive.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves positive cache, explicit eviction and short negative-cache behavior against Redis. */
@SpringJUnitConfig(RedisLabTestConfiguration.class)
class RedisCacheAsideLabIntegrationTest {

    @Autowired
    private RedisCacheAsideLab cacheAside;

    private String currentKey;

    @AfterEach
    void removeOnlyTheKeyCreatedByThisTest() {
        if (currentKey != null) {
            cacheAside.evict(currentKey);
        }
    }

    @Test
    void positiveValueIsLoadedOnceThenReadFromRedisUntilEvicted() {
        currentKey = "positive-" + System.nanoTime();
        AtomicInteger sourceCalls = new AtomicInteger();

        RedisCacheAsideLab.CacheRead first = cacheAside.readThrough(currentKey,
                Duration.ofSeconds(20), Duration.ofSeconds(2), () -> {
                    sourceCalls.incrementAndGet();
                    return Optional.of("from-source");
                });
        RedisCacheAsideLab.CacheRead second = cacheAside.readThrough(currentKey,
                Duration.ofSeconds(20), Duration.ofSeconds(2), () -> {
                    sourceCalls.incrementAndGet();
                    return Optional.of("should-not-run");
                });

        assertEquals(RedisCacheAsideLab.Source.SOURCE, first.source());
        assertEquals(RedisCacheAsideLab.Source.VALUE_CACHE, second.source());
        assertEquals(Optional.of("from-source"), second.value());
        assertEquals(1, sourceCalls.get());

        cacheAside.evict(currentKey);
        RedisCacheAsideLab.CacheRead afterEvict = cacheAside.readThrough(currentKey,
                Duration.ofSeconds(20), Duration.ofSeconds(2), () -> {
                    sourceCalls.incrementAndGet();
                    return Optional.of("reloaded");
                });
        assertEquals(RedisCacheAsideLab.Source.SOURCE, afterEvict.source());
        assertEquals(Optional.of("reloaded"), afterEvict.value());
        assertEquals(2, sourceCalls.get());
    }

    @Test
    void absentValueUsesShortNegativeMarkerSoRepeatedMissDoesNotHitSourceAgain() {
        currentKey = "negative-" + System.nanoTime();
        AtomicInteger sourceCalls = new AtomicInteger();

        RedisCacheAsideLab.CacheRead first = cacheAside.readThrough(currentKey,
                Duration.ofSeconds(20), Duration.ofSeconds(3), () -> {
                    sourceCalls.incrementAndGet();
                    return Optional.empty();
                });
        RedisCacheAsideLab.CacheRead second = cacheAside.readThrough(currentKey,
                Duration.ofSeconds(20), Duration.ofSeconds(3), () -> {
                    sourceCalls.incrementAndGet();
                    return Optional.of("should-not-run");
                });

        assertEquals(RedisCacheAsideLab.Source.SOURCE, first.source());
        assertTrue(first.value().isEmpty());
        assertEquals(RedisCacheAsideLab.Source.NEGATIVE_CACHE, second.source());
        assertTrue(second.value().isEmpty());
        assertEquals(1, sourceCalls.get());
    }
}
