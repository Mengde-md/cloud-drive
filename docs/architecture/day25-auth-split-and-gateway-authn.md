# Day25 auth-service 拆分、网关鉴权与身份透传

本文件是 Day25 的设计依据，与 `docs/day23-service-boundaries.md`（边界）和
`docs/day24-gateway-and-config.md`（入口与配置）构成阶段二前三天的完整设计链。
凡是"本日没做"的部分，本文一律写明没做以及为什么，不用将来时掩盖现状。

## 1. 起点：Day24 结束时的真实状态

Day24 交付了网关进程与六条路由，但四条业务路由的 `uri` 解析到同一个地址
（`http://127.0.0.1:8080`）。也就是说：**边界只存在于路由 id 的命名里，进程只有一个。**
网关此时是一个纯转发器，它没有做任何鉴权——`SecurityConfiguration` 里的 JWT 校验
仍然发生在单体的 filter chain 里，每一个未来的服务都要各自实现一遍。

这留下三个必须在本日处理的问题：

1. **拆分从未被验证过。** 路由按未来服务命名是一种承诺，但没有任何一条路由真的
   指向过第二个进程。"改一行 uri 就能拆"这句话在 Day24 只是推断。
2. **鉴权没有统一落点。** 今天只有单体一个进程，所以"每个服务各写一份验签"
   还看不出代价。一旦出现第二个进程，这份代价立刻变成两份实现、两套口径。
3. **身份在服务间无法传递。** 下游服务只认 `Authorization` 头，意味着每个服务都得
   持有签名密钥、都得会解 JWT。这与"网关是唯一验签点"的目标相反。

## 2. 本日唯一增量

| 增量 | 位置 |
|---|---|
| auth-service 独立 Maven 工程与进程（8081） | `auth-service/` |
| 网关边缘验签 + 身份透传 + 身份头防伪造 | `gateway/.../AuthenticationGlobalFilter.java` |
| `auth-service-route` 指向新进程，与 user/file 分道 | `gateway/src/main/resources/application.yml` |
| 单体"信任网关身份头"模式（默认关闭） | `src/main/java/com/mengde/clouddrive/auth/TrustedGatewayHeaderAuthenticationFilter.java` |
| 权限回归测试三组 | auth-service / gateway / 单体各一组 |

**不做**：不新建 user-service / file-service（Day26）；不引入 Feign 与任何服务间调用
（Day26）；不做熔断、灰度、重试；不迁移 `user_account` 的物理分库；不把三个 Java 进程
容器化；不做多模块聚合重构。

## 3. 三个进程的职责切分

```
                 ┌──────────────────────────────────────────┐
  客户端 ──────► │ gateway :9000                            │
                 │  1. 剥离客户端自带的 X-User-*（无条件）    │
                 │  2. 白名单路径直接放行                     │
                 │  3. 其余路径验签，失败即 401（不转发）      │
                 │  4. 验签通过注入 X-User-Id/-Name/-Role     │
                 └───────┬─────────────────────┬────────────┘
                         │ /api/auth/**        │ 其余
                         ▼                     ▼
           ┌─────────────────────┐   ┌─────────────────────────┐
           │ auth-service :8081  │   │ 单体 :8080               │
           │  注册/登录/me        │   │  entries/files/uploads  │
           │  /internal/** (不经  │   │  本地 JWT 验签（默认）    │
           │   网关，网关无路由)   │   │  信任网关头（开关，默认关）│
           └──────────┬──────────┘   └───────────┬─────────────┘
                      │                          │
                      └────────► MySQL ◄─────────┘
                        （今天仍是同一个库、同一张 user_account）
```

## 4. 鉴权链路的三步顺序，以及为什么顺序不能换

`AuthenticationGlobalFilter` 里三件事的顺序是本日安全性的全部依据：

**第一步，无条件剥离 `X-User-Id` / `X-User-Name` / `X-User-Role`。**
不受 `authn.enabled` 开关影响，也不受白名单影响。

