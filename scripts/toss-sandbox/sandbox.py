#!/usr/bin/env python3
"""D-064 Toss 테스트 환경 실호출 검증 도구 (docs/plans/D-064.md §4).

로컬 스택(루트 docker-compose.yml 과 local 프로필 서비스 3개)에서 결제 1건을 만든다. 승인과 환불은 PeekCart 경로
(ApprovalExecutor, RefundExecutor)가 실제 Toss 를 부르게 하고, 조회와 중복 승인·중복 취소는 Toss 를 직접 불러 응답
원문을 남긴다.

키는 .cache/toss-sandbox.env 의 TOSS_CLIENT_KEY(test_gck_), TOSS_SECRET_KEY(test_gsk_) 에서만 읽는다. 결제위젯 테스트
키 한 세트가 아니면 거부하고, 값은 화면에도 파일에도 쓰지 않는다. 산출물은 .cache/toss-sandbox/<run>/ 이다(저장소 밖. fixture 에는 정리본만 옮긴다).

순서:
  up             compose(MySQL·Redis·Kafka) 와 product 8082, order 8081, payment 8083 기동
                 (--without-keys 는 키 없이 스택만 점검한다. 승인 호출은 인증에서 막힌다)
  serve          결제창 페이지와 successUrl·failUrl(127.0.0.1:8765). 다른 터미널에 띄워 둔다
  prepare        상품·주문을 만들고 결제창 주소를 출력한다
  (브라우저)     결제창을 한 번 통과한다
  confirm        PeekCart 승인 접수와 승인 원장 종결 대기
  probe-confirm  조회(DONE), 같은 멱등키 승인 재요청, 다른 멱등키 승인 재요청
  refund         order.compensation.requested 주입과 환불 원장 종결 대기
  probe-cancel   같은 멱등키 취소 재요청, 다른 멱등키 취소 재요청, 조회(CANCELED)
  report         run 의 호출별 상태 코드와 PeekCart 가 읽는 필드
  fixtures       run 의 응답에서 식별값만 가짜로 바꿔 payment-service 테스트 fixture 로 쓴다
  down           up 이 띄운 서비스 종료와 검증 전용 compose 프로젝트 삭제(볼륨 포함)
  self-test      키 거부, successUrl 대조, 페이지 주입 검사
"""
import argparse
import base64
import html
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ENV_FILE = ROOT / '.cache' / 'toss-sandbox.env'
CACHE = ROOT / '.cache' / 'toss-sandbox'
LOGS = CACHE / 'logs'
TOSS = 'https://api.tosspayments.com'
SERVE = ('127.0.0.1', 8765)
# 루트 compose 파일을 검증 전용 프로젝트로 띄운다. 기존 로컬 볼륨은 서비스별 DB 계정이 생기기 전에 초기화된 경우가 있어
# (2026-10-26 실측: Access denied for user 'peekcart_product') 새 볼륨에서 init 스크립트가 계정을 만들게 한다.
COMPOSE = ['docker', 'compose', '-p', 'peekcart-toss-sandbox']
PORTS ={'product': 8082, 'order': 8081, 'payment': 8083}
SCHEMAS = {'product': 'peekcart_product', 'order': 'peekcart_order', 'payment': 'peekcart_payment'}
# e2e 의 x-app-env 와 같다. 전환기 헤더 모드로 gateway 없이 컨트롤러를 지나고, local 프로필의 디버그 로그는 되돌린다.
COMMON_ENV = {
    'APP_INTERNAL_TOKEN_MODE': 'DUAL_ACCEPT',
    'SPRING_JPA_SHOW_SQL': 'false',
    'SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL': 'false',
    'LOGGING_LEVEL_COM_PEEKCART': 'INFO',
    'LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY': 'INFO',
}
PRICE = 10000
PG_ORDER_ID = re.compile(r'[A-Za-z0-9_-]{6,64}')
RUN_ID = re.compile(r'20\d{6}-\d{6}')
REPORT = ['approval-ledger', 'lookup-done', 'confirm-same-key', 'confirm-other-key',
          'refund-ledger', 'cancel-same-key', 'cancel-other-key', 'lookup-canceled']
