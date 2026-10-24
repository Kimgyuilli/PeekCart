#!/usr/bin/env python3
"""Free D-092 fixture checks for Grafana ops routing and guarded Outbox re-entry SQL."""
import json
from pathlib import Path
import re
import sqlite3

import yaml

ROOT = Path(__file__).resolve().parents[1]
ROUTING = ROOT / 'k8s/monitoring/shared/grafana-notification-routing.yml'
RUNBOOK = ROOT / 'docs/runbooks/outbox-failed-recovery.md'
MYSQL_TEST = ROOT / 'notification-service/src/test/java/com/peekcart/global/outbox/NotificationOutboxIntegrationTest.java'
REPLAY_POLICY = ROOT / 'common/src/main/java/com/peekcart/global/replay/ReplayPolicyRegistry.java'
DLQ_TOPOLOGY = ROOT / 'common/src/main/java/com/peekcart/global/kafka/DlqTopology.java'
SECRET_RUNBOOK = ROOT / 'docs/runbooks/slack-webhook-secret.md'
SERVICES = ('order', 'product', 'payment', 'notification')
PAIR_LABELS = {'allow': 'ALLOW', 'deny': 'DENY', 'allowWithPrecondition': 'PRECONDITION'}


def assert_routing_contract(contact_points, root_policy):
    assert len(contact_points) == 1
    assert contact_points[0]['name'] == 'peekcart-operations'
    assert contact_points[0]['receivers'][0]['settings']['url'] == '${PEAKCART_OPS_SLACK_WEBHOOK_URL}'
    assert root_policy['receiver'] == 'grafana-default-email'
    assert root_policy['routes'] == [{
        'receiver': 'peekcart-operations',
        'object_matchers': [['signal', '=~', '^(outbox-publish|dlq)$']],
        'continue': False,
    }]


def check_routing():
    configmap = yaml.safe_load(ROUTING.read_text())
    assert configmap['metadata']['labels']['grafana_alert'] == '1'
    assert 'grafana-notification-routing.yml' in yaml.safe_load(
        (ROOT / 'k8s/monitoring/shared/kustomization.yml').read_text()
    )['resources']
    contact = yaml.safe_load(configmap['data']['contact-points.yaml'])['contactPoints']
    tree = yaml.safe_load(configmap['data']['notification-policies.yaml'])['policies']
    assert len(tree) == 1
    root = tree[0]
    assert_routing_contract(contact, root)
    assert contact[0]['receivers'] == [{
        'uid': 'peekcart-operations-slack', 'type': 'slack',
        'settings': {'url': '${PEAKCART_OPS_SLACK_WEBHOOK_URL}'},
    }]
    matcher = re.compile(root['routes'][0]['object_matchers'][0][2])
    for signal in ('outbox-publish', 'dlq'):
        assert matcher.fullmatch(signal)
        assert root['routes'][0]['receiver'] == 'peekcart-operations'
    for signal in ('payment-approval', 'cache-fallback-get', 'user-message', ''):
        assert not matcher.fullmatch(signal)
        assert root['receiver'] == 'grafana-default-email'
    # Prove the contract checker rejects route regressions rather than passing only the happy fixture.
    mutants = [
        ([], root),
        (contact, {**root, 'receiver': 'peekcart-operations'}),
        (contact, {**root, 'routes': [{**root['routes'][0], 'object_matchers': [['signal', '=', 'payment-approval']]}]}),
    ]
    for bad_points, bad_root in mutants:
        try:
            assert_routing_contract(bad_points, bad_root)
        except AssertionError:
            continue
        raise AssertionError('routing contract checker accepted a missing-contact/default-route/unknown-signal mutant')

    for env_file in ('gke/values-prometheus.yml', 'minikube/values-prometheus.yml'):
        values = yaml.safe_load((ROOT / 'k8s/monitoring' / env_file).read_text())
        ref = values['grafana']['envValueFrom']['PEAKCART_OPS_SLACK_WEBHOOK_URL']['secretKeyRef']
        assert ref == {'name': 'grafana-ops-webhook', 'key': 'url', 'optional': True}

    alert_config = yaml.safe_load(yaml.safe_load(
        (ROOT / 'k8s/monitoring/shared/grafana-alerts.yml').read_text()
    )['data']['alerts.yaml'])
    rules = {rule['uid']: rule for group in alert_config['groups'] for rule in group['rules']}
    assert rules['peekcart-outbox-failed']['labels']['signal'] == 'outbox-publish'
    assert rules['peekcart-dlq-backlog']['labels']['signal'] == 'dlq'


