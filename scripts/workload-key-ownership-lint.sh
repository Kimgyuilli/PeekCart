#!/usr/bin/env bash
# workload-key-ownership-lint.sh — 개인키 소유 경계 전수 검증 (ADR-0013 D2 · ADR-0017 D2/D3 · 구현 ③ PR3d-b P7)
#
# 무엇을 막는가:
#   이 클러스터에는 서로 다른 신뢰 도메인의 개인키가 둘 있다.
#     gateway-internal-signing-key   → 내부 토큰 서명. 이게 새면 전 서비스 인증 위조가 된다.
#     user-service-jwt-signing-key   → 사용자 access token 서명. 이게 새면 사용자 사칭이 된다.
#   두 키가 서로의 Pod 에, 혹은 제3의 워크로드에 마운트되면 "키 도메인 분리"(ADR-0017 D3)가 무너진다.
#   렌더는 이런 오배선을 전부 성공으로 통과시킨다.
#
# 왜 Deployment 만 보면 안 되는가(계획 loop2 #4):
#   디버그 Pod·백필 Job·CronJob·DaemonSet 은 Deployment 를 훑는 검사에 안 잡힌다. native sidecar 는
#   restartPolicy=Always 로 initContainers 에 위치해 "컨테이너 1개" 류 검사도 피한다.
#   → **Pod 를 만들어내는 모든 종류**를 전수하고, 그 안의 모든 컨테이너 종류를 본다.
#
# 왜 gateway-exposure-lint 로 충분하지 않은가:
#   그쪽은 gateway 워크로드 **하나**의 노출 표면을 본다. 여기서는 "다른 워크로드가 gateway 키를
#   가져갔는가" 를 본다 — 검사 대상 집합이 반대다.
#
# 공개키 도메인 분리도 함께 본다(ITKO 계열의 렌더 판): internal-token-keys ConfigMap 에 실린 공개키가
# user-service 의 JWKS 설정 키와 **같은 키**면 kid 를 달리해도 노출이다 → SPKI DER SHA-256 대조.
#
# 사용: bash scripts/workload-key-ownership-lint.sh [--self-test]
#
# Exit:
#   0 — 위반 0건
#   1 — 위반 1건 이상
#   2 — 전제 미충족(kubectl/pyyaml 미존재, self-test 실패)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[WL-KEY-OWNERSHIP]"

# CI 와 --self-test 에서 kubectl 부재는 skip 이 아니라 실패다(계획 loop3 #6) —
# "도구가 없어서 통과" 는 검사가 없는 것과 같다.
if ! command -v kubectl >/dev/null 2>&1; then
    echo "$TAG kubectl 이 없다 — 렌더 기반 검사를 수행할 수 없다" >&2
    exit 2
fi
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "$TAG python3 pyyaml 미설치" >&2
    exit 2
fi

CHECKER="$(mktemp -t workload-key-ownership.XXXXXX.py)"
trap 'rm -f "$CHECKER"' EXIT

cat > "$CHECKER" <<'PY'
import base64
import hashlib
import os
import re
import sys

import yaml

TAG = "[WL-KEY-OWNERSHIP]"
overlay = os.environ["OVERLAY_NAME"]
docs = [d for d in yaml.safe_load_all(open(os.environ["OVERLAY_OUT"])) if d]
repo = os.environ["REPO_ROOT"]

# 개인키 SPC allow-list.
#
# **이름만 승인하면 안 된다**: SPC 이름은 그대로 두고 그 안의 `resourceName` 만 상대 키로 바꾸면
# user-service 가 gateway 개인키를 받아가면서도 "승인된 이름" 검사를 통과한다. 그래서 SPC 의
# namespace·provider·entry 수·GCP secret 이름·파일 alias·secretObjects/nodePublishSecretRef 부재까지
# **내용을 exact 로 고정**한다.
#
# 소유자는 이름이 아니라 **(namespace, kind, name)** 이다. 이름만 비교하면 `Job/gateway`,
# `Pod/gateway` 처럼 이름만 같은 별도 워크로드가 개인키를 마운트해도 통과한다.
KEY_OWNERS = {
    "gateway-internal-signing-key": {
        "owner": ("peekcart", "Deployment", "gateway"),
        "namespace": "peekcart",
        "provider": "gcp",
        # project 세그먼트는 배포 시 치환되므로 자유, secret 이름·버전은 고정.
        "resource_re": r"^projects/[^/]+/secrets/peekcart-gateway-internal-signing-key/versions/latest$",
        "alias": "gateway-internal-private.pem",
        # Workload Identity 주체(P2). 소유자 워크로드는 이 KSA 로만 떠야 하고, 이 KSA 는 이 GSA 에만
        # 바인딩된다 — 둘 다 exact 로 고정해야 신원 층위의 교차 접근이 막힌다.
        "ksa": "gateway",
        "gsa_re": r"^peekcart-gateway-signer@[^@]+\.iam\.gserviceaccount\.com$",
    },
    "user-service-jwt-signing-key": {
        "owner": ("peekcart", "Deployment", "user-service"),
        "namespace": "peekcart",
        "provider": "gcp",
        "resource_re": r"^projects/[^/]+/secrets/peekcart-user-jwt-signing-key/versions/latest$",
        "alias": "jwt-private.pem",
        "ksa": "user-service",
        "gsa_re": r"^peekcart-user-signer@[^@]+\.iam\.gserviceaccount\.com$",
    },
}

