package com.mengde.clouddrive.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class EmailVerificationService {
    public enum Purpose { REGISTER, RESET_PASSWORD }
    public static final int COOLDOWN_SECONDS = 60;
    public static final int EXPIRY_SECONDS = 600;
    public static final int MAX_ATTEMPTS = 5;
    private final EmailVerificationMapper codes;
    private final UserAccountMapper users;
    private final SmtpCodeMailer mailer;
    private final byte[] pepper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public EmailVerificationService(EmailVerificationMapper codes, UserAccountMapper users,
                                    SmtpCodeMailer mailer, JwtProperties jwt) {
        this(codes, users, mailer, jwt, Clock.systemUTC());
    }

    EmailVerificationService(EmailVerificationMapper codes, UserAccountMapper users,
                             SmtpCodeMailer mailer, JwtProperties jwt, Clock clock) {
        this.codes = codes;
        this.users = users;
        this.mailer = mailer;
        this.pepper = jwt.getSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.trim().length() > 254
                || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的邮箱地址");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public void send(String rawEmail, Purpose purpose) {
        String email = normalizeEmail(rawEmail);
        mailer.requireConfigured();
        // Every valid address has the same first-request and cooldown behavior. Returning
        // before this lock for ineligible recipients would expose account existence via 429.
        codes.ensureRow(email, purpose.name());
        EmailVerificationRow row = codes.lock(email, purpose.name()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.getSentAt() != null && row.getSentAt().plusSeconds(COOLDOWN_SECONDS).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送频繁，请 60 秒后重试");
        }
        var account = users.findByEmail(email);
        boolean eligible = purpose == Purpose.REGISTER ? account.isEmpty()
                : account.isPresent() && account.get().isEnabled();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        row.setCodeHash(eligible ? hash(email, purpose, code) : null);
        row.setSentAt(now);
        row.setExpiresAt(now.plusSeconds(EXPIRY_SECONDS));
        row.setAttempts(0);
        // An ineligible address gets only an unusable cooldown marker, never a valid code.
        row.setConsumed(!eligible);
        codes.save(row);
        if (!eligible) return;
        // The row remains locked during SMTP; a failure rolls back the code and cooldown.
        mailer.sendVerification(email, purpose, code);
    }

    /** Call within the account transaction. Wrong-attempt counters must commit on rejection. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void verifyAndConsume(String email, Purpose purpose, String code) {
        EmailVerificationRow row = codes.lock(email, purpose.name()).orElseThrow(this::invalidCode);
        if (row.isConsumed() || row.getExpiresAt() == null
                || !row.getExpiresAt().isAfter(LocalDateTime.now(clock)) || row.getAttempts() >= MAX_ATTEMPTS) {
            throw invalidCode();
        }
        row.setAttempts(row.getAttempts() + 1);
        boolean matches = code != null && code.matches("[0-9]{6}") && MessageDigest.isEqual(
                row.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(email, purpose, code).getBytes(StandardCharsets.UTF_8));
        if (matches) row.setConsumed(true);
        codes.save(row);
        if (!matches) throw invalidCode();
    }

    private ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效、已使用或已过期，请重新获取");
    }

    private String hash(String email, Purpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (email + "\n" + purpose.name() + "\n" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to protect verification code", exception);
        }
    }
}
