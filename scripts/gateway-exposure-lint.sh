#!/usr/bin/env bash
# gateway-exposure-lint.sh — gateway 노출 계약 정적 검증 (ADR-0013 D3 · 구현 ③ PR3b)
#
# 목적: 양 overlay(minikube, gke) 의 kubectl kustomize 산출물에서 gateway 의 노출 표면을 검사.
#       아래 위반들은 **kustomize 렌더가 전부 성공으로 통과시킨다** — 렌더 성공은 계약 검증이 아니다.
#
# 왜 이 검사들인가:
#   - gateway Service 는 overlay 에서 NodePort/LoadBalancer 로 patch 된다. Service 에 관리 포트(8081)가
#     섞이면 LB 가 /actuator/prometheus 까지 외부에 게시한다 — PR3a 가 포트를 분리해 막은 것이
#     k8s 층에서 되살아난다. port 만 보면 `port: 8080, targetPort: 8081` 이 통과하므로 둘 다 고정한다.
#   - 정본 이름만 세면 (a) 다른 이름의 Service 가 gateway Pod 를 선택하거나 (b) 다른 Deployment 가
#     app=gateway Pod 를 추가하는 우회가 남는다 → 이름이 아니라 **실제 selector 매칭**으로 판정한다.
#   - hostNetwork/hostPort 는 Service 를 통째로 우회해 관리 포트를 노출한다.
#   - gateway 는 소비하는 비밀이 0 이다(RS256 개인키는 user-service 전용, HS512 fallback off).
#     Secret 이름을 추측하는 대신 **PodSpec 전체에서 Secret 참조가 전무**함을 검사한다.
#     initContainers 도 포함 — native sidecar 는 restartPolicy=Always 로 initContainers 에 위치해
#     "컨테이너 정확히 1개" 검사를 회피한다.
#   - ConfigMap 배선이 빠지면 SPRING_PROFILES_ACTIVE 가 주입되지 않아 application-k8s.yml 이 통째로
#     비활성되고 Redis 가 localhost 로 붙는다 — 렌더도 부팅 테스트도 못 잡는 false-green.
#
# 검사하지 않는 것(소유권 분계):
#   - ServiceMonitor 집합 → scripts/servicemonitor-selector-lint.sh (canonical 정확 일치)
#   - 이미지 ref 3-way 계약 → scripts/image-contract-lint.sh
#
# 자기검증: `bash scripts/gateway-exposure-lint.sh --self-test` 는 무변조 baseline 통과 + 조작 입력
#           13종이 **의도한 검사에** 걸리는지 확인한다(진단 문자열까지 대조 — non-zero 여부만 보면
#           다른 위반에 걸려도 통과라 그 검사가 살아 있는지 증명되지 않는다).
#           vacuous-green 차단 — image-contract-lint 두 모드 검증 선례.
#
# Exit:
#   0 — 위반 0건 (또는 kubectl 미존재로 skip)
#   1 — 위반 1건 이상
#   2 — 전제 미충족(pyyaml 미설치, self-test 실패)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[GW-EXPOSURE]"

if ! command -v kubectl >/dev/null 2>&1; then
    echo "$TAG kubectl not found — skipping (CI 에서는 azure/setup-kubectl@v4 로 설치)"
    exit 0
fi

if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "$TAG pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

CHECKER="$(mktemp)"
trap 'rm -f "$CHECKER"' EXIT

cat >"$CHECKER" <<'PY'
import os, sys, yaml

overlay = os.environ["OVERLAY_NAME"]
path = os.environ["OVERLAY_OUT"]
TAG = "[GW-EXPOSURE]"

with open(path) as f:
    docs = [d for d in yaml.safe_load_all(f) if d]

v = []
def bad(msg):
    v.append(f"{TAG} {overlay}: {msg}")

GW_LABELS = {"app": "gateway"}

def selects(selector, labels):
    """selector(dict) 가 labels(dict) 를 선택하는가."""
    if not selector:
        return False
    return all(labels.get(k) == val for k, val in selector.items())

def pod_templates(doc):
    """Pod 를 만드는 모든 표준 리소스에서 (pod_labels, pod_spec) 를 뽑는다.

    한 종류라도 빠뜨리면 그 종류로 gateway Pod 를 띄우는 우회가 검사 전체를 통과한다
    (Job/CronJob/직접 Pod 로 hostPort 8081 을 여는 경로 — Service 를 거치지 않는다).
    """
    kind = doc.get("kind")
    spec = doc.get("spec") or {}
    if kind == "Pod":
        return [((doc.get("metadata") or {}).get("labels") or {}, spec)]
    if kind == "CronJob":
        tmpl = (((spec.get("jobTemplate") or {}).get("spec") or {}).get("template") or {})
        if tmpl:
            return [((tmpl.get("metadata") or {}).get("labels") or {}, tmpl.get("spec") or {})]
        return []
    if kind in ("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet",
                "ReplicationController", "Job"):
        tmpl = spec.get("template") or {}
        if tmpl:
            return [((tmpl.get("metadata") or {}).get("labels") or {}, tmpl.get("spec") or {})]
    return []


