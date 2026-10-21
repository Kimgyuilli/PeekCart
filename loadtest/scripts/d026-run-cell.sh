#!/usr/bin/env bash
# loadtest/scripts/d026-run-cell.sh
#
# D-026 배속 재측정 + D-002a 읽기 경로 천장 — **셀 1회 실행 프로토콜**.
# 계획서 `docs/plans/done/task-d026-d002a-read-ceiling-session.md` P4/P5.
#
# 왜 스크립트인가: 이 세션은 셀당 3회 × 10셀 = **30런**이고, 런마다
#   ① 조건 캡처(Pod UID·effective CPU limit·cache env·rollout)
#   ② 캐시 초기화 + 결정적 워밍업(ON 조건)
#   ③ counter **차분**(cache_gets_total, cpu.stat throttled_usec)
# 를 요구한다. 손으로 30번 하면 빠뜨린다 — 그리고 빠뜨린 줄 모르는 것이
# false-green 이다(계획 리뷰 라운드 1 지적 #4·#5·#7).
#
# 사용법:
#   EP=detail CACHE=on MYSQL=500m REP=1 bash loadtest/scripts/d026-run-cell.sh
#
# 환경변수:
#   EP      detail | list
#   CACHE   on | off      (측정 전 Deployment env 를 이 값으로 맞춘다)
#   MYSQL   500m | 2000m  (기록용 라벨 — 실제 전환은 overlay apply 로 한다)
#   REP     반복 회차 (1..3)
#   VUS     기본 400   DUR 기본 60s   IDS 기본 100
#   OUTDIR  기본 .cache/d026-runs

set -uo pipefail

command -v python3 >/dev/null 2>&1 || { echo "이름별 counter 검증에 python3 필요" >&2; exit 1; }

NS=peekcart
EP="${EP:?EP=detail|list 필요}"
CACHE="${CACHE:?CACHE=on|off 필요}"
MYSQL="${MYSQL:?MYSQL=500m|2000m 필요}"
REP="${REP:-1}"
VUS="${VUS:-400}"
DUR="${DUR:-60s}"
IDS="${IDS:-100}"
OUTDIR="${OUTDIR:-.cache/d026-runs}"
LOADGEN="${LOADGEN:-peekcart-loadgen}"
ZONE="${ZONE:-asia-northeast3-a}"

CELL="mysql${MYSQL}-${EP}-cache${CACHE}-r${REP}"
mkdir -p "$OUTDIR"
META="$OUTDIR/${CELL}.meta"
: > "$META"

say() { echo "[$CELL] $*"; }
meta() { printf '%s\t%s\n' "$1" "$2" >> "$META"; }

ssh_loadgen() {
  gcloud compute ssh "$LOADGEN" --zone "$ZONE" --tunnel-through-iap --quiet --command "$1" 2>/dev/null
}

