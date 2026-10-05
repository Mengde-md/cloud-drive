package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class RagProtocolFixture implements AutoCloseable {
    enum Mode { VALID, FORGED_ID, FORGED_QUOTE, REFUSED, NO_USAGE, TRUNCATED, ERROR, HOLD }
    final AtomicInteger requests = new AtomicInteger();
    volatile Mode mode = Mode.VALID;
    volatile JsonNode lastRequest, lastPrompt;
    volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final HttpServer server;

    RagProtocolFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", this::reply);
            server.start();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

    private void reply(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            Mode requestedMode = mode;
            JsonNode request = json.readTree(exchange.getRequestBody());
            JsonNode prompt = json.readTree(request.path("messages").get(1).path("content").asText());
            lastRequest = request; lastPrompt = prompt;
            entered.countDown();
            if (requestedMode == Mode.HOLD) release.await(4, TimeUnit.SECONDS);
            if (requestedMode == Mode.ERROR) {
                byte[] body = json.writeValueAsBytes(Map.of("error", Map.of("message", "private-rag-provider-canary")));
                exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body); return;
            }
            JsonNode evidence = prompt.path("evidence").get(0);
            String id = requestedMode == Mode.FORGED_ID ? "another-owner:0" : evidence.path("id").asText();
            String quote = requestedMode == Mode.FORGED_QUOTE ? "The document says unlimited retention."
                    : evidence.path("text").asText();
            Object output = requestedMode == Mode.REFUSED ? Map.of("status", "REFUSED", "answer", "", "citations", List.of())
                    : Map.of("status", "ANSWERED", "answer", "Deleted files are retained for thirty days.",
                            "citations", List.of(Map.of("id", id, "quote", quote)));
            Map<String, Object> response = new HashMap<>(Map.of("id", "rag-protocol-fixture", "model", "deepseek-flash",
                    "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", json.writeValueAsString(output)),
                            "finish_reason", requestedMode == Mode.TRUNCATED ? "length" : "stop"))));
            if (requestedMode != Mode.NO_USAGE) response.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
            byte[] body = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* The timeout contract deliberately closes fixture connections. */ }
    }
    @Override public void close() { release.countDown(); server.stop(0); workers.shutdownNow(); }
}
