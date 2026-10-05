[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=[IO.Path]::GetTempPath())
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'day30-verify-ai-jar.ps1')
$output=Join-Path $OutputRoot ('day30-packaged-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$java=Join-Path $JavaHome ('bin/java'+$(if($IsWindows){'.exe'}else{''}))
$pwsh=(Get-Process -Id $PID).Path
$owned=[Collections.Generic.List[object]]::new()
$checks=[Collections.Generic.List[string]]::new()
$keys=@('CLOUDDRIVE_JWT_SECRET','CLOUDDRIVE_AI_ENABLED','CLOUDDRIVE_AI_API_KEY','CLOUDDRIVE_AI_URI','SPRING_PROFILES_ACTIVE')
$old=@{};foreach($key in $keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
function Check([string]$name,[bool]$ok){if(!$ok){throw "FAIL: $name"};$checks.Add($name);Write-Output "PASS: $name"}
function Ready([int]$port,[string]$path){
    for($i=0;$i -lt 60;$i++){
        try{if((Invoke-WebRequest "http://127.0.0.1:$port$path" -TimeoutSec 2).StatusCode -eq 200){return}}catch{}
        if($owned|Where-Object HasExited){throw 'Owned smoke process exited'}
        Start-Sleep -Seconds 1
    }
    throw "Readiness timeout: $port"
}
function Start-Owned($file,$arguments,$name){
    $parameters=@{FilePath=$file;ArgumentList=$arguments;PassThru=$true;WorkingDirectory=$repo
        RedirectStandardOutput=(Join-Path $output ($name+'.out.log'));RedirectStandardError=(Join-Path $output ($name+'.err.log'))}
    if($IsWindows){$parameters.WindowStyle='Hidden'}
    $process=Start-Process @parameters;$owned.Add($process)
}
try{
    foreach($port in @(18084,18085,19000)){
        $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
        try{$listener.Start()}finally{$listener.Stop()}
    }
    $env:CLOUDDRIVE_JWT_SECRET=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
    $env:CLOUDDRIVE_AI_ENABLED='true';$env:CLOUDDRIVE_AI_API_KEY='test-only-packaged-protocol-key'
    $env:CLOUDDRIVE_AI_URI='http://127.0.0.1:18084';$env:SPRING_PROFILES_ACTIVE=$null
    Start-Owned $pwsh @('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'day30-protocol-fixture.ps1')+'"'),'-Port','18085') 'fixture'
    Ready 18085 '/health'
    Start-Owned $java @('-jar',('"'+(Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar')+'"'),
        '--server.port=18084','--clouddrive.ai.protocol-fixture=true','--clouddrive.ai.base-url=http://127.0.0.1:18085/v1') 'ai'
    Ready 18084 '/actuator/health'
    Start-Owned $java @('-jar',('"'+(Join-Path $repo 'gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar')+'"'),
        '--server.port=19000','--spring.profiles.active=prod','--clouddrive.gateway.ai-uri=http://127.0.0.1:18084',
        '--clouddrive.gateway.auth-uri=http://127.0.0.1:8081','--clouddrive.gateway.file-uri=http://127.0.0.1:8082') 'gateway'
    Ready 19000 '/actuator/health'
    Check 'packaged AI and gateway health' $true
    function Base64Url([byte[]]$bytes){[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
    $header=Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $claims=@{iss='cloud-drive-learning';sub='7';exp=[DateTimeOffset]::UtcNow.AddMinutes(2).ToUnixTimeSeconds();username='packaged-user';roles=@('USER')}|ConvertTo-Json -Compress
    $payload=Base64Url ([Text.Encoding]::UTF8.GetBytes($claims));$signed=$header+'.'+$payload
    $mac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:CLOUDDRIVE_JWT_SECRET))
    try{$token=$signed+'.'+(Base64Url ($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signed))))}finally{$mac.Dispose()}
    $headers=@{Authorization='Bearer '+$token}
    $body=@{text='packaged';allowExternalProcessing=$true}|ConvertTo-Json -Compress
    $anonymous=Invoke-WebRequest 'http://127.0.0.1:19000/api/ai/summary' -Method Post -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
    Check 'gateway rejects anonymous AI request' ($anonymous.StatusCode -eq 401)
    $spoof=Invoke-WebRequest 'http://127.0.0.1:18084/api/ai/summary' -Method Post -Headers @{'X-User-Id'='7'} -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
    Check 'AI direct access rejects forged identity headers' ($spoof.StatusCode -eq 401)
    $response=Invoke-WebRequest 'http://127.0.0.1:19000/api/ai/summary' -Method Post -Headers $headers -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
    $content=$response.Content|ConvertFrom-Json
    Check 'gateway forwards to executable AI service and real SDK' ($response.StatusCode -eq 200 -and $content.output.summary -eq 'Packaged protocol summary')
    Check 'usage is marked as fixture with exact decimal estimate' ($content.usage.protocolFixture -and $content.usage.totalTokens -eq 150 -and $content.usage.estimatedUsd -eq 0.00009)
    $events=Invoke-WebRequest 'http://127.0.0.1:19000/api/ai/summary/stream' -Method Post -Headers $headers -ContentType 'application/json' -Body $body -SkipHttpErrorCheck
    $eventText=if($events.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($events.Content)}else{[string]$events.Content}
    Check 'SSE passes gateway with progress before validated result' ($events.StatusCode -eq 200 -and $eventText.Contains('event:progress') -and $eventText.Contains('event:result') -and $eventText.IndexOf('event:progress') -lt $eventText.IndexOf('event:result'))
    $invalid=@{text='invalid';allowExternalProcessing=$true}|ConvertTo-Json -Compress
    $bad=Invoke-WebRequest 'http://127.0.0.1:19000/api/ai/summary' -Method Post -Headers $headers -ContentType 'application/json' -Body $invalid -SkipHttpErrorCheck
    Check 'invalid model schema is rejected after gateway authentication' ($bad.StatusCode -eq 502)
    $checks|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding UTF8
    Write-Output "DAY30 PACKAGED SMOKE PASS: $($checks.Count); $output"
}finally{
    $cleanup=[Collections.Generic.List[string]]::new()
    foreach($process in $owned){try{if(!$process.HasExited){$process.Kill();$process.WaitForExit()}}catch{$cleanup.Add("PID $($process.Id)")}}
    foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
    if($cleanup.Count){throw ('Owned process cleanup failed: '+($cleanup -join ', '))}
}
