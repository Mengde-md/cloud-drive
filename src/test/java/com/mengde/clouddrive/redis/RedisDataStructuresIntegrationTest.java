package com.mengde.clouddrive.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses a real Redis connection, but only generates and removes Day14 lab keys. */
@SpringJUnitConfig(RedisLabTestConfiguration.class)
class RedisDataStructuresIntegrationTest {

    private static final String PREFIX = RedisCacheAsideLab.KEY_PREFIX + "types:";

    @Autowired
    private StringRedisTemplate redis;

    private String root;

    @AfterEach
    void removeOnlyThisTestNamespace() {
        if (root != null) {
            redis.delete(List.of(root + ":string", root + ":hash", root + ":list", root + ":set", root + ":ttl"));
        }
    }

    @Test
    void writesAndReadsStringHashListSetAndTtlInAnIsolatedLabNamespace() {
        root = PREFIX + UUID.randomUUID();

        redis.opsForValue().set(root + ":string", "directory");
        redis.opsForHash().put(root + ":hash", "kind", "DIRECTORY");
        redis.opsForList().rightPushAll(root + ":list", "create", "rename");
        redis.opsForSet().add(root + ":set", "owner-7", "owner-7", "owner-8");
        redis.opsForValue().set(root + ":ttl", "temporary", Duration.ofSeconds(30));

        Long ttl = redis.getExpire(root + ":ttl");
        assertEquals("directory", redis.opsForValue().get(root + ":string"));
        assertEquals("DIRECTORY", redis.opsForHash().get(root + ":hash", "kind"));
        assertEquals(List.of("create", "rename"), redis.opsForList().range(root + ":list", 0, -1));
        assertEquals(Set.of("owner-7", "owner-8"), redis.opsForSet().members(root + ":set"));
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 30);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(root + ":unknown")));
    }
}
