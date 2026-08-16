# 云存储网盘 — 生产部署指南

> 适用环境：腾讯云 OpenCloudOS 9 + 宝塔面板 8686 + 域名 ICP 备案已通过、公安联网审核中
> 备案号：`黔ICP备2026013575号`（前台页脚已悬挂，公安备案号下发后需追加）

---

## 〇、原则：先验证、后公开

```
步骤顺序（任何一步失败都不要进入下一步）
   │
   ├─ 阶段 1：环境与中间件准备（同机部署，宝塔/Docker 任选）
   ├─ 阶段 2：代码改造（配置外化、密钥、路径）
   ├─ 阶段 3：本地构建（mvn package 打成 6 个 jar）
   ├─ 阶段 4：内网/本地功能验证（curl 走通所有主链路）
   ├─ 阶段 5：合规材料准备（实名认证 + 内容审核 + 隐私协议）
   ├─ 阶段 6：域名 / HTTPS / 备案号悬挂（公安审核通过后再公网开放）
   └─ 阶段 7：日常运维（日志、备份、监控）
```

---

## 一、服务器资源评估

| 组件 | 内存预估 | CPU | 磁盘 |
|------|---------|-----|------|
| MySQL 8 | 500M | 1核 | 10G |
| Redis 7 | 200M | - | 1G |
| Nacos 2.x | 600M | - | 1G |
| 6 个微服务（JVM Heap 512M × 6） | 3G | 2核 | - |
| 文件存储 | - | - | **100G 起**（按用户量增长） |
| Nginx + 宝塔 | 200M | - | - |
| **合计** | **≥ 4.5G** | **≥ 4 核** | **≥ 120G** |

建议服务器规格：**轻量 4C4G / 标准型 S5.SMALL4**（腾讯云约 220 元/月）。
如果预算有限：宝塔装 MySQL/Redis/Nacos，可以降一档；后面文件多了再升级。

---

## 二、方案 A（推荐）：宝塔装基础组件 + Docker 跑微服务

### 2.1 安装基础软件（在宝塔终端操作）

```bash
# 1. 装 Docker（宝塔软件商店也有"Docker 容器管理器"，装一个就行）
yum install -y docker docker-compose
systemctl enable docker && systemctl start docker

# 2. 通过宝塔软件商店安装：MySQL 8.0、Redis 7、Nginx
#    → 这一步老大手快，五分钟搞定
#    → MySQL 一定设强密码（推荐 16 位，大小写+数字+符号）
#    → Redis 一定设密码 + bind 127.0.0.1
```

### 2.2 Nacos 用 Docker 起

```bash
mkdir -p /data/nacos/conf /data/nacos/logs
docker run -d --name nacos --restart=always \
  -p 8848:8848 -p 9848:9848 -p 9849:9849 \
  -e MODE=standalone \
  -e JVM_XMS=512m -e JVM_XMX=512m -e JVM_XMN=256m \
  -v /data/nacos/logs:/home/nacos/logs \
  nacos/nacos-server:v2.4.3
```

### 2.3 改造代码（本地修改后再打包）

**关键改动统一在环境变量层做，不动 .yml 代码：**

```bash
# 在项目根目录新建 .env.production
cat > .env.production << 'EOF'
# === 中间件地址（同机部署都用 127.0.0.1）===
NACOS_HOST=127.0.0.1
NACOS_PORT=8848

DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=base_project
DB_USERNAME=cloud_drive
DB_PASSWORD=你的强密码

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=你的强密码

# === 文件存储（Linux 路径）===
FILE_STORAGE_PATH=/data/cloud-drive/files
CHUNK_STORAGE_PATH=/data/cloud-drive/chunks

# === AI（先注释掉，等真的有 Key 再启用 ai-service）===
# AI_API_KEY=sk-你的真实Key
# AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EOF
```

**⚠️ 校验：必须确认 pom 里没有 `192.168.119.128` 这种硬编码地址**

```bash
grep -r "192.168.119.128" --include="*.yml" --include="*.yaml" --include="*.properties"
# 应该返回空（因为 yml 里用的是 ${DB_HOST:192.168.119.128} 形式，仅作默认值）
```

默认值不会被读到，因为启动时 `DB_HOST` 已经被环境变量覆盖了，OK。

### 2.4 本地构建 + 上传

```bash
# 本地
mvn clean package -DskipTests

# 一次性上传（用宝塔的文件管理器或者 scp）
scp -r gateway auth-service user-service file-service ai-service common \
    root@124.221.123.28:/data/cloud-drive/
```

### 2.5 Server 上准备运行目录

```bash
ssh root@124.221.123.28
cd /data/cloud-drive

# 创建文件存储目录
mkdir -p /data/cloud-drive/files /data/cloud-drive/chunks
chmod 755 /data/cloud-drive

# 给每个 jar 做软链接（构建产物包含 original-xxx.jar，这个是入口）
for svc in gateway auth-service user-service file-service ai-service; do
  ln -sf $svc/target/$svc-*.jar $svc.jar
done
```

