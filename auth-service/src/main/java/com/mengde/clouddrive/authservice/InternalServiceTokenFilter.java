package com.mengde.clouddrive.authservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

final class InternalServiceTokenFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-CloudDrive-Internal-Token";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private final byte[] expectedToken;
    private final SecurityConfiguration.JsonAuthFailureHandler failures;

    InternalServiceTokenFilter(String token, SecurityConfiguration.JsonAuthFailureHandler failures) {
        if (token == null || token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("CLOUDDRIVE_INTERNAL_SERVICE_TOKEN must be at least 32 bytes");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.failures = failures;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedToken = request.getHeader(HEADER_NAME);
        if (suppliedToken == null || !MessageDigest.isEqual(expectedToken, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            failures.write401(request, response);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                "internal-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
