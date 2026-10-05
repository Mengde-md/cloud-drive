# Day30 受控模型接入

## 范围与选择

唯一增量是LLM的Token、流式、结构化输出、超时与成本，不提前实现RAG、Agent、工具调用、文件读取、向量库或费用账本。用户明确选择DeepSeek + LangChain4j。

| 冻结项 | 当前值 | 验证边界 |
|---|---|---|
| Java / Boot | 17 / 3.2.4 | 独立AI模块，本机编译及实包已运行 |
| SDK | langchain4j-open-ai 1.21.0 | 普通Java集成，不是Boot4 starter |
| JSON依赖 | Jackson BOM 2.22.1 | 只在AI模块优先于Boot BOM，旧五工程未升级 |
| SDK传递依赖 | reactive-streaming 1.21.0-beta31 | 如实保留beta事实，不使用其Flow接口 |
| 模型配置 | deepseek-flash | 供应商别名可能升级，不是权重哈希锁 |
| 正式地址 | https://api.deepseek.com/v1 | 非fixture只能使用此精确地址 |
| 思考 / 格式 | disabled / json_object | 实际SDK HTTP参数已由本地服务捕获 |
| 默认开关 / 密钥 | false / 外部空值 | 无真实模型密钥，也没有云端推理 |
| 默认限制 | 输入4096 UTF-16，输出512 tokens，10秒，2并发 | 不是生产容量规划或全局费用预算 |

官方依据在2026-10-05核对：[模型/计价](https://api-docs.deepseek.com/quick_start/pricing/)、[JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)、[LangChain4j](https://docs.langchain4j.dev/get-started/)。模型别名、计价与SDK升级均需再验证。

## 数据与责任

```text
USER JWT + supplied text + explicit external-processing consent
  -> gateway /api/ai/** (existing edge JWT verification)
  -> ai-service loopback:8084 (own JWT verification, USER required)
  -> validated input (no file id, no download or metadata transaction)
  -> bounded logical slots / workers / queue / application deadline
  -> LangChain4j -> frozen provider, or explicitly marked local fixture
  -> final usage + finish reason + strict typed JSON validation
  -> sync result, or SSE progress then result/error
```

AI服务只接收调用者提供的文字，不证明这段文字来自有权限的云盘文件。处理同意不是脱敏，也不授权自动读取文件。没有DataSource/Redis/Feign/文件服务依赖。禁止将真实秘密或个人资料用于模型验证。

共享JWT沿用HS256、issuer=cloud-drive-learning、正数subject、exp及roles合同。AI再次验签、要求USER，不读取X-User-*身份头；health公开、其他入口拒绝，Spring默认时间容差保留。未升级非对称签名或物理分库。

网关增加第七条静态路由，不修改匿名集合，不暴露/internal，不改变路径。Day29四服务部署不自动启动AI，需独立launcher；Nacos/lb profile未接入AI。

## 结果发布合同

`POST /api/ai/summary` 返回requestId、model、output与usage；`POST /api/ai/summary/stream` 返回真正SSE。输入仅text与allowExternalProcessing，拒绝空白、超长、未同意、畸形JSON和未知字段。@Size是绑定后字符串约束，不是原始body内存限制。

同步与stream共享容量。默认有限worker和ArrayBlockingQueue，满载429；超时同步504，已提交的SSE200以error事件结束。StreamCall.closed避免终态双释放，计时任务及时移除，取消handle异常仍释放槽位。自审修复d2a757f让工作任务出队时先检查closed，排队期间已超时则不联系供应商。

SSE progress只含requestId、receivedChars、validated=false，不发布原始草稿。完整ChatResponse须STOP、无工具请求，且最终usage有效，再经SummaryValidator得到唯一result。错误、截断、缺usage都不发布result；没有终态的EOF不是成功。

严格校验拒绝重复key、尾随JSON、围栏、未知字段、隐式类型转换、控制字符、过深嵌套和超长内容。输出恰好summary/keywords；summary非空至多1000，关键词1–5个、每个至多40，忽略大小写判重、不可修改List。schema不证明摘要事实正确、模型安全或注入完全防御。

错误码为自有固定小合同：400 AI_INPUT_INVALID、429 AI_BUSY、502 AI_OUTPUT_INVALID/AI_USAGE_INVALID/AI_UPSTREAM_FAILED、503 AI_DISABLED/AI_INTERRUPTED、504 AI_TIMEOUT。401/403由Security处理，错误体不必具有统一JSON形式。请求/响应SDK日志关闭，不回显provider异常或输入。

## Token与成本

最终供应商usage必须包含非负input/output/total，且使用long验证input+output=total。字符数、SSE块数、token与关键字不可互换；不把缺usage猜成0。

AiCost用BigDecimal按2026-10-05峰时cache-miss快照估算：输入0.3美元/百万、输出1.2美元/百万。100/50/150得到0.0000900000美元。未解析cache/峰谷折扣，估算不是真账单、不跨日期承诺上界。fixture有明确标志，模拟usage不是实际tokenizer或费用。

同步maxRetries=0，stream没有应用层重放或补问；HTTP500请求计数已验证只加1。超时取消只停止本地等待或尝试取消传输，不保证远端停止计算/退费。首次回调前没有handle，仍依赖底层HTTP期限。没有最终usage保留unknown。

## 部署与复验

先按Day29构建四服务并配置共享外置JWT。独立AI包位于`ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar`。使用PowerShell7的`deploy/Start-CloudDriveAi.ps1`，固定loopback8084、正式URL和非fixture；使用输出run目录调用原`Stop-CloudDrive.ps1`，只停止所属PID/路径/启动时间一致的进程。

默认AI关闭仍健康，认证请求503 AI_DISABLED已真实验证。启用需外部CLOUDDRIVE_AI_ENABLED=true与CLOUDDRIVE_AI_API_KEY，禁止把key放命令历史、聊天、教材或仓库。不得用health200声称真实模型可用。

`scripts/day30-verify-ai-jar.ps1` 验证manifest/SDK/Jackson与测试依赖排除；`day30-packaged-smoke.ps1` 启动真实AI/gateway包及本地协议服务，验证7项后只停持有进程、恢复环境。API资产在`docs/day30/api.http`。运行记录见[Day30证据](../day30/runtime-evidence.md)。

## 剩余风险

极慢消费者阻塞send临界区的严格端到端期限、巨大HTTP body、压测、多节点容量/费用、真实模型质量/费用均未验证。既有HS256、共享schema/DDL账号、请求驱动补偿、存储/数据库非原子限制仍在。

GitHub hosted CI、Docker runtime、Nacos/lb AI、Linux、公网TLS/HA未运行。CI新增无外部key的AI clean verify；本机等价执行不能冒充托管Ubuntu/Redis7.2结果。Day31能力尚未实施。
