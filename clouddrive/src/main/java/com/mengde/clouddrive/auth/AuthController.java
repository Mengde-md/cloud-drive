package com.mengde.clouddrive.auth;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 认证接口（Day15）与账号概览（Day16 的配额可见性）。
 *
 * <p>响应体里永远不包含 passwordHash：DTO 手工挑字段，而不是把行对象直接序列化出去。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAccountService accounts;
    private final DriveEntryRepository entries;
    private final EmailVerificationService emailCodes;
    private final EmailRequestRateLimiter emailRequests;

    public AuthController(UserAccountService accounts, DriveEntryRepository entries,
                          EmailVerificationService emailCodes, EmailRequestRateLimiter emailRequests) {
        this.accounts = accounts;
        this.entries = entries;
        this.emailCodes = emailCodes;
        this.emailRequests = emailRequests;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody Registration request) {
        UserAccountRow user = accounts.register(request.username(), request.password(), request.email(), request.code());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PostMapping("/email-code")
    public EmailCodeResponse sendEmailCode(@Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        // Never parse client headers here. In deploy, Tomcat resolves the address only through
        // the trusted loopback proxy; otherwise forwarding headers are ignored by default.
        emailRequests.requirePermit(http.getRemoteAddr());
        emailCodes.send(request.email(), request.purpose());
        return new EmailCodeResponse("若该邮箱符合条件，验证码已发送，请检查收件箱及垃圾邮件",
                EmailVerificationService.COOLDOWN_SECONDS, EmailVerificationService.EXPIRY_SECONDS);
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accounts.resetPassword(request.email(), request.code(), request.password());
        return new MessageResponse("密码已重置，请使用新密码登录");
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Credentials request) {
        JwtTokenService.IssuedToken token = accounts.login(request.username(), request.password());
        return new TokenResponse(token.value(), token.expiresAt());
    }

    /** 当前身份 + 配额使用情况。身份来自令牌，不接受调用方传入的 userId。 */
    @GetMapping("/me")
    public AccountOverview me(@AuthenticationPrincipal AuthenticatedUser user) {
        UserAccountRow row = accounts.requireById(user.id());
        long used = entries.usedBytesByOwner(user.id());
        return new AccountOverview(row.getId(), row.getUsername(), row.getRole(),
                row.getQuotaBytes(), used, row.getQuotaBytes() - used, row.getEmail(), row.isEnabled());
    }

    public record Credentials(
            @NotBlank(message = "username must not be blank")
            @Size(min = 3, max = 64, message = "username length must be between 3 and 64")
            String username,

            @NotBlank(message = "password must not be blank")
            @Size(min = 8, max = 72, message = "password length must be between 8 and 72")
            String password) {
    }

    public record Registration(@NotBlank @Size(min = 3, max = 64) String username,
                               @NotBlank @Size(min = 8, max = 72) String password,
                               @NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    public record EmailCodeRequest(@NotBlank @Email @Size(max = 254) String email,
                                   @NotNull EmailVerificationService.Purpose purpose) {}

    public record ResetPasswordRequest(@NotBlank @Email @Size(max = 254) String email,
                                       @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                                       @NotBlank @Size(min = 8, max = 72) String password) {}

    public record EmailCodeResponse(String message, int retryAfterSeconds, int expiresInSeconds) {}

    public record MessageResponse(String message) {}

    public record UserResponse(long id, String username, String role, String email) {
        public static UserResponse from(UserAccountRow row) {
            return new UserResponse(row.getId(), row.getUsername(), row.getRole(), row.getEmail());
        }
    }

    public record TokenResponse(String accessToken, Instant expiresAt) {
    }

    public record AccountOverview(long id, String username, String role,
                                  long quotaBytes, long usedBytes, long availableBytes, String email, boolean enabled) {
    }
}
