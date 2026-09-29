# Day23：微服务边界拆分设计（auth / user / file / gateway）

> 范围声明：本文件只做**设计**，不做代码物理拆分。仓库在 Day23 结束后仍然是单体
> （`com.mengde.clouddrive` 单一 Maven 模块），真正的服务拆分从 Day25/26 才开始，
> 与《CloudDrive_目标架构与34天增量蓝图.md》Day23 行的“禁止：先拆代码再定契约”一致。
> 本文件先列出可审查的契约、数据归属与通信取舍。后续仍需迁移数据、实现内部接口、验证网关信任边界并处理失败补偿；不能把物理拆分理解为简单搬代码。

## 1. 现状证据（来自对当前单体代码的直接检查，非臆测）

1. `auth` 包下的 `UserAccountMapper` 同时承担两类完全不同的职责：
   - 身份职责：`insert`（注册写入 `user_account`）、`findByUsername`（登录校验）
   - 业务/配额职责：`lockQuotaById`（`SELECT quota_bytes FROM user_account WHERE id=#{id} FOR UPDATE`，
     供分片上传完成前的配额检查使用；当前没有持久化预占记录）
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
- **对外契约（HTTP，保留现有路径和字段名）**：
  - `POST /api/auth/register` — 请求 `{username, password}` → 201 `{id, username, role}`。
  - `POST /api/auth/login` — 请求 `{username, password}` → 200 `{accessToken, expiresAt}`；`expiresAt` 是时间点，不是 `expiresIn` 秒数。
  - `GET /api/auth/me` — 请求头 `Authorization: Bearer <jwt>` → 200 `{id, username, roles}`；`roles` 是列表。
- **对内契约（拆分后，供 user-service / file-service 消费，不经过 Gateway）**：
  - `GET /internal/users/{id}/identity` → `{id, username, role}`，用于其它服务需要校验
    "这个 userId 是否真实存在" 时使用，避免各服务各自查一份身份表。
- **禁止**：auth-service 不得直接读写 `quota_bytes`；登录不处理配额，注册只负责身份创建并触发 user-service 的 profile 初始化契约。

注册后还需为新身份创建 user-service 的 `user_profile`。这是拆库时必须补齐的跨服务生命周期契约：至少要定义按 `user_id` 幂等初始化、初始化失败的重试/补偿，以及在 profile 尚未建立时上传如何返回。Day23 只记录此缺口，不能把现有数据库默认 `quota_bytes` 列当作未来服务已自动完成的初始化。

### 2.2 user-service（用户业务与配额）

- **归属数据**：新表 `user_profile`（拆分时从 `user_account` 迁出）——
  `user_id`（=auth 的 id，逻辑外键，不跨库物理 FK）、`quota_bytes`、`used_bytes`、`updated_at`。
- **对内契约**：
  - `POST /internal/users/{id}/quota/reserve` — 请求 `{reservationId, amount}` → 响应 `{reservationId}` 或 409（配额不足）；file-service 在首次请求前生成该 ID，重试复用。
  - `POST /internal/users/{id}/quota/commit` — 请求 `{reservationId}` → 响应 200
  - `POST /internal/users/{id}/quota/release` — 请求 `{reservationId}` → 响应 200
  这三个接口是未来拆分方案，并非 Day23 已实现的 HTTP 接口。当前上传流程仅调用
  `lockQuotaById` 做配额检查；拆分后需新增持久化预占记录、状态转换和超时回收，才能兑现 reserve/commit/release（见 3.1 节）。
- **禁止**：user-service 不得处理密码、JWT；不得直接暴露 `/api/auth/**` 路径。

### 2.3 file-service（文件与目录）

- **归属数据**：`drive_entries`、`upload_sessions`、`upload_chunks`、`file_shares`。
- **对外契约**：保持现有 `/api/entries/**`、`/api/files/**`、`/api/upload-sessions/**`、
  `/api/shares/**` 不变。
- **对内依赖**：
  - 上传完成前调用 user-service 的 `quota reserve`；上传成功调用 `commit`；上传失败/取消调用 `release`。
  - 未来如增加回收站保留期与后台硬删除，可向 MQ 发布"文件已物理删除"事件（见 3.2 节）。Day23 没有定时清理或 MQ；现有 `DELETE /api/entries/trash` 是用户主动触发的硬删除。
  - 若删除后必须返还用户可用配额，配额回补属于核心状态变更，须另行定义可靠投递、幂等消费和对账；不能仅凭尽力而为的通知事件宣称配额一致。
- **禁止**：file-service 不得直接写 `user_account`/`user_profile` 表。

### 2.4 gateway（统一入口）

- **职责**：路由转发 + **JWT 签名校验**。当前实现用 HS256 对称密钥，没有公钥；若未来网关沿用 HS256，验签者同时拥有签发能力，密钥分发是待解决的风险。校验通过后可向下游服务转发 `X-User-Id`、`X-User-Role`，但须先剥离客户端同名头，并保证服务直连入口受限、网关来路可验证；仅靠"内网互信"不足以证明身份可信。Day23 尚未实现该链路。
- **不做**：不做业务级鉴权（比如"这个文件是否属于当前用户"），业务级鉴权仍下沉到 file-service
  自己做（因为只有 file-service 知道 `drive_entries.owner_id`）。
- **路由表（沿用现有 SecurityConfiguration 的 permitAll/authenticated 划分）**：
  - 直通不验签：`/api/auth/register`、`/api/auth/login`、`/api/shares/*/download`
  - 验签后转发：`/api/auth/me` → auth-service；`/api/entries/**`、`/api/files/**`、
    `/api/upload-sessions/**`、`/api/shares` → file-service
  - 当前 `POST /api/snapshots` 未被上述 `authenticated()` 匹配器覆盖，落入 `anyRequest().permitAll()`；拆分前须明确其预期鉴权规则，不能把它默认为已受网关保护。

