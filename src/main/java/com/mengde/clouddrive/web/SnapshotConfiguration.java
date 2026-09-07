package com.mengde.clouddrive.web;

import com.mengde.clouddrive.snapshot.EntrySnapshotStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 把 Day04 的 {@code EntrySnapshotStore} 适配为 Day06 的 {@link SnapshotSink}。
 * 快照文件位置由配置 {@code clouddrive.snapshot.path} 决定，
 * 未配置时默认写到 {@code snapshots/entries.txt}。
 */
@Configuration
public class SnapshotConfiguration {

    @Bean
    public EntrySnapshotStore entrySnapshotStore(
            @Value("${clouddrive.snapshot.path:snapshots/entries.txt}") Path snapshotPath) {
        return new EntrySnapshotStore(snapshotPath);
    }

    @Bean
    public SnapshotSink snapshotSink(EntrySnapshotStore store) {
        return store::save;
    }
}
