package com.mengde.clouddrive.persistence;

/** MyBatis needs a mutable holder to receive the AUTO_INCREMENT id after insert. */
public class DriveEntryInsertRow {
    private Long id;
    private long ownerId;
    private Long parentId;
    private String displayName;
    private String kind;
    private Long byteSize;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getOwnerId() { return ownerId; }
    public void setOwnerId(long ownerId) { this.ownerId = ownerId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getByteSize() { return byteSize; }
    public void setByteSize(Long byteSize) { this.byteSize = byteSize; }
}
