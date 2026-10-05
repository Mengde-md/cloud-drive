# This runner never obtains credentials from source files or a developer's home directory.
[CmdletBinding()]
param([string]$Maven='mvn', [string]$MavenRepository, [switch]$LiveSearch)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
foreach ($key in @('CLOUDDRIVE_DB_URL','CLOUDDRIVE_DB_USERNAME','CLOUDDRIVE_DB_PASSWORD',
    'CLOUDDRIVE_REDIS_HOST','CLOUDDRIVE_REDIS_PORT')) {
    if (![Environment]::GetEnvironmentVariable($key,'Process')) { throw "CI requires disposable service setting: $key" }
}
& (Join-Path $PSScriptRoot 'day29-config-tests.ps1')
& (Join-Path $PSScriptRoot 'day29-secret-check.ps1')
$arguments = @('-B','-ntp')
if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$MavenRepository" }
$oldProfile = $env:SPRING_PROFILES_ACTIVE
$oldRedisPort = $env:SPRING_DATA_REDIS_PORT
$oldRedisHost = $env:SPRING_DATA_REDIS_HOST
$oldRedisPassword = $env:SPRING_DATA_REDIS_PASSWORD
try {
    $env:SPRING_PROFILES_ACTIVE='ci'
    $env:SPRING_DATA_REDIS_PORT=$env:CLOUDDRIVE_REDIS_PORT
    $env:SPRING_DATA_REDIS_HOST=$env:CLOUDDRIVE_REDIS_HOST
    $env:SPRING_DATA_REDIS_PASSWORD=$env:CLOUDDRIVE_REDIS_PASSWORD
    # The root owns V1-V10. Do not run these five projects concurrently on one schema.
    foreach ($module in @('', 'auth-service','gateway','user-service','file-service')) {
        $pom = Join-Path $repo ($module+'/pom.xml')
        & $Maven @arguments -f $pom clean verify
        if ($LASTEXITCODE -ne 0) { throw "Module verification failed: $module" }
    }
    if ($LiveSearch) {
        if (!$env:CLOUDDRIVE_SEARCH_TEST_URI) { throw 'LiveSearch requires CLOUDDRIVE_SEARCH_TEST_URI' }
        & $Maven @arguments -f (Join-Path $repo 'file-service/pom.xml') '-Dtest=ElasticSearchLiveIT' test
        if ($LASTEXITCODE -ne 0) { throw 'Live Elasticsearch tests failed' }
    }
    & (Join-Path $PSScriptRoot 'day29-verify-jars.ps1')
    Write-Output 'DAY29 CI EQUIVALENT PASS: five modules and packaged runtime artifacts'
} finally {
    [Environment]::SetEnvironmentVariable('SPRING_PROFILES_ACTIVE',$oldProfile,'Process')
    [Environment]::SetEnvironmentVariable('SPRING_DATA_REDIS_PORT',$oldRedisPort,'Process')
    [Environment]::SetEnvironmentVariable('SPRING_DATA_REDIS_HOST',$oldRedisHost,'Process')
    [Environment]::SetEnvironmentVariable('SPRING_DATA_REDIS_PASSWORD',$oldRedisPassword,'Process')
}
