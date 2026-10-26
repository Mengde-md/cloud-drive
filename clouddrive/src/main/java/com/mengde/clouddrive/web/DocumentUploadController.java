package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DocumentUploadApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.redis.UploadRequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件上传接口 /api/files（Day17，Day21 加限流）。
 *
 * <p>限流放在 Controller 而不是 Service：限流是"入口保护"，
 * 目的是在真正开始消耗磁盘和数据库之前就把超额请求挡掉。
 * 放到 Service 里意味着文件已经被读进来了，保护效果大打折扣。</p>
 */
@RestController
@RequestMapping("/api/files")
// @Validated 不能省：@RequestParam 上的 @NotBlank 属于方法参数级约束，
// 没有它就不会被校验——这是"写了注解但根本没生效"的经典坑。
@Validated
public class DocumentUploadController {

    private final DocumentUploadApplicationService uploads;
    private final UploadRequestRateLimiter rateLimiter;

    public DocumentUploadController(DocumentUploadApplicationService uploads,
                                    UploadRequestRateLimiter rateLimiter) {
        this.uploads = uploads;
        this.rateLimiter = rateLimiter;
    }

    /** 普通 multipart 上传。displayName 与 parentId 走表单字段，文件本身走 file 字段。 */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "parentId", required = false) Long parentId,
            @RequestParam("displayName") @NotBlank String displayName,
            @RequestParam("file") MultipartFile file) throws IOException {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.DIRECT_UPLOAD);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UploadResponse.from(uploads.upload(user, parentId, displayName, file), false));
    }

    /**
     * 秒传：只传 MD5，不传文件体。
     *
     * <p>命中则直接建一条指向同一对象的元数据（201 + instant=true），
     * 未命中返回 404，客户端据此退回普通上传。</p>
     */
    @PostMapping("/instant")
    public ResponseEntity<UploadResponse> instant(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody InstantUploadRequest request) {
        rateLimiter.requirePermit(user.id(), UploadRequestRateLimiter.Operation.INSTANT_UPLOAD);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UploadResponse.from(uploads.instantUpload(
                        user, request.parentId(), request.displayName(), request.md5()), true));
    }

    public record InstantUploadRequest(
            @Positive(message = "parentId must be positive when present") Long parentId,
            @NotBlank(message = "displayName must not be blank") String displayName,
            // 只接受 32 位十六进制：非法格式在进业务之前就被拦掉，避免脏值进 SQL 查询。
            @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "md5 must be a 32-character hex string")
            String md5) {
    }

    public record UploadResponse(long id, Long parentId, String displayName,
                                 long byteSize, String md5, boolean instant) {
        public static UploadResponse from(StoredDocumentRow row, boolean instant) {
            return new UploadResponse(row.getId(), row.getParentId(), row.getDisplayName(),
                    row.getByteSize(), row.getContentMd5(), instant);
        }
    }
}
