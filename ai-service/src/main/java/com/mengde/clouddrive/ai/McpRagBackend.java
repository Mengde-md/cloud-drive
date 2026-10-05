package com.mengde.clouddrive.ai;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class McpRagBackend {
    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public McpRagBackend(String base, String token) {
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
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (args.name().equals(AgentToolCatalog.LIST)) request.GET();
        else request.POST(HttpRequest.BodyPublishers.ofString(AgentToolCatalog.write(java.util.Map.of("question", args.query()))));
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 401 || response.statusCode() == 403) throw new AiFailure(403, "MCP_BACKEND_UNAUTHORIZED");
                if (response.statusCode() != 200) throw new AiFailure(502, "MCP_BACKEND_FAILED");
                byte[] bytes = body.readNBytes(16001);
                if (bytes.length > 16000) throw new AiFailure(502, "MCP_BACKEND_OUTPUT_INVALID");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (AiFailure failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AiFailure(503, "MCP_INTERRUPTED"); }
        catch (Exception failure) { throw new AiFailure(502, "MCP_BACKEND_FAILED"); }
    }
    @Override public String toString() { return "McpRagBackend[loopback=true, token=REDACTED]"; }
}
