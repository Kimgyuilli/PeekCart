#!/usr/bin/env python3
"""Kill wrong health/base implementations using the real harness CLI fixtures."""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'scripts/harness-context.sh').read_text()
mutants = [
    ('empty-archive-allowed', 'archived_debts = archive_health(archive, archive_path)',
     'archived_debts = debt_rows(archive, archive_path)', 'test_empty_archive_and_digest_are_failures'),
    ('duplicate-id-allowed', "unique_ids(active_debts + archived_debts, '현행 task 문서')", 'pass',
     'test_duplicate_archive_id_is_failure'),
    ('extra-empty-status-hidden', 'text = text[:-1]', "text = text.rstrip('|')",
     'test_extra_empty_final_status_is_not_a_border'),
    ('escaped-pipe-counted-as-column', 'len(cells(line)) < 5',
     "len(line.strip().strip('|').split('|')) < 5", 'test_escaped_pipe_cannot_supply_missing_columns'),
    ('base-ignored', 'if args.base:\n', 'if False:\n', 'test_relative_decrease_requires_explicit_base'),
    ('requested-base-replaced-by-head', "args.base + '^{commit}'", "'HEAD^{commit}'",
     'test_explicit_older_base_is_not_replaced_with_current_head'),
    ('bad-revision-ignored', "problems.append('base 조회 실패(exit %s): %s' % (revision.returncode, args.base))",
     'pass', 'test_unknown_base_does_not_fall_back_to_current'),
    ('bad-source-ignored', "problems.append('base 문서 조회 실패(exit %s): %s:%s' % (result.returncode, base, path))",
     'pass', 'test_git_source_failure_is_propagated'),
    ('archive-return-to-active-allowed', "archive_lost = {key for key, _ in old_archive} - {key for key, _ in archived_debts}",
     'archive_lost = set()', 'test_archive_return_to_active_preserves_total_but_is_regression'),
    ('undated-frame-headings-counted', '[h for h in heads if entry_date(h)]', 'list(heads)',
     'test_digest_empty_work_history_reports_none_without_frame_headings'),
    ('always-success', 'sys.exit(3)', 'sys.exit(0)', 'test_header_only_archive_is_failure'),
    ('always-reject', "if mode == '--check':", "sys.exit(3)\nif mode == '--check':",
     'test_normal_standalone_explicitly_reports_no_base'),
]
workflow_source = (ROOT / '.github/workflows/ci.yml').read_text()
workflow_mutants = [
    ('shallow-query-failure-ignored', 'exit "$harness_query_rc"', ':',
     'test_workflow_shallow_query_failure_stops_all_tools'),
    ('unshallow-fetch-failure-ignored', 'git fetch --unshallow --no-tags origin || exit "$?"',
     'git fetch --unshallow --no-tags origin || true', 'test_workflow_unshallow_fetch_failure_stops_all_tools'),
    ('unexpected-shallow-output-accepted',
     '*) echo "Unexpected shallow state: $harness_shallow" >&2; exit 2 ;;', '*) : ;;',
     'test_workflow_unexpected_query_output_is_not_full_state'),
    ('empty-event-base-accepted',
     '[ -n "${HARNESS_BASE:-}" ] || { echo \'HARNESS_BASE is empty\' >&2; exit 2; }', ':',
     'test_workflow_empty_event_base_stops_before_query'),
    ('health-failure-ignored', 'bash scripts/harness-context.sh --check --base "$HARNESS_BASE"',
     'bash scripts/harness-context.sh --check --base "$HARNESS_BASE" || true',
     'test_workflow_health_failure_stops_later_checks'),
]
with tempfile.TemporaryDirectory(prefix='d099-mutants-') as directory:
    for name, old, new, test in mutants:
        assert old in source, 'mutation source drift: ' + name
        mutated = Path(directory) / (name + '.sh')
        mutated.write_text(source.replace(old, new))
        environment = os.environ.copy()
        environment['HARNESS_CONTEXT_TEST_SCRIPT'] = str(mutated)
        result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/harness-context-selftest.py'),
                                 'HarnessContextTest.' + test], env=environment, text=True, capture_output=True)
        assert result.returncode != 0 and 'FAIL: ' + test in result.stderr, (name, result.stdout, result.stderr)
        print(name + ': REJECTED by ' + test)
    for name, old, new, test in workflow_mutants:
        assert old in workflow_source, 'workflow mutation source drift: ' + name
        mutated = Path(directory) / (name + '.yml')
        mutated.write_text(workflow_source.replace(old, new))
        environment = os.environ.copy()
        environment['HARNESS_CONTEXT_TEST_WORKFLOW'] = str(mutated)
        result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/harness-context-selftest.py'),
                                 'HarnessContextTest.' + test], env=environment, text=True, capture_output=True)
        assert result.returncode != 0 and 'FAIL: ' + test in result.stderr, (name, result.stdout, result.stderr)
        print(name + ': REJECTED by ' + test)
print('PASS: 12 CLI and 5 exact-workflow wrong-implementation controls rejected; source worktree untouched.')
