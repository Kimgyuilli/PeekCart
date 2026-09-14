#!/usr/bin/env bash
# servicemonitor-selector-lint.sh — D5-V5 정적 검증 (ADR-0009 §Decision S5)
#
# 목적: 양 overlay (minikube, gke) 의 kubectl kustomize 산출물에서
#       ServiceMonitor selector 가 같은 namespace 의 Service label/port 와
#       정확히 매칭되는지 정적 검증.
#
# 검증:
#   - ServiceMonitor.spec.selector.matchLabels ⊆ Service.metadata.labels
#   - ServiceMonitor.spec.namespaceSelector.matchNames 에 Service.metadata.namespace 포함
#   - ServiceMonitor.spec.endpoints[].port ∈ Service.spec.ports[].name
#
# kubectl 미존재 시 skip (exit 0) — 환경 의존 spurious 실패 방지.
# CI ubuntu runner 는 azure/setup-kubectl@v4 로 설치.
#
# Exit:
#   0 — 위반 0건 (또는 kubectl 미존재로 skip)
#   1 — 위반 1건 이상

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v kubectl >/dev/null 2>&1; then
    echo "[D5-V5] kubectl not found — skipping (CI 에서는 azure/setup-kubectl@v4 로 설치)"
    exit 0
fi

# pyyaml preflight (CI 는 ci.yml step 으로 설치, 로컬은 venv/pip 권장)
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[D5-V5] pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

# ---------- self-test (구현 ③ PR4) ----------
# 이 lint 는 지금까지 음성 대조군이 없었다 — canonical 집합을 6 으로 넓히면서 함께 세운다.
# 조작 대상은 **렌더 산출**이다(입력 매니페스트가 아니라): overlay patch 가 만들어내는 상태까지
# 포함해 검사하는 것이 이 lint 의 계약이기 때문이다.
if [[ "${1:-}" == "--self-test" ]]; then
    ST_TMP="$(mktemp -d)"
    trap 'rm -rf "$ST_TMP"' EXIT
    ST_BASE="$ST_TMP/base.yml"
    kubectl kustomize k8s/overlays/gke >"$ST_BASE"
    ST_FAIL=0

    # (0) 원본 렌더는 통과해야 한다 — 통과 못 하면 아래 음성 케이스가 무의미하다
    if ! RENDERED_OVERRIDE="$ST_BASE" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
        echo "self-test 실패: 원본 렌더가 통과하지 않는다" >&2
        exit 1
    fi

    run_case() {
        local name="$1" file="$2"
        if RENDERED_OVERRIDE="$file" bash "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
            echo "self-test 실패: '$name' 을 검출하지 못했다" >&2
            ST_FAIL=1
        fi
    }

    ST_BASE="$ST_BASE" ST_TMP="$ST_TMP" python3 - <<'PYEOF'
import os, copy, yaml

base, tmp = os.environ["ST_BASE"], os.environ["ST_TMP"]
with open(base) as f:
    docs = [d for d in yaml.safe_load_all(f) if d]

def write(name, mutate):
    out = copy.deepcopy(docs)
    mutate(out)
    with open(os.path.join(tmp, name), "w") as f:
        yaml.safe_dump_all(out, f, allow_unicode=True, sort_keys=False)

def find(ds, kind, name):
    for d in ds:
        if d.get("kind") == kind and (d.get("metadata") or {}).get("name") == name:
            return d
    raise AssertionError(f"{kind}/{name} not found in render")

# (1) SM 1개 삭제 — 집합이 줄면 그 서비스는 영원히 scrape 되지 않는데 매칭 검사는 통과한다
def drop_sm(ds):
    ds.remove(find(ds, "ServiceMonitor", "gateway"))
write("sm-deleted.yml", drop_sm)

# (2) extra SM — 정본에 없는 이름이 늘어도 통과하면 집합이 계약이 아니다
def extra_sm(ds):
    ds.append(copy.deepcopy(find(ds, "ServiceMonitor", "gateway")) | {
        "metadata": dict(find(ds, "ServiceMonitor", "gateway")["metadata"], name="gateway-shadow")})
