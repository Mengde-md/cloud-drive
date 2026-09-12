# Day10 Final：并发原语、线程状态与死锁排查

## 当日范围与禁止项

Day10 是原理/排障复盘日。唯一增量是把 Day05 `SnapshotTaskExecutor` 的有界线程池、`ConcurrentHashMap` 在途表和 Day06 多 HTTP 线程入口，映射为 `synchronized`、`volatile`、`ReentrantLock`、线程状态及 deadlock 证据；新增独立实验、教材、Final 与发布回执。

禁止改写 `SnapshotTaskExecutor`、`DriveEntryCatalog` 或任何业务测试；禁止引入 Spring 异步、数据库事务、Redis、消息队列或下一日 IOC/AOP；禁止把 `volatile` 说成复合操作的原子性保证，也禁止把“线程很多”当成死锁结论。

## Checkpoint 0 实际起点

输入为 Day09 的 `f8b5779` 和工程标签 `learning/day09-engineering`。Day09 已说明 JVM 线程栈与 `jcmd Thread.print` 的诊断作用，但没有解释竞争中的可见性、原子性、顺序性，也没有用实际线程 dump 演示锁环。

项目事实：`DriveEntryCatalog` 的目录操作使用对象监视器 `synchronized` 维护 `InMemoryEntryIndex` 与顺序列表的共同不变量；`SnapshotTaskExecutor` 使用固定大小 `ThreadPoolExecutor`、有界 `ArrayBlockingQueue` 和按 key 原子登记的 `ConcurrentHashMap`。它们解决的是当前进程边界内的互斥、容量与同 key 合并，不是分布式事务或永久幂等。

## Checkpoint 1 唯一增量

### 1. 三个并发性质必须分开

- **原子性**：操作不可被中途观察或拆开。`count++` 是读、加、写组合，不因字段 `volatile` 自动变原子；独占锁、原子类或正确的并发结构才可能满足需求。
- **可见性**：一个线程写入后，其他线程何时能看到。监视器的解锁/加锁、volatile 写/读等建立 happens-before 关系。
- **有序性**：编译器和 CPU 可在不破坏单线程语义的前提下重排；同步原语建立必要的内存排序边界。

`DriveEntryCatalog` 的 `synchronized` 是同一把监视器上的互斥和可见性边界。它不能让不同 JVM 共享状态。`SnapshotTaskExecutor.inFlight.putIfAbsent` 是单 key 原子登记；它不能把“登记、入队、消费外部副作用”变成整体事务。

### 2. 独立实验与真实线程证据

Day10 lab 位于文档仓库 `阶段一/Day10_验收证据`，不加入 `src`。它验证四个线程各自执行 2500 次 `synchronized` 增量后得到 10000；使用 volatile stop flag 让 busy worker 停止；再由两个 daemon thread 反向取得 `ReentrantLock`，故意形成锁环。主线程持有窗口，供 `jcmd Thread.print` 采集。

实验中的 deadlock 是受控演示，daemon thread 让 JVM 在主线程结束后退出。生产中绝不依赖 daemon 逃避死锁；正确处理是统一锁顺序、减少嵌套锁、必要时用 `tryLock` 的超时/回退，并在发生时抓线程 dump。

### 3. Lock 的取舍

`synchronized` 语法简洁、自动释放监视器，适合 `DriveEntryCatalog` 这种小范围、明确的不变量。`ReentrantLock` 适合确实需要可中断获取、定时 `tryLock`、公平性或多个 `Condition` 的场景，但必须以 `try/finally unlock()` 释放。不能为了“展示设计”把所有 synchronized 换成 Lock。

## Checkpoint 2 Debug 与边界

1. **把 volatile counter 当线程安全。** `volatile int count; count++` 仍会丢更新；修复是按不变量选择锁、`AtomicInteger` 或并发容器。Day10 的 volatile 只用于 stop signal。
2. **误以为 `synchronized` 保护两个实例。** 它锁的是具体 monitor；两个 `DriveEntryCatalog` 实例不是一把锁。跨 JVM 或跨节点需要后续 Day21 的协调设计，不能提前承诺。
3. **`jcmd Thread.print` 没有 deadlock。** 先核对 lab 输出是否到达 `deadlockExpected=true`，以及采样 pid 是否仍在 hold 窗口；锁环必须由两个线程都先拿到第一把锁再请求第二把锁。不要只看 WAITING/BLOCKED 关键词。
4. **手写 Lock 忘记 unlock。** 所有成功锁定路径都要在 finally 释放；异常路径不释放会把“偶发卡顿”制造成真实阻塞。