def extract_update():
    text = RUNBOOK.read_text()
    match = re.search(
        r'```sql\n(UPDATE outbox_events\b.*?;)\n```', text, flags=re.DOTALL
    )
    assert match, 'runbook must contain exactly one conditional outbox update statement'
    return match.group(1)


def check_mysql_test_matches_runbook():
    test_source = MYSQL_TEST.read_text()
    match = re.search(r'String recoverySql = """\n(.*?)\n\s*""";', test_source, flags=re.DOTALL)
    assert match, 'notification MySQL integration test must execute the D-092 recovery SQL'
    test_sql = ' '.join(match.group(1).split())
    runbook_sql = ' '.join(extract_update().split())
    runbook_sql = runbook_sql.replace(':id', '?').replace(':event_id', '?').removesuffix(';')
    assert test_sql == runbook_sql, 'MySQL integration SQL must match the operator runbook statement exactly (JDBC bind markers allowed)'

    duplicate_test = re.search(r'String duplicateCountSql = """\n(.*?)\n\s*""";', test_source, flags=re.DOTALL)
    duplicate_runbook = re.search(
        r'```sql\n(SELECT COUNT\(\*\) AS payload_event_rows.*?;)\n```',
        RUNBOOK.read_text(), flags=re.DOTALL
    )
    assert duplicate_test and duplicate_runbook, 'MySQL integration test and runbook must include the duplicate payload preflight'
    java_duplicate = ' '.join(duplicate_test.group(1).split())
    docs_duplicate = ' '.join(duplicate_runbook.group(1).split())
    docs_duplicate = docs_duplicate.replace(' AS payload_event_rows', '').replace(':payload_event_id', '?').removesuffix(';')
    assert java_duplicate == docs_duplicate, 'MySQL duplicate preflight must match the operator runbook query'


