# Day29 Runtime Evidence

## Provenance

2026-10-05，本轮指定代码仓库，be0f06a实现及6969e12目录保护。原始日志在协作工作区`C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理`，不在用户复刻仓库。无GitHub push、无托管CI触发。

依赖是本次新创建的MySQL8.0.42/13306、Redis3.0.504/16379，凭据新生成并仅用于测试；ES为本次启动的审计8.10.4/19200。原有MySQL/Redis实例未改变。native依赖与工作流Linux/Redis7.2不能视为同一环境验证。

| 原始证据 | 实际结果 |
|---|---|
| day29-ci-first.log | 根111通过；auth22中2失败，401配置污染 |
| day29-ci-verified.log | 根111/auth22/gateway23/user15/file49，五次BUILD SUCCESS，exit0 |
| 最终day29-config-tests执行输出 | 28项，exit0；6969e12追加目录保护负例 |
| day29-live-search.log | 5失败，端点变量名错误 |
| day29-live-search-verified.log | 共享临时schema被执行者重置，4错误，不能当通过 |
| day29-live-search-final.log | 串行最终5/0/0/0，exit0 |
| day29-packaged-smoke.log | 上传/下载已成功，Stop日期比较误拒绝，exit1 |
| day29-packaged-smoke-verified.log | 完整11项重启验证，exit0，全部本轮进程清理 |
| day29-packaged-fault.log | prod/-jar 28项，exit0，所属应用清理与环境恢复 |
| Day29WorkflowAudit执行 | SnakeYAML结构解析、最小权限、服务账号和action SHA合同PASS |
| compose config --quiet | exit0；docker info为pipe不可用、exit1 |
| HouseRobberExercise.java | 9项独立算法练习PASS，不算JUnit |

220常规+5真实ES=225唯一JUnit，配置/包/运行独立计数。失败原记录保留。最终机器闸门记录在新建acceptance.md，本文件不预填其结果。

## Migration And Packaged Storage

空临时库的migration JAR真实输出`Migration succeeded: executed=10`，四服务重启后为`executed=0`。两个history只读SQL结果为root_version10/user_version1，failed_migrations均0。

最终smoke目录：`day29-packaged-ad163ed8670948f0bdcb4f552611209d`。完整11项检查：

```text
PASS: packaged service health 8081
PASS: packaged service health 8083
PASS: packaged service health 8082
PASS: packaged service health 9000
PASS: prod gateway route diagnostics disabled
PASS: packaged gateway registration
PASS: multipart upload uses actual storage and quota chain
PASS: download bytes match before restart
PASS: root migrations are idempotent on restart
PASS: persistent object survives all four process restarts
PASS: Range remains 206 after restart
```

数据目录在本次独立临时服务根下，不在Maven输出目录。此实验仅证明同一DB和对象目录的进程重启；不是磁盘容灾、备份恢复或对象/元数据的跨介质事务证明。

## Packaged Fault Drill

实际命令为`scripts/day28-fault-drill.ps1 -PackagedJars`。新分支只使用运行JAR启动四服务，prod profile。只读JDBC probe的driver从file包BOOT-INF/lib提取，没有Surefire/test依赖。

最终目录：`day28-runtime-20261005-124125`，28项完整输出：

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
```

远程Timer COUNT3/TOTAL_TIME0.3771305秒/MAX0.295008秒。停user写503、同trace三次reserve ERROR、pending1，读200；重启后下一写201、release/reserve关联、pending0。没有后台恢复时限承诺。

```text
pending=1
EXPLAIN type=ref key=uk_drive_entries_active_owner_parent_name rows=1 extra=Using where; Using filesort
pending=0
EXPLAIN type=ref key=uk_drive_entries_active_owner_parent_name rows=2 extra=Using where; Using filesort
```

file线程转储来自PID2580，2026-10-05 12:43:18，OpenJDK17.0.20+8-LTS。没有死锁、Linux执行、压测或P99结论。

## Tested Artifact Identity

六包均具有匹配Start-Class和Boot JarLauncher，常见测试库/test-classes检查为0。以下为实际运行阶段产物，不承诺重复构建字节相同。

| JAR | SHA256 |
|---|---|
| root历史Web（仅审计未启动） | 4D5068A47B0A4FCCDD8C1F8105E8F77064A999CE119684EB834F1A0D32D71F0D |
| root migration | 1C41FBE8A1E5B4FDCEDA31B6809B114365683ADC2FD1C539435386BEBEF5948F |
| auth | 4EB30BA08AA323A96D4BB31ADE364081A4A09AEF05986CBD2669B732E8ABC728 |
| user | F0E8ADFF8AE3E1D6BF2C70BA5CE6F4692526AA0456AB9BB5CC21C840B72D2C0F |
| file | 143F63397CB4CB14C2ABAF43431A9C61EFB706194FADD7198B6B1B9054283015 |
| gateway | 2831E1737A4492C5CE9C698219A9B4B09C4B4A6420712E333945D0FE8246A973 |

## Limits

未运行托管CI、Docker Compose runtime、Nacos/lb、Linux部署、生产压测、备份恢复、公网TLS/HA。secret check是当前树针对性合同，不是历史或通用密钥扫描。曾被提交的口令须由实际服务拥有者轮换。

共享schema与DDL账号、HS256、请求驱动补偿、对象/数据库非原子、ES/SQL语义差异与1000文档上限继续保留。人工学习门未代填；工程机器结果与最终依赖清理另见acceptance.md。
