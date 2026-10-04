package com.mengde.clouddrive.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Configuration(proxyBeanMethods = false)
public class MetricsSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain metricsSecurity(HttpSecurity http,
            @Value("${clouddrive.internal.service-token:}") String token) throws Exception {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        if (expected.length < 32) throw new IllegalStateException("internal token must be at least 32 bytes");
        return http.securityMatcher("/actuator/metrics", "/actuator/metrics/**")
                .csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        var values = Collections.list(request.getHeaders("X-CloudDrive-Internal-Token"));
                        if (!"GET".equals(request.getMethod()) || values.size() != 1
                                || !MessageDigest.isEqual(expected, values.get(0).getBytes(StandardCharsets.UTF_8))) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, AuthorizationFilter.class).build();
    }
}
