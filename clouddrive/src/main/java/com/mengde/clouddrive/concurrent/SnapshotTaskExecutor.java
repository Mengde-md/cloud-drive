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
 * 快照写入的线程池边界（Day05）。
 *
 * <p>四个刻意的选择：</p>
 * <ol>
 *   <li><b>有界队列 + AbortPolicy</b>：无界队列在积压时会一直吃内存直到 OOM，
 *       而且故障现象是"越来越慢"而不是"明确失败"。有界队列满了就立刻抛
 *       RejectedExecutionException，让调用方知道系统已经过载。</li>
 *   <li><b>按 taskKey 去重</b>：同一个 key 的重复提交直接返回已在飞的 future，
 *       避免同一份数据被并发写两次造成互相覆盖。</li>
 *   <li><b>提交时冻结入参</b>：List.copyOf 立刻拍快照，
 *       否则调用方在任务执行前继续往原列表里加元素，worker 看到的就不是它被提交时的数据。</li>
 *   <li><b>finally 里清 key</b>：任务失败也要把 key 移除，否则这个 key 永远"在飞"，
 *       后续重试会被误判为重复提交而永远拿到那个已失败的 future。</li>
 * </ol>
 */
public final class SnapshotTaskExecutor implements AutoCloseable {

    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final SnapshotConsumer consumer;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, CompletableFuture<SnapshotTaskReceipt>> inFlight =
            new ConcurrentHashMap<>();

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
     * 接受一个任务，或与同 key 的在飞任务合并。
     * 队列满时快速失败，并在异常抛给调用方之前把该 key 清掉。
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
            // 给线程起有意义的名字：线上 jstack / thread dump 时能一眼看出是谁在忙。
            Thread thread = new Thread(runnable, "snapshot-worker-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
