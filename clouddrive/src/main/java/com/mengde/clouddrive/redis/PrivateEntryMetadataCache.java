package com.mengde.clouddrive.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mengde.clouddrive.web.EntryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 用户私有元数据的 Cache Aside 缓存（Day20）。
 *
 * <p>四个关键决定：</p>
 * <ol>
 *   <li><b>key 里必须带 ownerId</b>：缓存键是 {@code clouddrive:entries:{ownerId}}。
 *       如果键里没有用户维度，A 的目录列表会被 B 读到——这是缓存最典型的越权事故。</li>
 *   <li><b>写操作只失效、不更新</b>：更新缓存需要知道"新值是什么"，
 *       在并发写下两个请求的更新顺序可能和数据库提交顺序相反，反而写入旧值。
 *       删除键是幂等的，下一次读自然从数据库回填。</li>
 *   <li><b>失效时连列表键一起删</b>：改了一个条目的名字，列表里那一份也过期了。</li>
 *   <li><b>缓存故障不影响主流程</b>：Redis 抛异常时吞掉并回源数据库。
 *       缓存是优化，不是正确性来源；因为 Redis 挂了就让整个网盘不可用是不可接受的。
 *       反过来，失效失败只会留下一个有界的脏读窗口（TTL 到期后自动消失）。</li>
 * </ol>
 */
@Service
public class PrivateEntryMetadataCache {

    private static final Logger log = LoggerFactory.getLogger(PrivateEntryMetadataCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "clouddrive:entries:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public PrivateEntryMetadataCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public static String listKey(long ownerId) {
        return KEY_PREFIX + ownerId + ":list";
    }

    public static String entryKey(long ownerId, long entryId) {
        return KEY_PREFIX + ownerId + ":entry:" + entryId;
    }

    public List<EntryResponse> list(long ownerId, Supplier<List<EntryResponse>> source) {
        return read(listKey(ownerId), new TypeReference<>() { }, source);
    }

    public EntryResponse entry(long ownerId, long entryId, Supplier<EntryResponse> source) {
        return read(entryKey(ownerId, entryId), new TypeReference<>() { }, source);
    }

    /** 条目集合发生变化（新增、删除、彻底清空）时调用。 */
    public void evictOwner(long ownerId) {
        safeDelete(listKey(ownerId));
    }

    /** 单个条目发生变化时调用：既要删它自己，也要删包含它的列表。 */
    public void evictEntry(long ownerId, long entryId) {
        safeDelete(entryKey(ownerId, entryId));
        safeDelete(listKey(ownerId));
    }

    private <T> T read(String key, TypeReference<T> type, Supplier<T> source) {
        T cached = readCachedValue(key, type);
        if (cached != null) {
            return cached;
        }
        T value = source.get();
        writeCachedValue(key, value);
        return value;
    }

    private <T> T readCachedValue(String key, TypeReference<T> type) {
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? null : json.readValue(raw, type);
        } catch (Exception failure) {
            // 反序列化失败通常意味着缓存里是旧版本结构的脏数据，删掉它并回源。
            log.warn("read cache failed, falling back to database. key={}", key, failure);
            safeDelete(key);
            return null;
        }
    }

    private void writeCachedValue(String key, Object value) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), TTL);
        } catch (Exception failure) {
            log.warn("write cache failed, result still returned from database. key={}", key, failure);
        }
    }

    private void safeDelete(String key) {
        try {
            redis.delete(key);
        } catch (Exception failure) {
            // 有界的脏读窗口（最长一个 TTL）比让写操作整体失败更可接受。
            log.warn("evict cache failed, stale value will expire in {}. key={}", TTL, key, failure);
        }
    }
}
