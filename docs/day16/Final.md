# Day16 Final：用户隔离、目录元数据 CRUD 与配额边界

## 当日范围与禁止项

- **唯一输入**：Day15 已验证的 `AuthenticatedUser`、JWT filter 与 `/api/auth/me`；Day12 的 HTTP→Service→Repository→MySQL 事务链。
- **唯一增量**：账号默认配额、由 MySQL 生成的条目 id、owner-scoped 目录/元数据 CRUD、父目录约束、同目录重名约束、目录非空删除保护与真实 HTTP 合同测试。
- **禁止提前**：不做 multipart 上传、文件字节写入、MD5/秒传、分片/断点续传、分享、真实 metadata Redis 缓存、分布式锁、分页与回收站。

## Checkpoint 0：实际起点

起点为 Day15 提交 `173f09c`，认证 token 能在 `/api/auth/me` 还原 `AuthenticatedUser`，但 `/api/entries` 仍允许匿名访问，DTO 仍允许客户端传入 `ownerId` 与 id；Repository 的列表/详情 SQL 没有 owner 条件，任何已登录用户都无法被证明只能看到自己的目录。

## Checkpoint 1：唯一增量

`V3__add_owner_scoped_drive_entries.sql` 为账号加 `quota_bytes`，将 `drive_entries.id` 改为 AUTO_INCREMENT，并以 `parent_scope_id = IFNULL(parent_id, 0)` 建立 `(owner_id, parent_scope_id, display_name)` 唯一约束和 owner/parent 索引。`OwnedDriveEntryApplicationService` 是 Day16 新的事务边界：它从 `AuthenticatedUser.id()` 取 owner，先 `FOR UPDATE` 锁定该账号的配额行，再验证同 owner 的父目录、核算已用字节、写入 metadata。

`DriveEntryController` 的 `/api/entries` 全部端点使用 `@AuthenticationPrincipal AuthenticatedUser`；请求 JSON 不再含 `id` 或 `ownerId`。查询、改名和删除均调用 owner-scoped Repository 方法；跨用户访问返回 404，不泄露目标条目是否存在。非空目录返回 409，重复同名和超额也映射为 409。

## Checkpoint 2：Debug 与边界

第一次编译失败来自遗留 `DriveEntryCatalog` 仍调用旧的无参 `EntryCreateRequest.toEntry()`；它是 Day06 内存资产而非当前 Controller 主链，仍要同步为显式 id/owner 参数以保持全仓编译。真实 MySQL 迁移随后把 schema 从 V2 升到 V3；Flyway 输出证明生成列、AUTO_INCREMENT 与唯一索引已进入真实库，不是只在 Java 里写了 if。

当前配额是 metadata `byte_size` 的逻辑预算，尚未写入真实文件；锁住 `user_account` 行只是单体 MySQL 内避免同一用户两次创建在读-算-写之间同时越额。Day17 才引入本地/可替换存储和文件写入一致性；Day20 才允许缓存实际 metadata。

## Final 验收记录

- 实现提交：`a7331e3 feat(day16): add owner-scoped drive metadata and quota`。
- `mvn clean test` 的 Surefire XML 汇总：**73 tests / 0 failures / 0 errors**；其中 Day16 `DriveEntryHttpContractTest` 为 7 个真实随机端口 HTTP 场景，覆盖匿名 401、owner 来源、跨用户不可见、父目录隔离、同级重名、配额/非空目录和跨用户改删。
- `git diff --check` 为 0；实现提交后工作区干净。
- 教材自审（Day02/Day05）：**项目源码**已重新读取 Day15 `SecurityConfiguration`/`AuthenticatedUser`，Day16 `OwnedDriveEntryApplicationService`、`DriveEntryRepository`、`DriveEntryMapper`、V3 migration、`DriveEntryController`；项目测试已重新读取 `DriveEntryHttpContractTest` 与 `RequestValidationContractTest`。教材依次记录前日衔接、真实问题、生产源码走读、项目测试走读/测试矩阵、8 个 Debug、算法/SQL/Java 题、面试与独立复刻；不把 73 项工程回归写成用户已学会。

### 教材结构同步复审 2026 09 18

教材原在第 49 节写入明日输入，后续第 50--56 节仍有完整请求、失败链和源码—测试核对。本次把第 49 节改为范围预览，并在全文末尾用 owner-scoped metadata、配额与数据库约束正式交接 Day17。教材现为 781 行、29,832 字符；未改 Day16 的 MySQL、认证或 HTTP 合同实现。

## 独立复刻任务

不看教材，在临时分支把 `GET /api/entries/{id}` 改成故意调用未带 owner 条件的方法，先预测 Bob 能否读到 Alice 的数据，再写一条 HTTP 合同测试证明漏洞。随后恢复 owner-scoped 查询；再在根目录创建两个同名目录，解释普通 `(owner_id,parent_id,display_name)` UNIQUE 为什么会被 MySQL 的 NULL 语义绕过。

## 面试题与追问

1. JWT 已能识别用户，为什么还必须在每一条资源 SQL 上带 owner 条件？
2. 为什么跨用户资源通常返回 404 而不是 403？追问：两种选择各自泄露什么？
3. MySQL 多列唯一索引遇到 NULL 有什么陷阱？为什么 Day16 使用生成列？
4. `SELECT ... FOR UPDATE` 在配额检查中保护了哪段临界区？它不解决什么分布式问题？
5. 为什么目录删除要先查子项而不是只调用 DELETE？追问：并发创建子项时还缺哪种数据库约束？
6. 你如何在简历中说明 Day16 的“用户隔离”而不夸大成完整 RBAC？

## 明日唯一输入

Day17 的唯一输入是 owner-scoped metadata、目录树和剩余配额。下一日才让 multipart 上传经可替换存储抽象写入内容，并处理“物理字节写入成功而 metadata 失败”这类一致性问题；本日没有任何文件上传实现。
