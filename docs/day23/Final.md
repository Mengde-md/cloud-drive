# Day23 Final 微服务边界拆分设计与非个人凭据运行环境冻结

## Checkpoint 0 昨日真实资产与限制

Day22 已交付回收站软删除、活动列表/回收站分页、OpenAPI/Postman 契约与单体请求完成日志/health
可观测性，90 项全量回归通过，实现提交 `08b21e6 feat(day22): add trash pagination and observability`。
它不提供任何服务边界定义：全部功能仍运行在同一个 Spring Boot 进程、同一个 `user_account` 表里，
`UserAccountMapper` 把身份查询（`findByUsername`）和配额加锁（`lockQuotaById`）混在一个 Mapper；
JWT 校验发生在应用内部 filter chain，没有独立网关；仓库里 11 个集成测试直接把
数据库账号与密码写死在 `@SpringBootTest(properties = {...})` 里，换凭据时需要改注解或另设覆盖手段。这些是 Day23 要处理的边界债务；环境变量化提供了新配置入口，非个人账号能否通过测试仍须在该账号下实际取证。

## Checkpoint 1 唯一增量、真实问题与禁止项

- **真实问题**：单体里 auth 与 user/配额数据混在一张表、一个 Mapper 里；网关鉴权边界没有写
  下来；一旦真的按 Day25/26 计划物理拆分，如果不先把数据归属、契约和 MQ 取舍钉死，会出现
  "边拆边返工"；同时 11 个测试文件硬编码个人数据库密码，任何非本机环境（CI、队友电脑、
  Docker 容器）都无法直接跑通 `mvn clean test`。
- **唯一增量**：
  1. `docs/architecture/day23-service-boundaries.md`：auth/user/file/gateway 四个服务的数据
     归属表、对外/对内契约、三类流程（未来的配额 reserve/commit/release、未来后台硬删除通知、登录活动遥测）各自的同步/MQ 取舍及幂等、重试、顺序、死信设计。这些是未来方案，不是 Day23 已运行的远程接口或消息链路。
  2. `deploy/docker-compose.yml` + `deploy/.env.example`：冻结一个可复现的 MySQL 8.0 + Redis
     7.2 运行环境，所有凭据通过 `CLOUDDRIVE_DB_*`/`CLOUDDRIVE_REDIS_*` 环境变量注入，
     compose 文件本身不含任何真实密码（`.env` 已在 `.gitignore` 中）。
  3. 修复 11 个测试文件里硬编码的 `spring.datasource.username=root`、
     `spring.datasource.password=123456`，改为 `${CLOUDDRIVE_DB_USERNAME:root}`、
     `${CLOUDDRIVE_DB_PASSWORD:123456}`（默认值不变，旧环境的行为预期不变；新账号覆盖路径仍需实测）。
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
  四个服务的数据归属与对外/对内契约。`POST /api/snapshots` 当前落入 `anyRequest().permitAll()`，控制器也没有认证主体；架构文档已把它标为拆分前待决策的例外，不能宣称现有路由全部安全覆盖。认证契约已按源码改为登录 `{accessToken, expiresAt}`、`/me` 的 `roles` 列表。
- 第 3 节把未来配额预占设计为同步 HTTP，调用方在首次 reserve 前生成 `reservationId`，超时重试复用；未来后台硬删除通知可用 MQ，但当前 `DELETE /api/entries/trash` 仍为用户同步触发，仓库没有定时批处理或消息发布；登录活动遥测可尽力而为，正式安全审计不能采用失败即丢策略。当前 HS256 是对称密钥，网关并没有可单独分发的公钥。
- `deploy/docker-compose.yml` 的 MySQL/Redis 服务全部使用 `${VAR:?错误信息}` 语法强制要求
  必填环境变量，容器启动前必须先复制 `deploy/.env.example` 为 `deploy/.env` 并填入真实值，
  且应用读取相应的 `CLOUDDRIVE_DB_*`/`CLOUDDRIVE_REDIS_*` 变量。`--env-file` 只供 Compose 插值；另行启动的 Spring/Maven 进程仍须取得同一套实际环境变量，不能假定它会自动读取 `deploy/.env`。
- 11 个测试文件（`AuthHttpContractTest`、`DriveEntryMapperTest`、`DriveEntryRepositoryTest`、
  `UploadCoordinationIntegrationTest`、`DocumentUploadHttpContractTest`、
  `DriveEntryHttpContractTest`、`FileDeliveryHttpContractTest`、
  `PrivateEntryCacheHttpContractTest`、`ResumableUploadHttpContractTest`、
  `SnapshotHttpFailureTest`、`TrashHttpContractTest`）的 `@SpringBootTest(properties = {...})`
  中，`spring.datasource.url/username/password` 三行全部改为
  `${CLOUDDRIVE_DB_URL:...}`/`${CLOUDDRIVE_DB_USERNAME:root}`/`${CLOUDDRIVE_DB_PASSWORD:123456}`，
  可用 `git show 598ed8c -- src/test` 核对最初的属性行替换。首次全量回归还发现共享 MySQL 中的外键清理债务；`ec1c747` 另外修复七个合同测试类的前后清理顺序，因此“整个 Day23 只改三行字面量”已不符合完整工程历史。

