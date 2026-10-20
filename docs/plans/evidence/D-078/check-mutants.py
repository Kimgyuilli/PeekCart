#!/usr/bin/env python3
"""Wrong-implementation controls on temporary copies, no operational runners."""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = ROOT / 'loadtest/scripts'
MUTANTS = [
    ('capture-omits-product', 'd026-run-cell.sh',
     'for C in product products productStock; do', 'for C in products productStock; do', 2),
    ('missing-scrape-becomes-zero', 'd026-run-cell.sh',
     'if (!n || bad)', 'if (bad)', 1),
    ('historical-absence-becomes-zero', 'd026-collect.py',
     'return None, None  # Historical metadata did not collect this cache name.',
     'return {"hit": 0, "miss": 0, "hitrate": 0}, None', 1),
    ('reset-accepted', 'd026-collect.py', 'if hit < 0 or miss < 0:', 'if False:', 2),
    ('zero-denominator-included-in-hitrate', 'd026-collect.py',
     '"hitrate": hit / total if total else None', '"hitrate": hit / total if total else 0', 1),
]
for label, filename, before, after, count in MUTANTS:
    with tempfile.TemporaryDirectory(prefix='d078-mutant-') as directory:
        temporary = Path(directory)
        for name in ('d026-run-cell.sh', 'd026-collect.py', 'test_d026_collect.py'):
            shutil.copyfile(SCRIPTS / name, temporary / name)
        target = temporary / filename
        source = target.read_text()
        assert source.count(before) == count, (label, 'mutation anchor changed')
        target.write_text(source.replace(before, after, 1))
        result = subprocess.run([sys.executable, '-B', str(temporary / 'test_d026_collect.py')],
                                text=True, capture_output=True)
        assert result.returncode != 0 and 'FAIL:' in result.stderr, (label, result.stderr)
        failures = [line for line in result.stderr.splitlines() if line.startswith('FAIL:')]
        print(label + ': REJECTED; ' + '; '.join(failures))
print('PASS: all 5 wrong-implementation controls rejected; original files untouched.')
