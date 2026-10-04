# Day27 Final：Elasticsearch 文件检索与故障降级

日期：2026-10-04。实现检查点：`8fe065f feat(day27): add owner-scoped Elasticsearch search with revision fallback`；终稿审查修复：`e883c9b fix(day27): validate normalized search text before querying`。

## Checkpoint 0 接班事实与保留项

用户要求“先自审查，通过后开启 Day27”，随后授权继续。Day26 独立只读复审通过，记录在 `docs/day26/review-before-day27.md`，提交 `d18d38e`。该复审重读既有 175 项、26 项 HTTP、12/12 闸门证据，没有把旧运行声称为新测试。

Day27 从代码 `d18d38e`、教材 `90bf085` 继续；开工时代码工作区干净。教材仓库原有 38 项无关 dirty 状态全部保留，包括总纲、交接手册、verify_day.ps1、历史教材及未跟踪资料。仅精确暂存本日新增文件，不回滚用户改动，不写用户练习仓库。

保护既有总纲、手册、原始闸门与已提交 Final。根单体保留作历史与 Flyway 迁移入口，但四进程业务验证期间不启动 8080，不混用旧单体写文件表。

## Checkpoint 1 唯一增量与禁止项

唯一增量：总纲 Day27 的 **Elasticsearch 文件检索：mapping、索引同步策略、分页与故障降级**。

- file-service 使用官方 Java API client 8.10.4，与现有 Boot 3.2.4 BOM 对齐。
- MySQL 保存权威文件元数据与 owner revision；ES 为可丢弃重建的文件名搜索副本。
- 事务内版本标记、按需有界 generation 快照、Bulk/manifest 可见后发布、本地回表校验。
- JWT owner 隔离、固定 id 排序分页、ES 异常时显式 MySQL 整页降级、本人显式重建入口。

禁止：MQ/CDC、后台任务平台、AI/RAG、物理分库、相关性平台、Day28 traceId/指标/慢 SQL 可观测扩建。没有把临时 ES 故障诊断扩成下一日能力。

## 架构与合同

新增 GET `/api/files/search?q=report&page=0&size=20`，返回 `items,total,page,size,source,degraded,reason`。仅检索本人活跃 DOCUMENT，不包括目录、回收站、分享给他人的私有数据或正文。响应没有 storageKey、MD5 或凭据。owner 从 JWT principal 获得，伪造请求头和 ownerId 参数不能覆盖。

新增 POST `/api/files/search/reindex`，成功返回状态版本，失败 503。GET 可成功降级为 200，且明确 `source=MYSQL,degraded=true`；参数非法 400，未认证 401。数据库自身失败不包装成 ES 降级空结果。

q 长度 1–100，page 非负，size 1–50，使用 long 验证完整窗口不超过 10000。ES 使用 standard analyzer + match AND，按 fileId DESC；SQL 使用 BINARY LOCATE 字面大小写敏感子串，两种模式语义不同，不能声称结果完全等价。

根 Flyway 必须先迁移到 V10。file-service 自身仍不管理 Flyway，user-service 仍保留独立 history。V10 创建 file_search_state(owner_id,revision,indexed_revision,generation)。六个 owned Repository 写方法使用 REQUIRED 事务，同步元数据与版本；普通上传、秒传、分片完成共用 saveStoredDocument 接点。

重建使用新 RC 事务和 owner 状态行锁，最多读取 1001 条以检查 1000 上限。ES 文档 id 包含 owner/generation/fileId；每项 Bulk 成功并刷新、manifest 成功并刷新后，才发布数据库状态。失败不替换旧已发布 generation。DB 提交后精确清理旧 generation，失败只留下不可查询的旧数据，不撤销新状态。

查询发生在发布事务之外，之后开启新的 RC 事务锁 state，比较完整状态并逐 id 按 owner 回表。版本变化或失效命中触发整页 MySQL 重查；fallback 的 page/count 在同一个 RR 只读事务内。没有跨页 PIT，也没有后台同步时延保证。

## 跨天改动清单

