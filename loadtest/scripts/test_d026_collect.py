#!/usr/bin/env python3
"""Local fixture checks only: never executes the cluster/load-generator runners."""
import csv
import io
import json
import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

COLLECTOR = Path(__file__).with_name('d026-collect.py')
SECTION = '=== 캐시 이름별 차분 (미수집은 0이 아님) ==='


def metadata():
    return {
        'pod_uid': 'same-pod', 'pod_uid_after': 'same-pod', 'ep': 'detail',
        'cache_env': 'true', 'dur': '60s', 'mysql_cpu_limit': '500m',
        'hit_before': '100', 'hit_after': '180', 'miss_before': '20', 'miss_after': '40',
        'product_throttled_before': '0', 'product_throttled_after': '100000',
        'mysql_throttled_before': '0', 'mysql_throttled_after': '200000',
        'mysql_usage_before': '0', 'mysql_usage_after': '3000000',
    }


def named(meta, name, hit_before, hit_after, miss_before, miss_after):
    for key, value in zip(('hit_before', 'hit_after', 'miss_before', 'miss_after'),
                          (hit_before, hit_after, miss_before, miss_after)):
        meta[f'{key}_{name}'] = str(value)
    return meta


class CacheCollectorFixtureTest(unittest.TestCase):
    def collect(self, runs):
        with tempfile.TemporaryDirectory(prefix='d078-fixture-') as directory:
            for label, meta, rps in runs:
                stem = Path(directory) / label
                stem.with_suffix('.meta').write_text(''.join(f'{k}\t{v}\n' for k, v in meta.items()))
                stem.with_suffix('.json').write_text(json.dumps({'metrics': {
                    'http_reqs': {'rate': rps}, 'http_req_duration': {'p(95)': 12, 'avg': 10},
                    'http_req_failed': {'value': 0},
                }}))
            result = subprocess.run([sys.executable, str(COLLECTOR), directory],
                                    text=True, capture_output=True, check=True)
        rows = list(csv.DictReader(io.StringIO(result.stdout.split(SECTION, 1)[1].lstrip()), delimiter='\t'))
        return result.stdout, {(r['block'], r['ep'], r['mode'], r['cache']): r for r in rows}

    def test_names_and_medians_are_independent(self):
        runs = []
        for rep, hit, miss, rps in [(1, 10, 40, 450), (2, 20, 30, 460), (3, 30, 20, 470)]:
            m = metadata()
            named(m, 'product', 100, 100 + hit, 200, 200 + miss)
            named(m, 'products', 0, 1000, 0, 100)
            named(m, 'productStock', 10, 19, 20, 21)
            runs.append((f'mysqlA-detail-cacheon-r{rep}', m, rps))
        _, rows = self.collect(runs)
        product = rows['A', 'detail', 'ON', 'product']
        self.assertEqual((product['hit_median'], product['miss_median'], product['hitrate_median']),
                         ('20.0', '30.0', '40.0%'))
        self.assertEqual(rows['A', 'detail', 'ON', 'products']['hitrate_median'], '90.9%')
        self.assertEqual(rows['A', 'detail', 'ON', 'productStock']['hitrate_median'], '90.0%')
        self.assertEqual((product['valid_runs'], product['total_runs'], product['hitrate_runs']), ('3', '3', '3'))

    def test_historical_missing_product_is_unknown_not_zero(self):
        m = metadata()
        named(m, 'products', 0, 12, 0, 3)
        named(m, 'productStock', 0, 4, 0, 1)
        _, rows = self.collect([('mysqlA-detail-cacheon-r1', m, 460)])
        row = rows['A', 'detail', 'ON', 'product']
        self.assertEqual((row['valid_runs'], row['total_runs'], row['hit_median'], row['hitrate_median']),
                         ('0', '1', '미수집', '미수집'))
        self.assertEqual((row['invalid_runs'], row['uncollected_runs']), ('0', '1'))
        self.assertEqual(rows['A', 'detail', 'ON', 'products']['valid_runs'], '1')

    def test_invalid_named_counters_never_become_valid_zero(self):
        for bad in ['', 'not-a-number', 'unavailable', 'NaN', 'Infinity', '-1', '1.5']:
            with self.subTest(bad=bad):
                m = metadata()
                named(m, 'product', 0, bad, 0, 4)
                named(m, 'products', 0, 12, 0, 3)
                output, rows = self.collect([('mysqlA-detail-cacheon-r1', m, 460)])
                row = rows['A', 'detail', 'ON', 'product']
                self.assertEqual((row['valid_runs'], row['hit_median']), ('0', '미수집'))
                self.assertEqual((row['invalid_runs'], row['uncollected_runs']), ('1', '0'))
                self.assertIn('캐시 차분만 제외', output)
                self.assertEqual(rows['A', 'detail', 'ON', 'products']['valid_runs'], '1')

    def test_partial_and_reset_are_not_hidden_by_positive_aggregate(self):
        partial = metadata()
        named(partial, 'product', 0, 5, 0, 1)
        del partial['miss_after_product']
        reset = metadata()
        named(reset, 'product', 80, 1, 0, 1)
        miss_reset = named(metadata(), 'product', 0, 5, 80, 1)
        for m, reason in [(partial, '부분 누락'), (reset, 'counter 역전'), (miss_reset, 'counter 역전')]:
            with self.subTest(reason=reason):
                output, rows = self.collect([('mysqlA-detail-cacheon-r1', m, 460)])
                row = rows['A', 'detail', 'ON', 'product']
                self.assertEqual((row['valid_runs'], row['total_runs'], row['miss_median']), ('0', '1', '미수집'))
                self.assertIn(reason, output)
                self.assertIn('80.0%', output)  # Existing aggregate stays independently valid.

    def test_zero_denominator_and_mixed_activity_counts(self):
        runs = []
        for rep, delta in [(1, 0), (2, 10), (3, 20)]:
            m = metadata()
            named(m, 'product', 0, delta, 0, delta)
            named(m, 'products', 0, 0, 0, 0)
            runs.append((f'mysqlA-detail-cacheon-r{rep}', m, 460))
        _, rows = self.collect(runs)
        active = rows['A', 'detail', 'ON', 'product']
        zero = rows['A', 'detail', 'ON', 'products']
        self.assertEqual((active['valid_runs'], active['hitrate_runs'], active['hitrate_median']), ('3', '2', '50.0%'))
        self.assertEqual((zero['valid_runs'], zero['hitrate_runs'], zero['hit_median'], zero['hitrate_median']),
                         ('3', '0', '0.0', '분모0'))

    def test_partial_coverage_counts_only_valid_named_samples(self):
        good = named(metadata(), 'product', 0, 8, 0, 2)
        bad = named(metadata(), 'product', 9, 1, 0, 2)
        _, rows = self.collect([('mysqlA-detail-cacheon-r1', good, 460),
                                ('mysqlA-detail-cacheon-r2', bad, 460),
                                ('mysqlA-detail-cacheon-r3', metadata(), 460)])
        row = rows['A', 'detail', 'ON', 'product']
        self.assertEqual((row['invalid_runs'], row['uncollected_runs']), ('1', '1'))
        self.assertEqual((row['valid_runs'], row['total_runs'], row['hitrate_runs'],
                          row['hit_median'], row['hitrate_median']), ('1', '3', '1', '8.0', '80.0%'))

    def test_before_and_after_capture_include_all_three_names(self):
        runner = COLLECTOR.with_name('d026-run-cell.sh').read_text()
        loops = re.findall(r'^for C in ([^;]+); do$', runner, re.M)
        self.assertEqual(loops, ['product products productStock'] * 2)

    def test_named_capture_with_mock_scrape_only(self):
        # Extract one function; never source or execute the operational runner.
        runner = COLLECTOR.with_name('d026-run-cell.sh').read_text()
        serializer = re.search(r'^meta\(\) \{[^\n]+', runner, re.M).group()
        function = re.search(r'^cachegets\(\) \{.*?^\}', runner, re.M | re.S).group()
        for body, status, expected in [
                ('cache_gets_total{name="product",result="hit"} 12.0\n'
                 'cache_gets_total{name="products",result="hit"} 999\n'
                 'cache_gets_total{name="product",result="miss"} 4\n', 0, '12'),
                ('cache_gets_total{name="product",result="hit",instance="a"} 1e2\n'
                 'cache_gets_total{name="product",result="hit",instance="b"} 20\n', 0, '120'),
                ('cache_gets_total{name="product",result="hit"} 0\n', 0, '0'),
                ('', 0, 'unavailable'),
                ('cache_gets_total{name="products",result="hit"} 9\n', 0, 'unavailable'),
                ('cache_gets_total{cache_name="product",result="hit"} 9\n', 0, 'unavailable'),
                ('cache_gets_total{name="product",result="hit"} 12\n', 1, 'unavailable'),
                *[(f'cache_gets_total{{name="product",result="hit"}} {bad}\n', 0, 'unavailable')
                  for bad in ('NaN', '+Inf', '-1', '1.5', 'wrong')]]:
            with self.subTest(body=body, status=status):
                with tempfile.TemporaryDirectory(prefix='d078-scrape-') as directory:
                    fixture = Path(directory) / 'prometheus.txt'
                    fixture.write_text(body)
                    script = ('set -uo pipefail\n'
                              f'prom() {{ cat {shlex.quote(str(fixture))}; return {status}; }}\n'
                              + serializer + '\n' + function
                              + f'\nMETA={shlex.quote(str(Path(directory) / "out.meta"))}\n'
                              + 'meta hit_after_product "$(cachegets hit product)"\ncat "$META"\n')
                    result = subprocess.run(['bash', '-c', script], text=True,
                                            capture_output=True, check=True)
                self.assertEqual(result.stdout, f'hit_after_product\t{expected}\n')

    def test_legacy_gate_outcomes_remain_explicit(self):
        runs = []
        for block, ep, on, rps in [('A', 'detail', False, 378.1), ('A', 'list', False, 380.0),
                                    ('A', 'list', True, 767.1), ('A2', 'detail', False, 420.0)]:
            m = metadata()
            m.update(ep=ep, cache_env=str(on).lower())
            runs.append((f'mysql{block}-{ep}-cache{str(on).lower()}-r1', m, rps))
        output, _ = self.collect(runs)
        gates = [line.strip() for line in output.splitlines() if line.strip().startswith(('[통과]', '[실패]'))]
        self.assertEqual(gates, [
            '[통과] 재현 detail OFF: 378.1 vs 기준 378.1 (+0.0%, 허용 ±10%)',
            '[통과] 재현 list OFF: 380.0 vs 기준 380.0 (+0.0%, 허용 ±10%)',
            '[통과] 대조군 list ON: 767.1 vs 기준 767.1 (+0.0%, 허용 ±15%)',
            '[실패] 되돌림 detail OFF (A′ vs A): 420.0 vs 기준 378.1 (+11.1%, 허용 ±10%)',
        ])


if __name__ == '__main__':
    unittest.main()
