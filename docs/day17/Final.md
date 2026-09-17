# Day17 Final：上传、存储抽象、MD5 与简单秒传

## 当日范围与禁止项

**唯一增量**：以 Day16 已认证、owner 隔离、目录树与配额为输入，实现完整 multipart 上传、本地可替换对象存储抽象、流式 MD5、metadata 一致性补偿，以及仅限同 owner 候选命中的简单秒传。

**禁止提前**：不实现分片、断点续传、upload session、chunk manifest、Range 下载、分享、跨用户秒传、Redis metadata 缓存、分布式锁、消息队列或微服务。Day18 才处理分片与续传；Day21 才讨论分布式幂等。

## Checkpoint 0：实际起点

- 基线提交：`1773d18 docs(day16): complete material audit markers`。
- 开始前 `git status --short` 为空。
- Day16 已提供 JWT 主体、owner 过滤、目录 parent 校验、逻辑配额与 MySQL/Flyway 基线。
- 今日要解决的真实问题：数据库 metadata 不能保存二进制内容；本地对象与 metadata 失败时可能分离。

## Checkpoint 1：唯一增量与代码证据

| 层 | 文件 | 今日职责 |
|---|---|---|
| Flyway | `V4__add_document_storage_metadata.sql` | 增加 `storage_key`、`content_md5` 与 `(owner_id, content_md5)` 候选索引 |
| storage | `ObjectStorage`、`LocalObjectStorage`、`StoredObject` | 流式保存、UUID key、8 KiB buffer、MD5、半文件清理 |
| persistence | `StoredDocumentRow`、`DriveEntryMapper`、`DriveEntryRepository` | 保存带物理定位的 Document，按 owner+MD5 查候选 |
| application | `DocumentUploadApplicationService` | parent/owner/配额校验，metadata 失败后补偿删除 |
| web | `DocumentUploadController` | `/api/files/upload` 与 `/api/files/instant` HTTP 合同 |
| security | `SecurityConfiguration` | 上传路由要求已认证 |
| error | `InstantUploadCandidateNotFoundException` | 跨用户或无候选时稳定返回 404 |
| tests | `DocumentUploadHttpContractTest` | 正常、越权候选、重名补偿三条真实 HTTP 链路 |

上传顺序：

```text
JWT -> owner/parent 检查 -> store(InputStream) + MD5
    -> 锁配额并重新统计 -> insert metadata -> 201
业务失败 -> delete(刚写入对象) -> 保留原异常
```

`storageKey` 是内部实现细节；HTTP DTO 只返回 id、ownerId、parentId、displayName、byteSize、md5、instant。

## Checkpoint 2：Debug 与边界

1. **multipart 400**：未显式写 `@RequestParam("displayName")` 与 `@RequestParam("file")` 时，参数名绑定依赖编译信息；已改成显式 HTTP 合同。
2. **候选字段 null/0**：MyBatis 手写查询的下划线列未稳定映射到 camelCase 属性；已用 `AS ownerId`、`AS byteSize`、`AS storageKey`、`AS contentMd5`。
3. **重复名称孤儿对象**：metadata 唯一约束失败时，应用服务删除刚写入对象；HTTP 测试同时验证 409 和本地对象数。
4. **I/O 半文件**：`LocalObjectStorage` 内部处理写入异常并清理 target。
5. **配额竞争**：对象写入后仍在锁定用户配额行后重新统计；配额失败进入补偿删除。
6. **跨用户摘要越权**：候选查询始终带 owner；Bob 知道 Alice 的 MD5 也得到 404。
7. **MD5 边界**：MD5 仅是候选索引，不能当身份、授权、签名或绝对相同证明。
8. **补偿边界**：本日不是分布式事务；进程崩溃、delete 失败、引用计数与孤儿扫描留给后续设计。

## Final 验收记录

### 项目源码

- `ObjectStorage` 把应用服务与本地 Path 解耦；`LocalObjectStorage` 防目录逃逸并在同一输入流循环中写入和计算 MD5。
- V4 迁移已被真实 Flyway 验证，schema 版本为 4。
- 简单秒传只复用当前 owner 的候选 `storage_key`；它不构成全局去重。
- `Locale.ROOT` 用于规范化候选请求的 MD5，避免依赖默认地区设置。

### 项目测试

真实执行命令：

```powershell
$env:JAVA_HOME='C:\Environment\jdk-17'
$env:Path='C:\Environment\jdk-17\bin;C:\Environment\maven\bin;C:\Program Files\Git\cmd;'+$env:Path
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Environment\cdl-java-sockets'
mvn -f 'C:\Users\惠普暗影精灵9\Documents\新建文件夹\cloud-drive-learning\pom.xml' `
  '-Dmaven.repo.local=C:\Environment\m2repo' clean test
```

原始汇总：

```text
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Day17 新增 HTTP 覆盖：

| 测试 | 证据 |
|---|---|
| 上传后秒传 | 两次 201；第二次 `instant=true`；MD5 与大小相同 |
| 跨用户候选 | Alice 上传后，Bob 使用相同 MD5 得到 404 |
| 重名补偿 | 第二次同名上传 409；测试存储根目录对象数仍为 1 |

## 教材自审

- 已交付学习教材：`C:\Users\惠普暗影精灵9\Desktop\test\项目面试题\阶段一\CloudDrive学习教材_Day17_上传存储抽象MD5与简单秒传.md`。
- 教材从 Day16 的身份、目录与配额出发，先解释“metadata 为什么不等于内容”，再引入存储抽象、流式摘要、补偿、简单秒传与失败边界。
- 教材包含真实源码走读、项目测试走读、8 个 Debug、独立复刻、算法、SQL、Java、网络、面试与简历边界；不把 Day02 的对象建模、Day05 的 Spring 基础原理或后续 Day18 分片内容混入当日增量。
- 教材明确列出 Day18 与 Day21 的前瞻边界，避免把分片和分布式锁伪装成本日能力。

## 面试题与追问

1. 为什么上传入口选择 InputStream 而不是 byte[]？它们的内存差异是什么？
2. 本地文件系统与数据库如何保证一致？本日补偿覆盖什么，又遗漏什么？
3. 为什么数据库最终唯一约束不能被“先查再插”取代？
4. 为什么秒传候选 SQL 必须同时带 `owner_id` 与 `content_md5`？
5. MD5 可以用于哪些事，不能用于哪些安全判断？
6. 如果两条 metadata 指向同一 storageKey，删除接口应如何设计？为什么本日没有实现？
7. EXPLAIN 中如何确认 `(owner_id, content_md5)` 真的被使用？
8. multipart 400 如何从 HTTP 字段名、Spring 参数绑定和编译信息三个层次排查？

## 独立复刻任务

不复制原项目类名，重写一个 `ObjectStorage`、一个流式本地实现和一张 metadata 表；分别证明普通上传、同 owner 候选、跨用户拒绝、重名补偿。最后写出崩溃、引用计数与孤儿清理未解决的原因。

## 明日唯一输入

Day18 的唯一输入是 Day17 已完成的完整对象上传：metadata 已有 storageKey、contentMd5，普通上传能补偿失败，且 owner/目录/配额边界未被破坏。Day18 才增量实现分片与续传。

