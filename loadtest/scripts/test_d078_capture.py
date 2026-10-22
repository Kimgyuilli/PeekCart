#!/usr/bin/env python3
"""Local checks only: never contacts Grafana or Prometheus."""
import copy
import importlib.util
import json
import tempfile
import unittest
import urllib.parse
from pathlib import Path

SCRIPT = Path(__file__).with_name('d078-capture.py')
spec = importlib.util.spec_from_file_location('d078_capture', SCRIPT)
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureSpecTest(unittest.TestCase):
    def test_every_capture_entry_resolves_to_a_unique_panel_with_id(self):
        for item in capture.CAPTURES:
            resolved = capture.resolve(item)
            self.assertEqual(len(resolved), len(capture.CAPTURES[item]), item)
            for _, uid, panel in resolved:
                self.assertTrue(uid)
                self.assertIsInstance(panel['id'], int)

    def test_panel_ids_are_unique_within_each_dashboard(self):
        for key in capture.DASHBOARDS:
            ids = [p['id'] for p in capture.load_dashboard(key)['panels']]
            self.assertEqual(len(ids), len(set(ids)), key)

    def _copy_dashboards(self, directory, mutate):
        for key, name in capture.DASHBOARDS.items():
            dashboard = capture.load_dashboard(key)
            if key == 'saga':
                mutate(dashboard)
            (Path(directory) / name).write_text(json.dumps(dashboard), encoding='utf-8')

    def test_renamed_panel_fails_loudly(self):
        def rename(dashboard):
            for panel in dashboard['panels']:
                if panel.get('title') == 'Outbox backlog':
                    panel['title'] = 'Outbox 대기열'
        with tempfile.TemporaryDirectory() as directory:
            self._copy_dashboards(directory, rename)
            with self.assertRaises(SystemExit) as raised:
                capture.resolve('d078-5-drain', directory)
            self.assertIn('Outbox backlog', str(raised.exception))

    def test_panel_without_id_fails_loudly(self):
        def drop_id(dashboard):
            for panel in dashboard['panels']:
                if panel.get('title') == '노드 CPU 사용률':
                    panel.pop('id')
        with tempfile.TemporaryDirectory() as directory:
            self._copy_dashboards(directory, drop_id)
            with self.assertRaises(SystemExit) as raised:
                capture.resolve('d078-4', directory)
            self.assertIn('id', str(raised.exception))


class UrlAndExprTest(unittest.TestCase):
    def test_solo_url_pins_absolute_range_in_milliseconds(self):
        url = capture.solo_url('http://localhost:3000/', 'peekcart-saga-messaging', 12, 1_793_000_000, 1_793_000_180)
        parsed = urllib.parse.urlparse(url)
        query = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(parsed.path, '/d-solo/peekcart-saga-messaging/')
        self.assertEqual(query['panelId'], ['12'])
        self.assertEqual(query['from'], ['1793000000000'])
        self.assertEqual(query['to'], ['1793000180000'])
        self.assertEqual(query['var-application'], ['$__all'])

    def test_concrete_expr_replaces_dashboard_variables(self):
        expr = 'sum by (application) (rate(x_total{application=~"$application"}[$__rate_interval]))'
        self.assertEqual(capture.concrete_expr(expr),
                         'sum by (application) (rate(x_total{application=~".*"}[1m]))')

    def test_every_exported_expr_has_no_unresolved_variable(self):
        for item in capture.CAPTURES:
            for _, _, panel in capture.resolve(item):
                for target in panel['targets']:
                    self.assertNotIn('$', capture.concrete_expr(target['expr']), panel['title'])

    def test_annotation_is_a_region_tagged_for_the_session(self):
        body = capture.annotation_body('peekcart-api-jvm', 'B list OFF r2', 100, 160)
        self.assertEqual(body, {'dashboardUID': 'peekcart-api-jvm', 'time': 100_000, 'timeEnd': 160_000,
                                'tags': ['d078'], 'text': 'B list OFF r2'})

    def test_file_stem_is_filesystem_safe(self):
        self.assertEqual(capture.file_stem('d078-1', 'B list OFF r2', 'api-jvm', 4),
                         'd078-1-B-list-OFF-r2-api-jvm-p4')

    def test_reversed_range_is_rejected(self):
        with self.assertRaises(SystemExit):
            capture.main(['plan', '--item', 'd078-4', '--label', 'x', '--from', '200', '--to', '100'])


if __name__ == '__main__':
    unittest.main()
