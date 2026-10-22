#!/usr/bin/env python3
"""External fixtures for four actual lint entrypoints, wiring, and CI failure propagation."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ['notification-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway']
OVERLAYS = ['minikube', 'gke', 'gke-d002a', 'gke-d002bc', 'gke-d002a-mysql-500m', 'gke-d002a-mysql-2000m', 'gke-d002bc-mysql-2000m', 'gke-d078-contention']


class HealthTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='lint-health-'); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, path, text):
        path = self.root / path; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
        return path

    def copy(self, name):
        self.write(name, (ROOT / name).read_text())

    def lint(self, name, status, diagnostic=''):
        env = os.environ.copy(); env.update(IMAGE_CONTRACT_TRANSITION='0', GITHUB_REPOSITORY_OWNER='kimgyuilli')
        p = subprocess.run(['bash', str(self.root / 'scripts' / name)], cwd=self.root, env=env, text=True, capture_output=True)
        self.assertEqual(p.returncode, status, p.stdout + p.stderr)
        if diagnostic: self.assertIn(diagnostic, p.stdout + p.stderr)

    def test_scheduler(self):
        name = 'scheduler-lock-contract-lint.sh'; self.copy('scripts/' + name)
        java = 'order-service/src/main/java/Job.java'
        baseline = '@Scheduled(fixedDelay = 1000)\n@SchedulerLock(name = "fixture")\nvoid run() {}\n'
        self.write(java, baseline)
        config = 'spring:\n  task:\n    scheduling:\n      pool:\n        size: 4\n'
        for svc in ['order-service', 'product-service', 'payment-service', 'notification-service']:
            self.write(svc + '/src/main/resources/application.yml', config)
        self.lint(name, 0)
        for value, tag in [(baseline.replace('@SchedulerLock', '//@SchedulerLock'), 'SCHED-001'),
                           (baseline.replace('fixedDelay', 'fixedRate'), 'SCHED-002'),
                           (baseline.replace('@SchedulerLock(name = "fixture")\n', ''), 'SCHED-001')]:
            self.write(java, value); self.lint(name, 1, tag)
        self.write(java, baseline)
        self.write('order-service/src/main/resources/application.yml', 'spring: {}\n'); self.lint(name, 1, 'SCHED-003')
        self.write('order-service/src/main/resources/application.yml', config)
        self.write(java, 'class Empty {}\n'); self.lint(name, 1, '0건')

    def test_image(self):
        name = 'image-contract-lint.sh'; self.copy('scripts/' + name)
        workflow = 'jobs:\n' + ''.join('  ' + job + ':\n    strategy:\n      matrix:\n        service:\n' + ''.join('          - ' + s + '\n' for s in SERVICES) for job in ['images', 'publish'])
        self.write('.github/workflows/ci.yml', workflow)
        gke = 'images:\n' + ''.join('  - name: ghcr.io/kimgyuilli/peekcart-' + s + '\n' for s in SERVICES)
        self.write('k8s/overlays/gke/kustomization.yml', gke)
        for svc in SERVICES:
            self.write('k8s/base/services/' + svc + '/deployment.yml', 'image: ghcr.io/kimgyuilli/peekcart-' + svc + ':latest\n')
        self.lint(name, 0)
        self.write('.github/workflows/ci.yml', workflow.replace('          - gateway\n', '')); self.lint(name, 1, 'canonical')
        self.write('.github/workflows/ci.yml', workflow)
        dep = 'k8s/base/services/gateway/deployment.yml'
        self.write(dep, 'image: ghcr.io/kimgyuilli/wrong:latest\n'); self.lint(name, 1, 'image mismatch')
        self.write(dep, 'image: ghcr.io/kimgyuilli/peekcart-gateway:latest\n')
        self.write('k8s/overlays/gke/kustomization.yml', gke.replace('  - name: ghcr.io/kimgyuilli/peekcart-gateway\n', '')); self.lint(name, 1, 'gke')
        self.write('k8s/overlays/gke/kustomization.yml', gke)
        (self.root / dep).unlink(); self.lint(name, 1, '추출 실패')

    def test_namespace(self):
        name = 'kustomize-namespace-lint.sh'; self.copy('scripts/' + name)
        resource = 'apiVersion: v1\nkind: Service\nmetadata:\n  name: fixture\n  namespace: peekcart\nspec:\n  ports:\n    - port: 80\n'
        for overlay in OVERLAYS:
            self.write('k8s/overlays/' + overlay + '/kustomization.yaml', 'resources:\n  - service.yml\n')
            self.write('k8s/overlays/' + overlay + '/service.yml', resource)
        self.lint(name, 0)
        path = 'k8s/overlays/gke/service.yml'
        self.write(path, resource.replace('  namespace: peekcart\n', '')); self.lint(name, 1, 'namespace missing')
        self.write(path, resource.replace('peekcart', 'default')); self.lint(name, 1, 'namespace mismatch')
        self.write(path, resource)
        (self.root / 'k8s/overlays/gke/kustomization.yaml').unlink(); self.lint(name, 1, 'kustomize failed')

    def test_ssot(self):
        name = 'observability-ssot-lint.sh'; self.copy('scripts/' + name)
        def config(svc):
            return 'management:\n  metrics:\n    tags:\n      application: ' + svc + '\n  endpoints:\n    web:\n      exposure:\n        include: health,prometheus\n'
        for svc in SERVICES: self.write(svc + '/src/main/resources/application.yml', config(svc))
        self.write('peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java', 'class MetricsConfig { MeterFilter owned; }\n')
        self.lint(name, 0)
        base = 'order-service/src/main/resources/application.yml'
        self.write(base, config('wrong')); self.lint(name, 1, '[D5-V2]')
        self.write(base, config('order-service'))
        profile = self.write('order-service/src/main/resources/application-k8s.yml', config('order-service')); self.lint(name, 1, '[D5-V1]')
        profile.unlink()
        self.write(base, 'management: {}\n'); self.lint(name, 1, 'SSOT key absent')
        (self.root / base).unlink(); self.lint(name, 1, '집합 불일치')
        self.write(base, config('order-service'))
        duplicate = self.write('order-service/src/main/java/Other.java', 'class Other { MeterFilter duplicate; }\n'); self.lint(name, 1, 'Duplicate declaration')
        duplicate.write_text('// MeterFilter is deliberately owned elsewhere\nclass Other {}\n'); self.lint(name, 0)

    def wiring_fixture(self):
        for path in (ROOT / 'scripts').glob('*-lint.sh'): self.copy('scripts/' + path.name)
        for path in ['scripts/ci-lint-wiring-lint.py', 'scripts/ci-lint-health-selftest.py',
                     '.github/workflows/ci.yml', '.claude/commands/ship.md']:
            self.copy(path)
        return yaml.safe_load((self.root / '.github/workflows/ci.yml').read_text())

    def wiring(self, status, diagnostic=''):
        p = subprocess.run([sys.executable, '-B', str(self.root / 'scripts/ci-lint-wiring-lint.py')], cwd=self.root, text=True, capture_output=True)
        self.assertEqual(p.returncode, status, p.stdout + p.stderr)
        if diagnostic: self.assertIn(diagnostic, p.stdout + p.stderr)

    def test_wiring_cli(self):
        workflow = self.wiring_fixture(); self.wiring(0)
        original = (self.root / '.github/workflows/ci.yml').read_text()
        target = 'bash scripts/scheduler-lock-contract-lint.sh'
        fakes = ['', '# ' + target, 'echo ' + target, 'echo "' + target + '"',
                 "cat <<'UNUSED'\n" + target + '\nUNUSED', 'printf "%s" "quoted\n' + target + '\ntext"',
                 'if false; then\n' + target + '\nfi']
        for fake in fakes:
            with self.subTest(fake=fake):
                workflow = yaml.safe_load(original)
                step = next(s for s in workflow['jobs']['lint']['steps'] if s.get('name') == 'Run CI policy lints')
                step['run'] = step['run'].replace(target, fake)
                self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False))
                self.wiring(1, 'scheduler-lock-contract-lint.sh: CI direct consumer missing')
        for key, value in [('if', False), ('if', '${{ false }}'), ('continue-on-error', True)]:
            workflow = yaml.safe_load(original)
            step = next(s for s in workflow['jobs']['lint']['steps'] if s.get('name') == 'Run CI policy lints'); step[key] = value
            self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False)); self.wiring(1, 'scheduler-lock-contract-lint.sh: CI direct consumer missing')
        workflow = yaml.safe_load(original); workflow['jobs']['lint']['if'] = False
        self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False)); self.wiring(1, 'scheduler-lock-contract-lint.sh: CI direct consumer missing')
        self.write('.github/workflows/ci.yml', original.replace('bash scripts/mysql-image-lint.sh --self-test', '# removed'))
        self.wiring(1, 'mysql-image-lint.sh: CI inline selftest missing')
        self.write('.github/workflows/ci.yml', original)
        fresh = self.write('scripts/future-lint.sh', '#!/bin/bash\nexit 0\n'); self.wiring(1, 'future-lint.sh: CI direct consumer missing')
        workflow = yaml.safe_load(original)
        workflow['jobs']['lint']['steps'].append({'run': 'bash scripts/future-lint.sh\nbash scripts/future-lint.sh --self-test'})
        self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False)); self.wiring(0)
        workflow['jobs']['lint']['steps'][-1]['run'] = 'bash scripts/future-lint.sh'
        self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False)); self.wiring(1, 'future-lint.sh: CI inline selftest missing')
        fresh.unlink(); self.write('.github/workflows/ci.yml', original)

        self.write('.claude/commands/ship.md', '# No ship consumer\n'); self.wiring(1, 'writing-lint.sh: ship-only consumer missing')
        self.copy('.claude/commands/ship.md')
        self.write('.github/workflows/ci.yml', 'jobs: null\n'); self.wiring(2, 'workflow jobs missing')

    def test_unsupported_preambles_fail_closed(self):
        workflow = self.wiring_fixture()
        step = next(s for s in workflow['jobs']['lint']['steps'] if s.get('name') == 'Run CI policy lints')
        body = step['run']
        binary = self.root / 'prefix-bin'; binary.mkdir(); log = self.root / 'prefix-calls'
        for name in ['bash', 'python3']:
            p = binary / name; p.write_text('#!/bin/sh\necho called >> "$LINT_PREFIX_CALLS"\n'); p.chmod(0o755)
        p = binary / 'grep'; p.write_text('#!/bin/sh\nexit 1\n'); p.chmod(0o755)
        env = os.environ.copy(); env.update(PATH=str(binary) + os.pathsep + env['PATH'], LINT_PREFIX_CALLS=str(log))
        variants = ['PRE=1 exit 0\n' + body, 'PRE=1 exec true\n' + body,
                    'builtin exit 0\n' + body, 'unused(){\n' + body + '\n}\n',
                    'unused (){\n' + body + '\n}\n']
        positive = body.replace('python3 -B scripts/ci-lint-wiring-lint.py', 'PRE=1 python3 -B scripts/ci-lint-wiring-lint.py')
        for value in [positive, *variants]:
            with self.subTest(preamble=value.splitlines()[0]):
                if log.exists(): log.unlink()
                actual = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', value], cwd=self.root, env=env, text=True, capture_output=True)
                self.assertEqual(actual.returncode, 0, actual.stderr)
                self.assertEqual(log.exists(), value == positive)
                step['run'] = value
                self.write('.github/workflows/ci.yml', yaml.safe_dump(workflow, sort_keys=False))
                self.wiring(0 if value == positive else 1)

    def test_policy_failure_propagation(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        body = next(s['run'] for s in workflow['jobs']['lint']['steps'] if s.get('name') == 'Run CI policy lints')
        binary = self.root / 'bin'; binary.mkdir(); log = self.root / 'calls'
        stub = '#!/bin/sh\necho "${0##*/}:$*" >> "$LINT_HEALTH_CALLS"\nif [ "$*" = "$LINT_HEALTH_FAIL" ]; then exit 23; fi\nexit 0\n'
        for name in ['bash', 'python3']:
            p = binary / name; p.write_text(stub); p.chmod(0o755)
        p = binary / 'grep'; p.write_text('#!/bin/sh\nexit 1\n'); p.chmod(0o755)
        children = ['-B scripts/ci-lint-wiring-lint.py', '-B scripts/ci-lint-health-selftest.py']
        for fail in ['none', *children]:
            if log.exists(): log.unlink()
            env = os.environ.copy(); env.update(PATH=str(binary) + os.pathsep + env['PATH'], LINT_HEALTH_CALLS=str(log), LINT_HEALTH_FAIL=fail)
            p = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', body], cwd=self.root, env=env, text=True, capture_output=True)
            self.assertEqual(p.returncode, 0 if fail == 'none' else 23, p.stdout + p.stderr)
            calls = log.read_text().splitlines()
            if fail == 'none':
                for child in children: self.assertIn('python3:' + child, calls)
            else: self.assertEqual(calls[-1], 'python3:' + fail)


def poisons():
    targets = [('scheduler-lock-contract-lint.sh', 'test_scheduler'), ('image-contract-lint.sh', 'test_image'),
               ('kustomize-namespace-lint.sh', 'test_namespace'), ('observability-ssot-lint.sh', 'test_ssot'),
               ('ci-lint-wiring-lint.py', 'test_wiring_cli')]
    with tempfile.TemporaryDirectory(prefix='lint-health-poisons-') as temp:
        root = Path(temp); (root / 'scripts').mkdir()
        for path in (ROOT / 'scripts').glob('*-lint.sh'): shutil.copyfile(path, root / 'scripts' / path.name)
        for path in ['scripts/ci-lint-health-selftest.py', 'scripts/ci-lint-wiring-lint.py', '.github/workflows/ci.yml', '.claude/commands/ship.md']:
            dest = root / path; dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(ROOT / path, dest)
        for name, test in targets:
            original = (ROOT / 'scripts' / name).read_text()
            for status in [0, 1]:
                prefix = 'import sys; sys.exit(' + str(status) + ')\n' if name.endswith('.py') else '#!/bin/bash\necho poisoned\nexit ' + str(status) + '\n'
                path = root / 'scripts' / name; path.write_text(prefix + original)
                p = subprocess.run([sys.executable, '-B', str(root / 'scripts/ci-lint-health-selftest.py'), 'HealthTest.' + test], text=True, capture_output=True)
                if p.returncode == 0 or ('FAIL: ' + test) not in p.stderr:
                    raise AssertionError(name + ' poison ' + str(status) + ' escaped fixture: ' + p.stdout + p.stderr)
                print(name + ' early-exit' + str(status) + ': REJECTED')
            path.write_text(original)
        original = (ROOT / 'scripts/ci-lint-wiring-lint.py').read_text()
        changed = original.replace('            break\n', '            continue\n')
        assert changed != original, 'unsupported-preamble mutation source drift'
        (root / 'scripts/ci-lint-wiring-lint.py').write_text(changed)
        test = 'test_unsupported_preambles_fail_closed'
        p = subprocess.run([sys.executable, '-B', str(root / 'scripts/ci-lint-health-selftest.py'), 'HealthTest.' + test], text=True, capture_output=True)
        if p.returncode == 0 or ('FAIL: ' + test) not in p.stderr:
            raise AssertionError('unsupported preamble skipped: ' + p.stdout + p.stderr)
        print('unsupported-preamble-skipped: REJECTED')
    print('LINT-HEALTH: 11 poisoned implementations rejected; four actual shell lints plus wiring CLI')


if __name__ == '__main__':
    result = unittest.main(verbosity=2, exit=False).result
    if not result.wasSuccessful(): sys.exit(1)
    if len(sys.argv) == 1: poisons()