如果把剥离放在白名单判断之后，那么每一条匿名路径都变成伪造身份的通道：攻击者对
`/api/auth/login` 发一个带 `X-User-Id: 1` 的请求，这个头会原样到达下游；下游若开启了
信任模式，就会把它当成用户 1 的已认证请求。**匿名的含义是"不需要证明身份"，
不是"可以自称任意身份"。** 测试 `forgedIdentityHeadersAreStrippedEvenOnAnonymousPaths`
专门覆盖这个顺序。

同理，剥离也必须用"先删再写"而不是 `add`：`add` 会让请求头变成 `["1", "42"]`，
下游 `getFirst` 读到的是伪造的那个。测试 `forgedIdentityHeadersAreReplacedNotAppended`
覆盖这一点。

**第二步，白名单判断。** 清单直接抄自单体 `SecurityConfiguration` 的 `permitAll` 集合。
拆分中最常见的事故不是漏放行（会立刻被用户发现），而是多放行（悄无声息）。
所以测试里既有"这三条必须在"，也有"这六条一条都不许在"的反向断言。

**第三步，验签失败即 401，不转发。** 这是统一入口的实际收益：坏请求在边缘被挡住，
不会打到每一个下游服务上。验签时同时校验 issuer，不只校验签名——只验签名意味着
任何一个恰好复用了同一把密钥的系统签出的 token 都会被接受。

过滤器 `getOrder()` 返回 `-100`，必须小于 `NettyRoutingFilter` 的
`Ordered.LOWEST_PRECEDENCE`，否则请求已经转发出去了再改请求头毫无意义。

## 5. 身份透传契约

| 头 | 含义 | 来源 |
|---|---|---|
| `X-User-Id` | 用户 id 的十进制字符串 | JWT 的 `sub` |
| `X-User-Name` | 用户名 | JWT 的 `username` claim |
| `X-User-Role` | 角色，多个以英文逗号分隔 | JWT 的 `roles` claim |

前两列的命名来自 Day23 第 2.4 节写定的 `X-User-Id` / `X-User-Role`。
`X-User-Name` 是本日的**扩展**：单体的 `AuthenticatedUser` 有 `username` 字段，
不透传它，下游就无法还原出与本地验签完全等价的身份，"信任网关头"模式会缺一块。
扩展理由记录在此，不当作 Day23 文档已经写过的东西。

三个头名集中定义在 `GatewayIdentityHeaders.ALL` 一处，防伪造逻辑遍历这个列表。
新增身份头只改一处，分散定义迟早漏掉一个。

## 6. 下游信任模型：为什么默认关闭

Day23 第 2.4 节原文是"下游服务信任网关传入的这两个头，不再重复验签"。
**本日没有照搬这一条**，理由是那句话隐含了一个今天并不成立的前提：网关是唯一入口。

今天的真实情况是单体的 8080、auth-service 的 8081 在本机都直接可达。在这个前提下
默认信任 `X-User-Id`，等于任何能连上 8080 的人都可以自称任意用户。所以：

- `clouddrive.gateway.trusted-headers.enabled` **默认 false**。关闭时
  `TrustedGatewayHeaderAuthenticationFilter` 第一行即放行，整条链的可观测行为
  与 Day24 完全一致，既有回归全部仍走本地验签路径。
- 开启是**部署决策**，前提是网络边界（只暴露网关端口），不是代码决策。
- 即使开启，过滤器也排在本地验签**之后**，且只在尚未认证时才填充。
  自己验出来的身份比别人给的更可信；反过来写意味着一个能注入头的中间环节
  可以顶替任何已登录用户。
- 头不完整或 `X-User-Id` 不是数字时当匿名处理，**绝不用默认值兜底**。
  "解析失败就当 userId=0"是越权的经典入口。

auth-service 自己也仍然对 `/api/auth/me` 验签，理由相同：把"内网互信"当成
"内网无需认证"，前提是先有内网边界。网关的验签是**前置快速失败**，不是替代。

