#!/usr/bin/env bash
# D-108 세션: payment-svc-stock-result-group 의 고아 레코드 적체가 빠질 때까지 30초마다 lag 을 적는다.
# describe 출력이 비거나 파티션 줄이 없으면 0 으로 치지 않고 '?' 로 적는다(빈 출력 = 0 오판 방지).
#   OUT=loadtest/reports/2026-10-26/s3/c0/payment-lag-wait.tsv bash loadtest/reports/2026-10-26/session-scripts/wait-payment-lag.sh
set -uo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart
: "${OUT:?}"
LIMIT="${LIMIT:-5400}"
GROUP=payment-svc-stock-result-group
start=$(date +%s)
printf 'epoch\tlag\tpartitions\n' > "$OUT"
while :; do
  now=$(date +%s)
  read -r lag parts < <(kubectl -n peekcart exec deploy/kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:29092 --describe --group "$GROUP" 2>/dev/null \
    | awk '$1 != "GROUP" && NF >= 6 && $3 ~ /^[0-9]+$/ {
             n++; if ($5 ~ /^[0-9]+$/) { if ($4 ~ /^[0-9]+$/) l += $5 - $4; else l += $5 }
           } END { if (n) print l + 0, n; else print "?", 0 }')
  printf '%s\t%s\t%s\n' "$now" "$lag" "$parts" >> "$OUT"
  if [ "$lag" = "0" ] && [ "$parts" -gt 0 ]; then echo "payment lag 0 after $((now - start))s $(date -u +%FT%TZ)"; exit 0; fi
  if [ $((now - start)) -gt "$LIMIT" ]; then echo "limit ${LIMIT}s exceeded, last lag ${lag} $(date -u +%FT%TZ)"; exit 3; fi
  sleep 30
done