# 5 도메인 서비스는 내부 토큰 **공개키**를 정확히 이 경로로 받아야 한다(§10.2 property-ownership).
DOMAIN_SERVICES = ["user-service", "product-service", "order-service", "payment-service", "notification-service"]
BINDING_CONFIGMAP = "internal-token-binding"
# User JWT 공개키 바인딩은 **발급 owner(user-service) 에게만** 승인한다.
# 다른 서비스가 이걸 끌어다 쓰면 그 서비스의 JWT 공개키 도메인까지 한 ConfigMap 이
# 좌우하게 되고, 승인 표면이 조용히 넓어진다.
JWKS_BINDING_CONFIGMAP = "user-jwt-binding"
JWKS_BINDING_OWNER = "user-service"
KEYS_CONFIGMAP = "internal-token-keys"
JWKS_CONFIGMAP = "user-jwt-public-keys"

violations = []


def bad(code, message):
    violations.append(f"[{code}] {overlay}: {message}")


def pod_specs(doc):
    """Pod 를 만들어내는 모든 종류에서 ((ns, kind, name), 표시이름, PodSpec) 을 뽑는다."""
    kind = doc.get("kind")
    meta = doc.get("metadata") or {}
    name = meta.get("name", "?")
    ns = meta.get("namespace", "")
    ident = (ns, kind, name)
    label = f"{kind}/{name}(ns={ns or '-'})"
    if kind == "Pod":
        return [(ident, label, doc.get("spec") or {})]
    if kind in ("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job"):
        return [(ident, label, ((doc.get("spec") or {}).get("template") or {}).get("spec") or {})]
    if kind == "CronJob":
        tmpl = (((doc.get("spec") or {}).get("jobTemplate") or {}).get("spec") or {}).get("template") or {}
        return [(ident, label, tmpl.get("spec") or {})]
    return []


def all_containers(pod):
    return ((pod.get("containers") or [])
            + (pod.get("initContainers") or [])
            + (pod.get("ephemeralContainers") or []))


# ---------- (0) SPC 내용 exact allow-list ----------
# 이름은 그대로 두고 내용만 상대 키로 바꾸는 우회가 여기서 걸린다.
spc_docs = {}
for doc in docs:
    if doc.get("kind") != "SecretProviderClass":
        continue
    meta = doc.get("metadata") or {}
    spc_docs.setdefault(meta.get("name"), []).append(doc)

for spc_name, rule in KEY_OWNERS.items():
    found = spc_docs.get(spc_name) or []
    if len(found) != 1:
        bad("WKO-008", f"SecretProviderClass '{spc_name}' 가 {len(found)}개 (정확히 1개여야 한다)")
        continue
    doc = found[0]
    meta = doc.get("metadata") or {}
    sspec = doc.get("spec") or {}
    if meta.get("namespace") != rule["namespace"]:
        bad("WKO-008", f"{spc_name}: namespace={meta.get('namespace')} (승인 {rule['namespace']})")
    if sspec.get("provider") != rule["provider"]:
        bad("WKO-008", f"{spc_name}: provider={sspec.get('provider')} (승인 {rule['provider']})")
    if sspec.get("secretObjects"):
        bad("WKO-008", f"{spc_name}: secretObjects 가 있다 — CSI 가 도로 k8s Secret 을 만든다")
    if (sspec.get("parameters") or {}).get("nodePublishSecretRef"):
        bad("WKO-008", f"{spc_name}: nodePublishSecretRef 금지(정적 자격증명) — Workload Identity 를 쓴다")
    raw = (sspec.get("parameters") or {}).get("secrets") or ""
    try:
        entries = yaml.safe_load(raw) or []
    except yaml.YAMLError:
        entries = None
    if not isinstance(entries, list) or len(entries) != 1:
        bad("WKO-008", f"{spc_name}: parameters.secrets entry 가 1개가 아니다 ({raw!r})")
    else:
        entry = entries[0] or {}
        if not re.match(rule["resource_re"], str(entry.get("resourceName") or "")):
            bad("WKO-008",
                f"{spc_name}: resourceName={entry.get('resourceName')} 가 승인 패턴과 다르다"
                f" ({rule['resource_re']}) — 상대 키를 가리키는 우회")
        if entry.get("path") != rule["alias"]:
            bad("WKO-008", f"{spc_name}: path={entry.get('path')} (승인 {rule['alias']})")


