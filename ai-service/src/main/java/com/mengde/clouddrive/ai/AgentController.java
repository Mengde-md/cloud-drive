package com.mengde.clouddrive.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/ai/agent")
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service) { this.service = service; }
    @GetMapping("/tools") public List<AgentToolCatalog.Definition> tools() { return service.catalog(); }
    @PostMapping("/ask") public AgentService.Result ask(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Ask input) {
        return service.ask(Long.parseLong(user.getSubject()), input.question(), input.allowExternalProcessing());
    }
    public record Ask(@NotBlank @Size(max = 512) String question, @AssertTrue boolean allowExternalProcessing) { }
}
