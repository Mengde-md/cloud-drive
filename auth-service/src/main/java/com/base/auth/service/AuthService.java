package com.base.auth.service;

import com.base.auth.entity.User;
import com.base.auth.param.LoginParam;
import com.base.auth.param.RegisterParam;

/**
 * 认证服务接口
 *
 * 【面试知识点 —— 迁移 SA-Token 后接口签名的变化】
 *
 * 迁移前（手动 Token 方案）：
 *   - getUserByToken(String token)  —— 需要业务代码自己拿 token 去 Redis 查 userId
 *   - logout(String token)          —— 需要业务代码自己删 Redis key
 *   调用方必须把 token 字符串一路透传，Controller 要 @RequestHeader("token") 取出来再传进来。
 *
 * 迁移后（SA-Token 方案）：
 *   - getCurrentUser()  —— 无参，SA-Token 自动从当前请求上下文中读取 token 并解析出 loginId
 *   - logout()          —— 无参，SA-Token 自动定位当前会话的 token 并从 Redis 中删除
 *
 * 【为什么可以去掉 token 参数？】
 * SA-Token 内部维护了一套 Token 上下文机制：
 *   1. 前端请求时携带 satoken 请求头（或 Cookie）
 *   2. SA-Token 的 Filter/Interceptor 自动读取这个值
 *   3. 存入当前线程的 ThreadLocal（TokenContext）
 *   4. 业务代码调用 StpUtil.getLoginId() 时，SA-Token 从 ThreadLocal 拿到 token，
 *      再去 Redis 查询对应的 loginId，整个过程对业务代码完全透明。
 *
 * 所以 Service 层不再需要接收 token 参数——SA-Token 帮你管了。
 * 这也是为什么 login() 仍然返回 String：前端需要拿到 token 值存起来，后续请求带上。
 */
public interface AuthService {

    /**
     * 用户注册
     *
     * @param param 注册参数（邮箱、昵称、密码）
     * @return 新用户的 ID
     */
    Long register(RegisterParam param);

    /**
     * 用户登录
     *
     * 【为什么返回值还是 String？】
     * 虽然 SA-Token 内部管理 token，但前端仍然需要拿到 token 值，
     * 存到 localStorage / Vuex / Pinia 中，后续请求通过 satoken 请求头携带。
     * 所以我们调用 StpUtil.getTokenValue() 把生成的 token 字符串返回给前端。
     *
     * @param param 登录参数（邮箱、密码）
     * @return SA-Token 生成的 token 字符串（UUID 格式）
     */
    String login(LoginParam param);

    /**
     * 获取当前登录用户信息
     *
     * 【迁移变化】去掉了 String token 参数
     * 原因：SA-Token 通过 StpUtil.getLoginIdAsLong() 自动从当前请求上下文获取 loginId，
     * 业务代码不需要手动传递 token。这是 SA-Token 相比手动方案最大的便利之一。
     *
     * @return 当前登录的 User 对象；未登录时由 SA-Token 抛出 NotLoginException
     */
    User getCurrentUser();

    /**
     * 用户登出
     *
     * 【迁移变化】去掉了 String token 参数
     * 原因：StpUtil.logout() 会自动从当前请求上下文定位 token，
     * 并从 Redis 中删除对应的会话记录，实现即时失效。
     * 相比手动方案（手动 redisTemplate.delete(key)），代码更简洁、不易出错。
     */
    void logout();
}
