package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = AiServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clouddrive.agent.enabled=true", "clouddrive.rag.enabled=true", "clouddrive.ai.enabled=true",
                "clouddrive.ai.protocol-fixture=true", "clouddrive.ai.api-key=day32-protocol-test-only-api-key",
                "clouddrive.ai.timeout-millis=2000", "clouddrive.ai.max-concurrent-calls=1",
                "clouddrive.security.jwt.secret=" + AgentTestJwt.SECRET})
class AgentHttpContractTest {
    private static final AgentProtocolFixture fixture = new AgentProtocolFixture();
    private static final ObjectMapper json = new ObjectMapper();
    private static final AtomicLong owners = new AtomicLong(800);
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;
    long owner;
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry registry) { registry.add("clouddrive.ai.base-url", fixture::baseUrl); }
    @BeforeEach void reset() {
        owner = owners.incrementAndGet(); fixture.mode = AgentProtocolFixture.Mode.VALID;
        fixture.entered = new CountDownLatch(1); fixture.release = new CountDownLatch(0);
    }
    @AfterAll static void stop() { fixture.close(); }
    private HttpRequest request(String method, String path, Object body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai" + path))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }
    private HttpResponse<String> send(String method, String path, Object body, long user) throws Exception {
        return http.send(request(method, path, body, AgentTestJwt.token(user, "USER")), HttpResponse.BodyHandlers.ofString());
    }
    private String seed() throws Exception {
        var response = send("POST", "/rag/documents", Map.of("name", "trash.txt", "contentBase64",
                RagDocumentParserTest.encoded(RagIndexTest.TRASH)), owner);
        assertEquals(201, response.statusCode(), response.body()); return json.readTree(response.body()).path("documentId").asText();
    }
    private HttpResponse<String> ask() throws Exception {
        return send("POST", "/agent/ask", Map.of("question", "How long are deleted files kept in the recycle bin?",
                "allowExternalProcessing", true), owner);
    }
    @Test void sdkToolResultRoundTripHasValidatedCitationAndPerStepUsage(CapturedOutput logs) throws Exception {
        String document = seed(); int before = fixture.requests.get(); var response = ask();
        assertEquals(200, response.statusCode(), response.body()); JsonNode out = json.readTree(response.body());
        assertEquals("ANSWERED", out.path("status").asText()); assertEquals(2, out.path("modelCalls").asInt());
        assertEquals(1, out.path("toolCalls").asInt()); assertEquals(2, fixture.requests.get() - before);
        assertEquals(document, out.path("citations").get(0).path("documentId").asText());
        assertTrue(RagIndexTest.TRASH.contains(out.path("citations").get(0).path("quote").asText()));
        assertEquals(2, out.path("usage").size()); assertTrue(out.path("usage").get(0).path("cost").path("protocolFixture").asBoolean());
        assertEquals(150, out.path("usage").get(1).path("cost").path("totalTokens").asInt());
        assertEquals(2, fixture.lastRequest.path("tools").size()); assertFalse(fixture.lastRequest.has("response_format"));
        assertEquals("disabled", fixture.lastRequest.path("thinking").path("type").asText());
        JsonNode messages = fixture.lastRequest.path("messages");
        assertEquals(messages.get(2).path("tool_calls").get(0).path("id").asText(), messages.get(3).path("tool_call_id").asText());
        assertFalse(logs.getAll().contains(RagIndexTest.TRASH)); assertFalse(logs.getAll().contains("day32-protocol-test-only-api-key"));
    }
    @Test void listThenSearchUsesThreeCallsWithoutInventingListCitations() throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.NAVIGATE; var response = ask();
        assertEquals(200, response.statusCode(), response.body()); JsonNode out = json.readTree(response.body());
        assertEquals(3, out.path("modelCalls").asInt()); assertEquals(2, out.path("toolCalls").asInt());
        assertEquals(3, out.path("usage").size());
    }
    @Test void emptyOrOtherTenantHasNoProviderInvocation() throws Exception {
        int before = fixture.requests.get(); var empty = json.readTree(ask().body());
        assertEquals("NO_EVIDENCE", empty.path("reason").asText()); assertEquals(0, empty.path("modelCalls").asInt());
        seed(); var other = send("POST", "/agent/ask", Map.of("question", "private question", "allowExternalProcessing", true), owner + 10000);
        assertEquals(200, other.statusCode()); assertEquals("NO_EVIDENCE", json.readTree(other.body()).path("reason").asText());
        assertEquals(before, fixture.requests.get());
    }
    @Test void anonymousSpoofAndGuestCannotAccessAgentOrToolCatalog() throws Exception {
        int before = fixture.requests.get();
        var base = request("GET", "/agent/tools", null, null);
        assertEquals(401, http.send(base, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(HttpRequest.newBuilder(base, (n, v) -> true).header("X-User-Id", "800").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(request("GET", "/agent/tools", null, AgentTestJwt.token(owner, "GUEST")), HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode tools = json.readTree(send("GET", "/agent/tools", null, owner).body());
        assertEquals(2, tools.size()); assertTrue(tools.get(0).path("readOnly").asBoolean()); assertEquals(before, fixture.requests.get());
    }
    @Test void consentControlCharactersAndOwnerFieldsFailBeforeProvider() throws Exception {
        seed(); int before = fixture.requests.get();
        for (var body : List.of(Map.of("question", "test", "allowExternalProcessing", false),
                Map.of("question", " ", "allowExternalProcessing", true), Map.of("question", "x".repeat(513), "allowExternalProcessing", true),
                Map.of("question", "line\nnext", "allowExternalProcessing", true),
                Map.of("question", "private-agent-question-canary", "allowExternalProcessing", true, "ownerId", 8))) {
            var response = send("POST", "/agent/ask", body, owner); assertEquals(400, response.statusCode());
            assertFalse(response.body().contains("private-agent-question-canary"));
        }
        assertEquals(before, fixture.requests.get());
    }
    @Test void unknownOwnerSpoofDuplicateAndParallelToolsFailBeforeDispatch() throws Exception {
        seed();
        for (var mode : List.of(AgentProtocolFixture.Mode.UNKNOWN, AgentProtocolFixture.Mode.BAD_ARGS,
                AgentProtocolFixture.Mode.DUPLICATE_ARGS, AgentProtocolFixture.Mode.MULTI)) {
            fixture.mode = mode; int before = fixture.requests.get(); var response = ask();
            assertEquals(502, response.statusCode(), response.body()); assertEquals(1, fixture.requests.get() - before);
            assertFalse(response.body().contains("answer"));
            assertEquals(1, json.readTree(send("GET", "/rag/documents", null, owner).body()).size());
        }
    }
    @Test void loopingStopsAtThirdModelProposalBeforeThirdTool() throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.LOOP; int before = fixture.requests.get(); var response = ask();
        assertEquals(502, response.statusCode()); assertEquals("AGENT_LIMIT_EXCEEDED", json.readTree(response.body()).path("code").asText());
        assertEquals(3, fixture.requests.get() - before); assertEquals(1, json.readTree(send("GET", "/rag/documents", null, owner).body()).size());
    }
    @Test void forgedCitationAndMissingUsageFailClosedAndRecover() throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.FORGED;
        var forged = ask(); assertEquals(502, forged.statusCode()); assertEquals("RAG_OUTPUT_INVALID", json.readTree(forged.body()).path("code").asText());
        fixture.mode = AgentProtocolFixture.Mode.NO_USAGE;
        var usage = ask(); assertEquals(502, usage.statusCode()); assertEquals("AI_USAGE_INVALID", json.readTree(usage.body()).path("code").asText());
        fixture.mode = AgentProtocolFixture.Mode.VALID; assertEquals(200, ask().statusCode());
    }
    @Test void modelMayRefuseButItsUsageIsStillAccounted() throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.REFUSED; JsonNode out = json.readTree(ask().body());
        assertEquals("MODEL_REFUSED", out.path("reason").asText()); assertEquals(1, out.path("usage").size());
        assertTrue(out.path("citations").isEmpty()); assertEquals(0, out.path("toolCalls").asInt());
    }
    @Test void upstreamErrorHasNoRetryOrBodyLeak(CapturedOutput logs) throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.ERROR; int before = fixture.requests.get(); var response = ask();
        assertEquals(502, response.statusCode()); assertEquals("AI_UPSTREAM_FAILED", json.readTree(response.body()).path("code").asText());
        assertEquals(1, fixture.requests.get() - before); assertFalse(response.body().contains("private-agent-provider-canary"));
        assertFalse(logs.getAll().contains("private-agent-provider-canary"));
    }
    @Test void wholeLoopSharesCapacityAndExpiredRequestCannotContinue() throws Exception {
        seed(); fixture.mode = AgentProtocolFixture.Mode.HOLD_FIRST; fixture.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor(); int before = fixture.requests.get();
        try {
            var pending = worker.submit(this::ask); assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            assertEquals(429, send("POST", "/summary", Map.of("text", "sample", "allowExternalProcessing", true), owner).statusCode());
            assertEquals(504, pending.get(5, TimeUnit.SECONDS).statusCode()); fixture.release.countDown();
            fixture.mode = AgentProtocolFixture.Mode.VALID; assertEquals(200, ask().statusCode());
            assertEquals(3, fixture.requests.get() - before);
        } finally { fixture.release.countDown(); worker.shutdownNow(); }
    }
    @Test void corpusChangeDuringFinalGenerationKeepsOriginal409Contract() throws Exception {
        String document = seed(); fixture.mode = AgentProtocolFixture.Mode.HOLD_FINAL; fixture.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(this::ask); assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            assertEquals(204, send("DELETE", "/rag/documents/" + document, null, owner).statusCode()); fixture.release.countDown();
            var response = pending.get(5, TimeUnit.SECONDS); assertEquals(409, response.statusCode());
            assertEquals("RAG_CORPUS_CHANGED", json.readTree(response.body()).path("code").asText()); assertFalse(response.body().contains("answer"));
        } finally { fixture.release.countDown(); worker.shutdownNow(); }
    }
}
