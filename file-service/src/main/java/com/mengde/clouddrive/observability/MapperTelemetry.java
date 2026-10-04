package com.mengde.clouddrive.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
public class MapperTelemetry implements Interceptor {
    private final MeterRegistry meters;
    private final long slowMs;

    public MapperTelemetry(MeterRegistry meters,
            @Value("${clouddrive.observability.slow-mapper-ms:200}") long slowMs) {
        this.meters = meters;
        this.slowMs = slowMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
        long start = System.nanoTime();
        String outcome = "ERROR";
        try {
            Object result = invocation.proceed();
            outcome = "SUCCESS";
            return result;
        } finally {
            long elapsed = System.nanoTime() - start;
            meters.timer("clouddrive.mapper.calls", "statement", statement, "outcome", outcome)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            if (elapsed / 1_000_000 >= slowMs || "ERROR".equals(outcome)) {
                // Executor time includes mapping and possible cache/lock/connection waits; it is not server SQL time.
                LoggerFactory.getLogger(MapperTelemetry.class).warn(
                        "event=mapper_call traceId={} statement={} outcome={} slow={} durationMs={}",
                        MDC.get(RequestTraceFilter.KEY), statement, outcome,
                        elapsed / 1_000_000 >= slowMs, elapsed / 1_000_000);
            }
        }
    }
}
