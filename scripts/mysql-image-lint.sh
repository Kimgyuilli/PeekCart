#!/usr/bin/env bash
# D-068: 옵티마이저/DDL 계약을 검증하는 MySQL 패치를 모든 실행 환경에서 일치시킨다.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python3 - "$@" <<'PY'
from pathlib import Path
import re
import sys

EXPECTED = 'mysql:8.0.46'
PATHS = (
    'docker-compose.yml',
    'docker-compose.e2e.yml',
    'k8s/base/infra/mysql/mysql.yml',
    'common/src/testFixtures/java/com/peekcart/support/SharedContainers.java',
    'order-service/src/test/java/com/peekcart/order/infrastructure/OrderCursorQueryPlanTest.java',
)

def check(files):
    errors = []
    for path in PATHS:
        images = re.findall(r'(?<![a-zA-Z0-9_:/])mysql:[a-zA-Z0-9_.-]+', files.get(path, ''))
        if images != [EXPECTED]:
            errors.append(f'{path}: expected one {EXPECTED}, found {images}')
    return errors

if '--self-test' in sys.argv:
    valid = dict.fromkeys(PATHS, f'image: {EXPECTED}')
    assert not check(valid)
    for path in PATHS:
        for invalid in ('mysql:8.0', 'mysql:latest', 'mysql:8.0.45', '', f'{EXPECTED} mysql:8.0'):
            mutated = dict(valid, **{path: invalid})
            assert check(mutated), (path, invalid)
    assert check({})
    print('mysql-image-lint self-test: 27 cases passed')
else:
    errors = check({p: Path(p).read_text() for p in PATHS if Path(p).exists()})
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        sys.exit(1)
    print(f'mysql-image-lint: all 5 image declarations pinned to {EXPECTED}')
PY
