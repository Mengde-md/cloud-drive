package com.mengde.clouddrive.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 签发与校验 JWT（Day15）。
 *
 * <p>每次请求还会校验数据库中的启用状态和令牌版本。重置密码、停用或调整角色后，
 * 已签发的令牌立即失效；权限始终来自数据库当前记录。</p>
 *
 * <p>这里用对称密钥（HS256）只是为了学习环境简单；密钥通过配置注入，不落代码。</p>
 */
@Service
public class JwtTokenService {

    private static final String ISSUER = "cloud-drive-learning";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final UserAccountMapper users;

    public JwtTokenService(JwtProperties properties, UserAccountMapper users) {
        this.users = users;
        SecretKey key = new SecretKeySpec(
                properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = decoder;
        this.ttl = Duration.ofMinutes(properties.getTtlMinutes());
    }

    /** 登录成功后签发令牌。subject 放用户 id，后续所有归属判断都以它为准。 */
    public IssuedToken issue(UserAccountRow user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLES, List.of(user.getRole()))
                .claim("ver", user.getTokenVersion())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * 校验令牌并还原身份。
     *
     * <p>decode 内部已经校验签名与过期时间，任何不合法的令牌都会抛 JwtException；
     * 这里把它统一翻译成 InvalidTokenException，避免把底层异常细节暴露给调用方。</p>
     */
    public AuthenticatedUser verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            long id = Long.parseLong(jwt.getSubject());
            UserAccountRow row = users.findById(id).orElseThrow(() -> new JwtException("account unavailable"));
            Object version = jwt.getClaim("ver");
            if (!row.isEnabled() || !(version instanceof Number number)
                    || number.longValue() != row.getTokenVersion()) {
                throw new JwtException("account session revoked");
            }
            return new AuthenticatedUser(id, row.getUsername(), List.of(row.getRole()));
        } catch (JwtException | NumberFormatException exception) {
            throw new InvalidTokenException(exception);
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    /** 令牌缺失、签名不对、已过期或格式非法。由 Security 的失败处理器转成 401。 */
    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(Throwable cause) {
            super("access token is missing, malformed or expired", cause);
        }
    }
}
