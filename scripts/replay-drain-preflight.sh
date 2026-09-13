#!/usr/bin/env bash
# replay-drain-preflight.sh — replay 개시 이후의 **롤백 안전(drain) 판정** (④-c-2b-4b P24 · ADR-0022 §D2)
#
# 왜 필요한가:
#   `outbox_events.record_kind='REPLAY'` 행은 replay-aware poller 만 올바르게 발행한다. 구 poller 는
#   그 행의 event_type 자리에 든 sentinel(`__replay__`)을 **토픽 이름으로** 써서 발행을 깨뜨린다.
#   즉 replay 를 한 번이라도 개시한 뒤 구 이미지로 내려가면 발행 경로가 손상된다.
#   "배포 전에 확인한다" 를 runbook 에만 적으면 그것은 검증이 아니다 — 문서는 프로세스 기동을 막지 못한다.
#
# 판정 4조건 (ADR-0022 §D2). 4개 서비스 DB **각각**에서 전부 만족해야 한다:
#   ⓐ   outbox_events 에 record_kind='REPLAY' AND status='PENDING' 인 행이 0
#   ⓐ'  dead_letter_records.publication_status='REQUESTED' 인 행이 0
#   ⓑⓒ  last_replay_target_group 에 등장한 group + 대응 .dlq intake group 의 lag = 0
#        (**상한 간격을 두고 2회 연속** — handlerBudget 이 코드로 강제되지 않으므로 시간 추론 하나에 걸지 않는다)
#   ⓓ   MAX(last_replay_settled_at) + 상한 < NOW(). NULL 이면 replay 이력 없음 → 충족
#
# **구조: 수집과 판정을 분리한다.** 수집은 클러스터(kubectl/mysql/kafka)에 의존해 CI 에서 돌릴 수 없지만,
#   판정은 순수 함수라 fixture 로 검증할 수 있다. `--self-test` 가 판정을 관통한다(계획 §6 V-37).
#   이 분리가 없으면 "스크립트가 있다" 외에는 아무 것도 관측되지 않는다.
#
# **fail-closed**: DB·브로커 접속 실패, 파싱 실패, 알 수 없는 상태는 전부 **배포를 막는다**(exit 1).
#   부재는 안전의 증거가 아니다.
#
# **강제력의 한계 (ADR-0022 §D3)**: 이 스크립트는 `scripts/deploy-overlay.sh` 가 호출한다. 운영자가
#   래퍼를 건너뛰고 `kubectl apply -k` 를 직접 치면 **우회된다**. 우회 불가를 주장하지 않는다.
#
# Exit:
#   0 — drain 완료. 구 이미지로 롤백해도 안전하다
#   1 — 위반 1건 이상, 또는 수집 실패(fail-closed)
#   2 — 전제 미충족(kubectl/python3 부재, self-test 실패)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[DRAIN-PREFLIGHT]"
NAMESPACE="${PEEKCART_NAMESPACE:-peekcart}"
SERVICES=(order product payment notification)

# --- drain 상한의 정본 (ADR-0022 §D2) ---
#
# 이 값들은 **이 스크립트가 소유한다**. 배포 절차의 값이지 앱 런타임 정책이 아니므로 앱 yml 에 둘 근거가
# 없고(ADR-0007), 배포 도구가 런타임 설정 포맷을 파싱하면 그 포맷에 묶인다.
# **대신 드리프트를 lint 가 잡는다** — scripts/replay-entrypoint-lint.sh 가 아래 두 값을
# FixedSequenceBackOff 리터럴(4벌)·clock-skew-budget(4벌 yml)과 교차 대조한다.
BACKOFF_TOTAL_SECONDS=36          # FixedSequenceBackOff(1_000, 5_000, 30_000)
CLOCK_SKEW_SECONDS=300            # app.idempotency.clock-skew-budget: 5m
# 아래 둘은 **코드로 강제되지 않는 선언값**이다 — consumer handler 에 timeout 설정이 없다.
# 그래서 ⓑⓒ 의 "2회 연속 lag=0" 이 이 약점을 보완한다(제거하지는 못한다).
ATTEMPTS=4                        # 최초 실행 + backoff 3회
HANDLER_BUDGET_SECONDS=30
DLT_PIPELINE_SECONDS=30           # DLT publish + intake 상한
MARGIN_SECONDS=60

