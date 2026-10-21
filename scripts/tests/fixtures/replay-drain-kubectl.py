#!/usr/bin/env python3
"""Strict, read-only kubectl adapter fixture; never delegates to a real cluster."""
import base64
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
fixture = json.loads(Path(os.environ['DRAIN_FIXTURE']).read_text())
log = Path(os.environ['DRAIN_CALLS'])
prior = [json.loads(line) for line in log.read_text().splitlines()]
assert args[:2] == ['-n', 'fixture-only'], args
args = args[2:]
service = None
if args[:2] == ['get', 'secret']:
    service = args[2].removesuffix('-service-secret')
    assert args[3] == '-o' and len(args) == 5, args
    field = args[4]
    assert field in ['jsonpath={.data.DB_USERNAME}', 'jsonpath={.data.DB_PASSWORD}'], args
    kind = 'password' if 'DB_PASSWORD' in field else 'username'
    output = base64.b64encode(b'fixture-credential').decode() + '\n'
    call = dict(kind=kind, service=service)
elif args[:4] == ['exec', 'deploy/mysql', '--', 'mysql']:
    assert args[4:7] == ['-ufixture-credential', '-pfixture-credential', '-D'], args
    service = args[7].removeprefix('peekcart_')
    assert args[8:11] == ['-N', '-B', '-e'] and len(args) == 12, args
    sql = args[11]
    if sql.startswith('SELECT DISTINCT last_replay_target_group'):
        assert sql.endswith('WHERE last_replay_target_group IS NOT NULL'), sql
        kind = 'groups'
        output = f'{service}-target-a\n{service}-target-b\n'
    else:
        assert sql.startswith('SELECT (SELECT COUNT(*) FROM outbox_events'), sql
        assert "publication_status='REQUESTED'" in sql and 'MAX(last_replay_settled_at)' in sql, sql
        kind = 'db'
        output = '0\t0\t1000000000.000000\n'
    call = dict(kind=kind, service=service)
elif args[:3] == ['exec', 'deploy/kafka', '--']:
    assert args[3:8] == ['/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092', '--describe', '--group'], args
    assert len(args) == 9, args
    group = args[8]
    sample = 1 + sum(c.get('group') == group for c in prior)
    kind = 'lag'
    service = group.split('-')[0]
    call = dict(kind=kind, service=service, group=group, sample=sample)
    # Kafka 3.x --describe output includes a leading blank and trailing member columns.
    output = ('\nGROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n'
              f'{group} order.created 0 15 15 0 consumer-a /10.0.0.1 client-a\n'
              f'{group} order.created 1 21 21 0 - - -\n')
else:
    raise AssertionError(f'Unexpected kubectl command: {args}')
assert service in ['order', 'product', 'payment', 'notification'], service
with log.open('a') as stream:
    stream.write(json.dumps(call) + '\n')
for override in fixture.get('overrides', []):
    if all(call.get(key) == value for key, value in override['match'].items()):
        output = override.get('output', output)
        if override.get('exit', 0):
            print('injected adapter failure', file=sys.stderr)
            sys.exit(override['exit'])
print(output, end='')
