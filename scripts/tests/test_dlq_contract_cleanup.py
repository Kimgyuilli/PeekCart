#!/usr/bin/env python3
"""Ownership boundary tests. --docker adds disposable real-Docker interruption controls."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import dlq_contract_lab as module

DOCKER = '--docker' in sys.argv
if DOCKER:
    sys.argv.remove('--docker')

CHILD = '''
import json,subprocess,sys,time
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import dlq_contract_lab as module
folder=Path(sys.argv[2]); mode=sys.argv[3]
fixture=module.Lab(folder)
original=module.command

def intercepted(args, *a, **kw):
    if args[:2] == ['docker','run']:
        owner=json.loads((folder/'container.json').read_text())
        assert owner['nonce']==fixture.nonce and 'id' not in owner
        if mode=='before-create':
            raise subprocess.TimeoutExpired('injected-before-create', 1)
        result=original(args,*a,**kw)
        (folder/'created-id.txt').write_text(result.stdout.strip())
        if mode=='kill-before-return':
            time.sleep(60)
        raise subprocess.TimeoutExpired('injected-after-create-before-return', 1)
    return original(args,*a,**kw)
module.command=intercepted
try:
    fixture.start()
finally:
    fixture.stop()
'''


class OwnershipTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='d065-cleanup-test-')
        self.addCleanup(self.directory.cleanup)
        self.folder = Path(self.directory.name)
        self.lab = module.Lab(self.folder)
        self.identifier = 'a' * 64

    def test_owner_is_durable_before_create_is_attempted(self):
        def fail(args, *unused, **kwargs):
            self.assertEqual(args[:2], ['docker', 'run'])
            owner = json.loads((self.folder / 'container.json').read_text())
            self.assertEqual(owner['nonce'], self.lab.nonce)
            self.assertNotIn('id', owner)
            self.assertIn('peekcart.fixture.run=' + owner['nonce'], args)
            raise subprocess.TimeoutExpired('fixture', 1)
        with patch.object(module, 'command', side_effect=fail):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.lab.start()
        self.assertFalse(self.lab.cleanup_completed)

    def test_lost_response_cleanup_uses_inspected_id(self):
        self.lab.save_owner()
        calls = []
        def response(args, *unused, **kwargs):
            calls.append(args)
            if args[1] == 'ps':
                return SimpleNamespace(stdout=self.identifier)
            if args[1] == 'inspect':
                return SimpleNamespace(stdout=json.dumps([{'Name': '/' + self.lab.name,
                    'Config': {'Labels': {'peekcart.fixture': 'dlq-contract',
                                          'peekcart.fixture.run': self.lab.nonce}}}]))
            self.assertEqual(args, ['docker', 'rm', '-f', self.identifier])
            return SimpleNamespace(stdout='')
        with patch.object(module, 'command', side_effect=response):
            self.lab.stop()
        self.assertTrue(self.lab.cleanup_completed)
        self.assertEqual(calls[-1][-1], self.identifier)

    def test_same_name_different_nonce_is_never_removed(self):
        self.lab.save_owner()
        with patch.object(module, 'command', side_effect=[
            SimpleNamespace(stdout=self.identifier),
            SimpleNamespace(stdout=json.dumps([{'Name': '/' + self.lab.name, 'Config': {
                'Labels': {'peekcart.fixture': 'dlq-contract', 'peekcart.fixture.run': 'b' * 32}}}]))
        ]) as invoke:
            with self.assertRaisesRegex(RuntimeError, 'nonce mismatch'):
                self.lab.stop()
            self.assertEqual(invoke.call_count, 2)
        self.assertFalse(self.lab.cleanup_completed)

    def test_received_id_mismatch_is_never_removed(self):
        self.lab.save_owner('b' * 64)
        with patch.object(module, 'command', return_value=SimpleNamespace(stdout=self.identifier)) as invoke:
            with self.assertRaisesRegex(RuntimeError, 'ID changed'):
                self.lab.stop()
            self.assertEqual(invoke.call_count, 1)

    def test_before_creation_failure_cleanup_finds_no_container(self):
        self.lab.save_owner()
        with patch.object(module, 'command', return_value=SimpleNamespace(stdout='')) as invoke:
            self.lab.stop()
            self.assertEqual(invoke.call_count, 1)
        self.assertTrue(self.lab.cleanup_completed)


@unittest.skipUnless(DOCKER, 'pass --docker for actual disposable container controls')
class DockerInterruptionTest(unittest.TestCase):
    def test_failures_before_create_after_create_and_sigkill_before_return(self):
        for mode in ('before-create', 'after-create', 'kill-before-return'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='d065-real-cleanup-') as directory:
                folder = Path(directory)
                proc = subprocess.Popen([sys.executable, '-c', CHILD, str(Path(__file__).parent), directory, mode],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    if mode == 'kill-before-return':
                        deadline = time.monotonic() + 45
                        while time.monotonic() < deadline and not (folder / 'created-id.txt').exists():
                            if proc.poll() is not None:
                                self.fail('child exited before create-return checkpoint: ' + proc.communicate()[1])
                            time.sleep(.05)
                        self.assertTrue((folder / 'created-id.txt').exists())
                        # Production start() has not received the create response or persisted its ID.
                        self.assertNotIn('id', json.loads((folder / 'container.json').read_text()))
                        proc.kill()
                    _, stderr = proc.communicate(timeout=50)
                    self.assertNotEqual(proc.returncode, 0)
                    if mode != 'kill-before-return':
                        self.assertIn('TimeoutExpired', stderr)
                    module.cleanup_owned(folder)
                    owner = json.loads((folder / 'container.json').read_text())
                    remaining = module.command(['docker', 'ps', '-aq', '--filter', 'name=^/' + owner['name'] + '$'])
                    self.assertEqual(remaining.stdout.strip(), '')
                    print(json.dumps({'control': mode, 'exit': proc.returncode,
                                      'cleanup_verified': True}), flush=True)
                finally:
                    if proc.poll() is None:
                        proc.kill()
                        proc.communicate(timeout=5)
                    module.cleanup_owned(folder)

    def test_real_same_name_foreign_nonce_is_preserved(self):
        with tempfile.TemporaryDirectory(prefix='d065-real-collision-') as directory:
            fixture = module.Lab(Path(directory))
            created = module.command(['docker', 'create', '--name', fixture.name,
                '--label', 'peekcart.fixture=dlq-contract', '--label', 'peekcart.fixture.run=' + '0' * 32,
                '--network', 'none', '--entrypoint', 'sleep', 'mysql:8.0.46', '60'])
            identifier = created.stdout.strip()
            try:
                with self.assertRaises(RuntimeError):
                    fixture.start()
                with self.assertRaisesRegex(RuntimeError, 'nonce mismatch'):
                    fixture.stop()
                present = module.command(['docker', 'ps', '-aq', '--no-trunc', '--filter', 'id=' + identifier])
                self.assertEqual(present.stdout.strip(), identifier)
                print(json.dumps({'control': 'same-name-foreign-nonce', 'preserved': True}), flush=True)
            finally:
                # This test created the foreign fixture, so it alone owns this cleanup.
                module.command(['docker', 'rm', '-f', identifier])


if __name__ == '__main__':
    unittest.main(verbosity=2)
