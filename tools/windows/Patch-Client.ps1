#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$GameDirectory,
    [ValidateSet('Install','Check','Undo')][string]$Action = 'Install',
    [switch]$RestoreLinuxBootstrap
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this script on native Windows, not under Wine.' }
Import-Module (Join-Path $PSScriptRoot 'ClientPatch.psm1') -Force
switch ($Action) {
    'Install' { Install-AstaPatch -GameDirectory $GameDirectory -Bundle $PSScriptRoot -RestoreLinuxBootstrap:$RestoreLinuxBootstrap }
    'Check' { $null = Test-AstaPatch -GameDirectory $GameDirectory -Bundle $PSScriptRoot; Write-Host 'Pinned client and patch hashes verified.' }
    'Undo' { Remove-AstaPatch -GameDirectory $GameDirectory -Bundle $PSScriptRoot }
}
