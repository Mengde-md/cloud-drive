package com.mengde.clouddrive.application;

import com.mengde.clouddrive.analysis.FileAnalysisTrigger;
import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.persistence.UploadChunkRow;
import com.mengde.clouddrive.persistence.UploadSessionRepository;
import com.mengde.clouddrive.persistence.UploadSessionRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ChunkStorage;
import com.mengde.clouddrive.storage.MergedChunks;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StoredChunk;
import com.mengde.clouddrive.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * 分片上传 / 断点续传 / 合并（Day18，Day20 接缓存失效，Day21 接会话锁）。
 *
 * <p>整条链路的三个状态点：</p>
 * <ol>
 *   <li><b>start</b>：建会话，声明总片数。此时磁盘上什么都没有。</li>
 *   <li><b>putChunk</b>：写一片并登记 manifest。重复提交同一片直接返回已有记录（幂等），
 *       乱序提交直接拒绝——顺序错了合并出来的文件内容是错的，而大小却是对的，
 *       不校验就会产生"看起来成功实际损坏"的文件。</li>
 *   <li><b>complete</b>：校验片数齐全 → 合并 → 写入正式对象 → 校验配额 → 写元数据 → 清理。
 *       任何一步失败都要把已经产生的正式对象删掉，并在 finally 里删掉合并临时文件。</li>
 * </ol>
 *
 * <p>status() 是断点续传的入口：客户端崩溃重连后先问"我已经传了哪些片"，
 * 再只补缺的片，而不是从头再来。</p>
 */
