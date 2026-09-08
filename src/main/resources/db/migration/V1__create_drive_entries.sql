CREATE TABLE drive_entries (
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    parent_id BIGINT,
    display_name VARCHAR(255) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    byte_size BIGINT
);
