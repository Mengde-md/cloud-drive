# Day12 Final：HTTP 经事务应用服务接入 MySQL

## 当日范围与禁止项

唯一增量是将 Day06 的 HTTP 条目接口从进程内 `DriveEntryCatalog` 改为 `DriveEntryApplicationService → DriveEntryRepository → MySQL`，并以 `@Transactional` 标出创建与重命名的业务边界；同时将 HTTP 合同测试改为每例清理真实测试库，并发布首份 Postman Collection。

禁止认证、Redis、上传下载、消息队列、微服务、缓存、分页和新业务表。事务不是跨线程、跨 HTTP 请求或跨服务的万能回滚；本日也不以测试上的 `@Transactional` 代替业务服务上的事务边界。

## Checkpoint 0 实际起点

Day07 已有 MyBatis Mapper、Repository、Flyway 表和真实 MySQL 测试，但 Day06 Controller 仍直接调用内存 Catalog；同一 `RANDOM_PORT` 上下文中的请求会共享该 Catalog。Day11 已解释 IOC/AOP 代理边界，但尚未让事务代理包住真实 HTTP→数据库业务链。

## Checkpoint 1 唯一增量

新增 `DriveEntryApplicationService`。它的读操作使用 `@Transactional(readOnly = true)`；创建和重命名使用 `@Transactional`。Controller 只负责 DTO、校验、状态码与响应映射；Service 把父目录存在性和一次业务调用放进事务代理；Repository 只处理 MyBatis 行映射和数据库异常翻译。

创建子条目时，Service 先按 parentId 查询父条目，再写入子条目；因此不存在的父目录会抛 `EntryNotFoundException`，由既有 Advice 转成 404。Repository 不再先查再写地判断重复主键，而是捕获 MySQL 的 `DuplicateKeyException` 转为业务重复异常，避免把“先查后插”的竞争窗口伪装成唯一性保证。

`DriveEntryWebApplication` 将扫描根提升到 `com.mengde.clouddrive` 并显式 `@MapperScan`，使 application、persistence 和 web 成为同一 Boot 容器资产。`application.properties` 只读取 `CLOUDDRIVE_DB_URL`、`CLOUDDRIVE_DB_USERNAME`、`CLOUDDRIVE_DB_PASSWORD` 环境变量；密码不写入生产配置。

## Checkpoint 2 Debug 与边界

1. **Service 没有 Bean。** 原因是 Day06 启动类位于 `web` 包，默认扫描不到同级 `application` 包。证据是 `NoSuchBeanDefinitionException`；修复为根包扫描加 MapperScan，77 项回归通过。
2. **HTTP 测试互相污染。** 原因不是端口随机，而是同一个数据库/上下文保留状态。每个 HTTP 用例在 `@BeforeEach` 调用 Repository 清理表；每个测试自行创建自己的前置数据。
3. **以为 `@Transactional` 会跨异步 worker。** 它只围绕经 Spring 代理进入 Service 的当前线程调用。Day05 的快照线程不自动继承该事务；本日不让 worker 写库。
4. **重复 ID 先查再插。** 两个事务都可能查到不存在；最终唯一性必须由 MySQL 主键裁决。Repository 将数据库重复键翻译回 409 业务语义。

## Final 验收记录

使用 JDK 17、Maven 3.9.10、`C:\Environment\m2repo` 和本机 MySQL 8.0 运行全量回归：`Tests run: 77, Failures: 0, Errors: 0, Skipped: 0`，结果 `BUILD SUCCESS`。HTTP 测试真实启动 Tomcat、Flyway 和 MySQL；创建、查询、重命名、校验失败、重复主键、缺失父目录与快照失败均有合同断言。首版手工接口资产位于同目录 Postman Collection。

## 独立复刻任务

1. 画 `POST /api/entries` 从 JSON 到 201 的链：Controller → Service 事务代理 → Repository → Mapper → MySQL → Response。
2. 先创建父目录，再创建子文档；重启应用后执行 GET，解释为什么仍可查询。
3. 用两个会话演示“先查再插”不能替代主键；说明 409 最终来自哪个边界。
4. 把 `@Transactional` 放到同类内部自调用上，预测它为什么可能不经过代理。

