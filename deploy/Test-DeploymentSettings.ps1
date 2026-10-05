[CmdletBinding()]
param([System.Collections.IDictionary]$Settings)
$ErrorActionPreference = 'Stop'
if ($null -eq $Settings) {
    $Settings = @{}
    Get-ChildItem Env: | ForEach-Object { $Settings[$_.Name] = $_.Value }
}
$required = @('CLOUDDRIVE_DB_URL','CLOUDDRIVE_DB_USERNAME','CLOUDDRIVE_DB_PASSWORD',
    'CLOUDDRIVE_REDIS_HOST','CLOUDDRIVE_REDIS_PORT','CLOUDDRIVE_REDIS_PASSWORD',
    'CLOUDDRIVE_JWT_SECRET','CLOUDDRIVE_INTERNAL_SERVICE_TOKEN',
    'CLOUDDRIVE_STORAGE_ROOT','CLOUDDRIVE_CHUNKS_ROOT',
    'CLOUDDRIVE_AUTH_URI','CLOUDDRIVE_USER_URI','CLOUDDRIVE_FILE_URI')
foreach ($key in $required) {
    $value = [string]$Settings[$key]
    if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('change-me')) {
        throw "Missing deployment setting: $key"
    }
}
foreach ($key in @('CLOUDDRIVE_JWT_SECRET','CLOUDDRIVE_INTERNAL_SERVICE_TOKEN')) {
    if ([Text.Encoding]::UTF8.GetByteCount([string]$Settings[$key]) -lt 32) { throw "Secret too short: $key" }
}
if ($Settings.CLOUDDRIVE_JWT_SECRET -ceq $Settings.CLOUDDRIVE_INTERNAL_SERVICE_TOKEN) { throw 'JWT and service token must differ' }
if ($Settings.CLOUDDRIVE_REDIS_HOST -ne '127.0.0.1') { throw 'Single-host Redis must use loopback' }
$redisPort = 0
if (![int]::TryParse([string]$Settings.CLOUDDRIVE_REDIS_PORT,[ref]$redisPort) -or $redisPort -lt 1 -or $redisPort -gt 65535) {
    throw 'Invalid Redis port'
}
if ($Settings.CLOUDDRIVE_DB_USERNAME -eq 'root') { throw 'Deployment requires a schema-scoped non-root account' }
if ($Settings.CLOUDDRIVE_DB_URL -notmatch '^jdbc:mysql://' -or $Settings.CLOUDDRIVE_DB_URL -match '(?i)[?&](user|password)=') {
    throw 'Use a credential-free MySQL JDBC URL'
}
foreach ($key in @('CLOUDDRIVE_STORAGE_ROOT','CLOUDDRIVE_CHUNKS_ROOT')) {
    if (![IO.Path]::IsPathRooted([string]$Settings[$key])) { throw "Persistent directory must be absolute: $key" }
    if ([IO.Path]::GetFullPath($Settings[$key]) -match '(^|[\\/])(build-output|target)([\\/]|$)') {
        throw "Persistent directory must not be Maven output: $key"
    }
}
$objects = [IO.Path]::GetFullPath($Settings.CLOUDDRIVE_STORAGE_ROOT).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$chunks = [IO.Path]::GetFullPath($Settings.CLOUDDRIVE_CHUNKS_ROOT).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if ($objects.StartsWith($chunks,[StringComparison]::OrdinalIgnoreCase) -or $chunks.StartsWith($objects,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Object and chunk directories must differ'
}
$ports = @{CLOUDDRIVE_AUTH_URI=8081;CLOUDDRIVE_USER_URI=8083;CLOUDDRIVE_FILE_URI=8082}
foreach ($key in @('CLOUDDRIVE_AUTH_URI','CLOUDDRIVE_USER_URI','CLOUDDRIVE_FILE_URI')) {
    $uri = $null
    if (![Uri]::TryCreate([string]$Settings[$key], [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -ne $ports[$key] -or $uri.AbsolutePath -ne '/' -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw "Single-host URI must be plain loopback HTTP: $key" }
}
Write-Output 'Deployment settings PASS (values withheld).'
