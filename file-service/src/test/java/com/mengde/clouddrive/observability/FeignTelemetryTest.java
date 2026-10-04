package com.mengde.clouddrive.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import feign.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class FeignTelemetryTest {
    public interface Probe { @RequestLine("GET /private-url?passcode=private-passcode") String call(); }
    private HttpServer server;
    private ExecutorService pool;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<String> trace = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delay;
    private final String id = "abcdef1234567890abcdef1234567890";
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FeignTelemetry.class);

    @BeforeEach void setup() throws Exception {
        captured.start(); logger.addAppender(captured);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/", exchange -> {
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = "private-body".getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start(); MDC.put("traceId", id);
    }
    @AfterEach void close() {
        server.stop(0); pool.shutdownNow(); MDC.clear(); meters.close();
        logger.detachAppender(captured); captured.stop();
    }
    private Probe client(int timeout) {
        var telemetry = new FeignTelemetry(meters, 20);
        return Feign.builder().addCapability(telemetry).requestInterceptor(telemetry.traceHeader())
                .retryer(Retryer.NEVER_RETRY).decoder(new feign.codec.StringDecoder())
                .options(new Request.Options(500, TimeUnit.MILLISECONDS, timeout, TimeUnit.MILLISECONDS, false))
                .target(Probe.class, "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @Test void slowDecodedCallPropagatesTraceAndRecordsOnlyCodeDefinedTags() {
        delay = 60;
        assertThat(client(2000).call()).isEqualTo("private-body");
        assertThat(trace.get()).isEqualTo(id);
        assertThat(meters.get("clouddrive.remote.calls").tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
        var event = captured.list.get(0).getFormattedMessage();
        assertThat(event).contains("traceId=" + id, "slow=true", "operation=call")
                .doesNotContain("private-url", "private-passcode", "private-body");
        assertThat(meters.getMeters().get(0).getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactlyInAnyOrder("client", "operation", "outcome");
    }
    @Test void conflictIsObservedWithoutChangingItsExceptionOrRetryPolicy() {
        status = 409;
        assertThatThrownBy(() -> client(2000).call()).isInstanceOf(FeignException.Conflict.class);
        assertThat(meters.get("clouddrive.remote.calls").tag("outcome", "REJECTED").timer().count()).isEqualTo(1);
        assertThat(captured.list).hasSize(1);
    }
    @Test void readTimeoutIsAnErrorAndASecondAttemptRetainsSameTrace() {
        delay = 200;
        var client = client(40);
        for (int i = 0; i < 2; i++) assertThatThrownBy(client::call).isInstanceOf(RetryableException.class);
        assertThat(trace.get()).isEqualTo(id);
        assertThat(meters.get("clouddrive.remote.calls").tag("outcome", "ERROR").timer().count()).isEqualTo(2);
        assertThat(captured.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("traceId=" + id));
    }
    @Test void interceptorReplacesExistingHeaderAndDoesNotReuseThePreviousRequest() {
        var interceptor = new FeignTelemetry(meters, 20).traceHeader();
        var template = new RequestTemplate(); template.header("X-Trace-Id", "forged");
        interceptor.apply(template);
        assertThat(template.headers().get("X-Trace-Id")).containsExactly(id);
        MDC.clear(); interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKey("X-Trace-Id");
    }
}
