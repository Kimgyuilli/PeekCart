#!/usr/bin/env python3
"""D-078 측정 세션 캡처 보조 (loadtest/captures.md).

세 가지만 한다. 스크린샷 자체는 찍지 않는다 — 브라우저로 `plan` 이 낸 URL 을 열어 찍는다.

  annotate  run 구간을 대시보드 4개에 region annotation 으로 남긴다
  plan      항목별 패널의 단독 화면 URL 과 저장 파일명을 TSV 로 낸다 (절대 시간 고정)
  export    같은 패널의 Prometheus 원 시계열을 JSON 으로 저장한다 — 클러스터를 지운 뒤 다시 그릴 수 있게

패널은 **제목으로 찾아 id 로 가리킨다.** 제목이 바뀌어 못 찾으면 조용히 건너뛰지 않고 실패한다.
시간은 epoch 초로 받는다(`date +%s`).

사용:
  GRAFANA_URL=http://localhost:3000 GRAFANA_AUTH=admin:admin \
    python3 loadtest/scripts/d078-capture.py annotate --label "B list OFF r2" --from 1793000000 --to 1793000120
  python3 loadtest/scripts/d078-capture.py plan   --item d078-5 --label contend-r1 --from ... --to ...
  PROM_URL=http://localhost:9090 \
    python3 loadtest/scripts/d078-capture.py export --item d078-5 --label contend-r1 --from ... --to ... --out-dir <dir>
"""
import argparse
import base64
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

DASHBOARD_DIR = Path(__file__).resolve().parents[2] / 'k8s' / 'monitoring' / 'shared'
DASHBOARDS = {
    'api-jvm': 'api-jvm-dashboard.json',
    'kafka-lag': 'kafka-lag-dashboard.json',
    'pod-resources': 'pod-resources-dashboard.json',
    'saga': 'saga-messaging-dashboard.json',
}

# loadtest/captures.md §항목별 캡처 표와 같은 내용이다. 표를 고치면 여기도 고친다.
CAPTURES = {
    'd078-1': [('api-jvm', 'Request Rate (RPS)'), ('api-jvm', 'API Response Time (p95)'),
               ('saga', 'MySQL CPU 사용 / 한도'), ('saga', 'CPU 스로틀 (컨테이너별)')],
    'd078-2': [('pod-resources', 'Pod CPU Usage'), ('saga', 'HikariCP 대기 커넥션'),
               ('saga', 'CPU 스로틀 (컨테이너별)')],
    'd078-4': [('saga', '노드 CPU 사용률')],
    'd078-5': [('saga', '소비 전달 실패율 (cause 별)'), ('saga', '누적 실패 · DLQ 유입 (Pod 기동 이후)'),
               ('saga', '재고 예약 결과 (건/초)'), ('kafka-lag', 'Kafka Consumer Lag (Max)')],
    'd078-5-drain': [('saga', 'Outbox 발행률 (건/초)'), ('saga', 'Outbox backlog')],
    'd078-6': [('saga', 'taskScheduler active / pool'), ('saga', 'taskScheduler 대기 작업')],
    'd078-jvm': [('api-jvm', 'JVM Heap Memory'), ('api-jvm', 'JVM GC Pause')],
    'd078-hpa': [('pod-resources', 'HPA Current Replicas'), ('pod-resources', 'Pod CPU Usage')],
}

# 측정 런은 60초~3분이라 15초 scrape 에 맞춘다. 대시보드의 $__rate_interval 도 같은 창으로 푼다.
STEP_SECONDS = 15
RATE_INTERVAL = '1m'


def load_dashboard(key, directory=DASHBOARD_DIR):
    return json.loads((Path(directory) / DASHBOARDS[key]).read_text(encoding='utf-8'))


def find_panel(dashboard, title):
    matches = [p for p in dashboard['panels'] if p.get('title') == title and p.get('type') != 'row']
    if len(matches) != 1:
        raise SystemExit(f"패널을 하나로 특정할 수 없다: '{title}' ({len(matches)}개) — "
                         f"대시보드 제목이 바뀌었으면 CAPTURES 와 captures.md 를 함께 고친다")
    panel = matches[0]
    if 'id' not in panel:
        raise SystemExit(f"패널 id 가 없다: '{title}' — 단독 화면 URL 이 다른 패널을 가리킬 수 있다")
    return panel