services = [d for d in docs if d.get("kind") == "Service"]

# --- gateway Pod 를 만드는 리소스는 정확히 1개, 그것이 Deployment/gateway ---
gw_producers = []   # (doc, pod_labels, pod_spec)
for d in docs:
    for labels, pspec in pod_templates(d):
        if all(labels.get(k) == val for k, val in GW_LABELS.items()):
            gw_producers.append((d, labels, pspec))

if len(gw_producers) != 1:
    names = sorted(f'{d["kind"]}/{d["metadata"]["name"]}' for d, _, _ in gw_producers)
    bad(f"app=gateway Pod 를 만드는 리소스가 {len(gw_producers)}개 (정확히 1개여야 함): {names}")
    print("\n".join(v), file=sys.stderr)
    sys.exit(1)

dep, pod_labels, pod = gw_producers[0]
if dep["kind"] != "Deployment" or dep["metadata"]["name"] != "gateway":
    bad(f'gateway workload 는 Deployment/gateway 여야 함 — 실제 {dep["kind"]}/{dep["metadata"]["name"]}')

spec = dep.get("spec") or {}

# --- gateway Pod 를 선택하는 Service 는 **승인된 2개 정확 일치** (PR4 · ADR-0024 D1) ---
# 구(PR3b): "정확히 1개". 신: public(8080) + metrics(8081) 두 개다. 이름을 세는 게 아니라
# **실제 selector 매칭**으로 판정하는 것은 그대로다 — 다른 이름의 Service 가 gateway Pod 를
# 선택하는 우회를 막는 것이 이 검사의 목적이기 때문이다.
#
# metrics Service 는 관리 포트를 게시하므로, public Service 보다 계약이 더 빡빡하다:
# ClusterIP 고정(overlay 에서 NodePort/LB 로 승격 금지) — 승격되면 PR3a 의 포트 분리가 무의미해진다.
APPROVED_GW_SERVICES = {"gateway": 8080, "gateway-metrics": 8081}

gw_services = [s for s in services if selects(s.get("spec", {}).get("selector"), pod_labels)]
gw_service_names = sorted(s["metadata"]["name"] for s in gw_services)
if gw_service_names != sorted(APPROVED_GW_SERVICES):
    bad(f"gateway Pod 를 선택하는 Service 집합 불일치 — expected(승인 2개):"
        f" {sorted(APPROVED_GW_SERVICES)}, actual: {gw_service_names}")

for svc in gw_services:
    name = svc["metadata"]["name"]
    expected_port = APPROVED_GW_SERVICES.get(name)
    if expected_port is None:
        continue   # 승인 외 Service — 위 집합 검사가 이미 위반으로 보고했다

    ports = svc["spec"].get("ports", []) or []
    if len(ports) != 1:
        bad(f"{name} Service 포트가 {len(ports)}개 (단일이어야 함): {ports}")
    else:
        p = ports[0]
        if p.get("port") != expected_port or p.get("targetPort") != expected_port:
            bad(f'{name} Service 는 port/targetPort 모두 {expected_port} 이어야 함 — 실제'
                f' port={p.get("port")} targetPort={p.get("targetPort")}'
                f' (public 에 8081 이 섞이면 관리 포트가 외부로, metrics 가 8080 을 가리키면'
                f' scrape 이 업무 트래픽 포트를 긁는다)')

    stype = svc["spec"].get("type")
    if name == "gateway-metrics":
        # 외부 노출 금지 — overlay patch 로 타입이 바뀌면 관리 포트가 그대로 나간다.
        if stype not in (None, "ClusterIP"):
            bad(f"gateway-metrics Service 는 ClusterIP 여야 함 — 실제 type={stype}"
                " (관리 포트 8081 이 클러스터 밖으로 노출된다)")
        if svc["spec"].get("externalIPs") or svc["spec"].get("externalName"):
            bad("gateway-metrics Service 에 external 노출 필드 — 관리 포트 노출 경로다")
        continue

    # overlay 별 노출 형태 (public Service 한정)
    if overlay == "minikube":
        if stype != "NodePort" or ports and ports[0].get("nodePort") != 30080:
            bad(f'minikube gateway Service 는 NodePort 30080 이어야 함 — 실제 type={stype}'
                f' nodePort={ports[0].get("nodePort") if ports else None}')
    elif overlay == "gke":
        ann = (svc["metadata"].get("annotations") or {})
        if stype != "LoadBalancer":
            bad(f"gke gateway Service 는 LoadBalancer 여야 함 — 실제 type={stype}")
        if ann.get("networking.gke.io/load-balancer-type") != "Internal":
            bad("gke gateway Service 에 Internal LB annotation 이 없음 — 공인 LB 로 노출된다")

