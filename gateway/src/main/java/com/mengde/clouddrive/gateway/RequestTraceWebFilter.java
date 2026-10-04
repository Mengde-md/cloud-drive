package com.mengde.clouddrive.gateway;

import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceWebFilter implements WebFilter {
    public static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            // A correlation identifier is not an identity claim. Never trust the public input.
            String trace = UUID.randomUUID().toString().replace("-", "");
            var traced = exchange.mutate().request(request -> request.headers(headers -> headers.set(HEADER, trace))).build();
            exchange.getResponse().getHeaders().set(HEADER, trace);
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HEADER, trace);
                return Mono.empty();
            });
            long start = System.nanoTime();
            var failed = new AtomicBoolean();
            // Keep correlation in the closure, never in a Reactor event-loop ThreadLocal.
            return Mono.defer(() -> chain.filter(traced))
                    .doOnError(error -> failed.set(true))
                    .doFinally(signal -> LoggerFactory.getLogger(RequestTraceWebFilter.class).info(
                            "event=gateway_request traceId={} status={} failed={} signal={} durationMs={}",
                            trace, exchange.getResponse().getStatusCode() == null ? "UNSET"
                                    : exchange.getResponse().getStatusCode().value(),
                            failed.get(), signal.name(), (System.nanoTime() - start) / 1_000_000));
        });
    }
}
