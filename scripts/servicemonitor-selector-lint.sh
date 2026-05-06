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

OVERLAYS=("k8s/overlays/minikube" "k8s/overlays/gke")
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

VIOLATIONS=0
for overlay in "${OVERLAYS[@]}"; do
    out="$TMP_DIR/$(basename "$overlay").yml"
    if ! kubectl kustomize "$overlay" >"$out" 2>"$TMP_DIR/err"; then
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
