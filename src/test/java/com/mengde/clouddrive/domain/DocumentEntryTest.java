package com.mengde.clouddrive.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentEntryTest {

    @Test
    void keepsByteSizeAndSharedState() {
        DocumentEntry entry = new DocumentEntry(12L, 7L, 11L, " JVM 笔记 ", 2048L);

        assertAll(
                () -> assertEquals(12L, entry.id()),
                () -> assertEquals(7L, entry.ownerId()),
                () -> assertEquals(11L, entry.parentId()),
                () -> assertEquals("JVM 笔记", entry.displayName()),
                () -> assertEquals(2048L, entry.byteSize()),
                () -> assertEquals(EntryKind.DOCUMENT, entry.kind())
        );
    }

    @Test
    void allowsZeroByteDocument() {
        DocumentEntry entry = new DocumentEntry(12L, 7L, 11L, "空白笔记", 0L);

        assertEquals(0L, entry.byteSize());
    }

    @Test
    void rejectsNegativeByteSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentEntry(12L, 7L, 11L, "JVM 笔记", -1L));
    }
}
