# Day20 Final：私有元数据 Cache Aside 与失效演练

## 当日范围与禁止项

- **唯一输入**：Day19 已交付的认证、私有条目 CRUD、上传/秒传/分片合并、Range 下载与提取码分享；基线提交为 `3f80b80`，本日实现提交为 `c61fd18`。
- **唯一增量**：Redis 接入 owner-scoped 的目录/条目元数据 Cache Aside；读取命中/回源/写入 TTL，创建、重命名、删除以及三种上传成功后的失效；缓存不一致窗口的测试演练。
- **禁止提前**：不缓存文件字节、Range 下载内容、分享 token 或提取码；不引入 Redisson、分布式锁、消息队列、微服务、跨节点缓存协调或热点击穿单飞控制。这些属于 Day21 及以后。

## Checkpoint 0：昨日可运行输入

Day19 的 `DriveEntryController` 对 `/api/entries` 的列表和详情每次都回源 MySQL；Day17 的 `DocumentUploadApplicationService`、Day18 的 `UploadSessionApplicationService` 成功落 metadata 后也没有任何缓存失效责任。Day19 的下载和分享能力保持不变：下载字节、分享 token、提取码不属于本日缓存对象。

接手时使用 `git -C` 核对提交历史和工作区；实现前没有将用户既有改动混入本日范围。Redis 真实集成测试使用本机已运行的 Redis，HTTP 合同测试启动真实 Spring Boot、Security、MySQL、Flyway 与 Redis 链路。

## Checkpoint 1：唯一增量及其责任边界

新增 `redis/PrivateEntryMetadataCache.java`，它只接收已认证 ownerId 和 `EntryResponse` / 列表数据。键包含 ownerId：

```text
clouddrive:day20:owner:{ownerId}:entries
clouddrive:day20:owner:{ownerId}:entry:{entryId}
```

读路径是 Cache Aside：先读 Redis；有值则反序列化并返回；无值、损坏值或 Redis 读失败才执行一次数据库 supplier；回源成功后尽力写 Redis，TTL 为 5 分钟。supplier 不在缓存异常的 `catch` 中，保证数据库异常不会被吞掉或被执行两次。

写路径坚持“数据库为真相、缓存为可丢优化”：Controller 的 create/rename/delete 成功后失效；普通上传、秒传和分片合并成功保存 metadata 后也失效 owner 列表。失效本身是 best effort：Redis 暂时不可用时，已经成功的 MySQL 写入不应被改成失败；代价是最多一个 TTL 的陈旧窗口。

## Checkpoint 2：真实测试、异常与边界

`PrivateEntryMetadataCacheIntegrationTest` 使用真实 `StringRedisTemplate` 验证：

1. 同一 owner 第二次列表读取命中缓存，supplier 不再次执行；不同 owner 的键和值隔离。
2. 模拟数据库内容已经变更但缓存尚未失效时，读取仍得到旧值；调用 `evictEntry` 后才回源得到新值。这是有意展示不一致窗口，而不是假装缓存天然一致。
3. 向 Redis 预写入非 JSON 内容后，读取降级回源、写回正确 JSON；损坏缓存不会让接口永久 500。

`PrivateEntryCacheHttpContractTest` 以真实 HTTP 注册/登录 JWT 调用 `/api/entries`：列表响应已写入 owner 私有 Redis key；重命名返回 204 后再次请求列表得到新名称并重新写入缓存；另一用户读到空列表及自己的 key，不能读到 Alice 的条目。

本日专属测试命令：

```powershell
$env:JAVA_HOME = 'C:\Environment\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Environment\cdl-java-sockets'
& 'C:\Environment\maven\bin\mvn.cmd' -f 'C:\Users\惠普暗影精灵9\Documents\新建文件夹\cloud-drive-learning\pom.xml' '-Dmaven.repo.local=C:\Environment\m2repo' '-Dtest=PrivateEntryMetadataCacheIntegrationTest,PrivateEntryCacheHttpContractTest' test
```

