package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.error.UploadSessionIncompleteException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 上传会话仓储（Day18）。
 */
@Repository
public class UploadSessionRepository {

    private final UploadSessionMapper mapper;

    public UploadSessionRepository(UploadSessionMapper mapper) {
        this.mapper = mapper;
    }

    public UploadSessionRow create(long ownerId, Long parentId, String displayName, int totalChunks) {
        UploadSessionRow row = new UploadSessionRow();
        row.setId(UUID.randomUUID().toString());
        row.setOwnerId(ownerId);
        row.setParentId(parentId);
        row.setDisplayName(displayName.trim());
        row.setTotalChunks(totalChunks);
        row.setStatus(UploadSessionRow.STATUS_ACTIVE);
        mapper.insertSession(row);
        return row;
    }

    public UploadSessionRow findActive(long ownerId, String sessionId) {
        UploadSessionRow session = mapper.findByOwnerAndId(ownerId, sessionId)
                .orElseThrow(() -> new UploadSessionIncompleteException(
                        "upload session not found: " + sessionId));
        if (!session.isActive()) {
            throw new UploadSessionIncompleteException(
                    "upload session already finished: " + sessionId);
        }
        return session;
    }

    public List<UploadChunkRow> chunks(String sessionId) {
        return mapper.findChunks(sessionId);
    }

    public UploadChunkRow findChunk(String sessionId, int chunkIndex) {
        return mapper.findChunk(sessionId, chunkIndex).orElse(null);
    }

    public void saveChunk(UploadChunkRow row) {
        mapper.insertChunk(row);
    }

    public void markCompleted(String sessionId) {
        mapper.updateStatus(sessionId, UploadSessionRow.STATUS_COMPLETED);
    }

    public void deleteChunks(String sessionId) {
        mapper.deleteChunks(sessionId);
    }

    public void deleteAll() {
        mapper.deleteAllChunks();
        mapper.deleteAllSessions();
    }
}
