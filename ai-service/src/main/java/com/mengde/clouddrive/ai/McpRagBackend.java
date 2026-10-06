package com.mengde.clouddrive.ai;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpRagBackend {
    private final String base;
    private final String token;
    private final Duration deadline;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public McpRagBackend(String base, String token) {
        this(base, token, Duration.ofSeconds(5));
    }
    McpRagBackend(String base, String token, Duration deadline) {
        if (deadline == null || deadline.toMillis() < 1 || deadline.toMillis() > 5000) throw new IllegalArgumentException("Invalid MCP deadline");
        this.deadline = deadline;
        URI uri;
        try { uri = URI.create(base); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("MCP requires a loopback AI endpoint"); }
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("MCP requires a loopback AI endpoint");
        }
        if (token == null || token.length() > 4096 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("MCP requires an explicit JWT; it is verified by the AI service");
        }
        this.base = "http://127.0.0.1:" + uri.getPort();
        this.token = token;
    }
    public String execute(String name, String arguments) {
        AgentToolCatalog.Arguments args = AgentToolCatalog.validate(name, arguments);
        String path = args.name().equals(AgentToolCatalog.LIST) ? "/api/ai/rag/documents" : "/api/ai/rag/search";
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(deadline)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (args.name().equals(AgentToolCatalog.LIST)) request.GET();
        else request.POST(HttpRequest.BodyPublishers.ofString(AgentToolCatalog.write(java.util.Map.of("question", args.query()))));
        AtomicReference<McpBoundedBody> body = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> response = http.sendAsync(request.build(), info -> {
            McpBoundedBody subscriber = new McpBoundedBody(); body.set(subscriber);
            if (finished.get()) subscriber.fail(new AiFailure(503, "MCP_INTERRUPTED"));
            else if (info.statusCode() == 401 || info.statusCode() == 403) subscriber.fail(new AiFailure(403, "MCP_BACKEND_UNAUTHORIZED"));
            else if (info.statusCode() != 200) subscriber.fail(new AiFailure(502, "MCP_BACKEND_FAILED"));
            return subscriber;
        });
        try {
            byte[] bytes = response.get(deadline.toMillis(), TimeUnit.MILLISECONDS).body();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (AiFailure failure) { throw failure; }
        catch (TimeoutException failure) { throw new AiFailure(504, "MCP_BACKEND_TIMEOUT"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AiFailure(503, "MCP_INTERRUPTED"); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof AiFailure rejected) throw rejected;
            if (cause instanceof HttpTimeoutException) throw new AiFailure(504, "MCP_BACKEND_TIMEOUT");
            throw new AiFailure(502, "MCP_BACKEND_FAILED");
        }
        catch (java.nio.charset.CharacterCodingException failure) { throw new AiFailure(502, "MCP_BACKEND_OUTPUT_INVALID"); }
        catch (Exception failure) { throw new AiFailure(502, "MCP_BACKEND_FAILED"); }
        finally {
            finished.set(true);
            response.cancel(true);
            McpBoundedBody subscriber = body.get();
            if (subscriber != null) subscriber.fail(new AiFailure(503, "MCP_INTERRUPTED"));
        }
    }
    @Override public String toString() { return "McpRagBackend[loopback=true, token=REDACTED]"; }
}
