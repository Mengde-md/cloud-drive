-- Existing accounts keep access; new registrations must verify an email address.
ALTER TABLE user_account
    ADD COLUMN email VARCHAR(254) NULL,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_user_account_email UNIQUE (email);

-- The row lock serializes sending/consuming a code, including across server instances.
CREATE TABLE email_verification (
    email VARCHAR(254) NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    code_hash VARCHAR(64) NULL,
    sent_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (email, purpose)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- All administrator mutations take this lock to protect the last enabled admin.
CREATE TABLE account_security_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO account_security_guard (id) VALUES (1);
