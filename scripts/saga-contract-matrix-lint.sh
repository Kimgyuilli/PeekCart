#!/usr/bin/env bash
# saga-contract-matrix-lint — saga 계약 매트릭스 게이트 (계획 P11~P15)
#
# 매트릭스(`scripts/saga-contract-matrix.tsv`)는 "이 saga 의 어떤 계약이
# 무엇으로 증명되는가" 의 정본이다. 세 분기로 나뉜다:
#
#   --structure       매트릭스 자체의 유효성 (증적 없이도 돈다 — build 잡에서 먼저)
#   --jvm-evidence    JUnit XML 과 대조 — jvm 행이 실제로 통과했는가
#   --e2e-evidence    E2E manifest 와 대조(디렉터리 여럿은 쉼표로) — e2e 행의 관측값이 expected 와 정확히 같은가
#   --self-test       lint 자신이 위반을 잡는지 (fixture 조작)
#
# **required-ID 정본은 이 스크립트 안에 둔다**(계획 N4). 매트릭스가 유일한 입력이면
# 행을 지우는 것이 검사 대상만 줄여 통과하기 때문이다 — 게이트가 논리적으로 성립하려면
# "무엇이 있어야 하는가" 가 매트릭스 **밖**에 있어야 한다.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

MATRIX="${SAGA_MATRIX:-scripts/saga-contract-matrix.tsv}"

LINT_PY="$(mktemp -t saga-contract-matrix-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

COLUMNS = ["id", "evidence_type", "path", "fault", "expected", "evidence_key"]
EVIDENCE_TYPES = {"jvm", "e2e"}
ID_RE = re.compile(r"^SAGA-[A-Z0-9-]+$")

# ---- required-ID 정본 (계획 N4) — 매트릭스 밖에 둔다 ----------------------------
# 매트릭스가 기대 행의 유일한 입력이면 행 삭제는 검사 대상만 줄여 조용히 통과한다.
REQUIRED_IDS = {
    "SAGA-APPROVAL-FENCE-CONTENTION",
    "SAGA-APPROVAL-FENCE-TERMINAL-REORDER",
    "SAGA-APPROVAL-FENCE-CRASH",
    "SAGA-APPROVAL-FENCE-PG-SUCCESS-CRASH",
    "SAGA-APPROVAL-FENCE-MANUAL",

    "SAGA-APPROVAL-HELD-LEASE",
    "SAGA-APPROVAL-FENCE-DISPATCH",
    "SAGA-APPROVAL-LEASE-RACE",
    # 환불 결과 3종 × 소비자 3곳 (ADR-0018 D4)
    "SAGA-REFUND-RESULT-ORDER-SUCCEEDED",
    "SAGA-REFUND-RESULT-ORDER-FAILED",
    "SAGA-REFUND-RESULT-ORDER-UNRESOLVED",
    "SAGA-REFUND-RESULT-PRODUCT-SUCCEEDED",
    "SAGA-REFUND-RESULT-PRODUCT-FAILED",
    "SAGA-REFUND-RESULT-PRODUCT-UNRESOLVED",
    "SAGA-REFUND-RESULT-NOTIFICATION-SUCCEEDED",
    "SAGA-REFUND-RESULT-NOTIFICATION-FAILED",
    "SAGA-REFUND-RESULT-NOTIFICATION-UNRESOLVED",
    # crash matrix 4칸 (ADR-0018 D3)
    "SAGA-REFUND-CRASH-A",
    "SAGA-REFUND-CRASH-B",
    "SAGA-REFUND-CRASH-C",
    "SAGA-REFUND-CRASH-D",
    # fence 수렴
    "SAGA-REFUND-FENCE-CONVERGE",
    # timeout 3종 + 충돌 정책
    "SAGA-TIMEOUT-PAYMENT-PENDING",
    "SAGA-TIMEOUT-UNCONFIRMED-RESERVATION",
    "SAGA-TIMEOUT-LEASE-EXPIRED",
    "SAGA-TIMEOUT-OPTIMISTIC-LOSS",
    # sweeper
    "SAGA-SWEEPER-LEASE-RECLAIM",
    "SAGA-SWEEPER-CAS-LOSS",
    # 스케줄러 배선 (계획 P17)
    "SAGA-SCHEDULER-WIRING-ORDER",
    "SAGA-SCHEDULER-WIRING-PRODUCT",
    # E2E 시나리오
    "SAGA-E2E-PAYMENT-FAILED-CONVERGE",
    "SAGA-E2E-RESERVATION-FAILED",
    "SAGA-E2E-REFUND-CHAIN",
    "SAGA-E2E-DLQ-INTAKE",
    "SAGA-E2E-REFUND-RESULTS",
}

