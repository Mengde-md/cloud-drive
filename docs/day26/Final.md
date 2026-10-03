# Day26 Final：file-service 迁移与 Feign 超时补偿

日期：2026-10-03。实现检查点：`b4acef5 feat(day26): migrate file service with bounded Feign quota compensation`。

## Checkpoint 0 接班事实与保留项

从 Day25 `d40e0cc` 继续：auth-service 已独立、网关已验签，文件业务仍依赖单体。Day25 默认三进程运行已验证，但真实 Nacos 注册/lb 仍受 Docker Desktop backend socket 启动失败阻塞。

开工时代码仓库已有未跟踪 `file-service/`、`user-service/` 半成品（73 个文件），用户授权继续 Day26 后在其基础上完成，未删除或回退。原单体源码与历史测试保留；旧 Final、总纲、交接手册、verify_day.ps1 未修改。用户练习仓库未写入。

教材仓库原有 38 项无关 dirty 状态，包括既有教材、治理文档及未跟踪资料，全部保留，不混入 Day26 提交。不能为满足干净工作区要求撤销用户改动；原始闸门检查的是代码仓库。

## Checkpoint 1 唯一增量与禁止项

唯一增量严格对应总纲 Day26：**file-service 迁移 + Feign 调用/超时/补偿/契约测试**。

- 独立 file-service 承接 entries、普通上传、秒传、分片、下载/Range、分享与回收站。
- 最小 user-service 提供内部配额预留/确认/释放，是本日 Feign 配额调用所需，不扩展用户管理功能。
- 稳定 reservationId、有界重试、持久化本地提交/取消意图及同 owner 下一次写入时恢复。
- 网关文件与 OpenAPI 路由改至独立 file 进程，保留既有认证与路径合同。

禁止：Elasticsearch/索引同步、MQ、后台调度平台、熔断平台、全局分布式事务、物理分库、多模块目录重构和任何 AI 功能。本轮不实施 Day27。

## 架构与实际合同

详见 `docs/architecture/day26-file-service-feign-compensation.md`。

默认进程：gateway 9000、auth 8081、file 8082、user 8083。业务联调时单体 8080 不运行。历史 `user-service-route` 路由名承载 `/api/entries/**`，目标实际为 file；quota 仅有内部路径，没有公网网关路由。

共享 schema 是明确过渡前提：根 Flyway 先迁移到 **V9**，user 自有 history 执行 V1（一次性从 user_account 回填 quota）。file 自身不管理 Flyway，不能声称四服务可从空库独立启动。切换后不得混用旧单体写入；持久化对象/分片目录必须显式设置，不能依赖会被 clean 删除的默认 build-output。

与 Day23 目标设计的差异明确保留：user 没有完整权威 used_bytes 账本；file 在 owner 锁内计算 usedBytes，user 管理 quota 与活动预留。注册不主动创建 profile，首次 reserve 经身份查询后懒初始化。reserve 传 `{reservationId, amount, usedBytes}`、返回纯文本 ID；commit/release 使用 ID 路径参数。实际合同以客户端、Controller 与测试为准，不覆盖旧设计文档。

本地状态：PREPARED → COMMIT_PENDING → DONE；或 PREPARED → RELEASE_PENDING → DONE。metadata 与 COMMIT_PENDING 原子提交；RELEASE_PENDING 必须在远端 release/文件清理前用独立事务提交。操作行锁与 expected-phase UPDATE 阻止旧请求恢复后复活已取消操作。COMMIT_PENDING 只确认，不释放、不删对象。

Feign 自带重试关闭；quota 只对 RetryableException 最多尝试 3 次，退避 100/200ms，connect/read timeout 为 1s/2s；409 不重试。网关 30s 不是全链路 deadline。身份调用不套显式 quota 重试循环。

## 跨天改动清单

