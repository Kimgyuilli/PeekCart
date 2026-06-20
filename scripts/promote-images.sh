#!/usr/bin/env bash
# promote-images.sh — D-016 image promotion (GHCR → Artifact Registry), per-service.
#
# CI(.github/workflows/ci.yml)가 5개 서비스 이미지를 GHCR 로 push 한다. GKE 는 Artifact
# Registry 에서 pull 하므로 GHCR → AR 승격이 필요하다. 본 스크립트는 그 승격을 형식화한다
# (수동 트리거). 완전 자동 트리거(태그/릴리스 훅)는 후속 non-blocking.
#
# - crane 우선(레지스트리간 직접 복사, 로컬 docker 무의존). 없으면 docker pull/tag/push 폴백.
# - 승격 후 AR digest(sha256)를 산출해 출력한다(L-016a digest 고정 · gke images[] 참조 근거).
#
# Usage:
#   scripts/promote-images.sh --project <PROJECT_ID> [--tag latest] [--service <svc>] [--dry-run]
#   scripts/promote-images.sh --help
#
# Exit: 0 성공/ dry-run/ help · 1 인자 오류 또는 승격 실패.

set -euo pipefail

OWNER="${GITHUB_REPOSITORY_OWNER:-kimgyuilli}"
OWNER="${OWNER,,}"
AR_LOCATION="asia-northeast3-docker.pkg.dev"
AR_REPO="peekcart"
TAG="latest"
PROJECT=""
ONLY_SERVICE=""
DRY_RUN=0

# Canonical 5서비스(ADR-0010 §5) — image-contract-lint 와 동일 ground-truth.
CANONICAL_SERVICES=(notification-service user-service product-service order-service payment-service)

usage() {
    cat <<'USAGE'
promote-images.sh — D-016 image promotion (GHCR → Artifact Registry), per-service.

CI 가 5개 서비스 이미지를 GHCR 로 push 한다. GKE 는 AR 에서 pull 하므로 GHCR → AR 승격이 필요하다.
crane 우선(레지스트리간 직접 복사, content digest 보존). 없으면 docker pull/tag/push 폴백.
승격 후 AR digest(sha256)를 산출하고, gke overlay 에 digest 를 고정하는 kustomize 명령을 출력한다
(L-016a digest 고정 · mutable latest 탈피). 완전 자동 트리거는 후속 non-blocking.

Usage:
  scripts/promote-images.sh --project <PROJECT_ID> [--tag latest] [--service <svc>] [--dry-run]
  scripts/promote-images.sh --help

Options:
  --project <id>   Artifact Registry 프로젝트 ID (실행 시 필수, --dry-run 은 선택)
  --tag <tag>      GHCR 원본 태그 (기본 latest). crane 은 content digest 를 보존해 복사한다.
  --service <svc>  단일 서비스만 승격 (canonical 5 중 하나)
  --dry-run        실제 승격 없이 GHCR→AR 매핑만 출력
  -h, --help       본 도움말
USAGE
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --project) PROJECT="${2:-}"; shift 2 ;;
        --tag) TAG="${2:-}"; shift 2 ;;
        --service) ONLY_SERVICE="${2:-}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; exit 1 ;;
    esac
done

if [[ -n "$ONLY_SERVICE" ]]; then
    # shellcheck disable=SC2076
    if [[ ! " ${CANONICAL_SERVICES[*]} " =~ " ${ONLY_SERVICE} " ]]; then
        echo "[D-016] --service '$ONLY_SERVICE' 는 canonical 5서비스가 아님: ${CANONICAL_SERVICES[*]}" >&2
        exit 1
    fi
    SERVICES=("$ONLY_SERVICE")
else
    SERVICES=("${CANONICAL_SERVICES[@]}")
fi

if [[ "$DRY_RUN" -eq 0 && -z "$PROJECT" ]]; then
    echo "[D-016] --project <PROJECT_ID> 필수 (또는 --dry-run). --help 참고." >&2
    exit 1
fi
PROJECT_SHOWN="${PROJECT:-<PROJECT_ID>}"

# 승격 도구 선택(dry-run 은 미실행이라 무관).
COPY_TOOL="none"
if command -v crane >/dev/null 2>&1; then COPY_TOOL="crane"
elif command -v docker >/dev/null 2>&1; then COPY_TOOL="docker"; fi

echo "[D-016] image promotion (GHCR → Artifact Registry)"
echo "  owner=$OWNER  project=$PROJECT_SHOWN  tag=$TAG  tool=$COPY_TOOL  dry-run=$DRY_RUN"
echo "  services: ${SERVICES[*]}"

rc=0
for svc in "${SERVICES[@]}"; do
    src="ghcr.io/${OWNER}/peekcart-${svc}:${TAG}"
    dst="${AR_LOCATION}/${PROJECT_SHOWN}/${AR_REPO}/${svc}:${TAG}"
    echo "---"
    echo "[${svc}] $src  ->  $dst"

    if [[ "$DRY_RUN" -eq 1 ]]; then
        echo "  (dry-run) 승격 생략. 실제 실행 시 ${COPY_TOOL} 로 복사 후 AR digest 산출."
        continue
    fi

    if [[ "$COPY_TOOL" == "crane" ]]; then
        crane copy "$src" "$dst" || { echo "  [실패] crane copy" >&2; rc=1; continue; }
        digest="$(crane digest "$dst" 2>/dev/null || true)"
    elif [[ "$COPY_TOOL" == "docker" ]]; then
        docker pull "$src" && docker tag "$src" "$dst" && docker push "$dst" \
            || { echo "  [실패] docker pull/tag/push" >&2; rc=1; continue; }
        digest="$(docker inspect --format='{{index .RepoDigests 0}}' "$dst" 2>/dev/null | sed -E 's/.*@//' || true)"
    else
        echo "  [실패] crane/docker 둘 다 없음 — 승격 도구 필요." >&2
        rc=1; continue
    fi

    if [[ -z "${digest:-}" ]]; then
        echo "  [실패] AR digest 산출 실패(승격은 됐을 수 있음 — 수동 확인)." >&2
        rc=1; continue
    fi
    ar_ref="${AR_LOCATION}/${PROJECT_SHOWN}/${AR_REPO}/${svc}"
    echo "  AR digest: ${digest}"
    # gke overlay 를 mutable latest 가 아니라 immutable digest 로 고정(GP-2 work #4 · L-016a).
    # operator-local 로 실행(PROJECT_ID 치환과 동형) — 커밋하지 않는다.
    echo "  digest 고정: (cd k8s/overlays/gke && kustomize edit set image ghcr.io/${OWNER}/peekcart-${svc}=${ar_ref}@${digest})"
done

echo "---"
if [[ "$rc" -ne 0 ]]; then
    echo "[D-016] 일부 서비스 승격 실패 — 위 로그 확인." >&2
    exit 1
fi
cat <<'NOTE'
[D-016] 완료. 위 'digest 고정' 명령으로 gke overlay images[] 를 AR digest 로 pin 하면
  mutable latest 를 벗어나 CI 가 검증한 immutable 이미지가 배포된다(L-016a 재현성).
  (committed manifest 는 newTag: latest 유지 — 실 digest 는 빌드 후에만 존재, operator-local pin.
   image-contract-lint 의 digest 강제(렌더 산출에 @sha256 필수)는 후속 — 현재는 ci↔base↔gke ref 일치만 검증.)
NOTE
