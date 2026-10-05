[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=[IO.Path]::GetTempPath())
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'day31-verify-rag-jar.ps1')
$output=Join-Path $OutputRoot ('day31-packaged-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$java=Join-Path $JavaHome ('bin/java'+$(if($IsWindows){'.exe'}else{''}))
$pwsh=(Get-Process -Id $PID).Path
$owned=[Collections.Generic.List[object]]::new();$checks=[Collections.Generic.List[string]]::new()
$keys=@('CLOUDDRIVE_JWT_SECRET','CLOUDDRIVE_AI_ENABLED','CLOUDDRIVE_AI_API_KEY','CLOUDDRIVE_AI_URI','CLOUDDRIVE_RAG_ENABLED','SPRING_PROFILES_ACTIVE')
$old=@{};foreach($key in $keys){$old[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
function Check([string]$name,[bool]$ok){if(!$ok){throw "FAIL: $name"};$checks.Add($name);Write-Output "PASS: $name"}
function Ready([int]$port,$process) {
    for($i=0;$i -lt 60;$i++) {
        try {if((Invoke-WebRequest "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2).StatusCode -eq 200){return}}catch{}
        if($process -and $process.HasExited){throw 'Owned process failed'}
        Start-Sleep -Seconds 1
    }
    throw "Readiness timeout: $port"
}
function Start-Owned($file,$arguments,$name) {
    $parameters=@{FilePath=$file;ArgumentList=$arguments;PassThru=$true;WorkingDirectory=$repo
        RedirectStandardOutput=(Join-Path $output ($name+'.out.log'));RedirectStandardError=(Join-Path $output ($name+'.err.log'))}
    if($IsWindows){$parameters.WindowStyle='Hidden'}
    $process=Start-Process @parameters;$owned.Add($process)
    return $process
}
function Base64Url([byte[]]$bytes){[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
function Token([string]$subject) {
    $header=Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $claims=@{iss='cloud-drive-learning';sub=$subject;exp=[DateTimeOffset]::UtcNow.AddMinutes(5).ToUnixTimeSeconds();roles=@('USER')}|ConvertTo-Json -Compress
    $signed=$header+'.'+(Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))
    $mac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:CLOUDDRIVE_JWT_SECRET))
    try{return $signed+'.'+(Base64Url ($mac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signed))))}finally{$mac.Dispose()}
}
function Request([string]$method,[string]$path,$body,$headers) {
    $parameters=@{Uri="http://127.0.0.1:19100/api/ai/rag$path";Method=$method;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=15}
    if($null -ne $body){$parameters.ContentType='application/json';$parameters.Body=($body|ConvertTo-Json -Compress)}
    return Invoke-WebRequest @parameters
}
try {
    foreach($port in @(18184,18185,19100)) {
        $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
        try{$listener.Start()}finally{$listener.Stop()}
    }
    $env:CLOUDDRIVE_JWT_SECRET=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
    $env:CLOUDDRIVE_AI_ENABLED='true';$env:CLOUDDRIVE_AI_API_KEY='day31-packaged-protocol-test-key'
    $env:CLOUDDRIVE_RAG_ENABLED='true';$env:CLOUDDRIVE_AI_URI='http://127.0.0.1:18184';$env:SPRING_PROFILES_ACTIVE=$null
    Start-Owned $pwsh @('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'day31-protocol-fixture.ps1')+'"')) 'fixture' | Out-Null
    for($i=0;$i -lt 30;$i++){try{Invoke-WebRequest 'http://127.0.0.1:18185/health' -TimeoutSec 1 | Out-Null;break}catch{Start-Sleep -Seconds 1}}
    $arguments=@('-jar',('"'+(Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar')+'"'),
        '--server.port=18184','--clouddrive.ai.protocol-fixture=true','--clouddrive.ai.base-url=http://127.0.0.1:18185/v1')
    $ai=Start-Owned $java $arguments 'ai';Ready 18184 $ai
    $gateway=Start-Owned $java @('-jar',('"'+(Join-Path $repo 'gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar')+'"'),
        '--server.port=19100','--spring.profiles.active=prod','--clouddrive.gateway.ai-uri=http://127.0.0.1:18184',
        '--clouddrive.gateway.auth-uri=http://127.0.0.1:8081','--clouddrive.gateway.file-uri=http://127.0.0.1:8082') 'gateway'
    Ready 19100 $gateway;Check 'real AI and gateway packages healthy' $true
    $headers=@{Authorization='Bearer '+(Token '7')};$other=@{Authorization='Bearer '+(Token '8')}
    Check 'gateway rejects anonymous RAG' ((Request 'GET' '/documents' $null @{}).StatusCode -eq 401)
    $spoof=Invoke-WebRequest 'http://127.0.0.1:18184/api/ai/rag/documents' -Headers @{'X-User-Id'='7'} -SkipHttpErrorCheck
    Check 'direct AI rejects spoofed identity' ($spoof.StatusCode -eq 401)
    $text='Deleted files are moved into the recycle bin for thirty days. Users can restore deleted files during this retention period.'
    $document=Request 'POST' '/documents' @{name='trash.txt';contentBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))} $headers
    $info=$document.Content|ConvertFrom-Json
    Check 'UTF8 document imported through gateway' ($document.StatusCode -eq 201 -and $info.chunks -eq 1 -and $info.sourceSha256.Length -eq 64)
    $question=@{question='How long are deleted files kept in the recycle bin?'}
    $search=(Request 'POST' '/search' $question $headers).Content|ConvertFrom-Json
    Check 'bundled ONNX produces scoped vector hit' ($search.hits.Count -ge 1 -and $search.hits[0].documentId -eq $info.documentId -and $search.hits[0].score -ge .78)
    $private=(Request 'POST' '/search' $question $other).Content|ConvertFrom-Json
    Check 'other owner sees no vector hits' ($private.hits.Count -eq 0)
    $ask=@{question=$question.question;allowExternalProcessing=$true}
    $empty=(Request 'POST' '/answer' $ask $other).Content|ConvertFrom-Json
    $stats=Invoke-RestMethod 'http://127.0.0.1:18185/stats'
    Check 'no evidence refuses without provider call' ($empty.reason -eq 'NO_EVIDENCE' -and $null -eq $empty.usage -and $stats.calls -eq 0)
    $response=Request 'POST' '/answer' $ask $headers;$answer=$response.Content|ConvertFrom-Json
    Check 'SDK answer has exact quote and fixture usage' ($response.StatusCode -eq 200 -and $answer.status -eq 'ANSWERED' -and
        $answer.citations[0].documentId -eq $info.documentId -and $text.Contains($answer.citations[0].quote) -and
        $answer.usage.totalTokens -eq 150 -and $answer.usage.protocolFixture)
    Invoke-RestMethod 'http://127.0.0.1:18185/mode/forged' | Out-Null
    $bad=Request 'POST' '/answer' $ask $headers
    Check 'forged model citation rejected' ($bad.StatusCode -eq 502 -and ($bad.Content|ConvertFrom-Json).code -eq 'RAG_OUTPUT_INVALID')
    Check 'owned document deleted' ((Request 'DELETE' ('/documents/'+$info.documentId) $null $headers).StatusCode -eq 204)
    $deleted=(Request 'POST' '/answer' $ask $headers).Content|ConvertFrom-Json
    Check 'deleted evidence cannot answer or call provider' ($deleted.reason -eq 'NO_EVIDENCE' -and (Invoke-RestMethod 'http://127.0.0.1:18185/stats').calls -eq 2)
    Request 'POST' '/documents' @{name='trash.txt';contentBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))} $headers | Out-Null
    $ai.Kill();$ai.WaitForExit()
    $ai=Start-Owned $java $arguments 'ai-restarted';Ready 18184 $ai
    $listed=Request 'GET' '/documents' $null $headers
    Check 'actual process restart clears volatile corpus' ($listed.StatusCode -eq 200 -and ($listed.Content|ConvertFrom-Json).Count -eq 0)
    $checks|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding UTF8
    Write-Output "DAY31 PACKAGED SMOKE PASS: $($checks.Count); $output"
} finally {
    $cleanup=[Collections.Generic.List[string]]::new()
    foreach($process in $owned){try{if(!$process.HasExited){$process.Kill();$process.WaitForExit()}}catch{$cleanup.Add("PID $($process.Id)")}}
    foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
    if($cleanup.Count){throw ('Owned process cleanup failed: '+($cleanup -join ', '))}
}
