param()
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'day31-verify-rag-jar.ps1')
$jar=Join-Path $repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar'
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach($entry in @('BOOT-INF/lib/mcp-core-2.0.1.jar','BOOT-INF/lib/mcp-json-jackson2-2.0.1.jar',
        'BOOT-INF/classes/com/mengde/clouddrive/ai/AgentService.class',
        'BOOT-INF/classes/com/mengde/clouddrive/ai/CloudDriveMcpServer.class')) {
        if(!$archive.GetEntry($entry)){throw "Missing packaged Agent/MCP entry: $entry"}
    }
    if($archive.Entries.FullName -match '^BOOT-INF/lib/(mcp-json-jackson3-|jackson-(core|databind)-3\.)') {
        throw 'Unexpected Jackson3 mapper/runtime in this Jackson2 service'
    }
    Write-Output ('DAY32 AGENT MCP JAR CONTRACT PASS: SHA256='+(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash)
} finally {$archive.Dispose()}
