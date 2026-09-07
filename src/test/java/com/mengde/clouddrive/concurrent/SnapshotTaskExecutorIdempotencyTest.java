package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DirectoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTaskExecutorIdempotencyTest {

    @Test
    void sameKeySharesOneFutureAndWritesOnlyOnceWhileTaskIsRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 2, entries -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
        })) {
            CompletableFuture<SnapshotTaskReceipt> first = executor.submit("daily-snapshot", entries());
            assertTrue(started.await(2, TimeUnit.SECONDS));

            CompletableFuture<SnapshotTaskReceipt> joined = executor.submit("daily-snapshot", entries());
            assertSame(first, joined);
            assertEquals(1, executor.inFlightTaskCount());

            release.countDown();
            SnapshotTaskReceipt receipt = first.get(2, TimeUnit.SECONDS);
            assertAll(
                    () -> assertEquals("daily-snapshot", receipt.taskKey()),
                    () -> assertEquals(1, receipt.entryCount()),
                    () -> assertTrue(receipt.elapsed().isZero() || !receipt.elapsed().isNegative()),
                    () -> assertEquals(1, calls.get()),
                    () -> assertEquals(0, executor.inFlightTaskCount())
            );
        }
    }

    @Test
    void distinctKeysCanOccupySeparateWorkers() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(2, 2, entries -> {
            bothStarted.countDown();
            await(release);
        })) {
            CompletableFuture<SnapshotTaskReceipt> left = executor.submit("left", entries());
            CompletableFuture<SnapshotTaskReceipt> right = executor.submit("right", entries());

            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            assertEquals(2, executor.inFlightTaskCount());
            release.countDown();
            assertAll(
                    () -> assertEquals("left", left.get(2, TimeUnit.SECONDS).taskKey()),
                    () -> assertEquals("right", right.get(2, TimeUnit.SECONDS).taskKey())
            );
        }
    }

    private static List<DirectoryEntry> entries() {
        return List.of(new DirectoryEntry(11L, 7L, null, "学习资料"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("test task did not receive its release signal");
        }
    }
}
