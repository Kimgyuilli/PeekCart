#!/usr/bin/env python3
"""Parser regression against saved Grafana 11.6 observations (no containers)."""
import copy
import io
import json
from pathlib import Path
import unittest
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from verify_alerts import all_normal, assert_alerting_instances, capture_snapshot, CONTRACT, wait_for_initial_evaluation


class GrafanaStateContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((Path(__file__).parent / 'fixtures/grafana-11.6-run2.json').read_text())

    def test_real_saved_alerting_instances(self):
        for uid, rule in self.fixture['alerting']['rules'].items():
            assert_alerting_instances(uid, rule)

    def test_real_normal_and_pending_are_distinct(self):
        self.assertTrue(all_normal(self.fixture['normal']['rules']))
        self.assertFalse(all_normal(self.fixture['pending']['rules']))
        self.assertFalse(all_normal(self.fixture['alerting']['rules']))

    def test_wrong_instance_states_never_count_as_alerting(self):
        for uid in CONTRACT:
            for state in ('Normal', 'Pending', 'NoData', 'firing'):
                with self.subTest(uid=uid, state=state):
                    rule = copy.deepcopy(self.fixture['alerting']['rules'][uid])
                    rule['alerts'][0]['state'] = state
                    with self.assertRaises(AssertionError):
                        assert_alerting_instances(uid, rule)

    def test_missing_application_or_cache_is_rejected(self):
        for uid in CONTRACT:
            rule = copy.deepcopy(self.fixture['alerting']['rules'][uid])
            rule['alerts'].pop()
            with self.subTest(uid=uid), self.assertRaises(AssertionError):
                assert_alerting_instances(uid, rule)

    def test_no_data_is_not_normal_recovery(self):
        for change in ('empty-vector', 'empty-instances', 'NoData', 'Pending', 'Alerting'):
            rules = copy.deepcopy(self.fixture['normal']['rules'])
            rule = rules['peekcart-compensation-backlog']
            if change == 'empty-vector':
                rule['vector'] = []
            elif change == 'empty-instances':
                rule['alerts'] = []
            else:
                rule['alerts'][0]['state'] = change
            with self.subTest(change=change):
                self.assertFalse(all_normal(rules))