## 测试与验收：按时间区分历史工程门和当前工作树

`598ed8c` 的 11 个测试类只替换 JDBC 三项属性字面量，默认值路径预期保持旧行为；环境变量覆盖路径和新数据库权限仍需独立实测。首版交付时执行环境没有 JDK 17/Maven，因此最初的“未运行”记录仅代表当时状态。2026-09-22 用户在 Windows 本机首次运行 `verify_day.ps1 -DayNN 23` 得 11/12 PASS；第 6 项全量测试为 90 项中 4 failures、1 error，第一处实质异常是 `TrashHttpContractTest.clean()` 删除用户时违反外键约束。共享的持久化 MySQL 中，`file_shares` 与 `upload_sessions` 可残留对父表的引用，清理中断再污染后续测试。

`ec1c747 fix(day23): make integration test cleanup FK-safe and self-healing across the shared MySQL database` 把七个 HTTP 合同测试类改为 `@BeforeEach @AfterEach` 双向清理，顺序为 `file_shares → upload_sessions → drive_entries → user_account`，未改业务代码与合同断言。修复后用户本机记录 `mvn -f <repo>\pom.xml -Dmaven.repo.local=C:\Environment\m2repo clean test` 为 `Tests run: 90, Failures: 0, Errors: 0, Skipped: 0`、BUILD SUCCESS；`verify_day.ps1 -DayNN 23` 为 **12/12 PASS**、`Day23 ENGINEERING ACCEPTED`。这是一条已发生的历史验收记录，不应继续把 Day23 写成“工程门待用户运行”。

本轮文档复核时，工程仓库已有未提交的后续 Day25 代码和测试。其状态不能拿来推翻 2026-09-22 的 Day23 结果，也不能声称当前 `git status --porcelain` 为空。若要验证当前混合工作树，须另行检验并按当前错误归因；本轮只修订 Day23 文档，不清理或提交 Day25 工作。Compose 模板与环境变量占位符也不等于非个人账号、Docker 启动和服务健康检查都已实测，这些实践证据仍由实际运行补齐。

## 面试题与真实问题映射

本 Day 教材 `CloudDrive学习教材_Day23_微服务边界拆分设计与非个人凭据环境冻结.md` 第 7 节以实际 `UserAccountMapper`、`AuthController` 和 `SecurityConfiguration` 为依据，回答数据归属、同步配额、未来异步通知、幂等键及网关信任边界。简历仅可写“设计服务边界与契约、冻结配置模板、修复共享数据库测试隔离”；不能写“完成微服务拆分”“上线 MQ”或“非个人账号已验证”。

## 教材自审 Day02 Day05 对照

| 审计项 | 真实教材证据 | 结论 |
| --- | --- | --- |
| 前日衔接与真实问题 | 第 0-1 节从 Day22 单体边界进入身份/配额同表、网关信任边界与配置债务；明确未来设计与已实现代码的时间边界 | 通过 |
| 两处以上项目生产源码 | 第 2-3 节逐段走读 `UserAccountMapper`、`SecurityConfiguration`、`AuthController`、`UploadSessionApplicationService.verifyQuota` 与路由表；纠正 `/api/snapshots` 实际放行及登录 DTO 字段 | 通过 |
| 两处以上项目测试源码 | 第 4 节走读 `AuthHttpContractTest` 的认证链断言、`TrashHttpContractTest` 的所有者隔离和外键安全清理，并给出 JDBC 属性改动前后对比 | 通过 |
| 测试矩阵与运行证据 | 第 4.4 节区分默认值、覆盖值、Compose 与历史工程门；第 20 节记录首跑失败、`ec1c747` 修复、历史 90/0/0 与 12/12 PASS，不把未实测的非个人账号写成已通过 | 通过 |
| Debug/故障排障 | 五类 Debug 分别覆盖环境变量、Compose 插值、预占幂等、网关头伪造、共享 MySQL 外键清理；每类交代输入/预期、证据、定位、修复与回归，未来演练显式标注 | 通过 |
| 独立复刻与阶段题 | 第 8-9 节含通信选择算法推导、同库迁移 SQL 草案、Java 预占状态题与跨域电商复刻；`used_bytes` 为未来字段、当前由条目求和，已明确区分 | 通过 |
| 面试、验收与明日输入 | 第 7 节简历话术不夸大交付；第 20 节汇总三门证据和人工学习记录；第 21 节是全文唯一明日衔接，位于末尾 | 通过 |

本表为同一执行者对教材与源码的交叉复读，不是第三方独立评审。历史工程门已于 2026-09-22 通过；用户仍需口述、闭卷复刻、记录错因和完成题单，工程验收与教材自审均不代替人工学习门。

本轮教材全文为 522 个 Markdown 物理行、28,635 字符，UTF-8 可严格解码且无替换字符。原教材末尾残留一个无效字节，已精确移除；中段提前出现的“验收 Checklist”已改为复刻前证据顺序，唯一最终验收与明日衔接置于末尾。篇幅由自然段、实际源码/测试片段与必要表格组成，不为达到固定行数拆分普通解释句。
