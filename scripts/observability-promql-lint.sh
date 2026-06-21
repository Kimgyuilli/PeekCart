#!/usr/bin/env bash
# observability-promql-lint.sh — D5-V6 PromQL 라벨 invariant + coverage + syntax lint
#   (ADR-0009 §Decision S6, ADR-0015 per-service 정정)
#
# per-service 계약(ADR-0015): alert 는 단일 application/service 값(과거 peekcart)이 아니라 5서비스를
# 전부 평가해야 한다. ground truth 는 5서비스 application.yml(태그) + k8s Service metadata.name(scrape).
#
# 검증:
#   peekcart-high-error-rate (S6.a) → application 라벨 5서비스 정확일치 regex(=~) + by(application)
#   peekcart-slow-response   (S6.b) → 동상 (by 에 application 포함)
#   peekcart-target-down     (S6.c) → namespace 필터 + by(service)
#   peekcart-scrape-absent-* (S6.d) → service equality matcher 5개, 집합 == Service metadata.name 집합 1:1
#
# Ground truth:
#   application set = 5서비스 <svc>-service/src/main/resources/application.yml :: management.metrics.tags.application
#   service set     = k8s/base/services/*/deployment.yml :: (kind: Service) metadata.name
#                     (up{service=} 의 service 라벨은 매칭 Service 이름 — selector app 값 아님, ADR-0015 S6.d)
#
# PromQL syntax: promtool check rules (정본). 미설치 시 검증 불가 → exit 2 (false-green 금지).
#   괄호 balance 등 보조 검사로 syntax 통과를 대체하지 않는다.
#
# Exit:
#   0 — 위반 0건
#   1 — 위반 1건 이상
#   2 — preflight 실패 (pyyaml / promtool 미설치)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# pyyaml preflight
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[D5-V6] pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

# promtool preflight — PromQL syntax 검증 정본 (ADR-0015 / Codex GP-2 #3).
# 미설치 시 syntax 검증 불가 → exit 2. balance 검사로 대체 금지.
if ! command -v promtool >/dev/null 2>&1; then
    echo "[D5-V6] promtool 미설치 — PromQL syntax 검증 불가." >&2
    echo "        CI 는 promtool 설치 step 필수, 로컬은 \`brew install prometheus\` (또는 go install)." >&2
    exit 2
fi

RULES_OUT=".cache/promql-syntax-rules.yml"
mkdir -p .cache

# ---------- 라벨 invariant + coverage + promtool rules 파일 생성 (python) ----------
PY_RC=0
RULES_OUT="$RULES_OUT" python3 - <<'PY' || PY_RC=$?
import re, sys, glob, os
import yaml

ALERTS_PATH = "k8s/monitoring/shared/grafana-alerts.yml"
RULES_OUT = os.environ["RULES_OUT"]

# ---- 5서비스 정본 (ADR-0010/0015) — glob 결과를 정본으로 삼지 않는다 ----
EXPECTED_SERVICES = {
    "notification-service", "order-service", "payment-service",
    "product-service", "user-service",
}

# ---- ground truth ----
app_set = set()
for p in sorted(glob.glob("*-service/src/main/resources/application.yml")):
    with open(p) as f:
        doc = yaml.safe_load(f) or {}
    val = (((doc.get("management") or {}).get("metrics") or {}).get("tags") or {}).get("application")
    if val:
        app_set.add(val)

svc_set = set()
for p in sorted(glob.glob("k8s/base/services/*/deployment.yml")):
    with open(p) as f:
        for d in yaml.safe_load_all(f):
            if d and d.get("kind") == "Service":
                name = (d.get("metadata") or {}).get("name")
                if name:
                    svc_set.add(name)

# 발견 집합이 5서비스 정본과 정확히 일치하는지 먼저 검증 (누락 서비스 false-green 차단)
if app_set != EXPECTED_SERVICES:
    sys.stderr.write(
        f"[D5-V6] application 태그 집합이 5서비스 정본과 불일치:\n"
        f"  expected: {sorted(EXPECTED_SERVICES)}\n  found: {sorted(app_set)}\n"
        f"  missing: {sorted(EXPECTED_SERVICES - app_set)} / extra: {sorted(app_set - EXPECTED_SERVICES)}\n")
    sys.exit(2)
