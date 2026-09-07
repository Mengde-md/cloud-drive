# Day05 Final：并发与线程池最小实验

## 当日范围与禁止项

Day05 的唯一增量是在 Day04 `EntrySnapshotStore` 的同步文件快照外侧增加一个纯 JDK 的异步任务边界。新增 `SnapshotTaskExecutor`、`SnapshotConsumer` 与 `SnapshotTaskReceipt`：调用方以任务键提交一份条目快照，线程池在后台调用 Day04 的写入动作；同一任务键在“已入队或正在执行”的窗口内共享同一个 `CompletableFuture`，不同任务键可以由多个工作线程并行处理；工作线程和有界队列都由构造参数明确限定，队列满时采用 `AbortPolicy` 立即拒绝。

本日的幂等证据有严格边界：它是单 JVM、内存中的重复提交合并，不是数据库唯一约束，不跨进程，不跨重启，也没有消息队列、分布式锁、Spring `@Async`、调度平台或 HTTP 接口。`EntrySnapshotStore` 仍然只负责一次快照的原子替换；`SnapshotTaskExecutor` 不理解文件格式，也不修改 Day02—Day04 的领域模型。Spring、数据库、Redis、鉴权和框架级并发设施均未引入。

## Checkpoint 0 实际起点

Day04 的快照能够以临时文件和原子替换保存一组 `DriveEntry`，但 `save` 是同步调用：调用线程必须等磁盘写完才能继续。把这段耗时 IO 直接挪到“新线程”看似简单，却会立刻出现四个需要说清的工程问题。第一，瞬时高峰会无限制创建线程还是排队？第二，两个调用方同时要求同一逻辑快照时会不会重复写？第三，队列满、写入失败和关闭时调用方能得到什么事实？第四，调用方在提交后继续修改列表，工作线程看到的是哪个版本？Day04 的 28 个测试是本日唯一输入和回归基线。

## Checkpoint 1 唯一增量

`SnapshotTaskExecutor` 使用 `ThreadPoolExecutor`，核心线程数与最大线程数保持相同，队列固定为 `ArrayBlockingQueue`，拒绝策略固定为 `AbortPolicy`。这不是为了给参数制造“标准答案”，而是为了让本实验的容量边界可观察：一个工作线程加容量为一的队列，第三个不同键任务一定被拒绝；释放前两个任务后，原来被拒的键可以再次提交。生产系统应依据任务耗时、到达速率、内存预算和压测结果计算参数，本日不宣称这一组小数值适用于生产。

提交前先 `List.copyOf` 冻结条目集合，再通过 `ConcurrentHashMap.putIfAbsent` 竞争任务键的所有权。抢到所有权的调用方创建候选 `CompletableFuture` 并向线程池投递；没抢到的调用方返回已存在的 future，因此同键并发调用只执行一次。若 `execute` 因队列已满抛出 `RejectedExecutionException`，候选键会立即从 `inFlight` 移除，防止一个从未执行的任务永久堵住后续重试。工作任务在 `finally` 中按“键和值都相同”删除映射，避免旧任务误删后来同键任务的状态。

写入动作抽象为 `SnapshotConsumer`。它允许测试替换成被锁存器控制的慢动作，也允许集成测试把 Day04 的 `EntrySnapshotStore::save` 作为消费者。完成时 `SnapshotTaskReceipt` 记录任务键、冻结列表大小和耗时；失败时 future 以原始异常完成，且键被清理，调用方可以在上层决定是否重试。`close()` 先拒绝新任务，等待已有任务结束；超时才中断剩余任务。关闭不等同于可靠投递，也不能代替进程退出时的数据恢复方案。

## Checkpoint 2 Debug 与边界

并发测试不用 `Thread.sleep` 猜测时序，而是用 `CountDownLatch` 建立可控证据。第一个任务真正开始后才提交同键请求，断言两个调用拿到同一个 future、消费者计数只为一；两个不同键任务在两个工作线程中同时到达起始栅栏，证明它们不是被同键合并；单工作线程和一格队列被锁存器占满后，第三个键必须立刻被拒绝。失败测试人为抛出 `IOException`，验证异常通过 future 暴露、`inFlight` 被清理、同一键随后能再次成功提交。可变列表测试在任务开始后篡改原列表，验证消费者只看到提交时冻结的一项。