真实结果：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

全量回归命令：

```powershell
& 'C:\Environment\maven\bin\mvn.cmd' -f 'C:\Users\惠普暗影精灵9\Documents\新建文件夹\cloud-drive-learning\pom.xml' '-Dmaven.repo.local=C:\Environment\m2repo' clean test
```

真实结果：`Tests run: 87, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

## Final 验收记录

| 门禁 | 真实证据 | 结果 |
| --- | --- | --- |
| 范围 | 仅 owner 私有元数据 Cache Aside 与失效；无字节缓存、锁、MQ、微服务 | 通过 |
| 项目源码 | `PrivateEntryMetadataCache`、`DriveEntryController`、`DocumentUploadApplicationService`、`UploadSessionApplicationService` | 通过 |
| 项目测试 | Redis 集成测试 3 项；真实 HTTP 合同测试 2 项 | 通过 |
| 正常/异常/边界 | 命中、回源、owner 隔离、失效、损坏 JSON、缓存陈旧窗口 | 通过 |
| 全量测试 | `mvn clean test`：87 / 0 / 0 / 0 | 通过 |
| 实现提交 | `c61fd18 feat(day20): cache owner metadata with redis aside` | 通过 |

## 教材自审（Day02/Day05 对照）

| 审计项 | 教材位置与证据 | 结论 |
| --- | --- | --- |
| 前日衔接和真实问题 | 第 1–2 节点名 Day19 私有条目、下载和分享边界，说明列表重复回源 | 通过 |
| 项目源码走读 | 第 5–9 节逐段解释 `PrivateEntryMetadataCache`、Controller 与两条上传写路径 | 通过 |
| 项目测试走读/测试矩阵 | 第 10–12 节走读 Redis 集成测试及真实 HTTP 合同测试 | 通过 |
| Debug | 第 13 节六类故障：键污染、损坏 JSON、Redis 不可达、漏失效、缓存字节、错误双删 | 通过 |
| 阶段题 | 第 15 节算法、SQL、Java 题及答案边界 | 通过 |
| 面试与独立复刻 | 第 14、16 节包含项目表述、追问与闭卷复刻任务 | 通过 |
| 运行证据 | 第 12、17 节记录专属 5 项、全量 87 项测试及验收命令 | 通过 |
| 篇幅与段落密度 | 教材 690 行、30,701 字符；逐段复读前日衔接、生产源码、项目测试、六个 Debug 和阶段题，无逐句拆行填充 | 通过（650–799 例外已逐项覆盖真实资产） |

本表已按教材、项目源码与项目测试重新对照；它不代表用户已经完成口述或闭卷复刻。

## 面试题与追问

1. 为什么 Cache Aside 在更新后通常删除缓存而不是直接更新缓存？追问：删除失败后为何仍允许数据库写成功？
2. 为什么 key 必须含 ownerId？如果只有 entryId，会产生哪一种越权风险？
3. 为什么本日不缓存文件下载字节和提取码？
4. Redis 中出现乱码/损坏 JSON 时接口如何降级？数据库 supplier 为什么不能包在同一个 catch 中？
5. TTL、缓存失效和最终一致性各自解决什么问题？
6. 如何证明 HTTP 接口不是只测到了内存 mock？本日测试启动了哪些真实组件？

## 独立复刻任务

不看教材，在另一条 owner 私有“收藏列表”链路实现同样的读旁路：先设计键、TTL 和不缓存的敏感字段；写一个“未失效读到旧值、失效后读到新值”的测试；再写一个两用户隔离合同测试。提交前说明 Redis 不可达时选择可用性还是强一致性，以及这个选择的风险。

## 明日唯一输入

Day20 的 owner 私有 metadata Cache Aside、5 分钟 TTL、创建/重命名/删除/上传成功后的 best-effort 失效，以及 87 项回归全绿。Day21 才讨论并发下重复请求、幂等键、分布式锁/原子操作等问题；不得把它们倒灌进本日实现。
