package com.mengde.clouddrive.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminAccountService {
    /** Administrative per-account ceiling, not a promise of available physical disk space. */
    public static final long MAX_QUOTA_BYTES = 100L * 1024 * 1024 * 1024;

    private final UserAccountMapper users;
    private final PasswordEncoder passwords;

    public AdminAccountService(UserAccountMapper users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public UserPage list(long actorId, int page, int size, String query) {
        requireAdmin(actorId);
        if (page < 0 || size < 1 || size > 100 || query == null || query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页或搜索参数无效");
        }
        String pattern = "%" + query.trim() + "%";
        List<ManagedUser> items = users.list(pattern, (long) page * size, size).stream()
                .map(ManagedUser::from).toList();
        return new UserPage(items, users.count(pattern), page, size);
    }

    @Transactional
    public ManagedUser resetPassword(long actorId, long targetId, String password) {
        users.lockSecurityGuard();
        requireAdmin(actorId);
        UserAccountService.validatePassword(password);
        lockTarget(targetId);
        users.updatePassword(targetId, passwords.encode(password));
        return ManagedUser.from(users.findById(targetId).orElseThrow());
    }

    @Transactional
    public ManagedUser setEnabled(long actorId, long targetId, boolean enabled) {
        users.lockSecurityGuard();
        requireAdmin(actorId);
        UserAccountRow target = lockTarget(targetId);
        if (!enabled && actorId == targetId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用自己的账号");
        }
        if (target.isEnabled() != enabled) {
            protectLastAdmin(target, enabled, target.getRole());
            users.updateEnabled(targetId, enabled);
        }
        return ManagedUser.from(users.findById(targetId).orElseThrow());
    }

    @Transactional
    public ManagedUser setRole(long actorId, long targetId, String role) {
        users.lockSecurityGuard();
        requireAdmin(actorId);
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色只能为 USER 或 ADMIN");
        }
        UserAccountRow target = lockTarget(targetId);
        if (!target.getRole().equals(role)) {
            protectLastAdmin(target, target.isEnabled(), role);
            users.updateRole(targetId, role);
        }
        return ManagedUser.from(users.findById(targetId).orElseThrow());
    }

    /** Set the absolute total, so retrying a successful request never adds capacity twice. */
    @Transactional
    public ManagedUser expandQuota(long actorId, long targetId, long quotaBytes) {
        // Match the role/status mutation lock order so an actor cannot be demoted mid-operation.
        users.lockSecurityGuard();
        requireAdmin(actorId);
        if (quotaBytes <= 0 || quotaBytes > MAX_QUOTA_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "总容量必须大于0且不超过100 GiB");
        }
        // This is also the row locked by QuotaGuard during uploads and share transfers.
        UserAccountRow target = lockTarget(targetId);
        if (quotaBytes < target.getQuotaBytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅支持扩容，新容量不能小于当前容量，请刷新后重试");
        }
        if (quotaBytes > target.getQuotaBytes()) {
            if (users.updateQuota(targetId, quotaBytes) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "账号配额已变化，请刷新后重试");
            }
        }
        return ManagedUser.from(users.findById(targetId).orElseThrow());
    }

    private void protectLastAdmin(UserAccountRow target, boolean nextEnabled, String nextRole) {
        if (target.isEnabled() && "ADMIN".equals(target.getRole())
                && (!nextEnabled || !"ADMIN".equals(nextRole)) && users.countEnabledAdmins() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "至少需要保留一个启用的管理员账号");
        }
    }

    private UserAccountRow lockTarget(long id) {
        return users.lockById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private void requireAdmin(long id) {
        UserAccountRow actor = users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员身份"));
        if (!actor.isEnabled() || !"ADMIN".equals(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员身份");
        }
    }

    /** Explicit one-time bootstrap. Existing ordinary accounts are never silently elevated. */
    @Transactional
    public void bootstrap(String username, String password, String email) {
        users.lockSecurityGuard();
        UserAccountService.validatePassword(password);
        username = username.trim();
        if (username.length() < 3 || username.length() > 64) {
            throw new IllegalStateException("Bootstrap admin username must contain 3 to 64 characters");
        }
        email = EmailVerificationService.normalizeEmail(email);
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            if (!"ADMIN".equals(existing.get().getRole())) {
                throw new IllegalStateException("Bootstrap username already belongs to an ordinary account");
            }
            return; // Restarting never overwrites an administrator's current password or state.
        }
        if (users.findByEmail(email).isPresent()) {
            throw new IllegalStateException("Bootstrap admin email is already registered");
        }
        UserAccountRow row = new UserAccountRow();
        row.setUsername(username);
        row.setEmail(email);
        row.setRole("ADMIN");
        row.setPasswordHash(passwords.encode(password));
        users.insert(row);
    }

    public record ManagedUser(long id, String username, String email, String role, boolean enabled,
                              long quotaBytes, LocalDateTime createdAt) {
        static ManagedUser from(UserAccountRow row) {
            return new ManagedUser(row.getId(), row.getUsername(), row.getEmail(), row.getRole(),
                    row.isEnabled(), row.getQuotaBytes(), row.getCreatedAt());
        }
    }

    public record UserPage(List<ManagedUser> items, long total, int page, int size) {}
}