# ── 1) 캐시 토글을 조건에 맞춘다 ───────────────────────────────────────────────
WANT=$([ "$CACHE" = "on" ] && echo true || echo false)
CUR=$(kubectl -n "$NS" get deploy product-service \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PEEKCART_CACHE_ENABLED")].value}')
if [ "$CUR" != "$WANT" ]; then
  say "cache env $CUR -> $WANT (재기동 동반)"
  kubectl -n "$NS" set env deploy/product-service "PEEKCART_CACHE_ENABLED=$WANT" >/dev/null
fi
kubectl -n "$NS" rollout status deploy/product-service --timeout=300s >/dev/null || {
  say "!! rollout 실패"; exit 1; }

# `rollout status` 는 **새 Pod 가 Ready** 가 되면 돌아온다 — 구 Pod 가 아직 Terminating
# 일 수 있다. 그 상태에서 `items[0]` 을 집으면 **곧 사라질 Pod** 를 측정 대상으로 잡고,
# 런 도중 그것이 죽어 "런 중 재시작" 으로 오판돼 셀이 통째로 폐기된다.
# (블록 A 에서 list:on 3런이 전부 이렇게 날아갔다 — 대조군이라 N4 가 닫히지 않는다.)
# 그래서 **Pod 가 정확히 1개로 수렴할 때까지** 기다린 뒤 고른다.
for _ in $(seq 1 60); do
  N=$(kubectl -n "$NS" get pod -l app=product-service --no-headers 2>/dev/null | wc -l | tr -d ' ')
  [ "$N" = "1" ] && break
  sleep 3
done
kubectl -n "$NS" wait --for=condition=Ready pod -l app=product-service --timeout=180s >/dev/null 2>&1

# ── 2) 조건 캡처 — "무엇을 쟀는지" 를 런마다 고정한다 ──────────────────────────
POD=$(kubectl -n "$NS" get pod -l app=product-service \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
meta pod "$POD"
meta pod_uid   "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.metadata.uid}')"
meta pod_start "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.startTime}')"
# imageID = digest. 계획서 N3 의 4자 대조 중 마지막 한 자리다.
meta image_id  "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].imageID}')"
meta product_cpu_limit "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.spec.containers[0].resources.limits.cpu}')"
meta cache_env "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.spec.containers[0].env[?(@.name=="PEEKCART_CACHE_ENABLED")].value}')"
MYPOD=$(kubectl -n "$NS" get pod -l app=mysql -o jsonpath='{.items[0].metadata.name}')
meta mysql_pod "$MYPOD"
meta mysql_cpu_limit "$(kubectl -n "$NS" get pod "$MYPOD" -o jsonpath='{.spec.containers[0].resources.limits.cpu}')"
meta ep "$EP"; meta vus "$VUS"; meta dur "$DUR"; meta ids "$IDS"; meta rep "$REP"
meta block "$MYSQL"   # 블록 라벨(A/B/A2) — 집계가 셀을 묶는 키

TARGET_IP=$(kubectl -n "$NS" get svc product-service-measure \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
[ -n "$TARGET_IP" ] || { say "!! Internal LB IP 없음"; exit 1; }
meta target "$TARGET_IP"

# ── 3) 캐시 초기화 + 결정적 워밍업 ────────────────────────────────────────────
# Redis 는 PVC 를 쓴다 → 재배포로는 비워진다고 보장할 수 없다(계획서 V15).
# 초기화는 **명시적 명령 하나**로 하고 전후 key count 를 남긴다.
rediscli() { kubectl -n "$NS" exec deploy/redis -- redis-cli "$@" 2>/dev/null; }
meta redis_keys_before "$(rediscli DBSIZE | tr -d '\r')"
rediscli FLUSHALL >/dev/null
meta redis_keys_after_flush "$(rediscli DBSIZE | tr -d '\r')"

if [ "$CACHE" = "on" ]; then
  # 워밍업 트래픽은 측정 구간 밖이다. detail 100키 + list 1키를 결정적으로 채운다.
  say "워밍업 (detail 1..$IDS + list)"
  ssh_loadgen "
    for i in \$(seq 1 $IDS); do curl -s -o /dev/null http://$TARGET_IP:8080/api/v1/products/\$i; done
    curl -s -o /dev/null 'http://$TARGET_IP:8080/api/v1/products?page=0&size=20'
  " >/dev/null
  sleep 3   # 안정화
  meta redis_keys_after_warmup "$(rediscli DBSIZE | tr -d '\r')"
fi

# ── 4) counter 사전 스냅샷 (절대값 아니라 **차분**을 쓴다 — 지적 #5) ──────────
prom() { kubectl -n "$NS" exec "$POD" -- curl -s localhost:8080/actuator/prometheus 2>/dev/null; }
# 캐시 **이름별**로 나눈다. product(상세)·products(목록)·productStock(재고)를 합산하면
# D-026 이 만든 재고 캐시의 miss 율을 못 본다 — 이 세션이 물어보는 바로 그 숫자다.
cachegets() {
  local snapshot
  snapshot=$(prom) || { printf 'unavailable\n'; return; }
  # Decimal 검증 전에 awk의 부동소수 반올림을 거치지 않는다.
  printf '%s\n' "$snapshot" | python3 -c '
import re
import sys
from decimal import Decimal, InvalidOperation

name, result = sys.argv[1:]
maximum = Decimal(str(sys.float_info.max))
count = total = 0
try:
    for line in sys.stdin:
        if not line.startswith("cache_gets_total{"): continue
        labels, separator, sample = line.partition("}")
        if not re.search(r"(?:[{,])name=\"" + re.escape(name) + r"\"(?:,|$)", labels): continue
        if not re.search(r"(?:[{,])result=\"" + re.escape(result) + r"\"(?:,|$)", labels): continue
        fields = sample.split()
        if not separator or len(fields) != 1: raise ValueError("sample")
        raw = fields[0]
        if not re.fullmatch(r"[+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", raw):
            raise ValueError("number")
        value = Decimal(raw)
        if not value.is_finite() or value > maximum or value != value.to_integral_value():
            raise ValueError("counter")
        count += 1
        total += int(value)
        if total > maximum: raise ValueError("sum")
    if not count: raise ValueError("missing")
    print(total)
except (InvalidOperation, ValueError, OverflowError):
    print("unavailable")
' "$2" "$1"
}
throttled() { kubectl -n "$NS" exec "$1" -- cat /sys/fs/cgroup/cpu.stat 2>/dev/null \
                | awk '/throttled_usec/{print $2}'; }
usage_usec() { kubectl -n "$NS" exec "$1" -- cat /sys/fs/cgroup/cpu.stat 2>/dev/null \
                | awk '/^usage_usec/{print $2}'; }

for C in product products productStock; do
  meta "hit_before_${C}"  "$(cachegets hit  "$C")"
  meta "miss_before_${C}" "$(cachegets miss "$C")"
done
meta hit_before  "$(prom | grep '^cache_gets_total' | grep 'result="hit"'  | awk '{s+=$NF} END{print s+0}')"
meta miss_before "$(prom | grep '^cache_gets_total' | grep 'result="miss"' | awk '{s+=$NF} END{print s+0}')"
meta product_throttled_before "$(throttled "$POD")"
meta product_usage_before     "$(usage_usec "$POD")"
meta mysql_throttled_before   "$(throttled "$MYPOD")"
meta mysql_usage_before       "$(usage_usec "$MYPOD")"

# ── 5) 부하 ───────────────────────────────────────────────────────────────────
say "k6 run EP=$EP VUS=$VUS DUR=$DUR"
ssh_loadgen "TARGET=http://$TARGET_IP:8080 EP=$EP VUS=$VUS DUR=$DUR IDS=$IDS \
  k6 run --summary-mode compact --summary-export=/tmp/${CELL}.json /tmp/d002a-product-read.js" \
  > "$OUTDIR/${CELL}.k6.log" 2>&1
gcloud compute scp "$LOADGEN:/tmp/${CELL}.json" "$OUTDIR/${CELL}.json" \
  --zone "$ZONE" --tunnel-through-iap --quiet >/dev/null 2>&1

# ── 6) counter 사후 스냅샷 ────────────────────────────────────────────────────
for C in product products productStock; do
  meta "hit_after_${C}"  "$(cachegets hit  "$C")"
  meta "miss_after_${C}" "$(cachegets miss "$C")"
done
meta hit_after  "$(prom | grep '^cache_gets_total' | grep 'result="hit"'  | awk '{s+=$NF} END{print s+0}')"
meta miss_after "$(prom | grep '^cache_gets_total' | grep 'result="miss"' | awk '{s+=$NF} END{print s+0}')"
meta product_throttled_after "$(throttled "$POD")"
meta product_usage_after     "$(usage_usec "$POD")"
meta mysql_throttled_after   "$(throttled "$MYPOD")"
meta mysql_usage_after       "$(usage_usec "$MYPOD")"
# Pod 가 도중에 재시작하면 counter 가 리셋된다 — 차분이 음수면 그 런은 버린다.
meta pod_uid_after "$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.metadata.uid}' 2>/dev/null)"

say "완료 → $OUTDIR/${CELL}.{json,meta,k6.log}"
