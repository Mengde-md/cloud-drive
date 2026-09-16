# Day13 Final：设计模式复盘与 Spring 映射

## 当日范围与禁止项

- **唯一输入**：Day12 的事务应用服务、HTTP/MySQL 合同测试、领域模型、快照执行器与干净提交 `976aa74`。
- **唯一增量**：通过已有源码和测试复盘 Spring 容器单例、小工厂、策略、抽象基类边界、事务 AOP 代理；不新增生产业务代码。
- **禁止提前**：Redis/缓存/Redisson（Day14/20/21）、认证（Day15）、目录树/配额（Day16）、上传/分片/分享/回收站（Day17–22）。

## Checkpoint 0：实际起点

代码仓库基线为 `976aa74`，工作区为空；不存在 `docs/day13/Final.md` 或 Day13 教材。Day12 具备 77 项回归与工程门 12/12 PASS；其人工重启、EXPLAIN、MVCC、闭卷复刻仍未由用户提供完成记录。本日依据用户明确“直接开始 Day13”的授权制作复盘资产，不将此授权写成 Day12 学习完成证明。

## Checkpoint 1：唯一增量

新增 Day13 教材与本 Final，不改 `src/main`、`src/test`、`pom.xml`、数据库迁移或配置。教材真实走读：

1. `SnapshotConfiguration`、`@Service`：容器默认 singleton scope 与手写全局单例的边界；
2. `EntryCreateRequest.toEntry`、`DriveEntryRepository.toDomain`：两类输入边界的小工厂；
3. `SnapshotConsumer`、`SnapshotTaskExecutor`：可替换行为策略及失败清理；
4. `AbstractDriveEntry`：共享不变量但并非严格 Template Method；
5. `DriveEntryApplicationService`：`@Transactional` 的 Spring AOP 代理边界。

## Checkpoint 2：Debug 与边界

教材逐项记录六类可复现问题：测试 Bean 未替换、枚举新增造成两处创建映射漂移、失败 future 未清理、子类破坏 identity、不经过代理的 private/self-invocation 事务、Controller 事务边界。每项均给出输入、错误预测、真实源码责任、修复原则与回归位置。

边界：Day13 没有把任意 interface 误称为策略、没有把抽象父类误称为模板方法、没有把 HTTP 前置失败测试夸大为多写回滚证据，也没有通过新增 Redis 或上传类凑模式。

## Final 验收记录

- 复盘日无生产/测试业务代码增量；提交只包含 `docs/day13/Final.md`。
- 教材包含前日衔接、真实问题、生产源码走读、项目测试走读、六个 Debug、独立复刻、算法/SQL/Java 题、面试回答和全文末尾的 Day14 输入。
- 最终运行门以 `verify_day.ps1 -DayNN 13` 的真实输出为准；运行前不得称工程交付完成。

## 独立复刻任务

不查看教材，手写一个可注入 `ExportConsumer` 的小执行器，分别以成功/失败 consumer 验证失败清理；再说明一个共享抽象父类为什么未必是 Template Method。复刻只能在临时目录进行，禁止改 CloudDrive 主线代码。

## 面试题与追问

1. Spring singleton Bean 与手写单例差异是什么？追问：为什么 singleton 仍可能线程不安全？
2. 项目的小工厂在哪里？为什么当前不拆独立 Factory 类？
3. `SnapshotConsumer` 为什么是策略的真实例子，而 `DriveEntry` 不能仅因是接口就被称为策略？
4. `AbstractDriveEntry` 为什么不是严格模板方法？追问：什么业务条件下再考虑模板方法？
5. `@Transactional` 的代理为何应放在应用服务？追问：self-invocation 为什么是边界？
6. HTTP parent 缺失测试证明了什么，又没有证明什么？

## 教材自审（Day02/Day05 对照）

| 质量项 | 教材位置 | 已重新打开的真实资产 | 审计结论与未覆盖边界 |
|---|---|---|---|
| 前日衔接与真实问题 | 第 1–4 节 | Day12 `DriveEntryApplicationService`、HTTP 合同链 | 通过；只复盘既有能力，不把 Day14 技术提前写入代码 |
| 项目源码走读 | 第 5、7–8、10、13–14 节 | `SnapshotConfiguration`、`EntryCreateRequest`、`DriveEntryRepository`、`SnapshotTaskExecutor`、`AbstractDriveEntry`、应用服务 | 通过；模板方法仅作反例边界，不伪称生产已有实现 |
| 项目测试走读/测试矩阵 | 第 6、9、11、15 节 | `SnapshotHttpFailureTest`、领域 identity/多态测试、并发失败测试、HTTP 合同测试 | 通过；不把行为结果测试夸大为代理实现类型证明 |
| Debug 与反例 | Debug 1–6 | Bean 替换、创建分支、future 清理、identity、事务代理、Controller 边界 | 通过；均明确不新增业务代码的修复原则 |
| 独立复刻、阶段题与面试 | 第 18–19、17 节 | 临时执行器练习、LC146/207、SQL/Java 题、项目化回答 | 通过；学习者需亲自提交解题与错因记录 |

## 明日唯一输入

输入是本日模式判断表、Day12 的事务/HTTP/MySQL 边界和干净提交。Day14 才进行 Redis 基础实验；不得在此之前新增 Redis 配置、依赖或缓存业务数据。
