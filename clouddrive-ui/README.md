# CloudDrive 前端

网络云盘存储系统的 Web 界面，基于 Vue 3 + Vite + Element Plus + Pinia。

## 运行

先确保后端已经在 8080 跑起来（`clouddrive/run.ps1`），然后：

```bash
npm install
npm run dev
```

打开 http://localhost:5173 。

开发期 Vite 会把 `/api` 代理到 `http://localhost:8080`，
所以浏览器看到的始终是同源请求，**后端不需要为联调开放 CORS**。
生产环境由 Nginx 做同样的转发，前端代码无需按环境切换域名。

打包：

```bash
npm run build     # 产出 dist/
```

## 页面与对应接口

| 页面 | 路由 | 用到的接口 |
|---|---|---|
| 登录 / 注册 / 找回密码 | `/login` | `/api/auth/register`、`/login`、`/email-code`、`/reset-password` |
| 账号管理（管理员） | `/admin/users` | `GET /api/admin/users`、`PATCH .../{id}/password`、`/status`、`/role` |
| 辅助审核（管理员） | `/admin/reviews` | `/api/admin/reviews`、`/api/admin/analysis-settings` |
| AI 智能分类 | `/ai` | `/api/analysis/capabilities`、`/api/analysis`、`POST /api/analysis/{id}/reanalyze` |
| 我的文件 | `/files` | `GET/POST /api/entries`、`PUT /api/entries/{id}/name`、`DELETE /api/entries/{id}`、`POST /api/files/upload`、`GET /api/files/{id}/download` |
| 我的分享 | `/shares` | `GET/POST /api/shares`、`PUT /api/shares/{token}/passcode`、`DELETE /api/shares/{token}` |
| 打开分享 | `/open` | `GET /api/shares/{token}/info`、`POST /api/shares/{token}/save` |
| 回收站 | `/trash` | `GET /api/entries/trash`、`POST /api/entries/trash/{id}/restore`、`DELETE /api/entries/trash` |
| 分享访问 | `/s/:token` | `GET /api/shares/{token}/info`、`/download`（匿名）、`POST .../save`（登录后可转存） |

文件列表支持自动分类、跨目录类别筛选、分析来源/状态和重新分析，元数据接口为 `/api/analysis`。辅助审核默认关闭，可由管理员保存开关和人工复核意见。注册要求真实邮箱验证码；配置方法见 [新增功能配置说明](../deploy/新增功能配置说明.md)。可见页面只展示功能说明，技术实现介绍保留在开发文档中。

## 分享的两个方向

**发出去**：在"我的文件"里对某个文件点分享 → 设提取码和有效期 → 得到链接。
列表在"我的分享"里，带文件名、大小、状态（长期有效 / 已过期 / 文件已删除），
可以复制链接、重置提取码、取消分享。

**收进来**：侧边栏的"打开分享"可以粘贴别人给的链接或分享码 + 提取码，
先看到文件名和大小，再决定下载还是转存到自己的网盘。
直接点开 `/s/:token` 链接的人（未登录也可以）走的是同一套流程。

转存不复制文件内容，只是在自己名下建一条指向同一份文件的记录，
所以再大的文件也是瞬间完成，但会按真实大小占用配额。

## 手机端适配

`useBreakpoint()` 以 768px 为界判断是否手机，页面据此切换结构：

- 侧边栏在窄屏改为抽屉，顶栏出现汉堡按钮，点菜单或切回桌面宽度都会自动收起；
- 文件 / 回收站 / 分享三个列表在窄屏由表格换成卡片，操作收进右上角的下拉菜单；
- 弹窗宽度改百分比，登录页与分享页卡片用 `max-width: calc(100vw - 32px)`；
- 导航项高度 48px，低于 44px 在手机上容易点不中。

为什么用 JS 判断而不是纯 CSS 媒体查询：表格在手机上不是"变窄"就能用，
需要换成完全不同的卡片结构，这种结构级差异必须让组件知道当前断点，
纯 CSS 只能靠隐藏/显示两套 DOM，会白渲染一份用不到的。

## 几个实现上的取舍

**下载为什么走 blob 而不是直接 `<a href>`**
下载接口要求 `Authorization` 头，而浏览器发起的普通导航请求带不上自定义头。
所以用 axios 取回 blob 再用 ObjectURL 触发保存。
分享下载是匿名接口（提取码本身就是凭证），可以用普通链接。

**目录树在前端拼**
后端 `GET /api/entries` 返回当前用户的扁平列表，
前端按 `parentId` 过滤出当前目录、沿 `parentId` 回溯出面包屑。
数据量小的时候这样最简单；条目多了应该改成后端按父目录分页返回。

**令牌存在 localStorage**
刷新页面后还能保持登录。代价是 XSS 场景下可被脚本读取，
更稳妥的是 HttpOnly Cookie，但那需要后端改成 Cookie 认证。这一点是已知取舍。

**路由守卫不是安全边界**
`router.beforeEach` 只是体验层面的拦截，前端代码可以被绕过。
真正的权限判断全部在后端：每条 SQL 都带 `owner_id`，越权返回 404。

## 已知缺口

1. **转存时不能选目标目录**。当前一律存到根目录，界面没做目录选择器。
2. **分片上传、断点续传没有做界面**。后端接口齐全
   （`/api/upload-sessions` 系列），但进度管理、缺片续传的交互较复杂，
   当前用 `clouddrive/smoke.ps1` 或 Postman 演示。
3. **秒传没有单独入口**。前端未做上传前计算 MD5，
   所以每次都是普通上传；秒传接口的效果同样用冒烟脚本演示。
4. **列表未分页**。`/files` 一次拉全量，后端的 `/api/entries/page` 尚未接入。
   条目多了应该改成按父目录分页请求。
5. **没有 HTTPS**。部署在公网时口令与令牌是明文传输，见 `deploy/部署说明.md`。
