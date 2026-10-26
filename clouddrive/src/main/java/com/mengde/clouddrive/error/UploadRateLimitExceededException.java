package com.mengde.clouddrive.error;

/**
 * 上传类操作触发限流（Day21）。对应 HTTP 429。
 */
public final class UploadRateLimitExceededException extends RuntimeException {
    public UploadRateLimitExceededException(String operation) {
        super("upload rate limit exceeded for operation: " + operation);
    }
}
