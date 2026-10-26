package com.mengde.clouddrive.persistence;

/**
 * 插入用的可变行对象（Day16）。
 *
 * <p>为什么不用 record：MyBatis 的 {@code @Options(useGeneratedKeys = true, keyProperty = "id")}
 * 需要在插入后把数据库生成的自增主键写回对象，record 是不可变的，写不回去。</p>
 */
public class DriveEntryInsertRow {

    private Long id;
    private long ownerId;
    private Long parentId;
    private String displayName;
    private String kind;
    private Long byteSize;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public void setByteSize(Long byteSize) {
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
