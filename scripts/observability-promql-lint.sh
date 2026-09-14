#!/usr/bin/env bash
# observability-promql-lint.sh — D5-V6 PromQL 라벨 invariant + coverage + syntax lint
#   (ADR-0009 §Decision S6, ADR-0015 per-service 정정)
#
# per-service 계약(ADR-0015): alert 는 단일 application/service 값(과거 peekcart)이 아니라 대상 전체를
# 평가해야 한다. PR4(ADR-0024 D1)부터 대상은 **도메인 5 + 인프라 1(gateway)** 이고, ground truth 가
# 세 집합으로 갈라진다 — A(application 태그) / B(SM 이 매칭하는 Service name) / C(SM 이름, 별도 lint).
# A 와 B 의 원소가 다르다: gateway 의 태그는 `gateway`, scrape 되는 Service 는 `gateway-metrics`.
#
# 검증:
#   peekcart-high-error-rate (S6.a) → application 라벨 5서비스 정확일치 regex(=~) + by(application)
#   peekcart-slow-response   (S6.b) → 동상 (by 에 application 포함)
#   peekcart-target-down     (S6.c) → namespace 필터 + by(service)
#   peekcart-scrape-absent-* (S6.d) → service equality matcher, 집합 == 집합 B 와 1:1
#   dashboard `application` 변수 (PR4)  → query/options 집합 == 파일별 기대 집합(api-jvm=A, kafka-lag=소비 4)
#
# Ground truth:
#   application set = 5서비스 <svc>-service/src/main/resources/application.yml :: management.metrics.tags.application
#   service set     = k8s/base/services/*/deployment.yml :: (kind: Service) 중 **ServiceMonitor 가
#                     매칭하는** 것의 metadata.name (ADR-0015 S6.d). SM 없는 인프라 Service(gateway)는 제외.
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

# ---------- self-test (구현 ④-d-1 P5) ----------
# lint 가 조작 입력에서 실제로 실패하는지 — 검사기 자체가 vacuous-green 으로 썩는 것을 막는다.
# 조작 대상은 신규 메트릭 alert 다. 기존 4종은 이미 자체 분기를 갖고 있다.
if [[ "${1:-}" == "--self-test" ]]; then
    ST_TMP="$(mktemp -d)"
    trap 'rm -rf "$ST_TMP"' EXIT
    ST_SRC="k8s/monitoring/shared/grafana-alerts.yml"
    ST_FAIL=0

    run_case() {
        local name="$1" file="$2"
        if ALERTS_PATH_OVERRIDE="$file" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
            echo "self-test 실패: '$name' 을 검출하지 못했다" >&2
            ST_FAIL=1
        fi
    }

    # (0) 원본은 통과해야 한다 — 통과 못 하면 아래 음성 케이스가 무의미하다
    if ! ALERTS_PATH_OVERRIDE="$ST_SRC" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
        echo "self-test 실패: 원본 alert 가 통과하지 않는다" >&2
        exit 1
    fi

    # (1) alert 삭제 — 지우면 검증 분기가 실행되지 않아 라벨 검사가 통과해버린다
    python3 - "$ST_SRC" "$ST_TMP/deleted.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
inner["groups"][0]["rules"] = [r for r in inner["groups"][0]["rules"]
                               if r["uid"] != "peekcart-dlq-backlog"]
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "alert 삭제" "$ST_TMP/deleted.yml"

    # (2) application 라벨 제거 — 서비스 구분 없이 합산하면 어디가 쌓이는지 모른다
    python3 - "$ST_SRC" "$ST_TMP/nolabel.yml" <<'PYEOF'
