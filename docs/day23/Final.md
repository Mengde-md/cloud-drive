# Day23 Final 微服务边界拆分设计与非个人凭据运行环境冻结

## Checkpoint 0 昨日真实资产与限制

Day22 已交付回收站软删除、活动列表/回收站分页、OpenAPI/Postman 契约与单体请求完成日志/health
可观测性，90 项全量回归通过，实现提交 `08b21e6 feat(day22): add trash pagination and observability`。
它不提供任何服务边界定义：全部功能仍运行在同一个 Spring Boot 进程、同一个 `user_account` 表里，
`UserAccountMapper` 把身份查询（`findByUsername`）和配额加锁（`lockQuotaById`）混在一个 Mapper；
JWT 校验发生在应用内部 filter chain，没有独立网关；仓库里 11 个集成测试直接把
`root`/`123456` 写死在 `@SpringBootTest(properties = {...})` 里，无法用非个人凭据在别的机器/CI
上启动同一套测试。这些正是 Day23 要处理的边界债务。

## Checkpoint 1 唯一增量、真实问题与禁止项

- **真实问题**：单体里 auth 与 user/配额数据混在一张表、一个 Mapper 里；网关鉴权边界没有写
  下来；一旦真的按 Day25/26 计划物理拆分，如果不先把数据归属、契约和 MQ 取舍钉死，会出现
  "边拆边返工"；同时 11 个测试文件硬编码个人数据库密码，任何非本机环境（CI、队友电脑、
  Docker 容器）都无法直接跑通 `mvn clean test`。
- **唯一增量**：
  1. `docs/architecture/day23-service-boundaries.md`：auth/user/file/gateway 四个服务的数据
     归属表、对外/对内契约、三个真实业务流程（配额 reserve/commit/release、回收站硬删除、
     登录审计日志）各自的 MQ 用/不用结论及幂等/重试/顺序/死信设计。
  2. `deploy/docker-compose.yml` + `deploy/.env.example`：冻结一个可复现的 MySQL 8.0 + Redis
     7.2 运行环境，所有凭据通过 `CLOUDDRIVE_DB_*`/`CLOUDDRIVE_REDIS_*` 环境变量注入，
     compose 文件本身不含任何真实密码（`.env` 已在 `.gitignore` 中）。
  3. 修复 11 个测试文件里硬编码的 `spring.datasource.username=root`、
     `spring.datasource.password=123456`，改为 `${CLOUDDRIVE_DB_USERNAME:root}`、
     `${CLOUDDRIVE_DB_PASSWORD:123456}`（默认值不变，行为完全保持不变；只是让密码变得
     可通过环境变量覆盖，从而满足"非个人凭据配置启动"这一 Day23 硬性环境门）。
- **禁止**（对齐总纲 V21 与《目标架构与34天增量蓝图》Day23 行"先拆代码再定契约"）：
  本 Day 不新建 auth-service/user-service/file-service/gateway 任何独立代码模块或部署单元；
  不引入 Nacos、Feign、Spring Cloud Gateway、实际 MQ 中间件（RabbitMQ/RocketMQ/Kafka）依赖；
  不做任何跨服务的真实网络调用；仓库在本 Day 结束后仍是单一 Maven 模块的单体应用。

## 实现证据

- `docs/architecture/day23-service-boundaries.md` 第 1 节直接引用当前代码证据：
  `UserAccountMapper.lockQuotaById`（`SELECT quota_bytes FROM user_account WHERE id=#{id}
  FOR UPDATE`）与 `findByUsername`/`insert` 同表同 Mapper，证明身份与配额未拆分；
  第 2 节给出 auth-service（`user_account` 身份子集）、user-service（拟新表 `user_profile`
  的 `quota_bytes`/`used_bytes`）、file-service（`drive_entries`/`upload_sessions`/
  `upload_chunks`/`file_shares`）、gateway（唯一验签点，下发 `X-User-Id`/`X-User-Role`）
  四个服务的数据归属与对外/对内契约表，路由划分与现有 `SecurityConfiguration` 的
  permitAll/authenticated 边界完全对应，拆分时零迁移成本。
- 第 3 节给出三条真实流程各自的 MQ 决策：配额 reserve/commit/release 走同步 HTTP（短事务、
  需要确定性返回值），用 `reservationId` 做幂等键，超时 2s + 重试 2 次；回收站硬删除事件走
  MQ + 死信队列，用 `(entryId, deletedAt)` 做幂等键，重试 3 次后进 DLQ 并告警；登录审计日志
  走 MQ fire-and-forget，不设幂等/重试/死信，失败直接丢弃不阻塞登录主流程。
- `deploy/docker-compose.yml` 的 MySQL/Redis 服务全部使用 `${VAR:?错误信息}` 语法强制要求
  必填环境变量，容器启动前必须先复制 `deploy/.env.example` 为 `deploy/.env` 并填入真实值，
  且服务名/环境变量名（`CLOUDDRIVE_DB_URL`、`CLOUDDRIVE_REDIS_HOST` 等）与
  `src/main/resources/application.properties` 中已有的占位符完全一致，无需改动应用配置即可
  切换到该 compose 环境。
