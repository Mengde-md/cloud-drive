package com.mengde.clouddrive.snapshot;

public final class SnapshotFormatException extends RuntimeException {
    public SnapshotFormatException(String message) {
        super(message);
    }

    public SnapshotFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
