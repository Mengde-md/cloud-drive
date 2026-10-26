# 单体网盘项目面试准备

## 先确认版本与入口

本仓库当前版本以桌面 CloudDrive 项目为准，采用 Spring Boot 单体后端与 Vue 前端。历史提交中的 Spring Cloud 微服务版保留在 Git 历史中，当前源码从以下位置阅读：

- [后端入口](../clouddrive/src/main/java/com/mengde/clouddrive/web/DriveEntryWebApplication.java)
- [前端路由](../clouddrive-ui/src/router/index.js)
- [部署配置](../deploy/部署说明.md)
- [微服务边界设计](../clouddrive/docs/Day23_微服务边界拆分设计.md)：设计文档，不代表当前已部署成微服务。

## 三个可以深入讲解的主题

| 主题 | 源码与测试入口 | 需要自己回答的问题 |
|---|---|---|
| 上传、事务与失败补偿 | [上传应用服务](../clouddrive/src/main/java/com/mengde/clouddrive/application/DocumentUploadApplicationService.java)、[事务安全测试](../clouddrive/src/test/java/com/mengde/clouddrive/application/UploadTransactionSafetyTest.java)、[存储失败测试](../clouddrive/src/test/java/com/mengde/clouddrive/storage/StorageFailurePreservationTest.java) | 磁盘文件和数据库能否用同一个事务回滚？写入、元数据保存和清理分别在哪一步执行？如何证明失败后没有误删已有文件？ |
| 账号安全与权限变化 | [JWT 服务](../clouddrive/src/main/java/com/mengde/clouddrive/auth/JwtTokenService.java)、[管理员服务](../clouddrive/src/main/java/com/mengde/clouddrive/auth/AdminAccountService.java)、[安全测试](../clouddrive/src/test/java/com/mengde/clouddrive/auth/AccountSecurityTest.java) | JWT 尚未过期时，修改密码、禁用账号或调整角色如何影响旧令牌？前端菜单隐藏和后端权限校验各自解决什么问题？ |
| AI 可选能力与降级 | [模型客户端](../clouddrive/src/main/java/com/mengde/clouddrive/analysis/DeepSeekAnalysisClient.java)、[分析服务](../clouddrive/src/main/java/com/mengde/clouddrive/analysis/FileAnalysisService.java)、[模型客户端测试](../clouddrive/src/test/java/com/mengde/clouddrive/analysis/DeepSeekAnalysisClientTest.java) | 未配置 Key、请求超时或输出不符合结构时怎么办？文件内容为何视为不可信数据？辅助审核为什么默认关闭、由管理员复核？ |

## 一段可按自己的理解组织的介绍

> 我做的是多用户网盘，后端使用 Spring Boot，前端使用 Vue。除了上传、分享和回收站，我重点处理了磁盘与数据库操作失败时的补偿、账号权限变化后旧令牌的处理，以及可选 AI 分类失败后的后缀分类回退。实现和验证都能对应到具体源码与测试。

介绍后挑一个主题，沿着“接口输入 → 服务调用 → 状态变化 → 失败分支 → 测试断言”展开。能现场运行测试、解释测试覆盖的边界，比只列技术名词更有说服力。

## 验证入口与边界

```bash
mvn -B -f clouddrive/pom.xml verify
```

该命令默认排除需要真实 MySQL/Redis 的集成测试；默认测试通过与真实数据库集成、生产部署验证是不同层次。集成环境配置见 [后端 README](../clouddrive/README.md)。

前端执行 `npm ci` 和 `npm run build`，目录为 `clouddrive-ui/`。构建通过还需结合浏览器操作验证相关交互。

AI 支持提取部分文本和代码，不覆盖所有二进制文件的内容识别。测试中的模型协议响应不能替代真实 DeepSeek 调用证据；上线前需单独验证自己的模型配置。辅助审核是管理员复核的线索，不能证明整个文件安全。

性能、并发能力和部署规模应以实际环境的测量为依据。
