package com.mengde.clouddrive.memory;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryEntryListTest {

    @Test
    void preservesInsertionOrderAndSize() {
        InMemoryEntryList entries = new InMemoryEntryList();
        DriveEntry directory = new DirectoryEntry(11L, 7L, null, "学习资料");
        DriveEntry document = new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L);

        entries.add(directory);
        entries.add(document);

        assertAll(
                () -> assertEquals(2, entries.size()),
                () -> assertEquals(List.of(directory, document), entries.snapshot())
        );
    }

    @Test
    void returnsAnUnmodifiableSnapshot() {
        InMemoryEntryList entries = new InMemoryEntryList();
        DriveEntry directory = new DirectoryEntry(11L, 7L, null, "学习资料");
        entries.add(directory);

        List<DriveEntry> snapshot = entries.snapshot();

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, snapshot::clear),
                () -> assertEquals(1, entries.size()),
                () -> assertEquals(List.of(directory), entries.snapshot())
        );
    }

    @Test
    void rejectsNullEntry() {
        InMemoryEntryList entries = new InMemoryEntryList();

        assertThrows(NullPointerException.class, () -> entries.add(null));
    }

    @Test
    void keepsEarlierSnapshotStableAfterLaterAdditions() {
        InMemoryEntryList entries = new InMemoryEntryList();
        DriveEntry directory = new DirectoryEntry(11L, 7L, null, "学习资料");
        DriveEntry document = new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L);
        entries.add(directory);

        List<DriveEntry> beforeLaterAddition = entries.snapshot();
        entries.add(document);

        assertAll(
                () -> assertEquals(List.of(directory), beforeLaterAddition),
                () -> assertEquals(List.of(directory, document), entries.snapshot())
        );
    }
}
