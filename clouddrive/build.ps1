# 一键构建脚本：编译 + 跑单元测试，并把完整输出写到 build.log
#
# 用法（在本文件所在目录打开 PowerShell）：
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#
# 跑完之后不用复制报错，build.log 就在这个目录里，我可以直接读。

$ErrorActionPreference = 'Continue'

$Jdk     = 'C:\Environment\jdk-17'
$M2Repo  = 'C:\Environment\m2repo'
$Project = $PSScriptRoot
$Log     = Join-Path $Project 'build.log'

Write-Host "===== CloudDrive build =====" -ForegroundColor Cyan

# 1. 固定 JDK 17（项目用了 record、文本块等 17 特性，JDK 11 编译不过）
if (Test-Path $Jdk) {
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;$env:Path"
    Write-Host "JAVA_HOME = $Jdk"
}
else {
    Write-Host "jdk-17 not found at $Jdk, using system default JDK (must be 17+)" -ForegroundColor Yellow
}

# 2. 记录环境信息，便于排查版本不对这类问题
$header = @()
$header += "==== environment ===="
$header += "time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$header += "project: $Project"
$header += "java -version:"
$header += (& java -version 2>&1 | Out-String).Trim()
$header += "mvn -v:"
$header += (& mvn -v 2>&1 | Out-String).Trim()
$header += ""
$header += "==== maven output ===="

# 3. 优先用本地仓库，缺的包仍会联网补
$mvnArgs = @('-B', 'clean', 'test')
$JavaSocketTemp = Join-Path $env:SystemRoot 'Temp/clouddrive-jdk-sockets'
New-Item -ItemType Directory -Path $JavaSocketTemp -Force | Out-Null
$mvnArgs += "-DargLine=-Djdk.net.unixdomain.tmpdir=$JavaSocketTemp"
if (Test-Path $M2Repo) {
    $mvnArgs = @("-Dmaven.repo.local=$M2Repo") + $mvnArgs
    Write-Host "local repo = $M2Repo"
}

Write-Host "run: mvn $($mvnArgs -join ' ')" -ForegroundColor Cyan
Write-Host ""

Push-Location $Project
& mvn @mvnArgs 2>&1 | Tee-Object -Variable mvnOut
Pop-Location

# 4. 全量输出落盘（UTF-8，避免中文乱码）
($header + ($mvnOut | ForEach-Object { $_.ToString() })) |
    Out-File -FilePath $Log -Encoding utf8

# 5. 控制台给个结论
Write-Host ""
Write-Host "===== result =====" -ForegroundColor Cyan
$tests  = $mvnOut | Select-String -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+' | Select-Object -Last 1
$ok     = $mvnOut | Select-String -Pattern 'BUILD SUCCESS' | Select-Object -Last 1
$errors = $mvnOut | Select-String -Pattern '\[ERROR\]' | Select-Object -First 10

if ($tests) { Write-Host $tests.Line.Trim() }
if ($ok) {
    Write-Host "BUILD SUCCESS" -ForegroundColor Green
}
else {
    Write-Host "BUILD FAILED, first errors:" -ForegroundColor Red
    $errors | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
}
Write-Host ""
Write-Host "full log: $Log"
