#!/usr/bin/env bash
# image-contract-lint.sh - D-015 image repository ref contract gate (per-service, PR3a)
#
# Purpose:
#   Fail when the image reference CI builds/pushes diverges from the reference
#   K8s manifests pull / rewrite. A mismatch means the cluster pulls an image CI
#   never produced — the D-015 break.
#
# Per-service (PR3 — 5개 서비스 풀 분해 이후):
#   CI 가 서비스별 이미지(`ghcr.io/<owner>/peekcart-<service>`)를 빌드/푸시한다. 본 lint 는
#   서비스별로 다음 3-way 가 일치하는지 검증한다:
#     - .github/workflows/ci.yml          images job matrix.service (CI 가 빌드하는 서비스 집합)
#     - k8s/base/services/<svc>/deployment.yml   container image (cluster 가 pull)
#     - k8s/overlays/gke/kustomization.yml        images[].name (AR rewrite source)
#
# 전환기(PR3a→PR3b): CI 매트릭스(이미지)는 PR3a 에서 도입되나 per-service k8s 매니페스트는
#   PR3b 에서 생성된다. 이 사이에는 deployable manifest(여전히 단일 peekcart)가 CI 가 만드는
#   peekcart-<svc> 와 불일치하므로 — D-015 의 정확한 위험(클러스터가 CI 가 안 만든 이미지 pull)이
#   실제로 성립한다. 따라서 매니페스트가 하나도 없으면 본 lint 는 조용히 OK 를 내지 않는다:
#   `IMAGE_CONTRACT_TRANSITION=1` 이 명시될 때만 "SUSPENDED" 로 통과하고(전환기 의도 명시),
#   그 flag 없이는 실패한다. ci.yml 가 PR3a~PR3b 동안만 이 flag 를 set 하고 PR3b 에서 제거한다.
#   매니페스트가 일부라도 있으면 그 서비스는 정상 3-way 검증한다.
#
# Exit:
#   0 - 일치(또는 IMAGE_CONTRACT_TRANSITION=1 로 명시된 전환기 suspend)
#   1 - mismatch / 전환기인데 flag 미설정 (silent false-green 차단)
#   2 - CI 매트릭스를 추출할 수 없음(파일 구조 변경)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

EXPECTED_OWNER="${GITHUB_REPOSITORY_OWNER:-kimgyuilli}"
EXPECTED_OWNER="${EXPECTED_OWNER,,}"
OWNER_TOKEN='${{ github.repository_owner }}'

CI="${REPO_ROOT}/.github/workflows/ci.yml"

# Canonical 서비스 집합(ADR-0010 §5 — 5개 서비스 풀 분해의 정본). lint 의 ground-truth 는
# 이 고정 배열이지 CI matrix 가 아니다 — CI matrix 를 ground-truth 로 쓰면 "matrix 에서 한
# 서비스가 빠지면 전체가 줄어 full-green" 인 순환 false-green 이 생긴다. 본 배열 vs CI matrix 를
# 대조해 CI 가 canonical 과 어긋나면 그 자체를 위반으로 잡는다.
CANONICAL_SERVICES=(notification-service user-service product-service order-service payment-service)
SERVICES=("${CANONICAL_SERVICES[@]}")

# CI 의 images / publish matrix.service 목록을 각각 추출해 canonical 과 정확히 일치하는지 검증.
extract_matrix() {
    local job="$1"
    JOB="$job" python3 - "$CI" <<'PY'
import re, sys, os
text = open(sys.argv[1]).read()
job = os.environ["JOB"]
# `  <job>:` 부터 다음 최상위 job(2-space 들여쓰기 `  \w...:`) 직전까지를 그 job 블록으로 자른다.
jm = re.search(rf'^  {re.escape(job)}:\n(.*?)(?=^  \w[\w-]*:\n|\Z)', text, flags=re.S | re.M)
if not jm:
    sys.exit(0)
block = jm.group(1)
m = re.search(r'service:\s*\n((?:\s*-\s*[\w-]+\s*\n)+)', block)
if not m:
    sys.exit(0)
for line in m.group(1).splitlines():
    t = line.strip().lstrip('-').strip()
    if t.endswith('-service'):
        print(t)
PY
}

matrix_violation=0
canon_sorted="$(printf '%s\n' "${CANONICAL_SERVICES[@]}" | sort | tr '\n' ' ')"
for job in images publish; do
    got="$(extract_matrix "$job" | sort | tr '\n' ' ')"
    if [[ -z "$got" ]]; then
        echo "[D-015] ci.yml '$job' job 의 matrix.service 를 추출할 수 없음 — ci.yml 구조 변경?" >&2
        matrix_violation=1
    elif [[ "$got" != "$canon_sorted" ]]; then
        echo "[D-015] ci.yml '$job' matrix.service 가 canonical 5서비스와 불일치:" >&2
        echo "  expected: ${canon_sorted}" >&2
        echo "  got($job): ${got}" >&2
        matrix_violation=1
    fi
done
if [[ "$matrix_violation" -ne 0 ]]; then
    echo "[D-015] CI matrix 가 canonical 서비스 집합과 드리프트 — 빌드/푸시 누락 위험." >&2
    exit 1
fi

