# Single-host launcher. TLS/network policy must be supplied by the hosting environment.
[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=(Join-Path $PSScriptRoot 'runtime'))
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Test-DeploymentSettings.ps1')
$java = Join-Path $JavaHome ('bin/java' + $(if ($IsWindows) { '.exe' } else { '' }))
if (!(Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to JDK 17' }
$legacy = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,8080)
try { $legacy.Start() } catch { throw 'Stop the historical monolith web writer on 8080 first' } finally { $legacy.Stop() }
$apps = @(
    @{Name='auth'; Dir='auth-service'; Jar='cloud-drive-auth-service'; Port=8081},
    @{Name='user'; Dir='user-service'; Jar='cloud-drive-user-service'; Port=8083},
    @{Name='file'; Dir='file-service'; Jar='cloud-drive-file-service'; Port=8082},
    @{Name='gateway'; Dir='gateway'; Jar='cloud-drive-gateway'; Port=9000})
foreach ($app in $apps) {
    $app.Path = Join-Path $repo ($app.Dir + '/build-output/' + $app.Jar + '-0.1.0-SNAPSHOT.jar')
    if (!(Test-Path -LiteralPath $app.Path -PathType Leaf)) { throw "Package missing: $($app.Name)" }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $app.Port)
    try { $listener.Start() } catch { throw "Port already occupied: $($app.Port)" } finally { $listener.Stop() }
}
$migration = Join-Path $repo 'build-output/cloud-drive-learning-0.1.0-SNAPSHOT-migration.jar'
if (!(Test-Path -LiteralPath $migration -PathType Leaf)) { throw 'Package the root migration JAR first' }
$output = Join-Path $OutputRoot ('run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $java -jar $migration *> (Join-Path $output 'migration.log')
if ($LASTEXITCODE -ne 0) { throw 'Schema migration failed; inspect secured migration.log' }
$owned = [Collections.Generic.List[object]]::new()
$records = [Collections.Generic.List[object]]::new()
try {
    foreach ($app in $apps) {
        $arguments = @('-jar', ('"' + $app.Path + '"'), '--spring.profiles.active=prod',
            '--server.address=127.0.0.1', "--server.port=$($app.Port)",
            '--spring.cloud.nacos.discovery.enabled=false','--spring.cloud.nacos.config.enabled=false',
            '--spring.cloud.nacos.config.import-check.enabled=false',
            '--spring.cloud.openfeign.client.config.cloud-drive-auth.url=http://127.0.0.1:8081',
            '--spring.cloud.openfeign.client.config.cloud-drive-user.url=http://127.0.0.1:8083',
            '--clouddrive.gateway.auth-uri=http://127.0.0.1:8081','--clouddrive.gateway.file-uri=http://127.0.0.1:8082')
        $parameters = @{FilePath=$java; ArgumentList=$arguments; WorkingDirectory=$repo; PassThru=$true
            RedirectStandardOutput=(Join-Path $output ($app.Name+'.out.log'))
            RedirectStandardError=(Join-Path $output ($app.Name+'.err.log'))}
        if ($IsWindows) { $parameters.WindowStyle='Hidden' }
        $process = Start-Process @parameters
        $owned.Add($process)
        $records.Add(@{Name=$app.Name; Id=$process.Id; Java=$java; StartedUtc=$process.StartTime.ToUniversalTime().ToString('O')})
        $ready = $false
        for ($i=0; $i -lt 90; $i++) {
            if ($process.HasExited) { throw "Application exited: $($app.Name); inspect secured startup log" }
            try {
                $response = Invoke-WebRequest "http://127.0.0.1:$($app.Port)/actuator/health" -TimeoutSec 2 -SkipHttpErrorCheck
                if ($response.StatusCode -eq 200) { $ready=$true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw "Health timeout: $($app.Name)" }
        Write-Output "READY: $($app.Name) port=$($app.Port)"
    }
    $records | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'processes.json') -Encoding UTF8
    Write-Output "DEPLOYMENT READY: $output"
} catch {
    $failure = $_
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    foreach ($process in $owned) {
        try { if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() } }
        catch { $cleanupErrors.Add("PID $($process.Id)") }
    }
    if ($cleanupErrors.Count) { throw ('Deployment failed; cleanup requires attention: ' + ($cleanupErrors -join ', ')) }
    throw $failure
}
