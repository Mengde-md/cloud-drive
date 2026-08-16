# 部署操作手册（腾讯云 4C4G · 中间件主机直装 · 同学测试版）

> 配套文件：`.env.production`（环境变量模板）、`nginx-cloud-drive.conf`（站点配置）、`start-all.sh`（启动）、`stop-all.sh`（停止）
> 服务器信息：4 核 4GB，已装宝塔，域名已建站 + 强制 HTTPS，Let's Encrypt 证书已配
> 合规状态：ICP 已通过（黔ICP备2026013575号）、公安联网审核中、页脚备案号已挂

---

## 〇、分工一览

| 事项 | 谁来做 | 状态 |
|------|--------|------|
| 中间件（MySQL 8 / Redis 7 / Nacos） | 你（宝塔 + Docker，主机直装） | ⬜ |
| Maven 安装 + 本地打包 | 你 | ⬜ |
| 数据库建库 + 导入 SQL | 照本手册第 2 步 | ⬜ |
| 部署文件（.env/nginx/脚本） | 我已生成 ✅ | ✅ |
| 前端合规（协议/隐私链接 + 备案号） | 我已改好 ✅ | ✅ |
| 启动 + 验证 | 照本手册第 4-6 步 | ⬜ |

---

## 一、中间件准备（你先做）

### 1.1 宝塔软件商店装 MySQL 8.0、Redis 7、Nginx
- **MySQL**：设 16 位强密码；**创建专用账号**（别用 root）：
  ```sql
  CREATE USER 'cloud_drive'@'localhost' IDENTIFIED BY '你的强密码';
  GRANT ALL PRIVILEGES ON base_project.* TO 'cloud_drive'@'localhost';
  FLUSH PRIVILEGES;
  ```
- **Redis**：必须设密码，只监听 127.0.0.1（宝塔默认即是）。配置里加 `requirepass 你的强密码`

### 1.2 Nacos（Docker 起，主机上跑）
```bash
mkdir -p /data/nacos/logs
docker run -d --name nacos --restart=always \
  -p 8848:8848 -p 9848:9848 -p 9849:9849 \
  -e MODE=standalone -e JVM_XMS=512m -e JVM_XMX=512m \
  -v /data/nacos/logs:/home/nacos/logs \
  nacos/nacos-server:v2.4.3
```
验证：浏览器访问 `http://服务器IP:8848/nacos`（账号密码默认 nacos/nacos）
> 如果 Docker 没装：宝塔软件商店装「Docker 管理器」即可。

---

## 二、数据库初始化（第 2 步）

```bash
# 先建库（init.sql 里也有 CREATE DATABASE，可跳过）
mysql -ucloud_drive -p -e "CREATE DATABASE IF NOT EXISTS base_project DEFAULT CHARACTER SET utf8mb4;"

# 导入基础表（重要：只执行 init.sql，不执行 migrate_compliance.sql）
mysql -ucloud_drive -p base_project < sql/init.sql

# 验证 6 张表
mysql -ucloud_drive -p base_project -e "SHOW TABLES;"
# 应显示：user, user_file, file, file_chunk, share, share_file
```
> ⚠️ `migrate_compliance.sql`（实名/审核相关表）**先不要执行**——当前代码还没实现那些功能，执行了反而对不上。

---

## 三、本地打包（你，需要先装 Maven）

```bash
cd "C:\Users\惠普暗影精灵9\Desktop\test\基础项目"
mvn clean package -DskipTests
```
打包产物：5 个可运行 jar + 1 个公共库（common 不用跑）
```
gateway/target/gateway-1.0.0.jar
auth-service/target/auth-service-1.0.0.jar
user-service/target/user-service-1.0.0.jar
file-service/target/file-service-1.0.0.jar
ai-service/target/ai-service-1.0.0.jar
```

---

## 四、上传服务器

```bash
# 在本地项目根目录执行（用宝塔「文件」上传也行，更直观）
scp -r deploy gateway auth-service user-service file-service ai-service \
    root@你的服务器IP:/data/cloud-drive/
```

在服务器上：
```bash
cd /data/cloud-drive
# jar 软链接（start-all.sh 按 xxx.jar 找）
for svc in auth-service user-service file-service ai-service gateway; do
  ln -sf $svc/target/$svc-*.jar $svc.jar
done
# 环境变量：复制模板并填真实密码
cp deploy/.env.production .env && vim .env
chmod +x deploy/start-all.sh deploy/stop-all.sh
```

