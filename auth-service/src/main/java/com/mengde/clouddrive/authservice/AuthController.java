package com.mengde.clouddrive.authservice;

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

/**
 * 对外契约，路径与响应体与单体**逐字段相同**。
 *
 * Day23 第 2.1 节把这一点写成硬约束："保持现有路径不变，拆分时零迁移成本"。
 * 于是网关把 /api/auth/** 从单体改指向本服务时，前端与既有测试一行都不用改。
 *
 * 一处必须如实记录的**文档与代码不一致**：Day23 文档写的响应是 {token, expiresIn}
 * 与 /api/auth/me 返回 {id, username, role}；而 Day15 起真实落地的单体代码返回的是
 * {accessToken, expiresAt} 与 {id, username, roles[]}。本类跟随**真实代码**而不是文档，
 * 因为跟随文档会破坏"零迁移成本"这条更根本的约束。差异已在 docs/day25/Final.md 记录。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody Credentials request) {
        UserAccountRow u = service.register(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(u));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Credentials request) {
        JwtTokenService.IssuedToken t = service.login(request.username(), request.password());
        return new TokenResponse(t.value(), t.expiresAt());
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new CurrentUserResponse(user.id(), user.username(), user.roles());
    }

    public record Credentials(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,40}") String username,
            @NotBlank @Size(min = 8, max = 72) String password) { }

    public record UserResponse(long id, String username, String role) {
        static UserResponse from(UserAccountRow u) { return new UserResponse(u.getId(), u.getUsername(), u.getRole()); }
    }

    public record TokenResponse(String accessToken, Instant expiresAt) { }

    public record CurrentUserResponse(long id, String username, List<String> roles) { }
}
