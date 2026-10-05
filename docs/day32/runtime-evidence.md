# Day32 运行证据

实际执行跨2026-10-05/06，Asia/Shanghai。本文件只记录已发生事实，原闸门结果在提交后新建acceptance.md，绝不预填。

## 环境与唯一范围

Microsoft JDK17.0.20、Maven3.9.10、Git2.54.0.windows.1、PowerShell7。代码入口2f23e8d，材料814f964，原材料38dirty、34保护hash作为基线；实现81f47dd。仅Day32 Agent/Function Calling/schema/MCP，未启动后续工作，未写用户练习仓库。

新增MCP2.0.1 Jackson2 artifact，保持Boot3.2.4/LC4j1.21.0/量化embedding1.21.0-beta31/AI局部Jackson2.22.1。Maven依赖实际下载和编译成功；未使用默认Jackson3聚合器，没有整体框架升级。

临时MySQL8.0.42/13306、Redis3.0.504/16379在本轮新建专属datadir和凭据；不读个人服务密码。原五工程clean verify串行使用该实例，AI测试不依赖数据库。清理事实在验收后记录，不提前宣布。

## Maven 与脚本

| 本轮最终执行 | 唯一数量 | failures | errors | skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根clean verify | 111 | 0 | 0 | 0 | BUILD SUCCESS |
| auth clean verify | 22 | 0 | 0 | 0 | BUILD SUCCESS |
| gateway clean verify | 24 | 0 | 0 | 0 | BUILD SUCCESS |
| user clean verify | 15 | 0 | 0 | 0 | BUILD SUCCESS |
| file clean verify | 49 | 0 | 0 | 0 | BUILD SUCCESS |
| AI最终clean verify | 138 | 0 | 0 | 0 | BUILD SUCCESS，51.731s |
| day29配置合同 | 28 checks | 0 | 不适用 | 不适用 | PASS |
| 旧六JAR与AI新合同 | 6+1 | 0 | 不适用 | 不适用 | PASS |
| 最终Agent/MCP实包 | 14 checks | 0 | 不适用 | 不适用 | exit0 |
| 同AI包旧RAG实包 | 12 checks | 0 | 不适用 | 不适用 | exit0 |
| 同AI包摘要/SSE实包 | 7 checks | 0 | 不适用 | 不适用 | exit0 |
| LC295独立AI演示 | 9cases+empty guard | 0 | 不适用 | 不适用 | Java17实编译/运行PASS |

按各POM XML实际build目录解析Surefire，六工程359=221+138，AI138=旧90+新48。新类Catalog13/Session4/Service4/HTTP12/Backend12/STDIO3；fixture/JWT/probe不是JUnit，不重复计数。脚本check和实包33不加入JUnit。

AI第二轮已有138/0/0/0，新增非JUnit实包探针后最终完整clean verify仍138/0/0/0。原AI老墙钟断言不改，串行最终运行通过，未证明生产负载SLA。

## 真实协议与包运行

Agent fixture是JDK回环HTTP固定响应，实际LangChain4j SDK往返tool_calls/tool_call_id；usage100/50/150是合成并标protocolFixture=true。真实安全链、ONNX、导入、搜索、引用校验、delete/revision不替身。无真实DeepSeek Key调用、语义质量或账单。

MCP测试通过官方StdioClientTransport/McpClient实际启动Java子进程，initialize/listTools/callTool；生成AI关闭仍能检索。实包探针进一步用java -jar最终AI --mcp-stdio完成同协议，不是只看Surefire classpath。协议2025-11-25、server clouddrive-readonly-rag/day32，业务两工具、无resources/prompts；SDK内置logging存在。

HTTP与MCP两路径共享schema但HTTP Agent直接读本地索引。带外JWT绑定tenant，额外owner/schema、未知delete、GUEST、另一USER、重定向trap、超限body均有实际合同。stdout不混Spring banner，stderr不输出token/资料；WindowsSDK关闭子进程时code1告警保留，不将关闭码冒称0或请求失败。

最终AI与prod网关JAR的33项=14Agent/MCP+12RAG+7summary/SSE。RAG12包括真实AI重启后资料空。首轮未完成的实包不计入这33项。

## 保留的失败与纠正

