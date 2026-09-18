# Day21 Final Redisson 分布式锁与上传限流

## 当日范围与禁止项

- **唯一输入**：Day20 的 owner 私有 metadata Cache Aside；Day17 的直接上传与秒传；Day18 的上传会话、顺序分片与合并。
- **唯一增量**：在真实上传写路径接入 Redisson 分布式锁、按 authenticated owner 的 Redis 共享限流、锁竞争 `409` 与限流 `429` 合同、真实 Redis 集成测试。
- **禁止提前**：不引入网关、Sentinel、Nacos、消息队列、微服务、分布式事务、全站登录限流、下载/分享限流、回收站、分页、OpenAPI 或可观测性端点。

## Checkpoint 0 昨日输入

Day20 的 `PrivateEntryMetadataCache` 只缓存 owner 私有 metadata，创建、重命名、删除和上传成功后 best-effort 失效。Day17 的 `DocumentUploadApplicationService` 仍承担对象写入、quota 校验、metadata 保存与失败补偿；Day18 的 `UploadSessionApplicationService` 仍承担 manifest、顺序校验、合并与临时文件清理。两日的简单幂等与数据库约束仍然保留，尚无跨 JVM 临界区互斥。

## Checkpoint 1 唯一增量和设计取舍

新增 `UploadCoordination`，以 `upload-owner:{ownerId}` 保护直接上传和秒传，以 `upload-session:{sessionId}` 保护分片写入与合并。采用 `tryLock(0)` 快速失败，锁竞争映射为 `409 Conflict`；不传固定 lease，由 Redisson watchdog 为存活持锁者续期，并由 try-with-resources 的 `HeldLock.close()` 在正常、异常和 early return 时释放当前线程持有的锁。

新增 `UploadRequestRateLimiter`，以 `ownerId + operation` 为 Redis key，使用 `RRateLimiter` 和 `RateType.OVERALL`，使同一用户在全部应用实例中共享额度。直接上传、秒传、创建会话、完成会话为 5/min；分片写入为 20/min；空闲 limiter 一小时过期。无 permit 时不进入业务 service，映射为 `429 Too Many Requests`。

## Checkpoint 2 跨天改动清单

| 既有日 | 修改文件 | 原因 |
| --- | --- | --- |
| Day17 | `DocumentUploadApplicationService.java` | 直接上传与秒传的对象/metadata/quota 临界区增加 owner 锁，降低跨节点重复写和补偿竞争；原有数据库约束、事务和补偿不删除。 |
| Day17 | `DocumentUploadController.java` | 在已认证用户进入直接上传和秒传 service 前获取对应限流 permit。 |
| Day18 | `UploadSessionApplicationService.java` | `putChunk` 与 `complete` 共享 session 锁，使顺序检查、分片写入、manifest 合并和状态转换跨节点串行。 |
| Day18 | `UploadSessionController.java` | 在创建会话、写分片和完成会话前按 owner + operation 限流。 |

## Final 验收记录

| 门禁 | 真实证据 | 结果 |
| --- | --- | --- |
| 依赖与运行环境 | `redisson-spring-boot-starter` 3.24.3；真实 Redis `127.0.0.1:6379` 建立 Redisson 连接 | 通过 |
| 项目源码 | `UploadCoordination`、`UploadRequestRateLimiter`、Day17/18 application service 与两项 controller | 通过 |
| 项目测试 | `UploadCoordinationIntegrationTest` 用真实 Redis 验证锁竞争、释放后重获、同 owner 限流和不同 owner 隔离 | 通过 |
| 错误合同 | `UploadInProgressException` → 409；`UploadRateLimitExceededException` → 429 | 通过 |
| 正常/异常/边界 | watchdog、current-thread 解锁、0 等待、key 粒度、Redis 限流 key 一小时回收、保留 DB 约束 | 通过 |
| 全量测试 | `mvn clean test`：89 / 0 / 0 / 0，BUILD SUCCESS | 通过 |
| 实现提交 | `f739f09 feat(day21): coordinate uploads with redisson` | 通过 |

## 教材自审 Day02 Day05 对照

| 审计项 | 教材位置与证据 | 结论 |
| --- | --- | --- |
| 前日衔接和真实问题 | 第 1-3 节从 Day20 缓存、Day17 对象补偿、Day18 单机幂等推导跨节点竞争 | 通过 |
| 项目源码走读 | 第 6-11 节逐段走读协调器、释放、两条 Day17 写路径、两条 Day18 写路径、limiter 与 controller | 通过 |
| 项目测试走读和测试矩阵 | 第 12-13 节解释真实 Redis 测试并列出旧 HTTP 回归与全量矩阵 | 通过 |
| Debug | 第 14-19 节覆盖锁键过粗、漏解锁、lease 过短、JVM 限流、混桶、误删 DB 约束六类问题 | 通过 |
| 阶段题 | 第 22 节包含算法滑动窗口、SQL 联合唯一索引、Java 锁语义题 | 通过 |
| 面试与独立复刻 | 第 20-21 节包含复刻任务、简历表达和五个追问 | 通过 |
| 运行证据 | 第 13、23 节记录专属 2 项和全量 89 项测试、实际 Maven 命令 | 通过 |
| 篇幅与资产密度 | 教材 677 行；内联真实新增核心代码、测试结构、失败链和阶段题，无占位内容 | 通过（650-799 例外按真实资产覆盖） |

本表已按教材、生产源码和测试源码重新核对；不代表用户已完成口述、闭卷复刻、错因记录或阶段题学习门。

## 面试题与追问

1. 为什么 Redis 锁不能替代 MySQL 唯一索引和事务？
2. 为什么按 owner/session 取锁，而不是按 URL 或全局锁？
3. watchdog、固定 lease 与 finally 解锁各自解决什么问题？
4. 为什么限流用 `RateType.OVERALL` 但仍是每用户独立额度？
5. 409 与 429 分别意味着什么，客户端该如何重试？
6. 如何证明这个实现不是仅在单 JVM mock 中通过？

## 独立复刻任务

对“创建分享链接”实现 owner 级锁和独立限流 key。写一个竞争失败、释放后成功和同用户超额的真实 Redis 测试；保留数据库唯一约束，并说明 watchdog 无法处理的网络分区和外部存储边界。

## 明日唯一输入

Day21 的 Redisson 跨节点锁、owner/session 锁粒度、watchdog 与异常释放、按 owner + operation 的共享限流，以及 89 项全量回归。Day22 才实现回收站、分页、OpenAPI/Postman、日志、health、耗时指标和故障演练。
