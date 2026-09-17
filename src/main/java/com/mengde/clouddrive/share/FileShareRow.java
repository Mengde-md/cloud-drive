package com.mengde.clouddrive.share;

import java.time.LocalDateTime;

public class FileShareRow {
    private String token; private long documentId; private long ownerId; private String passcodeHash; private LocalDateTime expiresAt;
    public String getToken() { return token; } public void setToken(String token) { this.token = token; }
    public long getDocumentId() { return documentId; } public void setDocumentId(long documentId) { this.documentId = documentId; }
    public long getOwnerId() { return ownerId; } public void setOwnerId(long ownerId) { this.ownerId = ownerId; }
    public String getPasscodeHash() { return passcodeHash; } public void setPasscodeHash(String passcodeHash) { this.passcodeHash = passcodeHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; } public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
