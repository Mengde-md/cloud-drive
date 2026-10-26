package com.mengde.clouddrive.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryEntryTest {

    @Test
    void keepsValidatedSharedStateForTopLevelEntry() {
        DirectoryEntry entry = new DirectoryEntry(11L, 7L, null, " 学习资料 ");

        assertAll(
                () -> assertEquals(11L, entry.id()),
                () -> assertEquals(7L, entry.ownerId()),
                () -> assertNull(entry.parentId()),
                () -> assertEquals("学习资料", entry.displayName()),
                () -> assertEquals(EntryKind.DIRECTORY, entry.kind()));
    }

    @Test
    void rejectsNonPositiveIdentifiers() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DirectoryEntry(0L, 7L, null, "学习资料")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DirectoryEntry(11L, -1L, null, "学习资料")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DirectoryEntry(11L, 7L, 0L, "学习资料")));
    }

    @Test
    void rejectsBlankAndNullDisplayNames() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DirectoryEntry(11L, 7L, null, "   ")),
                () -> assertThrows(NullPointerException.class,
                        () -> new DirectoryEntry(11L, 7L, null, null)));
    }
}
