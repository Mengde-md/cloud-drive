package com.mengde.clouddrive.snapshot;

/**
 * 快照文件格式错误或快照读写失败（Day04）。
 */
public final class SnapshotFormatException extends RuntimeException {
    public SnapshotFormatException(String message) {
        super(message);
    }

    public SnapshotFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
