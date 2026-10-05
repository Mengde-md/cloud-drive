package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class AgentProtocolFixture implements AutoCloseable {
    enum Mode { VALID, NAVIGATE, UNKNOWN, BAD_ARGS, DUPLICATE_ARGS, MULTI, LOOP, FORGED, REFUSED, NO_USAGE, ERROR, HOLD_FIRST, HOLD_FINAL }
    final AtomicInteger requests = new AtomicInteger();
    volatile Mode mode = Mode.VALID;
    volatile JsonNode lastRequest;
    volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(0);
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final HttpServer server;
    AgentProtocolFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    int sequence = requests.incrementAndGet();
                    JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes()); lastRequest = request;
                    int results = 0; JsonNode evidence = null;
                    for (JsonNode message : request.path("messages")) if (message.path("role").asText().equals("tool")) {
                        results++; JsonNode value = json.readTree(message.path("content").asText());
                        if (value.has("hits")) evidence = value;
                    }
                    Mode behavior = mode;
                    if (behavior == Mode.ERROR) {
                        byte[] failure = "private-agent-provider-canary".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, failure.length); exchange.getResponseBody().write(failure); return;
                    }
                    if ((behavior == Mode.HOLD_FIRST && results == 0) || (behavior == Mode.HOLD_FINAL && evidence != null)) {
                        entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    boolean tool = behavior != Mode.REFUSED && (evidence == null || behavior == Mode.LOOP);
                    Map<String, Object> message;
                    if (tool) {
                        String name = behavior == Mode.UNKNOWN ? "delete_document"
                                : behavior == Mode.LOOP || (behavior == Mode.NAVIGATE && results == 0) ? AgentToolCatalog.LIST : AgentToolCatalog.SEARCH;
                        String args = name.equals(AgentToolCatalog.LIST) || name.equals("delete_document") ? "{}"
                                : AgentToolCatalog.write(Map.of("query", "How long are deleted files kept in the recycle bin?"));
                        if (behavior == Mode.BAD_ARGS) args = "{\"query\":\"test\",\"ownerId\":8}";
                        if (behavior == Mode.DUPLICATE_ARGS) args = "{\"query\":\"first\",\"query\":\"second\"}";
                        Map<String, Object> call = Map.of("id", "call_" + sequence, "type", "function", "function", Map.of("name", name, "arguments", args));
                        List<Map<String, Object>> calls = new ArrayList<>(List.of(call));
                        if (behavior == Mode.MULTI) calls.add(Map.of("id", "call_other", "type", "function", "function", Map.of("name", name, "arguments", args)));
                        message = Map.of("role", "assistant", "content", "", "tool_calls", calls);
                    } else {
                        Object answer = Map.of("status", "REFUSED", "answer", "", "citations", List.of());
                        if (evidence != null && !evidence.path("hits").isEmpty()) {
                            JsonNode hit = evidence.path("hits").get(0);
                            String quote = hit.path("text").asText(); quote = quote.substring(0, Math.min(120, quote.length()));
                            answer = Map.of("status", "ANSWERED", "answer", quote, "citations", List.of(Map.of("id",
                                    behavior == Mode.FORGED ? "other-owner:0" : hit.path("id").asText(), "quote", quote)));
                        }
                        message = Map.of("role", "assistant", "content", json.writeValueAsString(answer));
                    }
                    Map<String, Object> response = new LinkedHashMap<>(Map.of("id", "fixture-" + sequence, "object", "chat.completion",
                            "created", 1, "model", "deepseek-flash", "choices", List.of(Map.of("index", 0,
                            "finish_reason", tool ? "tool_calls" : "stop", "message", message))));
                    if (behavior != Mode.NO_USAGE) response.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                    byte[] bytes = json.writeValueAsBytes(response); exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException ignored) { }
                finally { exchange.close(); }
            });
            server.start();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    @Override public void close() { release.countDown(); server.stop(0); workers.shutdownNow(); }
}
