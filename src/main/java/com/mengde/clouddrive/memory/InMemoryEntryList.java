package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DriveEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
