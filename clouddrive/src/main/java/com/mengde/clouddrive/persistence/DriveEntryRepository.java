package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DocumentEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.domain.EntryKind;
import com.mengde.clouddrive.error.DuplicateEntryNameException;
import com.mengde.clouddrive.error.EntryNotFoundException;
import com.mengde.clouddrive.error.InstantUploadCandidateNotFoundException;
import com.mengde.clouddrive.web.TrashEntryResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 条目仓储（Day07 建立，Day16/17/22 扩展）：负责行对象 ↔ 领域对象的转换，
 * 以及把数据库异常翻译成业务异常。
 *
 * <p>为什么重名判断靠"先插入再捕获 DuplicateKeyException"而不是"先查再插"：
 * 先查再插在并发下必然漏判——两个请求可以同时查到"不存在"，然后一起插入。
 * 唯一键是唯一可靠的裁判，代码只负责把它的报错翻译成业务语言。</p>
 */
@Repository
public class DriveEntryRepository {

    /**
     * 单次级联删除允许触及的最大条目数。
     * 超过就拒绝，而不是硬着头皮把一条超长的 IN 列表发给数据库——
     * 那样既可能超过 max_allowed_packet，也会长时间持有行锁。
     */
    private static final int MAX_SUBTREE_SIZE = 5000;

    private final DriveEntryMapper mapper;

    public DriveEntryRepository(DriveEntryMapper mapper) {
        this.mapper = mapper;
    }

    /* ---------------- 元数据读取（全部按 owner 收敛） ---------------- */

    public DriveEntry findByOwnerAndId(long ownerId, long id) {
        return mapper.findByOwnerAndId(ownerId, id)
                .map(this::toDomain)
                .orElseThrow(() -> new EntryNotFoundException(id));
    }

    public List<DriveEntry> findAllByOwner(long ownerId) {
        return mapper.findAllByOwner(ownerId).stream().map(this::toDomain).toList();
    }

    public List<DriveEntry> findPageByOwner(long ownerId, int page, int size) {
        return mapper.findPageByOwner(ownerId, page * size, size).stream()
                .map(this::toDomain)
                .toList();
    }

    public long countByOwner(long ownerId) {
        return mapper.countByOwner(ownerId);
    }

    public long usedBytesByOwner(long ownerId) {
        return mapper.usedBytesByOwner(ownerId);
    }

    /* ---------------- 写入 ---------------- */

    public DriveEntry saveGenerated(long ownerId, Long parentId, String displayName,
                                    EntryKind kind, long byteSize) {
        DriveEntryInsertRow row = new DriveEntryInsertRow();
        row.setOwnerId(ownerId);
        row.setParentId(parentId);
        row.setDisplayName(displayName.trim());
        row.setKind(kind.name());
        row.setByteSize(kind == EntryKind.DOCUMENT ? byteSize : null);
        try {
            mapper.insertGenerated(row);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEntryNameException(displayName);
        }
        return findByOwnerAndId(ownerId, row.getId());
    }

    public StoredDocumentRow saveStoredDocument(long ownerId, Long parentId, String displayName,
                                                long byteSize, String storageKey, String contentMd5) {
        DriveEntryInsertRow row = new DriveEntryInsertRow();
        row.setOwnerId(ownerId);
        row.setParentId(parentId);
        row.setDisplayName(displayName.trim());
        row.setKind(EntryKind.DOCUMENT.name());
        row.setByteSize(byteSize);
        row.setStorageKey(storageKey);
        row.setContentMd5(contentMd5);
        try {
            mapper.insertGenerated(row);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEntryNameException(displayName);
        }
        return findStoredDocumentByOwnerAndId(ownerId, row.getId());
    }

    public void renameByOwnerAndId(long ownerId, long id, String displayName) {
        try {
            if (mapper.updateDisplayNameByOwner(ownerId, id, displayName.trim()) == 0) {
                throw new EntryNotFoundException(id);
            }
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEntryNameException(displayName);
        }
    }

    /* ---------------- Day17：秒传候选 ---------------- */

    public StoredDocumentRow findReusableStoredDocument(long ownerId, String md5) {
        return mapper.findStoredDocumentByOwnerAndMd5(ownerId, md5)
                .orElseThrow(InstantUploadCandidateNotFoundException::new);
    }

    public StoredDocumentRow findStoredDocumentByOwnerAndId(long ownerId, long id) {
        return mapper.findStoredDocumentByOwnerAndId(ownerId, id)
                .orElseThrow(() -> new EntryNotFoundException(id));
    }

    /* ---------------- Day22：回收站 ---------------- */

