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
            peekcart-common-messaging/src/main \
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
            peekcart-common-messaging/src/main \
            order-service/src/main product-service/src/main payment-service/src/main notification-service/src/main \
            2>/dev/null || true)

# --- (2) 진입점이 공유 모듈에 있어야 한다 (없으면 4서비스 원장 전부 replay 불가) ---
# 4서비스가 이 모듈을 소비하는지는 컴파일러가 강제한다(ADR-0033).
if [[ ! -f "peekcart-common-messaging/src/main/java/com/peekcart/global/deadletter/DeadLetterReplayService.java" ]]; then
    violations+=("[REPLAY-ENTRY-002] peekcart-common-messaging 에 replay 진입점이 없다")
fi

# The collector's two fixed fixture states use only nonce/immutable-ID owned MySQL.
# Match full source lines, never a whole-file exemption or an operational SQL allowance.
owned_collector_setup() {
    [[ "$1" == "scripts/replay-drain-docker.py" ]] || return 1
    local requested="^        sql\\(\"order\", \"UPDATE[ ]dead_letter_records[ ]SET[ ]publication_status='REQUESTED' WHERE id=1001\"\\)$"
    local settled="^        sql\\(\"order\", \"UPDATE[ ]dead_letter_records[ ]SET[ ]publication_status=NULL,last_replay_settled_at=UTC_TIMESTAMP\\(6\\) WHERE id=1001\"\\)$"
    [[ "$2" =~ $requested || "$2" =~ $settled ]]
}

# --- (3) 문서·스크립트의 직접 상태 변경 SQL 0건 ---
# 계획서(docs/plans)는 설계 논의 중 금지 사례를 인용하므로 제외한다 — 실행 절차가 아니다.
while IFS= read -r hit; do
    hit_file="${hit%%:*}"
    hit_statement="${hit#*:}"
    hit_statement="${hit_statement#*:}"
    owned_collector_setup "$hit_file" "$hit_statement" && continue
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
import math, os, re, sys
from decimal import Decimal, InvalidOperation

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
declared_attempts = constant("ATTEMPTS")
if declared_backoff is None or declared_skew is None:
    problems.append("[REPLAY-ENTRY-006] preflight 에서 상한 상수를 읽지 못했다 "
                    "(BACKOFF_TOTAL_SECONDS / CLOCK_SKEW_SECONDS)")

# backoff 는 4서비스 공유 DeadLetterKafkaConfig 한 벌이다 (ADR-0033).
config = os.path.join(root, "peekcart-common-messaging/src/main/java/com/peekcart/global/deadletter/DeadLetterKafkaConfig.java")
try:
    with open(config, encoding="utf-8") as f:
        body = f.read()
except OSError:
    body = None
    problems.append("[REPLAY-ENTRY-006] peekcart-common-messaging: DeadLetterKafkaConfig.java 가 없다")
# D-076: exact runtime policy and drain upper bound must move together.
# Jitter uses Math.round(nextDouble(base-spread, base+spread)); although the
# floating endpoint is exclusive, rounding can reach it (1500/7500/45000ms).
matches = re.findall(r"new\s+JitteredSequenceBackOff\(([^)]*)\)", body or "")
if len(matches) != 1:
    problems.append("[REPLAY-ENTRY-006] shared DLQ JitteredSequenceBackOff 리터럴이 정확히 한 개가 아니다")
else:
    try:
        args = [v.strip().replace("_", "") for v in matches[0].split(",")]
        ratio = Decimal(args[0])
        millis = [int(v) for v in args[1:]]
        if ratio != Decimal("0.5") or millis != [1000, 5000, 30000]:
            problems.append("[REPLAY-ENTRY-006] jitter 정책은 0.5 및 기준 1/5/30초여야 한다")
        total = math.ceil(sum(math.ceil(Decimal(v) * (1 + ratio)) for v in millis) / 1000)
        if total != declared_backoff:
            problems.append("[REPLAY-ENTRY-006] backoff 최대 합 %ds != BACKOFF_TOTAL_SECONDS=%s — drain 조기 통과 위험"
                            % (total, declared_backoff))
        if declared_attempts != len(millis) + 1 or declared_attempts != 4:
            problems.append("[REPLAY-ENTRY-006] ATTEMPTS는 최초 실행과 재시도 3회를 합한 4여야 한다")
    except (ValueError, InvalidOperation, IndexError):
        problems.append("[REPLAY-ENTRY-006] jitter 비율/간격 리터럴 해석 실패")

