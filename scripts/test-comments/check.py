#!/usr/bin/env python3
"""Read-only D-102 inventory and exact JDK 17 compiler-token comparison; no CI wiring."""
import argparse
import csv
import io
import re
import struct
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = 'be535c57ced11acfcbe9a24a349ddb6ad2055d8a'
BROAD = re.compile(r'구현\s*[①②③④⑤⑥]|PR\d|(?<![A-Z])P\d|loop\d|계획|[①②③④⑤⑥]-|diff 리뷰|리뷰\s*[123]R|strangler')
EXTRA = re.compile(r'(?:GW|GP)-\d|\bc[123]:\d|\b[123]R\s*#|\b(?:R|C|N|U|B)-?\d+[a-z]?\b|§\d')
SOURCE_ID = re.compile(r'ADR-\d{4}|(?<![A-Za-z])(?:D|L)-\d{3}\b')
EXPORTS = ['--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED',
           '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED']
CACHE = {}


def scan_all(sources):
    """Compiler owns token boundaries, Unicode translation and text-block recognition."""
    unique = list(dict.fromkeys(s for s in sources if s not in CACHE))
    if not unique:
        return
    with tempfile.TemporaryDirectory(prefix='d102-javac-') as directory:
        temp = Path(directory)
        subprocess.run(['javac', *EXPORTS, '-d', directory,
                        str(Path(__file__).with_name('JavaTokenScanner.java'))], check=True)
        files = []
        for number, source in enumerate(unique):
            path = temp / f'source-{number}.java'
            path.write_text(source)
            files.append(str(path))
        result = subprocess.run(['java', *EXPORTS, '-cp', directory, 'JavaTokenScanner', *files],
                                check=True, stdout=subprocess.PIPE)
        stream = io.BytesIO(result.stdout)

        def integer():
            return struct.unpack('>i', stream.read(4))[0]

        def text():
            return stream.read(integer()).decode('utf-8')

        assert integer() == len(unique), 'scanner result population mismatch'
        for source in unique:
            parts = [(text(), text()) for _ in range(integer())]
            gaps = [(integer(), text()) for _ in range(integer())]
            CACHE[source] = (parts, gaps)
        assert stream.read() == b'', 'unexpected scanner trailing output'


def tokens(source):
    return CACHE[source][0]


def comments(source):
    # Gaps contain only material the Java compiler skipped (comments and whitespace),
    # so string/text-block contents never enter the comment candidate search.
    return CACHE[source][1]


def candidates(source):
    result = []
    for start, gap in comments(source):
        for offset, line in enumerate(gap.splitlines()):
            if BROAD.search(line) or EXTRA.search(line):
                result.append((start + offset, line.strip()))
    return result


def git_text(path):
    return subprocess.check_output(['git', '-C', str(ROOT), 'show', BASE + ':' + path], text=True)


def paths():
    return sorted(str(p.relative_to(ROOT)) for p in ROOT.glob('*/src/test/**/*.java'))


def controls():
    probe = 'class Probe { String value = "계획 P24"; String block = """\n// 계획 P24\n"""; int n = 1; }'
    increment = 'class Probe { int f(int a,int b){return a + ++b;} }'
    unicode_comment = 'class Probe {// safe\n int x=1;}'
    escaped_block = 'class Probe { String block = """\n escaped \\"""\n// 계획 P24\n"""; }'
    mutations = [probe.replace('int n = 1', 'int n = 2'), probe + '\n// changed comment',
                 probe + '\n// 계획 P24', probe + '\n// ADR-0022 §D4 · D-050',
                 increment.replace('a + ++b', 'a++ + b'),
                 unicode_comment.replace('// safe', '// safe \\u000a int y=99;'),
                 escaped_block.replace('계획 P24', '계획 P25')]
    scan_all([probe, increment, unicode_comment, escaped_block, *mutations])
    assert tokens(probe) != tokens(mutations[0]), 'assertion/constant mutation missed'
    assert tokens(probe) == tokens(mutations[1]), 'comment-only change rejected'
    assert candidates(mutations[2]), 'opaque comment false negative'
    assert not candidates(mutations[3]), 'qualified source false positive'
    assert not candidates(probe), 'string/plain text-block false positive'
    assert tokens(increment) != tokens(mutations[4]), 'operator boundary mutation missed'
    assert tokens(unicode_comment) != tokens(mutations[5]), 'Unicode comment newline activated code missed'
    assert tokens(escaped_block) != tokens(mutations[6]), 'escaped text-block content mutation missed'
    assert not candidates(escaped_block), 'escaped triple quote text-block false candidate'


def verify():
    baseline_paths = subprocess.check_output(['git', '-C', str(ROOT), 'ls-tree', '-r', '--name-only', BASE], text=True).splitlines()
    baseline_paths = sorted(p for p in baseline_paths if re.fullmatch(r'[^/]+/src/test/.*\.java', p))
    assert baseline_paths == paths(), 'JAVA TEST FILE ADDED OR REMOVED'
    before = {path: git_text(path) for path in paths()}
    after = {path: (ROOT / path).read_text() for path in paths()}
    scan_all([*before.values(), *after.values()])
    changed = 0
    for path in paths():
        assert tokens(before[path]) == tokens(after[path]), 'EXECUTABLE TOKENS CHANGED: ' + path
        old_ids = set(SOURCE_ID.findall('\n'.join(gap for _, gap in comments(before[path]))))
        new_ids = set(SOURCE_ID.findall('\n'.join(gap for _, gap in comments(after[path]))))
        assert old_ids <= new_ids, 'SOURCE IDS LOST: ' + path + ': ' + str(old_ids - new_ids)
        changed += before[path] != after[path]
    controls()
    print(f'PASS: {len(paths())} Java test files; {changed} changed files; exact compiler tokens and existing source IDs preserved (superset); 9 injected controls')


def inventory(destination):
    before = {path: git_text(path) for path in paths()}
    scan_all(before.values())
    with Path(destination).open('w') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['path', 'base_line', 'disposition', 'before', 'after'])
        for path in paths():
            after = (ROOT / path).read_text().splitlines()
            assert len(before[path].splitlines()) == len(after), 'inventory line mapping changed: ' + path
            for line, text in candidates(before[path]):
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
        current = {path: (ROOT / path).read_text() for path in paths()}
        scan_all(current.values())
        for path, source in current.items():
            for line, text in candidates(source):
                print(f'{path}:{line}:{text}')
    elif args.inventory:
        inventory(args.inventory)
    else:
        verify()