    /**
     * 删除一个条目及其整棵子树（V8 起的级联语义）。
     *
     * <p>为什么要先把 id 收集出来再批量更新，而不是写一条递归 SQL：
     * MySQL 8 的递归 CTE 在 UPDATE 语句里不能引用正在被更新的同一张表，
     * 硬写会报 "You can't specify target table for update in FROM clause"。
     * 在应用层做广度优先遍历、再用一条带 IN 的 UPDATE 收尾，既绕开了这个限制，
     * 也让"删了多少行"这件事可验证。</p>
     *
     * <p>遍历带深度上限：万一数据里出现了环（父指子、子又指父），
     * 没有上限的递归会直接把线程栈或内存吃掉。正常目录树远达不到这个深度。</p>
     *
     * @return 实际被标记删除的行数
     */
    public int deleteSubtreeByOwnerAndId(long ownerId, long id) {
        // 先确认这个条目存在且属于本人；不存在会抛 EntryNotFoundException（→ 404）
        findByOwnerAndId(ownerId, id);

        List<Long> subtree = collectSubtreeIds(ownerId, id);
        int affected = mapper.softDeleteSubtree(ownerId, subtree, id);
        if (affected == 0) {
            throw new EntryNotFoundException(id);
        }
        return affected;
    }

    /** 广度优先收集子树中所有活跃条目的 id（含根自身）。 */
    private List<Long> collectSubtreeIds(long ownerId, long rootId) {
        List<Long> collected = new ArrayList<>();
        Deque<Long> pending = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();

        pending.add(rootId);
        seen.add(rootId);

        while (!pending.isEmpty()) {
            if (collected.size() >= MAX_SUBTREE_SIZE) {
                throw new IllegalStateException(
                        "subtree exceeds " + MAX_SUBTREE_SIZE + " entries, refuse to delete in one request");
            }
            long current = pending.poll();
            collected.add(current);
            for (Long child : mapper.findActiveChildIds(ownerId, current)) {
                // seen 去重是防环保护：数据异常时不至于无限循环
                if (seen.add(child)) {
                    pending.add(child);
                }
            }
        }
        return collected;
    }

    /**
     * 按删除批次整棵恢复。
     *
     * <p>必须捕获 DuplicateKeyException：条目进回收站之后，
     * 用户完全可能在原位置又建了一个同名文件，此时恢复会撞上活跃唯一键。
     * 直接把数据库异常抛出去会变成 500，而这其实是一个可以向用户解释的 409。</p>
     *
     * @return 实际恢复的行数
     */
    public int restoreSubtreeByOwnerAndId(long ownerId, long rootId) {
        try {
            int affected = mapper.restoreSubtree(ownerId, rootId);
            if (affected == 0) {
                throw new EntryNotFoundException(rootId);
            }
            return affected;
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEntryNameException("restored entry " + rootId);
        }
    }

    public int countSubtreeInTrash(long ownerId, long rootId) {
        return mapper.countSubtreeInTrash(ownerId, rootId);
    }

    public List<TrashEntryResponse> findTrashByOwner(long ownerId, int page, int size) {
        return mapper.findTrashByOwner(ownerId, page * size, size).stream()
                .map(row -> new TrashEntryResponse(
                        row.getId(), row.getParentId(), row.getDisplayName(),
                        row.getKind(), row.getByteSize(), row.getDeletedAt()))
                .toList();
    }

    public long countTrashByOwner(long ownerId) {
        return mapper.countTrashByOwner(ownerId);
    }

    public List<String> findTrashStorageKeys(long ownerId) {
        return mapper.findTrashStorageKeys(ownerId);
    }

    public int purgeTrashByOwner(long ownerId) {
        return mapper.purgeTrashByOwner(ownerId);
    }

    public boolean isStorageKeyStillReferenced(String storageKey) {
        return mapper.countStorageReferences(storageKey) > 0;
    }

    /* ---------------- 回收站定时清理（跨用户） ---------------- */

    public List<String> findExpiredTrashStorageKeys(LocalDateTime cutoff) {
        return mapper.findExpiredTrashStorageKeys(cutoff);
    }

    public int purgeExpiredTrash(LocalDateTime cutoff) {
        return mapper.purgeExpiredTrash(cutoff);
    }

    /* ---------------- 测试辅助 ---------------- */

    public void deleteAll() {
        mapper.deleteAll();
    }

    /* ---------------- 行 → 领域对象 ---------------- */

    private DriveEntry toDomain(DriveEntryRow row) {
        if (EntryKind.valueOf(row.kind()) == EntryKind.DIRECTORY) {
            return new DirectoryEntry(row.id(), row.ownerId(), row.parentId(), row.displayName());
        }
        long byteSize = row.byteSize() != null ? row.byteSize() : 0L;
        return new DocumentEntry(row.id(), row.ownerId(), row.parentId(), row.displayName(), byteSize);
    }
}
