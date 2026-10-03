package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DocumentUploadApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.upload.UploadSessionApplicationService;
import com.mengde.clouddrive.upload.UploadSessionRow;
import com.mengde.clouddrive.redis.UploadRequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/upload-sessions")
public class UploadSessionController {
    private final UploadSessionApplicationService uploads;
    private final UploadRequestRateLimiter rateLimiter;
    public UploadSessionController(UploadSessionApplicationService uploads, UploadRequestRateLimiter rateLimiter) {
        this.uploads = uploads; this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> start(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody StartUploadRequest request) {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.START_SESSION);
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(
                uploads.start(user, request.parentId(), request.displayName(), request.totalChunks())));
    }

    @PostMapping("/{sessionId}/chunks/{chunkIndex}")
    public ResponseEntity<ChunkResponse> putChunk(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable("sessionId") String sessionId,
                                                   @PathVariable("chunkIndex") int chunkIndex,
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.PUT_CHUNK);
        var result = uploads.putChunk(user, sessionId, chunkIndex, file);
        return ResponseEntity.status(result.alreadyReceived() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new ChunkResponse(result.chunkIndex(), result.byteSize(), result.md5(), result.alreadyReceived()));
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<DocumentUploadController.UploadResponse> complete(@AuthenticationPrincipal AuthenticatedUser user,
                                                                              @PathVariable("sessionId") String sessionId) throws IOException {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.COMPLETE_SESSION);
        StoredDocumentRow row = uploads.complete(user, sessionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentUploadController.UploadResponse.from(row, false));
    }

    public record StartUploadRequest(Long parentId, @NotBlank String displayName, @Positive int totalChunks) { }
    public record SessionResponse(String id, Long parentId, String displayName, int totalChunks, String status) {
        static SessionResponse from(UploadSessionRow row) { return new SessionResponse(row.getId(), row.getParentId(), row.getDisplayName(), row.getTotalChunks(), row.getStatus()); }
    }
    public record ChunkResponse(int chunkIndex, long byteSize, String md5, boolean alreadyReceived) { }
}