DRAIN_BOUND_SECONDS=$(( BACKOFF_TOTAL_SECONDS + ATTEMPTS * HANDLER_BUDGET_SECONDS \
    + DLT_PIPELINE_SECONDS + CLOCK_SKEW_SECONDS + MARGIN_SECONDS ))

# ⓑⓒ 재관측 간격 — 한 번의 lag=0 은 "컨슈머가 아직 안 받았다" 와 구별되지 않는다.
LAG_RECHECK_INTERVAL_SECONDS="${DRAIN_LAG_RECHECK_SECONDS:-15}"

# --- 판정기 (순수 함수 — stdin TSV 를 읽는다) ---
#
# 입력 레코드:
#   NOW  <epoch>
#   DB   <service> <pendingReplay> <requestedRows> <maxSettledEpoch|->
#   LAG  <service> <group> <lag1> <lag2>
#   ERR  <service> <message>          ← 수집 실패. 한 건이라도 있으면 fail-closed
JUDGE=$(cat <<'PY'
import sys

bound = int(sys.argv[1])
violations = []
errors = []
now = None
seen_db = set()
expected = set(sys.argv[2].split(","))

for raw in sys.stdin:
    line = raw.rstrip("\n")
    if not line.strip():
        continue
    parts = line.split("\t")
    kind = parts[0]
    try:
        if kind == "NOW":
            now = int(parts[1])
        elif kind == "ERR":
            errors.append("%s: %s" % (parts[1], parts[2]))
        elif kind == "DB":
            service, pending, requested, settled = parts[1], int(parts[2]), int(parts[3]), parts[4]
            seen_db.add(service)
            if pending > 0:
                violations.append(
                    "[ⓐ] %s: 미발행 replay outbox 행 %d건 (record_kind='REPLAY' AND status='PENDING'). "
                    "구 poller 는 이 행의 sentinel 을 토픽으로 써서 발행을 깨뜨린다" % (service, pending))
            if requested > 0:
                violations.append(
                    "[ⓐ'] %s: 발행 결과 미확정 원장 행 %d건 (publication_status='REQUESTED'). "
                    "outbox 행이 이미 사라진 건이면 publication-unknown 으로 해제해야 한다" % (service, requested))
            if settled != "-":
                elapsed = now - int(settled)
                if elapsed < bound:
                    violations.append(
                        "[ⓓ] %s: 마지막 발행 종착 이후 %ds 밖에 지나지 않았다 (상한 %ds). "
                        "재발행분의 소비 재시도가 아직 끝나지 않았을 수 있다" % (service, elapsed, bound))
        elif kind == "LAG":
            service, group, lag1, lag2 = parts[1], parts[2], int(parts[3]), int(parts[4])
            if lag1 != 0 or lag2 != 0:
                violations.append(
                    "[ⓑⓒ] %s: consumer group %s 의 lag 가 0 이 아니다 (1회차 %d · 2회차 %d). "
                    "재발행분이 아직 소비되지 않았다" % (service, group, lag1, lag2))
        else:
            errors.append("알 수 없는 입력 레코드: %s" % kind)
    except (IndexError, ValueError) as e:
        errors.append("입력 파싱 실패 (%s): %s" % (e, line))

if now is None:
    errors.append("기준 시각(NOW)이 수집되지 않았다")

# **수집 누락은 통과가 아니다.** 서비스 하나의 조회가 통째로 빠지면 위반이 0 건으로 보인다 —
# 정확히 fail-open 이다. 기대 서비스 집합과 대조한다.
missing = expected - seen_db
if missing:
    errors.append("DB 판정이 수집되지 않은 서비스: %s" % ", ".join(sorted(missing)))

if errors:
    print("수집 실패 — fail-closed (부재는 안전의 증거가 아니다):")
    for e in errors:
        print("  ! " + e)
if violations:
    print("drain 미완료 — 롤백하면 발행 경로가 손상된다:")
    for v in violations:
        print("  x " + v)

sys.exit(1 if (errors or violations) else 0)
PY
)

judge() {
    python3 -c "$JUDGE" "$DRAIN_BOUND_SECONDS" "$(IFS=,; echo "${SERVICES[*]}")"
}