## 7. 本日已知局限与契约状态

1. **两份 auth 代码并存。** 单体的 `com.mengde.clouddrive.auth` 与 auth-service 的
   `com.mengde.clouddrive.authservice` 是两套独立实现。单体那份仍然服务于 95 项既有回归
   （其中认证集成测试通过本地 `/api/auth/login` 拿 token）。删掉它需要调整这些测试的认证准备，
   那是 Feign 的范围，属于 Day26。**代价**：改 JWT claim 结构必须同时改两处，
   忘记一处的表现是"登录成功但后续请求 401"。`JwtMonolithCompatibilityTest`
   把 claim 结构当契约断言，就是为了让这种遗漏立刻变红。
2. **三个进程共享同一把对称密钥。** HS256 的固有代价是"能验就能签"，
   网关理论上可以伪造任意 token。真正的最小权限要换成 RS256/ES256，网关只持公钥。
   本日不做，因为换算法会同时改动三个进程的签发与验签路径，超出当日增量。
3. **`user_account` 未真正拆分。** auth-service 与单体读同一个库同一张表。
   更严重的是单体的 `DocumentUploadApplicationService`、`OwnedDriveEntryApplicationService`、
   `UploadSessionApplicationService` 三处都在直接调 `lockQuotaById` 读 `quota_bytes`——
   按 Day23 的归属判定这是 user-service 的数据。这是一处**真实存在的跨边界读**，
   本日只记录不修复，它的解法（reserve/commit/release 三段式）已在 Day23 第 3.1 节设计好。
4. **`/internal/**` 仍是共享静态令牌模型。** 路径不在网关路由表中，且请求还必须携带
   `X-CloudDrive-Internal-Token`，其值来自独立的 `CLOUDDRIVE_INTERNAL_SERVICE_TOKEN`；
   auth-service 默认仅绑定 `127.0.0.1`。这消除了匿名直连，但还没有令牌轮换、密钥托管或 mTLS，
   Day26 的 Feign 调用必须复用该契约并补齐轮换策略。
5. **三个 Java 进程未容器化。** `deploy/docker-compose.yml` 仍只编排中间件。
   因此"只暴露网关端口"这个真实形态在本日**无法真正搭出来**，信任开关的开启只能做对照实验，
   不能当作安全结论。
6. **仓库有三个互不聚合的 Maven 工程。** 全量验证需要三条命令。
   `gateway/pom.xml` 的注释曾写"真正的多模块重构属于 Day25"，本日**没有**做，
   理由写在 `auth-service/pom.xml` 的头部注释里（搬迁 `src/` 会淹没本日增量，
   并改变受保护脚本 `verify_day.ps1` 第 6 项的语义）。这条债务留给 Day26 一并评估。
7. **接口契约需持续防漂移。** Day23 文档中的旧字段已校正；实际契约为
   `{accessToken, expiresAt}` 与 `{id, username, roles[]}`。
   `AuthServiceHttpContractTest` 保留两条 `doesNotExist` 断言，防止旧字段重新出现。

## 8. 2026-10-02 自审修复

auth-service 只在 JWT 验证阶段捕获认证异常，下游异常原样交给框架处理；两个安全过滤器
禁用 Servlet 自动注册，避免在安全链之外重复执行。网关的错误恢复同样位于下游转发之前。
内部令牌只能用于 `/internal/**`，用户 JWT 不能反向替代内部令牌，测试覆盖请求间上下文隔离。

auth-service 默认监听与 Nacos 上报地址均为 `127.0.0.1`。跨主机时同时配置
`CLOUDDRIVE_AUTH_BIND_ADDRESS` 与 `CLOUDDRIVE_AUTH_ADVERTISED_IP`，后者为可路由的
具体地址，不能填 `0.0.0.0`。配置回归不等于真实注册验证；本轮 Nacos 受 Docker 启动失败阻塞。
