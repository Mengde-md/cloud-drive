package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReviewController {
    private final FileAnalysisRepository repository;
    private final AnalysisSettingsService settings;
    public AdminReviewController(FileAnalysisRepository repository, AnalysisSettingsService settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @GetMapping("/api/admin/reviews")
    public FileAnalysisRepository.ReviewPage list(@RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) { throw new IllegalArgumentException("页码必须非负，每页1至100条。"); }
        return repository.reviews(status, page, size);
    }

    @PostMapping("/api/admin/reviews/{entryId}")
    public FileAnalysisResponse review(@AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable("entryId") long entryId, @Valid @RequestBody ReviewDecision request) {
        return repository.review(entryId, admin.id(), request.decision(), request.reason().trim());
    }

    @GetMapping("/api/admin/analysis-settings")
    public ReviewSettings settings() { return new ReviewSettings(settings.reviewEnabled()); }

    @PutMapping("/api/admin/analysis-settings")
    public ReviewSettings update(@Valid @RequestBody ReviewSettings request) {
        settings.setReviewEnabled(request.reviewEnabled());
        return new ReviewSettings(settings.reviewEnabled());
    }

    public record ReviewDecision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
                                 @NotBlank @Size(max = 1000) String reason) { }
    public record ReviewSettings(@NotNull Boolean reviewEnabled) { }
}
