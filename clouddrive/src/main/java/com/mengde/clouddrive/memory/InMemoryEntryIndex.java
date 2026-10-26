package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 按 id 查条目的哈希索引（Day03）。
 *
 * <p>为什么用 putIfAbsent 而不是 put：put 会先把已有条目覆盖掉，
 * 即使随后抛出 DuplicateEntryException，原来那条数据也已经没了。
 * putIfAbsent 保证"拒绝重复"和"不破坏已有数据"同时成立。</p>
 *
 * <p>本类不是线程安全的，多线程使用时由上层（如 DriveEntryCatalog）负责串行化。</p>
 */
public final class InMemoryEntryIndex {

    private final Map<Long, DriveEntry> entriesById = new HashMap<>();

    public void add(DriveEntry entry) {
        Objects.requireNonNull(entry, "entry");
        DriveEntry previous = entriesById.putIfAbsent(entry.id(), entry);
        if (previous != null) {
            throw new DuplicateEntryException(entry.id());
        }
    }

    public DriveEntry getRequired(long entryId) {
        DriveEntry entry = entriesById.get(entryId);
        if (entry == null) {
            throw new EntryNotFoundException(entryId);
        }
        return entry;
    }

    public int size() {
        return entriesById.size();
    }
}
