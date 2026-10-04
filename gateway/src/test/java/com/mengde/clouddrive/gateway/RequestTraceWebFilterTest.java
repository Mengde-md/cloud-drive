package com.mengde.clouddrive.gateway;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class RequestTraceWebFilterTest {
    @Test void replacesPublicInputAndReassertsResponseHeaderAfterReactiveThreadSwitch() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/shares/private/download?passcode=secret")
                .header("X-Trace-Id", "f".repeat(32), "e".repeat(32)));
        var seen = new AtomicReference<String>();
        new RequestTraceWebFilter().filter(exchange, next -> Mono.fromRunnable(() -> {
            var headers = next.getRequest().getHeaders().get("X-Trace-Id");
            assertThat(headers).hasSize(1);
            seen.set(headers.get(0));
            assertThat(seen.get()).matches("[0-9a-f]{32}").isNotEqualTo("f".repeat(32));
            assertThat(MDC.get("traceId")).isNull();
            next.getResponse().getHeaders().set("X-Trace-Id", "bad-downstream");
            next.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        }).subscribeOn(Schedulers.parallel()).then(next.getResponse().setComplete())).block();
        assertThat(exchange.getResponse().getHeaders().get("X-Trace-Id")).containsExactly(seen.get());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void failureIsNotConvertedIntoAuthenticationFailureAndRetainsResponseCorrelation() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        var failure = new IllegalStateException("downstream unavailable");
        assertThatThrownBy(() -> new RequestTraceWebFilter().filter(exchange, next -> Mono.error(failure)).block())
                .isSameAs(failure);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void separateRequestsNeverShareCorrelation() {
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        var second = MockServerWebExchange.from(MockServerHttpRequest.get("/missing"));
        var filter = new RequestTraceWebFilter();
        filter.filter(first, next -> Mono.empty()).block();
        filter.filter(second, next -> Mono.empty()).block();
        assertThat(first.getResponse().getHeaders().getFirst("X-Trace-Id"))
                .isNotEqualTo(second.getResponse().getHeaders().getFirst("X-Trace-Id"));
    }
}
