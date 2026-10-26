# 网络云盘存储系统（CloudDrive）

Java 后端课程设计项目：多用户隔离的个人云盘，前后端分离 + 单机部署。

| 目录 | 内容 |
|---|---|
| [`clouddrive/`](clouddrive) | 后端：Spring Boot 3 + Spring Security 6 + MyBatis + MySQL + Redis |
| [`clouddrive-ui/`](clouddrive-ui) | 前端：Vue 3 + Vite + Element Plus（含手机端适配） |
| [`deploy/`](deploy) | 部署：Nginx 配置、systemd 单元、MySQL/Redis 调参、部署文档 |
| `docs/`（在 clouddrive 下） | 微服务边界拆分设计文档 |

## 功能

账号注册登录与 JWT 鉴权 · 用户隔离与存储配额 · 目录树与同级重名约束 ·
普通上传（服务端计算 MD5）· 秒传 · 分片上传与断点续传 · HTTP Range 下载 ·
提取码分享与过期控制 · 回收站（级联删除、整棵恢复、定时清理）·
Redis 缓存与失效 · Redisson 分布式锁与限流 · 结构化日志与 traceId

新增：邮箱验证码注册和找回密码 · 管理员账号管理 · DeepSeek 文件分类与类别筛选 · 可选辅助审核（默认关闭）。配置方法见 [新增功能配置说明](deploy/新增功能配置说明.md)。

2026-10-26 已同步桌面单体版源码并更新 CI，原版本保留在 Git 历史中。[本次验证记录](docs/verification-2026-10-26.md)包含 149 个默认后端测试及前端构建结果。

## 快速开始

```bash
# 后端（需要 JDK 17、MySQL 8、Redis）
cd clouddrive
cp local.env.ps1.example local.env.ps1     # 填入本机 MySQL/Redis 口令
powershell -ExecutionPolicy Bypass -File .\run.ps1

# 前端
cd ../clouddrive-ui
npm install
npm run dev        # http://localhost:5173
```

表结构由 Flyway 的迁移脚本在启动时自动创建和升级，不需要手工建表。

验证：

```bash
cd clouddrive
powershell -ExecutionPolicy Bypass -File .\build.ps1    # 编译 + 单元测试
powershell -ExecutionPolicy Bypass -File .\smoke.ps1    # 输入两个未注册邮箱与收到的验证码，执行业务冒烟检查
```

## 技术要点

源码走读、可深入讲解的主题和验证边界见 [项目面试指南](docs/interview-guide.md)，开发检查见 [贡献说明](CONTRIBUTING.md)。GitHub Actions 分别检查后端测试与前端构建。

各模块的设计取舍与已知缺口分别记录在 [`clouddrive/README.md`](clouddrive/README.md)
和 [`clouddrive-ui/README.md`](clouddrive-ui/README.md) 里，包括为什么越权返回 404 而不是 403、
重名为什么依赖数据库唯一键而不是先查后插、回收站的活跃唯一键怎么设计、
跨资源操作为什么要自己写补偿，等等。

## 安全说明

仓库里不包含任何真实口令或密钥：

- 本机口令放在 `clouddrive/local.env.ps1`（已被 `.gitignore` 排除）
- 服务器口令放在 `/opt/clouddrive/clouddrive.env`（权限 600，不入库）
- 配置文件里出现的都是占位值，部署时必须换成随机生成的密钥

当前部署方案没有配置 HTTPS，公网访问时口令与令牌是明文传输，
详见 [`deploy/部署说明.md`](deploy/部署说明.md) 的安全检查清单。
