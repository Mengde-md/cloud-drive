package com.mengde.clouddrive.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/ai/rag")
public class RagController {
    private final RagIndex index;
    private final RagService service;
    public RagController(RagIndex index, RagService service) { this.index = index; this.service = service; }

    @PostMapping("/documents") @ResponseStatus(HttpStatus.CREATED)
    public RagIndex.DocumentInfo ingest(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Import input) {
        return index.ingest(Long.parseLong(user.getSubject()), input.name(), input.contentBase64());
    }
    @GetMapping("/documents")
    public List<RagIndex.DocumentInfo> list(@AuthenticationPrincipal Jwt user) {
        return index.list(Long.parseLong(user.getSubject()));
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt user, @PathVariable("id") String id) {
        index.delete(Long.parseLong(user.getSubject()), id);
    }
    @PostMapping("/search")
    public RagIndex.Retrieval search(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Query input) {
        return index.search(Long.parseLong(user.getSubject()), input.question());
    }
    @PostMapping("/answer")
    public RagService.Result answer(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Ask input) {
        return service.ask(Long.parseLong(user.getSubject()), input.question(), input.allowExternalProcessing());
    }

    public record Import(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 44000) String contentBase64) { }
    public record Query(@NotBlank @Size(max = 512) String question) { }
    public record Ask(@NotBlank @Size(max = 512) String question, @AssertTrue boolean allowExternalProcessing) { }
}
