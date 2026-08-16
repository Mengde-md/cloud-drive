package com.base.common.limiter;

/**
 * 限流器接口
 *
 * 【面试知识点】
 * 限流是保护系统的重要手段，防止突发流量压垮服务。
 * 常见算法：固定窗口、滑动窗口、漏桶、令牌桶
 *
 * 本项目使用 Redisson 的 RRateLimiter（基于令牌桶算法）
 * Sentinel 作为更高级的限流框架提供补充能力
 *
 * 【为什么定义接口？】
 * 面向接口编程（依赖倒置原则）：
 * - 业务代码只依赖 RateLimiter 接口，不关心底层实现
 * - 将来想换成 Sentinel 限流、Guava RateLimiter 等，只需新增实现类
 * - 便于单元测试：可以 Mock 一个 RateLimiter 而不需要启动 Redis
 */
public interface RateLimiter {

    /**
     * 尝试获取令牌
     *
     * 【方法设计思路】
     * - key:        限流资源标识，不同接口/用户可以用不同的 key 实现细粒度限流
     * - limit:      窗口内允许的最大请求数，即令牌桶的容量
     * - windowSize: 窗口大小（秒），即令牌桶的补充周期
     *
     * 例如：tryAcquire("api:upload:userId=1", 10, 1)
     * 表示：用户1的上传接口，每1秒最多允许10次请求
     *
     * @param key        限流资源标识
     * @param limit      窗口内允许的最大请求数
     * @param windowSize 窗口大小（秒）
     * @return true=允许通过, false=被限流
     */
    Boolean tryAcquire(String key, int limit, int windowSize);
}
