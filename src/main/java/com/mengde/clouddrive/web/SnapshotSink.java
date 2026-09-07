package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DriveEntry;

import java.io.IOException;
import java.util.List;

/**
 * 快照写入动作的抽象，与 Day05 的 {@code SnapshotConsumer} 同构：
 * 生产实现委托给 Day04 的 {@code EntrySnapshotStore#save}，
 * 测试可以替换成受控的成功或失败动作来验证 2xx 与 5xx。
 */
@FunctionalInterface
public interface SnapshotSink {

    void save(List<DriveEntry> entries) throws IOException;
}
