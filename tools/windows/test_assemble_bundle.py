"""Synthetic packaging tests; no installed client or proprietary binaries are read."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('assemble_bundle', Path(__file__).with_name('assemble_bundle.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='astaps-kit-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.setup = self.root / 'runtime' / 'client-setup'
        self.source = self.setup / 'patch-source'
        self.source.mkdir(parents=True)
        (self.source / 'LICENSE').write_text('synthetic license')
        (self.setup / 'Astrolabe.dll').write_bytes(b'synthetic proxy')
        (self.setup / 'rea').mkdir()
        (self.setup / 'rea' / 'mhypbase-original.dll').write_bytes(b'synthetic stock')
        self.output = self.root / 'windows' / 'kit'
        self.manifest = {
            'patch_commit': '1' * 40,
            'patch_sha256': hashlib.sha256(b'synthetic proxy').hexdigest(),
            'bootstrap_sha256': hashlib.sha256(b'synthetic stock').hexdigest(),
        }
        self.status = ''
        self.commit = self.manifest['patch_commit']
        self.files = 'LICENSE'
        self.load = patch.object(MODULE.json, 'loads', return_value=self.manifest)
        self.git = patch.object(MODULE, 'git', side_effect=self.fake_git)
        self.load.start(); self.git.start()
        self.addCleanup(self.load.stop); self.addCleanup(self.git.stop)

    def fake_git(self, source, *args):
        if args == ('rev-parse', 'HEAD'):
            return self.commit if source == self.source else '2' * 40
        if args[0] == 'status':
            return self.status
        if args == ('ls-files',):
            return self.files
        raise AssertionError(args)

    def test_complete_package_and_all_hashes(self):
        result = MODULE.assemble(self.setup, self.output)
        self.assertEqual('2' * 40, result['handoff_commit'])
        self.assertIn('patch-source/LICENSE', result['files'])
        self.assertIn('Client.cmd', result['files'])
        for path, facts in result['files'].items():
            self.assertEqual(facts['sha256'], MODULE.digest(self.output / path))
        checks = (self.output / 'SHA256SUMS.txt').read_text().splitlines()
        self.assertEqual(len(result['files']) + 1, len(checks))
        for line in checks:
            value, path = line.split('  ', 1)
            self.assertEqual(value, MODULE.digest(self.output / path))
        self.assertFalse((self.output / 'GenshinImpact.exe').exists())
        self.assertFalse((self.output / 'config.json').exists())
        self.assertFalse((self.output / 'patch-source/.git').exists())

    def test_existing_output_untouched(self):
        self.output.mkdir(parents=True)
        (self.output / 'sentinel').write_text('keep')
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertEqual('keep', (self.output / 'sentinel').read_text())

    def test_dirty_source_refused(self):
        self.status = ' M src/lib.rs'
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_revision_refused(self):
        self.commit = '3' * 40
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertFalse(self.output.exists())

    def test_modified_proxy_refused(self):
        (self.setup / 'Astrolabe.dll').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertFalse(self.output.exists())

    def test_modified_original_refused(self):
        (self.setup / 'rea/mhypbase-original.dll').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertFalse(self.output.exists())

    def test_output_inside_runtime_refused(self):
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.setup.parent / 'kit')
        self.assertFalse((self.setup.parent / 'kit').exists())

    def test_linked_source_refused_and_staging_removed(self):
        (self.source / 'LICENSE').unlink()
        outside = self.root / 'outside'
        outside.write_text('not source')
        (self.source / 'LICENSE').symlink_to(outside)
        with self.assertRaises(ValueError):
            MODULE.assemble(self.setup, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob('.astaps-windows-*')))
        self.assertEqual('not source', outside.read_text())


if __name__ == '__main__':
    unittest.main()
