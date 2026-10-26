package com.mengde.clouddrive.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置（Day21）。
 *
 * <p>为什么显式写而不是完全交给 starter 自动装配：Redis 是否设了口令在不同环境下不一样。
 * 自动装配会把配置里的空字符串当成"有口令"，于是向一个没开认证的 Redis 发 AUTH ""，
 * 直接连不上——这种失败的报错信息还很难指向真正原因。
 * 这里显式判断 blank，只有真的配了口令才设置。</p>
 *
 * <p>本类只创建 RedissonClient（供分布式锁与限流使用）；
 * 缓存读写用的 StringRedisTemplate 仍由 Spring Data Redis 自动装配，
 * 两者连同一个 Redis，各用各擅长的 API。</p>
 */
@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                // 超时不能不设：默认无限等待会让 Redis 故障演变成应用线程被耗尽。
                .setTimeout(3000)
                .setRetryAttempts(2);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
