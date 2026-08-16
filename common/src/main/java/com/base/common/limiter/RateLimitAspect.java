package com.base.common.limiter;

import com.base.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流 AOP 切面 —— 拦截 @RateLimit 注解，实现声明式限流
 *
 * 【面试知识点 - AOP 切面编程】
 * 1. @Around 环绕通知：
 *    - 在目标方法执行前后都有机会介入
 *    - ProceedingJoinPoint.proceed() 执行原方法
 *    - 可以在 proceed() 之前做前置检查（限流判断）
 *    - 可以在 proceed() 之后做后置处理（日志记录等）
 *
 * 2. SpEL 解析流程（与 DistributeLockAspect 相同的设计模式）：
 *    - MethodBasedEvaluationContext：将方法参数注入 SpEL 上下文
 *    - DefaultParameterNameDiscoverer：通过反射获取方法参数名
 *    - SpelExpressionParser：解析 SpEL 表达式
 *
 * 3. 与 DistributeLockAspect 的设计对比：
 *    - DistributeLockAspect：获取锁失败 → 自旋等待 → 超时抛 SystemException
 *    - RateLimitAspect：获取令牌失败 → 立即抛 BusinessException（不等待）
 *    - 两者都使用了 SpEL 解析 key，保持了一致的设计风格
 *
 * 4. 切面执行顺序（如果有多个切面拦截同一方法）：
 *    - 默认按 Bean 名称的字母顺序执行
 *    - 可以用 @Order 注解控制顺序
 *    - 建议：限流切面应该在事务切面之前执行（避免被限流的请求还开了事务）
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /**
     * 注入限流器实现（依赖接口而非具体实现，方便将来替换）
     */
    private final RateLimiter rateLimiter;

    /** SpEL 表达式解析器（线程安全，可以作为静态常量复用） */
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /**
     * 方法参数名发现器
     *
     * 【为什么需要它？】
     * Java 编译后参数名信息默认不保留（除非编译时加 -parameters 参数）
     * DefaultParameterNameDiscoverer 通过读取字节码中的 LocalVariableTable 获取参数名
     * 这样 SpEL 中才能通过 #userId 这样的表达式引用方法参数
     */
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * 环绕通知：拦截所有带 @RateLimit 注解的方法
     *
     * 【切入点表达式解析】
     * @annotation(rateLimit) 表示拦截所有标注了 @RateLimit 的方法
     * 参数 rateLimit 会自动绑定为注解实例，方便读取注解的属性值
     *
     * @param joinPoint  被拦截的方法（ProceedingJoinPoint 是 AOP 的核心接口）
     * @param rateLimit  注解实例（Spring AOP 自动注入）
     * @return 原方法的返回值
     * @throws Throwable 原方法可能抛出的异常
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 1. 解析限流 key
        String key = resolveKey(joinPoint, rateLimit);

        // 2. 尝试获取令牌（调用 Redisson 令牌桶）
        Boolean acquired = rateLimiter.tryAcquire(key, rateLimit.limit(), rateLimit.windowSize());

        // 3. 令牌获取失败 → 被限流，抛出业务异常
        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("限流拦截: method={}, key={}, limit={}/{}s",
                    joinPoint.getSignature().toShortString(),
                    key, rateLimit.limit(), rateLimit.windowSize());
            // 抛出 BusinessException，GlobalExceptionHandler 会统一处理并返回 JSON 错误响应
            throw new BusinessException(rateLimit.message());
        }

        // 4. 令牌获取成功 → 放行，执行原方法
        return joinPoint.proceed();
    }

    /**
     * 解析限流 key
     *
     * 【解析策略】
     * 1. 如果注解中指定了 key（SpEL 表达式），则解析表达式
     * 2. 如果注解中 key 为空（默认值），则使用 "类名:方法名" 作为默认 key
     *
     * @param joinPoint 被拦截的方法
     * @param rateLimit 注解实例
     * @return 解析后的限流 key
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        // 如果注解中没有指定 key，使用默认的 "类名:方法名"
        if (rateLimit.key().isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            return signature.getDeclaringType().getSimpleName() + ":" + signature.getMethod().getName();
        }

        // 解析 SpEL 表达式（与 DistributeLockAspect 中的 resolveKey 逻辑一致）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 创建 SpEL 上下文，将方法参数注入
        // 例如方法签名是 upload(Long userId, MultipartFile file)
        // 那么 SpEL 中 #userId 会被替换为实际的 userId 参数值
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), NAME_DISCOVERER);

        // 解析 SpEL 表达式，返回 String 类型的结果
        return PARSER.parseExpression(rateLimit.key()).getValue(context, String.class);
    }
}
