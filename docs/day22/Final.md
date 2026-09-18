# Day22 Final 回收站、分页与单体可观测性

## Checkpoint 0 昨日真实资产与限制

Day21 已有 owner/session Redisson 方法体互斥、按 owner + operation 的 Redis 限流、MySQL 最终约束与 89 项全量回归。它不提供误删恢复、稳定页契约或单体服务可观测性；锁也不覆盖 Spring 代理的 commit 窗口。

## Checkpoint 1 唯一增量、真实问题与禁止项

- **唯一增量**：回收站软删除、活动列表/回收站分页、OpenAPI/Postman、结构化请求完成日志和 health 暴露。
- **禁止**：不引入网关、Sentinel、Nacos、MQ、微服务、分布式事务或下载/分享限流。

## 实现证据

- `V7__add_drive_entry_trash.sql` 以 `deleted_at` 表示回收站状态；活动条目唯一性由 generated columns + `(owner_id, active_parent_scope_id, active_display_name)` 保证，已删除的同名条目不阻塞新建。
- `DriveEntryMapper` 所有读取、秒传候选、计量、子项计数与改名均排除 `deleted_at IS NOT NULL`；回收站查询按 owner、删除时间倒序并使用 `LIMIT/OFFSET`。
- `GET /api/entries/page`、`GET /api/entries/trash` 默认 `page=0,size=20`，上限 100；`POST /api/entries/trash/{id}/restore` 恢复，`DELETE /api/entries/trash` 只永久清空当前 owner 的回收站。
- 回收站响应包含 `deletedAt`；恢复发生同名冲突时由数据库活动唯一约束转为 409，跨 owner 或活动条目恢复均为 404。
- `RequestTimingFilter` 输出 `event=request_complete method path status durationMs`，不记录 body、token 或密码；`management.endpoints.web.exposure.include=health,info` 发布 `/actuator/health`。

## 测试与验收

- `TrashHttpContractTest`：Alice 删除后详情 404、Alice 回收站可见、Bob 回收站为空、Alice 恢复 204。
- 专属命令：`mvn -Dtest=TrashHttpContractTest test`，1/0/0/0，BUILD SUCCESS。
- 全量测试：`mvn clean test`，90 / 0 / 0 / 0，BUILD SUCCESS；固定 JDK 17、Maven repo 与 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Environment\cdl-java-sockets` 执行。
- 实现提交：`08b21e6 feat(day22): add trash pagination and observability`。

## 教材自审 Day02 Day05 对照

| 审计项 | 真实教材证据 | 结论 |
| --- | --- | --- |
| 前日衔接与问题 | 第 0-1 节从 Day21 上传协调边界推导误删、分页和排障问题 | 已覆盖 |
| 项目源码走读 | 第 2-3 节走读 V7、mapper 活动过滤、controller/service/page 契约与 timing filter | 已覆盖 |
| 项目测试走读 | 第 4 节走读 `TrashHttpContractTest` 和 owner 隔离断言 | 已覆盖 |
| Debug | 第 5 节至少四类：漏过滤、恢复重名、参数绑定、日志泄密 | 已覆盖 |
| 阶段题 | 第 7 节含算法、SQL、Java 题 | 已覆盖 |
| 面试与复刻 | 第 6、8 节包含简历说法、追问与闭卷复刻 | 已覆盖 |
| 篇幅要求 | 教材 655 行；已按源码、测试、Debug、阶段题与独立复刻逐项复审 | 通过 |

本表不代表用户已学会；用户仍需口述、闭卷复刻、记录错因和完成题单。
