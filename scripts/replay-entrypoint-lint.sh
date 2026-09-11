#!/usr/bin/env bash
# replay-entrypoint-lint — DLQ replay 개시 진입점 단일성 (구현 ④-c-2b-4a P25 · 계획 N14)
#
# 무엇을 막는가:
#   (1) replay 개시 코드가 DeadLetterReplayService 밖에 생기는 것. 진입점이 둘이 되면 적격성 6축(§D5-2)·
#       fence(§D8-3)·조건부 claim(§D6-4)을 우회하는 경로가 생기고, 그 경로는 아무 것도 실패시키지 않는다.
#   (2) runbook·리허설 스크립트가 원장 상태를 **직접 SQL 로** 바꾸는 것. ④-c-2a 에서 실제로 났던 결함이다 —
#       그러면 DeadLetterRecord 의 "사유 필수" 가드와 상태 전이 규칙이 통째로 우회되고, 리허설은
#       "SQL 이 돌았다" 만 증명하는 false-green 이 된다.
#
# 왜 테스트가 아니라 lint 인가: 검사 대상이 4모듈의 소스와 문서라 한 모듈의 테스트가 볼 수 없다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

violations=()

# --- (1) OutboxEvent.replay(...) 호출자는 진입점 하나뿐 ---
# 팩토리 자신(OutboxEvent.java)은 선언이므로 제외한다.
while IFS= read -r file; do
    base="$(basename "$file")"
    [[ "$base" == "OutboxEvent.java" ]] && continue
    [[ "$base" == "DeadLetterReplayService.java" ]] && continue
    violations+=("[REPLAY-ENTRY-001] replay 개시 코드가 진입점 밖에 있다: $file")
done < <(grep -rl "OutboxEvent\.replay(" --include="*.java" \
            order-service/src/main product-service/src/main payment-service/src/main notification-service/src/main \
            2>/dev/null || true)

# --- (2) 진입점은 4서비스 전부에 있어야 한다 (한 서비스만 빠지면 그 원장은 replay 불가) ---
for svc in order product payment notification; do
    path="${svc}-service/src/main/java/com/peekcart/global/deadletter/DeadLetterReplayService.java"
    if [[ ! -f "$path" ]]; then
        violations+=("[REPLAY-ENTRY-002] ${svc}-service 에 replay 진입점이 없다")
    fi
done

# --- (3) 문서·스크립트의 직접 상태 변경 SQL 0건 ---
# 계획서(docs/plans)는 설계 논의 중 금지 사례를 인용하므로 제외한다 — 실행 절차가 아니다.
while IFS= read -r hit; do
    violations+=("[REPLAY-ENTRY-003] 문서/스크립트가 원장 상태를 직접 SQL 로 바꾼다: $hit")
done < <(grep -rn -E "UPDATE[[:space:]]+dead_letter_records[[:space:]]+SET[[:space:]]+(status|publication_status)" \
            docs scripts --include="*.md" --include="*.sh" --include="*.py" 2>/dev/null \
            | grep -v "^docs/plans/" || true)

# --- (4) kill-switch 가 4서비스 base yml 에 **false 로** 선언돼 있어야 한다 ---
# Java 기본값(DeadLetterProperties.Replay.enabled=false)만으로는 부족하다 — yml 이 true 로 덮으면
# 기본값 테스트는 통과하면서 운영에서는 열린 채 뜬다. 두 출처를 모두 고정한다.
for svc in order product payment notification; do
    yml="${svc}-service/src/main/resources/application.yml"
    # `    replay:` 바로 다음 줄이 `      enabled: false` 인지 본다. 값만 grep 하면 다른 블록의
    # 동명 키에 걸리고, 블록만 grep 하면 값이 true 여도 통과한다.
    if ! grep -A1 -E "^    replay:$" "$yml" | grep -qE "^      enabled: false$"; then
        violations+=("[REPLAY-ENTRY-004] ${svc}-service application.yml 의 app.dead-letter.replay.enabled 가 false 로 선언돼 있지 않다")
    fi
done

if ((${#violations[@]} > 0)); then
    printf '%s\n' "${violations[@]}" >&2
    echo "replay-entrypoint-lint FAILED (${#violations[@]}건)" >&2
    exit 1
fi

echo "replay-entrypoint-lint OK — 진입점 4서비스 1개씩, 우회 호출 0, 문서 직접 SQL 0"