# GHCR ref 를 "owner/repo" 로 정규화(owner 식 해석, 레지스트리/태그 제거, 소문자).
normalize() {
    local ref="$1"
    ref="${ref/$OWNER_TOKEN/$EXPECTED_OWNER}"
    ref="${ref#ghcr.io/}"
    ref="${ref%:*}"
    printf '%s' "${ref,,}"
}

# 파일에서 첫 비주석 GHCR ref 추출(<key>: 라인).
extract() {
    local key="$1" file="$2"
    [[ -f "$file" ]] || return 0
    { grep -E "${key}:[[:space:]]*ghcr\.io/" "$file" \
        | grep -vE '^[[:space:]]*#' \
        | head -1 \
        | sed -E "s/.*${key}:[[:space:]]*//; s/[[:space:]]*(#.*)?$//"; } || true
}

violations=0
checked_manifests=0
for svc in "${SERVICES[@]}"; do
    ci_norm="${EXPECTED_OWNER}/peekcart-${svc}"   # ci.yml: ghcr.io/<owner>/peekcart-<service>

    dep_file="k8s/base/services/${svc}/deployment.yml"
    gke_file="k8s/overlays/gke/kustomization.yml"

    dep_raw="$(extract 'image' "$dep_file")"
    # gke kustomization 은 5 서비스 images[] entry — 해당 서비스 newName 의 source name 라인.
    gke_raw=""
    if [[ -f "$gke_file" ]]; then
        gke_raw="$({ grep -E "name:[[:space:]]*ghcr\.io/[^[:space:]]*peekcart-${svc}\b" "$gke_file" \
            | grep -vE '^[[:space:]]*#' | head -1 | sed -E 's/.*name:[[:space:]]*//; s/[[:space:]]*(#.*)?$//'; } || true)"
    fi

    if [[ -z "$dep_raw" && -z "$gke_raw" ]]; then
        echo "[D-015] ${svc}: per-service 매니페스트 미존재(전환기) — CI naming: $ci_norm"
        continue
    fi
    # 이 서비스는 매니페스트가 있으니 deployable — base deployment 와 gke images[] **둘 다** 필수.
    # 한쪽만 있으면(예: deployment 만, gke entry 누락) 그 자체가 D-015 위반(클러스터/AR rewrite 불일치).
    checked_manifests=$((checked_manifests + 1))

    if [[ -n "$dep_raw" ]]; then
        dep_norm="$(normalize "$dep_raw")"
        if [[ "$dep_norm" != "$ci_norm" ]]; then
            echo "[D-015] ${svc} image mismatch (base deployment):" >&2
            echo "  ci images job -> $ci_norm" >&2
            echo "  base deployment -> $dep_norm  ($dep_raw)" >&2
            violations=$((violations + 1))
        fi
    else
        echo "[D-015] ${svc}: base deployment image 추출 실패 ($dep_file) — gke entry 만 존재" >&2
        violations=$((violations + 1))
    fi

    if [[ -n "$gke_raw" ]]; then
        gke_norm="$(normalize "$gke_raw")"
        if [[ "$gke_norm" != "$ci_norm" ]]; then
            echo "[D-015] ${svc} image mismatch (gke kustomization images[] source):" >&2
            echo "  ci images job -> $ci_norm" >&2
            echo "  gke images src -> $gke_norm  ($gke_raw)" >&2
            violations=$((violations + 1))
        fi
    else
        echo "[D-015] ${svc}: gke kustomization images[] entry 누락 ($gke_file) — base deployment 만 존재" >&2
        violations=$((violations + 1))
    fi
done

if [[ "$violations" -gt 0 ]]; then
    echo "[D-015] image ref contract 위반 ${violations}건 — cluster 가 CI 가 안 만든 이미지를 pull" >&2
    exit 1
fi

# 전환기(부분/전무) 처리: 검증된 매니페스트가 **전체 서비스 수에 못 미치면**(0 포함) 아직
# deployable 상태가 일부라도 단일 peekcart 에 묶여 있을 수 있다 — D-015 위험이 성립한다.
# 따라서 checked < 전체면 조용히 OK 내지 않는다: 명시적 전환기 flag 가 있을 때만 SUSPENDED/PARTIAL
# 통과, 없으면 실패(silent false-green 차단). checked == 전체일 때만 진짜 "OK".
if [[ "$checked_manifests" -lt "${#SERVICES[@]}" ]]; then
    if [[ "${IMAGE_CONTRACT_TRANSITION:-0}" == "1" ]]; then
        echo "[D-015] image ref contract PARTIAL/SUSPENDED (전환기, IMAGE_CONTRACT_TRANSITION=1)"
        echo "  per-service 매니페스트 ${checked_manifests}/${#SERVICES[@]} 검증 — 나머지는 PR3b 완료 시 활성. 전체 생성 후 ci.yml 에서 flag 제거."
        exit 0
    fi
    echo "[D-015] per-service 매니페스트 ${checked_manifests}/${#SERVICES[@]} (전체 미만)이고 IMAGE_CONTRACT_TRANSITION 미설정 — silent false-green 차단." >&2
    echo "  전환기라면 ci.yml 에서 IMAGE_CONTRACT_TRANSITION=1 을 set 하라(전 서비스 매니페스트 완성 시 제거)." >&2
    exit 1
fi

echo "[D-015] image ref contract OK — services: ${SERVICES[*]} (manifest-checked: ${checked_manifests}/${#SERVICES[@]}, full)"
exit 0
