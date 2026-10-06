[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=[IO.Path]::GetTempPath())
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& (Join-Path $repo 'deploy/Test-DeploymentSettings.ps1')
if(Get-NetTCPConnection -LocalPort 8084 -State Listen -ErrorAction SilentlyContinue){throw 'AI port already has a listener; stop only an explicitly owned deployment first'}
$classpathFile=Join-Path $repo 'ai-service/build-output/day32-test-classpath.txt'
foreach($file in @($classpathFile,(Join-Path $repo 'ai-service/build-output/test-classes/com/mengde/clouddrive/ai/Day34FullChainProbe.class'))) {
    if(!(Test-Path -LiteralPath $file)){throw 'Build AI test classes and classpath first; never run concurrently with clean'}
}
$output=Join-Path $OutputRoot ('day34-full-chain-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$oldAiUri=$env:CLOUDDRIVE_AI_URI
$env:CLOUDDRIVE_AI_URI='http://127.0.0.1:8084'
$classpath=(Join-Path $repo 'ai-service/build-output/test-classes')+[IO.Path]::PathSeparator+
    (Join-Path $repo 'ai-service/build-output/classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
$powershell=(Get-Process -Id $PID).Path
try {
    & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' '-cp' $classpath 'com.mengde.clouddrive.ai.Day34FullChainProbe' $repo $output $powershell
    if($LASTEXITCODE -ne 0){throw 'Day34 full chain failed; inspect owned runtime report'}
    $report=Get-Content -LiteralPath (Join-Path $output 'full-chain.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($report.status -ne 'PASS' -or $report.realModelCalled -or $report.humanLearningAccepted){throw 'Invalid full-chain receipt'}
    Write-Output "DAY34 FULL CHAIN REPORT: $output"
} finally {
    $env:CLOUDDRIVE_AI_URI=$oldAiUri
    # Recover recorded deployments even if the probe JVM was interrupted.
    Get-ChildItem -LiteralPath (Join-Path $output 'business') -Filter processes.json -Recurse -ErrorAction SilentlyContinue |
        ForEach-Object { & (Join-Path $repo 'deploy/Stop-CloudDrive.ps1') -RunDirectory $_.DirectoryName }
}
