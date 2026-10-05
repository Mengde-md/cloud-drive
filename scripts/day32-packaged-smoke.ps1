[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=[IO.Path]::GetTempPath())
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
foreach($jar in @('ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar',
    'gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar')) {
    if(!(Test-Path -LiteralPath (Join-Path $repo $jar))){throw 'Build AI and gateway JARs first; never run this probe concurrently with clean'}
}
$classpathFile=Join-Path $repo 'ai-service/build-output/day32-test-classpath.txt'
if(!(Test-Path -LiteralPath $classpathFile)){throw 'Run dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=build-output/day32-test-classpath.txt first'}
$output=Join-Path $OutputRoot ('day32-packaged-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$classpath=(Join-Path $repo 'ai-service/build-output/test-classes')+[IO.Path]::PathSeparator+
    (Join-Path $repo 'ai-service/build-output/classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
& (Join-Path $JavaHome 'bin/java.exe') '-cp' $classpath 'com.mengde.clouddrive.ai.Day32PackagedProbe' $repo $output
if($LASTEXITCODE -ne 0){throw 'Day32 packaged smoke failed; inspect its owned runtime directory'}