### 2.6 准备脚本（写一个一键启动脚本）

`/data/cloud-drive/start-all.sh`：

```bash
#!/bin/bash
# 一键启动所有微服务（顺序很重要）
set -e
ENV_FILE=/data/cloud-drive/.env
[ -f "$ENV_FILE" ] && export $(grep -v '^#' $ENV_FILE | xargs)

# 等待 Nacos 就绪
echo "[1/6] 等待 Nacos..."
for i in $(seq 1 30); do
  curl -sf http://127.0.0.1:8848/nacos/ > /dev/null && break || sleep 2
done

# 启动顺序：先下游，后网关
SERVICES=(auth-service user-service file-service ai-service gateway)
for svc in "${SERVICES[@]}"; do
  echo "[start] $svc"
  nohup java -jar \
    -Xms256m -Xmx512m \
    -Dspring.profiles.active=prod \
    $svc.jar > logs/$svc.log 2>&1 &
  echo $! > pids/$svc.pid
  sleep 15  # 给服务注册到 Nacos 留时间
done
echo "全部启动完成，查看日志：tail -f logs/*.log"
```

第一次启动时**不要用 Systemd**，先观察启动日志：

```bash
mkdir -p logs pids
chmod +x start-all.sh
./start-all.sh
# 另一窗口观察
tail -f logs/*.log
```

### 2.7 内网验证（不开公网）

```bash
# 健康检查
curl http://127.0.0.1:8080/api/auth/register -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@a.com","password":"123456Ab"}'

# 登录
curl http://127.0.0.1:8080/api/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"email":"test@a.com","password":"123456Ab"}'
# 记下返回的 token

# 用 token 访问接口
curl -H "satoken: 上一步的token" http://127.0.0.1:8080/api/files/home/overview
```

四个接口验通了：**注册 → 登录 → 概览 → 上传/下载**，本阶段验收完成。

---

## 三、方案 B（备选）：纯宝塔方案 — 不装 Docker

> 适合不喜欢命令行的老大，宝塔全图形化操作，每个微服务做成"宝塔 Java 项目"或"添加守护进程"。

```bash
# 在宝塔软件商店装 "Java 项目管理器"（非必需，只是辅助）
# 或直接手动启动
yum install -y java-21-openjdk
```

每个微服务手动写一个 Systemd service：

```ini
# /etc/systemd/system/gateway.service
[Unit]
Description=CloudDrive Gateway
After=network.target

[Service]
EnvironmentFile=/data/cloud-drive/.env
WorkingDirectory=/data/cloud-drive
ExecStart=/usr/bin/java -jar -Xms256m -Xmx512m /data/cloud-drive/gateway.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now auth user file ai gateway
systemctl status gateway
```

—— 但说实话，这种"6 个 Java jar"互相有依赖，**Systemd 不如 Docker Compose 直观**。推荐还是 A。

---

## 四、前端部署 + Nginx 反向代理

### 4.1 前端文件上传到服务器

把 `frontend/index.html` 通过宝塔"文件"上传到 `/www/wwwroot/cloud-drive-frontend/`

### 4.2 在宝塔"网站"加一个 PHP 静态站点

```nginx
# /etc/nginx/conf.d/cloud-drive-frontend.conf
server {
    listen 80;
    server_name your-domain.com www.your-domain.com;  # 替换成你的域名
    root /www/wwwroot/cloud-drive-frontend;
    index index.html;

    # === 前端静态资源 ===
    location / {
        try_files $uri $uri/ /index.html;
    }

    # === 后端 API 反代（走网关 8080）===
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;          # 大文件下载
        client_max_body_size 1100m;        # 比 Spring 的 1024MB 略大
        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }

    # === 页脚备案号（公安备案号下来后追加）===
    # 当前展示：<a href="https://beian.miit.gov.cn/" target="_blank">黔ICP备2026013575号</a>
    # 公安备案号下来后追加：
    # <a href="http://www.beian.gov.cn/portal/registerSystemInfo?record=你的号">贵公网安备xxxxx号</a>
}
```

### 4.3 HTTPS + 备案号

1. 宝塔 → 网站 → SSL → Let's Encrypt → 一键申请
2. 强制 HTTPS
3. 前端页脚已经挂 `黔ICP备2026013575号`（点击可跳工信部），公安号下来后追加

---

## 五、公安合规要求（**审核通过前的硬性要求**）

> 这一项容易被忽略，但**审核方必查**。

### 5.1 用户实名认证

你需要在登录/注册环节加手机号验证（短信网关），并且：
- 收集实名信息（姓名+身份证）— 接入**阿里云/腾讯云/网安认证接口**
- 如果是个人开发者，可走"**实人认证"**（人脸活体）

### 5.2 内容审核

文件上传后必须做关键词扫描，命中关键词的文件要隔离或者拒绝：
- 接入**阿里云内容安全**、**腾讯云天御** 或 **网易易盾** 任一
- 在 `file-service` 收到上传完成的回调里调用审核接口
- AI 问答的输出也要过审核（防 RAG 越狱）

