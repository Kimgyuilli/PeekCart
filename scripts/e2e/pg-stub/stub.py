#!/usr/bin/env python3
"""E2E PG stub — Toss 취소/조회 API 의 계약 형태만 흉내낸다 (계획 P2).

**전역 모드를 두지 않는다.** 응답은 paymentKey 접두사가 결정하는 *불변 script* 다 —
전역 모드는 시나리오가 순서대로 돌 때 앞 시나리오의 설정이 뒤 시나리오를 오염시킨다(계획 P10).

script (paymentKey 접두사):
  e2e-ok-*            승인/취소 성공                  POST -> 200
  e2e-4xx-*           영구 거절                      POST -> 400 {"code":"NOT_CANCELABLE_AMOUNT"}
  e2e-transient-*     일시 실패(재시도 소진)          POST -> 500
  e2e-already-full-*  이미 취소됨 + 조회상 전액 취소  POST -> 400 ALREADY_CANCELED / GET -> cancels 합계 = amount
  e2e-already-part-*  이미 취소됨 + 조회상 금액 부족  POST -> 400 ALREADY_CANCELED / GET -> cancels 합계 < amount
  e2e-already-fail-*  이미 취소됨 + 조회 실패         POST -> 400 ALREADY_CANCELED / GET -> 500
  e2e-timeout-*       응답 지연(클라이언트 타임아웃)   POST -> sleep
  e2e-cfail-*         승인 확정 거절 (시나리오 A) confirm -> 400 {"code":"REJECT_CARD_COMPANY"}

**script 없는 paymentKey 는 500 `STUB_UNSCRIPTED_KEY`** 다. 기본값을 성공으로 두면
오타 난 키가 조용히 성공해 시나리오가 무엇을 검증했는지 알 수 없게 된다.

조회 전용 관측 표면:
  GET /__ledger  호출 원장(paymentKey·method·path·Idempotency-Key·순서)
  DELETE /__ledger  원장 초기화
  GET /__health  기동 확인
"""
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AMOUNT = int(os.environ.get("PG_STUB_AMOUNT", "10000"))
TIMEOUT_SLEEP = float(os.environ.get("PG_STUB_TIMEOUT_SLEEP", "60"))

_lock = threading.Lock()
_ledger = []

CANCEL_RE = re.compile(r"^/v1/payments/([^/]+)/cancel$")
FIND_RE = re.compile(r"^/v1/payments/([^/]+)$")
CONFIRM_PATH = "/v1/payments/confirm"

ALREADY = {"code": "ALREADY_CANCELED_PAYMENT", "message": "이미 취소된 결제입니다."}


SCRIPTS = ("ok", "4xx", "transient", "already-full", "already-part", "already-fail", "timeout", "cfail")


def script_of(payment_key):
    for name in SCRIPTS:
        if payment_key.startswith("e2e-%s-" % name):
            return name
    return None   # 미등록 키 — 조용히 성공시키지 않는다


