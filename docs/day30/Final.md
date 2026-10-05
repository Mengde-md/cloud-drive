# CloudDrive Day30 Final

## Checkpoint 0 接班与G0

读取交接手册、总纲Day30/31及并行能力轴、Day29 Final/acceptance、部署/POM/配置/真实测试。接班代码HEAD5001abc，status空；材料HEADf0cd514，原有38项dirty状态保留。当前用户明确授权制作Day30，模型供应商答复为DeepSeek + LangChain4j；授权不等于人工学习门通过。

唯一输入：Day29四服务JWT、网关、分环境、可执行部署与临时依赖验收能力。

唯一增量：LLM接入的Token、流式、结构化、超时与成本；冻结模型供应商与Java框架，输出必须本地校验。

禁止：RAG、embedding、向量库、Agent、工具调用、自动文件读取、费用账本/新迁移、物理分库、整体框架升级；不改总纲/手册/原verify_day/已提交Final，不修改用户复刻仓库。

G0已回显并遵守：工程门与教材门分开，分块不是收工，红测试先修复，原始脚本不能证明教材质量，学习门用户独立记录。

## Checkpoint 1 实现与改动边界

实现722e176 `feat(day30): add guarded DeepSeek summaries and validated streaming`；自审修复d2a757f `fix(day30): skip expired streaming work before contacting provider`。25份实现资产=22份新增+3份旧文件必要差异。

