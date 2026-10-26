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
npm run build
```

GitHub Actions 分别检查后端和前端构建。修改页面交互后，还应在浏览器中检查相关流程。

## 配置和反馈

真实密码和密钥保存在被忽略的 `local.env.ps1`、`.env` 或服务器环境变量中。提交配置时使用示例文件中的占位值。

反馈问题时提供提交版本、复现步骤、预期与实际结果，以及去除密码、验证码、Token 和个人文件内容的截图或日志。

源码走读与面试准备见 [项目面试指南](docs/interview-guide.md)。
