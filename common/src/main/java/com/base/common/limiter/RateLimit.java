package com.base.common.limiter;

import java.lang.annotation.*;

/**
 * 限流注解 —— 标记需要限流的方法
 *
 * 【面试知识点 - 注解 + AOP 实现声明式限流】
 * 1. 设计思路（对标 @DistributeLock 的设计模式）：
 *    - 注解定义限流规则（key、限流阈值、窗口大小等）
 *    - AOP 切面拦截注解，调用 RateLimiter 判断是否放行
 *    - 业务代码只需加一个注解，完全解耦
 *
 * 2. SpEL（Spring Expression Language）支持：
 *    - key 属性支持 SpEL 表达式，可以动态拼接方法参数
 *    - 例如："'upload:' + #userId" 可以对每个用户独立限流
 *    - 例如："'api:search'" 对整个接口全局限流
 *
 * 3. 使用示例：
 * <pre>
 * // 对搜索接口全局限流：每秒最多10次
 * &#064;RateLimit(key = "'api:search'", limit = 10, windowSize = 1)
 * public List&lt;Result&gt; search(String keyword) { ... }
 *
 * // 对每个用户独立限流：每个用户每秒最多上传3个文件
 * &#064;RateLimit(key = "'upload:' + #userId", limit = 3, windowSize = 1)
 * public void upload(Long userId, MultipartFile file) { ... }
 * </pre>
 *
 * 【与 @DistributeLock 的对比】
 * | 特性       | @DistributeLock         | @RateLimit              |
 * |------------|------------------------|------------------------|
 * | 目的       | 防并发（互斥访问）       | 防刷（流量控制）         |
 * | 底层实现    | Redis SETNX            | Redisson 令牌桶         |
 * | 被拦截时    | 等待或抛 SystemException | 直接拒绝，抛 BusinessException |
 * | 适用场景    | 分片合并、扣库存         | 搜索、上传、短信验证码    |
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流 key，支持 SpEL 表达式
     * <p>
     * 例如："'api:search'" 表示全局限流
     * 例如："'upload:' + #userId" 表示按用户限流
     * <p>
     * 默认为空字符串时，切面会自动使用 "类名:方法名" 作为 key
     */
    String key() default "";

    /**
     * 窗口内最大请求数（令牌桶容量）
     * <p>
     * 默认 10 次，适合大多数读接口
     * 写接口（如上传）建议设置为 3~5 次
     */
    int limit() default 10;

    /**
     * 窗口大小（秒）
     * <p>
     * 默认 1 秒，即每秒最多 limit 次请求
     * 如果需要"每分钟60次"，可以设置 limit=60, windowSize=60
     */
    int windowSize() default 1;

    /**
     * 被限流时的提示信息
     * <p>
     * 通过 BusinessException 抛出，前端可以直接展示给用户
     */
    String message() default "请求过于频繁，请稍后再试";
}