def check_reissue_fixture():
    text = RUNBOOK.read_text()
    update_sql = extract_update()
    count_match = re.search(r'```sql\n(SELECT COUNT\(\*\) AS payload_event_rows.*?;)\n```', text, flags=re.DOTALL)
    assert count_match, 'runbook must contain a read-only duplicate-payload preflight query'
    duplicate_count_sql = count_match.group(1)
    for required in ('FOR UPDATE', 'ROLLBACK', 'record_kind', 'eventId', '별도 검토자'):
        assert required in text, f'runbook missing required guard: {required}'
    connection = sqlite3.connect(':memory:')
    connection.create_function('JSON_UNQUOTE', 1, lambda value: value)
    connection.executescript('''
        CREATE TABLE outbox_events (
            id INTEGER PRIMARY KEY,
            event_id TEXT NOT NULL UNIQUE,
            payload TEXT NOT NULL,
            status TEXT NOT NULL,
            retry_count INTEGER NOT NULL,
            last_attempted_at TEXT,
            published_at TEXT,
            record_kind TEXT
        );
    ''')
    rows = [
        (1, 'domain-1', json.dumps({'eventId': 'domain-1', 'value': 'same'}), 'FAILED', 5, 't1', None, 'DOMAIN'),
        (2, 'legacy-1', json.dumps({'eventId': 'legacy-1'}), 'FAILED', 5, 't1', None, None),
        (3, 'pending-1', json.dumps({'eventId': 'pending-1'}), 'PENDING', 2, 't2', None, 'DOMAIN'),
        (4, 'published-1', json.dumps({'eventId': 'published-1'}), 'PUBLISHED', 5, 't3', 't3', 'DOMAIN'),
        (5, 'replay-1', json.dumps({'eventId': 'replay-payload'}), 'FAILED', 5, 't1', None, 'REPLAY'),
        (6, 'mismatch-1', json.dumps({'eventId': 'other-id'}), 'FAILED', 5, 't1', None, 'DOMAIN'),
        (7, 'duplicate-payload', json.dumps({'eventId': 'duplicate-payload'}), 'FAILED', 5, 't1', None, 'DOMAIN'),
        (8, 'duplicate-other', json.dumps({'eventId': 'duplicate-payload'}), 'FAILED', 5, 't1', None, 'DOMAIN'),
        (9, 'published-marker', json.dumps({'eventId': 'published-marker'}), 'FAILED', 5, 't1', 't3', 'DOMAIN'),
    ]
    connection.executemany('INSERT INTO outbox_events VALUES (?, ?, ?, ?, ?, ?, ?, ?)', rows)
    connection.commit()

    def snapshot(row_id):
        return connection.execute(
            'SELECT event_id, payload, status, retry_count, last_attempted_at, published_at, record_kind '
            'FROM outbox_events WHERE id = ?', (row_id,)
        ).fetchone()

    before = {row[0]: snapshot(row[0]) for row in rows}
    for row_id, event_id, expected_count in ((1, 'domain-1', 1), (2, 'legacy-1', 1)):
        connection.execute('BEGIN IMMEDIATE')
        count = connection.execute(duplicate_count_sql, {'payload_event_id': event_id}).fetchone()[0]
        assert count == expected_count
        cursor = connection.execute(update_sql, {'id': row_id, 'event_id': event_id})
        assert cursor.rowcount == 1
        connection.commit()

    for row_id, event_id in ((3, 'pending-1'), (4, 'published-1'), (5, 'replay-1'),
                             (6, 'mismatch-1'), (9, 'published-marker')):
        connection.execute('BEGIN IMMEDIATE')
        cursor = connection.execute(update_sql, {'id': row_id, 'event_id': event_id})
        assert cursor.rowcount == 0
        connection.rollback()

    # Payload-eventId duplicates fail the read-only preflight, so the mutation is never attempted.
    duplicate_count = connection.execute(duplicate_count_sql, {'payload_event_id': 'duplicate-payload'}).fetchone()[0]
    assert duplicate_count == 2
    for row_id in (7, 8):
        assert snapshot(row_id) == before[row_id]

    # Mutation control: dropping the cross-row count gate lets the otherwise valid target through.
    connection.execute('BEGIN IMMEDIATE')
    unsafe_mutant = connection.execute(update_sql, {'id': 7, 'event_id': 'duplicate-payload'})
    assert unsafe_mutant.rowcount == 1, 'duplicate-preflight removal must be detected by this control'
    connection.rollback()
    for row_id in (7, 8):
        assert snapshot(row_id) == before[row_id]

    for row_id, event_id in ((1, 'domain-1'), (2, 'legacy-1')):
        after = snapshot(row_id)
        assert after[:2] == before[row_id][:2]  # event_id and payload bytes stay unchanged
        assert after[2:] == ('PENDING', 0, None, None, before[row_id][6])
    for row_id in (3, 4, 5, 6, 9):
        assert snapshot(row_id) == before[row_id]
    connection.close()


def expected_policy_table():
    """Derive per-topic re-issue verdicts from the replay policy registry and DLQ topology."""
    policies = re.findall(
        r'put\(PeekcartService\.(\w+), "([^"]+)",\s*(deny|allow|allowWithPrecondition)\(',
        REPLAY_POLICY.read_text()
    )
    assert policies, 'ReplayPolicyRegistry policies were not found'
    producers = {}
    for owner, body in re.findall(
        r'QUARANTINE\.put\(PeekcartService\.(\w+), Set\.copyOf\(List\.of\((.*?)\)\)\);',
        DLQ_TOPOLOGY.read_text(), flags=re.DOTALL
    ):
        for topic in re.findall(r'dlq\("([^"]+)"\)', body):
            assert topic not in producers, f'topic has two producers: {topic}'
            producers[topic] = owner.lower()
    consumers = {}
    for owner, topic, kind in policies:
        pairs = consumers.setdefault(topic, {})
        assert owner.lower() not in pairs, f'duplicate replay policy: {owner}/{topic}'
        pairs[owner.lower()] = PAIR_LABELS[kind]
    assert set(consumers) == set(producers), 'every consumed topic needs exactly one producer and vice versa'
    table = {}
    for topic, pairs in consumers.items():
        labels = set(pairs.values())
        verdict = '금지' if 'DENY' in labels else '사전조건' if 'PRECONDITION' in labels else '가능'
        table[topic] = (producers[topic], pairs, verdict)
    return table


def runbook_policy_table(text):
    section = text.split('### 2-1.', 1)[1].split('\n## ', 1)[0]
    table = {}
    for topic, producer, pairs, verdict in re.findall(
        r'^\| `([a-z.-]+)` \| (\w+) \| ([^|]+?) \| (\S+) \|$', section, flags=re.MULTILINE
    ):
        assert topic not in table, f'duplicate runbook row: {topic}'
        table[topic] = (producer, dict(re.findall(r'(\w+) (ALLOW|DENY|PRECONDITION)', pairs)), verdict)
    return table


