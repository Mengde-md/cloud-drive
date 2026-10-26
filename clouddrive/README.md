# 网络云盘存储系统（CloudDrive · 单体版）

Java 后端课程设计项目，对应 Day01–Day23 的累积代码。
系统提供多用户隔离的云盘后端：账号认证、目录与文件管理、上传（含秒传与分片断点续传）、
下载（支持 HTTP Range）、提取码分享、回收站，以及缓存、分布式锁、限流与基础可观测性。

---

## 1. 技术栈

| 层次 | 选型 |
|---|---|
| 语言与运行时 | Java 17 |
| 框架 | Spring Boot 3.2、Spring MVC、Spring Security 6 |
| 认证 | JWT（Nimbus JOSE，HS256） |
| 持久化 | MyBatis 3、MySQL 8、Flyway 迁移 |
| 缓存与并发 | Redis（Cache Aside）、Redisson（分布式锁、限流） |
| 存储 | 本地磁盘（通过 `ObjectStorage` 抽象，可替换） |
| 可观测 | Spring Boot Actuator（health / metrics）、结构化日志 + traceId |
| 测试 | JUnit 5、Mockito、Spring Boot Test |

**不包含**：微服务拆分（Nacos / Gateway / OpenFeign）、Elasticsearch 检索、AI 能力。
Day23 只产出了拆分**设计文档**（见 `docs/Day23_微服务边界拆分设计.md`），没有对应实现代码。

---

## 2. 快速开始

### 2.1 启动依赖

```bash
cp .env.example .env          # 修改其中的口令
docker compose up -d
docker compose ps             # 等 mysql 与 redis 都变成 healthy
```

### 2.2 启动应用

```bash
mvn spring-boot:run
```

首次启动时 Flyway 会自动执行 `src/main/resources/db/migration/V1~V10`，建出全部表结构；已有数据库只执行新增迁移。

邮箱注册、管理员初始化、DeepSeek Key 与辅助审核配置见 [新增功能配置说明](../deploy/新增功能配置说明.md)。
服务默认监听 `http://localhost:8080`。

### 2.3 跑测试

```bash
mvn test                        # 只跑不依赖 MySQL/Redis 的单元测试
mvn test -DexcludedGroups=      # 连同 @Tag("integration") 的集成测试一起跑（需要依赖已启动）
```

---

## 3. 接口一览

所有接口统一前缀 `/api`。除注册、登录、发送邮箱验证码、邮箱重置密码、凭提取码的分享访问外，其余接口都需要
`Authorization: Bearer <token>`。所有非 2xx 响应体结构一致：`{status, error, message}`。

### 3.1 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 邮箱验证码注册，返回 201 |
| POST | `/api/auth/email-code` | 发送注册/找回密码验证码 |
| POST | `/api/auth/reset-password` | 凭邮箱验证码重置密码 |
| POST | `/api/auth/login` | 登录，返回 accessToken 与过期时间 |
| GET | `/api/auth/me` | 当前身份 + 配额使用情况 |

### 3.2 目录与文件元数据

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/entries` | 当前用户的全部条目（走 Redis 缓存） |
| GET | `/api/entries/page?page=&size=` | 分页列表 |
| GET | `/api/entries/{id}` | 条目详情（走缓存） |
| POST | `/api/entries` | 创建目录或占位文件，校验配额与同级重名 |
| PUT | `/api/entries/{id}/name` | 重命名 |
| DELETE | `/api/entries/{id}` | 删除 → 进回收站（目录会级联整棵子树），返回受影响条目数 |

### 3.3 上传

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/files/upload` | multipart 上传，服务端计算 MD5 |
| POST | `/api/files/instant` | 秒传：只传 MD5，命中本人已有文件则直接建引用 |
| POST | `/api/upload-sessions` | 开启分片上传会话 |
| PUT | `/api/upload-sessions/{id}/chunks/{index}` | 上传一片（重复提交幂等） |
| GET | `/api/upload-sessions/{id}` | 查询已收到的分片（断点续传依据） |
| POST | `/api/upload-sessions/{id}/complete` | 校验清单 → 合并 → 落库 |

### 3.4 下载与分享

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/files/{id}/download` | 下载本人文件，支持 `Range`，返回 200 或 206 |
| POST | `/api/shares` | 为文件生成分享 token + 提取码，可设过期时间 |
| GET | `/api/shares` | 我创建的分享列表 |
| DELETE | `/api/shares/{token}` | 取消分享 |
| PUT | `/api/shares/{token}/passcode` | 重置提取码（链接不变，旧码立即失效） |
| GET | `/api/shares/{token}/info?passcode=` | **匿名**查看分享的文件名与大小 |
| GET | `/api/shares/{token}/download?passcode=` | **匿名**凭提取码下载 |
| POST | `/api/shares/{token}/save` | 转存到自己的网盘（需登录） |

### 3.5 回收站

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/entries/trash?page=&size=` | 回收站分页列表 |
| POST | `/api/entries/trash/{id}/restore` | 恢复到原位置（整棵子树一起恢复） |
| DELETE | `/api/entries/trash` | 彻底清空，并回收无人引用的磁盘对象 |

