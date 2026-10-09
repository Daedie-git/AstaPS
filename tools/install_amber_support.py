#!/usr/bin/env python3
"""Stage or install Amber support without rebuilding the customized server archive."""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import warnings
import zipfile

ROOT = Path('/home/aim/.local/share/astaps')
REPO = Path(__file__).resolve().parent.parent
CLASSES = REPO / 'build/classes/java/main'
ENTRIES = (
    'emu/grasscutter/config/ConfigContainer$GameOptions.class',
    'emu/grasscutter/game/ability/Ability.class',
    'emu/grasscutter/game/ability/AbilityManager.class',
    'emu/grasscutter/game/ability/actions/ActionAvatarSkillStart.class',
    'emu/grasscutter/game/entity/EntityRuntimeStateCleanup.class',
    'emu/grasscutter/game/player/PlayerRuntimeStateCleanup.class',
    'emu/grasscutter/server/packet/send/PacketPlayerEnterSceneInfoNotify.class',
    'emu/grasscutter/server/packet/recv/HandlerAbilityInvocationsNotify.class',
    'emu/grasscutter/server/packet/recv/HandlerClientAbilityInitFinishNotify.class',
    'emu/grasscutter/server/packet/recv/HandlerClientAbilityChangeNotify.class',
    'emu/grasscutter/server/packet/recv/HandlerClientAbilitiesInitFinishCombineNotify.class',
)
SETTINGS = {'amberBurstHealHpRatio': 0.03, 'amberC6AttackMultiplier': 2.0}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sync(path):
    with path.open('rb') as stream:
        os.fsync(stream.fileno())


def prepare(stage, expected):
    jar, config = ROOT / 'grasscutter.jar', ROOT / 'config.json'
    assert digest(jar) == expected, 'Live archive changed'
    before = config.read_bytes()
    parsed = json.loads(before)
    options = parsed['server']['game']['gameOptions']
    assert not any(key in options for key in SETTINGS), 'Amber settings already exist'
    marker = b'"gameOptions": {\n'
    assert before.count(marker) == 1, 'Unexpected config formatting'
    insertion = ''.join(f'        "{key}": {value},\n' for key, value in SETTINGS.items()).encode()
    after = before.replace(marker, marker + insertion)
    check = json.loads(after)
    for key, value in SETTINGS.items():
        assert check['server']['game']['gameOptions'].pop(key) == value
    assert check == parsed, 'Unrelated config changed'
    stage.mkdir(parents=True, exist_ok=False)
    replacements = {entry: (CLASSES / entry).read_bytes() for entry in ENTRIES}
    helpers = list((CLASSES / 'emu/grasscutter/game/ability').glob('AmberSupport*.class'))
    assert helpers, 'Amber classes have not been compiled'
    replacements.update({str(path.relative_to(CLASSES)): path.read_bytes() for path in helpers})
    seen = set()
    with zipfile.ZipFile(jar) as source, warnings.catch_warnings():
        warnings.filterwarnings('ignore', message='Duplicate name:')
        with zipfile.ZipFile(stage / 'grasscutter.jar', 'w') as dest:
            dest.comment = source.comment
            for entry in source.infolist():
                data = replacements.get(entry.filename, source.read(entry))
                if entry.filename in replacements:
                    assert entry.filename not in seen, 'Duplicate target'
                    seen.add(entry.filename)
                dest.writestr(copy.copy(entry), data)
            assert set(ENTRIES) <= seen, 'Missing baseline class'
            for entry, data in replacements.items():
                if entry not in seen:
                    dest.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(stage / 'grasscutter.jar') as dest:
            old, new = source.infolist(), dest.infolist()
            assert len(new) == len(old) + len(set(replacements) - seen)
            for previous, replacement in zip(old, new):
                assert previous.filename == replacement.filename
                assert dest.read(replacement) == replacements.get(previous.filename, source.read(previous))
            for entry, data in replacements.items():
                assert dest.read(entry) == data
            assert dest.testzip() is None
    cfg = stage / 'config.json'
    cfg.touch(mode=0o600)
    cfg.write_bytes(after)
    cfg.chmod(0o600)
    manifest = {
        'before_sha256': expected, 'after_sha256': digest(stage / 'grasscutter.jar'),
        'before_config_sha256': hashlib.sha256(before).hexdigest(), 'after_config_sha256': digest(cfg),
        'entries': sorted(replacements), 'settings': SETTINGS,
        'unrelated_archive_entries_preserved': True, 'unrelated_configuration_preserved': True,
    }
    (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    assert digest(jar) == expected and config.read_bytes() == before, 'Concurrent live change'
    print(json.dumps(manifest, indent=2))


def install(stage):
    manifest = json.loads((stage / 'manifest.json').read_text())
    jar, config = ROOT / 'grasscutter.jar', ROOT / 'config.json'
    with (ROOT / 'customization/amber-support.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = subprocess.run(['systemctl', '--user', 'is-active', 'astaps.service'],
                               capture_output=True, text=True).stdout.strip()
        assert state == 'inactive', f'Stop server first (state: {state})'
        assert digest(jar) == manifest['before_sha256'], 'Live archive changed'
        assert digest(config) == manifest['before_config_sha256'], 'Live config changed'
        assert digest(stage / 'grasscutter.jar') == manifest['after_sha256'], 'Staged archive changed'
        assert digest(stage / 'config.json') == manifest['after_config_sha256'], 'Staged config changed'
        backups = ROOT / 'customization/backups'
        backups.mkdir(exist_ok=True)
        jar_backup = backups / f"grasscutter-{manifest['before_sha256']}.jar"
        cfg_backup = backups / f"config-before-amber-{manifest['before_config_sha256']}.json"
        if not jar_backup.exists():
            shutil.copy2(jar, jar_backup)
        if not cfg_backup.exists():
            cfg_backup.touch(mode=0o600)
            shutil.copyfile(config, cfg_backup)
        cfg_backup.chmod(0o600)
        assert digest(jar_backup) == manifest['before_sha256']
        assert digest(cfg_backup) == manifest['before_config_sha256']
        sync(jar_backup)
        sync(cfg_backup)
        temporary = []
        try:
            for live in (jar, config):
                fd, name = tempfile.mkstemp(prefix='amber-', dir=ROOT / 'tmp')
                os.close(fd)
                path = Path(name)
                temporary.append(path)
                shutil.copyfile(stage / live.name, path)
                path.chmod(live.stat().st_mode & 0o777)
                sync(path)
            assert digest(jar) == manifest['before_sha256'] and digest(config) == manifest['before_config_sha256']
            os.replace(temporary[1], config)
            try:
                os.replace(temporary[0], jar)
            except BaseException:
                shutil.copyfile(cfg_backup, temporary[1])
                temporary[1].chmod(config.stat().st_mode & 0o777)
                sync(temporary[1])
                os.replace(temporary[1], config)
                raise
            fd = os.open(ROOT, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
            manifest.update(jar_backup=str(jar_backup), config_backup=str(cfg_backup))
            (ROOT / 'customization/amber-support-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
            print(json.dumps(manifest, indent=2))
        finally:
            for path in temporary:
                path.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'install'))
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--expected-sha256')
    args = parser.parse_args()
    if args.mode == 'prepare':
        if not args.expected_sha256:
            parser.error('prepare requires --expected-sha256')
        prepare(args.stage.resolve(), args.expected_sha256)
    else:
        install(args.stage.resolve())
