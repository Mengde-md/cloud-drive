# Day09 Final：JVM 内存、类加载与诊断证据

## 当日范围与禁止项

Day09 是复盘与诊断日。唯一增量是：把 Day02–Day05 已存在的对象、集合和线程池，映射到 JDK 17 的内存、类加载、GC 与诊断工具；并保存一次独立 JVM 进程的真实观测证据。本日不新增、删除或改写任何 CloudDrive 生产代码、项目测试、依赖、数据库迁移或配置。

禁止把“执行过 `System.gc()`”写成垃圾回收保证；禁止用一次 histogram 直接断言内存泄漏；禁止随意修改 `-Xmx`、收集器或线上 JVM 参数；禁止提前实现并发锁、Spring 原理、事务、Redis、认证或业务功能。

## Checkpoint 0 实际起点

输入为 Day08 的 `af8f991 docs(day08): verify collection review and runtime evidence`。该提交后的工程已经有：

- Day02 的 `AbstractDriveEntry`，其中 `id`、`ownerId` 是对象身份的稳定字段，`displayName` 可改；
- Day03 的 `InMemoryEntryIndex`，它让对象被 `HashMap<Long, DriveEntry>` 引用；
- Day04 的文件快照，以及 Day05 的 `SnapshotTaskExecutor`，后者拥有线程、队列、`CompletableFuture` 与 `ConcurrentHashMap` 中的在途任务引用；
- Day06–Day07 的 Spring/MyBatis 测试上下文，说明“对象何时可回收”已不能只看一个方法局部变量。

Day09 要回答的不是“JVM 有几个区域”，而是：一个 `DriveEntry` 为什么仍活着、一个 worker 为什么还在、类为什么没有初始化、内存上涨时怎样先抓证据再推断。

## Checkpoint 1 唯一增量

### 1. 用可达性而不是“变量出了作用域”理解对象生命周期

HotSpot 是否回收对象取决于它能否从 GC Roots 通过强引用链到达。常见 Roots 包括正在执行线程的栈帧局部变量、静态字段、存活线程、JNI 引用及 JVM 自身结构。`InMemoryEntryIndex.entriesById` 是长寿命对象字段；只要 index 仍被 `DriveEntryCatalog` 持有，map value 中的 `DriveEntry` 就可达。`SnapshotTaskExecutor.inFlight` 里的 future、线程池队列里的 runnable、闭包捕获的 `List<? extends DriveEntry>` 也都可能延长对象存活时间。

这不等于它们是泄漏。泄漏是“业务本该结束后，引用仍不该存在却持续保留”。Day05 完成或拒绝任务时使用 `inFlight.remove(taskKey, result)`，正是在缩短这条引用链；但要以运行中的 heap、队列长度、任务数和对象 histogram 验证，不能只看源码猜结论。

### 2. 记住规范术语与 JDK 17 实现边界

| 问题 | 可用于面试的准确说法 | 本项目落点 |
|---|---|---|
| 堆 | 对象和数组通常在 heap；分代、region 等布局由收集器决定 | `DriveEntry`、HashMap 节点、`byte[]` 快照缓冲会占用堆 |
| Java 虚拟机栈 | 每个 Java 线程有栈帧，保存局部变量、操作数栈、返回地址等 | `SnapshotTaskExecutor.execute` 在 `snapshot-task-*` worker 的栈中运行 |
| Metaspace | JDK 8 起 HotSpot 使用 native metaspace 存类元数据；“方法区”是规范概念，不应等同于永久代 | Spring 测试启动时装载大量应用/框架类；Day09 不修改其配置 |
| 直接内存/本地内存 | 不等同于 Java heap；NIO、线程栈、JIT code cache 等也会影响进程 RSS | 发现系统内存高而 heap 平稳时，不能只调 `-Xmx` |

### 3. 类加载的三个阶段和一个容易误答的点

概念上有加载、链接（验证/准备/解析）与初始化。初始化才执行静态字段赋值、静态代码块。应用类通常经 Application ClassLoader 加载；JDK 模块相关类由 platform/bootstrap 层处理。双亲委派是默认安全模型，但不能把它说成“永远不能自定义加载器”：容器、插件等场景可有不同策略，关键是保持同名类的身份边界清楚。

