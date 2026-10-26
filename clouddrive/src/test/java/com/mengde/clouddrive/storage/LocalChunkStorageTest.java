package com.mengde.clouddrive.storage;

import com.mengde.clouddrive.persistence.UploadChunkRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Day18：分片落盘、按序合并与清理。
 */
class LocalChunkStorageTest {

    @TempDir
    Path root;

    private LocalChunkStorage storage() {
        StorageProperties properties = new StorageProperties();
        properties.setChunkRoot(root.toString());
        return new LocalChunkStorage(properties);
    }

    @Test
    void mergesChunksInGivenOrderAndCleansUp() throws IOException {
        LocalChunkStorage storage = storage();
        String sessionId = "session-1";
        StoredChunk first = storage.store(sessionId, 0,
                new ByteArrayInputStream("hello ".getBytes(StandardCharsets.UTF_8)));
        StoredChunk second = storage.store(sessionId, 1,
                new ByteArrayInputStream("world".getBytes(StandardCharsets.UTF_8)));

        MergedChunks merged = storage.merge(sessionId, List.of(row(sessionId, 0, first), row(sessionId, 1, second)));

        assertAll(
                () -> assertEquals(11L, merged.byteSize()),
                () -> assertEquals("hello world",
                        Files.readString(merged.temporaryFile(), StandardCharsets.UTF_8)));

        storage.deleteTemporary(merged);
        storage.clearSession(sessionId);

        assertAll(
                () -> assertFalse(Files.exists(merged.temporaryFile())),
                () -> assertFalse(Files.exists(root.resolve(sessionId))));
    }

    @Test
    void chunkMd5AndSizeAreComputedPerChunk() throws IOException {
        StoredChunk chunk = storage().store("session-2", 0,
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));

        assertAll(
                () -> assertEquals(3L, chunk.byteSize()),
                () -> assertEquals("900150983cd24fb0d6963f7d28e17f72", chunk.md5()),
                () -> assertEquals("session-2/0.part", chunk.storageKey()));
    }

    private static UploadChunkRow row(String sessionId, int index, StoredChunk chunk) {
        UploadChunkRow row = new UploadChunkRow();
        row.setSessionId(sessionId);
        row.setChunkIndex(index);
        row.setByteSize(chunk.byteSize());
        row.setContentMd5(chunk.md5());
        row.setStorageKey(chunk.storageKey());
        return row;
    }
}
