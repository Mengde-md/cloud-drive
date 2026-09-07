# Day03 Final：按 ID 索引、相等性与业务失败

## 当日范围与禁止项

Day03 在 Day02 的顺序条目列表之外新增按 ID 查询的内存索引，并定义条目的逻辑相等性。新增内容仅包括 `HashMap` 索引、`equals`/`hashCode`、`HashSet` 去重验证和重复/缺失条目的最小业务异常。本日不包含文件 IO、Spring、数据库、并发或缓存。

## Checkpoint 0 实际起点

输入是已提交的 Day02：条目对象已保证构造约束，顺序列表只负责展示和快照，回归基线为 15 个测试通过。它尚不能按 ID 直接定位，也没有规定两个不同对象何时代表同一条目，更没有把重复和缺失转成可识别的失败。

## Checkpoint 1 唯一增量

`AbstractDriveEntry` 以不可变的 `ownerId` 与 `id` 实现身份相等性，并用同一对字段计算哈希码；名称和大小不参与，改名不会破坏哈希容器位置。`InMemoryEntryIndex` 用 `HashMap<Long, DriveEntry>` 保存全局 ID 到条目的索引；`putIfAbsent` 防止重复键覆盖旧值，`getRequired` 将缺失转换为 `EntryNotFoundException`。`DuplicateEntryException` 与它都只是 Java 领域边界，不承担 HTTP、日志或持久化职责。

## Checkpoint 2 Debug 与边界

Day03 重点检查五类失败：只重写 equals 会使逻辑相等对象可能进入不同桶；可变名称加入哈希身份会造成改名后无法定位；`put` 会覆盖重复键而 `putIfAbsent` 会保留旧值；返回 null 会把缺失故障推迟到调用点；过宽的静态扫描会把 Stream 的 `map()` 误判为 `Map`。测试分别验证相等对象哈希一致、HashSet 去重、重复写后原对象仍在、缺失键异常和 null 入口失败。

## Final 验收记录

发布前执行 `git diff --check`、Day03 边界扫描和 `mvn clean test`。全量测试必须包含 Day01 smoke、Day02 四个测试类和 Day03 的相等性/索引测试；真实输出、提交哈希、环境版本和工作区 clean status 写入仓库外发布回执。Day03 测试没有把文件、线程、Web、数据库或 Redis 伪装为已验证能力。

## 独立复刻任务

从 Day02 的条目模型开始，写一个按 ID 索引的容器：重复 ID 不替换旧值、缺失 ID 抛最小业务异常；为两个独立对象定义稳定身份相等性，并用 HashSet 验证去重。先写相等、哈希、重复、缺失、改名后仍可定位五类测试，再写实现。禁止加入文件读写、Spring 或数据库。

## 面试题与追问

1. `==` 与 `equals` 分别比较什么？
2. 为什么 equals 为真时 hashCode 必须相同？追问：反向为何不成立？
3. HashMap 先做 hashCode 还是 equals？
4. 为什么名称不参与条目身份？
5. 可变字段作为 HashSet 键会怎样？
6. `put` 与 `putIfAbsent` 的差异？
7. 为什么缺失资源用业务异常而不是返回 null？
8. HashSet 如何完成去重？
9. 为什么对象相等性与 Map 的全局 ID 键要分别说明？
10. Day03 为什么禁止文件 IO？

## 明日唯一输入

Day04 只以当前已验证的内存对象、顺序列表、Map 索引、稳定相等性和业务失败为输入，解决“进程退出后状态消失”的 JVM/IO 文件快照问题。