import sys, yaml, re
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-compensation-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = re.sub(r'\{application=~[^}]*\}', '', expr)
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "application 라벨 제거" "$ST_TMP/nolabel.yml"

    # (3) 서비스 집합 축소 — 빠진 서비스의 미결은 영원히 안 잡힌다
    python3 - "$ST_SRC" "$ST_TMP/shrunk.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-dlq-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace("notification-service|", "")
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "서비스 집합 축소" "$ST_TMP/shrunk.yml"

    # (4) 메트릭 이름 변조 — alert 가 다른 것을 재고 있어도 통과하면 안 된다
    python3 - "$ST_SRC" "$ST_TMP/wrongmetric.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-dlq-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace("dlq_backlog", "outbox_backlog")
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "메트릭 이름 변조" "$ST_TMP/wrongmetric.yml"

    # (5) prometheus entry 삭제 — uid 만 남기면 라벨 검사 루프가 돌지 않아 통과하던 우회
    python3 - "$ST_SRC" "$ST_TMP/noentry.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-dlq-backlog":
        rule["data"] = [d for d in rule["data"] if d.get("datasourceUid") != "prometheus"]
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "prometheus entry 삭제" "$ST_TMP/noentry.yml"

    # (6) 부정 matcher 로 서비스 제외 — 집합은 그대로인데 실제로는 안 보는 우회
    python3 - "$ST_SRC" "$ST_TMP/negmatcher.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-dlq-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace(
                    'product-service"}', 'product-service", application!="notification-service"}')
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "부정 matcher 로 서비스 제외" "$ST_TMP/negmatcher.yml"

    # (7) `0 * metric` 우회 — 이름·라벨 집합은 그대로인데 alert 가 영원히 발화하지 않는다
    python3 - "$ST_SRC" "$ST_TMP/zeromul.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-dlq-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = "0 * " + expr
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "0 * metric 우회" "$ST_TMP/zeromul.yml"

    # (8) status 필터 삭제 — 상태 축소/확대가 조용히 통과하면 계약이 아니다
    python3 - "$ST_SRC" "$ST_TMP/nostatus.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-compensation-backlog":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace(', status=~"open|refund_failed"', "")
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "status 필터 삭제" "$ST_TMP/nostatus.yml"

    # (9) 기존 alert 에도 같은 우회가 통하는지 — 신규 2종만 지키면 약한 쪽이 뚫린다
    python3 - "$ST_SRC" "$ST_TMP/legacy-zeromul.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-high-error-rate":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = "0 * " + expr
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "기존 alert 0 * 우회" "$ST_TMP/legacy-zeromul.yml"

    # (10) 기존 alert 의 prometheus entry 삭제
    python3 - "$ST_SRC" "$ST_TMP/legacy-noentry.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-high-error-rate":
        rule["data"] = [d for d in rule["data"] if d.get("datasourceUid") != "prometheus"]
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "기존 alert entry 삭제" "$ST_TMP/legacy-noentry.yml"

    # (11) 인프라 1(gateway)을 alert regex 에서 제외 — 외부 진입점의 5xx/지연이 통째로 안 보인다
    python3 - "$ST_SRC" "$ST_TMP/no-gateway.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] in ("peekcart-high-error-rate", "peekcart-slow-response"):
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace("gateway|", "")
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "gateway 를 alert 집합에서 제외" "$ST_TMP/no-gateway.yml"

    # (12) gateway scrape-absent rule 삭제 — SM 이 끊겨도 아무도 모른다(S9 전량 미수집)
    python3 - "$ST_SRC" "$ST_TMP/no-gw-absent.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
inner["groups"][0]["rules"] = [r for r in inner["groups"][0]["rules"]
                               if r["uid"] != "peekcart-scrape-absent-gateway-metrics"]
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "gateway scrape-absent rule 삭제" "$ST_TMP/no-gw-absent.yml"

    # (13) scrape-absent 의 service 값을 public Service 이름으로 — up{service=} 는 매칭 Service
    #      이름이라 `gateway` 로 쓰면 영원히 absent 인 series 를 기다린다(항상 발화 또는 무의미).
    python3 - "$ST_SRC" "$ST_TMP/wrong-absent-name.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
