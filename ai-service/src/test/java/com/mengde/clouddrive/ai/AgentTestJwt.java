package com.mengde.clouddrive.ai;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class AgentTestJwt {
    static final String SECRET = "day32-test-only-shared-jwt-signing-material";
    private AgentTestJwt() { }
    static String token(long owner, String role) throws Exception { return token(owner, role, SECRET); }
    static String token(long owner, String role, String secret) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(AgentToolCatalog.write(Map.of("iss", "cloud-drive-learning", "sub", String.valueOf(owner),
                "exp", Instant.now().plusSeconds(300).getEpochSecond(), "roles", List.of(role))).getBytes(StandardCharsets.UTF_8));
        String signed = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signed + "." + b64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }
}
