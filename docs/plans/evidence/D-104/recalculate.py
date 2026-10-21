#!/usr/bin/env python3
"""Read pinned tracked audit prose; reconstruct selected matches, not original script."""
from collections import Counter
import hashlib
import json
import re
import subprocess

BASE = '6a5a47bbb8d7f717604883e8b9981430190867aa'

def git(*args):
    return subprocess.check_output(['git', *args])

paths = [p for p in git('ls-tree', '-r', '--name-only', BASE, 'docs/plans').decode().splitlines()
         if re.fullmatch(r'docs/plans/[^/]+\.audit\.md', p)]
patterns = {'severity': r'항목: (\d+)건 \(P0:(\d+), P1:(\d+), P2:(\d+)\)',
            'disposition': r'반영 (\d+)건 / 기각 (\d+)건', 'round': r'라운드 (\d+)'}
result = {'source_commit': BASE, 'selection': 'docs/plans/*.audit.md (direct children only)',
          'method': 'reconstruction from tracked prose; no deduplication; not recovery of original script',
          'files': [], 'results': {}}
for path in paths:
    raw = git('show', BASE + ':' + path)
    result['files'].append({'path': path, 'sha256': hashlib.sha256(raw).hexdigest()})
for key, pattern in patterns.items():
    matches = []
    for path in paths:
        text = git('show', BASE + ':' + path).decode('utf-8')
        for line, content in enumerate(text.splitlines(), 1):
            for match in re.finditer(pattern, content):
                matches.append({'path': path, 'line': line, 'values': list(map(int, match.groups())),
                                'matched_text': match.group(0)})
    item = {'pattern': pattern, 'count': len(matches), 'matches': matches}
    if key == 'round':
        item['frequency'] = dict(sorted(Counter(m['values'][0] for m in matches).items()))
    else:
        item['sums'] = [sum(m['values'][i] for m in matches) for i in range(len(matches[0]['values']))]
    result['results'][key] = item
assert len(paths) == 18
assert result['results']['severity']['count'] == 56
assert result['results']['severity']['sums'] == [442, 9, 297, 136]
assert result['results']['disposition']['count'] == 41
assert result['results']['disposition']['sums'] == [306, 1]
assert result['results']['round']['frequency'][3] == 20
print(json.dumps(result, ensure_ascii=False, indent=2))