# --- self-test: 판정을 fixture 로 관통한다 (계획 §6 V-37) ---
self_test() {
    local failures=0
    local now=2000000000

    run_case() {
        local name="$1" expected_exit="$2" expected_needle="$3" input="$4"
        local output rc
        set +e
        output=$(printf '%s' "$input" | judge 2>&1)
        rc=$?
        set -e
        if [[ "$rc" != "$expected_exit" ]]; then
            echo "$TAG self-test 실패: $name — exit $rc (기대 $expected_exit)" >&2
            echo "$output" >&2
            failures=$((failures + 1))
            return
        fi
        if [[ -n "$expected_needle" && "$output" != *"$expected_needle"* ]]; then
            # **진단 문자열까지 대조한다** — non-zero 여부만 보면 다른 위반에 걸려도 통과라,
            # 그 조건이 실제로 살아 있는지 증명되지 않는다.
            echo "$TAG self-test 실패: $name — 진단에 '$expected_needle' 가 없다" >&2
            echo "$output" >&2
            failures=$((failures + 1))
            return
        fi
        echo "  ok  $name"
    }

    local clean=""
    for service in "${SERVICES[@]}"; do
        clean+=$'DB\t'"$service"$'\t0\t0\t-\n'
    done
    clean="NOW"$'\t'"$now"$'\n'"$clean"

    echo "$TAG self-test — 판정 fixture"
    run_case "무위반 baseline 은 통과한다 (항상 red 인 게이트는 검사가 아니다)" 0 "" "$clean"

    run_case "ⓐ 단독 위반" 1 "[ⓐ] order" \
        "${clean/$'DB\torder\t0\t0\t-'/$'DB\torder\t1\t0\t-'}"
    run_case "ⓐ' 단독 위반 — outbox 행이 강제 삭제된 건이 여기 걸린다" 1 "[ⓐ'] product" \
        "${clean/$'DB\tproduct\t0\t0\t-'/$'DB\tproduct\t0\t1\t-'}"
    run_case "ⓓ 단독 위반 — 종착 직후" 1 "[ⓓ] payment" \
        "${clean/$'DB\tpayment\t0\t0\t-'/$'DB\tpayment\t0\t0\t'$((now - 10))}"
    run_case "ⓑⓒ 단독 위반 — 2회차만 0 이어도 막는다" 1 "[ⓑⓒ] order" \
        "${clean}"$'LAG\torder\torder-svc-dlq-group\t3\t0\n'

    # 경계: 상한 직전/직후. skew 를 식에서 빼면 앞 케이스가 통과해 red 가 된다.
    run_case "ⓓ 경계 — 상한 1초 전이면 막는다" 1 "[ⓓ] order" \
        "${clean/$'DB\torder\t0\t0\t-'/$'DB\torder\t0\t0\t'$((now - DRAIN_BOUND_SECONDS + 1))}"
    run_case "ⓓ 경계 — 상한을 지나면 통과한다" 0 "" \
        "${clean/$'DB\torder\t0\t0\t-'/$'DB\torder\t0\t0\t'$((now - DRAIN_BOUND_SECONDS))}"

    run_case "수집 실패는 fail-closed 다" 1 "fail-closed" \
        "${clean}"$'ERR\tpayment\tAccess denied for user\n'
    run_case "서비스 하나의 조회가 통째로 빠지면 막는다 (누락은 통과가 아니다)" 1 "수집되지 않은 서비스" \
        "NOW"$'\t'"$now"$'\nDB\torder\t0\t0\t-\n'
    run_case "기준 시각이 없으면 막는다" 1 "기준 시각" \
        "${clean#NOW*$'\n'}"
    run_case "파싱 불가 입력은 막는다" 1 "fail-closed" \
        "${clean}"$'DB\torder\tNaN\t0\t-\n'

    if [[ "$failures" -gt 0 ]]; then
        echo "$TAG self-test 실패 $failures 건" >&2
        exit 2
    fi
    echo "$TAG self-test OK — 판정 11종"
}

