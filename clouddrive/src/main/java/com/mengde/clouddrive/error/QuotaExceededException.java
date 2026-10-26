package com.mengde.clouddrive.error;

/**
 * 存储配额不足（Day16）。对应 HTTP 409。
 * 把 quota/used/requested 三个数字都带出去，便于调用方直接提示"还差多少"。
 */
public final class QuotaExceededException extends RuntimeException {
    private final long quotaBytes;
    private final long requestedBytes;
    private final long usedBytes;

    public QuotaExceededException(long quotaBytes, long requestedBytes, long usedBytes) {
        super("quota exceeded: quota=" + quotaBytes + ", used=" + usedBytes
                + ", requested=" + requestedBytes);
        this.quotaBytes = quotaBytes;
        this.requestedBytes = requestedBytes;
        this.usedBytes = usedBytes;
    }

    public long quotaBytes() {
        return quotaBytes;
    }

    public long requestedBytes() {
        return requestedBytes;
    }

    public long usedBytes() {
        return usedBytes;
    }
}
