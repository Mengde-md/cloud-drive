# Day15 Final：注册登录、JWT 与 Spring Security 过滤链

## 当日范围与禁止项

- **唯一输入**：Day14 的缓存身份边界、Day12 HTTP/MySQL 链和 Day13 Spring 装配知识。
- **唯一增量**：账号迁移、BCrypt、JWT、Spring Security 6 过滤链、`/api/auth/register`、`/login`、受保护的 `/me` 与认证合同测试。
- **禁止提前**：不做 Day16 目录用户隔离、配额或重名约束；不做 refresh token、黑名单、OAuth、RBAC 管理后台、真实 metadata 缓存或上传。

## Checkpoint 0：实际起点

起点为 `48660b9` 的干净 Day14 基线，80 tests 通过；不存在用户表、认证服务、Security 依赖或 Day15 Final。

## Checkpoint 1：唯一增量

Flyway V2 增加 `user_account` 与 username 唯一约束。`AuthService` 用 BCrypt encode/matches；`JwtTokenService` 签发含 subject、username、roles、iat、exp 的 HMAC JWT；生产密钥只从 `CLOUDDRIVE_JWT_SECRET` 获取且至少 32 bytes。`SecurityFilterChain` 无状态运行，放入 JWT filter，公开注册/登录，保护 `/api/auth/me`。统一 JSON 401/403 由 Security entry point/denied handler 输出。

## Checkpoint 2：Debug 与边界

修复了 MyBatis mapper 扫描范围、`password_hash` 到 `passwordHash` 的显式映射，以及错误密码 HTTP 测试受 JDK Basic-auth 重试机制干扰的问题。后者通过禁用 HTTP Basic/form login、保留 JWT 401 HTTP 合同并用服务层验证密码匹配解决。未把这一最小认证证据夸大成目录隔离或 token 注销能力。

## Final 验收记录

- `mvn clean test`：**83 tests / 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**。
- `AuthHttpContractTest`：注册→登录→Bearer `/me`、缺失/篡改 token 401、重复 username 409；错误密码验证为服务层 `InvalidCredentialsException`。
- 教材自审（Day02/Day05）：从 Day14 的真实问题进入，按问题→责任边界→项目源码走读→项目测试走读→Debug→复刻→面试展开；重新读取 `AuthService`、`JwtTokenService`、`SecurityConfiguration`、`AuthController`、`UserAccountMapper` 及 `AuthHttpContractTest`。不把 JWT Base64 误称加密，不把 token 无状态误称无需数据库。

## 独立复刻任务

在临时项目写 `PasswordEncoder` 的正确/错误密码测试；手画 JWT filter 的三条路径（无 Header、有效 Header、篡改 Header）；解释为什么 Day16 前 `/api/entries` 还不能宣称完成用户隔离。

## 面试题与追问

1. BCrypt 为什么不能两次 encode 后直接比较？
2. JWT 的签名与加密差异是什么？追问：payload 能否放密码？
3. 401 与 403 如何区分？
4. 为什么认证逻辑应在 filter chain，而不是每个 Controller？
5. token 无状态为什么仍可能需要数据库？
6. 当前认证能力还缺哪些上线护栏？

## 明日唯一输入

输入是经 SecurityContext 传播的 `AuthenticatedUser`。Day16 才由此派生 ownerId，落实用户隔离、目录树、配额和数据库约束。