# ---- e2e expected 의 **필수 관측 키** 정본 (계획 N4 의 확장) ------------------
# required-ID 만 lint 밖에 두면 **행은 남기고 expected 를 축소**하는 우회가 남는다.
# 매트릭스의 expected 와 saga_e2e.py 의 actual 을 함께 `{"refund_rows":1}` 로 줄이면
# --structure 도 --e2e-evidence 도 통과하면서 payment_status·refund_status·outbox 계약이
# 사라진다(diff 리뷰 #1). "무엇을 관측해야 하는가" 도 매트릭스 밖에 있어야 한다.
REQUIRED_E2E_KEYS = {
    "SAGA-E2E-REFUND-RESULTS": {"success", "failed", "already", "reconcile", "synthetic_unresolved", "unresolved_producer_outbox"},
    "SAGA-APPROVAL-LEASE-RACE": {"held_during_unknown", "stock_while_cancelled", "stock_restored", "confirm_calls", "lookup_observed"},
    "SAGA-E2E-PAYMENT-FAILED-CONVERGE": {
        "cancel_reason", "order_status", "payment_status",
        "processed_events", "reservation_status",
    },
    "SAGA-E2E-RESERVATION-FAILED": {
        "cancel_reason", "order_status", "reserved_remaining",
    },
    "SAGA-E2E-REFUND-CHAIN": {
        "payment_refunded_outbox", "payment_status", "refund_rows", "refund_status",
    },
    "SAGA-E2E-DLQ-INTAKE": {
        "attempt_count", "rows",
    },
}


# ---- canonical JSON (계획 P11) -------------------------------------------------
def canonical(value):
    """key 사전순 · 타입 구분 · null 명시 · 배열은 각 원소의 canonical 문자열로 정렬.

    자유 문장을 금지하는 이유는 대조가 문자열 비교이기 때문이다 — 같은 의미의 다른
    표현이 통과하면 매트릭스가 무엇을 고정했는지 알 수 없다."""
    if isinstance(value, dict):
        items = sorted((k, canonical(v)) for k, v in value.items())
        return "{" + ",".join('%s:%s' % (json.dumps(k, ensure_ascii=False), v)
                              for k, v in items) + "}"
    if isinstance(value, list):
        return "[" + ",".join(sorted(canonical(v) for v in value)) + "]"
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    raise ValueError("지원하지 않는 타입: %r" % type(value))


def parse_expected(raw):
    """`expected` 는 **JSON object** 여야 한다. 스칼라·배열 최상위는 거부한다 —
    evidence_key 하나가 여러 관측값을 갖는 게 정상이고, object 가 아니면 그걸 담지 못한다."""
    doc = json.loads(raw)
    if not isinstance(doc, dict):
        raise ValueError("최상위가 object 가 아니다: %s" % type(doc).__name__)
    return doc


def read_matrix(path):
    with open(path, encoding="utf-8") as f:
        lines = [ln.rstrip("\n") for ln in f if ln.strip()]
    if not lines:
        raise SystemExit("매트릭스가 비어 있다: %s" % path)
    header = lines[0].split("\t")
    if header != COLUMNS:
        raise SystemExit("열 구성이 다르다\n  기대: %s\n  실제: %s" % (COLUMNS, header))
    rows = []
    for i, ln in enumerate(lines[1:], start=2):
        cells = ln.split("\t")
        if len(cells) != len(COLUMNS):
            raise SystemExit("%d행: 열 개수 %d (기대 %d)" % (i, len(cells), len(COLUMNS)))
        row = dict(zip(COLUMNS, cells))
        row["_line"] = i
        rows.append(row)
    return rows


