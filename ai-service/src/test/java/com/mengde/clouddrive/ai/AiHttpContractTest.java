package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = AiServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clouddrive.ai.enabled=true", "clouddrive.ai.protocol-fixture=true",
                "clouddrive.ai.api-key=test-only-api-key", "clouddrive.ai.timeout-millis=1500",
                "clouddrive.ai.max-concurrent-calls=1", "clouddrive.security.jwt.secret=" + AiHttpContractTest.SECRET})
class AiHttpContractTest {
    static final String SECRET = "day30-test-only-shared-jwt-signing-material";
    private static final ProtocolFixture fixture = new ProtocolFixture();
    private static final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;

    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        registry.add("clouddrive.ai.base-url", fixture::baseUrl);
    }
    @BeforeEach void reset() {
        fixture.firstChunk = new CountDownLatch(1);
        fixture.continueStream = new CountDownLatch(0);
    }
    @AfterAll static void stop() { fixture.close(); }

    private String token(String issuer, String subject, String role, long expiry) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(json.writeValueAsBytes(Map.of("iss", issuer, "sub", subject,
                "exp", expiry, "username", "contract-user", "roles", List.of(role))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = header + "." + payload;
        return signed + "." + encoder.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }
    private String token() throws Exception { return token("cloud-drive-learning", "7", "USER", Instant.now().plusSeconds(120).getEpochSecond()); }
    private HttpRequest request(String path, String text, boolean consent, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("text", text, "allowExternalProcessing", consent))));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }
    private HttpResponse<String> summary(String text) throws Exception {
        return http.send(request("/api/ai/summary", text, true, token()), HttpResponse.BodyHandlers.ofString());
    }
    private String stream(String text) throws Exception {
        var response = http.send(request("/api/ai/summary/stream", text, true, token()), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test void validSummaryUsesRealSdkHttpAndProviderUsage(CapturedOutput output) throws Exception {
        var response = summary("prompt-private-canary");
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals("A controlled summary", body.path("output").path("summary").asText());
        assertEquals(150, body.path("usage").path("totalTokens").asInt());
        assertTrue(body.path("usage").path("protocolFixture").asBoolean());
        assertEquals("Bearer test-only-api-key", fixture.lastAuthorization);
        assertEquals("deepseek-flash", fixture.lastRequest.path("model").asText());
        assertEquals("json_object", fixture.lastRequest.path("response_format").path("type").asText());
        assertEquals("disabled", fixture.lastRequest.path("thinking").path("type").asText());
        assertEquals(512, fixture.lastRequest.path("max_tokens").asInt());
        assertFalse(output.getAll().contains("prompt-private-canary"));
        assertFalse(output.getAll().contains("test-only-api-key"));
    }

    @Test void invalidAndTruncatedOutputAndMissingUsageFailClosed() throws Exception {
        assertEquals("AI_OUTPUT_INVALID", json.readTree(summary("invalid").body()).path("code").asText());
        assertEquals("AI_OUTPUT_INVALID", json.readTree(summary("truncated").body()).path("code").asText());
        assertEquals("AI_USAGE_INVALID", json.readTree(summary("usage-missing").body()).path("code").asText());
    }

    @Test void upstream500IsNotRetriedAndItsBodyIsNotExposed(CapturedOutput output) throws Exception {
        int before = fixture.requests.get();
        var response = summary("upstream-error");
        assertEquals(502, response.statusCode());
        assertEquals(1, fixture.requests.get() - before);
        assertFalse(response.body().contains("provider-private-canary"));
        assertFalse(output.getAll().contains("provider-private-canary"));
    }

    @Test void anonymousSpoofedExpiredWrongIssuerAndWrongRoleNeverReachProvider() throws Exception {
        int before = fixture.requests.get();
        var spoof = HttpRequest.newBuilder(request("/api/ai/summary", "text", true, null).uri())
                .header("Content-Type", "application/json").header("X-User-Id", "7")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"text\",\"allowExternalProcessing\":true}")).build();
        assertEquals(401, http.send(spoof, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request("/api/ai/summary", "text", true,
                token("wrong", "7", "USER", Instant.now().plusSeconds(120).getEpochSecond())), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request("/api/ai/summary", "text", true,
                token("cloud-drive-learning", "0", "USER", Instant.now().plusSeconds(120).getEpochSecond())), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request("/api/ai/summary", "text", true,
                token("cloud-drive-learning", "7", "USER", Instant.now().minusSeconds(120).getEpochSecond())), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(request("/api/ai/summary", "text", true,
                token("cloud-drive-learning", "7", "GUEST", Instant.now().plusSeconds(120).getEpochSecond())), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(before, fixture.requests.get());
    }

    @Test void inputLimitsAndExplicitExternalConsentRunBeforeProvider() throws Exception {
        int before = fixture.requests.get();
        assertEquals(400, http.send(request("/api/ai/summary", "text", false, token()), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, summary(" ").statusCode());
        assertEquals(400, summary("x".repeat(4097)).statusCode());
        assertEquals(before, fixture.requests.get());
    }

    @Test void malformedAndUnknownInputFieldsAreRejectedWithoutEchoingTheBody() throws Exception {
        int before = fixture.requests.get();
        for (String body : List.of("{not-json", "{\"text\":\"text\",\"allowExternalProcessing\":true,\"action\":\"private-input-canary\"}")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/summary"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + token())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains("private-input-canary"));
        }
        assertEquals(before, fixture.requests.get());
    }

    @Test void syncDeadlineReturns504AndNextCallCanRecover() throws Exception {
        long started = System.nanoTime();
        var response = summary("slow");
        assertEquals(504, response.statusCode(), response.body());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
        assertEquals(200, summary("recovered").statusCode());
    }

    @Test void streamingProgressArrivesBeforeProviderCompletesAndOnlyFinalOutputIsPublished() throws Exception {
        fixture.continueStream = new CountDownLatch(1);
        var response = http.send(request("/api/ai/summary/stream", "stream", true, token()), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String event = reader.readLine();
            assertEquals("event:progress", event);
            String progress = reader.readLine();
            assertTrue(progress.contains("receivedChars"));
            assertFalse(progress.contains("A controlled summary"));
            fixture.continueStream.countDown();
            String remaining = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(remaining.contains("event:result"), remaining);
            assertTrue(remaining.contains("A controlled summary"));
            assertFalse(remaining.contains("event:error"));
        } finally { fixture.continueStream.countDown(); }
    }

    @Test void streamValidationFailureAndMissingUsageNeverEmitResult() throws Exception {
        for (String text : List.of("stream-invalid", "stream-truncated", "stream-usage-missing")) {
            String events = stream(text);
            assertTrue(events.contains("event:error"), events);
            assertFalse(events.contains("event:result"));
        }
    }

    @Test void stalledStreamHasOneTimeoutEventAndReleasesItsSlot() throws Exception {
        String events = stream("stream-slow");
        assertEquals(1, events.split("event:error", -1).length - 1, events);
        assertTrue(events.contains("AI_TIMEOUT"));
        assertFalse(events.contains("event:result"));
        assertEquals(200, summary("after-timeout").statusCode());
    }

    @Test void occupiedStreamRejectsAnotherCallAndClientDisconnectReleasesSlot() throws Exception {
        fixture.continueStream = new CountDownLatch(1);
        var response = http.send(request("/api/ai/summary/stream", "stream", true, token()), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:progress", reader.readLine());
            assertEquals(429, summary("while-busy").statusCode());
        } finally { fixture.continueStream.countDown(); }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        HttpResponse<String> recovered;
        do { recovered = summary("after-disconnect"); }
        while (recovered.statusCode() == 429 && System.nanoTime() < deadline);
        assertEquals(200, recovered.statusCode(), recovered.body());
    }
}
