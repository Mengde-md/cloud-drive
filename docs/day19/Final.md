# Day19 Final：Range 下载与提取码分享

## 当日范围与禁止项

Day18 的最终对象已经可以由 owner 写入，但用户还不能读取它，也没有受控地把文件交给别人。Day19 的唯一增量是：owner 下载支持单个 HTTP Range；owner 创建带 BCrypt 提取码与可选过期时间的分享；匿名用户只有在 token、提取码和过期条件均满足时才能下载分享对象。

不做多 Range、预签名 URL、下载限流、Redis、分布式锁、分享运营、消息队列或跨节点状态。Range 仅支持一个 `bytes=start-end` 范围。

## Checkpoint 0：Day18 输入

Day18 已得到最终 `drive_entries.storage_key` 和 `byte_size`，`ObjectStorage` 只提供 store/delete。下载需要只读打开对象的能力；分享需要独立于 JWT 的访问边界，但不能绕开提取码与过期校验。

## Checkpoint 1：实现

V6 创建 `file_shares`，保存 token、document_id、owner_id、BCrypt passcode hash 与 expires_at。明文提取码不落库。`ObjectStorage.open(key,start,length)` 由 `LocalObjectStorage` 以流式读取实现并限制返回长度，避免把完整文件读入堆。

`FileDeliveryService` 是边界集中处：owner 下载先按 owner 查询存储文档；分享下载先查 token，再校验过期与 `PasswordEncoder.matches`，然后复用同一 delivery 路径。Range 解析只允许单段，支持普通、开放尾部与 suffix 范围，并拒绝越界和多段范围。

`FileDeliveryController` 以 200 返回完整文件、以 206 和 `Content-Range` 返回范围下载；设置 `Accept-Ranges: bytes` 与安全化后的下载文件名。公开路径只有 `/api/shares/*/download`，而创建分享和 owner 下载仍要求 JWT。

## Checkpoint 2：测试与边界

`FileDeliveryServiceTest` 真实验证：`bytes=2-99` 被裁剪为 2–5 且只读取 `cdef`；提取码以 BCrypt 哈希存储；错误提取码和过期分享均抛分享访问异常。新增 `FileDeliveryHttpContractTest` 用真实 Spring Boot、JWT、Flyway/MySQL 与本地对象目录验证 owner Range 的 206/`Content-Range`/实际正文、错误提取码 404、正确提取码匿名下载 200。测试夹具先删除分享再删除文档，避免将 Day22 的业务删除策略偷带入 Day19。

真实 Debug 风险：若把公开分享路径写成 `/api/shares/**`，创建分享也会绕过认证；因此仅放行下载精确路径。若把 `Range` 字符串直接 split 后使用而不校验，负数、多段和越界会导致错误响应或文件读取边界失控。

## Final 验收记录

代码提交：`0e5098b feat(day19): add range download and passcode shares`。

Day19 自身测试：`mvn -Dtest=FileDeliveryServiceTest,FileDeliveryHttpContractTest test` 通过，退出码 0。全量回归和发布脚本将在文档完成后运行。

## 教材自审

Day02 的问题→职责→测试闭环：教材从 Day18“对象已写但不能读”出发，分别走读存储接口、Range 解析、分享校验和测试断言。Day05 的失败证据门：错误提取码、过期、越界 Range、多 Range 与分享外键夹具冲突均有明确证据。项目源码为 `LocalObjectStorage`、`FileDeliveryService`、`FileDeliveryController`；项目测试为 `FileDeliveryServiceTest` 与 `FileDeliveryHttpContractTest`。教材使用自然段、完整协议表和真实源码/测试，不以单句换行制造物理行数。

### 文档同步复审 2026 09 18

Day19 教材曾在早期“测试矩阵”和第 22 节中遗留“HTTP 合同测试待补”的历史说法，但 `62bf447 test(day19): add range and share HTTP contract` 已实际提供该测试。现已统一为：`FileDeliveryServiceTest` 负责隔离服务规则；`FileDeliveryHttpContractTest` 负责真实 Spring Boot/JWT/MySQL/对象目录下的 206、`Content-Range`、错误提取码 404 与正确提取码 200 合同。教材的两个重复“明日唯一输入”也已合并为一个；未覆盖的 HTTP 边界仍明确标作后续补强，不伪称已验证。

## 面试题

1. 为什么 Range 响应是 206 而不是 200？`Content-Range` 表示什么？
2. 为什么提取码要 BCrypt 哈希而不是 MD5 或明文？
3. JWT 认证和分享 token + 提取码的权限模型有什么区别？
4. 为什么下载必须流式返回而不读取完整 byte[]？
5. 单 Range 与多 Range 的实现和安全边界差异是什么？

## 明日唯一输入

Day20 只以 Day19 已有的 owner 元数据查询、下载/分享边界为输入，引入私有元数据 Cache Aside；Day20 前不得引入 Redis 缓存。
