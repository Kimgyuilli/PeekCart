#!/usr/bin/env python3
"""Actual CLI fixtures for the renderer contract; no model/API calls."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
RENDER = Path(os.environ.get('CODEX_REVIEW_RENDER_TEST_SCRIPT', ROOT / 'scripts/codex-review-render.sh'))
ITEM = dict(id=1, severity='P1', category='bug', finding='finding', suggestion='suggestion')
DOC = dict(run_id='fixture', summary='summary', items=[ITEM])


class RendererContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='renderer-contract-')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'plan-fixture.json'

    def run_cli(self, value, raw=False):
        self.path.write_text(value if raw else json.dumps(value), encoding='utf-8')
        return subprocess.run(['bash', str(RENDER), str(self.path)], text=True, capture_output=True)

    def invalid(self, value, raw=False):
        result = self.run_cli(value, raw)
        self.assertEqual(result.returncode, 2, (value, result.stdout, result.stderr))
        self.assertIn('리뷰 실패', result.stdout)
        for forbidden in ('자동 통과', '총 ', '| sev |', 'Traceback'):
            self.assertNotIn(forbidden, result.stdout + result.stderr, value)

    def item_with(self, key, value):
        doc = copy.deepcopy(DOC)
        doc['items'][0][key] = value
        return doc

    def test_envelope_types_and_required_metadata(self):
        for value in (None, [], True, 1, 'text', {}, {'items': []}):
            with self.subTest(value=value): self.invalid(value)
        for key in DOC:
            for value in (None, {}, 1, False):
                with self.subTest(key=key, value=value):
                    doc = copy.deepcopy(DOC); doc[key] = value; self.invalid(doc)
            doc = copy.deepcopy(DOC); del doc[key]; self.invalid(doc)
        doc = copy.deepcopy(DOC); doc['model'] = 'invented'; self.invalid(doc)

    def test_item_objects_and_required_core(self):
        for value in (None, {}, [], 'text', 1, False):
            doc = copy.deepcopy(DOC); doc['items'] = [value]; self.invalid(doc)
        for key in ITEM:
            doc = copy.deepcopy(DOC); del doc['items'][0][key]; self.invalid(doc)
        self.invalid(self.item_with('extra', 'unknown'))

    def test_severity_and_category_enums(self):
        for key in ('severity', 'category'):
            for value in ('P9', '', None, 1, [], {}):
                with self.subTest(key=key, value=value): self.invalid(self.item_with(key, value))

    def test_string_and_optional_fields(self):
        for key in ('finding', 'suggestion', 'file'):
            for value in (None, 1, [], {}, False):
                self.invalid(self.item_with(key, value))
        for key in ('scope', 'suggested_disposition'):
            for value in ('unknown', '', None, 1, [], {}):
                self.invalid(self.item_with(key, value))
        self.invalid(self.item_with('finding', '\ud800'))

    def test_integer_types_and_ranges(self):
        for key, values in (('id', (0, -1, 1.5, True, False, '1', None, [], {})),
                            ('line', (-1, 1.5, True, False, '1', None, [], {}))):
            for value in values:
                with self.subTest(key=key, value=value): self.invalid(self.item_with(key, value))
        for key, number in (('id', '1.0000000000000001'), ('id', '0.99999999999999999999'),
                            ('line', '1e-999'), ('line', '-1e-999'), ('line', '1.0000000000000001')):
            raw = json.dumps(self.item_with(key, 'RAW_NUMBER')).replace('"RAW_NUMBER"', number)
            self.invalid(raw, raw=True)
        for key, number in (('id', '1.0'), ('id', '1e999'), ('id', '1e1000000'),
                            ('line', '0.0'), ('line', '10e-1')):
            raw = json.dumps(self.item_with(key, 'RAW_NUMBER')).replace('"RAW_NUMBER"', number)
            result = self.run_cli(raw, raw=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_empty_malformed_and_non_json_constants(self):
        for value in ('', ' ', '{', '{"items":', 'null', '[]',
                      '{"run_id":"r","summary":"s","items":NaN}', '[' * 1500 + '0' + ']' * 1500):
            self.invalid(value, raw=True)

    def test_legacy_and_current_success(self):
        docs = [dict(run_id='r', summary='s', items=[]), copy.deepcopy(DOC)]
        for optional in ({'file': 'src/F.java', 'line': 0},
                         {'scope': 'in_scope', 'suggested_disposition': 'reflect'},
                         {'file': '', 'line': 1.0, 'scope': 'new_surface', 'suggested_disposition': 'defer'}):
            doc = copy.deepcopy(DOC); doc['items'][0].update(optional); docs.append(doc)
        doc = copy.deepcopy(DOC); doc.update(run_id='', summary=''); doc['items'][0].update(id=1.0, finding='', suggestion=''); docs.append(doc)
        for doc in docs:
            result = self.run_cli(doc)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertIn('P1 1' if doc['items'] else '지적 0건', result.stdout)
        for severity in ('P0', 'P1', 'P2'):
            for category in ('architecture', 'bug', 'security', 'test', 'doc', 'style', 'convention'):
                result = self.run_cli(DOC | {'items': [ITEM | {'severity': severity, 'category': category}]})
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(severity + ' 1', result.stdout)

    def test_independent_oracle_raw_numbers(self):
        wrapper = Path(os.environ.get('CODEX_REVIEW_RENDER_TEST_WRAPPER', ROOT / 'scripts/codex-review-render-selftest.sh')).read_text()
        start = wrapper.index('expected_valid() {')
        function = wrapper[start:wrapper.index('\nfor f in "$TMP"', start)]
        for key, number, want in (('id', '1.0', 0), ('id', '1e1000000', 0), ('line', '10e-1', 0),
                                  ('id', '1.0000000000000001', 1), ('line', '1e-999', 1)):
            raw = json.dumps(self.item_with(key, 'RAW_NUMBER')).replace('"RAW_NUMBER"', number)
            self.path.write_text(raw)
            result = subprocess.run(['/bin/bash', '-o', 'pipefail', '-c', function + '\nexpected_valid "$1"',
                                     'oracle', str(self.path)], text=True, capture_output=True)
            if want: self.assertNotEqual(result.returncode, 0, (key, number, result.stderr))
            else: self.assertEqual(result.returncode, 0, (key, number, result.stderr))

    def test_ci_policy_step_propagates_renderer_failure(self):
        import yaml
        steps = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())['jobs']['lint']['steps']
        body = next(step['run'] for step in steps if step.get('name') == 'Run CI policy lints')
        directory = Path(self.temp.name)
        binary = directory / 'bin'; binary.mkdir()
        log = directory / 'calls'
        stub = '#!/bin/sh\nprintf "%s:%s\\n" "${0##*/}" "$*" >> "$RENDER_CI_LOG"\ncase "$1" in scripts/codex-review-render-selftest.sh) exit "$RENDER_CI_FAILURE";; esac\nexit 0\n'
        for tool in ('bash', 'python3'):
            script = binary / tool; script.write_text(stub); script.chmod(0o755)
        script = binary / 'grep'; script.write_text('#!/bin/sh\nexit 1\n'); script.chmod(0o755)
        for rc in (0, 23):
            if log.exists(): log.unlink()
            env = os.environ.copy()
            env.update(RENDER_CI_LOG=str(log), RENDER_CI_FAILURE=str(rc))
            env['PATH'] = str(binary) + os.pathsep + env['PATH']
            result = subprocess.run(['/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', body],
                                    env=env, cwd=directory, text=True, capture_output=True)
            self.assertEqual(result.returncode, rc, result.stdout + result.stderr)
            calls = log.read_text().splitlines()
            target = 'bash:scripts/codex-review-render-selftest.sh'
            self.assertIn(target, calls)
            if rc: self.assertEqual(calls[-1], target)
            else: self.assertIn('bash:scripts/ship-resume-selftest.sh', calls)


def mutations():
    source = (ROOT / 'scripts/codex-review-render.sh').read_text()
    variants = [
        ('unknown-severity-accepted', "'severity': {'P0', 'P1', 'P2'}", "'severity': {'P0', 'P1', 'P2', 'P9'}", 'test_severity_and_category_enums'),
        ('unknown-category-accepted', "'architecture', 'bug', 'security', 'test', 'doc', 'style', 'convention'", "'architecture', 'bug', 'security', 'test', 'doc', 'style', 'convention', 'P9'", 'test_severity_and_category_enums'),
        ('metadata-missing-accepted', "set(doc) == {'run_id', 'summary', 'items'}", "set(doc) <= {'run_id', 'summary', 'items'}", 'test_envelope_types_and_required_metadata'),
        ('core-missing-accepted', 'required <= set(item) <= required | optional', 'set(item) <= required | optional', 'test_item_objects_and_required_core'),
        ('optional-enum-ignored', "if key in item:\n                require(string(item[key]) and item[key] in choices", "if key in item and key not in ('scope', 'suggested_disposition'):\n                require(string(item[key]) and item[key] in choices", 'test_string_and_optional_fields'),
        ('fractional-integer-accepted', 'value == value.to_integral_value()', 'True', 'test_integer_types_and_ranges'),
        ('decimal-rounded-before-check', 'parse_float=Decimal', 'parse_float=lambda spelling: Decimal(str(float(spelling)))', 'test_integer_types_and_ranges'),
        ('failure-mapped-to-usage', '  exit 2\n}', '  exit 1\n}', 'test_empty_malformed_and_non_json_constants'),
        ('always-reject', "items = doc['items']", "sys.exit(2)\nitems = doc['items']", 'test_legacy_and_current_success'),
    ]
    with tempfile.TemporaryDirectory(prefix='renderer-mutants-') as directory:
        for name, old, new, method in variants:
            assert old in source, 'mutation source drift: ' + name
            script = Path(directory) / (name + '.sh'); script.write_text(source.replace(old, new))
            env = os.environ.copy(); env['CODEX_REVIEW_RENDER_TEST_SCRIPT'] = str(script)
            result = subprocess.run([sys.executable, '-B', __file__, 'RendererContractTest.' + method],
                                    env=env, text=True, capture_output=True)
            assert result.returncode and ('FAIL: ' + method) in result.stderr, (name, result.stdout, result.stderr)
            print(name + ': REJECTED by ' + method)
    wrapper = (ROOT / 'scripts/codex-review-render-selftest.sh').read_text()
    old = 'parse_float=Decimal'
    assert old in wrapper
    with tempfile.TemporaryDirectory(prefix='renderer-oracle-mutant-') as directory:
        script = Path(directory) / 'oracle-rounded.sh'
        script.write_text(wrapper.replace(old, 'parse_float=lambda spelling: Decimal(str(float(spelling)))'))
        env = os.environ.copy(); env['CODEX_REVIEW_RENDER_TEST_WRAPPER'] = str(script)
        method = 'test_independent_oracle_raw_numbers'
        result = subprocess.run([sys.executable, '-B', __file__, 'RendererContractTest.' + method],
                                env=env, text=True, capture_output=True)
        assert result.returncode and ('FAIL: ' + method) in result.stderr, (result.stdout, result.stderr)
        print('oracle-decimal-rounded: REJECTED by ' + method)
    print('PASS: 10 wrong-implementation controls rejected; source untouched')


if __name__ == '__main__':
    if sys.argv[1:] == ['--mutants']:
        mutations()
    else:
        unittest.main(verbosity=2)
