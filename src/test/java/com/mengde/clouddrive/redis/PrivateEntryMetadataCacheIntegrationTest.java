package com.mengde.clouddrive.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengde.clouddrive.web.EntryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig(RedisLabTestConfiguration.class)
class PrivateEntryMetadataCacheIntegrationTest {
    private static final long ALICE = 70001L;
    private static final long BOB = 70002L;

    @Autowired
    StringRedisTemplate redis;

    @AfterEach
    void clean() {
        redis.delete(List.of(
                PrivateEntryMetadataCache.listKey(ALICE),
                PrivateEntryMetadataCache.listKey(BOB),
                PrivateEntryMetadataCache.entryKey(ALICE, 9)));
    }

    @Test
    void cacheHitDoesNotRunSourceAndOwnerKeysAreIsolated() {
        PrivateEntryMetadataCache cache = new PrivateEntryMetadataCache(redis, new ObjectMapper());
        AtomicInteger calls = new AtomicInteger();

        cache.list(ALICE, () -> {
            calls.incrementAndGet();
            return List.of(row(ALICE, "alice-file"));
        });
        List<EntryResponse> hit = cache.list(ALICE, () -> {
            calls.incrementAndGet();
            return List.of(row(ALICE, "wrong"));
        });
        List<EntryResponse> bobList = cache.list(BOB, () -> List.of(row(BOB, "bob-file")));

        assertAll(
                () -> assertEquals(1, calls.get()),
                () -> assertEquals("alice-file", hit.get(0).displayName()),
                () -> assertEquals("bob-file", bobList.get(0).displayName()));
    }

    @Test
    void staleValueRemainsUntilMutationEvictsThenFreshSourceIsRead() {
        PrivateEntryMetadataCache cache = new PrivateEntryMetadataCache(redis, new ObjectMapper());
        AtomicInteger calls = new AtomicInteger();

        cache.entry(ALICE, 9, () -> {
            calls.incrementAndGet();
            return row(ALICE, "before");
        });
        EntryResponse stale = cache.entry(ALICE, 9, () -> {
            calls.incrementAndGet();
            return row(ALICE, "after");
        });
        cache.evictEntry(ALICE, 9);
        EntryResponse fresh = cache.entry(ALICE, 9, () -> {
            calls.incrementAndGet();
            return row(ALICE, "after");
        });

        assertAll(
                () -> assertEquals("before", stale.displayName(), "un-evicted cache is intentionally stale"),
                () -> assertEquals("after", fresh.displayName()),
                () -> assertEquals(2, calls.get()));
    }

    @Test
    void malformedCachedJsonFallsBackToSourceAndRepairsTheKey() {
        PrivateEntryMetadataCache cache = new PrivateEntryMetadataCache(redis, new ObjectMapper());
        String key = PrivateEntryMetadataCache.entryKey(ALICE, 9);
        redis.opsForValue().set(key, "not-json");

        EntryResponse response = cache.entry(ALICE, 9, () -> row(ALICE, "recovered"));

        assertAll(
                () -> assertEquals("recovered", response.displayName()),
                () -> assertTrue(redis.opsForValue().get(key).contains("recovered")));
    }

    private static EntryResponse row(long owner, String name) {
        return new EntryResponse(9, owner, null, name, "DOCUMENT", 1L);
    }
}
