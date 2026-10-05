[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar'
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    $reader=[IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try{$manifest=$reader.ReadToEnd() -replace '\r?\n ',''}finally{$reader.Dispose()}
    if($manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' -or
        $manifest -notmatch '(?m)^Start-Class: com\.mengde\.clouddrive\.ai\.AiServiceApplication\r?$'){throw 'AI JAR entry mismatch'}
    $names=@($zip.Entries.FullName)
    if($names | Where-Object {$_ -match 'test-classes|BOOT-INF/lib/(junit|mockito|assertj|spring-test|spring-boot-test)'}){throw 'Test library in runtime JAR'}
    if($names -notcontains 'BOOT-INF/lib/langchain4j-open-ai-1.21.0.jar'){throw 'Frozen SDK missing from executable JAR'}
    if($names -notcontains 'BOOT-INF/lib/jackson-databind-2.22.1.jar'){throw 'SDK Jackson contract mismatch'}
}finally{$zip.Dispose()}
Get-FileHash -LiteralPath $jar -Algorithm SHA256 | Select-Object Path,Hash
Write-Output 'DAY30 AI JAR CONTRACT PASS'
