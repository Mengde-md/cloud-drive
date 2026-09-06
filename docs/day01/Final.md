# Day01 Final：工程基线与 JUnit 冒烟测试

## 当日范围与禁止项

今天的目标不是尽早展示功能，而是建立一个能被命令行独立验证的 Java 起点。交付物只有 Git 仓库、`.gitignore`、Java 17 Maven 骨架、一个打印启动标识的入口、JUnit 5 冒烟测试、README、工具版本和一次原子提交。入口输出固定文本，只证明 classpath、编译产物和 Java 运行命令已经连通。测试只检查工程元数据、启动文本和运行时 Java 特性号；它不表达任何后续场景。

范围小是有意为之。以后每次添加增量前，都可以先运行今天的测试，得到一个低成本的健康信号。若它失败，排查对象先缩小为 JDK、Maven、目录约定、依赖解析或前一天的改动；若它通过，才值得继续看当天新增部分。第一笔提交也不是形式：它是“从这里开始，工程曾经真实可运行”的可追溯证据。

今天禁止提前出现任何关系数据、对象层次、集合索引、异常分层、文件读写、框架、网络、数据库、容器、线程、缓存或模型依赖。不要为未来预建空包、空接口或空配置。提前的空壳同样会扩大变量数量，破坏“每天只有一个增量”的验收意义。

## Checkpoint 0 实际起点

起点为一个尚无可依赖历史的目录。先从命令行确认 JDK，而不是只相信 IDE 的语言级别。Maven 中的 `maven.compiler.release` 同时约束可使用的 Java API 与目标字节码；它比只配置 `source` 或 `target` 更不容易产生“本机可编译、另一台机器不可运行”的错觉。JUnit 依赖采用 test scope，因此只参与测试编译和测试运行，不进入应用运行时依赖集合。

本机 JDK 路径为 `C:\Environment\jdk-17`。依赖缓存路径是机器配置，不写入版本库，而通过当前命令传入：

```powershell
$env:JAVA_HOME = 'C:\Environment\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$repo = 'C:\Environment\maven-cloud-drive-learning-m2'
java -version
mvn -version
git --version
mvn "-Dmaven.repo.local=$repo" clean test
```

`JAVA_HOME` 供 Maven 定位 JDK，`Path` 决定当前终端优先解析哪个可执行文件，Maven 本地仓库仅缓存下载构件。三者都不同于 `pom.xml`：POM 才是应由 Git 跟踪、能够在他人机器复用的构建声明。`.gitignore` 忽略 `target/`、编辑器元数据、环境文件、日志和系统杂项，避免把可再生产物、私密信息或个人状态当成源码。

## Checkpoint 1 唯一增量

唯一增量是一条最小闭环：入口输出稳定标识；测试读取项目名、Java 配置和启动文本；Maven 发现并执行测试；README 给出无 IDE 依赖的命令；Git 记录这一刻的状态。入口保持为普通 Java `main`，不初始化任何框架。这样第一次报错时变量极少：失败要么来自命令环境，要么来自 POM，要么来自标准测试目录，而不是被许多未验证的配置混在一起。

smoke test 不替代未来的详细测试。它解决的是“测试通道是否存在”这个更早的问题：测试是否被 Surefire 发现，JUnit 是否解析成功，编译目标是否为 Java 17，最终类是否在预期 classpath。测试中的多个断言使用同一个极小的启动文本和常量，故意避免任何未来需求。后续每天都应先保留这些断言，再新增当天自己的验证；这能在重构或依赖升级时快速发现基础设施回归。

## Checkpoint 2 Debug 与边界

**场景一：JDK 路径错误。** 故意把当前终端的 `JAVA_HOME` 设置为不存在的目录后运行 `mvn -version`。预期是 Maven 无法继续；证据是启动器明确报告路径不是有效 JDK。定位顺序是打印 `$env:JAVA_HOME`、用 `Test-Path` 验证目录、再执行 `java -version`。修复是恢复 `C:\Environment\jdk-17` 并将其 `bin` 放到当前终端 `Path` 前面，不能改 POM 来掩盖环境错误。回归标准是 `mvn -version` 显示 Java 17，随后完整测试通过。

