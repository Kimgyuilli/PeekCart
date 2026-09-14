#!/usr/bin/env bash
# hpa-replicas-ownership-lint.sh — HPA 가 소유하는 replicas 를 매니페스트가 다시 뺏지 않게 한다
#
# 목적:
#   렌더 산출에서 HorizontalPodAutoscaler 의 scaleTargetRef 가 가리키는 Deployment 가
#   spec.replicas 를 선언하면 실패시킨다.
#
# 왜:
#   `kubectl apply -k` 는 매니페스트에 선언된 replicas 로 **매번 덮어쓴다**. HPA 가 올려둔 수가
#   apply 한 번에 그 값으로 깎이고, HPA 가 되돌리기 전까지 가용 Pod 가 minReplicas 아래에 머문다.
#   gateway 는 minReplicas 2 인데 base 가 `replicas: 1` 을 들고 있어 **배포마다** 이 구간이
#   생겼다(구현 ③ PR3d-b-2 클러스터 세션에서 실측). order-service 는 min 이 1 이라 평소엔
#   무해해 보이지만 부하로 3 까지 올라간 상태에서 apply 하면 똑같이 1 로 깎인다.
#
#   렌더는 이 위반을 전부 성공으로 통과시킨다. 주석으로 "HPA 가 관리한다"고 적어두는 것으로는
#   막히지 않는다 — 실제로 그 주석이 달린 채 계약이 깨져 있었다.
#
# 검사:
#   [HPA-REPL-001] HPA 대상 Deployment 가 spec.replicas 를 선언함
#   [HPA-REPL-002] HPA 의 scaleTargetRef 가 렌더에 없는 workload 를 가리킴 (오타/삭제 방치)
#
# Scope: k8s/overlays/minikube · k8s/overlays/gke
#
# Usage:
#   bash scripts/hpa-replicas-ownership-lint.sh
#   bash scripts/hpa-replicas-ownership-lint.sh --self-test   # 음성 대조군
#
# Exit: 0 위반 없음 / 1 위반 / 2 전제 미충족
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[HPA-REPLICAS]"

for tool in kubectl python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "$TAG $tool 없음" >&2; exit 2; }
done
python3 -c 'import yaml' 2>/dev/null || { echo "$TAG pyyaml 없음" >&2; exit 2; }

OVERLAYS=(k8s/overlays/minikube k8s/overlays/gke)

# stdin 으로 받은 렌더 산출을 검사한다. self-test 가 변이된 렌더를 먹이는 데 같은 코드를 쓴다.
check_render() {
    python3 -c '
import sys, yaml
label = sys.argv[1]
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]

scalable = {}   # (kind, name) -> spec
for d in docs:
    k = d.get("kind")
    if k in ("Deployment", "StatefulSet", "ReplicaSet"):
        scalable[(k, (d.get("metadata") or {}).get("name"))] = d.get("spec") or {}

violations = []
targets = 0
for d in docs:
    if d.get("kind") != "HorizontalPodAutoscaler":
        continue
    ref = ((d.get("spec") or {}).get("scaleTargetRef") or {})
    kind, name = ref.get("kind"), ref.get("name")
    hpa = (d.get("metadata") or {}).get("name")
    key = (kind, name)
    if key not in scalable:
        violations.append(
            "[HPA-REPL-002] HPA %s 의 scaleTargetRef %s/%s 가 렌더에 없다 — 오타이거나 삭제된 workload"
            % (hpa, kind, name))
        continue
    targets += 1
    if "replicas" in scalable[key]:
        violations.append(
            "[HPA-REPL-001] %s/%s 가 spec.replicas=%s 를 선언한다 — HPA %s(min=%s) 가 소유해야 한다. "
            "apply 마다 이 값으로 덮여 HPA 가 올려둔 수가 깎인다"
            % (kind, name, scalable[key]["replicas"], hpa,
               (d.get("spec") or {}).get("minReplicas")))

if violations:
    for v in violations:
        print("%s %s: %s" % ("'"$TAG"'", label, v), file=sys.stderr)
    sys.exit(1)

# HPA 가 0개면 "검사할 게 없음" 이 아니라 검사가 무의미해진 것이다. 그 사실을 드러낸다.
print("%s %s: HPA 소유 replicas OK (대상 %d개)" % ("'"$TAG"'", label, targets))
' "$1"
}

