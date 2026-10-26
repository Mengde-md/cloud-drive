# 启动脚本：连本机 MySQL/Redis 跑起应用，输出写到 run.log
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\run.ps1
#
# 口令不写在本文件里！第一次使用请复制 local.env.ps1.example 为 local.env.ps1，
# 在那里填真实口令。local.env.ps1 已被 .gitignore 排除，不会进版本库。

$ErrorActionPreference = 'Continue'

$Project = $PSScriptRoot
$Log     = Join-Path $Project 'run.log'
$Jdk     = 'C:\Environment\jdk-17'
$M2Repo  = 'C:\Environment\m2repo'

# ---- 默认值（可被 local.env.ps1 覆盖）----
$DbHost = '127.0.0.1'
$DbPort = '3306'
$DbName = 'cloud_drive'
$DbUser = 'root'
$DbPassword = ''
$RedisHost = '127.0.0.1'
$RedisPort = '6379'
$RedisPassword = ''
$JwtSecret = 'local-dev-only-secret-key-at-least-32-bytes-long'

# ---- 加载本机私有配置 ----
$LocalConfig = Join-Path $Project 'local.env.ps1'
if (Test-Path $LocalConfig) {
    . $LocalConfig
    Write-Host "loaded local config: $LocalConfig" -ForegroundColor DarkGray
}
else {
    Write-Host "local.env.ps1 not found (copy from local.env.ps1.example)" -ForegroundColor Yellow
}

# 口令仍为空就当场问一次，而不是把它写死在脚本里
if ([string]::IsNullOrEmpty($DbPassword)) {
    $secure = Read-Host "MySQL password for user '$DbUser'" -AsSecureString
    $DbPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

if (Test-Path $Jdk) {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;$env:Path"
}

# createDatabaseIfNotExist=true：库不存在时由驱动自动建，省掉手工 CREATE DATABASE。
# 表结构仍由 Flyway 迁移负责，不会手工建表。
$env:CLOUD_DRIVE_DB_URL = "jdbc:mysql://${DbHost}:${DbPort}/${DbName}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
$env:CLOUD_DRIVE_DB_USER = $DbUser
$env:CLOUD_DRIVE_DB_PASSWORD = $DbPassword
$env:CLOUD_DRIVE_REDIS_HOST = $RedisHost
$env:CLOUD_DRIVE_REDIS_PORT = $RedisPort
$env:CLOUD_DRIVE_REDIS_PASSWORD = $RedisPassword
$env:CLOUD_DRIVE_JWT_SECRET = $JwtSecret

Write-Host "===== start CloudDrive =====" -ForegroundColor Cyan
Write-Host "db:    ${DbHost}:${DbPort}/${DbName}  user: $DbUser"
Write-Host "redis: ${RedisHost}:${RedisPort}"
Write-Host "health: http://localhost:8080/actuator/health"
Write-Host "stop:   Ctrl+C"
Write-Host ""

$mvnArgs = @('-B', 'spring-boot:run')
# Windows JDK 17 的 NIO 内部套接字不兼容部分中文临时目录。
# 仅为本次 Java 进程指定独立的系统临时目录，不修改全局环境或 JVM 设置。
$JavaSocketTemp = Join-Path $env:SystemRoot 'Temp/clouddrive-jdk-sockets'
New-Item -ItemType Directory -Path $JavaSocketTemp -Force | Out-Null
$mvnArgs += "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=$JavaSocketTemp"
if (Test-Path $M2Repo) {
    $mvnArgs = @("-Dmaven.repo.local=$M2Repo") + $mvnArgs
}

# 日志实时落盘：原来用 Tee-Object -Variable 收集、退出后才写文件，
# 结果是应用还在跑的时候 run.log 始终是空的，出了异常根本没法查。
# 现在逐行写入，边跑边能看。
if (Test-Path $Log) { Clear-Content $Log }

Push-Location $Project
& mvn @mvnArgs 2>&1 | ForEach-Object {
    $line = $_.ToString()
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding utf8
}
Pop-Location

Write-Host ""
Write-Host "full log: $Log"
