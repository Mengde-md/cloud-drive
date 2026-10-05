param()
$ErrorActionPreference='Stop'
$Repo=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'day30-verify-ai-jar.ps1')
$path=Join-Path $Repo 'ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar'
$archive=[IO.Compression.ZipFile]::OpenRead($path)
try {
    $model=$archive.GetEntry('BOOT-INF/lib/langchain4j-embeddings-all-minilm-l6-v2-q-1.21.0-beta31.jar')
    if(!$model -or !$archive.GetEntry('BOOT-INF/lib/langchain4j-1.21.0.jar')){throw 'RAG dependencies missing'}
    if(!($archive.Entries.FullName -match '^BOOT-INF/lib/onnxruntime-[0-9].*\.jar$')){throw 'ONNX runtime missing'}
    $memory=[IO.MemoryStream]::new();$stream=$model.Open()
    try{$stream.CopyTo($memory)}finally{$stream.Dispose()}
    $memory.Position=0
    $nested=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read)
    try {
        foreach($name in @('all-minilm-l6-v2-q.onnx','all-minilm-l6-v2-q-tokenizer.json')) {
            $entry=$nested.GetEntry($name)
            if(!$entry -or $entry.Length -le 0){throw "Bundled embedding asset missing: $name"}
            $source=$entry.Open()
            try{$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($source))}finally{$source.Dispose()}
            Write-Output "RAG ASSET PASS: $name bytes=$($entry.Length) SHA256=$hash"
        }
    }finally{$nested.Dispose();$memory.Dispose()}
    Write-Output 'DAY31 RAG JAR CONTRACT PASS'
}finally{$archive.Dispose()}