for service in services:
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
    cfg_dir="peekcart-common-messaging/src/main/java/com/peekcart/global/deadletter"
    mkdir -p "$tmp/$cfg_dir"
    cp "$cfg_dir/DeadLetterKafkaConfig.java" "$tmp/$cfg_dir/"
    for svc in order product payment notification; do
        mkdir -p "$tmp/${svc}-service/src/main/resources"
        cp "${svc}-service/src/main/resources/application.yml" "$tmp/${svc}-service/src/main/resources/"
    done

    failures=0
    fixture_lines=0
    while IFS= read -r line; do
        [[ "$line" == *'sql("order",'*'publication_status='* ]] || continue
        fixture_lines=$((fixture_lines + 1))
        if ! owned_collector_setup "scripts/replay-drain-docker.py" "$line" \
            || owned_collector_setup "scripts/operational-replay.py" "$line" \
            || owned_collector_setup "scripts/replay-drain-docker.py" "${line/id=1001/id=1002}" \
            || owned_collector_setup "scripts/replay-drain-docker.py" "${line/publication_status/status}"; then
            echo "self-test 실패: exact owned fixture SQL boundary" >&2
            failures=$((failures + 1))
        fi
    done < scripts/replay-drain-docker.py
    if [[ "$fixture_lines" != 2 ]]; then
        echo "self-test 실패: fixture SQL inventory $fixture_lines != 2" >&2
        failures=$((failures + 1))
    fi
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
    sed -i.bak 's/JitteredSequenceBackOff(0.5, 1_000, 5_000, 30_000)/JitteredSequenceBackOff(0.5, 1_000, 5_000, 60_000)/' \
        "$tmp/$cfg_dir/DeadLetterKafkaConfig.java"
    expect "backoff 를 늘리면 red" 1 "backoff 최대 합"
    cp "$tmp/$cfg_dir/DeadLetterKafkaConfig.java.bak" "$tmp/$cfg_dir/DeadLetterKafkaConfig.java"

    sed -i.bak 's/clock-skew-budget: 5m/clock-skew-budget: 9m/' \
        "$tmp/payment-service/src/main/resources/application.yml"
    expect "clock-skew 를 한 벌만 바꾸면 red" 1 "clock-skew-budget"
    cp "$tmp/payment-service/src/main/resources/application.yml.bak" \
       "$tmp/payment-service/src/main/resources/application.yml"

    sed -i.bak 's/^BACKOFF_TOTAL_SECONDS=54/BACKOFF_TOTAL_SECONDS=99/' "$tmp/scripts/replay-drain-preflight.sh"
    expect "preflight 상한만 바꿔도 red (드리프트는 양방향이다)" 1 "backoff 최대 합"
    cp "$tmp/scripts/replay-drain-preflight.sh.bak" "$tmp/scripts/replay-drain-preflight.sh"

    for mutant in '0.8, 1_000, 5_000, 30_000' '0.5, 1_000, 5_000' '0.5, 1_000, 5_000, 30_000, 1_000'; do
        cp "$cfg_dir/DeadLetterKafkaConfig.java" "$tmp/$cfg_dir/DeadLetterKafkaConfig.java"
        sed -i.bak "s/0.5, 1_000, 5_000, 30_000/$mutant/" "$tmp/$cfg_dir/DeadLetterKafkaConfig.java"
        expect "jitter 비율/재시도 횟수 변이 $mutant" 1 "jitter 정책"
    done
    cp "$cfg_dir/DeadLetterKafkaConfig.java" "$tmp/$cfg_dir/DeadLetterKafkaConfig.java"
    sed -i.bak 's/^ATTEMPTS=4/ATTEMPTS=3/' "$tmp/scripts/replay-drain-preflight.sh"
    expect "선언 시도 횟수 감소" 1 "ATTEMPTS"
    cp "$tmp/scripts/replay-drain-preflight.sh.bak" "$tmp/scripts/replay-drain-preflight.sh"
    sed -i.bak 's/^BACKOFF_TOTAL_SECONDS=54/BACKOFF_TOTAL_SECONDS=36/' "$tmp/scripts/replay-drain-preflight.sh"
    expect "기존 36초 상한 회귀" 1 "backoff 최대 합"
    cp "$tmp/scripts/replay-drain-preflight.sh.bak" "$tmp/scripts/replay-drain-preflight.sh"

    rm "$tmp/scripts/replay-drain-preflight.sh"
    expect "preflight 가 사라지면 red (상한 정본 소실)" 1 "정본이 사라졌다"

    if ((failures > 0)); then
        echo "replay-entrypoint-lint self-test FAILED (${failures}건)" >&2
        exit 2
    fi
    echo "replay-entrypoint-lint self-test OK — 드리프트 9종"
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

echo "replay-entrypoint-lint OK — 진입점 공유 모듈 1개, 우회 호출 0, 문서 직접 SQL 0, drain 상한 드리프트 0"
