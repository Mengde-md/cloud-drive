#!/bin/bash
# ============================================================
# 云盘微服务一键启动脚本（4C4G 防跑崩版）
#
# 【4G 内存分配账本】
#   MySQL ~500M + Redis ~100M + Nacos ~600M = 1.2G
#   4 个 JVM(384M heap + ~100M 开销) × 4 ≈ 2.0G
#   OS/宝塔/Nginx ≈ 0.4G
#   ──────────────
#   合计 ≈ 3.6G，在 4G 内，余量靠 swap 兜底
#
# 【务必先加 swap，防 OOM 被杀】：
#   fallocate -l 2G /swapfile && chmod 600 /swapfile \
#   && mkswap /swapfile && swapon /swapfile
#   持久化：echo '/swapfile none swap sw 0 0' >> /etc/fstab
#
# 【ai-service 默认不启动】：没配 AI_API_KEY 用不上，省内存。
#   以后配好 key 后：START_AI=true ./start-all.sh
# ============================================================
set -e
cd "$(dirname "$0")"

mkdir -p logs pids /data/cloud-drive/files /data/cloud-drive/chunks

# 加载环境变量
ENV_FILE=/data/cloud-drive/.env
if [ -f "$ENV_FILE" ]; then
    export $(grep -v '^#' "$ENV_FILE" | xargs)
else
    echo "❌ 找不到 $ENV_FILE"
    echo "   请先执行：cp .env.production .env && vim .env（填好密码）"
    exit 1
fi

# 等待 Nacos 就绪（最多 60 秒）
echo "[0/6] 等待 Nacos 就绪..."
NACOS_OK=0
for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:8848/nacos/" > /dev/null 2>&1; then
        NACOS_OK=1
        break
    fi
    sleep 2
done
if [ "$NACOS_OK" != "1" ]; then
    echo "❌ Nacos 60 秒内未就绪，请先启动 Nacos（见 deploy/README.md 1.2）"
    exit 1
fi

# 启动服务（ai-service 默认跳过，除非 START_AI=true）
SERVICES=(auth-service user-service file-service gateway)
if [ "$START_AI" = "true" ]; then
    SERVICES=(auth-service user-service file-service ai-service gateway)
    echo "→ START_AI=true，将同时启动 ai-service"
fi

for svc in "${SERVICES[@]}"; do
    echo "[start] $svc"
    # 自动查找 jar（支持 auth-service.jar 和 auth-service-1.0.0.jar 两种命名）
    JAR_FILE=$(ls ${svc}*.jar 2>/dev/null | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "   ⚠️ 缺少 $svc 的 jar 文件，跳过（请先上传构建产物）"
        continue
    fi
    echo "   → 使用 $JAR_FILE"
    # 4C4G 防崩：heap 上限 384m（业务量小足够，比 512m 更稳）
    nohup java -jar \
        -Xms128m -Xmx384m \
        -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
        "$JAR_FILE" > "logs/$svc.log" 2>&1 &
    echo $! > "pids/$svc.pid"
    sleep 15
done

echo "✅ 启动命令已全部执行。"
echo "   观察：tail -f logs/*.log"
echo "   内存：free -m  （swap 有剩余 = 安全）"
echo "   OOM 排查：dmesg | grep -i 'killed process'"
