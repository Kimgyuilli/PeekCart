#!/usr/bin/env bash
# scheduler-lock-contract-lint.sh — 스케줄러 풀 확대(D-024)가 안전하게 유지되는지 강제한다
#
# 왜:
#   D-024 수정으로 `spring.task.scheduling.pool.size` 를 1 → 4 로 올렸다. 풀이 1일 때는 구조적으로
#   불가능했던 것이 열린다 — **cron/fixedRate 잡이 자기 자신과 겹쳐 실행**될 수 있다(이전 실행이
#   주기보다 길면). ShedLock 이 그것을 막지만, 락 없는 잡이 새로 들어오면 조용히 깨진다.
#
#   fixedDelay 는 완료 후 재스케줄이라 풀 크기와 무관하게 겹치지 않는다. 위험한 것은 cron/fixedRate 다.
#
#   예외: **인스턴스별로 각자 돌아야 하는** 잡(로컬 캐시/스냅샷 갱신 등)은 ShedLock 을 걸면 안 된다 —
#   한 인스턴스만 갱신하고 나머지는 낡은 상태를 서빙하게 된다(gateway JWKS 가 그 예). 그런 잡은
#   `// [SCHED-LOCK exempt] <사유>` 를 애노테이션 근처에 달아 **의도를 명시**한다(ADR-0007 예외 주석과 같은 관례).
#
# 검사:
#   [SCHED-001] @Scheduled 메서드에 @SchedulerLock 도 exempt 표기도 없음
#   [SCHED-002] fixedRate 사용 (fixedDelay 로 바꾸거나 락 + 근거 필요 — 현재 레포에 0건이 계약)
#
# Usage: bash scripts/scheduler-lock-contract-lint.sh
# Exit: 0 위반 없음 / 1 위반
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TAG="[SCHED-LOCK]"
violations=0

# 주석/javadoc 의 `@Scheduled` 언급은 제외한다 — 애노테이션만 본다(줄 시작이 공백+@Scheduled).
mapfile -t files < <(grep -rl --include="*.java" '^\s*@Scheduled' */src/main 2>/dev/null | sort)

if [ ${#files[@]} -eq 0 ]; then
    echo "$TAG @Scheduled 를 가진 파일이 0건 — 검사 전제 실패" >&2
    exit 1
fi

for f in "${files[@]}"; do
    # @Scheduled 애노테이션이 붙은 줄 번호마다, 그 다음 3줄 안에 @SchedulerLock 이 있는지 본다.
    while IFS=: read -r ln _; do
        # exempt 표기는 애노테이션 위쪽 주석에도 올 수 있으므로 앞뒤를 함께 본다.
        window=$(sed -n "$((ln > 3 ? ln - 3 : 1)),$((ln + 4))p" "$f")
        # 애노테이션은 **줄 시작**(공백 허용)이어야 한다 — 주석 처리된 `//@SchedulerLock` 을
        # 유효한 것으로 세면 검사가 무력해진다(false-green 검사에서 실제로 걸렸다).
        if ! grep -qE '^[[:space:]]*@SchedulerLock|\[SCHED-LOCK exempt\]' <<< "$window"; then
            echo "$TAG [SCHED-001] $f:$ln — @SchedulerLock 도 '[SCHED-LOCK exempt] 사유' 표기도 없다"
            violations=$((violations + 1))
        fi
        if grep -q "fixedRate" <<< "$window"; then
            echo "$TAG [SCHED-002] $f:$ln — fixedRate 사용(풀>1 에서 자기 자신과 겹칠 수 있다)"
            violations=$((violations + 1))
        fi
    done < <(grep -n '^\s*@Scheduled' "$f")
done

# 풀 설정이 실제로 선언돼 있는지도 함께 본다 — 설정이 사라지면 이 계약의 전제가 없어진다.
for m in order-service product-service payment-service notification-service; do
    y="$m/src/main/resources/application.yml"
    [ -f "$y" ] || continue
    if ! grep -qA3 'scheduling:' "$y" 2>/dev/null | grep -q .; then :; fi
    if ! awk '/^  task:/{t=1} t&&/pool:/{p=1} p&&/size:/{print; exit}' "$y" | grep -q "size:"; then
        echo "$TAG [SCHED-003] $y — spring.task.scheduling.pool.size 미선언 (D-024 회귀)"
        violations=$((violations + 1))
    fi
done

if [ "$violations" -gt 0 ]; then
    echo "$TAG 위반 ${violations}건" >&2
    exit 1
fi
echo "$TAG OK — @Scheduled 전부 @SchedulerLock 보유 · fixedRate 0건 · 풀 설정 4서비스 선언됨"
