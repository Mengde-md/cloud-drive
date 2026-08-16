package com.base.auth.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息响应 VO（返回给前端，不包含密码哈希等敏感字段）
 *
 * ========================================================================
 * 【面试知识点 —— 为什么要定义 VO 而不是直接返回 Entity？】
 * ========================================================================
 *
 * 1. 安全性：Entity 包含 passwordHash，直接返回会泄露密码哈希值
 *    即使前端不展示，哈希值本身也是敏感信息（可被离线暴力破解）
 *
 * 2. 接口稳定性：Entity 和数据库表结构强绑定，但 VO 和前端约定绑定
 *    - 如果数据库加了新字段（如 deleted），Entity 会变，但 VO 不需要变
 *    - 如果前端要求改名（如 gmtCreate → registerTime），只需改 VO，不影响 Entity
 *
 * 3. 数据脱敏：VO 可以对字段做格式化（如手机号中间四位打码）
 *    而 Entity 应该保持和数据库一致
 *
 * 4. 分层清晰：
 *    Entity ← 对应数据库表（持久层）
 *    VO     ← 对应前端接口（展示层）
 *    Param  ← 对应前端请求参数（入参层）
 *    DTO    ← 对应微服务间传输（传输层，本项目暂未用到）
 */
@Data
public class UserInfoVO {

    /** 用户 ID */
    private Long id;

    /** 邮箱 */
    private String email;

    /** 昵称 */
    private String nickName;

    /** 已用空间（字节） */
    private Long useSpace;

    /** 总空间（字节） */
    private Long totalSpace;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 注册时间 */
    private LocalDateTime gmtCreate;
}
