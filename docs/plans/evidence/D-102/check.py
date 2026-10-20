#!/usr/bin/env python3
"""D-102 reproducible comment inventory and token comparison; no CI wiring."""
import argparse
import csv
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
BASE = 'be535c57ced11acfcbe9a24a349ddb6ad2055d8a'
LEX = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|\s+|[A-Za-z_$][\w$]*|\d[\w.]*|[^\s]')
BROAD = re.compile(r'구현\s*[①②③④⑤⑥]|PR\d|(?<![A-Z])P\d|loop\d|계획|[①②③④⑤⑥]-|diff 리뷰|리뷰\s*[123]R|strangler')
EXTRA = re.compile(r'(?:GW|GP)-\d|\bc[123]:\d|\b[123]R\s*#|\b(?:R|C|N|U|B)-?\d+[a-z]?\b|§\d')
SOURCE_ID = re.compile(r'ADR-\d{4}|(?<![A-Za-z])(?:D|L)-\d{3}\b')


def comments(text):
    return [m for m in LEX.finditer(text) if m.group().startswith(('//', '/*'))]


def tokens(text):
    return [m.group() for m in LEX.finditer(text)
            if not m.group().isspace() and not m.group().startswith(('//', '/*'))]


def candidates(text):
    result = []
    for match in comments(text):
        start = text[:match.start()].count('\n') + 1
        for offset, line in enumerate(match.group().splitlines()):
            if BROAD.search(line) or EXTRA.search(line):
                result.append((start + offset, line.strip()))
    return result


def git_text(path):
    return subprocess.check_output(['git', '-C', str(ROOT), 'show', BASE + ':' + path], text=True)


def paths():
    return sorted(str(p.relative_to(ROOT)) for p in ROOT.glob('*/src/test/**/*.java'))


def verify():
    baseline_paths = subprocess.check_output(["git", "-C", str(ROOT), "ls-tree", "-r", "--name-only", BASE], text=True).splitlines()
    baseline_paths = sorted(p for p in baseline_paths if re.fullmatch(r"[^/]+/src/test/.*\.java", p))
    assert baseline_paths == paths(), "JAVA TEST FILE ADDED OR REMOVED"
    changed = 0
    for path in paths():
        before, after = git_text(path), (ROOT / path).read_text()
        assert tokens(before) == tokens(after), 'EXECUTABLE TOKENS CHANGED: ' + path
        old_ids = set(SOURCE_ID.findall('\n'.join(m.group() for m in comments(before))))
        new_ids = set(SOURCE_ID.findall('\n'.join(m.group() for m in comments(after))))
        assert old_ids <= new_ids, 'SOURCE IDS LOST: ' + path + ': ' + str(old_ids - new_ids)
        changed += before != after
    probe = 'class Probe { String value = "계획 P24"; String block = """\n// 계획 P24\n"""; int n = 1; }'
    assert not candidates(probe), 'string/text block false positive'
    assert candidates(probe + '\n// 계획 P24'), 'opaque comment false negative'
    assert not candidates(probe + '\n// ADR-0022 §D4 · D-050'), 'qualified source false positive'
    assert tokens(probe) != tokens(probe.replace('int n = 1', 'int n = 2')), 'code mutation missed'
    assert tokens(probe) == tokens(probe + '\n// changed comment'), 'comment-only change rejected'
    print(f'PASS: {len(paths())} Java test files; {changed} changed files; tokens and source IDs preserved; 5 injected controls')


def inventory(destination):
    with Path(destination).open('w') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['path', 'base_line', 'disposition', 'before', 'after'])
        for path in paths():
            before = git_text(path)
            after = (ROOT / path).read_text().splitlines()
            assert len(before.splitlines()) == len(after), 'inventory line mapping changed: ' + path
            for line, text in candidates(before):
                final = after[line - 1].strip()
                disposition = 'rewrite' if text != final else 'retain: source/local-scenario/technical-word'
                writer.writerow([path, line, disposition, text, final])
    print('Inventory written:', destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory')
    parser.add_argument('--scan', action='store_true')
    args = parser.parse_args()
    if args.scan:
        for path in paths():
            for line, text in candidates((ROOT / path).read_text()):
                print(f'{path}:{line}:{text}')
    elif args.inventory:
        inventory(args.inventory)
    else:
        verify()
