# Day08 Final：HashMap 底层与 ConcurrentHashMap 边界复盘

## 当日范围与禁止项

Day08 是复盘型 Day。**唯一增量**是三份基于已提交代码的学习交付：本 Final、阶段一教材与仓库外发布回执。复盘对象只限于 `InMemoryEntryIndex` 中的 `HashMap<Long, DriveEntry>`、`DriveEntryCatalog` 的对象级 `synchronized`，以及 `SnapshotTaskExecutor` 中的 `ConcurrentHashMap` 在途任务表。

**禁止**新增、删除或改写业务代码、测试、数据库迁移、Spring Bean、Redis、认证、锁实现或下一日 JVM 内容；也禁止把“换成 ConcurrentHashMap”写成不经验证的性能优化。本日不改变 Day01–07 的 72 测试基线。

## Checkpoint 0 实际起点

输入是 Day07 的 `57a260d`：真实 MySQL + MyBatis + Flyway 已完成，用户在独立 Windows PowerShell 已验证 `mvn -Dmaven.repo.local=C:\Environment\m2repo clean test` 为 **Tests run: 72, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**。仓库中的索引和线程池代码均已在此前提交；Day08 不以“补一段新代码”冒充复盘。

源码事实如下：

- `memory/InMemoryEntryIndex` 声明 `Map<Long, DriveEntry> entriesById = new HashMap<>()`，`add` 以 `entry.id()` 为键执行 `putIfAbsent`；同 id 会抛 `DuplicateEntryException`，测试验证首个对象没有被替换。
- `web/DriveEntryCatalog` 同时持有该索引和 `InMemoryEntryList`，`create/listAll/findRequired/rename` 都是 `synchronized`。它保护的是“按 id 查找”和“按顺序枚举”两个结构之间不被并发请求交叉破坏的当前边界。
- `concurrent/SnapshotTaskExecutor` 声明 `ConcurrentMap<String, CompletableFuture<SnapshotTaskReceipt>> inFlight = new ConcurrentHashMap<>()`。`submit` 先取旧 future，再用 `putIfAbsent` 争抢候选 future；执行结束或拒绝时以 `remove(key, candidate)` 条件删除。

## Checkpoint 1 唯一增量

### 1. HashMap 的一次按 id 插入在本项目中如何发生

`entry.id()` 是 `long`，放入 `Map<Long, DriveEntry>` 时自动装箱为 `Long`。HashMap 先根据 `Long` 的哈希值做高位扰动，再用 `(tableLength - 1) & hash` 定位桶；表长保持 2 的幂，因此位运算可代替取模。目标桶为空就放入首节点；不为空则先比较 hash 和 key 的 `equals`，不等才沿冲突链继续找。

当碰撞节点达到树化阈值且表容量已足够大时，JDK 17 的 HashMap 才会把该桶转为红黑树；表还小则优先扩容。默认负载因子为 0.75，达到阈值会扩容。扩容到两倍时，旧节点只会留在原索引，或移动到“原索引 + oldCapacity”，由 `hash & oldCapacity` 决定。这些规则解释了平均 O(1) 的来源，也说明极端碰撞、扩容和树化仍会影响常数与延迟。

这里要特别区分两个层次：当前索引的键是 `Long`，所以查重依赖的是 **Long 的值相等规则**，不是 `DriveEntry.equals/hashCode`。Day03 的 `AbstractDriveEntry` 确实以 `(ownerId, id)` 实现逻辑相等和配套哈希；它在把 DriveEntry 放进 `HashSet<DriveEntry>` 或作为 `Map<DriveEntry, ...>` 的键时才直接生效。不能因为项目里同时出现了两者，就错误地说 `entriesById` 的桶定位依赖了 DriveEntry 的 `hashCode`。

### 2. 为什么 HashMap 的 putIfAbsent 不能单独当成线程安全承诺

`InMemoryEntryIndex.add` 的 `HashMap.putIfAbsent` 表达的是“已有 id 时不要覆盖”，它满足单线程的重复 id 语义；它不让底层 HashMap 变成并发容器。并发下“查找旧值—决定插入—改变桶/size”的复合过程不能由普通 HashMap 可靠协调，两个请求可能同时观察到空位，甚至在扩容和结构修改时发生更严重的问题。

本项目没有把这层风险藏起来：HTTP 请求可能由多个 Tomcat 工作线程调用，`DriveEntryCatalog` 以同一个监视器串行化目录操作。尤其 `create` 必须同时写 `entriesById` 和 `orderedEntries`；只把第一个字段替换为 ConcurrentHashMap，无法让“索引成功后列表也成功、读者不看到半更新”自动成立。当前粗粒度锁牺牲并行度，换来两个内存结构的操作顺序明确。它不是数据库事务，也不等同于未来的高吞吐方案；数据层和更细粒度并发策略留给后续范围。

### 3. ConcurrentHashMap 在线程池中实际解决什么

