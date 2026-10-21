#!/usr/bin/env python3
"""Internal selected archive writer; called only after plans-archive eligibility checks."""
import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import quote, urlsplit

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('preservation', Path(__file__).with_name('docs-preservation-check.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--select', nargs='+', required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    output = Path(args.manifest)
    module.require(not output.is_absolute() and output.parent == Path('docs/preservation')
                   and output.suffix == '.json' and '..' not in output.parts,
                   'Manifest must be docs/preservation/<name>.json')
    manifest_path = root / output
    module.require(manifest_path.resolve().is_relative_to(root), 'Manifest escapes repository')
    initial = {'version': 1}
    if manifest_path.exists():
        initial = json.loads(manifest_path.read_bytes())
        module.require(set(initial) <= {'version', 'corrections'} and initial.get('version') == 1,
                       'Existing manifest may contain only explicit narrow correction allowances')
    revision = subprocess.check_output(['git', 'rev-parse', '--verify', args.base + '^{commit}'],
                                       cwd=root, text=True).strip()
    checker = module.Checker(root, revision, [manifest_path] if manifest_path.exists() else [])
    sources = []
    selected = set()
    for stem in args.select:
        module.require(isinstance(stem, str) and re.fullmatch(r'[A-Za-z0-9._-]+', stem) and not stem.startswith(('.', '-'))
                       and '..' not in stem and '/' not in stem and not stem.endswith('.audit'),
                       'Invalid selected plan stem')
        module.require(stem not in selected, 'Duplicate selected plan')
        selected.add(stem)
        plan = f'docs/plans/{stem}.md'
        module.require((root / plan).is_file(), f'Selected plan not found: {plan}')
        sources.append(plan)
        audit = f'docs/plans/{stem}.audit.md'
        if (root / audit).exists(): sources.append(audit)
    routes = {path: path.replace('docs/plans/', 'docs/plans/done/', 1) for path in sources}
    for target in routes.values():
        module.require(not (root / target).exists(), f'Archive destination already exists: {target}')
    checker.tasks()  # Verify pending row transfers/footer before any mutation.

    def rewrite(text, old_path, new_path):
        edits = []
        for start, end, target in module.links(text):
            resolved = checker.resolve(old_path, target)
            if resolved is None: continue  # Historical URLs are never current-path substitutions.
            dest, fragment, query = resolved
            dest = routes.get(dest, dest)
            if old_path == new_path and dest == resolved[0]: continue
            parsed = urlsplit(target)
            if not parsed.path and dest == new_path:
                path = ''
            else:
                path = quote(os.path.relpath(root / dest, (root / new_path).parent), safe='/._-~')
            replacement = path + ('?' + query if query else '') + ('#' + quote(fragment, safe='-._~') if fragment else '')
            if text[start:end].startswith('<'): replacement = '<' + replacement + '>'
            edits.append((start, end, replacement))
        for start, end, value in reversed(edits): text = text[:start] + value + text[end:]
        return text

    manifest = {'version': 1, 'sources': [], 'destinations': []}
    if initial.get('corrections'): manifest['corrections'] = initial['corrections']
    updates = {}
    for source, target in routes.items():
        original = checker.before(source)
        corrected = checker.corrected(source)
        current = checker.current(source)
        module.require(current.startswith(corrected), f'Non-append source edit needs a narrow correction allowance: {source}')
        lines = corrected.splitlines(keepends=True)
        module.require(lines, f'Empty source cannot be archived: {source}')
        manifest['sources'].append({'path': source, 'sha256': module.sha(original)})
        parts = [{'source': source, 'start': 1, 'end': len(lines)}]
        addition = current[len(corrected):]
        if addition: parts.append({'literal': rewrite(addition, source, target)})
        manifest['destinations'].append({'path': target, 'parts': parts})
        updates[target] = rewrite(current, source, target)
    tracked = subprocess.check_output(['git', 'ls-files', '-z', '*.md'], cwd=root).decode().split('\0')
    for path in tracked:
        if not path or path in routes or not (root / path).is_file(): continue
        text = checker.current(path)
        if not module.references_source(text, routes): continue
        rewritten = rewrite(text, path, path)
        if rewritten != text: updates[path] = rewritten

    # Keep exact current preimages, including uncommitted appends, if validation fails.
    (root / '.cache').mkdir(exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix='docs-archive-backup-', dir=root / '.cache'))
    for path in [*sources, *(p for p in updates if p not in routes.values())]:
        target = backup / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / path, target)
    if manifest_path.exists(): shutil.copyfile(manifest_path, backup / 'input-manifest.json')
    for source, target in routes.items():
        (root / target).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'mv', '--', source, target], cwd=root, check=True)
    for path, text in updates.items(): (root / path).write_bytes(text.encode())
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    module.Checker(root, revision, [manifest_path]).run()
    print(f'Archive preservation PASS: {len(sources)} files; manifest={output}; preimages={backup.relative_to(root)}')
    print('Stage the declared files/manifest, and check CI against the same comparison base. No commit/push performed.')


if __name__ == '__main__':
    try:
        main()
    except (module.Violation, ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        print('[archive] FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
