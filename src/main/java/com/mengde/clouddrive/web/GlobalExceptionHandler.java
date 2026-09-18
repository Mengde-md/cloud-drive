package com.mengde.clouddrive.web;

import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import com.mengde.clouddrive.error.DuplicateEntryNameException;
import com.mengde.clouddrive.error.DirectoryNotEmptyException;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.error.InstantUploadCandidateNotFoundException;
import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import com.mengde.clouddrive.error.UploadInProgressException;
import com.mengde.clouddrive.error.UploadRateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import com.mengde.clouddrive.auth.InvalidCredentialsException;
import com.mengde.clouddrive.auth.UsernameAlreadyExistsException;
import com.mengde.clouddrive.share.ShareAccessException;

/**
 * 全局异常到 HTTP 状态码的映射，是本日“4xx/5xx 真实响应”的落点：
 * 找不到条目 → 404；重复键 → 409；非法参数/无法解析的请求体 → 400；
 * 快照 IO 失败 → 500。未映射的异常仍走 Spring Boot 默认的 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUsernameExists(UsernameAlreadyExistsException exception) { return respond(HttpStatus.CONFLICT, exception.getMessage()); }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception) { return respond(HttpStatus.UNAUTHORIZED, exception.getMessage()); }

    @ExceptionHandler(EntryNotFoundException.class)
    public ResponseEntity<ApiError> handleEntryNotFound(EntryNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InstantUploadCandidateNotFoundException.class)
    public ResponseEntity<ApiError> handleInstantCandidateMissing(InstantUploadCandidateNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }
    @ExceptionHandler(ShareAccessException.class)
    public ResponseEntity<ApiError> handleShareAccess(ShareAccessException exception) { return respond(HttpStatus.NOT_FOUND, exception.getMessage()); }

    @ExceptionHandler(DuplicateEntryException.class)
    public ResponseEntity<ApiError> handleDuplicateEntry(DuplicateEntryException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({DuplicateEntryNameException.class, DirectoryNotEmptyException.class, QuotaExceededException.class, UploadSessionIncompleteException.class})
    public ResponseEntity<ApiError> handleEntryConflict(RuntimeException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(UploadInProgressException.class)
    public ResponseEntity<ApiError> handleUploadInProgress(UploadInProgressException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(UploadRateLimitExceededException.class)
    public ResponseEntity<ApiError> handleUploadRateLimit(UploadRateLimitExceededException exception) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception) {
        return respond(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request validation failed");
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> handleSnapshotIo(IOException exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
