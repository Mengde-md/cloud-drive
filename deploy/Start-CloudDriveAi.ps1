[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=(Join-Path $PSScriptRoot 'runtime'))
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if([Text.Encoding]::UTF8.GetByteCount([string]$env:CLOUDDRIVE_JWT_SECRET) -lt 32){throw 'Set CLOUDDRIVE_JWT_SECRET to the shared signing material'}
$java=Join-Path $JavaHome ('bin/java'+$(if($IsWindows){'.exe'}else{''}))
$jar=Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar)){throw 'JDK17 or packaged AI service missing'}
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,8084)
try{$listener.Start()}finally{$listener.Stop()}
$output=Join-Path $OutputRoot ('ai-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$parameters=@{FilePath=$java;PassThru=$true;WorkingDirectory=$repo
    ArgumentList=@('-jar',('"'+$jar+'"'),'--server.address=127.0.0.1','--server.port=8084',
        '--clouddrive.ai.base-url=https://api.deepseek.com/v1','--clouddrive.ai.protocol-fixture=false','--clouddrive.ai.model=deepseek-flash')
    RedirectStandardOutput=(Join-Path $output 'ai.out.log');RedirectStandardError=(Join-Path $output 'ai.err.log')}
if($IsWindows){$parameters.WindowStyle='Hidden'}
$process=Start-Process @parameters
try{
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($process.HasExited){throw 'AI service exited; inspect secured startup logs'}
        try{if((Invoke-WebRequest 'http://127.0.0.1:8084/actuator/health' -TimeoutSec 2).StatusCode -eq 200){$ready=$true;break}}catch{}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'AI service health timeout'}
    @(@{Name='ai';Id=$process.Id;Java=$java;StartedUtc=$process.StartTime.ToUniversalTime().ToString('O')}) |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'processes.json') -Encoding UTF8
    Write-Output "AI DEPLOYMENT READY: $output; external invocation defaults to disabled"
}catch{
    if(!$process.HasExited){$process.Kill();$process.WaitForExit()}
    throw
}
