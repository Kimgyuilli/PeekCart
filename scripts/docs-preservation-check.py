#!/usr/bin/env python3
"""Verify declared document transfers and protected completion history against git.

Ordinary document edits are outside this contract. Sources/parts make split and
merge ownership explicit; exceptions are small exact replacements, never paths.
"""
import argparse
import hashlib
import html
import json
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

TASKS = 'docs/TASKS.md'
DONE = 'docs/progress/TASKS-done.md'
ROW = re.compile(r'^\|\s*(D-[0-9]{3,})\s*\|')
INLINE = re.compile(r'\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+"[^"\n]*")?\s*\)')
REFERENCE = re.compile(r'^\s{0,3}\[[^\]\n]+\]:\s*(<[^>\n]+>|\S+)', re.M)


class Violation(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Violation(message)


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def safe_path(value):
    require(isinstance(value, str) and value.startswith('docs/'), f'Invalid document path: {value!r}')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == value
            and path.suffix == '.md', f'Unsafe/non-Markdown path: {value!r}')
    return value


def visible(text):
    """Mask fenced/inline code without changing byte positions of ordinary text."""
    lines = text.splitlines(keepends=True)
    fence = None
    output = []
    for line_number, line in enumerate(lines):
        match = re.match(r'^\s{0,3}(`{3,}|~{3,})([^\n]*)', line)
        if match:
            marker = match.group(1)
            if fence is None:
                require(marker[0] != "`" or "`" not in match.group(2), "Unsupported backtick fence info")
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence) and not match.group(2).strip():
                fence = None
            output.append(re.sub(r'[^\n]', ' ', line))
        elif fence:
            output.append(re.sub(r'[^\n]', ' ', line))
        elif line.startswith('    ') or line.startswith('\t'):
            require(not re.search(r'\]\(|^\s*\[.*\]:|(?:href|src)\s*=', line, re.I),
                    'Unsupported indented-code/list link ambiguity')
            output.append(re.sub(r'[^\n]', ' ', line))
        else:
            runs = list(re.finditer(r"`+", line))
            masked = list(line)
            number = 0
            while number < len(runs):
                opener = runs[number]
                preceding = line[:opener.start()]
                if len(preceding) - len(preceding.rstrip("\\")) & 1:
                    number += 1
                    continue
                closing = next((n for n in range(number + 1, len(runs))
                                if len(runs[n].group()) == len(opener.group())), None)
                if closing is None:
                    # A genuinely unmatched run is ordinary literal Markdown. A matching
                    # run in this paragraph would create a multiline code span, unsupported here.
                    tail = []
                    for later in lines[line_number + 1:]:
                        if not later.strip() or re.match(r'^\s{0,3}(?:`{3,}|~{3,}|#{1,6}\s)', later): break
                        tail.append(later)
                    require(not re.search(r'(?<!`)' + re.escape(opener.group()) + r'(?!`)', ''.join(tail)),
                            'Unsupported multiline backtick span')
                    number += 1
                    continue
                masked[opener.start():runs[closing].end()] = " " * (runs[closing].end() - opener.start())
                number = closing + 1
            output.append("".join(masked))
    return ''.join(output)


def links(text):
    masked = visible(text)
    for line in masked.splitlines():
        if re.match(r'^\s{0,3}</?[A-Za-z][A-Za-z0-9-]*(?:\s|/?>)', line):
            require(bool(re.fullmatch(r"\s*<(?:a|h[1-6])\s+(?:id|name)=[\"'][^\"']+[\"']\s*>(?:</(?:a|h[1-6])>)?\s*", line, re.I)),
                    'Unsupported HTML block in preservation scope')
    require('<!--' not in masked and not re.search(r"\b(?:href|src)\s*=", masked, re.I),
            'Unsupported HTML comment/link: preserve literally or use Markdown links')
    require(not re.search(r'^ {4,}.*(?:\]\(|^\s*\[.*\]:)', masked, re.M),
            'Unsupported indented-code/list link ambiguity')
    matches = sorted([*INLINE.finditer(masked), *REFERENCE.finditer(masked)],
                     key=lambda m: m.start(1))
    # Unsupported inline syntax fails closed instead of becoming invisible to a transfer check.
    for match in matches:
        preceding = masked[:match.start()]
        require("\\" not in match.group(0) and not ((len(preceding) - len(preceding.rstrip("\\"))) & 1),
                "Unsupported escaped link syntax")
        require('[' not in match.group(0)[1:match.group(0).find(']')], 'Unsupported nested link label')
    spans = [(m.start(), m.end()) for m in matches]
    for marker in re.finditer(r'\]\(', masked):
        require(any(a <= marker.start() < b for a, b in spans), 'Unsupported Markdown inline link syntax')
    return [(m.start(1), m.end(1), m.group(1).strip('<>')) for m in matches]


