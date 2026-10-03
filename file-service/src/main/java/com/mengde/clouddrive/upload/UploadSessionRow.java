package com.mengde.clouddrive.upload;

public class UploadSessionRow {
    private String id;
    private long ownerId;
    private Long parentId;
    private String displayName;
    private int totalChunks;
    private String status;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getOwnerId() { return ownerId; }
    public void setOwnerId(long ownerId) { this.ownerId = ownerId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
