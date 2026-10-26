package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DriveEntry;

import java.io.IOException;
import java.util.List;

/**
 * 快照写入动作的抽象（Day06），与 Day05 的 SnapshotConsumer 同构。
 * 生产实现委托给 Day04 的 EntrySnapshotStore::save；
 * 测试可以换成必然失败的实现，用来验证 5xx 路径。
 */
@FunctionalInterface
public interface SnapshotSink {

    void save(List<? extends DriveEntry> entries) throws IOException;
}
