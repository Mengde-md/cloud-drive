# Day30 真实运行证据

日期：2026-10-05，Asia/Shanghai。实现722e176，自审修复d2a757f。证据是实际命令与受控HTTP，不是真实DeepSeek推理。

## 环境与依赖

Microsoft JDK17.0.20、Maven3.9.10、Git2.54.0.windows.1、PowerShell7。Maven缓存`C:\Environment\m2repo`，JDK socket目录参数沿用此前已验证设置。

旧五工程使用本轮独立native MySQL8.0.42/13306和Redis3.0.504/16379，loopback监听，凭据新生成、DPAPI本机保存。临时数据根`C:\Windows\Temp\clouddrive-day30-e81b7646fbe6435e9bc7fb89d13df3ed`，非个人3306/6379。记录的是@@pid_file中的实际MySQL子进程、Java/原生路径与UTC启动时间，不猜启动器PID。最终停止结果由acceptance.md记录。

AI模块无DB/Redis依赖。动态HTTP测试和实包fixture只能127.0.0.1、显式protocolFixture，使用测试专用key及JWT；未读取用户模型密钥。测试JWT签发不是用户真实注册/登录。

## JUnit与合同

| 本轮执行 | 数量 | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| 根clean verify | 111 | 0 | 0 | 0 | BUILD SUCCESS |
| auth clean verify | 22 | 0 | 0 | 0 | BUILD SUCCESS |
| gateway clean verify | 24 | 0 | 0 | 0 | BUILD SUCCESS |
| user clean verify | 15 | 0 | 0 | 0 | BUILD SUCCESS |
| file clean verify | 49 | 0 | 0 | 0 | BUILD SUCCESS |
| AI最终clean verify | 38 | 0 | 0 | 0 | BUILD SUCCESS / exit0 |
| 配置合同 | 28 checks | 0 | 不适用 | 不适用 | PASS |
| 旧可执行包合同 | 6 jars | 0 | 不适用 | 不适用 | PASS |
| 新AI包合同 | 1 jar | 0 | 不适用 | 不适用 | PASS |

221旧工程+38AI=259唯一JUnit；重复跑根/AI不累加。AI为SummaryValidatorTest22、AiSettingsTest4、AiHttpContractTest12；ProtocolFixture是辅助服务器，不计额外测试类案例。原闸门只运行根111，不会自动聚合AI模块。

AI首次clean test36/0/0/0；补输入/配置边界后37/0/0/0。自审新增队列回归真实1/1/0/0失败，修复后重新clean verify为38/0/0/0。

AI HTTP合同捕获真实LangChain4j参数：deepseek-flash、json_object、thinking.disabled、max_tokens512、Bearer测试key、非stream与stream，供应商模拟usage100/50/150。非法schema、LENGTH、缺usage、上游500、错误JWT、无处理同意、大小越界均拒绝；500只有一次请求、响应/日志不泄露私有canary。stream首段在供应商锁存器未放开时已读取；timeout单error、busy429、disconnect后恢复。排队超时后释放worker不再有provider请求，下一请求200。

## 实包与默认生产启动

修复后的`day30-packaged-smoke.ps1`使用真正java -jar，不使用Surefire或test-classes。AI18084、网关19000(prod)、fixture18085；auth/user/file未启动也未请求。

```text
DAY30 AI JAR CONTRACT PASS
PASS: packaged AI and gateway health
PASS: gateway rejects anonymous AI request
PASS: AI direct access rejects forged identity headers
PASS: gateway forwards to executable AI service and real SDK
PASS: usage is marked as fixture with exact decimal estimate
PASS: SSE passes gateway with progress before validated result
PASS: invalid model schema is rejected after gateway authentication
DAY30 PACKAGED SMOKE PASS: 7
```

最后运行目录`day30-packaged-13b34f2a16844f42b018a13d7ae8f617`含checks.json及AI/gateway/fixture输出。最终生产launcher运行目录`day30-default-efa181ba2c104e53a304ef9c1635b71b/ai-735910b12e964533898d3ab47f80426a`：

```text
PASS: production launcher health without an AI API key
PASS: authenticated production request returns 503 AI_DISABLED
STOPPED: ai
DAY30 DEFAULT DEPLOYMENT PASS: 2
```

7+2=9运行断言，与259JUnit分别计。默认生产启动不用真实key，也不联系模型。测试应用/fixture均在finally或原Stop按所属记录停止。

