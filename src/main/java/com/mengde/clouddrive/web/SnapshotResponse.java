package com.mengde.clouddrive.web;

/**
 * 快照写入结果的 HTTP 响应体。
 *
 * @param entryCount 本次写入快照的条目数
 */
public record SnapshotResponse(int entryCount) {
}
