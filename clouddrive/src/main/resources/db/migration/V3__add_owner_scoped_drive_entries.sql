-- Day16：账号配额 + 用户隔离 + 同级重名约束。
-- 1) 配额落在账号上，上传前用 SELECT ... FOR UPDATE 串行化额度判断；
-- 2) id 改为数据库生成，调用方不再自带 id；
-- 3) parent_scope_id 把 NULL 父目录折叠成 0，让"同一父目录下不能重名"可以用唯一键表达
--    （MySQL 的唯一索引对 NULL 不做去重，所以不能直接用 parent_id）。
ALTER TABLE user_account
    ADD COLUMN quota_bytes BIGINT NOT NULL DEFAULT 1073741824;

ALTER TABLE drive_entries
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT,
    ADD COLUMN parent_scope_id BIGINT GENERATED ALWAYS AS (IFNULL(parent_id, 0)) STORED,
    ADD CONSTRAINT uk_drive_entries_owner_parent_name
        UNIQUE (owner_id, parent_scope_id, display_name),
    ADD INDEX idx_drive_entries_owner_parent (owner_id, parent_scope_id, id);
