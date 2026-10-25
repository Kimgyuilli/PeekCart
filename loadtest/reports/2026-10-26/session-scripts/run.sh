#!/usr/bin/env bash
# D-108 세션 run 래퍼. d078-s3-run.sh 앞뒤로 order·product 재시작 수를 남기고(§3.1 수렴 조건 "그 사이 재시작 없음"),
# SAMPLE_DELAY 를 주면(판정 run) d078-mysql-sampler.sh 를 run 과 함께 백그라운드로 돌린다(§3.2).
#   CONFIG=c0 RUN=w2 PREV_RUN=w1 MODE=contend bash loadtest/reports/2026-10-26/session-scripts/run.sh
#   CONFIG=c0 RUN=c1 MODE=contend SAMPLE_DELAY=125 bash loadtest/reports/2026-10-26/session-scripts/run.sh
# USERS·RPS·DUR·PREV_RUN 은 환경으로 d078-s3-run.sh 에 그대로 넘어간다.
set -uo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart
: "${CONFIG:?}" "${RUN:?}" "${MODE:?}"
export SESSION_DIR=loadtest/reports/2026-10-26 GW_IP=10.178.0.28
OUT="$SESSION_DIR/s3/$CONFIG"
case "$RUN" in w*) OUT="$OUT/warmup" ;; esac
mkdir -p "$OUT"
restarts() {
  kubectl -n peekcart get pod -l 'app in (order-service,product-service)' \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].restartCount}{"\n"}{end}'
}
{ echo "## before $(date -u +%FT%TZ)"; restarts; } > "$OUT/$RUN-restarts.txt"
if [ -n "${SAMPLE_DELAY:-}" ]; then
  OUT="$OUT/$RUN-mysql-sample.txt" DELAY="$SAMPLE_DELAY" bash loadtest/scripts/d078-mysql-sampler.sh > /dev/null 2>&1 &
fi
CONFIG="$CONFIG" RUN="$RUN" MODE="$MODE" bash loadtest/scripts/d078-s3-run.sh
rc=$?
wait
{ echo "## after $(date -u +%FT%TZ)"; restarts; } >> "$OUT/$RUN-restarts.txt"
exit $rc
