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

# --- (1b) DeadLetterReplayService.replay(...) 호출자는 DeadLetterEndpoint 하나뿐 ---
# (1) 만으로는 부족하다 (diff 리뷰 1R #8): 다른 scheduler/controller 가 replayService.replay(...) 를
# 부르면 적격성·fence 는 공유하지만 **사람이 Endpoint 에서만 개시한다**(N14)는 단일성이 깨진다.
# 그 경로는 kill-switch 외에 아무 운영 통제도 받지 않는다.
while IFS= read -r file; do
    base="$(basename "$file")"
    [[ "$base" == "DeadLetterReplayService.java" ]] && continue
    [[ "$base" == "DeadLetterEndpoint.java" ]] && continue
    violations+=("[REPLAY-ENTRY-005] replay 개시 호출자가 Endpoint 밖에 있다: $file")
done < <(grep -rlE "replayService\.replay\(|DeadLetterReplayService[[:space:]]+[a-zA-Z]+" --include="*.java" \
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

# --- (5) drain 상한 상수가 코드·설정과 갈라지지 않았는가 (④-c-2b-4b · ADR-0022 §D2 · 계획 V-41) ---
#
# 상한은 preflight 스크립트가 소유한다 — 배포 절차의 값이지 앱 런타임 정책이 아니기 때문이다(ADR-0007).
# 그 대가로 **코드/설정과 갈라질 수 있다**: backoff 를 늘리거나 clock-skew 를 바꿔도 상한은 그대로여서
# drain 이 **조기 통과**한다. 그 드리프트를 여기서 잡는다.
drift_check() {
    local root="${1:-.}"
    python3 - "$root" <<'PYEOF'
import os, re, sys

root = sys.argv[1]
services = ["order", "product", "payment", "notification"]
problems = []

preflight = os.path.join(root, "scripts/replay-drain-preflight.sh")
try:
    with open(preflight, encoding="utf-8") as f:
        script = f.read()
except OSError:
    print("[REPLAY-ENTRY-006] scripts/replay-drain-preflight.sh 가 없다 — 상한 정본이 사라졌다")
    sys.exit(1)

def constant(name):
    m = re.search(r"^%s=(\d+)" % name, script, re.M)
    return int(m.group(1)) if m else None

declared_backoff = constant("BACKOFF_TOTAL_SECONDS")
declared_skew = constant("CLOCK_SKEW_SECONDS")
if declared_backoff is None or declared_skew is None:
    problems.append("[REPLAY-ENTRY-006] preflight 에서 상한 상수를 읽지 못했다 "
                    "(BACKOFF_TOTAL_SECONDS / CLOCK_SKEW_SECONDS)")

for service in services:
    config = os.path.join(root, "%s-service/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java" % service)
    try:
        with open(config, encoding="utf-8") as f:
            body = f.read()
    except OSError:
        problems.append("[REPLAY-ENTRY-006] %s: DeadLetterKafkaConfig.java 가 없다" % service)
        continue
    m = re.search(r"FixedSequenceBackOff\(([^)]*)\)", body)
    if not m:
        problems.append("[REPLAY-ENTRY-006] %s: FixedSequenceBackOff 리터럴을 찾지 못했다" % service)
        continue
    millis = [int(v.strip().replace("_", "")) for v in m.group(1).split(",") if v.strip()]
    total = sum(millis) // 1000
    if declared_backoff is not None and total != declared_backoff:
        problems.append(
            "[REPLAY-ENTRY-006] %s: backoff 합 %ds 가 preflight 의 BACKOFF_TOTAL_SECONDS=%ds 와 다르다 "
            "— drain 이 조기 통과한다" % (service, total, declared_backoff))

    yml = os.path.join(root, "%s-service/src/main/resources/application.yml" % service)
    try:
        with open(yml, encoding="utf-8") as f:
            text = f.read()
    except OSError:
        problems.append("[REPLAY-ENTRY-006] %s: application.yml 이 없다" % service)
        continue
    m = re.search(r"^\s*clock-skew-budget:\s*(\d+)([smh])", text, re.M)
    if not m:
        problems.append("[REPLAY-ENTRY-006] %s: clock-skew-budget 을 찾지 못했다" % service)
        continue
    seconds = int(m.group(1)) * {"s": 1, "m": 60, "h": 3600}[m.group(2)]
    if declared_skew is not None and seconds != declared_skew:
        problems.append(
            "[REPLAY-ENTRY-006] %s: clock-skew-budget %ds 가 preflight 의 CLOCK_SKEW_SECONDS=%ds 와 다르다"
            % (service, seconds, declared_skew))

for p in problems:
    print(p)
sys.exit(1 if problems else 0)
PYEOF
}

if [[ "${1:-}" == "--self-test" ]]; then
    # 드리프트 검사가 **실제로 red 가 되는지** 고정한다. 정상 트리에서 green 인 것도 함께 본다 —
    # 항상 red 인 lint 는 검사가 아니고, 항상 green 인 lint 는 더 나쁘다.
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    mkdir -p "$tmp/scripts"
    cp scripts/replay-drain-preflight.sh "$tmp/scripts/"
    for svc in order product payment notification; do
        mkdir -p "$tmp/${svc}-service/src/main/java/com/peekcart/global/deadletter" \
                 "$tmp/${svc}-service/src/main/resources"
        cp "${svc}-service/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java" \
           "$tmp/${svc}-service/src/main/java/com/peekcart/global/deadletter/"
        cp "${svc}-service/src/main/resources/application.yml" "$tmp/${svc}-service/src/main/resources/"
    done

    failures=0
    expect() {
        local name="$1" want="$2" needle="$3"
        local out rc
        set +e
        out=$(drift_check "$tmp" 2>&1); rc=$?
        set -e
        if [[ "$rc" != "$want" ]] || { [[ -n "$needle" ]] && [[ "$out" != *"$needle"* ]]; }; then
            echo "self-test 실패: $name (exit $rc, 기대 $want)" >&2
            echo "$out" >&2
            failures=$((failures + 1))
        else
            echo "  ok  $name"
        fi
    }

    expect "무변조 baseline 은 green" 0 ""
    sed -i.bak 's/FixedSequenceBackOff(1_000, 5_000, 30_000)/FixedSequenceBackOff(1_000, 5_000, 60_000)/' \
        "$tmp/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java"
    expect "backoff 를 한 벌만 늘리면 red" 1 "backoff 합"
    cp "$tmp/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java.bak" \
       "$tmp/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java"

    sed -i.bak 's/clock-skew-budget: 5m/clock-skew-budget: 9m/' \
        "$tmp/payment-service/src/main/resources/application.yml"
    expect "clock-skew 를 한 벌만 바꾸면 red" 1 "clock-skew-budget"
    cp "$tmp/payment-service/src/main/resources/application.yml.bak" \
       "$tmp/payment-service/src/main/resources/application.yml"

    sed -i.bak 's/^BACKOFF_TOTAL_SECONDS=36/BACKOFF_TOTAL_SECONDS=99/' "$tmp/scripts/replay-drain-preflight.sh"
    expect "preflight 상한만 바꿔도 red (드리프트는 양방향이다)" 1 "backoff 합"
    cp "$tmp/scripts/replay-drain-preflight.sh.bak" "$tmp/scripts/replay-drain-preflight.sh"

    rm "$tmp/scripts/replay-drain-preflight.sh"
    expect "preflight 가 사라지면 red (상한 정본 소실)" 1 "정본이 사라졌다"

    if ((failures > 0)); then
        echo "replay-entrypoint-lint self-test FAILED (${failures}건)" >&2
        exit 2
    fi
    echo "replay-entrypoint-lint self-test OK — 드리프트 4종"
    exit 0
fi

while IFS= read -r line; do
    violations+=("$line")
done < <(drift_check "." || true)

if ((${#violations[@]} > 0)); then
    printf '%s\n' "${violations[@]}" >&2
    echo "replay-entrypoint-lint FAILED (${#violations[@]}건)" >&2
    exit 1
fi

echo "replay-entrypoint-lint OK — 진입점 4서비스 1개씩, 우회 호출 0, 문서 직접 SQL 0, drain 상한 드리프트 0"
