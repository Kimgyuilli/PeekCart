#!/usr/bin/env bash
# D-108 마지막 재측정 세션용 치환 (2026-10-25 세션 사본에서 digest 만 바꿨다) (커밋하지 않는다. S5 에서 git restore).
#   프로젝트 자리표시를 peekcart-d078 로, overlay 이미지를 AR digest 로 고정한다.
set -euo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart

P=peekcart-d078
AR="asia-northeast3-docker.pkg.dev/${P}/peekcart"

digest() {
  case "$1" in
    gateway)              echo sha256:de50d4cb5744a36695afe3effe5ab18da74a9973668af0da26042b1d2496154a ;;
    user-service)         echo sha256:474a9b6d359b5fb7f6c5abfe3a87c4801fc247b7812cad40b954ed89d2e01b63 ;;
    product-service)      echo sha256:1999651905f9e26abc732ab542b745db918d50c97a92eafe9930b1df5460be46 ;;
    order-service)        echo sha256:8be032df074f3716835cb620724c72be4ade9cfd224734834f68d6611ccf2bb2 ;;
    payment-service)      echo sha256:92976c941fb20a91856184a83d7173c410ef88a375f80f6aee7c80f293263634 ;;
    notification-service) echo sha256:72f2180c0f6c414b9305b8a41e0984b015ff656ec994192a1e44dbdc6c2f9bb9 ;;
    *) echo "unknown service $1" >&2; exit 1 ;;
  esac
}

# 1) 프로젝트 자리표시 (이미지 경로 외에 SPC resourceName, WI 주석)
for f in k8s/overlays/gke/kustomization.yml k8s/overlays/gke-d002bc/kustomization.yml \
         k8s/base/services/gateway/secretproviderclass.yml k8s/base/services/user-service/secretproviderclass.yml \
         k8s/base/services/gateway/serviceaccount.yml k8s/base/services/user-service/serviceaccount.yml; do
  sed -i '' "s/PROJECT_ID_PLACEHOLDER/${P}/g" "$f"
done

# 2) 이미지 digest 고정
pin() {
  local overlay="$1"; shift
  for svc in "$@"; do
    (cd "k8s/overlays/${overlay}" && kustomize edit set image \
      "ghcr.io/kimgyuilli/peekcart-${svc}=${AR}/${svc}@$(digest "$svc")")
  done
}
pin gke notification-service user-service product-service order-service payment-service gateway
pin gke-d002bc gateway user-service product-service order-service payment-service
pin gke-d002a product-service

echo "치환 완료. 변경 파일:"
git status --short k8s/