FIXTURES = ROOT / 'payment-service' / 'src' / 'test' / 'resources' / 'toss-sandbox'
# fixture 이름과 원본 run 파일. 원장 두 개는 PeekCart 가 승인·취소 호출에서 받은 응답 원문(200)이다.
FIXTURE_SOURCES = {'confirm-done': 'approval-ledger', 'lookup-done': 'lookup-done',
                   'confirm-same-key': 'confirm-same-key', 'confirm-other-key': 'confirm-other-key',
                   'cancel-done': 'refund-ledger', 'cancel-same-key': 'cancel-same-key',
                   'cancel-other-key': 'cancel-other-key', 'lookup-canceled': 'lookup-canceled'}
# 식별값만 고정된 가짜 값으로 바꾼다. 시각, 금액, status, cancels 구조처럼 PeekCart 가 판정에 읽는 필드는 그대로 둔다.
# number 는 카드 번호 일부, secret 은 결제별 웹훅 검증 값, url 은 결제 키가 실린 영수증·checkout 주소다.
FAKE = {'paymentKey': 'tsandbox_fixture_payment_key', 'orderId': 'pc_fixture_order_0001', 'mId': 'tgen_fixture',
        'lastTransactionKey': 'txrd_fixture_last', 'transactionKey': 'txrd_fixture_cancel',
        'secret': 'ps_fixture_secret', 'number': '00000000****000*', 'url': 'https://example.invalid/toss-sandbox'}


def fail(message):
    sys.exit('중단: ' + message)


# ----------------------------------------------------------------- 키와 run

def parse_keys(text):
    keys = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            name, value = line.split('=', 1)
            keys[name.strip()] = value.strip().strip('"\'')
    client, secret = keys.get('TOSS_CLIENT_KEY', ''), keys.get('TOSS_SECRET_KEY', '')
    # 라이브 키는 실제 출금이 일어난다. 결제창은 결제위젯 SDK 라 결제위젯 연동 키 한 세트만 받는다. 처음 넣은 API 개별 연동
    # 클라이언트 키(ck)로 구버전 결제창을 열자 결제창 열기 요청이 401 이었다(2026-10-26, 계획서 §1 정정).
    if not client.startswith('test_gck_') or not secret.startswith('test_gsk_'):
        raise ValueError('TOSS_CLIENT_KEY 는 test_gck_, TOSS_SECRET_KEY 는 test_gsk_ 로 시작해야 한다'
                         '(결제위젯 테스트 키 한 세트, 라이브 키 거부)')
    return client, secret


def load_keys():
    if not ENV_FILE.exists():
        fail('%s 가 없다. 계획서 §4 준비 2 를 따른다' % ENV_FILE.relative_to(ROOT))
    try:
        return parse_keys(ENV_FILE.read_text(encoding='utf-8'))
    except ValueError as e:
        fail(str(e))


def run_dir(run_id):
    if run_id is None:
        runs = sorted(p for p in CACHE.glob('20*') if p.is_dir() and RUN_ID.fullmatch(p.name))
        if not runs:
            fail('run 이 없다. prepare 를 먼저 실행한다')
        return runs[-1]
    if not RUN_ID.fullmatch(run_id) or not (CACHE / run_id).is_dir():
        fail('run %s 가 없다' % run_id)
    return CACHE / run_id


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('  기록', path.relative_to(ROOT))


