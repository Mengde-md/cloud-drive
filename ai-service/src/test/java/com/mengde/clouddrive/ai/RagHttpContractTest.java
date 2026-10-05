package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.mengde.clouddrive.ai.RagDocumentParserTest.encoded;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = AiServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clouddrive.rag.enabled=true", "clouddrive.ai.enabled=true", "clouddrive.ai.protocol-fixture=true",
                "clouddrive.ai.api-key=day31-test-only-api-key", "clouddrive.ai.timeout-millis=1500",
                "clouddrive.ai.max-concurrent-calls=1", "clouddrive.security.jwt.secret=" + RagHttpContractTest.SECRET})
class RagHttpContractTest {
    static final String SECRET = "day31-test-only-shared-jwt-signing-material";
    private static final RagProtocolFixture fixture = new RagProtocolFixture();
    private static final ObjectMapper json = new ObjectMapper();
    private static final AtomicLong owners = new AtomicLong(100);
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;
    long owner;
    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        registry.add("clouddrive.ai.base-url", fixture::baseUrl);
    }
    @BeforeEach void reset() {
        owner = owners.incrementAndGet(); fixture.mode = RagProtocolFixture.Mode.VALID;
        fixture.entered = new CountDownLatch(1); fixture.release = new CountDownLatch(0);
    }
    @AfterAll static void stop() { fixture.close(); }

    static String token(long owner, String role) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(json.writeValueAsBytes(Map.of("iss", "cloud-drive-learning", "sub", String.valueOf(owner),
                "exp", Instant.now().plusSeconds(120).getEpochSecond(), "roles", List.of(role))));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = header + "." + payload;
        return signed + "." + b64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }
    private HttpRequest request(String method, String path, Object body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai" + path))
                .timeout(Duration.ofSeconds(7)).header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }
    private HttpResponse<String> send(String method, String path, Object body, long user) throws Exception {
        return http.send(request(method, path, body, token(user, "USER")), HttpResponse.BodyHandlers.ofString());
    }
    private String seed() throws Exception {
        var response = send("POST", "/rag/documents", Map.of("name", "trash.txt", "contentBase64", encoded(RagIndexTest.TRASH)), owner);
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body()).path("documentId").asText();
    }
    private HttpResponse<String> answer() throws Exception {
        return send("POST", "/rag/answer", Map.of("question", "How long are deleted files kept in the recycle bin?",
                "allowExternalProcessing", true), owner);
    }

    @Test void realEmbeddingToSdkAnswerHasVerifiedCitationAndUsageWithoutPromptLogs(CapturedOutput logs) throws Exception {
        String document = seed();
        var response = answer(); assertEquals(200, response.statusCode(), response.body());
        JsonNode output = json.readTree(response.body());
        assertEquals("ANSWERED", output.path("status").asText());
        assertEquals(document, output.path("citations").get(0).path("documentId").asText());
        assertEquals("trash.txt", output.path("citations").get(0).path("name").asText());
        assertTrue(RagIndexTest.TRASH.contains(output.path("citations").get(0).path("quote").asText()));
        assertEquals(150, output.path("usage").path("totalTokens").asInt());
        assertTrue(output.path("usage").path("protocolFixture").asBoolean());
        assertEquals("json_object", fixture.lastRequest.path("response_format").path("type").asText());
        assertEquals("disabled", fixture.lastRequest.path("thinking").path("type").asText());
        assertTrue(fixture.lastPrompt.path("evidence").size() <= RagIndex.TOP_K);
        assertFalse(logs.getAll().contains(RagIndexTest.TRASH));
        assertFalse(logs.getAll().contains("day31-test-only-api-key"));
    }
    @Test void anonymousSpoofedIdentityAndGuestNeverReachProvider() throws Exception {
        int before = fixture.requests.get();
        var base = request("POST", "/rag/answer", Map.of("question", "test", "allowExternalProcessing", true), null);
        assertEquals(401, http.send(base, HttpResponse.BodyHandlers.ofString()).statusCode());
        var spoof = HttpRequest.newBuilder(base, (name, value) -> true).header("X-User-Id", "100").build();
        assertEquals(401, http.send(spoof, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(request("GET", "/rag/documents", null, token(owner, "GUEST")), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(before, fixture.requests.get());
    }
    @Test void otherOwnerCannotListSearchOrDeleteDocuments() throws Exception {
        String document = seed(); long other = owner + 10000;
        assertTrue(json.readTree(send("GET", "/rag/documents", null, other).body()).isEmpty());
        var search = send("POST", "/rag/search", Map.of("question", RagIndexTest.TRASH), other);
        assertEquals(200, search.statusCode()); assertTrue(json.readTree(search.body()).path("hits").isEmpty());
        assertEquals(404, send("DELETE", "/rag/documents/" + document, null, other).statusCode());
        assertEquals(204, send("DELETE", "/rag/documents/" + document, null, owner).statusCode());
        assertEquals(404, send("DELETE", "/rag/documents/" + document, null, owner).statusCode());
    }
    @Test void absentAndUnrelatedEvidenceRefuseWithoutModelInvocation() throws Exception {
        int before = fixture.requests.get();
        JsonNode empty = json.readTree(answer().body());
        assertEquals("NO_EVIDENCE", empty.path("reason").asText()); assertTrue(empty.path("usage").isNull());
        seed();
        var result = send("POST", "/rag/answer", Map.of("question", "Who composed the opera The Magic Flute?", "allowExternalProcessing", true), owner);
        assertEquals(200, result.statusCode()); assertEquals("REFUSED", json.readTree(result.body()).path("status").asText());
        assertEquals(before, fixture.requests.get());
    }
    @Test void invalidConsentQuestionAndForgedOwnerFieldFailBeforeGeneration() throws Exception {
        int before = fixture.requests.get(); seed();
        assertEquals(400, send("POST", "/rag/answer", Map.of("question", "test", "allowExternalProcessing", false), owner).statusCode());
        assertEquals(400, send("POST", "/rag/answer", Map.of("question", " ", "allowExternalProcessing", true), owner).statusCode());
        assertEquals(400, send("POST", "/rag/search", Map.of("question", "x".repeat(513)), owner).statusCode());
        var forged = send("POST", "/rag/search", Map.of("question", "private-input-canary", "ownerId", 8), owner);
        assertEquals(400, forged.statusCode()); assertFalse(forged.body().contains("private-input-canary"));
        assertEquals(before, fixture.requests.get());
    }
    @Test void invalidDocumentFormatAndUtf8PublishNothing() throws Exception {
        for (String name : List.of("file.pdf", "../file.txt")) {
            assertEquals(400, send("POST", "/rag/documents", Map.of("name", name, "contentBase64", encoded("sample")), owner).statusCode());
        }
        assertEquals(400, send("POST", "/rag/documents", Map.of("name", "bad.txt", "contentBase64", "wyg="), owner).statusCode());
        assertTrue(json.readTree(send("GET", "/rag/documents", null, owner).body()).isEmpty());
    }
    @Test void forgedCitationTruncationAndMissingUsageFailClosedAndThenRecover() throws Exception {
        seed();
        for (var mode : List.of(RagProtocolFixture.Mode.FORGED_ID, RagProtocolFixture.Mode.FORGED_QUOTE, RagProtocolFixture.Mode.TRUNCATED)) {
            fixture.mode = mode; var response = answer(); assertEquals(502, response.statusCode());
            assertEquals("RAG_OUTPUT_INVALID", json.readTree(response.body()).path("code").asText());
            assertFalse(response.body().contains("answer"));
        }
        fixture.mode = RagProtocolFixture.Mode.NO_USAGE;
        assertEquals("AI_USAGE_INVALID", json.readTree(answer().body()).path("code").asText());
        fixture.mode = RagProtocolFixture.Mode.VALID; assertEquals(200, answer().statusCode());
    }
    @Test void modelCanRefuseEvenWhenRetrievalHasEvidence() throws Exception {
        seed(); fixture.mode = RagProtocolFixture.Mode.REFUSED;
        var result = json.readTree(answer().body());
        assertEquals("MODEL_REFUSED", result.path("reason").asText());
        assertTrue(result.path("citations").isEmpty()); assertEquals(150, result.path("usage").path("totalTokens").asInt());
    }
    @Test void upstreamErrorDoesNotRetryOrLeakItsBody(CapturedOutput logs) throws Exception {
        seed(); fixture.mode = RagProtocolFixture.Mode.ERROR; int before = fixture.requests.get();
        var response = answer(); assertEquals(502, response.statusCode());
        assertEquals("AI_UPSTREAM_FAILED", json.readTree(response.body()).path("code").asText());
        assertEquals(1, fixture.requests.get() - before);
        assertFalse(response.body().contains("private-rag-provider-canary"));
        assertFalse(logs.getAll().contains("private-rag-provider-canary"));
    }
    @Test void sharesSummaryCapacityAndTimeoutReleasesIt() throws Exception {
        seed(); fixture.mode = RagProtocolFixture.Mode.HOLD; fixture.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> pending = worker.submit(this::answer);
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            assertEquals(429, send("POST", "/summary", Map.of("text", "sample", "allowExternalProcessing", true), owner).statusCode());
            var timeout = pending.get(4, TimeUnit.SECONDS); assertEquals(504, timeout.statusCode());
            assertEquals("AI_TIMEOUT", json.readTree(timeout.body()).path("code").asText());
            fixture.release.countDown(); fixture.mode = RagProtocolFixture.Mode.VALID;
            assertEquals(200, answer().statusCode());
        } finally { fixture.release.countDown(); worker.shutdownNow(); }
    }
    @Test void deletingCorpusDuringGenerationPreventsStaleAnswer() throws Exception {
        String document = seed(); fixture.mode = RagProtocolFixture.Mode.HOLD; fixture.release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> pending = worker.submit(this::answer);
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            assertEquals(204, send("DELETE", "/rag/documents/" + document, null, owner).statusCode());
            fixture.release.countDown();
            var result = pending.get(4, TimeUnit.SECONDS); assertEquals(409, result.statusCode());
            assertEquals("RAG_CORPUS_CHANGED", json.readTree(result.body()).path("code").asText());
            assertFalse(result.body().contains("answer"));
        } finally { fixture.release.countDown(); worker.shutdownNow(); }
    }
}
