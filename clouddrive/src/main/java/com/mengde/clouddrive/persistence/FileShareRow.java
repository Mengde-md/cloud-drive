package com.mengde.clouddrive.persistence;

import java.time.LocalDateTime;

/**
 * 分享记录（Day19）。expiresAt 为 null 表示长期有效。
 * passcodeHash 只存摘要，任何地方都拿不回原始提取码。
 */
public class FileShareRow {

    private String token;
    private long documentId;
    private long ownerId;
    private String passcodeHash;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    /* 以下三个字段不在 file_shares 表里，是列表查询时从 drive_entries 联查带出来的。
       放在同一个行对象里是为了少建一个类；它们在单表查询（findByToken）时为 null。 */
    private String documentDisplayName;
    private Long documentByteSize;
    private boolean documentDeleted;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(long documentId) {
        this.documentId = documentId;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    public String getPasscodeHash() {
        return passcodeHash;
    }

    public void setPasscodeHash(String passcodeHash) {
        this.passcodeHash = passcodeHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getDocumentDisplayName() {
        return documentDisplayName;
    }

    public void setDocumentDisplayName(String documentDisplayName) {
        this.documentDisplayName = documentDisplayName;
    }

    public Long getDocumentByteSize() {
        return documentByteSize;
    }

    public void setDocumentByteSize(Long documentByteSize) {
        this.documentByteSize = documentByteSize;
    }

    public boolean isDocumentDeleted() {
        return documentDeleted;
    }

    public void setDocumentDeleted(boolean documentDeleted) {
        this.documentDeleted = documentDeleted;
    }
}
