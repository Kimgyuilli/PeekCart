#!/usr/bin/env bash
# integration-test-container-lint.sh — D-032 컨테이너 싱글톤 규약 가드
#
# 목적:
#   서비스 모듈 테스트가 Testcontainers 컨테이너를 **직접 선언**하지 못하게 한다.
#   공유 선언은 common testFixtures 의 SharedContainers 하나다.
#
# 왜:
#   ADR-0028 이 컨테이너 수명을 per-class 에서 모듈 싱글톤으로 바꿨다. 그런데 이 규약은
#   코드로 강제하지 않으면 **반년 뒤 원상복구된다** — 새 통합 테스트가 관행대로
#   @Container 를 선언하면 그 클래스만 조용히 컨테이너를 다시 띄우고, CI 는 초록이며,
#   :order-service:test 는 다시 우상향한다. 실측으로 그 비용이 클래스당 약 31초였다.
#
#   화이트리스트는 **사유와 함께** 이 파일 안에 둔다. 목록만 있으면 왜 예외인지 모르는 채
#   항목이 늘어난다.
#
# 사용: bash scripts/integration-test-container-lint.sh [--self-test]
#
# 종료:
#   0 - 위반 없음
#   1 - 위반
#   2 - 필요한 도구 부재
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! command -v python3 >/dev/null 2>&1; then
    echo "[ITC-000] python3 부재" >&2
    exit 2
fi

# 검사 대상 — 서비스 모듈의 test 소스. 픽스처(--self-test)도 이 목록을 순회하므로 대상을 넓힐 때
# 여기 한 줄만 고친다. 모듈이 사라지거나 이름이 바뀌면 검사가 조용히 증발하므로, 존재하지 않는
# 모듈은 위반으로 센다(vacuous-green 차단).
MODULES=(order-service user-service notification-service)

LINT_PY="$(mktemp -t integration-test-container-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import os
import re
import sys

root = sys.argv[1]
MODULES = sys.argv[2:]

# 화이트리스트: 파일명 -> 사유. 사유 없는 항목은 허용하지 않는다.
ALLOWED = {
    "OrderCursorQueryPlanTest.java":
        "mysql:8.0.46 을 의도적으로 고정한다 — EXPLAIN 실행계획이 마이너 버전에 따라 달라져 "
        "싱글톤(mysql:8.0)과 공유할 수 없다.",
}

violations = []


def bad(code, message):
    violations.append("[%s] %s" % (code, message))


