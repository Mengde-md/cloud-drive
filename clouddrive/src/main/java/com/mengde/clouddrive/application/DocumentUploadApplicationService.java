package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * 普通上传与秒传（Day17，Day20 接入缓存失效，Day21 接入分布式锁）。
 *
 * <p>本类最重要的一段是 upload() 里的补偿逻辑。上传要做两件事：
 * 把字节写到磁盘、把元数据写进数据库。这两件事不在同一个事务里
 * （文件系统不参与数据库事务）。如果元数据插入失败（例如撞了重名唯一键），
 * 数据库会回滚，但磁盘上那个文件不会自己消失——于是留下一个谁都引用不到的孤儿对象。
 * 所以 catch 块里必须显式把刚写的对象删掉。这就是"跨资源操作要自己写补偿"。</p>
 */
@Service
public class DocumentUploadApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadApplicationService.class);

    private final DriveEntryRepository entries;
    private final QuotaGuard quotaGuard;
    private final ObjectStorage storage;
    private final PrivateEntryMetadataCache metadataCache;
    private final UploadCoordination coordination;
    private final FileAnalysisTrigger analysis;

    public DocumentUploadApplicationService(DriveEntryRepository entries,
                                            QuotaGuard quotaGuard,
                                            ObjectStorage storage,
                                            PrivateEntryMetadataCache metadataCache,
                                            UploadCoordination coordination,
                                            FileAnalysisTrigger analysis) {
        this.entries = entries;
        this.quotaGuard = quotaGuard;
        this.storage = storage;
        this.metadataCache = metadataCache;
        this.coordination = coordination;
        this.analysis = analysis;
    }

    @Transactional(rollbackFor = IOException.class)
    public StoredDocumentRow upload(AuthenticatedUser user, Long parentId,
                                    String displayName, MultipartFile file) throws IOException {
        try (var ignored = coordination.acquireOwnerUploadLock(user.id())) {
            // MultipartFile.getSize() is the size of the received part. Reject over-quota
            // requests before making another disk copy. Keep this account row locked through
            // insertion, then still verify the byte count computed by our storage implementation.
            quotaGuard.require(user.id(), file.getSize());
            verifyParent(user.id(), parentId);

            // 先落盘：这样才能得到服务端自己算出来的真实大小与 MD5，
            // 而不是采信客户端声称的数字。
            StoredObject stored = storage.store(file.getInputStream(), file.getOriginalFilename());
            try {
                quotaGuard.require(user.id(), stored.byteSize());
                StoredDocumentRow document = entries.saveStoredDocument(
                        user.id(), parentId, displayName,
                        stored.byteSize(), stored.storageKey(), stored.md5());
                metadataCache.evictOwner(user.id());
                log.info("document uploaded ownerId={} entryId={} bytes={} md5={}",
                        user.id(), document.getId(), stored.byteSize(), stored.md5());
                UploadCleanup.onRollback(() -> storage.delete(stored.storageKey()),
                        "remove rolled-back document", log);
                analysis.uploaded(document);
                return document;
            } catch (RuntimeException failure) {
                // 补偿：元数据没写成，磁盘上那份就是垃圾，必须删掉。
                UploadCleanup.run(() -> storage.delete(stored.storageKey()), failure,
                        "remove rejected document", log);
                throw failure;
            }
        }
    }

    /**
     * 秒传（简化版，Day17）。
     *
     * <p>候选只在"当前用户自己已有的文件"里找（SQL 的 WHERE 带 owner_id）。
     * 如果放开成全库查 MD5，那么只要知道一个文件的 MD5 就能把它挂到自己名下，
     * 等于内容泄露。真正的跨用户去重需要引物理对象表 + 引用计数，属于后续演进。</p>
     *
     * <p>这一版的幂等只靠唯一键这种简单机制，没有分布式协调；
     * 跨节点的并发保护由 Day21 的锁补上。</p>
     */
    @Transactional
    public StoredDocumentRow instantUpload(AuthenticatedUser user, Long parentId,
                                           String displayName, String md5) {
        try (var ignored = coordination.acquireOwnerUploadLock(user.id())) {
            verifyParent(user.id(), parentId);
            StoredDocumentRow candidate = entries.findReusableStoredDocument(
                    user.id(), md5.toLowerCase(Locale.ROOT));
            quotaGuard.require(user.id(), candidate.getByteSize());
            StoredDocumentRow document = entries.saveStoredDocument(
                    user.id(), parentId, displayName, candidate.getByteSize(),
                    candidate.getStorageKey(), candidate.getContentMd5());
            metadataCache.evictOwner(user.id());
            log.info("document instant-uploaded ownerId={} entryId={} reusedFrom={}",
                    user.id(), document.getId(), candidate.getId());
            analysis.uploaded(document);
            return document;
        }
    }

    private void verifyParent(long ownerId, Long parentId) {
        if (parentId == null) {
            return;
        }
        DriveEntry parent = entries.findByOwnerAndId(ownerId, parentId);
        if (!(parent instanceof DirectoryEntry)) {
            throw new IllegalArgumentException("parent entry must be a directory");
        }
    }
}
