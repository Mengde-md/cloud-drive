package com.mengde.clouddrive.upload;

import com.mengde.clouddrive.auth.AuthenticatedUser;
import com.mengde.clouddrive.auth.UserAccountMapper;
import com.mengde.clouddrive.domain.DirectoryEntry;
import com.mengde.clouddrive.domain.DriveEntry;
import com.mengde.clouddrive.error.QuotaExceededException;
import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import com.mengde.clouddrive.persistence.DriveEntryRepository;
import com.mengde.clouddrive.persistence.StoredDocumentRow;
import com.mengde.clouddrive.redis.PrivateEntryMetadataCache;
import com.mengde.clouddrive.redis.UploadCoordination;
import com.mengde.clouddrive.storage.ObjectStorage;
import com.mengde.clouddrive.storage.StoredObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Service
public class UploadSessionApplicationService {
    private final UploadSessionRepository sessions;
    private final ChunkStorage chunks;
    private final ObjectStorage objects;
    private final DriveEntryRepository entries;
    private final UserAccountMapper users;
    private final PrivateEntryMetadataCache metadataCache;
    private final UploadCoordination coordination;

    public UploadSessionApplicationService(UploadSessionRepository sessions, ChunkStorage chunks,
                                           ObjectStorage objects, DriveEntryRepository entries,
                                           UserAccountMapper users, PrivateEntryMetadataCache metadataCache, UploadCoordination coordination) {
        this.sessions = sessions;
        this.chunks = chunks;
        this.objects = objects;
        this.entries = entries;
        this.users = users;
        this.metadataCache = metadataCache; this.coordination = coordination;
    }

    @Transactional
    public UploadSessionRow start(AuthenticatedUser user, Long parentId, String displayName, int totalChunks) {
        verifyParent(user.id(), parentId);
        return sessions.create(user.id(), parentId, displayName, totalChunks);
    }

    @Transactional
    public ChunkResult putChunk(AuthenticatedUser user, String sessionId, int chunkIndex, MultipartFile file) throws IOException {
        try (var ignored = coordination.acquireSessionLock(sessionId)) {
            UploadSessionRow session = activeSession(user.id(), sessionId);
            if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
                throw new IllegalArgumentException("chunk index outside session range");
            }
            UploadChunkRow existing = sessions.findChunk(sessionId, chunkIndex);
            if (existing != null) return ChunkResult.from(existing, true);

            List<UploadChunkRow> received = sessions.chunks(sessionId);
            if (chunkIndex != received.size()) {
                throw new UploadSessionIncompleteException("chunks must arrive in order; expected index " + received.size());
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

    @Transactional
    public StoredDocumentRow complete(AuthenticatedUser user, String sessionId) throws IOException {
        try (var ignored = coordination.acquireSessionLock(sessionId)) {
            UploadSessionRow session = activeSession(user.id(), sessionId);
            List<UploadChunkRow> received = sessions.chunks(sessionId);
            validateManifest(session, received);
            MergedChunks merged = chunks.merge(sessionId, received);
            StoredObject finalObject = null;
            try (var input = Files.newInputStream(merged.temporaryFile())) {
                finalObject = objects.store(input, session.getDisplayName());
                verifyQuota(user.id(), finalObject.byteSize());
                StoredDocumentRow document = entries.saveStoredDocument(user.id(), session.getParentId(),
                        session.getDisplayName(), finalObject.byteSize(), finalObject.storageKey(), finalObject.md5());
                metadataCache.evictOwner(user.id());
                sessions.markCompleted(sessionId);
                sessions.deleteChunks(sessionId);
                chunks.clearSession(sessionId);
                return document;
            } catch (RuntimeException | IOException failure) {
                if (finalObject != null) objects.delete(finalObject.storageKey());
                throw failure;
            } finally {
                chunks.deleteTemporary(merged);
            }
        }
    }

    private UploadSessionRow activeSession(long ownerId, String id) {
        UploadSessionRow session = sessions.findOwned(id, ownerId);
        if (!"ACTIVE".equals(session.getStatus())) throw new UploadSessionIncompleteException("upload session is no longer active");
        return session;
    }

    private void validateManifest(UploadSessionRow session, List<UploadChunkRow> received) {
        if (received.size() != session.getTotalChunks()) {
            throw new UploadSessionIncompleteException("missing chunks: received " + received.size() + " of " + session.getTotalChunks());
        }
        for (int index = 0; index < received.size(); index++) {
            if (received.get(index).getChunkIndex() != index) {
                throw new UploadSessionIncompleteException("chunk manifest is not contiguous at index " + index);
            }
        }
    }

    private void verifyQuota(long ownerId, long requested) {
        long quota = users.lockQuotaById(ownerId).orElseThrow(() -> new IllegalArgumentException("authenticated account no longer exists"));
        long used = entries.usedBytesByOwner(ownerId);
        if (requested > quota - used) throw new QuotaExceededException(quota, requested, used);
    }

    private void verifyParent(long ownerId, Long parentId) {
        if (parentId == null) return;
        DriveEntry parent = entries.findByOwnerAndId(ownerId, parentId);
        if (!(parent instanceof DirectoryEntry)) throw new IllegalArgumentException("parent entry must be a directory");
    }

    public record ChunkResult(int chunkIndex, long byteSize, String md5, boolean alreadyReceived) {
        static ChunkResult from(UploadChunkRow row, boolean alreadyReceived) {
            return new ChunkResult(row.getChunkIndex(), row.getByteSize(), row.getContentMd5(), alreadyReceived);
        }
    }
}
