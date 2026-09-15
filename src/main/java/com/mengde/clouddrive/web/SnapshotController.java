package com.mengde.clouddrive.web;

import com.mengde.clouddrive.application.DriveEntryApplicationService;
import com.mengde.clouddrive.domain.DriveEntry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 快照资源 (/api/snapshots) 的 HTTP 契约：把当前目录写入快照文件，
 * 成功返回 200 与条目数；底层 IO 失败时由全局异常处理器转为 500。
 */
@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final DriveEntryApplicationService entryService;
    private final SnapshotSink snapshotSink;

    public SnapshotController(DriveEntryApplicationService entryService, SnapshotSink snapshotSink) {
        this.entryService = entryService;
        this.snapshotSink = snapshotSink;
    }

    @PostMapping
    public SnapshotResponse create() throws IOException {
        List<DriveEntry> entries = entryService.listAll();
        snapshotSink.save(entries);
        return new SnapshotResponse(entries.size());
    }
}