# --- selector 3자 일치 (정확 일치) ---
# 부분집합 판정(selects)은 "추가 Service 가 이 Pod 를 선택하는가" 탐지용이고, 계약은 세 맵의 정확
# 일치다 — Deployment selector={app:gateway} / Pod labels={app:gateway,surface:public} /
# Service selector={app:gateway,surface:public} 같은 어긋남을 부분집합만으로는 잡지 못한다.
match_labels = (spec.get("selector", {}) or {}).get("matchLabels", {}) or {}
if (spec.get("selector") or {}).get("matchExpressions"):
    bad("Deployment selector.matchExpressions 사용 — 계약은 matchLabels 정확 일치다")
if match_labels != GW_LABELS:
    bad(f"Deployment selector.matchLabels 가 {GW_LABELS} 와 정확히 일치해야 함 — 실제 {match_labels}")
for svc in gw_services:
    if (svc["spec"].get("selector") or {}) != GW_LABELS:
        bad(f'{svc["metadata"]["name"]} Service selector 가 {GW_LABELS} 와 정확히 일치해야 함'
            f' — 실제 {svc["spec"].get("selector")}')
if not all(pod_labels.get(k) == val for k, val in match_labels.items()):
    bad(f"Deployment selector({match_labels}) 와 Pod template labels({pod_labels}) 불일치")

# --- 컨테이너 계약 ---
containers = pod.get("containers", []) or []
ephemeral = pod.get("ephemeralContainers", []) or []
if ephemeral:
    bad(f'ephemeralContainers 선언({[c.get("name") for c in ephemeral]}) — 매니페스트에 둘 것이 아니다')
if len(containers) != 1:
    bad(f'gateway 컨테이너가 {len(containers)}개 (정확히 1개): {[c.get("name") for c in containers]}'
        " — strategic merge patch 의 container name 이 어긋나면 두 번째 컨테이너가 생긴다")
if containers and containers[0].get("name") != "gateway":
    bad(f'gateway 컨테이너 이름이 gateway 가 아님: {containers[0].get("name")}')

init_containers = pod.get("initContainers", []) or []
if init_containers:
    bad(f'gateway 에 initContainers 가 있음 ({[c.get("name") for c in init_containers]})'
        " — 계약상 0개(비밀 복사·선행 작업 경로 차단)")

# --- 호스트 네트워크 우회 ---
if pod.get("hostNetwork"):
    bad("hostNetwork: true — Service 검사를 우회해 관리 포트가 노출된다")
for c in containers + init_containers + ephemeral:
    for p in (c.get("ports") or []):
        if p.get("hostPort") is not None:
            bad(f'컨테이너 {c.get("name")} 에 hostPort={p.get("hostPort")} — Service 검사를 우회한다')

# --- probe 는 관리 포트 8081 ---
PROBE_PATHS = {
    "startupProbe": "/actuator/health/liveness",
    "readinessProbe": "/actuator/health/readiness",
    "livenessProbe": "/actuator/health/liveness",
}
for c in containers:
    for probe, expected_path in PROBE_PATHS.items():
        pr = c.get(probe)
        if not pr:
            bad(f'{c.get("name")}: {probe} 없음 (관리 포트 8081 기준 3종 필수)')
            continue
        http = pr.get("httpGet") or {}
        if http.get("port") != 8081:
            bad(f'{c.get("name")}: {probe} 포트가 {http.get("port")} — management.server.port=8081 이어야 함')
        if http.get("path") != expected_path:
            bad(f'{c.get("name")}: {probe} path={http.get("path")} (기대 {expected_path})')

# --- 비밀 계약: 승인된 CSI 마운트 정확히 1개 (ADR-0017 D2 · 구현 ③ PR3d-b) ---
#
# PR3b 의 계약은 "gateway 는 비밀을 소비하지 않는다(Secret 참조 0)" 였다. PR3d-b 에서 gateway 가
# 내부 토큰 **서명 개인키**를 갖게 되면서 계약이 바뀐다 — 비밀은 정확히 하나, 그것도 CSI 로만.
#
# "승인된 provider 를 쓰는가" 까지만 보면 통과시키는 우회가 많다(계획 loop2 #4). 그래서 driver·SPC
# 이름·provider·GCP resource ID·파일 alias·mountPath·readOnly 를 **하나의 exact allow-list** 로 묶고
# 계수 단위를 SPC 1 ↔ volume 1 ↔ volumeMount 1 관계로 강제한다(loop3 #4) — 같은 volume 을 여러
# 경로/컨테이너에 mount 하면 read-only tmpfs 라도 개인키 사본 표면이 늘어난다.
APPROVED_CSI = {
    "driver": "secrets-store.csi.k8s.io",
    "spc": "gateway-internal-signing-key",
    "namespace": "peekcart",
    "provider": "gcp",
    "resource": "projects/PROJECT_ID_PLACEHOLDER/secrets/peekcart-gateway-internal-signing-key/versions/latest",
    "alias": "gateway-internal-private.pem",
    "mount_path": "/etc/peekcart/gateway-keys",
}

