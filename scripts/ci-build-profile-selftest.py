#!/usr/bin/env python3
"""Actual analyzer CLI fixtures. No Gradle, Docker, or remote calls."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(os.environ.get('CI_PROFILE_TEST_SCRIPT', ROOT / 'scripts/ci-build-profile.py'))
SERVICE = 'notification-service'
CONTEXT = {'service': SERVICE, 'checkout_sha': 'a' * 40, 'run_id': '123', 'runtime_target': 'runtime',
           'started_epoch': 100, 'image_finished_epoch': 200, 'builder_name': 'builder'}
SUMMARY = ['Total Build Time', 'Startup', 'Settings and buildSrc', 'Loading Projects',
           'Configuring Projects', 'Artifact Transforms', 'Task Execution']


def html():
    summary = ''.join('<tr><td>' + key + '</td><td>1.000s</td></tr>' for key in SUMMARY)
    return ('<html><div id="tab0"><table><tr><th>Description</th><th>Duration</th></tr>' + summary +
            '</table></div><div id="tab4"><table><tr><th>Task</th><th>Duration</th><th>Result</th></tr>' +
            '<tr><td>:common</td><td>1.000s</td><td>(total)</td></tr>' +
            '<tr><td>:common:compileJava</td><td>1.000s</td><td></td></tr>' +
            '<tr><td>:' + SERVICE + ':bootJar</td><td>1.000s</td><td></td></tr></table></div></html>')


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='build-profile-test-'); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'export/metadata').mkdir(parents=True); (self.root / 'export/reports').mkdir()
        self.write_json('context.json', CONTEXT)
        self.write_json('image-metadata.json', {'buildx.build.ref': 'builder/node/image'})
        self.write_json('export-metadata.json', {'buildx.build.ref': 'builder/node/export'})
        self.write_json('export/metadata/profile.json', dict(service=SERVICE, started_epoch=110, finished_epoch=150))
        self.write('image-build.log', '#35 [build 27/27] RUN ./gradlew :notification-service:bootJar --profile\n#35 DONE 35.6s\n#41 exporting to GitHub Actions Cache\n#41 DONE 44.1s\n')
        self.write('profile-export.log', '#35 [build 27/27] RUN ./gradlew :notification-service:bootJar --profile\n#35 CACHED\n')
        self.write('image.dockerbuild', 'opaque fixture record')
        self.report(html())

    def write(self, name, value): (self.root / name).write_text(value)
    def write_json(self, name, value): self.write(name, json.dumps(value))
    def report(self, value):
        self.write('export/reports/profile-fixture.html', value)
        digest = hashlib.sha256(value.encode()).hexdigest()
        self.write('export/metadata/report-sha256.txt', digest + '  build/reports/profile/profile-fixture.html\n')

    def cli(self, want=0):
        result = subprocess.run([sys.executable, '-B', str(SCRIPT), 'analyze', str(self.root)], text=True, capture_output=True)
        self.assertEqual(result.returncode, want, result.stdout + result.stderr)
        if want:
            self.assertIn('invalid evidence', result.stderr)
            self.assertFalse((self.root / 'analysis.json').exists(), 'failed analyzer retained prior successful metrics')
        if not want:
            self.assertTrue((self.root / 'analysis.json').is_file(), 'successful analyzer omitted analysis.json')
            return json.loads((self.root / 'analysis.json').read_text())
        return None

    def test_fresh_profile_and_distinct_cache_export(self):
        result = self.cli()
        self.assertEqual(result['measurement_status'], 'fresh_execution')
        self.assertEqual(result['bootjar_vertex']['seconds'], '35.6')
        self.assertEqual(result['gha_cache_export_vertex']['seconds'], '44.1')
        self.assertEqual(result['gradle_profile']['summary_seconds']['Startup'], '1.000')
        self.assertIsNone(result['previous_cached_profile'])

    def test_cache_hit_is_not_new_gradle_execution(self):
        p = self.root / 'image-build.log'; self.write('image-build.log', p.read_text().replace('#35 DONE 35.6s', '#35 CACHED'))
        self.write_json('export/metadata/profile.json', dict(service=SERVICE, started_epoch=10, finished_epoch=50))
        result = self.cli()
        self.assertEqual(result['measurement_status'], 'cached_previous_profile')
        self.assertIsNone(result['gradle_profile'])
        self.assertIsNotNone(result['previous_cached_profile'])

    def test_stale_fresh_report_is_rejected(self):
        self.write_json('export/metadata/profile.json', dict(service=SERVICE, started_epoch=10, finished_epoch=50)); self.cli(2)

    def test_missing_or_unparseable_inputs_are_rejected(self):
        self.cli()  # A later failure must remove this prior successful result.
        for name in ('image-metadata.json', 'image-build.log', 'export/metadata/profile.json',
                     'export/reports/profile-fixture.html', 'image.dockerbuild'):
            path = self.root / name; original = path.read_bytes(); path.unlink()
            with self.subTest(missing=name): self.cli(2)
            path.write_bytes(original)
            self.write(name, '')
            with self.subTest(empty=name): self.cli(2)
            path.write_bytes(original)

    def test_wrong_service_origin_or_linkage_is_rejected(self):
        for value in (dict(service='payment-service', started_epoch=110, finished_epoch=150),
                      dict(service=SERVICE, started_epoch=160, finished_epoch=150),
                      dict(service=SERVICE, started_epoch=110, finished_epoch=999),
                      dict(service=SERVICE, started_epoch='110', finished_epoch=150)):
            self.write_json('export/metadata/profile.json', value); self.cli(2)
        self.write_json('export/metadata/profile.json', dict(service=SERVICE, started_epoch=110, finished_epoch=150))
        for value in ({}, {'buildx.build.ref': 'builder/node/image'}, {'buildx.build.ref': 'other/node/export'}):
            self.write_json('export-metadata.json', value); self.cli(2)

    def test_hash_and_profile_shape_fail_closed(self):
        self.write('export/metadata/report-sha256.txt', '0' * 64 + '  build/reports/profile/profile-fixture.html\n'); self.cli(2)
        variants = [html().replace('<tr><td>Startup</td><td>1.000s</td></tr>', ''),
                    html().replace('1.000s', 'NaNs'), html().replace('1.000s', '-1s'),
                    html().replace('1.000s', '1'), html().replace('Startup', 'Task Execution'),
                    html().replace(':notification-service:bootJar', ':payment-service:bootJar'), '<html></html>']
        for value in variants:
            with self.subTest(value=value[:60]): self.report(value); self.cli(2)

    def test_export_rebuild_and_incomplete_vertices_rejected(self):
        p = self.root / 'profile-export.log'; old = p.read_text(); self.write('profile-export.log', old.replace('CACHED', 'DONE 35.6s')); self.cli(2); self.write('profile-export.log', old)
        p = self.root / 'image-build.log'; old = p.read_text()
        for value in (old.replace('#35 DONE 35.6s\n', ''), old.replace('#41 DONE 44.1s', '#41 DONE NaNs'),
                      old.replace('exporting to GitHub Actions Cache', 'exporting unknown'),
                      old + '#35 CACHED\n'):
            self.write('image-build.log', value); self.cli(2)

    def test_record_command_failure_is_propagated(self):
        binary = self.root / 'bin'; binary.mkdir(); calls = self.root / 'calls'
        docker = binary / 'docker'
        docker.write_text('#!/bin/sh\necho "$*" >> "$PROFILE_TEST_CALLS"\nif [ "$3" = "$PROFILE_TEST_FAIL" ]; then exit 23; fi\n')
        docker.chmod(0o755)
        env = os.environ.copy(); env.update(BUILD_METADATA='{"buildx.build.ref":"builder/node/image"}', PROFILE_TEST_CALLS=str(calls))
        env['PATH'] = str(binary) + os.pathsep + env['PATH']
        logs = 'buildx history logs --builder builder image --progress plain'
        export = 'buildx history export --builder builder image -o ' + str(self.root / 'image.dockerbuild')
        for mode, failing, expected in (('record-image', 'none', [logs, export]),
                                        ('record-export', 'none', [logs]),
                                        ('record-image', 'logs', [logs]),
                                        ('record-export', 'logs', [logs]),
                                        ('record-image', 'export', [logs, export])):
            with self.subTest(mode=mode, failing=failing):
                if calls.exists(): calls.unlink()
                env['PROFILE_TEST_FAIL'] = failing
                result = subprocess.run([sys.executable, '-B', str(SCRIPT), mode, str(self.root)], env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0 if failing == 'none' else 2, result.stdout + result.stderr)
                self.assertEqual(calls.read_text().splitlines(), expected)

    def test_workflow_and_final_runtime_contract(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        steps = workflow['jobs']['images']['steps']
        image = next(s for s in steps if s.get('id') == 'image-build')['with']
        export = next(s for s in steps if s.get('id') == 'profile-export')['with']
        self.assertEqual(image['target'], 'runtime'); self.assertTrue(image['load'])
        self.assertEqual(image['cache-to'], 'type=gha,mode=max,scope=image-${{ matrix.service }}')
        self.assertEqual(image['builder'], export['builder']); self.assertEqual(image['build-args'], export['build-args'])
        self.assertEqual(export['target'], 'gradle-profile'); self.assertNotIn('cache-to', export)
        dockerfile = Path(os.environ.get('CI_PROFILE_TEST_DOCKERFILE', ROOT / 'Dockerfile')).read_text()
        runtime = dockerfile[dockerfile.index('# Stage 2: Runtime'):].replace(' AS runtime', '')
        expected = '''# Stage 2: Runtime
FROM eclipse-temurin:17-jre@sha256:d226a3132511d057267330beeca73804275899aacdea331b7bd8313bb1506a7a
WORKDIR /app

ARG SERVICE
COPY --from=build /app/${SERVICE}/build/libs/*.jar app.jar

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
'''
        self.assertEqual(runtime, expected)

    def test_existing_policy_step_propagates_failure(self):
        import yaml
        steps = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())['jobs']['lint']['steps']
        body = next(step['run'] for step in steps if step.get('name') == 'Run CI policy lints')
        binary = self.root / 'mock-bin'; binary.mkdir()
        log = self.root / 'policy-calls'
        stub = '#!/bin/sh\necho "${0##*/}:$*" >> "$PROFILE_POLICY_LOG"\nif [ "$*" = "$PROFILE_POLICY_FAIL" ]; then exit 23; fi\nexit 0\n'
        for tool in ('bash', 'python3'):
            path = binary / tool; path.write_text(stub); path.chmod(0o755)
        path = binary / 'grep'; path.write_text('#!/bin/sh\nexit 1\n'); path.chmod(0o755)
        calls = ('-B scripts/ci-build-profile-selftest.py', '-B scripts/ci-build-profile-selftest.py --mutants')
        for failing in ('none', *calls):
            if log.exists(): log.unlink()
            env = os.environ.copy(); env.update(PROFILE_POLICY_LOG=str(log), PROFILE_POLICY_FAIL=failing)
            env['PATH'] = str(binary) + os.pathsep + env['PATH']
            result = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', body],
                                    env=env, cwd=self.root, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0 if failing == 'none' else 23, result.stderr)
            lines = log.read_text().splitlines()
            if failing == 'none':
                for command in calls: self.assertIn('python3:' + command, lines)
            else: self.assertEqual(lines[-1], 'python3:' + failing)


def mutants():
    original = (ROOT / 'scripts/ci-build-profile.py').read_text()
    variants = [
        ('stale-accepted', 'origin_start >= start', 'True', 'test_stale_fresh_report_is_rejected'),
        ('cached-marked-fresh', "'cached_previous_profile' if boot['cached'] else 'fresh_execution'", "'fresh_execution'", 'test_cache_hit_is_not_new_gradle_execution'),
        ('summary-incomplete', 'set(summary) == SUMMARY', 'True', 'test_hash_and_profile_shape_fail_closed'),
        ('detached-rebuild-accepted', "vertex(directory / 'profile-export.log', 'bootJar')['cached']", 'True', 'test_export_rebuild_and_incomplete_vertices_rejected'),
        ('checksum-ignored', "manifest == sha(report) + '  build/reports/profile/' + report.name + '\\n'", 'True', 'test_hash_and_profile_shape_fail_closed'),
        ('qualified-ref-passed-to-cli', "'--builder', builder, record_id", 'ref', 'test_record_command_failure_is_propagated'),
        ('docker-failure-ignored', 'check=True', 'check=False', 'test_record_command_failure_is_propagated'),
        ('always-pass', "context = read_json(directory / 'context.json')", "return", 'test_fresh_profile_and_distinct_cache_export'),
        ('always-reject', "context = read_json(directory / 'context.json')", "raise ValueError('mutant')", 'test_fresh_profile_and_distinct_cache_export'),
    ]
    with tempfile.TemporaryDirectory(prefix='build-profile-mutants-') as directory:
        for name, old, new, test in variants:
            assert old in original, 'mutation source drift: ' + name
            path = Path(directory) / (name + '.py'); path.write_text(original.replace(old, new))
            env = os.environ.copy(); env['CI_PROFILE_TEST_SCRIPT'] = str(path)
            result = subprocess.run([sys.executable, '-B', __file__, 'ProfileTest.' + test], env=env, text=True, capture_output=True)
            assert result.returncode and ('FAIL: ' + test) in result.stderr, (name, result.stdout, result.stderr)
            print(name + ': REJECTED')
    print('PASS: 9 wrong-implementation controls rejected; no Gradle/Docker invocation')


if __name__ == '__main__':
    if sys.argv[1:] == ['--mutants']: mutants()
    else: unittest.main(verbosity=2)
