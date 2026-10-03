ALTER TABLE file_quota_operations DROP CHECK chk_file_quota_phase;
ALTER TABLE file_quota_operations ADD CONSTRAINT chk_file_quota_phase
    CHECK (phase IN ('PREPARED', 'RELEASE_PENDING', 'COMMIT_PENDING', 'DONE'));
