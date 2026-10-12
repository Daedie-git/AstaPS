#!/usr/bin/env python3
"""Run the pinned deployment in a fresh sandbox; never connect to the live database."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
INSTALL = Path('/home/aim/.local/share/astaps')
EXPECTED = 'df5c4db3aacd642d7d2732e995d1b713ed6b52652eec6a953147d0aba2cafe07'
PORTS = ((socket.SOCK_STREAM, 28088), (socket.SOCK_DGRAM, 32101), (socket.SOCK_STREAM, 37018))


STARTUP = 'exact archive starts HTTP, game ticks and real Mongo'
RESOURCE_GAPS = 'installed resource gaps are explicitly bounded'
EXPECTED_RESULTS = {
    'fresh': [STARTUP, 'unseeded fresh account completes world login'],
    'activity': [STARTUP, 'new activity document obtains an ID and persists through normal writer'],
    'write': [STARTUP,
        'parallel UID allocation is unique in real MongoDB',
        'credential rotation survives actual BSON round-trip',
        'invalid token cannot admit or create a player',
        'existing-save fixture token and world login load real components',
        'loaded Nahida resource retains its defense modifier formula',
        'earned rank reward pays once through registered router',
        'future and malformed rank requests preserve inventory and markers',
        'full bag rejects claim without consuming retry eligibility',
        'duplicate payment costs are aggregated before debit',
        'output stack overflow rejects exchange without payment',
        'fully consumed material is deleted and does not resurrect',
        'TPS accessories unlock in both acquisition orders and survive material removal',
        'duplicate active login joins saves before replacing ownership',
        'closed generation cannot overwrite acknowledged player state',
        'logout acknowledges final player and item state'],
    'restart': [STARTUP, 'server and Mongo restart preserve player, items and quest variables',
        'TPS reload preserves unlocks and final removal persists clean equipment',
        'persisted claim markers block repayment after restart'],
}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def phase_results(run, phase, resource_gap_profile):
    results = json.loads((run / f'{phase}-results.json').read_text())
    expected = EXPECTED_RESULTS[phase].copy()
    if resource_gap_profile:
        expected.insert(1, RESOURCE_GAPS)
    if (not isinstance(results, list) or any(not isinstance(t, dict) for t in results)
            or [t.get('name') for t in results] != expected):
        raise RuntimeError(f'{phase}: expected exact ordered assertion contract, including completion of every case')
    if any(t.get('status') != 'passed' or type(t.get('durationMillis')) is not int
           or t['durationMillis'] < 0 for t in results):
        raise RuntimeError(f'{phase}: failed or malformed assertion')
    marker = json.loads((run / f'{phase}-complete.json').read_text())
    if marker != {'schemaVersion': 1, 'phase': phase, 'status': 'completed',
                  'assertionCount': len(expected), 'resultsSha256': sha(run / f'{phase}-results.json')}:
        raise RuntimeError(f'{phase}: missing or invalid phase completion')
    return results


def require_tree_root(path):
    if path.is_symlink():
        raise RuntimeError(f'Input tree root symlink refused: {path}')
    if not path.is_dir():
        raise FileNotFoundError(f'Input tree root missing or not a directory: {path}')


def validate_tree(path, boundary):
    require_tree_root(path)
    boundary = boundary.resolve(strict=True)
    for descendant in path.rglob('*'):
        if descendant.is_symlink() and not descendant.resolve(strict=True).is_relative_to(boundary):
            raise RuntimeError(f'Input symlink escapes sandbox/source tree: {descendant}')


def remove_bulk(path):
    # is_symlink also detects dangling links; unlink rather than traversing the target.
    if path.is_symlink() or not path.is_dir():
        path.unlink(missing_ok=True)
    else:
        shutil.rmtree(path)


def record_inputs(run):
    """Stable, complete identity of copied inputs, before JVM mutations or cleanup."""
    entries = {}
    for directory in ['resources', 'data', 'customization']:
        validate_tree(run / directory, run)
    for directory in ['resources', 'data', 'customization']:
        entries[directory] = {'directory': True}
        for path in sorted((run / directory).rglob('*')):
            relative = path.relative_to(run).as_posix()
            if path.is_symlink():
                if not path.resolve().is_relative_to(run):
                    raise RuntimeError(f'Copied resource symlink escapes sandbox: {relative}')
                entries[relative] = {'symlink': os.readlink(path)}
                if path.is_file():
                    entries[relative]['sha256'] = sha(path)
            elif path.is_file():
                entries[relative] = {'sha256': sha(path), 'size': path.stat().st_size}
            elif path.is_dir():
                entries[relative] = {'directory': True}
    target = run / 'input-manifest.json'
    target.write_text(json.dumps({'schemaVersion': 1, 'files': entries}, sort_keys=True, indent=2) + '\n')
    return sha(target)


RESOURCE_MESSAGES = {
    'statue': ('TsvUtils', "Error loading file './resources/ExcelBinOutput/StatuePromoteExcelConfigData.tsj' - "
               'File does not exist. You are missing resources. Note that this file may exist in JSON, TSV, or TSJ format, any of which are suitable.'),
    'blossom': ('BlossomManager', 'Cannot find blossom type BLUE (chest 2) in BlossomRefreshExcelConfigData; its reward preview is skipped'),
    'thaiLoad': ('Language', 'Error loading textmap: TH'),
    'thaiMissing': ('Language', 'java.nio.file.NoSuchFileException: ./resources/TextMap/TextMapTH.json'),
}
RESOURCE_REASONS = {
    'statue': 'Installed pack omits StatuePromote; offering/statue behavior is not covered.',
    'blossom': 'Loaded blossom table omits BLUE chest 2; blossom reward previews are not covered.',
    'thaiLoad': 'Installed pack omits Thai textmap; Thai localization is not covered.',
    'thaiMissing': 'Installed pack omits Thai textmap; Thai localization is not covered.',
}


def resource_evidence(run, phase):
    evidence = json.loads((run / f'{phase}-resource-gaps.json').read_text())
    expected = {'profile': 'installed-20261011', 'statuePromoteMissing': True,
                'blueBlossomChestMissing': True,
                'thaiTextMissing': not (run / 'resources/TextMap/TextMapTH.json').exists()}
    if evidence != expected or any((run / f'resources/ExcelBinOutput/StatuePromoteExcelConfigData.{ext}').exists()
                                   for ext in ['json', 'tsv', 'tsj']):
        raise RuntimeError(f'{phase}: resource gap assertions do not match copied inputs')
    return evidence


def inspect_log(text, phase, evidence=None):
    """Only exact, bounded resource records with a passed loaded-resource contract are dispositioned."""
    text = re.sub(r'\x1b\[[0-9;]*m', '', text)
    report = {'acceptedResourceErrors': [], 'unexpectedErrors': []}
    counts = {kind: 0 for kind in RESOURCE_MESSAGES}
    fatal_signals = ['Shutdown failed', 'Database shutdown incomplete', 'A world threw', 'threw while ticking',
                     'Login for account', 'Final script save did not complete', 'Refused uid', 'tick failed']
    for number, line in enumerate(text.splitlines(), 1):
        record = re.fullmatch(r'\d{2}:\d{2}:\d{2} <ERROR:([^>]+)> (.*)', line)
        kind = next((k for k, value in RESOURCE_MESSAGES.items() if record and record.groups() == value), None)
        if kind and evidence and (not kind.startswith('thai') or evidence['thaiTextMissing']):
            counts[kind] += 1
            report['acceptedResourceErrors'].append({'kind': kind, 'line': number,
                                                     'message': line, 'reason': RESOURCE_REASONS[kind]})
        elif (any(s in line for s in fatal_signals) or re.search(r'\b(?:ERROR|FATAL)\b', line)
              or re.search(r'\b[\w.$]+(?:Exception|Error)\b', line)
              or re.match(r'\s*(?:at [\w.$]+\(|Caused by:|Suppressed:)', line)):
            report['unexpectedErrors'].append({'line': number, 'message': line})
    if evidence and (counts['statue'] != 1 or counts['blossom'] != (0 if phase == 'activity' else 1)
                     or counts['thaiLoad'] != counts['thaiMissing'] or counts['thaiLoad'] > 1):
        report['unexpectedErrors'].append({'message': 'Resource error counts/pairs do not match bounded profile', 'counts': counts})
    return report


def wait_port(port, child):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if child.poll() is not None:
            raise RuntimeError(f'Process exited before port {port}; inspect sandbox logs')
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', port)) == 0:
                return
        time.sleep(.1)
    raise TimeoutError(f'Port {port} did not start')


def stop(child):
    if child is None or child.poll() is not None:
        return
    child.send_signal(signal.SIGTERM)
    try:
        child.wait(timeout=60)
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGKILL)
        child.wait()
        raise RuntimeError('Owned sandbox process needed SIGKILL; shutdown is unverified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=INSTALL / 'grasscutter.jar')
    parser.add_argument('--sha256', default=EXPECTED, help='Explicit reviewed archive checksum; mismatch refuses execution')
    parser.add_argument('--phases', nargs='+', choices=['fresh', 'activity', 'write', 'restart'],
                        default=['fresh', 'activity', 'write', 'restart'],
                        help='Focused diagnosis; restart requires write in the same run')
    parser.add_argument('--resource-gap-profile', choices=['installed-20261011'],
                        help='Explicitly disposition only asserted, bounded StatuePromote/BLUE blossom/Thai textmap gaps')
    args = parser.parse_args()
    if len(args.phases) != len(set(args.phases)) or ('restart' in args.phases
            and ('write' not in args.phases or args.phases.index('write') > args.phases.index('restart'))):
        parser.error('Phases must be unique; write must precede restart')
    os.umask(0o077)
    parent = ROOT / 'captures/runtime-integration'
    parent.mkdir(parents=True, exist_ok=True)
    with (parent / 'runner.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        execute(parent, args.archive.absolute(), args.sha256, args.phases, args.resource_gap_profile)


def execute(parent, archive, expected, phases, resource_gap_profile=None):
    if (not phases or any(p not in EXPECTED_RESULTS for p in phases) or len(set(phases)) != len(phases)
            or ('restart' in phases and ('write' not in phases or phases.index('write') > phases.index('restart')))):
        raise ValueError('Phases must be known and unique; write must precede restart')
    if resource_gap_profile not in (None, 'installed-20261011'):
        raise ValueError('Unknown resource gap profile')
    if len(expected) != 64 or any(c not in '0123456789abcdef' for c in expected):
        raise ValueError('Expected SHA256 must be 64 lowercase hexadecimal characters')
    run = parent / datetime.datetime.now().astimezone().strftime('%Y%m%dT%H%M%S%z')
    run.mkdir(mode=0o700)
    manifest = {'archiveSha256': expected, 'archiveSource': str(archive),
                'implementation': 'cf4f5bcd' if expected == EXPECTED else 'see archive build metadata', 'sandbox': str(run),
                'database': 'astaps_runtime_test', 'mongoPort': 37018, 'httpPort': 28088, 'gamePort': 32101,
                'status': 'preparing', 'nativeTransportTested': False, 'requestedPhases': phases}
    def save():
        (run / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    mongo = None
    game = None
    handles = []
    results = []
    preserved = {}
    primary_error = None
    watched = ['grasscutter.jar', 'config.json', 'customization/rotation-catalog.json', 'data/DungeonDrop.json']
    try:
        save()
        print(f'Test sandbox: {run}', flush=True)
        if INSTALL.is_symlink() or archive.is_symlink():
            raise RuntimeError('Installation/archive root symlink refused')
        for name in ['resources', 'data', 'customization']:
            validate_tree(INSTALL / name, INSTALL)
        if sha(archive) != expected:
            raise RuntimeError('Archive changed; review and supply its checksum before testing')
        for kind, port in PORTS:
            with socket.socket(socket.AF_INET, kind) as sock:
                if kind == socket.SOCK_STREAM:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(('127.0.0.1', port))
        for name in ['tmp', 'db', 'logs', 'classes']:
            (run / name).mkdir()
        env = dict(os.environ, TMPDIR=str(run / 'tmp'))
        env.pop('JAVA_TOOL_OPTIONS', None)
        preserved = {n: sha(INSTALL / n) for n in watched}
        # Copies, not symlinks/hardlinks: server-generated files cannot reach installation data.
        for name in ['resources', 'data']:
            require_tree_root(INSTALL / name)
            subprocess.run(['cp', '-a', '--reflink=auto', str(INSTALL / name), str(run / name)], check=True)
            require_tree_root(run / name)
        (run / 'customization').mkdir()
        for source in (INSTALL / 'customization').iterdir():
            if source.is_file() and source.suffix == '.json':
                shutil.copy2(source, run / 'customization' / source.name)
        manifest['inputManifestSha256'] = record_inputs(run)
        manifest['inputManifest'] = 'input-manifest.json'
        shutil.copy2(archive, run / 'server.jar')
        if sha(run / 'server.jar') != expected:
            raise RuntimeError('Archive changed while copying; sandbox refused')
        if (INSTALL / 'keystore.p12').exists():
            shutil.copy2(INSTALL / 'keystore.p12', run / 'keystore.p12')
        config = json.loads((INSTALL / 'config.json').read_text())
        config['folderStructure'] = dict(resources='./resources/', data='./data/', packets='./packets/',
                                        scripts='resources:Scripts/', plugins='./plugins/', cache='./cache/')
        for target in ['game', 'server']:
            config['databaseInfo'][target] = dict(connectionUri='mongodb://127.0.0.1:37018', collection='astaps_runtime_test')
        config['account'] = dict(autoCreate=False, EXPERIMENTAL_RealPassword=False, useIntegrationPassword=False,
                                 defaultPermissions=[], maxPlayer=8)
        server = config['server']
        server['runMode'] = 'HYBRID'
        for name, port in [('http', 28088), ('game', 32101)]:
            server[name].update(bindAddress='127.0.0.1', accessAddress='127.0.0.1', bindPort=port, accessPort=port)
        server['http']['webConsole'] = {'enabled': False, 'password': ''}
        server['http']['encryption']['useEncryption'] = False
        server['http']['startImmediately'] = False
        server['dispatch']['regions'] = []
        server['dispatch']['dispatchUrl'] = 'http://127.0.0.1:28088'
        server['game']['enableConsole'] = False
        server['game']['asyncLogin'] = True
        # Handler/Mongo checks do not exercise scene scripts; avoid background world generation.
        server['game']['enableScriptInBigWorld'] = False
        server['game']['gameOptions']['newAccountIntro']['enabled'] = False
        # Keep game mechanics/tuning, but exclude scheduled restarts and raw credential logging.
        server['game']['logPackets'] = 'NONE'
        server['game']['isShowPacketPayload'] = False
        server['dispatch']['logRequests'] = 'NONE'
        server['game']['watchdog']['enableAutoRestart'] = False
        (run / 'config.json').write_text(json.dumps(config, indent=2) + '\n')
        for source in [Path(__file__), Path(__file__).with_name('RuntimeIntegration.java')]:
            shutil.copy2(source, run / source.name)
        manifest['harnessSha256'] = {source.name: sha(source) for source in [Path(__file__), Path(__file__).with_name('RuntimeIntegration.java')]}
        manifest['resourceSha256'] = {name: sha(run / 'resources/ExcelBinOutput' / name) for name in
                                    ['PlayerLevelExcelConfigData.json', 'RewardExcelConfigData.json']}
        manifest['resourceGapProfile'] = resource_gap_profile
        save()
        java = INSTALL / 'runtime/jdk/bin/java'
        javac = INSTALL / 'runtime/jdk/bin/javac'
        subprocess.run([str(javac), '-J-Xmx256m', '-cp', str(run / 'server.jar'), '-d', str(run / 'classes'),
                        str(Path(__file__).with_name('RuntimeIntegration.java'))], env=env, check=True)
        for phase in phases:
            mongo_log = (run / f'{phase}-mongo-process.log').open('w'); handles.append(mongo_log)
            mongo = subprocess.Popen([str(INSTALL / 'runtime/mongo/bin/mongod'), '--dbpath', str(run / 'db'),
                                      '--bind_ip', '127.0.0.1', '--port', '37018', '--nounixsocket',
                                      '--wiredTigerCacheSizeGB', '0.25', '--logpath', str(run / f'{phase}-mongo.log')],
                                     cwd=run, env=env, stdin=subprocess.DEVNULL, stdout=mongo_log,
                                     stderr=subprocess.STDOUT, start_new_session=True)
            wait_port(37018, mongo)
            output = (run / f'{phase}-server.log').open('w'); handles.append(output)
            game = subprocess.Popen([str(java), '-Xms256m', '-Xmx3g', '-XX:ActiveProcessorCount=2',
                                     f'-Djava.io.tmpdir={run / "tmp"}',
                                     f'-Druntime.resourceGapProfile={resource_gap_profile or ""}',
                                     '-cp', f'{run / "classes"}:{run / "server.jar"}',
                                     'RuntimeIntegration', phase], cwd=run, env=env, stdin=subprocess.DEVNULL,
                                    stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            manifest.update(status='running', phase=phase, mongoPid=mongo.pid, serverPid=game.pid); save()
            deadline = time.monotonic() + 180
            health = None
            while game.poll() is None and time.monotonic() < deadline:
                try:
                    with urllib.request.urlopen('http://127.0.0.1:28088/api/status', timeout=1) as response:
                        data = json.load(response)
                    health = {'ticks': data['game']['tick']['completedCount'], 'deadlock': data['jvm']['deadlock']['status']}
                except (OSError, ValueError):
                    pass
                time.sleep(.2)
            if game.poll() is None:
                raise TimeoutError(f'{phase} exceeded 180 seconds')
            manifest[phase + 'ExitCode'] = game.returncode
            if game.returncode != 0:
                raise RuntimeError(f'{phase} exited {game.returncode}; inspect {phase}-server.log')
            if health is None or health['deadlock'] != 'CLEAR':
                raise RuntimeError(f'{phase}: HTTP health unavailable or deadlocked')
            manifest[phase + 'Health'] = health
            output.flush()
            phase_cases = phase_results(run, phase, resource_gap_profile)
            evidence = resource_evidence(run, phase) if resource_gap_profile else None
            disposition = inspect_log((run / f'{phase}-server.log').read_text(), phase, evidence)
            (run / f'{phase}-log-disposition.json').write_text(json.dumps(disposition, indent=2) + '\n')
            manifest[phase + 'LogDisposition'] = f'{phase}-log-disposition.json'
            if disposition['unexpectedErrors']:
                raise RuntimeError(f'{phase}: unexpected server errors/exceptions; inspect log disposition')
            results.extend(phase_cases)
            manifest[phase + 'Completed'] = True
            stop(mongo)
            if mongo.returncode != 0 or 'mongod shutdown complete' not in (run / f'{phase}-mongo.log').read_text():
                raise RuntimeError(f'{phase}: Mongo shutdown not clean')
            mongo = None; game = None
        failures = sum(t['status'] != 'passed' for t in results)
        suite = ET.Element('testsuite', name='AstaPSRealRuntimeScenarios', tests=str(len(results)), failures=str(failures), errors='0',
                           time=str(sum(t['durationMillis'] for t in results) / 1000))
        for result in results:
            case = ET.SubElement(suite, 'testcase', name=result['name'], classname='RuntimeIntegration', time=str(result['durationMillis'] / 1000))
            if result['status'] != 'passed':
                ET.SubElement(case, 'failure', message=result.get('error', 'failed'))
        ET.ElementTree(suite).write(run / 'results.xml', encoding='utf-8', xml_declaration=True)
        manifest.update(status='failed' if failures else 'passed', tests=len(results), failures=failures,
                        mongoRestartVerified='write' in phases and 'restart' in phases)
    except BaseException as error:
        primary_error = error
        manifest.update(status='failed', error=str(error))
        raise
    finally:
        final_errors = []
        def attempt(action):
            try:
                action()
                return True
            except BaseException as error:
                final_errors.append(error)
                return False
        attempt(lambda: stop(game))
        attempt(lambda: stop(mongo))
        for handle in handles:
            attempt(handle.close)
        changed = []
        fingerprints_ok = len(preserved) == len(watched)
        for name, checksum in preserved.items():
            def check(name=name, checksum=checksum):
                # Revalidate roots before reading: a concurrently replaced root must not be followed.
                for root in ['resources', 'data', 'customization']:
                    require_tree_root(INSTALL / root)
                if not (INSTALL / name).resolve(strict=True).is_relative_to(INSTALL.resolve()):
                    raise RuntimeError(f'Live deployment fingerprint escapes installation: {name}')
                if sha(INSTALL / name) != checksum:
                    changed.append(name)
            if not attempt(check):
                fingerprints_ok = False
        manifest['liveDeploymentFilesUnchanged'] = not changed if fingerprints_ok else None
        manifest['externalDeploymentChangesObserved'] = changed
        if changed:
            final_errors.append(RuntimeError(f'Live deployment changed during tests: {changed}'))
        manifest['finishedAt'] = datetime.datetime.now().astimezone().isoformat()
        manifest['bulkCopiesRemoved'] = False
        # Never remove copies until both owned children have exited.
        def children_exited():
            return all(child is None or child.poll() is not None for child in [game, mongo])
        exited = []
        if attempt(lambda: exited.append(children_exited())) and exited[0]:
            removed = []
            for name in ['resources', 'data', 'server.jar', 'keystore.p12']:
                removed.append(attempt(lambda name=name: remove_bulk(run / name)))
            manifest['bulkCopiesRemoved'] = all(removed)
        else:
            final_errors.append(RuntimeError('Owned children may still be running; bulk copies retained'))
        if final_errors:
            manifest['finalizationErrors'] = [f'{type(error).__name__}: {error}' for error in final_errors]
            manifest.update(status='failed', error=str(primary_error or final_errors[0]))
        attempt(save)
        if final_errors:
            if primary_error is not None:
                primary_error.add_note('Finalization: ' + '; '.join(str(error) for error in final_errors))
            else:
                raise final_errors[0]
    print(json.dumps({'status': manifest['status'], 'tests': manifest['tests'], 'results': str(run / 'results.xml'),
                      'failures': manifest['failures'], 'liveDeploymentFilesUnchanged': manifest['liveDeploymentFilesUnchanged']}, indent=2), flush=True)
    if manifest['failures']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
