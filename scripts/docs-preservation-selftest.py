#!/usr/bin/env python3
"""Isolated git fixtures for completion, move/split/merge and link preservation."""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECK = Path(__file__).with_name('docs-preservation-check.py').resolve()
TASKS = 'docs/TASKS.md'
DONE = 'docs/progress/TASKS-done.md'
ROW1 = '| D-021 | docs | body with `a | b` | origin | 🔲 |\n'
ROW2 = '| D-022 | docs | second body | origin | 🔲 |\n'
OLDROW = '| D-020 | docs | history body | origin | ✅ |\n'
FOOTER = '\n## Historical correction\nRepeated history.\nRepeated history.\n'
SOURCE = '# Source\n\nRepeated substantive source content.\nRepeated substantive source content.\n## Section\n[guide](guide.md#guide)\nLast source line.\n'
EVIDENCE = 'docs/evidence-old/run-77'
MOVED = 'docs/evidence-new/run-77'


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


class PreservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='docs-preservation-fixture-')
        self.root = Path(self.temporary.name)
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        for path, text in {
            TASKS: '# Active\n' + ROW1 + ROW2,
            DONE: '# Done\n' + OLDROW + FOOTER,
            'docs/source.md': SOURCE,
            'docs/guide.md': '# Guide\n\n## Other\n',
            'docs/index.md': '[source](source.md#section)\n',
            'docs/ordinary.md': 'Ordinary current documentation.\n',
            'docs/plans/done/history.md': '# Protected\nHistory sentence.\nHistory sentence.\n',
            'docs/a.md': '# A\n## Overview\nOriginal A body.\n',
            'docs/b.md': '# B\n## Overview\nOriginal B body.\n',
        }.items(): self.write(path, text)
        self.git('add', '--', 'docs')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@local', 'commit', '-qm', 'fixture baseline')
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.manifest = None

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.check_output(['git', *arguments], cwd=self.root, text=True, stderr=subprocess.DEVNULL)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf-8'))

    def read(self, path):
        return (self.root / path).read_bytes().decode('utf-8')

    def check(self, passes=True, reason=None):
        command = [sys.executable, '-B', str(CHECK), '--root', str(self.root), '--base', self.base]
        if self.manifest:
            self.write('manifest.json', json.dumps(self.manifest))
            command += ['--manifest', str(self.root / 'manifest.json')]
        result = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0 if passes else 1, result.stdout + result.stderr)
        if not passes: self.assertIn('[preservation] FAIL:', result.stderr)
        if reason: self.assertIn(reason, result.stderr)

    def move_task(self):
        self.write(TASKS, '# Active\n' + ROW2)
        moved = ROW1.rsplit('|', 2)[0] + '| ✅ ([#777](https://github.com/x/y/pull/777)) |\n'
        self.write(DONE, '# Done\n' + OLDROW + moved + FOOTER)

    def transfer(self, sources, destinations):
        self.manifest = {'version': 1, 'sources': [
            {'path': path, 'sha256': digest(self.read(path))} for path in sources], 'destinations': destinations}
        texts = {path: self.read(path).splitlines(keepends=True) for path in sources}
        for path in sources: (self.root / path).unlink()
        for destination in destinations:
            text = ''.join(part['literal'] if 'literal' in part else
                           ''.join(texts[part['source']][part['start'] - 1:part['end']])
                           for part in destination['parts'])
            self.write(destination['path'], text)

    def move_source(self):
        count = len(self.read('docs/source.md').splitlines(keepends=True))
        target = 'docs/plans/done/source.md'
        self.transfer(['docs/source.md'], [{'path': target, 'parts': [
            {'source': 'docs/source.md', 'start': 1, 'end': count}]}])
        self.write(target, self.read(target).replace('(guide.md#guide)', '(../../guide.md#guide)'))
        self.write('docs/index.md', '[source](plans/done/source.md#section)\n')
        return target

    def test_noop_and_ordinary_document_edit(self):
        self.write('docs/ordinary.md', 'A legitimate current-document revision.\n')
        self.check()

    def test_completed_row_normal_body_pipe_and_footer(self):
        self.move_task(); self.check()

    def test_duplicate_id_rejected(self):
        self.write(TASKS, self.read(TASKS) + ROW1); self.check(False)

    def test_row_loss_rejected(self):
        self.write(TASKS, '# Active\n' + ROW2); self.check(False)

    def test_moved_body_change_rejected(self):
        self.move_task(); self.write(DONE, self.read(DONE).replace('body with', 'rewritten body with')); self.check(False)

    def test_remaining_row_order_rejected(self):
        self.write(TASKS, '# Active\n' + ROW2 + ROW1); self.check(False)

    def test_moved_rows_keep_source_order(self):
        completed = lambda row: row.rsplit('|', 2)[0] + '| ✅ ([#777](https://github.com/x/y/pull/777)) |\n'
        self.write(TASKS, '# Active\n')
        self.write(DONE, '# Done\n' + OLDROW + completed(ROW1) + completed(ROW2) + FOOTER)
        self.check()
        self.write(DONE, '# Done\n' + OLDROW + completed(ROW2) + completed(ROW1) + FOOTER)
        self.check(False)

    def test_done_prepend_rejected(self):
        self.move_task()
        rows = self.read(DONE).splitlines(keepends=True)
        rows[1], rows[2] = rows[2], rows[1]
        self.write(DONE, ''.join(rows)); self.check(False)

    def test_done_wrong_destination_rejected(self):
        self.write(TASKS, self.read(TASKS) + OLDROW)
        self.write(DONE, '# Done\n' + FOOTER); self.check(False)

    def test_missing_completion_evidence_rejected(self):
        self.move_task(); self.write(DONE, self.read(DONE).replace('✅ ([#777](https://github.com/x/y/pull/777))', '✅')); self.check(False)

    def test_repeated_footer_deletion_rejected(self):
        self.move_task(); self.write(DONE, self.read(DONE).replace('Repeated history.\n', '', 1)); self.check(False)

    def test_historical_append_is_allowed(self):
        self.write(DONE, self.read(DONE) + '\n## Later correction\nNew evidence.\n')
        self.write('docs/plans/done/history.md', self.read('docs/plans/done/history.md') + '\n## Update Log\nNew context.\n')
        self.check()

    def test_protected_history_reorder_rejected(self):
        self.write('docs/plans/done/history.md', 'History sentence.\n# Protected\nHistory sentence.\n'); self.check(False)

    def test_move_relative_link_and_inbound_rewrite(self):
        self.move_source(); self.check()

    def test_repeated_source_line_deletion_rejected(self):
        path = self.move_source()
        self.write(path, self.read(path).replace('Repeated substantive source content.\n', '', 1)); self.check(False)

    def test_destination_order_change_rejected(self):
        path = self.move_source()
        lines = self.read(path).splitlines(keepends=True)
        lines[0], lines[-1] = lines[-1], lines[0]
        self.write(path, ''.join(lines)); self.check(False)

    def test_wrong_valid_link_target_rejected(self):
        path = self.move_source()
        self.write(path, self.read(path).replace('../../guide.md#guide', '../../guide.md#other')); self.check(False)

    def test_missing_link_target_rejected(self):
        path = self.move_source()
        self.write(path, self.read(path).replace('../../guide.md', '../../missing.md')); self.check(False)

    def test_missing_anchor_rejected(self):
        path = self.move_source()
        self.write(path, self.read(path).replace('#guide', '#missing')); self.check(False)

    def test_inbound_not_retargeted_rejected(self):
        self.move_source(); self.write('docs/index.md', '[source](source.md#section)\n'); self.check(False)

    def test_split_and_ownership_control(self):
        self.transfer(['docs/source.md'], [
            {'path': 'docs/left.md', 'parts': [{'source': 'docs/source.md', 'start': 1, 'end': 4}]},
            {'path': 'docs/right.md', 'parts': [{'source': 'docs/source.md', 'start': 5, 'end': 7}]}])
        self.write('docs/index.md', '[source](right.md#section)\n')
        self.check()
        left, right = self.read('docs/left.md'), self.read('docs/right.md')
        self.write('docs/left.md', right); self.write('docs/right.md', left)
        self.check(False)

    def test_manifest_source_line_order_rejected(self):
        self.transfer(['docs/source.md'], [{'path': 'docs/target.md', 'parts': [
            {'source': 'docs/source.md', 'start': 5, 'end': 7},
            {'source': 'docs/source.md', 'start': 1, 'end': 4}]}])
        self.check(False)

    def test_manifest_missing_or_duplicate_line_rejected(self):
        path = self.move_source()
        self.manifest['destinations'][0]['parts'][0]['end'] = 6
        self.check(False)
        self.manifest['destinations'][0]['parts'][0]['end'] = 7
        self.manifest['destinations'][0]['parts'].append({'source': 'docs/source.md', 'start': 7, 'end': 7})
        self.check(False)

    def test_merge_duplicate_heading_routes(self):
        self.write('docs/index.md', '[B overview](b.md#overview)\n')
        self.git('add', '--', 'docs/index.md')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@local', 'commit', '-qm', 'inbound fixture')
        self.base = self.git('rev-parse', 'HEAD').strip()
        self.transfer(['docs/a.md', 'docs/b.md'], [{'path': 'docs/merged.md', 'parts': [
            {'source': 'docs/a.md', 'start': 1, 'end': 3}, {'source': 'docs/b.md', 'start': 1, 'end': 3}]}])
        self.write('docs/index.md', '[B overview](merged.md#overview-1)\n')
        self.check()
        self.write('docs/index.md', '[B overview](merged.md#overview)\n')
        self.check(False)

    def install_archive_fixture(self):
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for filename in ('plans-archive.sh', 'docs-preservation-check.py', 'docs-archive-apply.py', 'docs-preservation-ci.py'):
            (scripts / filename).write_bytes((CHECK.parent / filename).read_bytes())
        self.write('docs/plans/task-merged.md', '# Plan\nOriginal statement.\n'
                   'PR: https://github.com/x/y/pull/777\n[guide](../guide.md#guide)\n')
        self.write('docs/plans/task-merged.audit.md', '# Audit\nPR: https://github.com/x/y/pull/777\n'
                   'Repeated audit.\nRepeated audit.\n')
        self.write('docs/plans/task-partial.md', '# Partial\nPR: https://github.com/x/y/pull/777\n- [ ] Remaining scope\n')
        self.write('docs/plans/task-open.md', '# Open\nPR: https://github.com/x/y/pull/888\n')
        self.write('docs/index.md', '[plan](plans/task-merged.md#plan)\n')
        self.write('docs/plans/task-hold.md', '---\narchive: hold 777\n---\n# Hold\nPR: https://github.com/x/y/pull/777\n')
        self.write('bin/gh', '#!/bin/sh\nprintf "777 MERGED\\n888 OPEN\\n"\n')
        (self.root / 'bin/gh').chmod(0o755)
        self.commit_changes()

    def archive(self, selections='task-merged', extra=()):
        command = ['bash', str(self.root / 'scripts/plans-archive.sh'), '--apply']
        if selections is not None:
            command += ['--select', selections, '--base', self.base,
                        '--manifest', 'docs/preservation/fixture.json']
        return subprocess.run(command + list(extra), cwd=self.root, text=True, capture_output=True,
                              env={**os.environ, 'PATH': str(self.root / 'bin') + ':' + os.environ['PATH']})

    def test_selected_archive_and_manifest_ci(self):
        self.install_archive_fixture()
        audit = 'docs/plans/task-merged.audit.md'
        self.write(audit, self.read(audit) + '\n## Later evidence\nFixture append.\n')
        partial = self.read('docs/plans/task-partial.md')
        result = self.archive()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / 'docs/plans/task-merged.md').exists())
        self.assertIn('(../../guide.md#guide)', self.read('docs/plans/done/task-merged.md'))
        self.assertTrue(self.read('docs/plans/done/task-merged.audit.md').endswith('Fixture append.\n'))
        self.assertEqual(self.read('docs/plans/task-partial.md'), partial)
        self.assertIn('plans/done/task-merged.md#plan', self.read('docs/index.md'))
        self.git('add', '--', 'docs')
        result = subprocess.run([sys.executable, '-B', str(CHECK.with_name('docs-preservation-ci.py')),
                                 '--root', str(self.root), '--base', self.base], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_blanket_archive_apply_rejected_without_mutation(self):
        self.install_archive_fixture()
        result = self.archive(None)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertTrue((self.root / 'docs/plans/task-merged.md').exists())
        self.assertFalse((self.root / 'docs/plans/done/task-merged.md').exists())

    def test_unmerged_selection_stops_all_archive_mutations(self):
        self.install_archive_fixture()
        result = self.archive('task-merged,task-open')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertTrue((self.root / 'docs/plans/task-merged.md').exists())
        self.assertFalse((self.root / 'docs/plans/done/task-merged.md').exists())

    def test_hold_selection_stops_all_archive_mutations(self):
        self.install_archive_fixture()
        result = self.archive('task-merged,task-hold')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertTrue((self.root / 'docs/plans/task-hold.md').exists())
        self.assertFalse((self.root / 'docs/plans/done/task-merged.md').exists())

    def test_archive_nonappend_edit_needs_narrow_allowance(self):
        self.install_archive_fixture()
        plan = 'docs/plans/task-merged.md'
        original = self.read(plan)
        self.write(plan, original.replace('Original statement.', 'Updated statement.'))
        result = self.archive()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertTrue((self.root / plan).exists())
        self.assertFalse((self.root / 'docs/plans/done/task-merged.md').exists())
        allowance = {'version': 1, 'corrections': [{'path': plan, 'base_sha256': digest(original),
            'old': 'Original statement.', 'new': 'Updated statement.', 'old_sha256': digest('Original statement.'),
            'reason': 'Narrow fixture fact correction'}]}
        self.write('docs/preservation/fixture.json', json.dumps(allowance))
        result = self.archive()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_ci_rejects_undeclared_archive(self):
        self.install_archive_fixture()
        destination = self.root / 'docs/plans/done/task-merged.md'
        (self.root / 'docs/plans/task-merged.md').rename(destination)
        self.git('add', '--', 'docs')
        result = subprocess.run([sys.executable, '-B', str(CHECK.with_name('docs-preservation-ci.py')),
                                 '--root', str(self.root), '--base', self.base], text=True, capture_output=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('needs a source/destination manifest', result.stderr)

    def correction(self, old, new, path='docs/progress/TASKS-done.md'):
        self.manifest = {'version': 1, 'corrections': [{'path': path, 'base_sha256': digest(self.read(path)),
            'old': old, 'new': new, 'old_sha256': digest(old), 'reason': 'Exact source fact correction'}]}
        self.write(path, self.read(path).replace(old, new, 1))

    def test_narrow_correction_allowed(self):
        self.correction('Historical correction', 'Historical source correction'); self.check()

    def test_wrong_correction_hash_rejected(self):
        self.correction('Historical correction', 'Historical source correction')
        self.manifest['corrections'][0]['old_sha256'] = '0' * 64; self.check(False)

    def test_whole_file_exception_rejected(self):
        self.correction(self.read(DONE), '# Replacement\n'); self.check(False)

    def test_unused_exception_rejected(self):
        self.correction('Ordinary', 'Revised', 'docs/ordinary.md'); self.check(False)

    def commit_changes(self):
        self.git('add', '--', 'docs')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@local', 'commit', '-qm', 'extra fixture')
        self.base = self.git('rev-parse', 'HEAD').strip()

    def test_crlf_is_preserved_not_normalized(self):
        self.write('docs/source.md', SOURCE.replace('\n', '\r\n'))
        self.commit_changes()
        target = self.move_source()
        self.check()
        self.write(target, self.read(target).replace('\r\n', '\n'))
        self.check(False)

    def test_historical_blob_url_is_not_a_current_path_alias(self):
        history = 'docs/plans/done/history.md'
        link = 'https://github.com/x/y/blob/old/docs/source.md'
        self.write(history, self.read(history) + f'[historical]({link})\nBare {link}\n')
        self.commit_changes()
        self.move_source(); self.check()
        self.write(history, self.read(history).replace('/blob/old/docs/source.md', '/blob/old/docs/plans/done/source.md'))
        self.check(False)

    def test_fence_info_is_not_a_closing_fence(self):
        self.write('docs/source.md', '# Source\n```text\n```not-a-closing-fence\n'
                   '[code](missing.md)\n```\n## Section\n[guide](guide.md#guide)\n')
        self.commit_changes()
        target = self.move_source()
        self.check()
        self.write(target, self.read(target).replace('../../guide.md', 'guide.md'))
        self.check(False)

    def test_unsupported_escaped_link_fails_closed(self):
        self.write('docs/source.md', '# Source\n[escaped\\](guide.md#guide)\n## Section\n')
        self.commit_changes()
        self.move_source(); self.check(False)

    def test_prose_and_code_paths_are_not_route_aliases(self):
        self.write('docs/source.md', SOURCE + 'Historical docs/source.md path.\n`docs/source.md`\n')
        self.commit_changes()
        target = self.move_source()
        self.check()
        self.write(target, self.read(target).replace('docs/source.md', target))
        self.check(False)

    def test_unsupported_literal_contexts_fail_closed(self):
        for text in ('`multiline\n[code](guide.md#guide)\n`\n',
                     '<!-- [comment](guide.md#guide) -->\n',
                     '<script>\n[script](guide.md#guide)\n</script>\n',
                     '<figure>\n[figure](guide.md#guide)\n</figure>\n',
                     '<a href = \"guide.md#guide\">link</a>\n',
                     '    [code](guide.md#guide)\n',
                     '\\[escaped](guide.md#guide)\n'):
            with self.subTest(text=text):
                # Reinitialize each independent transfer from its own immutable source.
                self.write('docs/source.md', '# Source\n' + text + '## Section\n')
                self.write('docs/index.md', '[source](source.md#section)\n')
                target = self.root / 'docs/plans/done/source.md'
                if target.exists(): target.unlink()
                self.commit_changes()
                self.move_source(); self.check(False)

    def test_canonical_marker_injection_is_rejected(self):
        target = self.move_source()
        self.write(target, self.read(target).replace('[guide](../../guide.md#guide)', '[guide](@@LOCAL[docs/guide.md#guide?])'))
        self.check(False)

    def test_combined_corrections_cannot_replace_whole_history(self):
        path = 'docs/plans/done/history.md'
        original = self.read(path)
        self.manifest = {'version': 1, 'corrections': []}
        # Each old fragment is below half; combined fragments exceed that bound.
        for old, new in (('# Protected', '# Revised'), ('History sentence.\nHistory', 'Changed sentence.\nChanged')):
            self.manifest['corrections'].append({'path': path, 'base_sha256': digest(original),
                'old': old, 'new': new, 'old_sha256': digest(old), 'reason': 'Fixture boundary'})
        for c in self.manifest['corrections']: original = original.replace(c['old'], c['new'])
        self.write(path, original)
        self.check(False)

    def test_unrelated_historical_prose_is_not_an_inbound_link(self):
        path = 'docs/plans/done/history.md'
        self.write(path, self.read(path) + 'Historical `docs/source.md` mention.\n'
                   '| unmatched ` |\n| prose ` only |\n')
        self.commit_changes()
        self.move_source(); self.check()

    def test_reference_link_retarget_and_bad_heading_control(self):
        self.write('docs/index.md', '[source][ref]\n[ref]: source.md#section\n')
        self.commit_changes()
        self.move_source()
        self.write('docs/index.md', '[source][ref]\n[ref]: plans/done/source.md#section\n')
        self.check()
        self.write('docs/index.md', '[source][ref]\n[ref]: plans/done/source.md#absent\n')
        self.check(False)

    def test_escaped_reference_to_source_does_not_bypass_prefilter(self):
        self.write('docs/index.md', '[source][ref]\n[ref]: source\\.md#section\n')
        self.commit_changes()
        self.move_source()
        self.write('docs/index.md', '[source][ref]\n[ref]: source\\.md#section\n')
        self.check(False)

    def test_percent_encoded_inbound_source_is_checked(self):
        self.write('docs/index.md', '[source](%73ource.md#section)\n')
        self.commit_changes()
        self.move_source()
        self.write('docs/index.md', '[source](%73ource.md#section)\n')
        self.check(False)

    def test_history_append_without_original_final_newline(self):
        self.write(DONE, self.read(DONE).rstrip('\n'))
        self.commit_changes()
        self.write(DONE, self.read(DONE) + '\nNew history.\n')
        self.check()

    def test_footer_stays_after_existing_done_row(self):
        self.write(DONE, '# Done\n' + OLDROW + FOOTER + 'New correction append.\n')
        self.check()
        self.write(DONE, '# Done\n' + FOOTER + OLDROW)
        self.check(False)

    def test_entity_escaped_source_target_does_not_bypass_prefilter(self):
        self.write('docs/index.md', '[source](source&#46;md#section)\n')
        self.commit_changes()
        self.move_source()
        self.write('docs/index.md', '[source](source&#46;md#section)\n')
        self.check(False)

    def test_unquoted_html_source_link_is_not_ignored(self):
        self.write('docs/index.md', '<a href=source.md#section>source</a>\n')
        self.commit_changes()
        self.move_source()
        self.write('docs/index.md', '<a href=source.md#section>source</a>\n')
        self.check(False)
        self.write('docs/index.md', '[source](plans/done/source.md#section)\n')
        # Unsupported original HTML still requires explicit conversion, never silent acceptance.
        self.check(False)

    def test_fragment_and_query_are_structurally_distinct(self):
        self.write('docs/guide.md', '# Guide\n<a id="guide?x"></a>\n')
        self.write('docs/source.md', SOURCE.replace('guide.md#guide', 'guide.md?x?#guide'))
        self.commit_changes()
        target = self.move_source()
        self.write(target, self.read(target).replace('(guide.md?x?#guide)', '(../../guide.md?x?#guide)'))
        self.check()
        self.write(target, self.read(target).replace('(../../guide.md?x?#guide)', '(../../guide.md#guide?x)'))
        self.check(False)

    def test_source_hash_mismatch_rejected(self):
        self.move_source(); self.manifest['sources'][0]['sha256'] = '0' * 64; self.check(False)

    def test_existing_destination_must_be_preserved_source(self):
        self.transfer(['docs/source.md'], [{'path': 'docs/guide.md', 'parts': [
            {'source': 'docs/source.md', 'start': 1, 'end': 7}]}]); self.check(False)

    def evidence_fixture(self, readme='# Run\n[data](data.json)\n[guide](../../guide.md)\n'):
        self.write(f'{EVIDENCE}/README.md', readme)
        self.write(f'{EVIDENCE}/data.json', '{}\n')
        self.commit_changes()

    def move_evidence(self, routes=True):
        readme = f'{EVIDENCE}/README.md'
        count = len(self.read(readme).splitlines(keepends=True))
        self.transfer([readme], [{'path': f'{MOVED}/README.md', 'parts': [
            {'source': readme, 'start': 1, 'end': count}]}])
        data = self.read(f'{EVIDENCE}/data.json')
        (self.root / f'{EVIDENCE}/data.json').unlink()
        self.write(f'{MOVED}/data.json', data)
        if routes: self.manifest['routes'] = [{'from': EVIDENCE, 'to': MOVED}]

    def test_route_keeps_sibling_links_of_moved_directory(self):
        self.evidence_fixture()
        self.move_evidence(routes=False); self.check(False, 'Destination ordered content differs')
        self.manifest['routes'] = [{'from': EVIDENCE, 'to': MOVED}]; self.check()

    def test_route_rejects_unmoved_leftover_and_empty_routes(self):
        self.evidence_fixture()
        self.move_evidence()
        data = self.read(f'{MOVED}/data.json')
        (self.root / f'{MOVED}/data.json').unlink(); self.check(False, 'Routed file missing at new path')
        self.write(f'{MOVED}/data.json', data); self.write(f'{EVIDENCE}/data.json', data)
        self.check(False, 'Routed file still exists at old path')
        (self.root / f'{EVIDENCE}/data.json').unlink(); self.check()
        self.manifest['routes'] = [{'from': 'docs/evidence-old/run-78', 'to': MOVED}]
        self.check(False, 'Route has no base files')

    def test_route_requires_markdown_under_it_to_be_declared(self):
        self.write(f'{EVIDENCE}/notes.md', '# Notes\n')
        self.evidence_fixture()
        self.move_evidence(); self.check(False, 'Routed Markdown needs a source/destination manifest')

    def test_overlapping_routes_rejected(self):
        self.evidence_fixture()
        self.move_evidence()
        self.manifest['routes'].append({'from': 'docs/evidence-old', 'to': 'docs/evidence-other'})
        self.check(False, 'Overlapping/duplicate route path')

    def test_route_lets_protected_history_follow_moved_data(self):
        path = 'docs/plans/done/history.md'
        self.write(path, self.read(path) + '[data](../../evidence-old/run-77/data.json)\n'
                   '[folder](../../evidence-old/run-77)\n')
        self.evidence_fixture('# Run\nBody.\n')
        self.move_evidence(routes=False)
        self.write(path, self.read(path).replace('evidence-old', 'evidence-new'))
        self.check(False, 'Protected archive changed')
        self.manifest['routes'] = [{'from': EVIDENCE, 'to': MOVED}]; self.check()
        self.write(path, self.read(path).replace('evidence-new/run-77/data.json', 'guide.md'))
        self.check(False, 'Protected archive changed')

    def test_same_named_link_elsewhere_does_not_parse_unrelated_document(self):
        self.write('docs/other/README.md', '# Other\n')
        self.write('docs/notes.md', 'Start `code\ncontinues` end.\n[other](other/README.md)\n')
        self.evidence_fixture('# Run\nBody.\n')
        self.move_evidence(); self.check()

    def test_unparseable_document_linking_moved_source_fails_closed(self):
        self.write('docs/notes.md', 'Start `code\ncontinues` end.\n[run](evidence-old/run-77/README.md)\n')
        self.evidence_fixture('# Run\nBody.\n')
        self.move_evidence(); self.check(False, 'Unsupported multiline backtick span')

    def test_outside_detour_link_still_needs_retarget(self):
        self.write('docs/notes.md', '[run](evidence-old/run-77/sub/../README.md)\n')
        self.evidence_fixture('# Run\nBody.\n')
        self.move_evidence(); self.check(False, 'Inbound source references not preserved/retargeted')
        self.write('docs/notes.md', '[run](evidence-new/run-77/README.md)\n'); self.check()

    def test_document_inside_source_directory_is_checked_by_name(self):
        self.write(f'{EVIDENCE}/notes.md', '[readme](README.md)\n')
        self.evidence_fixture('# Run\nBody.\n')
        self.move_evidence(routes=False)
        self.check(False, 'Inbound source references not preserved/retargeted')
        self.write(f'{EVIDENCE}/notes.md', '[readme](../../evidence-new/run-77/README.md)\n'); self.check()


if __name__ == '__main__':
    unittest.main(verbosity=2)