def resolve(item, directory=DASHBOARD_DIR):
    if item not in CAPTURES:
        raise SystemExit(f'알 수 없는 항목: {item} (가능: {", ".join(sorted(CAPTURES))})')
    resolved = []
    for key, title in CAPTURES[item]:
        dashboard = load_dashboard(key, directory)
        resolved.append((key, dashboard['uid'], find_panel(dashboard, title)))
    return resolved


def file_stem(item, label, key, panel_id):
    safe_label = ''.join(c if c.isalnum() or c in '-_' else '-' for c in label).strip('-')
    return f'{item}-{safe_label}-{key}-p{panel_id}'


def solo_url(grafana_url, uid, panel_id, start, end):
    query = urllib.parse.urlencode({
        'orgId': 1, 'panelId': panel_id,
        'from': start * 1000, 'to': end * 1000,
        'var-application': '$__all', 'theme': 'light',
    })
    return f'{grafana_url.rstrip("/")}/d-solo/{uid}/?{query}'


def concrete_expr(expr):
    return expr.replace('$__rate_interval', RATE_INTERVAL).replace('$application', '.*')


def annotation_body(uid, label, start, end):
    return {'dashboardUID': uid, 'time': start * 1000, 'timeEnd': end * 1000,
            'tags': ['d078'], 'text': label}


def request(url, data=None, auth=None):
    headers = {'Content-Type': 'application/json'} if data is not None else {}
    if auth:
        headers['Authorization'] = 'Basic ' + base64.b64encode(auth.encode()).decode()
    body = json.dumps(data).encode() if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers), timeout=30) as resp:
        return json.loads(resp.read().decode())


def cmd_annotate(args):
    grafana = os.environ.get('GRAFANA_URL', 'http://localhost:3000')
    auth = os.environ.get('GRAFANA_AUTH', 'admin:admin')
    for key in DASHBOARDS:
        uid = load_dashboard(key)['uid']
        result = request(f'{grafana.rstrip("/")}/api/annotations',
                         annotation_body(uid, args.label, args.start, args.end), auth)
        print(f'{key}\t{uid}\tannotation id={result.get("id")}')


def cmd_plan(args):
    grafana = os.environ.get('GRAFANA_URL', 'http://localhost:3000')
    for key, uid, panel in resolve(args.item):
        # 브라우저 자동화 도구가 JPEG 로 저장한다. 확장자를 실제 형식과 맞춘다.
        print(f'{file_stem(args.item, args.label, key, panel["id"])}.jpg\t'
              f'{solo_url(grafana, uid, panel["id"], args.start, args.end)}')


def cmd_export(args):
    prom = os.environ.get('PROM_URL', 'http://localhost:9090').rstrip('/')
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for key, uid, panel in resolve(args.item):
        targets = []
        for target in panel.get('targets', []):
            expr = concrete_expr(target['expr'])
            query = urllib.parse.urlencode({'query': expr, 'start': args.start, 'end': args.end,
                                            'step': STEP_SECONDS})
            result = request(f'{prom}/api/v1/query_range?{query}')
            if result.get('status') != 'success':
                raise SystemExit(f'Prometheus 조회 실패: {expr} → {result}')
            targets.append({'expr': expr, 'legendFormat': target.get('legendFormat'),
                            'result': result['data']['result']})
        path = out / f'{file_stem(args.item, args.label, key, panel["id"])}.json'
        path.write_text(json.dumps({
            'dashboard_uid': uid, 'panel_id': panel['id'], 'title': panel['title'],
            'start': args.start, 'end': args.end, 'step_seconds': STEP_SECONDS,
            'rate_interval': RATE_INTERVAL, 'targets': targets,
        }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        series = sum(len(t['result']) for t in targets)
        print(f'{path}\tseries={series}')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('annotate', 'plan', 'export'):
        p = sub.add_parser(name)
        if name == 'annotate':
            p.add_argument('--label', required=True)
        else:
            p.add_argument('--item', required=True)
            p.add_argument('--label', required=True)
        p.add_argument('--from', dest='start', type=int, required=True, help='epoch 초')
        p.add_argument('--to', dest='end', type=int, required=True, help='epoch 초')
        if name == 'export':
            p.add_argument('--out-dir', required=True)
    args = parser.parse_args(argv)
    if args.end <= args.start:
        raise SystemExit('--to 는 --from 보다 뒤여야 한다')
    {'annotate': cmd_annotate, 'plan': cmd_plan, 'export': cmd_export}[args.command](args)


if __name__ == '__main__':
    sys.exit(main())
