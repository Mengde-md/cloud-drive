package com.mengde.clouddrive.userservice;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/users/{id}/quota")
class QuotaController {
    private final QuotaService service;

    QuotaController(QuotaService service) { this.service = service; }

    @PostMapping("/reserve")
    String reserve(@PathVariable("id") long userId, @Valid @RequestBody QuotaService.ReserveRequest request) {
        return service.reserve(userId, request);
    }

    @PostMapping("/{reservationId}/commit")
    ResponseEntity<Void> commit(@PathVariable("id") long userId, @PathVariable("reservationId") String reservationId) {
        service.commit(userId, reservationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{reservationId}/release")
    ResponseEntity<Void> release(@PathVariable("id") long userId, @PathVariable("reservationId") String reservationId) {
        service.release(userId, reservationId);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(QuotaService.QuotaDeniedException.class)
    ResponseEntity<String> quotaDenied() { return ResponseEntity.status(HttpStatus.CONFLICT).body("quota exceeded"); }

    @ExceptionHandler(feign.FeignException.class)
    ResponseEntity<String> unavailable() { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("identity service unavailable"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException ignored) { return ResponseEntity.badRequest().body("invalid request"); }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<String> conflict(IllegalStateException ignored) { return ResponseEntity.status(HttpStatus.CONFLICT).body("reservation conflict"); }
}