write("sm-extra.yml", extra_sm)

# (3) selector 오타 — 매칭되는 Service 가 없어진다
def selector_typo(ds):
    find(ds, "ServiceMonitor", "gateway")["spec"]["selector"]["matchLabels"]["app"] = "gateway-typo"
write("sm-selector-typo.yml", selector_typo)

# (4) endpoint port 오타 — Service 에 없는 포트를 긁는다
def port_typo(ds):
    find(ds, "ServiceMonitor", "gateway")["spec"]["endpoints"][0]["port"] = "http"
write("sm-port-typo.yml", port_typo)

# (5) **핵심 회귀**: gateway SM 의 selector 를 `app: gateway` 단독으로 되돌린다.
#     그러면 8081 이 없는 public gateway Service 까지 매칭해 endpoint port 검사가 깨져야 한다.
#     이 케이스가 red 여야 "두 라벨 논리곱" 계약이 실제로 강제되는 것이다(ADR-0024 D1).
def selector_too_broad(ds):
    find(ds, "ServiceMonitor", "gateway")["spec"]["selector"]["matchLabels"] = {"app": "gateway"}
write("sm-selector-broad.yml", selector_too_broad)

# (6) gateway-metrics Service 의 포트 이름 변경 — SM endpoint 와 어긋난다
def svc_port_rename(ds):
    find(ds, "Service", "gateway-metrics")["spec"]["ports"][0]["name"] = "metrics"
write("svc-port-rename.yml", svc_port_rename)
PYEOF

    run_case "SM 1개 삭제" "$ST_TMP/sm-deleted.yml"
    run_case "정본 외 SM 추가" "$ST_TMP/sm-extra.yml"
    run_case "SM selector 오타" "$ST_TMP/sm-selector-typo.yml"
    run_case "SM endpoint port 오타" "$ST_TMP/sm-port-typo.yml"
    run_case "gateway SM selector 과대매칭(app 단독)" "$ST_TMP/sm-selector-broad.yml"
    run_case "gateway-metrics Service 포트 이름 변경" "$ST_TMP/svc-port-rename.yml"

    if [[ "$ST_FAIL" -ne 0 ]]; then
        exit 1
    fi
    echo "servicemonitor-selector-lint self-test 6종 통과"
    exit 0
fi

OVERLAYS=("k8s/overlays/minikube" "k8s/overlays/gke")
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# self-test 는 렌더 산출을 조작해 먹인다 — kustomize 를 다시 돌리면 조작이 사라진다.
if [[ -n "${RENDERED_OVERRIDE:-}" ]]; then
    OVERLAYS=("$RENDERED_OVERRIDE")
fi

VIOLATIONS=0
for overlay in "${OVERLAYS[@]}"; do
    out="$TMP_DIR/$(basename "$overlay").yml"
    if [[ -n "${RENDERED_OVERRIDE:-}" ]]; then
        out="$overlay"
    elif ! kubectl kustomize "$overlay" >"$out" 2>"$TMP_DIR/err"; then
        echo "[D5-V5] kubectl kustomize failed for $overlay:" >&2
        cat "$TMP_DIR/err" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
        continue
    fi
    OVERLAY_NAME="$(basename "$overlay")" \
    OVERLAY_OUT="$out" \
    python3 - <<'PY'
import os, sys, yaml

overlay = os.environ["OVERLAY_NAME"]
path = os.environ["OVERLAY_OUT"]

with open(path) as f:
    docs = [d for d in yaml.safe_load_all(f) if d]

services = []   # list of (ns, name, labels, ports[name list])
monitors = []   # list of (ns, name, ns_match, label_match, endpoint_ports)

