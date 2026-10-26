package com.mengde.clouddrive.persistence;

/**
 * 带存储元数据的文件行（Day17）：除元数据外还带 storageKey 与 contentMd5，
 * 用于秒传候选判断、Range 下载定位实际对象和分享下载。
 */
public class StoredDocumentRow {

    private Long id;
    private long ownerId;
    private Long parentId;
    private String displayName;
    private long byteSize;
    private String storageKey;
    private String contentMd5;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentMd5() {
        return contentMd5;
    }

    public void setContentMd5(String contentMd5) {
        this.contentMd5 = contentMd5;
    }
}
