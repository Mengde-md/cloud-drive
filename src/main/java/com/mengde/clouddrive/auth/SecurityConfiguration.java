package com.mengde.clouddrive.auth;

import com.mengde.clouddrive.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

@Configuration @EnableWebSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean Duration jwtTtl(@Value("${clouddrive.security.jwt.ttl:30m}") Duration ttl) { return ttl; }
    @Bean SecretKey jwtSecretKey(@Value("${clouddrive.security.jwt.secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("CLOUDDRIVE_JWT_SECRET must be at least 32 bytes");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKey key) { NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cloud-drive-learning")); return decoder; }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, JsonAuthFailureHandler failures) throws Exception {
        return http.csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/register", "/api/auth/login").permitAll().requestMatchers("/api/auth/me").authenticated().anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(failures).accessDeniedHandler(failures))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
    }
    @Bean JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService tokens, JsonAuthFailureHandler failures) { return new JwtAuthenticationFilter(tokens, failures); }
    @Bean JsonAuthFailureHandler jsonAuthFailureHandler(ObjectMapper mapper) { return new JsonAuthFailureHandler(mapper); }

    static final class JwtAuthenticationFilter extends OncePerRequestFilter {
        private final JwtTokenService tokens; private final JsonAuthFailureHandler failures;
        JwtAuthenticationFilter(JwtTokenService tokens, JsonAuthFailureHandler failures) { this.tokens = tokens; this.failures = failures; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            if (header == null || header.isBlank()) { chain.doFilter(req, res); return; }
            if (!header.startsWith("Bearer ") || header.length() == 7) { failures.write401(req, res); return; }
            try { AuthenticatedUser user = tokens.verify(header.substring(7));
                var authorities = user.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList());
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities); org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth); chain.doFilter(req, res);
            } catch (JwtException | IllegalArgumentException ex) { org.springframework.security.core.context.SecurityContextHolder.clearContext(); failures.write401(req, res); }
        }
    }
    static final class JsonAuthFailureHandler implements org.springframework.security.web.AuthenticationEntryPoint, org.springframework.security.web.access.AccessDeniedHandler {
        private final ObjectMapper mapper; JsonAuthFailureHandler(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public void commence(HttpServletRequest req, HttpServletResponse res, org.springframework.security.core.AuthenticationException ex) throws IOException { write401(req,res); }
        @Override public void handle(HttpServletRequest req, HttpServletResponse res, org.springframework.security.access.AccessDeniedException ex) throws IOException { write(res, HttpStatus.FORBIDDEN, "access denied"); }
        void write401(HttpServletRequest req, HttpServletResponse res) throws IOException { write(res, HttpStatus.UNAUTHORIZED, "authentication required or token invalid"); }
        private void write(HttpServletResponse res, HttpStatus status, String message) throws IOException { res.setStatus(status.value()); res.setContentType("application/json"); mapper.writeValue(res.getOutputStream(), new ApiError(status.value(), status.getReasonPhrase(), message)); }
    }
}
