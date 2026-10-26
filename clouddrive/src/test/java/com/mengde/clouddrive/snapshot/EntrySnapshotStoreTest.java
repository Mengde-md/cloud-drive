package com.mengde.clouddrive.snapshot;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.domain.EntryKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntrySnapshotStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsDirectoryAndDocumentInOrder() {
        Path snapshot = temporaryDirectory.resolve("nested/entries.snapshot");
        EntrySnapshotStore store = new EntrySnapshotStore(snapshot);
        store.save(List.of(new DirectoryEntry(11L, 7L, null, "学习资料"),
                new DocumentEntry(12L, 7L, 11L, "JVM 笔记", 2048L)));

        List<DriveEntry> loaded = store.load();

        assertAll(
                () -> assertTrue(Files.exists(snapshot)),
                () -> assertEquals(2, loaded.size()),
                () -> assertEquals(EntryKind.DIRECTORY, loaded.get(0).kind()),
                () -> assertEquals("学习资料", loaded.get(0).displayName()),
                () -> assertEquals(EntryKind.DOCUMENT, loaded.get(1).kind()),
                () -> assertEquals(2048L, ((DocumentEntry) loaded.get(1)).byteSize()));
    }

    @Test
    void survivesNameContainingFieldSeparator() {
        // 名字里带 '|' 是分隔符方案最容易漏的边界：不编码就会多出字段。
        Path snapshot = temporaryDirectory.resolve("pipe.snapshot");
        EntrySnapshotStore store = new EntrySnapshotStore(snapshot);
        store.save(List.of(new DirectoryEntry(11L, 7L, null, "a|b")));

        assertEquals("a|b", store.load().get(0).displayName());
    }

    @Test
    void returnsEmptySnapshotWhenFileDoesNotExist() {
        assertTrue(new EntrySnapshotStore(temporaryDirectory.resolve("missing.snapshot"))
                .load().isEmpty());
    }

    @Test
    void rejectsUnknownOrTruncatedLine() throws IOException {
        Path snapshot = temporaryDirectory.resolve("broken.snapshot");
        Files.writeString(snapshot, "X|1|2|3|name", StandardCharsets.UTF_8);

        assertThrows(SnapshotFormatException.class, () -> new EntrySnapshotStore(snapshot).load());
    }

    @Test
    void rejectsInvalidEncodedName() throws IOException {
        Path snapshot = temporaryDirectory.resolve("bad-name.snapshot");
        Files.writeString(snapshot, "D|11|7||%%%", StandardCharsets.UTF_8);

        assertThrows(SnapshotFormatException.class, () -> new EntrySnapshotStore(snapshot).load());
    }

    @Test
    void removesTemporaryFileAfterReplacement() throws IOException {
        Path snapshot = temporaryDirectory.resolve("entries.snapshot");
        new EntrySnapshotStore(snapshot).save(List.of(new DirectoryEntry(11L, 7L, null, "学习资料")));

        try (var children = Files.list(temporaryDirectory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
