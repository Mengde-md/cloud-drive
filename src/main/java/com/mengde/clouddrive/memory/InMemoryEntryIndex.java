package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