for rule in inner["groups"][0]["rules"]:
    if rule["uid"] == "peekcart-scrape-absent-gateway-metrics":
        for d in rule["data"]:
            expr = (d.get("model") or {}).get("expr")
            if expr:
                d["model"]["expr"] = expr.replace('service="gateway-metrics"', 'service="gateway"')
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "scrape-absent 대상 이름 변조" "$ST_TMP/wrong-absent-name.yml"

    # (14) reuse alert 삭제 — S9 에서 유일하게 alert 로 올린 신호다
    python3 - "$ST_SRC" "$ST_TMP/no-reuse.yml" <<'PYEOF'
import sys, yaml
src, dst = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open(src))
inner = yaml.safe_load(doc["data"]["alerts.yaml"])
inner["groups"][0]["rules"] = [r for r in inner["groups"][0]["rules"]
                               if r["uid"] != "peekcart-token-reuse-detected"]
doc["data"]["alerts.yaml"] = yaml.dump(inner, allow_unicode=True, sort_keys=False)
yaml.dump(doc, open(dst, "w"), allow_unicode=True, sort_keys=False)
PYEOF
    run_case "reuse alert 삭제" "$ST_TMP/no-reuse.yml"

    # (15~17) dashboard 변수 드리프트 — alert 만 고치고 패널을 두면 거기서만 조용히 빠진다.
    #         조작 대상이 dashboard 이므로 alert 는 원본을 쓰고 디렉터리만 갈아끼운다.
    run_dash_case() {
        local name="$1" dir="$2"
        if ALERTS_PATH_OVERRIDE="$ST_SRC" DASHBOARD_DIR_OVERRIDE="$dir" \
                bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
            echo "self-test 실패: '$name' 을 검출하지 못했다" >&2
            ST_FAIL=1
        fi
    }

    python3 - "$ST_TMP" <<'PYEOF'
import sys, os, json, shutil
tmp = sys.argv[1]
src = "k8s/monitoring/shared"

def prepare(name):
    d = os.path.join(tmp, name)
    os.makedirs(d, exist_ok=True)
    for f in ("api-jvm-dashboard.json", "kafka-lag-dashboard.json"):
        shutil.copy(os.path.join(src, f), os.path.join(d, f))
    return d

def load(d, f):
    with open(os.path.join(d, f)) as fh:
        return json.load(fh)

def save(d, f, doc):
    with open(os.path.join(d, f), "w") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)

# (15) query 에서 gateway 제거 (options 는 그대로) — 한쪽만 고친 드리프트
d = prepare("dash-query-drift")
doc = load(d, "api-jvm-dashboard.json")
var = doc["templating"]["list"][0]
var["query"] = ",".join(s for s in var["query"].split(",") if s != "gateway")
save(d, "api-jvm-dashboard.json", doc)

# (16) options 에서 gateway 제거 (query 는 그대로)
d = prepare("dash-option-drift")
doc = load(d, "api-jvm-dashboard.json")
var = doc["templating"]["list"][0]
var["options"] = [o for o in var["options"] if o.get("value") != "gateway"]
save(d, "api-jvm-dashboard.json", doc)

# (17) Kafka 소비자가 아닌 서비스를 lag 대시보드에 추가 — 고를 수는 있는데 series 가 없다
d = prepare("dash-kafka-extra")
doc = load(d, "kafka-lag-dashboard.json")
var = doc["templating"]["list"][0]
var["query"] = var["query"] + ",user-service"
var["options"].append({"text": "user-service", "value": "user-service", "selected": False})
save(d, "kafka-lag-dashboard.json", doc)
PYEOF
    run_dash_case "dashboard query 드리프트" "$ST_TMP/dash-query-drift"
    run_dash_case "dashboard options 드리프트" "$ST_TMP/dash-option-drift"
    run_dash_case "kafka-lag 에 비소비 서비스 추가" "$ST_TMP/dash-kafka-extra"

    if [[ "$ST_FAIL" -ne 0 ]]; then
        exit 1
    fi
    echo "observability-promql-lint self-test 17종 통과"
    exit 0
fi

RULES_OUT=".cache/promql-syntax-rules.yml"
mkdir -p .cache

# ---------- 라벨 invariant + coverage + promtool rules 파일 생성 (python) ----------
PY_RC=0
RULES_OUT="$RULES_OUT" python3 - <<'PY' || PY_RC=$?
import re, sys, glob, os
import yaml

