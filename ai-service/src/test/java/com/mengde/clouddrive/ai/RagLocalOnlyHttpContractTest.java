package com.mengde.clouddrive.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.mengde.clouddrive.ai.RagDocumentParserTest.encoded;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AiServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"clouddrive.rag.enabled=true", "clouddrive.ai.enabled=false", "clouddrive.ai.api-key=",
                "clouddrive.security.jwt.secret=day31-local-only-test-signing-material"})
class RagLocalOnlyHttpContractTest {
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();
    private HttpResponse<String> post(String path, Object body) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(json.writeValueAsBytes(Map.of("iss", "cloud-drive-learning", "sub", "7",
                "exp", Instant.now().plusSeconds(120).getEpochSecond(), "roles", List.of("USER"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("day31-local-only-test-signing-material".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signed = header + "." + payload;
        String token = signed + "." + b64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/rag/" + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
    @Test void indexesAndSearchesWithoutApiKeyButDoesNotInventGeneratedAnswer() throws Exception {
        assertEquals(201, post("documents", Map.of("name", "trash.txt", "contentBase64", encoded(RagIndexTest.TRASH))).statusCode());
        var search = post("search", Map.of("question", "How long are deleted files kept in the recycle bin?"));
        assertEquals(200, search.statusCode()); assertFalse(json.readTree(search.body()).path("hits").isEmpty());
        var answer = post("answer", Map.of("question", "How long are deleted files kept in the recycle bin?", "allowExternalProcessing", true));
        assertEquals(503, answer.statusCode()); assertEquals("AI_DISABLED", json.readTree(answer.body()).path("code").asText());
    }
}
