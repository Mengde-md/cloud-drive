package com.mengde.clouddrive.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.*;
import org.junit.jupiter.api.*;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FeignQuotaContractTest {
    private HttpServer server;
    private ExecutorService pool;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile long delay;
    private final String id = java.util.UUID.randomUUID().toString();
    private UserQuotaClient client;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/internal/users/9/quota/", exchange -> {
            requests.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastToken.set(exchange.getRequestHeaders().getFirst("X-CloudDrive-Internal-Token"));
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = id.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(responseStatus, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
        client = Feign.builder().contract(new SpringMvcContract())
                .encoder((value, type, template) -> { try { template.body(json.writeValueAsString(value)); } catch (Exception e) { throw new IllegalStateException(e); } })
                .decoder(new feign.codec.StringDecoder()).retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(100, TimeUnit.MILLISECONDS, 100, TimeUnit.MILLISECONDS, false))
                .target(UserQuotaClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void close() { server.stop(0); pool.shutdownNow(); }
    private QuotaGateway gateway() {
        var auth = mock(AuthIdentityClient.class);
        when(auth.identity(eq(9L), anyString())).thenReturn(new AuthIdentityClient.Identity(9, "alice", "USER"));
        return new QuotaGateway(auth, client, "day26-test-only-service-token-over-32-bytes");
    }
    @Test void actualHttpContractUsesStableIdAndDedicatedHeader() throws Exception {
        gateway().reserve(new QuotaGateway.Reservation(9, id), 12, 5);
        assertThat(json.readTree(lastBody.get()).path("reservationId").asText()).isEqualTo(id);
        assertThat(json.readTree(lastBody.get()).path("amount").asLong()).isEqualTo(12);
        assertThat(lastToken.get()).startsWith("day26-test-only");
        assertThat(requests).hasValue(1);
    }
    @Test void businessConflictIsNotRetried() {
        responseStatus = 409;
        assertThatThrownBy(() -> gateway().reserve(new QuotaGateway.Reservation(9, id), 12, 5)).isInstanceOf(QuotaGateway.QuotaRejectedException.class);
        assertThat(requests).hasValue(1);
    }
    @Test void readTimeoutHasExactlyTwoRetriesAndPreservesId() throws Exception {
        delay = 600;
        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway().reserve(new QuotaGateway.Reservation(9, id), 12, 5)).isInstanceOf(QuotaGateway.QuotaUnavailableException.class);
        assertThat(requests).hasValue(3);
        assertThat(json.readTree(lastBody.get()).path("reservationId").asText()).isEqualTo(id);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
    }
}
