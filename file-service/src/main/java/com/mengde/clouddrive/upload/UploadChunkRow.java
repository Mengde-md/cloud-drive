package com.mengde.clouddrive.upload;

public class UploadChunkRow {
    private String sessionId;
    private int chunkIndex;
    private long byteSize;
    private String contentMd5;
    private String storageKey;
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }
    public String getContentMd5() { return contentMd5; }
    public void setContentMd5(String contentMd5) { this.contentMd5 = contentMd5; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
}
