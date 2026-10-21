#!/usr/bin/env python3
"""Exercise real extractor against actual XML and single-field corrupted copies."""
import copy
import gzip
import json
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
raw = gzip.decompress((HERE / 'probe.xml.gz').read_bytes())
original = ET.fromstring(raw)


def corrupt(label):
    root = copy.deepcopy(original)
    if label == 'junit-failure':
        root.set('failures', '1')
        return root
    output = root.find('system-out')
    lines = output.text.splitlines()
    index = next(i for i, line in enumerate(lines)
                 if line.startswith('D075_RESULT ') and json.loads(line[12:])['kind'] == 'scheduled')
    row = json.loads(lines[index][12:])
    if label == 'missing-row':
        del lines[index]
    else:
        if label == 'duplicate-repetition': row['repetition'] = 2
        elif label == 'broker-not-verified': row['brokerVerified'] = False
        elif label == 'wrong-timer-count': row['publishTimerCount'] = 299
        elif label == 'reversed-attempt-time': row['attempts'][0]['startNs'] = row['attempts'][0]['endNs'] + 1
        else: raise AssertionError(label)
        lines[index] = 'D075_RESULT ' + json.dumps(row)
    output.text = '\n'.join(lines) + '\n'
    return root


with tempfile.TemporaryDirectory(prefix='d075-extractor-') as directory:
    base = Path(directory)
    positive = base / 'normal.xml'
    positive.write_bytes(raw)
    def run(path):
        return subprocess.run([sys.executable, '-B', str(HERE / 'extract-results.py'), str(path),
                               '--out', str(base / 'out')], text=True, capture_output=True)
    result = run(positive)
    assert result.returncode == 0, result.stderr
    print('actual XML: PASS')
    for label in ('junit-failure', 'missing-row', 'duplicate-repetition', 'broker-not-verified',
                  'wrong-timer-count', 'reversed-attempt-time'):
        target = base / (label + '.xml')
        ET.ElementTree(corrupt(label)).write(target, encoding='utf-8', xml_declaration=True)
        result = run(target)
        assert result.returncode != 0 and 'AssertionError' in result.stderr, (label, result.stderr)
        print(label + ': REJECTED')
print('PASS: normal actual XML accepted, 6 analysis-corruption controls rejected.')
