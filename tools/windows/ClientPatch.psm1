Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-FileDigest([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-PlainPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing linked/junction path: $cursor"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Assert-GameClosed {
    $running = @(Get-Process -Name GenshinImpact,YuanShen -ErrorAction SilentlyContinue)
    if ($running.Count -gt 0) { throw 'Close every Genshin client before patching or launching.' }
}

function Read-ClientManifest([string]$Bundle) {
    $manifest = Get-Content -LiteralPath (Join-Path $Bundle 'client-manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.format -ne 1 -or $manifest.dispatch_url -ne 'http://127.0.0.1:8088') {
        throw 'Unsupported client manifest.'
    }
    foreach ($name in @('executable_sha256','plugin_sha256','patch_sha256','bootstrap_sha256','wine_bootstrap_sha256')) {
        if ($manifest.$name -notmatch '^[a-f0-9]{64}$') { throw "Invalid hash: $name" }
    }
    return $manifest
}

function Get-ClientLayout([string]$GameDirectory) {
    $root = (Resolve-Path -LiteralPath $GameDirectory).ProviderPath
    Assert-PlainPath $root
    foreach ($relative in @('GenshinImpact.exe','GenshinImpact_Data/Plugins/Astrolabe.dll',
            'Astrolabe.dll','Astrolabe_orig.dll','mhypbase.dll','.astaps-windows')) {
        Assert-PlainPath (Join-Path $root $relative)
    }
    return $root
}

function Assert-ClientBuild([string]$Root, $Manifest) {
    if ((Get-FileDigest (Join-Path $Root 'GenshinImpact.exe')) -ne $Manifest.executable_sha256) {
        throw 'Unsupported executable. This bundle only supports the pinned Global 7.1.0 build.'
    }
    if ((Get-FileDigest (Join-Path $Root 'GenshinImpact_Data/Plugins/Astrolabe.dll')) -ne $Manifest.plugin_sha256) {
        throw 'Original plugin differs from the pinned build; no files changed.'
    }
}

function Test-AstaPatch([string]$GameDirectory, [string]$Bundle) {
    $root = Get-ClientLayout $GameDirectory
    $m = Read-ClientManifest $Bundle
    Assert-ClientBuild $root $m
    foreach ($pair in @(@('Astrolabe.dll',$m.patch_sha256), @('Astrolabe_orig.dll',$m.plugin_sha256),
            @('mhypbase.dll',$m.bootstrap_sha256))) {
        if ((Get-FileDigest (Join-Path $root $pair[0])) -ne $pair[1]) { throw "Unexpected hash: $($pair[0])" }
    }
    return $root
}

function Install-AstaPatch([string]$GameDirectory, [string]$Bundle, [switch]$RestoreLinuxBootstrap) {
    Assert-GameClosed
    $root = Get-ClientLayout $GameDirectory
    $m = Read-ClientManifest $Bundle
    Assert-ClientBuild $root $m
    $proxy = Join-Path $Bundle 'payload/Astrolabe.dll'
    Assert-PlainPath $proxy
    if ((Get-FileDigest $proxy) -ne $m.patch_sha256) { throw 'Patch DLL failed its SHA-256 check.' }
    $meta = Join-Path $root '.astaps-windows'
    $statePath = Join-Path $meta 'state.json'
    if (Test-Path -LiteralPath $statePath) {
        $null = Test-AstaPatch $root $Bundle
        Write-Host 'The pinned patch is already installed; existing backups retained.'
        return
    }
    $bootstrap = Get-FileDigest (Join-Path $root 'mhypbase.dll')
    if ($bootstrap -eq $m.wine_bootstrap_sha256) {
        if (-not $RestoreLinuxBootstrap) {
            throw 'Linux driver skip detected. Re-run with -RestoreLinuxBootstrap to restore the bundled stock DLL first.'
        }
        $stock = Join-Path $Bundle 'originals/mhypbase.dll'
        Assert-PlainPath $stock
        if ((Get-FileDigest $stock) -ne $m.bootstrap_sha256) { throw 'Stock bootstrap backup failed its SHA-256 check.' }
    } elseif ($bootstrap -ne $m.bootstrap_sha256) {
        throw 'Unknown mhypbase.dll; refusing to overwrite or launch an unrecognized build.'
    }
    foreach ($name in @('Astrolabe.dll','Astrolabe_orig.dll')) {
        $path = Join-Path $root $name
        if (Test-Path -LiteralPath $path) {
            $allowed = @($m.plugin_sha256)
            if ($name -eq 'Astrolabe.dll') { $allowed += $m.patch_sha256 }
            if ((Get-FileDigest $path) -notin $allowed) { throw "Unknown existing $name; back it up and investigate manually." }
        }
    }
    $backupName = 'backup-' + [Guid]::NewGuid().ToString('N')
    $backup = Join-Path $meta $backupName
    $null = New-Item -ItemType Directory -Path $backup -Force
    $entries = @()
    $names = @('Astrolabe_orig.dll','Astrolabe.dll')
    if ($bootstrap -eq $m.wine_bootstrap_sha256) { $names += 'mhypbase.dll' }
    foreach ($name in $names) {
        $target = Join-Path $root $name
        $existed = Test-Path -LiteralPath $target -PathType Leaf
        $hash = $null
        if ($existed) {
            $hash = Get-FileDigest $target
            Copy-Item -LiteralPath $target -Destination (Join-Path $backup $name)
            if ((Get-FileDigest (Join-Path $backup $name)) -ne $hash) { throw 'Backup verification failed; no game files written.' }
        }
        $installedHash = $m.patch_sha256
        if ($name -eq 'Astrolabe_orig.dll') { $installedHash = $m.plugin_sha256 }
        if ($name -eq 'mhypbase.dll') { $installedHash = $m.bootstrap_sha256 }
        $entries += [pscustomobject]@{ name=$name; existed=$existed; before=$hash; installed=$installedHash }
    }
    $state = [ordered]@{ format=1; backup=$backupName; files=$entries }
    # Write the recovery journal BEFORE touching game files. Retain it if rollback fails.
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding UTF8
    try {
        Copy-Item -LiteralPath (Join-Path $root 'GenshinImpact_Data/Plugins/Astrolabe.dll') -Destination (Join-Path $root 'Astrolabe_orig.dll') -Force
        Copy-Item -LiteralPath $proxy -Destination (Join-Path $root 'Astrolabe.dll') -Force
        if ($names -contains 'mhypbase.dll') { Copy-Item -LiteralPath $stock -Destination (Join-Path $root 'mhypbase.dll') -Force }
        $null = Test-AstaPatch $root $Bundle
    } catch {
        $failure = $_
        foreach ($entry in $entries) {
            $target = Join-Path $root $entry.name
            if ($entry.existed) { Copy-Item -LiteralPath (Join-Path $backup $entry.name) -Destination $target -Force }
            elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        }
        foreach ($entry in $entries) {
            $target = Join-Path $root $entry.name
            if ($entry.existed -and (Get-FileDigest $target) -ne $entry.before) { throw "Rollback failed; retain recovery journal: $statePath" }
            if (-not $entry.existed -and (Test-Path -LiteralPath $target)) { throw 'Rollback failed to remove a new proxy.' }
        }
        Remove-Item -LiteralPath $statePath
        throw $failure
    }
    Write-Host "Installed. Recovery journal: $statePath"
}

function Remove-AstaPatch([string]$GameDirectory, [string]$Bundle) {
    Assert-GameClosed
    $root = Get-ClientLayout $GameDirectory
    $m = Read-ClientManifest $Bundle
    Assert-ClientBuild $root $m
    $meta = Join-Path $root '.astaps-windows'
    $statePath = Join-Path $meta 'state.json'
    Assert-PlainPath $statePath
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.format -ne 1 -or $state.backup -notmatch '^backup-[a-f0-9]{32}$') { throw 'Invalid recovery journal.' }
    $names = @($state.files | ForEach-Object { $_.name })
    if ($names.Count -lt 2 -or $names.Count -gt 3 -or
            (@($names | Select-Object -Unique).Count -ne $names.Count) -or
            'Astrolabe.dll' -notin $names -or 'Astrolabe_orig.dll' -notin $names) { throw 'Invalid recovery file list.' }
    $backup = Join-Path $meta $state.backup
    foreach ($entry in $state.files) {
        if ($entry.name -notin @('Astrolabe.dll','Astrolabe_orig.dll','mhypbase.dll')) { throw 'Invalid recovery filename.' }
        $target = Join-Path $root $entry.name
        $saved = Join-Path $backup $entry.name
        Assert-PlainPath $saved
        $expected = $m.patch_sha256
        if ($entry.name -eq 'Astrolabe_orig.dll') { $expected = $m.plugin_sha256 }
        if ($entry.name -eq 'mhypbase.dll') { $expected = $m.bootstrap_sha256 }
        if ($entry.installed -ne $expected -or (Get-FileDigest $target) -ne $expected) {
            throw "Refusing undo: $($entry.name) changed since installation. No files restored."
        }
        if ($entry.existed) {
            if ($entry.before -notmatch '^[a-f0-9]{64}$' -or (Get-FileDigest $saved) -ne $entry.before) { throw 'Backup hash mismatch.' }
        }
    }
    foreach ($entry in $state.files) {
        $target = Join-Path $root $entry.name
        if ($entry.existed) { Copy-Item -LiteralPath (Join-Path $backup $entry.name) -Destination $target -Force }
        else { Remove-Item -LiteralPath $target }
    }
    foreach ($entry in $state.files) {
        $target = Join-Path $root $entry.name
        if ($entry.existed -and (Get-FileDigest $target) -ne $entry.before) { throw 'Undo verification failed; journal retained.' }
        if (-not $entry.existed -and (Test-Path -LiteralPath $target)) { throw 'Undo verification failed; journal retained.' }
    }
    Remove-Item -LiteralPath $statePath
    Write-Host 'Restored the pre-install files. Backups remain available.'
}

Export-ModuleMember -Function Install-AstaPatch,Remove-AstaPatch,Test-AstaPatch