# --- 수집 (클러스터 의존) ---
collect() {
    local now
    now=$(date +%s)
    printf 'NOW\t%s\n' "$now"

    for service in "${SERVICES[@]}"; do
        local secret="${service}-service-secret"
        local user password schema
        if ! user=$(kubectl -n "$NAMESPACE" get secret "$secret" -o jsonpath='{.data.DB_USERNAME}' 2>/dev/null | base64 -d) \
            || [[ -z "$user" ]]; then
            printf 'ERR\t%s\t%s\n' "$service" "Secret $secret 에서 DB_USERNAME 을 읽지 못했다"
            continue
        fi
        password=$(kubectl -n "$NAMESPACE" get secret "$secret" -o jsonpath='{.data.DB_PASSWORD}' 2>/dev/null | base64 -d)
        schema="peekcart_${service}"

        local sql row
        # 세 값을 **한 번의 쿼리**로 읽는다 — 나눠 읽으면 그 사이의 전이가 조건 간 불일치를 만든다.
        sql="SELECT (SELECT COUNT(*) FROM outbox_events WHERE record_kind='REPLAY' AND status='PENDING'),"
        sql+=" (SELECT COUNT(*) FROM dead_letter_records WHERE publication_status='REQUESTED'),"
        sql+=" COALESCE((SELECT UNIX_TIMESTAMP(MAX(last_replay_settled_at)) FROM dead_letter_records), '-')"
        if ! row=$(kubectl -n "$NAMESPACE" exec deploy/mysql -- \
                mysql -u"$user" -p"$password" -D "$schema" -N -B -e "$sql" 2>/dev/null); then
            printf 'ERR\t%s\t%s\n' "$service" "스키마 $schema 조회 실패 (접속·권한)"
            continue
        fi
        printf 'DB\t%s\t%s\n' "$service" "$row"

        # ⓑⓒ — 원장이 기록한 표적 group + 그 서비스의 DLQ intake group
        local groups
        groups=$(kubectl -n "$NAMESPACE" exec deploy/mysql -- \
            mysql -u"$user" -p"$password" -D "$schema" -N -B \
            -e "SELECT DISTINCT last_replay_target_group FROM dead_letter_records WHERE last_replay_target_group IS NOT NULL" \
            2>/dev/null || true)
        groups+=$'\n'"${service}-svc-dlq-group"

        while IFS= read -r group; do
            [[ -z "$group" ]] && continue
            local lag1 lag2
            if ! lag1=$(consumer_group_lag "$group"); then
                printf 'ERR\t%s\t%s\n' "$service" "consumer group $group 의 lag 조회 실패"
                continue
            fi
            sleep "$LAG_RECHECK_INTERVAL_SECONDS"
            if ! lag2=$(consumer_group_lag "$group"); then
                printf 'ERR\t%s\t%s\n' "$service" "consumer group $group 의 lag 재조회 실패"
                continue
            fi
            printf 'LAG\t%s\t%s\t%s\t%s\n' "$service" "$group" "$lag1" "$lag2"
        done <<< "$groups"
    done
}

consumer_group_lag() {
    local group="$1" output
    output=$(kubectl -n "$NAMESPACE" exec deploy/kafka -- \
        /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
        --describe --group "$group" 2>/dev/null) || return 1
    # LAG 컬럼 합. '-'(할당 없음)은 0 으로 읽지 않고 **실패로 돌린다** — 미할당은 소비 완료의 증거가 아니다.
    awk 'NR > 1 && NF >= 6 { if ($6 == "-") { bad = 1 } else { sum += $6 } } END { if (bad) exit 1; print sum + 0 }' \
        <<< "$output"
}

main() {
    if [[ "${1:-}" == "--self-test" ]]; then
        self_test
        exit 0
    fi

    if ! command -v python3 >/dev/null 2>&1; then
        echo "$TAG python3 가 필요하다" >&2
        exit 2
    fi
    if ! command -v kubectl >/dev/null 2>&1; then
        # **skip 하지 않는다.** lint 와 달리 이것은 배포 게이트다 — 도구가 없으면 판정할 수 없고,
        # 판정하지 못한 채 통과시키는 것이 정확히 이 스크립트가 막으려는 것이다.
        echo "$TAG kubectl 이 없다 — 판정 불가이므로 배포를 막는다" >&2
        exit 2
    fi

    echo "$TAG namespace=$NAMESPACE · drain 상한 ${DRAIN_BOUND_SECONDS}s · lag 재관측 간격 ${LAG_RECHECK_INTERVAL_SECONDS}s"
    if collect | judge; then
        echo "$TAG OK — drain 4조건 충족. 구 이미지 롤백이 안전하다"
    else
        echo "$TAG 배포를 막는다. 조치는 docs/runbooks/dlq-recovery.md §6-R 을 따른다" >&2
        exit 1
    fi
}

main "$@"
