package com.mengde.clouddrive.persistence;

/**
 * 已落盘的分片记录（Day18），(sessionId, chunkIndex) 是主键。
 * 它既是断点续传的依据（客户端据此知道还缺哪些片），也是合并时的清单。
 */
public class UploadChunkRow {

    private String sessionId;
    private int chunkIndex;
    private long byteSize;
    private String contentMd5;
    private String storageKey;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getContentMd5() {
        return contentMd5;
    }

    public void setContentMd5(String contentMd5) {
        this.contentMd5 = contentMd5;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
}
