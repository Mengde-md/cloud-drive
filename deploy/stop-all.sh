#!/bin/bash
# ============================================================
# 停止全部微服务（按 pids 目录里的 pid 文件）
# ============================================================
cd "$(dirname "$0")"

SERVICES=(gateway auth-service user-service file-service ai-service)
for svc in "${SERVICES[@]}"; do
    PID_FILE="pids/$svc.pid"
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID"
            echo "[stop] $svc (pid=$PID)"
        else
            echo "[stop] $svc 未在运行"
        fi
        rm -f "$PID_FILE"
    fi
done
echo "✅ 全部停止完成"
