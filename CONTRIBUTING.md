# 开发与验证

当前仓库使用 `clouddrive/` 单体后端与 `clouddrive-ui/` Vue 前端。后端入口是 `com.mengde.clouddrive.web.DriveEntryWebApplication`，部署配置位于 `deploy/`。

## 提交前检查

后端需要 JDK 17 和 Maven，在仓库根目录执行：

```bash
mvn -B -f clouddrive/pom.xml verify
```

默认测试排除 `integration` 分组。需要真实 MySQL/Redis 的集成测试应使用独立测试库，配置和执行方式见 [后端说明](clouddrive/README.md)。

前端验证：

```bash
cd clouddrive-ui
npm ci
npm test
npm run build
```

GitHub Actions 分别检查后端测试与打包、前端单元测试与构建。也可在 Actions → CloudDrive CI 中选择 Run workflow 手动执行，每个任务最多运行 15 分钟。修改页面交互后，还应在浏览器中检查相关流程。

### 分享流程回归

准备两个文件名不同的有效分享 A、B，分别记录分享链接和提取码。

1. 未登录打开 A 的免填链接（带 `?p=提取码`），确认自动显示 A 的文件名和大小。
2. 在同一标签页内通过前端路由切换到 B 的免填链接，确认显示 B。再去掉 B 的 `p` 参数，确认重新提示输入提取码。
3. 登录后进入 `/open`，查询 A。请求尚未返回时修改分享码或提取码并查询 B，确认最终只显示 B。清空表单后，等待旧请求返回，确认结果卡片仍然关闭。需要时可使用浏览器开发者工具的网络限速复现。
4. 从有效分享开始下载后修改或清空表单，确认已开始的下载仍使用原文件名。重新查询有效分享并转存，在“我的文件”中确认文件名、大小和配额变化。

## 配置和反馈

真实密码和密钥保存在被忽略的 `local.env.ps1`、`.env` 或服务器环境变量中。提交配置时使用示例文件中的占位值。

反馈问题时提供提交版本、复现步骤、预期与实际结果，以及去除密码、验证码、Token 和个人文件内容的截图或日志。

源码走读与面试准备见 [项目面试指南](docs/interview-guide.md)。