| 实际失败 | 定位与修改 | 最终证据 |
|---|---|---|
| 首轮testCompile clientInfo双字符串不匹配 | SDK2.0.1要求Implementation；只改测试调用，没升级SDK | 后续完整138全绿，首轮未执行JUnit |
| 第一次实包网关JAR无法访问 | 与gateway clean竞争；新包装脚本加文件前置，等待构建结束再串行 | 最终同包14成功 |
| 旧summary启动ragIndex布尔转换[]失败 | 上一脚本恢复空环境值；显式RAG/Agent=false，不改旧生产/脚本/阈值 | 同最终AI包7成功 |
| 辅助SQL guessed deleted列1054 | 重新读V7真实deleted_at，改只读EXPLAIN | exit0，ref / Using where; Using filesort |
| 教材自审MCP手工启动前置不够直接 | 补§21环境名、带外JWT、RAG后端、STDIO客户端/EOF与重启责任 | 终稿再次重读，不通过关键词补丁 |

没有带着红测试写最终教材或提交源码。第一次编译失败与后两次运行启动失败是各自发生事实，不用最终成功覆盖历史，也不重新累加测试数量。

## 日志定位与哈希

日志位于执行工作目录，不含个人凭据；从中文终端输出可见Maven编码乱码，本机原文件和ASCII断言/结果保留。源资产与证据不依赖乱码路径识别。

| 工作目录文件 | SHA256 |
|---|---|
| day32-ai-first.log | 18DFD13425E300E9D1805F02ED740FF0AB113B82DED5F1FCF57EA2273312CF90 |
| day32-ai-final.log | F09D8778A583F1592FAEBDDE4D1F8E8377794EA7DEA578E1998935E08CD7F297 |
| day32-five-ci.log | 2B2BF6F60FE0AA5A81A885D496E29CD81ED56A9F1C2FBBA83690121BD7C36E86 |
| day32-packaged.log（失败） | 703803F8A141A18222BBA67830C0AF5D4EAB53668341714849827A04D03926E2 |
| day32-packaged-final.log | E8FF2C971414A0CD2677547A983359D3DE431FD8444A7F209E82861DCC687C65 |
| day32-rag-packaged.log | FF4E0D2D54A72ED70101629770AF04BF996C997C1389C1E8DA4E4529C58FBFD1 |
| day32-summary-packaged.log（启动失败） | 7EB70257593DA06F682A3DDDA54A9D260D4FC514F645237F5CA74B08F8DA61A6 |
| day32-summary-packaged-final.log | 6BA85421D03771A53602F4EF440538FE0AADF44A9ADE5CEBBAA266D68929F3E9 |
| day32-sql.log（EXPLAIN失败） | E8EF523F2731591BB35064D4926355D57797DE8201387113C58CDAE1E2F5AB77 |
| day32-sql-final.log | F10B04D966DF097B77EE45C7C5D996822C6DD2A3777F4911A4119D8304F0CF77 |

## 最终实测包 SHA256

| 实际build-output包 | SHA256 |
|---|---|
| root web | 74599E827A4B114423258C249529E9E035C4CC7829243B8DC17E606955DC9D98 |
| root migration | 8ABE9078AC4F32C0C3E7057C6A967F1D0D08298076986F016F8DB722940D0DDD |
| auth | 906C6C272FA284EC083C3F55D32B25875FCAA7213E6FFDB0CD46299FBAB7175B |
| gateway | 858D68F3B3500832C22D48D9A70C0DB8B6FE15DB43F8AFE6630F6BBC4598AADC |
| user | 9DF68FBAF7FC418D50E93F13D36EBD1C91E65A214BB942B98ABC7755EC4D973B |
| file | 5F66D9EC987AF15BF87BB2EA8BF16CE0A25E1CC715C9EE4330DF2055A842965D |
| AI | 67A1CB668BD2E1DDF84C03158F6C2781D2665264C92CB72200FEC31B2120F8CF |

两ONNX资源实际哈希不变：model AFDB6F1A0E45B715D0BB9B11772F032C399BABD23BFC31FED1C170AFC848BDB1，tokenizer DA0E79933B9ED51798A3AE27893D3C5FA4A201126CEF75586296DF9B4D2C62A0。没有重新跑前日英文6正4负语义小集或扩展其生产质量结论。

## SQL 与后续验收

本轮只读root history10/success1、user history1/success1；EXPLAIN实际字段display_name/deleted_at，owner7过滤，所选uk_drive_entries_active_owner_parent_name，type ref、key_len8、rows估计1，Extra Using where; Using filesort。没有新索引、RAG表或模型SQL执行器。

教材三块及独立重读、源码与测试核对通过后写Final与精简回执。两根实测JAR已按哈希备份，原闸门clean test若移除，仅向实际build-output恢复缺失且相同验证字节；不从target猜路径、不覆盖冲突字节、不重新打包冒称旧实包。原门、清理、保护结果提交到新acceptance记录。
