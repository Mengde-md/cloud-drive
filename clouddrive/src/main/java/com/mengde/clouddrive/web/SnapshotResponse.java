package com.mengde.clouddrive.web;

/**
 * 快照写入结果（Day06）：任务键、写入条目数、耗时毫秒。
 */
public record SnapshotResponse(String taskKey, int entryCount, long elapsedMillis) {
}
