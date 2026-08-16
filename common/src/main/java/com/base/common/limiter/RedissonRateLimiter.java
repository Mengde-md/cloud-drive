package com.base.common.limiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redisson 的分布式限流器实现
 *
 * 【面试知识点 - Redisson RRateLimiter】
 * Redisson 的 RRateLimiter 基于令牌桶算法（Token Bucket），底层通过 Redis + Lua 脚本实现：
 *
 * 1. 令牌桶算法原理：
 *    - 桶中以固定速率生成令牌（token），桶有最大容量
 *    - 每次请求需要从桶中取出一个令牌
 *    - 如果桶中没有令牌，请求被拒绝（限流）
 *    - 与漏桶的区别：令牌桶允许突发流量（桶满时可以一次取多个令牌）
 *
 * 2. Redisson 的实现细节：
 *    - 使用 Redis 的 hash 结构存储令牌数和上次补充时间
 *    - 通过 Lua 脚本保证"计算补充令牌 + 扣减令牌"的原子性
 *    - 分布式环境下多个实例共享同一个令牌桶（Redis 中）
 *
 * 3. 与本项目已有的分布式锁的关系：
 *    - 分布式锁（DistributeLockAspect）使用的是 Spring 的 StringRedisTemplate + SETNX
 *    - 限流器使用的是 Redisson 客户端（功能更强大）
 *    - 两者可以共存：Redisson 本身也提供分布式锁（RLock），但项目已有自己的实现
 *
 * 4. RateType.OVERALL vs RateType.PER_CLIENT：
 *    - OVERALL：全局共享一个令牌桶（所有实例、所有请求者共用限流配额）
 *    - PER_CLIENT：每个客户端独立一个令牌桶（需要 clientId 参数）
 *    - 本项目使用 OVERALL，适用于"某接口全局 QPS 限制"的场景
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonRateLimiter implements RateLimiter {

    /**
     * Redisson 客户端
     *
     * 【注入说明】
     * RedissonClient 由 redisson-spring-boot-starter 自动配置
     * 它会读取 application.yml 中的 spring.redis 配置，自动创建 RedissonClient Bean
     * 无需手动编写配置类（与 StringRedisTemplate 的自动配置类似）
     */
    private final RedissonClient redissonClient;

    /** 限流器 Redis key 前缀，方便在 Redis 中识别和管理 */
    private static final String RATE_LIMITER_PREFIX = "rate_limiter:";

    /**
     * 尝试获取限流令牌
     *
     * 【执行流程】
     * 1. 根据 key 获取 Redisson 的 RRateLimiter 实例
     * 2. 尝试设置令牌桶的速率（trySetRate）
     *    - 如果已经设置过，trySetRate 会返回 false，不会重复设置
     *    - 这是幂等操作，多个实例并发调用也没问题
     * 3. 尝试获取一个令牌（tryAcquire）
     *    - 有令牌 → 返回 true（放行）
     *    - 无令牌 → 返回 false（限流）
     *
     * @param key        限流资源标识（如 "api:upload"）
     * @param limit      窗口内允许的最大请求数（令牌桶容量）
     * @param windowSize 窗口大小（秒）（令牌补充周期）
     * @return true=允许通过, false=被限流
     */
    @Override
    public Boolean tryAcquire(String key, int limit, int windowSize) {
        // 拼接完整的 Redis key，加上前缀方便管理
        String rateLimiterKey = RATE_LIMITER_PREFIX + key;

        // 获取 Redisson 的 RRateLimiter 实例
        // 注意：这只是获取引用，不会立即访问 Redis
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(rateLimiterKey);

        // 尝试设置令牌桶速率
        // RateType.OVERALL = 全局共享令牌桶（所有实例共用配额）
        // limit = 每个 windowSize 时间段内最多允许 limit 个请求
        // RateIntervalUnit.SECONDS = 时间单位为秒
        // trySetRate 是幂等的：如果已经设置过则返回 false，不会覆盖已有配置
        rateLimiter.trySetRate(RateType.OVERALL, limit, windowSize, RateIntervalUnit.SECONDS);

        // 尝试获取一个令牌
        // tryAcquire(1) 是非阻塞的：有令牌立即返回 true，无令牌立即返回 false
        // 如果需要阻塞等待，可以使用 tryAcquire(1, timeout, timeUnit)
        boolean acquired = rateLimiter.tryAcquire(1);

        if (!acquired) {
            log.warn("限流触发: key={}, limit={}/{}s", key, limit, windowSize);
        }

        return acquired;
    }
}
