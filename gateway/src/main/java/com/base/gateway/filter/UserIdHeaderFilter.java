package com.base.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局过滤器：将 userId 注入到 X-User-Id 请求头
 * <p>
 * SaReactorFilter（WebFilter）先执行认证，本过滤器（GlobalFilter）随后执行，
 * 从 SA-Token 读取 loginId，注入到请求头中转发给下游微服务。
 */
@Component
public class UserIdHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("satoken");
        if (token != null && !token.isEmpty()) {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId != null) {
                ServerHttpRequest request = exchange.getRequest().mutate()
                        .header("X-User-Id", String.valueOf(loginId))
                        .build();
                exchange = exchange.mutate().request(request).build();
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
