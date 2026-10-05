# CloudDrive Day32 Final

## Checkpoint 0 接班与 G0

已读交接手册、总纲Day32/33、Day31 Final及acceptance、原verify_day、当日AI真实源码/测试及题单LC295；对照Day02问题先行和Day05共享执行责任。进入时代码2f23e8d/status空，材料814f964/原38dirty，34受保护hash锁定。用户“开启下一项”授权进入本日，不把人工学习门代填为通过。

唯一输入：Day31租户RAG、真实本地ONNX、严格引用/拒答/revision及Day30受控DeepSeek + LangChain4j、JWT、期限/容量/usage。

唯一增量：Agent / Function Calling / tool schema / MCP，只读目录与检索、有限本地执行循环及真实官方SDK STDIO。

禁止：任意shell/自动路径读取、破坏性或其他CRUD工具、新DDL/持久化向量服务、整体框架升级、远程MCP/OAuth、完整后续评测/观测/注入工程。不改总纲/手册/原门/已提交Final，不写用户练习仓库、不提交原有材料改动。

G0：工程门和教材门分列；连续三块不是停工理由；先修红编译/测试/运行，再写终稿；无关键字补丁/假云端调用/假人工学习记录。本轮同一执行者独立一步自审，无第三方或子代理审计。

## Checkpoint 1 实现与改动责任

实现81f47dd `feat(day32): add bounded readonly agent and real MCP stdio tools`，24个确切文件=19新增资产+5旧文件必要差异，全量测试与最终实包通过后提交，单一意图。该提交与clean status不等于原闸门通过。

| 资产 | 本日责任 |
|---|---|
| AgentToolCatalog | 唯一两schema、LC4j/MCP共享、名称/字段/类型/重复键/尾随/长度/控制字符本地拒绝 |
| AgentToolSession | owner构造绑定，只读list/search、12000字符结果、最近retrieval/revision |
| AgentService | 显式consent、空租户零调用、3模型/2工具/每轮1、ID唯一、共享整轮期限、最后旧Validator与revision |
| AgentConfiguration / AgentController | 默认关闭Bean、现有JWT USER subject、typed请求不接收owner |
| McpRagBackend | 显式回环origin与带外JWT、两个固定RAG路径、Redirect.NEVER、16000字节、错误正文脱敏 |
| CloudDriveMcpServer | 官方SDK STDIO initialize/list/call、Jackson2、schema输入验证、两工具注册、stdout协议/stderr日志、EOF关闭 |
| 六JUnit类+fixture/JWT/probe（九份T资产） | 新48案例、真实HTTP/SDK/ONNX/Java STDIO、非JUnit14项JAR探针分账 |
| 两脚本+api.http | 新Agent/MCP包合同与测试classpath实包入口、四请求前置/失败例 |
| ai-service/pom.xml（旧） | 仅mcp-json-jackson2:2.0.1；保留Boot3.2.4/Java17/LC4j1.21.0/ONNXbeta31/局部Jackson2.22.1 |
| AiServiceApplication.java（旧） | 恰好单参数--mcp-stdio走独立main，普通HTTP启动不变 |
| DeepSeekClient.java（旧） | 独立工具ChatModel，nonthinking/maxTokens/timeout/retry0/禁日志沿用，旧summary/stream JSONmode不变 |
| SummaryService.java（旧） | 泛型execute承载整轮Callable，slot/workers/deadline共用，逐步usage与AiFailure状态解包 |
| application.properties（旧） | 增CLOUDDRIVE_AGENT_ENABLED默认false，不改变RAG/AI/JWT语义 |