### 3.6 其它

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/snapshots` | 把当前用户条目异步导出为快照文件 |
| GET | `/actuator/health` | 健康检查（匿名可访问） |
| GET | `/actuator/metrics` | 指标（需登录） |

---

## 4. 代码结构

```
src/main/java/com/mengde/clouddrive/
├── CloudDriveApplication.java   Day01 命令行基线入口
├── domain/        领域对象：DriveEntry / DirectoryEntry / DocumentEntry / EntryKind
├── error/         业务异常（每一个都在 GlobalExceptionHandler 里有明确的状态码映射）
├── memory/        内存集合与索引（Day02/03 的数据结构落点）
├── snapshot/      文件快照：临时文件 + 原子替换
├── concurrent/    快照线程池：有界队列、去重、失败清键
├── storage/       存储抽象与本地实现（对象存储 + 分片存储）
├── persistence/   MyBatis Mapper 与仓储（行对象 ↔ 领域对象）
├── auth/          JWT、Security 过滤链、邮箱验证码与管理员账号管理
├── analysis/      DeepSeek 分类、后缀回退、异步任务与可选辅助审核
├── application/   应用服务：事务边界、配额、上传/分片/下载编排
├── redis/         Cache Aside 缓存、Redisson 锁与限流
├── schedule/      回收站过期清理定时任务与保留策略配置
└── web/           HTTP 契约：控制器、请求/响应 DTO、全局异常处理、traceId 过滤器
```

---

## 5. 关键设计说明

- **身份只来自令牌**：所有业务方法的第一个参数是 `AuthenticatedUser`，
  接口上不接受调用方传入的 `ownerId`。
- **越权返回 404 而不是 403**：SQL 的 `WHERE` 里始终带 `owner_id`，查不到即 404，
  连"资源是否存在"都不泄露。
- **重名依赖数据库唯一键**：先插入再捕获 `DuplicateKeyException`，
  而不是"先查后插"——后者在并发下必然漏判。
- **回收站的唯一键难点**：删除后原位置应能重建同名文件，
  所以唯一键建在"仅未删除时才有值"的生成列上（见 `V7`）。
- **跨资源操作自己写补偿**：文件系统不参与数据库事务，
  元数据写入失败时必须显式删除刚落盘的对象。
- **缓存只失效不更新**，且缓存故障时回源数据库，不影响主流程正确性。
- **限流放在入口**：在进入应用业务及写入正式对象前拒绝超额请求；
  multipart 可能已由 Servlet 容器解析并暂存，入口限流不是请求体零落盘保证。
- **删目录级联整棵子树**：每行记下 `trash_root_id`，回收站列表只显示"用户当初删的那一个"，
  恢复时按同一个批次整体还原。不这样做，删掉非空目录后子条目仍是"未删除"状态，
  会变成看不见却占配额的幽灵条目。
- **回收站有定时清理**：默认保留 30 天，到期后彻底删除并回收无人引用的磁盘对象；
  保留天数设为 0 即关闭。
- **转存不复制字节**：把别人分享的文件存到自己网盘时，只新建一条指向同一 `storage_key`
  的元数据，所以转存 1 GB 的文件也是毫秒级、磁盘不多占一份。
  配额仍按真实大小计入接收者，否则用户可以靠互相转存无限扩容。
  这也是为什么清理磁盘对象时的引用计数是全库统计、不带 owner 条件——
  回收站内仍可恢复的条目同样计入引用，否则 A 清空回收站会把 B 的内容一起删掉。
- **提取码可重置但不可查看**：只存 BCrypt 摘要，所以无法回显；
  提供重置能力是为了让用户忘记时不必取消重建（重建会换 token，已发出的链接全部失效）。
- **分享的三种失败合并成同一个 404**：token 不存在、已过期、提取码错误返回完全一样的结果，
  避免攻击者靠响应差异先枚举出哪些链接真实存在。

---

## 6. 当前完成度与已知缺口

**已实现并可运行**：第 3 节列出的接口、10 个 Flyway 迁移，以及管理员管理、文件智能分类、可选辅助审核接口。
2026-10-15 验证：96 项后端测试（含独立 MySQL/Redis 的 HTTP 集成测试）通过；40 项完整流程检查通过，邮件和 DeepSeek 使用本地模拟服务。真实 SMTP 和 DeepSeek 账号仍需配置后验证。
`smoke.ps1` 对运行中的服务执行 49 项端到端检查，覆盖认证、隔离、上传、秒传、
Range 下载、分享全链路（含转存与重置提取码）、级联删除与回收站恢复。

**已知缺口（诚实记录，不在文档里夸大）**：

1. **真实外部服务尚待配置**。SMTP 和 DeepSeek 的接口契约已通过模拟服务验证，未使用用户的真实邮箱授权码或 API Key 外呼。
2. **测试覆盖不完整**。目前是针对核心边界（领域身份、快照、线程池、
   Range 解析、存储路径逃逸、HTTP 合同）的代表性用例，不是全量回归集。
3. **快照文件路径全局唯一**，多用户先后导出会互相覆盖（见 `SnapshotController` 注释）。
4. **秒传仅限本人文件**，没有做跨用户的物理对象去重与引用计数。
5. **没有刷新令牌**。密码重置、账号禁用、角色变更会通过账号 token_version 立即使旧 JWT 失效；仍未实现单设备会话撤销。
6. **分片必须按序上传**，不支持并发乱序提交。
7. **微服务与全文检索尚未实现**。AI 当前支持有限 UTF-8 文本分类和辅助风险标记，不包含图片、音视频、PDF/Office 全文解析或强制分享拦截。
8. **回收站没有保留期以外的容量上限**。定时任务按天数清理（默认 30 天），
   但没有"超过多少容量就强制清理"的策略，短时间内大量删除仍会让磁盘吃紧。
9. **定时任务是单实例的**。`TrashCleanupJob` 没有分布式锁，多实例部署会重复执行。
   每步操作都幂等所以不会出错，但会重复扫描。
10. **Day02/03 的内存结构不在生产链路上**。`InMemoryEntryIndex` 只被测试引用，
   `InMemoryEntryList` 只被 Day01 的命令行入口 `CloudDriveApplication` 使用。
   它们是数据结构阶段的学习资产，自 Day12 把 HTTP 入口接到数据库之后就不再参与线上路径，
   保留是为了保留当时的设计与测试，而不是因为系统还在用它们。

## 7. 2026年10月17日安全修复

- 注册发码不再明确返回“该邮箱已注册”。所有合法邮箱与用途采用一致的正常响应和
  60秒冷却，不符合发码条件时只登记不可使用的冷却占位，不发送邮件。
- 注册唯一键冲突使用独立的409业务异常，回滚正确验证码的消费，允许更换用户名后
  使用原验证码重试；错误验证码仍累加失败次数，五次锁定保持不变。
- 本机直连默认忽略转发头。同机Nginx部署必须启用`SPRING_PROFILES_ACTIVE=deploy`，
  使用配套Nginx配置并限制8080仅本机访问；详见`../deploy/部署说明.md`。
- 普通上传在复制到正式对象目录前用收到的multipart大小检查配额，落盘后再次用服务端
  流式统计的真实大小精检。分片完成在合并前预检清单总量，保存正式对象后再次精检。
  账号行锁贯穿校验与元数据写入；这不替代200MB请求上限、临时目录空间与入口限流。
- 上传补偿、分片清理和半成品删除失败不替换原始异常，附加异常保留为suppressed或记日志。
  存储`IOException`明确触发事务回滚；分片文件只在完成事务提交后清理，回滚时保持可重试。
- 手工与定时清理共用独立事务服务，全局引用检查包含回收站。元数据提交后由单线程、
  有界队列执行器重新检查引用并清理物理文件，避免在提交线程等待第二条数据库连接。
  队列拒绝或清理失败时保留文件并记录日志，不以误删换取空间；残留对象需另行核查处理。

新增回归覆盖`AccountSecurityTest`、`TrustedProxyRateLimitTest`、`TrashPurgePersistenceTest`、
`TrashCleanupExecutorTest`、`UploadSafetyTest`、`UploadTransactionSafetyTest`和
`StorageFailurePreservationTest`。默认`mvn test`使用隔离H2、临时文件和受控外部服务边界；
真实MySQL/Redis集成测试仍由`integration`标签隔离，不应把测试连接指向正式库。

剩余边界：同步SMTP可能存在响应时延或投递故障差异，本轮不宣称恒定时延；
共享对象的“引用检查—物理删除”与并发秒传/转存之间仍非原子操作，多实例或高并发场景
需要所有复用与清理路径共同采用对象级锁或对象表。配额预检不能阻止Servlet此前接收并暂存请求体。
