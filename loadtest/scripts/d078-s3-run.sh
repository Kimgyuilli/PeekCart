#!/usr/bin/env bash
# D-078·D-108 재측정 S3 run 1개 (docs/plans/D-108.md §3·§5). 무효 판정·재시도·설정 전환은 run 이 끝난 뒤
# 계획서의 사전 규칙대로 정한다. 2026-10-25 세션 스크립트(loadtest/reports/2026-10-25/session-scripts/s3-run.sh)를
# 옮기고 산출 경로·USERS·RPS·DUR 을 인자로 받게 했다.
#
#   SESSION_DIR=loadtest/reports/<날짜> CONFIG=c0 RUN=w2 PREV_RUN=w1 MODE=contend GW_IP=10.x.x.x bash loadtest/scripts/d078-s3-run.sh
#
# 필수: SESSION_DIR(저장소 기준 상대 경로 또는 절대 경로), CONFIG(c0·c1·c2·f), RUN, MODE(contend·spread), GW_IP
# 선택: USERS(기본 450), RPS(기본 170), DUR(기본 60s),
#       PREV_RUN(같은 디렉터리의 직전 워밍업 run. 주면 d078-warmup-check.py 의 수렴 판정을 찍는다),
#       DRY_RUN=1(산출 경로와 k6 명령만 찍고 아무것도 하지 않는다)
#
# 순서: product·order Pod 원값(전) -> k6 (loadgen) -> 배수(outbox PENDING 0 그리고 전 group lag 0, 2초 간격)
#       -> 원값(후)·차분 -> 지연 원자료 TSV -> 건수·정합성 -> 초기화 -> annotate.
# RUN 이 w 로 시작하면(워밍업) 같은 절차를 돌되 산출물을 warmup/ 에 두고 분석에 쓰지 않는다.
# 파일 이름과 형식은 2026-10-24·10-25 S3 원자료와 같다(drain.tsv 는 머리줄을 더했다).
set -uo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
: "${SESSION_DIR:?}" "${CONFIG:?}" "${RUN:?}" "${MODE:?}" "${GW_IP:?}"
USERS="${USERS:-450}"
RPS="${RPS:-170}"
DUR="${DUR:-60s}"
NS=peekcart
LOADGEN=peekcart-d078-loadgen
ZONE=asia-northeast3-a
DRAIN_LIMIT=900   # 지난 계획 §5: 한 run 의 배수가 15분을 넘으면 S3 를 접는다
case "$SESSION_DIR" in /*) BASE="$SESSION_DIR" ;; *) BASE="$ROOT/$SESSION_DIR" ;; esac
OUT="$BASE/s3/$CONFIG"
case "$RUN" in w*) OUT="$OUT/warmup" ;; esac
P="$OUT/$RUN"
REMOTE_SUMMARY="/tmp/${CONFIG}-${RUN}.json"
K6_CMD="cd /tmp && k6 run --summary-export=${REMOTE_SUMMARY} -e GW=http://${GW_IP}:8080 -e PRODUCT_IDS=\"\$(cat /tmp/product-ids.txt)\" -e USERS=${USERS} -e RPS=${RPS} -e DUR=${DUR} -e MODE=${MODE} /tmp/d002bc-contention.js"
if [ "${DRY_RUN:-0}" = 1 ]; then
  printf 'OUT=%s\n%s\n' "$P" "$K6_CMD"
  exit 0
fi
mkdir -p "$OUT"
GRAFANA_URL=http://localhost:3000
GRAFANA_AUTH=admin:admin

ssh_lg() { gcloud compute ssh "$LOADGEN" --zone "$ZONE" --tunnel-through-iap --quiet --command "$1"; }
q() { local db="$1"; shift; kubectl -n "$NS" exec -i deploy/mysql -- mysql -N -B -u"$db" -p"$db" "$db" -e "$*" 2>/dev/null; }
sql_file() { local db="$1" f="$2" flags="${3:-}"; kubectl -n "$NS" exec -i deploy/mysql -- mysql $flags -u"$db" -p"$db" "$db" < "$f" 2>/dev/null; }

prom_snapshot() {
  : > "$1"
  for svc in product-service order-service; do
    for pod in $(kubectl -n "$NS" get pod -l "app=$svc" --field-selector=status.phase=Running -o jsonpath='{.items[*].metadata.name}'); do
      uid=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.metadata.uid}')
      kubectl -n "$NS" exec "$pod" -- curl -s localhost:8080/actuator/prometheus 2>/dev/null \
        | grep -E '^(kafka_listener_delivery_failed_total|kafka_listener_recovered_total|saga_reservation_result_total|executor_active_threads|executor_queued_tasks|executor_pool_size_threads)\{' \
        | awk -v s="$svc" -v p="$pod" -v u="$uid" '{print s "\t" p "\t" u "\t" $0}' >> "$1"
    done
  done
}

lag_total() {
  kubectl -n "$NS" exec deploy/kafka -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 \
      --describe --all-groups 2>/dev/null \
    | awk '$1 != "GROUP" && NF >= 6 && $3 ~ /^[0-9]+$/ {
             if ($5 ~ /^[0-9]+$/) { if ($4 ~ /^[0-9]+$/) l += $5 - $4; else l += $5 }
           } END { print l + 0 }'
}

echo "=== S3 $CONFIG $RUN ($MODE, USERS=$USERS RPS=$RPS DUR=$DUR) 시작 $(date -u +%FT%TZ) ==="
prom_snapshot "$P-prom-before.tsv"

t0=$(date +%s)
ssh_lg "$K6_CMD" > "$P.k6.log" 2>&1
rc=$?
t1=$(date +%s)
gcloud compute scp "$LOADGEN:${REMOTE_SUMMARY}" "$P.json" --zone "$ZONE" --tunnel-through-iap --quiet >/dev/null 2>&1 \
  || echo "  !! 요약 JSON 회수 실패"
verdict=$(grep -m1 -E '부하 판정' "$P.k6.log" | sed -E 's/^[[:space:]]+//')
echo "  k6 exit=$rc  ${verdict:-부하 판정 줄 없음}"
if [ -n "${PREV_RUN:-}" ]; then
  python3 loadtest/scripts/d078-warmup-check.py "$OUT/${PREV_RUN}.json" "$P.json" | tee "$P-warmup.txt"
fi

printf 'epoch\torder_pending\tproduct_pending\n' > "$P-drain.tsv"
t_outbox0=""; t_full=""; aborted=""
while :; do
  now=$(date +%s)
  o=$(q peekcart_order "SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'")
  p=$(q peekcart_product "SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'")
  printf '%s\t%s\t%s\n' "$now" "${o:-?}" "${p:-?}" >> "$P-drain.tsv"
  if [ "$o" = "0" ] && [ "$p" = "0" ]; then
    [ -z "$t_outbox0" ] && t_outbox0=$now
    if [ "$(lag_total)" = "0" ]; then t_full=$(date +%s); break; fi
  fi
  if [ $((now - t1)) -gt "$DRAIN_LIMIT" ]; then aborted=1; echo "  !! 배수가 ${DRAIN_LIMIT}초를 넘었다 (계획 §5: S3 를 접는다)"; break; fi
  sleep 2
done
printf 't0\t%s\nt1\t%s\nt_outbox0\t%s\nt_full\t%s\nk6_exit\t%s\n' "$t0" "$t1" "${t_outbox0:--}" "${t_full:--}" "$rc" > "$P-times.tsv"
echo "  배수: outbox 0 까지 $(( ${t_outbox0:-$t1} - t1 ))초, lag 0 까지 ${t_full:+$((t_full - t1))초}${aborted:+중단}"

prom_snapshot "$P-prom-after.tsv"
python3 -I - "$P-prom-before.tsv" "$P-prom-after.tsv" > "$P-prom-delta.tsv" <<'PY'
import collections
import sys

def load(path):
    values, uids = collections.defaultdict(float), set()
    for line in open(path, encoding="utf-8"):
        svc, pod, uid, rest = line.rstrip("\n").split("\t", 3)
        metric, value = rest.rsplit(" ", 1)
        uids.add(uid)
        if metric.split("{", 1)[0].endswith("_total"):
            values[(svc, metric)] += float(value)
    return values, uids

before, uids_before = load(sys.argv[1])
after, uids_after = load(sys.argv[2])
if uids_before != uids_after:
    print("# Pod 가 바뀌었다: 차분을 쓰지 않는다")
for key in sorted(set(before) | set(after)):
    delta = after.get(key, 0.0) - before.get(key, 0.0)
    if delta:
        print(f"{delta:g}\t{key[0]}\t{key[1]}")
PY

sql_file peekcart_order loadtest/sql/d078-saga-latency.sql -B > "$P-saga.tsv"
sql_file peekcart_order loadtest/sql/d078-outbox-latency.sql -B > "$P-outbox-order.tsv"
sql_file peekcart_product loadtest/sql/d078-outbox-latency.sql -B > "$P-outbox-product.tsv"
{
  echo "## orders by status";  q peekcart_order "SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status"
  echo "## order outbox";      q peekcart_order "SELECT event_type, status, COUNT(*) FROM outbox_events GROUP BY event_type, status ORDER BY 1, 2"
  echo "## product outbox";    q peekcart_product "SELECT event_type, status, COUNT(*) FROM outbox_events GROUP BY event_type, status ORDER BY 1, 2"
  echo "## dlq order";         q peekcart_order "SELECT COUNT(*) FROM dead_letter_records"
  echo "## dlq product";       q peekcart_product "SELECT COUNT(*) FROM dead_letter_records"
} > "$P-counts.txt"
sql_file peekcart_product loadtest/sql/d002bc-verify.sql > "$P-verify.txt"

{ sql_file peekcart_order loadtest/sql/d002bc-reset.sql; sql_file peekcart_product loadtest/sql/d002bc-product-reset.sql; } > "$P-reset.txt"

GRAFANA_URL="$GRAFANA_URL" GRAFANA_AUTH="$GRAFANA_AUTH" \
  python3 loadtest/scripts/d078-capture.py annotate --label "S3 $CONFIG $RUN $MODE" --from "$t0" --to "$t1" >/dev/null \
  || echo "  !! annotate 실패 (run)"
if [ -n "$t_full" ]; then
  GRAFANA_URL="$GRAFANA_URL" GRAFANA_AUTH="$GRAFANA_AUTH" \
    python3 loadtest/scripts/d078-capture.py annotate --label "S3 $CONFIG $RUN drain" --from "$t1" --to "$t_full" >/dev/null \
    || echo "  !! annotate 실패 (drain)"
fi

echo "--- 건수 ---"; cat "$P-counts.txt"
echo "--- 정합성 ---"; cat "$P-verify.txt"
echo "--- 차분 ---"; cat "$P-prom-delta.tsv"
echo "=== S3 $CONFIG $RUN 끝 $(date -u +%FT%TZ) ==="
[ -n "$aborted" ] && exit 3
exit 0
