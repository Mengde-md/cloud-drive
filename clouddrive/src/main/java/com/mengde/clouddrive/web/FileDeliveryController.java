package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.FileDeliveryService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.persistence.FileShareRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 下载、分享与分享转存接口（Day19 + 转存补充）。
 *
 * <p>匿名可访问的只有两个：查看分享信息和下载分享文件——提取码本身就是凭证。
 * 转存必须登录（要存到某个人的网盘里，得先知道是谁）。</p>
 *
 * <p>响应头是下载接口的重点：</p>
 * <ul>
 *   <li><b>Accept-Ranges: bytes</b> —— 告诉客户端本接口支持范围请求；</li>
 *   <li><b>Content-Length</b> —— 必须是本次真正发送的字节数（end-start+1），
 *       不是文件总大小，否则客户端会一直等剩下的数据直到超时；</li>
 *   <li><b>Content-Range: bytes start-end/total</b> —— 仅在 206 时出现；</li>
 *   <li><b>Content-Disposition</b> —— 文件名用 RFC 5987 的 {@code filename*=UTF-8''...} 形式，
 *       否则中文名在多数浏览器上会变成乱码或被截断。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class FileDeliveryController {

    private final FileDeliveryService delivery;

    public FileDeliveryController(FileDeliveryService delivery) {
        this.delivery = delivery;
    }

    /* ---------------- 本人文件下载 ---------------- */

    @GetMapping("/files/{id}/download")
    public ResponseEntity<InputStreamResource> owner(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("id") long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return response(delivery.ownerDownload(user.id(), id, range), range != null);
    }

    /* ---------------- 分享管理（所有者） ---------------- */

    @PostMapping("/shares")
    public ResponseEntity<ShareResponse> share(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateShareRequest request) {
        FileShareRow row = delivery.create(user.id(), request.documentId(),
                request.passcode(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShareResponse.from(row));
    }

    @GetMapping("/shares")
    public List<ShareResponse> listShares(@AuthenticationPrincipal AuthenticatedUser user) {
        return delivery.listShares(user.id()).stream().map(ShareResponse::from).toList();
    }

    /** 重置提取码：token 不变，已发出的链接继续可用，旧提取码立即失效。 */
    @PutMapping("/shares/{token}/passcode")
    public ResponseEntity<Void> resetPasscode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("token") String token,
            @Valid @RequestBody ResetPasscodeRequest request) {
        delivery.resetPasscode(user.id(), token, request.passcode());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/shares/{token}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable("token") String token) {
        delivery.revokeShare(user.id(), token);
        return ResponseEntity.noContent().build();
    }

    /* ---------------- 分享访问（接收者） ---------------- */

    /** 匿名查看分享的文件信息，让接收者在下载前知道这是什么文件、多大。 */
    @GetMapping("/shares/{token}/info")
    public FileDeliveryService.ShareInfo info(@PathVariable("token") String token,
                                              @RequestParam("passcode") String passcode) {
        return delivery.shareInfo(token, passcode);
    }

    /** 匿名凭提取码下载。这是整个系统唯一对未登录用户开放的数据出口。 */
    @GetMapping("/shares/{token}/download")
    public ResponseEntity<InputStreamResource> shared(
            @PathVariable("token") String token,
            @RequestParam("passcode") String passcode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        return response(delivery.sharedDownload(token, passcode, range), range != null);
    }

    /**
     * 转存到我的网盘（需要登录）。
     *
     * <p>不复制字节，只在接收者名下新建一条指向同一物理文件的元数据，
     * 所以转存大文件也是毫秒级的；但配额按真实大小计入，防止靠互相转存无限扩容。</p>
     */
    @PostMapping("/shares/{token}/save")
    public ResponseEntity<DocumentUploadController.UploadResponse> save(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("token") String token,
            @Valid @RequestBody SaveShareRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                DocumentUploadController.UploadResponse.from(
                        delivery.saveToMyDrive(user, token, request.passcode(),
                                request.parentId(), request.displayName()),
                        true));
    }

    /* ---------------- 内部 ---------------- */

    private ResponseEntity<InputStreamResource> response(FileDeliveryService.FileDelivery file,
                                                         boolean partial) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(file.end() - file.start() + 1);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.displayName()));
        if (partial) {
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + file.start() + "-" + file.end() + "/" + file.totalSize());
        }
        return new ResponseEntity<>(new InputStreamResource(file.input()), headers,
                partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    private static String contentDisposition(String displayName) {
        String fallback = displayName.replaceAll("[\"\\r\\n]", "_");
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    /* ---------------- 请求/响应体 ---------------- */

    public record CreateShareRequest(
            @Positive(message = "documentId must be positive") long documentId,
            @NotBlank(message = "passcode must not be blank")
            @Size(min = 4, max = 32, message = "passcode length must be between 4 and 32")
            String passcode,
            LocalDateTime expiresAt) {
    }

    public record ResetPasscodeRequest(
            @NotBlank(message = "passcode must not be blank")
            @Size(min = 4, max = 32, message = "passcode length must be between 4 and 32")
            String passcode) {
    }

    public record SaveShareRequest(
            @NotBlank(message = "passcode must not be blank") String passcode,
            @Positive(message = "parentId must be positive when present") Long parentId,
            /** 留空则沿用原文件名 */
            String displayName) {
    }

    /** 分享列表项：带上文件名和大小，否则用户分不清哪个分享对应哪个文件。 */
    public record ShareResponse(String token, long documentId, LocalDateTime expiresAt,
                                LocalDateTime createdAt, String displayName,
                                Long byteSize, boolean fileDeleted) {
        public static ShareResponse from(FileShareRow row) {
            return new ShareResponse(row.getToken(), row.getDocumentId(), row.getExpiresAt(),
                    row.getCreatedAt(), row.getDocumentDisplayName(),
                    row.getDocumentByteSize(), row.isDocumentDeleted());
        }
    }
}
