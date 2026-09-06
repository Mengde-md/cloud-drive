package com.mengde.clouddrive.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DriveEntryPolymorphismTest {

    @Test
    void readsDifferentConcreteEntriesThroughOneProtocol() {
        List<DriveEntry> entries = List.of(
                new DirectoryEntry(11L, 7L, null, "学习资料"),
                new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L)
        );

        assertEquals(List.of(EntryKind.DIRECTORY, EntryKind.DOCUMENT),
                entries.stream().map(DriveEntry::kind).toList());
    }

    @Test
    void renamesThroughSharedProtocolAndNormalizesWhitespace() {
        DriveEntry entry = new DirectoryEntry(11L, 7L, null, "学习资料");

        entry.rename(" Java 基础 ");

        assertEquals("Java 基础", entry.displayName());
    }

    @Test
    void keepsIdentityWhenDisplayNameChanges() {
        DriveEntry entry = new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L);

        entry.rename("JVM 笔记第二版");

        assertAll(
                () -> assertEquals(12L, entry.id()),
                () -> assertEquals(7L, entry.ownerId()),
                () -> assertEquals(11L, entry.parentId()),
                () -> assertEquals("JVM 笔记第二版", entry.displayName())
        );
    }
}
