#!/usr/bin/env bash
# saga-e2e-smoke — cross-service saga E2E 실행자 (계획 P5~P11 · P16 · P19)
#
# 스택을 띄우고 → readiness 를 확인하고 → 시나리오를 돌리고 → 증적을 남기고 → 내린다.
# 시나리오 본체는 runner 컨테이너 안의 saga_e2e.py 다(internal 네트워크 안에서만 DB·Kafka·앱에 닿는다).
#
# 사용:
#   bash scripts/saga-e2e-smoke.sh                 # 전체 (readiness + 시나리오 4종)
#   bash scripts/saga-e2e-smoke.sh --scenarios a,b # 일부만
#   bash scripts/saga-e2e-smoke.sh --keep          # 실패 조사용으로 스택 유지
#   bash scripts/saga-e2e-smoke.sh --self-test     # 순서 계약 검사만 (스택 불필요)
#   bash scripts/saga-e2e-smoke.sh --negative-control  # 결함 주입 대조군 (계획 P16)
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE_FILE="docker-compose.e2e.yml"
SCENARIOS="a,b,c,d"
KEEP=0

SELF_TEST=0
NEGATIVE_CONTROL=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenarios) SCENARIOS="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --negative-control) NEGATIVE_CONTROL=1; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done

if [[ $SELF_TEST -eq 1 ]]; then
  # 순서 계약 검사가 실제로 위반을 잡는지 — 검사 자체가 vacuous-green 으로 썩는 것을 막는다.
  # 스택을 띄우지 않으므로 CI 의 build 잡에서도 돈다.
  exec python3 - <<'PY_ORDER_SELFTEST'
import sys
sys.path.insert(0, "scripts/e2e")
import saga_e2e as m

fails = 0


def check(name, requested, want_ok):
    global fails
    got_ok = not m.validate_order(list(requested))
    if got_ok == want_ok:
        print("  ok   [%s]" % name)
    else:
        print("  FAIL [%s] 기대 %s / 실제 %s" % (name, want_ok, got_ok))
        fails += 1


print("saga-e2e-smoke 순서 계약 self-test")
check("정본 전체 순서", m.SCENARIO_ORDER, True)
check("부분집합(순서 유지)", ["a", "c"], True)
check("빈 목록", [], True)
check("역순", list(reversed(m.SCENARIO_ORDER)), False)
check("두 개 뒤바뀜", ["b", "a"], False)
check("중복 지정", ["a", "a"], False)
check("모르는 시나리오", ["z"], False)

# 잔여 시나리오가 꼬리를 벗어나면 잡히는가 — LINGERING 이 비어 있어도 규칙은 살아 있어야
# 한다. 임시로 하나 등록해 검사한다(끝나면 정본을 되돌린다).
saved = list(m.LINGERING_SCENARIOS)
try:
    m.LINGERING_SCENARIOS[:] = ["a"]
    check("잔여 시나리오가 선두", ["a", "b"], False)
    m.LINGERING_SCENARIOS[:] = ["d"]
    check("잔여 시나리오가 꼬리", ["a", "d"], True)
finally:
    m.LINGERING_SCENARIOS[:] = saved

if m.LINGERING_SCENARIOS != saved:
    print("  FAIL [정본 복원]")
    fails += 1


# 음성 대조군 판정 규칙(ADR-0031 D1) — 의도한 지점의 타임아웃만 통과다.
def check_control(name, fn, want_ok):
    global fails
    try:
        m.expect_timeout_at("reservation", fn)
        got_ok = True
    except AssertionError:
        got_ok = False
    if got_ok == want_ok:
        print("  ok   [%s]" % name)
    else:
        print("  FAIL [%s] 기대 %s / 실제 %s" % (name, want_ok, got_ok))
        fails += 1


def raise_(e):
    raise e


check_control("대조군: 의도한 지점의 타임아웃", lambda: raise_(m.Timeout("t", "reservation")), True)
check_control("대조군: 다른 지점의 타임아웃", lambda: raise_(m.Timeout("t", "price-cache")), False)
check_control("대조군: 지점 없는 타임아웃", lambda: raise_(m.Timeout("t")), False)
check_control("대조군: 타임아웃 아닌 단언 실패", lambda: raise_(AssertionError("x")), False)
check_control("대조군: 결함이 검사에 안 닿음(성공)", lambda: None, False)