# ---- --structure ---------------------------------------------------------------
def check_structure(rows):
    problems = []
    seen_ids, seen_keys = {}, {}

    for r in rows:
        ln = r["_line"]
        if not ID_RE.match(r["id"]):
            problems.append("%d행: id 형식 위반 %r (SAGA-대문자/숫자/하이픈)" % (ln, r["id"]))
        if r["id"] in seen_ids:
            problems.append("%d행: id 중복 %r (앞서 %d행)" % (ln, r["id"], seen_ids[r["id"]]))
        seen_ids[r["id"]] = ln

        if r["evidence_type"] not in EVIDENCE_TYPES:
            problems.append("%d행: evidence_type %r (허용 %s)"
                            % (ln, r["evidence_type"], sorted(EVIDENCE_TYPES)))

        if not os.path.exists(r["path"]):
            problems.append("%d행: path 가 실재하지 않는다 — %s" % (ln, r["path"]))

        if not r["fault"].strip():
            problems.append("%d행: fault 가 비어 있다 — 무엇을 주입했는지 없으면 증적이 아니다" % ln)

        try:
            doc = parse_expected(r["expected"])
        except (ValueError, json.JSONDecodeError) as e:
            problems.append("%d행: expected 가 canonical JSON object 가 아니다 — %s" % (ln, e))
        else:
            if canonical(doc) != r["expected"]:
                problems.append(
                    "%d행: expected 가 canonical 형태가 아니다\n    기대: %s\n    실제: %s"
                    % (ln, canonical(doc), r["expected"]))

        if not r["evidence_key"].strip():
            problems.append("%d행: evidence_key 가 비어 있다" % ln)
        if r["evidence_key"] in seen_keys:
            problems.append("%d행: evidence_key 중복 %r (앞서 %d행)"
                            % (ln, r["evidence_key"], seen_keys[r["evidence_key"]]))
        seen_keys[r["evidence_key"]] = ln

        # jvm 행의 키는 `<fqcn>#<SAGA-ID>` 여야 한다(계획 P13).
        if r["evidence_type"] == "jvm":
            if "#" not in r["evidence_key"]:
                problems.append("%d행: jvm evidence_key 는 '<fqcn>#<SAGA-ID>' 형식이어야 한다 — %r"
                                % (ln, r["evidence_key"]))
            else:
                fqcn, cid = r["evidence_key"].rsplit("#", 1)
                if cid != r["id"]:
                    problems.append("%d행: evidence_key 의 contract ID(%s)가 id(%s)와 다르다"
                                    % (ln, cid, r["id"]))
                if not fqcn or "/" in fqcn:
                    problems.append("%d행: evidence_key 의 클래스명이 FQCN 이 아니다 — %r" % (ln, fqcn))

    # **정확 일치**를 요구한다(계획 §7). 누락만 보면 임의의 SAGA-* 행을 덧붙여
    # 매트릭스를 부풀릴 수 있고, 그러면 "무엇이 계약인가" 가 다시 매트릭스 쪽으로 흘러간다.
    missing = REQUIRED_IDS - set(seen_ids)
    if missing:
        problems.append("필수 계약 ID 누락 (행 삭제는 검사 대상만 줄인다): %s" % sorted(missing))
    extra = set(seen_ids) - REQUIRED_IDS
    if extra:
        problems.append("정본에 없는 계약 ID: %s — 행을 추가하려면 lint 의 REQUIRED_IDS 도 "
                        "함께 갱신해야 한다(두 정본이 같이 움직인다)" % sorted(extra))

    # e2e 행은 **전부** REQUIRED_E2E_KEYS 에 등록돼 있어야 한다(R2 #4).
    # 등록을 빠뜨리면 `.get()` 이 None 을 돌려 그 행만 조용히 축소 검사에서 빠진다.
    unregistered = sorted(r["id"] for r in rows
                          if r["evidence_type"] == "e2e" and r["id"] not in REQUIRED_E2E_KEYS)
    if unregistered:
        problems.append("e2e 행이 REQUIRED_E2E_KEYS 에 없다: %s — 등록 없이는 expected 축소를 "
                        "막지 못한다" % unregistered)
    stale_keys = sorted(set(REQUIRED_E2E_KEYS) - {r["id"] for r in rows})
    if stale_keys:
        problems.append("REQUIRED_E2E_KEYS 에만 있는 ID: %s — 매트릭스와 어긋났다" % stale_keys)

    # expected 축소 차단 — 행을 남긴 채 관측 항목만 지우는 우회를 막는다.
    for r in rows:
        want_keys = REQUIRED_E2E_KEYS.get(r["id"])
        if not want_keys:
            continue
        try:
            doc = parse_expected(r["expected"])
        except (ValueError, json.JSONDecodeError):
            continue        # expected 문법 오류는 앞에서 이미 신고했다
        gone = want_keys - set(doc)
        if gone:
            problems.append("%d행 [%s]: expected 에서 필수 관측 키가 빠졌다 %s — "
                            "행을 남기고 관측만 줄이는 우회다"
                            % (r["_line"], r["id"], sorted(gone)))
    return problems


