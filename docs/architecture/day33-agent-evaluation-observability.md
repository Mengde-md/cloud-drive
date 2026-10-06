# Day33 Agent Evaluation, Observability and Failure Boundaries

## Scope

Input: Day32 readonly Agent and actual MCP STDIO, Day31 in-memory tenant RAG and Day30 controlled DeepSeek/LangChain4j execution. Increment: whole-loop telemetry, a frozen 20-scenario protocol evaluation, and bounded MCP HTTP body collection. No new business endpoint, write tool, DDL, remote MCP or framework upgrade.

HTTP Agent and MCP remain separate execution paths. Agent uses owner-bound local RagIndex. MCP uses an explicit loopback origin and out-of-band JWT to reach the existing two RAG HTTP routes. The model proposes actions; neither a prompt nor readOnlyHint grants execution authority.

## Whole-Loop Span

```text
Security / JSON / Bean Validation
  rejected before service -> no Agent Span
AgentService.ask -> Span.begin
  disabled / input / empty evidence -> finish
  controlled worker -> model attempt -> valid usage -> successful tool
                    -> next attempt / answer / fixed failure
caller outcome / deadline -> finish once -> ignore late callbacks
```

Micrometer uses the existing Boot MeterRegistry. Only finite outcome, code, kind, mode and tool tags are permitted. runId correlates a loop and requestId correlates one existing SDK step; neither is a metric label. New Agent logs exclude owner, question, document text, JWT, API key and untrusted exception messages. Existing ai_usage ownerId metadata is unchanged, so this is not a claim that all project logs are anonymous.

| Metric | Meaning |
|---|---|
| clouddrive.agent.requests | One finished service invocation; pre-service HTTP rejection excluded |
| clouddrive.agent.duration | Caller-visible whole-loop duration |
| clouddrive.agent.model.calls | Attempted SDK entries, not provider completions |
| clouddrive.agent.tool.calls | Successfully completed readonly tools, not proposals |
| clouddrive.agent.tokens | Validated usage only; fixture/provider kept separate |
| clouddrive.agent.estimated.usd | Known estimate from valid steps; not a bill |

Cost status is not_called, estimated or incomplete. A failed attempt without usage is not free. Span synchronization makes completion idempotent and freezes observability after timeout; it does not guarantee remote/native work has stopped. Management exposure remains health only and metrics remains denied.

## MCP Body Deadline

McpRagBackend now waits for the full sendAsync response using a five-second future deadline, not merely headers plus an unbounded InputStream read. McpBoundedBody collects at most 16000 logical bytes, checks before copying, requests one batch at a time and cancels on rejection. Non-200 bodies are never collected. Validated bytes undergo strict UTF-8 decoding.

AtomicReference covers a published subscriber during caller completion; AtomicBoolean covers a handler arriving after completion. finally attempts future cancellation and cancels a present subscription. This bounds caller waiting/body collection, not remote hard cancellation. A successful body can still be non-JSON; JSON/application semantics remain the fixed backend/tool consumer's responsibility.

The JDK distinguishes response headers from asynchronous body completion and recommends cancelling abandoned subscriptions. Cancellation is an attempt, not proof of server-side termination. Sources: [BodySubscriber](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html), [HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html).

## Frozen Evaluation and Injection Boundary

agent-evaluation.json freezes 20 ids and expected HTTP/code/model/tool counts. AgentEvaluationHttpTest executes all rows through real Spring HTTP, JWT, ONNX, SDK and local readonly tools; the model proposals are synthetic protocol responses. The generated report carries source=protocol_fixture, realModelCalled=false and semanticQualityMeasured=false, and omits tenant/question/document/token/key data. Dataset existence is not a passing run.

E16/E17 combine malicious questions with fixed unlisted delete/http_fetch proposals. E18/E19 import and retrieve malicious document text, assert the canary is actually sent back in SDK tool messages, then reject delete/owner-change proposals before dispatch. The document remains present. These demonstrate application authorization boundaries for given proposals, not model-level detection, universal prompt-injection prevention or semantic correctness.

System instructions separate data and commands, but execution checks enforce the boundary outside the model: two tool names, strict schema, bound owner, bounded rounds and old source quote/revision validation. Least privilege and application authorization must not be delegated to untrusted model output. Source: [OWASP Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html).

## Verification Responsibilities

Four new JUnit classes add 38 cases: 5 telemetry, 6 body, 6 real HTTP failure and 21 evaluation/management. Old 138 AI assertions remain. Day33PackagedProbe is not JUnit: it drives the actual executable AI JAR and official MCP STDIO against a controlled slow/malformed/unauthorized backend. Its child JVM explicitly writes UTF-8 logs; this does not relax backend decoding.

Old five-module CI and Day32/31/30 package probes run separately and serially against the final artifact. No probe runs concurrently with clean. The original gate remains unmodified and covers root only; its result belongs in acceptance after frozen Final. Same-executor independent reread is not third-party audit or human learning completion.

Remaining boundaries: no real DeepSeek call, real-model planning/semantic attack quality or billing; in-memory/restart-empty RAG; exact quote is not entailment; no ability to retract evidence already sent; cancellation best effort; Windows SDK STDIO shutdown may log exit code 1 independently of successful protocol checks. No public metric collection/deployment monitoring service added.
