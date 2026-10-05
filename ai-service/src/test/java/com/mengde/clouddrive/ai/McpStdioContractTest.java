package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AiServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clouddrive.rag.enabled=true", "clouddrive.ai.enabled=false", "clouddrive.ai.api-key=",
                "clouddrive.security.jwt.secret=" + AgentTestJwt.SECRET})
class McpStdioContractTest {
    private static final ObjectMapper json = new ObjectMapper();
    private static final AtomicLong owners = new AtomicLong(1200);
    @LocalServerPort int port;
    private long seed() throws Exception {
        long owner = owners.incrementAndGet();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/rag/documents"))
                .header("Authorization", "Bearer " + AgentTestJwt.token(owner, "USER")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(AgentToolCatalog.write(Map.of("name", "trash.txt", "contentBase64",
                        RagDocumentParserTest.encoded(RagIndexTest.TRASH))))).build();
        assertEquals(201, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()); return owner;
    }
    private McpSyncClient connect(long owner, String role, List<String> errors) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var parameters = ServerParameters.builder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString())
                .args("-cp", classpath, CloudDriveMcpServer.class.getName())
                .env(Map.of("CLOUDDRIVE_MCP_AI_URL", "http://127.0.0.1:" + port,
                        "CLOUDDRIVE_MCP_TOKEN", AgentTestJwt.token(owner, role))).build();
        var transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(json), 65536);
        transport.setStdErrorHandler(errors::add);
        var client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(8))
                .requestTimeout(Duration.ofSeconds(8)).clientInfo(new McpSchema.Implementation("day32-real-stdio-test", "1")).build();
        try { client.initialize(); return client; }
        catch (RuntimeException failure) { client.close(); throw failure; }
    }
    private String content(McpSchema.CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), result.toString());
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
    @Test void actualSdkStdioHandshakeDiscoveryAndSearchReachJwtProtectedRealIndex() throws Exception {
        long owner = seed(); List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var client = connect(owner, "USER", errors)) {
            assertEquals("clouddrive-readonly-rag", client.getServerInfo().name());
            var tools = client.listTools().tools(); assertEquals(2, tools.size());
            assertTrue(tools.stream().allMatch(t -> Boolean.TRUE.equals(t.annotations().readOnlyHint())));
            assertNull(client.getServerCapabilities().resources()); assertNull(client.getServerCapabilities().prompts());
            assertEquals(1, json.readTree(content(client.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.LIST, Map.of())))).size());
            var found = json.readTree(content(client.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.SEARCH,
                    Map.of("query", "How long are deleted files kept in the recycle bin?")))));
            assertFalse(found.path("hits").isEmpty()); assertEquals("trash.txt", found.path("hits").get(0).path("name").asText());
        }
        assertFalse(String.join("\n", errors).contains(RagIndexTest.TRASH));
        assertFalse(String.join("\n", errors).contains("Bearer "));
    }
    @Test void serverSchemaRejectsOwnerSpoofAndHasNoDeleteTool() throws Exception {
        long owner = seed();
        try (var client = connect(owner, "USER", new java.util.concurrent.CopyOnWriteArrayList<>())) {
            var rejected = client.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.SEARCH, Map.of("query", "test", "ownerId", 8)));
            assertTrue(Boolean.TRUE.equals(rejected.isError()));
            assertThrows(RuntimeException.class, () -> client.callTool(new McpSchema.CallToolRequest("delete_document", Map.of())));
            assertEquals(1, json.readTree(content(client.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.LIST, Map.of())))).size());
        }
    }
    @Test void differentUserGetsEmptyCorpusAndGuestCannotCallBackend() throws Exception {
        long owner = seed();
        try (var other = connect(owner + 10000, "USER", new java.util.concurrent.CopyOnWriteArrayList<>())) {
            assertTrue(json.readTree(content(other.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.LIST, Map.of())))).isEmpty());
            var result = json.readTree(content(other.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.SEARCH, Map.of("query", "trash")))));
            assertTrue(result.path("hits").isEmpty());
        }
        try (var guest = connect(owner, "GUEST", new java.util.concurrent.CopyOnWriteArrayList<>())) {
            var denied = guest.callTool(new McpSchema.CallToolRequest(AgentToolCatalog.LIST, Map.of()));
            assertTrue(Boolean.TRUE.equals(denied.isError()));
            assertEquals("MCP_BACKEND_UNAUTHORIZED", ((McpSchema.TextContent) denied.content().get(0)).text());
        }
    }
}
