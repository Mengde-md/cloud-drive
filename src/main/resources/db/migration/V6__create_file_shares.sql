CREATE TABLE file_shares (
    token VARCHAR(36) NOT NULL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    passcode_hash VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_file_shares_document FOREIGN KEY (document_id) REFERENCES drive_entries(id),
    CONSTRAINT fk_file_shares_owner FOREIGN KEY (owner_id) REFERENCES user_account(id)
);
