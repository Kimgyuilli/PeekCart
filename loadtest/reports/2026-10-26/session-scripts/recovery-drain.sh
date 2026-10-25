#!/usr/bin/env bash
# D-108 세션 복구 배수 감시. MySQL 볼륨 확장(20:30:33Z) 뒤 멈췄던 c2 재시도 run 의 배수를 d078-s3-run.sh 와 같은 질의로 본다.
# outbox PENDING(order·product) 0 그리고 전 group lag 0 이면 끝, LIMIT 초를 넘으면 exit 3.
#   OUT=loadtest/reports/2026-10-26/s3/c0/invalid/contend-retry2/recovery-drain.tsv bash loadtest/reports/2026-10-26/session-scripts/recovery-drain.sh
set -uo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart
: "${OUT:?}"
LIMIT="${LIMIT:-1200}"
NS=peekcart
q() { local db="$1"; shift; kubectl -n "$NS" exec -i deploy/mysql -- mysql -N -B -u"$db" -p"$db" "$db" -e "$*" 2>/dev/null; }
lag_total() {
  kubectl -n "$NS" exec deploy/kafka -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 \
      --describe --all-groups 2>/dev/null \
    | awk '$1 != "GROUP" && NF >= 6 && $3 ~ /^[0-9]+$/ {
             if ($5 ~ /^[0-9]+$/) { if ($4 ~ /^[0-9]+$/) l += $5 - $4; else l += $5 }
           } END { print l + 0 }'
}
start=$(date +%s)
printf 'epoch\torder_pending\tproduct_pending\tlag\n' > "$OUT"
while :; do
  now=$(date +%s)
  o=$(q peekcart_order "SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'")
  p=$(q peekcart_product "SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'")
  l=-
  if [ "$o" = "0" ] && [ "$p" = "0" ]; then
    l=$(lag_total)
  fi
  printf '%s\t%s\t%s\t%s\n' "$now" "${o:-?}" "${p:-?}" "$l" >> "$OUT"
  if [ "$l" = "0" ]; then echo "drain done $((now - start))s $(date -u +%FT%TZ)"; exit 0; fi
  if [ $((now - start)) -gt "$LIMIT" ]; then echo "drain limit ${LIMIT}s exceeded $(date -u +%FT%TZ)"; exit 3; fi
  sleep 5
done
