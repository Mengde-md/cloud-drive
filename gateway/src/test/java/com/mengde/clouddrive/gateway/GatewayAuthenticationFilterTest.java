package com.mengde.clouddrive.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day25 网关鉴权与身份透传的行为契约。
 *
 * <p>用 MockServerWebExchange 直接驱动过滤器，不起真实网关上下文，也不连任何后端：
 * 这一层要证明的是"过滤器对一个请求做了什么"，而真实转发属于手工验收。
 * 把两件事混在一个测试里，失败时分不清是鉴权逻辑错了还是后端没起。
 *
 * <p>其中 {@code forgedIdentityHeadersAreStrippedEvenOnAnonymousPaths} 是整组里
 * 最重要的一条：它覆盖的是一个**能够直接导致越权**的实现顺序错误。
 */
class GatewayAuthenticationFilterTest {

    private static final String SECRET = "day25-test-only-signing-material-at-least-32-bytes";
    private static final SecretKey KEY = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();

    private AuthenticationGlobalFilter filter() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(KEY)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cloud-drive-learning"));
        return new AuthenticationGlobalFilter(decoder, properties, new ObjectMapper());
    }

    private static String token(String issuer, long id, String username, String role, Instant expiresAt) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(KEY));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).issuedAt(expiresAt.minus(1, ChronoUnit.MINUTES)).expiresAt(expiresAt)
                .subject(Long.toString(id)).claim("username", username).claim("roles", List.of(role)).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    private static String validToken() {
        return token("cloud-drive-learning", 42L, "alice", "USER", Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    /** 跑一次过滤器，返回下游真正看到的那个 exchange（null 表示请求被拦下、没有进入 chain）。 */
    private ServerWebExchange run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> seenByDownstream = new AtomicReference<>();
        filter().filter(exchange, ex -> { seenByDownstream.set(ex); return Mono.empty(); }).block();
        return seenByDownstream.get();
    }

    private HttpStatus statusOf(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter().filter(exchange, ex -> Mono.empty()).block();
        return HttpStatus.resolve(exchange.getResponse().getStatusCode().value());
    }

    @Test
    @DisplayName("白名单路径无需 token 即可通过")
    void anonymousPathsPassWithoutToken() {
        assertThat(run(MockServerHttpRequest.post("/api/auth/login").build())).isNotNull();
        assertThat(run(MockServerHttpRequest.post("/api/auth/register").build())).isNotNull();
        assertThat(run(MockServerHttpRequest.get("/api/shares/abc/download").build())).isNotNull();
    }

    @Test
    @DisplayName("受保护路径不带 token 一律 401，且请求不会被转发到下游")
    void protectedPathWithoutTokenIsRejectedAtTheEdge() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/entries").build();
        assertThat(run(request)).as("坏请求不应到达下游").isNull();
        assertThat(statusOf(request)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Authorization 头格式不对（缺 Bearer、空 Bearer）同样 401，不当作匿名放行")
    void malformedAuthorizationHeaderIsRejected() {
        assertThat(statusOf(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Token abc").build())).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ").build())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("签名无效、issuer 不对、已过期的 token 都在网关被挡下")
    void invalidTokensAreRejected() {
        assertThat(statusOf(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").build())).isEqualTo(HttpStatus.UNAUTHORIZED);

        String wrongIssuer = token("someone-else", 1L, "alice", "USER", Instant.now().plus(30, ChronoUnit.MINUTES));
        assertThat(statusOf(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongIssuer).build())).isEqualTo(HttpStatus.UNAUTHORIZED);

        String expired = token("cloud-drive-learning", 1L, "alice", "USER", Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(statusOf(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).build())).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("验签通过后的下游异常必须原样传播，不能被误写成 401")
    void downstreamFailuresAreNotMappedToUnauthorized() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()).build());

        assertThatThrownBy(() -> filter().filter(exchange,
                ignored -> Mono.error(new IllegalArgumentException("downstream unavailable"))).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("downstream unavailable");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("验签通过后注入 X-User-Id / X-User-Name / X-User-Role")
    void validTokenIsTranslatedIntoIdentityHeaders() {
        ServerWebExchange downstream = run(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()).build());

        HttpHeaders headers = downstream.getRequest().getHeaders();
        assertThat(headers.getFirst(GatewayIdentityHeaders.USER_ID)).isEqualTo("42");
        assertThat(headers.getFirst(GatewayIdentityHeaders.USER_NAME)).isEqualTo("alice");
        assertThat(headers.getFirst(GatewayIdentityHeaders.USER_ROLE)).isEqualTo("USER");
    }

    @Test
    @DisplayName("客户端伪造的身份头必须被网关自己的值覆盖，绝不叠加")
    void forgedIdentityHeadersAreReplacedNotAppended() {
        ServerWebExchange downstream = run(MockServerHttpRequest.get("/api/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .header(GatewayIdentityHeaders.USER_ID, "1")
                .header(GatewayIdentityHeaders.USER_ROLE, "ADMIN")
                .build());

        HttpHeaders headers = downstream.getRequest().getHeaders();
        // 如果实现用的是 add 而不是"先删再写"，这里会拿到 ["1", "42"]，
        // 而下游取 getFirst 就会读到伪造的 1 —— 越权。
        assertThat(headers.get(GatewayIdentityHeaders.USER_ID)).containsExactly("42");
        assertThat(headers.get(GatewayIdentityHeaders.USER_ROLE)).containsExactly("USER");
    }

    @Test
    @DisplayName("匿名路径上的伪造身份头也必须被剥离——这是本组最关键的一条")
    void forgedIdentityHeadersAreStrippedEvenOnAnonymousPaths() {
        ServerWebExchange downstream = run(MockServerHttpRequest.post("/api/auth/login")
                .header(GatewayIdentityHeaders.USER_ID, "1")
                .header(GatewayIdentityHeaders.USER_NAME, "root")
                .header(GatewayIdentityHeaders.USER_ROLE, "ADMIN")
                .build());

        // 若实现把"剥离"写在白名单判断之后，任何一条匿名路径都会变成伪造身份的通道。
        HttpHeaders headers = downstream.getRequest().getHeaders();
        assertThat(headers.get(GatewayIdentityHeaders.USER_ID)).isNull();
        assertThat(headers.get(GatewayIdentityHeaders.USER_NAME)).isNull();
        assertThat(headers.get(GatewayIdentityHeaders.USER_ROLE)).isNull();
    }

    @Test
    @DisplayName("鉴权开关关闭时退化为纯转发，但身份头仍然被剥离")
    void disablingAuthenticationStillStripsForgedHeaders() {
        properties.setEnabled(false);
        ServerWebExchange downstream = run(MockServerHttpRequest.get("/api/entries")
                .header(GatewayIdentityHeaders.USER_ID, "1").build());

        assertThat(downstream).as("关闭鉴权后请求应当直接放行").isNotNull();
        assertThat(downstream.getRequest().getHeaders().get(GatewayIdentityHeaders.USER_ID))
                .as("剥离不受开关控制：关掉的是鉴权，不是防伪造").isNull();
    }

    @Test
    @DisplayName("过滤器必须排在路由转发过滤器之前，否则改请求头毫无意义")
    void filterRunsBeforeRouting() {
        assertThat(filter().getOrder()).isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("网关的匿名清单与单体 SecurityConfiguration 的 permitAll 集合逐条对应")
    void anonymousListMatchesTheMonolithPermitAllSet() {
        assertThat(properties.getAnonymous())
                .contains("/api/auth/register", "/api/auth/login", "/api/shares/*/download");
        // 反向断言：受保护路径一条都不能漏进白名单。
        assertThat(properties.getAnonymous())
                .doesNotContain("/api/auth/me", "/api/entries/**", "/api/files/**",
                        "/api/upload-sessions/**", "/api/shares", "/internal/**");
    }
}