if svc_set != EXPECTED_SERVICES:
    sys.stderr.write(
        f"[D5-V6] k8s Service metadata.name 집합이 5서비스 정본과 불일치:\n"
        f"  expected: {sorted(EXPECTED_SERVICES)}\n  found: {sorted(svc_set)}\n"
        f"  missing: {sorted(EXPECTED_SERVICES - svc_set)} / extra: {sorted(svc_set - EXPECTED_SERVICES)}\n")
    sys.exit(2)

# ---- alerts 로드 (ConfigMap → data['alerts.yaml'] → inner yaml) ----
with open(ALERTS_PATH) as f:
    cm = yaml.safe_load(f) or {}
inner_text = ((cm.get("data") or {}).get("alerts.yaml")) or ""
if not inner_text:
    sys.stderr.write(f"[D5-V6] {ALERTS_PATH} 의 data['alerts.yaml'] 비어 있음\n")
    sys.exit(2)
alerts_doc = yaml.safe_load(inner_text) or {}

MATCHER_RE = re.compile(r'(\b[a-zA-Z_][a-zA-Z0-9_]*)\s*(=~|!~|!=|=)\s*"([^"]*)"')
BY_RE = re.compile(r'by\s*\(\s*([^)]*)\)')

def matchers(expr):
    """label -> list of (op, value)"""
    out = {}
    for k, op, v in MATCHER_RE.findall(expr):
        out.setdefault(k, []).append((op, v))
    return out

def by_labels(expr):
    out = set()
    for grp in BY_RE.findall(expr):
        for lbl in grp.split(","):
            lbl = lbl.strip()
            if lbl:
                out.add(lbl)
    return out

violations = []
prom_exprs = []          # (uid, refId, expr) for promtool syntax
scrape_absent_services = set()
seen_uids = set()        # 필수 alert 존재 검증용 (Codex GP-2 #2)

def prom_entries(rule):
    out = []
    for entry in rule.get("data", []) or []:
        if entry.get("datasourceUid") != "prometheus":
            continue
        expr = (entry.get("model") or {}).get("expr")
        if expr:
            out.append((entry.get("refId", "?"), expr))
    return out

for group in alerts_doc.get("groups", []) or []:
    for rule in group.get("rules", []) or []:
        uid = rule.get("uid", "")
        seen_uids.add(uid)
        entries = prom_entries(rule)
        for ref_id, expr in entries:
            prom_exprs.append((uid, ref_id, expr))

        # rule 단위 검증 — application coverage (high-error-rate / slow-response)
        if uid in ("peekcart-high-error-rate", "peekcart-slow-response"):
            for ref_id, expr in entries:
                m = matchers(expr)
                bys = by_labels(expr)
                app_ms = m.get("application", [])
                if not app_ms:
                    violations.append(
                        f"[D5-V6] application matcher 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6.a/b: application 라벨 필수.\n")
                    continue
                # 단일 equality(=) 금지 — 5서비스 중 하나만 감시 = false-green
                for op, val in app_ms:
                    if op == "=":
                        violations.append(
                            f"[D5-V6] application 단일 equality 금지: uid={uid} refId={ref_id}\n"
                            f"  found: application=\"{val}\"\n"
                            f"  → ADR-0015 S6: 5서비스 정확일치 regex(=~) 필요, 단일 서비스 필터 불가.\n")
                    elif op == "=~":
                        vals = set(v for v in val.split("|") if v)
                        if vals != app_set:
                            violations.append(
                                f"[D5-V6] application regex 집합 불일치: uid={uid} refId={ref_id}\n"
                                f"  expected(5서비스): {sorted(app_set)}\n"
                                f"  found: {sorted(vals)}\n"
                                f"  → ADR-0015 S6: regex 값 == 5서비스 ground truth.\n")
                # by(application) 강제 (무필터+by 단독 아닌, regex+by 동반)
                if "application" not in bys:
                    violations.append(
                        f"[D5-V6] by (application) grouping 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6: 서비스별 평가 위해 by (application) 필요.\n")

        elif uid == "peekcart-target-down":
            for ref_id, expr in entries:
                m = matchers(expr)
                bys = by_labels(expr)
                ns = m.get("namespace", [])
                if not any(op == "=" and v == "peekcart" for op, v in ns):
                    violations.append(
                        f"[D5-V6] namespace=\"peekcart\" 필터 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6.c.\n")
                if "service" not in bys:
                    violations.append(
                        f"[D5-V6] by (service) grouping 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6.c: 서비스별 평가.\n")

        elif uid.startswith("peekcart-scrape-absent"):
            for ref_id, expr in entries:
                m = matchers(expr)
                # namespace="peekcart" equality 필수 — 누락 시 타 NS 동일 service 라벨이
                # PeekCart 부재를 가려 alert 미발화하는 false-green (Codex GP-2 #3).
                ns_ms = m.get("namespace", [])
                if not any(op == "=" and v == "peekcart" for op, v in ns_ms):
                    violations.append(
                        f"[D5-V6] scrape-absent namespace=\"peekcart\" 필터 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6.d: absent(up{{namespace=\"peekcart\", service=\"<name>\"}}) 형태 필수.\n")
                svc_ms = [v for op, v in m.get("service", []) if op == "="]
                if not svc_ms:
                    violations.append(
                        f"[D5-V6] scrape-absent service equality 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0015 S6.d: service=\"<name>\" equality 필요.\n")
                for v in svc_ms:
                    scrape_absent_services.add(v)

