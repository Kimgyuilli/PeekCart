#!/usr/bin/env python3
"""Check literal CI consumers; external health fixtures cover the four named lints."""
from pathlib import Path
import re
import shlex
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
EXTERNAL = {'scheduler-lock-contract-lint.sh', 'image-contract-lint.sh',
            'kustomize-namespace-lint.sh', 'observability-ssot-lint.sh'}
HEALTH = 'scripts/ci-lint-health-selftest.py'
WIRING = 'scripts/ci-lint-wiring-lint.py'


def commands(body):
    # Current consumers are simple commands before any control block. Unsupported
    # heredocs/multiline quotes cannot establish wiring; this is not a shell parser.
    if not isinstance(body, str) or '<<' in body:
        return []
    try:
        lines = [(line, shlex.split(line, comments=True)) for line in body.splitlines()]
    except ValueError:
        return []
    result = []
    for line, tokens in lines:
        if not tokens:
            continue
        # Only the initial uninterrupted sequence of supported direct commands is
        # evidence. Unknown preambles (functions/builtins/control) stop the scan.
        while tokens and re.fullmatch(r'[A-Za-z_]\w*=.*', tokens[0]):
            tokens = tokens[1:]
        if not tokens or line != line.lstrip() or any(t in {'||', '&&', ';', '&', '|'} for t in tokens):
            break
        if tokens[:1] == ['bash']:
            tokens = tokens[1:]
        elif tokens[:1] == ['python3']:
            tokens = tokens[1:]
            if tokens[:1] == ['-B']:
                tokens = tokens[1:]
        elif not tokens[0].startswith('scripts/'):
            break
        if not tokens or re.fullmatch(r'scripts/(?:[\w.-]+/)*[\w.-]+', tokens[0]) is None:
            break
        result.append(tokens)
    return result


def enabled(node):
    return node.get('if') in (None, '${{ !cancelled() }}') and node.get('continue-on-error', False) is False


def check(root):
    workflow = yaml.safe_load((root / '.github/workflows/ci.yml').read_text())
    if not isinstance(workflow, dict) or not isinstance(workflow.get('jobs'), dict):
        raise ValueError('workflow jobs missing')
    calls = []
    for job in workflow['jobs'].values():
        if not isinstance(job, dict):
            raise ValueError('invalid job')
        if not enabled(job):
            continue
        for step in job.get('steps', []):
            if not isinstance(step, dict):
                raise ValueError('invalid step')
            if enabled(step):
                calls.extend(commands(step.get('run', '')))
    inventory = sorted((root / 'scripts').glob('*-lint.sh'))
    if not inventory:
        raise ValueError('lint inventory empty')
    errors = []
    for path in inventory:
        name = 'scripts/' + path.name
        if path.name == 'writing-lint.sh':
            # Commit/PR prose is checked at ship time, not against all repository
            # documents. This sole exception must retain its actual ship consumer.
            ship = (root / '.claude/commands/ship.md').read_text()
            blocks = re.findall(r'^```[^\n]*\n(.*?)^```', ship, flags=re.M | re.S)
            # The existing ship block creates .cache before its three prose checks.
            ship_calls = [call for block in blocks for call in commands(block.removeprefix('mkdir -p .cache\n'))]
            if not any(c[0] == name for c in ship_calls):
                errors.append(name + ': ship-only consumer missing')
            continue
        own = [call for call in calls if call[0] == name]
        if not own:
            errors.append(name + ': CI direct consumer missing')
        if path.name not in EXTERNAL and not any('--self-test' in call[1:] for call in own):
            errors.append(name + ': CI inline selftest missing; new external coverage requires review')
    for name in EXTERNAL:
        if not (root / 'scripts' / name).is_file():
            errors.append(name + ': external health target missing')
    for path in (HEALTH, WIRING):
        if not (root / path).is_file() or not any(c == [path] for c in calls):
            errors.append(path + ': unconditional CI direct consumer missing')
    return inventory, errors


def main():
    try:
        inventory, errors = check(ROOT)
        if errors:
            print('\n'.join(errors), file=sys.stderr)
            return 1
        print(f'LINT-WIRING: {len(inventory)} scripts; CI consumers/selftest wiring and ship-only exception verified')
        print('External poison health scope: four named lints; other inline selftests are wiring evidence only')
        return 0
    except (OSError, ValueError, TypeError, yaml.YAMLError) as error:
        print('LINT-WIRING: invalid premises: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
