package com.mengde.clouddrive.web;

import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.memory.InMemoryEntryIndex;
import com.mengde.clouddrive.memory.InMemoryEntryList;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Day06 的 HTTP 门面：组合 Day02 的顺序列表与 Day03 的 id 索引，
 * 为 Controller 提供一个有明确并发边界的目录服务。
 *
 * <p>底层 HashMap/ArrayList 不是线程安全的，而 Tomcat 会用多个工作线程
 * 处理请求，因此本类用粗粒度 synchronized 串行化全部目录操作。这是
 * “先正确、再谈吞吐”的最小选择；数据层到位后的并发策略属于后续 Day。</p>
 */
@Component
public class DriveEntryCatalog {

    private final InMemoryEntryIndex entriesById = new InMemoryEntryIndex();
    private final InMemoryEntryList orderedEntries = new InMemoryEntryList();

    public synchronized DriveEntry create(EntryCreateRequest request, long entryId, long ownerId) {
        DriveEntry entry = request.toEntry(entryId, ownerId);
        entriesById.add(entry);
        orderedEntries.add(entry);
        return entry;
    }

    public synchronized List<DriveEntry> listAll() {
        return orderedEntries.snapshot();
    }

    public synchronized DriveEntry findRequired(long entryId) {
        return entriesById.getRequired(entryId);
    }

    public synchronized void rename(long entryId, String requestedName) {
        entriesById.getRequired(entryId).rename(requestedName);
    }
}
