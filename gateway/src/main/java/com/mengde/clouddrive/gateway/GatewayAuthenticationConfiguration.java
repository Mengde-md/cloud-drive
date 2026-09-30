package com.mengde.clouddrive.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(GatewayAuthenticationProperties.class)
public class GatewayAuthenticationConfiguration {

    /**
     * 网关持有的是**验签**能力，不是签发能力：这里只建 decoder，没有 encoder。
     * 对称密钥（HS256）的固有代价是"能验就能签"——网关理论上可以伪造 token。
     * 真正的最小权限要换成非对称（RS256/ES256），网关只拿公钥。
     * 本日不做这个切换，原因与代价写在
     * docs/architecture/day25-auth-split-and-gateway-authn.md 的"已知局限"。
     */
    @Bean
    ReactiveJwtDecoder gatewayJwtDecoder(@Value("${clouddrive.security.jwt.secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("CLOUDDRIVE_JWT_SECRET must be at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        // issuer 校验必须显式加上。只验签名不验 issuer 意味着：任何一个恰好用同一把
        // 密钥签出来的 token（比如另一个系统复用了这个 secret）都会被接受。
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cloud-drive-learning"));
        return decoder;
    }

    @Bean
    AuthenticationGlobalFilter authenticationGlobalFilter(ReactiveJwtDecoder decoder,
                                                          GatewayAuthenticationProperties properties,
                                                          ObjectMapper json) {
        return new AuthenticationGlobalFilter(decoder, properties, json);
    }
}
