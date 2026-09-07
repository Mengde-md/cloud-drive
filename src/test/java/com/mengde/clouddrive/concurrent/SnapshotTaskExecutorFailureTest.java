package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTaskExecutorFailureTest {

    @Test
    void failedTaskLeavesNoStaleKeyAndCanBeSubmittedAgain() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("simulated disk failure");
            }
        })) {
            CompletableFuture<SnapshotTaskReceipt> failed = executor.submit("retryable", entries());
            ExecutionException exception = assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));

            assertAll(
                    () -> assertInstanceOf(IOException.class, exception.getCause()),
                    () -> assertEquals(0, executor.inFlightTaskCount()),
                    () -> assertEquals("retryable", executor.submit("retryable", entries()).get(2, TimeUnit.SECONDS).taskKey()),
                    () -> assertEquals(2, attempts.get())
            );
        }
    }

    @Test
    void freezesCallerListBeforeWorkerObservesIt() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<List<? extends DriveEntry>> observed = new AtomicReference<>();
        List<DirectoryEntry> mutableEntries = new ArrayList<>(entries());

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> {
            workerStarted.countDown();
            await(releaseWorker);
            observed.set(entries);
        })) {
            CompletableFuture<SnapshotTaskReceipt> future = executor.submit("frozen-input", mutableEntries);
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            mutableEntries.add(new DirectoryEntry(14L, 7L, null, "不应进入已提交任务"));
            releaseWorker.countDown();

            assertAll(
                    () -> assertEquals(1, future.get(2, TimeUnit.SECONDS).entryCount()),
                    () -> assertEquals(1, observed.get().size()),
                    () -> assertThrows(UnsupportedOperationException.class, () -> observed.get().clear())
            );
        }
    }

    private static List<DirectoryEntry> entries() {
        return List.of(new DirectoryEntry(13L, 7L, null, "失败恢复"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("test task did not receive its release signal");
        }
    }
}