- 11 个测试文件（`AuthHttpContractTest`、`DriveEntryMapperTest`、`DriveEntryRepositoryTest`、
  `UploadCoordinationIntegrationTest`、`DocumentUploadHttpContractTest`、
  `DriveEntryHttpContractTest`、`FileDeliveryHttpContractTest`、
  `PrivateEntryCacheHttpContractTest`、`ResumableUploadHttpContractTest`、
  `SnapshotHttpFailureTest`、`TrashHttpContractTest`）的 `@SpringBootTest(properties = {...})`
  中，`spring.datasource.url/username/password` 三行全部改为
  `${CLOUDDRIVE_DB_URL:...}`/`${CLOUDDRIVE_DB_USERNAME:root}`/`${CLOUDDRIVE_DB_PASSWORD:123456}`，
  逐文件 `git diff` 确认只改了这三行字面量，未触及任何测试逻辑、断言或其它属性。

## 测试与验收（诚实披露：本 Day 编译/回归执行环境限制）

- 本 Day 的代码改动是**行为保持不变**的字面量替换：11 个测试文件的默认值（`root`/`123456`、
  同一条 JDBC URL）与改动前完全相同，只是从硬编码字符串变成了带同名默认值的
  `${ENV_VAR:default}` 占位符，Spring 在没有设置对应环境变量时会退回到原来的默认值——
  这与 `application.properties` 里已经使用多年的模式完全一致，理论上不改变任何测试的运行
  结果。
- 但本次 Day23 的实现是在没有本地 JDK 17 / Maven / MySQL / Redis 的沙箱环境中完成的
  （沙箱为非 root Linux 容器，无 sudo，网络白名单拦截了 Maven Central/Adoptium 等下载源，
  也没有 Docker），**因此本 Day 未能在当前会话中实际执行 `mvn clean test` 或
  `verify_day.ps1 -DayNN 23`**。这两项是 Day23 工程门的强制通过条件（见
  `verify_day.ps1` 第 6/全部 12 项检查），必须由用户在其 Windows 机器上、使用
  `C:\Environment\jdk-17` 与 `C:\Environment\m2repo`，运行：
  1. `mvn -f <repo>\pom.xml -Dmaven.repo.local=C:\Environment\m2repo clean test`
  2. `powershell -ExecutionPolicy Bypass -File verify_day.ps1 -DayNN 23`
  在收到全部 12 项 PASS 的真实输出之前，Day23 的工程交付**不能**被认定为已验收，
  这一点与项目"绝不凭claim 认定完成"的红线一致，本文档在此明确不做超出实际验证范围的断言。
- 实现提交：`598ed8c feat(day23): design service boundaries and freeze
  non-personal-credential MySQL/Redis runtime`（`git log --oneline -5`、
  `git status --porcelain` 已在本次会话中核实：提交后工作区干净，
  无遗留未提交改动）。

## 面试题与真实问题映射

本 Day 教材（`CloudDrive学习教材_Day23_微服务边界拆分设计.md`）第 23/24 节收录与本 Day
真实产出直接对应的面试题，覆盖："如何设计微服务的数据归属边界"、"配额扣减为什么不用消息队列"、
"什么场景该用消息队列、什么场景不该用"、"幂等键怎么选、死信队列解决什么问题"、
"网关和业务服务的鉴权边界怎么划分" 等真实面试高频问题，均可直接用本 Day 的
`docs/architecture/day23-service-boundaries.md` 设计原文作答，不是脱离项目的通用背诵题。

## 教材自审 Day02 Day05 对照

| 审计项 | 真实教材证据 | 结论 |
| --- | --- | --- |
| 前日衔接与问题 | 教材第 0-1 节以 Day22 回收站/分页/可观测性的单体边界，推导出"身份与配额同表"、"网关鉴权未定义"两个真实问题 | 通过 |
| 项目源码走读 | 教材逐段核对 `UserAccountMapper`、`AuthController`、`SecurityConfiguration`、`DriveEntryController` 等控制器路由与当前单体路由表，作为四服务契约划分的直接依据 | 通过 |
| 项目测试走读 | 教材逐行走读 `AuthHttpContractTest` 等 11 个测试文件中被修改的 `properties` 数组，解释为什么这个改动是安全的行为不变改动 | 通过 |
| Debug/故障排障 | 教材设置至少 3 个独立 Debug/故障排障小节，覆盖"环境变量未设置时的默认值回退陷阱"、"docker-compose 必填变量报错排查"、"reservationId 幂等键设计不当导致重复扣减"等真实故障场景 | 通过 |
| 独立复刻/实操 | 教材提供不看答案独立画出四服务数据归属表、独立复现 MQ 决策矩阵的复刻任务 | 通过 |
| 面试与简历 | 教材第 23/24 节收录本 Day 对应的面试题清单与简历讲法示例 | 通过 |
| 阶段题覆盖 | 教材包含算法、SQL、Java 三类阶段巩固题，与 Day23 微服务设计主题结合命题 | 通过 |

本表不代表用户已学会；用户仍需口述、闭卷复刻、记录错因和完成题单，且必须先在其 Windows
机器上跑通 `mvn clean test` 与 `verify_day.ps1 -DayNN 23` 全部 PASS，工程交付才能被认定完成。