for module in MODULES:
    test_root = os.path.join(root, module, "src", "test")
    if not os.path.isdir(test_root):
        bad("ITC-001", "검사 대상 '%s' 가 없다 — 모듈 이름이 바뀌었거나 삭제됐다. "
                       "대상 부재를 통과로 읽지 않는다" % test_root)
        continue

    found = []
    for dirpath, _, filenames in os.walk(test_root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            text = open(path, encoding="utf-8").read()
            # 주석/javadoc 안의 언급은 위반이 아니다 — 줄 선두 어노테이션만 본다.
            has_container = re.search(r"^\s*@Container\b", text, re.M)
            has_tc = re.search(r"^\s*@Testcontainers\b", text, re.M)
            if has_container or has_tc:
                found.append(fn)

    for fn in sorted(found):
        if fn in ALLOWED:
            continue
        bad("ITC-002",
            "%s/%s 가 컨테이너를 직접 선언한다. "
            "@Import(SharedContainers.class) 를 쓴다 (ADR-0028). "
            "예외가 필요하면 이 스크립트의 ALLOWED 에 **사유와 함께** 등록한다" % (module, fn))

# 화이트리스트가 실제 파일을 가리키는지 — 이름이 바뀌면 예외가 유령이 된다.
for fn, reason in ALLOWED.items():
    if not reason.strip():
        bad("ITC-003", "화이트리스트 항목 '%s' 에 사유가 없다" % fn)
    hit = False
    for module in MODULES:
        for dirpath, _, filenames in os.walk(os.path.join(root, module, "src", "test")):
            if fn in filenames:
                hit = True
    if not hit:
        bad("ITC-004", "화이트리스트 항목 '%s' 에 해당하는 파일이 없다 — 유령 예외다" % fn)

if violations:
    print("\n".join(violations))
    sys.exit(1)

print("integration-test-container-lint: 직접 선언 0건 (예외 %d건, 사유 명시)" % len(ALLOWED))
PYEOF

run_lint() {
    python3 "$LINT_PY" "$1" "${MODULES[@]}"
}

self_test() {
    local tmp failures=0
    tmp="$(mktemp -d)"

    _case() {
        local name="$1" expect_code="$2" fixture="$3"
        local out rc=0
        out="$(run_lint "$fixture" 2>&1)" || rc=$?
        if [[ "$expect_code" == "NONE" ]]; then
            if [[ $rc -ne 0 ]]; then
                echo "  ✗ $name: 통과해야 하는데 실패했다"
                printf '%s\n' "$out" | sed 's/^/      /'
                failures=$((failures + 1))
            else
                echo "  ✓ $name"
            fi
            return
        fi
        if [[ $rc -eq 0 ]]; then
            echo "  ✗ $name: 위반인데 통과했다 (false-green)"
            failures=$((failures + 1))
            return
        fi
        if ! printf '%s\n' "$out" | grep -q "\[${expect_code}\]"; then
            echo "  ✗ $name: ${expect_code} 를 기대했으나 다른 위반이 나왔다"
            printf '%s\n' "$out" | sed 's/^/      /'
            failures=$((failures + 1))
            return
        fi
        echo "  ✓ $name (${expect_code})"
    }

    # mode: clean | new-container | module-new-container:<모듈> | testcontainers-revived | no-module | ghost-allow
    _fixture() {
        local dir="$1" mode="$2" m
        local t="$dir/order-service/src/test/java/com/peekcart"
        [[ "$mode" == "no-module" ]] && { mkdir -p "$dir/other"; return; }
        for m in "${MODULES[@]}"; do
            mkdir -p "$dir/$m/src/test/java/com/peekcart"
        done
        cat > "$t/SomethingIntegrationTest.java" <<'JAVA'
@SpringBootTest
@Import(SharedContainers.class)
class SomethingIntegrationTest {
    // @Container 라는 문자열이 주석에 있어도 위반이 아니다
}
JAVA
        # 각 모듈이 실제로 검사되는지 — 목록에서 빠지면 그 모듈 케이스가 false-green 이 된다
        if [[ "$mode" == module-new-container:* ]]; then
            cat > "$dir/${mode#module-new-container:}/src/test/java/com/peekcart/NewIntegrationTest.java" <<'JAVA'
@SpringBootTest
@Testcontainers
class NewIntegrationTest {
}
JAVA
        fi
        if [[ "$mode" == "new-container" ]]; then
            cat > "$t/NewlyAddedIntegrationTest.java" <<'JAVA'
@SpringBootTest
class NewlyAddedIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
}
JAVA
        fi
        if [[ "$mode" == "testcontainers-revived" ]]; then
            cat > "$t/RevivedTest.java" <<'JAVA'
@Testcontainers
class RevivedTest {
}
JAVA
        fi
        # 화이트리스트 대상 파일 — ghost-allow 모드에서는 만들지 않는다
        if [[ "$mode" != "ghost-allow" ]]; then
            cat > "$t/OrderCursorQueryPlanTest.java" <<'JAVA'
@DataJpaTest
@Testcontainers
class OrderCursorQueryPlanTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.46");
}
JAVA
        fi
    }

    echo "integration-test-container-lint --self-test"
    _case "정상 배선(현 저장소)" NONE "$(pwd)"
    _fixture "$tmp/c0" clean
    _case "정상 배선(픽스처)" NONE "$tmp/c0"
    _fixture "$tmp/c1" new-container
    _case "새 테스트가 @Container 선언" ITC-002 "$tmp/c1"
    _fixture "$tmp/c2" testcontainers-revived
    _case "@Testcontainers 부활" ITC-002 "$tmp/c2"
    _fixture "$tmp/c3" no-module
    _case "검사 대상 모듈 소멸" ITC-001 "$tmp/c3"
    local m
    for m in "${MODULES[@]}"; do
        _fixture "$tmp/m-$m" "module-new-container:$m"
        _case "$m 테스트가 @Testcontainers 선언" ITC-002 "$tmp/m-$m"
    done
    _fixture "$tmp/c4" ghost-allow
    _case "화이트리스트가 유령 파일을 가리킴" ITC-004 "$tmp/c4"

    rm -rf "$tmp"
    if [[ $failures -gt 0 ]]; then
        echo "self-test 실패 ${failures}건"
        return 1
    fi
    echo "self-test OK ($((6 + ${#MODULES[@]}))/$((6 + ${#MODULES[@]})))"
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
else
    run_lint "$(pwd)"
fi