# ---------- (1) 개인키 CSI 마운트의 배타적 소유 ----------
seen_owner_mounts = {spc: [] for spc in KEY_OWNERS}
for doc in docs:
    for ident, label, pod in pod_specs(doc):
        vol_spc = {}
        for vol in (pod.get("volumes") or []):
            csi = vol.get("csi")
            if not csi:
                continue
            spc = (csi.get("volumeAttributes") or {}).get("secretProviderClass")
            if spc:
                vol_spc[vol.get("name")] = spc
            # inline CSI volume 에도 nodePublishSecretRef 를 달 수 있다 — SPC 쪽만 보면 놓친다.
            if csi.get("nodePublishSecretRef"):
                bad("WKO-009",
                    f"{label}: csi.nodePublishSecretRef 로 정적 자격증명을 주입한다 — 금지")

        for vname, spc in vol_spc.items():
            if spc not in KEY_OWNERS:
                bad("WKO-001",
                    f"{label} 이 미승인 SecretProviderClass '{spc}' 를 마운트한다"
                    " — 개인키 SPC 는 allow-list 에만 있어야 한다")
                continue
            owner = KEY_OWNERS[spc]["owner"]
            seen_owner_mounts[spc].append(ident)
            if ident != owner:
                bad("WKO-002",
                    f"{label} 이 '{spc}' 개인키를 마운트한다 — 이 키의 소유자는"
                    f" {owner[1]}/{owner[2]}(ns={owner[0]}) 뿐이다 (키 도메인 교차, ADR-0017 D3)")

        # volume 을 선언만 하고 mount 하지 않는 워크로드도 대상이다 — 선언 자체가 노드 투영을 일으킨다.
        for c in all_containers(pod):
            for m in (c.get("volumeMounts") or []):
                spc = vol_spc.get(m.get("name"))
                if spc in KEY_OWNERS and ident != KEY_OWNERS[spc]["owner"]:
                    bad("WKO-002",
                        f'{label}/{c.get("name")} 이 \'{spc}\' 개인키를 {m.get("mountPath")} 로 마운트한다'
                        f" — 소유자가 아니다")

for spc, owners in seen_owner_mounts.items():
    if not owners:
        bad("WKO-003",
            f"'{spc}' 를 마운트하는 워크로드가 없다 — 검사 대상이 없어 무의미한 통과가 된다"
            " (매니페스트 배선 누락이거나 SPC 이름이 바뀌었다)")

# ---------- (1b) Workload Identity 신원 경계 ----------
#
# CSI 마운트 전수 검사((1))만으로는 부족하다. Secret Manager 접근 권한은 **볼륨이 아니라 신원**에
# 붙는다 — GSA 가 바인딩된 KSA 로 뜨는 Pod 는 CSI 를 마운트하지 않고도 `gcloud secrets versions
# access` 로 개인키를 그대로 읽는다. (1) 은 그 경로를 전혀 보지 못한다.
#
# 그래서 세 가지를 exact 로 고정한다:
#   (a) 소유자 워크로드가 전용 KSA 로 뜬다 — `default` 나 미지정이면 NS 전체가 그 권한을 공유한다.
#   (b) 그 KSA 에 승인된 GSA 어노테이션이 달려 있다 — 다른 GSA 면 상대 키 접근이 열린다.
#   (c) **그 KSA 를 다른 워크로드가 쓰지 않는다** — 디버그 Pod 하나가 빌려 쓰면 (1) 을 통째로 우회한다.
service_accounts = {}
for doc in docs:
    if doc.get("kind") != "ServiceAccount":
        continue
    meta = doc.get("metadata") or {}
    service_accounts[(meta.get("namespace", ""), meta.get("name"))] = doc

approved_ksa = {}  # (ns, ksa) -> spc 이름
for spc_name, rule in KEY_OWNERS.items():
    owner_ns, _, _ = rule["owner"]
    approved_ksa[(owner_ns, rule["ksa"])] = spc_name

for spc_name, rule in KEY_OWNERS.items():
    owner_ns, owner_kind, owner_name = rule["owner"]
    owner_doc = next(
        (d for d in docs
         if d.get("kind") == owner_kind
         and (d.get("metadata") or {}).get("name") == owner_name
         and (d.get("metadata") or {}).get("namespace", "") == owner_ns), None)
    if owner_doc is None:
        bad("WKO-012", f"{owner_kind}/{owner_name}: 렌더에 없다 — 신원 경계를 검사할 대상이 없다")
        continue
    owner_pod = next((pod for _, _, pod in pod_specs(owner_doc)), {})
    sa_name = owner_pod.get("serviceAccountName") or owner_pod.get("serviceAccount")
    if sa_name != rule["ksa"]:
        bad("WKO-012",
            f"{owner_kind}/{owner_name}: serviceAccountName={sa_name!r} (승인 {rule['ksa']!r})"
            f" — '{spc_name}' 개인키 접근 신원이 전용 KSA 가 아니면 NS 의 다른 Pod 도 같은 권한을 갖는다")
        continue

    sa_doc = service_accounts.get((owner_ns, sa_name))
    if sa_doc is None:
        bad("WKO-012",
            f"ServiceAccount/{sa_name}(ns={owner_ns}) 가 렌더에 없다 —"
            f" {owner_kind}/{owner_name} 가 존재하지 않는 KSA 를 참조한다(Pod 기동 실패)")
        continue
    gsa = ((sa_doc.get("metadata") or {}).get("annotations") or {}).get("iam.gke.io/gcp-service-account")
    if not gsa:
        bad("WKO-012",
            f"ServiceAccount/{sa_name}: iam.gke.io/gcp-service-account 어노테이션이 없다"
            " — Workload Identity 주체가 없어 CSI 투영이 실패한다")
    elif not re.match(rule["gsa_re"], str(gsa)):
        bad("WKO-012",
            f"ServiceAccount/{sa_name}: GSA={gsa} 가 승인 패턴과 다르다 ({rule['gsa_re']})"
            " — 다른 GSA 는 상대 키 도메인 접근을 열 수 있다(ADR-0017 D3)")

