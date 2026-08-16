-- =============================================
-- 合规升级迁移脚本：公安联网备案所需
-- 适用版本：基础项目 v1.0.0
-- 执行时机：用户实名认证 + 内容审核接入前一次性执行
-- =============================================
-- 【备案依据】
--   - 《网络安全法》第二十一条：网络日志留存不少于 6 个月
--   - 《个人信息保护法》：个人身份信息加密存储
--   - 《网络安全审查办法》：涉网数据出境审查
-- =============================================

USE base_project;

-- =============================================
-- 1. user 表加实名状态字段
-- =============================================
-- 【字段说明】
-- real_name_status: -1=未实名 0=审核中 1=已通过 2=已拒绝
-- real_name_time: 实名通过时间（用于人脸活体再次校验周期）
-- is_upload_allowed: 是否允许上传文件（未实名前 false）
ALTER TABLE `user`
    ADD COLUMN `real_name_status` TINYINT NOT NULL DEFAULT -1
        COMMENT '实名状态 (-1未实名 0审核中 1已通过 2已拒绝)' AFTER `last_login_time`,
    ADD COLUMN `real_name_time` DATETIME DEFAULT NULL
        COMMENT '实名通过时间' AFTER `real_name_status`,
    ADD COLUMN `id_card_hash` VARCHAR(64) DEFAULT NULL
        COMMENT '身份证号SHA-256哈希(只存哈希不存原值)' AFTER `real_name_time`;


-- =============================================
-- 2. user_file 表加内容审核状态
-- =============================================
-- 文件上传后状态流转：
-- 0=待审核 → 1=已通过（异步审核完成后置位，允许下载）
--          → 2=已拒绝（命中违规，仅删除逻辑记录，物理文件异步清理）
--          → 3=申诉中（用户提交申诉）
ALTER TABLE `user_file`
    ADD COLUMN `audit_status` TINYINT NOT NULL DEFAULT 0
        COMMENT '内容审核状态 (0待审核 1通过 2拒绝 3申诉中)' AFTER `deleted`,
    ADD COLUMN `audit_time` DATETIME DEFAULT NULL
        COMMENT '审核完成时间' AFTER `audit_status`,
    ADD COLUMN `audit_label` VARCHAR(64) DEFAULT NULL
        COMMENT '命中违规分类(用于申诉参考)，如 porn/terrorism/politics' AFTER `audit_time`;


-- =============================================
-- 3. 新增 用户实名认证 表
-- =============================================
-- 一次实名认证可能需要多种方式（人脸、身份证、银行卡、手机号四要素）
-- 我们采用实名认证服务商做主核对（阿里云实人认证 / 腾讯云慧眼）
-- 服务商返回的核验结果落在本表
CREATE TABLE IF NOT EXISTS `user_realname` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `id_card_hash` CHAR(64) NOT NULL COMMENT '身份证号SHA-256哈希',
    `real_name_hash` CHAR(64) NOT NULL COMMENT '真实姓名哈希(可逆加密由业务侧处理)',
    `verify_channel` VARCHAR(32) NOT NULL COMMENT '认证渠道(aliyun|tencent|... )',
    `verify_scene` VARCHAR(32) DEFAULT NULL COMMENT '认证场景(liveness|idcard)',
    `verify_token` VARCHAR(128) DEFAULT NULL COMMENT '本次认证会话token(用于核验结果回调)',
    `verify_result` TINYINT NOT NULL COMMENT '核验结果(0失败 1通过)',
    `verify_score` DECIMAL(5,4) DEFAULT NULL COMMENT '认证置信度分数(0-1)',
    `verify_raw` JSON DEFAULT NULL COMMENT '认证服务原始返回(便于审计)',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '认证时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_id_card_hash` (`id_card_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户实名认证表';


-- =============================================
-- 4. 新增 文件内容审核日志 表
-- =============================================
-- 【公安合规必存】所有上传文件的审核结果必须落库
-- 留存 6 个月以上（按《网络安全法》第二十一条）
CREATE TABLE IF NOT EXISTS `file_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_file_id` BIGINT NOT NULL COMMENT '用户文件记录ID',
    `user_id` BIGINT NOT NULL COMMENT '操作人',
    `audit_channel` VARCHAR(32) NOT NULL COMMENT '审核服务渠道',
    `audit_scene` VARCHAR(64) DEFAULT NULL COMMENT '审核场景(text|image|video|audio)',
    `audit_status` TINYINT NOT NULL COMMENT '审核结果(1通过 2拒绝 3待人工)',
    `audit_labels` VARCHAR(512) DEFAULT NULL COMMENT '命中标签(porn|terrorism|politics...)',
    `audit_score` DECIMAL(5,4) DEFAULT NULL COMMENT '风险分数',
    `audit_raw` JSON DEFAULT NULL COMMENT '审核服务原始返回',
    `audit_action` VARCHAR(32) DEFAULT NULL COMMENT '采取的动作(block|review|pass)',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审核时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_file_id` (`user_file_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_audit_status` (`audit_status`),
    KEY `idx_gmt_create` (`gmt_create`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件内容审核日志表';


-- =============================================
-- 5. 新增 用户操作审计日志 表（公安合规必存）
-- =============================================
-- 涵盖：登录、注册、上传、下载、分享、删除、注销、实名
-- 留存 6 个月以上
CREATE TABLE IF NOT EXISTS `user_operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_id` BIGINT NOT NULL COMMENT '操作用户',
    `operation` VARCHAR(32) NOT NULL COMMENT '操作类型(LOGIN/REGISTER/UPLOAD/DOWNLOAD/SHARE/DELETE/...)',
    `target_id` BIGINT DEFAULT NULL COMMENT '操作对象ID(文件/分享)',
    `target_type` VARCHAR(32) DEFAULT NULL COMMENT '操作对象类型',
    `ip` VARCHAR(45) DEFAULT NULL COMMENT '客户端IP(v4/v6都兼容)',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT '用户代理',
    `extra` JSON DEFAULT NULL COMMENT '附加信息',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_operation` (`operation`),
    KEY `idx_gmt_create` (`gmt_create`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户操作审计日志表';


-- =============================================
-- 6. share 表加审核状态
-- =============================================
ALTER TABLE `share`
    ADD COLUMN `audit_status` TINYINT NOT NULL DEFAULT 0
        COMMENT '分享审核状态 (0待审核 1通过 2拒绝)' AFTER `share_status`;

-- =============================================
-- 完成标记：执行成功标准
-- =============================================
-- SELECT '全部迁移脚本执行完毕' AS status;
