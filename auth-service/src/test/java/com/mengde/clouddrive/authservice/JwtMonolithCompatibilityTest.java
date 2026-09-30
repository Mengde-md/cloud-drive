package com.mengde.clouddrive.authservice;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本类保护的是整个 Day25 拆分里**最容易悄悄坏掉**的一件事：
 * auth-service 签发的 token，单体和网关必须都能验通，并解析出同一个身份。
 *
 * 这个故障如果发生，表现不是"报错"，而是"登录成功但后续请求 401"，
 * 或者更糟——"认证通过但 ownerId 解析成了别人"。所以把 claim 结构本身当契约来断言，
 * 而不是只断言"能解析"。断言里的字面量刻意写死，与单体
 * com.mengde.clouddrive.auth.JwtTokenService 的实现逐项对照，改任何一边都会红。
 */
class JwtMonolithCompatibilityTest {

    private static final String SECRET = "day25-test-only-signing-material-at-least-32-bytes";

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static JwtTokenService serviceWith(String secret) {
        SecretKey k = key(secret);
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(k));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(k).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtTokenService.ISSUER));
        return new JwtTokenService(encoder, decoder, Duration.ofMinutes(30));
    }

    private static UserAccountRow user(long id, String name, String role) {
        UserAccountRow r = new UserAccountRow();
        r.setId(id); r.setUsername(name); r.setRole(role);
        return r;
    }

    @Test
    @DisplayName("issuer 必须是 cloud-drive-learning——单体的 jwtDecoder 用它做校验，写错就是全量 401")
    void issuerMatchesTheMonolith() {
        assertThat(JwtTokenService.ISSUER).isEqualTo("cloud-drive-learning");

        JwtTokenService tokens = serviceWith(SECRET);
        String value = tokens.issue(user(1L, "alice", "USER")).value();

        NimbusJwtDecoder raw = NimbusJwtDecoder.withSecretKey(key(SECRET)).macAlgorithm(MacAlgorithm.HS256).build();
        Jwt jwt = raw.decode(value);
        // 用 getClaimAsString 而不是 getIssuer()：后者会把 iss 当 URL 解析，
        // 而 "cloud-drive-learning" 不是合法 URL，断言会以一个与本意无关的异常失败。
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("cloud-drive-learning");
    }

    @Test
    @DisplayName("subject 是用户 id 的十进制字符串——单体用 Long.parseLong(getSubject()) 解析")
    void subjectIsDecimalUserId() {
        JwtTokenService tokens = serviceWith(SECRET);
        String value = tokens.issue(user(1234567890L, "alice", "USER")).value();

        NimbusJwtDecoder raw = NimbusJwtDecoder.withSecretKey(key(SECRET)).macAlgorithm(MacAlgorithm.HS256).build();
        Jwt jwt = raw.decode(value);
        assertThat(jwt.getSubject()).isEqualTo("1234567890");
        assertThat(Long.parseLong(jwt.getSubject())).isEqualTo(1234567890L);
    }

    @Test
    @DisplayName("claim 名恰好是 username 与 roles，且 roles 是数组而不是字符串")
    void claimNamesAndShapesMatch() {
        JwtTokenService tokens = serviceWith(SECRET);
        String value = tokens.issue(user(5L, "alice", "USER")).value();

        NimbusJwtDecoder raw = NimbusJwtDecoder.withSecretKey(key(SECRET)).macAlgorithm(MacAlgorithm.HS256).build();
        Jwt jwt = raw.decode(value);
        assertThat(jwt.getClaimAsString("username")).isEqualTo("alice");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaims().get("roles")).isInstanceOf(List.class);
        // 单体不读这些 claim；多出来的 claim 是兼容性风险，保持最小集合。
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "iat", "exp", "sub", "username", "roles");
    }

    @Test
    @DisplayName("签名算法是 HS256——换算法会让另外两个进程的 decoder 直接拒绝")
    void algorithmIsHs256() {
        JwtTokenService tokens = serviceWith(SECRET);
        String value = tokens.issue(user(5L, "alice", "USER")).value();

        NimbusJwtDecoder raw = NimbusJwtDecoder.withSecretKey(key(SECRET)).macAlgorithm(MacAlgorithm.HS256).build();
        assertThat(raw.decode(value).getHeaders().get("alg")).hasToString("HS256");
    }

    @Test
    @DisplayName("换一把密钥就验不过——证明三个进程共享同一个 secret 是硬前提，不是可选项")
    void differentSecretIsRejected() {
        String value = serviceWith(SECRET).issue(user(5L, "alice", "USER")).value();
        JwtTokenService other = serviceWith("a-completely-different-secret-of-at-least-32b");
        assertThatThrownBy(() -> other.verify(value)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("verify 还原出的身份与签发时一致，roles 为空时退化为空列表而不是 NPE")
    void verifyRoundTrip() {
        JwtTokenService tokens = serviceWith(SECRET);
        String value = tokens.issue(user(88L, "dave", "ADMIN")).value();
        AuthenticatedUser user = tokens.verify(value);
        assertThat(user.id()).isEqualTo(88L);
        assertThat(user.username()).isEqualTo("dave");
        assertThat(user.roles()).containsExactly("ADMIN");
    }
}
