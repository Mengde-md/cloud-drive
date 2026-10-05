[CmdletBinding()]
param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$artifacts = @(
    @{Dir=''; Name='cloud-drive-learning'; Class='com.mengde.clouddrive.web.DriveEntryWebApplication'},
    @{Dir=''; Name='cloud-drive-learning'; Suffix='-migration'; Class='com.mengde.clouddrive.deployment.DatabaseMigrationApplication'},
    @{Dir='auth-service'; Name='cloud-drive-auth-service'; Class='com.mengde.clouddrive.authservice.CloudDriveAuthServiceApplication'},
    @{Dir='user-service'; Name='cloud-drive-user-service'; Class='com.mengde.clouddrive.userservice.UserServiceApplication'},
    @{Dir='file-service'; Name='cloud-drive-file-service'; Class='com.mengde.clouddrive.FileServiceApplication'},
    @{Dir='gateway'; Name='cloud-drive-gateway'; Class='com.mengde.clouddrive.gateway.CloudDriveGatewayApplication'})
$results = foreach ($artifact in $artifacts) {
    $path = Join-Path $repo ($artifact.Dir+'/build-output/'+$artifact.Name+'-0.1.0-SNAPSHOT'+$artifact.Suffix+'.jar')
    $zip = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
        try { $manifest=$reader.ReadToEnd() -replace "\r?\n ", '' } finally { $reader.Dispose() }
        if ($manifest -notmatch ('Start-Class: '+[regex]::Escape($artifact.Class))) { throw "Wrong main class: $($artifact.Name)" }
        if (!$zip.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class')) { throw 'Boot launcher missing' }
        $leaks = @($zip.Entries | Where-Object {
            $_.FullName -match '^BOOT-INF/lib/(junit|mockito|assertj|spring-test|spring-boot-test|spring-security-test)' -or
            $_.FullName -match '(^|/)test-classes/' })
        if ($leaks.Count) { throw 'Test dependencies leaked into runtime artifact' }
        [pscustomobject]@{Artifact=($artifact.Name+$artifact.Suffix); Main=$artifact.Class
            SHA256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash; TestLibraries=0}
    } finally { $zip.Dispose() }
}
if ($OutputPath) { $results | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding UTF8 }
$results | Format-Table Artifact,TestLibraries,SHA256
Write-Output 'Executable JAR contracts PASS: 6'
