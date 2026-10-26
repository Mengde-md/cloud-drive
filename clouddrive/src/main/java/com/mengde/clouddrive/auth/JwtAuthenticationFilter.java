package com.mengde.clouddrive.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把 Authorization 头里的 JWT 翻译成 Spring Security 的认证对象（Day15）。
 *
 * <p>三个容易踩的点：</p>
 * <ol>
 *   <li><b>没有令牌时不能直接拒绝</b>，要放行给后面的过滤链。
 *       注册/登录/分享下载都是匿名接口，是否需要认证由 SecurityConfig 的授权规则决定，
 *       不是这个过滤器的职责。这里只负责"有令牌就解析"。</li>
 *   <li><b>令牌无效时清空 SecurityContext 后继续</b>，而不是在这里写响应。
 *       统一的 401 由 AuthenticationEntryPoint 输出，保证错误体格式只有一处定义。</li>
 *   <li><b>继承 OncePerRequestFilter</b>：一次请求内部 forward/include 时不会重复执行解析。</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokens;

    public JwtAuthenticationFilter(JwtTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = tokens.verify(header.substring(BEARER_PREFIX.length()));
            var authorities = user.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtTokenService.InvalidTokenException exception) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
