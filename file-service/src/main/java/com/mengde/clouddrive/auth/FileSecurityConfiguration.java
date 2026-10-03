package com.mengde.clouddrive.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class FileSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    JwtDecoder jwtDecoder(@Value("${clouddrive.security.jwt.secret:}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("CLOUDDRIVE_JWT_SECRET must be at least 32 bytes");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cloud-drive-learning"));
        return decoder;
    }

    @Bean
    SecurityFilterChain fileSecurity(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/shares/*/download", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/entries/**", "/api/entries", "/api/files/**", "/api/upload-sessions/**",
                                "/api/upload-sessions", "/api/shares").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new FileJwtFilter(decoder), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static final class FileJwtFilter extends OncePerRequestFilter {
        private final JwtDecoder decoder;

        private FileJwtFilter(JwtDecoder decoder) { this.decoder = decoder; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String header = request.getHeader("Authorization");
            if (header == null || header.isBlank()) {
                chain.doFilter(request, response);
                return;
            }
            if (!header.startsWith("Bearer ") || header.length() == 7) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            try {
                var jwt = decoder.decode(header.substring(7));
                long id = Long.parseLong(jwt.getSubject());
                List<String> roles = jwt.getClaimAsStringList("roles");
                if (id <= 0 || roles == null) throw new IllegalArgumentException("invalid identity claims");
                var user = new AuthenticatedUser(id, jwt.getClaimAsString("username"), roles);
                var authorities = roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, authorities));
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            try { chain.doFilter(request, response); }
            finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
