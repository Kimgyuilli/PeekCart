#!/usr/bin/env python3
"""PG stub self-test (계획 P2 · §5).

stub 이 조용히 썩는 것을 막는다 — 특히 `GET /payments/{key}` 가 빠지면
`ALREADY_CANCELED` 분기와 reconciliation 이 도달 불가가 되는데, E2E 만으로는
그것이 "시나리오 미도달" 로 보일 뿐 원인을 짚어주지 않는다.
"""
import json
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import stub

FAILURES = []


CHECKS = 0


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  ok   %s" % name)
    else:
        print("  FAIL %s %s" % (name, detail))
        FAILURES.append(name)


def call(base, method, path, headers=None, body=None):
    req = urllib.request.Request(base + path, method=method, headers=headers or {})
    if method == "POST":
        req.data = json.dumps(body or {}).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), stub.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % server.server_address[1]

    print("PG stub self-test @ %s" % base)

    status, body = call(base, "POST", "/v1/payments/confirm", body={"paymentKey": "e2e-ok-9", "orderId": 9})
    check("confirm ok script → 200 + approvedAt/method",
          status == 200 and body["method"] and body["approvedAt"], "got %s %s" % (status, body))

    status, body = call(base, "POST", "/v1/payments/confirm", body={"paymentKey": "e2e-cfail-9", "orderId": 9})
    check("confirm cfail script → 400 (시나리오 A 의 실패 지점)",
          status == 400 and body["code"] == "REJECT_CARD_COMPANY", "got %s %s" % (status, body))

    status, body = call(base, "POST", "/v1/payments/confirm", body={"paymentKey": "typo-key", "orderId": 9})
    check("미등록 키 confirm → 500 STUB_UNSCRIPTED_KEY — 조용한 성공 차단",
          status == 500 and body["code"] == "STUB_UNSCRIPTED_KEY", "got %s %s" % (status, body))

    status, body = call(base, "POST", "/v1/payments/typo-key/cancel")
    check("미등록 키 cancel → 500 STUB_UNSCRIPTED_KEY",
          status == 500 and body["code"] == "STUB_UNSCRIPTED_KEY", "got %s %s" % (status, body))

    status, body = call(base, "GET", "/v1/payments/typo-key")
    check("미등록 키 조회 → 500 STUB_UNSCRIPTED_KEY",
          status == 500 and body["code"] == "STUB_UNSCRIPTED_KEY", "got %s %s" % (status, body))

    status, _ = call(base, "POST", "/v1/payments/e2e-ok-1/cancel", {"Idempotency-Key": "refund-1"})
    check("ok script → 200", status == 200, "got %s" % status)

    status, body = call(base, "POST", "/v1/payments/e2e-4xx-1/cancel")
    check("4xx script → 400 + code", status == 400 and body["code"] == "NOT_CANCELABLE_AMOUNT",
          "got %s %s" % (status, body))

    status, _ = call(base, "POST", "/v1/payments/e2e-transient-1/cancel")
    check("transient script → 500", status == 500, "got %s" % status)

    for name in ("full", "part", "fail"):
        status, body = call(base, "POST", "/v1/payments/e2e-already-%s-1/cancel" % name)
        check("already-%s script → 400 ALREADY_CANCELED_PAYMENT" % name,
              status == 400 and body["code"] == "ALREADY_CANCELED_PAYMENT", "got %s %s" % (status, body))

    status, body = call(base, "GET", "/v1/payments/e2e-already-full-1")
    total = sum(c["cancelAmount"] for c in body.get("cancels", []))
    check("already-full 조회 → 전액 취소", status == 200 and total == stub.AMOUNT,
          "got %s total=%s" % (status, total))

    status, body = call(base, "GET", "/v1/payments/e2e-already-part-1")
    total = sum(c["cancelAmount"] for c in body.get("cancels", []))
    check("already-part 조회 → 금액 부족(전액 아님)", status == 200 and 0 < total < stub.AMOUNT,
          "got %s total=%s" % (status, total))

    status, _ = call(base, "GET", "/v1/payments/e2e-already-fail-1")
    check("already-fail 조회 → 500", status == 500, "got %s" % status)

    # 원장: paymentKey 별 script 이므로 서로 다른 키의 호출이 섞이지 않는다
    _, ledger = call(base, "GET", "/__ledger")
    calls = ledger["calls"]
    ok_calls = [c for c in calls if c["paymentKey"] == "e2e-ok-1"]
    check("원장이 Idempotency-Key 를 기록한다",
          len(ok_calls) == 1 and ok_calls[0]["idempotencyKey"] == "refund-1", str(ok_calls))

    already_full = [(c["method"]) for c in calls if c["paymentKey"] == "e2e-already-full-1"]
    check("already-full ledger 순서 = POST → GET", already_full == ["POST", "GET"], str(already_full))

    check("원장이 순번을 매긴다", [c["seq"] for c in calls] == list(range(1, len(calls) + 1)))

    status, _ = call(base, "DELETE", "/__ledger")
    _, ledger = call(base, "GET", "/__ledger")
    check("원장 초기화", status == 200 and ledger["calls"] == [])

    status, _ = call(base, "GET", "/v1/nope")
    check("모르는 경로는 404 — 조용한 200 금지", status == 404, "got %s" % status)

    server.shutdown()
    if FAILURES:
        print("\n%d 건 실패: %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("\nself-test 통과 (%d 검사)" % CHECKS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
