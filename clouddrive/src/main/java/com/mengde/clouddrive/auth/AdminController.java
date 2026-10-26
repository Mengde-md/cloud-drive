package com.mengde.clouddrive.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminController {
    private final AdminAccountService accounts;

    public AdminController(AdminAccountService accounts) { this.accounts = accounts; }

    @GetMapping
    public AdminAccountService.UserPage list(@AuthenticationPrincipal AuthenticatedUser actor,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(defaultValue = "") String query) {
        return accounts.list(actor.id(), page, size, query);
    }

    @PatchMapping("/{id}/password")
    public AdminAccountService.ManagedUser password(@AuthenticationPrincipal AuthenticatedUser actor,
                                                      @PathVariable long id,
                                                      @Valid @RequestBody PasswordRequest request) {
        return accounts.resetPassword(actor.id(), id, request.password());
    }

    @PatchMapping("/{id}/status")
    public AdminAccountService.ManagedUser status(@AuthenticationPrincipal AuthenticatedUser actor,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody StatusRequest request) {
        return accounts.setEnabled(actor.id(), id, request.enabled());
    }

    @PatchMapping("/{id}/role")
    public AdminAccountService.ManagedUser role(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @PathVariable long id,
                                                  @Valid @RequestBody RoleRequest request) {
        return accounts.setRole(actor.id(), id, request.role());
    }

    @PatchMapping("/{id}/quota")
    public AdminAccountService.ManagedUser quota(@AuthenticationPrincipal AuthenticatedUser actor,
                                                   @PathVariable long id,
                                                   @Valid @RequestBody QuotaRequest request) {
        return accounts.expandQuota(actor.id(), id, request.quotaBytes());
    }

    public record QuotaRequest(
            @NotNull(message = "请填写扩容后的总容量")
            @Positive(message = "总容量必须大于0")
            @Max(value = AdminAccountService.MAX_QUOTA_BYTES, message = "总容量不能超过100 GiB")
            Long quotaBytes) {}

    public record PasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {}
    public record StatusRequest(@NotNull Boolean enabled) {}
    public record RoleRequest(@NotBlank @Pattern(regexp = "USER|ADMIN") String role) {}
}
