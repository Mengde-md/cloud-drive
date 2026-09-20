# Day23：微服务边界拆分设计（auth / user / file / gateway）

> 范围声明：本文件只做**设计**，不做代码物理拆分。仓库在 Day23 结束后仍然是单体
> （`com.mengde.clouddrive` 单一 Maven 模块），真正的服务拆分从 Day25/26 才开始，
> 与《CloudDrive_目标架构与34天增量蓝图.md》Day23 行的“禁止：先拆代码再定契约”一致。
> 本文件的作用是：先把契约、数据归属、MQ 取舍在纸面上钉死，后面拆分时只是“搬代码”，
> 不再有“边设计边重构”的返工风险。

## 1. 现状证据（来自对当前单体代码的直接检查，非臆测）

1. `auth` 包下的 `UserAccountMapper` 同时承担两类完全不同的职责：
   - 身份职责：`insert`（注册写入 `user_account`）、`findByUsername`（登录校验）
   - 业务/配额职责：`lockQuotaById`（`SELECT quota_bytes FROM user_account WHERE id=#{id} FOR UPDATE`，
     供文件上传前的配额预占使用）
   这说明当前 `user_account` 表把“我是谁”（身份）和“我还有多少配额”（业务状态）耦合在同一张表、
   同一个 Mapper 里，是后续拆分 auth-service / user-service 必须先解决的边界债务。
2. `SecurityConfiguration` 中的 JWT 校验目前发生在应用内部的 filter chain 里，
   permitAll 路径为 `/api/auth/register`、`/api/auth/login`、`/api/shares/*/download`，
   其余路径（`/api/auth/me`、`/api/entries/**`、`/api/files/**`、`/api/upload-sessions/**`、
   `/api/shares`）都要求已认证。这决定了拆分后 Gateway 的鉴权职责边界（见第 3 节）。
3. 当前没有任何 MQ 依赖（`pom.xml` 中无 RabbitMQ/RocketMQ/Kafka starter），
   所有跨模块调用都是同进程内的方法调用（Spring Bean 直接注入）。Day23 要做的是把这些
   “同进程调用”逐一归类为“拆分后要继续同步调用”还是“拆分后应该走 MQ 异步”。

## 2. 四个服务的数据归属与契约

### 2.1 auth-service（身份认证）

- **归属数据**：`user_account` 表中的身份子集——`id`、`username`、`password_hash`、`role`、`created_at`。
- **不归属**：`quota_bytes`（归 user-service）。
- **对外契约（HTTP，保持现有路径不变，拆分时零迁移成本）**：
  - `POST /api/auth/register` — 请求 `{username, password}` → 响应 `{id, username, role}`
  - `POST /api/auth/login` — 请求 `{username, password}` → 响应 `{token, expiresIn}`
  - `GET /api/auth/me` — 请求头 `Authorization: Bearer <jwt>` → 响应 `{id, username, role}`
- **对内契约（拆分后，供 user-service / file-service 消费，不经过 Gateway）**：
  - `GET /internal/users/{id}/identity` → `{id, username, role}`，用于其它服务需要校验
    "这个 userId 是否真实存在" 时使用，避免各服务各自查一份身份表。
- **禁止**：auth-service 不得直接读写 `quota_bytes`；登录/注册流程不做任何配额相关操作。

### 2.2 user-service（用户业务与配额）

- **归属数据**：新表 `user_profile`（拆分时从 `user_account` 迁出）——
  `user_id`（=auth 的 id，逻辑外键，不跨库物理 FK）、`quota_bytes`、`used_bytes`、`updated_at`。
- **对内契约**：
  - `POST /internal/users/{id}/quota/reserve` — 请求 `{amount}` → 响应 `{reservationId}` 或 409（配额不足）
  - `POST /internal/users/{id}/quota/commit` — 请求 `{reservationId}` → 响应 200
  - `POST /internal/users/{id}/quota/release` — 请求 `{reservationId}` → 响应 200
  这三个接口对应现有 `lockQuotaById` 里"先锁行再判断"的逻辑，拆分后用
  reserve/commit/release 三段式代替跨服务的行锁（见 3.1 节，同步调用而非 MQ）。
- **禁止**：user-service 不得处理密码、JWT；不得直接暴露 `/api/auth/**` 路径。

### 2.3 file-service（文件与目录）

- **归属数据**：`drive_entries`、`upload_sessions`、`upload_chunks`、`file_shares`。
- **对外契约**：保持现有 `/api/entries/**`、`/api/files/**`、`/api/upload-sessions/**`、
  `/api/shares/**` 不变。
- **对内依赖**：
  - 上传完成前调用 user-service 的 `quota reserve`；上传成功调用 `commit`；上传失败/取消调用 `release`。
  - 回收站硬删除（超过保留期）后，向 MQ 发布"文件已物理删除"事件（见 3.2 节），
    不再直接同步调用 user-service 释放配额，避免文件服务被下游服务的可用性拖累。
- **禁止**：file-service 不得直接写 `user_account`/`user_profile` 表。

### 2.4 gateway（统一入口）

- **职责**：路由转发 + **JWT 签名校验**（复用 auth-service 签发时用的同一份公钥/密钥，
  验证 token 合法性与过期时间），校验通过后向下游服务转发请求头 `X-User-Id`、`X-User-Role`，
  下游服务信任网关传入的这两个头，不再重复验签（网关成为唯一验签点，服务内部是"内网互信"）。
- **不做**：不做业务级鉴权（比如"这个文件是否属于当前用户"），业务级鉴权仍下沉到 file-service
  自己做（因为只有 file-service 知道 `drive_entries.owner_id`）。