def record(method, path, payment_key, idempotency_key):
    with _lock:
        _ledger.append({
            "seq": len(_ledger) + 1,
            "method": method,
            "path": path,
            "paymentKey": payment_key,
            "idempotencyKey": idempotency_key,
        })


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):   # 기본 stderr 로깅은 compose 로그를 뒤덮는다
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/__health":
            self._send(200, {"status": "UP"})
            return
        if self.path == "/__ledger":
            with _lock:
                self._send(200, {"calls": list(_ledger)})
            return
        m = FIND_RE.match(self.path)
        if not m:
            self._send(404, {"code": "STUB_NO_ROUTE", "message": self.path})
            return

        payment_key = m.group(1)
        record("GET", self.path, payment_key, None)
        script = script_of(payment_key)
        if script is None:
            self._send(500, {"code": "STUB_UNSCRIPTED_KEY", "message": payment_key})
            return

        if script == "already-fail":
            self._send(500, {"code": "STUB_LOOKUP_FAILED", "message": "조회 실패 script"})
            return
        if script == "already-part":
            # 금액 불일치 — 전액 취소가 아니므로 호출자는 성공으로 확정하면 안 된다
            self._send(200, {"status": "PARTIAL_CANCELED",
                             "cancels": [{"cancelAmount": AMOUNT // 2}]})
            return
        if script in ("already-full", "ok", "cfail"):
            self._send(200, {"status": "CANCELED", "cancels": [{"cancelAmount": AMOUNT}]})
            return
        # 나머지 script 는 아직 취소되지 않은 상태로 보인다
        self._send(200, {"status": "DONE", "cancels": []})

    def _read_body(self):
        """요청 본문을 읽는다. **chunked 를 반드시 처리해야 한다.**

        JDK HttpClient(RestClient 의 기본 팩토리)는 Content-Length 없이
        `Transfer-Encoding: chunked` 로 보낸다. Content-Length 만 보면 본문이 0 바이트로
        보여 paymentKey 가 빈 문자열이 되고, script 판정이 통째로 무너진다 — 실제로 이
        stub 은 그동안 confirm 본문을 한 번도 읽지 못했다(아래 주석 참고).

        읽지 않고 남겨두면 keep-alive 커넥션에 본문이 남아 다음 요청의 파싱까지 어긋난다.
        """
        encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in encoding:
            parts = []
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    break
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    while True:                      # trailer + 종료 CRLF 소비
                        trailer = self.rfile.readline()
                        if trailer in (b"\r\n", b"\n", b""):
                            break
                    break
                parts.append(self.rfile.read(size))
                self.rfile.read(2)                   # 청크 뒤 CRLF
            return b"".join(parts)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_POST(self):
        raw = self._read_body() or b"{}"

        if self.path == CONFIRM_PATH:
            try:
                payload = json.loads(raw.decode("utf-8") or "{}")
            except ValueError:
                payload = {}
            payment_key = payload.get("paymentKey", "")
            record("POST", self.path, payment_key, self.headers.get("Idempotency-Key"))
            script = script_of(payment_key)
            if not payment_key:
                # **미등록 키와 구별한다.** 둘 다 500 으로 뭉개면 본문을 못 읽고 있다는
                # 사실이 "승인 실패" 로 보여 시나리오가 통과해 버린다(실제로 그랬다).
                self._send(500, {"code": "STUB_EMPTY_BODY",
                                 "message": "confirm 본문에 paymentKey 가 없다 — 전송/파싱 확인"})
            elif script is None:
                self._send(500, {"code": "STUB_UNSCRIPTED_KEY", "message": payment_key})
            elif script == "cfail":
                # 시나리오 A 의 실패 지점. **4xx 여야 한다** — 카드사 거절처럼 재시도해도
                # 상태가 바뀌지 않는 확정 거절이라야 payment.failed 체인이 성립한다.
                # 5xx 는 ADR-0023 D5 상 "과금이 성립했는지 모름" 이라 원장이 UNRESOLVED 로
                # 남고 주문 취소·재고 복구가 일어나지 않는다(D-020 의 수정 내용 그 자체다).
                self._send(400, {"code": "REJECT_CARD_COMPANY", "message": "카드사 거절 script"})
            else:
                self._send(200, {
                    "paymentKey": payment_key,
                    "orderId": str(payload.get("orderId", "")),
                    "status": "DONE",
                    "method": "간편결제",
                    "approvedAt": "2026-08-27T12:00:00+09:00",
                })
            return

        m = CANCEL_RE.match(self.path)
        if not m:
            self._send(404, {"code": "STUB_NO_ROUTE", "message": self.path})
            return

        payment_key = m.group(1)
        record("POST", self.path, payment_key, self.headers.get("Idempotency-Key"))
        script = script_of(payment_key)
        if script is None:
            self._send(500, {"code": "STUB_UNSCRIPTED_KEY", "message": payment_key})
            return

        if script in ("ok", "cfail"):
            self._send(200, {"status": "CANCELED", "cancels": [{"cancelAmount": AMOUNT}]})
        elif script == "4xx":
            self._send(400, {"code": "NOT_CANCELABLE_AMOUNT", "message": "취소 불가 금액"})
        elif script == "transient":
            self._send(500, {"code": "STUB_TRANSIENT", "message": "일시 오류 script"})
        elif script.startswith("already-"):
            self._send(400, ALREADY)
        elif script == "timeout":
            time.sleep(TIMEOUT_SLEEP)
            self._send(200, {"status": "CANCELED", "cancels": [{"cancelAmount": AMOUNT}]})
        else:
            self._send(500, {"code": "STUB_NO_BRANCH", "message": script})

    def do_DELETE(self):
        if self.path == "/__ledger":
            with _lock:
                _ledger.clear()
            self._send(200, {"cleared": True})
            return
        self._send(404, {"code": "STUB_NO_ROUTE", "message": self.path})


def _self_test():
    """script 판정이 **본문을 실제로 읽는지** 고정한다.

    이 self-test 가 없어서 다음이 30분짜리 e2e 에서야 드러났다: RestClient 의 기본
    팩토리(JDK HttpClient)는 `Transfer-Encoding: chunked` 로 보내는데 stub 이
    Content-Length 만 읽어 paymentKey 가 빈 문자열이 됐고, 그 결과 **confirm script 가
    한 번도 실행되지 않은 채** 500(STUB_UNSCRIPTED_KEY)만 돌아갔다. 호출자가 5xx 를
    실패로 뭉개던 시절엔 시나리오가 그대로 통과했다 — false-green 이다.
    """
    import http.client
    import threading

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    fails = 0

    def check(name, actual, expected):
        nonlocal fails
        if actual == expected:
            print("  ok   [%s]" % name)
        else:
            fails += 1
            print("  FAIL [%s] — 기대 %r / 실제 %r" % (name, expected, actual))

    def post(path, body, chunked):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        data = body.encode("utf-8")
        if chunked:
            conn.putrequest("POST", path)
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Transfer-Encoding", "chunked")
            conn.endheaders()
            conn.send(b"%x\r\n" % len(data) + data + b"\r\n0\r\n\r\n")
        else:
            conn.request("POST", path, body=data,
                         headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8")
        conn.close()
        return resp.status, json.loads(raw or "{}")

    def get(path):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        conn.request("GET", path)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8")
        conn.close()
        return resp.status, json.loads(raw or "{}")

    print("pg-stub self-test")

    body = '{"paymentKey":"e2e-cfail-x-1","orderId":"1","amount":10000}'

    # 핵심 — chunked 로 보내도 script 가 먹어야 한다.
    status, payload = post(CONFIRM_PATH, body, chunked=True)
    check("chunked confirm: script 적용", (status, payload.get("code")),
          (400, "REJECT_CARD_COMPANY"))

    # 대조군 — Content-Length 로도 동일해야 한다(둘 다 같은 판정이라야 의미가 있다).
    status, payload = post(CONFIRM_PATH, body, chunked=False)
    check("content-length confirm: script 적용", (status, payload.get("code")),
          (400, "REJECT_CARD_COMPANY"))

    status, payload = post(CONFIRM_PATH,
                           '{"paymentKey":"e2e-ok-x-1","orderId":"1","amount":10000}', chunked=True)
    check("chunked confirm: 성공 script", (status, payload.get("status")), (200, "DONE"))

    # 본문을 못 읽는 상태를 **미등록 키와 구별**한다. 뭉개면 위 함정이 되살아난다.
    status, payload = post(CONFIRM_PATH, "{}", chunked=True)
    check("본문 없음 → STUB_EMPTY_BODY", (status, payload.get("code")), (500, "STUB_EMPTY_BODY"))

    status, payload = post(CONFIRM_PATH,
                           '{"paymentKey":"typo-key","orderId":"1","amount":10000}', chunked=True)
    check("미등록 키 → STUB_UNSCRIPTED_KEY", (status, payload.get("code")),
          (500, "STUB_UNSCRIPTED_KEY"))

    # 원장이 빈 키를 기록하면 시나리오의 호출 대조가 전부 무의미해진다.
    _, ledger = get("/__ledger")
    keys = [c["paymentKey"] for c in ledger["calls"] if c["path"] == CONFIRM_PATH]
    check("원장이 실제 paymentKey 를 기록", keys[0], "e2e-cfail-x-1")

    server.shutdown()
    if fails:
        print("self-test 실패 %d건" % fails)
        return 1
    print("self-test 통과 (6종)")
    return 0


if __name__ == "__main__":
    import sys
    if "--self-test" in sys.argv:
        raise SystemExit(_self_test())
    port = int(os.environ.get("PG_STUB_PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
