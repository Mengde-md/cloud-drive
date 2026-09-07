package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.snapshot.EntrySnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTaskExecutorSnapshotIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesDay04SnapshotAsynchronouslyAndReportsReceipt() throws Exception {
        EntrySnapshotStore store = new EntrySnapshotStore(temporaryDirectory.resolve("entries.snapshot"));
        List<DriveEntry> entries = List.of(
                new DirectoryEntry(21L, 8L, null, "后端"),
                new DocumentEntry(22L, 8L, 21L, "线程池.md", 512L));

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 2, store::save)) {
            SnapshotTaskReceipt receipt = executor.submit("owner-8-snapshot", entries).get(2, TimeUnit.SECONDS);
            List<DriveEntry> restored = store.load();

            assertAll(
                    () -> assertEquals("owner-8-snapshot", receipt.taskKey()),
                    () -> assertEquals(2, receipt.entryCount()),
                    () -> assertTrue(!receipt.elapsed().isNegative()),
                    () -> assertEquals(entries, restored),
                    () -> assertEquals("线程池.md", restored.get(1).displayName())
            );
        }
    }

    @Test
    void rejectsNewTasksAfterClose() {
        SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> { });
        executor.close();

        assertAll(
                () -> assertTrue(executor.isClosed()),
                () -> assertThrows(IllegalStateException.class,
                        () -> executor.submit("after-close", List.of(new DirectoryEntry(23L, 8L, null, "关闭后"))))
        );
    }
}