# (c) 승인 KSA 를 소유자 아닌 워크로드가 빌려 쓰는가.
for doc in docs:
    for ident, label, pod in pod_specs(doc):
        sa_name = pod.get("serviceAccountName") or pod.get("serviceAccount")
        if sa_name is None:
            continue
        spc_name = approved_ksa.get((ident[0], sa_name))
        if spc_name and ident != KEY_OWNERS[spc_name]["owner"]:
            owner = KEY_OWNERS[spc_name]["owner"]
            bad("WKO-013",
                f"{label} 이 KSA '{sa_name}' 로 뜬다 — 이 신원은 '{spc_name}' 개인키를 Secret Manager 에서"
                f" 직접 읽을 수 있다. 소유자는 {owner[1]}/{owner[2]} 뿐이다(마운트 없이 CSI 검사 우회)")


# ---------- (2) 개인키가 k8s Secret/env 로 새는 경로 ----------
# 이름 추측에 의존하지 않는다 — data 를 실제로 decode 해 PEM 개인키 marker 를 찾는다.
# (이름만 보면 `key.pem` 같은 무해한 키 이름에 PKCS#8 을 담는 우회가 통과한다.)
PRIVATE_MARKER = re.compile(r"-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----")
PRIVATE_HINT = re.compile(r"(private[_-]?key|signing[_-]?key)", re.I)


def looks_like_private_key(value):
    if not isinstance(value, str):
        return False
    if PRIVATE_MARKER.search(value):
        return True
    try:
        decoded = base64.b64decode(value, validate=True).decode("utf-8", "ignore")
    except Exception:
        return False
    return bool(PRIVATE_MARKER.search(decoded))


approved_secret_names = set()
for doc in docs:
    if doc.get("kind") != "Secret":
        continue
    name = (doc.get("metadata") or {}).get("name", "?")
    payload = dict(doc.get("data") or {})
    payload.update(doc.get("stringData") or {})
    for k, v in payload.items():
        if looks_like_private_key(v) or PRIVATE_HINT.search(k or ""):
            bad("WKO-004",
                f"Secret/{name} 의 '{k}' 가 개인키다 — 개인키는 Secret Manager + CSI 로만 투영한다")
            approved_secret_names.add(name)

for doc in docs:
    for ident, label, pod in pod_specs(doc):
        for c in all_containers(pod):
            for e in (c.get("env") or []):
                if "value" in e and (looks_like_private_key(e.get("value"))
                                     or PRIVATE_HINT.search(e.get("name") or "")):
                    bad("WKO-004",
                        f'{label}/{c.get("name")}: env {e.get("name")} 에 개인키 값이 직접 들어 있다')

# ---------- (2b) 서비스별 내부 토큰 배선 + JWT 도메인 오염 ----------
#
# 전체 합산으로 보면 한 서비스만 배선돼도 통과한다 — 나머지는 부팅 시 죽거나 전 요청을 거부한다.
# 또 gateway 공개키를 **User JWT 도메인**(app.jwt.rs256.public-keys)에 env/args 로 주입하면
# ConfigMap fingerprint 비교만으로는 안 잡힌다(JWKS 로 내부 앵커가 새는 경로).
JWT_DOMAIN_ENV = re.compile(r"^APP_JWT_RS256_PUBLICKEYS", re.I)

deployments = {}
for doc in docs:
    if doc.get("kind") == "Deployment":
        deployments[(doc.get("metadata") or {}).get("name")] = doc

for svc in DOMAIN_SERVICES:
    dep = deployments.get(svc)
    if dep is None:
        bad("WKO-010", f"{svc}: Deployment 가 렌더에 없다 — 배선을 검사할 대상이 없다")
        continue
    pod = ((dep.get("spec") or {}).get("template") or {}).get("spec") or {}
    containers = pod.get("containers") or []

    binding_refs = sum(
        1 for c in containers for ef in (c.get("envFrom") or [])
        if (ef.get("configMapRef") or {}).get("name") == BINDING_CONFIGMAP)
    if binding_refs != 1:
        bad("WKO-010",
            f"{svc}: envFrom.configMapRef={BINDING_CONFIGMAP} 가 {binding_refs}개 (정확히 1개)"
            " — 없으면 Gateway 서명을 검증할 kid/경로가 주입되지 않는다")

    key_vols = [v.get("name") for v in (pod.get("volumes") or [])
                if (v.get("configMap") or {}).get("name") == KEYS_CONFIGMAP]
    if len(key_vols) != 1:
        bad("WKO-010", f"{svc}: {KEYS_CONFIGMAP} volume 이 {len(key_vols)}개 (정확히 1개)")
    mounts = [m for c in all_containers(pod) for m in (c.get("volumeMounts") or [])
              if m.get("name") in key_vols]
    if len(mounts) != 1:
        bad("WKO-010", f"{svc}: 공개키 volumeMount 가 {len(mounts)}개 (정확히 1개)")
    elif mounts[0].get("readOnly") is not True:
        bad("WKO-010", f"{svc}: 공개키 volumeMount 가 readOnly 가 아니다")

    for c in all_containers(pod):
        for e in (c.get("env") or []):
            ename = e.get("name") or ""
            if JWT_DOMAIN_ENV.match(ename):
                bad("WKO-011",
                    f'{svc}/{c.get("name")}: env {ename} 로 User JWT 공개키 도메인을 덮어쓴다'
                    " — 내부 토큰 키가 JWKS 로 새는 경로다(ADR-0017 D3)")
            if ename == "SPRING_APPLICATION_JSON":
                bad("WKO-011",
                    f'{svc}/{c.get("name")}: SPRING_APPLICATION_JSON 은 어떤 프로퍼티도 덮을 수 있어'
                    " 키 도메인 분리를 우회한다 — 사용 금지")
        for ef in (c.get("envFrom") or []):
            cm = (ef.get("configMapRef") or {}).get("name")
            approved = {f"{svc}-config", BINDING_CONFIGMAP}
            if svc == JWKS_BINDING_OWNER:
                approved.add(JWKS_BINDING_CONFIGMAP)
            if cm and cm not in approved:
                bad("WKO-011",
                    f'{svc}/{c.get("name")}: 미승인 ConfigMap envFrom={cm}'
                    " — 임의 ConfigMap 은 키 도메인 프로퍼티를 덮을 수 있다")

