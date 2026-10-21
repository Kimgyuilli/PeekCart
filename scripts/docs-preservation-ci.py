#!/usr/bin/env python3
"""Run preservation against an explicit CI comparison base and new manifests."""
import argparse
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.root.resolve()
    revision = subprocess.run(['git', 'rev-parse', '--verify', args.base + '^{commit}'], cwd=root,
                              capture_output=True, text=True)
    if revision.returncode:
        print('Cannot resolve preservation base: ' + revision.stderr.strip(), file=sys.stderr)
        return 2
    base = revision.stdout.strip()
    result = subprocess.run(['git', 'diff', '--name-only', '-z', '--diff-filter=ACMRD', base,
                             '--', 'docs/preservation'], cwd=root, capture_output=True)
    if result.returncode:
        print('Cannot enumerate preservation manifests', file=sys.stderr)
        return 2
    manifests = []
    for path in result.stdout.decode().split('\0'):
        if not path.endswith('.json'): continue
        if not (root / path).is_file() or not (root / path).resolve().is_relative_to(root):
            print('Missing/unsafe preservation manifest: ' + path, file=sys.stderr)
            return 1
        old = subprocess.run(['git', 'show', f'{base}:{path}'], cwd=root, capture_output=True)
        if old.returncode == 0:
            if old.stdout != (root / path).read_bytes():
                print('Historical manifest must remain unchanged: ' + path, file=sys.stderr)
                return 1
        else:
            manifests += ['--manifest', str(root / path)]
    command = [sys.executable, '-B', str(Path(__file__).with_name('docs-preservation-check.py')),
               '--root', str(root), '--base', base, *manifests]
    return subprocess.run(command).returncode


if __name__ == '__main__':
    sys.exit(main())
