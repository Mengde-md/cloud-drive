-- Day18：分片上传 / 断点续传。
-- upload_sessions 是一次大文件上传的会话；upload_chunks 是该会话已落盘的分片清单(manifest)。
-- (session_id, chunk_index) 复合主键让"同一分片重复提交"天然幂等；
-- ON DELETE CASCADE 保证会话被清理时不会留下孤儿分片记录。
CREATE TABLE upload_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    display_name VARCHAR(255) NOT NULL,
    total_chunks INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_upload_sessions_owner FOREIGN KEY (owner_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE upload_chunks (
    session_id VARCHAR(36) NOT NULL,
    chunk_index INT NOT NULL,
    byte_size BIGINT NOT NULL,
    content_md5 CHAR(32) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    PRIMARY KEY (session_id, chunk_index),
    CONSTRAINT fk_upload_chunks_session FOREIGN KEY (session_id)
        REFERENCES upload_sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
