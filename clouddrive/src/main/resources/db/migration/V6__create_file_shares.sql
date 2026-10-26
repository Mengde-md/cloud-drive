-- Day19：提取码分享。
-- token 是对外暴露的随机分享标识；passcode_hash 存提取码的 BCrypt 摘要，
-- 即使分享记录被读到也无法还原提取码。expires_at 为 NULL 表示长期有效。
CREATE TABLE file_shares (
    token VARCHAR(64) NOT NULL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    passcode_hash VARCHAR(100) NOT NULL,
    expires_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_shares_owner_document (owner_id, document_id),
    CONSTRAINT fk_file_shares_owner FOREIGN KEY (owner_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
