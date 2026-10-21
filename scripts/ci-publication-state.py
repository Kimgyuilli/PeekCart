#!/usr/bin/env python3
"""Observe each repository's expected SHA/latest without changing registry state."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ['notification-service', 'user-service', 'product-service', 'order-service', 'payment-service', 'gateway']
DIGEST = r'sha256:[0-9a-f]{64}'


def now():
    return datetime.now(timezone.utc).isoformat()


def require(condition, message):
    if not condition: raise ValueError(message)


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def command(directory, name, args):
    try:
        p = subprocess.run(args, capture_output=True, timeout=20)
    except subprocess.TimeoutExpired as error:
        (directory / (name + '.stdout')).write_bytes(error.stdout or b'')
        (directory / (name + '.stderr')).write_bytes(error.stderr or b'')
        raise ValueError(name + ': command timed out') from error
    (directory / (name + '.stdout')).write_bytes(p.stdout)
    (directory / (name + '.stderr')).write_bytes(p.stderr)
    require(p.returncode == 0, name + ': command exit ' + str(p.returncode))
    return p.stdout


def current_main(directory, name, repository):
    for suffix in ['.json', '.error']: (directory / (name + suffix)).unlink(missing_ok=True)
    request = urllib.request.Request('https://api.github.com/repos/' + repository + '/git/ref/heads/main',
                                     headers={'Accept': 'application/vnd.github+json'})
    if os.environ.get('GH_TOKEN'): request.add_header('Authorization', 'Bearer ' + os.environ['GH_TOKEN'])
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
        (directory / (name + '.json')).write_bytes(raw)
        sha = json.loads(raw)['object']['sha']
        require(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40}', sha), 'invalid current main SHA')
        return sha
    except (OSError, ValueError, KeyError, TypeError) as error:
        (directory / (name + '.error')).write_text(str(error) + '\n')
        return None


def manifest(directory, name, ref):
    raw = command(directory, name, ['docker', 'buildx', 'imagetools', 'inspect', '--format', '{{json .Manifest}}', ref])
    digest = json.loads(raw)['digest']
    require(isinstance(digest, str) and re.fullmatch(DIGEST, digest), name + ': invalid manifest digest')
    return digest


def evidence_for(inputs, service, expected, run_id, attempt):
    candidates = list(inputs.glob('publish-evidence-' + service + '-attempt-*'))
    require(candidates, service + ': publication evidence missing')
    choices = []
    for directory in candidates:
        match = re.fullmatch('publish-evidence-' + re.escape(service) + r'-attempt-([1-9][0-9]*)', directory.name)
        require(match is not None, 'invalid artifact attempt name')
        origin = match[1]
        require(int(origin) <= int(attempt), 'future artifact attempt')
        for file, value in [('service.txt', service), ('expected-sha.txt', expected), ('run-id.txt', run_id), ('run-attempt.txt', origin)]:
            require((directory / file).read_text() == value + '\n', service + ': invalid ' + file)
        choices.append((int(origin), directory))
    _, selected = max(choices, key=lambda item: item[0])
    return selected


def observe(expected, inputs, output):
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').unlink(missing_ok=True)
    require(re.fullmatch(r'[0-9a-f]{40}', expected), 'invalid expected SHA')
    repository = os.environ['GITHUB_REPOSITORY']
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'invalid repository')
    run_id, attempt = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']
    require(re.fullmatch(r'[1-9][0-9]*', run_id) and re.fullmatch(r'[1-9][0-9]*', attempt), 'invalid run/attempt')
    owner = repository.split('/')[0].lower()
    unexpected = [p.name for p in inputs.glob('publish-evidence-*')
                  if not any(p.name.startswith('publish-evidence-' + service + '-attempt-') for service in SERVICES)]
    started = now(); before = current_main(output, 'main-before', repository)
    rows = []
    for service in SERVICES:
        directory = output / service; directory.mkdir(exist_ok=True)
        for name in ['expected', 'latest', 'identity']:
            for suffix in ['.stdout', '.stderr']: (directory / (name + suffix)).unlink(missing_ok=True)
        row = {'service': service, 'started_at': now(), 'status': 'unavailable'}
        try:
            evidence = evidence_for(inputs, service, expected, run_id, attempt)
            origin_attempt = (evidence / 'run-attempt.txt').read_text().strip()
            outcome = (evidence / 'push-outcome.txt').read_text().strip()
            require(outcome in {'success', 'failure', 'cancelled', 'skipped'}, 'invalid push outcome')
            require((evidence / 'load-outcome.txt').read_text() == 'success\n', 'artifact load/identity verification did not succeed')
            image_id = (evidence / 'image-id.txt').read_text().strip()
            require(re.fullmatch(DIGEST, image_id), 'invalid artifact image ID')
            row.update(evidence_artifact=evidence.name, origin_attempt=origin_attempt, push_outcome=outcome, expected_image_id=image_id)
            repo = 'ghcr.io/' + owner + '/peekcart-' + service
            row['expected_manifest'] = manifest(directory, 'expected', repo + ':' + expected)
            row['latest_manifest'] = manifest(directory, 'latest', repo + ':latest')
            command(directory, 'identity', ['bash', str(ROOT / 'scripts/ci-image-identity.sh'), 'verify-remote',
                                           repo, row['expected_manifest'], str(evidence / 'image-id.txt')])
            row['status'] = 'aligned' if row['expected_manifest'] == row['latest_manifest'] else 'different_latest'
        except (OSError, ValueError, KeyError, TypeError) as error:
            row['error'] = str(error)
        row['finished_at'] = now(); rows.append(row)
    after = current_main(output, 'main-after', repository)
    aligned = sum(row['status'] == 'aligned' for row in rows)
    if (before is not None and before != expected) or (after is not None and after != expected):
        status = 'stale_or_concurrent_release'
    elif unexpected or before is None or after is None or any(row['status'] == 'unavailable' for row in rows):
        status = 'incomplete_observation'
    elif aligned == len(SERVICES): status = 'aligned'
    elif aligned: status = 'partial_relative_to_expected'
    else: status = 'no_aligned_services'
    result = {'expected_sha': expected, 'repository': repository, 'run_id': run_id, 'run_attempt': attempt,
              'started_at': started, 'finished_at': now(), 'main_before': before, 'main_after': after,
              'publish_job_result': os.environ.get('PUBLISH_RESULT', 'not_provided'),
              'status': status, 'aligned_services': aligned, 'rows': rows, 'unexpected_artifacts': unexpected,
              'limitation': 'Sequential observations, not an atomic snapshot. Compare each repository to its own expected SHA. Unknown reads do not prove partial promotion or its cause.'}
    save(output / 'summary.json', result)
    print('PUBLICATION: ' + status + ' (' + str(aligned) + '/6 aligned)')
    return 0 if status == 'aligned' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('expected_sha'); parser.add_argument('evidence', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        sys.exit(observe(args.expected_sha, args.evidence, args.output))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print('PUBLICATION: invalid premises: ' + str(error), file=sys.stderr)
        sys.exit(2)
