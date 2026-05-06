#!/usr/bin/env bash
# observability-promql-lint.sh — D5-V6 PromQL 라벨 invariant lint (ADR-0009 §Decision S6)
#
# 검증: alert uid 별 required-label matrix
#   peekcart-high-error-rate (S6.a) → application 필수, S2 값 일치
#   peekcart-slow-response   (S6.b) → application 필수, S2 값 일치
#   peekcart-target-down     (S6.c) → namespace + service 필수, S5 값 일치
#   peekcart-scrape-absent   (S6.d) → namespace + service 필수, S5 값 일치
#
# Ground truth:
#   S2 application = src/main/resources/application.yml :: management.metrics.tags.application
#   S5 namespace   = k8s/base/services/peekcart/servicemonitor.yml :: metadata.namespace
#   S5 service     = k8s/base/services/peekcart/servicemonitor.yml :: spec.selector.matchLabels.app
#
# PromQL syntax check 비대상 (트레이드오프 §7 R1).
#
# Exit:
#   0 — 위반 0건
#   1 — 위반 1건 이상

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# pyyaml preflight (CI 는 ci.yml step 으로 설치, 로컬은 venv/pip 권장)
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[D5-V6] pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

python3 - <<'PY'
import re, sys, yaml

ALERTS_PATH = "k8s/monitoring/shared/grafana-alerts.yml"
APP_YAML = "src/main/resources/application.yml"
SM_YAML  = "k8s/base/services/peekcart/servicemonitor.yml"

# alert uid 별 필수 라벨 (ADR-0009 §Context L23-26)
MATRIX = {
    "peekcart-high-error-rate": ["application"],
    "peekcart-slow-response":   ["application"],
    "peekcart-target-down":     ["namespace", "service"],
    "peekcart-scrape-absent":   ["namespace", "service"],
}

# Ground truth 로드
with open(APP_YAML) as f:
    app_doc = yaml.safe_load(f) or {}
gt_application = (((app_doc.get("management") or {}).get("metrics") or {}).get("tags") or {}).get("application")

with open(SM_YAML) as f:
    sm_doc = yaml.safe_load(f) or {}
gt_namespace = (sm_doc.get("metadata") or {}).get("namespace")
gt_service = (((sm_doc.get("spec") or {}).get("selector") or {}).get("matchLabels") or {}).get("app")

if not gt_application:
    sys.stderr.write(f"[D5-V6] ground truth missing: management.metrics.tags.application not in {APP_YAML}\n")
    sys.exit(2)
if not gt_namespace or not gt_service:
    sys.stderr.write(f"[D5-V6] ground truth missing: namespace/service from {SM_YAML}\n")
    sys.exit(2)

GROUND_TRUTH = {
    "application": gt_application,
    "namespace": gt_namespace,
    "service": gt_service,
}

# alerts ConfigMap → data["alerts.yaml"] → 내부 yaml 파싱
with open(ALERTS_PATH) as f:
    cm = yaml.safe_load(f) or {}
inner_text = ((cm.get("data") or {}).get("alerts.yaml")) or ""
if not inner_text:
    sys.stderr.write(f"[D5-V6] {ALERTS_PATH} 의 data['alerts.yaml'] 비어 있음\n")
    sys.exit(2)
alerts_doc = yaml.safe_load(inner_text) or {}

# rule 순회
LABEL_RE = re.compile(r'(\b[a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*"([^"]*)"')

violations = []
for group in alerts_doc.get("groups", []) or []:
    for rule in group.get("rules", []) or []:
        uid = rule.get("uid")
        required = MATRIX.get(uid)
        if required is None:
            # matrix 외 uid 는 검사 안 함 — 라벨 의존 surface 정의 부재
            continue

        # rule 내 data[].model.expr 를 entry (refId) 단위로 검증
        # rule-level 합산은 false negative 위험 — 한 PromQL 에 application 라벨이 있고
        # 다른 PromQL 에 없어도 통과해버림. ADR-0009 §Context S6 의존 surface 는
        # 모든 prometheus expr 단위로 적용되어야 한다.
        prom_entries = []
        for entry in rule.get("data", []) or []:
            if entry.get("datasourceUid") != "prometheus":
                continue
            model = entry.get("model") or {}
            expr = model.get("expr")
            if expr:
                prom_entries.append((entry.get("refId", "?"), expr))

        if not prom_entries:
            continue

        for ref_id, expr in prom_entries:
            present_in_expr = {}
            for k, v in LABEL_RE.findall(expr):
                present_in_expr.setdefault(k, set()).add(v)

            for label in required:
                if label not in present_in_expr:
                    violations.append(
                        f"[D5-V6] PromQL label invariant violation:\n"
                        f"  rule uid: {uid}, refId: {ref_id}\n"
                        f"  required label absent: {label}\n"
                        f"  PromQL: {expr}\n"
                        f"  → ADR-0009 §Context S6 의존 surface 위반.\n"
                    )
                    continue
                expected = GROUND_TRUTH[label]
                for val in present_in_expr[label]:
                    if val != expected:
                        violations.append(
                            f"[D5-V6] PromQL label invariant violation:\n"
                            f"  rule uid: {uid}, refId: {ref_id}\n"
                            f"  label: {label}\n"
                            f"  expected: {expected!r} (ground truth)\n"
                            f"  found:    {val!r}\n"
                            f"  PromQL: {expr}\n"
                            f"  → ADR-0009 §Decision S2/S5: 라벨 값 ground truth 일치 위반.\n"
                        )

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)
sys.exit(0)
PY
PY_RC=$?
exit $PY_RC
