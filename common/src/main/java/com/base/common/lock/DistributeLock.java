package com.base.common.lock;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解 —— 基于 Redis SETNX 实现
 * <p>
 * 【使用方式】
 * <pre>
 * // 在方法上加注解，自动加分布式锁
 * &#064;DistributeLock(key = "'merge:' + #identifier", waitTime = 5, expireTime = 30)
 * public void mergeFile(String identifier) { ... }
 * </pre>
 * <p>
 * 【面试知识点】
 * 1. 为什么需要分布式锁？
 *    - 单机锁（synchronized / ReentrantLock）在多实例部署下失效
 *    - 分布式锁通过 Redis 等中间件实现跨进程的互斥访问
 * 2. Redis 分布式锁的核心原理：
 *    - SET key value NX EX seconds（原子操作）
 *    - NX = 不存在才设置（互斥），EX = 过期时间（防死锁）
 *    - 释放锁时用 Lua 脚本保证"判断+删除"的原子性
 * 3. key 支持 SpEL 表达式，可以动态拼接参数
 *    - 例如 "'merge:' + #identifier" 会根据方法参数 identifier 的值生成锁的 key
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributeLock {

    /**
     * 锁的 key，支持 SpEL 表达式
     * 例如："'merge:' + #identifier" 会解析为 "merge:abc123"
     */
    String key();

    /**
     * 等待获取锁的最大时间（秒），超时则抛异常
     * 默认 5 秒，避免请求长时间阻塞
     */
    long waitTime() default 5;

    /**
     * 锁的自动释放时间（秒），防止持有者崩溃导致死锁
     * 默认 30 秒，一般足够方法执行完毕
     */
    long expireTime() default 30;

    /**
     * 时间单位，默认秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
