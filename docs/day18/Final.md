# Day18 Final：分片、断点续传与受控合并

## 当日范围与禁止项

Day17 已经能够把一次 HTTP 请求中的完整文件写入对象存储，也能够在同一用户、同一 MD5 命中的情况下返回简单秒传结果。但这个模型有一个硬边界：网络在大文件传输到 99% 时断开，客户端只能从第 0 个字节重新发送；服务端也无法判断“第 3 段已安全落盘、只差第 4 段”。Day18 的唯一增量是把一次上传拆成一个**上传会话**和多块可恢复的分片：创建会话、按顺序收块、记录 manifest、校验完整性、合并、写入 Day17 对象存储、失败时清理本次最终对象。

本日不引入下载或 HTTP Range、分享、Redis 缓存、消息队列、分布式锁、跨节点会话协调、微服务。当前上传/分片幂等靠简单机制（唯一键/putIfAbsent），分布式锁将在 Day21 引入。

## Checkpoint 0：昨日 Final 的真实输入

Day17 的 `DocumentUploadApplicationService` 接受完整 `MultipartFile`，将字节写进 `ObjectStorage`，做配额检查，再写 `drive_entry` 元数据。它解决了“文件如何持久化”和“同一用户重复上传能否避免再写一次”，却不能表达上传尚未结束的中间状态。

本日输入基线为提交 `b27155d` 的父提交 `4b7d99d`。开始实现前工作区为空；Day17 的上传、秒传、权限和 76 个回归测试都是本日不得改坏的约束。

## Checkpoint 1：唯一增量与设计选择

### 1. 不直接在 `drive_entry` 上加 `chunk_index`

一个文件在“尚未传完”时不应成为用户可见文档。若把每一块直接写成文档记录，目录列表会看到半文件，删除、配额、重命名也会被迫理解中间状态。因此新增两张临时状态表：

```sql
CREATE TABLE upload_sessions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    display_name VARCHAR(255) NOT NULL,
    total_chunks INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_upload_sessions_owner
        FOREIGN KEY (owner_id) REFERENCES user_account(id)
);

CREATE TABLE upload_chunks (
    session_id VARCHAR(36) NOT NULL,
    chunk_index INT NOT NULL,
    byte_size BIGINT NOT NULL,
    content_md5 CHAR(32) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    PRIMARY KEY (session_id, chunk_index),
    CONSTRAINT fk_upload_chunks_session
        FOREIGN KEY (session_id) REFERENCES upload_sessions(id)
        ON DELETE CASCADE
);
```

`upload_sessions` 是这次上传的身份和所有权；`upload_chunks` 是 manifest。联合主键 `(session_id, chunk_index)` 是第一层重复保护：同一会话的同一个位置最多有一条记录。它不等于多节点分布式幂等，但能支撑本机单实例的重试语义。

### 2. 协议与状态

```text
POST /api/upload-sessions
  -> ACTIVE(sessionId, totalChunks)
POST /api/upload-sessions/{id}/chunks/0
  -> 0.part + manifest[0]
POST /api/upload-sessions/{id}/chunks/1
  -> 1.part + manifest[1]
POST /api/upload-sessions/{id}/complete
  -> 校验 0..N-1 -> 合并临时文件 -> Day17 ObjectStorage
  -> drive_entry -> COMPLETED -> 删除分片目录
```

服务只接受顺序块：已收到 `0` 时，下一个合法 index 是 `1`。这是有意的简化，换来极容易验证的 manifest 连续性。任意顺序、并发分块、跨节点抢占会在后续工程化阶段讨论，不能假装 Day18 已完成。

### 3. 存储边界

`ChunkStorage` 只知道“分片临时文件如何保存、如何合并、如何清理”；`ObjectStorage` 仍只知道 Day17 的最终对象；应用服务编排二者和数据库。这样测试可以验证 HTTP 合同，而本地文件实现没有被 Controller 绑死。

```java
public interface ChunkStorage {
    StoredChunk store(String sessionId, int chunkIndex, InputStream input) throws IOException;
    MergedChunks merge(String sessionId, List<UploadChunkRow> chunks) throws IOException;
    void clearSession(String sessionId) throws IOException;
    void deleteTemporary(MergedChunks merged) throws IOException;
}
```

`LocalChunkStorage.store` 使用八 KB 缓冲边写边计算 MD5；写失败时删掉目标 `.part`，避免“磁盘有半块、数据库没记录”的残留。合并时按数据库已经排序的 manifest 逐块读写，产生 `<sessionId>.merged` 临时文件并重新计算整体 MD5。每次从 `root.resolve(...)` 得到路径后都检查 `startsWith(root)`，防止 sessionId 或 storageKey 通过 `..` 越出临时目录。

### 4. 关键应用服务走读

