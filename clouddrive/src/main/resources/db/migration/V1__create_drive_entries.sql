-- Day07：能持久化的最小表。
-- 此时 id 由调用方提供，重复 id 由主键做最后防线；
-- owner_id 先留出归属信息，但用户隔离与越权校验在 Day16 才实现。
CREATE TABLE drive_entries (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    display_name VARCHAR(255) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    byte_size BIGINT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