# ---------- (3) 공개키 도메인 분리 (fingerprint) ----------
def spki_fingerprint(pem_text):
    m = re.search(r"-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----", pem_text, re.S)
    if not m:
        return None
    try:
        return hashlib.sha256(base64.b64decode("".join(m.group(1).split()))).hexdigest()
    except Exception:
        return None


internal_fps = {}
for doc in docs:
    if doc.get("kind") == "ConfigMap" and (doc.get("metadata") or {}).get("name") == "internal-token-keys":
        for fname, body in (doc.get("data") or {}).items():
            fp = spki_fingerprint(body)
            if fp is None:
                bad("WKO-005", f"internal-token-keys ConfigMap 의 {fname} 을 공개키로 파싱할 수 없다")
            else:
                internal_fps[fname] = fp

if not internal_fps:
    bad("WKO-006",
        "internal-token-keys ConfigMap 에 공개키가 없다 — 5서비스가 Gateway 서명을 검증할 수 없다")

# user-service 가 JWKS 로 게시하는 공개키와 겹치면 내부 앵커 노출이다.
#
# JWKS 원본은 **두 곳**이다 — 둘 다 봐야 한다:
#   (a) 이미지 classpath 정본 common/src/main/resources/keys
#   (b) user-jwt-public-keys ConfigMap (운영 kid 를 이미지 재빌드 없이 싣는 자리)
# (b) 가 생기면서 누출 경로가 하나 늘었다: 내부 토큰 공개키를 (b) 에 넣으면 kid 를 달리해도
# JwkController 가 그것까지 JWKS 로 게시한다. (a) 만 보던 검사는 그 변이를 통째로 놓친다.
jwks_fps = {}
jwks_dir = os.path.join(repo, "common/src/main/resources/keys")
if os.path.isdir(jwks_dir):
    for fname in sorted(os.listdir(jwks_dir)):
        if not fname.endswith(".pem"):
            continue
        fp = spki_fingerprint(open(os.path.join(jwks_dir, fname), encoding="utf-8").read())
        if fp:
            jwks_fps["classpath:" + fname] = fp

user_cm_fps = {}
for doc in docs:
    if doc.get("kind") == "ConfigMap" and (doc.get("metadata") or {}).get("name") == JWKS_CONFIGMAP:
        for fname, body in (doc.get("data") or {}).items():
            fp = spki_fingerprint(body)
            if fp is None:
                bad("WKO-014", f"{JWKS_CONFIGMAP} ConfigMap 의 {fname} 을 공개키로 파싱할 수 없다")
            else:
                user_cm_fps[fname] = fp
                jwks_fps[JWKS_CONFIGMAP + ":" + fname] = fp

if not user_cm_fps:
    bad("WKO-015",
        f"{JWKS_CONFIGMAP} ConfigMap 에 공개키가 없다 — user-service 가 JWKS 를 게시할 수 없고,"
        " 운영 kid 를 실을 자리가 사라져 공개키가 이미지에 고정된다")

overlap = set(internal_fps.values()) & set(jwks_fps.values())
if overlap:
    bad("WKO-007",
        "internal-token 공개키가 User JWKS 공개키와 동일하다"
        f" (fingerprint {sorted(fp[:16] for fp in overlap)}) — kid 를 달리해도 JWKS 로 노출된다")

if violations:
    print(f"{TAG} 위반 {len(violations)}건", file=sys.stderr)
    for v in violations:
        print("  " + v, file=sys.stderr)
    sys.exit(1)

print(f"{TAG} {overlay}: 개인키 소유 경계 OK"
      f" (개인키 SPC {len(KEY_OWNERS)}종 배타 소유 · 내부 공개키 {len(internal_fps)}개 ·"
      f" JWKS 원본 {len(jwks_fps)}개[classpath+ConfigMap] 와 서로소)")
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
        if ! OVERLAY_NAME="$(basename "$overlay")" OVERLAY_OUT="$out" REPO_ROOT="$REPO_ROOT" \
             python3 "$CHECKER"; then
            violations=$((violations + 1))
        fi
    done
    rm -rf "$tmp_dir"
    return "$violations"
}

# ---------- self-test ----------
# 진단은 고유 ID + 기대 발생 여부로 대조한다. non-zero 여부만 보면 다른 검사에 걸려도 통과로 오판한다.
if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"; rm -f "$CHECKER"' EXIT
    kubectl kustomize k8s/overlays/gke >"$TMP/base.yml"

    mutate() {
        MUT="$1" SRC="$TMP/base.yml" python3 - <<'PY'
import os, sys, yaml

docs = [d for d in yaml.safe_load_all(open(os.environ["SRC"])) if d]
mut = os.environ["MUT"]

gw = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "gateway")
order = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "order-service")
gw_pod = gw["spec"]["template"]["spec"]
gw_vol = next(v for v in gw_pod["volumes"] if v.get("csi"))
order_pod = order["spec"]["template"]["spec"]