# ---- --jvm-evidence ------------------------------------------------------------
def collect_junit(roots):
    """JUnit XML 을 훑어 `(fqcn, contract_id) -> outcome` 을 만든다.

    키는 **`testcase@classname`** 이다. `testsuite@name` 은 클래스 `@DisplayName` 으로
    덮이므로 키가 될 수 없다(계획 P13). contract ID 는 `testcase@name` 안의 `[SAGA-...]`
    에서 뽑는다 — 메서드명·표시명이 바뀌어도 살아남는 안정 키다."""
    found = {}
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            for fn in files:
                if not (fn.startswith("TEST-") and fn.endswith(".xml")):
                    continue
                path = os.path.join(dirpath, fn)
                try:
                    tree = ET.parse(path)
                except ET.ParseError:
                    continue
                for tc in tree.getroot().iter("testcase"):
                    classname = tc.get("classname") or ""
                    name = tc.get("name") or ""
                    for cid in re.findall(r"\[(SAGA-[A-Z0-9-]+)\]", name):
                        if list(tc.iter("failure")):
                            outcome = "failure"
                        elif list(tc.iter("error")):
                            outcome = "error"
                        elif list(tc.iter("skipped")):
                            outcome = "skipped"
                        else:
                            outcome = "passed"
                        key = (classname, cid)
                        # **같은 결과로 두 번 나와도 duplicate 다**(계획 P15·N5).
                        # 한 contract ID 가 여러 테스트에 붙어 있으면 "어느 테스트가 이 계약을
                        # 증명하는가" 가 불확정이고, 그중 하나를 지워도 게이트가 통과한다.
                        found.setdefault(key, []).append(outcome)
    return found


def check_jvm_evidence(rows, roots, commit_sha):
    problems = []
    found = collect_junit(roots)
    for r in rows:
        if r["evidence_type"] != "jvm":
            continue
        ln = r["_line"]
        if "#" not in r["evidence_key"]:
            problems.append("%d행: evidence_key 형식 오류 — --structure 를 먼저 통과시켜라" % ln)
            continue
        fqcn, cid = r["evidence_key"].rsplit("#", 1)
        outcomes = found.get((fqcn, cid))
        if not outcomes:
            problems.append("%d행 [%s]: 증적 없음 — %s 에 [%s] 를 단 테스트가 없다"
                            % (ln, r["id"], fqcn, cid))
            continue
        if len(outcomes) > 1:
            problems.append("%d행 [%s]: 같은 키에 증적이 %d건 — 어느 테스트가 이 계약을 "
                            "증명하는지 불확정이다 (결과: %s)"
                            % (ln, r["id"], len(outcomes), sorted(set(outcomes))))
            continue
        if outcomes[0] != "passed":
            problems.append("%d행 [%s]: 증적 결과 %s" % (ln, r["id"], outcomes[0]))
            continue
        want = json.loads(r["expected"])
        if want != {"outcome": "passed"}:
            problems.append("%d행 [%s]: jvm 행의 expected 는 {\"outcome\":\"passed\"} 여야 한다 — %s"
                            % (ln, r["id"], r["expected"]))
    return problems