| 文件边界 | 本日责任/改动 | 原因 |
|---|---|---|
| ai-service/pom.xml / application.properties / AiServiceApplication | 独立Java17/Boot3.2.4非聚合模块；普通LC4j1.21.0、局部Jackson2.22.1；默认关闭与loopback | 隔离模型依赖，不整体升级五工程 |
| AiSettings / AiConfiguration / AiFailure | 参数范围、正式/fixture端点成对冻结、启用才建客户端、key脱敏、小错误合同 | 不给调用者换地址或把真实调用伪标fixture |
| DeepSeekClient | 真实SDK同步/stream、JSON mode、non-thinking、max_tokens、同步retry0、SDK日志关闭 | 让成熟框架承担HTTP/SSE协议，避免隐含重复费用 |
| SummaryValidator / AiCost | STOP/无工具、严格typed JSON/重复/大小/不可变结果、最终usage与BigDecimal快照 | 上游200不等于成功，字符不等于token，估算不等于账单 |
| SummaryService | 共享有限slots/workers/queue、deadline/终态/取消、progress与result区分、过期排队不发请求 | 有限等待与失败恢复，自审补上晚调用漏洞 |
| AiSecurityConfiguration / SummaryController / AiExceptionHandler | 本地JWT复验与USER，输入同意/尺寸，固定小错误 | 拒绝伪造身份及无同意消费，原文不回显 |
| SummaryValidatorTest / AiSettingsTest / ProtocolFixture / AiHttpContractTest | 22+4+12案例，真正本地SDK HTTP/SSE与可控故障 | 不用生产假客户端冒充模型接入；fixture不是模型 |
| deploy/Start-CloudDriveAi.ps1 | 独立隐藏启动、固定8084/官方非fixture、所属记录与失败清理 | 复用原Stop，不杀任意Java |
| scripts/day30-verify-ai-jar.ps1 / protocol-fixture / packaged-smoke | manifest依赖、明确协议模拟、真实两包7项及环境恢复 | 静态包与实际运行分别证明 |
| docs/day30/api.http | 同步/stream/不同意负例，仅token占位 | 可手工复验，不提交真实凭据 |
| gateway/src/main/resources/application.yml（旧） | 新增第七条/api/ai/**路由及静态ai-uri | AI独立入口，不改变匿名集合或暴露internal |
| GatewayRouteTableTest.java（旧） | 明确七路由预期、新AI URI/namespace断言、测试专用地址 | 保留旧安全/无冲突断言，避免外部环境污染 |
| .github/workflows/day29-ci.yml（旧） | 五工程后追加无外部key的AI clean verify | fixture验证不依赖生产Secrets；原固定actions/最小权限不变 |

共享HS256、物理schema、旧补偿与存储/DB非原子限制保持。AI没有DB/Redis/文件/Feign或聊天记忆，无新业务表与迁移。模型配置deepseek-flash/正式https://api.deepseek.com/v1冻结，别名权重及价格不保证不变；SDK传递依赖含beta，未冒称全树稳定。

架构与部署细节：[受控LLM边界](../architecture/day30-guarded-llm.md)。完整运行记录：[runtime-evidence.md](runtime-evidence.md)。

## Checkpoint 2 真实测试与运行

| 本轮执行 | 数量 | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根clean verify | 111 | 0 | 0 | 0 | BUILD SUCCESS |
| auth clean verify | 22 | 0 | 0 | 0 | BUILD SUCCESS |
| gateway clean verify | 24 | 0 | 0 | 0 | BUILD SUCCESS |
| user clean verify | 15 | 0 | 0 | 0 | BUILD SUCCESS |
| file clean verify | 49 | 0 | 0 | 0 | BUILD SUCCESS |
| AI最终clean verify | 38 | 0 | 0 | 0 | BUILD SUCCESS / exit0 |
| 配置合同 | 28 checks | 0 | 不适用 | 不适用 | PASS |
| 可执行包合同 | 6旧+1AI | 0 | 不适用 | 不适用 | PASS |
| 实包AI/gateway/SDK/SSE | 7 checks | 0 | 不适用 | 不适用 | PASS / exit0 |
| 生产默认关闭/所属Stop | 2 checks | 0 | 不适用 | 不适用 | PASS / exit0 |
| 独立LC139 | 9 cases | 0 | 不适用 | 不适用 | PASS / exit0，不计JUnit |

221+38=259唯一JUnit，原verify_day只重跑根111；重复执行不累加。独立算法和9项运行断言不计JUnit。六项目surefire XML已重新汇总一致。

2026-10-05，Microsoft JDK17.0.20、Maven3.9.10、Git2.54.0.windows.1、PowerShell7。五工程使用本轮新建MySQL8.0.42/13306与Redis3.0.504/16379，凭据新生成而非个人默认。只读SQL最近root history10/1、user history1/1。

HTTP测试捕获实际SDK参数；非法JSON/终止原因/usage均fail closed，未认证/无同意在模型HTTP前拒绝。锁存器证明供应商完成前已有progress；stall/busy/disconnect/queued-timeout后可恢复。模型500不重试、不返回异常私有canary；SDK与应用日志不回显测试prompt/key。

最终两包java -jar通过网关合法摘要和SSE、匿名401、AI直连伪头401、非法输出502、usage150和fixture估算。AI SHA256=47C536DACE4554385F761ED6CB99886AAAF3E12CA85B4200B121965A4B1CFD71；gateway=228FDA28D55F43E6AB53A0A01EB8F50D3BAF3E99D33FDFDFEA7BF4553A0D1167。默认无API key健康，已认证请求503 AI_DISABLED；原Stop真实退出所属AI。未调用DeepSeek云端，模拟usage不是费用。

## 先列缺口，再修复复审

| 缺口/失败 | 定位与修复 | 真实复验 |
|---|---|---|
| PS条件-or换行导致语法错 | 运算符移至行尾，不追连带括号错误 | 四脚本ParseFile PASS及真实JAR合同 |
| AI健康但prod gateway失败 | 缺auth/file URI；smoke显式loopback，不删除prod必填约束 | 完整7项重跑，再对自审修复包7项通过 |
| fixture标志可与官方URL矛盾 | AiSettings要求标志与本地端点成对 | 配置负例进入最终38 |
| 超时排队stream稍后仍发模型HTTP | 新测试真实1/1/0/0红；出队先检查closed | 最终38/0/0/0，provider不增加且下一请求200，实包9项复验 |
| 教材第一合法例被表述为错误 | 改“后三个反例”，同步最新源码/测试/hash/计数 | 22资产逐字一致、全文及修改段复读 |
| 辅助SQL表名/算法contains(null)错 | 读取实际user history配置；stream.anyMatch | 只读SQL10/1与1/1；独立9例通过 |

红测试已修复，没有降低断言；失败原证据保留。全部由同一执行者自审，不称第三方独立审计。

## 教材自审（Day02/Day05 对照）

教材：阶段三/CloudDrive学习教材_Day30_受控DeepSeek接入与结构化流式校验.md。连续三块完整写作，1773物理行；行数仅用于异常预警，不作为质量结论。只内联本日22份新增资产，旧三文件只展示必要差异，不重复前日整类。

| 自审项 | 项目源码/项目测试与教材位置 | 核对事实 |
|---|---|---|
| Day02问题先行/Day05受控执行链 | 首段、§1–4；对照两份样板的问题/责任/竞争证据段 | 点名Day29部署/CI/JAR缺口，学习者先预测200/片段/超时收费 |
| 前日衔接、范围与资产 | 首段、§2–4 | 输入→网关→AI→SDK→校验清晰，禁止次日能力 |
| 项目源码走读 | §5–12 | 配置、客户端、usage/输出、共享容量、JWT、Controller/异常逐段说明 |
| 项目测试走读 | §14–16 | Validator/Settings/HTTP完整测试与fixture，逐项说明断言证明和不能证明的事实 |
| 完整新增代码 | 22个完整资产块，3个旧文件差异§13 | 全部从磁盘重新读取、逐字相等，修复源码/测试已同步 |
| 真实测试/部署矩阵 | §17–21 | 259、28、7包、9运行分计，真实两包hash/默认关闭，不伪称云端模型 |
| Debug失败闭环 | §22的11例 | 预测/触发/证据/定位/修复/回归；fixture负例与真实执行错误明确区分 |
| 独立复刻 | §23–24 | 7项可预测/可判定任务，输入拒绝与已消费输出失败分开 |
| 算法/SQL/Java | §25–27 | LC139独立9例、只读两history、record/Future/中断/终态；不代填用户练习 |
| 面试与简历 | §28 | 十问，1/5分钟事实模板；不伪造质量/性能/个人独立经历 |
| 质量、学习门、唯一末尾输入 | §29–30与末节 | 原闸门不代替教材门，人工四项待用户，次日输入唯一且位于末尾 |
| 段落密度 | 首段、§7/10、§16、Debug2/11 | 连贯因果自然段，无拆句凑行或无解释代码堆砌 |

Final填写前已重新打开全文、生产源码和三类测试/fixture，逐项比较输入、责任、观察证据、反例及修正；源码/内联检查22/22通过，并复读所有修改段。样板对照的是问题驱动深度而非复制字数。教材自审结论PASS，无必须返工缺口；非第三方结论，非关键词机器勾选。

## 面试证据

可说：独立AI进程承接既有JWT网关，用LangChain4j真协议、有限并发/等待、本地严格输出和usage估算；流式只发进度，结果必须全量校验；259JUnit和9本地运行断言通过。不能说真实DeepSeek调用/费用、模型准确率、生产压测或RAG/Agent完成。可讲自审发现的排队超时晚请求，以及红绿测试如何界定费用取消限制。

## Checkpoint Final 验收边界

工程实现和教材复审已完成；本Final/回执提交、代码status空后执行原始verify_day.ps1 -DayNN 30。本Final提交后冻结，实际机器结果和依赖清理只进入随后新建acceptance.md，当前不预填PASS。

未运行真实DeepSeek推理/账单/质量评测、GitHub hosted CI、Docker runtime、Nacos/lb AI、Linux、生产压测/TLS/HA。显式ES5项与四业务故障演练本日未重跑。不承诺极慢消费者/巨大body、分布式额度、全局账本、完整防注入、远端取消退款；首次handle前仍依赖HTTP期限，HS256/shared schema/旧非原子边界保留。

受保护总纲、手册、原闸门和Day01–29 Final应逐哈希比对；材料原38项dirty继续保留，用户复刻仓库不写。人工学习门仍需用户闭卷口述、小复刻、真实错因、LC139或题单记录，不声称已学会。

## 明日唯一输入

Day31仅从受控模型入口进入RAG：解析、切块、embedding、明确向量存储选型、检索、引用、拒答与评测。本日不提前制作，等待用户开启。