# self-test 는 조작한 사본을 가리킨다. ground truth(application.yml · k8s Service)는
# 실제 저장소 것을 그대로 쓴다 — 조작 대상은 alert 뿐이다.
ALERTS_PATH = os.environ.get("ALERTS_PATH_OVERRIDE", "k8s/monitoring/shared/grafana-alerts.yml")
RULES_OUT = os.environ["RULES_OUT"]

# ---- canonical 정본 (ADR-0010/0015 · ADR-0024 D1) — glob 결과를 정본으로 삼지 않는다 ----
#
# 세 집합의 원소가 서로 다르다. 하나의 상수로 셋을 강제하던 것이 PR4 이전 상태이고,
# gateway 가 들어오면서 갈라졌다(ADR-0024 D1):
#   A. application 메트릭 태그   = spring.application.name       → "gateway"
#   B. scrape 되는 Service 이름  = up{service=} 라벨              → "gateway-metrics"(관리 포트 전용 Service)
#   C. ServiceMonitor 이름       = servicemonitor-selector-lint 소관(본 스크립트 비대상)
# 억지로 같게 만들려면 관리 포트를 public Service 에 게시하거나 태그를 Service 이름으로
# 흉내내야 한다 — 둘 다 더 나쁘다(ADR-0024 Alternatives B).
DOMAIN_SERVICES = {
    "notification-service", "order-service", "payment-service",
    "product-service", "user-service",
}
# 집합 A — application 태그
EXPECTED_APP_TAGS = DOMAIN_SERVICES | {"gateway"}
# 집합 B — SM 이 매칭하는 Service metadata.name
EXPECTED_SCRAPE_SERVICES = DOMAIN_SERVICES | {"gateway-metrics"}
# 태그 값이 디렉터리명과 다른 모듈만 명시한다. glob 확장으로 처리하면 디렉터리가 사라져도
# 남은 것만으로 통과하는 false-green 이 생긴다(기존 주석과 같은 이유).
EXTRA_APP_YMLS = ["gateway/src/main/resources/application.yml"]

# ---- 메트릭 alert 계약 (구현 ④-d-1 P5) ----
# saga/DLQ alert 는 http_server_requests 처럼 5서비스 전부에 있는 메트릭이 아니다.
# 메트릭을 실제로 등록한 서비스 집합으로 평가해야 한다 —
#   5서비스 regex 로 걸면 없는 series 를 기다리는 alert 가 되고,
#   단일 equality 로 좁히면 소유 서비스가 늘어도 alert 가 안 따라온다.
# 그래서 uid 별로 "메트릭 이름 + 소유 서비스 집합" 을 정본으로 고정하고 정확 일치를 강제한다.
# 메트릭 이름 추출 시 걸러낼 PromQL 예약어/함수 (구현 ④-d-1 diff 리뷰 #2)
PROMQL_KEYWORDS = {
    "sum", "by", "without", "rate", "irate", "increase", "count", "avg", "min", "max",
    "absent", "vector", "on", "ignoring", "group_left", "group_right", "histogram_quantile",
    "or", "and", "unless", "offset", "bool", "topk", "bottomk", "quantile", "stddev",
}