def assert_policy_table(text, expected):
    assert runbook_policy_table(text) == expected, 'runbook §2-1 must match ReplayPolicyRegistry and DlqTopology'


def check_reissue_policy_table():
    text = RUNBOOK.read_text()
    expected = expected_policy_table()
    assert_policy_table(text, expected)
    windows = {}
    for service in SERVICES:
        config = (ROOT / f'{service}-service/src/main/resources/application.yml').read_text()
        windows[service] = re.search(r'^\s+dlq-replay-window:\s*(\S+)', config, flags=re.MULTILINE).group(1)
    assert len(set(windows.values())) == 1, f'dlq-replay-window differs across services: {windows}'
    assert f'`app.idempotency.floor.dlq-replay-window`(현재 {windows["order"]})' in text, \
        'runbook must state the current dedupe window'
    for required in ('created_at', 'processed_events', 'ReplayPolicyRegistry', 'PRECONDITION'):
        assert required in text, f'runbook missing re-issue condition: {required}'
    # Mutation control: a flipped pair, a dropped row and a changed verdict must all be rejected.
    row = '| `order.created` | order | product DENY, payment DENY, notification ALLOW | 금지 |'
    assert row in text
    for mutant in (
        text.replace(row, row.replace('product DENY', 'product ALLOW')),
        text.replace(row + '\n', ''),
        text.replace(row, row.replace('| 금지 |', '| 가능 |')),
    ):
        try:
            assert_policy_table(mutant, expected)
        except AssertionError:
            continue
        raise AssertionError('policy table checker accepted a flipped, dropped or changed-verdict mutant')


def assert_names_present(text, names):
    for name in names:
        assert re.search(rf'(?<![\w-]){re.escape(name)}(?![\w-])', text), f'Slack secret runbook must name {name}'


def check_secret_runbook():
    text = SECRET_RUNBOOK.read_text()
    profile = (ROOT / 'notification-service/src/main/resources/application-k8s.yml').read_text()
    env_names = set(re.findall(r'\$\{(SLACK_[A-Z_]+)\}', profile))
    assert env_names == {'SLACK_USER_WEBHOOK_URL', 'SLACK_OPS_WEBHOOK_URL'}, env_names
    deployment = next(
        doc for doc in yaml.safe_load_all((ROOT / 'k8s/base/services/notification-service/deployment.yml').read_text())
        if doc and doc['kind'] == 'Deployment'
    )
    secret_refs = {
        source['secretRef']['name']
        for container in deployment['spec']['template']['spec']['containers']
        for source in container.get('envFrom', []) if 'secretRef' in source
    }
    assert secret_refs == {'notification-service-secret'}, secret_refs
    routing = yaml.safe_load(ROUTING.read_text())
    contact = yaml.safe_load(routing['data']['contact-points.yaml'])['contactPoints']
    grafana_env = re.fullmatch(r'\$\{(\w+)\}', contact[0]['receivers'][0]['settings']['url']).group(1)
    grafana_ref = yaml.safe_load((ROOT / 'k8s/monitoring/gke/values-prometheus.yml').read_text())[
        'grafana']['envValueFrom'][grafana_env]['secretKeyRef']
    names = sorted(env_names | secret_refs | {grafana_env, grafana_ref['name'], contact[0]['name']})
    assert_names_present(text, names)
    assert f'--from-file={grafana_ref["key"]}=' in text and f'.data.{grafana_ref["key"]}' in text, \
        'runbook must inject and check the Grafana secret key used by values-prometheus.yml'
    # Mutation control: a renamed variable must be rejected.
    mutant = text.replace('SLACK_OPS_WEBHOOK_URL', 'SLACK_OPS_URL')
    try:
        assert_names_present(mutant, names)
    except AssertionError:
        return
    raise AssertionError('secret runbook checker accepted a renamed environment variable')


if __name__ == '__main__':
    check_routing()
    check_mysql_test_matches_runbook()
    check_reissue_fixture()
    check_reissue_policy_table()
    check_secret_runbook()
    print('D-092 routing and SQLite logical fixtures passed; MySQL statement parity, re-issue policy table '
          'and Slack secret runbook names checked, no Grafana, Slack, cluster, or production DB was used.')