| 文件边界 | 改动 | 原因 |
|---|---|---|
| file-service/pom.xml | 增加 BOM 管理的官方 ES client | 不手写搜索引擎或 HTTP 协议 |
| file application.yml | search 开关、端点、索引名；不把可降级 ES 计入 health | 默认关闭 ES，仍可使用 SQL 合同 |
| DriveEntryRepository | 新 FileSearchMapper 依赖，六处 owned 写事务内 changed | 防元数据成功但索引版本漏标 |
| 新 FileSearchMapper/search 包/Controller | 参数、模型、发布、查询、降级与本人重建 | 本日完整生产链 |
| 根 V10 migration | 新状态表，不改 V1–V9 | 保留已应用迁移校验和 |
| 三组普通搜索测试与 fixture | 真实 MySQL、客户端 HTTP 合同、MockMvc | 18 个本日新增常规案例 |
| ElasticSearchLiveIT | 显式 opt-in 真 ES/MySQL | 5 个真实索引集成案例 |

gateway/auth/user 生产代码、历史单体 Java、旧 Finals 与治理文件未修改。

## Checkpoint 2 测试与真实运行

应用环境：Microsoft JDK 17.0.20、Maven 3.9.10、Git 2.54.0.windows.1，Windows，缓存 `C:\Environment\m2repo`。ES 使用官方 8.10.4 ZIP 自带 JDK，SHA512 已核验；该归档版本是教学兼容基线，不是生产推荐版本。

| 执行项 | Tests/Checks | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根 clean test | 108 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| auth-service clean test | 19 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| gateway clean test | 20 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| user-service clean test | 11 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| file-service clean test | 35 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| file `-Dtest=ElasticSearchLiveIT test` | 5 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| 默认四进程 HTTP 审计 | 25 checks | 0 | 不适用 | 不适用 | AUDIT PASSED / exit 0 |

193 项常规加 5 项显式真实集成，共 198 个 JUnit 案例。file 的常规 35 是前日 17 + 本日 18；后来 Surefire XML 总数为 40 是两次运行的集合，不是一次默认 clean test 数字。原始 verify_day.ps1 只测试根模块，不能替代上述子模块矩阵。

五模块初次完整回归在 10-03；10-04 教材复审发现归一化后的空查询缺口，修复并追加 NUL/null 断言后，重新完整执行 file 35 项、真实 ES 5 项与四进程 25 项，均 exit 0。其他未改模块沿用 10-03 本日运行证据；不把旧运行伪称为同日重跑。

真实运行验证 JWT、双 owner、普通上传、秒传、分片合并后的索引、稳定分页、改名、删除/恢复、参数 400，实际停掉本轮 ES 后 MYSQL 降级、reindex 503、继续文件写入。四个应用均为生产 main + 依赖 classpath，移除 test-classes。未把 Maven spring-boot:run 或 Nacos lb 当作已实测。细节见 `docs/day27/runtime-evidence.md`。

全部相关命令会话已收取退出码；本轮临时四个应用和 ES 进程已停止，未结束其他 Java 进程。

## 审核发现与修复

| 问题 | 修复或处置 | 证据 |
|---|---|---|
| 搜索 Mapper 初稿单数表名错误 | 改为真实 drive_entries | 独立审查发现；真实 MySQL 9 项通过 |
| Operator 包名及 Number 比较不匹配 8.10.4 | query_dsl.Operator；failed().intValue() | 编译后完整 35 项通过 |
| ES bat 忽略 JAVA_TOOL_OPTIONS | 已诊断 socket 参数直接置 ES_JAVA_OPTS | 实际 JVM arguments 与启动成功 |
| 磁盘默认百分比水位阻断临时集群分片 | 核对集群身份后临时改剩余 5GB/4GB/3GB，保留保护 | LiveIT 从失败复测到 5/0/0/0 |
| Bulk 200 可能逐项失败 | errors + item.error，拒绝发布 | HTTP 替身与真实错误 mapping 双层测试 |
| RR 旧快照可能使版本复查失效 | 锁外查询后新 RC 锁 state 并回表 | 查询期间提交 rename 的真实 MySQL 测试 |
| 原 q 的 isBlank 检查早于 trim，NUL 可变成空查询 | 先归一化再检查 null/空白/长度 | 参数 NUL/null、HTTP NUL 400；35+5+25 复验 |

