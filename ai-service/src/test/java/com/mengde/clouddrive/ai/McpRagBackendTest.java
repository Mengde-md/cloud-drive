package com.mengde.clouddrive.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpRagBackendTest {
    private static final String TOKEN = "test_only.public.signature";
    @ParameterizedTest @ValueSource(strings = {"https://127.0.0.1:8084", "http://localhost:8084", "http://example.com:8084",
            "http://127.0.0.1:8084/api/ai", "http://user@127.0.0.1:8084", "http://127.0.0.1:8084?target=x",
            "http://127.0.0.1:8084#fragment", "http://127.0.0.1:65536"})
    void noRemoteDnsRedirectTargetOrUserInfoCanBeConfigured(String url) {
        assertThrows(IllegalArgumentException.class, () -> new McpRagBackend(url, TOKEN));
    }
    @Test void jwtIsMandatoryAndRedactedWithoutPretendingToVerifyItsSignature() {
        assertThrows(IllegalArgumentException.class, () -> new McpRagBackend("http://127.0.0.1:8084", null));
        assertThrows(IllegalArgumentException.class, () -> new McpRagBackend("http://127.0.0.1:8084", "header\ninjection"));
        assertFalse(new McpRagBackend("http://127.0.0.1:8084", TOKEN).toString().contains(TOKEN));
    }
    @Test void allowlistMapsOnlyTwoFixedApiRoutesAndPropagatesBearer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("GET")) assertEquals("/api/ai/rag/documents", exchange.getRequestURI().getPath());
            else {
                assertEquals("/api/ai/rag/search", exchange.getRequestURI().getPath());
                assertEquals("{\"question\":\"query\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var backend = new McpRagBackend("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
            assertEquals("[]", backend.execute(AgentToolCatalog.LIST, "{}"));
            assertEquals("[]", backend.execute(AgentToolCatalog.SEARCH, "{\"query\":\"query\"}"));
            assertThrows(AiFailure.class, () -> backend.execute("delete_document", "{}")); assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
    @Test void authenticationFailureDiscardsBackendBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "private-mcp-backend-canary".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var backend = new McpRagBackend("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
            assertEquals("MCP_BACKEND_UNAUTHORIZED", assertThrows(AiFailure.class,
                    () -> backend.execute(AgentToolCatalog.LIST, "{}")).getMessage());
        } finally { server.stop(0); }
    }
    @Test void redirectIsNotFollowedAndOversizeSuccessIsRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger redirected = new AtomicInteger();
        server.createContext("/api/ai/rag/documents", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/trap");
            exchange.sendResponseHeaders(307, -1); exchange.close();
        });
        server.createContext("/api/ai/rag/search", exchange -> {
            byte[] bytes = "x".repeat(16001).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/trap", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); server.start();
        try {
            var backend = new McpRagBackend("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
            assertThrows(AiFailure.class, () -> backend.execute(AgentToolCatalog.LIST, "{}")); assertEquals(0, redirected.get());
            assertEquals("MCP_BACKEND_OUTPUT_INVALID", assertThrows(AiFailure.class,
                    () -> backend.execute(AgentToolCatalog.SEARCH, "{\"query\":\"query\"}")).getMessage());
        } finally { server.stop(0); }
    }
}
