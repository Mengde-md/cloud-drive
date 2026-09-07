package com.mengde.clouddrive.concurrent;

import com.mengde.clouddrive.domain.DriveEntry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deliberately small, JDK-only boundary for asynchronous file snapshots.
 *
 * <p>A task key represents one logical snapshot request while it is queued or
 * running. Requests with the same key share the same future, so concurrent
 * callers do not write the same in-memory snapshot twice. This is only
 * process-local coalescing: it is not a durable idempotency guarantee across
 * restarts or across JVMs.</p>
 */
public final class SnapshotTaskExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final SnapshotConsumer consumer;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, CompletableFuture<SnapshotTaskReceipt>> inFlight = new ConcurrentHashMap<>();

    public SnapshotTaskExecutor(int workerCount, int queueCapacity, SnapshotConsumer consumer) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new SnapshotThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Accepts a task or joins an already queued/running task with the same key.
     * A full bounded queue fails fast with {@link RejectedExecutionException};
     * the rejected key is removed before the exception reaches the caller.
     */
    public CompletableFuture<SnapshotTaskReceipt> submit(String requestedTaskKey,
                                                           List<? extends DriveEntry> requestedEntries) {
        String taskKey = normalizeTaskKey(requestedTaskKey);
        List<? extends DriveEntry> immutableEntries = List.copyOf(
                Objects.requireNonNull(requestedEntries, "requestedEntries"));
        requireOpen();

        while (true) {
            CompletableFuture<SnapshotTaskReceipt> existing = inFlight.get(taskKey);
            if (existing != null) {
                return existing;
            }

            CompletableFuture<SnapshotTaskReceipt> candidate = new CompletableFuture<>();
            if (inFlight.putIfAbsent(taskKey, candidate) != null) {
                continue;
            }

            try {
                executor.execute(() -> execute(taskKey, immutableEntries, candidate));
                return candidate;
            } catch (RejectedExecutionException exception) {
                inFlight.remove(taskKey, candidate);
                candidate.completeExceptionally(exception);
                throw exception;
            }
        }
    }

    public int inFlightTaskCount() {
        return inFlight.size();
    }

    public int queuedTaskCount() {
        return executor.getQueue().size();
    }

    public boolean isClosed() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void execute(String taskKey,
                         List<? extends DriveEntry> entries,
                         CompletableFuture<SnapshotTaskReceipt> result) {
        long startedAt = System.nanoTime();
        try {
            consumer.accept(entries);
            result.complete(new SnapshotTaskReceipt(
                    taskKey,
                    entries.size(),
                    Duration.ofNanos(System.nanoTime() - startedAt)));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        } finally {
            inFlight.remove(taskKey, result);
        }
    }

    private void requireOpen() {
        if (executor.isShutdown()) {
            throw new IllegalStateException("snapshot task executor is closed");
        }
    }

    private static String normalizeTaskKey(String requestedTaskKey) {
        String taskKey = Objects.requireNonNull(requestedTaskKey, "requestedTaskKey").trim();
        if (taskKey.isEmpty()) {
            throw new IllegalArgumentException("requestedTaskKey must not be blank");
        }
        return taskKey;
    }

    private static final class SnapshotThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "snapshot-task-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
