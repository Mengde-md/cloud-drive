package com.mengde.clouddrive.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Day17 写入与 MD5、Day19 范围读取、以及路径逃逸防护。
 */
class LocalObjectStorageTest {

    @TempDir
    Path root;

    private LocalObjectStorage storage() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(root.toString());
        return new LocalObjectStorage(properties);
    }

    @Test
    void computesRealSizeAndMd5WhileWriting() throws IOException {
        StoredObject stored = storage().store(
                new ByteArrayInputStream("hello-day17".getBytes(StandardCharsets.UTF_8)), "notes.txt");

        assertAll(
                () -> assertEquals(11L, stored.byteSize()),
                // 固定值：服务端自己算的 MD5，不采信客户端上报。
                () -> assertEquals("c6973c299874592a250a2c5fc288f3b0", stored.md5()),
                () -> assertEquals(".txt", stored.storageKey().substring(stored.storageKey().length() - 4)));
    }

    @Test
    void openReadsOnlyTheRequestedRange() throws IOException {
        LocalObjectStorage storage = storage();
        StoredObject stored = storage.store(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), "range.txt");

        try (InputStream input = storage.open(stored.storageKey(), 2, 4)) {
            assertEquals("cdef", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsStorageKeyEscapingTheRoot() {
        LocalObjectStorage storage = storage();

        assertAll(
                () -> assertThrows(IOException.class, () -> storage.open("../secret.txt", 0, 1)),
                () -> assertThrows(IOException.class, () -> storage.delete("../../secret.txt")));
    }

    @Test
    void deleteRemovesTheObject() throws IOException {
        LocalObjectStorage storage = storage();
        StoredObject stored = storage.store(
                new ByteArrayInputStream("bye".getBytes(StandardCharsets.UTF_8)), "x.bin");

        storage.delete(stored.storageKey());

        assertFalse(Files.exists(root.resolve(stored.storageKey())));
    }
}
