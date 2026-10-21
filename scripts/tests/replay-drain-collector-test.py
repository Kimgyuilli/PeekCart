#!/usr/bin/env python3
"""Exercise the deployed shell entrypoint with CLI fixtures, never source its functions."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/replay-drain-preflight.sh'
SERVICES = ['order', 'product', 'payment', 'notification']
HEADER = 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n'
ROW = 'order-target-a order.created 0 15 15 0 consumer-a /10.0.0.1 client-a\n'


class CollectorTest(unittest.TestCase):
    def run_fixture(self, overrides=(), interval="0"):
        with tempfile.TemporaryDirectory(prefix='drain-collector-') as directory:
            temp = Path(directory)
            stub = temp / 'kubectl'
            stub.write_bytes((ROOT / 'scripts/tests/fixtures/replay-drain-kubectl.py').read_bytes())
            stub.chmod(0o755)
            fixture = temp / 'fixture.json'
            fixture.write_text(json.dumps(dict(overrides=overrides)))
            calls = temp / 'calls.jsonl'
            calls.touch()
            env = dict(os.environ, PATH=f'{temp}{os.pathsep}{os.environ["PATH"]}',
                       DRAIN_FIXTURE=str(fixture), DRAIN_CALLS=str(calls),
                       PEEKCART_NAMESPACE='fixture-only', DRAIN_LAG_RECHECK_SECONDS=interval)
            result = subprocess.run(['bash', str(SCRIPT)], env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            return result, [json.loads(line) for line in calls.read_text().splitlines()]

    def assert_blocked(self, override, needle):
        result, calls = self.run_fixture([override])
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(needle, result.stdout)
        self.assertNotIn('OK — drain', result.stdout)
        return calls

    def test_valid_partitions_and_every_group_are_observed_twice(self):
        result, calls = self.run_fixture()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('OK — drain', result.stdout)
        for service in SERVICES:
            for kind in ['username', 'password', 'db', 'groups']:
                self.assertEqual(sum(c == dict(kind=kind, service=service) for c in calls), 1)
            for group in [f'{service}-target-a', f'{service}-target-b', f'{service}-svc-dlq-group']:
                self.assertEqual([c['sample'] for c in calls if c.get('group') == group], [1, 2])
        self.assertEqual(len(calls), 40)

    def test_no_replay_history_still_checks_all_intake_groups(self):
        overrides = [dict(match=dict(kind=kind), output=output)
                     for kind, output in [('groups', ''), ('db', '0\t0\t-\n')]]
        result, calls = self.run_fixture(overrides)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual([c['group'] for c in calls if c['kind'] == 'lag'],
                         [f'{s}-svc-dlq-group' for s in SERVICES for _ in range(2)])

    def test_all_services_fail_closed_on_query_and_secret_failure(self):
        for service in SERVICES:
            for kind, needle in [('username', 'DB_USERNAME'), ('password', 'DB_PASSWORD'),
                                 ('db', f'스키마 peekcart_{service} 조회 실패'),
                                 ('groups', '표적 group 조회 실패')]:
                with self.subTest(service=service, kind=kind):
                    self.assert_blocked(dict(match=dict(kind=kind, service=service), exit=1), needle)

    def test_invalid_kafka_describe_is_never_zero_lag(self):
        outputs = {
            'empty': '', 'whitespace': '\n  \n', 'header-only': HEADER,
            'missing-group': "Error: Consumer group 'order-target-a' does not exist.\n",
            'wrong-group': HEADER + ROW.replace('order-target-a', 'other-group'),
            'malformed-row': HEADER + 'order-target-a order.created 0\n',
            'non-numeric-lag': HEADER + ROW.replace('15 15 0 ', '15 15 NaN '),
            'negative-lag': HEADER + ROW.replace('15 15 0 ', '15 15 -1 '),
            'unassigned': HEADER + ROW.replace('15 15 0 ', '- 15 - '),
            'non-numeric-partition': HEADER + ROW.replace('order.created 0', 'order.created NaN'),
            'non-numeric-offset': HEADER + ROW.replace('15 15 0 ', 'NaN 15 0 '),
            'missing-header': ROW,
            'extra-malformed-row': HEADER + ROW + 'unexpected line\n',
            'wrong-header': HEADER.replace('LAG', 'UNKNOWN') + ROW,
            'duplicate-partition': HEADER + ROW + ROW,
        }
        for sample in [1, 2]:
            for name, output in outputs.items():
                with self.subTest(name=name, sample=sample):
                    needle = 'lag 조회 실패' if sample == 1 else 'lag 재조회 실패'
                    self.assert_blocked(dict(match=dict(kind='lag', group='order-target-a', sample=sample),
                                             output=output), needle)

    def test_invalid_db_aggregate_blocks_rollback(self):
        for output in ['', '0\t0\n', '-1\t0\t-\n', '0\tNaN\t-\n',
                       '0\t0\t-1\n', '0\t0\tNaN\n', '0\t0\tInfinity\n', '0\t0\t-\textra\n', '0\t0\t-\n0\t0\t-\n']:
            with self.subTest(output=output):
                self.assert_blocked(dict(match=dict(kind='db', service='order'), output=output), 'fail-closed')

    def test_db_drain_violations_reach_judge(self):
        for output, needle in [('1\t0\t-\n', '[ⓐ] order'),
                               ('0\t1\t-\n', "[ⓐ'] order"),
                               ('0\t0\t9999999999.123456\n', '[ⓓ] order')]:
            with self.subTest(output=output):
                self.assert_blocked(dict(match=dict(kind='db', service='order'), output=output), needle)

    def test_failed_recheck_wait_blocks_rollback(self):
        result, calls = self.run_fixture(interval='invalid')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('lag 재관측 대기 실패', result.stdout)
        self.assertTrue(all(c['sample'] == 1 for c in calls if c['kind'] == 'lag'))

    def test_failed_kafka_samples(self):
        for sample in [1, 2]:
            with self.subTest(sample=sample):
                self.assert_blocked(dict(match=dict(kind='lag', group='order-target-a', sample=sample), exit=1),
                                    'lag 조회 실패' if sample == 1 else 'lag 재조회 실패')

    def test_positive_lag_in_either_sample_blocks_rollback(self):
        for sample in [1, 2]:
            with self.subTest(sample=sample):
                output = HEADER + ROW + ROW.replace('order.created 0 15 15 0', 'order.created 1 12 15 3')
                self.assert_blocked(dict(match=dict(kind='lag', group='order-target-a', sample=sample), output=output),
                                    '[ⓑⓒ] order: consumer group order-target-a')


if __name__ == '__main__':
    unittest.main(verbosity=2)
