CREATE TABLE file_search_state (
    owner_id BIGINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    indexed_revision BIGINT NOT NULL DEFAULT -1,
    generation VARCHAR(36) NULL
);
