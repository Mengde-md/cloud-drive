package com.mengde.clouddrive.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DriveEntryIdentityTest {
    @Test
    void sameOwnerAndIdAreLogicallyEqual() {
        DriveEntry directory = new DirectoryEntry(11L, 7L, null, "资料");
        DriveEntry document = new DocumentEntry(11L, 7L, null, "不同展示名", 0L);

        assertEquals(directory, document);
        assertEquals(directory.hashCode(), document.hashCode());
    }

    @Test
    void differentOwnerOrIdAreNotLogicallyEqual() {
        DriveEntry base = new DirectoryEntry(11L, 7L, null, "资料");

        assertNotEquals(base, new DirectoryEntry(12L, 7L, null, "资料"));
        assertNotEquals(base, new DirectoryEntry(11L, 8L, null, "资料"));
    }

    @Test
    void hashSetDeduplicatesLogicalIdentity() {
        Set<DriveEntry> entries = new HashSet<>();
        entries.add(new DirectoryEntry(11L, 7L, null, "资料"));
        entries.add(new DocumentEntry(11L, 7L, null, "笔记", 4L));

        assertEquals(1, entries.size());
    }
}
