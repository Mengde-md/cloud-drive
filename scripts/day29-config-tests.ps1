# Dependency-free contract tests; PowerShell 7 is available on GitHub Ubuntu runners.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$test = Join-Path $repo 'deploy/Test-DeploymentSettings.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('clouddrive-config-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$count = 0
function Expect-Failure($block, $label) {
    $failed = $false
    try { & $block | Out-Null } catch {
        $failed = $true
        if ($_.Exception.Message -match 'sensitive-canary') { throw 'Failure leaked setting value' }
    }
    if (!$failed) { throw "Expected rejection: $label" }
    $script:count++
    Write-Output "PASS: $label"
}
$valid = @{
    CLOUDDRIVE_DB_URL='jdbc:mysql://127.0.0.1:13306/ci'; CLOUDDRIVE_DB_USERNAME='ci_app'; CLOUDDRIVE_DB_PASSWORD='sensitive-canary'
    CLOUDDRIVE_REDIS_HOST='127.0.0.1'; CLOUDDRIVE_REDIS_PORT='16379'; CLOUDDRIVE_REDIS_PASSWORD='sensitive-canary'
    CLOUDDRIVE_JWT_SECRET=('j' * 48); CLOUDDRIVE_INTERNAL_SERVICE_TOKEN=('i' * 48)
    CLOUDDRIVE_STORAGE_ROOT=(Join-Path $temp 'objects'); CLOUDDRIVE_CHUNKS_ROOT=(Join-Path $temp 'chunks')
    CLOUDDRIVE_AUTH_URI='http://127.0.0.1:8081'; CLOUDDRIVE_USER_URI='http://127.0.0.1:8083'; CLOUDDRIVE_FILE_URI='http://127.0.0.1:8082'
}
$original = $env:CLOUDDRIVE_DAY29_IMPORT_TEST
try {
    & $test -Settings $valid | Out-Null
    $count++; Write-Output 'PASS: explicit isolated settings accepted'
    foreach ($key in $valid.Keys) {
        $input = $valid.Clone(); $input.Remove($key)
        Expect-Failure { & $test -Settings $input } "missing $key"
    }
    $input = $valid.Clone(); $input.CLOUDDRIVE_JWT_SECRET='short-sensitive-canary'
    Expect-Failure { & $test -Settings $input } 'short signing secret'
    $input = $valid.Clone(); $input.CLOUDDRIVE_INTERNAL_SERVICE_TOKEN=$input.CLOUDDRIVE_JWT_SECRET
    Expect-Failure { & $test -Settings $input } 'shared JWT and service token'
    $input = $valid.Clone(); $input.CLOUDDRIVE_DB_USERNAME='root'
    Expect-Failure { & $test -Settings $input } 'root deployment account'
    $input = $valid.Clone(); $input.CLOUDDRIVE_STORAGE_ROOT='relative'
    Expect-Failure { & $test -Settings $input } 'relative persistent directory'
    $input = $valid.Clone(); $input.CLOUDDRIVE_CHUNKS_ROOT=$input.CLOUDDRIVE_STORAGE_ROOT
    Expect-Failure { & $test -Settings $input } 'overlapping directories'
    $input = $valid.Clone(); $input.CLOUDDRIVE_CHUNKS_ROOT=Join-Path $input.CLOUDDRIVE_STORAGE_ROOT 'nested'
    Expect-Failure { & $test -Settings $input } 'nested persistent directories'
    $input = $valid.Clone(); $input.CLOUDDRIVE_REDIS_PORT='70000'
    Expect-Failure { & $test -Settings $input } 'invalid Redis port'
    $input = $valid.Clone(); $input.CLOUDDRIVE_AUTH_URI='http://127.0.0.1:9999'
    Expect-Failure { & $test -Settings $input } 'wrong fixed service port'
    $input = $valid.Clone(); $input.CLOUDDRIVE_DB_URL+='?password=sensitive-canary'
    Expect-Failure { & $test -Settings $input } 'credentials in JDBC URL'
    $input = $valid.Clone(); $input.CLOUDDRIVE_AUTH_URI='http://remote.invalid:8081'
    Expect-Failure { & $test -Settings $input } 'unexpected remote provider'
    $file = Join-Path $temp '.env'
    @('CLOUDDRIVE_DAY29_IMPORT_TEST=first','INVALID_KEY=sensitive-canary') | Set-Content -LiteralPath $file
    Expect-Failure { & (Join-Path $repo 'deploy/Import-CloudDriveEnv.ps1') -Path $file } 'invalid import is atomic'
    if ($env:CLOUDDRIVE_DAY29_IMPORT_TEST -cne $original) { throw 'Partial import occurred' }
    @('CLOUDDRIVE_DAY29_IMPORT_TEST=first','CLOUDDRIVE_DAY29_IMPORT_TEST=duplicate') | Set-Content -LiteralPath $file
    Expect-Failure { & (Join-Path $repo 'deploy/Import-CloudDriveEnv.ps1') -Path $file } 'duplicate key rejected'
    @('# comment','CLOUDDRIVE_DAY29_IMPORT_TEST=value=with=equals') | Set-Content -LiteralPath $file
    & (Join-Path $repo 'deploy/Import-CloudDriveEnv.ps1') -Path $file
    if ($env:CLOUDDRIVE_DAY29_IMPORT_TEST -cne 'value=with=equals') { throw 'Value was split more than once' }
    $count++; Write-Output 'PASS: import preserves equals in value'
    Write-Output "Config contracts PASS: $count"
} finally {
    [Environment]::SetEnvironmentVariable('CLOUDDRIVE_DAY29_IMPORT_TEST', $original, 'Process')
    $resolved = [IO.Path]::GetFullPath($temp)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (!$resolved.StartsWith($prefix) -or !(Split-Path $resolved -Leaf).StartsWith('clouddrive-config-')) { throw 'Unsafe cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
