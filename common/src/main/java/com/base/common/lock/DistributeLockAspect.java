package com.base.common.lock;

import com.base.common.exception.SystemException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * 分布式锁 AOP 切面
 * <p>
 * 【工作原理】
 * 1. 方法调用前：尝试用 Redis SETNX 获取锁
 *    - SET lockKey value NX EX expireTime（原子操作）
 *    - value 是 UUID，用于确保只有锁的持有者才能释放锁
 * 2. 方法执行：获取锁成功后正常执行业务逻辑
 * 3. 方法执行后（finally）：用 Lua 脚本释放锁
 *    - Lua 脚本保证 "判断 value 是否匹配 + 删除 key" 是原子操作
 *    - 防止误删其他线程/实例持有的锁
 * <p>
 * 【面试知识点】
 * 1. AOP（面向切面编程）：通过 @Around 环绕通知拦截方法执行
 *    - ProceedingJoinPoint 代表被拦截的方法
 *    - joinPoint.proceed() 执行原方法
 * 2. SpEL（Spring Expression Language）：
 *    - 用于从注解的 key 属性中解析动态表达式
 *    - MethodBasedEvaluationContext 将方法参数注入 SpEL 上下文
 * 3. Lua 脚本释放锁：
 *    - Redis 执行 Lua 脚本是原子性的（单线程）
 *    - 先 GET 比较 value，匹配才 DEL，防止误删别人的锁
 * 4. @Aspect + @Component：Spring 会扫描并注册这个切面
 *    - @Around("execution(* *(..)) && @annotation(...)") 表示拦截所有带 @DistributeLock 的方法
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributeLockAspect {

    private final StringRedisTemplate redisTemplate;

    /** SpEL 表达式解析器 */
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /** 方法参数名发现器（用于 SpEL 中通过参数名引用） */
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /** 锁 key 前缀，方便在 Redis 中识别 */
    private static final String LOCK_PREFIX = "lock:";

    /**
     * Lua 脚本：原子性释放锁
     * <p>
     * 逻辑：如果 key 的值等于传入的 value（UUID），则删除 key
     * 这保证了只有锁的持有者才能释放锁，防止误删
     * <p>
     * 参数：KEYS[1] = lockKey, ARGV[1] = lockValue
     */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    /**
     * 环绕通知：拦截带 @DistributeLock 注解的方法
     */
    @Around("@annotation(distributeLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributeLock distributeLock) throws Throwable {
        // 1. 解析 SpEL 表达式，生成实际的锁 key
        String lockKey = LOCK_PREFIX + resolveKey(joinPoint, distributeLock.key());

        // 2. 生成唯一标识（UUID），用于释放锁时验证持有者身份
        String lockValue = UUID.randomUUID().toString().replace("-", "");

        long waitTime = distributeLock.timeUnit().toSeconds(distributeLock.waitTime());
        long expireTime = distributeLock.timeUnit().toSeconds(distributeLock.expireTime());

        // 3. 尝试获取锁（自旋等待，最多等待 waitTime 秒）
        boolean acquired = tryLock(lockKey, lockValue, waitTime, expireTime);
        if (!acquired) {
            throw new SystemException("获取分布式锁失败，请稍后重试: " + lockKey);
        }

        log.debug("获取分布式锁成功: {}", lockKey);

        try {
            // 4. 获取锁成功，执行原方法
            return joinPoint.proceed();
        } finally {
            // 5. 无论成功还是异常，都要释放锁
            unlock(lockKey, lockValue);
            log.debug("释放分布式锁: {}", lockKey);
        }
    }

    /**
     * 尝试获取锁（带自旋等待）
     * <p>
     * 如果锁被其他线程持有，会循环等待（每 100ms 重试一次）
     * 直到获取成功或超过 waitTime
     */
    private boolean tryLock(String lockKey, String lockValue, long waitTime, long expireTime) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitTime * 1000;
        while (System.currentTimeMillis() < deadline) {
            // SETNX：如果 key 不存在则设置成功（获取锁），同时设置过期时间防止死锁
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, expireTime, java.util.concurrent.TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(success)) {
                return true;
            }
            // 锁被占用，等待 100ms 后重试
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * 释放锁（使用 Lua 脚本保证原子性）
     * <p>
     * 为什么需要 Lua 脚本？
     * - 如果用 GET + 判断 + DEL 三步操作，中间可能被其他线程打断
     * - 例如：线程 A 执行 GET 后锁过期了，线程 B 拿到了锁，
     *   此时线程 A 执行 DEL 就会误删线程 B 的锁
     * - Lua 脚本在 Redis 中是原子执行的，不会被其他命令打断
     */
    private void unlock(String lockKey, String lockValue) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        redisTemplate.execute(script, Collections.singletonList(lockKey), lockValue);
    }

    /**
     * 解析 SpEL 表达式，生成实际的锁 key
     * <p>
     * 例如：key = "'merge:' + #identifier"，方法参数 identifier = "abc123"
     * 解析结果："merge:abc123"
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 创建 SpEL 上下文，将方法参数注入（如 #identifier -> 参数值）
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), NAME_DISCOVERER);

        // 解析 SpEL 表达式
        return PARSER.parseExpression(keyExpression).getValue(context, String.class);
    }
}