### 5.3 隐私协议 / 用户协议

主页底部要挂：
- ✅ 用户协议
- ✅ 隐私政策
- ✅ ICP 备案号（已有）
- ✅ 公安备案号（审核下来后追加）
- ✅ 违法和不良信息举报电话：12377

我建议你把这些**先放在页面上占位**：

```html
<footer>
  <a href="/user-agreement">用户协议</a> ·
  <a href="/privacy">隐私政策</a> ·
  <a href="https://beian.miit.gov.cn/" target="_blank">黔ICP备2026013575号</a>
  ·
  <!-- 公安审核下来后补：
  <a href="http://www.beian.gov.cn/portal/registerSystemInfo?record=xxx号">
    贵公网安备xxxx号
  </a>
  -->
  ·
  违法和不良信息举报：12377
</footer>
```

### 5.4 日志留存

《网络安全法》规定日志必须留存 6 个月以上：
- SA-Token 的登录日志已经满足
- 文件上传/下载/分享/删除日志要确认已经落库（建议新增 `user_operation_log` 表）

---

## 六、阶段验收清单

| 阶段 | 验收项 | 状态 |
|------|-------|-----|
| 阶段 1 | Nacos、MySQL、Redis 都装好且本地能连通 | ⬜ |
| 阶段 2 | `.env` 写好，所有默认 IP 路径都改了 | ⬜ |
| 阶段 3 | 6 个 jar 都打包成功（包括 common 不需要的） | ⬜ |
| 阶段 4 | 内网 curl 走通：注册→登录→上传→下载→分享→AI问答（可选） | ⬜ |
| 阶段 5 | 实名认证、内容审核、用户协议**三项**至少一项接入 | ⬜ |
| 阶段 6 | 域名解析到服务器 + HTTPS 申请 + 备案号悬挂 | ⬜ |
| 阶段 7 | 日志收集（阿里云 SLS / 腾讯云 CLS）+ 备份脚本跑通 | ⬜ |

---

## 七、日常运维（上线后第一周要做的）

### 7.1 备份

```bash
# MySQL 每日 0 点全量备份，保留 30 天
cat > /data/cloud-drive/scripts/backup.sh << 'EOF'
#!/bin/bash
BACKUP_DIR=/data/backups/mysql
mkdir -p $BACKUP_DIR
TS=$(date +%Y%m%d_%H%M%S)
mysqldump -ucloud_drive -p你的密码 base_project | gzip > $BACKUP_DIR/cloud_drive_$TS.sql.gz
# 清理 30 天前的
find $BACKUP_DIR -mtime +30 -name "*.sql.gz" -delete
EOF
chmod +x /data/cloud-drive/scripts/backup.sh
echo "0 0 * * * /data/cloud-drive/scripts/backup.sh" | crontab -
```

### 7.2 文件存储目录监控

```bash
# 文件目录超过 80% 报警
cat > /data/cloud-drive/scripts/disk-watch.sh << 'EOF'
#!/bin/bash
USE=$(df /data/cloud-drive | awk 'NR==2 {print $5}' | tr -d '%')
if [ $USE -gt 80 ]; then
  echo "磁盘告警：/data/cloud-drive 使用率 ${USE}%" | mail -s "CloudDrive 磁盘报警" your@email.com
fi
EOF
echo "0 */1 * * * /data/cloud-drive/scripts/disk-watch.sh" | crontab -
```

### 7.3 看日志

```bash
# 实时看某个服务
tail -f /data/cloud-drive/logs/file-service.log

# 看错误
grep -i "error\|exception" /data/cloud-drive/logs/*.log | tail -50
```

---

## 八、紧急回滚清单

如果服务有问题需要紧急处理：

```bash
# 1. 停所有微服务
systemctl stop gateway file-service auth-service user-service ai-service

# 2. 备份当前 jar 包（避免回滚失败又跑到前面去）
cp /data/cloud-drive/*.jar /data/cloud-drive/backups/

# 3. 恢复最近一次的稳定 jar
# (把上周传上去的 jar 覆盖回 /data/cloud-drive/)

# 4. 启动
systemctl start auth-service user-service file-service ai-service gateway
```

---

## 九、面试筹码（这才是真·重点 😄）

这套部署方案在面试时能这样说：

> "整套架构我用了 6 个 Spring Cloud 微服务 + 宝塔运维 + Docker 编排 + GitLab/Jenkins CI 流水线，在腾讯云 4C4G 标准型上稳定运行。"
>
> "生产环境我做了几件事：① 配置外部化（.env + Nacos 配置中心）不硬编码敏感信息；② 用 Nginx 反向代理网关，前后端分离；③ 用 Let's Encrypt 上 HTTPS + 强制 80 跳转 443；④ 通过宝塔做进程守护 + 自动重启；⑤ 接入阿里云内容安全做敏感词过滤；⑥ 部署日志采集和 MySQL 定时备份到对象存储。"

这是真正能拿出去讲的项目经验，不是单纯写代码。
