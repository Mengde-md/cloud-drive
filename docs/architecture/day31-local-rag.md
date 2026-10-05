# Day31 Local RAG Boundary

## Scope And Decision

Day30 DeepSeek + LangChain4j entry is the input. Day31 adds strict caller-supplied text ingestion, splitting, real local embeddings, explicit vector storage, retrieval, citations, refusal and evaluation. No Agent, Function Calling execution, MCP, automatic drive access or new SQL migration.

| Decision | Frozen implementation | Limit |
|---|---|---|
| Generation | deepseek-flash through existing 1.21.0 SDK | No real cloud invocation in this delivery |
| Embeddings | all-MiniLM-L6-v2 quantized ONNX, 1.21.0-beta31, 384 dimensions | English engineering sample; multilingual/domain quality unverified |
| Vector store | LangChain4j InMemoryEmbeddingStore 1.21.0, separate owner corpus | Volatile, exact scan, one process; restart clears it |
| Parser | Base64 bytes, strict UTF-8 .txt/.md, BOM/newline normalization | No PDF/DOCX/OCR/URL/path reader or Markdown rendering |
| Splitting | DocumentSplitters.recursive(240,32) | Character parameters, not billing/token measurements |
| Retrieval | Same model for question; topK3/minScore0.78 | Normalized cosine threshold, not correctness probability |
| Output | Strict local JSON + retrieved-id/exact-substring validation | Does not establish semantic entailment of every answer claim |

The local model and tokenizer are bundled in the runtime JAR. Embedding does not contact a cloud service at runtime. First Maven resolution requires network/cache. Beta ONNX integration is disclosed; no whole-repository framework upgrade.

## API And Ownership

| API under /api/ai/rag | Responsibility | Status boundaries |
|---|---|---|
| POST /documents | Caller bytes, generated UUID, source SHA256, local embedding, publish | 201 / 400 / 409 / 429 / 503 |
| GET /documents | JWT owner document metadata | 200 / RAG_DISABLED503 |
| DELETE /documents/{id} | Delete only owned document | 204; missing and other-owner404 |
| POST /search | Local retrieval, never external generation | 200 scoped hits / 400 / 429 / 503 |
| POST /answer | Explicit consent, retrieve, guarded generation, validate | 200 ANSWERED/REFUSED; 400/409/429/502/503/504 |

JWT USER and local HS256 issuer/subject/expiry verification are unchanged. X-User-Id and request ownerId are not trusted. Unknown JSON fields fail. Existing gateway /api/ai/** forwards all endpoints without a new route or anonymous allow-list change.

RAG is disabled by default (CLOUDDRIVE_RAG_ENABLED=false). Setting true permits local import/search without an AI key. Generation with hits still requires existing AI enable/key and explicit consent. No hits returns REFUSED/NO_EVIDENCE with null usage and zero supplier calls; model refusal has MODEL_REFUSED and final usage estimate.

## State And Capacity

- At most 32 owners with documents, 8 documents/owner, 128 chunks/owner; no TTL or distributed account fairness.
- Encoded envelope44000, decoded bytes32768, normalized UTF-16 chars12000, parser chunks96, query512; not an early raw HTTP body DoS bound.
- One local inference permit, fail-fast429; Runnable::run avoids the SDK embedding executor, not all ONNX native threads.
- All vectors validated (384, finite, nonzero norm, complete count) before synchronized snapshot publication. Failure leaves no partial document.
- Store chosen by JWT owner before vector search; never global topK followed by an ownership filter.
- Deletion rebuilds owner store, releases empty-owner capacity; monotonic revisions prevent remove/reimport ABA.
- Ingest is not idempotent: duplicate imports create distinct UUIDs and consume quotas. SHA is provenance, not a deduplication key.

## Generation Boundary

DeepSeekClient.answer sends JSON evidence as user data and fixed system instructions. No tools or memory are configured. SummaryService.answer and summarize use the same bounded slots/workers/Future wait; existing stream terminal handling remains. Retries0 and provider logging remain disabled.

RagAnswerValidator requires STOP/no tool requests, bounded/strict duplicate-free JSON, exactly status/answer/citations, typed strings, distinct retrieved ids and exact quotes. Source name/docId/ordinal/score are server-owned. REFUSED means empty answer and citations. Generated invalid output fails502 rather than escaping to the caller.

After generation, requireCurrent rejects changed corpus409. It does not retract already transmitted text, refund token costs, cancel all upstream execution or make delivery/deletion globally linearizable. Checking just before returning still leaves a send/receive race; corpusRevision states provenance. Local inference has no established hard wall-clock deadline. Natural-language prompt instructions are not a complete injection defense.

## Evaluation And Evidence

Real ONNX benchmark: 4 English documents, 6 positive source cases, 4 obviously unrelated negatives. Recall@3=6/6, negative empty-retrieval=4/4. One JUnit drives these10 samples. This jointly designed set is not held out; no difficult in-domain negatives, Chinese, real-user queries or production quality claim.

Protocol fixtures synthesize generation and usage. They prove SDK/HTTP/schema/citation/refusal/error contracts, not model reasoning, faithfulness or actual token charges. Final 311 unique JUnit, 19 real-package checks and preservation evidence are documented separately in runtime-evidence/Final/acceptance.

## Sources

- [Local ONNX integration](https://docs.langchain4j.dev/integrations/embedding-models/in-process/)
- [Model card](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- [In-memory vector store](https://docs.langchain4j.dev/integrations/embedding-stores/in-memory/)

Checked2026-10-05. Library bytecode in downloaded1.21.0 confirms RelevanceScore=(cosine+1)/2. Model resource SHA contracts are in runtime-evidence; descriptive model names are not content hashes.

