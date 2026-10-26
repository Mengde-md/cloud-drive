package com.mengde.clouddrive.storage;

import com.mengde.clouddrive.persistence.UploadChunkRow;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/** Inject disk faults without changing real filesystem permissions or existing files. */
class StorageFailurePreservationTest {
    private final IOException original = new IOException("original write/read failure");
    private final IOException cleanup = new IOException("secondary delete failure");

    private InputStream brokenInput() {
        return new InputStream() {
            @Override public int read() throws IOException { throw original; }
        };
    }

    private StorageProperties properties() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(Path.of("isolated-storage-fault-probe").toAbsolutePath().toString());
        properties.setChunkRoot(Path.of("isolated-chunk-fault-probe").toAbsolutePath().toString());
        return properties;
    }

    private MockedStatic<Files> diskFailures() throws IOException {
        MockedStatic<Files> files = mockStatic(Files.class);
        files.when(() -> Files.newOutputStream(any(Path.class))).thenReturn(OutputStream.nullOutputStream());
        files.when(() -> Files.newInputStream(any(Path.class))).thenThrow(original);
        files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(cleanup);
        return files;
    }

    @Test
    void finalObjectWriteFailureSurvivesFailedPartialObjectRemoval() throws IOException {
        var storage = new LocalObjectStorage(properties());
        try (var ignored = diskFailures()) {
            assertSame(original, assertThrows(IOException.class, () -> storage.store(brokenInput(), "test.txt")));
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        }
    }

    @Test
    void chunkWriteFailureSurvivesFailedPartialChunkRemoval() throws IOException {
        var storage = new LocalChunkStorage(properties());
        try (var ignored = diskFailures()) {
            assertSame(original, assertThrows(IOException.class, () -> storage.store("session", 0, brokenInput())));
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        }
    }

    @Test
    void mergeReadFailureSurvivesFailedPartialMergeRemoval() throws IOException {
        var storage = new LocalChunkStorage(properties());
        UploadChunkRow part = new UploadChunkRow();
        part.setStorageKey("session/0.part");
        try (var ignored = diskFailures()) {
            assertSame(original, assertThrows(IOException.class, () -> storage.merge("session", List.of(part))));
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        }
    }
}
