[CmdletBinding()]
param(
    [string]$Path = (Join-Path $PSScriptRoot ".env")
)

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "CloudDrive environment file was not found: $Path"
}

$lineNumber = 0
$values = @{}
Get-Content -LiteralPath $Path | ForEach-Object {
    $lineNumber++
    $line = $_.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
        return
    }

    $parts = $line -split "=", 2
    if ($parts.Count -ne 2 -or $parts[0] -notmatch "^(CLOUDDRIVE_[A-Z0-9_]+|SPRING_PROFILES_ACTIVE)$" -or $values.ContainsKey($parts[0])) {
        throw "Invalid KEY=VALUE entry in ${Path} at line $lineNumber"
    }
    $values[$parts[0]] = $parts[1]
}
foreach ($key in $values.Keys) {
    [Environment]::SetEnvironmentVariable($key, $values[$key], "Process")
}
