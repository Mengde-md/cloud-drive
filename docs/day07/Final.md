# Day07 Final：MySQL/MyBatis/Flyway 持久化

## 当日范围与禁止项

Day07 的唯一增量是把 Day02–06 的内存条目/索引能力持久化到 MySQL：`DriveEntryRow`（行映射）、`DriveEntryMapper`（MyBatis CRUD + 重复键语义）、`DriveEntryRepository`（领域对象 ↔ 行互转，抛与 Day03 一致的业务异常）、Flyway V1 迁移建表。集成测试连接**本机真实 MySQL**（127.0.0.1:3306/cloud_drive_learning）。本日未引入 Redis、缓存、鉴权、分片、上传或 Web 新端点。

## Checkpoint 0 实际起点

起点为 Day06 的 `3e542b1`（52 测试全绿，Spring MVC + 内存存储）。内存对象/索引/快照均在 JVM/文件中，尚无数据库；Day03 的重复键与缺失语义只存在于 `InMemoryEntryIndex`，未落到 SQL。

## Checkpoint 1 唯一增量

- `pom.xml`：加入 mybatis-spring-boot-starter 3.0.3、mysql-connector-j、flyway-core + flyway-mysql。
- `V1__create_drive_entries.sql`：`drive_entries` 表（id/owner_id/parent_id/display_name/kind/byte_size），MySQL 兼容。
- `DriveEntryRow`：行记录；`DriveEntryMapper`：insert/findById/count 等；`DriveEntryRepository`：领域 ↔ 行互转 + 重复键(DuplicateEntryException)/缺失(EntryNotFoundException)。
- 测试连接真实 MySQL：`spring.datasource.url=jdbc:mysql://127.0.0.1:3306/cloud_drive_learning`（Flyway 建表，`@Transactional` 回滚隔离）；本机凭据只从受控测试配置读取，不记录在教材、Final 或回执中。

**关键修正记录（本日验收过程）**：
1. 早期版本测试用 **H2（MySQL 兼容模式）模拟 MySQL**，违反总纲"只靠 H2 代替 MySQL 是禁止"红线；且本机 MySQL 真实可用，无必要替代。已改为真实 MySQL 连接，移除 H2 依赖。
2. 三个 `@SpringBootConfiguration` 冲突（两个测试各内嵌 + 独立 PersistenceTestApplication）→ 统一指向共享 `PersistenceTestApplication`。
3. 重复键异常断言：裸 MyBatis 的 `PersistenceException` 在 MyBatis-Spring 下被翻译为 Spring `DuplicateKeyException`，测试断言已修正并注明。
4. 引入 mybatis starter 后 Day06 web 测试触发 DataSource 自动配置失败 → 两个 web 测试通过 `spring.autoconfigure.exclude` 排除 DataSource（它们测内存行为）。
5. `.gitignore` 增加 `build-output/`（pom 输出目录从 target 改为 build-output，产物不再污染工作区）。

## Checkpoint 2 Debug 与边界

1. MySQL 连通性实测：基础项目的 Docker 环境凭据不适用于本机直连；Day07 测试使用本机受控账户连接独立库 `cloud_drive_learning`，不污染用户业务库。凭据不写入文档。
2. H2 → 真库差异：H2 与 MySQL 的异常翻译不同，暴露了断言错误（见上）。
3. 重复键 insert → `DuplicateKeyException`（Spring 翻译层），非裸 PersistenceException。
4. Maven 仓库：Day07 起依赖(mysql/mybatis/flyway)在 `C:\Environment\m2repo`，独立缓存/默认 .m2 均缺 → 用 m2repo。

## Final 验收记录

用户在独立 Windows PowerShell 真实执行 `mvn -Dmaven.repo.local=C:\Environment\m2repo clean test`：**Tests run: 72, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**（Day01–06 回归 52 + Day07 新增 20：MapperTest 10 + RepositoryTest 10）。真实 MySQL 连接、Flyway 建表、事务回滚隔离均验证。

`EXPLAIN` 真实证据：在单一事务中临时插入专用探针行，执行 Mapper 同形的主键查询后回滚；结果为 `type=const`、`possible_keys=PRIMARY`、`key=PRIMARY`、`rows=1`。这证明 `WHERE id = ?` 命中主键索引，而不是全表扫描；该探针没有遗留数据。

## 独立复刻任务

建 MySQL 库与 Flyway V1；写 Mapper(CRUD+重复键) 与 Repository(领域↔行互转+业务异常)；用真实 MySQL 的 @Transactional 测试隔离。禁止 H2 冒充 MySQL。

## 面试题与追问

1. MyBatis-Spring 为什么把 SQL 异常翻译成 Spring 数据异常？这对上层意味着什么？
2. Flyway 迁移为什么必须版本化只追加？
3. 为什么领域对象构造约束在恢复路径仍生效？（行→对象仍走构造器）
4. Repository 抛的业务异常与 Day03 内存版如何保持一致？
5. 追问：H2 模拟 MySQL 会掩盖哪些真实差异？为何本机有 MySQL 就不该用 H2？
6. 追问：`EXPLAIN` 中 `type=const`、`key=PRIMARY`、`rows=1` 分别说明什么？为什么不能只看“查询跑得快”？

## 明日唯一输入

Day08 唯一输入 = 本日持久化 Final + commit + 72 测试基线。Day08 为**复盘型**（HashMap 底层 + ConcurrentHashMap 对照），不新增业务代码，白名单 {08}。
