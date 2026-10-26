-- Day17：真实上传落地所需的存储元数据。
-- storage_key 指向对象存储中的实际文件；content_md5 用于秒传候选命中。
-- (owner_id, content_md5) 复合索引保证秒传查询只在"本人已有文件"范围内命中，
-- 从设计上排除"拿到别人文件的 MD5 就能白拿文件"的越权路径。
ALTER TABLE drive_entries
    ADD COLUMN storage_key VARCHAR(500) NULL,
    ADD COLUMN content_md5 CHAR(32) NULL,
    ADD INDEX idx_drive_entries_owner_md5 (owner_id, content_md5);