def references_source(text, sources):
    """Conservative raw target prefilter; prose/code path mentions need no retarget.

    This deliberately includes code/escaped candidates: those still fail closed
    in links(). Whitespace/newline after a link opener is included.
    """
    decoded = unquote(text).replace("\\", "")
    for source in sources:
        name = re.escape(PurePosixPath(source).name)
        if re.search(r'(?:\]\(\s*|\]:\s*|(?:href|src)\s*=\s*)[^\n]*' + name, decoded, re.I):
            return True
    return False


def anchors(text):
    masked = visible(text)
    counts = Counter()
    result = {}
    lines = masked.splitlines()
    raw_lines = text.splitlines()
    for i, line in enumerate(lines):
        match = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        title = None
        if match:
            # Keep inline code content in a heading, unlike link extraction.
            title = re.sub(r'^\s{0,3}#{1,6}\s+', '', raw_lines[i]).rstrip('# ').strip()
        elif i + 1 < len(lines) and line.strip() and re.fullmatch(r'\s{0,3}(?:=+|-+)\s*', lines[i + 1]):
            title = raw_lines[i].strip()
        if title is not None:
            require(not re.search(r"(?<!\w)_{1,2}(?=\S)", line), "Unsupported underscore-emphasis heading")
            require(not re.search(r"\]\s*\[|!\[|<img\b", title, re.I), "Unsupported reference/image heading")
            title = INLINE.sub(lambda m: m.group()[1:m.group().index("]")], title)
            title = html.unescape(re.sub(r'<[^>]+>', '', title)).replace('`', '').replace('*', '')
            slug = ''.join(c for c in title.lower() if c in ' _-' or unicodedata.category(c)[0] in 'LNM')
            slug = slug.replace(' ', '-')
            number = counts[slug]
            counts[slug] += 1
            result[slug + (f'-{number}' if number else '')] = i + 1
        for explicit in re.finditer(r'<(?:a|h[1-6])\b[^>]*\b(?:id|name)=["\x27]([^"\x27]+)["\x27]', line, re.I):
            require(explicit.group(1) not in result, 'Duplicate explicit heading anchor')
            result[explicit.group(1)] = i + 1
    return result


