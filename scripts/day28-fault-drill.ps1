# Requires PowerShell 7, successful module tests, migrated MySQL, Redis, and process-scoped DB credentials.
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$RedisConfig,
    [string]$OutputRoot = ([IO.Path]::GetTempPath())
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$java = Join-Path $JavaHome 'bin/java.exe'
$output = Join-Path $OutputRoot ('day28-runtime-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (!$env:CLOUDDRIVE_DB_USERNAME -or !$env:CLOUDDRIVE_DB_PASSWORD) { throw 'Set process DB credentials first; never put them in this file.' }
New-Item -ItemType Directory -Path $output | Out-Null
$names = @('CLOUDDRIVE_JWT_SECRET','CLOUDDRIVE_INTERNAL_SERVICE_TOKEN','CLOUDDRIVE_STORAGE_ROOT','CLOUDDRIVE_CHUNKS_ROOT',
    'CLOUDDRIVE_SEARCH_ENABLED','CLOUDDRIVE_SEARCH_URI','CLOUDDRIVE_SEARCH_INDEX')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:CLOUDDRIVE_JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:CLOUDDRIVE_INTERNAL_SERVICE_TOKEN = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:CLOUDDRIVE_STORAGE_ROOT = Join-Path $output 'objects'
$env:CLOUDDRIVE_CHUNKS_ROOT = Join-Path $output 'chunks'
$env:CLOUDDRIVE_SEARCH_ENABLED = 'true'
$env:CLOUDDRIVE_SEARCH_URI = 'http://127.0.0.1:19201'
$env:CLOUDDRIVE_SEARCH_INDEX = 'day28-unreachable'
$processes = [Collections.Generic.List[object]]::new()
$checks = [Collections.Generic.List[string]]::new()
$script:classpath = @{}
function Check($name, $condition) {
    if (!$condition) { throw "FAIL: $name" }
    $checks.Add("PASS: $name"); Write-Output "PASS: $name"
}
function Request($port, $path, $method = 'GET', $body = $null, $headers = @{}) {
    $parameters = @{Uri="http://127.0.0.1:$port$path";Method=$method;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=40}
    if ($null -ne $body) { $parameters.Body=($body | ConvertTo-Json -Compress); $parameters.ContentType='application/json' }
    $response = Invoke-WebRequest @parameters
    # Actuator's vendor JSON media type may be returned as bytes by PowerShell.
    $content = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { $response.Content }
    [pscustomobject]@{StatusCode=$response.StatusCode; Headers=$response.Headers; Content=$content}
}
function Start-App($app) {
    $dir = Join-Path $repo $app.Dir
    $report = Get-ChildItem (Join-Path $dir 'build-output/surefire-reports') -Filter 'TEST-*.xml' | Select-Object -First 1
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding UTF8
    $cp = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $cp = (($cp -split ';') | Where-Object { $_ -and $_ -notmatch '[\\/]test-classes[\\/]?$' }) -join ';'
    $script:classpath[$app.Name] = $cp
    $arguments = @('-cp', ('"'+$cp+'"'), $app.Main, '--server.address=127.0.0.1', "--server.port=$($app.Port)",
        '--spring.profiles.active=default', '--spring.cloud.nacos.discovery.enabled=false',
        '--spring.cloud.nacos.config.enabled=false', '--spring.cloud.nacos.config.import-check.enabled=false',
        '--spring.cloud.openfeign.client.config.cloud-drive-auth.url=http://127.0.0.1:8081',
        '--spring.cloud.openfeign.client.config.cloud-drive-user.url=http://127.0.0.1:8083')
    if ($app.Name -eq 'gateway') {
        $arguments += @('--clouddrive.gateway.auth-uri=http://127.0.0.1:8081',
            '--clouddrive.gateway.file-uri=http://127.0.0.1:8082', '--clouddrive.gateway.user-uri=http://127.0.0.1:8080')
    }
    if ($app.Name -eq 'file') {
        if ($RedisConfig) { $arguments += '"--spring.redis.redisson.file=file:' + (Resolve-Path $RedisConfig).Path.Replace('\','/') + '"' }
        # Zero only exercises the logging branch. Real delay/timeout is tested separately, not claimed here.
        $arguments += @('--clouddrive.observability.slow-mapper-ms=0','--clouddrive.observability.slow-call-ms=0')
    }
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0,6)
    $p = Start-Process $java -ArgumentList $arguments -WorkingDirectory $dir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $output ($app.Name+$suffix+'.out.log')) `
        -RedirectStandardError (Join-Path $output ($app.Name+$suffix+'.err.log'))
    $processes.Add($p)
    for ($i=0; $i -lt 75; $i++) {
        if ($p.HasExited) { throw ($app.Name + ' exited; inspect startup log') }
        try { if ((Request $app.Port '/actuator/health').StatusCode -eq 200) { return $p } } catch {}
        Start-Sleep -Seconds 1
    }
    throw ($app.Name + ' health timeout')
}
function Stop-Owned($process) {
    if (!$process.HasExited) {
        $current = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
        if (!$current) { return }
        if ($current.Path -ne $java -or $current.StartTime -ne $process.StartTime) { throw 'Process ownership changed' }
        try { $process.Kill(); $process.WaitForExit() }
        catch { if (!$process.HasExited) { throw } }
    }
}
function Logs($name) { (Get-ChildItem $output -Filter ($name+'*.out.log') | Get-Content -Encoding UTF8) -join "`n" }
function Database-Evidence($owner, $pending, $label) {
    $result = & $java -cp $script:classpath.file (Join-Path $PSScriptRoot 'Day28DatabaseProbe.java') $owner $pending
    if ($LASTEXITCODE -ne 0) { throw 'Database evidence assertion failed' }
    $result | Set-Content (Join-Path $output ($label+'-sql.txt')) -Encoding UTF8
    Check ($label + ' database pending count and real EXPLAIN') $true
}
try {
    foreach ($port in @(8080,8081,8082,8083,9000,19201)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port occupied" }
    }
    $apps=@(
        @{Name='auth';Dir='auth-service';Port=8081;Main='com.mengde.clouddrive.authservice.CloudDriveAuthServiceApplication'},
        @{Name='user';Dir='user-service';Port=8083;Main='com.mengde.clouddrive.userservice.UserServiceApplication'},
        @{Name='file';Dir='file-service';Port=8082;Main='com.mengde.clouddrive.FileServiceApplication'},
        @{Name='gateway';Dir='gateway';Port=9000;Main='com.mengde.clouddrive.gateway.CloudDriveGatewayApplication'}
    )
    $running=@{}
    foreach ($app in $apps) { $running[$app.Name]=Start-App $app; Check ($app.Name+' ready') $true }
    $denied = Request 9000 '/api/entries' 'GET' $null @{'X-Trace-Id'=('f'*32)}
    Check 'gateway 401 has a fresh unspoofable correlation id' ($denied.StatusCode -eq 401 -and $denied.Headers['X-Trace-Id'][0] -match '^[a-f0-9]{32}$' -and $denied.Headers['X-Trace-Id'][0] -ne ('f'*32))
    $username='day28_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
    $password=[Guid]::NewGuid().ToString('N')
    $registered=Request 9000 '/api/auth/register' 'POST' @{username=$username;password=$password}
    Check 'real account registration' ($registered.StatusCode -eq 201)
    $owner=($registered.Content|ConvertFrom-Json).id
    $login=Request 9000 '/api/auth/login' 'POST' @{username=$username;password=$password}
    Check 'real JWT login' ($login.StatusCode -eq 200)
    $auth=@{Authorization='Bearer '+($login.Content|ConvertFrom-Json).accessToken}
    $created=Request 9000 '/api/entries' 'POST' @{displayName='report-private-canary';kind='DOCUMENT';byteSize=1} $auth
    Check 'file write succeeds through real quota chain' ($created.StatusCode -eq 201)
    $trace=$created.Headers['X-Trace-Id'][0]
    foreach ($name in @('gateway','file','user','auth')) { Check ($name+' logs same write trace') ((Logs $name).Contains("traceId=$trace")) }
    Check 'user-to-auth initialization preserves the same trace' (([regex]::Matches((Logs 'auth'), "event=servlet_dispatch traceId=$trace ")).Count -eq 2)
    $admin=@{'X-CloudDrive-Internal-Token'=$env:CLOUDDRIVE_INTERNAL_SERVICE_TOKEN}
    Check 'metrics rejects anonymous and ordinary JWT' ((Request 8082 '/actuator/metrics').StatusCode -eq 401 -and (Request 8082 '/actuator/metrics' 'GET' $null $auth).StatusCode -eq 401)
    Check 'metrics is not routed through public gateway' ((Request 9000 '/actuator/metrics' 'GET' $null $admin).StatusCode -eq 404)
    $metric=Request 8082 '/actuator/metrics/clouddrive.remote.calls' 'GET' $null $admin
    $metric.Content | Set-Content (Join-Path $output 'remote-metric.json') -Encoding UTF8
    Write-Output "Remote metric HTTP status=$($metric.StatusCode)"
    Check 'protected actuator exposes actual remote Timer' ($metric.StatusCode -eq 200 -and ($metric.Content|ConvertFrom-Json).name -eq 'clouddrive.remote.calls')
    $search=Request 9000 '/api/files/search?q=report' 'GET' $null $auth
    $page=$search.Content|ConvertFrom-Json
    Check 'unreachable ES yields explicit successful MYSQL degradation' ($search.StatusCode -eq 200 -and $page.source -eq 'MYSQL' -and $page.degraded -and $page.total -eq 1)
    $searchMetric=Request 8082 '/actuator/metrics/clouddrive.search.calls?tag=outcome:MYSQL' 'GET' $null $admin
    Check 'degraded HTTP 200 is distinguishable in metrics' (($searchMetric.Content|ConvertFrom-Json).measurements[0].value -ge 1)
    Stop-Owned $running.user
    $failed=Request 9000 '/api/entries' 'POST' @{displayName='outage-private-canary';kind='DIRECTORY'} $auth
    Check 'user process outage returns 503 with trace' ($failed.StatusCode -eq 503 -and $failed.Headers['X-Trace-Id'][0] -match '^[a-f0-9]{32}$')
    $failedTrace=$failed.Headers['X-Trace-Id'][0]
    Check 'quota retries appear as three reserve errors on one trace' (([regex]::Matches((Logs 'file'), "event=remote_call traceId=$failedTrace client=UserQuotaClient operation=reserve outcome=ERROR")).Count -eq 3)
    Database-Evidence $owner 1 'outage'
    Check 'reads remain available during quota outage' ((Request 9000 '/api/entries' 'GET' $null $auth).StatusCode -eq 200)
    $running.user=Start-App $apps[1]
    $recovered=Request 9000 '/api/entries' 'POST' @{displayName='after-recovery';kind='DIRECTORY'} $auth
    Check 'next write recovers after provider restart' ($recovered.StatusCode -eq 201)
    Database-Evidence $owner 0 'recovered'
    $recoveryTrace=$recovered.Headers['X-Trace-Id'][0]
    Check 'recovery release and reserve share new request trace' ((Logs 'file') -match "traceId=$recoveryTrace client=UserQuotaClient operation=release outcome=SUCCESS" -and (Logs 'file') -match "traceId=$recoveryTrace client=UserQuotaClient operation=reserve outcome=SUCCESS")
    & (Join-Path $JavaHome 'bin/jcmd.exe') $running.file.Id Thread.print -l > (Join-Path $output 'file-thread-dump.txt')
    Check 'owned Java process thread dump collected' ($LASTEXITCODE -eq 0 -and (Get-Item (Join-Path $output 'file-thread-dump.txt')).Length -gt 1000)
    $events=((Get-ChildItem $output -Filter '*.out.log' | Get-Content -Encoding UTF8) | Where-Object { $_ -match 'event=' }) -join "`n"
    Check 'mapper structured events emitted with correlation' ($events -match 'event=mapper_call traceId=[a-f0-9]{32} statement=')
    foreach ($secret in @($password,$env:CLOUDDRIVE_INTERNAL_SERVICE_TOKEN,$auth.Authorization.Substring(7),'report-private-canary','outage-private-canary')) {
        if ($events.Contains($secret)) { throw 'Sensitive canary found in structured events' }
    }
    Check 'structured events omit credential and filename canaries' $true
    $checks | Set-Content (Join-Path $output 'checks.txt') -Encoding UTF8
    Write-Output "AUDIT PASSED: $($checks.Count) checks; $output"
} finally {
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    try {
        foreach ($process in $processes) {
            try { Stop-Owned $process } catch { $cleanupErrors.Add("PID $($process.Id): $($_.Exception.Message)") }
        }
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    }
    if ($cleanupErrors.Count) { throw ('Cleanup requires attention: ' + ($cleanupErrors -join '; ')) }
    Write-Output 'Stopped only drill-owned applications and restored process configuration.'
}