class FakeClock:
    def __init__(self):
        self.now = 0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class InitialEvaluationTest(unittest.TestCase):
    def setUp(self):
        fixture = json.loads((Path(__file__).parent / 'fixtures/grafana-11.6-run2.json').read_text())
        self.normal = fixture['normal']['rules']
        for rule in self.normal.values():
            rule['health'] = 'ok'
        self.clock = FakeClock()
        self.events = io.StringIO()
        self.sources = {uid: {'title': uid, 'data': [{'refId': 'A', 'model': {'expr': uid}}]} for uid in CONTRACT}

    def wait(self, probe, timeout=10):
        return wait_for_initial_evaluation(probe, 'evidence/observations.jsonl', timeout=timeout, interval=5,
                                           clock=self.clock.monotonic, sleep=self.clock.sleep)

    def api(self, rules, query_status='success'):
        def get(url, authenticated=False):
            if authenticated:
                return {'data': {'groups': [{'rules': [dict(rule, uid=uid) for uid, rule in rules.items()]}]}}
            uid = parse_qs(urlparse(url).query)['query'][0]
            return {'status': query_status, 'data': {'result': rules[uid]['vector']}, 'fixtureRaw': uid}
        return get

    def capture(self, rules, strict=False, get=None):
        return capture_snapshot(get or self.api(rules), 'http://grafana', 'http://prometheus', self.sources,
                                'readiness', self.events, strict=strict)

    def test_delayed_evaluation_waits_through_no_data_and_pending(self):
        no_data = copy.deepcopy(self.normal)
        no_data['peekcart-compensation-backlog']['health'] = 'nodata'
        pending = copy.deepcopy(self.normal)
        pending['peekcart-dlq-backlog']['alerts'][0]['state'] = 'Pending'
        empty = copy.deepcopy(self.normal)
        empty['peekcart-cache-fallback-get']['vector'] = []
        replies = iter([no_data, pending, empty, self.normal])
        actual = self.wait(lambda: self.capture(next(replies)), timeout=20)
        self.assertTrue(all_normal(actual))
        self.assertEqual(self.clock.sleeps, [5, 5, 5])
        observed = [json.loads(line) for line in self.events.getvalue().splitlines()]
        self.assertEqual(len(observed), 4)
        self.assertEqual(observed[0]['grafana']['data']['groups'][0]['rules'][0]['health'], 'nodata')
        self.assertEqual(observed[2]['queries']['peekcart-cache-fallback-get']['data']['result'], [])

    def test_permanently_unready_never_returns_as_baseline_ready(self):
        for mutation in ('nodata', 'health-missing', 'nonzero', 'nan', 'missing-service', 'missing-cache',
                         'duplicate-vector', 'duplicate-instance', 'instance-nodata', 'missing-instance'):
            with self.subTest(mutation=mutation):
                self.clock = FakeClock()
                rules = copy.deepcopy(self.normal)
                rule = rules['peekcart-dlq-backlog']
                if mutation == 'nodata': rule['health'] = 'nodata'
                elif mutation == 'health-missing': del rule['health']
                elif mutation in ('nonzero', 'nan'): rule['vector'][0]['value'][1] = '1' if mutation == 'nonzero' else 'NaN'
                elif mutation == 'missing-service': rule['vector'].pop()
                elif mutation == 'missing-cache': rules['peekcart-cache-fallback-get']['vector'].pop()
                elif mutation == 'duplicate-vector': rule['vector'].append(copy.deepcopy(rule['vector'][0]))
                elif mutation == 'duplicate-instance': rule['alerts'].append(copy.deepcopy(rule['alerts'][0]))
                elif mutation == 'instance-nodata': rule['alerts'][0]['state'] = 'NoData'
                else: rule['alerts'].pop()
                with self.assertRaisesRegex(AssertionError, 'after 10s; raw observations: evidence/observations.jsonl'):
                    self.wait(lambda: rules)
                self.assertEqual(self.clock.now, 10)
                self.assertEqual(self.clock.sleeps, [5, 5])

    def test_probe_that_finishes_after_deadline_is_not_accepted(self):
        def late():
            self.clock.now = 11
            return self.normal
        with self.assertRaisesRegex(AssertionError, 'Initial evaluation not ready'):
            self.wait(late)
        self.assertEqual(self.clock.sleeps, [])

    def test_strict_snapshot_records_original_no_data_before_assertion(self):
        rules = copy.deepcopy(self.normal)
        rules['peekcart-compensation-backlog']['health'] = 'nodata'
        with self.assertRaises(AssertionError):
            self.capture(rules, strict=True)
        raw = json.loads(self.events.getvalue())
        self.assertEqual(raw['grafana']['data']['groups'][0]['rules'][0]['health'], 'nodata')
        self.assertEqual(raw['queries']['peekcart-compensation-backlog']['fixtureRaw'], 'peekcart-compensation-backlog')

    def test_http_failure_body_is_recorded_and_timeout_remains_finite(self):
        def unavailable(url, authenticated=False):
            raise HTTPError(url, 503, 'not evaluated yet', {}, io.BytesIO(b'{"error":"warming evaluator"}'))
        with self.assertRaisesRegex(AssertionError, 'after 10s'):
            self.wait(lambda: self.capture(self.normal, get=unavailable))
        errors = [json.loads(line)['error'] for line in self.events.getvalue().splitlines()]
        self.assertEqual(len(errors), 3)
        self.assertTrue(all(e['type'] == 'HTTPError' and e['body'] == '{"error":"warming evaluator"}' for e in errors))

    def test_query_error_payload_is_preserved_and_not_healthy(self):
        result = self.capture(self.normal, get=self.api(self.normal, query_status='error'))
        self.assertEqual(result, {})
        raw = json.loads(self.events.getvalue())
        self.assertEqual(raw['queries']['peekcart-compensation-backlog']['status'], 'error')
        self.assertIn('grafana', raw)


if __name__ == '__main__':
    unittest.main()
