# 开发与问题反馈指南

## 开发环境

- JDK 17、Maven 3.8+。
- 启动服务时需要 MySQL、Redis 和 Nacos，配置与启动步骤见 [README](README.md#快速开始) 和 [部署手册](deploy/README.md)。
- 前端页面位于 `frontend/`，部署方式见 [部署说明](DEPLOY.md)。

## 提交前验证

在仓库根目录执行：

```bash
mvn -B verify
git diff --check
```

`mvn -B verify` 与 [GitHub Actions](.github/workflows/ci.yml) 使用的构建和测试命令一致。修改登录、上传、分享等用户流程后，还应在浏览器中检查正常操作和失败提示。

提交时说明修改解决的问题、操作后的预期行为，以及实际执行的验证。数据库、Redis、Nacos 密码和 AI API Key 使用环境变量或本地私有配置，不要加入提交。

## 按模块查找代码

| 修改内容 | 位置 |
|---|---|
| 登录、注册与会话 | `auth-service/` |
| 用户信息与存储空间 | `user-service/` |
| 上传、下载、分享与回收站 | `file-service/` |
| 文档解析、摘要与问答 | `ai-service/` |
| 请求路由与网关鉴权 | `gateway/` |
| 公共响应与异常处理 | `common/` |
| 页面与交互 | `frontend/` |

## 反馈问题

通过仓库的 [Issues](https://github.com/Mengde-md/cloud-drive/issues) 提交问题时，请提供：

1. **运行环境**：操作系统、JDK 版本、浏览器，以及当前提交版本（`git rev-parse --short HEAD`）。
2. **复现步骤**：从哪个页面开始、输入或选择了什么、点击了哪个按钮。
3. **预期与实际结果**：应出现什么，实际出现什么；例如密码错误时是否显示提示。
4. **排查信息**：相关截图、接口的 HTTP 状态码与响应中的 `code`、`message`，以及对应时段的服务日志。

分享截图和日志前，请遮盖密码、验证码、Token、API Key 和个人文件内容。