# k8s Secret 경유는 여전히 전면 금지다 — CSI 를 도입한 이유 자체가 etcd 를 피하는 것이다.
for c in containers + init_containers + ephemeral:
    for ef in (c.get("envFrom") or []):
        if ef.get("secretRef"):
            bad(f'[GWX-CSI-001] {c.get("name")}: envFrom.secretRef={ef["secretRef"].get("name")}'
                ' — 개인키는 CSI 로만 투영한다(k8s Secret 금지)')
    for e in (c.get("env") or []):
        ref = ((e.get("valueFrom") or {}).get("secretKeyRef") or {})
        if ref:
            bad(f'[GWX-CSI-001] {c.get("name")}: env {e.get("name")} 이 secretKeyRef({ref.get("name")}) 주입 — 금지')
for vol in (pod.get("volumes") or []):
    if vol.get("secret"):
        bad(f'[GWX-CSI-001] volume {vol.get("name")} 이 Secret 마운트 — 금지')
    for src in ((vol.get("projected") or {}).get("sources") or []):
        if src.get("secret"):
            bad(f'[GWX-CSI-001] volume {vol.get("name")} 의 projected source 가 Secret — 금지')

# (a) CSI volume 은 정확히 1개이고 allow-list 와 정확히 일치해야 한다.
csi_vols = [vol for vol in (pod.get("volumes") or []) if vol.get("csi")]
if len(csi_vols) != 1:
    bad(f"[GWX-CSI-002] gateway PodSpec 의 CSI volume 이 {len(csi_vols)}개 — 승인된 개인키 마운트는 정확히 1개다")
csi_vol_names = set()
for vol in csi_vols:
    csi = vol["csi"]
    csi_vol_names.add(vol.get("name"))
    if csi.get("driver") != APPROVED_CSI["driver"]:
        bad(f'[GWX-CSI-003] volume {vol.get("name")}: driver={csi.get("driver")}'
            f' (승인 {APPROVED_CSI["driver"]}) — 미승인 provider 우회')
    if csi.get("readOnly") is not True:
        bad(f'[GWX-CSI-003] volume {vol.get("name")}: csi.readOnly 가 true 가 아님 — 개인키 경로가 쓰기 가능하다')
    spc = (csi.get("volumeAttributes") or {}).get("secretProviderClass")
    if spc != APPROVED_CSI["spc"]:
        bad(f'[GWX-CSI-003] volume {vol.get("name")}: secretProviderClass={spc}'
            f' (승인 {APPROVED_CSI["spc"]})')
    # nodePublishSecretRef 는 SPC 뿐 아니라 **inline CSI volume** 에도 달 수 있다 — SPC 쪽만 보면
    # 정적 자격증명 Secret 참조가 그대로 통과한다.
    if csi.get("nodePublishSecretRef"):
        bad(f'[GWX-CSI-003] volume {vol.get("name")}: csi.nodePublishSecretRef'
            f'({csi["nodePublishSecretRef"].get("name")}) — 노드 인증은 Workload Identity 로 한다')

# (b) 그 volume 을 mount 하는 지점도 정확히 1개 — 컨테이너/경로가 늘면 사본 표면이 는다.
mounts = [
    (c.get("name"), m)
    for c in containers + init_containers + ephemeral
    for m in (c.get("volumeMounts") or [])
    if m.get("name") in csi_vol_names
]
if len(mounts) != 1:
    bad(f"[GWX-CSI-004] 개인키 CSI volume 의 volumeMount 가 {len(mounts)}개 — 정확히 1개여야 한다"
        " (여러 컨테이너/경로 마운트 금지)")
for cname, m in mounts:
    if m.get("mountPath") != APPROVED_CSI["mount_path"]:
        bad(f'[GWX-CSI-004] {cname}: 개인키 mountPath={m.get("mountPath")}'
            f' (승인 {APPROVED_CSI["mount_path"]}) — application-k8s.yml 의 경로와 어긋나면 부팅이 죽는다')
    if m.get("readOnly") is not True:
        bad(f'[GWX-CSI-004] {cname}: volumeMount.readOnly 가 true 가 아님'
            " — volume 쪽만 readOnly 면 컨테이너에서 쓰기 가능한 경로가 남는다")

# (c) SecretProviderClass 자체 — secretObjects/nodePublishSecretRef 는 CSI 도입 취지를 되돌린다.
spcs = [d for d in docs
        if d.get("kind") == "SecretProviderClass"
        and (d.get("metadata") or {}).get("name") == APPROVED_CSI["spc"]]