# expr 를 정확 문자열로 고정한다 (구현 ④-d-1 diff 리뷰 2R #1).
# 이름 집합·라벨 집합만 보면 `0 * sum by(application)(metric{정상라벨})` 이 전부 통과하는데
# Grafana 의 `$A > 0` 은 영원히 참이 되지 않는다 — 검사는 통과하고 alert 는 죽는다.
# 동일 메트릭을 두 selector 에 쓰고 한쪽에만 정상 matcher 를 붙이는 우회도 같은 부류다.
# PromQL AST 파서를 들이는 대신, 우리가 소유한 alert 2종의 **식 형태 자체**를 못박는다.
METRIC_ALERT_CONTRACTS = {
    "peekcart-compensation-backlog": {
        # order_compensations 원장은 order-service 단독 소유 (ADR-0012 D1).
        # status 를 명시 고정 — 상태가 추가돼도 조용히 alert 대상에 들어오지 않는다.
        "metric": "saga_compensation_backlog",
        "apps": {"order-service"},
        "expr": 'sum by (application)(saga_compensation_backlog'
                '{application=~"order-service", status=~"open|refund_failed"})',
    },
    "peekcart-token-reuse-detected": {
        # refresh token 발급 owner 는 user-service 단독(ADR-0010) — 5/6 서비스 regex 로 걸면
        # 없는 series 를 기다리는 alert 가 된다.
        "metric": "auth_token_reuse_detected_total",
        "apps": {"user-service"},
        "expr": 'sum by (application)(increase(auth_token_reuse_detected_total'
                '{application=~"user-service"}[5m]))',
    },
    "peekcart-dlq-backlog": {
        # dead_letter_records 는 Kafka 소비 4서비스가 소유. user-service 는 소비자가 없다.
        "metric": "dlq_backlog",
        "apps": {"notification-service", "order-service", "payment-service", "product-service"},
        "expr": 'sum by (application)(dlq_backlog'
                '{application=~"notification-service|order-service|payment-service|product-service"})',
    },
}

# ---- alert expr 정본 (구현 ④-d-1 diff 리뷰 3R #1) ----
# 신규 2종만 식을 고정하면 기존 4종은 `0 * <식>` 으로 무력화해도 라벨 검사를 그대로 통과한다
# (application matcher 도 by(application) 도 그대로다) — 계약 강도가 비대칭이면 약한 쪽이 뚫린다.
# 그래서 **모든 필수 alert 의 prometheus 식을 정확 문자열로 고정**한다.
# 대가: alert 를 고칠 때 이 정본도 함께 고쳐야 한다. 그게 의도다 — 식 변경은 계약 변경이다.
def _scrape_absent_expr(service):
    return ('absent(up{namespace="peekcart", service="%s"}) or on() vector(0)' % service)

APP_REGEX = "|".join(sorted(EXPECTED_APP_TAGS))

ALERT_EXPR_CONTRACTS = {
    "peekcart-high-error-rate": [
        'sum by (application)(rate(http_server_requests_seconds_count'
        '{application=~"%s", status=~"5.."}[5m]))' % APP_REGEX,
        'sum by (application)(rate(http_server_requests_seconds_count'
        '{application=~"%s"}[5m]))' % APP_REGEX,
    ],
    "peekcart-slow-response": [
        'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket'
        '{application=~"%s", uri!~"/actuator.*"}[5m])) by (le, application))' % APP_REGEX,
    ],
    "peekcart-target-down": [
        'count by (service)(up{namespace="peekcart"} == 0) or on() vector(0)',
    ],
}
for _svc in sorted(EXPECTED_SCRAPE_SERVICES):
    ALERT_EXPR_CONTRACTS["peekcart-scrape-absent-%s" % _svc] = [_scrape_absent_expr(_svc)]
for _uid, _c in METRIC_ALERT_CONTRACTS.items():
    ALERT_EXPR_CONTRACTS[_uid] = [_c["expr"]]

# ---- ground truth ----
app_set = set()
for p in sorted(glob.glob("*-service/src/main/resources/application.yml")) + EXTRA_APP_YMLS:
    with open(p) as f:
        doc = yaml.safe_load(f) or {}
    val = (((doc.get("management") or {}).get("metrics") or {}).get("tags") or {}).get("application")
    if val:
        app_set.add(val)

# scrape 대상 = **ServiceMonitor 가 매칭하는** Service 의 metadata.name (ADR-0015 §Decision S6.d).
# 전체 Service glob 이 아니다 — SM 이 없는 인프라 컴포넌트(gateway, 구현 ③ PR3b)는 scrape 대상이
# 아니므로 scrape-absent rule 도 가질 수 없다. glob 근사를 쓰면 그런 Service 가 추가되는 순간
# "alert 에 없다" 며 오탐한다(계약이 아니라 구현 근사의 문제).
sm_selectors = []
for p in sorted(glob.glob("k8s/base/services/*/servicemonitor.yml")):
    with open(p) as f:
        for d in yaml.safe_load_all(f):
            if d and d.get("kind") == "ServiceMonitor":
                sel = ((d.get("spec") or {}).get("selector") or {}).get("matchLabels") or {}
                if sel:
                    sm_selectors.append(sel)

