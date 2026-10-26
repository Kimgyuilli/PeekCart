#!/usr/bin/env python3
"""Recheck the comment-only delta after merging an explicit newer main base."""
import argparse
import importlib.util
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('d102', Path(__file__).with_name('check.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
original_base = module.BASE
module.BASE = subprocess.check_output(['git', 'rev-parse', '--verify', args.base + '^{commit}'],
                                      cwd=module.ROOT, text=True).strip()
module.verify()
reviewed = '3d1c96ce9ee4f9181ab3b171ecbcadb17d531fea'
changed = subprocess.check_output(['git', 'diff', '--name-only', original_base, reviewed],
                                  cwd=module.ROOT, text=True).splitlines()
changed = [p for p in changed if re.fullmatch(r'[^/]+/src/test/.*\.java', p)]
assert len(changed) == 116, 'original authored population changed'
texts = {}
for path in changed:
    for label, revision in [('before', original_base), ('reviewed', reviewed)]:
        texts[path, label] = subprocess.check_output(['git', 'show', revision + ':' + path],
                                                    cwd=module.ROOT, text=True)
    texts[path, 'merged'] = (module.ROOT / path).read_text()
module.scan_all(texts.values())


def lines(text):
    return {line.strip() for _, gap in module.comments(text)
            for line in gap.splitlines() if line.strip()}


for path in changed:
    added = lines(texts[path, 'reviewed']) - lines(texts[path, 'before'])
    assert added <= lines(texts[path, 'merged']), 'Original rewritten comment text lost: ' + path
print('PASS: every newly rewritten comment line remains in its original class (116 classes); '
      'line presence check, not a duplicate-count or semantic oracle.')
