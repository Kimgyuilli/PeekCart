#!/usr/bin/env bash
# D-078 재측정 세션용 치환 (커밋하지 않는다. S5 에서 git restore).
#   프로젝트 자리표시를 peekcart-d078 로, overlay 이미지를 AR digest 로 고정한다.
set -euo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart

P=peekcart-d078
AR="asia-northeast3-docker.pkg.dev/${P}/peekcart"

digest() {
  case "$1" in
    gateway)              echo sha256:ac1929491bbe823c752c106c4467abe0c63ff44385048e2f03aaa2ccec3b226e ;;
    user-service)         echo sha256:04118de5fedf6e19b97c5c384b6c19d2a5d1f831fdd5427b343e54231b4fc605 ;;
    product-service)      echo sha256:60175d296688b02dc7f23dad821bacfd3c7f5309b09b18be4914907046ef17ad ;;
    order-service)        echo sha256:c8193a34eb86a6233136928c97de3e595358803aae709111350b1c211c3f1470 ;;
    payment-service)      echo sha256:b33b016141b55fc7cec28e2e5f64e3375f0a515e293ddcdd43be68d46bc46e40 ;;
    notification-service) echo sha256:9d7dc6612079c340e478dd0e9fdbbb1a74c0326f679b169fd7357b32455dd52d ;;
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