`SnapshotTaskExecutor.submit` 解决的是**进程内、任务尚在排队或运行期间**的同 key 合并：第一个调用把候选 `CompletableFuture` 原子登记到 `inFlight`；后续调用读取到同一 key 的 future 后直接返回它。`SnapshotTaskExecutorIdempotencyTest` 用两个 latch 固定时序，断言两次提交得到同一个 future、消费者只调用一次、结束后 `inFlightTaskCount()` 回到 0。

`ConcurrentHashMap.putIfAbsent` 的原子性是按 key 的，而不是给整个执行器添加一个万能事务。不同 key 可以同时入表和被不同 worker 处理；队列满时 `executor.execute` 抛出拒绝异常，代码必须撤销刚登记的 candidate，失败/完成时也必须条件删除。`remove(taskKey, result)` 不是多余写法：它只删除当前 result 仍对应的映射，不误删失败后同 key 的后续提交。

这仍不是持久化幂等：JVM 重启、多个 JVM 或外部副作用已经发生时，内存 `inFlight` 都不能证明“全局只执行一次”。因此 Day08 的正确结论是“当前代码避免本进程同一在途 key 的重复消费”，而不是“系统拥有分布式恰好一次语义”。

## Checkpoint 2 Debug 与边界

1. **错误归因：把 DriveEntry 的 hashCode 说成索引桶依据。** 证据是字段类型 `Map<Long, DriveEntry>` 与调用 `entry.id()`；修正是先写出 Map 的 key 类型，再讨论谁的 `equals/hashCode` 被调用。
2. **错误修复：认为 HashMap 的 putIfAbsent 已经并发安全。** 证据是 `DriveEntryCatalog` 明确为全部目录入口加 `synchronized`，并维护两个集合；修正是把“单结构的按键原子操作”和“多结构业务不变量”分开描述。
3. **错误承诺：把在途去重当成永久幂等。** `SnapshotTaskExecutor` 的类注释明确限定为 process-local coalescing，且 finally 会删除 key；修正是在面试中限定时间窗口、进程边界与副作用边界，并说明跨 JVM 要另行设计。

本日没有代码 diff，因此不新增单测。回归门禁仍必须在用户的本机 PowerShell 真实运行；Codex 当前受限运行时曾在 JDK NIO 内部回环 Pipe 上失败，不能据此篡改代码、跳过或伪造 Maven 结果。

## Final 验收记录

- 已阅读并对照三处源码及两类测试：`InMemoryEntryIndexTest` 覆盖重复 id 不覆盖首对象与缺失查询；`SnapshotTaskExecutorIdempotencyTest` 覆盖同 key 共用 future 和只消费一次；容量、失败与异步快照集成测试覆盖拒绝后的清理、失败后的重试和 Day04 快照协作。
- 已保持 Day01–07 代码与测试不变；Day08 提交仅应包含 `docs/day08/Final.md`。
- 当前可复核测试基线来自 Day07 的真实原始结果：72 run / 0 failures / 0 errors / BUILD SUCCESS。Day08 发布前仍需以 `verify_day.ps1 -DayNN 8` 在本机 PowerShell 重跑，11/11 PASS 才能宣称 Day08 完成。

## 独立复刻任务

不看本文代码，画出 `HashMap<Long, DriveEntry>` 的一次 `putIfAbsent(11L, entry)`：标出 hash、桶索引、碰撞时 equals 的位置以及何时扩容。再画出两个线程同时提交 `daily-snapshot` 的时序：线程 A 成功 `putIfAbsent`，线程 B 返回 A 的 future，worker finally 使用条件 remove 清理。最后回答：为何目录场景不能只把 HashMap 换为 ConcurrentHashMap 就删除 `synchronized`？答案必须包含“索引 + 有序列表是跨结构不变量”。

## 面试题与追问

1. HashMap 的 `(n - 1) & hash` 为什么要求表容量是 2 的幂？扩容后节点为什么只有两种去向？
2. `HashMap` 冲突很多时链表何时树化？为什么容量不足时优先扩容而非立即树化？
3. 本项目 `Map<Long, DriveEntry>` 的查重为什么不调用 `DriveEntry.equals/hashCode`？如果改成 `Set<DriveEntry>`，Day03 的实现又为何必须成对存在？
4. `HashMap.putIfAbsent` 与 `ConcurrentHashMap.putIfAbsent` 的线程安全边界分别是什么？
5. 追问：为何 `DriveEntryCatalog` 已有 `synchronized` 仍不能声称拥有事务一致性？请区分 JVM 内存互斥与数据库事务。
6. 追问：`inFlight.remove(taskKey, result)` 为什么传入 value？如果用无条件 `remove(key)`，在失败重试交错时可能损坏什么状态？
7. 追问：同 key 合并如何降低重复快照写入？为什么 JVM 重启后它不能替代数据库唯一键、消息幂等键或分布式协调？

## 明日唯一输入

Day09 唯一输入 = 本 Day08 的 HashMap/ConcurrentHashMap 复盘材料、Day07 的 72 测试基线与 Day08 验收输出。Day09 只做 JVM 内存、GC、类加载和调优复盘；禁止新增业务代码、并发实现、Spring/数据库/Redis 功能。