@Service
public class UploadSessionApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionApplicationService.class);

    private final UploadSessionRepository sessions;
    private final ChunkStorage chunks;
    private final ObjectStorage objects;
    private final DriveEntryRepository entries;
    private final QuotaGuard quotaGuard;
    private final PrivateEntryMetadataCache metadataCache;
    private final UploadCoordination coordination;
    private final FileAnalysisTrigger analysis;

    public UploadSessionApplicationService(UploadSessionRepository sessions,
                                           ChunkStorage chunks,
                                           ObjectStorage objects,
                                           DriveEntryRepository entries,
                                           QuotaGuard quotaGuard,
                                           PrivateEntryMetadataCache metadataCache,
                                           UploadCoordination coordination,
                                           FileAnalysisTrigger analysis) {
        this.sessions = sessions;
        this.chunks = chunks;
        this.objects = objects;
        this.entries = entries;
        this.quotaGuard = quotaGuard;
        this.metadataCache = metadataCache;
        this.coordination = coordination;
        this.analysis = analysis;
    }

    @Transactional
    public UploadSessionRow start(AuthenticatedUser user, Long parentId,
                                  String displayName, int totalChunks) {
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be positive");
        }
        verifyParent(user.id(), parentId);
        UploadSessionRow session = sessions.create(user.id(), parentId, displayName, totalChunks);
        log.info("upload session started ownerId={} sessionId={} totalChunks={}",
                user.id(), session.getId(), totalChunks);
        return session;
    }

    @Transactional
    public ChunkResult putChunk(AuthenticatedUser user, String sessionId,
                                int chunkIndex, MultipartFile file) throws IOException {
        try (var ignored = coordination.acquireSessionLock(sessionId)) {
            UploadSessionRow session = sessions.findActive(user.id(), sessionId);
            if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
                throw new IllegalArgumentException("chunk index outside session range");
            }

            // 幂等：这一片已经收过就直接返回，不重复落盘、不报错。
            // 客户端超时重试是常态，重试不该产生副作用。
            UploadChunkRow existing = sessions.findChunk(sessionId, chunkIndex);
            if (existing != null) {
                return ChunkResult.from(existing, true);
            }

            List<UploadChunkRow> received = sessions.chunks(sessionId);
            if (chunkIndex != received.size()) {
                throw new UploadSessionIncompleteException(
                        "chunks must arrive in order; expected index " + received.size());
            }

            StoredChunk stored = chunks.store(sessionId, chunkIndex, file.getInputStream());
            UploadChunkRow row = new UploadChunkRow();
            row.setSessionId(sessionId);
            row.setChunkIndex(chunkIndex);
            row.setByteSize(stored.byteSize());
            row.setContentMd5(stored.md5());
            row.setStorageKey(stored.storageKey());
            sessions.saveChunk(row);
            return ChunkResult.from(row, false);
        }
    }

    /** 断点续传：返回已收到的分片下标，客户端据此只补缺片。 */
    @Transactional(readOnly = true)
    public SessionStatus status(AuthenticatedUser user, String sessionId) {
        UploadSessionRow session = sessions.findActive(user.id(), sessionId);
        List<UploadChunkRow> received = sessions.chunks(sessionId);
        return new SessionStatus(
                session.getId(),
                session.getTotalChunks(),
                received.stream().map(UploadChunkRow::getChunkIndex).toList(),
                received.stream().mapToLong(UploadChunkRow::getByteSize).sum());
    }

    @Transactional(rollbackFor = IOException.class)
    public StoredDocumentRow complete(AuthenticatedUser user, String sessionId) throws IOException {
        try (var ignored = coordination.acquireSessionLock(sessionId)) {
            // Acquire the account lock before ordinary SELECTs establish a REPEATABLE READ
            // snapshot, so different sessions cannot compare against stale used space.
            quotaGuard.require(user.id(), 0L);
            UploadSessionRow session = sessions.findActive(user.id(), sessionId);
            List<UploadChunkRow> received = sessions.chunks(sessionId);
            validateManifest(session, received);

            // An early check avoids two extra full-file writes for an already over-quota
            // session. The final storage byte count remains the authoritative second check.
            quotaGuard.require(user.id(), received.stream().mapToLong(UploadChunkRow::getByteSize).sum());

            MergedChunks merged = chunks.merge(sessionId, received);
            StoredObject finalObject = null;
            Throwable operationFailure = null;
            try (var input = Files.newInputStream(merged.temporaryFile())) {
                finalObject = objects.store(input, session.getDisplayName());
                quotaGuard.require(user.id(), finalObject.byteSize());
                StoredDocumentRow document = entries.saveStoredDocument(
                        user.id(), session.getParentId(), session.getDisplayName(),
                        finalObject.byteSize(), finalObject.storageKey(), finalObject.md5());
                metadataCache.evictOwner(user.id());
                sessions.markCompleted(sessionId);
                sessions.deleteChunks(sessionId);
                UploadCleanup.afterCommit(() -> chunks.clearSession(sessionId),
                        "remove completed session parts", log);
                String completedKey = finalObject.storageKey();
                UploadCleanup.onRollback(() -> objects.delete(completedKey),
                        "remove rolled-back merged document", log);
                log.info("upload session completed ownerId={} sessionId={} entryId={} bytes={}",
                        user.id(), sessionId, document.getId(), finalObject.byteSize());
                analysis.uploaded(document);
                return document;
            } catch (RuntimeException | IOException failure) {
                operationFailure = failure;
                if (finalObject != null) {
                    String rejectedKey = finalObject.storageKey();
                    UploadCleanup.run(() -> objects.delete(rejectedKey), failure,
                            "remove rejected merged document", log);
                }
                throw failure;
            } finally {
                // 无论成功失败，合并出的临时大文件都不能留下。
                UploadCleanup.run(() -> chunks.deleteTemporary(merged), operationFailure,
                        "remove merge temporary file", log);
            }
        }
    }

    /**
     * 清单校验：片数必须齐全且下标连续。
     *
     * <p>只比较数量是不够的——数量对但下标重复/缺失时合并结果依然是错的。</p>
     */
    private void validateManifest(UploadSessionRow session, List<UploadChunkRow> received) {
        if (received.size() != session.getTotalChunks()) {
            throw new UploadSessionIncompleteException(
                    "expected " + session.getTotalChunks() + " chunks but received " + received.size());
        }
        for (int index = 0; index < received.size(); index++) {
            if (received.get(index).getChunkIndex() != index) {
                throw new UploadSessionIncompleteException("missing chunk at index " + index);
            }
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

    public record ChunkResult(String sessionId, int chunkIndex, long byteSize,
                              String md5, boolean duplicate) {
        public static ChunkResult from(UploadChunkRow row, boolean duplicate) {
            return new ChunkResult(row.getSessionId(), row.getChunkIndex(),
                    row.getByteSize(), row.getContentMd5(), duplicate);
        }
    }

    public record SessionStatus(String sessionId, int totalChunks,
                                List<Integer> receivedChunkIndexes, long receivedBytes) {
    }
}
