package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class Day32PackagedProbe {
    private static final ObjectMapper json = new ObjectMapper();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String TEXT = "Deleted files are moved into the recycle bin for thirty days. Users can restore deleted files during this retention period.";
    private final Path repo, output;
    private final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    private final List<Process> owned = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private final AgentProtocolFixture fixture = new AgentProtocolFixture();
    private Day32PackagedProbe(Path repo, Path output) { this.repo = repo; this.output = output; }
    private Process start(String name, String jar, boolean agent, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExecutable, "-jar", repo.resolve(jar).toString())); command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(repo.toFile()).redirectOutput(output.resolve(name + ".out.log").toFile())
                .redirectError(output.resolve(name + ".err.log").toFile());
        var env = builder.environment(); env.put("CLOUDDRIVE_JWT_SECRET", secret); env.put("CLOUDDRIVE_AI_ENABLED", "true");
        env.put("CLOUDDRIVE_RAG_ENABLED", "true"); env.put("CLOUDDRIVE_AGENT_ENABLED", String.valueOf(agent));
        env.put("CLOUDDRIVE_AI_API_KEY", "day32-packaged-protocol-test-only-key"); env.put("CLOUDDRIVE_AI_URI", "http://127.0.0.1:18184");
        env.remove("SPRING_PROFILES_ACTIVE"); Process process = builder.start(); owned.add(process); return process;
    }
    private void ready(int port, Process process) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (!process.isAlive()) throw new IllegalStateException("Owned package exited: " + port);
            try { if (http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return; }
            catch (java.io.IOException ignored) { }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Owned readiness timeout: " + port);
    }
    private HttpResponse<String> request(int port, String method, String path, Object body, Long owner) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai" + path)).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json");
        if (owner != null) builder.header("Authorization", "Bearer " + AgentTestJwt.token(owner, "USER", secret));
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(AgentToolCatalog.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void check(String name, boolean ok) {
        if (!ok) throw new IllegalStateException("FAIL: " + name); checks.add(name); System.out.println("PASS: " + name);
    }
    private Map<String, Object> ask() { return Map.of("question", "How long are deleted files kept in the recycle bin?", "allowExternalProcessing", true); }
    private void run() throws Exception {
        for (int port : List.of(18184, 19100)) try (var socket = new java.net.ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
        String jar = "ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar";
        String[] settings = {"--server.port=18184", "--clouddrive.ai.protocol-fixture=true", "--clouddrive.ai.base-url=" + fixture.baseUrl()};
        Process disabled = start("ai-disabled", jar, false, settings); ready(18184, disabled);
        check("packaged agent defaults disabled without provider call", request(18184, "POST", "/agent/ask", ask(), 7L).statusCode() == 503 && fixture.requests.get() == 0);
        disabled.destroy(); if (!disabled.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) disabled.destroyForcibly().waitFor();
        Process ai = start("ai", jar, true, settings); ready(18184, ai);
        Process gateway = start("gateway", "gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar", true,
                "--server.port=19100", "--spring.profiles.active=prod", "--clouddrive.gateway.ai-uri=http://127.0.0.1:18184",
                "--clouddrive.gateway.auth-uri=http://127.0.0.1:8081", "--clouddrive.gateway.file-uri=http://127.0.0.1:8082");
        ready(19100, gateway); check("real AI and prod gateway JARs healthy", true);
        check("gateway rejects anonymous agent", request(19100, "GET", "/agent/tools", null, null).statusCode() == 401);
        var spoof = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18184/api/ai/agent/tools"))
                .header("X-User-Id", "7").build(), HttpResponse.BodyHandlers.discarding()); check("direct AI rejects spoofed user", spoof.statusCode() == 401);
        check("gateway lists exactly two readonly tool schemas", json.readTree(request(19100, "GET", "/agent/tools", null, 7L).body()).size() == 2);
        var imported = request(19100, "POST", "/rag/documents", Map.of("name", "trash.txt", "contentBase64",
                Base64.getEncoder().encodeToString(TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8))), 7L);
        String document = json.readTree(imported.body()).path("documentId").asText(); check("real ONNX document imported via gateway", imported.statusCode() == 201 && !document.isEmpty());
        var empty = json.readTree(request(19100, "POST", "/agent/ask", ask(), 8L).body());
        check("different tenant refuses without provider call", empty.path("reason").asText().equals("NO_EVIDENCE") && fixture.requests.get() == 0);
        var answer = json.readTree(request(19100, "POST", "/agent/ask", ask(), 7L).body());
        check("SDK tool result round-trip returns source-owned citation and fixture usage", answer.path("status").asText().equals("ANSWERED")
                && answer.path("modelCalls").asInt() == 2 && answer.path("toolCalls").asInt() == 1
                && answer.path("citations").get(0).path("documentId").asText().equals(document)
                && TEXT.contains(answer.path("citations").get(0).path("quote").asText()) && answer.path("usage").size() == 2
                && answer.path("usage").get(0).path("cost").path("protocolFixture").asBoolean());
        fixture.mode = AgentProtocolFixture.Mode.NAVIGATE;
        var navigation = json.readTree(request(19100, "POST", "/agent/ask", ask(), 7L).body());
        check("list then search completes within three model calls", navigation.path("modelCalls").asInt() == 3 && navigation.path("toolCalls").asInt() == 2);
        fixture.mode = AgentProtocolFixture.Mode.FORGED;
        check("forged citation fails closed", request(19100, "POST", "/agent/ask", ask(), 7L).statusCode() == 502);
        fixture.mode = AgentProtocolFixture.Mode.UNKNOWN;
        check("unlisted delete proposal cannot mutate corpus", request(19100, "POST", "/agent/ask", ask(), 7L).statusCode() == 502
                && json.readTree(request(19100, "GET", "/rag/documents", null, 7L).body()).size() == 1);
        fixture.mode = AgentProtocolFixture.Mode.LOOP; int before = fixture.requests.get();
        var limit = request(19100, "POST", "/agent/ask", ask(), 7L);
        check("third repeated model proposal stops before third tool", limit.statusCode() == 502 && fixture.requests.get() - before == 3
                && json.readTree(limit.body()).path("code").asText().equals("AGENT_LIMIT_EXCEEDED"));
        var transport = new StdioClientTransport(ServerParameters.builder(javaExecutable).args("-jar", repo.resolve(jar).toString(), "--mcp-stdio")
                .env(Map.of("CLOUDDRIVE_MCP_AI_URL", "http://127.0.0.1:18184", "CLOUDDRIVE_MCP_TOKEN", AgentTestJwt.token(7, "USER", secret))).build(), new JacksonMcpJsonMapper(json), 65536);
        transport.setStdErrorHandler(line -> { });
        try (var client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(10)).requestTimeout(Duration.ofSeconds(8)).build()) {
            client.initialize(); check("executable JAR starts real MCP STDIO and discovers two tools", client.listTools().tools().size() == 2
                    && client.getServerInfo().name().equals("clouddrive-readonly-rag"));
            var result = client.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.SEARCH, Map.of("query", ask().get("question"))));
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            check("MCP tool reaches same JWT-scoped live RAG index", !Boolean.TRUE.equals(result.isError())
                    && json.readTree(text).path("hits").get(0).path("documentId").asText().equals(document));
        }
        Files.writeString(output.resolve("checks.json"), AgentToolCatalog.write(checks));
        System.out.println("DAY32 PACKAGED SMOKE PASS: " + checks.size() + "; " + output);
    }
    public static void main(String[] args) throws Exception {
        var probe = new Day32PackagedProbe(Path.of(args[0]), Path.of(args[1]));
        try { probe.run(); }
        finally {
            probe.fixture.close();
            for (Process process : probe.owned) if (process.isAlive()) {
                process.destroy(); if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
            }
        }
    }
}