svc_set = set()
for p in sorted(glob.glob("k8s/base/services/*/deployment.yml")):
    with open(p) as f:
        for d in yaml.safe_load_all(f):
            if d and d.get("kind") == "Service":
                name = (d.get("metadata") or {}).get("name")
                labels = (d.get("metadata") or {}).get("labels") or {}
                if not name:
                    continue
                if any(all(labels.get(k) == v for k, v in sel.items()) for sel in sm_selectors):
                    svc_set.add(name)

# 발견 집합이 5서비스 정본과 정확히 일치하는지 먼저 검증 (누락 서비스 false-green 차단)
if app_set != EXPECTED_APP_TAGS:
    sys.stderr.write(
        f"[D5-V6] application 태그 집합(A)이 정본과 불일치:\n"
        f"  expected: {sorted(EXPECTED_APP_TAGS)}\n  found: {sorted(app_set)}\n"
        f"  missing: {sorted(EXPECTED_APP_TAGS - app_set)} / extra: {sorted(app_set - EXPECTED_APP_TAGS)}\n"
        f"  → ADR-0024 D1 집합 A: 도메인 5 + 인프라 1(gateway).\n")
    sys.exit(2)
if svc_set != EXPECTED_SCRAPE_SERVICES:
    sys.stderr.write(
        f"[D5-V6] SM 이 매칭하는 Service metadata.name 집합(B)이 정본과 불일치:\n"
        f"  expected: {sorted(EXPECTED_SCRAPE_SERVICES)}\n  found: {sorted(svc_set)}\n"
        f"  missing: {sorted(EXPECTED_SCRAPE_SERVICES - svc_set)} / extra: {sorted(svc_set - EXPECTED_SCRAPE_SERVICES)}\n"
        f"  → ADR-0024 D1 집합 B: gateway 는 관리 포트 전용 `gateway-metrics` 로 scrape 된다\n"
        f"    (public gateway Service 는 SM 이 매칭하지 않는다).\n")
    sys.exit(2)

# ---- dashboard `application` 변수 ground truth (구현 ③ PR4 · ADR-0024 D1) ----
# alert 만 검사하고 dashboard 를 두면, 서비스가 늘거나 줄 때 패널에서만 조용히 빠진다.
# 파일마다 기대 집합이 다르다 — 모든 앱이 http/JVM 메트릭을 내지만 Kafka lag 은 소비자만 낸다.
import json
DASHBOARD_DIR = os.environ.get("DASHBOARD_DIR_OVERRIDE", "k8s/monitoring/shared")
DASHBOARD_APP_SETS = {
    "api-jvm-dashboard.json": EXPECTED_APP_TAGS,
    # Kafka 소비자를 가진 서비스만. user-service 는 @KafkaListener 가 0 건이고 gateway 는
    # Kafka 를 쓰지 않는다 — 넣으면 고를 수는 있는데 series 가 없는 값이 된다.
    "kafka-lag-dashboard.json": DOMAIN_SERVICES - {"user-service"},
}
dashboard_violations = []
for fname, expected in DASHBOARD_APP_SETS.items():
    path = os.path.join(DASHBOARD_DIR, fname)
    if not os.path.exists(path):
        dashboard_violations.append(
            f"[D5-V6] dashboard 파일 부재: {path}\n"
            f"  → 파일이 사라지면 변수 검사가 통째로 실행되지 않는다(false-green).\n")
        continue
    with open(path) as f:
        dash = json.load(f)
    var = None
    for v in ((dash.get("templating") or {}).get("list") or []):
        if v.get("name") == "application":
            var = v
            break
    if var is None:
        dashboard_violations.append(
            f"[D5-V6] dashboard `application` 변수 부재: {fname}\n"
            f"  → 변수를 지우면 패널이 전 서비스를 합산하거나 비어버린다.\n")
        continue
    query_set = {s for s in (var.get("query") or "").split(",") if s}
    option_set = {o.get("value") for o in (var.get("options") or []) if o.get("value") != "$__all"}
    for label, found in (("query", query_set), ("options", option_set)):
        if found != expected:
            dashboard_violations.append(
                f"[D5-V6] dashboard `application` {label} 집합 불일치: {fname}\n"
                f"  expected: {sorted(expected)}\n  found: {sorted(found)}\n"
                f"  missing: {sorted(expected - found)} / extra: {sorted(found - expected)}\n"
                f"  → query 와 options 는 같은 집합이어야 한다 — 한쪽만 고치면 드롭다운과\n"
                f"    실제 쿼리 값이 어긋난다(ADR-0024 D1).\n")

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

