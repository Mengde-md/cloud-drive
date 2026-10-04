# Day28 Final：一致性取舍与链路故障可观测

日期：2026-10-04。实现检查点 `ff9d2bd feat(day28): correlate service faults and protect bounded diagnostics`；纯格式后续提交 `57712b8 style(day28): remove extra trailing blank lines`。

## Checkpoint 0 接班事实与保留项

用户明确开启 Day28 并授权继续。最小接班读取总纲 Day28/29、交接手册相关门禁、Day27 Final 与 acceptance、当前项目源码/测试、两个仓库 git log/status。代码从 `0e1fc8f` 开始且工作区干净；教材从 `d5cec4d` 开始，原有 38 项无关 dirty 状态全部保留，不混入本日提交。

唯一代码仓库是既有文档配套工程，不写桌面用户练习仓库。保护总纲、交接手册、verify_day.ps1 与已经提交的所有旧 Final。根单体在四进程业务演练中停用，不并行写文件表。教材制作推进不等于用户人工学习门通过。

## Checkpoint 1 唯一增量与禁止项

唯一输入是 Day26 配额预约/补偿链与 Day27 ES 搜索/SQL 降级链。唯一增量为总纲 Day28：**一致性/CAP/分布式事务取舍复盘 + 故障演练（traceId、结构化日志、指标、慢调用/慢 SQL 可观测）**。

- 网关产生并覆盖 X-Trace-Id，下游校验格式、多值拒绝复用、同步 dispatch 绑定并恢复 MDC，Feign 透传。
- 固定 key=value 事件，有限 outcome 与代码定义的操作名；日志不输出原始 URL、SQL 参数、响应体、令牌或异常原文。
- file 远程调用、Mapper 调用与搜索的 Micrometer Timer；file metrics 端点只允许 GET+单值内部令牌，未放入公网网关路由。
- 保留业务补偿/降级语义，通过进程故障、持久化状态、真实 EXPLAIN、线程转储与 HTTP 证明观察点有效。

禁止：MQ/CDC、Seata/XA 平台、后台恢复平台、物理分库、业务重构、完整 tracing/APM 平台、AI，以及 Day29 的分环境/CI/综合架构交付。没有将“复盘”当作降低教材深度的理由。

## 跨天改动与责任

| 文件边界 | 本日改动 | 必要原因 |
|---|---|---|
| gateway 新 RequestTraceWebFilter | 覆盖所有 WebFilter 请求，生成/转发/回显 | 401 与无匹配路由也能关联；不跨线程持有 MDC |
| gateway AuthenticationGlobalFilter | 拒绝日志改为 traceId，去 raw path | 分享 token 可在路径，不泄漏 |
| auth/file/user 新 RequestTraceFilter | 格式校验、响应头、finally 恢复上下文 | 同步 Servlet/Feign 链关联 |
| file FeignTelemetry、user FeignTraceConfiguration | RequestInterceptor；file Capability Timer | 关联延续与每次方法调用观测，不新增重试 |
| file MapperTelemetry | MyBatis query/update 插件 | 应用侧慢 Mapper 及错误计时，不修改参数/事务 |
| file MetricsSecurityConfiguration 与 application.yml | 精准优先安全链及 metrics exposure | 诊断可读但不是普通 JWT 权限 |
| FileSearchService | 新 Registry 参数及完整 search 外层计时 | 区分 ES、MYSQL 成功与 ERROR |
| QuotaCoordinator | 待确认 warning 用 traceId/phase | 不打印 owner/reservation，不改变阶段转换 |
| 两个既有 search 测试 | 手工构造器传 Registry | 保留旧合同，适应显式依赖 |
| 新增测试与 scripts 两文件 | 24 个常规案例、可重复真实演练、只读 SQL 工具 | 防止用直接构造的单测冒充自动装配成功 |

四个 pom 不变，不抽公共 module，不新增依赖，不修改迁移表或旧事务合同。三个 Servlet 类及其同构测试除 package 外一致，教材给完整规范文件与精确生成规则，避免重复三份相同逻辑。

## Checkpoint 2 测试与真实运行

| 本轮执行 | Tests/Checks | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根 clean test | 108 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| auth clean test | 22 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| gateway clean test | 23 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| user clean test | 15 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| file clean test | 49 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| 显式真实 ElasticSearchLiveIT | 5 | 0 | 0 | 0 | BUILD SUCCESS / exit 0 |
| 四进程故障演练 | 28 checks | 0 | 不适用 | 不适用 | AUDIT PASSED / exit 0 |

217 常规 + 5 显式集成，共 222 JUnit；28 检查另计。全部为 10-04 本轮实际运行，file XML 后来 54 是两次运行集合。原始验收脚本只跑根，不替代子模块矩阵。环境为 Microsoft JDK 17.0.20、Maven 3.9.10、Git 2.54.0.windows.1、PowerShell 7、既有本机 MySQL/Redis。