独立实验用 `Class.forName(name, true, loader)` 触发 `InitializationProbe` 的静态初始化。输出中静态块只打印一次，证明同一个类加载器下初始化至多一次；它不是“类永远只会加载一份”的证明，因为不同 class loader 可拥有不同的同名 `Class`。

### 4. GC 与调优：先观测，后行动

GC 的核心是从 Roots 识别可达对象，再回收不可达对象；具体暂停、并发标记、分代或 region 策略由收集器实现。JDK 17 常见 G1，但必须以当前进程 flag/日志为准。`young GC`、`mixed GC`、`full GC` 都不是跨收集器完全等价的万能词。

正确排障顺序：先确认症状（GC 次数/暂停、heap used/committed、容器限制、吞吐与延迟）；再抓证据（GC 日志、`jcmd GC.heap_info`、`jcmd GC.class_histogram`、线程 dump、必要时 heap dump）；最后比较多个时间点和业务请求量。参数只在有证据后调整：`-Xms/-Xmx` 是 heap 初始/最大值；`-Xlog:gc*` 记录日志；`-XX:NativeMemoryTracking=summary` 必须启动时开启且有开销。它们不是“内存高就复制粘贴”的配方。

## Checkpoint 2 Debug 与边界

1. **把一次 histogram 当泄漏结论。** 单次 `GC.class_histogram` 只说明当时有多少实例/字节。修复做法是固定负载、间隔采样、观察 Full/老年代回收后是否仍单调保留，并把增长对象反查到引用链；本实验只将其作为对象构成证据。
2. **`jcmd` 报目标不存在或 attach 失败。** 先核对 lab 打印的 pid、进程是否仍在 `holdingSeconds` 窗口内，再运行 `jcmd <pid> VM.command_line`；不要把“进程退出”误判成 JVM 损坏。若堆栈是已知 `PipeImpl → UnixDomainSockets.connect`，按 Day08 保存的 socket 临时目录诊断分支设置子进程参数，不改 pom 或全局环境。
3. **把 `System.gc()` 当成确定性测试。** 它只是建议，可能被忽略或延后；测试 GC 行为会引入时间不确定性。本日实验没有用它做通过条件，验证点是可重复的 class 初始化输出、pid、heap 指标和 `jcmd` 采样。
4. **系统内存高就只盯 heap。** 进程 RSS 还可能受 metaspace、线程栈、direct memory 和 native library 影响。应并看 OS 进程指标、`Thread.print`、NMT（如启动时已开启）与 heap 指标，再决定抓 dump 的类型。

## Final 验收记录

- 独立 lab 保存到文档仓库的 `阶段一/Day09_验收证据`；它不加入 `src`，因此不伪装为业务功能。
- 实验真实启动独立 JDK 17.0.20 进程 `45676`：输出为 `OpenJDK 64-Bit Server VM`、AppClassLoader、`INITIALIZATION_PROBE_STATIC_BLOCK` 一次、`initializationCount=1`。在进程存活窗口成功执行 `jcmd`，保存 `VM.command_line`、`GC.heap_info`、`GC.class_histogram` 与 `Thread.print` 原始输出。heap 采样显示 G1 total 258048K、used 7060K、region size 2048K；histogram 首项为 5403 个 `byte[]`、3440976 bytes。它是该实验时刻的观测，不是泄漏结论。
- 本日不增加项目源码或测试，因此 Maven 回归数保持 Day08 的 76 项；实际 Maven 结果、提交 hash 和机器闸门结果以仓库外发布回执为准。
- 面试证据已在教材给出：GC Roots→Day05 任务引用链、类初始化→静态字段、heap 上涨→采样路径、full GC→证据优先的排障顺序。

## 独立复刻任务

不看教材完成以下四件事：

