package com.base.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.base.auth.entity.User;
import com.base.auth.mapper.UserMapper;
import com.base.auth.param.LoginParam;
import com.base.auth.param.RegisterParam;
import com.base.auth.service.AuthService;
import com.base.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现（SA-Token 版）
 *
 * ========================================================================
 * 【面试知识点 —— 迁移前后核心对比】
 * ========================================================================
 *
 * ┌────────────────────┬───────────────────────────┬─────────────────────────────┐
 * │       功能          │  迁移前（手动 Token 方案）  │  迁移后（SA-Token 方案）      │
 * ├────────────────────┼───────────────────────────┼─────────────────────────────┤
 * │ 依赖                │ StringRedisTemplate       │ sa-token-redis-jackson      │
 * │ Token 生成          │ UUID.randomUUID()         │ StpUtil.login() 内部生成     │
 * │ Token 存 Redis     │ redisTemplate.set(key,v,ttl) │ SA-Token 自动写入 Redis   │
 * │ Token 读取          │ redisTemplate.get(key)    │ StpUtil.getLoginIdAsLong()  │
 * │ Token 删除（登出）   │ redisTemplate.delete(key) │ StpUtil.logout()            │
 * │ Token 续期          │ 手动重新 set               │ SA-Token 自动续期（isRenew） │
 * │ 踢人/单点登录       │ 需要自己写逻辑             │ StpUtil.kickout() 一行搞定   │
 * └────────────────────┴───────────────────────────┴─────────────────────────────┘
 *
 * ========================================================================
 * 【面试知识点 —— 为什么不再需要 StringRedisTemplate？】
 * ========================================================================
 *
 * 迁移前我们用 StringRedisTemplate 手动操作 Redis：
 *   - 登录：redisTemplate.opsForValue().set("token:" + uuid, userId, 7, TimeUnit.DAYS)
 *   - 查用户：redisTemplate.opsForValue().get("token:" + token) -> userId
 *   - 登出：redisTemplate.delete("token:" + token)
 *
 * 这些操作 SA-Token + sa-token-redis-jackson 全部自动完成了：
 *   - pom.xml 中引入 sa-token-redis-jackson 后，SA-Token 自动检测到 Redis 依赖，
 *     启用 Redis 存储模式（而非默认的内存存储）
 *   - StpUtil.login(userId) 时，SA-Token 自动生成 UUID token，
 *     以 "satoken:login:token:{uuid}" 为 key 存入 Redis，value 是 loginId
 *     同时以 "satoken:login:last-activity:{loginId}" 记录最后活跃时间
 *   - StpUtil.getLoginIdAsLong() 时，SA-Token 从当前请求的 satoken 请求头
 *     读取 token，然后去 Redis 查对应的 loginId
 *   - StpUtil.logout() 时，SA-Token 自动从 Redis 删除相关 key
 *
 * 所以我们的代码中不再出现任何 redisTemplate 引用——SA-Token 帮我们管了。
 *
 * ========================================================================
 * 【面试知识点 —— StpUtil.login() 内部做了什么？】
 * ========================================================================
 *
 * 调用 StpUtil.login(userId) 时，SA-Token 依次执行：
 *   1. 检查是否重复登录（同账号是否已有有效 token）
 *      - 默认策略：如果已登录则复用已有 token（可通过配置改为踢掉旧会话）
 *   2. 生成 Token 值
 *      - 根据 application.yml 中 token-style: uuid 配置，生成 UUID 格式
 *      - 其他可选格式：simple-uuid、random-32、random-64、random-128、tik
 *   3. 写入 Redis
 *      - key: satoken:login:token:{uuid}  value: {userId}
 *      - TTL: 根据 timeout: 2592000（30天，单位秒）设置过期时间
 *   4. 写入当前请求上下文（ThreadLocal）
 *      - 后续同一请求中调用 StpUtil.getLoginId() 直接从上下文读取，不用再查 Redis
 *   5. （可选）写入响应 Cookie
 *      - 如果 isReadCookie: true（默认），SA-Token 会在响应中设置 Cookie
 *      - 我们是前后端分离 + 网关架构，主要依赖请求头，Cookie 用得少
 *
 * ========================================================================
 * 【面试知识点 —— StpUtil.getLoginIdAsLong() 的工作原理】
 * ========================================================================
 *
 * 调用链：
 *   1. 前端请求携带 satoken 请求头（值就是登录时拿到的 token 字符串）
 *   2. SA-Token 的 SaTokenFilter / SaTokenInterceptor 拦截请求
 *   3. 从请求头中读取 satoken 值，存入当前线程的 TokenContext（ThreadLocal）
 *   4. 业务代码调用 StpUtil.getLoginIdAsLong()
 *   5. SA-Token 从 TokenContext 取出 token 字符串
 *   6. 用这个 token 去 Redis 查询对应的 loginId
 *   7. 将 loginId 转为 Long 类型返回
 *   8. 如果 token 不存在或已过期，抛出 NotLoginException
 *
 * 【为什么要用 ThreadLocal？】
 * 因为 HTTP 请求是线程模型——每个请求由一个线程处理。
 * ThreadLocal 保证同一请求链路中（Controller → Service → Mapper）
 * 都能拿到同一个 token 上下文，且不同请求之间互不干扰。
 *
 * ========================================================================
 * 【面试知识点 —— 完整的 Token 流转链路】
 * ========================================================================
 *
 *  前端                        网关 (Gateway)                  auth-service
 *   │                              │                               │
 *   │  ① POST /api/auth/login     │                               │
 *   │  {email, password}          │                               │
 *   │─────────────────────────────>│  转发（白名单路由，不校验token） │
 *   │                              │──────────────────────────────>│
 *   │                              │                               │ StpUtil.login(userId)
 *   │                              │                               │ → 生成UUID token
 *   │                              │                               │ → 存入Redis（TTL=30天）
 *   │                              │                               │ → 返回token字符串
 *   │                              │<──────────────────────────────│
 *   │  返回 token 字符串            │                               │
 *   │<─────────────────────────────│                               │
 *   │                              │                               │
 *   │  ② GET /api/auth/userinfo   │                               │
 *   │  Header: satoken={uuid}     │                               │
 *   │─────────────────────────────>│                               │
 *   │                              │  网关校验token有效性            │
 *   │                              │  → 调用SA-Token或Redis验证     │
 *   │                              │  → 有效：转发请求               │
 *   │                              │  → 无效：返回401               │
 *   │                              │──────────────────────────────>│
 *   │                              │                               │ StpUtil.getLoginIdAsLong()
 *   │                              │                               │ → 从satoken请求头读token
 *   │                              │                               │ → Redis查loginId
 *   │                              │                               │ → 返回用户信息
 *   │                              │<──────────────────────────────│
 *   │  返回用户信息                 │                               │
 *   │<─────────────────────────────│                               │
 *
 * ========================================================================
 * 【面试知识点 —— BCrypt 密码加密】
 * ========================================================================
 *
 * BCrypt 仍然是密码存储的最佳实践（无论用什么认证框架）：
 *   - 自带随机盐（每次加密结果不同）
 *   - cost factor（默认10）可调整计算成本，对抗 GPU 暴力破解
 *   - 输出格式：$2a$10$<22字符盐><31字符哈希>
 *   - matches(明文, 密文) 自动提取盐值比对，无需单独存储盐
 *
 * SA-Token 不管密码——密码校验永远是业务层的职责。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;

    // 【迁移变化】不再注入 StringRedisTemplate
    // 原因：sa-token-redis-jackson 已接管 Redis 操作
    // SA-Token 内部使用自己的 SaTokenDao（Redis实现）来管理 Token 的增删查
    // 我们在 application.yml 中配置了 sa-token 相关参数，SA-Token 自动完成一切

    /**
     * BCrypt 密码编码器
     *
     * 【为什么不用 @Bean 注入而是 static final？】
     * BCryptPasswordEncoder 是无状态的线程安全工具类，
     * 不需要 Spring 管理其生命周期，直接 new 一个全局共享实例即可。
     * 当然，如果你在 SecurityConfig 中已经 @Bean 了一个，也可以注入使用，效果一样。
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * 用户注册
     *
     * 注册逻辑与认证框架无关——无论用 Spring Security、Shiro 还是 SA-Token，
     * 注册都是：校验唯一性 → 加密密码 → 存数据库。
     */
    @Override
    public Long register(RegisterParam param) {
        // 检查邮箱是否已注册
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, param.getEmail()));
        if (count > 0) {
            throw new BusinessException("该邮箱已被注册");
        }

        User user = new User();
        user.setEmail(param.getEmail());
        user.setNickName(param.getNickName());

        // BCrypt 加密密码
        // encode() 内部自动生成随机盐，输出格式：$2a$10$<盐><哈希>
        user.setPasswordHash(PASSWORD_ENCODER.encode(param.getPassword()));

        user.setUseSpace(0L);
        user.setTotalSpace(1073741824L); // 1GB 默认空间
        user.setDeleted(0);
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 用户登录
     *
     * 【迁移核心变化】
     * 迁移前：手动 UUID.randomUUID() → 手动 redisTemplate.set() → 手动设置过期时间
     * 迁移后：一行 StpUtil.login(user.getId()) 全部搞定
     *
     * StpUtil.login(userId) 内部执行：
     *   1. 生成 UUID 格式的 token（由 application.yml 中 token-style: uuid 决定）
     *   2. 将 token → userId 映射存入 Redis
     *      key: satoken:login:token:{uuid}
     *      value: userId
     *      TTL: 2592000秒（30天，由 timeout 配置决定）
     *   3. 将 token 存入当前线程上下文（ThreadLocal）
     *   4. 如果是并发登录策略，还会处理旧会话
     *
     * 然后我们调用 StpUtil.getTokenValue() 获取刚才生成的 token 字符串，
     * 返回给前端，前端存到 localStorage 中，后续请求携带。
     *
     * @return token 字符串，前端需要保存并在后续请求中通过 satoken 请求头携带
     */
    @Override
    public String login(LoginParam param) {
        // 1. 根据邮箱查询未删除的用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getEmail, param.getEmail())
                        .eq(User::getDeleted, 0));
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 2. BCrypt 校验密码
        // matches() 自动从 passwordHash 中提取盐值，对明文密码加密后比对
        if (!PASSWORD_ENCODER.matches(param.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("密码错误");
        }

        // 3. 【核心】SA-Token 登录 —— 一行代码替代之前的 5 行手动 Redis 操作
        // StpUtil.login() 完成后：
        //   - Redis 中已存入 token → userId 映射
        //   - 当前线程上下文中已存入 token 值
        //   - 如果配置了 isReadCookie: true，响应中还会自动设置 Cookie
        StpUtil.login(user.getId());

        // 4. 更新最后登录时间（业务逻辑，与认证框架无关）
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 5. 获取刚才生成的 token 值，返回给前端
        // StpUtil.getTokenValue() 从当前线程上下文中读取，不需要再查 Redis
        return StpUtil.getTokenValue();
    }

    /**
     * 获取当前登录用户
     *
     * 【迁移核心变化】
     * 迁移前：接收 token 参数 → redisTemplate.get("token:" + token) → 拿到 userId → selectById
     * 迁移后：StpUtil.getLoginIdAsLong() 一步到位
     *
     * StpUtil.getLoginIdAsLong() 内部执行：
     *   1. 从当前请求的 satoken 请求头中读取 token 值
     *      （SA-Token 的 Filter 已经在请求进入时把它存入了 ThreadLocal）
     *   2. 用 token 去 Redis 查询对应的 loginId
     *      key: satoken:login:token:{token值}
     *   3. 将 loginId 转为 Long 返回（因为 login 时传的是 Long 类型的 userId）
     *   4. 如果 token 不存在或已过期 → 抛出 NotLoginException
     *
     * 【异常处理】
     * NotLoginException 由 GlobalExceptionHandler 统一捕获，返回 401 给前端。
     * 所以我们这里不需要写 if (loginId == null) 之类的判断。
     *
     * @return 当前登录的 User 对象
     */
    @Override
    public User getCurrentUser() {
        // StpUtil.getLoginIdAsLong() —— SA-Token 的核心 API
        // 它完成了：读请求头 → 查 Redis → 类型转换，三步合一
        // 如果未登录（token缺失/过期/无效），会抛出 NotLoginException
        Long userId = StpUtil.getLoginIdAsLong();

        // 用 userId 查数据库获取完整用户信息
        return userMapper.selectById(userId);
    }

    /**
     * 用户登出
     *
     * 【迁移核心变化】
     * 迁移前：接收 token 参数 → redisTemplate.delete("token:" + token)
     * 迁移后：StpUtil.logout() 一行搞定
     *
     * StpUtil.logout() 内部执行：
     *   1. 从当前线程上下文中获取 token 值
     *   2. 从 Redis 中删除 satoken:login:token:{token值} 这个 key
     *   3. 清除当前线程的 TokenContext（ThreadLocal）
     *   4. 后续请求如果还带这个 token，就会查不到 → 判定为未登录
     *
     * 【面试亮点：主动失效 vs 被动过期】
     * - 主动失效（logout 删 Redis key）：用户点击退出后立即生效，安全性高
     * - 被动过期（Redis TTL 到期自动删除）：用户不操作，30天后自动失效
     * - SA-Token 两者都支持：logout 主动删除 + timeout 被动兜底
     * - 对比 JWT：JWT 无法主动失效（除非额外维护黑名单，那就又需要 Redis 了）
     */
    @Override
    public void logout() {
        // 无需参数——SA-Token 自动从上下文定位当前会话的 token 并删除
        StpUtil.logout();
    }
}
