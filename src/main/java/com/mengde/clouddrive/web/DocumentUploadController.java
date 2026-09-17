package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DocumentUploadApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class DocumentUploadController {
    private final DocumentUploadApplicationService uploads;
    public DocumentUploadController(DocumentUploadApplicationService uploads) { this.uploads = uploads; }

    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(value = "parentId", required = false) Long parentId, @RequestParam("displayName") @NotBlank String displayName,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(uploads.upload(user, parentId, displayName, file), false));
    }
    @PostMapping("/instant")
    public ResponseEntity<UploadResponse> instant(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InstantUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(uploads.instantUpload(user, request.parentId(), request.displayName(), request.md5()), true));
    }
    public record InstantUploadRequest(@Positive Long parentId, @NotBlank String displayName, @Pattern(regexp = "[a-fA-F0-9]{32}") String md5) { }
    public record UploadResponse(long id, long ownerId, Long parentId, String displayName, long byteSize, String md5, boolean instant) {
        static UploadResponse from(StoredDocumentRow row, boolean instant) { return new UploadResponse(row.getId(), row.getOwnerId(), row.getParentId(), row.getDisplayName(), row.getByteSize(), row.getContentMd5(), instant); }
    }
}
