CREATE TABLE user_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    quota_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_profile_quota CHECK (quota_bytes >= 0)
);

CREATE TABLE quota_reservations (
    reservation_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_quota_reservation_user_state (user_id, state, expires_at),
    CONSTRAINT chk_reservation_amount CHECK (amount >= 0)
);

-- One-time backfill from the Day25 shared schema. New accounts are initialized on first reserve.
INSERT INTO user_profile (user_id, quota_bytes)
SELECT id, quota_bytes FROM user_account;
