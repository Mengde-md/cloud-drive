package com.mengde.clouddrive.authservice;

/**
 * user_account 表的身份子集投影。
 *
 * 刻意**不包含** quota_bytes：Day23 第 2.1 节的"禁止"条写明 auth-service 不得直接
 * 读写配额。把字段从这个类里删掉，比写一条注释约束有效——想违规就得先改这个类，
 * 改动会出现在 diff 里。
 */
public class UserAccountRow {
    private Long id;
    private String username;
    private String passwordHash;
    private String role;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
