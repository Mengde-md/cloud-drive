ALTER TABLE drive_entries
    ADD COLUMN deleted_at DATETIME NULL,
    ADD COLUMN active_parent_scope_id BIGINT GENERATED ALWAYS AS
        (IF(deleted_at IS NULL, IFNULL(parent_id, 0), NULL)) STORED,
    ADD COLUMN active_display_name VARCHAR(255) GENERATED ALWAYS AS
        (IF(deleted_at IS NULL, display_name, NULL)) STORED,
    DROP INDEX uk_drive_entries_owner_parent_name,
    ADD CONSTRAINT uk_drive_entries_active_owner_parent_name
        UNIQUE (owner_id, active_parent_scope_id, active_display_name),
    ADD INDEX idx_drive_entries_owner_deleted_at (owner_id, deleted_at, id);
