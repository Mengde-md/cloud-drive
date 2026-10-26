package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.web.EntryPageResponse;
import com.mengde.clouddrive.web.EntryResponse;
import com.mengde.clouddrive.web.TrashPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 目录/文件元数据的应用服务（Day12 建立事务边界，Day16 加入隔离与配额，Day22 加入回收站）。
 *
 * <p>事务边界为什么放在这一层而不是 Controller 或 Repository：
 * 一个业务动作（例如"建条目"= 锁配额 + 校验父目录 + 统计已用量 + 插入）
 * 必须整体成功或整体回滚。放在 Repository 太细（每个方法一个事务，配额判断和插入就不在同一个事务里了），
 * 放在 Controller 又会把 HTTP 关注点和事务混在一起。</p>
 *
 * <p>注意所有方法的第一个参数都是 AuthenticatedUser：身份只从令牌来，不从参数来。</p>
 */
@Service
public class DriveEntryApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DriveEntryApplicationService.class);

    private final DriveEntryRepository entries;
    private final QuotaGuard quotaGuard;
    private final TrashPurgeService trashPurge;
    private final FileAnalysisTrigger analysis;

    public DriveEntryApplicationService(DriveEntryRepository entries,
                                        QuotaGuard quotaGuard,
                                        TrashPurgeService trashPurge,
                                        FileAnalysisTrigger analysis) {
        this.entries = entries;
        this.quotaGuard = quotaGuard;
        this.trashPurge = trashPurge;
        this.analysis = analysis;
    }

    /* ---------------- 读 ---------------- */

    @Transactional(readOnly = true)
    public List<DriveEntry> list(AuthenticatedUser user) {
        return entries.findAllByOwner(user.id());
    }

    @Transactional(readOnly = true)
    public EntryPageResponse page(AuthenticatedUser user, int page, int size) {
        validatePaging(page, size);
        List<EntryResponse> items = entries.findPageByOwner(user.id(), page, size).stream()
                .map(EntryResponse::from)
                .toList();
        return new EntryPageResponse(items, page, size, entries.countByOwner(user.id()));
    }

    @Transactional(readOnly = true)
    public DriveEntry find(AuthenticatedUser user, long entryId) {
        return entries.findByOwnerAndId(user.id(), entryId);
    }

    /* ---------------- 写 ---------------- */

    /**
     * 创建条目。
     *
     * <p>顺序是有意的：先锁账号行取配额，再校验父目录，再统计已用量、比较、插入。
     * 先锁再算，保证"读已用量 → 判断 → 写入"这三步对同一用户是串行的；
     * 否则两个并发请求会各自读到同一个已用量，双双通过判断，最终超额。</p>
     */
    @Transactional
    public DriveEntry create(AuthenticatedUser user,
                             Long parentId,
                             String displayName,
                             com.mengde.clouddrive.domain.EntryKind kind,
                             long byteSize) {
        // 顺序有意：先锁账号行（QuotaGuard 内部 SELECT ... FOR UPDATE），再校验父目录、再写入，
        // 保证"读已用量 → 判断 → 写入"对同一用户串行
        quotaGuard.require(user.id(), byteSize);
        verifyParent(user.id(), parentId);
        DriveEntry created = entries.saveGenerated(user.id(), parentId, displayName, kind, byteSize);
        log.info("entry created ownerId={} entryId={} kind={} bytes={}",
                user.id(), created.id(), kind, byteSize);
        return created;
    }

    @Transactional
    public void rename(AuthenticatedUser user, long entryId, String displayName) {
        entries.renameByOwnerAndId(user.id(), entryId, displayName);
        DriveEntry renamed = entries.findByOwnerAndId(user.id(), entryId);
        if (!(renamed instanceof DirectoryEntry)
                && entries.findStoredDocumentByOwnerAndId(user.id(), entryId).getStorageKey() != null) {
            analysis.renamed(user.id(), entryId);
        }
        log.info("entry renamed ownerId={} entryId={}", user.id(), entryId);
    }

    /**
     * 删除 = 进回收站（逻辑删除），磁盘对象此时不动。
     *
     * <p>V8 起改为级联：删一个目录会把它下面的整棵子树一起标记删除。
     * 否则子条目仍是"未删除"状态——照样占配额、照样出现在列表接口里，
     * 但父目录已进回收站导致界面进不去，于是变成看不见却占空间的幽灵条目。</p>
     *
     * @return 本次进回收站的条目总数（含目录自身）
     */
    @Transactional
    public int delete(AuthenticatedUser user, long entryId) {
        int affected = entries.deleteSubtreeByOwnerAndId(user.id(), entryId);
        log.info("entry moved to trash ownerId={} entryId={} affectedRows={}",
                user.id(), entryId, affected);
        return affected;
    }

    /* ---------------- Day22：回收站 ---------------- */

    @Transactional(readOnly = true)
    public TrashPageResponse trash(AuthenticatedUser user, int page, int size) {
        validatePaging(page, size);
        return new TrashPageResponse(
                entries.findTrashByOwner(user.id(), page, size),
                page, size, entries.countTrashByOwner(user.id()));
    }

    /**
     * 从回收站恢复。删的时候是整棵子树一起删的，恢复也必须整棵一起回来，
     * 否则会出现"目录回来了但里面空了"这种更让人困惑的中间状态。
     *
     * @return 实际恢复的条目总数
     */
    @Transactional
    public int restore(AuthenticatedUser user, long entryId) {
        int affected = entries.restoreSubtreeByOwnerAndId(user.id(), entryId);
        log.info("entry restored from trash ownerId={} entryId={} affectedRows={}",
                user.id(), entryId, affected);
        return affected;
    }

    /**
     * 彻底清空回收站。
     *
     * <p>先把要删的对象键取出来，数据库事务提交之后再检查并删磁盘文件，
     * 并且只删"已经没有任何条目（包括回收站）引用它"的对象——
     * 秒传会让多条元数据共用同一个对象，直接按条目删文件会把别的条目的内容一起删掉。</p>
     *
     * <p>磁盘删除放在数据库事务提交之后且失败只记日志：
     * 元数据已经没了，残留文件只是占空间的垃圾，不该让整个请求回滚。</p>
     */
    @Transactional
    public int purgeTrash(AuthenticatedUser user) {
        int purged = trashPurge.purgeOwner(user.id());
        log.info("trash purged ownerId={} rows={}", user.id(), purged);
        return purged;
    }

    /* ---------------- 内部校验 ---------------- */

    /**
     * 父目录必须存在、属于本人、且确实是目录。
     *
     * <p>findByOwnerAndId 本身已经做了归属判断（查不到就是 404），
     * 这里补的是"不能把文件当成父目录"——否则会出现挂在一个文件下面的子条目，
     * 目录树就不再是树了。</p>
     */
    private void verifyParent(long ownerId, Long parentId) {
        if (parentId == null) {
            return;
        }
        DriveEntry parent = entries.findByOwnerAndId(ownerId, parentId);
        if (!(parent instanceof DirectoryEntry)) {
            throw new IllegalArgumentException("parent entry must be a directory");
        }
    }

    private void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be 1..100");
        }
    }
}