if len(spcs) != 1:
    bad(f'[GWX-CSI-005] SecretProviderClass "{APPROVED_CSI["spc"]}" 가 {len(spcs)}개 (정확히 1개)')
for spc_doc in spcs:
    sspec = spc_doc.get("spec") or {}
    # namespace 까지 고정한다 — 다른 NS 의 동명 SPC 는 Pod 에서 해석되지 않아 마운트가 조용히 비고,
    # 이름만 보는 검사는 그걸 정상으로 통과시킨다.
    spc_ns = (spc_doc.get("metadata") or {}).get("namespace")
    if spc_ns != APPROVED_CSI["namespace"]:
        bad(f'[GWX-CSI-005] SPC namespace={spc_ns} (승인 {APPROVED_CSI["namespace"]})')
    if sspec.get("provider") != APPROVED_CSI["provider"]:
        bad(f'[GWX-CSI-005] SPC provider={sspec.get("provider")} (승인 {APPROVED_CSI["provider"]})')
    if sspec.get("secretObjects"):
        bad("[GWX-CSI-005] SPC 에 secretObjects 가 있다 — CSI 가 도로 k8s Secret 을 만들어 etcd 회피가 무의미해진다")
    if (sspec.get("parameters") or {}).get("nodePublishSecretRef"):
        bad("[GWX-CSI-005] SPC 에 nodePublishSecretRef 가 있다 — 노드 인증은 Workload Identity 로 한다")
    raw = (sspec.get("parameters") or {}).get("secrets") or ""
    try:
        entries = yaml.safe_load(raw) or []
    except yaml.YAMLError:
        entries = None
    if not isinstance(entries, list):
        bad(f"[GWX-CSI-005] SPC parameters.secrets 를 목록으로 파싱할 수 없다: {raw!r}")
    elif len(entries) != 1:
        bad(f"[GWX-CSI-005] SPC parameters.secrets entry 가 {len(entries)}개 — 개인키 1개만 승인된다")
    else:
        entry = entries[0] or {}
        if entry.get("resourceName") != APPROVED_CSI["resource"]:
            bad(f'[GWX-CSI-005] SPC resourceName={entry.get("resourceName")}'
                f' (승인 {APPROVED_CSI["resource"]}) — 다른 secret 을 끌어오는 우회')
        if entry.get("path") != APPROVED_CSI["alias"]:
            bad(f'[GWX-CSI-005] SPC path={entry.get("path")} (승인 {APPROVED_CSI["alias"]})'
                " — 파일 alias 가 바뀌면 마운트 경로는 같아도 키 파일을 못 찾는다")
if pod.get("automountServiceAccountToken") is not False:
    bad("automountServiceAccountToken 이 false 가 아님 — gateway 는 k8s API 를 쓰지 않는다")

# --- ConfigMap 배선 + 프로파일 스위치 ---
cm_names = set()
for c in containers:
    for ef in (c.get("envFrom") or []):
        if ef.get("configMapRef"):
            cm_names.add(ef["configMapRef"].get("name"))
if "gateway-config" not in cm_names:
    bad("gateway 컨테이너에 envFrom.configMapRef=gateway-config 가 없음"
        " — SPRING_PROFILES_ACTIVE 미주입 시 application-k8s.yml 이 비활성되고 Redis 가 localhost 로 붙는다")
else:
    cms = [d for d in docs if d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "gateway-config"]
    if len(cms) != 1:
        bad(f"gateway-config ConfigMap 이 {len(cms)}개 (정확히 1개)")
    elif (cms[0].get("data") or {}).get("SPRING_PROFILES_ACTIVE") != "k8s":
        bad(f'gateway-config 의 SPRING_PROFILES_ACTIVE 가 k8s 가 아님: {(cms[0].get("data") or {}).get("SPRING_PROFILES_ACTIVE")}')

# --- 무중단 롤아웃 ---
strategy = (spec.get("strategy") or {})
ru = strategy.get("rollingUpdate") or {}
if ru.get("maxUnavailable") != 0:
    bad(f'rollingUpdate.maxUnavailable 이 {ru.get("maxUnavailable")} — 외부 진입점은 0 이어야 한다')

if v:
    print("\n".join(v), file=sys.stderr)
    sys.exit(1)
print(f"{TAG} {overlay}: gateway 노출 계약 OK")
PY

run_checks() {
    local overlays=("k8s/overlays/minikube" "k8s/overlays/gke")
    local tmp_dir violations=0
    tmp_dir="$(mktemp -d)"
    for overlay in "${overlays[@]}"; do
        local out="$tmp_dir/$(basename "$overlay").yml"
        if ! kubectl kustomize "$overlay" >"$out" 2>"$tmp_dir/err"; then
            echo "$TAG kubectl kustomize failed for $overlay:" >&2
            cat "$tmp_dir/err" >&2
            violations=$((violations + 1))
            continue
        fi
        if ! OVERLAY_NAME="$(basename "$overlay")" OVERLAY_OUT="$out" python3 "$CHECKER"; then
            violations=$((violations + 1))
        fi
    done
    rm -rf "$tmp_dir"
    return "$violations"
}