# ---- --e2e-evidence ------------------------------------------------------------
# CI 는 시나리오를 러너 여럿으로 나눠 돌린다. 디렉터리를 쉼표로 받아 **합집합**을 대조하고,
# 같은 시나리오가 두 디렉터리에 있으면 디렉터리 안의 중복과 똑같이 실패로 센다.
def check_e2e_evidence(rows, manifest_dirs, commit_sha):
    problems = []
    manifests = {}
    if not manifest_dirs:
        return ["manifest 디렉터리가 지정되지 않았다"]
    missing = [d for d in manifest_dirs if not os.path.isdir(d)]
    if missing:
        return ["manifest 디렉터리가 없다: %s" % d for d in missing]
    for manifest_dir in manifest_dirs:
        for fn in sorted(os.listdir(manifest_dir)):
            if not (fn.startswith("manifest-") and fn.endswith(".json")):
                continue
            with open(os.path.join(manifest_dir, fn), encoding="utf-8") as f:
                doc = json.load(f)
            sid = doc.get("scenario_id")
            if sid is None:
                problems.append("%s: scenario_id 가 없다" % fn)
                continue
            if commit_sha and doc.get("commit_sha") != commit_sha:
                problems.append("%s: stale manifest — commit_sha %s (기대 %s)"
                                % (fn, doc.get("commit_sha"), commit_sha))
                continue
            if doc.get("result") != "success":
                problems.append("%s: result=%s" % (fn, doc.get("result")))
                continue
            if sid in manifests:
                problems.append("시나리오 %s 의 성공 manifest 가 2개 이상이다 — 어느 실행의 "
                                "증적인지 불확정이다" % sid)
                continue
            manifests[sid] = doc.get("evidence") or {}

    for r in rows:
        if r["evidence_type"] != "e2e":
            continue
        ln = r["_line"]
        key = r["evidence_key"]
        if "." not in key:
            problems.append("%d행: e2e evidence_key 는 '<scenario>.<key>' 형식이어야 한다 — %r"
                            % (ln, key))
            continue
        sid, sub = key.split(".", 1)
        ev = manifests.get(sid)
        if ev is None:
            problems.append("%d행 [%s]: 시나리오 %s 의 성공 manifest 가 없다" % (ln, r["id"], sid))
            continue
        if sub not in ev:
            problems.append("%d행 [%s]: manifest evidence 에 키 %r 가 없다 (있는 키: %s)"
                            % (ln, r["id"], sub, sorted(ev)))
            continue
        actual = ev[sub]
        if not isinstance(actual, dict) or "actual" not in actual:
            problems.append("%d행 [%s]: evidence[%r] 는 {\"actual\": ...} 구조여야 한다"
                            % (ln, r["id"], sub))
            continue
        # **exact equality** — subset 이 아니다(계획 P14). 관측이 늘어도 매트릭스가 모르면 실패다.
        got = canonical(actual["actual"])
        want = canonical(json.loads(r["expected"]))
        if got != want:
            problems.append("%d행 [%s]: 관측값 불일치\n    기대: %s\n    실제: %s"
                            % (ln, r["id"], want, got))
    return problems


def main(argv):
    mode = argv[1]
    matrix = argv[2]
    rows = read_matrix(matrix)

    if mode == "--structure":
        problems = check_structure(rows)
    elif mode == "--jvm-evidence":
        roots = [d for d in argv[3].split(",") if d]
        problems = check_jvm_evidence(rows, roots, os.environ.get("SAGA_COMMIT_SHA", ""))
    elif mode == "--e2e-evidence":
        dirs = [d for d in argv[3].split(",") if d]
        problems = check_e2e_evidence(rows, dirs, os.environ.get("SAGA_COMMIT_SHA", ""))
    else:
        print("알 수 없는 모드: %s" % mode, file=sys.stderr)
        return 2

    if problems:
        print("\n".join("  - %s" % p for p in problems))
        return 1
    print("OK — %s · %d행" % (mode, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
PYEOF

run() { python3 "$LINT_PY" "$@"; }

MODE="${1:---structure}"

case "$MODE" in
  --structure)
    if ! run --structure "$MATRIX"; then
      echo "::error::[saga-contract-matrix-lint] 매트릭스 구조 위반 (계획 P11·P12)" >&2
      exit 1
    fi
    ;;
  --jvm-evidence)
    ROOTS="${2:-$(ls -d ./*/build/test-results/test 2>/dev/null | paste -sd, -)}"
    if ! run --jvm-evidence "$MATRIX" "$ROOTS"; then
      echo "::error::[saga-contract-matrix-lint] JVM 증적 대조 실패 (계획 P13)" >&2
      exit 1
    fi
    ;;
  --e2e-evidence)
    DIR="${2:-.cache/e2e/${E2E_RUN_ID:-local}}"
    if ! run --e2e-evidence "$MATRIX" "$DIR"; then
      echo "::error::[saga-contract-matrix-lint] E2E 증적 대조 실패 (계획 P14)" >&2
      exit 1
    fi
    ;;
  --self-test)
    exec bash "$(dirname "${BASH_SOURCE[0]}")/saga-contract-matrix-selftest.sh"
    ;;
  *)
    echo "사용: $0 [--structure|--jvm-evidence [roots]|--e2e-evidence [dir[,dir...]]|--self-test]" >&2
    exit 2
    ;;
esac
