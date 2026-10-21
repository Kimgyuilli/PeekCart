#!/usr/bin/env python3
"""Real local Prometheus/Grafana rule evaluation, preserving production rules and timers.

Default prepares files only. --run requires root's global heavy slot. No GKE or external
notifications. Normal -> Pending -> Alerting -> Normal evidence uses actual elapsed time.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import yaml

ROOT = Path(__file__).resolve().parents[2]
IMAGES = {'prometheus': 'prom/prometheus:v3.2.1', 'grafana': 'grafana/grafana:11.6.0', 'fixture': 'python:3.12-alpine'}
CONTRACT = {
    'peekcart-compensation-backlog': ({'order-service'}, 900),
    'peekcart-dlq-backlog': ({'notification-service', 'order-service', 'payment-service', 'product-service'}, 600),
    'peekcart-outbox-failed': ({'notification-service', 'order-service', 'payment-service', 'product-service'}, 300),
    'peekcart-cache-fallback-get': ({'product-service'}, 600),
    'peekcart-cache-fallback-evict': ({'product-service'}, 0),
}


def duration(text):
    import re
    return sum(int(n) * {'h': 3600, 'm': 60, 's': 1}[u] for n, u in re.findall(r'(\d+)([hms])', text))


def all_normal(result):
    """Grafana rule state and instance state use different vocabularies."""
    for uid, rule in result.items():
        if rule['state'] != 'inactive' or not rule['vector'] or not rule['alerts']:
            return False
        if any(float(v['value'][1]) != 0 for v in rule['vector']):
            return False
        if any(a['state'] != 'Normal' for a in rule['alerts']):
            return False
        if {a['labels'].get('application') for a in rule['alerts']} != CONTRACT[uid][0]:
            return False
        if uid.startswith('peekcart-cache-fallback') and {a['labels'].get('cache') for a in rule['alerts']} != {'product', 'products', 'productStock'}:
            return False
    return set(result) == set(CONTRACT)


def assert_alerting_instances(uid, rule):
    # Grafana 11.6 /api/prometheus/grafana/api/v1/rules: rule=firing,
    # each individual instance=Alerting (not the Prometheus rule vocabulary).
    assert rule['state'] == 'firing', (uid, rule['state'])
    assert rule['alerts'] and all(a['state'] == 'Alerting' for a in rule['alerts']), (uid, rule['alerts'])
    assert {a['labels'].get('application') for a in rule['alerts']} == CONTRACT[uid][0], uid
    if uid.startswith('peekcart-cache-fallback'):
        assert {a['labels'].get('cache') for a in rule['alerts']} == {'product', 'products', 'productStock'}, uid


def slack_message_fields(notification):
    """Return only Slack message text and attachment text/field values."""
    values = [notification.get('text', '')]
    for attachment in notification.get('attachments', []):
        if not isinstance(attachment, dict):
            continue
        values.extend((attachment.get('title', ''), attachment.get('text', '')))
        for field in attachment.get('fields', []):
            if isinstance(field, dict):
                values.extend((field.get('title', ''), field.get('value', '')))
    return '\n'.join(str(value) for value in values if value)


def assert_operations_routes_are_separate(notifications, expected, default_signals):
    """Require each operations signal in its own captured Slack request."""
    messages = [slack_message_fields(notification) for notification in notifications]
    routed = {}
    for signal in expected:
        matches = [i for i, message in enumerate(messages)
                   if re.search(rf'(?i)signal\s*=\s*{re.escape(signal)}\b', message)]
        assert matches, ('operations Slack payload is missing a matched signal', signal, messages)
        routed[signal] = matches[0]
    assert len(set(routed.values())) == len(expected), (
        'matched signals must arrive in separate Slack requests', routed, messages
    )
    for signal in default_signals:
        assert not any(re.search(rf'(?i)signal\s*=\s*{re.escape(signal)}\b', message)
                       for message in messages), (
            'default-route signal leaked into operations Slack payload', signal
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source_path = ROOT / 'k8s/monitoring/shared/grafana-alerts.yml'
    source_bytes = source_path.read_bytes()
    original = yaml.safe_load(source_bytes)['data']['alerts.yaml']
    config = yaml.safe_load(original)
    rules = {r['uid']: r for g in config['groups'] for r in g['rules'] if r['uid'] in CONTRACT}
    assert set(rules) == set(CONTRACT)
    assert all(duration(rules[uid]['for']) == expected[1] for uid, expected in CONTRACT.items())
    assert all(g['interval'] == '1m' for g in config['groups'])
    for directory in ('provisioning/alerting', 'provisioning/datasources'):
        (out / directory).mkdir(parents=True)
    (out / 'provisioning/alerting/alerts.yaml').write_text(original)
    routing_path = ROOT / 'k8s/monitoring/shared/grafana-notification-routing.yml'
    routing_bytes = routing_path.read_bytes()
    routing = yaml.safe_load(routing_bytes)
    routing_data = routing['data']
    points = yaml.safe_load(routing_data['contact-points.yaml'])['contactPoints']
    policies = yaml.safe_load(routing_data['notification-policies.yaml'])['policies']
    assert len(points) == 1 and points[0]['name'] == 'peekcart-operations'
    assert points[0]['receivers'][0]['settings']['url'] == '${PEAKCART_OPS_SLACK_WEBHOOK_URL}'
    assert len(policies) == 1 and policies[0]['receiver'] == 'grafana-default-email'
    assert policies[0]['routes'] == [{
        'receiver': 'peekcart-operations',
        'object_matchers': [['signal', '=~', '^(outbox-publish|dlq)$']],
        'continue': False,
    }]
    for filename, body in routing_data.items():
        (out / 'provisioning/alerting' / filename).write_text(body)
    (out / 'capture').mkdir()
    (out / 'capture/notifications.jsonl').write_text('')
    (out / 'provisioning/datasources/prometheus.yaml').write_text(yaml.safe_dump({
        'apiVersion': 1, 'datasources': [{'name': 'Prometheus', 'uid': 'prometheus', 'type': 'prometheus',
                                       'access': 'proxy', 'url': 'http://prometheus:9090', 'isDefault': True}]}))
    (out / 'prometheus.yml').write_text(yaml.safe_dump({'global': {'scrape_interval': '5s'},
        'scrape_configs': [{'job_name': 'fixture', 'honor_labels': True,
                            'static_configs': [{'targets': ['fixture-a:8000', 'fixture-b:8000']}]}]}))
    shutil.copyfile(ROOT / 'scripts/monitoring/fixture_exporter.py', out / 'fixture_exporter.py')
    (out / 'state.json').write_text(json.dumps({'mode': 'healthy'}))
    project = 'peakcart-alert-' + str(int(time.time()))
    services = {
        'prometheus': {'image': IMAGES['prometheus'], 'ports': ['127.0.0.1::9090'],
                       'volumes': ['./prometheus.yml:/etc/prometheus/prometheus.yml:ro']},
        'grafana': {'image': IMAGES['grafana'], 'ports': ['127.0.0.1::3000'],
                    'environment': {'GF_SECURITY_ADMIN_USER': 'local-fixture', 'GF_SECURITY_ADMIN_PASSWORD': 'local-fixture',
                                    'GF_ANALYTICS_REPORTING_ENABLED': 'false', 'GF_ANALYTICS_CHECK_FOR_UPDATES': 'false',
                                    'GF_UNIFIED_ALERTING_ENABLED': 'true',
                                    # Local receiver only; never sourced from live secret material.
                                    'PEAKCART_OPS_SLACK_WEBHOOK_URL': 'http://fixture-a:8000/ops'},
                    'volumes': ['./provisioning:/etc/grafana/provisioning:ro']},
    }
    for instance in ('fixture-a', 'fixture-b'):
        services[instance] = {'image': IMAGES['fixture'],
                              'environment': {'FIXTURE_INSTANCE': instance,
                                              'NOTIFICATIONS': '/capture/notifications.jsonl'},
                              'volumes': ['.:/fixture:ro', './capture:/capture'],
                              'command': ['python', '/fixture/fixture_exporter.py']}
    (out / 'compose.yml').write_text(yaml.safe_dump({'services': services}, sort_keys=False))
    manifest = {'sourceSHA': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'sourceDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
                'ruleFileSha256': hashlib.sha256(source_bytes).hexdigest(),
                'provisionedRulesSha256': hashlib.sha256(original.encode()).hexdigest(),
                'project': project, 'images': IMAGES,
                'scope': 'Local fixture evaluation only; no GKE discovery, app emission or external delivery evidence'}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    if not args.run:
        print(f'Prepared only: {out}', flush=True)
        return
    assert not manifest['sourceDirty'], 'Commit reviewed harness before real evidence run'
    compose = ['docker', 'compose', '-p', project, '-f', str(out / 'compose.yml')]
    events = open(out / 'observations.jsonl', 'a', buffering=1)
    started = time.time()
    auth = 'Basic ' + base64.b64encode(b'local-fixture:local-fixture').decode()

    def command(*args):
        return subprocess.check_output([*compose, *args], text=True, stderr=subprocess.STDOUT)

    def get(url, authenticated=False):
        return json.load(urlopen(Request(url, headers={'Authorization': auth} if authenticated else {}), timeout=15))

    def wait_api(url, authenticated=False):
        deadline = time.time() + 180
        while time.time() < deadline:
            try:
                return get(url, authenticated)
            except Exception:
                time.sleep(2)
        raise AssertionError('API startup timed out: ' + url)

    def set_mode(mode):
        temporary = out / 'state.tmp'
        temporary.write_text(json.dumps({'mode': mode}))
        temporary.replace(out / 'state.json')
        print(f'{time.strftime("%FT%TZ", time.gmtime())} mode={mode}', flush=True)

    try:
        (out / 'startup.log').write_text(command('up', '-d'))
        prometheus = 'http://' + command('port', 'prometheus', '9090').strip()
        grafana = 'http://' + command('port', 'grafana', '3000').strip()
        wait_api(prometheus + '/api/v1/status/buildinfo')
        wait_api(grafana + '/api/health')
        manifest['prometheus'] = get(prometheus + '/api/v1/status/buildinfo')
        manifest['grafana'] = get(grafana + '/api/health')
        ids = command('ps', '-q').split()
        manifest['containers'] = json.loads(subprocess.check_output(['docker', 'inspect', *ids], text=True))
        manifest['imageIdentity'] = json.loads(subprocess.check_output(['docker', 'image', 'inspect', *IMAGES.values()], text=True))
        (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
        provisioned = get(grafana + '/api/v1/provisioning/alert-rules', True)
        (out / 'provisioned-rules.json').write_text(json.dumps(provisioned, indent=2))
        indexed = {r['uid']: r for r in provisioned}
        for uid, source in rules.items():
            actual = indexed[uid]
            assert duration(actual['for']) == duration(source['for'])
            assert actual['condition'] == source['condition']
            assert actual['labels'] == source['labels']
            for expected_data in source['data']:
                actual_data = next(d for d in actual['data'] if d['refId'] == expected_data['refId'])
                assert actual_data['datasourceUid'] == expected_data['datasourceUid']
                for key, value in expected_data['model'].items():
                    assert actual_data['model'][key] == value, (uid, key)

        def snapshot(phase):
            raw = get(grafana + '/api/prometheus/grafana/api/v1/rules', True)
            all_rules = [r for g in raw['data']['groups'] for r in g['rules']]
            result = {}
            for uid, source in rules.items():
                actual = next(r for r in all_rules if r.get('uid') == uid or r.get('name') == source['title'])
                expr = next(d['model']['expr'] for d in source['data'] if d['refId'] == 'A')
                query = get(prometheus + '/api/v1/query?' + urlencode({'query': expr}))
                assert query['status'] == 'success'
                vector = query['data']['result']
                assert vector, (uid, 'NoData is not a healthy zero')
                apps = {row['metric']['application'] for row in vector}
                assert apps == CONTRACT[uid][0], (uid, apps)
                assert actual.get('health', 'ok') == 'ok', (uid, actual)
                if uid.startswith('peekcart-cache-fallback'):
                    assert {row['metric']['cache'] for row in vector} == {'product', 'products', 'productStock'}
                result[uid] = {'state': actual['state'], 'alerts': actual.get('alerts', []), 'vector': vector,
                               'lastEvaluation': actual.get('lastEvaluation')}
            entry = {'timestamp': time.time(), 'phase': phase, 'rules': result}
            events.write(json.dumps(entry) + '\n')
            return result

        def sample_for(phase, seconds, forbid_firing=False):
            deadline = time.time() + seconds
            seen = []
            while time.time() < deadline:
                state = snapshot(phase)
                if forbid_firing:
                    assert all(r['state'] != 'firing' for r in state.values()), (phase, state)
                seen.append(state)
                time.sleep(15)
            return seen

        # Two samples are needed for counter rate/increase; wait while keeping every series present.
        time.sleep(15)
        healthy = sample_for('baseline', 120, True)
        assert all_normal(healthy[-1])
        controls = {}
        for expression in (
                'dlq_backlog{application="user-service"}',
                'outbox_backlog{status="pending"}',
                'saga_compensation_backlog{status="resolved"}',
                'rate(cache_fallback_total{operation="put"}[5m])',
                'rate(cache_fallback_total{application="order-service",operation="get"}[5m])'):
            response = get(prometheus + '/api/v1/query?' + urlencode({'query': expression}))
            vector = response['data']['result']
            assert vector and all(float(row['value'][1]) > 0 for row in vector), expression
            assert {row['metric']['instance'] for row in vector} == {'fixture-a', 'fixture-b'}
            controls[expression] = response
        (out / 'negative-control-series.json').write_text(json.dumps(controls, indent=2))
        set_mode('transient')
        transient = sample_for('transient', 90, True)
        assert any(any(r['state'] == 'pending' for r in state.values()) for state in transient)
        set_mode('healthy')
        recovered = sample_for('transient-resolved', 390, True)
        assert all_normal(recovered[-1]), 'Non-sustained breach must return to Normal before firing'
        set_mode('sustained')
        sustained = sample_for('sustained', 1080)
        for uid, (apps, pending_seconds) in CONTRACT.items():
            states = [s[uid]['state'] for s in sustained]
            assert 'firing' in states, (uid, 'never fired')
            assert ('pending' in states) == (pending_seconds > 0), (uid, states)
            firing = next(s[uid] for s in reversed(sustained) if s[uid]['state'] == 'firing')
            assert_alerting_instances(uid, firing)
            if pending_seconds:
                observations = [json.loads(line) for line in (out / 'observations.jsonl').read_text().splitlines()]
                pending_at = next(o['timestamp'] for o in observations if o['phase'] == 'sustained' and o['rules'][uid]['state'] == 'pending')
                firing_at = next(o['timestamp'] for o in observations if o['phase'] == 'sustained' and o['rules'][uid]['state'] == 'firing')
                assert firing_at - pending_at >= pending_seconds - 20, (uid, 'fired before production pending duration')
            for alert in firing['alerts']:
                if uid.startswith('peekcart-cache-fallback'):
                    assert alert['labels']['signal'] == uid.removeprefix('peekcart-')
        notifications = [json.loads(line) for line in (out / 'capture/notifications.jsonl').read_text().splitlines()]
        notification_text = json.dumps(notifications, ensure_ascii=False).lower()
        assert notifications, 'matched operations alerts must reach the local receiver stub'
        assert 'outbox' in notification_text and 'dlq' in notification_text, notification_text
        expected_signals = {'outbox-publish', 'dlq'}
        default_signals = {
            rule['labels']['signal']
            for uid, rule in rules.items()
            if uid in CONTRACT and rule['labels'].get('signal')
        } - expected_signals
        # Grafana's Slack contact point sends a Slack message payload, not the webhook
        # notifier's `alerts[].labels` schema. Inspect only Slack text/attachment fields
        # and require separate requests so grouping both signals cannot false-pass.
        assert_operations_routes_are_separate(notifications, expected_signals, default_signals)
        try:
            assert_operations_routes_are_separate(
                [{'text': 'signal = outbox-publish\nsignal = dlq'}], expected_signals, default_signals
            )
        except AssertionError:
            pass
        else:
            raise AssertionError('route assertion accepted both signals in one Slack request')
        set_mode('healthy')
        resolved = sample_for('resolved', 390)
        assert all_normal(resolved[-1]), 'Recovery must be healthy zero, never absent/NoData'
        assert hashlib.sha256(source_path.read_bytes()).hexdigest() == manifest['ruleFileSha256']
        summary = {'result': 'PASS', 'startedAt': started, 'finishedAt': time.time(),
                   'rules': list(CONTRACT), 'originalForSeconds': {k: v[1] for k, v in CONTRACT.items()},
                   'negativeControls': ['wrong application/status', 'put operation', 'zero values', 'non-sustained breach'],
                   'operationsDelivery': 'Outbox and DLQ matched to the local receiver stub; no external endpoint',
                   'replicas': ['fixture-a', 'fixture-b'], 'sourceSHA': manifest['sourceSHA']}
        (out / 'summary.json').write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary), flush=True)
    finally:
        events.close()
        try:
            (out / 'containers.log').write_text(command('logs', '--no-color'))
        finally:
            (out / 'cleanup.log').write_text(command('down', '--volumes', '--remove-orphans'))
            assert not command('ps', '-q').strip(), 'Owned containers still running'


if __name__ == '__main__':
    main()
