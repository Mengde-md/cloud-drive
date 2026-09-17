ALTER TABLE user_account
    ADD COLUMN quota_bytes BIGINT NOT NULL DEFAULT 1073741824;

ALTER TABLE drive_entries
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
    ADD COLUMN parent_scope_id BIGINT GENERATED ALWAYS AS (IFNULL(parent_id, 0)) STORED,
    ADD CONSTRAINT uk_drive_entries_owner_parent_name UNIQUE (owner_id, parent_scope_id, display_name),
    ADD INDEX idx_drive_entries_owner_parent (owner_id, parent_scope_id, id);
