#!/usr/bin/env bash
# kustomize-namespace-lint.sh - D-012 / L-017 namespace leakage gate
#
# Purpose:
#   Render app overlays and fail when a namespaced resource would be applied
#   without an explicit metadata.namespace, or outside the peekcart namespace.
#
# Why:
#   kustomize build can succeed even when a namespaced resource lacks
#   metadata.namespace. In that case kubectl applies it to the caller's current
#   namespace, often "default". That is the exact leakage this gate blocks.
#
# Scope:
#   - k8s/overlays/minikube
#   - k8s/overlays/gke
#
# Dependencies:
#   - kubectl (CI installs it with azure/setup-kubectl@v4)
#   - python3 + pyyaml (CI installs pyyaml before running lints)
#
# Exit:
#   0 - no namespace leakage
#   1 - one or more violations
#   2 - required local tool missing

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v kubectl >/dev/null 2>&1; then
    echo "[D-012/L-017] kubectl not found - CI installs it with azure/setup-kubectl@v4" >&2
    exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "[D-012/L-017] python3 not found - required for YAML parsing" >&2
    exit 2
fi

if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[D-012/L-017] pyyaml not found - run \`python3 -m pip install --user pyyaml\`" >&2
    exit 2
fi

OVERLAYS=("k8s/overlays/minikube" "k8s/overlays/gke")
EXPECTED_NAMESPACE="peekcart"
VIOLATIONS=0
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

for overlay in "${OVERLAYS[@]}"; do
    rendered="$TMP_DIR/$(basename "$overlay").yml"
    if ! kubectl kustomize "$overlay" >"$rendered" 2>"$TMP_DIR/err"; then
        echo "[D-012/L-017] kubectl kustomize failed for $overlay:" >&2
        cat "$TMP_DIR/err" >&2
        VIOLATIONS=$((VIOLATIONS + 1))
        continue
    fi

    if OVERLAY="$overlay" \
       EXPECTED_NAMESPACE="$EXPECTED_NAMESPACE" \
       RENDERED="$rendered" \
       python3 <<'PY'
import os
import sys
import yaml

overlay = os.environ["OVERLAY"]
expected = os.environ["EXPECTED_NAMESPACE"]
rendered = os.environ["RENDERED"]

namespaced_kinds = {
    "ConfigMap",
    "Secret",
    "Service",
    "PersistentVolumeClaim",
    "Deployment",
    "StatefulSet",
    "DaemonSet",
    "ReplicaSet",
    "ReplicationController",
    "Job",
    "CronJob",
    "Ingress",
    "ServiceAccount",
    "Role",
    "RoleBinding",
    "NetworkPolicy",
    "HorizontalPodAutoscaler",
    "PodDisruptionBudget",
    "ServiceMonitor",
}

violations = []
with open(rendered) as f:
    docs = [doc for doc in yaml.safe_load_all(f) if doc]

for doc in docs:
    kind = doc.get("kind")
    if kind not in namespaced_kinds:
        continue

    metadata = doc.get("metadata", {}) or {}
    name = metadata.get("name") or "(missing-name)"
    namespace = metadata.get("namespace")

    if namespace is None or str(namespace).strip() == "":
        violations.append(
            f"[D-012/L-017] namespace missing ({overlay}): "
            f"{kind}/{name} -> would leak to kubectl current namespace"
        )
    elif namespace != expected:
        violations.append(
            f"[D-012/L-017] namespace mismatch ({overlay}): "
            f"{kind}/{name} namespace={namespace!r}, expected={expected!r}"
        )

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)

sys.exit(0)
PY
    then
        :
    else
        VIOLATIONS=$((VIOLATIONS + 1))
    fi
done

if [[ "$VIOLATIONS" -gt 0 ]]; then
    exit 1
fi

echo "[D-012/L-017] namespace lint passed for ${OVERLAYS[*]}"
