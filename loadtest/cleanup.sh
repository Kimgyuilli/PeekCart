#!/usr/bin/env bash
# loadtest/cleanup.sh
#
# Phase 3 Task 3-4 부하 테스트 정리 (ADR-0004 운영 체크리스트).
# 측정 세션 종료 시 반드시 실행 — 클러스터/VM/PD/예약 IP 를 모두 회수한다.
#
# 사용법:
#   bash loadtest/cleanup.sh                 # 실제 삭제 실행
#   bash loadtest/cleanup.sh --dry-run       # 상태만 확인 (삭제 없음)
#
# 환경변수 override:
#   CLUSTER_NAME   기본 peekcart-loadtest
#   LOADGEN_NAME   기본 peekcart-loadgen
#   ZONE           기본 asia-northeast3-a
#   REGION         기본 asia-northeast3
#
# 주의:
# - 삭제 실패는 일부 단계만 실행될 수 있으므로 스크립트 종료 후 `disks list` /
#   `addresses list` 출력을 반드시 육안 확인한다.
# - 본 스크립트는 billing alert 를 설정/해제하지 않는다. 콘솔에서 수동 확인.

set -uo pipefail

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
fi

CLUSTER_NAME="${CLUSTER_NAME:-peekcart-loadtest}"
LOADGEN_NAME="${LOADGEN_NAME:-peekcart-loadgen}"
ZONE="${ZONE:-asia-northeast3-a}"
REGION="${REGION:-asia-northeast3}"

run() {
  if $DRY_RUN; then
    echo "[dry-run] $*"
  else
    echo "[exec] $*"
    "$@" || echo "  -> exit $? (계속 진행)"
  fi
}

echo "=== Phase 3 Task 3-4 정리 시작 ($(date)) ==="
echo "cluster=$CLUSTER_NAME  loadgen=$LOADGEN_NAME  zone=$ZONE  region=$REGION"
echo

echo "--- 1) GKE 클러스터 삭제 ---"
run gcloud container clusters delete "$CLUSTER_NAME" --zone="$ZONE" --quiet

echo
echo "--- 2) 부하 발생기 VM 삭제 ---"
run gcloud compute instances delete "$LOADGEN_NAME" --zone="$ZONE" --quiet

echo
echo "--- 3) Orphan Persistent Disk 확인 ---"
echo "아래 목록에 남아있는 disk 가 있으면 수동 삭제 필요:"
run gcloud compute disks list --filter="zone:($ZONE)"

echo
echo "--- 4) 예약 IP (reserved addresses) 확인 ---"
echo "아래 목록에 남아있는 IP 가 있으면 수동 release 필요:"
run gcloud compute addresses list --filter="region:($REGION) OR global"

echo
echo "=== 정리 완료. billing 콘솔에서 당일/익일 과금을 반드시 재확인 ==="