**场景二：测试选择器错误。** 使用不存在的测试选择器，例如 `mvn "-Dmaven.repo.local=$repo" -Dtest=MissingSmokeTest test`。预期是构建失败；证据是 Surefire 报告没有匹配测试。定位时先移除选择器，再核对文件位于 `src/test/java`、包名与路径一致、文件名以 `Test` 结尾。修复是恢复标准 `clean test`，而不是把测试移入主源码或关闭“没有测试即失败”的保护。回归标准是测试数量恢复并且失败数为零。

这两个场景说明相同的排障纪律：保存原始命令和输出，先定位层次，再只修复该层次，最后用原命令回归。环境问题不应靠改源码解决；测试发现问题不应靠删除测试解决。

## Final 验收记录

发布前必须真实执行以下命令，并把原始结果写入仓库外发布回执；不得把预期结果写成已执行结果。

```powershell
$env:JAVA_HOME = 'C:\Environment\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$repo = 'C:\Environment\maven-cloud-drive-learning-m2'
mvn "-Dmaven.repo.local=$repo" clean test
java -cp target/classes com.mengde.clouddrive.CloudDriveApplication
git status --short
git log -1 --oneline
```

Final 判定：至少 5 个 smoke 断言真实通过，入口能打印启动标识，`git diff --check` 无输出，工作区在提交后为空，且回执中有实际 JDK/Maven/Git 版本、测试结果、提交哈希和日志。任一项不成立，不能发布本日 Final。

本次发布前的真实执行记录（2026-09-06）：JDK 为 Microsoft OpenJDK `17.0.20`，Maven 为 `3.9.10`，Git 为 `2.54.0.windows.1`。`mvn "-Dmaven.repo.local=$repo" clean test` 的 Surefire 汇总是 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`；两个测试方法合计执行 7 个非领域断言（`exposesStableBootstrapMetadata` 内 `assertAll` 6 个 + `runsOnTheConfiguredJavaFeature` 1 个，已逐条对照源码核实）。入口实际输出为 `cloud-drive-learning Day01 bootstrap: Java 17`，`git diff --check` 无输出。提交哈希、提交日志和提交后的干净状态记录在本日仓库外发布回执。

> 勘误记录：早期版本曾写"8 个非领域断言"，与源码不符；已修正为 7。

## 独立复刻任务

新建一个空目录，不看当前文件逐项完成：初始化 Git；创建 Java 17 Maven 工程；加入 JUnit 5；写一个只输出工程启动标识的入口；写至少 5 个非领域断言；补齐 `.gitignore` 和 README；执行测试并完成一次原子提交。然后只核查目录、命令、测试和 Git 证据是否闭合。若机器配置不同，记录差异和命令，但不把本机绝对路径提交到项目。

## 面试题与追问

1. `maven.compiler.release` 与单独配置 `source`、`target` 有什么差别？为什么 Java 17 工程优先使用前者？
2. JUnit 为什么应使用 test scope？若它进入运行时依赖，通常意味着什么？
3. Maven 的 `clean` 与 `test` 分别做什么？排查构建为什么常从 `clean test` 开始？
4. `.gitignore` 为什么应忽略 `target/`，而 `pom.xml` 必须提交？请结合今天的工程说明。
5. smoke test 的职责是什么？为什么它不适合承载后续需求的正确性判断？
6. 追问：CI 中测试数量从 2 变为 0 但构建仍显示成功，你如何从目录、命名、Surefire 与命令四个方向取证？
7. 追问：同一 POM 在你的机器通过、另一台机器失败，你会依次比较哪些可复查事实，而不是直接建议重装环境？

## 明日唯一输入

明日唯一输入是本文件 Final、今天的原子 Git commit 和该提交对应的可运行工程。明日开始前必须先运行今天的验证命令；今天不新增任何明日内容。