## 3. MQ 用/不用决策矩阵（三类业务场景，含未来方案）

### 3.1 配额 reserve/commit/release —— 结论：**不用 MQ，同步 HTTP 调用**

- **原因**：配额校验要在上传请求路径上返回确定性结果（成功/配额不足），同步 HTTP 更直接。MQ 也能实现请求应答，但需要相关 ID、超时和重复响应处理。reserve→commit/release 是跨服务状态机；同步调用加超时重试并不能单独保证一致性，仍需预占过期、失败补偿和对账。
- **幂等**：file-service 在首次 reserve 前生成 `reservationId`（UUID），首次与重试请求均携带它。user-service 以唯一索引持久化预占记录；重复 reserve 返回原记录，重复 commit/release 返回原终态，不重复扣减/释放。`COMMITTED` 后的 release 或 `RELEASED` 后的 commit 必须拒绝状态冲突，不能一律返回成功。
- **重试**：file-service 对 user-service 的调用设置超时（如 2s）+ 最多 2 次重试
  （指数退避），重试沿用同一个 `reservationId`，因幂等设计不会重复生效。
- **顺序**：单个 `reservationId` 的 reserve→commit（或 reserve→release）有严格先后依赖，
  由调用方（file-service）在业务逻辑层保证顺序发起，不依赖消息队列的顺序保证。
- **死信**：不适用（同步调用失败即返回给上传请求本身，由用户/前端决定是否重试上传）。

### 3.2 回收站硬删除后的“文件已物理删除”事件 —— 结论：**用 MQ，带死信队列**

- **原因**：这是未来新增的后台硬删除通知方案，并非 Day23 已运行的流程。若由定时批处理触发，就不在用户同步请求路径上；当前 `DELETE /api/entries/trash` 仍是同步 HTTP。下游（例如未来的统计服务）消费通知可接受最终一致，
  用 MQ 解耦可以让 file-service 不必等待下游处理完成，也不因下游服务不可用而阻塞删除本身。
- **幂等**：消息体携带持久化的 `deletionEventId`（或 `entryId` + 删除版本）作为稳定幂等键，消费者用去重表或唯一索引避免重复处理。重发时不能重新取当前时间作为键，否则同一次删除会变成两次不同事件。
- **重试**：消费失败自动重试 3 次（指数退避），业务处理必须设计为可重复执行（幂等）。
- **顺序**：同一删除版本只有一条逻辑事件，但恢复后再次删除会产生新版本；消费者须处理同一条目的版本乱序。不同 `entryId` 通常无顺序依赖，可并行消费。
- **死信**：重试 3 次仍失败的消息进入死信队列（DLQ），由人工/定时任务巡检 DLQ 并告警，
  防止"消费一直失败又没人知道"的静默丢失。

### 3.3 登录活动遥测（登录成功/失败事件） —— 结论：**可用 MQ，尽力而为**

- **原因**：这里讨论的是允许少量丢失的登录活动遥测，不是合规或安全取证要求的正式审计日志。若产品要求可追溯的审计，不可采用下述丢弃策略，须另设可靠持久化与补偿链路。
- **幂等**：登录活动遥测允许重复记录，不强制去重；若改为正式审计，须另行定义事件身份与可靠性要求。
- **重试**：不做业务重试；发送失败仅记录本地 WARN 日志，不阻塞登录响应返回给用户。
- **顺序**：不保证传输顺序；展示活动记录时可按事件时间排序，但这不能补回丢失的事件。
- **死信**：本尽力而为方案不设置 DLQ；发送失败记录本地失败计数。正式安全审计不可直接沿用这条丢弃策略。

## 4. 拆分后跨服务调用总表

| 调用方 | 被调方 | 方式 | 幂等键 | 失败处理 |
|---|---|---|---|---|
| file-service | user-service | 同步 HTTP（reserve/commit/release） | reservationId | 超时+重试2次，最终失败返回给上传请求 |
| file-service | MQ（未来后台删除通知） | 异步 MQ | 稳定的 deletionEventId/删除版本 | 重试3次→DLQ+告警；投递可靠性另行设计 |
| auth-service | MQ（登录活动遥测） | 异步 MQ，尽力而为 | 无 | 失败即丢弃，仅记本地日志；不用于正式审计 |
| gateway | auth-service/file-service | 同步 HTTP 转发 | 不适用 | 网关侧超时返回 502/504 |

## 5. 本设计与 Day23 唯一增量代码的对应关系

本设计文档不引入 Java 业务代码。Day23 最初提交的代码和配置增量是：

1. `deploy/docker-compose.yml` + `deploy/.env.example`：冻结一个可复现的 MySQL/Redis
   运行环境，允许从环境变量配置非个人凭据（`CLOUDDRIVE_DB_PASSWORD` 等），为后续改造提供基础设施起点。Compose 的 `--env-file` 不会自动把变量导出给另行启动的 Maven/Spring 进程；两边须分别配置。
2. 修复 11 个测试文件里硬编码的个人数据库凭据（`root`/`123456`），改为读取与
   `application.properties` 一致的环境变量（`CLOUDDRIVE_DB_URL/USERNAME/PASSWORD`），
   这是"可由非个人凭据配置启动"的配置入口，不涉及业务逻辑变更；默认值仍是旧环境值，非个人账号路径须实测。

后续 `ec1c747` 又修复七个合同测试类在持久化 MySQL 上的外键清理顺序，历史全量回归由首次失败转为 90 项全绿、验收脚本 12/12 PASS。上述工程验收不等于模板已用一套非个人账号完成 Compose 启动实测，也不等于任何未来服务或 MQ 方案已落地。
