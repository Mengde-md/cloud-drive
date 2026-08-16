package com.base.auth.controller;

import com.base.auth.convertor.AuthConvertor;
import com.base.auth.param.LoginParam;
import com.base.auth.param.RegisterParam;
import com.base.auth.service.AuthService;
import com.base.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器（SA-Token 版）
 *
 * ========================================================================
 * 【面试知识点 —— 迁移前后 Controller 的核心变化】
 * ========================================================================
 *
 * 最大的变化：不再需要 @RequestHeader("token") 参数
 *
 * 迁移前：
 *   public Result<?> userInfo(@RequestHeader("token") String token) {
 *       var user = authService.getUserByToken(token);  // 手动传 token
 *   }
 *
 * 迁移后：
 *   public Result<?> userInfo() {
 *       var user = authService.getCurrentUser();  // SA-Token 自动处理
 *   }
 *
 * 【为什么不再需要 @RequestHeader("token")？】
 *
 * 1. 请求头名称变了
 *    - 迁移前：前端发送 Header: token=xxxxx，我们用 @RequestHeader("token") 接收
 *    - 迁移后：前端发送 Header: satoken=xxxxx（由 application.yml 中 token-name: satoken 决定）
 *
 * 2. 读取方式变了
 *    - 迁移前：Controller 手动从请求头取值，传给 Service，Service 再去 Redis 查
 *    - 迁移后：SA-Token 的 Filter/Interceptor 在请求进入时自动读取 satoken 请求头，
 *      存入当前线程的 TokenContext（ThreadLocal），业务代码直接调用 StpUtil 即可获取
 *
 * 3. 代码更简洁
 *    - 迁移前：Controller → 取 header → 传 Service → 传 Redis → 拿到 userId → 查 DB
 *    - 迁移后：Controller → 调 Service → StpUtil 一步到位
 *    链路从 6 步缩减到 3 步，减少了 token 在方法参数间透传的"管道代码"
 *
 * ========================================================================
 * 【面试知识点 —— 网关层的 Token 校验流程】
 * ========================================================================
 *
 * 在微服务网关架构中，Token 校验通常在网关层完成：
 *
 *   前端请求
 *     │ Header: satoken = {uuid}
 *     ▼
 *   网关 (Gateway)
 *     │ 1. 从 satoken 请求头读取 token
 *     │ 2. 调用 SA-Token 或直连 Redis 验证 token 有效性
 *     │ 3. 有效：从 token 解析出 userId
 *     │         将 userId 写入 X-User-Id 请求头
 *     │         转发请求到下游服务
 *     │ 4. 无效：直接返回 401，请求不到达下游服务
 *     ▼
 *   auth-service / file-service / ...
 *     │ 下游服务可以通过 @RequestHeader("X-User-Id") 获取用户 ID
 *     │ 也可以再次调用 StpUtil.getLoginIdAsLong() 获取（SA-Token 上下文仍在）
 *     ▼
 *
 * 【为什么要网关注入 X-User-Id？】
 * - 解耦：下游服务不需要知道 Token 的具体实现（Redis / JWT / SA-Token）
 * - 安全：网关统一校验，下游服务信任网关即可
 * - 灵活：下游服务既可以用 X-User-Id（网关注入），也可以用 StpUtil（SA-Token 上下文）
 *
 * ========================================================================
 * 【面试知识点 —— @RestController 与 @ResponseBody】
 * ========================================================================
 *
 * @RestController = @Controller + @ResponseBody
 * - @Controller：将类标记为 Spring MVC 控制器，处理 HTTP 请求
 * - @ResponseBody：所有方法的返回值自动序列化为 JSON（通过 Jackson）
 * - 如果不加 @ResponseBody，返回的字符串会被当成视图名称（JSP/Thymeleaf）
 *
 * ========================================================================
 * 【面试知识点 —— @Valid 参数校验】
 * ========================================================================
 *
 * @Valid 触发 JSR-380（Bean Validation 3.0）校验：
 * - RegisterParam 中的 @NotBlank、@Email、@Size 等注解定义校验规则
 * - 校验失败时抛出 MethodArgumentNotValidException
 * - 由 GlobalExceptionHandler 统一捕获，返回友好的错误信息
 * - Controller 不需要写 if-else 判断参数是否合法
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册
     * 路由：POST /api/auth/register
     *
     * 网关白名单路由，无需登录即可访问。
     * 注册逻辑与认证框架无关——无论用什么 Token 方案，注册都是：校验 → 加密 → 存库。
     *
     * @param param 注册参数，@Valid 触发参数校验（邮箱格式、非空等）
     * @return 新注册用户的 ID
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterParam param) {
        // 业务异常（如邮箱重复）由 GlobalExceptionHandler 统一捕获
        Long userId = authService.register(param);
        return Result.success(userId);
    }

    /**
     * 用户登录
     * 路由：POST /api/auth/login
     *
     * 网关白名单路由，无需登录即可访问。
     *
     * 【返回值说明】
     * 返回 SA-Token 生成的 token 字符串（UUID 格式）。
     * 前端拿到后应存入 localStorage / Vuex / Pinia，
     * 后续所有请求通过 satoken 请求头携带此值：
     *   axios.defaults.headers.common['satoken'] = token;
     *
     * @param param 登录参数（邮箱 + 密码）
     * @return token 字符串
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginParam param) {
        // authService.login() 内部调用 StpUtil.login(userId) 完成登录，
        // 然后返回 StpUtil.getTokenValue() 即 token 字符串
        String token = authService.login(param);
        return Result.success(token);
    }

    /**
     * 获取当前登录用户信息
     * 路由：GET /api/auth/userinfo
     *
     * 【迁移变化】
     * 迁移前签名：public Result<?> userInfo(@RequestHeader("token") String token)
     * 迁移后签名：public Result<?> userInfo()
     *
     * 去掉了 @RequestHeader("token") 参数，因为：
     * - SA-Token 的 Filter 在请求进入时已自动读取 satoken 请求头
     * - token 值已存入当前线程的 TokenContext（ThreadLocal）
     * - Service 层调用 StpUtil.getLoginIdAsLong() 即可获取 userId
     * - 整个链路不需要 Controller 手动传递 token
     *
     * 【未登录时的行为】
     * 如果请求未携带 satoken 请求头，或 token 已过期：
     * - StpUtil.getLoginIdAsLong() 会抛出 NotLoginException
     * - GlobalExceptionHandler 捕获后返回 401 状态码
     * - 所以我们不需要在这里写 if (user == null) 的判断
     *
     * @return 当前登录用户的信息（密码哈希字段已隐藏）
     */
    @GetMapping("/userinfo")
    public Result<?> userInfo() {
        // 不再需要传 token 参数——SA-Token 自动从请求上下文获取
        var user = authService.getCurrentUser();

        // 【MapStruct 实战应用】使用 AuthConvertor 将 Entity 转换为 VO
        // 好处：UserInfoVO 中根本没有 passwordHash 字段，不需要手动置空
        // 对比旧写法 user.setPasswordHash(null)：不修改原始 Entity，更安全
        var vo = AuthConvertor.INSTANCE.toUserInfoVO(user);
        return Result.success(vo);
    }

    /**
     * 用户登出
     * 路由：POST /api/auth/logout
     *
     * 【迁移变化】
     * 迁移前签名：public Result<Void> logout(@RequestHeader("token") String token)
     * 迁移后签名：public Result<Void> logout()
     *
     * 去掉了 @RequestHeader("token") 参数，原因同上。
     * StpUtil.logout() 内部自动从上下文定位 token，并从 Redis 中删除。
     *
     * 【登出后的效果】
     * - Redis 中的 token 记录被立即删除
     * - 后续请求如果还携带这个 token，SA-Token 查 Redis 查不到 → 判定未登录
     * - 前端应清除本地存储的 token，并跳转到登录页
     *
     * @return 操作成功标记
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        // 不再需要传 token 参数——SA-Token 自动处理
        authService.logout();
        return Result.success(null);
    }
}