真实四服务相同写入 trace，user 初始化到 auth 的额外调用保留关联。metrics 对匿名/JWT 返回 401，内部 token GET 读到 Timer COUNT=3，网关路径 404。user 进程停止后 reserve 三次 ERROR 且 HTTP 503，DB pending=1；重启后下一写入成功并先 release 旧意图，pending=0。无监听 ES 端口导致 HTTP 200 明确 MYSQL 降级，指标可区分。

真实 MySQL SLEEP 与 HTTP 延迟/超时证明慢调用计时，不把演练阈值 0 当性能证据。SQL EXPLAIN 为 type=ref、key=uk_drive_entries_active_owner_parent_name、Using where; Using filesort；停机/恢复两次估计 rows=1/2。采集本轮 file PID 55740 的真实线程转储，不声称发生死锁或执行 Linux。

详细结果与完整 28 项输出在 `docs/day28/runtime-evidence.md`。原日志与原始线程现场留在协作工作区。所有命令会话已收取退出码，全部本轮应用及 ES 已停止，未结束其他进程。

## 审核发现与修复

| 发现 | 处理 | 复验 |
|---|---|---|
| 演练受既有服务 URI/PORT/Profile 污染 | CLI 固定本次端口、gateway/Feign URI、default profile、禁用 Nacos | 独立复审关闭 P1；最终四进程通过 |
| 清理中一个异常可阻断后续清理与环境恢复 | 每进程 catch，路径/StartTime 校验，原 Process Kill，环境内层 finally | 独立复审关闭 P2；最终正常清理 |
| Actuator 实际 200，PowerShell 解析 vendor JSON 失败 | 根据 Content 是否 byte[] 做 UTF-8 解码 | 保留前两次失败；第三次 28/28 |
| 六个新文件有多余末尾空行 | 独立纯格式提交，不改实现历史 | diff whitespace 检查通过 |

代码只读复审对生产初稿和脚本修复均给出 PASS，未发现未闭合的确定性阻断。它不替代随后真实执行；本轮真实执行补齐自动装配、实际端点与进程清理证据。

## 教材自审（Day02/Day05 对照）

教材为 `阶段二/CloudDrive学习教材_Day28_一致性取舍与链路故障可观测.md`。已完成连续三块写作，不以约 1474 行作为质量通过依据。没有重复旧业务整类，旧接点精准走读，新同构文件用可还原的完整规范代码与包名替换规则。

| 审计项 | 项目源码/项目测试及位置 | 执行者复读依据 |
|---|---|---|
| 前日衔接、真实问题与预测 | 首段、§0、§1 | 点名 FileSearchService/Mapper/LiveIT 与 QuotaCoordinator |
| 一致性/CAP/分布式事务取舍 | §2 | 局部原子性、远程不确定状态、降级语义分开，不贴整体 CP/AP |
| 项目源码走读 | §3–5 | WebFilter、Servlet、Feign、MyBatis、metrics 安全、search/补偿接点 |
| 项目测试逐例走读 | §6 | 24 新案例、真实/替身边界、旧测试回归，不把 MockMvc 当 Actuator |
| 运行与证据 | §7 | 两脚本完整源码、217+5+28、SQL、线程与失败重跑 |
| Debug | §8 九例 | 每例有输入/预测、定位与证据、修复、回归或明确未覆盖 |
| 独立复刻 | §9 | 七项可判定任务，不替用户做结果 |
| 算法/SQL/Java | §10 | LC322、SQL Day15–16 真表 EXPLAIN、finally/MDC/代理/进程 |
| 面试题与简历 | §11 | 两分钟主线、追问、可说与不可说 |
| 验收与未运行限制 | §12 | 三门分离、来源和计数边界明确 |
| 段落密度与末尾输入 | 首段、§3.2、§6.4、Debug7、§13 | 自然段论述；明日唯一输入在全文末尾 |

执行者已重新打开教材及相关源码、测试、日志核对。独立审计者已分段复读全文，逐块核对 15 个完整内联文件与磁盘及 57712b8 一致，并验证 auth/user 四份同构源码/测试的包名替换规则；交叉核对原始 217+5、28 项、Timer、SQL 与线程证据，明确给出教材门 PASS，无必须返工项。其建议已用于澄清 Surefire 依赖 classpath 仍包含测试作用域 JAR，本轮不是生产打包部署验证。提交后的 Final 冻结，最终机器闸门输出记录到新建 acceptance.md。

## Checkpoint Final 验收边界

代码与实际验证已完成。教材、Final、回执须独立复读通过并提交，代码工作区干净后，再执行原始 `verify_day.ps1 -DayNN 28`。此处不预填尚未执行的闸门结果。

保留限制：请求关联不是 span tracing；Servlet 事件是同步 dispatch，未处理异常可能 status=200/failed=true，网关可能 UNSET；无异步 MDC、metrics 长存、P99 或生产压测；Mapper 总耗时非服务端 SQL 时间；脚本不是外部配置沙箱；共享 schema、owner 锁等待、无全链 deadline、无后台补偿时限、存储/元数据非原子、ES 1000 上限与检索语义差异等前日限制均保留。

人工学习门仍待用户闭卷解释、独立复刻、真实错因和题单记录。明日唯一输入是总纲 Day29 微服务验收段②与分环境、密钥、临时数据库 CI；本日不提前实施。
