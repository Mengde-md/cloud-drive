package com.mengde.clouddrive.userservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
class InternalTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    InternalTokenFilter(@Value("${clouddrive.internal.service-token:}") String token) {
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("CLOUDDRIVE_INTERNAL_SERVICE_TOKEN must be at least 32 bytes");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Fail closed: MVC may normalize matrix parameters or encoded path segments.
        if ("/actuator/health".equals(path) || "/actuator/info".equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        String actual = request.getHeader("X-CloudDrive-Internal-Token");
        if (actual == null || !MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
