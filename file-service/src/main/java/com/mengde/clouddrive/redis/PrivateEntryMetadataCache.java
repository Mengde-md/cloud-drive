package com.mengde.clouddrive.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengde.clouddrive.web.EntryResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Day20 Cache Aside for authenticated, owner-scoped metadata only. */
@Service
public class PrivateEntryMetadataCache {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public PrivateEntryMetadataCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public List<EntryResponse> list(long ownerId, Supplier<List<EntryResponse>> source) {
        return read(listKey(ownerId), new TypeReference<>() {}, source);
    }

    public EntryResponse entry(long ownerId, long entryId, Supplier<EntryResponse> source) {
        return read(entryKey(ownerId, entryId), new TypeReference<>() {}, source);
    }

    /**
     * Cache invalidation is deliberately best effort: a Redis outage must not turn an
     * already-successful metadata write into an HTTP failure. TTL bounds any stale list.
     */
    public void evictOwner(long ownerId) {
        safeDelete(listKey(ownerId));
    }

    public void evictEntry(long ownerId, long entryId) {
        safeDelete(entryKey(ownerId, entryId));
        safeDelete(listKey(ownerId));
    }

    private <T> T read(String key, TypeReference<T> type, Supplier<T> source) {
        T cached = readCachedValue(key, type);
        if (cached != null) {
            return cached;
        }

        // Do not place the authoritative supplier in a catch block: a database exception
        // must be propagated once, not hidden or executed a second time.
        T value = source.get();
        writeCachedValue(key, value);
        return value;
    }

    private <T> T readCachedValue(String key, TypeReference<T> type) {
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? null : json.readValue(raw, type);
        } catch (Exception ignored) {
            safeDelete(key);
            return null;
        }
    }

    private void writeCachedValue(String key, Object value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), TTL);
        } catch (Exception ignored) {
            // Cache is an optimization. The caller still receives the database result.
        }
    }

    private void safeDelete(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // A bounded stale window is safer than failing an owner metadata mutation.
        }
    }

    static String listKey(long ownerId) {
        return "clouddrive:day20:owner:" + ownerId + ":entries";
    }

    static String entryKey(long ownerId, long entryId) {
        return "clouddrive:day20:owner:" + ownerId + ":entry:" + entryId;
    }
}
