package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.snapshot.EntrySnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day05：线程池的准入、去重、失败恢复与入参冻结。
 */
class SnapshotTaskExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundedQueueRejectsThirdDistinctTaskAndLaterAcceptsItsKey() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            }
        })) {
            CompletableFuture<SnapshotTaskReceipt> first = executor.submit("first", entries());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<SnapshotTaskReceipt> second = executor.submit("second", entries());

            assertAll(
                    () -> assertEquals(1, executor.queuedTaskCount()),
                    () -> assertThrows(RejectedExecutionException.class,
                            () -> executor.submit("third", entries())),
                    () -> assertEquals(2, executor.inFlightTaskCount()));

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            // 被拒绝的 key 必须没有残留，否则重试会永远拿到那个失败的 future。
            SnapshotTaskReceipt retried = executor.submit("third", entries()).get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals("third", retried.taskKey()),
                    () -> assertEquals(3, calls.get()),
                    () -> assertEquals(0, executor.inFlightTaskCount()));
        }
    }

    @Test
    void failedTaskLeavesNoStaleKeyAndCanBeSubmittedAgain() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("simulated disk failure");
            }
        })) {
            CompletableFuture<SnapshotTaskReceipt> failed = executor.submit("retryable", entries());
            ExecutionException exception =
                    assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));

            assertAll(
                    () -> assertInstanceOf(IOException.class, exception.getCause()),
                    () -> assertEquals(0, executor.inFlightTaskCount()),
                    () -> assertEquals("retryable",
                            executor.submit("retryable", entries()).get(2, TimeUnit.SECONDS).taskKey()),
                    () -> assertEquals(2, attempts.get()));
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
            CompletableFuture<SnapshotTaskReceipt> future = executor.submit("frozen", mutableEntries);
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            mutableEntries.add(new DirectoryEntry(14L, 7L, null, "不应进入已提交任务"));
            releaseWorker.countDown();

            assertAll(
                    () -> assertEquals(1, future.get(2, TimeUnit.SECONDS).entryCount()),
                    () -> assertEquals(1, observed.get().size()),
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> observed.get().clear()));
        }
    }

    @Test
    void writesSnapshotAsynchronouslyAndReportsReceipt() throws Exception {
        EntrySnapshotStore store = new EntrySnapshotStore(temporaryDirectory.resolve("entries.snapshot"));
        List<DriveEntry> entries = List.of(
                new DirectoryEntry(21L, 8L, null, "后端"),
                new DocumentEntry(22L, 8L, 21L, "线程池.md", 512L));

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 2, store::save)) {
            SnapshotTaskReceipt receipt = executor.submit("owner-8-snapshot", entries)
                    .get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals("owner-8-snapshot", receipt.taskKey()),
                    () -> assertEquals(2, receipt.entryCount()),
                    () -> assertTrue(!receipt.elapsed().isNegative()),
                    () -> assertEquals(entries, store.load()));
        }
    }

    @Test
    void rejectsNewTasksAfterCloseAndInvalidConstructorArguments() {
        SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> { });
        executor.close();

        assertAll(
                () -> assertTrue(executor.isClosed()),
                () -> assertThrows(IllegalStateException.class,
                        () -> executor.submit("after-close", entries())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SnapshotTaskExecutor(0, 1, entries -> { })),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SnapshotTaskExecutor(1, 0, entries -> { })));
    }

    private static List<DirectoryEntry> entries() {
        return List.of(new DirectoryEntry(13L, 7L, null, "线程池实验"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("test task did not receive its release signal");
        }
    }
}
