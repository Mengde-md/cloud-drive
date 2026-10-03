package com.mengde.clouddrive.error;

public final class UploadRateLimitExceededException extends RuntimeException {
    public UploadRateLimitExceededException(String operation) {
        super("upload rate limit exceeded for " + operation + "; retry later");
    }
}