def csi_copy():
    return yaml.safe_load(yaml.safe_dump(gw_vol))

if mut == "cross_mount_deployment":
    # 다른 Deployment 가 gateway 개인키를 가져간다.
    order_pod.setdefault("volumes", []).append(csi_copy())
    order_pod["containers"][0].setdefault("volumeMounts", []).append(
        {"name": gw_vol["name"], "mountPath": "/etc/peekcart/gateway-keys", "readOnly": True})
elif mut == "cross_mount_initcontainer":
    # initContainer 경유 — 컨테이너 배열만 보는 검사를 피해간다.
    order_pod.setdefault("volumes", []).append(csi_copy())
    order_pod["initContainers"] = [{
        "name": "seed", "image": "busybox",
        "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys", "readOnly": True}]}]
elif mut == "cross_mount_job":
    docs.append({
        "apiVersion": "batch/v1", "kind": "Job",
        "metadata": {"name": "backfill", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "b", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}})
elif mut == "cross_mount_cronjob":
    docs.append({
        "apiVersion": "batch/v1", "kind": "CronJob",
        "metadata": {"name": "rotate", "namespace": "peekcart"},
        "spec": {"schedule": "* * * * *", "jobTemplate": {"spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "r", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}}}})
elif mut == "cross_mount_daemonset":
    docs.append({
        "apiVersion": "apps/v1", "kind": "DaemonSet",
        "metadata": {"name": "agent", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "a", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}})
elif mut == "cross_mount_bare_pod":
    docs.append({
        "apiVersion": "v1", "kind": "Pod",
        "metadata": {"name": "debug", "namespace": "peekcart"},
        "spec": {"volumes": [csi_copy()],
                 "containers": [{"name": "d", "image": "busybox",
                                 "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}]}})
elif mut == "user_key_on_gateway":
    # 반대 방향 — gateway 가 사용자 서명 개인키를 가져간다.
    v = csi_copy()
    v["name"] = "user-key"
    v["csi"]["volumeAttributes"]["secretProviderClass"] = "user-service-jwt-signing-key"
    gw_pod["volumes"].append(v)
    gw_pod["containers"][0]["volumeMounts"].append(
        {"name": "user-key", "mountPath": "/etc/peekcart/user-keys", "readOnly": True})
elif mut == "unapproved_spc":
    v = csi_copy()
    v["name"] = "mystery"
    v["csi"]["volumeAttributes"]["secretProviderClass"] = "some-vendor-spc"
    order_pod.setdefault("volumes", []).append(v)
elif mut == "no_gateway_mount":
    # 배선이 통째로 빠진 상태에서 통과하면 vacuous-green 이다.
    gw_pod["volumes"] = [v for v in gw_pod["volumes"] if not v.get("csi")]
    gw_pod["containers"][0]["volumeMounts"] = [
        m for m in gw_pod["containers"][0].get("volumeMounts", []) if m["name"] != gw_vol["name"]]
elif mut == "private_key_secret":
    docs.append({
        "apiVersion": "v1", "kind": "Secret",
        "metadata": {"name": "gateway-key-legacy", "namespace": "peekcart"},
        "type": "Opaque",
        "stringData": {"GATEWAY_PRIVATE_KEY": "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----"}})
elif mut == "public_key_domain_merge":
    # 내부 공개키를 User JWKS 정본 키와 같은 값으로 바꾼다 — kid 는 그대로라 이름 검사는 통과한다.
    cm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "internal-token-keys")
    jwks = open(os.path.join(os.environ.get("REPO_ROOT", "."),
                             "common/src/main/resources/keys/dev-jwt-public.pem"), encoding="utf-8").read()
    for k in list(cm["data"].keys()):
        cm["data"][k] = jwks
elif mut == "internal_key_into_user_jwks_cm":
    # **이 ConfigMap 이 만든 새 누출 경로** — 내부 토큰 공개키를 user-jwt-public-keys ConfigMap 에
    # 싣는다. JwkController 가 그 레지스트리를 통째로 JWKS 로 게시하므로 내부 신뢰 앵커가 외부로
    # 나간다. classpath 정본만 보던 검사는 이 변이를 통째로 놓친다 — 그래서 JWKS 원본 집합에
    # 이 ConfigMap 을 포함시켰다.
    icm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "internal-token-keys")
    ucm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "user-jwt-public-keys")
    internal_pem = next(iter(icm["data"].values()))
    for k in list(ucm["data"].keys()):
        ucm["data"][k] = internal_pem
elif mut == "user_jwks_cm_emptied":
    # 공개키가 비면 JWKS 게시가 불가능하고, 운영 kid 를 실을 자리가 사라져 공개키가 이미지에 고정된다
    # (= 이 PR 이 없애려는 상태로의 회귀).
    ucm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "user-jwt-public-keys")
    ucm["data"] = {}
elif mut == "jwks_binding_borrowed_by_other_service":
    # user-jwt-binding 을 발급 owner 가 아닌 서비스가 끌어다 쓴다 — 그 서비스의 JWT 공개키 도메인까지
    # 한 ConfigMap 이 좌우하게 되어 승인 표면이 조용히 넓어진다.
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "order-service")
    c = dep["spec"]["template"]["spec"]["containers"][0]
    c.setdefault("envFrom", []).append({"configMapRef": {"name": "user-jwt-binding"}})
elif mut == "empty_public_keys":
    cm = next(d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "internal-token-keys")
    cm["data"] = {}
elif mut == "spc_user_points_to_gateway":
    # SPC 이름은 그대로 두고 내용만 상대 키로 — "승인된 이름" 검사만으로는 통과하는 우회.
    spc = next(d for d in docs if d["kind"] == "SecretProviderClass"
               and d["metadata"]["name"] == "user-service-jwt-signing-key")
    entries = yaml.safe_load(spc["spec"]["parameters"]["secrets"])
    entries[0]["resourceName"] = "projects/p/secrets/peekcart-gateway-internal-signing-key/versions/latest"
    spc["spec"]["parameters"]["secrets"] = yaml.safe_dump(entries)
elif mut == "spc_wrong_namespace":
    spc = next(d for d in docs if d["kind"] == "SecretProviderClass"
               and d["metadata"]["name"] == "gateway-internal-signing-key")
    spc["metadata"]["namespace"] = "default"
elif mut == "same_name_job":
    # 이름만 gateway 인 별도 워크로드 — 이름 비교만 하면 통과한다.
    docs.append({
        "apiVersion": "batch/v1", "kind": "Job",
        "metadata": {"name": "gateway", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "j", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}})
elif mut == "statefulset_mount":
    docs.append({
        "apiVersion": "apps/v1", "kind": "StatefulSet",
        "metadata": {"name": "cache", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "s", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}})
elif mut == "replicaset_mount":
    docs.append({
        "apiVersion": "apps/v1", "kind": "ReplicaSet",
        "metadata": {"name": "legacy", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "volumes": [csi_copy()],
            "containers": [{"name": "r", "image": "busybox",
                            "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}],
        }}}})
elif mut == "ephemeral_mount":
    # ephemeralContainers(디버그 주입) 경유 — containers 배열만 훑으면 놓친다.
    order_pod.setdefault("volumes", []).append(csi_copy())
    order_pod["ephemeralContainers"] = [{
        "name": "dbg", "image": "busybox",
        "volumeMounts": [{"name": gw_vol["name"], "mountPath": "/keys"}]}]
elif mut == "inline_node_publish":
    # SPC 가 아니라 **inline CSI volume** 에 정적 자격증명을 단다.
    gw_vol["csi"]["nodePublishSecretRef"] = {"name": "static-creds"}
elif mut == "b64_private_secret":
    # 무해한 키 이름 + base64 PKCS#8 — 이름 정규식만으로는 미탐.
    import base64 as _b64
    pem = "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n"
    docs.append({
        "apiVersion": "v1", "kind": "Secret",
        "metadata": {"name": "assets", "namespace": "peekcart"}, "type": "Opaque",
        "data": {"bundle.pem": _b64.b64encode(pem.encode()).decode()}})
elif mut == "binding_removed":
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "payment-service")
    c0 = dep["spec"]["template"]["spec"]["containers"][0]
    c0["envFrom"] = [ef for ef in c0["envFrom"]
                     if (ef.get("configMapRef") or {}).get("name") != "internal-token-binding"]
elif mut == "jwt_domain_env":
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "product-service")
    dep["spec"]["template"]["spec"]["containers"][0].setdefault("env", []).append(
        {"name": "APP_JWT_RS256_PUBLICKEYS_1_KID", "value": "peekcart-gateway-dev-2026"})
elif mut == "spring_application_json":
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "order-service")
    dep["spec"]["template"]["spec"]["containers"][0].setdefault("env", []).append(
        {"name": "SPRING_APPLICATION_JSON", "value": "{\"app\":{\"jwt\":{\"rs256\":{}}}}"})
elif mut == "ksa_default":
    # serviceAccountName 제거 → default KSA. GSA 를 default 에 바인딩하면 NS 전체가 개인키를 읽는다.
    gw_pod.pop("serviceAccountName", None)
elif mut == "ksa_annotation_removed":
    sa = next(d for d in docs if d["kind"] == "ServiceAccount" and d["metadata"]["name"] == "gateway")
    sa["metadata"].pop("annotations", None)
elif mut == "ksa_foreign_gsa":
    # user KSA 를 gateway GSA 에 바인딩 — CSI 마운트는 분리돼 있어도 신원 층위에서 교차 접근이 열린다.
    sa = next(d for d in docs if d["kind"] == "ServiceAccount" and d["metadata"]["name"] == "user-service")
    sa["metadata"]["annotations"]["iam.gke.io/gcp-service-account"] = \
        "peekcart-gateway-signer@p.iam.gserviceaccount.com"
elif mut == "ksa_object_removed":
    docs[:] = [d for d in docs
               if not (d["kind"] == "ServiceAccount" and d["metadata"]["name"] == "gateway")]
elif mut == "ksa_borrowed_by_job":
    # 볼륨을 하나도 마운트하지 않는다 — (1) 의 CSI 전수 검사에는 전혀 걸리지 않는 우회.
    docs.append({
        "apiVersion": "batch/v1", "kind": "Job",
        "metadata": {"name": "dump", "namespace": "peekcart"},
        "spec": {"template": {"spec": {
            "serviceAccountName": "gateway",
            "containers": [{"name": "d", "image": "google/cloud-sdk"}],
        }}}})
elif mut == "keys_mount_writable":
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "notification-service")
    m = next(m for m in dep["spec"]["template"]["spec"]["containers"][0]["volumeMounts"]
             if m["name"] == "internal-token-keys")
    m.pop("readOnly", None)
