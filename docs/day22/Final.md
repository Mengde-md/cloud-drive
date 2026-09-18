# Day22 Final 回收站、分页与单体可观测性

## 范围

- 增量仅为回收站软删除、活动列表/回收站分页、OpenAPI/Postman、结构化请求完成日志和 health 暴露。
- 不引入网关、Sentinel、Nacos、MQ、微服务、分布式事务或下载/分享限流。

## 实现证据

- `V7__add_drive_entry_trash.sql` 以 `deleted_at` 表示回收站状态；活动条目唯一性由 generated columns + `(owner_id, active_parent_scope_id, active_display_name)` 保证，已删除的同名条目不阻塞新建。
- `DriveEntryMapper` 所有读取、秒传候选、计量、子项计数与改名均排除 `deleted_at IS NOT NULL`；回收站查询按 owner、删除时间倒序并使用 `LIMIT/OFFSET`。
- `GET /api/entries/page`、`GET /api/entries/trash` 默认 `page=0,size=20`，上限 100；`POST /api/entries/trash/{id}/restore` 恢复，`DELETE /api/entries/trash` 只永久清空当前 owner 的回收站。
- 回收站响应包含 `deletedAt`；恢复发生同名冲突时由数据库活动唯一约束转为 409，跨 owner 或活动条目恢复均为 404。
- `RequestTimingFilter` 输出 `event=request_complete method path status durationMs`，不记录 body、token 或密码；`management.endpoints.web.exposure.include=health,info` 发布 `/actuator/health`。

## 测试与验收

- `TrashHttpContractTest`：Alice 删除后详情 404、Alice 回收站可见、Bob 回收站为空、Alice 恢复 204。
- 专属命令：`mvn -Dtest=TrashHttpContractTest test`，1/0/0/0，BUILD SUCCESS。
- 全量测试、日验收、教材审计和发布提交在本文件最终写入前仍需完成，不把本阶段草稿视为 Day22 完成。

## 教材自审待办

教材必须在最终发布前逐段对照源码、测试、至少三类 Debug、面试题、算法/SQL/Java 题及闭卷复刻；不足 650 行不得发布。
