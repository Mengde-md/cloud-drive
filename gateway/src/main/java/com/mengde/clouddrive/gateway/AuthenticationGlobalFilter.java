package com.mengde.clouddrive.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Day25 的核心：边缘鉴权 + 身份透传 + 身份头防伪造。
 *
 * 三件事的**顺序**是这个类里最重要的东西，顺序错了安全性直接归零：
 *
 *   1. 先无条件剥离客户端自带的 X-User-* 头。放在最前面，且不受 enabled 开关影响、
 *      也不受匿名白名单影响。理由：如果先判白名单再剥离，那么任何一条匿名路径
 *      都成了伪造身份的通道——攻击者对 /api/auth/login 发一个带 X-User-Id: 1 的请求，
 *      这个头就会原样到达下游。匿名的是"不需要证明身份"，不是"可以自称任意身份"。
 *   2. 再判白名单。白名单命中就直接放行（此时下游收到的请求已经没有任何身份头）。
 *   3. 最后验签。验不过立刻 401，不转发——这就是网关作为统一入口的价值：
 *      坏请求在边缘被挡住，不会打到每一个下游服务上。
 *
 * 为什么 order 取 -100：Spring Cloud Gateway 的路由转发过滤器
 * （NettyRoutingFilter）order 是 Ordered.LOWEST_PRECEDENCE，本过滤器必须排在它前面，
 * 否则请求已经转发出去了再改请求头毫无意义。取 -100 而不是 HIGHEST_PRECEDENCE，
 * 是给将来可能插在更前面的过滤器（如 traceId 生成）留出位置。
 */
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationGlobalFilter.class);

    private final ReactiveJwtDecoder decoder;
    private final GatewayAuthenticationProperties properties;
    private final ObjectMapper json;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public AuthenticationGlobalFilter(ReactiveJwtDecoder decoder,
                                      GatewayAuthenticationProperties properties,
                                      ObjectMapper json) {
        this.decoder = decoder;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public int getOrder() { return -100; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 第 1 步：剥离。无条件、无例外。
        ServerWebExchange stripped = exchange.mutate()
                .request(r -> r.headers(h -> GatewayIdentityHeaders.ALL.forEach(h::remove)))
                .build();

        if (!properties.isEnabled()) {
            return chain.filter(stripped);
        }

        String path = exchange.getRequest().getURI().getPath();

        // 第 2 步：白名单。
        if (isAnonymous(path)) {
            return chain.filter(stripped);
        }

        // 第 3 步：验签。
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7) {
            return unauthorized(exchange, "authentication required or token invalid");
        }

        return decoder.decode(header.substring(7))
                .flatMap(jwt -> chain.filter(withIdentity(stripped, jwt)))
                .onErrorResume(JwtException.class, ex -> {
                    // 只记路径与异常类型，绝不记 token 本身——日志会被收集、被转发、被长期保存。
                    log.debug("gateway rejected token for path {}: {}", path, ex.getClass().getSimpleName());
                    return unauthorized(exchange, "authentication required or token invalid");
                })
                .onErrorResume(IllegalArgumentException.class,
                        ex -> unauthorized(exchange, "authentication required or token invalid"));
    }

    private boolean isAnonymous(String path) {
        List<String> patterns = properties.getAnonymous();
        if (patterns == null) return false;
        return patterns.stream().anyMatch(p -> matcher.match(p, path));
    }

    private ServerWebExchange withIdentity(ServerWebExchange exchange, Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String role = (roles == null || roles.isEmpty()) ? "" : String.join(",", roles);
        String username = jwt.getClaimAsString("username");
        return exchange.mutate()
                .request(r -> r
                        .header(GatewayIdentityHeaders.USER_ID, jwt.getSubject())
                        .header(GatewayIdentityHeaders.USER_NAME, username == null ? "" : username)
                        .header(GatewayIdentityHeaders.USER_ROLE, role))
                .build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            // 与单体 ApiError 同结构：调用方无论被谁拒绝，拿到的错误体格式是一样的。
            body = json.writeValueAsBytes(Map.of(
                    "status", 401,
                    "error", "Unauthorized",
                    "message", message));
        } catch (Exception e) {
            body = ("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
