package com.mengde.clouddrive.web;

import com.mengde.clouddrive.concurrent.SnapshotTaskExecutor;
import com.mengde.clouddrive.snapshot.EntrySnapshotStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 把 Day04 的快照存储与 Day05 的线程池接进 Spring 容器（Day06/Day11 的 @Bean 落点）。
 *
 * <p>为什么这三个 Bean 要手写而不是加 @Component：
 * EntrySnapshotStore 需要一个来自配置的 Path 参数，SnapshotTaskExecutor 需要
 * 队列容量和线程数这类构造参数——它们是"需要装配决策的对象"，
 * 而不是可以直接被扫描出来的无参组件。</p>
 */
@Configuration
public class SnapshotConfiguration {

    @Bean
    public EntrySnapshotStore entrySnapshotStore(
            @Value("${clouddrive.snapshot.path:build-output/snapshots/entries.txt}") Path snapshotPath) {
        return new EntrySnapshotStore(snapshotPath);
    }

    /**
     * 快照写入动作。单独抽一个 Bean 是为了可替换：
     * 测试里用一个必然抛 IOException 的实现就能验证 500 分支，不必真的把磁盘写坏。
     */
    @Bean
    public SnapshotSink snapshotSink(EntrySnapshotStore store) {
        return store::save;
    }

    /**
     * 快照线程池。
     *
     * <p>destroyMethod = "close" 让容器关闭时优雅停止线程池；
     * 不写的话应用退出时还有任务在飞，会写出半个快照文件。</p>
     *
     * <p>队列容量 16、线程 2：这是有意的小数值。快照是低频操作，
     * 给大队列只会在异常堆积时延迟暴露问题。</p>
     */
    @Bean(destroyMethod = "close")
    public SnapshotTaskExecutor snapshotTaskExecutor(SnapshotSink sink) {
        return new SnapshotTaskExecutor(2, 16, sink::save);
    }
}