| 实际构建产物 | SHA256 |
|---|---|
| 根Web | 2A9A90D9AFFBC002A30F18AFFE353D77E7DD7D8A0DC7BC8D5E6B2B6EE786CDE6 |
| 根migration | BA7CC988AE3BEAD01238B3CC9A3E1FC607510EDF53EC3F464016E2669E9DD5EF |
| auth | 8210F626EEF384FC22A40803396EC696C19811798A2995C24CF5D5ED6B83FC20 |
| gateway | 228FDA28D55F43E6AB53A0A01EB8F50D3BAF3E99D33FDFDFEA7BF4553A0D1167 |
| user | C0542E509521CBA6E0C3AEA0DB8E4C22CDB2C487E4ED468461A0FF5966FD5139 |
| file | E40AD4BF431AC328DFD869276458D477625CFEFD62351E73D04EEDDC8AD27FD0 |
| AI最终 | 47C536DACE4554385F761ED6CB99886AAAF3E12CA85B4200B121965A4B1CFD71 |

AI和gateway两包真实执行。其余本轮构建并通过包合同，不宣称Day30全部业务包再次实跑。哈希标识实际产物，不承诺可重复构建字节一致。执行原闸门前备份根两包，clean test后只恢复相同已验收字节、不用重新打包掩盖变化，最终比较结果进入acceptance。

## 实际失败、自审与修复

| 原失败/发现 | 真实证据 | 定位与修复 | 最终复验 |
|---|---|---|---|
| PS JAR脚本换行-or | Parser首错Missing closing ')' | 运算符移至前一行尾 | 四脚本语法PASS、真实JAR PASS |
| 首轮实包网关失败 | gateway.out.log无法绑定CLOUDDRIVE_AUTH_URI，exit1 | 给smoke显式auth/file loopback URI，保留prod必填约束 | 完整7项重跑PASS，修复后再7项PASS |
| fixture真/官方URL组合 | 配置审查发现标记与实际端点矛盾 | AiSettings成对校验，仅显式本地fixture允许 | 4配置测试/最终38PASS |
| 排队超时晚调用 | day30-queue-red.log，1/1/0/0，Expired work contacted the provider | StreamCall.start在出队时检查closed | day30-ai-final.log，38/0/0/0；实包9项复跑 |
| SQL辅助探针失败 | ERROR1046，随后ERROR1146 | 显式schema，读取实际flyway_schema_history_user名称 | root最近version10/success1，user1/1 |
| 独立算法辅助NPE | Set.of.contains(null) | 改stream.anyMatch校验词条 | LC139独立JDK9例PASS |
| 教材样本措辞错误 | 第一例合法却被文字混为反例 | 修正为“后三个反例”，同步最新源码/测试/哈希 | 22资产逐字一致及人工复读 |

没有降低断言，没有删除历史失败日志；同一执行者自审，不称第三方独立评审。队列注入反射仅在测试获取自有worker，没有新增生产反射/调试入口。

## 日志索引与复验

本机原始日志根：`C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理`。仅本机证据，不上传日志原文或秘密。

| 日志/资产 | 事实 |
|---|---|
| day30-dependency.log | SDK1.21.0解析成功 |
| day30-ai-tests-first.log / day30-ai-verified.log | 初始36和边界修复37 |
| day30-regression.log | 五工程221、配置28、JAR6、本机CI等价 |
| day30-queue-red.log | 排队超时回归真实红 |
| day30-ai-final.log | 最终38、BUILD SUCCESS |
| day30-packaged-final.log | 修复后真实两包7项PASS |
| day30-default-final.log | 生产默认关闭2项、所属Stop |
| day30-packaged-5fd8e97413954fb1a42b2108d1e218f4/gateway.out.log | 首轮prod网关绑定失败 |
| audit-day30-material.ps1 | 22资产和结构辅助核对，不替代人工审查 |
| WordBreakExercise.java | 独立LC139练习9例，不计JUnit或用户学习门 |

```powershell
# 先显式提供临时DB/Redis与非个人外置凭据
./scripts/day29-ci.ps1
mvn -f ./ai-service/pom.xml "-Dmaven.repo.local=C:\Environment\m2repo" clean verify
./scripts/day30-verify-ai-jar.ps1
./scripts/day30-packaged-smoke.ps1 -JavaHome $env:JAVA_HOME -OutputRoot $AuditOutputDirectory
# 原始verify_day.ps1 -DayNN 30仅在Final/回执提交后执行
```

未运行真实DeepSeek推理/账单/质量评测，GitHub hosted、Docker runtime、Nacos/lb AI、Linux、生产压测、TLS/HA。本日没有重跑显式真实ES5项或四业务服务故障演练，不借前日证据给今日加数。极慢消费者、巨大body、多节点额度、全局账本、完整防注入和远端取消退款均不能由本地合同推断。
