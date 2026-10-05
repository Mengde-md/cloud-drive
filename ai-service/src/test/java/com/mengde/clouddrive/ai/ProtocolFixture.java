package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class ProtocolFixture implements AutoCloseable {
    static final String VALID = "{\"summary\":\"A controlled summary\",\"keywords\":[\"CloudDrive\"]}";
    final AtomicInteger requests = new AtomicInteger();
    volatile JsonNode lastRequest;
    volatile String lastAuthorization;
    volatile CountDownLatch firstChunk = new CountDownLatch(1);
    volatile CountDownLatch continueStream = new CountDownLatch(0);
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final HttpServer server;

    ProtocolFixture() {
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
            lastRequest = json.readTree(exchange.getRequestBody());
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            String text = lastRequest.path("messages").get(1).path("content").asText();
            if (text.equals("upstream-error")) {
                byte[] body = "{\"error\":{\"message\":\"provider-private-canary\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (text.equals("slow")) new CountDownLatch(1).await(4, TimeUnit.SECONDS);
            String content = text.contains("invalid") ? "{\"action\":\"delete\"}" : VALID;
            String finish = text.contains("truncated") ? "length" : "stop";
            Map<String, Object> usage = Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150);
            if (!lastRequest.path("stream").asBoolean()) {
                Map<String, Object> response = new java.util.HashMap<>(Map.of("id", "fixture-sync", "model", "deepseek-flash",
                        "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content), "finish_reason", finish))));
                if (!text.equals("usage-missing")) response.put("usage", usage);
                byte[] body = json.writeValueAsBytes(response);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            int half = content.length() / 2;
            chunk(exchange, content.substring(0, half), null, null);
            firstChunk.countDown();
            if (text.equals("stream-slow")) new CountDownLatch(1).await(4, TimeUnit.SECONDS);
            continueStream.await(4, TimeUnit.SECONDS);
            chunk(exchange, content.substring(half), null, null);
            chunk(exchange, "", finish, null);
            if (!text.equals("stream-usage-missing")) chunk(exchange, null, null, usage);
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* Cancellation deliberately closes the fixture socket. */ }
    }

    private void chunk(HttpExchange exchange, String content, String finish, Map<String, Object> usage) throws IOException {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("id", "fixture-stream", "model", "deepseek-flash",
                "choices", List.of()));
        if (content != null) {
            Map<String, Object> choice = new java.util.HashMap<>(Map.of("index", 0, "delta", Map.of("content", content)));
            if (finish != null) choice.put("finish_reason", finish);
            body.put("choices", List.of(choice));
        }
        if (usage != null) body.put("usage", usage);
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(body) + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
