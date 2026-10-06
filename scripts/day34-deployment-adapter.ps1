[CmdletBinding()]
param([Parameter(Mandatory=$true)][ValidateSet('Start','Stop')][string]$Mode,
    [string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot,[string]$RunDirectory)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$deploy=Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy'
if($Mode -eq 'Start'){
    & (Join-Path $deploy 'Start-CloudDrive.ps1') -JavaHome $JavaHome -OutputRoot $OutputRoot
} else {
    & (Join-Path $deploy 'Stop-CloudDrive.ps1') -RunDirectory $RunDirectory
}
