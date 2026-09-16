package com.mengde.clouddrive.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class AuthService {
    private final UserAccountMapper mapper; private final PasswordEncoder encoder; private final JwtTokenService tokens;
    public AuthService(UserAccountMapper mapper, PasswordEncoder encoder, JwtTokenService tokens) { this.mapper = mapper; this.encoder = encoder; this.tokens = tokens; }
    public UserAccountRow register(String username, String password) {
        UserAccountRow row = new UserAccountRow(); row.setUsername(normalize(username)); row.setPasswordHash(encoder.encode(password)); row.setRole("USER");
        try { mapper.insert(row); return row; } catch (DuplicateKeyException e) { throw new UsernameAlreadyExistsException(); }
    }
    public JwtTokenService.IssuedToken login(String username, String password) {
        UserAccountRow row = mapper.findByUsername(normalize(username)).orElseThrow(InvalidCredentialsException::new);
        if (!encoder.matches(password, row.getPasswordHash())) throw new InvalidCredentialsException();
        return tokens.issue(row);
    }
    private String normalize(String username) { return username.trim().toLowerCase(Locale.ROOT); }
}
