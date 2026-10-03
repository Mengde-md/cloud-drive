package com.mengde.clouddrive.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface ChunkStorage {
    StoredChunk store(String sessionId, int chunkIndex, InputStream input) throws IOException;
    MergedChunks merge(String sessionId, List<UploadChunkRow> chunks) throws IOException;
    void clearSession(String sessionId) throws IOException;
    void deleteTemporary(MergedChunks merged) throws IOException;
}