class Checker:
    def __init__(self, root, base, manifests):
        self.root, self.base = root, base
        self.cache = {}
        self.corrections = {}
        self.used_corrections = set()
        self.sources, self.destinations = {}, []
        self.entry_routes = {}
        self.anchor_routes = {}
        for filename in manifests:
            data = json.loads(filename.read_text())
            require(set(data) <= {'version', 'sources', 'destinations', 'corrections'}, 'Unknown manifest field')
            require(data.get('version') == 1 and type(data.get('version')) is int, 'Manifest version must be 1')
            require(data.get('sources') or data.get('corrections'), 'Empty manifest')
            for source in data.get('sources', []):
                require(set(source) == {'path', 'sha256'}, 'Invalid source schema')
                path = safe_path(source['path'])
                require(path not in self.sources, f'Duplicate source: {path}')
                original = self.before(path)
                require(sha(original) == source['sha256'], f'Source hash mismatch: {path}')
                self.sources[path] = original
            for target in data.get('destinations', []):
                require(set(target) == {'path', 'parts'}, 'Invalid destination schema')
                safe_path(target['path'])
                self.destinations.append(target)
            for correction in data.get('corrections', []):
                require(set(correction) == {'path', 'base_sha256', 'old', 'new', 'old_sha256', 'reason'},
                        'Invalid correction schema')
                path = safe_path(correction['path'])
                old, new, reason = correction['old'], correction['new'], correction['reason']
                require(all(isinstance(v, str) for v in (old, new, reason)), 'Correction fields must be strings')
                original = self.before(path)
                require(sha(original) == correction['base_sha256'] and sha(old) == correction['old_sha256'],
                        f'Correction hash mismatch: {path}')
                require(old and new and old != new and reason.strip(), 'Empty/no-op correction')
                require(len(old.splitlines()) <= 20 and len(old) <= 2000 and len(old) < len(original) / 2,
                        'Correction is too broad; preserve history with an append instead')
                require(original.count(old) == 1, f'Correction old text is not unique: {path}')
                self.corrections.setdefault(path, []).append(correction)
        for path, corrections in self.corrections.items():
            old = ''.join(c['old'] for c in corrections)
            require(len(old.splitlines()) <= 20 and len(old) <= 2000 and len(old) < len(self.before(path)) / 2,
                    f'Combined corrections are too broad: {path}')

    def before(self, path):
        if path not in self.cache:
            result = subprocess.run(['git', 'show', f'{self.base}:{path}'], cwd=self.root,
                                    capture_output=True)
            require(result.returncode == 0, f'Cannot read base document: {path}')
            self.cache[path] = result.stdout.decode("utf-8")
        return self.cache[path]

    def current(self, path):
        target = (self.root / path).resolve()
        require(target.is_relative_to(self.root), f'Document escapes repository: {path}')
        require(target.is_file(), f'Missing current document: {path}')
        return target.read_bytes().decode("utf-8")

    def corrected(self, path):
        text = self.before(path)
        for correction in self.corrections.get(path, []):
            old = correction['old']
            require(text.count(old) == 1, f'Overlapping/ambiguous correction: {path}')
            text = text.replace(old, correction['new'], 1)
            self.used_corrections.add(id(correction))
        return text

    def resolve(self, path, target, before=False):
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc: return None
        relative = unquote(parsed.path)
        resolved = PurePosixPath(path).parent / relative if relative else PurePosixPath(path)
        if relative.startswith('/'): resolved = PurePosixPath(relative.lstrip('/'))
        # Normalize .. lexically; resolving a vanished base path via filesystem would be wrong.
        parts = []
        for part in resolved.parts:
            if part == '..':
                require(parts, f'Link escapes repository: {path}: {target}')
                parts.pop()
            elif part not in ('', '.'): parts.append(part)
        dest = '/'.join(parts)
        fragment = unquote(parsed.fragment)
        if before:
            if dest in self.sources and fragment:
                require((dest, fragment) in self.anchor_routes, f'Unknown source anchor: {dest}#{fragment}')
                dest, fragment = self.anchor_routes[dest, fragment]
            else:
                dest = self.entry_routes.get(dest, dest)
        return dest, fragment, parsed.query

    def canonical(self, text, path, before=False, validate=False):
        require(not any(marker in text for marker in ('@@LOCAL[', '@@EXTERNAL[', '@@URL[')),
                'Reserved canonical marker in document content')
        changes = []
        for start, end, target in links(text):
            resolved = self.resolve(path, target, before)
            if resolved is None:
                changes.append((start, end, f"@@EXTERNAL[{sha(target)}]"))
                continue
            dest, fragment, query = resolved
            if validate:
                actual = self.root / dest
                require(actual.resolve().is_relative_to(self.root) and actual.exists(),
                        f'Broken local link: {path}: {target}')
                if fragment:
                    require(actual.is_file() and actual.suffix == '.md' and fragment in anchors(actual.read_bytes().decode('utf-8')),
                            f'Broken local anchor: {path}: {target}')
            changes.append((start, end, '@@LOCAL[' + json.dumps([dest, fragment, query], ensure_ascii=False, separators=(',', ':')) + ']'))
        for start, end, value in reversed(changes): text = text[:start] + value + text[end:]
        # Historical blob URLs remain opaque even when written as bare URLs in prose/code.
        text = re.sub(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s<>()]+", lambda m: f"@@URL[{sha(m.group())}]", text)
        return text

    def transfers(self):
        covered = {path: Counter() for path in self.sources}
        targets = set()
        raw_targets = {}
        positions = {}
        for destination in self.destinations:
            target = destination['path']
            require(target not in targets, f'Duplicate destination: {target}')
            targets.add(target)
            exists = subprocess.run(['git', 'cat-file', '-e', f'{self.base}:{target}'], cwd=self.root,
                                    capture_output=True).returncode == 0
            require(not exists or target in self.sources, f'Existing destination must be a source: {target}')
            require(isinstance(destination['parts'], list) and destination['parts'], 'Empty destination parts')
            last = {}
            raw = ""
            for part in destination['parts']:
                if set(part) == {'literal'}:
                    require(isinstance(part['literal'], str) and part['literal'], 'Empty framing literal')
                    raw += part['literal']
                    continue
                require(set(part) == {'source', 'start', 'end'}, 'Invalid source part')
                source, start, end = part['source'], part['start'], part['end']
                require(source in self.sources and type(start) is int and type(end) is int, 'Unknown source/bad range')
                lines = self.corrected(source).splitlines(keepends=True)
                require(1 <= start <= end <= len(lines), f'Out of range source part: {source}')
                require(start > last.get(source, 0), f'Reordered source content within {target}: {source}')
                last[source] = end
                for number in range(start, end + 1):
                    covered[source][number] += 1
                    positions[source, number] = (target, raw.count("\n") + 1)
                    raw += lines[number - 1]
            raw_targets[target] = raw
        for source in self.sources:
            length = len(self.corrected(source).splitlines(keepends=True))
            require(length and covered[source] == Counter(range(1, length + 1)),
                    f'Missing/duplicated source lines: {source}')
            self.entry_routes[source] = positions[source, 1][0]
            for fragment, line in anchors(self.corrected(source)).items():
                target, new_line = positions[source, line]
                candidates = [key for key, value in anchors(raw_targets[target]).items() if value == new_line]
                require(candidates, f'Source heading lost at destination: {source}#{fragment}')
                new_fragment = fragment if fragment in candidates else candidates[0]
                self.anchor_routes[source, fragment] = (target, new_fragment)
            if source not in targets:
                require(not (self.root / source).exists(), f'Old source still exists: {source}')
        for destination in self.destinations:
            target = destination['path']
            expected = ''
            for part in destination['parts']:
                if 'literal' in part:
                    expected += self.canonical(part['literal'], target)
                else:
                    source = part['source']
                    text = ''.join(self.corrected(source).splitlines(keepends=True)[part['start'] - 1:part['end']])
                    expected += self.canonical(text, source, before=True)
            actual = self.canonical(self.current(target), target, validate=True)
            require(actual == expected, f'Destination ordered content differs: {target}')

    @staticmethod
    def rows(text):
        rows = []
        for line in text.splitlines(keepends=True):
            match = ROW.match(line)
            if match: rows.append((match.group(1), line))
        return rows

    def tasks(self):
        originals = {path: self.rows(self.corrected(path)) for path in (TASKS, DONE)}
        currents = {path: self.rows(self.current(path)) for path in (TASKS, DONE)}
        for label, documents in [('base', originals), ('current', currents)]:
            counts = Counter(key for rows in documents.values() for key, _ in rows)
            require(all(n == 1 for n in counts.values()), f'Duplicate task row ID in {label}')
        old = {key: (path, row) for path, rows in originals.items() for key, row in rows}
        new = {key: (path, row) for path, rows in currents.items() for key, row in rows}
        require(old.keys() <= new.keys(), 'Task row ID multiset lost entries')
        for key in new.keys() - old.keys():
            require(new[key][0] == TASKS, f'New ID inserted directly into done: {key}')
        moved = {key for key in old if old[key][0] != new[key][0]}
        require(all(old[k][0] == TASKS and new[k][0] == DONE for k in moved), 'Wrong task row destination')
        for path in (TASKS, DONE):
            before_ids = [key for key, _ in originals[path] if key not in moved]
            after_ids = [key for key, _ in currents[path] if key in old and key not in moved]
            require(before_ids == after_ids, f'Task row order changed: {path}')
        source_moved = [key for key, _ in originals[TASKS] if key in moved]
        destination_moved = [key for key, _ in currents[DONE] if key in moved]
        require(source_moved == destination_moved, 'Moved task row order differs from source order: '
                + ','.join(destination_moved) + ' != ' + ','.join(source_moved))
        done_ids = [key for key, _ in currents[DONE]]
        require(done_ids[:len(originals[DONE])] == [key for key, _ in originals[DONE]], 'Done rows not appended')
        for key in old:
            if old[key][0] == DONE or key in moved:
                old_body = old[key][1].rsplit('|', 2)[0]
                new_body = new[key][1].rsplit('|', 2)[0]
                require(self.canonical(old_body, old[key][0], before=True) == self.canonical(new_body, new[key][0]),
                        f'Task row body changed: {key}')
            if key in moved:
                status = new[key][1].rsplit('|', 2)[1]
                require('✅' in status and re.search(r'https://github\.com/[^/]+/[^/]+/pull/[0-9]+', status),
                        f'Moved row lacks completion/PR evidence: {key}')
        for path in (TASKS, DONE):
            if path != DONE and not moved: continue
            retained = {key for key, _ in originals[path] if key not in moved}

            def history(text, before):
                # Preserve where the existing rows occur among header/footer lines.
                # Ignore only newly appended/moved rows; their bodies/order are checked above.
                canonical = self.canonical(text, path, before=before, validate=not before)
                result = []
                for line in canonical.splitlines(keepends=True):
                    match = ROW.match(line)
                    if match:
                        if match.group(1) in retained: result.append(('row', match.group(1)))
                    else:
                        result.append(('text', line))
                return result

            before = history(self.corrected(path), True)
            after = history(self.current(path), False)
            prefix_preserved = after[:len(before)] == before
            # A byte-preserving append may extend an original final line without LF.
            final_line_append = (before and len(after) >= len(before) and before[-1][0] == 'text'
                                 and after[len(before) - 1][0] == 'text'
                                 and after[:len(before) - 1] == before[:-1]
                                 and after[len(before) - 1][1].startswith(before[-1][1]))
            require(prefix_preserved or final_line_append, f'Existing row/footer interleave changed: {path}')

    def protected(self):
        paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', self.base, 'docs'],
                                        cwd=self.root, text=True).splitlines()
        for path in paths:
            protected = ((path.startswith('docs/plans/done/') and path.endswith('.md')
                          and not path.endswith('/INDEX.md'))
                         or re.fullmatch(r'docs/progress/TASKS-archive-[^/]+\.md', path))
            if not protected or path in self.sources: continue
            original, current = self.corrected(path), self.current(path)
            if original == current: continue
            before = self.canonical(original, path, before=True)
            after = self.canonical(current, path, validate=False)
            require(after.startswith(before), f'Protected archive changed: {path}')
            if after != before: self.canonical(self.current(path), path, validate=True)
        all_corrections = {id(c) for items in self.corrections.values() for c in items}
        require(self.used_corrections == all_corrections, 'Unused correction allowance')

    def inbound_links(self):
        if not self.sources: return
        paths = subprocess.check_output(['git', 'ls-files', '*.md'], cwd=self.root, text=True).splitlines()
        for path in paths:
            if path in self.sources or not (self.root / path).is_file(): continue
            actual = self.current(path)
            base = subprocess.run(['git', 'show', f'{self.base}:{path}'], cwd=self.root,
                                  capture_output=True)
            original = base.stdout.decode("utf-8") if base.returncode == 0 else ""
            if not references_source(original, self.sources) and not references_source(actual, self.sources): continue
            expected = Counter()
            try:
                old_links, new_links = links(original), links(actual)
            except Violation as error:
                raise Violation(f'{path}: {error}') from error
            for _, _, target in old_links:
                old = self.resolve(path, target)
                if old and old[0] in self.sources:
                    expected[self.resolve(path, target, before=True)] += 1
            received = Counter(self.resolve(path, target) for _, _, target in new_links)
            require(all(received[target] >= count for target, count in expected.items()),
                    f'Inbound source references not preserved/retargeted: {path}')
            for _, _, target in new_links:
                resolved = self.resolve(path, target)
                if resolved and (resolved[0] in self.sources or resolved[0] in {d['path'] for d in self.destinations}):
                    self.canonical(f"[reference]({target})", path, validate=True)

    def declared_deletions(self):
        deleted = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '--diff-filter=D',
                                           self.base, '--', 'docs'], cwd=self.root, text=True).splitlines()
        for path in deleted:
            if path.endswith('.md'):
                require(path in self.sources, f'Deleted document needs a source/destination manifest: {path}')

    def run(self):
        self.declared_deletions()
        self.transfers()
        self.tasks()
        self.protected()
        self.inbound_links()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--manifest', type=Path, action='append', default=[])
    args = parser.parse_args()
    root = args.root.resolve()
    revision = subprocess.run(['git', 'rev-parse', '--verify', args.base + '^{commit}'], cwd=root,
                              capture_output=True, text=True)
    if revision.returncode:
        print('Cannot read base commit: ' + revision.stderr.strip(), file=sys.stderr)
        return 2
    try:
        Checker(root, revision.stdout.strip(), args.manifest).run()
    except (Violation, ValueError, KeyError, TypeError, OSError) as error:
        print('[preservation] FAIL: ' + str(error), file=sys.stderr)
        return 1
    print('[preservation] PASS: task rows, declared ordered transfers, protected history and local links')
    return 0


if __name__ == '__main__':
    sys.exit(main())
