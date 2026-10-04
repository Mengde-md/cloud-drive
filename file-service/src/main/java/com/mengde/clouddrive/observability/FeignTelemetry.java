package com.mengde.clouddrive.observability;

import feign.Capability;
import feign.FeignException;
import feign.InvocationHandlerFactory;
import feign.RequestInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class FeignTelemetry implements Capability {
    private final MeterRegistry meters;
    private final long slowMs;

    public FeignTelemetry(MeterRegistry meters,
            @Value("${clouddrive.observability.slow-call-ms:500}") long slowMs) {
        this.meters = meters;
        this.slowMs = slowMs;
    }

    @Bean
    RequestInterceptor traceHeader() {
        return template -> {
            template.removeHeader(RequestTraceFilter.HEADER);
            String trace = MDC.get(RequestTraceFilter.KEY);
            if (trace != null) template.header(RequestTraceFilter.HEADER, RequestTraceFilter.normalize(trace));
        };
    }

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory factory) {
        return (target, dispatch) -> {
            var delegate = factory.create(target, dispatch);
            String client = target.type().getSimpleName();
            return (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return delegate.invoke(proxy, method, args);
                long start = System.nanoTime();
                String outcome = "ERROR";
                try {
                    Object result = delegate.invoke(proxy, method, args);
                    outcome = "SUCCESS";
                    return result;
                } catch (FeignException failure) {
                    outcome = failure.status() == 409 ? "REJECTED" : "ERROR";
                    throw failure;
                } finally {
                    long elapsed = System.nanoTime() - start;
                    meters.timer("clouddrive.remote.calls", "client", client,
                            "operation", method.getName(), "outcome", outcome).record(elapsed, TimeUnit.NANOSECONDS);
                    LoggerFactory.getLogger(FeignTelemetry.class).info(
                            "event=remote_call traceId={} client={} operation={} outcome={} slow={} durationMs={}",
                            MDC.get(RequestTraceFilter.KEY), client, method.getName(), outcome,
                            elapsed / 1_000_000 >= slowMs, elapsed / 1_000_000);
                }
            };
        };
    }
}
