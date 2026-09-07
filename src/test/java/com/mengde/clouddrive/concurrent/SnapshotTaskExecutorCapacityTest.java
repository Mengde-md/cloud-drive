package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DirectoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTaskExecutorCapacityTest {

    @Test
    void boundedQueueRejectsThirdDistinctTaskAndLaterAcceptsItsKey() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        try (SnapshotTaskExecutor executor = new SnapshotTaskExecutor(1, 1, entries -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            }
        })) {
            CompletableFuture<SnapshotTaskReceipt> first = executor.submit("first", entries());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<SnapshotTaskReceipt> second = executor.submit("second", entries());

            assertAll(
                    () -> assertEquals(1, executor.queuedTaskCount()),
                    () -> assertThrows(RejectedExecutionException.class, () -> executor.submit("third", entries())),
                    () -> assertEquals(2, executor.inFlightTaskCount())
            );

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            SnapshotTaskReceipt retried = executor.submit("third", entries()).get(2, TimeUnit.SECONDS);

            assertAll(
                    () -> assertEquals("third", retried.taskKey()),
                    () -> assertEquals(3, calls.get()),
                    () -> assertEquals(0, executor.inFlightTaskCount()),
                    () -> assertEquals(0, executor.queuedTaskCount())
            );
        }
    }

    @Test
    void constructorRejectsNonPositiveCapacityAndWorkerCount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new SnapshotTaskExecutor(0, 1, entries -> { })),
                () -> assertThrows(IllegalArgumentException.class, () -> new SnapshotTaskExecutor(1, 0, entries -> { }))
        );
    }

    private static List<DirectoryEntry> entries() {
        return List.of(new DirectoryEntry(12L, 7L, null, "线程池实验"));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("test task did not receive its release signal");
        }
    }
}