最终独立代码只读复审 PASS，未发现当前范围新的必须修复项。审计还通过本机客户端字节码确认解析异常包装为 TransportException extends IOException，现有适配器捕获覆盖；未直接注入 malformed JSON，属于覆盖限制而非已证明缺陷。

## 教材自审（Day02/Day05 对照）

教材：`阶段二/CloudDrive学习教材_Day27_Elasticsearch文件检索与故障降级.md`，约 1444 行。正文经段落密度审查合并为自然段，不删内容、不以行数证明质量。内联全部本日新增生产文件与测试，既有 Repository 用精确接点与改动示例说明，不重复粘贴昨日上传/配额整类。

| 审计项 | 项目源码/项目测试与教材位置 | 自审依据 |
|---|---|---|
| 前日衔接与真实问题 | 首段、§0 | 点名 Day26 三应用服务、QuotaCoordinator、FileHttpContractTest |
| 资产清单 | §1.3 | 至少两处当前生产与测试，责任及证据区分 |
| 项目源码走读 | §2–5 | 版本、模型、mapping、manifest、发布、RC/RR、身份与错误合同 |
| 项目测试走读 | §6 | 9/6/3/5 完整测试文件，mock、真实组件与证据边界明确 |
| 运行与迁移 | §7 | 根 V10、五模块、显式 LiveIT、四终端环境与生产 classpath |
| Debug | §8 十例 | 表名、编译、socket、水位、Bulk、索引丢失、语义、并发、分页、NUL 归一化 |
| 独立复刻 | §9 | 六项可判定任务，不代填用户结果 |
| 算法/SQL/Java | §10 | LC300，SQL Day15–16，事务/record/异常/顺序，未造运行结果 |
| 面试与简历 | §11 | 两分钟主线、追问和可说/不可说边界 |
| 限制与学习门 | §12 | 单节点安全、容量、锁、PIT、孤儿及前日风险 |
| 明日唯一输入 | §13 全文末尾 | 仅 Day28 的复盘与可观测，不追加本日核心内容 |

执行者已重读真实源码、测试、Surefire、25 项 HTTP 输出并核对教材。另一只读审计者在完成四项返工复核后，逐块确认 15 个完整内联文件与最终 e883c9b 一致，并读取最终 checks.txt/stdout、核对提交日期与部署前置，明确给出教材门 PASS，无未闭合的确定性缺口。该结论不代替尚未运行的工程闸门或用户学习门；最终机器结果记录在新增 acceptance.md，不修改提交后的本 Final。

首轮教材独立审计确实发现四项缺口后返工：NUL 参数校验顺序、mock 空快照不能证明 manifest 的证据表述、全部终端的 PowerShell 7/UTF-8 前置、逐句断行排版。相应代码与教材均已修正，复核不能只用旧审查结论。

## Checkpoint Final 验收边界

代码实现及上述真实验证已完成；完整教材、Final、回执需要独立复读并提交后，再执行原始 `verify_day.ps1 -DayNN 27`。这里不预填未运行的闸门结果，最终输出留到 `docs/day27/acceptance.md`。

必须保留的限制：每 owner 1000 文档快照上限、同 owner 状态锁等待、无全链 deadline/压测 SLA、无跨页 PIT、ES 与 SQL 检索语义不同、失败清理及未发布 generation 可能孤儿、无后台修复/回收、外部索引删除需显式恢复、manifest 不检测任意外部文档篡改、学习单节点无认证仅回环、共享 schema、旧单体停写，以及 Day26 已披露的 Nacos/锁/存储/补偿限制。

人工学习门仍待用户闭卷解释、闭卷复刻、真实错因与题单记录，AI 无权代填。明日唯一输入是总纲 Day28 一致性/CAP/分布式事务取舍复盘与故障可观测，本轮不实施 Day28。
