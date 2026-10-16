#Requires -Version 5.1
# Synthetic files only. Does not inspect or alter any game installation.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'ClientPatch.psm1') -Force
$temp = Join-Path ([IO.Path]::GetTempPath()) ('astaps-windows-test-' + [Guid]::NewGuid().ToString('N'))
$script:checks = 0
function Check($Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:checks++
}
function Refuses([scriptblock]$Operation, [string]$Message) {
    $failed = $false
    try { & $Operation | Out-Null } catch { $failed = $true }
    Check $failed $Message
}
function Put([string]$Path, [string]$Value) {
    $null = New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force
    [IO.File]::WriteAllText($Path,$Value)
}
function Digest([string]$Path) { return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() }
function Fixture([string]$Name, [switch]$Wine) {
    $base = Join-Path $temp $Name
    $game = Join-Path $base 'Game with spaces'
    $bundle = Join-Path $base 'bundle'
    Put (Join-Path $game 'GenshinImpact.exe') 'synthetic executable'
    Put (Join-Path $game 'GenshinImpact_Data/Plugins/Astrolabe.dll') 'synthetic original plugin'
    Put (Join-Path $bundle 'payload/Astrolabe.dll') 'synthetic proxy'
    Put (Join-Path $bundle 'originals/mhypbase.dll') 'synthetic stock bootstrap'
    Put (Join-Path $base 'wine.dll') 'synthetic Wine bootstrap'
    $bootstrap = Join-Path $bundle 'originals/mhypbase.dll'
    if ($Wine) { $bootstrap = Join-Path $base 'wine.dll' }
    Copy-Item -LiteralPath $bootstrap -Destination (Join-Path $game 'mhypbase.dll')
    $m = [ordered]@{
        format=1; dispatch_url='http://127.0.0.1:8088'
        executable_sha256=(Digest (Join-Path $game 'GenshinImpact.exe'))
        plugin_sha256=(Digest (Join-Path $game 'GenshinImpact_Data/Plugins/Astrolabe.dll'))
        patch_sha256=(Digest (Join-Path $bundle 'payload/Astrolabe.dll'))
        bootstrap_sha256=(Digest (Join-Path $bundle 'originals/mhypbase.dll'))
        wine_bootstrap_sha256=(Digest (Join-Path $base 'wine.dll'))
    }
    $m | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $bundle 'client-manifest.json') -Encoding UTF8
    return @{ game=$game; bundle=$bundle; manifest=$m }
}
try {
    $f = Fixture 'stock'
    Install-AstaPatch $f.game $f.bundle
    $null = Test-AstaPatch $f.game $f.bundle
    Check ((Digest (Join-Path $f.game 'Astrolabe.dll')) -eq $f.manifest.patch_sha256) 'proxy installed'
    Check ((Digest (Join-Path $f.game 'GenshinImpact_Data/Plugins/Astrolabe.dll')) -eq $f.manifest.plugin_sha256) 'plugin untouched'
    $statePath = Join-Path $f.game '.astaps-windows/state.json'
    $state = Get-Content -LiteralPath $statePath -Raw
    Install-AstaPatch $f.game $f.bundle
    Check ((Get-Content -LiteralPath $statePath -Raw) -eq $state) 'idempotent install keeps original backup'
    Remove-AstaPatch $f.game $f.bundle
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game 'Astrolabe.dll'))) 'new proxy removed on undo'
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game 'Astrolabe_orig.dll'))) 'new original copy removed on undo'
    Check ((Digest (Join-Path $f.game 'mhypbase.dll')) -eq $f.manifest.bootstrap_sha256) 'stock bootstrap unchanged'

    $f = Fixture 'prior-files'
    Copy-Item -LiteralPath (Join-Path $f.game 'GenshinImpact_Data/Plugins/Astrolabe.dll') -Destination (Join-Path $f.game 'Astrolabe.dll')
    Install-AstaPatch $f.game $f.bundle
    Remove-AstaPatch $f.game $f.bundle
    Check ((Digest (Join-Path $f.game 'Astrolabe.dll')) -eq $f.manifest.plugin_sha256) 'existing stock root DLL restored'

    $f = Fixture 'wine' -Wine
    Refuses { Install-AstaPatch $f.game $f.bundle } 'Wine bootstrap requires explicit consent'
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game '.astaps-windows'))) 'refusal writes nothing'
    Install-AstaPatch $f.game $f.bundle -RestoreLinuxBootstrap
    Check ((Digest (Join-Path $f.game 'mhypbase.dll')) -eq $f.manifest.bootstrap_sha256) 'explicit stock bootstrap restoration'
    Remove-AstaPatch $f.game $f.bundle
    Check ((Digest (Join-Path $f.game 'mhypbase.dll')) -eq $f.manifest.wine_bootstrap_sha256) 'undo restores exact prior Wine bootstrap'

    foreach ($case in @('bad-exe','bad-plugin','bad-proxy','unknown-root','bad-bootstrap','bad-stock-backup')) {
        $f = Fixture $case -Wine:($case -eq 'bad-stock-backup')
        $path = switch ($case) {
            'bad-exe' { Join-Path $f.game 'GenshinImpact.exe' }
            'bad-plugin' { Join-Path $f.game 'GenshinImpact_Data/Plugins/Astrolabe.dll' }
            'bad-proxy' { Join-Path $f.bundle 'payload/Astrolabe.dll' }
            'unknown-root' { Join-Path $f.game 'Astrolabe.dll' }
            'bad-bootstrap' { Join-Path $f.game 'mhypbase.dll' }
            'bad-stock-backup' { Join-Path $f.bundle 'originals/mhypbase.dll' }
        }
        Put $path 'unexpected content'
        Refuses { Install-AstaPatch $f.game $f.bundle -RestoreLinuxBootstrap } "$case refused"
        Check (-not (Test-Path -LiteralPath (Join-Path $f.game '.astaps-windows'))) "$case refusal precedes all writes"
    }
    $f = Fixture 'changed-after-install'
    Install-AstaPatch $f.game $f.bundle
    Put (Join-Path $f.game 'Astrolabe.dll') 'changed after install'
    Refuses { Remove-AstaPatch $f.game $f.bundle } 'undo refuses changed installed file'
    Check (([IO.File]::ReadAllText((Join-Path $f.game 'Astrolabe.dll'))) -eq 'changed after install') 'refused undo preserves new content'

    $f = Fixture 'corrupt-backup'
    Copy-Item -LiteralPath (Join-Path $f.game 'GenshinImpact_Data/Plugins/Astrolabe.dll') -Destination (Join-Path $f.game 'Astrolabe.dll')
    Install-AstaPatch $f.game $f.bundle
    $state = Get-Content -LiteralPath (Join-Path $f.game '.astaps-windows/state.json') -Raw | ConvertFrom-Json
    Put (Join-Path (Join-Path (Join-Path $f.game '.astaps-windows') $state.backup) 'Astrolabe.dll') 'corrupt backup'
    Refuses { Remove-AstaPatch $f.game $f.bundle } 'corrupt backup rejected before undo'
    Check ((Digest (Join-Path $f.game 'Astrolabe.dll')) -eq $f.manifest.patch_sha256) 'failed undo leaves proxy installed'

    $f = Fixture 'journal-traversal'
    Install-AstaPatch $f.game $f.bundle
    $path = Join-Path $f.game '.astaps-windows/state.json'
    $state = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $state.backup = '../outside'
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $path -Encoding UTF8
    Refuses { Remove-AstaPatch $f.game $f.bundle } 'journal traversal rejected'

    $f = Fixture 'failed-copy'
    Copy-Item -LiteralPath (Join-Path $f.game 'GenshinImpact_Data/Plugins/Astrolabe.dll') -Destination (Join-Path $f.game 'Astrolabe.dll')
    $module = Get-Module ClientPatch
    & $module {
        function script:Copy-Item {
            param([string]$LiteralPath,[string]$Destination,[switch]$Force)
            if ($LiteralPath -match 'payload[/\\]Astrolabe.dll$') { throw 'Injected proxy-copy failure' }
            Microsoft.PowerShell.Management\Copy-Item @PSBoundParameters
        }
    }
    try { Refuses { Install-AstaPatch $f.game $f.bundle } 'failed copy propagates error' }
    finally { & $module { Remove-Item Function:Copy-Item } }
    Check ((Digest (Join-Path $f.game 'Astrolabe.dll')) -eq $f.manifest.plugin_sha256) 'failed install restores original root file'
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game 'Astrolabe_orig.dll'))) 'failed install removes new original copy'
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game '.astaps-windows/state.json'))) 'successful rollback clears journal'

    $f = Fixture 'running-client'
    & $module { function script:Get-Process { param($Name,$ErrorAction); return [pscustomobject]@{Id=123} } }
    try { Refuses { Install-AstaPatch $f.game $f.bundle } 'running client refused' }
    finally { & $module { Remove-Item Function:Get-Process } }
    Check (-not (Test-Path -LiteralPath (Join-Path $f.game '.astaps-windows'))) 'running-client refusal writes nothing'

    $f = Fixture 'linked-root'
    $link = Join-Path $temp 'linked-game'
    try {
        $null = New-Item -ItemType SymbolicLink -Path $link -Target $f.game
        Refuses { Install-AstaPatch $link $f.bundle } 'linked game root refused'
    } catch {
        # Windows without developer mode/elevation cannot create the test link.
        if (Test-Path -LiteralPath $link) { throw }
        Write-Host 'SKIP: creating a test symbolic link is not permitted on this host.'
    } finally { if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link -Force } }

    # Inspect every script for syntax errors without invoking the launcher or platform wrapper.
    foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object { $_.Extension -in @('.ps1','.psm1') }) {
        $tokens = $null; $errors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile($script.FullName,[ref]$tokens,[ref]$errors)
        Check ($errors.Count -eq 0) "syntax: $($script.Name)"
    }
    Write-Host "PASS: $script:checks synthetic assertions; no game installation accessed."
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
