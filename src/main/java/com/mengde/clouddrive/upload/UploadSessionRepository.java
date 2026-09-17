package com.mengde.clouddrive.upload;

import com.mengde.clouddrive.error.EntryNotFoundException;
import com.mengde.clouddrive.persistence.UploadSessionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class UploadSessionRepository {
    private final UploadSessionMapper mapper;
    public UploadSessionRepository(UploadSessionMapper mapper) { this.mapper = mapper; }

    public UploadSessionRow create(long ownerId, Long parentId, String displayName, int totalChunks) {
        UploadSessionRow row = new UploadSessionRow();
        row.setId(UUID.randomUUID().toString());
        row.setOwnerId(ownerId);
        row.setParentId(parentId);
        row.setDisplayName(displayName.trim());
        row.setTotalChunks(totalChunks);
        row.setStatus("ACTIVE");
        mapper.insertSession(row);
        return row;
    }

    public UploadSessionRow findOwned(String id, long ownerId) {
        return mapper.findSessionByOwner(id, ownerId).orElseThrow(() -> new EntryNotFoundException(-1));
    }

    public UploadChunkRow findChunk(String sessionId, int chunkIndex) {
        return mapper.findChunk(sessionId, chunkIndex).orElse(null);
    }

    public void saveChunk(UploadChunkRow row) {
        try { mapper.insertChunk(row); }
        catch (DuplicateKeyException exception) { throw new IllegalStateException("chunk index already exists"); }
    }

    public List<UploadChunkRow> chunks(String sessionId) { return mapper.findChunks(sessionId); }
    public void markCompleted(String id) {
        if (mapper.markCompleted(id) == 0) throw new IllegalStateException("upload session is not active");
    }
    public void deleteChunks(String id) { mapper.deleteChunks(id); }
    public void deleteAll() { mapper.deleteAllSessions(); }
}