if fails:
    print("self-test 실패 %d건" % fails)
    sys.exit(1)
print("self-test 통과 (14종)")
PY_ORDER_SELFTEST
fi

# 실행 순서 계약 (계획 P10) — 스택을 띄우기 **전에** 검사한다. 순서가 틀린 실행은
# 20분 뒤 시나리오 단언에서 깨지는 것보다 지금 죽는 편이 낫다. 정본은 saga_e2e.py 하나다.
if ! python3 -c "
import sys
sys.path.insert(0, 'scripts/e2e')
import saga_e2e
problems = saga_e2e.validate_order('${SCENARIOS}'.split(','))
for pb in problems:
    print(pb, file=sys.stderr)
sys.exit(1 if problems else 0)
"; then
  echo "::error::[saga-e2e] 시나리오 실행 순서 계약 위반 (계획 P10)" >&2
  exit 1
fi

export E2E_RUN_ID="${E2E_RUN_ID:-local-$(date +%s)-$$}"
if [[ "$E2E_RUN_ID" == "unset" ]]; then
  echo "::error::[saga-e2e] E2E_RUN_ID 가 'unset' 이다 — cold start 판정이 무력화된다" >&2
  exit 1
fi
export E2E_COMMIT_SHA="${E2E_COMMIT_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
export PEEKCART_IMAGE_TAG="${PEEKCART_IMAGE_TAG:-ci}"
PROJECT="${E2E_PROJECT:-e2e-${E2E_RUN_ID}}"

OUT_DIR=".cache/e2e/${E2E_RUN_ID}"
mkdir -p "$OUT_DIR"

# ------------------------------------------------------------ 실행 예산 (계획 P19)
#
# 구간마다 **절대 상한**을 둔다. 무한 대기는 CI 러너를 매달고, 어디서 매달렸는지도 남기지
# 않는다. 상한을 넘기면 그 구간의 이름과 함께 죽는다.
#
# **재시도는 인프라 기동에만 허용한다.** 상태 단언 실패를 재시도하면 "몇 번째엔 통과했다" 가
# 되어 saga 가 결정적으로 동작한다는 주장이 성립하지 않는다 — 재시도로 초록이 되는 검사는
# 무엇도 증명하지 않는다.
BUDGET_INFRA="${E2E_BUDGET_INFRA:-600}"        # 인프라 3종 + stub 기동
BUDGET_APP="${E2E_BUDGET_APP:-420}"            # 앱 1개 기동(순차)
BUDGET_READINESS="${E2E_BUDGET_READINESS:-300}"
BUDGET_SCENARIO="${E2E_BUDGET_SCENARIO:-300}"
BUDGET_CONTROL="${E2E_BUDGET_CONTROL:-900}"    # 대조군 전체(스택 조작 포함)

DURATIONS="$OUT_DIR/durations.tsv"
printf 'phase\tseconds\tresult\n' > "$DURATIONS"

# 이식 가능한 timeout — macOS 에는 coreutils `timeout` 이 없다.
# $1=상한(초) $2=구간이름 $3.. = 명령
with_budget() {
  local limit="$1" phase="$2"; shift 2
  local start; start=$(date +%s)
  "$@" &
  local pid=$!
  local rc=0
  while kill -0 "$pid" 2>/dev/null; do
    if (( $(date +%s) - start >= limit )); then
      kill -TERM "$pid" 2>/dev/null || true
      sleep 2
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      printf '%s\t%s\ttimeout\n' "$phase" "$((  $(date +%s) - start ))" >> "$DURATIONS"
      echo "::error::[saga-e2e] 구간 '${phase}' 이 예산 ${limit}s 를 넘겼다 (계획 P19)" >&2
      return 124
    fi
    sleep 2
  done
  wait "$pid" || rc=$?
  local elapsed=$(( $(date +%s) - start ))
  printf '%s\t%s\t%s\n' "$phase" "$elapsed" "$([[ $rc -eq 0 ]] && echo ok || echo fail)" >> "$DURATIONS"
  return $rc
}

