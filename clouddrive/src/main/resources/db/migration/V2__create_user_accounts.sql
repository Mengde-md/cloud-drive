-- Day15：注册/登录所需的账号表。
-- password_hash 只存 BCrypt 摘要，任何情况下都不落明文口令。
CREATE TABLE user_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_account_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
