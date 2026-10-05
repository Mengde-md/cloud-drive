[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$RunDirectory)
$ErrorActionPreference = 'Stop'
$path = Join-Path $RunDirectory 'processes.json'
$records = @(Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json)
$errors = [Collections.Generic.List[string]]::new()
foreach ($record in $records) {
    try {
        $process = Get-Process -Id $record.Id -ErrorAction SilentlyContinue
        if (!$process) { continue }
        if ($process.Path -ne $record.Java -or
            $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$record.StartedUtc).ToUniversalTime().Ticks) { throw 'Ownership changed; refusing to kill' }
        $process.Kill(); $process.WaitForExit()
        Write-Output "STOPPED: $($record.Name)"
    } catch { $errors.Add("PID $($record.Id): $($_.Exception.Message)") }
}
if ($errors.Count) { throw ($errors -join '; ') }
