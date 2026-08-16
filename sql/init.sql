-- =============================================
-- 基础项目 数据库初始化脚本
-- 包含：用户表、文件表、分片表、分享表
-- =============================================

-- 如果数据库不存在则创建
CREATE DATABASE IF NOT EXISTS base_project DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE base_project;

-- =============================================
-- 用户表（auth-service 和 user-service 共用）
-- =============================================
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `email` VARCHAR(128) NOT NULL COMMENT '邮箱',
    `nick_name` VARCHAR(50) NOT NULL COMMENT '昵称',
    -- 【注意】改用 BCrypt 后，哈希长度从 32 位（MD5）变为 60 位
    -- VARCHAR(128) 留足余量，BCrypt 输出格式为 $2a$10$xxx...（60个字符）
    `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希（BCrypt）',
    `use_space` BIGINT NOT NULL DEFAULT 0 COMMENT '已使用空间(字节)',
    `total_space` BIGINT NOT NULL DEFAULT 1073741824 COMMENT '总空间(字节)，默认1GB',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- =============================================
-- 用户文件表（逻辑层面，包含文件和文件夹）
-- =============================================
CREATE TABLE IF NOT EXISTS `user_file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '上级文件夹ID，顶级为0',
    `real_file_id` BIGINT DEFAULT NULL COMMENT '真实文件ID（关联file表），文件夹时为NULL',
    `filename` VARCHAR(255) NOT NULL COMMENT '文件名',
    `folder_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '是否是文件夹（0否 1是）',
    `file_size_desc` VARCHAR(32) DEFAULT NULL COMMENT '文件大小展示字符',
    `file_type` INT DEFAULT NULL COMMENT '文件类型（1普通 2压缩 3excel 4word 5pdf 6txt 7图片 8音频 9视频 10ppt 11源码 12csv）',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
    PRIMARY KEY (`id`),
    KEY `idx_user_parent` (`user_id`, `parent_id`),
    KEY `idx_user_deleted` (`user_id`, `deleted`),
    KEY `idx_user_folder_deleted` (`user_id`, `folder_flag`, `deleted`),
    KEY `idx_real_file_id` (`real_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文件表';

-- =============================================
-- 物理文件表（存储实际文件信息）
-- =============================================
CREATE TABLE IF NOT EXISTS `file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件ID',
    `filename` VARCHAR(255) NOT NULL COMMENT '文件名称',
    `real_path` VARCHAR(512) NOT NULL COMMENT '文件物理存储路径',
    `file_size` BIGINT NOT NULL COMMENT '文件大小（字节）',
    `file_size_desc` VARCHAR(32) NOT NULL COMMENT '文件大小展示字符',
    `file_suffix` VARCHAR(16) DEFAULT NULL COMMENT '文件后缀',
    `file_preview_content_type` VARCHAR(128) DEFAULT NULL COMMENT '文件预览Content-Type',
    `identifier` VARCHAR(64) NOT NULL COMMENT '文件唯一标识（MD5），用于秒传',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_identifier` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物理文件表';

-- =============================================
-- 文件分片表
-- =============================================
CREATE TABLE IF NOT EXISTS `file_chunk` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片记录ID',
    `identifier` VARCHAR(64) NOT NULL COMMENT '文件唯一标识',
    `real_path` VARCHAR(512) NOT NULL COMMENT '分片真实存储路径',
    `chunk_number` INT NOT NULL COMMENT '分片编号',
    `expiration_time` DATETIME NOT NULL COMMENT '过期时间',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_identifier_expiration` (`identifier`, `expiration_time`),
    KEY `idx_expiration` (`expiration_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分片表';

-- =============================================
-- 分享表（新增）
-- =============================================
-- 【设计说明】
-- 一条 share 记录对应一个分享链接，可以包含多个文件
-- share_url 是 UUID 短链，用于前端路由（不可预测，防止遍历攻击）
-- share_code 是提取码，用于私密分享（4位随机字母数字）
-- share_day_type 支持 1天/7天/30天/永久 四种有效期
CREATE TABLE IF NOT EXISTS `share` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分享ID',
    `share_name` VARCHAR(255) NOT NULL COMMENT '分享名称（默认取第一个文件名）',
    `share_type` TINYINT NOT NULL DEFAULT 0 COMMENT '分享类型（0公开 1需要提取码）',
    `share_day_type` INT NOT NULL DEFAULT 0 COMMENT '有效期类型（1=一天 7=七天 30=三十天 0=永久）',
    `share_day` INT NOT NULL DEFAULT 0 COMMENT '有效天数',
    `share_end_time` DATETIME DEFAULT NULL COMMENT '过期时间（永久则为NULL）',
    `share_url` VARCHAR(64) NOT NULL COMMENT '分享链接标识（UUID短链，16字符）',
    `share_code` VARCHAR(8) DEFAULT NULL COMMENT '提取码（公开分享为NULL）',
    `share_status` TINYINT NOT NULL DEFAULT 0 COMMENT '分享状态（0正常 1已取消 2已过期）',
    `create_user` BIGINT NOT NULL COMMENT '创建者用户ID',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除(0未删除 1已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_url` (`share_url`),
    KEY `idx_create_user` (`create_user`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享表';

-- =============================================
-- 分享文件关联表（新增）
-- =============================================
-- 一个分享可以包含多个文件，通过此表关联 share 和 user_file
CREATE TABLE IF NOT EXISTS `share_file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `share_id` BIGINT NOT NULL COMMENT '分享ID（关联share表）',
    `file_id` BIGINT NOT NULL COMMENT '用户文件ID（关联user_file表）',
    `create_user` BIGINT NOT NULL COMMENT '创建者用户ID',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_share_id_deleted` (`share_id`, `deleted`),
    KEY `idx_file_id` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享文件关联表';
