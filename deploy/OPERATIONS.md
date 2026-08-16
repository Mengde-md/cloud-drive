# 部署与运维清单

## 技术定位

项目采用 JDK 17、Spring Boot 3、Spring Cloud Alibaba、Nacos、MySQL、Redis、Docker Compose、Actuator、Prometheus 指标和 GitHub Actions。JDK 17 是当前项目的编译与 CI 基线，也是企业 Java 后端常见的 LTS 版本。

## 服务器部署所需组件

| 组件 | 是否必须 | 建议部署方式 | 作用 |
| --- | --- | --- | --- |
| Nginx + HTTPS 证书 | 必须 | 宿主机/宝塔 | 域名、静态页面、反向代理 |
| MySQL 8 | 必须 | Docker Compose | 业务数据 |
| Redis 7 | 必须 | Docker Compose | 登录态、缓存、分布式锁、限流 |
| Nacos 2 | 必须 | Docker Compose | 注册中心和配置中心 |
| auth/user/file/gateway | 必须 | 现有 `start-all.sh` | 核心业务服务 |
| ai-service | 可选 | `START_AI=true` | 仅使用 AI 功能时启动 |
| Prometheus/Grafana | 可选 | 后续独立容器 | 指标展示；不影响业务服务运行 |

> 4C4G 服务器建议只启动核心四个服务；需要演示 AI 时短时启用 `ai-service`。Prometheus/Grafana 可在面试演示环境再启动，不与业务服务长期争抢内存。

## 首次部署

1. 安装 Docker Engine、Docker Compose plugin、JDK 17、Nginx，放行公网 `80/443`，SSH 仅限可信 IP。
2. 将 `deploy/.env.example` 复制为 `deploy/.env`，设置强密码，并额外添加 `MYSQL_ROOT_PASSWORD`。
3. 启动基础设施：

```bash
cd /data/cloud-drive/deploy
docker compose --env-file .env -f docker-compose.infrastructure.yml up -d
```

4. 通过 `docker compose ps` 确认 MySQL、Redis、Nacos 健康；再在项目根目录运行 `./deploy/start-all.sh`。
5. 将 `frontend/` 中的页面发布到 `/www/wwwroot/mengdecode.com`，将 `nginx-cloud-drive.conf` 合并到站点配置，申请覆盖 `mengdecode.com` 和 `www.mengdecode.com` 的证书后重载 Nginx。

## 健康检查与指标

各服务提供以下本机端点：

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8092/actuator/prometheus
```

运维端点配置为仅监听 `127.0.0.1`，不可将 `/actuator/**` 代理到公网。Prometheus 若部署在同一台机器，可采集这些地址。

## 上线前必须修改

- `.env` 中所有密码、数据库账号、AI API Key；权限设为 `chmod 600 deploy/.env`。
- Nacos 默认账号密码，至少修改为强密码，并且只允许内网/本机访问 8848。
- 用户协议与隐私政策中的示例邮箱 `support@example.com`、`report@example.com`。
- SSL 证书路径：确认覆盖主域名和 `www` 子域名。

## 验收命令

```bash
mvn verify
curl -f http://127.0.0.1:8080/actuator/health
curl -f https://mengdecode.com/
```
