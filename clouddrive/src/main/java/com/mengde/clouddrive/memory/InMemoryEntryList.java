package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DriveEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 保持插入顺序的条目列表（Day02）。
 *
 * <p>snapshot() 返回不可变副本而不是内部列表本身：否则调用方拿到引用后
 * 一次 clear() 就能把这个对象的内部状态清空，"谁改了数据"将无法追查。</p>
 */
public final class InMemoryEntryList {

    private final List<DriveEntry> entries = new ArrayList<>();

    public void add(DriveEntry entry) {
        entries.add(Objects.requireNonNull(entry, "entry"));
    }

    public List<DriveEntry> snapshot() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
