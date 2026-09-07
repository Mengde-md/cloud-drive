package com.mengde.clouddrive.concurrent;

import java.time.Duration;
import java.util.Objects;

/**
 * Observable outcome of one accepted in-process snapshot task.
 */
public record SnapshotTaskReceipt(String taskKey, int entryCount, Duration elapsed) {

    public SnapshotTaskReceipt {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("taskKey must not be blank");
        }
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must not be negative");
        }
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
