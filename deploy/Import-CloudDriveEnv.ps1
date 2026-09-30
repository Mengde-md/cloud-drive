[CmdletBinding()]
param(
    [string]$Path = (Join-Path $PSScriptRoot ".env")
)

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "CloudDrive environment file was not found: $Path"
}

$lineNumber = 0
Get-Content -LiteralPath $Path | ForEach-Object {
    $lineNumber++
    $line = $_.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
        return
    }

    $parts = $line -split "=", 2
    if ($parts.Count -ne 2 -or $parts[0] -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
        throw "Invalid KEY=VALUE entry in ${Path} at line $lineNumber"
    }

    [Environment]::SetEnvironmentVariable($parts[0], $parts[1], "Process")
}