第一次在主仓库的 Unicode 路径执行 Maven 时，`testCompile` 报出连 Day01—Day04 已提交类型也无法解析的错误；清空本会话内可能注入变量后现象仍在。根据交接手册，没有修改 `pom.xml`、没有使用 legacy `javac`、没有创建 junction。随后在 `C:\\Environment\\cloud-drive-day05-verify` 的 ASCII 临时 worktree 中复验：先确认 Day04 的 28 个测试通过，再复制 Day05 变更；该环境首先暴露了测试中 `List<? extends DriveEntry>` 不能 `add` 子类的真实泛型错误。修正为对同一不可变列表调用 `clear()` 并断言拒绝后，ASCII worktree 运行全部 36 个测试通过。这区分了真实测试问题与主仓库路径下的终端问题，不能把任一方的结论扩大成另一方。

静态边界检查还应拒绝 `org.springframework`、数据库驱动、Redis 客户端、`@Async`、定时调度和消息中间件引用。本日只使用 `java.util.concurrent` 中的 JDK 工具；线程池解决的是“有限资源下怎样执行本进程任务”，不是事务、权限、可靠消息或跨节点协调。

## Final 验收记录

新增 3 个生产类和 4 个 Day05 测试类，共 8 个测试方法；它们与 Day01—Day04 的 28 个回归测试一起在 ASCII 验证 worktree 实际运行。Maven 编译 15 个生产源文件和 12 个测试源文件，Surefire 输出：`Tests run: 36, Failures: 0, Errors: 0, Skipped: 0`，并以 `BUILD SUCCESS` 结束。新增测试覆盖同键 future 合并、不同键双工作线程、队列满拒绝与恢复提交、非法容量、失败后键清理与重试、提交列表冻结、Day04 快照异步集成、关闭后拒绝提交八类场景。

提交前将执行 `git diff --check`、范围扫描和完整 Maven 复验；提交后写入发布回执并运行 `verify_day.ps1 -DayNN 5`。该脚本的任何 FAIL 都表示 Day05 未完成，不能以教材已写或 ASCII 验证通过替代。主仓库路径的 Maven 异常及其 ASCII 对照会如实记录在回执，不会通过更改依赖或偷偷跳过测试来掩盖。

## 独立复刻任务

1. 不看实现，写一个容量为“一个工作线程加一个队列槽”的 `ThreadPoolExecutor`。让首个任务停在锁存器上，提交第二个任务进入队列，再提交第三个不同键任务并证明它被拒绝；释放锁存器后重新提交第三个键，解释为什么它此时可以成功。
2. 为“同一快照任务只在进行中合并”的规则写一个最小协调器。两个线程同时提交同键，必须取得同一个 future；让第一次消费者抛出异常，证明映射被清理且下一次提交能重新执行。最后说明这为什么不是跨 JVM 的幂等保证。

## 面试题与追问

1. 为什么本日不用“来一个请求就 `new Thread`”？追问：线程创建、栈内存和上下文切换在高峰下怎样相互放大？
2. 核心线程数、最大线程数、队列和拒绝策略各控制什么？为什么没有万能参数？
3. `ArrayBlockingQueue` 与无界队列在故障表现上有什么差异？
4. `ConcurrentHashMap.putIfAbsent` 在同键任务合并中解决了什么竞争？追问：为什么仍要在拒绝和 finally 中清理键？
5. 为什么提交前要 `List.copyOf`？它保护的是对象本身还是集合结构？
6. `CompletableFuture` 的异常如何传回调用方？为什么不能只在工作线程打印异常？
7. 队列满时为什么选择明确拒绝，而不是无限排队？调用方接到拒绝后应怎样设计？
8. 本日“幂等”与数据库唯一键、消息消费幂等分别有什么边界？
9. `close()` 的 `shutdown` 与 `shutdownNow` 有何不同？为什么关闭策略必须写进接口契约？
10. 为什么并发测试优先用 `CountDownLatch` 而不是 `Thread.sleep`？追问：怎样让竞争测试既可重复又不掩盖真正死锁？

## 明日唯一输入

Day06 的唯一输入是截至 Day05 已验证的领域对象、内存索引、文件快照和受容量约束的进程内异步任务边界。Day06 只新增 Spring MVC 与 HTTP 契约，必须用真实 2xx、4xx、5xx 响应证明边界；MySQL、Redis 与鉴权仍属于禁止提前项。