for d in docs:
    kind = d.get("kind")
    md = d.get("metadata", {}) or {}
    spec = d.get("spec", {}) or {}
    if kind == "Service":
        port_names = []
        for p in spec.get("ports", []) or []:
            n = p.get("name")
            if n:
                port_names.append(n)
        services.append({
            "ns": md.get("namespace"),
            "name": md.get("name"),
            "labels": md.get("labels", {}) or {},
            "port_names": port_names,
        })
    elif kind == "ServiceMonitor":
        ns_sel = (spec.get("namespaceSelector", {}) or {}).get("matchNames", []) or []
        lbl_sel = (spec.get("selector", {}) or {}).get("matchLabels", {}) or {}
        ep_ports = [e.get("port") for e in (spec.get("endpoints", []) or []) if e.get("port")]
        monitors.append({
            "ns": md.get("namespace"),
            "name": md.get("name"),
            "ns_match": ns_sel,
            "label_match": lbl_sel,
            "endpoint_ports": ep_ports,
        })

violations = []

# canonical 5서비스 ServiceMonitor 집합 강제 (PR3b GP-2 #1) — 0개/축소 시 selector 매칭이
# vacuous-green 되는 false-negative 차단. ground truth 는 CI matrix 가 아닌 고정 5서비스.
# 집합 C (ADR-0024 D1) — 도메인 5 + 인프라 1. SM 이름은 per-service 디렉터리명을 따르므로
# gateway 의 SM 이름은 `gateway` 다(scrape 되는 Service 이름 `gateway-metrics` 와 다르다 — 의도).
CANONICAL = {"notification-service", "user-service", "product-service", "order-service",
             "payment-service", "gateway"}
monitor_names = {m["name"] for m in monitors}
if monitor_names != CANONICAL:
    violations.append(
        f"[D5-V5] ServiceMonitor 집합 불일치 ({overlay}):\n"
        f"  expected(canonical 6 = 도메인 5 + 인프라 1): {sorted(CANONICAL)}\n"
        f"  actual: {sorted(monitor_names)}\n"
        f"  missing: {sorted(CANONICAL - monitor_names)} / extra: {sorted(monitor_names - CANONICAL)}\n"
        f"  → ADR-0024 D1 집합 C: 6개 ServiceMonitor 필수 — 0개/축소 시 selector-lint vacuous-green 차단.\n"
    )

for sm in monitors:
    # endpoints / port presence 우선 검증 — empty endpoints[] 또는 port 없는 endpoint 가
    # selector 매칭 통과로 위반 없이 exit 0 되는 false negative 차단.
    if not sm["endpoint_ports"]:
        violations.append(
            f"[D5-V5] ServiceMonitor endpoints/port missing ({overlay}):\n"
            f"  ServiceMonitor: {sm['ns']}/{sm['name']}\n"
            f"  spec.endpoints[].port: (none)\n"
            f"  → ADR-0009 §Decision S5: scrape 계약 위반 — endpoints + port 필수.\n"
        )

    candidate_ns = sm["ns_match"] if sm["ns_match"] else [sm["ns"]]
    matching_services = [
        s for s in services
        if s["ns"] in candidate_ns
        and all(s["labels"].get(k) == v for k, v in sm["label_match"].items())
    ]
    if not matching_services:
        violations.append(
            f"[D5-V5] ServiceMonitor selector unmatched ({overlay}):\n"
            f"  ServiceMonitor: {sm['ns']}/{sm['name']}\n"
            f"  namespaceSelector.matchNames: {candidate_ns}\n"
            f"  selector.matchLabels: {sm['label_match']}\n"
            f"  → ADR-0009 §Decision S5: scrape target 매칭 Service 부재.\n"
        )
        continue
    for svc in matching_services:
        for p in sm["endpoint_ports"]:
            if p not in svc["port_names"]:
                violations.append(
                    f"[D5-V5] ServiceMonitor endpoint port unmatched ({overlay}):\n"
                    f"  ServiceMonitor: {sm['ns']}/{sm['name']}\n"
                    f"  endpoint port: {p!r}\n"
                    f"  Service: {svc['ns']}/{svc['name']}\n"
                    f"  Service.spec.ports[].name: {svc['port_names']}\n"
                    f"  → ADR-0009 §Decision S5: ServiceMonitor endpoints[].port ∈ Service.spec.ports[].name 위반.\n"
                )

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)
sys.exit(0)
PY
    rc=$?
    if [[ $rc -ne 0 ]]; then
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done

if [[ "$VIOLATIONS" -gt 0 ]]; then
    exit 1
fi
exit 0
