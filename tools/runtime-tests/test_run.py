"""Network-free execute() regressions: real tiny-fixture cp, mocked runtime children/HTTP."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('runtime_runner', HERE / 'run.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
# Independent expected contracts: real assertion names, not arbitrary success records.
START = 'exact archive starts HTTP, game ticks and real Mongo'
CONTRACTS = {
    'fresh': [START, 'unseeded fresh account completes world login'],
    'activity': [START, 'new activity document obtains an ID and persists through normal writer'],
    'write': [START] + [
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
    'restart': [START, 'server and Mongo restart preserve player, items and quest variables',
        'TPS reload preserves unlocks and final removal persists clean equipment',
        'persisted claim markers block repayment after restart'],
}
ABILITY_ERROR = ('15:25:35 <\x1b[1;31mERROR\x1b[0m:AbilityManager> Ability action TriggerAbility threw\n'
    'java.lang.NullPointerException: Player.getWorld() is null\n'
    '\tat emu.grasscutter.game.ability.actions.ActionTriggerAbility.execute(ActionTriggerAbility.java:28)\n')
STATUE = ("15:25:32 <ERROR:TsvUtils> Error loading file './resources/ExcelBinOutput/StatuePromoteExcelConfigData.tsj' - "
    'File does not exist. You are missing resources. Note that this file may exist in JSON, TSV, or TSJ format, any of which are suitable.\n')
BLOSSOM = ('15:25:35 <ERROR:BlossomManager> Cannot find blossom type BLUE (chest 2) in '
    'BlossomRefreshExcelConfigData; its reward preview is skipped\n')


class ExecuteTests(unittest.TestCase):
    def setUp(self):
        scratch = HERE.parents[1] / 'captures/runner-regressions'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.install = self.root / 'install'
        for name in ['grasscutter.jar', 'customization/rotation-catalog.json', 'data/DungeonDrop.json',
                     'resources/ExcelBinOutput/PlayerLevelExcelConfigData.json',
                     'resources/ExcelBinOutput/RewardExcelConfigData.json',
                     'resources/ExcelBinOutput/BlossomRefreshExcelConfigData.json',
                     'resources/ExcelBinOutput/TpsWeaponExcelConfigData.json',
                     'resources/ExcelBinOutput/TpsWeaponAccessoryExcelConfigData.json',
                     'resources/ExcelBinOutput/ItemExcelConfigData.json',
                     'resources/BinOutput/Ability/Avatar_Nahida.json']:
            path = self.install / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}')
        (self.install / 'config.json').write_text(json.dumps({'databaseInfo': {}, 'server': {
            'http': {'encryption': {}}, 'game': {'gameOptions': {'newAccountIntro': {}}, 'watchdog': {}}, 'dispatch': {}}}))
        self.parent = self.root / 'runs'
        self.parent.mkdir()
        self.code = 0
        self.names = None
        self.complete = True
        self.marker_change = {}
        self.log = ''
        self.profile = None
        self.assertion_status = 'passed'
        self.gap_evidence = True
        self.before_java = lambda run: None
        self.after_java = lambda run: None
        self.compiler_error = None
        self.java_started = False
        self.compiler_started = False
        self.copied_root_link = None
        self.copy_error = False
        self.copy_started = False
        self.real_process = subprocess.run
        self.real_popen = subprocess.Popen

    def execute(self, phases=('fresh',)):
        owner = self
        class Child:
            def __init__(self, cmd, **kwargs):
                self.pid = 42
                self.returncode = None
                self.polls = 0
                self.mongo = 'mongod' in cmd[0]
                run = kwargs['cwd']
                if self.mongo:
                    Path(cmd[cmd.index('--logpath') + 1]).write_text('mongod shutdown complete')
                else:
                    owner.java_started = True
                    owner.after_java(run)
                    phase = cmd[-1]
                    names = owner.names if owner.names is not None else CONTRACTS[phase]
                    if owner.profile:
                        names = [names[0], 'installed resource gaps are explicitly bounded'] + names[1:]
                    if owner.profile and owner.gap_evidence:
                        (run / f'{phase}-resource-gaps.json').write_text(json.dumps({
                            'profile': owner.profile, 'statuePromoteMissing': True,
                            'blueBlossomChestMissing': True, 'thaiTextMissing': True}))
                    payload = json.dumps([{'name': name, 'status': owner.assertion_status, 'durationMillis': 1} for name in names])
                    (run / f'{phase}-results.json').write_text(payload)
                    if owner.complete:
                        marker = {'schemaVersion': 1, 'phase': phase, 'status': 'completed',
                                  'assertionCount': len(names), 'resultsSha256': hashlib.sha256(payload.encode()).hexdigest()}
                        marker.update(owner.marker_change)
                        (run / f'{phase}-complete.json').write_text(json.dumps(marker))
                    kwargs['stdout'].write(owner.log)
                    kwargs['stdout'].flush()
            def poll(self):
                if not self.mongo:
                    self.polls += 1
                    if self.polls > 1:
                        self.returncode = owner.code
                return self.returncode
            def send_signal(self, _):
                self.returncode = 0
            def wait(self, **_):
                return self.returncode
        class Socket:
            def __init__(self, *_): pass
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def setsockopt(self, *_): pass
            def bind(self, *_): pass
        def process(cmd, **kwargs):
            if cmd[0] == 'cp':
                owner.copy_started = True
                with patch.object(runner.subprocess, 'Popen', owner.real_popen):
                    owner.real_process(cmd, **kwargs)
                if owner.copy_error:
                    raise subprocess.CalledProcessError(1, cmd)
                if owner.copied_root_link == Path(cmd[-1]).name:
                    shutil.rmtree(cmd[-1])
                    Path(cmd[-1]).symlink_to(owner.install / owner.copied_root_link, target_is_directory=True)
            else:
                owner.compiler_started = True
                owner.before_java(Path(cmd[cmd.index('-d') + 1]).parent)
                if owner.compiler_error:
                    raise owner.compiler_error
        @contextlib.contextmanager
        def response(*_, **__):
            yield io.StringIO('{"game":{"tick":{"completedCount":4}},"jvm":{"deadlock":{"status":"CLEAR"}}}')
        with patch.object(runner, 'INSTALL', self.install), patch.object(runner.socket, 'socket', Socket), \
                patch.object(runner.subprocess, 'run', process), patch.object(runner.subprocess, 'Popen', Child), \
                patch.object(runner, 'wait_port'), patch.object(runner.urllib.request, 'urlopen', response), \
                patch.object(runner.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()):
            kwargs = {'resource_gap_profile': self.profile} if self.profile else {}
            runner.execute(self.parent, self.install / 'grasscutter.jar', runner.sha(self.install / 'grasscutter.jar'), list(phases), **kwargs)
        return self.manifest()

    def manifest(self):
        return json.loads(next(self.parent.glob('*/manifest.json')).read_text())

    def reject(self, phases=('fresh',)):
        with self.assertRaises((RuntimeError, ValueError, FileNotFoundError, SystemExit)):
            self.execute(phases)
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertTrue(manifest['liveDeploymentFilesUnchanged'])
        self.assertTrue(manifest['bulkCopiesRemoved'])
        run = Path(manifest['sandbox'])
        self.assertTrue((run / (phases[0] + '-server.log')).exists())
        self.assertTrue((run / (phases[0] + '-results.json')).exists())

    def preparation_rejected(self):
        with self.assertRaises(Exception):
            self.execute()
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertTrue(manifest['error'])
        self.assertTrue(manifest['bulkCopiesRemoved'])
        self.assertFalse(self.java_started)
        run = Path(manifest['sandbox'])
        for name in ['resources', 'data', 'server.jar']:
            self.assertFalse((run / name).exists())
            self.assertFalse((run / name).is_symlink())
        return manifest

    def test_external_source_roots_reject_before_copy_hash_or_compile(self):
        for name in ['resources', 'data']:
            for absolute in [False, True]:
                with self.subTest(name=name, absolute=absolute):
                    original = self.install / name
                    outside = self.root / (name + '-outside')
                    original.rename(outside)
                    original.symlink_to(outside if absolute else Path('..') / outside.name, target_is_directory=True)
                    self.parent = self.root / f'runs-{name}-{absolute}'
                    self.parent.mkdir()
                    try:
                        with patch.object(runner, 'sha', wraps=runner.sha) as hashed:
                            self.preparation_rejected()
                            self.assertFalse(any(str(original) in str(call.args[0]) for call in hashed.call_args_list))
                        self.assertFalse(self.compiler_started)
                        self.assertFalse(self.copy_started)
                        self.assertTrue((outside / ('DungeonDrop.json' if name == 'data' else 'ExcelBinOutput/RewardExcelConfigData.json')).exists())
                    finally:
                        original.unlink()
                        outside.rename(original)

    def test_destination_root_link_rejected_and_unlinked_safely(self):
        for name in ['resources', 'data']:
            with self.subTest(name=name):
                self.copied_root_link = name
                self.parent = self.root / ('destination-' + name)
                self.parent.mkdir()
                self.preparation_rejected()
                self.assertFalse(self.compiler_started)
                self.assertTrue((self.install / name).is_dir())

    def test_gnu_cp_preserves_root_links_and_cleanup_only_unlinks(self):
        for name in ['resources', 'data']:
            for absolute in [False, True]:
                with self.subTest(name=name, absolute=absolute):
                    source = self.root / f'source-{name}-{absolute}'
                    source.symlink_to(self.install / name if absolute else Path('install') / name, target_is_directory=True)
                    destination = self.root / f'copied-{name}-{absolute}'
                    self.real_process(['cp', '-a', '--reflink=auto', str(source), str(destination)], check=True)
                    self.assertTrue(destination.is_symlink())
                    with self.assertRaisesRegex(RuntimeError, 'root symlink'):
                        runner.validate_tree(destination, self.root)
                    runner.remove_bulk(destination)
                    self.assertFalse(destination.is_symlink())
                    self.assertTrue((self.install / name).is_dir())

    def test_dangling_bulk_root_cleanup_unlinks(self):
        link = self.root / 'dangling'
        link.symlink_to(self.root / 'absent', target_is_directory=True)
        runner.remove_bulk(link)
        self.assertFalse(link.is_symlink())

    def test_partial_copy_failure_is_cleaned_and_published(self):
        self.copy_error = True
        self.preparation_rejected()
        self.assertFalse(self.compiler_started)

    def test_nested_external_link_rejects_before_compile(self):
        outside = self.root / 'external'
        outside.mkdir()
        (outside / 'sentinel').write_text('untouched')
        (self.install / 'resources/escape').symlink_to(outside, target_is_directory=True)
        self.preparation_rejected()
        self.assertFalse(self.compiler_started)
        self.assertEqual('untouched', (outside / 'sentinel').read_text())

    def test_internal_descendant_link_retains_identity(self):
        (self.install / 'resources/alias.json').symlink_to('ExcelBinOutput/RewardExcelConfigData.json')
        (self.install / 'resources/alias-dir').symlink_to('ExcelBinOutput', target_is_directory=True)
        manifest = self.execute()
        files = json.loads((Path(manifest['sandbox']) / 'input-manifest.json').read_text())['files']
        self.assertEqual({'directory': True}, files['resources'])
        self.assertEqual({'directory': True}, files['data'])
        self.assertEqual('ExcelBinOutput/RewardExcelConfigData.json', files['resources/alias.json']['symlink'])
        self.assertEqual({'symlink': 'ExcelBinOutput'}, files['resources/alias-dir'])
        self.assertEqual(runner.sha(self.install / 'resources/alias.json'), files['resources/alias.json']['sha256'])

    def test_missing_required_input_publishes_failure(self):
        (self.install / 'resources/ExcelBinOutput/RewardExcelConfigData.json').unlink()
        self.preparation_rejected()
        self.assertFalse(self.compiler_started)

    def test_missing_watched_input_publishes_failure(self):
        (self.install / 'data/DungeonDrop.json').unlink()
        self.preparation_rejected()
        self.assertFalse(self.compiler_started)

    def test_compiler_failure_publishes_failure(self):
        self.compiler_error = subprocess.CalledProcessError(1, ['javac'])
        self.preparation_rejected()
        self.assertTrue(self.compiler_started)

    def test_final_fingerprint_change_rejects_overall_acceptance(self):
        self.after_java = lambda run: (self.install / 'data/DungeonDrop.json').write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'Live deployment changed'):
            self.execute()
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertIn('Live deployment changed', manifest['error'])
        self.assertFalse(manifest['liveDeploymentFilesUnchanged'])
        self.assertTrue(manifest['bulkCopiesRemoved'])
        suite = ET.parse(Path(manifest['sandbox']) / 'results.xml').getroot()
        self.assertEqual('AstaPSRealRuntimeScenarios', suite.attrib['name'])
        self.assertEqual('0', suite.attrib['failures'])  # Scenario pass is not overall acceptance.

    def test_final_fingerprint_error_does_not_mask_primary_failure(self):
        self.code = -9
        self.after_java = lambda run: (self.install / 'data/DungeonDrop.json').unlink()
        with self.assertRaisesRegex(RuntimeError, 'fresh exited -9'):
            self.execute()
        manifest = self.manifest()
        self.assertIn('fresh exited -9', manifest['error'])
        self.assertTrue(any('FileNotFoundError' in error for error in manifest['finalizationErrors']))
        self.assertTrue(manifest['bulkCopiesRemoved'])

    def test_missing_final_fingerprint_publishes_failure(self):
        self.after_java = lambda run: (self.install / 'data/DungeonDrop.json').unlink()
        with self.assertRaises(Exception):
            self.execute()
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertTrue(manifest['error'])
        self.assertTrue(manifest['bulkCopiesRemoved'])

    def test_cleanup_failure_publishes_failed_acceptance(self):
        with patch.object(runner.shutil, 'rmtree', side_effect=OSError('cleanup broke')):
            with self.assertRaisesRegex(OSError, 'cleanup broke'):
                self.execute()
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertIn('cleanup broke', manifest['error'])
        self.assertFalse(manifest['bulkCopiesRemoved'])
        self.assertFalse((Path(manifest['sandbox']) / 'server.jar').exists())

    def test_cleanup_error_does_not_mask_primary_failure(self):
        self.code = -9
        with patch.object(runner.shutil, 'rmtree', side_effect=OSError('cleanup broke')):
            with self.assertRaisesRegex(RuntimeError, 'fresh exited -9'):
                self.execute()
        manifest = self.manifest()
        self.assertIn('fresh exited -9', manifest['error'])
        self.assertTrue(any('cleanup broke' in error for error in manifest['finalizationErrors']))

    def test_shutdown_failure_still_publishes_and_preserves_primary(self):
        self.code = -9
        with patch.object(runner, 'stop', side_effect=RuntimeError('shutdown broke')):
            with self.assertRaisesRegex(RuntimeError, 'fresh exited -9'):
                self.execute()
        manifest = self.manifest()
        self.assertEqual('failed', manifest['status'])
        self.assertFalse(manifest['bulkCopiesRemoved'])
        self.assertTrue(any('shutdown broke' in error for error in manifest['finalizationErrors']))

    def test_failed_phase_retains_raw_errors_without_disposition(self):
        self.code = -9
        self.log = ABILITY_ERROR
        self.reject()
        run = Path(self.manifest()['sandbox'])
        self.assertIn('NullPointerException', (run / 'fresh-server.log').read_text())
        self.assertFalse((run / 'fresh-log-disposition.json').exists())

    def test_fresh_nonzero_after_startup(self):
        self.code = -9; self.names = [START]
        self.reject()

    def test_activity_nonzero_after_startup(self):
        self.code = -9; self.names = [START]
        self.reject(('activity',))

    def test_zero_exit_startup_only(self):
        self.names = [START]
        self.reject()

    def test_complete_results_cannot_hide_nonzero_exit(self):
        self.code = 1
        self.reject()

    def test_activity_complete_results_cannot_hide_nonzero_exit(self):
        self.code = -9
        self.reject(('activity',))

    def test_complete_results_without_completion(self):
        self.complete = False
        self.reject()

    def test_wrong_completion_phase(self):
        self.marker_change = {'phase': 'activity'}
        self.reject()

    def test_wrong_completion_digest(self):
        self.marker_change = {'resultsSha256': '0' * 64}
        self.reject()

    def test_wrong_completion_count(self):
        self.marker_change = {'assertionCount': 99}
        self.reject()

    def test_wrong_completion_status(self):
        self.marker_change = {'status': 'running'}
        self.reject()

    def test_duplicate_results(self):
        self.names = [START, START]
        self.reject()

    def test_arbitrary_passing_results(self):
        self.names = ['looks good']
        self.reject()

    def test_wrong_restart_results(self):
        self.names = CONTRACTS['fresh']
        self.reject(('write', 'restart'))

    def test_failed_assertion(self):
        self.assertion_status = 'failed'
        self.reject()

    def test_unexpected_ability_exception(self):
        self.log = ABILITY_ERROR
        self.reject()

    def test_unlogged_exception(self):
        self.log = 'java.lang.IllegalStateException: detached owner\n\tat example.Owner.run(Owner.java:1)\n'
        self.reject()

    def test_shutdown_error_after_successful_assertions(self):
        self.log = 'Shutdown failed\n'
        self.reject()

    def test_fatal_error(self):
        self.log = '12:00:00 <FATAL:Server> shutdown corrupted\n'
        self.reject()

    def test_unknown_error(self):
        self.log = '12:00:00 <ERROR:NewSubsystem> corrupted state\n'
        self.reject()

    def test_resource_errors_require_explicit_profile(self):
        self.log = STATUE + BLOSSOM
        self.reject()

    def test_narrow_resource_profile_records_disposition(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM
        manifest = self.execute()
        report = json.loads((Path(manifest['sandbox']) / 'fresh-log-disposition.json').read_text())
        self.assertEqual(2, len(report['acceptedResourceErrors']))
        self.assertEqual([], report['unexpectedErrors'])
        self.assertEqual(self.profile, manifest['resourceGapProfile'])

    def test_resource_profile_requires_asserted_evidence(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM
        self.gap_evidence = False
        self.reject()

    def test_resource_profile_never_accepts_ability_error(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM + ABILITY_ERROR
        self.reject()

    def test_resource_profile_rejects_changed_resource_message(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM.replace('BLUE', 'GOLD')
        self.reject()

    def test_resource_profile_rejects_repeated_resource_error(self):
        self.profile = 'installed-20261011'; self.log = STATUE + STATUE + BLOSSOM
        self.reject()

    def test_resource_profile_rejects_present_statue_input(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM
        (self.install / 'resources/ExcelBinOutput/StatuePromoteExcelConfigData.json').write_text('[]')
        self.reject()

    def test_resource_profile_rejects_unpaired_thai_error(self):
        self.profile = 'installed-20261011'
        self.log = STATUE + BLOSSOM + '12:00:00 <ERROR:Language> Error loading textmap: TH\n'
        self.reject()

    def test_resource_profile_cannot_hide_bare_stacktrace(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM + '\tat unknown.Owner.run(Owner.java:1)\n'
        self.reject()

    def test_resource_profile_bounds_thai_error_pair(self):
        self.profile = 'installed-20261011'
        self.log = STATUE + BLOSSOM + ('12:00:00 <ERROR:Language> Error loading textmap: TH\n'
            '12:00:00 <ERROR:Language> java.nio.file.NoSuchFileException: ./resources/TextMap/TextMapTH.json\n')
        self.assertEqual('passed', self.execute()['status'])

    def test_resource_profile_rejects_other_missing_textmap(self):
        self.profile = 'installed-20261011'; self.log = STATUE + BLOSSOM + (
            '12:00:00 <ERROR:Language> java.nio.file.NoSuchFileException: ./resources/TextMap/TextMapEN.json\n')
        self.reject()

    def test_complete_focused_phase(self):
        manifest = self.execute()
        self.assertEqual('passed', manifest['status'])
        self.assertFalse(manifest['mongoRestartVerified'])

    def test_complete_write_restart(self):
        manifest = self.execute(('write', 'restart'))
        self.assertTrue(manifest['mongoRestartVerified'])
        self.assertEqual(20, manifest['tests'])

    def test_actual_resource_manifest_retained(self):
        manifest = self.execute()
        run = Path(manifest['sandbox'])
        recorded = json.loads((run / 'input-manifest.json').read_text())
        for relative in ['resources/BinOutput/Ability/Avatar_Nahida.json',
                         'resources/ExcelBinOutput/TpsWeaponExcelConfigData.json',
                         'resources/ExcelBinOutput/TpsWeaponAccessoryExcelConfigData.json',
                         'resources/ExcelBinOutput/ItemExcelConfigData.json', 'data/DungeonDrop.json']:
            self.assertEqual(runner.sha(self.install / relative), recorded['files'][relative]['sha256'])
        self.assertEqual(runner.sha(run / 'input-manifest.json'), manifest['inputManifestSha256'])
        self.assertFalse((run / 'resources').exists())

    def test_resource_changes_change_identity(self):
        first = self.execute()['inputManifestSha256']
        for relative in ['resources/BinOutput/Ability/Avatar_Nahida.json',
                         'resources/ExcelBinOutput/TpsWeaponExcelConfigData.json',
                         'resources/ExcelBinOutput/TpsWeaponAccessoryExcelConfigData.json',
                         'resources/ExcelBinOutput/ItemExcelConfigData.json']:
            with self.subTest(relative=relative):
                (self.install / relative).write_text('changed')
                self.parent = self.root / ('runs-' + str(len(list(self.root.iterdir()))))
                self.parent.mkdir()
                changed = self.execute()['inputManifestSha256']
                self.assertNotEqual(first, changed)
                first = changed


if __name__ == '__main__':
    unittest.main()