| 文件/边界 | 本日改动 | 原因 |
|---|---|---|
| `file-service/` | 新独立工程，迁入领域、存储、下载/分享、缓存及文件 HTTP 链 | 真正迁移业务进程 |
| file `DocumentUploadApplicationService` | quota.execute；秒传无新对象清理权 | 去除直接用户配额 SQL，防失败删除共享对象 |
| file `OwnedDriveEntryApplicationService` | create/restore 预留，delete/purge 持 owner 锁 | 覆盖所有用量变更 |
| file `UploadSessionApplicationService` | 持 session 锁内事务提交；合并完成走补偿协调 | 修复 manifest 可见性竞争并迁移分片用量 |
| `user-service/` | 最小 quota provider、内部令牌、V1 与合同测试 | 为文件服务提供可验证远端依赖 |
| 根 `V8__create_file_quota_operations.sql` | 操作日志 | 保存局部结果 |
| 根 `V9__persist_quota_release_intent.sql` | 扩展状态约束 | 已应用 V8 不改校验和，增加持久化取消意图 |
| gateway 两份 application YAML | 文件路由至 file、默认 8082/nacos lb、响应超时 30s | 避免流量仍回到单体 |
| `GatewayRouteTableTest` | file URI 与 legacy health 目标不同 | 防迁移配置回退 |
| `deploy/.env.example` | file/user 地址、端口、服务名与额度输入 | 明确启动变量与停写前提 |

根业务 Java 与 auth-service 实现未在 Day26 改写；复制带来的后续合同同步成本如实保留。旧单体 health 路由仍存在但联调时不可用，不宣称六条路由都可达。

## Checkpoint 2 测试与真实运行

环境实测：Microsoft JDK 17.0.20、Maven 3.9.10、Git 2.54.0.windows.1，Windows 11，Maven cache `C:\Environment\m2repo`。本机使用 JAVA_TOOL_OPTIONS 的 Unix-domain socket 临时目录兼容设置，没有搬仓库或创建替代验收工程。

| 工程 | Tests | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根工程 | 108 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| auth-service | 19 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| gateway | 20 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| user-service | 11 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| file-service | 17 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |

合计 175 项，不是新增 175 项。五条 clean test 均完整结束，之后重新读取各模块 Surefire XML 核对。原始脚本只测根工程，不能替代另外四个模块。

file 测试明细：Feign HTTP 3、真实 MySQL 补偿 8、分片事务顺序 2、HTTP/security 4。user：真实 MySQL 配额 6、内部 HTTP 5。JWT decoder 在 FileHttpContractTest 中是替身，真实签发/验证兼容由 auth 测试与四进程运行补证。

最终四进程运行在 `b4acef5` 上通过 26 项：上传、Range、跨用户下载拒绝、秒传、分片重传/合并、分享、软删除/恢复、OpenAPI、quota 停机写 503/读 200、重启后的延迟释放恢复。file/user 独立访问日志证明请求实际到达。详情与原始 PASS 摘要在 `docs/day26/runtime-evidence.md`，本轮所有审计进程已停止。

运行使用生产 main classes + 依赖 classpath（剔除 test-classes）直接启动，不把它写成已验证 Maven spring-boot:run 启动。库凭据使用已有学习测试配置；本机无密码 Redis 使用独立 Redisson 覆盖，不改变服务器或输出凭据。

## 审核发现与修复

| 问题 | 修复 | 证据 |
|---|---|---|
| RR 早期一致性读导致聚合预留旧快照 | reserve 显式 RC + profile 行锁 | 两个 60 对 quota 100 恰一成功 |
| putChunk 锁早于代理提交释放 | 持锁内 REQUIRES_NEW TransactionTemplate | commit/rollback 都先于 close 两项测试 |
| raw URI 前缀可能绕过内部鉴权 | 除精确 health/info 外全部要求 token | 分号、编码首段、context path 与未知路径回归 |
| Redis 旧 holder 恢复将取消操作复活 | 操作行锁、条件更新、写入只允许 PREPARED | 已取消旧写拒绝、事务竞争等待两项 MySQL 测试 |
| release 成功但 delete 失败回滚取消意图 | 独立事务持久化 RELEASE_PENDING，追加 V9 | 清理失败旧 supplier 未调用，恢复后收尾 |
| 部署文案可能隐去 schema 前置 | 明确根 V9、user V1、停单体写与绝对存储目录 | 教材 §1/§9、架构文档 |

