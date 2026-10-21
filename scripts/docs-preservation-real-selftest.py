#!/usr/bin/env python3
"""Preservation controls using unmodified tracked architecture/plan originals."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / 'scripts/docs-preservation-check.py'
# Immutable historical fixture; never silently switch to a changed current corpus.
source_commit = 'a7ffa11f24be2bd281d8952ac5fc5d351fb6119c'
with tempfile.TemporaryDirectory(prefix='docs-preservation-real-') as directory:
    repo = Path(directory) / 'repo'
    subprocess.run(['git', 'clone', '--quiet', '--shared', '--no-checkout', str(ROOT), str(repo)], check=True)
    subprocess.run(['git', 'checkout', '--quiet', '--detach', source_commit], cwd=repo, check=True)
    def check(manifest, passes):
        path = Path(directory) / 'manifest.json'
        path.write_text(json.dumps(manifest))
        result = subprocess.run([sys.executable, '-B', str(CHECK), '--root', str(repo), '--base', source_commit,
                                 '--manifest', str(path)], text=True, capture_output=True)
        assert result.returncode == (0 if passes else 1), result.stdout + result.stderr
    def read(path): return (repo / path).read_bytes().decode('utf-8')
    def write(path, text):
        destination = repo / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(text.encode())
    source = 'docs/02-architecture.md'
    original = read(source)
    lines = original.splitlines(keepends=True)
    split = next(i for i, line in enumerate(lines) if line.startswith('## 12. '))
    left, right = 'docs/architecture-direction.md', 'docs/architecture-packages.md'
    manifest = {'version': 1, 'sources': [{'path': source, 'sha256': hashlib.sha256(original.encode()).hexdigest()}],
        'destinations': [
            {'path': left, 'parts': [{'source': source, 'start': 1, 'end': split}]},
            {'path': right, 'parts': [{'source': source, 'start': split + 1, 'end': len(lines)}]}]}
    write(left, ''.join(lines[:split])); write(right, ''.join(lines[split:])); (repo / source).unlink()
    readme = read('README.md')
    assert readme.count('](docs/02-architecture.md)') == 1
    write('README.md', readme.replace('](docs/02-architecture.md)', '](docs/architecture-direction.md)'))
    check(manifest, True)
    print(f'Actual {source_commit} architecture {len(lines)} lines split {split}/{len(lines)-split}: PASS')
    left_text, right_text = read(left), read(right)
    write(left, right_text); write(right, left_text)
    check(manifest, False)
    print('Actual architecture destination ownership swap: REJECTED')
    write(left, left_text); write(right, right_text)
    # Remove exactly one repeated empty line, preserving the global line set.
    assert '\n\n' in left_text
    write(left, left_text.replace('\n\n', '\n', 1))
    check(manifest, False)
    print('Actual architecture one repeated blank-line deletion: REJECTED')
    write(left, left_text)
    # Reset only this isolated checkout; the author worktree is never mutated.
    subprocess.run(['git', 'reset', '--hard', '--quiet', source_commit], cwd=repo, check=True)
    (repo / left).unlink(); (repo / right).unlink()
    source = 'docs/plans/D-097.md'
    audit = 'docs/plans/D-097.audit.md'
    sources = [source, audit]
    originals = {path: read(path) for path in sources}
    manifest = {'version': 1, 'sources': [
        {'path': path, 'sha256': hashlib.sha256(originals[path].encode()).hexdigest()} for path in sources],
        'destinations': []}
    for path in sources:
        destination = path.replace('docs/plans/', 'docs/plans/done/')
        manifest['destinations'].append({'path': destination, 'parts': [
            {'source': path, 'start': 1, 'end': len(originals[path].splitlines(keepends=True))}]})
        write(destination, originals[path]); (repo / path).unlink()
    check(manifest, True)
    print('Actual D-097 plan/audit full ordered archive: PASS')
    destination = 'docs/plans/done/D-097.audit.md'
    archive = read(destination)
    write(destination, '\n'.join(reversed(archive.split('\n'))))
    check(manifest, False)
    print('Actual audit ordered-line reversal: REJECTED')
print('PASS: actual-source normal transfers and 3 single-fault controls, originals untouched.')