violations = list(dashboard_violations)
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

        # 모든 필수 alert 의 식 정확 일치 (3R #1) — `0 * <식>` 류 무력화를 구조적으로 차단한다.
        if uid in ALERT_EXPR_CONTRACTS:
            expected_exprs = [" ".join(e.split()) for e in ALERT_EXPR_CONTRACTS[uid]]
            found_exprs = [" ".join(e.split()) for _, e in entries]
            if found_exprs != expected_exprs:
                violations.append(
                    f"[D5-V6] alert 식이 계약과 다르다: uid={uid}\n"
                    f"  expected({len(expected_exprs)}개):\n"
                    + "".join(f"    {e}\n" for e in expected_exprs)
                    + f"  found({len(found_exprs)}개):\n"
                    + "".join(f"    {e}\n" for e in found_exprs)
                    + "  → 라벨 집합만 맞추고 `0 * ...` 같은 항을 섞으면 검사는 통과하고\n"
                      "    alert 는 영원히 발화하지 않는다. 식 변경은 계약 변경이므로 정본도 함께 고친다.\n")

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
                            f"  → ADR-0015 S6 · ADR-0024 D3: 집합 A 정확일치 regex(=~) 필요, 단일 서비스 필터 불가.\n")
                    elif op == "=~":
                        vals = set(v for v in val.split("|") if v)
                        if vals != app_set:
                            violations.append(
                                f"[D5-V6] application regex 집합 불일치: uid={uid} refId={ref_id}\n"
                                f"  expected(집합 A): {sorted(app_set)}\n"
                                f"  found: {sorted(vals)}\n"
                                f"  → ADR-0015 S6 · ADR-0024 D3: regex 값 == 집합 A ground truth.\n")
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

        elif uid in METRIC_ALERT_CONTRACTS:
            contract = METRIC_ALERT_CONTRACTS[uid]
            expected_apps = contract["apps"]
            # prometheus entry 를 전부 지우면 아래 루프가 돌지 않아 라벨 검사가 통과해버린다
            # (uid 만 남기면 required 검사도 통과) → entry 수를 먼저 못박는다.
            if len(entries) != 1:
                violations.append(
                    f"[D5-V6] prometheus entry 수 불일치: uid={uid}\n"
                    f"  expected: 1, found: {len(entries)}\n"
                    f"  → entry 를 지우면 라벨 검사 자체가 실행되지 않는다(우회 경로).\n")
            for ref_id, expr in entries:
                m = matchers(expr)
                bys = by_labels(expr)
                # 식 형태 정확 일치 — 우회를 구조적으로 차단한다. 아래 라벨/메트릭 검사는
                # 위반 시 "무엇이 다른가" 를 짚어주기 위해 남긴다(진단 품질).
                if " ".join(expr.split()) != " ".join(contract["expr"].split()):
                    violations.append(
                        f"[D5-V6] alert 식이 계약과 다르다: uid={uid} refId={ref_id}\n"
                        f"  expected: {contract['expr']}\n"
                        f"  found:    {expr}\n"
                        f"  → 이름·라벨 집합만 맞추고 `0 * ...` 같은 항을 섞으면 검사는 통과하고\n"
                        f"    alert 는 영원히 발화하지 않는다. 식 자체를 고정한다.\n")
                # substring 대신 "식에 등장하는 메트릭 이름 집합" 을 뽑아 정확히 1종인지 본다.
                # `잘못된식 + 0 * 계약메트릭{정상라벨}` 처럼 계약 메트릭을 곁들여 검사를 통과시키고
                # 실제로는 다른 것을 재는 우회를 막는다.
                # by(...)/without(...) 안의 라벨 목록과 {..} 안의 matcher 는 메트릭이 아니다 —
                # 먼저 지우고 남은 식별자만 메트릭 후보로 본다.
                stripped = re.sub(r"\b(?:by|without)\s*\([^)]*\)", " ", expr)
                stripped = re.sub(r"\{[^}]*\}", " ", stripped)
                names = set(re.findall(r"(?<![\w:])([a-zA-Z_][a-zA-Z0-9_]*)", stripped))
                names -= PROMQL_KEYWORDS
                if names != {contract["metric"]}:
                    violations.append(
                        f"[D5-V6] 메트릭 이름 불일치: uid={uid} refId={ref_id}\n"
                        f"  expected(정확히 1종): {contract['metric']}\n"
                        f"  found: {sorted(names)}\n"
                        f"  PromQL: {expr}\n"
                        f"  → 계약 메트릭 외의 항이 섞이면 alert 가 다른 것을 재고 있을 수 있다.\n")
                app_ms = m.get("application", [])
                if not app_ms:
                    violations.append(
                        f"[D5-V6] application matcher 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → 서비스 구분 없이 합산하면 어느 서비스가 쌓이는지 알 수 없다.\n")
                    continue
                found_apps = set()
                for op, val in app_ms:
                    if op in ("!=", "!~"):
                        violations.append(
                            f"[D5-V6] application 부정 matcher 금지: uid={uid} refId={ref_id}\n"
                            f"  found: application{op}\"{val}\"\n"
                            f"  → 부정 matcher 로 특정 서비스를 빼면 그 서비스의 미결이 영원히 안 잡힌다.\n")
                    elif op == "=":
                        found_apps.add(val)
                    elif op == "=~":
                        found_apps |= {v for v in val.split("|") if v}
                if found_apps != expected_apps:
                    violations.append(
                        f"[D5-V6] application 집합 불일치: uid={uid} refId={ref_id}\n"
                        f"  expected(메트릭 소유 서비스): {sorted(expected_apps)}\n"
                        f"  found: {sorted(found_apps)}\n"
                        f"  → 소유 서비스가 아닌 값을 넣으면 없는 series 를 기다리고,\n"
                        f"    빠뜨리면 그 서비스의 미결이 영원히 안 잡힌다.\n")
                if "application" not in bys:
                    violations.append(
                        f"[D5-V6] by (application) grouping 부재: uid={uid} refId={ref_id}\n"
                        f"  PromQL: {expr}\n"
                        f"  → 서비스별 평가 필요.\n")

# 필수 alert uid 존재 검증 — rule 삭제 시 분기 미실행으로 통과하는 false-green 차단 (Codex GP-2 #2)
REQUIRED_UIDS = (
    {"peekcart-high-error-rate", "peekcart-slow-response", "peekcart-target-down"}
    | {f"peekcart-scrape-absent-{s}" for s in EXPECTED_SCRAPE_SERVICES}
    # 구현 ④-d-1 P5 — alert 를 지우면 위 분기가 실행되지 않아 라벨 검사가 통과해버린다.
    | set(METRIC_ALERT_CONTRACTS)
)
missing_uids = REQUIRED_UIDS - seen_uids
if missing_uids:
    violations.append(
        f"[D5-V6] 필수 alert rule 부재:\n"
        f"  missing uid: {sorted(missing_uids)}\n"
        f"  → ADR-0015 S6 · ADR-0024 D3: high-error-rate/slow-response/target-down 각 1\n"
        f"    + scrape-absent(집합 B) 필수.\n"
        f"    구현 ④-d-1 P5: compensation-backlog / dlq-backlog 각 1 추가.\n")

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
