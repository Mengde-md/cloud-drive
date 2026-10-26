package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.UploadSessionApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.UploadSessionRow;
import com.mengde.clouddrive.redis.UploadRequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 分片上传接口 /api/upload-sessions（Day18，Day21 加限流）。
 *
 * <p>典型调用顺序：</p>
 * <ol>
 *   <li>{@code POST /api/upload-sessions} 开会话，拿到 sessionId；</li>
 *   <li>{@code PUT /api/upload-sessions/{id}/chunks/{index}} 按序传每一片；</li>
 *   <li>断线重连时先 {@code GET /api/upload-sessions/{id}} 查已收到的片，只补缺的；</li>
 *   <li>{@code POST /api/upload-sessions/{id}/complete} 触发合并，返回最终文件元数据。</li>
 * </ol>
 *
 * <p>分片用 PUT 而不是 POST：同一个 (sessionId, index) 重复提交的语义是"把这一片放在这个位置"，
 * 是幂等的，PUT 更贴切，也让客户端可以放心重试。</p>
 */
@RestController
@RequestMapping("/api/upload-sessions")
public class UploadSessionController {

    private final UploadSessionApplicationService sessions;
    private final UploadRequestRateLimiter rateLimiter;

    public UploadSessionController(UploadSessionApplicationService sessions,
                                   UploadRequestRateLimiter rateLimiter) {
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> start(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody StartSessionRequest request) {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.START_SESSION);
        UploadSessionRow session = sessions.start(user, request.parentId(),
                request.displayName(), request.totalChunks());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session));
    }

    @PutMapping("/{sessionId}/chunks/{chunkIndex}")
    public UploadSessionApplicationService.ChunkResult putChunk(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("sessionId") String sessionId,
            @PathVariable("chunkIndex") int chunkIndex,
            @RequestParam("file") MultipartFile file) throws IOException {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.PUT_CHUNK);
        return sessions.putChunk(user, sessionId, chunkIndex, file);
    }

    /** 断点续传查询：已收到哪些片、共收了多少字节。 */
    @GetMapping("/{sessionId}")
    public UploadSessionApplicationService.SessionStatus status(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("sessionId") String sessionId) {
        return sessions.status(user, sessionId);
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<DocumentUploadController.UploadResponse> complete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("sessionId") String sessionId) throws IOException {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.COMPLETE_SESSION);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                DocumentUploadController.UploadResponse.from(
                        sessions.complete(user, sessionId), false));
    }

    public record StartSessionRequest(
            @Positive(message = "parentId must be positive when present") Long parentId,
            @NotBlank(message = "displayName must not be blank") String displayName,
            @Positive(message = "totalChunks must be positive")
            @Max(value = 10000, message = "totalChunks must not exceed 10000")
            int totalChunks) {
    }

    public record SessionResponse(String sessionId, Long parentId, String displayName,
                                  int totalChunks, String status) {
        public static SessionResponse from(UploadSessionRow row) {
            return new SessionResponse(row.getId(), row.getParentId(), row.getDisplayName(),
                    row.getTotalChunks(), row.getStatus());
        }
    }
}
