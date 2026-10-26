package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DriveEntry;

import java.util.List;

/**
 * 真正执行一次快照写入的同步动作（Day05）。
 *
 * <p>把"写"抽成接口，线程池就只负责准入、去重和生命周期，不关心落盘细节；
 * 生产实现委托给 Day04 的 EntrySnapshotStore::save，测试可以换成受控的成功/失败动作。</p>
 */
@FunctionalInterface
public interface SnapshotConsumer {

    void accept(List<? extends DriveEntry> entries) throws Exception;
}
