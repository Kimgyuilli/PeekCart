#!/usr/bin/env python3
"""Exercise the actual harness entrypoint in disposable git fixtures, without ops."""
import hashlib
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(os.environ.get('HARNESS_CONTEXT_TEST_SCRIPT', ROOT / 'scripts/harness-context.sh'))
TASKS = 'docs/TASKS.md'
DONE = 'docs/progress/TASKS-done.md'
HISTORY_FIXTURES = ROOT / 'scripts/fixtures/harness-context'
ACTIVE1 = '| D-900 | Docs | first fixture | source | 🔲 |\n'
ACTIVE2 = '| D-901 | Docs | second fixture | source | 🔲 |\n'
OLD1 = '| D-800 | Docs | first history | source | ✅ |\n'
OLD2 = '| D-801 | Docs | second history | source | ✅ |\n'
TASK_HEADER = '# Tasks\n## 현재 단계: Phase 5\n| ID | 영역 | 설명 | 근거 | 상태 |\n|---|---|---|---|---|\n'
DONE_HEADER = '# Done\n## 완료 행\n| ID | 영역 | 설명 | 근거 | 상태 |\n|---|---|---|---|---|\n'
FOOTER = '\n## Historical correction\nHistory stays here.\n'


class HarnessContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='harness-context-')
        self.root = Path(self.temp.name)
        self.write('scripts/harness-context.sh', SCRIPT.read_text())
        self.write(TASKS, TASK_HEADER + ACTIVE1 + ACTIVE2)
        self.write(DONE, DONE_HEADER + OLD1 + OLD2 + FOOTER)
        self.write('docs/adr/README.md', '# ADR\n| [0001](0001-example.md) | Example | Accepted |\n')
        self.write('docs/progress/PHASE5.md', '# Phase 5\n## 작업 이력\n## 2026-10-21 fixture\n')
        self.write('docs/plans/task-fixture.md', '# Fixture plan\n')
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.commit()
        self.env = os.environ.copy()

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf-8'))

    def read(self, path):
        return (self.root / path).read_bytes().decode('utf-8')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL)

    def commit(self):
        self.git('add', '--', 'docs')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@local', 'commit', '-qm', 'fixture')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def check(self, expected=0, base=False, extra=(), digest=False):
        command = ['bash', 'scripts/harness-context.sh']
        if not digest: command.append('--check')
        if base: command += ['--base', self.base if base is True else base]
        command += list(extra)
        result = subprocess.run(command, cwd=self.root, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        if expected == 3 and not digest: self.assertIn('파서 고장', result.stdout)
        return result

    def test_normal_standalone_explicitly_reports_no_base(self):
        result = self.check()
        self.assertIn('base 비교 미실행', result.stdout)
        self.assertNotIn('전체 완료', result.stdout)

    def test_normal_base_comparison_reports_its_scope(self):
        self.assertIn('명시 base ID 비교 통과', self.check(base=True).stdout)

    def test_digest_retains_status_and_plan_summary(self):
        result = self.check(digest=True)
        self.assertIn('완료 표기 행 2 · 열린 행 2', result.stdout)
        self.assertIn('진행 중 1개', result.stdout)

    def test_digest_lists_every_open_row_by_leading_status(self):
        # Picking known open marks hid partial rows; a later check mark in the status text is not completion.
        statuses = {'D-900': '◐ 부분 병합', 'D-901': '⏸', 'D-902': '🔄 P1 ✅ 뒤 진행',
                    'D-903': '◑ 범례 밖', 'D-904': '✅ 이동 전'}
        self.write(TASKS, TASK_HEADER + ''.join('| %s | Docs | fixture | source | %s |\n' % row
                                                for row in statuses.items()))
        result = self.check(digest=True)
        self.assertIn('## 열린 행 (4)', result.stdout)
        for ident, label in (('D-900', '부분'), ('D-901', '보류'), ('D-902', '진행중'), ('D-903', '◑')):
            self.assertIn('- [%s] Docs | %s\n' % (ident, label), result.stdout)
        self.assertNotIn('D-904', result.stdout)

    def test_empty_archive_and_digest_are_failures(self):
        self.write(DONE, '')
        self.check(3); self.check(3, digest=True)

    def test_missing_archive_is_failure(self):
        (self.root / DONE).unlink(); self.check(3)

    def test_header_only_archive_is_failure(self):
        self.write(DONE, DONE_HEADER + FOOTER); self.check(3)

    def test_duplicate_archive_id_is_failure(self):
        self.write(DONE, self.read(DONE) + OLD1); self.check(3)

    def test_indented_duplicate_id_is_not_ignored(self):
        self.write(DONE, self.read(DONE) + '  ' + OLD1)
        self.check(3)

    def test_optional_final_pipe_uses_actual_final_status(self):
        no_pipe = OLD1.rstrip().removesuffix('|').rstrip() + '\n'
        self.write(DONE, DONE_HEADER + no_pipe + OLD2 + FOOTER)
        self.check()
        self.write(DONE, DONE_HEADER + no_pipe.replace('✅', '🔲') + OLD2 + FOOTER)
        self.check(3)

    def test_extra_empty_final_status_is_not_a_border(self):
        for body in ('first history', '`a | b` and escaped a \\| b'):
            row = OLD1.replace('first history', body)
            for normal in (row, row.rstrip().removesuffix('|').rstrip() + '\n'):
                self.write(DONE, DONE_HEADER + normal + OLD2 + FOOTER)
                self.check(base=True)
            self.write(DONE, DONE_HEADER + row.rstrip('\n') + '|\n' + OLD2 + FOOTER)
            self.check(3, base=True)

    def test_escaped_pipe_cannot_supply_missing_columns(self):
        valid = (r'| D-800 | Docs | a \| b | source | ✅ |',
                 r'| D-800 | Docs \\| body | source | ✅ |')
        invalid = (r'| D-800 | a \| b \| c | ✅ |',
                   r'| D-800 | a \\\| b \\\| c | ✅ |')
        for row in valid:
            with self.subTest(valid=row):
                self.write(DONE, DONE_HEADER + row + '\n' + OLD2 + FOOTER)
                self.check(base=True)
        for row in invalid:
            with self.subTest(invalid=row):
                self.write(DONE, DONE_HEADER + row + '\n' + OLD2 + FOOTER)
                self.check(3, base=True)

    def workflow(self, state='false', query_rc=0, fetch_rc=0, tool_rc=0, base='fixture-base'):
        import yaml
        source = Path(os.environ.get('HARNESS_CONTEXT_TEST_WORKFLOW', ROOT / '.github/workflows/ci.yml'))
        steps = yaml.safe_load(source.read_text())['jobs']['lint']['steps']
        bodies = [s['run'] for s in steps if s.get('name') == 'Harness archive health']
        self.assertEqual(len(bodies), 1)
        self.write('workflow-bin/git', '''#!/bin/sh
printf 'git:%s\n' "$*" >> "$HARNESS_WORKFLOW_LOG"
case "$1" in
  rev-parse) printf '%s\n' "$HARNESS_TEST_SHALLOW"; exit "$HARNESS_TEST_QUERY_RC" ;;
  fetch) exit "$HARNESS_TEST_FETCH_RC" ;;
  *) exit 97 ;;
esac
''')
        stub = '''#!/bin/sh
printf 'tool:%s:%s\n' "${0##*/}" "$*" >> "$HARNESS_WORKFLOW_LOG"
exit "$HARNESS_TEST_TOOL_RC"
'''
        for tool in ('bash', 'python3'):
            self.write('workflow-bin/' + tool, stub)
        for path in (self.root / 'workflow-bin').iterdir():
            path.chmod(0o755)
        log = self.root / 'workflow-calls.txt'
        if log.exists(): log.unlink()
        env = self.env.copy()
        env.update(HARNESS_BASE=base, HARNESS_TEST_SHALLOW=state,
                   HARNESS_TEST_QUERY_RC=str(query_rc), HARNESS_TEST_FETCH_RC=str(fetch_rc),
                   HARNESS_TEST_TOOL_RC=str(tool_rc), HARNESS_WORKFLOW_LOG=str(log))
        env['PATH'] = str(self.root / 'workflow-bin') + os.pathsep + env['PATH']
        result = subprocess.run(['/bin/bash', '-c', bodies[0]], cwd=self.root, env=env,
                                text=True, capture_output=True)
        calls = log.read_text().splitlines() if log.exists() else []
        return result, calls

    def test_workflow_normal_shallow_and_full_states(self):
        expected_tools = ['tool:bash:scripts/harness-context.sh --check --base fixture-base',
                          'tool:python3:-B scripts/harness-context-selftest.py',
                          'tool:python3:-B docs/plans/evidence/D-099/check-mutants.py']
        for state in ('true', 'false'):
            with self.subTest(state=state):
                result, calls = self.workflow(state)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([c for c in calls if c.startswith('tool:')], expected_tools)
                self.assertEqual('git:fetch --unshallow --no-tags origin' in calls, state == 'true')

    def test_workflow_shallow_query_failure_stops_all_tools(self):
        result, calls = self.workflow(query_rc=23)
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(calls, ['git:rev-parse --is-shallow-repository'])

    def test_workflow_unshallow_fetch_failure_stops_all_tools(self):
        result, calls = self.workflow(state='true', fetch_rc=23)
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(calls, ['git:rev-parse --is-shallow-repository',
                                 'git:fetch --unshallow --no-tags origin'])

    def test_workflow_unexpected_query_output_is_not_full_state(self):
        for state in ('', 'unknown', 'true\nfalse'):
            with self.subTest(state=state):
                result, calls = self.workflow(state=state)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(calls, ['git:rev-parse --is-shallow-repository'])

    def test_workflow_empty_event_base_stops_before_query(self):
        result, calls = self.workflow(base='')
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(calls, [])

    def test_workflow_health_failure_stops_later_checks(self):
        result, calls = self.workflow(tool_rc=3)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual(calls, ['git:rev-parse --is-shallow-repository',
                                 'tool:bash:scripts/harness-context.sh --check --base fixture-base'])

    def test_cross_document_duplicate_is_failure(self):
        self.write(TASKS, self.read(TASKS) + OLD1); self.check(3)

    def test_wrong_archive_heading_is_failure(self):
        self.write(DONE, self.read(DONE).replace('## 완료 행', '## Unknown')); self.check(3)

    def test_wrong_archive_table_header_is_failure(self):
        self.write(DONE, self.read(DONE).replace('| ID |', '| Unknown |')); self.check(3)

    def test_malformed_debt_id_is_failure(self):
        self.write(DONE, self.read(DONE).replace('D-800', 'D-80')); self.check(3)

    def test_incomplete_archive_status_is_failure(self):
        self.write(DONE, self.read(DONE).replace('✅', '🔲', 1)); self.check(3)

    def test_relative_decrease_requires_explicit_base(self):
        self.write(DONE, DONE_HEADER + OLD1 + FOOTER)
        self.check()  # Shape alone cannot know whether a valid row was lost.
        self.assertIn('완료 archive 감소', self.check(3, base=True).stdout)

    def test_explicit_older_base_is_not_replaced_with_current_head(self):
        expected_base = self.base
        self.write(DONE, DONE_HEADER + OLD1 + FOOTER)
        self.commit()
        self.check(base=True)  # Current committed shape is internally healthy.
        self.check(3, base=expected_base)

    def test_replacement_id_does_not_hide_loss_at_same_count(self):
        self.write(DONE, self.read(DONE).replace('D-801', 'D-802'))
        self.check(); self.check(3, base=True)

    def test_active_id_loss_is_failure_with_base(self):
        self.write(TASKS, self.read(TASKS).replace(ACTIVE1, ''))
        self.check(); self.check(3, base=True)

    def test_archive_return_to_active_preserves_total_but_is_regression(self):
        self.write(DONE, self.read(DONE).replace(OLD1, ''))
        self.write(TASKS, self.read(TASKS) + OLD1.replace('✅', '🔲'))
        result = self.check(3, base=True)
        self.assertIn('완료 archive 감소: D-800', result.stdout)
        self.assertNotIn('task D-ID 소실', result.stdout)

    def test_new_active_debt_is_allowed(self):
        self.write(TASKS, self.read(TASKS) + ACTIVE1.replace('D-900', 'D-902'))
        self.check(base=True)

    def test_completion_move_and_new_archive_row_are_allowed(self):
        self.write(TASKS, self.read(TASKS).replace(ACTIVE1, ''))
        moved = ACTIVE1.replace('🔲', '✅ ([#777](https://github.com/x/y/pull/777))')
        self.write(DONE, DONE_HEADER + OLD1 + OLD2 + moved + FOOTER)
        self.check(base=True)

    def test_plan_archive_decrease_is_not_task_loss(self):
        target = self.root / 'docs/plans/done/task-fixture.md'
        target.parent.mkdir(parents=True)
        (self.root / 'docs/plans/task-fixture.md').rename(target)
        self.check(base=True)
        result = self.check(digest=True)
        self.assertIn('진행 중 0개 · 아카이브 1개', result.stdout)

    def test_no_active_debts_is_valid(self):
        self.write(TASKS, TASK_HEADER)
        self.check()  # Standalone does not infer a history or mark the debts complete.

    def test_unknown_base_does_not_fall_back_to_current(self):
        self.assertIn('base 조회 실패', self.check(3, base='missing-fixture-ref').stdout)

    def test_missing_base_archive_does_not_fall_back_to_current(self):
        original = self.read(DONE)
        (self.root / DONE).unlink(); self.commit(); self.write(DONE, original)
        self.check()
        self.assertIn('base 문서 조회 실패', self.check(3, base=True).stdout)

    def test_invalid_utf8_base_source_is_failure(self):
        original = self.read(DONE)
        (self.root / DONE).write_bytes(b'\xff')
        self.commit(); self.write(DONE, original)
        self.check()
        self.assertIn('base git 실행/읽기 실패', self.check(3, base=True).stdout)

    def test_unhealthy_base_is_not_a_valid_expected_population(self):
        original = self.read(DONE)
        self.write(DONE, DONE_HEADER); self.commit(); self.write(DONE, original)
        self.check(); self.check(3, base=True)

    def fake_git(self, subcommand):
        real = shutil.which('git')
        self.write('bin/git', '#!/bin/sh\nif [ "$1" = ' + shlex.quote(subcommand)
                   + ' ]; then exit 23; fi\nexec ' + shlex.quote(real) + ' "$@"\n')
        (self.root / 'bin/git').chmod(0o755)
        self.env['PATH'] = str(self.root / 'bin') + os.pathsep + self.env['PATH']

    def test_git_revision_failure_is_propagated(self):
        self.fake_git('rev-parse')
        self.assertIn('base 조회 실패(exit 23)', self.check(3, base=True).stdout)

    def test_git_source_failure_is_propagated(self):
        self.fake_git('show')
        self.assertIn('base 문서 조회 실패(exit 23)', self.check(3, base=True).stdout)

    def test_git_status_failure_is_not_ignored(self):
        self.fake_git('status')
        self.assertIn('git 조회 실패(exit 23)', self.check(3).stdout)

    def test_phase_and_invalid_cli_arguments(self):
        self.check(extra=('--phase', '5'))
        self.check(2, extra=('--phase', '../5'))
        self.check(2, extra=('--unknown',))
        self.check(2, extra=('--base', ''))
        self.check(2, base=True, digest=True)

    def test_missing_historical_objects_fail_instead_of_skipping(self):
        # Execute the historical control from a repository with no such git objects.
        target = self.root / 'scripts/harness-context-selftest.py'
        target.write_bytes(Path(__file__).read_bytes())
        # Byte fixtures are repository content; only the git object for the legacy checker is absent.
        shutil.copytree(HISTORY_FIXTURES, self.root / HISTORY_FIXTURES.relative_to(ROOT))
        environment = self.env.copy()
        environment['HARNESS_CONTEXT_TEST_SCRIPT'] = str(self.root / 'scripts/harness-context.sh')
        result = subprocess.run([sys.executable, '-B', str(target),
                                 'HarnessContextTest.test_original_d053_overwrite_and_recovery'],
                                cwd=self.root, env=environment, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('ERROR: test_original_d053_overwrite_and_recovery', result.stderr)
        self.assertIn('CalledProcessError', result.stderr)

    def test_original_d053_overwrite_and_recovery(self):
        # The original commits lived only on the deleted PR #171 branch, so their
        # docs/progress/TASKS-done.md bytes are kept as fixtures and pinned by git blob id.
        originals = {}
        for revision, fixture, blob, size, lines, debts in [
                ('d5f1a2ac^', 'TASKS-done-d5f1a2ac-parent.txt', '0d4de41cd15e3ee7784c189d5f7e2b35e72beed8', 67553, 59, 44),
                ('d5f1a2ac', 'TASKS-done-d5f1a2ac.txt', '1fe0093a6ffade1cf4be22626b51a221539ac4db', 875, 2, 1),
                ('bf678c0c', 'TASKS-done-bf678c0c.txt', '592412c4c64df8439dbb29cc8add981f93b4242d', 68427, 60, 45)]:
            raw = (HISTORY_FIXTURES / fixture).read_bytes()
            self.assertEqual(hashlib.sha1(b'blob %d\0' % len(raw) + raw).hexdigest(), blob, fixture)
            self.assertEqual((len(raw), len(raw.splitlines()), sum(l.startswith(b'| D-') for l in raw.splitlines())),
                             (size, lines, debts))
            originals[revision] = raw.decode('utf-8')
        self.write(DONE, originals['d5f1a2ac^']); self.commit(); self.check(base=True)
        self.write(DONE, originals['d5f1a2ac'])
        current_script = self.read('scripts/harness-context.sh')
        legacy = subprocess.check_output(['git', 'show',
            '4a4a36a12a3d914d6bc0379ded94bc0123c4d370:scripts/harness-context.sh'], cwd=ROOT).decode()
        self.write('scripts/harness-context.sh', legacy)
        self.check()  # Existing pre-D099 checker falsely accepts the exact historical overwrite bytes.
        self.write('scripts/harness-context.sh', current_script)
        self.check(3); self.check(3, base=True)
        self.write(DONE, originals['bf678c0c']); self.check(); self.check(base=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
