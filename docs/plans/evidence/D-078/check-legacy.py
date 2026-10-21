#!/usr/bin/env python3
"""Compare old aggregate/gates using local fixtures; no operational runners."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

BASE = 'af60cd6d9dceac541b6f457062093df70753f3ce'
ROOT = Path(__file__).resolve().parents[4]
SECTION = '=== 캐시 이름별 차분 (미수집은 0이 아님) ==='
legacy = subprocess.run(['git', 'show', BASE + ':loadtest/scripts/d026-collect.py'],
                        cwd=ROOT, text=True, capture_output=True, check=True).stdout
with tempfile.TemporaryDirectory(prefix='d078-legacy-') as directory:
    fixture = Path(directory) / 'fixtures'
    fixture.mkdir()
    old = Path(directory) / 'old.py'
    old.write_text(legacy)
    for block, ep, on, rps in [('A', 'detail', False, 378.1), ('A', 'detail', True, 463.6),
                              ('A', 'list', False, 380.0), ('A', 'list', True, 767.1),
                              ('A2', 'detail', False, 420.0), ('A2', 'list', False, 380.0)]:
        for rep in range(1, 4):
            meta = {'pod_uid': 'fixture', 'pod_uid_after': 'fixture', 'ep': ep,
                    'cache_env': str(on).lower(), 'dur': '60s', 'mysql_cpu_limit': '500m',
                    'hit_before': 100, 'hit_after': 180, 'miss_before': 20, 'miss_after': 40,
                    'product_throttled_before': 0, 'product_throttled_after': 100000,
                    'mysql_throttled_before': 0, 'mysql_throttled_after': 200000,
                    'mysql_usage_before': 0, 'mysql_usage_after': 3000000}
            # Distinct valid, invalid and historical-uncollected named samples;
            # none should change the independently supplied legacy aggregate.
            if rep < 3:
                meta.update(hit_before_product=0, hit_after_product=8 if rep == 1 else 'unavailable',
                            miss_before_product=0, miss_after_product=2)
            stem = fixture / f'mysql{block}-{ep}-cache{str(on).lower()}-r{rep}'
            stem.with_suffix('.meta').write_text(''.join(f'{k}\t{v}\n' for k, v in meta.items()))
            stem.with_suffix('.json').write_text(json.dumps({'metrics': {
                'http_reqs': {'rate': rps + rep - 2},
                'http_req_duration': {'p(95)': 12, 'avg': 10}, 'http_req_failed': {'value': 0}}}))
    def run(script):
        return subprocess.run([sys.executable, str(script), str(fixture)],
                              text=True, capture_output=True, check=True).stdout
    before = run(old)
    current = run(ROOT / 'loadtest/scripts/d026-collect.py')
    prefix = current.split(SECTION, 1)[0]
    after = ''.join(line for line in prefix.splitlines(keepends=True)
                    if not line.startswith('!! 캐시 차분만 제외:')).rstrip('\n') + '\n'
    assert before == after, 'Legacy aggregate/gate output changed'
    print(before, end='')
    print('PASS: 18 fixture runs, legacy aggregate/gates byte-identical '
          '(new per-name warning lines and appended table excluded).')
    print('legacy-output-sha256=' + hashlib.sha256(before.encode()).hexdigest())
