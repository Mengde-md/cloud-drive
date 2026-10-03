CREATE TABLE file_quota_operations (
    reservation_id CHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    phase VARCHAR(24) NOT NULL,
    storage_key VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_quota_pending (owner_id, phase),
    CONSTRAINT chk_file_quota_phase CHECK (phase IN ('PREPARED', 'COMMIT_PENDING', 'DONE'))
);
