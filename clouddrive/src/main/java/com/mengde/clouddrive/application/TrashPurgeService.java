package com.mengde.clouddrive.application;

import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 手工/定时清空共用的事务边界：先提交元数据，再清理不再被引用的对象。
 *
 * <p>磁盘无法参与数据库回滚，因此不能在事务提交前删除文件。提交后的引用检查
 * 投递到专用有界单线程执行器，使用新事务和 READ_COMMITTED，避免继续使用已完成
 * 事务的连接/旧快照，也不在尚未释放原连接的提交线程中等待第二条连接。
 * 任何回收站条目（包括其他用户的条目）仍然算有效引用。</p>
 *
 * <p>并发边界：引用检查与磁盘删除不是原子操作。现有秒传/分享转存也没有按对象键
 * 加同一把锁，故不能保证与“已经读到源文件、稍后才插入新引用”的并发请求串行。
 * 多实例或高并发共享对象生命周期需要所有复用/清理路径共同采用对象级锁或对象表；
 * 本次修复不宣称解决该独立竞争窗口。</p>
 */
@Service
public class TrashPurgeService {
    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

    private final DriveEntryRepository entries;
    private final ObjectStorage storage;
    private final TransactionTemplate cleanupTransaction;
    private final TaskExecutor cleanupExecutor;

    public TrashPurgeService(DriveEntryRepository entries, ObjectStorage storage,
                             PlatformTransactionManager transactions,
                             @Qualifier("trashObjectCleanupExecutor") TaskExecutor cleanupExecutor) {
        this.entries = entries;
        this.storage = storage;
        this.cleanupExecutor = cleanupExecutor;
        this.cleanupTransaction = new TransactionTemplate(transactions);
        this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cleanupTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional
    public int purgeOwner(long ownerId) {
        requireTransaction();
        List<String> candidateKeys = entries.findTrashStorageKeys(ownerId);
        int purged = entries.purgeTrashByOwner(ownerId);
        cleanAfterCommit(candidateKeys);
        return purged;
    }

    @Transactional
    public int purgeExpired(LocalDateTime cutoff) {
        requireTransaction();
        List<String> candidateKeys = entries.findExpiredTrashStorageKeys(cutoff);
        int purged = entries.purgeExpiredTrash(cutoff);
        cleanAfterCommit(candidateKeys);
        return purged;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("trash purge requires a Spring-managed transaction");
        }
    }

    private void cleanAfterCommit(List<String> candidateKeys) {
        List<String> uniqueKeys = List.copyOf(new LinkedHashSet<>(candidateKeys));
        if (uniqueKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    cleanupExecutor.execute(() -> cleanUnreferencedObjects(uniqueKeys));
                } catch (RuntimeException rejected) {
                    // 队列满/停机时不能退回提交线程执行。保留对象并记录人工重试依据。
                    log.warn("trash cleanup: cleanup task rejected; objects retained for retry storageKeys={}",
                            uniqueKeys, rejected);
                }
            }
        });
    }

    private void cleanUnreferencedObjects(List<String> keys) {
        for (String key : keys) {
            try {
                cleanupTransaction.executeWithoutResult(ignored -> {
                    if (entries.isStorageKeyStillReferenced(key)) {
                        return;
                    }
                    try {
                        storage.delete(key);
                    } catch (IOException failure) {
                        log.warn("trash cleanup: unreferenced object left on disk storageKey={}", key, failure);
                    }
                });
            } catch (RuntimeException failure) {
                // 元数据已提交；查询/存储故障应保留对象、记录重试依据，不能伪装成数据库回滚。
                log.warn("trash cleanup: post-commit object cleanup failed storageKey={}", key, failure);
            }
        }
    }
}
