-- 分类只保存元数据，不移动文件；只有管理员能写人工审核结论。
CREATE TABLE file_analysis (
    entry_id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    generation CHAR(36) NOT NULL,
    category VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    review_status VARCHAR(24) NOT NULL,
    review_summary VARCHAR(1000) NOT NULL,
    review_reason VARCHAR(1000) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    analyzed_at DATETIME(6) NULL,
    requested_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_file_analysis_entry FOREIGN KEY (entry_id) REFERENCES drive_entries(id) ON DELETE CASCADE,
    INDEX idx_file_analysis_owner (owner_id, entry_id),
    INDEX idx_file_analysis_review (review_status, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE analysis_settings (
    settings_key VARCHAR(32) NOT NULL PRIMARY KEY,
    settings_value VARCHAR(100) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
