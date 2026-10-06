package com.mengde.clouddrive.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class McpFailureContractTest {
    private static final String TOKEN = "test_only.public.signature";
    @Test void slowBodyAfterHeadersHasWholeResponseDeadline() throws Exception {
        try (var server = new Backend(200, null)) {
            long start = System.nanoTime();
            var error = assertThrows(AiFailure.class, () -> server.backend(500).execute(AgentToolCatalog.LIST, "{}"));
            assertTrue(server.entered.await(1, TimeUnit.SECONDS)); assertEquals(504, error.status());
            assertEquals("MCP_BACKEND_TIMEOUT", error.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
        }
    }
    @Test void unauthorizedSlowErrorBodyIsRejectedAtHeaders() throws Exception {
        try (var server = new Backend(401, null)) {
            var error = assertThrows(AiFailure.class, () -> server.backend(2000).execute(AgentToolCatalog.LIST, "{}"));
            assertEquals(403, error.status()); assertEquals("MCP_BACKEND_UNAUTHORIZED", error.getMessage());
            assertEquals(1, server.release.getCount());
        }
    }
    @Test void upstreamFailureBodyCannotEscape() throws Exception {
        try (var server = new Backend(500, "private-body-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var error = assertThrows(AiFailure.class, () -> server.backend(2000).execute(AgentToolCatalog.LIST, "{}"));
            assertEquals("MCP_BACKEND_FAILED", error.getMessage()); assertFalse(error.toString().contains("private-body-canary"));
        }
    }
    @Test void malformedUtf8IsNotSilentlyReplaced() throws Exception {
        try (var server = new Backend(200, new byte[]{(byte) 0xc3, 0x28})) {
            var error = assertThrows(AiFailure.class, () -> server.backend(2000).execute(AgentToolCatalog.LIST, "{}"));
            assertEquals("MCP_BACKEND_OUTPUT_INVALID", error.getMessage());
        }
    }
    @Test void interruptStopsCallerAndPreservesThreadFlag() throws Exception {
        try (var server = new Backend(200, null)) {
            var result = new CompletableFuture<AiFailure>(); var interrupted = new AtomicBoolean();
            var worker = new Thread(() -> {
                try { server.backend(5000).execute(AgentToolCatalog.LIST, "{}"); result.completeExceptionally(new AssertionError("expected interruption")); }
                catch (AiFailure failure) { interrupted.set(Thread.currentThread().isInterrupted()); result.complete(failure); }
            }, "day33-mcp-interruption");
            try {
                worker.start(); assertTrue(server.entered.await(2, TimeUnit.SECONDS)); worker.interrupt();
                assertEquals("MCP_INTERRUPTED", result.get(2, TimeUnit.SECONDS).getMessage()); assertTrue(interrupted.get());
            } finally { worker.interrupt(); worker.join(2000); assertFalse(worker.isAlive()); }
        }
    }
    @Test void shortenedTestDeadlineCannotExceedProductionCeiling() {
        for (var duration : new Duration[]{Duration.ZERO, Duration.ofMillis(5001)})
            assertThrows(IllegalArgumentException.class, () -> new McpRagBackend("http://127.0.0.1:8084", TOKEN, duration));
    }
    private static final class Backend implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ExecutorService workers = Executors.newCachedThreadPool(); final HttpServer server;
        Backend(int status, byte[] bytes) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    exchange.sendResponseHeaders(status, 0); exchange.getResponseBody().flush(); entered.countDown();
                    if (bytes == null) release.await(5, TimeUnit.SECONDS); else exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException ignored) { }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); server.start();
        }
        McpRagBackend backend(int millis) { return new McpRagBackend("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, Duration.ofMillis(millis)); }
        public void close() { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }
}