---

## 五、启动（第 5 步）

```bash
# 0. 先加 2G swap（4G 内存防 OOM 必做，一次性的）
fallocate -l 2G /swapfile && chmod 600 /swapfile \
  && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab   # 开机自动挂载

# 1. 启动
cd /data/cloud-drive
./deploy/start-all.sh
# 观察日志
tail -f logs/*.log
```
> **ai-service 默认不启动**（没配 AI_API_KEY 用不上，省内存）。以后配好 key 想启用：
> `START_AI=true ./deploy/start-all.sh`

**启动成功的标志**：每个服务的日志出现 `Started ... in x seconds`，且 Nacos 控制台的服务列表里能看到 4~5 个服务全部 `UP`。

> 常见卡点：日志里报 `dataId ... not found` 之类 —— 不影响服务发现，可忽略；如果某个服务反复重启，看它的日志里 `Exception` 前 20 行。

---

## 六、前端 + 站点反代（第 6 步）

1. 把 `frontend/` 下的 `index.html`、`agreement.html`、`privacy.html` 传到宝塔站点的根目录（`/www/wwwroot/你的域名/`）
2. 宝塔 → 网站 → 你的站点 → **配置文件**，在 server 块里加（见 `nginx-cloud-drive.conf` 里★标注部分）：
   ```nginx
   location /api/ {
       proxy_pass http://127.0.0.1:8080;
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_read_timeout 300s;
       client_max_body_size 1100m;
       proxy_http_version 1.1;
       proxy_set_header Connection "";
   }
   ```
3. 保存后重载 Nginx（宝塔「重载」按钮）

---

## 七、验证（第 7 步）— 主链路必须全通

```bash
# 1. 注册
curl http://127.0.0.1:8080/api/auth/register -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@a.com","password":"123456Ab"}'

# 2. 登录，记下返回的 token
curl http://127.0.0.1:8080/api/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"email":"test@a.com","password":"123456Ab"}'

# 3. 带 token 访问概览（token 替换成上一步返回值）
curl -H "satoken: 你的token" http://127.0.0.1:8080/api/files/home/overview
```
浏览器验证：`https://你的域名` 能打开，能注册/登录/上传/下载，地址栏有锁。

---

## 八、上线前你要自己补的 3 个占位（都是小事）

1. **协议页的邮箱**：`agreement.html` 里 `report@example.com`、`privacy.html` 里 `support@example.com`，替换成 ICP 备案时填的真实邮箱
2. **公安备案号**：审核通过后，取消 `index.html` footer 里注释的 `黔公网安备xxxxx号`，填上真实号码并链接到 `www.beian.gov.cn`
3. **`.env` 里的密码**：把 `请替换为...` 全部换成真实强密码（和宝塔 MySQL/Redis 里设的一致）

---

## 九、测试阶段的合规说明（重要）

- ✅ **已做**：ICP 备案号页脚（3 个页面都有）、用户协议、隐私政策、12377 举报电话
- ⏸️ **缓做**（正式公开前再补）：实名认证（阿里云实人认证）、内容审核（阿里云内容安全）、完整审计日志 —— 几个同学测试阶段不用接，省钱省事
- ⚠️ **公安审核期间**：网站保持可访问、页面内容干净。别把网站设成密码门挡住审核员访问

---

## 十、服务器安全（顺手做掉，5 分钟）

- 宝塔面板端口别用默认 8888，换成随机端口
- 腾讯云安全组只放行 80、443、SSH 22
- `.env` 权限：`chmod 600 /data/cloud-drive/.env`（里面有密码）

---

## 常见问题速查

| 现象 | 原因 | 处理 |
|------|------|------|
| 某个服务一直重启 | 连不上 MySQL/Redis/Nacos | 看日志里 `Unable to connect` 指向谁，检查 .env 密码 |
| 上传大文件失败 | nginx 或 Spring 限制 | 确认 nginx 加了 `client_max_body_size 1100m` |
| 能开首页但接口 404/502 | 网关没起来或没注册到 Nacos | `curl 127.0.0.1:8080/api/auth/login` 测网关；看 Nacos 服务列表 |
| HTTPS 访问报错 | 证书问题 | 宝塔 → 网站 → SSL → 重新申请/续期 |
