$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$tracked = @(& git -C $repo ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tracked files' }
$leakedEnv = @($tracked | Where-Object { (Split-Path $_ -Leaf) -match '^\.env($|\.)' -and (Split-Path $_ -Leaf) -ne '.env.example' })
if ($leakedEnv.Count) { throw 'A non-example environment file is tracked' }
$violations = [Collections.Generic.List[string]]::new()
foreach ($module in @('', 'auth-service','user-service','file-service','gateway')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repo ($module+'/src')) -Recurse -File) {
        if ($file.Extension -notin @('.java','.properties','.yml','.yaml')) { continue }
        $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        if ($text -match '\$\{CLOUDDRIVE_DB_PASSWORD:[^}]+' -or
            $text -match '(?m)^spring\.datasource\.password=(?!\$\{|\s*$).+') {
            $violations.Add($file.FullName)
        }
    }
}
if ($violations.Count) { throw ('Non-empty database password defaults found in: '+($violations -join ', ')) }
Write-Output 'Secret hygiene PASS: no tracked private env or database password fallbacks in source.'
# This is a targeted current-tree contract, not a history scan or a general secret detector.