HTTP Agent直接读本地RagIndex，不经MCP；MCP独立桥接同RAG HTTP后端，共享schema不是同一调用路径。没有新SQL/事务/文件读取能力，网关旧/api/ai/**无需新增路由。模型提议不是执行权，readOnlyHint不是授权，外部处理同意不是破坏审批。

架构：[day32-readonly-agent-mcp.md](../architecture/day32-readonly-agent-mcp.md)，事实：[runtime-evidence.md](runtime-evidence.md)。

## Checkpoint 2 真实验证

| 本轮最终执行 | 数量 | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根clean verify | 111 | 0 | 0 | 0 | BUILD SUCCESS |
| auth clean verify | 22 | 0 | 0 | 0 | BUILD SUCCESS |
| gateway clean verify | 24 | 0 | 0 | 0 | BUILD SUCCESS |
| user clean verify | 15 | 0 | 0 | 0 | BUILD SUCCESS |
| file clean verify | 49 | 0 | 0 | 0 | BUILD SUCCESS |
| AI最终clean verify | 138 | 0 | 0 | 0 | BUILD SUCCESS / exit0 |
| 配置 / JAR包 | 28 / 6旧+1AI | 0 | 不适用 | 不适用 | PASS |
| 最终同包Agent-MCP / 旧RAG / 摘要-SSE | 14+12+7 | 0 | 不适用 | 不适用 | 三脚本exit0 |
| LC295独立AI演示 | 9cases+empty guard | 0 | 不适用 | 不适用 | Java17实编译/运行PASS |

六工程唯一359=221+138，AI138=旧90+新48；新六类13/4/4/12/12/3，重跑不累计。按每POM解析实际build-output及SurefireXML汇总，避免相对目录误数。脚本28和实包33不加JUnit，非JUnitprobe也不加测试数。

实际环境MicrosoftJDK17.0.20/Maven3.9.10/Git2.54.0.windows.1/PowerShell7；临时MySQL8.0.42/13306、Redis3.0.504/16379及本轮独立datadir/凭据。只读SQLroot10/success1、user1/success1；真实EXPLAIN owner7 + deleted_at IS NULL，ref、Using where; Using filesort，不声称新增索引或性能优化。

真实JWT、ONNX、SDK tool_calls/tool_call_id、官方STDIO子进程initialize/listTools/callTool及java -jar --mcp-stdio。协议fixture合成固定回答/100+50usage，未调用真实DeepSeek、未测真实决策/答案语义/账单。实际协议2025-11-25；SDK内置logging capability存在，不冒称只有tools字段。

最终AI SHA256 67A1CB668BD2E1DDF84C03158F6C2781D2665264C92CB72200FEC31B2120F8CF；gateway 858D68F3B3500832C22D48D9A70C0DB8B6FE15DB43F8AFE6630F6BBC4598AADC。旧RAG12含实际重启空库，摘要/SSE7同一最终AI包复跑。七包与ONNX资源哈希见运行事实；原门clean将删除的两根包已备份实际已验证字节。

## 先列缺口、修复、再复审

| 首次缺口/失败 | 修正 | 复验及不扩大结论 |
|---|---|---|
| MCP clientInfo签名不符，首轮testCompile失败 | 改Implementation对象，独立JWT测试helper避免旧fixture静态耦合 | 后续138全绿，首次未执行JUnit |
| 首轮网关JAR被并行clean移除 | 新脚本前置存在检查，构建结束后串行实包 | 最终14成功，不改写初次失败 |
| 旧摘要接到空RAG布尔变量，启动绑定失败 | 当前进程显式RAG/Agent=false，不改旧类/脚本/断言 | 同包7成功，默认false不等于接受空字符串 |
| SQL猜deleted列1054 | 读V7后仅修只读查询为deleted_at IS NULL | 真实exit0/ref/filesort，不新增DDL |
| 教材MCP手工运行前置不够直接、一个标题异体字 | 补§21JWT带外/固定origin/RAG后端/STDIO客户端/EOF/重启，改为“实际” | 重新打开终稿检查，没有用行数或关键词代替内容 |

预防检查包含重复ID、错误finish/过长工具消息、输出上限、越权额外字段、单轮多工具、未知删除、伪引文、usage缺失、429/504恢复、生成中DELETE409、MCP禁redirect/正文上限。没有放宽旧90断言，没有带红测试提交或写终稿。

## 教材自审（Day02/Day05 对照）

教材：阶段三/CloudDrive学习教材_Day32_受控Agent工具调用与MCP协议.md。三块连续完成；19新增完整资产各内联一次、五旧必要diff。1829物理行仅作异常预警，不当作质量结论。

| 复读项 | 实际位置 | 核对结论 |
|---|---|---|
| Day02问题先行/Day05责任与反例 | §1–7、§10/12、Debug6 | 从固定RAG缺口推到权限、次数、共享执行；先预测后解释 |
| 唯一输入、增量、禁止 | §1–5 | 两路径点名，未暗称HTTP经过MCP或实现写工具 |
| 项目源码走读 | §8–14 | 七新生产类+五旧diff；构造依赖、输入输出、状态异常及不负责事项 |
| 项目测试走读 | §15–18 | 六真实JUnit+fixture/JWT；Mockito、真实SDK/ONNX与合成回答分清 |
| 完整代码与测试矩阵 | §6/7/16–18 | 独立重读当前19资产与5diff，逐字各一次；不是复制前日整类 |
| API/实包/运行命令 | §19–21 | 四请求逐项前置/成功失败、重启责任、官方client与最终JAR |
| Debug五联闭环 | Debug1–9 | 输入/预测/责任/观察/修正回归，编译/运行/SQL失败均保留 |
| 独立复刻、算法SQLJava | §30–33 | 越权/时序证据；LC295两堆9+empty；真实SQL字段与取消边界 |
| 面试与简历 | §34 | 十问及1/5分钟，可讲工程、不虚报语义/账单/个人学习 |
| 三门与末尾输入 | §35/末章 | 人工四项待用户，次日仅末章一次 |
| 段落密度 | §1、§10、§17、Debug6 | 连贯因果自然段；没有短句拆行、重复整类或核心内容放结尾后 |
| 内容自审修正 | §21 | 增直接MCP启动前置后再次重读，非关键词补丁 |

Final填写之前已独立一步重新打开完整终稿、19资产、五旧真实文件/差异及全部新测试，重新检查引用/身份/循环/SDK/资源/失败责任。修正后的教材内容自审PASS，无当前唯一范围内必须返工的新缺口；一致性扫描仅辅助防过期，机器格式扫描不能替代自然段和链路判断。本次同一执行者自审，不冒称第三方。

## 面试证据与剩余边界

可讲绑定JWT的只读工具执行器、严格参数/关联ID/有限循环、逐步usage、旧RAG引用/版本、真实官方STDIO及同包回归、359JUnit与33实包断言。不讲云端规划成功率、真实账单、中文生产质量、生产Agent自动修改网盘或用户已学会。

未运行真实DeepSeek/语义质量/费用、远程MCP/OAuth、跨平台/生产负载、完整防注入/观测评测、向量持久化、多节点、Docker/Linux/托管CI、实时ES/四业务故障演练。继承易失索引、子串quote非蕴含、revision不能撤回已发送证据、native无硬取消、旧墙钟负载敏感；MCP ofInputStream慢body硬deadline未证明、带外JWT受本机账户信任边界。Windows官方client关闭STDIO可能记录code1告警，协议成功与无残留分开，不宣称关闭码0。

## Checkpoint Final 原闸门前状态

代码实现/全量回归/最终实包与教材自审闭合。此Final、架构、事实、教材和五行回执提交后冻结Final，工作树干净再实际运行未修改verify_day.ps1 -DayNN32；原门及清理事实仅新建acceptance.md记录，本处不预填PASS。

保护核对34旧hash（含已批准重冻结Day31 Final）、材料原38状态，保留用户已有改动；只停本轮自有进程、保留个人MySQL80/Redis。人工闭卷口述、小复刻、错因、LC295/越权题单待用户，不把AI代写或工程PASS记为学习完成。

## 明日唯一输入

Day33仅在用户明确开启后从受控Agent工具链继续评测、观测与失败/注入防护；本轮未提前实施。