## Final 验收记录

- 本日项目生产代码、项目测试、pom 与迁移不变；独立实验源和 `jcmd Thread.print` 原始输出归档到文档仓库证据目录。
- 实验真实输出 `synchronizedCounter=10000`、`volatileWorkerStopped=true`；进程 `57452` 的 `jcmd Thread.print -l` 输出 `Found one Java-level deadlock`，明确标出 `day10-deadlock-left` 等待 right 持有的 synchronizer，right 等待 left 持有的 synchronizer。其输出只能证明实验设计的锁环，不能证明 CloudDrive 当前存在死锁。
- Maven 回归保持此前 76 项；真实测试、提交 hash 与机器闸门结果以发布回执为准，不能用本文叙述替代。
- 面试证据已覆盖：JMM 三性质、当前项目两种同步边界、线程状态与线程 dump、死锁预防和 Lock 取舍。

## 独立复刻任务

1. 用自己的话说明 `count++` 为什么不是原子操作，并分别说出原子性、可见性、有序性。
2. 把 `DriveEntryCatalog` 的一个 `synchronized` 方法写成“它具体保护什么共同不变量”的一句话，不许只说“线程安全”。
3. 写两个 daemon thread 制造锁环；在进程退出前保存 `jcmd Thread.print`，圈出等待的锁、持有者和 JVM 的 deadlock 结论。
4. 为“两个锁嵌套”给出一种统一锁顺序方案和一种 `tryLock` 超时回退方案，并说明何时不应使用后者。

## 面试题与追问

1. **volatile 能保证什么？** 可见性和一定的有序性，不保证 `count++` 等复合操作原子性。追问：stop flag 为什么适合 volatile？单写、循环读，写后读者需尽快看到状态。
2. **synchronized 的底层语义？** 对同一 monitor 的加锁/解锁提供互斥，并形成 happens-before；回答必须带“同一对象/同一类锁”的范围。
3. **Lock 与 synchronized 怎么选？** 默认优先简单监视器；需要可中断、超时、公平或多个条件队列时选 Lock，并 finally unlock。
4. **如何定位死锁？** 先保存 thread dump，用 `jcmd <pid> Thread.print`/jstack 找 JVM 的 deadlock 区块，沿每个线程等待锁和锁 owner 还原环，再确定锁顺序修复。
5. **BLOCKED、WAITING、TIMED_WAITING 区别？** BLOCKED 常等待 monitor；WAITING 等待无期限协调；TIMED_WAITING 带超时。状态本身不是死锁结论。
6. **项目中 ConcurrentHashMap 后还为什么有 synchronized？** 它们处理层次不同：前者是单 key 表操作；目录创建需保持索引与顺序列表跨结构一致。
7. **简历怎样讲？** “针对网盘内存目录与异步快照链路，我区分 monitor 的共同不变量和 ConcurrentHashMap 的单 key 原子性；通过 JDK17 thread dump 复现并定位锁环，形成死锁证据与锁顺序修复方案。”

## 明日唯一输入

输入是 Day10 Final、独立 deadlock dump、干净提交与机器闸门。Day11 只学习 IOC/AOP/Bean 生命周期、配置绑定和 Profile，不接事务、不加认证、不改写当前业务链。

## 教材自审（Day02/Day05 对照）

| 质量项 | Day10 教材位置 | 真实证据 | 结论 |
|---|---|---|---|
| 前日衔接与真实问题 | 第 13 节 | Day05 executor、Day08 CHM、Day09 线程/队列诊断 | 通过 |
| 项目生产源码走读 | 第 14--17 节 | `DriveEntryCatalog.java`、`SnapshotTaskExecutor.java`、线程工厂 | 通过 |
| 项目测试走读与矩阵 | 第 18 节 | Idempotency、Capacity、Failure 三组 executor 测试 | 通过 |
| 原理回扣项目 | 第 15--19 节 | monitor、CHM、队列、happens-before、线程状态 | 通过 |
| 三类以上 Debug | 第 20 节 Debug 1--Debug 5 | volatile、同步范围、拒绝清理、死锁判断、Lock 释放 | 通过 |
| 复刻、阶段题、面试 | 第 21 节 | 有界任务执行器、LC1114、事务锁 SQL、原语选型 | 通过 |

**审计结论**：Day10 工程事实不变；教材已补齐从项目源码、测试和线程 dump 证据出发的完整复盘。人工学习门仍由用户完成。
