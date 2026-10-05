package com.mengde.clouddrive.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class SummaryController {
    private final SummaryService service;
    public SummaryController(SummaryService service) { this.service = service; }

    @PostMapping("/summary")
    public SummaryService.Result summary(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Input input) {
        return service.summarize(Long.parseLong(user.getSubject()), input.text());
    }

    @PostMapping(value = "/summary/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Input input) {
        return service.stream(Long.parseLong(user.getSubject()), input.text());
    }

    public record Input(@NotBlank @Size(max = 4096) String text, @AssertTrue boolean allowExternalProcessing) { }
}