## 面试题与追问

1. `@Transactional` 为什么放 Service 而不是 Controller？业务用例边界独立于 HTTP；同一服务可被其它入口复用。
2. 默认哪些异常回滚？运行时异常通常回滚；受检异常需明确 `rollbackFor` 或转换，不能靠猜。
3. 什么是 self-invocation？同一对象内部调用绕过外部代理，事务注解可能不生效。
4. 隔离级别与 MVCC 解决什么？隔离并发读写可见性；MVCC 用版本让许多读取不必等写锁，具体语义依 MySQL 隔离级别而定。
5. 简历怎么说？“我将网盘 HTTP 接口经事务应用服务接入 MyBatis/MySQL，使用数据库主键而非内存预查保证重复 ID 边界，并用真实 HTTP+MySQL 合同测试验证状态隔离与失败响应。”

## 教材自审（Day02/Day05 对照）

| 质量项 | 教材位置 | 已重新打开的真实资产 | 审计结论与未覆盖边界 |
|---|---|---|---|
| 前日衔接与真实问题 | 第 1、9--12 节 | Day11 Controller/代理边界、Day07 Repository、Day06 Catalog | 通过；不把内存 Catalog 与数据库状态混称同一存储 |
| 项目源码（生产）逐段走读 | 第 10--14、21、34--36 节 | `DriveEntryWebApplication`、`DriveEntryController`、`DriveEntryApplicationService`、`DriveEntryRepository`、`DriveEntryMapper`、`application.properties` | 通过；未实现认证、缓存、上传或跨实例幂等 |
| 项目测试走读与测试矩阵 | 第 15--16、33 节 | `DriveEntryHttpContractTest`、`SnapshotHttpFailureTest`、`DriveEntryRepositoryTest`、`DriveEntryMapperTest` | 通过；HTTP/MySQL 合同不等于浏览器端到端或并发压测 |
| SQL/事务原理回扣项目 | 第 14、19、21、36--38 节 | `V1__create_drive_entries.sql`、主键查询、Service `@Transactional` | 通过；MVCC 双会话与手工 EXPLAIN 是用户待执行实验，不伪称已跑 |
| 六类 Debug | Debug 1--6、第 22、37 节 | Bean 扫描、测试污染、异步边界、Mapper、重复键、重启检查 | 通过；每类均给出输入、预测、证据、修复和回归 |
| API、独立复刻、阶段题、面试 | 第 17--18、23--24、37--38 节 | Postman Collection、LC207、SQL EXPLAIN、动态代理题、答辩口述 | 通过；Postman 重启后 GET 需用户真实记录 |

**返工复审记录（2026-09-15）**：教材从 74 行/3,799 字符/1 个 Java 片段扩展为 615 行/29,226 字符/13 个 Java 片段，并补齐真实 HTTP 合同逐例走读、异常翻译链、事务代理三个反例、配置与重启证据链、SQL 小卷和闭卷复盘。此结论只表示教材交付的素材完整度已复审；Postman 重启、真实 `EXPLAIN`、MVCC 双会话和闭卷复刻仍只能由学习者亲自记录。

### 教材返工复审 2026 09 18

教材原为 615 行，未满足当前 650 行下限。本次以既有 `DriveEntryApplicationService`、Controller、Mapper、`GlobalExceptionHandler`、HTTP 合同与 Mapper 测试为依据扩写到 650 行、30,766 字符；补充事务/异常/HTTP 证据边界、Debug 13--14、答辩模板和 SQL 复读清单。未改 Day12 业务代码或测试，也不把 Day13 的设计模式复盘写成 Day12 能力。

## 明日唯一输入

输入是本日事务应用服务、MySQL HTTP 合同证据、Postman Collection 和干净提交。Day13 只复盘设计模式与 Spring 对应关系；在用户完成手工重启验证与人工学习门前不新增业务功能。
