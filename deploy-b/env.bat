@echo off
REM ============================================================
REM CloudDrive 方案B 环境变量（Windows 主机运行）
REM 中间件在 VM 192.168.119.128，Java 服务在主机
REM ============================================================

REM ---- VM 中间件地址 ----
set NACOS_HOST=192.168.119.128
set NACOS_PORT=8848

set DB_HOST=192.168.119.128
set DB_PORT=3306
set DB_NAME=base_project
set DB_USERNAME=root
set DB_PASSWORD=123456

set REDIS_HOST=192.168.119.128
set REDIS_PORT=6379
set REDIS_PASSWORD=123456

REM ---- 文件存储（Windows 路径）----
set FILE_STORAGE_PATH=D:/cloud-drive/files
set CHUNK_STORAGE_PATH=D:/cloud-drive/chunks
set CHUNK_EXPIRATION_DAYS=1

REM ---- AI（暂不启用，留空）----
set AI_API_KEY=
set AI_BASE_URL=https://api.deepseek.com/v1
set AI_CHAT_MODEL=deepseek-chat
