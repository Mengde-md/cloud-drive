package com.mengde.clouddrive.authservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * auth-service 自己的安全配置。
 *
 * 一个容易想当然的问题：网关已经验签了，auth-service 为什么还要自己验？
 * 因为 /api/auth/me 之外，auth-service 还挂着 /internal/**，而 /internal/** 不经过网关。
 * 更重要的是：**网关不是唯一入口这件事在今天是事实**——auth-service 的 8081 端口在
 * 本机是直接可达的。把"内网互信"当成"内网无需认证"，是拆分期最常见的越权来源。
 * 所以本服务对 /api/auth/me 仍然自己验签，网关的验签是**前置快速失败**而不是替代。
 * 这与 Day23 第 2.4 节"下游服务信任网关传入的头、不再重复验签"的差别，
 * 以及为什么本日选择不照搬那一条，写在 docs/architecture/day25-auth-split-and-gateway-authn.md。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    Duration jwtTtl(@Value("${clouddrive.security.jwt.ttl:30m}") Duration ttl) { return ttl; }

    @Bean
    SecretKey jwtSecretKey(@Value("${clouddrive.security.jwt.secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // 启动期就炸，而不是等到第一个登录请求才炸。密钥缺失是部署错误，
            // 让它表现为"进程起不来"比表现为"登录接口 500"更容易被发现。
            throw new IllegalStateException("CLOUDDRIVE_JWT_SECRET must be at least 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtTokenService.ISSUER));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter,
                                            InternalServiceTokenFilter internalServiceTokenFilter,
                                            JsonAuthFailureHandler failures) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                        .requestMatchers("/api/auth/me").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(failures).accessDeniedHandler(failures))
                .addFilterBefore(internalServiceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    InternalServiceTokenFilter internalServiceTokenFilter(
            @Value("${clouddrive.internal.service-token:}") String serviceToken,
            JsonAuthFailureHandler failures) {
        return new InternalServiceTokenFilter(serviceToken, failures);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService tokens, JsonAuthFailureHandler failures) {
        return new JwtAuthenticationFilter(tokens, failures);
    }

    @Bean
    JsonAuthFailureHandler jsonAuthFailureHandler(ObjectMapper mapper) { return new JsonAuthFailureHandler(mapper); }

    // These filters belong only to Spring Security, not a second servlet-container chain.
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<InternalServiceTokenFilter> internalFilterRegistration(InternalServiceTokenFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    static final class JwtAuthenticationFilter extends OncePerRequestFilter {
        private final JwtTokenService tokens;
        private final JsonAuthFailureHandler failures;

        JwtAuthenticationFilter(JwtTokenService tokens, JsonAuthFailureHandler failures) {
            this.tokens = tokens;
            this.failures = failures;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return request.getRequestURI().startsWith("/internal/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            String header = req.getHeader("Authorization");
            if (header == null || header.isBlank()) { chain.doFilter(req, res); return; }
            if (!header.startsWith("Bearer ") || header.length() == 7) { failures.write401(req, res); return; }
            try {
                AuthenticatedUser user = tokens.verify(header.substring(7));
                var authorities = user.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList());
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
                failures.write401(req, res);
                return;
            }
            chain.doFilter(req, res);
        }
    }

    static final class JsonAuthFailureHandler
            implements org.springframework.security.web.AuthenticationEntryPoint,
                       org.springframework.security.web.access.AccessDeniedHandler {
        private final ObjectMapper mapper;

        JsonAuthFailureHandler(ObjectMapper mapper) { this.mapper = mapper; }

        @Override
        public void commence(HttpServletRequest req, HttpServletResponse res,
                             org.springframework.security.core.AuthenticationException ex) throws IOException {
            write401(req, res);
        }

        @Override
        public void handle(HttpServletRequest req, HttpServletResponse res,
                           org.springframework.security.access.AccessDeniedException ex) throws IOException {
            write(res, HttpStatus.FORBIDDEN, "access denied");
        }

        void write401(HttpServletRequest req, HttpServletResponse res) throws IOException {
            write(res, HttpStatus.UNAUTHORIZED, "authentication required or token invalid");
        }

        private void write(HttpServletResponse res, HttpStatus status, String message) throws IOException {
            res.setStatus(status.value());
            res.setContentType("application/json");
            mapper.writeValue(res.getOutputStream(), new ApiError(status.value(), status.getReasonPhrase(), message));
        }
    }
}
