# Day32：受控只读 Agent 与 MCP STDIO

## 范围与两条入口

唯一增量为 Agent、Function Calling、工具 schema 和 MCP。实现提交81f47dd，24个确切文件；Day31 RAG、引文、revision及Day30共享模型容量为输入。无新DDL、CRUD工具、shell、自动文件读取、持久化向量库、远程MCP/OAuth或整体框架升级。

HTTP Agent直接使用本地RagIndex，不经MCP环回；独立MCP服务通过只读HTTP桥访问既有RAG，两者共享唯一AgentToolCatalog。不要把共享schema描述成HTTP模型已经通过MCP执行工具。

```text
JWT -> gateway -> AgentController -> AgentService
  -> SummaryService.controlled(whole loop)
  -> actual LangChain4j SDK -> proposed tool call
  -> Catalog -> constructor-bound owner Session -> local RAG
  -> matching tool_call_id -> next SDK call
  -> RagAnswerValidator + requireCurrent -> result

official MCP client -> SDK STDIO -> CloudDriveMcpServer
  -> Catalog + fixed McpRagBackend routes
  -> out-of-band Bearer -> RAG HTTP USER authorization
  -> same live tenant index
```

## 工具与授权

| 工具 | 输入 | 真正权限与动作 |
|---|---|---|
| rag_list_documents | 空对象 | 当前JWT owner的已导入文档元数据；不更新引用证据 |
| rag_search | 唯一query字符串 | 当前owner的既有ONNX检索；只保留最近snapshot |

两schema禁止额外字段；Catalog再严格拒绝重复键、尾随JSON、错误类型、blank/控制字符、query超过512、arguments超过2048。长度由代码强制，schema描述不冒称供应商保证。Session工具输出上限12000字符；工具调用ID只能是安全字符1–64且本轮唯一。

JWT subject由既有安全链验签/issuer/有效期/正long与USER role验证后绑定；Controller不接收ownerId。MCP token仅由操作者经启动环境提供，模型schema、tool args、messages和命令行args都不接收它。后端验签，本地bridge语法校验不冒称完成授权。

外部处理同意为HTTP Agent必需的显式true；不代表读取他人资料，更不代表破坏操作审批。没有注册任何写工具，readOnlyHint只是描述而不是安全执行器。

## 受控循环与输出

每请求新建局部history/session/usage/ID集合；最多3模型调用、2工具、每轮恰好1提议。第三模型仍提议工具先拒绝而不执行第三工具。无资料本地NO_EVIDENCE零调用；已有资料也可模型REFUSED，但该次有效usage照常记录。

SummaryService以同一Semaphore/worker/Future包住整个loop；内部toolChat不二次acquire。旧summary/RAG/SSE共享容量。模型步骤前后和工具前后检查中断，504取消后不继续下一轮；native或供应商硬取消未证明。

工具模型沿用冻结DeepSeek provider/model、nonthinking、token上限、retry0、禁请求响应日志，独立ChatModel不启用JSON response_format。最终STOP/严格JSON/ID/原样quote与来源补回仍由旧RagAnswerValidator；最近检索revision改变仍返回409，不被ExecutionException一律掩盖成502。

每个有效SDK响应先校验并记录usage，再处理提议/终稿；成功返回逐步usage。错误响应不带未校验答案，异常未获得usage不是零成本，不声明真实账单。引文子串只证明出处，不保证语义蕴含。

## MCP 与打包入口

仅一个参数 --mcp-stdio时，AiServiceApplication进入独立SDK main而不启动Spring。普通HTTP主入口不变。原stdout专用于JSON-RPC，System.out先转stderr；stdin EOF关闭server。SDK负责initialize/发现/调用/输入schema，未手写协议。

CLOUDDRIVE_MCP_AI_URL仅接受http://127.0.0.1:合法显式端口，无userinfo/query/fragment/子路径；JWT后端必须启用RAG，MCP本身不需要外部AI或Agent开关。工具映射仅GET /api/ai/rag/documents、POST /api/ai/rag/search，Redirect.NEVER，成功响应上限16000字节，401/403及其他后端错误只返回通用码。

Java MCP SDK2.0.1显式mcp-json-jackson2，不引入默认Jackson3模块；Boot3.2.4、Java17、LC4j1.21.0、ONNX1.21.0-beta31及AI局部Jackson2.22.1保留。实际握手协议2025-11-25、服务器clouddrive-readonly-rag/day32，SDK内置logging capability也存在；业务仅注册两工具，不注册resources/prompts/roots。

## 验证与残余边界

六工程唯一359JUnit全0failure/error/skipped，AI138=旧90+新48；新六类13/4/4/12/12/3。真实HTTP/JWT/ONNX/SDK与真实Java STDIO子进程，生成是明确协议fixture。最终同包实测14 Agent/MCP +12旧RAG +7摘要/SSE。

JAR中MCP core/json-jackson2、LangChain4j/ONNX模型资源合同通过。Windows官方client关闭后可能记录子进程code1告警，不声称该关闭码0；请求成功和无残留分开核对。连接3秒、request5秒和SDK10秒不是已证明的慢body端到端硬deadline。

真实DeepSeek决策质量/账单、完整注入防护、远程OAuth、多节点、持久化、中文生产质量、跨平台/负载没有运行。继承内存重启清空、HS256本机账户信任、原样quote非蕴含、revision不能撤回已发送资料、底层native无硬取消。详细日志和失败历史见[运行证据](../day32/runtime-evidence.md)。

参考机制：[LangChain4j tools](https://docs.langchain4j.dev/tutorials/tools/)、[DeepSeek tools](https://api-docs.deepseek.com/guides/tool_calls/)、[Java MCP SDK server](https://java.sdk.modelcontextprotocol.io/latest/server/)。版本兼容以锁定构建和真实协议结果为证，不外推全部最新版能力。
