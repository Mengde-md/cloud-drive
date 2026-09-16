package com.mengde.clouddrive.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class JwtTokenService {
    private final JwtEncoder encoder; private final JwtDecoder decoder; private final Duration ttl;
    public JwtTokenService(JwtEncoder encoder, JwtDecoder decoder, Duration ttl) { this.encoder = encoder; this.decoder = decoder; this.ttl = ttl; }
    public IssuedToken issue(UserAccountRow user) {
        Instant now = Instant.now(); Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("cloud-drive-learning").issuedAt(now).expiresAt(expiresAt)
                .subject(user.getId().toString()).claim("username", user.getUsername()).claim("roles", List.of(user.getRole())).build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }
    public AuthenticatedUser verify(String token) {
        Jwt jwt = decoder.decode(token);
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("username"), roles == null ? List.of() : roles);
    }
    public record IssuedToken(String value, Instant expiresAt) { }
}