```java
public ChunkResult putChunk(AuthenticatedUser user, String sessionId,
                            int chunkIndex, MultipartFile file) throws IOException {
    UploadSessionRow session = activeSession(user.id(), sessionId);
    if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
        throw new IllegalArgumentException("chunk index outside session range");
    }
    UploadChunkRow existing = sessions.findChunk(sessionId, chunkIndex);
    if (existing != null) return ChunkResult.from(existing, true);

    List<UploadChunkRow> received = sessions.chunks(sessionId);
    if (chunkIndex != received.size()) {
        throw new UploadSessionIncompleteException(
                "chunks must arrive in order; expected index " + received.size());
    }
    StoredChunk stored = chunks.store(sessionId, chunkIndex, file.getInputStream());
    UploadChunkRow row = new UploadChunkRow();
    row.setSessionId(sessionId);
    row.setChunkIndex(chunkIndex);
    row.setByteSize(stored.byteSize());
    row.setContentMd5(stored.md5());
    row.setStorageKey(stored.storageKey());
    sessions.saveChunk(row);
    return ChunkResult.from(row, false);
}
```

先查已记录的块再读请求体，是为了让客户端的相同分片重试返回原有元数据，而不是覆盖文件。随后以 `received.size()` 作为下一个唯一位置，拒绝跳块。这个顺序还避免了“收到 index=1，磁盘已有 1.part，但 manifest 只有 0”的普通错误路径。

完成接口的核心是：先校验 manifest 个数与 `0..N-1` 连续性；再合并；将合并流交给 Day17 对象存储；锁定配额并写最终 `StoredDocumentRow`；标记会话完成并删掉临时 manifest / 目录。最终对象写好而后续数据库失败时，catch 会删除这个刚写的最终对象。`@Transactional` 只能回滚数据库，不能回滚文件系统，所以“补偿删除”是本日对双资源不一致的明确处理，而不是虚假的原子事务承诺。

### 5. HTTP 契约

| 动作 | 请求 | 成功 | 失败边界 |
| --- | --- | --- | --- |
| 创建 | `POST /api/upload-sessions` + 文件名、总块数 | 201 + ACTIVE 会话 | 未登录、空名、非正块数 |
| 写块 | `POST /{id}/chunks/{index}` multipart `file` | 首次 201；同 index 重试 200 | 非 owner 404；跳块/缺块 409 |
| 完成 | `POST /{id}/complete` | 201 + Day17 文档响应 | 未收齐 409；配额不足 409 |

`@PathVariable("sessionId")` 和 `@PathVariable("chunkIndex")` 显式写出名字，不依赖编译器是否保留参数名。Controller 不直接写目录；它只解析 HTTP，传递认证主体给应用服务。

## Checkpoint 2：测试、Debug 与边界

### HTTP 合同测试

新增 `ResumableUploadHttpContractTest`，使用真实 Spring Boot 随机端口、真实 MySQL/Flyway、Day15 JWT，并在每个测试前后删除会话、目录条目、用户、对象目录与分片目录。它不是 Mock 断言：每个行为都跨过 Controller、Security、Mapper、文件系统和数据库。

1. 空会话直接完成返回 409；先发 index 1 也返回 409，并断言 `1.part` 根本不存在。
2. `hello ` 与 `world` 两块顺序上传后完成，整体 MD5 必须是 `5eb63bbbe01eeed093cb22bb8f5acdc3`；同 index 重发 `ignored` 返回 200 / `alreadyReceived=true`，且不会覆盖第一个分片。
3. Bob 知道 Alice 的 sessionId 仍不能追加或完成，两个接口均返回 404；资源所有权不因 UUID 泄露而失效。

### 真实 Debug 记录

| 现象 | 证据与定位 | 修复 | 回归 |
| --- | --- | --- | --- |
| Spring 启动时没有 `UploadSessionMapper` Bean | 新 Mapper 放在 `upload` 包，但应用只扫描 `persistence` 与 `auth` | 将 Mapper 移入 `persistence` 包，保持 Mapper 扫描边界 | 全量 79 测试通过 |
| 扩大 `@MapperScan` 后又报 `ChunkStorage` 不是有效 Mapper | `upload` 包既有 Mapper 也有普通接口；宽扫描把存储接口也当 Mapper | 恢复窄扫描，Mapper 归入 persistence | 上下文成功启动 |
| multipart URL 的 path variable 得到 400 | 运行时不保证 Java 参数名可反射取得 | 在两个 `@PathVariable` 上显式标注字符串名 | 三条 HTTP 合同测试通过 |

### 仍然不能声称解决的问题

- 两台应用实例同时接同一会话的相同分块请求，当前没有跨节点互斥；Day21 才引入分布式锁。
- 本日要求顺序上传，不提供位图、乱序重排、客户端并发窗口。
- 应用进程在物理块写入后、数据库 manifest 写入前被杀死时，残留 `.part` 需要未来的过期会话清理任务处理。
- 没有下载、Range、预签名 URL、分享链接；这些不属于 Day18。

