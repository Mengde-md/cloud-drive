# 桌面单体版同步验证（2026-10-26）

本次将当前仓库的工作树替换为桌面 CloudDrive 单体项目，保留原有 Git 提交历史。当前后端入口为 `com.mengde.clouddrive.web.DriveEntryWebApplication`，源码位于 `clouddrive/`，前端位于 `clouddrive-ui/`。

## 本地验证结果

- JDK 17 后端 `mvn verify`：149 个默认测试全部通过，失败 0、错误 0；可执行 JAR 打包成功。
- 前端按 `package-lock.json` 执行 `npm ci` 后，`npm run build` 成功，使用 Vite 5.4.21。
- 发布文件采用源码清单复制；真实 `local.env.ps1`、邮箱授权码、DeepSeek API Key、构建产物、缓存、个人文档和本地存储未纳入发布。
- 文档中的本机项目绝对路径改为通用路径，配置示例保留占位值。

## 验证范围

后端默认排除标记为 `integration` 的测试。本次没有连接真实 MySQL/Redis 执行完整业务链路，也没有发送真实邮件或调用真实 DeepSeek API。

前端结果证明可构建，不替代浏览器交互验收。测试中主动制造故障的用例会输出预期错误日志，应以测试结果汇总判断是否通过。

GitHub Actions 已改为后端、前端两个检查任务；远端运行结果以对应提交的 Actions 页面为准。
