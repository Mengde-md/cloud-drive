# Day28 运行证据

日期：2026-10-04。实现 `ff9d2bd`，末尾空行清理 `57712b8`。本文件记录真实运行，不把后续原始闸门预填为通过。

## 环境与方式

Microsoft JDK 17.0.20、Maven 3.9.10、Git 2.54.0.windows.1、PowerShell 7；MySQL/Redis 为既有本机学习环境。五个独立 Maven 工程使用既有缓存。凭据仅注入测试/演练进程，不复制到本日代码或材料。

四进程运行生产 main + Surefire 提供的依赖 classpath，剔除 test-classes，但仍包含测试作用域 JAR，不是生产打包发布验证。网关 9000、auth 8081、file 8082、user 8083 均绑定回环；根单体 8080 不运行。脚本固定服务端口、gateway/Feign URI、default profile 与 Nacos 禁用，不把默认环境变量视为隔离保证。

## JUnit 矩阵

| 命令目标 | Tests | Failures | Errors | Skipped | Exit |
|---|---:|---:|---:|---:|---:|
| 根 clean test | 108 | 0 | 0 | 0 | 0 |
| auth-service clean test | 22 | 0 | 0 | 0 | 0 |
| gateway clean test | 23 | 0 | 0 | 0 | 0 |
| user-service clean test | 15 | 0 | 0 | 0 | 0 |
| file-service clean test | 49 | 0 | 0 | 0 | 0 |
| file `-Dtest=ElasticSearchLiveIT test` | 5 | 0 | 0 | 0 | 0 |

217 个常规 JUnit + 5 个显式集成 = 222。file XML 在两次运行后共 54 个案例，不是默认 clean test 的数量。常规矩阵完成于 20:57:22，LiveIT 完成于 21:02:32；纯空行清理未改变字节码语义。

原始本机日志：`day28-root-tests.log`、`day28-auth-service-tests.log`、`day28-gateway-tests.log`、`day28-user-service-tests.log`、`day28-file-service-tests.log`、`day28-es-live-tests.log`。日志保留在本次协作工作区，不把含机器环境的完整测试输出提交公开资料。

## 真 ES 与慢调用

真实集成重用了已校验 SHA512 的 ES 8.10.4 官方 Windows ZIP，回环 19200/19300，独立临时索引。启动后运行五项现有 LiveIT，全部通过，再核对路径和 cluster_name，仅停止本轮 ES PID 41284。旧版本与关闭认证仅为隔离教学兼容基线，不是生产部署建议。

FeignTelemetryTest 使用真实 JDK HttpServer：60ms 延迟超过 20ms 测试阈值，验证 slow=true；读超时保持 RetryableException，并记录 ERROR。MapperTelemetryTest 使用真实 MySQL `SELECT SLEEP(0.06)`，计时至少 50ms 且 count=1；本轮日志一次为 67ms。错误表查询仍抛 DataAccessException，参数/结果哨兵不进事件或标签。

这里的 Mapper duration 是 Executor 总调用时间，含连接/锁/映射等，不是数据库服务端 slow_query_log。后面的四进程阈值设 0 仅验证事件分支，不能作为真实变慢证据。

## 四进程故障演练

发布脚本为 `scripts/day28-fault-drill.ps1`；只读 SQL 工具为 `scripts/Day28DatabaseProbe.java`。最终真实输出是 `day28-runtime-audit-verified.log`，证据目录 `day28-runtime-20261004-210424`。

```text
PASS: auth ready
PASS: user ready
PASS: file ready
PASS: gateway ready
PASS: gateway 401 has a fresh unspoofable correlation id
PASS: real account registration
PASS: real JWT login
PASS: file write succeeds through real quota chain
PASS: gateway logs same write trace
PASS: file logs same write trace
PASS: user logs same write trace
PASS: auth logs same write trace
PASS: user-to-auth initialization preserves the same trace
PASS: metrics rejects anonymous and ordinary JWT
PASS: metrics is not routed through public gateway
PASS: protected actuator exposes actual remote Timer
PASS: unreachable ES yields explicit successful MYSQL degradation
PASS: degraded HTTP 200 is distinguishable in metrics
PASS: user process outage returns 503 with trace
PASS: quota retries appear as three reserve errors on one trace
PASS: outage database pending count and real EXPLAIN
PASS: reads remain available during quota outage
PASS: next write recovers after provider restart
PASS: recovered database pending count and real EXPLAIN
PASS: recovery release and reserve share new request trace
PASS: owned Java process thread dump collected
PASS: mapper structured events emitted with correlation
PASS: structured events omit credential and filename canaries
AUDIT PASSED: 28 checks
Stopped only drill-owned applications and restored process configuration.
```

所有命令会话已取得 exit 0。最终成功后再次检查业务及 ES 端口无监听；没有按名称批量终止其他 Java 进程。

### 指标、SQL 与线程证据

实际 Actuator 返回 `name=clouddrive.remote.calls`、`baseUnit=seconds`、COUNT=3、TOTAL_TIME=0.19131759999999998、MAX=0.1466345。有限 tags 为 client(AuthIdentityClient/UserQuotaClient)、operation(identity/reserve/commit)、outcome(SUCCESS)。只表示 file 发出的三次调用，不包括 user 自己到 auth 的一跳。

停掉本轮拥有的 user 进程后，同一 trace 下 reserve 三次 ERROR，HTTP 503；只读探针得到 pending=1。恢复 user 后下一次写入执行 release/新 reserve，HTTP 201；探针得到 pending=0。这个场景是 provider 完全停机，不冒充“远端已提交但响应丢失”的故障窗口。

```text
outage-sql.txt:
pending=1
EXPLAIN type=ref key=uk_drive_entries_active_owner_parent_name rows=1 extra=Using where; Using filesort
recovered-sql.txt:
pending=0
EXPLAIN type=ref key=uk_drive_entries_active_owner_parent_name rows=2 extra=Using where; Using filesort
```

文件搜索 SQL 的 owner 前缀参与访问路径，LOCATE 仍过滤、排序仍可能 filesort；小样本估计不是性能 SLA。对本轮 file PID 55740 执行 jcmd Thread.print -l，获得 21:05:31 的真实 OpenJDK 17 线程转储。没有声称本轮发生死锁、CPU异常或执行 Linux 命令。

ES 故障演练指向预先确认无监听的 19201；此时 SQL 搜索成功且 source=MYSQL/degraded=true，search Timer 的 MYSQL count 可见。它与独立真实 ES 的五项测试分开，不声称本轮 HTTP 演练启动后再次停止 ES。

## 失败与修复记录

前两次演练在真实 Actuator 已返回 200 时，脚本 JSON 断言失败。第二次保存 Content 发现 PowerShell 将 vendor JSON 返回为 byte[]；改为按类型 UTF-8 解码后再解析，第三次 28 项全过。没有更改业务 Timer 或降低断言。

独立代码审查发现脚本未锁定端点与一个清理错误会中断环境恢复，已修复为 CLI 固定路由/端口、逐进程独立清理、路径/StartTime 校验与必执行的环境恢复。只读复审 PASS。原环境相关 URI/PORT/Profile 无值，未发现前两次调用指向外部实例；每次失败后均清理了本轮应用。

## 限制

没有 Nacos lb、Docker 运行、真实网络分区、性能压测、全链 deadline、APM span、异步 MDC 或 metrics 长期存储验证。结构化事件哨兵断言不等于所有第三方 stdout/stderr 都经过脱敏审计。DB/Redis 依赖受控学习环境，脚本不是外部配置的安全沙箱；测试账号与持久化演练对象不做无差别删除。
