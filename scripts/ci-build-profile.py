#!/usr/bin/env python3
"""Collect exact build records and validate Gradle HTML without inventing missing timings."""
import argparse
from decimal import Decimal
import hashlib
from html.parser import HTMLParser
import json
import os
import platform
from pathlib import Path
import re
import subprocess
import sys
import time

SERVICES = {'notification-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway'}
SUMMARY = {'Total Build Time', 'Startup', 'Settings and buildSrc', 'Loading Projects',
           'Configuring Projects', 'Artifact Transforms', 'Task Execution'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def epoch(value):
    require(type(value) is int and value > 0, 'invalid epoch')
    return value


def duration(text):
    match = re.fullmatch(r'(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(\d+(?:\.\d+)?)s', text)
    require(match is not None, 'invalid duration: ' + text)
    days, hours, minutes, seconds = [Decimal(value or '0') for value in match.groups()]
    return str(days * 86400 + hours * 3600 + minutes * 60 + seconds)


class Report(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.divs, self.rows, self.cells = [], {}, []
        self.cell = None

    def handle_starttag(self, tag, attrs):
        if tag == 'div': self.divs.append(dict(attrs).get('id'))
        if tag == 'tr': self.cells = []
        if tag in ('td', 'th'): self.cell = []

    def handle_data(self, data):
        if self.cell is not None: self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.cells.append(' '.join(''.join(self.cell).split())); self.cell = None
        if tag == 'tr':
            tabs = [item for item in self.divs if item in ('tab0', 'tab4')]
            if tabs: self.rows.setdefault(tabs[-1], []).append(self.cells)
        if tag == 'div' and self.divs: self.divs.pop()


def profile(path, service):
    parser = Report(); parser.feed(path.read_text(encoding='utf-8')); parser.close()
    summary, tasks = {}, {}
    for row in parser.rows.get('tab0', []):
        if row == ['Description', 'Duration']: continue
        require(len(row) == 2 and row[0] in SUMMARY, 'unknown/malformed summary row')
        require(row[0] not in summary, 'duplicate summary row')
        summary[row[0]] = duration(row[1])
    require(set(summary) == SUMMARY, 'incomplete summary')
    for row in parser.rows.get('tab4', []):
        if row == ['Task', 'Duration', 'Result']: continue
        require(len(row) == 3 and row[0].startswith(':'), 'malformed task row')
        if row[2] == '(total)': continue  # Raw HTML retains totals; do not add them to task timings.
        require(row[0] not in tasks, 'duplicate task row')
        tasks[row[0]] = {'seconds': duration(row[1]), 'result': row[2]}
    require(':' + service + ':bootJar' in tasks, 'requested bootJar absent')
    require(any(key.endswith(':compileJava') for key in tasks), 'compileJava rows absent')
    return {'summary_seconds': summary, 'tasks': tasks}


def vertex(log, kind):
    text = log.read_text(encoding='utf-8')
    pattern = r'^#(\d+) \[build [^\]]+\] RUN .*bootJar.*--profile' if kind == 'bootJar' else r'^#(\d+) exporting to GitHub Actions Cache\s*$'
    found = re.findall(pattern, text, flags=re.M)
    require(len(found) == 1, 'missing/duplicate ' + kind + ' vertex')
    ident = found[0]
    cached = re.findall(r'^#' + ident + r' CACHED\s*$', text, flags=re.M)
    done = re.findall(r'^#' + ident + r' DONE (\d+(?:\.\d+)?)s\s*$', text, flags=re.M)
    require(len(cached) + len(done) == 1, 'incomplete/ambiguous ' + kind + ' vertex')
    require(not cached or kind == 'bootJar', 'unexpected cached export')
    return {'id': ident, 'cached': bool(cached), 'seconds': done[0] if done else None}


def reference(metadata):
    value = metadata.get('buildx.build.ref') if isinstance(metadata, dict) else None
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', value), 'missing/invalid exact build ref')
    return value


def analyze(directory):
    (directory / 'analysis.json').unlink(missing_ok=True)
    context = read_json(directory / 'context.json')
    service = context['service']; require(service in SERVICES, 'unknown service')
    require(re.fullmatch(r'[0-9a-f]{40}', context['checkout_sha']) is not None, 'invalid checkout')
    require(context['runtime_target'] == 'runtime' and str(context['run_id']).isdigit(), 'invalid run/target')
    start, end = epoch(context['started_epoch']), epoch(context['image_finished_epoch'])
    require(start <= end, 'reversed image window')
    main_ref = reference(read_json(directory / 'image-metadata.json'))
    export_ref = reference(read_json(directory / 'export-metadata.json'))
    require(main_ref != export_ref, 'export ref aliases image ref')
    require(main_ref.split('/')[0] == context['builder_name'] == export_ref.split('/')[0], 'builder mismatch')
    require((directory / 'image.dockerbuild').stat().st_size > 0, 'empty build record')
    boot = vertex(directory / 'image-build.log', 'bootJar')
    export = vertex(directory / 'image-build.log', 'cache-export')
    require(vertex(directory / 'profile-export.log', 'bootJar')['cached'], 'profile was rebuilt after image; detached measurement')
    raw = directory / 'export'
    metadata = read_json(raw / 'metadata/profile.json')
    require(metadata['service'] == service, 'profile service mismatch')
    origin_start, origin_end = epoch(metadata['started_epoch']), epoch(metadata['finished_epoch'])
    require(origin_start <= origin_end <= end, 'invalid/future profile origin')
    reports = list((raw / 'reports').glob('profile-*.html'))
    require(len(reports) == 1, 'missing/ambiguous profile HTML')
    report = reports[0]
    manifest = (raw / 'metadata/report-sha256.txt').read_text(encoding='utf-8')
    require(manifest == sha(report) + '  build/reports/profile/' + report.name + '\n', 'profile checksum/path mismatch')
    parsed = profile(report, service)
    if not boot['cached']:
        require(origin_start >= start, 'stale profile presented as fresh')
    result = {'context': context, 'image_build_ref': main_ref, 'profile_export_build_ref': export_ref,
              'measurement_status': 'cached_previous_profile' if boot['cached'] else 'fresh_execution',
              'profile_origin': metadata, 'bootjar_vertex': boot, 'gha_cache_export_vertex': export,
              'gradle_profile': None if boot['cached'] else parsed,
              'previous_cached_profile': parsed if boot['cached'] else None,
              'timing_note': 'Gradle rows are nested/possibly overlapping. Vertex durations are separate wall times. Do not sum these as job/critical-path time.',
              'files': {str(path.relative_to(directory)): sha(path) for path in sorted(directory.rglob('*'))
                        if path.is_file() and path.name != 'analysis.json'}}
    save(directory / 'analysis.json', result)
    print('BUILD-PROFILE: ' + result['measurement_status'] + ' ' + service)


def record(directory, prefix):
    metadata = json.loads(os.environ['BUILD_METADATA'])
    ref = reference(metadata)
    builder, _, record_id = ref.split('/')
    save(directory / (prefix + '-metadata.json'), metadata)
    if prefix == 'image':
        context = read_json(directory / 'context.json'); context['image_finished_epoch'] = int(time.time()); save(directory / 'context.json', context)
    name = 'image-build.log' if prefix == 'image' else 'profile-export.log'
    with (directory / name).open('w') as output:
        subprocess.run(['docker', 'buildx', 'history', 'logs', '--builder', builder, record_id, '--progress', 'plain'], stdout=output, stderr=subprocess.STDOUT, check=True)
    if prefix == 'image':
        subprocess.run(['docker', 'buildx', 'history', 'export', '--builder', builder, record_id, '-o', str(directory / 'image.dockerbuild')], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['begin', 'record-image', 'record-export', 'analyze'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--service', choices=sorted(SERVICES))
    args = parser.parse_args(); directory = args.directory
    directory.mkdir(parents=True, exist_ok=True)
    if args.mode == 'begin':
        require(args.service is not None, 'service required')
        checkout = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        for name, command in [('buildx-version.txt', ['docker', 'buildx', 'version']),
                              ('docker-version.txt', ['docker', 'version'])]:
            (directory / name).write_text(subprocess.check_output(command, text=True))
        save(directory / 'context.json', {'service': args.service, 'checkout_sha': checkout,
             'github_sha': os.environ['GITHUB_SHA'], 'run_id': os.environ['GITHUB_RUN_ID'],
             'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'], 'runtime_target': 'runtime',
             'builder_name': os.environ['PROFILE_BUILDER'], 'runner_arch': platform.machine(),
             'runner_os': platform.system(), 'runner_cpus': os.cpu_count(),
             'dockerfile_sha256': sha(Path('Dockerfile')),
             'gradle_wrapper_sha256': sha(Path('gradle/wrapper/gradle-wrapper.properties')), 'cache_scope': 'image-' + args.service, 'cache_export_mode': 'max', 'started_epoch': int(time.time())})
    elif args.mode.startswith('record-'): record(directory, args.mode.removeprefix('record-'))
    else: analyze(directory)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print('BUILD-PROFILE: invalid evidence: ' + str(error), file=sys.stderr)
        sys.exit(2)
