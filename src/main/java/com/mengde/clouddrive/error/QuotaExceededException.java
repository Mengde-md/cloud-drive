package com.mengde.clouddrive.error;

public final class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(long quotaBytes, long requestedBytes, long usedBytes) {
        super("quota exceeded: quota=%d, used=%d, requested=%d".formatted(quotaBytes, usedBytes, requestedBytes));
    }
}
