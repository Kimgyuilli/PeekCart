#!/usr/bin/env python3
"""Exercise the actual observation CLI; only GitHub reads and Docker transport are mocked."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(os.environ.get('CI_PUBLICATION_TEST_SCRIPT', ROOT / 'scripts/ci-publication-state.py'))
SHA = 'a' * 40
SERVICES = ['notification-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway']
HARNESS = '''import io,json,os,runpy,sys,urllib.request
fixture=json.load(open(os.environ['PUBLICATION_FIXTURE']))
responses=iter(fixture['main'])
def read(request,timeout):
    assert request.full_url == 'https://api.github.com/repos/example/PeekCart/git/ref/heads/main'
    value=next(responses)
    if value == 'ERROR': raise OSError('fixture main unavailable')
    return io.BytesIO(json.dumps({'object':{'sha':value}}).encode())
urllib.request.urlopen=read
sys.argv=sys.argv[1:]
runpy.run_path(sys.argv[0],run_name='__main__')
'''


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='publication-state-'); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.inputs = self.root / 'inputs'; self.output = self.root / 'output'
        self.fixture = {'main': [SHA, SHA], 'services': {}}
        for n, service in enumerate(SERVICES, 1):
            digest, image_id = 'sha256:' + f'{n:064x}', 'sha256:' + f'{n + 100:064x}'
            self.fixture['services'][service] = {'expected': digest, 'latest': digest, 'config': image_id}
            directory = self.inputs / ('publish-evidence-' + service + '-attempt-1'); directory.mkdir(parents=True)
            for name, value in [('service', service), ('expected-sha', SHA), ('run-id', '100'), ('run-attempt', '1'),
                                ('push-outcome', 'success'), ('load-outcome', 'success'), ('image-id', image_id)]:
                (directory / (name + '.txt')).write_text(value + '\n')
        binary = self.root / 'bin'; binary.mkdir()
        docker = binary / 'docker'
        docker.write_text('#!' + sys.executable + '''
import json,os,sys
args=sys.argv[1:]
with open(os.environ['PUBLICATION_CALLS'],'a') as f: f.write(json.dumps(args)+'\\n')
if args[:3] != ['buildx','imagetools','inspect']: sys.exit(99)
ref=args[-1]; service=ref.split('/peekcart-')[1].split(':')[0].split('@')[0]
x=json.load(open(os.environ['PUBLICATION_FIXTURE']))['services'][service]
if x.get('read_error'): sys.exit(23)
if '--raw' in args: print(json.dumps({'config': {'digest': x['config']}}))
else:
    if x.get('bad_json'): print('{broken'); sys.exit(0)
    key='latest' if ref.endswith(':latest') else 'expected'
    print(json.dumps({'digest':x[key]}))
'''); docker.chmod(0o755)
        self.env = os.environ.copy(); self.env.update(PATH=str(binary) + os.pathsep + self.env['PATH'],
            GITHUB_REPOSITORY='example/PeekCart', GITHUB_RUN_ID='100', GITHUB_RUN_ATTEMPT='2',
            PUBLICATION_FIXTURE=str(self.root / 'fixture.json'), PUBLICATION_CALLS=str(self.root / 'calls'), GH_TOKEN='fixture-only')

    def cli(self, expected=0):
        (self.root / 'fixture.json').write_text(json.dumps(self.fixture))
        p = subprocess.run([sys.executable, '-B', '-c', HARNESS, str(SCRIPT), SHA, str(self.inputs), str(self.output)],
                           env=self.env, cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(p.returncode, expected, p.stdout + p.stderr)
        self.assertTrue((self.output / 'summary.json').exists(), 'CLI omitted summary')
        return json.loads((self.output / 'summary.json').read_text())

    def test_six_distinct_images_and_retry_artifacts(self):
        # Different repository digests are valid; an attempt1 receipt remains usable in attempt2.
        gateway = self.inputs / 'publish-evidence-gateway-attempt-1'
        newer = self.inputs / 'publish-evidence-gateway-attempt-2'
        shutil.copytree(gateway, newer); (newer / 'run-attempt.txt').write_text('2\n')
        (gateway / 'push-outcome.txt').write_text('failure\n')
        x = self.cli(); self.assertEqual(x['status'], 'aligned'); self.assertEqual(x['aligned_services'], 6)
        self.assertEqual(len({r['expected_manifest'] for r in x['rows']}), 6)
        self.assertEqual([r['origin_attempt'] for r in x['rows']], ['1'] * 5 + ['2'])
        self.assertTrue((gateway / 'push-outcome.txt').exists(), 'original failed attempt lost')
        self.assertTrue(all(r['status'] == 'aligned' and r['started_at'] <= r['finished_at'] for r in x['rows']))
        calls = [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]
        self.assertEqual(len(calls), 18); self.assertTrue(all(c[:3] == ['buildx','imagetools','inspect'] for c in calls))

    def test_partial_and_no_aligned_services(self):
        self.fixture['services']['gateway']['latest'] = 'sha256:' + 'f' * 64
        x = self.cli(1); self.assertEqual(x['status'], 'partial_relative_to_expected'); self.assertEqual(x['aligned_services'], 5)
        for service in SERVICES: self.fixture['services'][service]['latest'] = 'sha256:' + 'f' * 64
        self.assertEqual(self.cli(1)['status'], 'no_aligned_services')

    def test_missing_and_invalid_evidence(self):
        directory = self.inputs / 'publish-evidence-gateway-attempt-1'
        for name, bad in [('service.txt', 'wrong\n'), ('expected-sha.txt', 'b' * 40 + '\n'), ('run-id.txt','101\n'),
                          ('run-attempt.txt','3\n'), ('run-attempt.txt','NaN\n'), ('push-outcome.txt','unknown\n'), ('load-outcome.txt','failure\n'), ('image-id.txt','bad\n')]:
            path = directory / name; old = path.read_bytes(); path.write_text(bad)
            with self.subTest(name=name, bad=bad):
                x = self.cli(1); self.assertEqual(x['status'], 'incomplete_observation'); self.assertEqual(x['aligned_services'], 5)
            path.write_bytes(old)
        unexpected = self.inputs / 'publish-evidence-unknown-attempt-1'; unexpected.mkdir()
        self.assertEqual(self.cli(1)['status'], 'incomplete_observation'); unexpected.rmdir()
        shutil.rmtree(directory); self.assertEqual(self.cli(1)['status'], 'incomplete_observation')

    def test_mixed_sha_or_run_in_old_attempt_is_rejected(self):
        old = self.inputs / 'publish-evidence-gateway-attempt-1'
        newer = self.inputs / 'publish-evidence-gateway-attempt-2'
        shutil.copytree(old, newer); (newer / 'run-attempt.txt').write_text('2\n')
        for name, bad in [('expected-sha.txt', 'b' * 40 + '\n'), ('run-id.txt', '101\n')]:
            path = old / name; original = path.read_bytes(); path.write_text(bad)
            with self.subTest(name=name): self.assertEqual(self.cli(1)['status'], 'incomplete_observation')
            path.write_bytes(original)
        for name, bad in [('expected-sha.txt', 'b' * 40 + '\n'), ('run-id.txt', '101\n'), ('run-attempt.txt', 'NaN\n'), ('image-id.txt','bad\n'), ('load-outcome.txt','failure\n')]:
            path = newer / name; original = path.read_bytes(); path.write_text(bad)
            with self.subTest(latest_invalid=name):
                x = self.cli(1); self.assertEqual(x['rows'][-1]['status'], 'unavailable')
                self.assertNotIn('origin_attempt', x['rows'][-1])
            path.write_bytes(original)

    def test_read_parse_and_config_failures(self):
        original = copy.deepcopy(self.fixture['services']['gateway'])
        for key, value in [('read_error', True), ('bad_json', True), ('expected','bad'), ('config','sha256:'+'f'*64)]:
            self.fixture['services']['gateway'] = dict(original, **{key:value})
            with self.subTest(key=key):
                x = self.cli(1); self.assertEqual(x['status'], 'incomplete_observation'); self.assertEqual(x['aligned_services'], 5)
                self.assertEqual(x['rows'][-1]['status'], 'unavailable')
        for service in SERVICES: self.fixture['services'][service]['read_error'] = True
        self.assertEqual(self.cli(1)['aligned_services'], 0)

    def test_current_main_and_observation_window(self):
        for main, status in [(['b'*40,SHA], 'stale_or_concurrent_release'), ([SHA,'b'*40], 'stale_or_concurrent_release'),
                             (['ERROR',SHA], 'incomplete_observation'), ([SHA,None], 'incomplete_observation')]:
            self.fixture['main'] = main
            with self.subTest(main=main): self.assertEqual(self.cli(1)['status'], status)

    def test_previous_success_is_not_reused(self):
        self.cli()
        shutil.rmtree(self.inputs / 'publish-evidence-gateway-attempt-1')
        x = self.cli(1); self.assertEqual(x['rows'][-1]['status'], 'unavailable')
        self.assertFalse((self.output / 'gateway/expected.stdout').exists())
        self.fixture['main'] = ['ERROR', 'ERROR']; self.cli(1)
        self.assertFalse((self.output / 'main-before.json').exists())
        self.assertFalse((self.output / 'main-after.json').exists())

    def test_workflow_evidence_and_failure_propagation(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        publish, observation = workflow['jobs']['publish'], workflow['jobs']['publication-state']
        self.assertEqual(sorted(publish['strategy']['matrix']['service']), sorted(SERVICES))
        step = lambda job, name: next(s for s in job['steps'] if s.get('name') == name)
        self.assertEqual(observation['needs'], ['gate', 'publish'])
        self.assertEqual(observation['if'], "${{ always() && github.event_name == 'push' && needs.gate.result == 'success' }}")
        self.assertEqual(observation['permissions'], {'contents':'read','packages':'read'})
        self.assertFalse(observation.get('continue-on-error', False))
        capture = step(publish, 'Preserve publication evidence')
        upload = step(publish, 'Upload publication evidence')
        self.assertEqual(capture['if'], '${{ always() }}'); self.assertEqual(upload['if'], '${{ always() }}')
        self.assertEqual(upload['with']['name'], 'publish-evidence-${{ matrix.service }}-attempt-${{ github.run_attempt }}')
        self.assertFalse(upload['with'].get('overwrite', False))
        self.assertEqual(step(observation, 'Upload publication observation')['with']['name'], 'publication-state-attempt-${{ github.run_attempt }}')
        working = self.root / 'work'; working.mkdir()
        for load, push in [('success','success'), ('success','failure'), ('failure','skipped')]:
            temporary = self.root / (load + '-' + push); temporary.mkdir()
            (working / 'image-id.txt').write_text('sha256:' + '1' * 64 + '\n')
            for name in ['ghcr-push.log','promotion.json']: (temporary / name).write_text('original-' + name)
            env = dict(self.env, RUNNER_TEMP=str(temporary), SERVICE='gateway', GITHUB_SHA=SHA, LOAD_OUTCOME=load, PUSH_OUTCOME=push)
            actual = subprocess.run(['/bin/bash','--noprofile','--norc','-e','-o','pipefail','-c',capture['run']], cwd=working, env=env, text=True, capture_output=True)
            self.assertEqual(actual.returncode, 0, actual.stderr)
            evidence = temporary / 'publish-evidence'
            for name, value in [('service','gateway'),('expected-sha',SHA),('run-id','100'),('run-attempt','2'),('load-outcome',load),('push-outcome',push)]:
                self.assertEqual((evidence / (name + '.txt')).read_text(), value + '\n')
            for name in ['ghcr-push.log','promotion.json']:
                self.assertEqual((evidence / name).read_bytes(), (temporary / name).read_bytes())
        observe = step(observation, 'Observe publication state')
        self.assertEqual(observe['if'], '${{ always() }}'); self.assertFalse(observe.get('continue-on-error',False))
        binary = self.root / 'observe-bin'; binary.mkdir()
        stub = binary / 'python3'; stub.write_text('#!/bin/sh\nexit "$PUBLICATION_EXIT"\n'); stub.chmod(0o755)
        for code in [0,23]:
            env = dict(self.env, PATH=str(binary) + os.pathsep + self.env['PATH'], GITHUB_SHA=SHA, RUNNER_TEMP=str(self.root), PUBLICATION_EXIT=str(code))
            actual = subprocess.run(['/bin/bash','--noprofile','--norc','-e','-o','pipefail','-c',observe['run']], cwd=working, env=env)
            self.assertEqual(actual.returncode, code)

    def test_existing_release_gate_actual_cli(self):
        scripts = self.root / 'scripts'; scripts.mkdir()
        shutil.copyfile(ROOT / 'scripts/ci-release-gate-lint.sh', scripts / 'ci-release-gate-lint.sh')
        path = self.root / '.github/workflows/ci.yml'; path.parent.mkdir(parents=True)
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        variants = [('normal',None),('missing',lambda w:w['jobs'].pop('publication-state')),
            ('skip_failed_publish',lambda w:w['jobs']['publication-state'].update({'if':'success()'})),
            ('write_permission',lambda w:w['jobs']['publication-state']['permissions'].update(packages='write')),
            ('ignored_failure',lambda w:w['jobs']['publication-state'].update({'continue-on-error':True}))]
        for label, change in variants:
            changed = copy.deepcopy(workflow)
            if change: change(changed)
            path.write_text(yaml.safe_dump(changed, sort_keys=False))
            actual = subprocess.run(['bash',str(scripts / 'ci-release-gate-lint.sh')], cwd=self.root, text=True, capture_output=True)
            self.assertEqual(actual.returncode, 0 if change is None else 1, actual.stdout + actual.stderr)
            if change: self.assertIn('CRG-006', actual.stdout + actual.stderr)


def mutants():
    original = (ROOT / 'scripts/ci-publication-state.py').read_text()
    variants = [
        ('unavailable-is-success', "status = 'incomplete_observation'", "status = 'aligned'", 'test_read_parse_and_config_failures'),
        ('old-attempt-preferred', 'max(choices, key=', 'min(choices, key=', 'test_six_distinct_images_and_retry_artifacts'),
        ('main-transition-ignored', "if (before is not None and before != expected) or (after is not None and after != expected):", 'if False:', 'test_current_main_and_observation_window'),
        ('identity-ignored', 'def command(directory, name, args):', "def command(directory, name, args):\n    if args[0] == 'bash': return b''", 'test_read_parse_and_config_failures'),
        ('source-marker-ignored', "(directory / file).read_text() == value + '\\n'", 'True', 'test_mixed_sha_or_run_in_old_attempt_is_rejected'),
        ('always-pass', 'def observe(expected, inputs, output):', 'def observe(expected, inputs, output):\n    return 0', 'test_six_distinct_images_and_retry_artifacts'),
        ('always-reject', 'def observe(expected, inputs, output):', 'def observe(expected, inputs, output):\n    return 1', 'test_six_distinct_images_and_retry_artifacts'),
    ]
    with tempfile.TemporaryDirectory(prefix='publication-mutants-') as temp:
        scripts = Path(temp) / 'scripts'; scripts.mkdir()
        shutil.copyfile(ROOT / 'scripts/ci-image-identity.sh', scripts / 'ci-image-identity.sh')
        for name, old, new, test in variants:
            assert old in original, 'mutation source drift: ' + name
            script = scripts / 'ci-publication-state.py'; script.write_text(original.replace(old,new))
            env = dict(os.environ, CI_PUBLICATION_TEST_SCRIPT=str(script))
            actual = subprocess.run([sys.executable,'-B',__file__,'PublicationTest.'+test],env=env,text=True,capture_output=True)
            assert actual.returncode and ('FAIL: '+test) in actual.stderr, name + actual.stdout + actual.stderr
            print(name + ': REJECTED')
    print('PUBLICATION-SELFTEST: 7 incorrect implementations rejected; no real registry/GitHub/Docker calls')


if __name__ == '__main__':
    result = unittest.main(verbosity=2, exit=False).result
    if not result.wasSuccessful(): sys.exit(1)
    if len(sys.argv) == 1: mutants()
