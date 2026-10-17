#!/usr/bin/env python3
"""Parser regression against saved Grafana 11.6 observations (no containers)."""
import copy
import json
from pathlib import Path
import unittest
from verify_alerts import all_normal, assert_alerting_instances, CONTRACT


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


if __name__ == '__main__':
    unittest.main()
