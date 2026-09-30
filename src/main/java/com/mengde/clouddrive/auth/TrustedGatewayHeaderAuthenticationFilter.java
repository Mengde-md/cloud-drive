package com.mengde.clouddrive.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 从网关注入的 X-User-* 头还原身份。
 *
 * 三条纪律，每一条都对应一种真实的越权方式：
 *
 *   1. **开关关闭时彻底无副作用**。不是"读了头但不用"，而是第一行就 return。
 *      默认形态下这个过滤器的存在对 95 项回归完全不可观测。
 *   2. **只在尚未认证时才填充**。本过滤器排在 JwtAuthenticationFilter 之后，
 *      本地验签成功的请求不会被它覆盖。谁的身份更可信？自己验出来的。
 *      反过来写（头覆盖本地验签）意味着一个能注入头的中间环节可以顶替任何已登录用户。
 *   3. **头不完整就当匿名，不猜**。X-User-Id 缺失或不是数字 → 不建立认证，
 *      交给后面的 authorizeHttpRequests 去返回 401。宁可拒绝，不要用默认值兜底——
 *      "解析失败就当 userId=0" 这类兜底是越权的经典入口。
 */
public class TrustedGatewayHeaderAuthenticationFilter extends OncePerRequestFilter {

    static final String USER_ID = "X-User-Id";
    static final String USER_NAME = "X-User-Name";
    static final String USER_ROLE = "X-User-Role";

    private final GatewayTrustProperties properties;

    public TrustedGatewayHeaderAuthenticationFilter(GatewayTrustProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!properties.isEnabled()) { chain.doFilter(req, res); return; }

        if (SecurityContextHolder.getContext().getAuthentication() != null) { chain.doFilter(req, res); return; }

        String rawId = req.getHeader(USER_ID);
        if (rawId == null || rawId.isBlank()) { chain.doFilter(req, res); return; }

        long id;
        try {
            id = Long.parseLong(rawId.trim());
        } catch (NumberFormatException ex) {
            chain.doFilter(req, res);
            return;
        }

        String username = req.getHeader(USER_NAME);
        List<String> roles = parseRoles(req.getHeader(USER_ROLE));

        AuthenticatedUser user = new AuthenticatedUser(id, username == null ? "" : username, roles);
        var authorities = roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
        chain.doFilter(req, res);
    }

    private static List<String> parseRoles(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