1. 指出 `InMemoryEntryIndex` 与 `SnapshotTaskExecutor` 中各一条可能保活 `DriveEntry` 的引用链；说明哪一种是正常生命周期、什么业务结束条件会让它变成泄漏嫌疑。
2. 写一个含 static block 的类，用 `Class.forName` 验证同一 class loader 的初始化次数；解释“类加载”与“初始化”为什么不能混说。
3. 启动一个保持 20 秒的 Java 进程，在其退出前用 `jcmd` 抓一项 heap 信息和一份线程信息；把 pid、命令、时间、输出保存下来。
4. 设计“内存持续上涨”的第一轮证据清单，至少包括一个 JVM 指标、一个 OS 指标、一个对象/线程证据，并写明什么结果会推翻你的初始猜测。

## 面试题与追问

1. **什么决定一个对象是否能回收？** 从 GC Roots 的可达性回答，再用 `inFlight → CompletableFuture → runnable/entries` 举例；不能只说“方法结束”。
2. **堆、栈、metaspace 的区别？** 说明线程私有/共享及 JDK 8 后 metaspace 的 native 属性，并指出具体布局依赖 JVM/收集器。
3. **`Class.forName` 一定初始化类吗？** 默认会；带 `initialize=false` 可仅加载。追问：不同 class loader 加载同名类为什么可能不相等？因为类身份含“二进制名 + 定义它的 loader”。
4. **如何定位频繁 Full GC？** 先取 GC 日志、heap/容器限制、暂停、对象 histogram 和负载；再看大对象、晋升、泄漏、内存限制等假设。追问：为什么不能先把 `-Xmx` 调大？它可能掩盖泄漏、超过容器限制或拉长暂停。
5. **`System.gc()` 能用于证明对象已回收吗？** 不能；它只是建议，回收时机和是否执行不确定。
6. **你的项目里哪里可能保留对象？** `HashMap`、`ConcurrentHashMap`、队列、future、线程栈/闭包均可能；以任务 finally 清理和 histogram/队列指标验证。
7. **简历怎样描述这天？** “针对网盘内存索引与异步快照链路，使用 JDK 17 的 jcmd 采集 heap、histogram 与线程证据，按 GC Roots 分析对象保活边界；没有用单次采样或参数猜测替代排障结论。”

## 明日唯一输入

输入是本 Final、Day09 机器闸门通过后的干净提交和独立诊断证据。Day10 只复盘 `synchronized`、`volatile`、Lock、线程状态与死锁排查；不得提前修改 CloudDrive 业务逻辑、Spring、事务或缓存。

## 教材自审（Day02/Day05 对照）

| Day02/Day05 质量项 | Day09 教材位置 | 已重新读取的真实证据 | 结论 |
|---|---|---|---|
| 前日衔接与真实问题 | 原第 1 节、返工第 14 节 | Day08 的 Map/CHM 边界，Catalog 与 executor 的引用链问题 | 通过 |
| 项目生产源码走读 | 第 14.1--14.3 节 | `DriveEntryCatalog.java`、`SnapshotTaskExecutor.java`、`EntrySnapshotStore.java` | 通过 |
| 项目测试走读与测试矩阵 | 第 14.1、14.4、14.5 节 | `SnapshotTaskExecutorFailureTest.java`、`SnapshotTaskExecutorSnapshotIntegrationTest.java`、`DriveEntryMapperTest.java` | 通过 |
| JVM 原理回扣项目 | 第 14.2--14.5 节 | GC Roots、队列/future 引用、heap 与 native 资源、class loader/context 边界 | 通过 |
| 三类以上 Debug | 第 14.6--14.9 节 | 单次 histogram、Map 保活、失败清理、jcmd attach；均包含证据和回归 | 通过 |
| 独立实验不冒充项目实现 | 第 14.10 节 | lab 仅训练 jcmd/class 初始化，明确回扣两条项目引用链 | 通过 |
| 阶段题、独立复刻、面试表达 | 第 14.10 节、原独立复刻与面试题 | LC239、事务/锁 SQL、类初始化 Java 题、独立诊断进程、项目化回答 | 通过 |
| 运行证据与范围边界 | Checkpoint 0/2、Final 验收记录 | 既有 Day09 jcmd 证据与 76 项工程基线；本次未改代码、依赖或 JVM 参数 | 通过 |

**审计结论**：Day09 工程提交和诊断实验的历史事实保持不变；教材已补齐以项目资产为主线的完整闭环。用户人工学习门仍需自行完成，AI 不得代填。
