package com.mengde.clouddrive.auth;

import java.time.LocalDateTime;

public class EmailVerificationRow {
    private String email;
    private String purpose;
    private String codeHash;
    private LocalDateTime sentAt;
    private LocalDateTime expiresAt;
    private int attempts;
    private boolean consumed;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public boolean isConsumed() { return consumed; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }
}
