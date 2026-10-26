package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.DuplicateEntryException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryEntryIndexTest {

    @Test
    void findsAddedEntryById() {
        InMemoryEntryIndex index = new InMemoryEntryIndex();
        DriveEntry entry = new DirectoryEntry(11L, 7L, null, "资料");
        index.add(entry);

        assertEquals(entry, index.getRequired(11L));
        assertEquals(1, index.size());
    }

    @Test
    void rejectsDuplicateIdsWithoutReplacingFirstEntry() {
        InMemoryEntryIndex index = new InMemoryEntryIndex();
        DriveEntry first = new DirectoryEntry(11L, 7L, null, "资料");
        index.add(first);

        assertThrows(DuplicateEntryException.class,
                () -> index.add(new DirectoryEntry(11L, 8L, null, "另一个")));
        // 关键断言：拒绝重复的同时，原来那条不能被覆盖掉。
        assertEquals(first, index.getRequired(11L));
    }

    @Test
    void reportsMissingEntryAsBusinessFailure() {
        assertThrows(EntryNotFoundException.class, () -> new InMemoryEntryIndex().getRequired(99L));
    }

    @Test
    void rejectsNullAtIndexBoundary() {
        assertThrows(NullPointerException.class, () -> new InMemoryEntryIndex().add(null));
    }
}