# ---------- self-test: 조작 입력 전 종에서 반드시 실패해야 한다 (현 29종) ----------
if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"; rm -f "$CHECKER"' EXIT
    kubectl kustomize k8s/overlays/gke >"$TMP/base.yml"

    mutate() {
        MUT="$1" SRC="$TMP/base.yml" python3 - <<'PY'
import os, yaml, sys
docs = [d for d in yaml.safe_load_all(open(os.environ["SRC"])) if d]
mut = os.environ["MUT"]
dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "gateway")
svc = next(d for d in docs if d["kind"] == "Service" and d["metadata"]["name"] == "gateway")
# 관리 포트 scrape 전용 Service (PR4) — public Service 와 계약이 다르므로 따로 조작한다.
metrics_svc = next((d for d in docs
                    if d["kind"] == "Service" and d["metadata"]["name"] == "gateway-metrics"), None)
pod = dep["spec"]["template"]["spec"]
c = pod["containers"][0]

if mut == "target_port_8081":
    svc["spec"]["ports"][0]["targetPort"] = 8081
elif mut == "selector_mismatch":
    svc["spec"]["selector"] = {"app": "gateway-typo"}
elif mut == "two_containers":
    pod["containers"].append({"name": "sidecar", "image": "busybox"})
elif mut == "second_service":
    extra = yaml.safe_load(yaml.safe_dump(svc))
    extra["metadata"]["name"] = "gateway-debug"
    extra["spec"]["ports"] = [{"name": "mgmt", "port": 8081, "targetPort": 8081}]
    docs.append(extra)
elif mut == "second_workload":
    extra = yaml.safe_load(yaml.safe_dump(dep))
    extra["metadata"]["name"] = "gateway-canary"
    docs.append(extra)
elif mut == "host_port":
    c.setdefault("ports", []).append({"name": "mgmt-host", "containerPort": 8081, "hostPort": 8081})
elif mut == "init_secret":
    pod["initContainers"] = [{
        "name": "seed", "image": "busybox",
        "env": [{"name": "S", "valueFrom": {"secretKeyRef": {"name": "any", "key": "k"}}}],
    }]
elif mut == "projected_secret":
    pod.setdefault("volumes", []).append(
        {"name": "creds", "projected": {"sources": [{"secret": {"name": "any"}}]}})
elif mut == "no_configmap":
    c["envFrom"] = []
elif mut == "container_secret":
    # 정본 컨테이너에 직접 주입 — initContainers 0개 계약에 걸리지 않고 secretKeyRef 검사를 때린다.
    c.setdefault("env", []).append(
        {"name": "LEAK", "valueFrom": {"secretKeyRef": {"name": "any", "key": "k"}}})
elif mut == "cronjob_host_port":
    # Service 를 거치지 않는 우회 — Deployment 만 훑으면 이 경로가 통째로 검사되지 않는다.
    docs.append({
        "apiVersion": "batch/v1", "kind": "CronJob",
        "metadata": {"name": "gateway-cron", "namespace": "peekcart"},
        "spec": {"schedule": "* * * * *", "jobTemplate": {"spec": {"template": {
            "metadata": {"labels": {"app": "gateway"}},
            "spec": {"containers": [{
                "name": "leak", "image": "busybox",
                "ports": [{"containerPort": 8081, "hostPort": 8081}],
            }]},
        }}}},
    })
elif mut == "bare_pod":
    docs.append({
        "apiVersion": "v1", "kind": "Pod",
        "metadata": {"name": "gateway-debug-pod", "namespace": "peekcart",
                     "labels": {"app": "gateway"}},
        "spec": {"hostNetwork": True, "containers": [{"name": "dbg", "image": "busybox"}]},
    })
