[CmdletBinding()]
param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot=[IO.Path]::GetTempPath())
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar'
$classpathFile=Join-Path $repo 'ai-service/build-output/day32-test-classpath.txt'
foreach($file in @($jar,$classpathFile,(Join-Path $repo 'ai-service/build-output/test-classes/com/mengde/clouddrive/ai/Day33PackagedProbe.class'))) {
    if(!(Test-Path -LiteralPath $file)){throw 'Build AI JAR, test classes, and test classpath first; do not run concurrently with clean'}
}
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach($name in @('AgentTelemetry','McpBoundedBody')) {
        if(!$archive.GetEntry("BOOT-INF/classes/com/mengde/clouddrive/ai/$name.class")){throw "Missing Day33 packaged class: $name"}
    }
} finally {$archive.Dispose()}
$output=Join-Path $OutputRoot ('day33-packaged-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$classpath=(Join-Path $repo 'ai-service/build-output/test-classes')+[IO.Path]::PathSeparator+
    (Join-Path $repo 'ai-service/build-output/classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath $classpathFile -Raw).Trim()
& (Join-Path $JavaHome 'bin/java.exe') '-cp' $classpath 'com.mengde.clouddrive.ai.Day33PackagedProbe' $repo $output
if($LASTEXITCODE -ne 0){throw 'Day33 package probe failed; inspect its owned runtime directory'}
Write-Output ('DAY33 VERIFIED AI SHA256='+(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash)
