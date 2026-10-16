#Requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$GameDirectory)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this script on native Windows.' }
Import-Module (Join-Path $PSScriptRoot 'ClientPatch.psm1') -Force
$root = Test-AstaPatch -GameDirectory $GameDirectory -Bundle $PSScriptRoot
if (@(Get-Process -Name GenshinImpact,YuanShen -ErrorAction SilentlyContinue).Count -gt 0) {
    throw 'A Genshin client is already running.'
}
# Bypass inherited web proxies for this loopback-only health check.
$request = [Net.HttpWebRequest]::Create('http://127.0.0.1:8088/api/status')
$request.Proxy = $null
$request.Timeout = 5000
$response = $null
try {
    $response = $request.GetResponse()
    if ([int]$response.StatusCode -ne 200) { throw 'Dispatch is not healthy.' }
} catch { throw 'AstaPS is not reachable at 127.0.0.1:8088. Start the Windows server or the agreed connection first.' }
finally { if ($null -ne $response) { $response.Close() } }
$logDir = Join-Path $root '.astaps-windows/logs'
$null = New-Item -ItemType Directory -Path $logDir -Force
$names = @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy',
    'LUNAGC_PATCH_LOG','WINEDLLOVERRIDES')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$null,'Process') }
    $env:LUNAGC_PATCH_LOG = Join-Path $logDir ('patch-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    $client = Start-Process -FilePath (Join-Path $root 'GenshinImpact.exe') -WorkingDirectory $root -PassThru
    Write-Host "Started PID $($client.Id). Patch log: $env:LUNAGC_PATCH_LOG"
    Write-Host 'Use only your private-server account. Never enter HoYoverse credentials.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