- **路由表（沿用现有 SecurityConfiguration 的 permitAll/authenticated 划分）**：
  - 直通不验签：`/api/auth/register`、`/api/auth/login`、`/api/shares/*/download`
  - 验签后转发：`/api/auth/me` → auth-service；`/api/entries/**`、`/api/files/**`、
    `/api/upload-sessions/**`、`/api/shares` → file-service

## 3. MQ 用/不用决策矩阵（三个真实流程）

### 3.1 配额 reserve/commit/release —— 结论：**不用 MQ，同步 HTTP 调用**

- **原因**：配额校验必须在“用户点击上传”这一同步请求路径上给出确定性结果
  （成功/配额不足），不能异步回调；且 reserve→commit/release 是短事务，跨服务同步调用
  加上超时+重试即可满足一致性要求，引入 MQ 反而增加"配额到底扣没扣"的中间不确定态。
- **幂等**：`reservationId` 由 file-service 生成（UUID），user-service 对同一
  `reservationId` 的重复 `commit`/`release` 请求直接返回幂等结果（不重复扣减/释放），
  依赖 user-service 侧对 `reservationId` 建唯一索引。
- **重试**：file-service 对 user-service 的调用设置超时（如 2s）+ 最多 2 次重试
  （指数退避），重试沿用同一个 `reservationId`，因幂等设计不会重复生效。
- **顺序**：单个 `reservationId` 的 reserve→commit（或 reserve→release）有严格先后依赖，
  由调用方（file-service）在业务逻辑层保证顺序发起，不依赖消息队列的顺序保证。
- **死信**：不适用（同步调用失败即返回给上传请求本身，由用户/前端决定是否重试上传）。

### 3.2 回收站硬删除后的“文件已物理删除”事件 —— 结论：**用 MQ，带死信队列**

- **原因**：硬删除是定时批处理触发的后台流程，不在用户同步请求路径上，
  下游（例如未来的审计服务、统计服务）消费这个事件是"最终一致"即可，
  用 MQ 解耦可以让 file-service 不必等待下游处理完成，也不因下游服务不可用而阻塞删除本身。
- **幂等**：消息体携带 `entryId` + `deletedAt`（物理删除时间戳）作为幂等键，
  消费者用 `(entryId, deletedAt)` 做去重表或唯一索引，重复消费不重复处理。
- **重试**：消费失败自动重试 3 次（指数退避），业务处理必须设计为可重复执行（幂等）。
- **顺序**：同一 `entryId` 的删除事件只会产生一次，不存在同一实体的多条事件需要保序的问题；
  跨不同 `entryId` 的事件之间没有顺序依赖，因此不需要单队列强顺序，可以并行消费提高吞吐。
- **死信**：重试 3 次仍失败的消息进入死信队列（DLQ），由人工/定时任务巡检 DLQ 并告警，
  防止"消费一直失败又没人知道"的静默丢失。

### 3.3 认证审计日志（登录成功/失败事件） —— 结论：**用 MQ，fire-and-forget，不设死信重试**

- **原因**：审计日志是旁路可观测性数据，不影响登录主流程的成功与否；
  即使日志偶尔丢失也不影响核心业务正确性，因此不需要 file-service/user-service 那种
  强保证，追求"尽力而为、不拖慢主流程"。
- **幂等**：不强制要求（审计日志允许重复记录，下游按时间戳去重是可选优化，不是必须）。
- **重试**：不做业务重试；发送失败仅记录本地 WARN 日志，不阻塞登录响应返回给用户。
- **顺序**：不保证顺序（审计查询按时间戳排序展示，不依赖消息队列顺序）。
- **死信**：不设置 DLQ；发送失败直接丢弃并计入本地失败计数指标，成本收益比不值得为审计日志
  建设死信补偿链路。

## 4. 拆分后跨服务调用总表

| 调用方 | 被调方 | 方式 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| file-service | user-service | 同步 HTTP（reserve/commit/release） | reservationId | 超时+重试2次，最终失败返回给上传请求 |
| file-service | MQ（删除事件） | 异步 MQ | entryId+deletedAt | 重试3次→DLQ+告警 |
| auth-service | MQ（审计事件） | 异步 MQ，fire-and-forget | 无 | 失败即丢弃，仅记本地日志 |
| gateway | auth-service/file-service | 同步 HTTP 转发 | 不适用 | 网关侧超时返回 502/504 |

## 5. 本设计与 Day23 唯一增量代码的对应关系

本设计文档不引入任何新的 Java 业务代码（避免"先拆代码再定契约”）。Day23 的实际代码增量是：

1. `deploy/docker-compose.yml` + `deploy/.env.example`：冻结一个可复现的 MySQL/Redis
   运行环境，使用非个人凭据的环境变量配置（`CLOUDDRIVE_DB_PASSWORD` 等），
   为 Day24 起的微服务化改造提供统一、可重复的基础设施起点。
2. 修复 11 个测试文件里硬编码的个人数据库凭据（`root`/`123456`），改为读取与
   `application.properties` 一致的环境变量（`CLOUDDRIVE_DB_URL/USERNAME/PASSWORD`），
   这是"非个人凭据配置启动"这一 Day23 硬性环境门的直接落地，不涉及业务逻辑变更。

以上两项都是 Day23 路线要求的“冻结 Docker 或等价运行环境，并让 MySQL/Redis 等依赖可由非个人
凭据的配置启动”，而不是服务代码物理拆分本身。
