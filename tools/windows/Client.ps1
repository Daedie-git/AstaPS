#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this menu on native Windows.' }
$pathFile = Join-Path $PSScriptRoot '.game-directory.txt'
$game = ''
if (Test-Path -LiteralPath $pathFile -PathType Leaf) { $game = (Get-Content -LiteralPath $pathFile -Raw).Trim() }
if (-not $game) { $game = (Read-Host 'Folder containing GenshinImpact.exe (no surrounding quotes)').Trim() }
Write-Host "Client folder: $game"
Write-Host '1 Install | 2 Launch | 3 Check | 4 Undo | 5 Install and restore known Linux-modified bootstrap'
$choice = Read-Host 'Choose'
switch ($choice) {
    '1' { & (Join-Path $PSScriptRoot 'Patch-Client.ps1') -GameDirectory $game }
    '2' { & (Join-Path $PSScriptRoot 'Start-Client.ps1') -GameDirectory $game }
    '3' { & (Join-Path $PSScriptRoot 'Patch-Client.ps1') -GameDirectory $game -Action Check }
    '4' { & (Join-Path $PSScriptRoot 'Patch-Client.ps1') -GameDirectory $game -Action Undo }
    '5' { & (Join-Path $PSScriptRoot 'Patch-Client.ps1') -GameDirectory $game -RestoreLinuxBootstrap }
    default { throw 'No action selected.' }
}
$game | Set-Content -LiteralPath $pathFile -Encoding UTF8
