package com.mengde.clouddrive.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }
    @PostMapping("/register") public ResponseEntity<UserResponse> register(@Valid @RequestBody Credentials request) { UserAccountRow u = service.register(request.username(), request.password()); return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(u)); }
    @PostMapping("/login") public TokenResponse login(@Valid @RequestBody Credentials request) { JwtTokenService.IssuedToken t = service.login(request.username(), request.password()); return new TokenResponse(t.value(), t.expiresAt()); }
    @GetMapping("/me") public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) { return new CurrentUserResponse(user.id(), user.username(), user.roles()); }
    public record Credentials(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,40}") String username, @NotBlank @Size(min=8,max=72) String password) { }
    public record UserResponse(long id, String username, String role) { static UserResponse from(UserAccountRow u) { return new UserResponse(u.getId(),u.getUsername(),u.getRole()); } }
    public record TokenResponse(String accessToken, Instant expiresAt) { }
    public record CurrentUserResponse(long id, String username, List<String> roles) { }
}