单写者负责实现与测试；另一只读审计者发现并复核事务、鉴权、补偿竞争问题，不修改文件或运行测试。最终状态机复核结论为未发现该修复范围内新的必须修复项；不把这个结论外推为无剩余风险。

## 教材自审（Day02/Day05 对照）

教材：`阶段二/CloudDrive学习教材_Day26_file-service迁移与Feign超时补偿.md`，完整稿约 1,860 行，15 个内联 Java 源码/测试代码块。原理与失败链围绕当前真实资产展开，不重复粘贴无变化的旧领域代码，不以行数替代质量。

| 审计项 | 项目源码/项目测试与教材位置 | 复读结论 |
|---|---|---|
| 前日衔接与真实问题 | 首段、§0：Day25 网关/auth，单体直接配额 SQL，超时未知结果 | 因果链完整 |
| 生产源码逐段走读 | §2–6：两个 Feign 客户端、QuotaGateway、QuotaCoordinator、两组 Mapper、QuotaService、Controller/Filter | 15 个内联源码/测试块均与实现逐块一致 |
| 迁移链路 | §7：普通上传、秒传、分片、目录/回收站及跨天清单 | 清理所有权与锁/事务边界明确 |
| 真实项目测试 | §8：FeignQuotaContractTest、QuotaCoordinatorIntegrationTest、QuotaServiceIntegrationTest、ChunkTransactionBoundaryTest、HTTP 合同 | 真实组件、替身、不能推出的结论分开 |
| 测试矩阵与运行 | §8 表、§9 命令与 HTTP 步骤，runtime-evidence.md | 175 与 26 两层证据不混计 |
| Debug | §10 十个案例，均含触发、预期/现象、证据、定位、修复、回归 | 已执行与推演/练习注入分别标明 |
| 网络 | §11 十二卡，DNS/TCP/TLS、Cookie/Session/Token、CORS、超时和重试放大 | 本项目参数与协议一般原则分开 |
| 独立复刻 | §12：状态机、失败清理、路径鉴权、双用户 | 有关闭教材后的可判定任务 |
| 阶段题 | §13：题单 Day26 LC23、SQL Day15–16 对应查询、Java 异常与代理 | 没有伪造 EXPLAIN 或学习完成记录 |
| 面试与简历 | §14 两分钟叙述、十个追问、能说/不能说 | 不宣称强一致、性能提升或 Nacos 已上线 |
| 限制与唯一后续 | §15 明确剩余风险；§16 是全文唯一明日输入且处于末尾 | 没有在收尾后追加核心内容 |

执行者重新读取实际源码、测试、教材运行/Debug/阶段题章节及 Surefire/HTTP 记录。另有只读审计者逐块对比全部 15 个内联 Java 块与 b4acef5，确认一致；其全文审核发现首段测试职责归属与 Maven Redisson 参数写法两处缺口，修正后再次只读复核，明确给出教材门 PASS。该结论不代表工程门或人工学习门通过。最终教材审核记录与原始工程闸门结果随 `acceptance.md` 留痕，不修改已提交 Final。

## Checkpoint Final 验收边界

实现、175 项自动化回归、默认四进程 26 项运行及完整教材已形成。工程闸门必须在本 Final、教材与发布回执提交后执行原始 `verify_day.ps1 -DayNN 26`；本段不预填尚未执行的 12/12 结果。最终机器输出与教材审核结论记录到 `docs/day26/acceptance.md`。

必须保留的限制：真实 Nacos 注册/lb 未验证；共享 schema 和账户尚未物理隔离；同 owner 下一次写入触发补偿、无后台自动对账；owner/分片锁失效无完整 fencing；无全链 deadline；对象落盘与日志之间可能遗留孤儿；提交后缓存或分片清理失败可能向客户端返回错误；普通 multipart 无客户端幂等键。

这些限制不影响已验证路径的事实，但禁止把 Day26 交付称为生产级强一致上线。用户人工学习门仍待闭卷解释、闭卷复刻、真实错因和题单记录，AI 不代填。

明日唯一输入：总纲 Day27 Elasticsearch 文件检索（mapping、索引同步策略、分页与故障降级）。本轮止于 Day26，不自动实施下一日。
