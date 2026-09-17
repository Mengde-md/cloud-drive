ALTER TABLE drive_entries
    ADD COLUMN storage_key VARCHAR(500) NULL,
    ADD COLUMN content_md5 CHAR(32) NULL,
    ADD INDEX idx_drive_entries_owner_md5 (owner_id, content_md5);
