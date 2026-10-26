package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DriveEntryApplicationService;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.concurrent.SnapshotTaskExecutor;
import com.mengde.clouddrive.concurrent.SnapshotTaskReceipt;
import com.mengde.clouddrive.domain.DriveEntry;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 目录快照接口 /api/snapshots（Day04 的文件写入 + Day05 的线程池 + Day06 的 HTTP 契约）。
 *
 * <p>作用是把当前用户的条目导出成一份服务端快照文件，用于演示
 * "异步任务 + 有界队列 + 去重 + 原子写文件"这一整条链路。</p>
 *
 * <p><b>已知范围限制</b>：快照文件路径是全局唯一的一条配置，
 * 不同用户先后导出会互相覆盖。这在当前教学范围内是可接受的——
 * 接口返回的条目数和任务键足以验证行为。若要作为正式功能，
 * 需要把路径按 ownerId 分开，这属于尚未实现的部分。</p>
 */
@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private static final long WAIT_SECONDS = 5L;

    private final DriveEntryApplicationService entryService;
    private final SnapshotTaskExecutor executor;

    public SnapshotController(DriveEntryApplicationService entryService,
                              SnapshotTaskExecutor executor) {
        this.entryService = entryService;
        this.executor = executor;
    }

    @PostMapping
    public SnapshotResponse create(@AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        List<DriveEntry> entries = entryService.list(user);
        // 任务键按用户区分：同一用户连点多次会合并成一次写入，
        // 不同用户互不影响（各自是独立的任务）。
        String taskKey = "owner-" + user.id() + "-snapshot";
        try {
            SnapshotTaskReceipt receipt = executor.submit(taskKey, entries)
                    .get(WAIT_SECONDS, TimeUnit.SECONDS);
            return new SnapshotResponse(receipt.taskKey(), receipt.entryCount(),
                    receipt.elapsed().toMillis());
        } catch (ExecutionException failure) {
            // 任务内部的真实失败原因藏在 cause 里；把 IOException 原样抛出，
            // 交给全局异常处理器映射成 500，而不是在这里拼一个含糊的错误。
            Throwable cause = failure.getCause();
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("snapshot task failed", cause);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("snapshot task did not finish in "
                    + WAIT_SECONDS + " seconds", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("snapshot task was interrupted", interrupted);
        }
    }
}
