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
echo "--- 3) Orphan Persistent Disk 회수 ---"
# 이 단계는 원래 `disks list` 출력만 하고 "남아있으면 수동 삭제 필요" 로 넘겼다.
# 그래서 실제로 남았다 — D-002a 세션(2026-09-16)의 PVC 3개가 회수되지 않은 채 발견됐고,
# 그 세션의 "과금 0 확인" 은 완결되지 않은 상태였다. 사람이 육안으로 확인하는 단계는
# 스킵되며, 스킵돼도 스크립트는 성공으로 끝난다. 그래서 회수까지 하도록 바꾼다.
#
# 붙어있는(users 가 있는) disk 는 건드리지 않는다 — 삭제 대상은 **미부착** disk 뿐이다.
ORPHAN_DISKS=$(gcloud compute disks list \
  --filter="zone:($ZONE) AND -users:*" --format="value(name)" 2>/dev/null)
if [[ -z "$ORPHAN_DISKS" ]]; then
  echo "미부착 disk 없음"
else
  echo "미부착 disk:"; echo "$ORPHAN_DISKS" | sed 's/^/  /'
  while read -r d; do
    [[ -n "$d" ]] && run gcloud compute disks delete "$d" --zone="$ZONE" --quiet
  done <<< "$ORPHAN_DISKS"
fi

echo
echo "--- 4) 예약 IP (reserved addresses) 회수 ---"
# 마찬가지로 목록만 찍던 단계다. IN_USE 는 건드리지 않고 RESERVED(미사용 예약)만 release 한다.
ORPHAN_IPS=$(gcloud compute addresses list \
  --filter="region:($REGION) AND status=RESERVED" --format="value(name)" 2>/dev/null)
if [[ -z "$ORPHAN_IPS" ]]; then
  echo "미사용 예약 IP 없음"
else
  echo "미사용 예약 IP:"; echo "$ORPHAN_IPS" | sed 's/^/  /'
  while read -r ip; do
    [[ -n "$ip" ]] && run gcloud compute addresses delete "$ip" --region="$REGION" --quiet
  done <<< "$ORPHAN_IPS"
fi

echo
echo "--- 5) 잔여 0 검증 ---"
# "정리했다" 와 "남은 게 없다" 는 다르다. 삭제가 일부만 성공해도 위 단계들은 계속 진행하므로
# (run 이 실패를 삼킨다) 마지막에 **상태로** 확인한다. 남아있으면 exit 1 — 측정 세션의
# 완료 조건(계획서 P12 "과금 0 확인")을 사람 눈이 아니라 종료 코드가 보장하게 한다.
#
# forwarding-rules / backend-services / health-checks 추가 (D-026 세션, 2026-09-20):
# 측정 overlay 가 Internal LB Service 를 띄운다(`measurement-lb.yml`). 그 Service 는
# GKE 가 대신 만드는 **forwarding rule + backend service + health check** 를 동반하고,
# 이들은 클러스터 삭제 시 대개 함께 지워지지만 **항상은 아니다**. disks 가 남았던 것과
# 같은 계열의 누수라 같은 방식(상태로 확인, 남으면 exit 1)으로 닫는다.
if $DRY_RUN; then
  echo "[dry-run] 삭제를 수행하지 않았으므로 잔여 검증을 건너뛴다"
else
  LEFT=0
  for q in \
    "clusters|gcloud container clusters list --filter=name:($CLUSTER_NAME) --format=value(name)" \
    "instances|gcloud compute instances list --filter=name:($LOADGEN_NAME) --format=value(name)" \
    "disks|gcloud compute disks list --filter=zone:($ZONE) --format=value(name)" \
    "addresses|gcloud compute addresses list --filter=region:($REGION) --format=value(name)" \
    "forwarding-rules|gcloud compute forwarding-rules list --filter=region:($REGION) --format=value(name)" \
    "backend-services|gcloud compute backend-services list --filter=region:($REGION) --format=value(name)" \
    "health-checks|gcloud compute health-checks list --format=value(name)"; do
    label="${q%%|*}"; cmd="${q#*|}"
    out=$($cmd 2>/dev/null)
    if [[ -n "$out" ]]; then
      echo "  [남음] $label:"; echo "$out" | sed 's/^/    /'
      LEFT=$((LEFT + 1))
    else
      echo "  [0] $label"
    fi
  done
  if (( LEFT > 0 )); then
    echo
    echo "!! 잔여 자원 ${LEFT}종 — 과금이 계속된다. 위 목록을 수동 확인하고 재실행하라."
    exit 1
  fi
fi

echo
echo "=== 정리 완료 ==="
echo
# 과금 재확인 — 잔여 0 은 필요조건이지 충분조건이 아니다.
# D-002a 증적의 "과금 0 확인" 이 사실과 달랐고 PVC 3개가 남아 있었다.
#
# 2026-09-22 부터 BigQuery 결제 내보내기가 켜져 있다
# (결제 계정 010664-339753-DC57A6 → peekcart-gke:billing_export, 표준 사용량 비용).
# 적재는 하루 단위라 **세션 종료 직후에는 항상 0행**이다. 게이트로 쓸 수 없고,
# 다음 세션 착수 시 직전 세션 과금을 확인하는 용도다.
#
#   bq query --project_id=peekcart-gke --use_legacy_sql=false '
#   SELECT service.description AS service, ROUND(SUM(cost),2) AS cost,
#          ANY_VALUE(currency) AS cur
#   FROM `peekcart-gke.billing_export.gcp_billing_export_v1_010664_339753_DC57A6`
#   WHERE DATE(usage_start_time) = "YYYY-MM-DD" AND project.id = "peekcart-gke"
#   GROUP BY 1 HAVING cost > 0 ORDER BY 2 DESC'
#
# 테이블 이름은 첫 적재 후 `bq ls billing_export` 로 확인한다(위는 규칙에 따른 예상값).
# 콘솔 리포트: https://console.cloud.google.com/billing/010664-339753-DC57A6/reports
echo "과금 재확인: 내보내기 적재 후 위 주석의 bq 쿼리 또는 콘솔 리포트로 직전 세션 비용을 본다."
