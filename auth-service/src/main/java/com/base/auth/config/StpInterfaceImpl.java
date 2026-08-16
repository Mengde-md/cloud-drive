package com.base.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SA-Token 权限认证接口实现
 *
 * ========================================================================
 * 【面试知识点 —— 为什么需要这个类？】
 * ========================================================================
 *
 * SA-Token 的权限认证体系分为两层：
 *   1. 身份认证（Authentication）：你是谁？—— 通过 StpUtil.login() 完成
 *   2. 权限认证（Authorization）：你能做什么？—— 通过 StpUtil.checkPermission() 完成
 *
 * 当代码中调用 StpUtil.checkPermission("user:add") 时，SA-Token 需要知道
 * "当前登录用户到底有没有 user:add 这个权限"。
 * 它通过 StpInterface 接口来查询，所以我们必须实现这个接口。
 *
 * 如果不实现 StpInterface：
 *   - StpUtil.login() 等身份认证功能正常工作（不依赖权限接口）
 *   - 但 StpUtil.checkPermission() / StpUtil.checkRole() 会抛出异常
 *   - 报错信息：NotImplException: 未实现权限数据加载接口
 *
 * 所以即使当前项目不需要细粒度权限控制，也建议实现这个接口（返回空列表），
 * 避免未来某天不小心调用了权限校验方法导致线上报错。
 *
 * ========================================================================
 * 【面试知识点 —— 设计模式：策略模式（Strategy Pattern）】
 * ========================================================================
 *
 * StpInterface 是典型的策略模式应用：
 *
 *   ┌──────────────────┐
 *   │  StpInterface     │  ← SA-Token 定义的策略接口
 *   │  (抽象策略)       │
 *   └────────▲─────────┘
 *            │ implements
 *   ┌────────┴─────────┐
 *   │ StpInterfaceImpl  │  ← 我们提供的具体策略
 *   │ (具体策略)        │
 *   └──────────────────┘
 *
 * 策略模式的好处：
 *   - SA-Token 不需要知道你的权限数据存在哪里（MySQL？Redis？LDAP？）
 *   - 你只需要实现接口，告诉 SA-Token "这个用户有哪些权限和角色"
 *   - 切换数据源时只需改实现类，不需要改框架代码
 *   - 符合开闭原则（对扩展开放，对修改关闭）
 *
 * 类似的策略模式在 Spring 中也常见：
 *   - UserDetailsService（Spring Security 的用户加载策略）
 *   - HandlerMethodArgumentResolver（Spring MVC 的参数解析策略）
 *   - AuthenticationProvider（Spring Security 的认证策略）
 *
 * ========================================================================
 * 【面试知识点 —— 后续扩展方向】
 * ========================================================================
 *
 * 当项目需要细粒度权限控制时，可以这样扩展：
 *
 * @Component
 * public class StpInterfaceImpl implements StpInterface {
 *
 *     @Autowired
 *     private UserPermissionMapper permissionMapper;
 *
 *     @Autowired
 *     private UserRoleMapper roleMapper;
 *
 *     @Override
 *     public List<String> getPermissionList(Object loginId, String loginType) {
 *         // 从数据库查询该用户拥有的权限列表
 *         // 例如：["user:add", "user:edit", "file:upload", "file:delete"]
 *         return permissionMapper.selectByUserId(Long.parseLong(loginId.toString()));
 *     }
 *
 *     @Override
 *     public List<String> getRoleList(Object loginId, String loginType) {
 *         // 从数据库查询该用户拥有的角色列表
 *         // 例如：["admin", "editor"]
 *         return roleMapper.selectByUserId(Long.parseLong(loginId.toString()));
 *     }
 * }
 *
 * 然后在 Controller 中就可以使用：
 *   StpUtil.checkPermission("user:add");   // 校验是否有 user:add 权限
 *   StpUtil.checkRole("admin");            // 校验是否有 admin 角色
 *   StpUtil.hasPermission("file:upload");  // 返回 boolean，不抛异常
 *
 * 也可以配合注解使用：
 *   @SaCheckPermission("user:add")         // 方法级权限校验
 *   @SaCheckRole("admin")                  // 方法级角色校验
 *
 * ========================================================================
 * 【面试知识点 —— loginId vs loginType】
 * ========================================================================
 *
 * 参数说明：
 *   - loginId：登录标识，就是我们调用 StpUtil.login(userId) 时传入的 userId
 *              类型为 Object 是因为 SA-Token 支持多种登录类型（String/Long/Int）
 *   - loginType：账号体系标识，默认为 "login"
 *              SA-Token 支持多账号体系，比如：
 *              - "login" 表示普通用户
 *              - "admin" 表示管理员
 *              - "user" 和 "admin" 可以有各自独立的 Token 命名空间
 *              我们项目只有单一用户体系，所以 loginType 始终是 "login"
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /**
     * 获取指定用户的权限列表
     *
     * 当前项目暂未使用细粒度权限控制，返回空列表。
     * 后续可从数据库查询用户的权限标识列表。
     *
     * @param loginId   用户 ID（登录时传入的标识）
     * @param loginType 账号体系标识（默认 "login"）
     * @return 权限标识列表，如 ["user:add", "user:edit"]
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return new ArrayList<>();
    }

    /**
     * 获取指定用户的角色列表
     *
     * 当前项目暂未使用角色控制，返回空列表。
     * 后续可从数据库查询用户的角色标识列表。
     *
     * @param loginId   用户 ID（登录时传入的标识）
     * @param loginType 账号体系标识（默认 "login"）
     * @return 角色标识列表，如 ["admin", "editor"]
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return new ArrayList<>();
    }
}
