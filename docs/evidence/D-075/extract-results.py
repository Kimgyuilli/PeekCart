#!/usr/bin/env python3
"""Validate actual probe XML, retain rows and summarize bounded fixture drains."""
import argparse
import hashlib
import json
import statistics
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('xml', type=Path)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
root = ET.parse(args.xml).getroot()
assert {key: int(root.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')} == {
    'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0}, 'Probe did not pass'
assert len(root.findall('testcase')) == 1
assert root.find('testcase').attrib['classname'].endswith('.OutboxThroughputLocalProbeTest')
raw_rows = [line.removeprefix('D075_RESULT ') for line in root.findtext('system-out', '').splitlines()
            if line.startswith('D075_RESULT ')]
rows = [json.loads(line) for line in raw_rows]
assert len(rows) == 53, ('Incomplete/extra probe rows', len(rows))
by_kind = defaultdict(list)
for row in rows:
    by_kind[row['kind']].append(row)
assert {k: len(v) for k, v in by_kind.items()} == {
    'environment': 1, 'warmup': 20, 'poller-warmup': 12, 'scheduled': 12,
    'producer-only': 6, 'save-delay-control': 1, 'duplicate-negative-control': 1}
assert by_kind['environment'][0]['records'] == 300
assert by_kind['environment'][0]['repetitions'] == 3
profiles = {'baseline': (100, 5000, 4000), 'delay-only': (100, 500, 4000),
            'larger-batch': (500, 500, 4000), 'no-min-lock-control': (100, 500, 0)}
groups = defaultdict(list)
for row in by_kind['scheduled']:
    assert (row['batch'], row['delayMs'], row['minLockMs']) == profiles[row['profile']]
    assert row['records'] == row['saveCalls'] == row['publishTimerCount'] == 300
    assert row['brokerVerified'] is True and row['dbVerified'] is True
    assert row['findCalls'] == (300 + row['batch'] - 1) // row['batch']
    previous_end = 0
    for attempt in row['attempts']:
        assert 0 <= previous_end <= attempt['startNs'] <= attempt['endNs'] <= row['wallNs']
        previous_end = attempt['endNs']
        if attempt['executed']:
            assert attempt['startNs'] <= attempt['bodyStartNs'] <= attempt['bodyEndNs'] <= attempt['endNs']
        else:
            assert attempt['bodyStartNs'] == attempt['bodyEndNs'] == 0
    assert sum(a['executed'] for a in row['attempts']) == row['findCalls']
    assert 0 < row['findNs'] + row['saveNs'] + row['publishTimerNs'] <= row['pollBodyNs'] <= row['attemptNs'] <= row['wallNs']
    row['schedulerWaitAndLifecycleNs'] = row['wallNs'] - row['attemptNs']
    row['lockDispatchOverheadNs'] = row['attemptNs'] - row['pollBodyNs']
    row['pollResidualNs'] = row['pollBodyNs'] - row['findNs'] - row['saveNs'] - row['publishTimerNs']
    row['lockSkips'] = sum(not a['executed'] for a in row['attempts'])
    groups[row['profile']].append(row)
for name, trials in groups.items():
    assert sorted(t['repetition'] for t in trials) == [1, 2, 3], name
for window in (1, 32):
    trials = [r for r in by_kind['producer-only'] if r['window'] == window]
    assert sorted(t['repetition'] for t in trials) == [1, 2, 3]
    assert all(t['records'] == 300 and t['wallNs'] > 0 and t['brokerVerified'] is True for t in trials)
control = by_kind['save-delay-control'][0]
assert control['saveCalls'] == control['records'] == 30 and control['saveNs'] >= 150_000_000
assert control['brokerVerified'] is True
assert by_kind['duplicate-negative-control'][0]['rejected'] is True
args.out.mkdir(parents=True, exist_ok=True)
(args.out / 'raw-results.jsonl').write_text('\n'.join(raw_rows) + '\n')
(args.out / 'results.json').write_text(json.dumps({'xml_sha256': hashlib.sha256(args.xml.read_bytes()).hexdigest(),
    'suite': root.attrib, 'rows': rows}, indent=2, ensure_ascii=False) + '\n')
summary = ['# Bounded local fixture results', '',
           'These are 300-record drains, not a steady-state or acceptance throughput target.', '',
           '| profile | wall ms median (min/max) | poll ms | find ms | save ms | publish timer ms | lock skips min/max |',
           '|---|---:|---:|---:|---:|---:|---:|']
for name in profiles:
    trials = groups[name]
    median = lambda key: statistics.median(t[key] for t in trials) / 1e6
    walls = [t['wallNs'] / 1e6 for t in trials]
    skips = [t['lockSkips'] for t in trials]
    summary.append(f'| {name} | {statistics.median(walls):.1f} ({min(walls):.1f}/{max(walls):.1f}) | '
                   f'{median("pollBodyNs"):.1f} | {median("findNs"):.1f} | {median("saveNs"):.1f} | '
                   f'{median("publishTimerNs"):.1f} | {min(skips)}/{max(skips)} |')
summary.extend(['', '| producer-only window | wall ms median (min/max) |', '|---|---:|'])
for window in (1, 32):
    values = [t['wallNs'] / 1e6 for t in by_kind['producer-only'] if t['window'] == window]
    summary.append(f'| {window} | {statistics.median(values):.1f} ({min(values):.1f}/{max(values):.1f}) |')
(args.out / 'summary.md').write_text('\n'.join(summary) + '\n')
print('\n'.join(summary))
print('PASS: 53 actual rows validated, all repetitions and controls present.')