elif mut in ("csi_zero", "csi_two", "wrong_spc", "csi_writable",
             "wrong_mount_path", "mount_writable", "mount_twice",
             "spc_secret_objects", "spc_two_entries", "spc_wrong_resource",
             "inline_node_publish", "spc_wrong_namespace"):
    # --- 개인키 CSI allow-list 우회 10종 (PR3d-b P7) ---
    csi_vol = next(v for v in pod["volumes"] if v.get("csi"))
    mount = next(m for m in c["volumeMounts"] if m["name"] == csi_vol["name"])
    spc = next(d for d in docs if d["kind"] == "SecretProviderClass"
               and d["metadata"]["name"] == "gateway-internal-signing-key")
    if mut == "csi_zero":
        pod["volumes"] = [v for v in pod["volumes"] if not v.get("csi")]
    elif mut == "csi_two":
        dup = yaml.safe_load(yaml.safe_dump(csi_vol))
        dup["name"] = csi_vol["name"] + "-2"
        pod["volumes"].append(dup)
    elif mut == "wrong_spc":
        csi_vol["csi"]["volumeAttributes"]["secretProviderClass"] = "some-other-spc"
    elif mut == "csi_writable":
        csi_vol["csi"]["readOnly"] = False
    elif mut == "wrong_mount_path":
        mount["mountPath"] = "/tmp/keys"
    elif mut == "mount_writable":
        mount.pop("readOnly", None)
    elif mut == "mount_twice":
        # 같은 컨테이너에 같은 volume 을 두 경로로 — "컨테이너 1개" 검사에 걸리지 않는 우회.
        extra = yaml.safe_load(yaml.safe_dump(mount))
        extra["mountPath"] = "/etc/peekcart/gateway-keys-copy"
        c["volumeMounts"].append(extra)
    elif mut == "spc_secret_objects":
        spc["spec"]["secretObjects"] = [
            {"secretName": "gateway-key-sync", "type": "Opaque",
             "data": [{"objectName": "gateway-internal-private.pem", "key": "key"}]}]
    elif mut == "spc_two_entries":
        entries = yaml.safe_load(spc["spec"]["parameters"]["secrets"])
        entries.append({"resourceName": "projects/x/secrets/other/versions/latest", "path": "other.pem"})
        spc["spec"]["parameters"]["secrets"] = yaml.safe_dump(entries)
    elif mut == "spc_wrong_resource":
        entries = yaml.safe_load(spc["spec"]["parameters"]["secrets"])
        entries[0]["resourceName"] = "projects/x/secrets/peekcart-user-jwt-signing-key/versions/latest"
        spc["spec"]["parameters"]["secrets"] = yaml.safe_dump(entries)
    elif mut == "inline_node_publish":
        csi_vol["csi"]["nodePublishSecretRef"] = {"name": "static-creds"}
    elif mut == "spc_wrong_namespace":
        spc["metadata"]["namespace"] = "default"
elif mut == "metrics_service_deleted":
    # scrape 표면이 사라지면 S9 가 통째로 수집되지 않는데 나머지 계약은 전부 그대로다.
    docs.remove(metrics_svc)
elif mut == "metrics_service_public_port":
    # metrics Service 가 8080 을 가리키면 scrape 이 업무 포트를 긁고 관리 포트는 안 긁힌다.
    metrics_svc["spec"]["ports"][0]["port"] = 8080
    metrics_svc["spec"]["ports"][0]["targetPort"] = 8080
elif mut == "metrics_service_loadbalancer":
    # overlay patch 로 타입이 승격되면 관리 포트가 클러스터 밖으로 나간다(PR3a 포트 분리 무력화).
    metrics_svc["spec"]["type"] = "LoadBalancer"
elif mut == "metrics_selector_drift":
    # 여전히 gateway Pod 를 선택하지만 계약 라벨({app: gateway})이 아니다 — 부분집합 판정만으로는
    # 통과하므로 3자 정확 일치 검사가 살아 있어야 잡힌다.
    metrics_svc["spec"]["selector"] = {"app.kubernetes.io/name": "gateway"}
elif mut == "label_drift":
    # 세 맵이 서로 어긋나지만 부분집합 판정만으로는 통과하는 조합(3자 정확 일치 검사 대상).
    dep["spec"]["template"]["metadata"]["labels"]["surface"] = "public"
    svc["spec"]["selector"]["surface"] = "public"
else:
    raise SystemExit(f"unknown mutation {mut}")

