-- Day22：回收站（逻辑删除）。
-- 难点不是加一个 deleted_at 字段，而是"重名唯一键必须只约束未删除的条目"：
-- 删除 a.txt 后应该还能再建一个 a.txt，而两者又都要留在表里。
-- 做法：把参与唯一键的两列换成"仅未删除时才有值"的生成列，
-- 已删除行在这两列上是 NULL，而 MySQL 唯一索引不对 NULL 去重，因此天然被排除在约束之外。
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
