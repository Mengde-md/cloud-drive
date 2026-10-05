# Cloud Drive Learning

这是一个从零构建的 Java 后端学习仓库。路线会按“完整单体 → 微服务 → 可信 RAG → 受控 Agent”递进；每一天只增加一项可验证能力。

## 当前部署入口（Day29）

当前运行形态为 auth/user/file/gateway 四个独立 Java 包；根工程仅使用 migration classifier 完成 V1–V10，不同时启动历史单体写服务。分环境配置、部署步骤、临时依赖 CI 与验证边界见 [Day29 部署架构](docs/architecture/day29-deployment-and-ci.md)、[Day29 Final](docs/day29/Final.md) 与 [运行证据](docs/day29/runtime-evidence.md)。

五个 Maven 项目未聚合：先配置独立测试 MySQL/Redis，再执行 `scripts/day29-ci.ps1`。私有口令从当前进程环境提供，不从旧源码提取。以下 Day01 小节是历史入门记录，不是当前四服务的部署命令。

## Day01：工程基线

Day01 只交付以下工程基础：

- Git 仓库与 `.gitignore`；
- Java 17 Maven 工程骨架；
- JUnit 5 非业务 smoke test；
- 可运行的启动标识；
- README、工具版本与一次原子 Git 提交。

Day01 不包含任何领域对象、集合索引、业务异常、Spring、数据库、Docker、HTTP、并发或 AI 能力。这些内容必须由后续 Day 的契约逐日引入。

## 本地验证

本机 JDK 路径为 `C:\Environment\jdk-17`。在 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\Environment\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$repo = 'C:\Environment\maven-cloud-drive-learning-m2'
mvn "-Dmaven.repo.local=$repo" clean test
java -cp target/classes com.mengde.clouddrive.CloudDriveApplication
```

其中 Maven 本地仓库作为**命令参数**传入，不写入项目配置文件；这样 Day01 的版本库只保留工程骨架本身，不绑定某台机器的 Maven 设置。

## 学习入口

按 [Day01 Final](docs/day01/Final.md) 依次完成环境检查、非业务测试和 Git 验收。不要提前阅读或创建下一天的源码结构。
