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

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"

        if self.path == CONFIRM_PATH:
            try:
                payload = json.loads(raw.decode("utf-8") or "{}")
            except ValueError:
                payload = {}
            payment_key = payload.get("paymentKey", "")
            record("POST", self.path, payment_key, self.headers.get("Idempotency-Key"))
            script = script_of(payment_key)
            if script is None:
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


if __name__ == "__main__":
    port = int(os.environ.get("PG_STUB_PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
