package com.mengde.clouddrive.storage;

import com.mengde.clouddrive.persistence.UploadChunkRow;

import java.io.IOException;
import java.util.List;

/**
 * 分片临时存储（Day18）。与 ObjectStorage 分开，是因为两者生命周期完全不同：
 * 分片是可丢弃的中间态，正式对象要长期保留。
 */
public interface ChunkStorage {

    StoredChunk store(String sessionId, int chunkIndex, java.io.InputStream input) throws IOException;

    MergedChunks merge(String sessionId, List<UploadChunkRow> chunks) throws IOException;

    /** 合并成功后清理该会话的所有分片目录。 */
    void clearSession(String sessionId) throws IOException;

    /** 删除合并产生的临时完整文件。 */
    void deleteTemporary(MergedChunks merged) throws IOException;
}