dc() { docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"; }

collect() {
  # 실패해도 증적은 남긴다 — 실패한 실행의 로그가 가장 필요하다.
  dc logs --no-color >"$OUT_DIR/compose.log" 2>&1 || true
  echo "증적: $OUT_DIR"
}

# 워치독 정리는 한 곳이 소유한다 — 명시적 말단과 teardown 양쪽에서 안전하게 불린다.
stop_watchdog() {
  [[ -n "${NC_SENTINEL:-}" ]] && rm -f "$NC_SENTINEL"
  if [[ -n "${NC_WATCHDOG:-}" ]]; then
    kill "$NC_WATCHDOG" >/dev/null 2>&1 || true
    wait "$NC_WATCHDOG" 2>/dev/null || true
    NC_WATCHDOG=""
  fi
  return 0
}

teardown() {
  local rc=$?
  stop_watchdog
  collect
  if [[ $KEEP -eq 1 ]]; then
    echo "[--keep] 스택을 유지한다. 정리: docker compose -f $COMPOSE_FILE -p $PROJECT down -v --remove-orphans"
  else
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit $rc
}
trap teardown EXIT

# volume 재사용 차단 (계획 P4) — flyway 검사만으로는 warm reuse 를 구별하지 못하므로
# 기동 **전에** 막는다. marker 대조는 readiness 에서 한 번 더 한다.
for v in mysql-data redis-data kafka-data; do
  if docker volume inspect "${PROJECT}_${v}" >/dev/null 2>&1; then
    echo "::error::[saga-e2e] volume ${PROJECT}_${v} 가 이미 있다 — cold start 가 아니다" >&2
    exit 1
  fi
done

# readiness 가 쓸 업무 group 목록을 DlqTopology 에서 유도한다(정본 복제 금지).
bash scripts/kafka-subscription-contract-lint.sh --emit-groups | sort -u > scripts/e2e/business-groups.txt

echo "== 스택 기동 (project=$PROJECT, run_id=$E2E_RUN_ID) =="
dc build runner >/dev/null

# 인프라 먼저, 그 다음 앱을 **하나씩** 띄운다.
# 4 JVM 을 동시에 올리면 CPU 를 서로 뺏어 각자의 기동이 healthcheck 창을 넘긴다
# (실측: 동시 기동 시 order-service 가 360s 창 안에 뜨지 못했다). 순차 기동은
# 전체 시간이 조금 늘지만 각 서비스가 온전한 창을 쓰므로 결정적이다.
# 기동 실패는 **인프라 사유**이므로 여기서만 재시도를 허용한다(계획 P19).
with_budget "$BUDGET_INFRA" "infra-up" \
  dc up -d --wait --wait-timeout 300 mysql redis kafka pg-stub
# 토픽을 **앱 기동 전에** 선언된 파티션 수 그대로 만들어 둔다 (계획 P19).
#
# 브로커의 KAFKA_NUM_PARTITIONS=1 은 **자동 생성**에만 적용된다. 앱의 KafkaAdmin 이 나중에
# partitions(3) 으로 만들면, 이미 구독 중이던 소비자는 새 파티션을 **메타데이터 갱신 주기
# (기본 300s)** 가 지나야 발견하고 그 사이 그 파티션으로 간 메시지는 소비되지 않는다.
# 이것이 "각각은 통과하는데 연속 실행하면 뒤쪽이 타임아웃" 의 원인이었다(#92 미충족 #2).
# 실측: `order.created` 발행 18:59:39 → 소비 19:03:02 (약 203초).
echo "== 토픽 사전 생성 =="
topic_create() {
  local created=0 bad=0 topic parts actual
  # 목록을 먼저 파일로 받는다 — 백그라운드 subshell 에서 프로세스 치환 + docker exec 를
  # 섞으면 조용히 0줄을 읽고 지나갈 수 있다(실측: 이 단계가 4초 만에 아무것도 안 하고 끝났다).
  local list="$OUT_DIR/topics.tsv"
  bash scripts/kafka-subscription-contract-lint.sh --emit-topics > "$list"
  local want; want=$(wc -l < "$list" | tr -d ' ')
  if [[ "$want" -lt 10 ]]; then
    echo "::error::[saga-e2e] 토픽 정본이 ${want}줄 — 파서가 깨졌다" >&2
    return 1
  fi

  while IFS=$'\t' read -r topic parts; do
    [[ -n "$topic" ]] || continue
    # `docker compose exec -T` 는 **stdin 을 읽는다** — while-read 루프 안에서 그대로 부르면
    # 남은 목록을 통째로 삼켜 첫 반복 뒤 루프가 끝난다(실측: 20개 중 1개만 생성되고,
    # 같은 버그로 대조 루프도 1건만 돌아 "대조 실패 0" 이 vacuous 였다). </dev/null 필수.
    if dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 \
         --create --if-not-exists --topic "$topic" --partitions "$parts" --replication-factor 1 \
         </dev/null >/dev/null 2>&1; then
      created=$((created + 1))
    fi
  done < "$list"

  # **대조는 별개 조건이다** — 생성 명령의 성공만 보면 조용한 실패를 못 잡는다.
  while IFS=$'\t' read -r topic parts; do
    [[ -n "$topic" ]] || continue
    actual="$(dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 \
      --describe --topic "$topic" </dev/null 2>/dev/null \
      | sed -n 's/.*PartitionCount: \([0-9]*\).*/\1/p' | head -1)"
    if [[ "$actual" != "$parts" ]]; then
      echo "::error::[saga-e2e] 토픽 $topic 파티션 '${actual:-없음}' (선언 $parts)" >&2
      bad=$((bad + 1))
    fi
  done < "$list"

  echo "  토픽 ${want}종 — 생성 성공 ${created} · 대조 실패 ${bad}"
  if [[ "$created" -ne "$want" ]]; then
    echo "::error::[saga-e2e] 토픽 ${want}종 중 ${created}종만 생성됐다 — 사전 생성이 부분 실패다" >&2
    return 1
  fi
  [[ $bad -eq 0 ]]
}
# with_budget 은 대상을 **백그라운드 subshell** 로 돌리는데, 그 안에서 `docker compose exec`
# 는 stdin 이 닫혀 대부분 실패한다(실측: 20개 중 생성 성공 1). 사전 생성은 현재 셸에서
# 직접 돌리고 소요만 기록한다.
_tp_start=$(date +%s)
if topic_create; then _tp_rc=ok; else _tp_rc=fail; fi
printf 'topic-precreate\t%s\t%s\n' "$(( $(date +%s) - _tp_start ))" "$_tp_rc" >> "$DURATIONS"
[[ "$_tp_rc" == "ok" ]] || exit 1

for svc in product-service order-service payment-service notification-service; do
  echo "  기동: $svc"
  with_budget "$BUDGET_APP" "app-up:$svc" dc up -d --wait --wait-timeout 300 "$svc"
done

echo "== readiness =="
readiness() {
  dc run --rm -T -e E2E_OUT_DIR="/work/out/${E2E_RUN_ID}" runner \
    -c "python3 /work/e2e/saga_e2e.py readiness" | tee "$OUT_DIR/readiness.json"
}
with_budget "$BUDGET_READINESS" "readiness" readiness

# 음성 대조군은 시나리오를 **일부러 실패시킨다**. 정상 실행과 같은 출력 디렉터리를 쓰면
# 그 failure manifest 가 앞서 만든 success manifest 를 덮어쓰고, 뒤따르는 --e2e-evidence
# 게이트가 result=failure 로 실패한다(diff 리뷰 R2 #1 — 로컬에서 서로 다른 run_id 로 따로
# 돌리느라 놓쳤던 조합이다). 대조군 증적은 하위 디렉터리에 격리한다.
RUNNER_OUT="/work/out/${E2E_RUN_ID}"

run_in_runner() {
  dc run --rm -T -e E2E_OUT_DIR="$RUNNER_OUT" runner -c "$1"
}

if [[ $NEGATIVE_CONTROL -eq 1 ]]; then
  # ---------------------------------------------------------------- 음성 대조군
  #
  # **통과하는 검사는 그것이 무엇을 잡을 수 있는지 말해주지 않는다.** 시나리오 4종이 전부
  # 초록인 상태는 "saga 가 돈다" 와 "단언이 vacuous 하다" 를 구별하지 못한다. 그래서 결함을
  # 일부러 주입하고 **해당 검사가 실패하는지**를 매 CI 에서 확인한다.
  nc_fails=0

  # 판정은 runner 의 대조군 함수가 한다(ADR-0031 D1). 함수는 결함이 **의도한 지점에서**
  # 검사를 실패시켰을 때만 0 을 낸다. 종전의 `expect_fail`(명령이 아무 이유로든 실패하면
  # 통과)은 ② 가 시나리오 첫 줄에서 죽는 것을 통과로 받았다. 실패 사유는 stderr 에 남긴다.
  expect_pass() {
    local name="$1"; shift
    local err="$OUT_DIR/negative-control/last-error.txt"
    if "$@" >/dev/null 2>"$err"; then
      echo "  ok   [$name]"
    else
      # compose 가 stderr 에 쓰는 컨테이너 생성 줄을 걸러야 runner 의 실패 사유가 보인다
      echo "  FAIL [$name] $(grep -v ' Container ' "$err" | grep -v '^[[:space:]]*$' | tail -1)"
      nc_fails=$((nc_fails + 1))
    fi
  }

  control() { run_in_runner "python3 /work/e2e/saga_e2e.py negative-control $1"; }

  RUNNER_OUT="/work/out/${E2E_RUN_ID}/negative-control"
  mkdir -p "$OUT_DIR/negative-control"
  NC_START=$(date +%s)
  # 계획 P19 의 대조군 절대 상한. 선언만 하고 쓰지 않으면 유일한 상한이 CI 잡의 45분이 된다
  # (diff 리뷰 R1 #5). 워치독을 띄워 예산 초과 시 이 스크립트를 죽인다.
  #
  # **센티넬 파일로 살아있음을 판정한다**(R2 #2). `kill -0 $$` 만 보면 부모가 이미 끝난 뒤
  # PID 가 재사용됐을 때 무관한 프로세스에 TERM 을 보낸다. 정리는 teardown 이 소유한다 —
  # 중간에 set -e 로 죽어도 백그라운드 sleep 이 남지 않는다.
  NC_SENTINEL="$OUT_DIR/.nc-running"
  : > "$NC_SENTINEL"
  ( sleep "$BUDGET_CONTROL"
    if [[ -e "$NC_SENTINEL" ]] && kill -0 $$ 2>/dev/null; then
      echo "::error::[saga-e2e] 음성 대조군이 예산 ${BUDGET_CONTROL}s 를 넘겼다 (계획 P19)" >&2
      kill -TERM $$ 2>/dev/null || true
    fi ) &
  NC_WATCHDOG=$!
  echo "== 음성 대조군 (계획 P16) =="

  # ① poller 정지 → 예약 대기에서 실패.
  #    **시작 이벤트가 실제 outbox poller 를 지나는지 검출하는 유일한 대조군**이다.
  #    운영 kill switch 를 만들지 않는다(ADR-0031 D3) — 대신 ShedLock 행을 미래로 잡아
  #    poller 만 멈춘다. HTTP 진입점은 살아 있으므로 '주문은 되는데 이벤트가 안 나가는'
  #    상태가 된다. 행을 걷은 뒤 같은 주문의 예약이 도착하는지까지 본다(복원 확인).
  echo "  -- ① outbox poller 정지"
  dc exec -T mysql mysql -uroot -proot -e "
    INSERT INTO peekcart_order.shedlock (name, lock_until, locked_at, locked_by)
    VALUES ('orderOutboxPollingJob', NOW() + INTERVAL 1 HOUR, NOW(), 'e2e-negative-control')
    ON DUPLICATE KEY UPDATE lock_until = NOW() + INTERVAL 1 HOUR, locked_by = 'e2e-negative-control';
  " >/dev/null
  expect_pass "① poller 정지 → 예약 대기에서 실패" control poller-stopped-breaks-a
  # 해제는 DELETE 가 아니라 lock_until 을 과거로 돌리는 UPDATE 다. ShedLock 은 행을 만든 적이
  # 있다고 기억하면(WeakHashMap 레지스트리) INSERT 없이 UPDATE 만 한다. 행을 지우면 그 UPDATE 가
  # 0행이 되어 poller 는 GC 가 기억을 치울 때까지 락을 못 잡는다 — 대조군 나머지 내내 poller 가
  # 죽어 ③ 이 공허하게 통과했다(①-복원이 잡았다, ADR-0031 Context).
  dc exec -T mysql mysql -uroot -proot -e "
    UPDATE peekcart_order.shedlock SET lock_until = NOW() - INTERVAL 1 DAY
    WHERE name = 'orderOutboxPollingJob' AND locked_by = 'e2e-negative-control';
  " >/dev/null
  expect_pass "①-복원 poller 재개 → 같은 주문 예약 도착" control poller-resumed-reserves

  # ② product-service 정지 → 예약 대기에서 실패.
  #    상품과 단가 캐시는 **정지 전에** 만든다. 종전에는 정지 후 시나리오 A 를 돌려 A 첫 줄의
  #    상품 생성 API 에서 죽었고, 예약 단계에 닿지 않았다(ADR-0031 Context).
  echo "  -- ② product-service 정지"
  expect_pass "②-준비 상품·단가 캐시" control product-down-prepare
  dc stop product-service >/dev/null 2>&1
  expect_pass "② product-service 정지 → 예약 대기에서 실패" control product-down-breaks-reservation

  # ④ 업무 listener 부재 → readiness 의 group 검사에서 실패.
  #    ②의 정지 상태를 그대로 쓴다 — 별도 기동/정지 사이클을 아끼기 위해서다.
  expect_pass "④ listener 부재 → readiness group 검사에서 실패" \
    control readiness-detects-missing-listener
  dc start product-service >/dev/null 2>&1
  dc exec -T product-service true >/dev/null 2>&1 || true
  run_in_runner "python3 /work/e2e/saga_e2e.py readiness" >/dev/null
  expect_pass "②-복원 product-service 재기동 → 밀린 예약 도착" \
    control product-down-reservation-resumes

  # ③ 재고 충분 → B 실패
  echo "  -- ③ 재고 충분"
  expect_pass "③ 재고 충분 → 시나리오 B 가 실패함을 감지" control sufficient-stock-breaks-b

  # ⑥ egress 격리 **양·음 대조**.
  #
  #    실패만 보면 대조가 성립하지 않는다 — 표적이 죽었거나 도구가 없어도 '실패' 는 나온다.
  #    그래서 **같은 표적**에 대해 비격리 컨테이너는 성공, internal-only 앱은 실패를 한 쌍으로
  #    묶는다. 이 양성 검사가 실제로 결함 3건을 잡았다:
  #      (1) 컨테이너 안 `ip`/`host.docker.internal` 로 주소를 못 구해 빈 값이 됐다
  #      (2) 프로브가 python3 를 썼는데 **앱 이미지에 python3 가 없어** 음성이 '도구 없음' 으로
  #          통과했다 — 정확히 이 대조군이 막으려던 false-green
  #      (3) 표적을 호스트 프로세스로 두니 Docker Desktop 에서는 브리지 게이트웨이가 VM 안
  #          주소라 비격리 컨테이너조차 닿지 못했다 → **표적을 컨테이너로** 옮겼다
  #
  #    이 대조가 증명하는 것: internal-only 앱은 다른 네트워크의 호스트에 닿지 못한다.
  #    인터넷 egress 차단 자체의 증거는 별도로 있다 — #92 에서 `TOSS_BASE_URL` 오설정으로
  #    dispatcher 가 실제 api.tosspayments.com 을 불렀고 `UnknownHostException` 으로 막혔다.
  echo "  -- ⑥ egress 격리 양·음 대조"

  COMPOSE_PROFILES=control dc up -d --wait --wait-timeout 60 egress-canary >/dev/null 2>&1 || true
  CANARY_IP="$(docker inspect -f \
    '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$(COMPOSE_PROFILES=control dc ps -q egress-canary 2>/dev/null | head -1)" 2>/dev/null \
    | tr -d '\r\n' || true)"

  if [[ -z "$CANARY_IP" ]]; then
    echo "  FAIL [⑥ 사전조건] egress-canary 주소를 못 구했다 — 대조가 성립하지 않는다"
    nc_fails=$((nc_fails + 1))
  else
    echo "     canary 주소: ${CANARY_IP}:8080"

    # curl 은 앱 이미지·runner 이미지 **양쪽에 모두 있다**(실측). 종료코드로 원인을 가른다:
    #   0 = 도달   7 = 연결 실패   28 = 타임아웃   127 = 명령 없음
    # `docker compose run` 은 --profile 을 받지 않는다(top-level 플래그다) — 환경변수로 넘긴다.
    probe_rc() {  # $1=서비스 $2=프로파일(없으면 "") → curl 종료코드를 stdout 으로
      local svc="$1" profile="${2:-}"
      COMPOSE_PROFILES="$profile" dc run --rm -T --entrypoint /bin/sh "$svc" -c \
        "curl -s -o /dev/null --connect-timeout 5 --max-time 8 http://${CANARY_IP}:8080/ ; echo rc=\$?" \
        2>/dev/null | tr -d '\r' | sed -n 's/^rc=//p' | tail -1 || true
    }

    # 양성: 비격리 대조군은 **닿아야 한다**. 여기가 실패하면 표적이 죽은 것이므로
    # 아래 음성의 '실패' 는 격리의 증거가 되지 못한다.
    pos_rc="$(probe_rc egress-control control)"
    if [[ "$pos_rc" == "0" ]]; then
      echo "  ok   [⑥-양성 비격리 control 컨테이너 → canary 도달]"
    else
      echo "  FAIL [⑥-양성 비격리 control 컨테이너 → canary 도달] curl rc=${pos_rc:-?}"
      nc_fails=$((nc_fails + 1))
    fi

    # 음성: internal:true 에만 붙은 앱은 **연결 자체가 실패**해야 한다.
    # rc 를 특정한다 — '아무 비정상 종료' 로 받으면 127(도구 없음)도 통과해 버린다.
    neg_rc="$(probe_rc payment-service)"
    if [[ "$neg_rc" == "7" || "$neg_rc" == "28" ]]; then
      echo "  ok   [⑥-음성 internal 전용 payment-service → 연결 실패(rc=${neg_rc})]"
    else
      echo "  FAIL [⑥-음성 internal 전용 payment-service] curl rc=${neg_rc:-?} — 0 이면 격리가 깨졌고, 127 이면 도구가 없어 대조가 무의미하다"
      nc_fails=$((nc_fails + 1))
    fi
  fi

  COMPOSE_PROFILES=control dc stop egress-canary >/dev/null 2>&1 || true

  # ⑤ compose project 2개 동시 기동. 호스트 포트·container_name 고정이 남아 있으면 충돌한다.
  echo "  -- ⑤ project 병렬 기동"
  ALT_PROJECT="${PROJECT}-alt"
  if E2E_RUN_ID="${E2E_RUN_ID}-alt" docker compose -f "$COMPOSE_FILE" -p "$ALT_PROJECT" \
       up -d --wait --wait-timeout 300 mysql redis kafka pg-stub >/dev/null 2>&1; then
    echo "  ok   [⑤ project 2개 동시 기동]"
  else
    echo "  FAIL [⑤ project 2개 동시 기동] 충돌했다 — 호스트 포트/container_name 고정 의심"
    nc_fails=$((nc_fails + 1))
  fi
  docker compose -f "$COMPOSE_FILE" -p "$ALT_PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true

  if [[ $nc_fails -gt 0 ]]; then
    stop_watchdog
    printf 'negative-control\t%s\tfail\n' "$(( $(date +%s) - NC_START ))" >> "$DURATIONS"
    echo "::error::[saga-e2e] 음성 대조군 ${nc_fails}건 실패 — 검사가 결함을 잡지 못한다 (계획 P16)" >&2
    exit 1
  fi
  stop_watchdog
  printf 'negative-control\t%s\tok\n' "$(( $(date +%s) - NC_START ))" >> "$DURATIONS"
  echo "== 음성 대조군 전량 통과 (구간별 소요: $DURATIONS) =="
  cat "$DURATIONS"
  exit 0
fi

IFS=',' read -ra LIST <<< "$SCENARIOS"
for s in "${LIST[@]}"; do
  echo "== 시나리오 $s =="
  # **재시도하지 않는다** — 상태 단언은 한 번에 성립해야 한다(계획 P19).
  with_budget "$BUDGET_SCENARIO" "scenario:$s" \
    run_in_runner "python3 /work/e2e/saga_e2e.py scenario $s"
done

echo "== 완료 (구간별 소요: $DURATIONS) =="
cat "$DURATIONS"
