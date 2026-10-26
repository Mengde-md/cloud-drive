package com.mengde.clouddrive.auth;

import com.mengde.clouddrive.error.InvalidCredentialsException;
import com.mengde.clouddrive.error.RegistrationConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

/**
 * 注册与登录（Day15）。
 */
@Service
public class UserAccountService {

    private static final String DEFAULT_ROLE = "USER";

    private final UserAccountMapper users;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;
    private final EmailVerificationService emailCodes;

    public UserAccountService(UserAccountMapper users, PasswordEncoder encoder, JwtTokenService tokens,
                              EmailVerificationService emailCodes) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.emailCodes = emailCodes;
    }

    /**
     * 注册。口令先过 PasswordEncoder（BCrypt）再落库，数据库里只有摘要。
     * 重名判断依赖唯一键，且冲突必须回滚正确验证码的消费，允许更换用户名重试。
     * 仅验证码等参数拒绝沿用 noRollbackFor，以保留错误验证码尝试次数。
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public UserAccountRow register(String username, String rawPassword, String rawEmail, String code) {
        validatePassword(rawPassword);
        username = username.trim();
        if (username.length() < 3 || username.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名长度应为 3 到 64 个字符");
        }
        String email = EmailVerificationService.normalizeEmail(rawEmail);
        emailCodes.verifyAndConsume(email, EmailVerificationService.Purpose.REGISTER, code);
        UserAccountRow row = new UserAccountRow();
        row.setUsername(username);
        row.setPasswordHash(encoder.encode(rawPassword));
        row.setRole(DEFAULT_ROLE);
        row.setEmail(email);
        try {
            users.insert(row);
        } catch (DuplicateKeyException exception) {
            throw new RegistrationConflictException(exception);
        }
        return users.findById(row.getId()).orElseThrow(IllegalStateException::new);
    }

    /**
     * 登录。
     *
     * <p>用户不存在和口令错误抛的是同一个异常：如果分别返回不同信息，
     * 攻击者就能靠响应差异先把系统里有哪些用户名枚举出来。</p>
     */
    @Transactional(readOnly = true)
    public JwtTokenService.IssuedToken login(String username, String rawPassword) {
        UserAccountRow row = users.findByUsername(username.trim())
                .orElseThrow(InvalidCredentialsException::new);
        if (!row.isEnabled() || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(rawPassword, row.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(row);
    }

    @Transactional(readOnly = true)
    public UserAccountRow requireById(long id) {
        return users.findById(id).orElseThrow(InvalidCredentialsException::new);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void resetPassword(String rawEmail, String code, String password) {
        validatePassword(password);
        String email = EmailVerificationService.normalizeEmail(rawEmail);
        emailCodes.verifyAndConsume(email, EmailVerificationService.Purpose.RESET_PASSWORD, code);
        UserAccountRow row = users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法重置密码，请联系管理员"));
        if (!row.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用，请联系管理员");
        }
        users.updatePassword(row.getId(), encoder.encode(password));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 个字符，UTF-8 编码后不超过 72 字节");
        }
    }
}
