package com.mengde.clouddrive.web;

import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.DuplicateEntryNameException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import com.mengde.clouddrive.error.InstantUploadCandidateNotFoundException;
import com.mengde.clouddrive.error.InvalidCredentialsException;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.error.RegistrationConflictException;
import com.mengde.clouddrive.error.ShareAccessException;
import com.mengde.clouddrive.error.UploadInProgressException;
import com.mengde.clouddrive.error.UploadRateLimitExceededException;
import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import com.mengde.clouddrive.error.UsernameAlreadyExistsException;
import com.mengde.clouddrive.snapshot.SnapshotFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * 全局异常 → HTTP 状态码的唯一映射处（Day06 建立，随业务逐日补充）。
 *
 * <p>为什么要集中在一处：如果每个 Controller 自己 try-catch 拼错误体，
 * 同一种失败在不同接口上会返回不同状态码和不同 JSON 结构，
 * 调用方就只能靠猜。集中映射让"业务语义 → 状态码"成为一张可审查的表。</p>
 *
 * <p>另一条纪律：响应体里只放能对外解释的信息，不回显异常堆栈、SQL 语句和服务器路径。
 * 5xx 打完整日志到服务端，给调用方的只是一句固定文案。</p>
 *
 * <p>状态码选择说明：</p>
 * <ul>
 *   <li>404 —— 不存在、越权访问他人资源、秒传未命中、分享无效。
 *       越权刻意用 404 而不是 403：403 等于承认"这个 id 存在，只是你不能看"，
 *       会泄露资源是否存在。</li>
 *   <li>409 —— 与当前状态冲突：重名、配额不足、分片乱序/缺片、有上传正在进行。</li>
 *   <li>429 —— 触发限流。</li>
 *   <li>400 —— 请求本身不合法：校验失败、参数类型错、Range 头非法。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /* ---------------- 404 ---------------- */

    @ExceptionHandler(EntryNotFoundException.class)
    public ResponseEntity<ApiError> handleEntryNotFound(EntryNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InstantUploadCandidateNotFoundException.class)
    public ResponseEntity<ApiError> handleInstantCandidateMissing(
            InstantUploadCandidateNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ShareAccessException.class)
    public ResponseEntity<ApiError> handleShareAccess(ShareAccessException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /* ---------------- 409 ---------------- */

    @ExceptionHandler({DuplicateEntryException.class, DuplicateEntryNameException.class,
            UsernameAlreadyExistsException.class, RegistrationConflictException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> handleQuota(QuotaExceededException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({UploadSessionIncompleteException.class, UploadInProgressException.class})
    public ResponseEntity<ApiError> handleUploadState(RuntimeException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    /* ---------------- 401 / 429 / 413 ---------------- */

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) {
        return respond(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(UploadRateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(UploadRateLimitExceededException exception) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException exception) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "uploaded content exceeds the configured limit");
    }

    /* ---------------- 400 ---------------- */

    @ExceptionHandler({IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception) {
        return respond(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 方法参数级约束失败（配合类上的 @Validated，例如 @RequestParam 上的 @NotBlank）。
     * 与请求体校验失败走同一个状态码，保证调用方看到的行为一致。
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .orElse("request validation failed");
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Bean Validation 失败。
     *
     * <p>只取第一个字段错误并拼成 "字段名 + 约束说明"：
     * 直接把 MethodArgumentNotValidException.getMessage() 丢出去会带上一大段
     * 代理类名和内部结构，既难读又泄露实现细节。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request validation failed");
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    /* ---------------- 403 ---------------- */

    /**
     * 显式处理 AccessDeniedException。
     *
     * <p>URL 级授权在过滤链里就被拦住了，走不到这里；但方法级授权
     * （例如将来加 @PreAuthorize）抛出的 AccessDeniedException 会到达这里。
     * 如果不单独处理，它会被下面兜底的 Exception 处理器变成 500——
     * 一个本该是 403 的结果被报成服务端故障，排障时会被完全误导。</p>
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException exception) {
        return respond(HttpStatus.FORBIDDEN,
                "the current identity is not allowed to access this resource");
    }

    /* ---------------- 500 ---------------- */

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        String reason = status == null ? "Request failed" : status.getReasonPhrase();
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiError(exception.getStatusCode().value(), reason,
                        exception.getReason() == null ? reason : exception.getReason()));
    }

    @ExceptionHandler({IOException.class, SnapshotFormatException.class})
    public ResponseEntity<ApiError> handleStorageFailure(Exception exception) {
        log.error("storage or snapshot operation failed", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "storage operation failed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("unhandled exception", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected server error");
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
