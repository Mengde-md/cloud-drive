package com.mengde.clouddrive.authservice;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 签发与校验 JWT。
 *
 * **本类与单体的 JwtTokenService 必须在 claim 结构上逐字节等价**，否则会出现一个
 * 极难排查的故障：用户在 auth-service 登录拿到 token，带着它请求 /api/entries，
 * 网关验签通过、单体也验签通过，但 subject/roles 的解析口径不同，导致"认证成功但
 * 身份是错的"。因此下面三处必须与 com.mengde.clouddrive.auth.JwtTokenService 一致：
 *   issuer = "cloud-drive-learning"（单体 jwtDecoder 用 JwtValidators.createDefaultWithIssuer
 *            校验这个值，写错会直接 401）
 *   subject = 用户 id 的十进制字符串（单体用 Long.parseLong(jwt.getSubject()) 解析）
 *   claims  = username(String)、roles(List<String>)
 * 签名算法同为 HS256，密钥同为 clouddrive.security.jwt.secret —— 两个进程共享同一个
 * 对称密钥，这是对称签名的固有代价，已在 docs/architecture/day25 里作为已知局限记录。
 */
@Service
public class JwtTokenService {

    static final String ISSUER = "cloud-drive-learning";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder encoder, JwtDecoder decoder, Duration ttl) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.ttl = ttl;
    }

    public IssuedToken issue(UserAccountRow user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("roles", List.of(user.getRole()))
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    public AuthenticatedUser verify(String token) {
        Jwt jwt = decoder.decode(token);
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(
                Long.parseLong(jwt.getSubject()),
                jwt.getClaimAsString("username"),
                roles == null ? List.of() : roles);
    }

    public record IssuedToken(String value, Instant expiresAt) { }
}