# 필수 alert uid 존재 검증 — rule 삭제 시 분기 미실행으로 통과하는 false-green 차단 (Codex GP-2 #2)
REQUIRED_UIDS = (
    {"peekcart-high-error-rate", "peekcart-slow-response", "peekcart-target-down"}
    | {f"peekcart-scrape-absent-{s}" for s in EXPECTED_SERVICES}
)
missing_uids = REQUIRED_UIDS - seen_uids
if missing_uids:
    violations.append(
        f"[D5-V6] 필수 alert rule 부재:\n"
        f"  missing uid: {sorted(missing_uids)}\n"
        f"  → ADR-0015 S6: high-error-rate/slow-response/target-down 각 1 + scrape-absent 5서비스 필수.\n")

# scrape-absent 집합 == Service metadata.name 집합 1:1
if scrape_absent_services != svc_set:
    violations.append(
        f"[D5-V6] scrape-absent service 집합 불일치:\n"
        f"  expected(Service metadata.name): {sorted(svc_set)}\n"
        f"  found(scrape-absent rules):      {sorted(scrape_absent_services)}\n"
        f"  missing: {sorted(svc_set - scrape_absent_services)} / extra: {sorted(scrape_absent_services - svc_set)}\n"
        f"  → ADR-0015 S6.d: scrape-absent rule 집합 == 매칭 Service 집합 1:1.\n")

# ---- promtool 용 임시 rules 파일 생성 (prometheus datasource expr 만, record rule 로) ----
rule_items = []
for uid, ref_id, expr in prom_exprs:
    safe = re.sub(r'[^a-zA-Z0-9_]', '_', f"{uid}_{ref_id}")
    rule_items.append({"record": f"syntaxcheck:{safe}", "expr": expr})
syntax_doc = {"groups": [{"name": "promql-syntax-check", "rules": rule_items}]}
with open(RULES_OUT, "w") as f:
    yaml.safe_dump(syntax_doc, f, allow_unicode=True, sort_keys=False)

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)
sys.exit(0)
PY

if [[ "$PY_RC" -eq 2 ]]; then
    exit 2
fi

# ---------- PromQL syntax 검증 (promtool 정본) ----------
SYNTAX_RC=0
promtool check rules "$RULES_OUT" >/dev/null 2>.cache/promtool.err || SYNTAX_RC=$?
if [[ "$SYNTAX_RC" -ne 0 ]]; then
    echo "[D5-V6] PromQL syntax 검증 실패 (promtool check rules):" >&2
    cat .cache/promtool.err >&2
    PY_RC=1
fi

if [[ "$PY_RC" -ne 0 ]]; then
    exit 1
fi
exit 0
