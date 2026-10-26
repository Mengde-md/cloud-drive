package com.mengde.clouddrive.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 结构化访问日志、traceId 与请求耗时（Day22 单体可观测性）。
 *
 * <p>为什么要 traceId：一次请求会打出多条日志（业务日志、警告、异常栈），
 * 在并发环境里它们会和其他请求的日志交错。没有 traceId 就无法把它们归拢到一次请求上，
 * 排障时只能靠时间戳猜。日志格式里的 {@code [%X{traceId}]} 就是从 MDC 取这个值。</p>
 *
 * <p>为什么 Order 要设成最高：它必须在鉴权等其它过滤器之前进入，
 * 否则 401/403 这类在过滤链里就被拒绝的请求不会被记录——
 * 而这些恰恰是排查安全问题时最需要看到的记录。</p>
 *
 * <p>为什么 MDC.clear() 必须放在 finally：线程池会复用线程，
 * 不清理就会把上一次请求的 traceId 带到下一个请求的日志里，
 * 得到的日志不仅无用，而且会误导排障方向。</p>
 *
 * <p>慢请求单独 warn：耗时超过阈值的请求要能一眼从日志里筛出来，
 * 这是找慢接口、慢 SQL 的第一入口。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final String TRACE_ID = "traceId";
    private static final String TRACE_HEADER = "X-Request-Id";
    private static final long SLOW_REQUEST_MILLIS = 1000L;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 复用上游传来的请求 id，便于跨系统串联；没有就自己生成一个。
        String incoming = request.getHeader(TRACE_HEADER);
        String traceId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : incoming;
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMillis >= SLOW_REQUEST_MILLIS) {
                log.warn("slow request method={} path={} status={} elapsedMs={}",
                        request.getMethod(), request.getRequestURI(),
                        response.getStatus(), elapsedMillis);
            } else {
                log.info("request method={} path={} status={} elapsedMs={}",
                        request.getMethod(), request.getRequestURI(),
                        response.getStatus(), elapsedMillis);
            }
            MDC.clear();
        }
    }
}