else:
    raise SystemExit(f"unknown mutation {mut}")

yaml.safe_dump_all(docs, sys.stdout)
PY
    }

    # mutation → 기대 진단 "ID:횟수" 목록 (계획 §8 loop3 #6).
    #
    # ID 존재 여부(grep -qF)만 보면 안 되는 이유: cross-mount 계열은 전부 WKO-002 를 내므로, 검사
    # 분기 하나가 삭제돼도 남은 분기가 같은 ID 를 내면 self-test 가 그린이다. **횟수까지** 대조해야
    # "그 분기가 살아 있는지" 가 증명된다. 아래 횟수는 실제 출력으로 확정했다.
    declare -A EXPECT=(
        # volume 선언 1건 + volumeMount 1건 = 2회
        [cross_mount_deployment]="WKO-002:2"
        [cross_mount_initcontainer]="WKO-002:2"
        [cross_mount_job]="WKO-002:2"
        [cross_mount_cronjob]="WKO-002:2"
        [cross_mount_daemonset]="WKO-002:2"
        [cross_mount_bare_pod]="WKO-002:2"
        [user_key_on_gateway]="WKO-002:2"
        [same_name_job]="WKO-002:2"
        [statefulset_mount]="WKO-002:2"
        [replicaset_mount]="WKO-002:2"
        # volume 선언만(mount 는 ephemeral 컨테이너) — 선언 1 + ephemeral mount 1
        [ephemeral_mount]="WKO-002:2"
        [unapproved_spc]="WKO-001:1"
        [no_gateway_mount]="WKO-003:1"
        [private_key_secret]="WKO-004:1"
        [b64_private_secret]="WKO-004:1"
        [public_key_domain_merge]="WKO-007:1"
        # user-jwt-public-keys ConfigMap 이 만든 새 표면 (구현 ③ 후속)
        [internal_key_into_user_jwks_cm]="WKO-007:1"
        [user_jwks_cm_emptied]="WKO-015:1"
        [jwks_binding_borrowed_by_other_service]="WKO-011:1"
        [empty_public_keys]="WKO-006:1"
        [spc_user_points_to_gateway]="WKO-008:1"
        [spc_wrong_namespace]="WKO-008:1"
        [inline_node_publish]="WKO-009:1"
        [binding_removed]="WKO-010:1"
        [keys_mount_writable]="WKO-010:1"
        [jwt_domain_env]="WKO-011:1"
        # 신원 경계(P2) — (1) 의 CSI 검사가 전부 통과하는 상태에서만 red 가 되어야 의미가 있다.
        [ksa_default]="WKO-012:1"
        [ksa_annotation_removed]="WKO-012:1"
        [ksa_foreign_gsa]="WKO-012:1"
        [ksa_object_removed]="WKO-012:1"
        [ksa_borrowed_by_job]="WKO-013:1"
        [spring_application_json]="WKO-011:1"
    )
    MUTATIONS=(cross_mount_deployment cross_mount_initcontainer cross_mount_job cross_mount_cronjob
               cross_mount_daemonset cross_mount_bare_pod user_key_on_gateway unapproved_spc
               no_gateway_mount private_key_secret public_key_domain_merge empty_public_keys
               spc_user_points_to_gateway spc_wrong_namespace same_name_job statefulset_mount
               replicaset_mount ephemeral_mount inline_node_publish b64_private_secret
               binding_removed jwt_domain_env spring_application_json keys_mount_writable
               ksa_default ksa_annotation_removed ksa_foreign_gsa ksa_object_removed
               ksa_borrowed_by_job
               internal_key_into_user_jwks_cm user_jwks_cm_emptied
               jwks_binding_borrowed_by_other_service)

    if ! OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/base.yml" REPO_ROOT="$REPO_ROOT" \
         python3 "$CHECKER" >/dev/null 2>&1; then
        echo "$TAG self-test FAILED — 무변조 baseline 이 실패한다" >&2
        OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/base.yml" REPO_ROOT="$REPO_ROOT" python3 "$CHECKER" >&2 || true
        exit 2
    fi
    echo "$TAG self-test ok — baseline 통과"

    failures=0
    for m in "${MUTATIONS[@]}"; do
        REPO_ROOT="$REPO_ROOT" mutate "$m" >"$TMP/mutated.yml"
        if OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/mutated.yml" REPO_ROOT="$REPO_ROOT" \
           python3 "$CHECKER" >/dev/null 2>"$TMP/diag"; then
            echo "$TAG self-test FAILED — 조작 입력 '$m' 을 통과시킴(vacuous green)" >&2
            failures=$((failures + 1))
            continue
        fi
        # ID × 기대 횟수 대조. 하나라도 어긋나면 "다른 검사에 걸린" 것으로 본다.
        mismatch=""
        for spec in ${EXPECT[$m]}; do
            want_id="${spec%%:*}"; want_n="${spec##*:}"
            got_n=$(grep -c "\[${want_id}\]" "$TMP/diag" || true)
            [ "$got_n" = "$want_n" ] || mismatch="$mismatch ${want_id}(기대 ${want_n}, 실제 ${got_n})"
        done
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

if run_checks; then
    echo "$TAG 개인키 소유 경계 OK — overlays: minikube, gke"
else
    echo "$TAG 위반 발견" >&2
    exit 1
fi