def parse_body(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return raw


# ----------------------------------------------------------------- 로컬 스택

def sql(service, query):
    """루트 compose 의 MySQL 질의. --raw 라 JSON 원문이 그대로 나온다. 행마다 한 줄이다.

    클라이언트 문자셋을 utf8mb4 로 지정한다. 컨테이너 기본값으로는 저장된 한글이 ? 로 바뀌어 나온다(2026-10-26 실측, 저장값은 정상)."""
    out = subprocess.run(['docker', 'exec', 'peekcart-mysql', 'mysql', '-N', '-B', '--raw', '--default-character-set=utf8mb4',
                          '-uroot', '-proot', SCHEMAS[service], '-e', query], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return [line for line in out.stdout.splitlines() if line]


def http(service, method, path, body=None, user_id=1, role='USER'):
    headers = {'Content-Type': 'application/json', 'X-User-Id': str(user_id), 'X-User-Role': role,
               'X-User-Family-Id': 'toss-sandbox'}
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request('http://localhost:%d%s' % (PORTS[service], path), data=data, headers=headers,
                                 method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        return e.code, json.loads(raw) if raw.strip().startswith('{') else {'raw': raw}


def wait_for(desc, fn, timeout=120, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = fn()
        except (urllib.error.URLError, ConnectionError):
            value = None
        if value:
            return value
        time.sleep(interval)
    fail('%s 대기 %d초 초과' % (desc, timeout))


def health_up(service):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/health' % PORTS[service], timeout=5) as r:
            return json.loads(r.read().decode('utf-8')).get('status') == 'UP'
    except (urllib.error.URLError, ConnectionError, ValueError):
        return False


def cmd_up(args):
    # 키 없이 띄우면 payment 는 base 의 test_sk_placeholder 로 서서 승인 호출이 인증에서 막힌다. 스택 점검용이다.
    secret = None if args.without_keys else load_keys()[1]
    subprocess.run(COMPOSE + ['up', '-d', 'mysql', 'redis', 'kafka'], cwd=ROOT, check=True)
    wait_for('MySQL', lambda: subprocess.run(['docker', 'exec', 'peekcart-mysql', 'mysqladmin', 'ping', '-uroot',
                                              '-proot', '--silent'], capture_output=True).returncode == 0)
    subprocess.run(['./gradlew', '-q'] + [':%s-service:bootJar' % s for s in PORTS], cwd=ROOT, check=True)
    LOGS.mkdir(parents=True, exist_ok=True)
    procs = {}
    for service, port in PORTS.items():
        env = dict(os.environ, **COMMON_ENV, SERVER_PORT=str(port))
        if service == 'payment':
            # 환불 dispatcher 주기는 e2e 와 같은 이름과 값이다(기본 30초).
            env['APP_REFUND_DISPATCH_INTERVAL_MS'] = '2000'
            if secret:
                env['TOSS_SECRET_KEY'] = secret
        jar = ROOT / ('%s-service' % service) / 'build' / 'libs' / ('%s-service.jar' % service)
        log = open(LOGS / ('%s.log' % service), 'w', encoding='utf-8')
        procs[service] = subprocess.Popen(['java', '-jar', str(jar)], cwd=ROOT, env=env, stdout=log,
                                          stderr=subprocess.STDOUT, start_new_session=True)
        (LOGS / ('%s.pid' % service)).write_text(str(procs[service].pid))
        print('  %s 기동 pid %d, 로그 %s' % (service, procs[service].pid, (LOGS / ('%s.log' % service)).relative_to(ROOT)))

    def ready(service):
        if procs[service].poll() is not None:
            fail('%s 가 종료됐다(exit %s). 로그를 본다' % (service, procs[service].returncode))
        return health_up(service)

    for service in PORTS:
        wait_for('%s health' % service, lambda s=service: ready(s), 240)
        print('  %s UP' % service)
    if not secret:
        print('키 없이 기동했다. 승인 전에 down 후 키를 넣고 up 을 다시 실행한다')


def cmd_down(args):
    for service in PORTS:
        pid_file = LOGS / ('%s.pid' % service)
        if not pid_file.exists():
            continue
        pid = int(pid_file.read_text())
        try:
            os.killpg(pid, signal.SIGTERM)
            print('  %s 종료 신호 pid %d' % (service, pid))
        except ProcessLookupError:
            print('  %s 는 이미 종료됐다' % service)
        pid_file.unlink()
    # 검증 전용 프로젝트라 볼륨까지 지운다. 개발용 기본 프로젝트의 볼륨은 건드리지 않는다.
    subprocess.run(COMPOSE + ['down', '-v'], cwd=ROOT, check=True)


# ----------------------------------------------------------------- 결제창

def page_config(state, client_key, base):
    return {'clientKey': client_key, 'customerKey': state['customer_key'], 'orderId': state['pg_order_id'],
            'amount': state['amount'], 'orderName': state['order_name'],
            'successUrl': '%s/success/%s' % (base, state['run_id']), 'failUrl': '%s/fail/%s' % (base, state['run_id'])}


def render_checkout(config):
    # 값에 </script> 가 들어가도 스크립트 블록이 닫히지 않도록 </ 를 이스케이프한다.
    payload = json.dumps(config, ensure_ascii=False).replace('</', '<\\/')
    return (HERE / 'checkout.html').read_text(encoding='utf-8').replace('/*__CONFIG__*/null', payload)


def cmd_serve(args):
    client_key, _ = load_keys()
    base = 'http://%s:%d' % SERVE

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            pass   # successUrl 쿼리에 paymentKey 가 실린다. 기록은 run 디렉터리 파일로만 한다

        def reply(self, status, body):
            data = body.encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            url = urlparse(self.path)
            parts = url.path.strip('/').split('/')
            if len(parts) != 2 or parts[0] not in ('checkout', 'success', 'fail') or not RUN_ID.fullmatch(parts[1]):
                return self.reply(404, 'not found')
            kind, d = parts[0], CACHE / parts[1]
            if not (d / 'state.json').exists():
                return self.reply(404, 'run 없음')
            if kind == 'checkout':
                return self.reply(200, render_checkout(page_config(read_json(d / 'state.json'), client_key, base)))
            query = {k: v[0] for k, v in parse_qs(url.query).items()}
            write_json(d / ('%s.json' % kind), query)
            message = ('인증 완료. 터미널에서 sandbox.py confirm 을 바로 실행한다' if kind == 'success'
                       else '결제창 실패: %s %s' % (query.get('code', ''), query.get('message', '')))
            return self.reply(200, '<!doctype html><meta charset="utf-8"><p>%s</p>' % html.escape(message))

    print('결제창 서버 %s (Ctrl+C 로 종료)' % base)
    ThreadingHTTPServer(SERVE, Handler).serve_forever()


# ----------------------------------------------------------------- 주문과 PeekCart 경로

def prepared(order_id, user_id):
    status, body = http('payment', 'POST', '/api/v1/payments/%d/prepare' % order_id, user_id=user_id)
    return body.get('data') if status == 200 else None


def cmd_prepare(args):
    run_id =time.strftime('%Y%m%d-%H%M%S')
    d = CACHE / run_id
    d.mkdir(parents=True)
    user_id = 640_000_000 + int(time.time()) % 1_000_000   # run 마다 빈 장바구니
    if not sql('product', "SELECT id FROM categories WHERE name = 'toss-sandbox'"):
        sql('product', "INSERT INTO categories (name) VALUES ('toss-sandbox')")
    category_id = int(sql('product', "SELECT id FROM categories WHERE name = 'toss-sandbox'")[0])
    status, body = http('product', 'POST', '/api/v1/admin/products', {
        'categoryId': category_id, 'name': 'toss-sandbox-%s' % run_id, 'description': 'D-064 Toss 테스트 결제',
        'price': PRICE, 'imageUrl': 'http://example.invalid/x.png', 'stock': 5}, role='ADMIN')
    if status != 201:
        fail('상품 생성 %d: %s' % (status, body))
    product_id = body['data']['id']
    wait_for('order-service 단가 캐시 product=%s' % product_id,
             lambda: sql('order', 'SELECT product_id FROM product_price_cache WHERE product_id = %d' % product_id))
    status, body = http('order', 'POST', '/api/v1/cart/items', {'productId': product_id, 'quantity': 1},
                        user_id=user_id)
    if status != 201:
        fail('장바구니 %d: %s' % (status, body))
    status, body = http('order', 'POST', '/api/v1/orders', {
        'receiverName': 'toss-sandbox', 'phone': '010-0000-0000', 'zipcode': '00000', 'address': 'toss-sandbox'},
        user_id=user_id)
    if status != 201:
        fail('주문 생성 %d: %s' % (status, body))
    order_id = body['data'].get('orderId', body['data'].get('id'))
    wait_for('재고 예약 order=%s' % order_id, lambda: sql(
        'product', "SELECT status FROM stock_reservations WHERE order_id = %d AND status IN ('RESERVED','CONFIRMED')"
        % order_id))
    checkout = wait_for('결제 준비 order=%s' % order_id, lambda: prepared(order_id, user_id), 60)
    if checkout.get('orderId') != order_id or not PG_ORDER_ID.fullmatch(str(checkout.get('pgOrderId'))):
        fail('prepare 응답이 이상하다: %s' % checkout)
    write_json(d / 'state.json', {
        'run_id': run_id, 'user_id': user_id, 'product_id': product_id, 'order_id': order_id,
        'pg_order_id': checkout['pgOrderId'], 'amount': checkout['amount'],
        'order_name': 'PeekCart 테스트 주문 %s' % order_id, 'customer_key': 'sandbox-' + uuid.uuid4().hex[:16]})
    print('결제창: http://%s:%d/checkout/%s' % (SERVE + (run_id,)))


def check_success(state, success):
    """Toss 안내대로 승인 전에 successUrl 의 주문 ID 와 금액을 prepare 값과 맞춘다(브라우저에서 바뀔 수 있다)."""
    if success.get('orderId') != state['pg_order_id']:
        raise ValueError('successUrl orderId %r 가 PG 주문 ID 와 다르다' % success.get('orderId'))
    if str(success.get('amount')) != str(state['amount']):
        raise ValueError('successUrl amount %r 가 prepare 금액 %s 와 다르다' % (success.get('amount'), state['amount']))
    if not success.get('paymentKey'):
        raise ValueError('successUrl 에 paymentKey 가 없다')


def ledger(table, order_id):
    lines = sql('payment', "SELECT status, attempts, IFNULL(failure_code, ''), IFNULL(pg_response, '') FROM %s "
                           "WHERE order_id = %d" % (table, order_id))
    if not lines:
        return None
    status, attempts, code, raw = lines[0].split('\t', 3)
    return {'status': status, 'attempts': int(attempts), 'failure_code': code, 'pg_response': parse_body(raw)}


def settled(row, pending):
    return row if row and row['status'] not in pending else None


def cmd_confirm(args):
    d = run_dir(args.run)
    state = read_json(d / 'state.json')
    if not (d / 'success.json').exists():
        fail('success.json 이 없다. 결제창을 먼저 통과한다')
    success = read_json(d / 'success.json')
    try:
        check_success(state, success)
    except ValueError as e:
        fail(str(e))
    state['payment_key'] = success['paymentKey']
    write_json(d / 'state.json', state)
    status, body = http('payment', 'POST', '/api/v1/payments/confirm', {
        'paymentKey': state['payment_key'], 'orderId': state['order_id'], 'pgOrderId': state['pg_order_id'],
        'amount': state['amount']}, user_id=state['user_id'])
    write_json(d / 'peekcart-confirm.json', {'status': status, 'body': body})
    if status != 202:
        fail('confirm 접수가 %d 이다' % status)
    # 진행 중 상태는 ApprovalStatus 의 전이를 따른다. 재고 보호 확인을 기다리는 WAITING_STOCK 과 READY 를 거쳐 CLAIMED 에서
    # PG 를 부른다(ADR-0034). V11 주석의 4값만 보고 CLAIMED 하나로 두었다가 WAITING_STOCK 을 종결로 오판했다(2026-10-26).
    row = wait_for('승인 원장 종결', lambda: settled(ledger('payment_approvals', state['order_id']),
                                                ('WAITING_STOCK', 'READY', 'CLAIMED')))
    payment = sql('payment', 'SELECT status FROM payments WHERE order_id = %d' % state['order_id'])
    write_json(d / 'approval-ledger.json', dict(row, payment_status=payment[0] if payment else None))
    if row['status'] != 'SUCCEEDED':
        fail('승인 원장이 %s 로 닫혔다(계획서 §5)' % row['status'])
    print('승인 원장 SUCCEEDED, 결제 %s' % (payment[0] if payment else None))


def cmd_refund(args):
    d = run_dir(args.run)
    state = read_json(d / 'state.json')
    if not state.get('payment_key'):
        fail('confirm 을 먼저 실행한다')
    now = time.strftime('%Y-%m-%dT%H:%M:%S')
    # 승인된 주문은 사용자 취소로 환불되지 않는다(PAYMENT_COMPLETED 는 CANCELLED 로 못 간다). e2e 시나리오 C 처럼
    # 보상 요청을 넣는다. 메시지만 인위적이고 PG 취소는 dispatcher 와 RefundExecutor 가 부른다.
    envelope = {'eventId': 'toss-sandbox-%s' % state['run_id'], 'eventType': 'compensation.requested',
                'timestamp': now, 'schemaVersion': 1,
                'payload': {'orderId': state['order_id'], 'reason': 'PAID_BUT_CANCELLED', 'detectedAt': now}}
    subprocess.run(['docker', 'exec', '-i', 'peekcart-kafka', '/opt/kafka/bin/kafka-console-producer.sh',
                    '--bootstrap-server', 'localhost:9092', '--topic', 'order.compensation.requested',
                    '--property', 'parse.key=true', '--property', 'key.separator=|'],
                   input='%d|%s\n' % (state['order_id'], json.dumps(envelope, ensure_ascii=False)), text=True,
                   check=True)
    write_json(d / 'compensation-request.json', envelope)
    row = wait_for('환불 원장 종결', lambda: settled(ledger('payment_refunds', state['order_id']),
                                                ('REQUESTED', 'CLAIMED')), 180)
    outbox = None
    if row['status'] == 'SUCCEEDED':
        outbox = wait_for('payment.refunded 발행', lambda: sql(
            'payment', "SELECT status FROM outbox_events WHERE event_type = 'payment.refunded' "
                       "AND aggregate_id = '%d' AND status = 'PUBLISHED'" % state['order_id']), 60)
    write_json(d / 'refund-ledger.json', dict(row, refunded_outbox=outbox[0] if outbox else None))
    if row['status'] != 'SUCCEEDED':
        fail('환불 원장이 %s 로 닫혔다(계획서 §5)' % row['status'])
    print('환불 원장 SUCCEEDED, payment.refunded %s' % outbox[0])


# ----------------------------------------------------------------- Toss 직접 호출

def toss(secret, method, path, body=None, idempotency_key=None):
    headers = {'Authorization': 'Basic ' + base64.b64encode((secret + ':').encode('utf-8')).decode('ascii'),
               'Content-Type': 'application/json'}
    if idempotency_key:
        headers['Idempotency-Key'] = idempotency_key
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(TOSS + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')


def probe(d, name, secret, method, path, body=None, idempotency_key=None):
    status, raw = toss(secret, method, path, body, idempotency_key)
    parsed = parse_body(raw)
    # 인증 헤더는 남기지 않는다. 요청은 PeekCart 가 보내는 것과 같은 본문과 멱등키다.
    write_json(d / ('%s.json' % name), {'request': {'method': method, 'path': path, 'idempotencyKey': idempotency_key,
                                                   'body': body}, 'status': status, 'body': parsed})
    print('  %s: HTTP %d %s' % (name, status, fields(parsed)))


def probe_state(args):
    d = run_dir(args.run)
    state = read_json(d / 'state.json')
    if not state.get('payment_key'):
        fail('confirm 을 먼저 실행한다')
    return d, state, load_keys()[1]


def cmd_probe_confirm(args):
    d, state, secret = probe_state(args)
    key = state['payment_key']
    body = {'paymentKey': key, 'orderId': state['pg_order_id'], 'amount': state['amount']}
    probe(d, 'lookup-done', secret, 'GET', '/v1/payments/%s' % key)
    probe(d, 'confirm-same-key', secret, 'POST', '/v1/payments/confirm', body, 'approve-%d' % state['order_id'])
    probe(d, 'confirm-other-key', secret, 'POST', '/v1/payments/confirm', body, 'sandbox-%s-confirm' % state['run_id'])


def cmd_probe_cancel(args):
    d, state, secret = probe_state(args)
    key = state['payment_key']
    body = {'cancelReason': '주문 보상 환불'}
    probe(d, 'cancel-same-key', secret, 'POST', '/v1/payments/%s/cancel' % key, body, 'refund-%d' % state['order_id'])
    probe(d, 'cancel-other-key', secret, 'POST', '/v1/payments/%s/cancel' % key, body,
          'sandbox-%s-cancel' % state['run_id'])
    probe(d, 'lookup-canceled', secret, 'GET', '/v1/payments/%s' % key)


# ----------------------------------------------------------------- 보고와 자체 검사

def fields(body):
    """PeekCart 가 판정에 쓰는 필드만 뽑는다(TossPaymentClient, TossPaymentIdentity, ApprovalExecutor)."""
    if not isinstance(body, dict):
        return {'raw': str(body)[:80]}
    cancels = body.get('cancels')
    return {'code': body.get('code'), 'status': body.get('status'), 'orderId': body.get('orderId'),
            'totalAmount': body.get('totalAmount'), 'method': body.get('method'), 'approvedAt': body.get('approvedAt'),
            'cancelSum': sum(c.get('cancelAmount') or 0 for c in cancels) if isinstance(cancels, list) else None}


def cmd_report(args):
    d = run_dir(args.run)
    for name in REPORT:
        path = d / ('%s.json' % name)
        if not path.exists():
            print('%-18s 없음' % name)
            continue
        data = read_json(path)
        if name.endswith('ledger'):
            print('%-18s 원장 %s, 시도 %s, 코드 %r, PG 응답 %s' % (name, data['status'], data['attempts'],
                                                         data['failure_code'], fields(data['pg_response'])))
        else:
            print('%-18s HTTP %s %s' % (name, data['status'], fields(data['body'])))


def sanitize(value):
    if isinstance(value, dict):
        return {k: FAKE[k] if k in FAKE and isinstance(v, str) else sanitize(v) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitize(v) for v in value]
    return value


def identifiers(value, found):
    """FAKE 로 바꿀 키에 들어 있던 원래 값을 모은다. 정리본에 하나라도 남으면 실패한다."""
    if isinstance(value, dict):
        for k, v in value.items():
            if k in FAKE and isinstance(v, str):
                found.add(v)
            else:
                identifiers(v, found)
    elif isinstance(value, list):
        for v in value:
            identifiers(v, found)
    return found


def cmd_fixtures(args):
    d = run_dir(args.run)
    state = read_json(d / 'state.json')
    FIXTURES.mkdir(parents=True, exist_ok=True)
    for name, source in FIXTURE_SOURCES.items():
        data = read_json(d / ('%s.json' % source))
        status, body = (200, data['pg_response']) if source.endswith('ledger') else (data['status'], data['body'])
        text = json.dumps({'status': status, 'body': sanitize(body)}, ensure_ascii=False, indent=2) + '\n'
        originals = identifiers(body, {state['payment_key'], state['pg_order_id']}) - set(FAKE.values())
        leaked = sorted(v for v in originals if v and v in text)
        if leaked:
            fail('%s 정리본에 원래 식별값 %d개가 남았다' % (name, len(leaked)))
        path = FIXTURES / ('%s.json' % name)
        path.write_text(text, encoding='utf-8')
        print('  기록', path.relative_to(ROOT))


def cmd_self_test(args):
    failures = []

    def check(name, ok):
        print(('ok   ' if ok else 'FAIL ') + name)
        if not ok:
            failures.append(name)

    for text, accept in [('TOSS_CLIENT_KEY=test_gck_a\nTOSS_SECRET_KEY=test_gsk_b\n', True),
                         ('TOSS_CLIENT_KEY="test_gck_a"\nTOSS_SECRET_KEY=\'test_gsk_b\'\n', True),
                         ('TOSS_CLIENT_KEY=live_gck_a\nTOSS_SECRET_KEY=live_gsk_b\n', False),
                         ('TOSS_CLIENT_KEY=test_gck_a\nTOSS_SECRET_KEY=live_gsk_b\n', False),
                         ('TOSS_CLIENT_KEY=test_ck_a\nTOSS_SECRET_KEY=test_sk_b\n', False),
                         ('TOSS_CLIENT_KEY=test_gck_a\n', False)]:
        try:
            parse_keys(text)
            accepted = True
        except ValueError:
            accepted = False
        check('키 %s: %s' % ('수락' if accept else '거부', text.splitlines()[-1].split('=')[1][:9]), accepted == accept)
    state = {'pg_order_id': 'pc-abc123', 'amount': 10000}
    for success, accept in [({'orderId': 'pc-abc123', 'amount': '10000', 'paymentKey': 'k'}, True),
                            ({'orderId': 'pc-other1', 'amount': '10000', 'paymentKey': 'k'}, False),
                            ({'orderId': 'pc-abc123', 'amount': '100', 'paymentKey': 'k'}, False),
                            ({'orderId': 'pc-abc123', 'amount': '10000'}, False)]:
        try:
            check_success(state, success)
            accepted = True
        except ValueError:
            accepted = False
        check('successUrl %s: %s' % ('수락' if accept else '거부', success), accepted == accept)
    page = render_checkout({'orderName': '</script><script>alert(1)</script>'})
    check('페이지 주입이 스크립트 블록을 닫지 않는다', '</script><script>alert' not in page and '__CONFIG__' not in page)
    raw = {'paymentKey': 'tgen_real', 'card': {'number': '12345678****123*', 'amount': 9979}, 'secret': 'ps_real',
           'cancels': [{'transactionKey': 'txrd_real', 'cancelAmount': 10000}], 'approvedAt': '2026-10-26T01:37:13+09:00'}
    clean = json.dumps(sanitize(raw))
    check('fixture 정리가 식별값을 지우고 판정 필드는 남긴다',
          not any(v in clean for v in ('tgen_real', '12345678', 'ps_real', 'txrd_real'))
          and '"cancelAmount": 10000' in clean and '2026-10-26T01:37:13+09:00' in clean)
    sys.exit(1 if failures else 0)


def main():
    parser = argparse.ArgumentParser(description='D-064 Toss 테스트 환경 실호출 검증 (docs/plans/D-064.md §4)')
    sub = parser.add_subparsers(dest='command', required=True)
    commands = {'up': cmd_up, 'down': cmd_down, 'serve': cmd_serve, 'prepare': cmd_prepare, 'confirm': cmd_confirm,
                'probe-confirm': cmd_probe_confirm, 'refund': cmd_refund, 'probe-cancel': cmd_probe_cancel,
                'report': cmd_report, 'fixtures': cmd_fixtures, 'self-test': cmd_self_test}
    for name, fn in commands.items():
        p = sub.add_parser(name)
        p.set_defaults(fn=fn)
        if name in ('confirm', 'probe-confirm', 'refund', 'probe-cancel', 'report', 'fixtures'):
            p.add_argument('--run', help='run 디렉터리 이름(기본: 가장 최근)')
        if name == 'up':
            p.add_argument('--without-keys', action='store_true', help='키 없이 스택만 점검한다(승인 불가)')
    args = parser.parse_args()
    args.fn(args)


if __name__ == '__main__':
    main()