if [[ "${1:-}" == "--self-test" ]]; then
    # 음성 대조군: 정상 렌더에 변이를 주입해 **실제로 red 가 되는지** 확인한다.
    # 이게 없으면 lint 가 vacuous-green 으로 썩어도 알 수 없다.
    TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
    kubectl kustomize k8s/overlays/gke >"$TMP/base.yml" 2>/dev/null

    fail=0
    run_mutation() {
        local name="$1" expect_code="$2"
        if check_render "self-test/$name" <"$TMP/mutated.yml" >/dev/null 2>&1; then
            got=0
        else
            got=$?
        fi
        if [[ "$got" == "$expect_code" ]]; then
            echo "$TAG self-test $name: 기대대로 exit=$got"
        else
            echo "$TAG self-test $name: exit=$got (기대 $expect_code) — 검사가 이 변이를 놓친다" >&2
            fail=1
        fi
    }

    # 1) HPA 대상에 replicas 재도입 (= 이 PR 이 고친 결함 그 자체)
    python3 - "$TMP" <<'PY'
import sys, yaml
t = sys.argv[1]
docs = [d for d in yaml.safe_load_all(open(t + "/base.yml")) if d]
for d in docs:
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "gateway":
        d["spec"]["replicas"] = 1
yaml.safe_dump_all(docs, open(t + "/mutated.yml", "w"))
PY
    run_mutation "gateway_replicas_reintroduced" 1

    # 2) order-service 쪽도 같은 변이로 잡히는지 (gateway 전용 검사가 아님을 고정)
    python3 - "$TMP" <<'PY'
import sys, yaml
t = sys.argv[1]
docs = [d for d in yaml.safe_load_all(open(t + "/base.yml")) if d]
for d in docs:
    if d.get("kind") == "Deployment" and d["metadata"]["name"] == "order-service":
        d["spec"]["replicas"] = 3
yaml.safe_dump_all(docs, open(t + "/mutated.yml", "w"))
PY
    run_mutation "order_service_replicas_reintroduced" 1

    # 3) scaleTargetRef 오타 — HPA 가 아무것도 스케일하지 않는 상태가 조용히 통과하면 안 된다
    python3 - "$TMP" <<'PY'
import sys, yaml
t = sys.argv[1]
docs = [d for d in yaml.safe_load_all(open(t + "/base.yml")) if d]
for d in docs:
    if d.get("kind") == "HorizontalPodAutoscaler" and d["metadata"]["name"] == "gateway":
        d["spec"]["scaleTargetRef"]["name"] = "gatewayy"
yaml.safe_dump_all(docs, open(t + "/mutated.yml", "w"))
PY
    run_mutation "scale_target_typo" 1

    # 4) 양성 대조군 — 변이 없는 렌더는 반드시 통과해야 한다.
    #    이게 없으면 "전부 red" 인 망가진 검사도 1~3 을 통과시킨다.
    cp "$TMP/base.yml" "$TMP/mutated.yml"
    run_mutation "unmutated_must_pass" 0

    # 5) HPA 가 없는 overlay(minikube)에서 replicas 선언은 위반이 아니다 — 과잉 차단 방지
    kubectl kustomize k8s/overlays/minikube >"$TMP/mutated.yml" 2>/dev/null
    run_mutation "no_hpa_overlay_is_fine" 0

    [[ "$fail" == 0 ]] && echo "$TAG self-test 5/5 통과" || { echo "$TAG self-test 실패" >&2; exit 1; }
    exit 0
fi

rc=0
for overlay in "${OVERLAYS[@]}"; do
    TMPF="$(mktemp)"
    if ! kubectl kustomize "$overlay" >"$TMPF" 2>/dev/null; then
        echo "$TAG kubectl kustomize 실패: $overlay" >&2
        rm -f "$TMPF"; exit 2
    fi
    check_render "$overlay" <"$TMPF" || rc=1
    rm -f "$TMPF"
done

[[ "$rc" == 0 ]] && echo "$TAG HPA 소유 replicas 계약 OK — overlays: minikube, gke"
exit "$rc"