yaml.safe_dump_all(docs, sys.stdout)
PY
    }

    # mutation → 그 위반이 **의도한 검사**에 걸렸는지 확인할 진단 문자열.
    # non-zero 여부만 보면 다른 위반에 걸려도 통과라 "그 검사가 살아 있는지" 를 증명하지 못한다
    # (예: 컨테이너를 늘리면 probe 부재로도 실패한다).
    declare -A EXPECT=(
        [target_port_8081]="targetPort"
        # PR4: "정확히 1개" → "승인 2개 집합 정확 일치" 로 계약이 바뀌었다(ADR-0024 D1).
        # 진단 문구도 집합 비교로 바뀌므로 기대값을 함께 옮긴다.
        [selector_mismatch]="Service 집합 불일치"
        [two_containers]="컨테이너가 2개"
        [second_service]="Service 집합 불일치"
        [metrics_service_deleted]="Service 집합 불일치"
        [metrics_service_public_port]="port/targetPort 모두 8081"
        [metrics_service_loadbalancer]="ClusterIP 여야 함"
        [metrics_selector_drift]="정확히 일치해야 함"
        [second_workload]="리소스가 2개"
        [host_port]="hostPort"
        [init_secret]="initContainers 가 있음"
        [container_secret]="secretKeyRef"
        [projected_secret]="projected source 가 Secret"
        [no_configmap]="configMapRef=gateway-config 가 없음"
        [cronjob_host_port]="리소스가 2개"
        [bare_pod]="리소스가 2개"
        [label_drift]="정확히 일치해야 함"
        # 개인키 CSI allow-list (PR3d-b) — 진단 ID 로 대조한다. 조작 하나가 여러 검사를 동시에
        # 건드리는 경우(예: csi_zero 는 volume 과 mount 를 함께 깬다)에도 "의도한 검사"가 살아
        # 있는지 증명되려면 문구가 아니라 ID 여야 한다(계획 loop3 #6).
        # CSI 계열은 여러 조작이 **같은 ID** 를 공유하므로 존재 여부만 보면 분기 하나가 죽어도
        # 다른 분기가 내는 같은 ID 로 그린이 된다 → "ID:횟수" 형식으로 정확히 대조한다.
        # (위 레거시 항목들은 서로 겹치지 않는 고유 문구라 substring 대조를 유지한다.)
        [csi_zero]="GWX-CSI-002:1 GWX-CSI-004:1"
        [csi_two]="GWX-CSI-002:1"
        [wrong_spc]="GWX-CSI-003:1"
        [csi_writable]="GWX-CSI-003:1"
        [wrong_mount_path]="GWX-CSI-004:1"
        [mount_writable]="GWX-CSI-004:1"
        [mount_twice]="GWX-CSI-004:2"
        [spc_secret_objects]="GWX-CSI-005:1"
        [spc_two_entries]="GWX-CSI-005:1"
        [spc_wrong_resource]="GWX-CSI-005:1"
        [inline_node_publish]="GWX-CSI-003:1"
        [spc_wrong_namespace]="GWX-CSI-005:1"
    )
    MUTATIONS=(target_port_8081 selector_mismatch two_containers second_service second_workload
               metrics_service_deleted metrics_service_public_port metrics_service_loadbalancer
               metrics_selector_drift
               host_port init_secret container_secret projected_secret no_configmap
               cronjob_host_port bare_pod label_drift
               csi_zero csi_two wrong_spc csi_writable wrong_mount_path mount_writable
               mount_twice spc_secret_objects spc_two_entries spc_wrong_resource
               inline_node_publish spc_wrong_namespace)

    # baseline: 무변조 입력은 반드시 통과해야 한다(전부 실패하는 lint 는 아무것도 증명 못 한다).
    if ! OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/base.yml" python3 "$CHECKER" >/dev/null 2>&1; then
        echo "$TAG self-test FAILED — 무변조 baseline 이 실패한다" >&2
        exit 2
    fi
    echo "$TAG self-test ok — baseline 통과"

    failures=0
    for m in "${MUTATIONS[@]}"; do
        mutate "$m" >"$TMP/mutated.yml"
        if OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/mutated.yml" python3 "$CHECKER" >/dev/null 2>"$TMP/diag"; then
            echo "$TAG self-test FAILED — 조작 입력 '$m' 을 통과시킴(vacuous green)" >&2
            failures=$((failures + 1))
            continue
        fi
        # 기대값이 "ID:횟수" 형식이면 정확 횟수로, 아니면(레거시 고유 문구) substring 으로 대조한다.
        mismatch=""
        if [[ "${EXPECT[$m]}" == GWX-* ]]; then
            for spec in ${EXPECT[$m]}; do
                want_id="${spec%%:*}"; want_n="${spec##*:}"
                got_n=$(grep -c "\[${want_id}\]" "$TMP/diag" || true)
                [ "$got_n" = "$want_n" ] || mismatch="$mismatch ${want_id}(기대 ${want_n}, 실제 ${got_n})"
            done
        elif ! grep -qF "${EXPECT[$m]}" "$TMP/diag"; then
            mismatch=" 기대 문구 '${EXPECT[$m]}' 부재"
        fi
        if [ -n "$mismatch" ]; then
            echo "$TAG self-test FAILED — '$m' 의 진단이 기대와 다르다:$mismatch" >&2
            sed 's/^/  실제: /' "$TMP/diag" >&2
            failures=$((failures + 1))
        else
            echo "$TAG self-test ok — '$m' 차단 (${EXPECT[$m]})"
        fi
    done
    if [[ "$failures" -gt 0 ]]; then
        echo "$TAG self-test 실패 ${failures}/${#MUTATIONS[@]}" >&2
        exit 2
    fi
    echo "$TAG self-test 통과 (${#MUTATIONS[@]}/${#MUTATIONS[@]} 차단)"
    exit 0
fi

if ! run_checks; then
    echo "$TAG gateway 노출 계약 위반 — 외부 진입점 표면이 계획(ADR-0013 D3 · PR3b)과 다르다" >&2
    exit 1
fi
echo "$TAG gateway 노출 계약 OK — overlays: minikube, gke"
