package com.mengde.clouddrive.concurrent;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次被接受的快照任务的可观测结果（Day05）。
 *
 * <p>异步任务如果什么都不返回，调用方就只能"相信它做了"。
 * 回执带上任务键、条目数和耗时，才能在日志和测试里断言真的写了多少、花了多久。</p>
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