## Final 验收记录

### 静态检查

- 生产增量：V5 迁移、上传会话/分片模型、`ChunkStorage` 本地实现、Repository/Mapper、应用服务、Controller、异常映射与安全路径。
- 测试增量：`ResumableUploadHttpContractTest` 三条真实 HTTP 用例。
- 边界扫描：未引入 Redis、消息队列、下载 Range、分享、分布式锁或跨节点协调。
- 项目源码走读：教材逐段对应 V5、`LocalChunkStorage`、`UploadSessionApplicationService`、`UploadSessionController` 与会话 Mapper 的真实职责和调用顺序。
- 项目测试走读：教材逐段对应三个真实 HTTP 合同测试的 409、201/200、404 断言以及临时目录清理断言。

### 真实运行结果

执行：

```powershell
mvn -f "C:\Users\惠普暗影精灵9\Documents\新建文件夹\cloud-drive-learning\pom.xml" `
  "-Dmaven.repo.local=C:\Environment\m2repo" clean test
```

结果：`Tests run: 79, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

代码原子提交：`b27155d feat(day18): add resumable chunk upload sessions`。

## 教材自审

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 昨日问题是否具体 | 通过 | Day17 的整文件请求断线只能重传 |
| 本日唯一增量是否闭合 | 通过 | 会话、块、manifest、合并、补偿清理 |
| 源码与测试是否都有走读 | 通过 | `ChunkStorage`、应用服务、HTTP 合同测试 |
| Debug 是否真实 | 通过 | Mapper 扫描、普通接口误扫描、PathVariable 绑定 |
| 是否偷跑后续能力 | 通过 | 禁止项与未解决项明确列出 |
| 面试与独立复刻是否可执行 | 通过 | 见阶段一 Day18 教材后半部分 |

本次教材自审沿用 Day02 的“问题→对象职责→代码→测试”闭环和 Day05 的“失败路径必须有证据”门槛：Day18 不以 API 列表替代解释，必须同时给出项目源码、项目测试、三个真实 Debug 与范围边界。

### 教材返工复审 2026 09 18

旧教材虽然有核心代码与三条合同测试走读，但只有 277 行，不满足完整递进教材的篇幅与资产覆盖要求。本次不改 Day18 已提交代码，而是以 `b27155d` 的历史源文件为唯一源码依据重新扩写教材，防止把 Day21 的锁/限流倒灌回 Day18。

本轮重读并重构后，教材为 682 行、30,492 字符。它移除了位于正文中段的“本节小结/明日输入”式提前收束，改为会话状态预测；新增了真实 HTTP 合同测试矩阵、`validateManifest` 的数量/连续性逐行推导、完成与清理的责任边界，以及工程证据和人工学习门的分层说明。682 行处于 650–799 的例外区间：本日已逐段覆盖 V5、临时存储、Repository、应用服务、Controller 和三条真实 HTTP 合同测试；继续内联 Day17/Day21 源码或虚构未实现的多节点演练只会造成重复或越界，因而不以 800 行为机械目标。静态检查确认无 placeholder、8 个 Debug 标题齐全，且前日衔接、真实问题、项目源码、项目测试、代码、测试、独立复刻、面试、算法、SQL、Java 和验收标记均存在。

教材仍如实保留 Day18 的边界：只支持单实例顺序上传；会话/manifest 唯一键不是跨节点互斥；文件系统与数据库只有局部补偿，不是分布式事务；进程被强杀后的孤儿块需后续 TTL 清理。

## 面试题与追问

1. 为什么分片元数据不能直接混进最终文件表？追问：半上传文件为什么不应出现在目录查询中？
2. `(session_id, chunk_index)` 联合主键解决了什么，又没有解决什么？追问：多实例同时写同一块时怎么办？
3. 为什么 `@Transactional` 不能保证“数据库和文件系统一起回滚”？本日如何补偿？
4. 为什么重试分片返回已有结果比覆盖文件更安全？
5. 为什么完成前要验证块数和连续 index，而不是只看分片目录里有几个文件？
6. 为什么 Controller 需要显式 `@PathVariable("sessionId")`？

## 独立复刻任务

不看代码完成一个两块文本上传：先创建会话，故意先上传 index 1，写下你期待的 HTTP 状态与为什么目录不应产生该块；再按 0、1 上传，重复发送 index 0，最后完成并手算 `hello world` 的 MD5 与响应比较。随后用第二个账号调用该 session 的 complete，解释 404 与 403 在“不泄露资源存在性”上的取舍。

## 明日唯一输入

Day19 只以本日完成的最终 `drive_entry`、对象存储和上传产物为输入，新增下载/HTTP Range 与分享边界。Day19 前不得提前创建下载或分享接口。
