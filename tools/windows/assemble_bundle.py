#!/usr/bin/env python3
"""Assemble a private Windows client patch kit without distributing game data in Git."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(source: Path, *args: str) -> str:
    return subprocess.check_output(['git', '-C', str(source), *args], text=True).strip()


def assemble(setup: Path, output: Path) -> dict:
    scripts = Path(__file__).resolve().parent
    manifest = json.loads((scripts / 'client-manifest.json').read_text())
    source = setup / 'patch-source'
    if git(source, 'rev-parse', 'HEAD') != manifest['patch_commit']:
        raise ValueError('Patch checkout is not the pinned commit')
    if git(source, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Patch checkout is dirty; do not mislabel its provenance')
    proxy = setup / 'Astrolabe.dll'
    stock = setup / 'rea' / 'mhypbase-original.dll'
    for path, expected in [(proxy, manifest['patch_sha256']), (stock, manifest['bootstrap_sha256']),
                           (source / 'Cargo.lock', manifest['patch_lock_sha256'])]:
        if path.is_symlink() or digest(path) != expected:
            raise ValueError(f'Unexpected source binary: {path}')
    # Never overwrite an existing handoff, game installation, runtime, or repository.
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError(f'Output already exists: {output}')
    for protected in [setup.resolve(), setup.parent.resolve(), scripts.parents[1]]:
        if output.resolve() == protected or protected in output.resolve().parents:
            raise ValueError('Output must be separate from source/runtime directories')
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.astaps-windows-', dir=output.parent))
    try:
        for name in ['Client.cmd', 'Client.ps1', 'Patch-Client.ps1', 'Start-Client.ps1', 'ClientPatch.psm1',
                     'Test-ClientPatch.ps1', 'client-manifest.json', 'README.md']:
            shutil.copyfile(scripts / name, stage / name)
        (stage / 'payload').mkdir()
        (stage / 'originals').mkdir()
        shutil.copyfile(proxy, stage / 'payload' / 'Astrolabe.dll')
        # Private recovery copy ONLY. This proprietary DLL is never added to Git.
        shutil.copyfile(stock, stage / 'originals' / 'mhypbase.dll')
        # The patch repository ignores Cargo.lock; retain the exact locally built
        # dependency lock explicitly so the supplied --locked rebuild works.
        for relative in sorted(set(git(source, 'ls-files').splitlines()) | {'Cargo.lock'}):
            path = source / relative
            if path.is_symlink() or not path.is_file():
                raise ValueError(f'Unsupported patch-source entry: {relative}')
            target = stage / 'patch-source' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        provenance = {
            'format': 1,
            'client': manifest,
            'scope': 'Private client patch kit; no executable, game assets, accounts, credentials or server runtime.',
            'server_repository': 'https://github.com/Daedie-git/AstaPS.git',
            'handoff_commit': git(scripts, 'rev-parse', 'HEAD'),
            'files': {p.relative_to(stage).as_posix(): {'sha256': digest(p), 'bytes': p.stat().st_size}
                      for p in sorted(stage.rglob('*')) if p.is_file()},
        }
        (stage / 'package-manifest.json').write_text(json.dumps(provenance, indent=2) + '\n')
        checksums = ''.join(f'{digest(p)}  {p.relative_to(stage).as_posix()}\n'
                            for p in sorted(stage.rglob('*')) if p.is_file())
        (stage / 'SHA256SUMS.txt').write_text(checksums)
        os.rename(stage, output)
        return provenance
    except BaseException:
        shutil.rmtree(stage)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--setup', type=Path, required=True, help='Existing local client-setup directory')
    parser.add_argument('--output', type=Path, required=True, help='New, separate output directory')
    args = parser.parse_args()
    result = assemble(args.setup, args.output)
    print(f"Created {args.output}: {len(result['files'])} payload/source/script files")


if __name__ == '__main__':
    main()
