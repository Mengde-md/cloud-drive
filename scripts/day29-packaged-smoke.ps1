# Real packaged deployment, multipart persistence, Range and managed shutdown.
[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=([IO.Path]::GetTempPath()))
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $OutputRoot ('day29-packaged-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$checks=[Collections.Generic.List[string]]::new()
$runs=[Collections.Generic.List[string]]::new()
function Check($name,$condition) {
    if (!$condition) { throw "FAIL: $name" }
    $checks.Add("PASS: $name"); Write-Output "PASS: $name"
}
function Start-Deployment {
    $lines=@(& (Join-Path $repo 'deploy/Start-CloudDrive.ps1') -JavaHome $JavaHome -OutputRoot $output)
    $line=$lines | Where-Object { $_ -like 'DEPLOYMENT READY: *' } | Select-Object -Last 1
    if (!$line) { throw 'Launcher did not return a run directory' }
    $run=$line.Substring('DEPLOYMENT READY: '.Length)
    $runs.Add($run)
    return $run
}
function Json-Post($path,$body) {
    Invoke-RestMethod ('http://127.0.0.1:9000'+$path) -Method POST -ContentType 'application/json' -Body ($body|ConvertTo-Json)
}
try {
    $first=Start-Deployment
    foreach ($port in @(8081,8083,8082,9000)) {
        Check "packaged service health $port" ((Invoke-WebRequest "http://127.0.0.1:$port/actuator/health").StatusCode -eq 200)
    }
    Check 'prod gateway route diagnostics disabled' ((Invoke-WebRequest 'http://127.0.0.1:9000/actuator/gateway/routes' -SkipHttpErrorCheck).StatusCode -eq 404)
    $username='day29_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
    $password=[Guid]::NewGuid().ToString('N')
    $account=Json-Post '/api/auth/register' @{username=$username;password=$password}
    Check 'packaged gateway registration' ($account.id -gt 0)
    $login=Json-Post '/api/auth/login' @{username=$username;password=$password}
    $headers=@{Authorization='Bearer '+$login.accessToken}
    $file=Join-Path $output 'payload.txt'
    $payload='day29-persistent-payload-'+[Guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($file,$payload,[Text.UTF8Encoding]::new($false))
    $uploaded=Invoke-RestMethod 'http://127.0.0.1:9000/api/files/upload' -Method POST -Headers $headers -Form @{displayName='day29-persist.txt';file=(Get-Item -LiteralPath $file)}
    Check 'multipart upload uses actual storage and quota chain' ($uploaded.id -gt 0)
    $download=Join-Path $output 'download-before.txt'
    Invoke-WebRequest "http://127.0.0.1:9000/api/files/$($uploaded.id)/download" -Headers $headers -OutFile $download
    Check 'download bytes match before restart' ((Get-FileHash $file).Hash -eq (Get-FileHash $download).Hash)
    & (Join-Path $repo 'deploy/Stop-CloudDrive.ps1') -RunDirectory $first
    $second=Start-Deployment
    Check 'root migrations are idempotent on restart' ((Get-Content (Join-Path $second 'migration.log') -Raw) -match 'executed=0')
    $after=Join-Path $output 'download-after.txt'
    Invoke-WebRequest "http://127.0.0.1:9000/api/files/$($uploaded.id)/download" -Headers $headers -OutFile $after
    Check 'persistent object survives all four process restarts' ((Get-FileHash $file).Hash -eq (Get-FileHash $after).Hash)
    $rangeHeaders=$headers.Clone();$rangeHeaders.Range='bytes=0-4'
    $range=Invoke-WebRequest "http://127.0.0.1:9000/api/files/$($uploaded.id)/download" -Headers $rangeHeaders -SkipHttpErrorCheck
    Check 'Range remains 206 after restart' ($range.StatusCode -eq 206 -and $range.Headers['Content-Range'][0] -like 'bytes 0-4/*')
    & (Join-Path $PSScriptRoot 'day29-verify-jars.ps1') -OutputPath (Join-Path $output 'artifact-hashes.json')
    $checks | Set-Content -LiteralPath (Join-Path $output 'checks.txt') -Encoding UTF8
    Write-Output "PACKAGED SMOKE PASS: $($checks.Count) checks; $output"
} finally {
    $errors=[Collections.Generic.List[string]]::new()
    foreach ($run in $runs) {
        try { & (Join-Path $repo 'deploy/Stop-CloudDrive.ps1') -RunDirectory $run } catch { $errors.Add($_.Exception.Message) }
    }
    if ($errors.Count) { throw ('Cleanup requires attention: '+($errors -join '; ')) }
}
