#!/usr/bin/env bash
# integration-test-container-lint.sh — D-032 컨테이너 싱글톤 규약 가드
#
# 목적:
#   모듈 테스트가 Testcontainers 컨테이너를 **직접 선언**하지 못하게 한다.
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
#   리스너를 켜는 테스트(속성 opt-in 또는 수동 start)는 발행 전에 awaitListenerAssignment 로
#   파티션 할당을 기다려야 한다(D-058). 할당 전에 발행하면 group join 이 await 예산을 먹거나
#   발행이 조용히 실패해, 원인 없이 timeout 으로만 보이는 간헐 실패가 된다.
#   자율 writer를 켜는 클래스는 DirtiesContext(AFTER_CLASS)로 수명을 제한한다(ADR-0029).
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

# 검사 대상 — settings.gradle 에 include 된 모듈 중 test 소스가 있는 것 전부. 픽스처(--self-test)도
# 이 목록을 순회하므로 대상을 넓힐 때 여기 한 줄만 고친다. 모듈이 사라지거나 이름이 바뀌면 검사가
# 조용히 증발하므로, 존재하지 않는 모듈은 위반으로 센다(vacuous-green 차단). 반대로 새 모듈이 생겼는데
# 여기 없으면 그 모듈이 조용히 검사 밖에 남으므로, settings.gradle 과 대조해 누락도 위반으로 센다.
# SharedContainers 는 common/src/testFixtures 에 있어 src/test 스캔 대상이 아니다.
MODULES=(common peekcart-common-auth peekcart-common-messaging gateway order-service user-service notification-service payment-service product-service)

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
        "EXPLAIN 전용 DataJpaTest 는 MySQL 만 필요하다. SharedContainers 를 import 하면 "
        "불필요한 Kafka/Redis 까지 기동되므로 같은 mysql:8.0.46 의 독립 컨테이너를 허용한다.",
}

violations = []
DIRECT = re.compile(r"^\s*@(?:org\.testcontainers\.[\w.]+\.)?(?:Container|Testcontainers)\b", re.M)
# Java 주석과 리터럴을 구분한다. 위치를 보존해 annotation 안의 속성만 따로 읽는다.
JAVA_TOKEN = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
PROPERTY_ON = re.compile(r'^\s*app\.(kafka\.listener|scheduling)\.enabled\s*[=:]\s*true\s*$', re.I)
LISTENER_TYPE = r'(?:MessageListenerContainer|(?:Concurrent|Kafka)MessageListenerContainer)'


def java_source(text):
    literals = []
    def mask(match):
        token = match.group()
        if token.startswith('"'):
            literals.append((match.start(), token.strip('"')))
        return re.sub(r'[^\n]', ' ', token)
    return JAVA_TOKEN.sub(mask, text), literals


def writer_properties(code, literals):
    # ADR-0029 opt-in 경로. 일반 문자열 변수에 적힌 설명은 실행 설정이 아니다.
    ranges = [(m.start(), m.end()) for m in re.finditer(
        r'@(?:[\w.]+\.)?(?:TestPropertySource|SpringBootTest)\s*\([^)]*\)', code)]
    return {m.group(1).lower() for pos, literal in literals
            if any(start <= pos < end for start, end in ranges)
            for line in literal.splitlines() if (m := PROPERTY_ON.fullmatch(line))}


def manual_listener_start(code):
    # 이름만 container인 서버/Testcontainer.start()는 Kafka 기동이 아니다.
    if re.search(r'\b' + LISTENER_TYPE + r'\s*::\s*start\b', code):
        return True
    registries = set(re.findall(r'\bKafkaListenerEndpointRegistry\s+(\w+)', code))
    containers = set(re.findall(r'\b' + LISTENER_TYPE + r'(?:\s*<[^;=()]+>)?\s+(\w+)', code))
    collections = set()
    for registry in registries:
        getter = r'\b' + re.escape(registry) + r'\s*\.\s*getListenerContainer(?:s)?\s*\([^)]*\)'
        if re.search(getter + r'\s*\.\s*start\s*\(', code):
            return True
        containers.update(re.findall(r'\b(\w+)\s*=\s*' + getter, code))
        collections.update(re.findall(r'\b(\w+)\s*=\s*' + getter + r'[^;]*', code))
        # registry.getListenerContainers().stream().filter(...).forEach(c -> c.start())
        for chain in re.finditer(getter + r'[^;]*', code):
            if re.search(r'\b(\w+)\s*->\s*\1\s*\.\s*start\s*\(', chain.group()):
                return True
    for collection in collections:
        for chain in re.finditer(r'\b' + re.escape(collection) + r'\s*\.[^;]*', code):
            if re.search(r'\b(\w+)\s*->\s*\1\s*\.\s*start\s*\(', chain.group()):
                return True
    return any(re.search(r'\b' + re.escape(name) + r'\s*(?:\.\s*start\s*\(|::\s*start\b)', code)
               for name in containers | registries)


def closes_class_context(code):
    # 메서드의 DirtiesContext는 클래스 종료 계약을 충족하지 않는다.
    declaration = re.search(r'\bclass\s+\w+', code)
    header = code[:declaration.start()] if declaration else ''
    annotation = re.search(r'@(?:org\.springframework\.test\.annotation\.)?DirtiesContext\b(?:\s*\(([^)]*)\))?', header)
    if not annotation:
        return False
    args = annotation.group(1) or ''
    mode = re.search(r'\bclassMode\s*=\s*([\w.]+)', args)
    # Spring 기본 classMode는 AFTER_CLASS. methodMode는 이 값을 바꾸지 않는다.
    return mode is None or mode.group(1).split('.')[-1] == 'AFTER_CLASS'



def bad(code, message):
    violations.append("[%s] %s" % (code, message))


for module in MODULES:
    test_root = os.path.join(root, module, "src", "test")
    if not os.path.isdir(test_root):
        bad("ITC-001", "검사 대상 '%s' 가 없다 — 모듈 이름이 바뀌었거나 삭제됐다. "
                       "대상 부재를 통과로 읽지 않는다" % test_root)
        continue

    found = []
    no_wait = []
    no_close = []
    for dirpath, _, filenames in os.walk(test_root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            text = open(path, encoding="utf-8").read()
            code, literals = java_source(text)
            if DIRECT.search(code):
                found.append(fn)
            properties = writer_properties(code, literals)
            listener_on = "kafka.listener" in properties or manual_listener_start(code)
            if listener_on and not re.search(r"\bawaitListenerAssignment\s*\(", code):
                no_wait.append(fn)
            if (listener_on or "scheduling" in properties) and not closes_class_context(code):
                no_close.append(fn)

    for fn in sorted(found):
        if fn in ALLOWED:
            continue
        bad("ITC-002",
            "%s/%s 가 컨테이너를 직접 선언한다. "
            "@Import(SharedContainers.class) 를 쓴다 (ADR-0028). "
            "예외가 필요하면 이 스크립트의 ALLOWED 에 **사유와 함께** 등록한다" % (module, fn))

    for fn in sorted(no_wait):
        bad("ITC-006",
            "%s/%s 가 리스너를 켜지만 awaitListenerAssignment 를 호출하지 않는다. "
            "@BeforeEach 에서 발행 전에 파티션 할당을 기다린다 (D-058)" % (module, fn))

    for fn in sorted(no_close):
        bad("ITC-007",
            "%s/%s 가 자율 writer 를 켜지만 클래스 @DirtiesContext(AFTER_CLASS)가 없다. "
            "캐시된 context 의 writer 수명을 클래스로 제한한다 (ADR-0029 D3)" % (module, fn))

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

# 목록 누락 — include 됐고 test 소스가 있는데 MODULES 에 없는 모듈은 검사 밖에 남는다.
settings = os.path.join(root, "settings.gradle")
if not os.path.isfile(settings):
    bad("ITC-005", "settings.gradle 이 없다 — 검사 대상 누락을 대조할 기준이 없다")
else:
    included = re.findall(r"^\s*include\s+['\"]([^'\"]+)['\"]", open(settings, encoding="utf-8").read(), re.M)
    for module in included:
        if module not in MODULES and os.path.isdir(os.path.join(root, module, "src", "test")):
            bad("ITC-005", "모듈 '%s' 는 settings.gradle 에 include 됐고 test 소스가 있는데 검사 대상(MODULES)에 "
                           "없다 — 이 스크립트의 MODULES 에 추가한다" % module)

if violations:
    print("\n".join(violations))
    sys.exit(1)

print("integration-test-container-lint: 직접 선언 0건 (예외 %d건, 사유 명시) · 리스너 할당 대기 누락 0건 · writer 수명 누락 0건" % len(ALLOWED))
PYEOF

run_lint() {
    python3 "$LINT_PY" "$1" "${MODULES[@]}"
}

self_test() {
    local tmp failures=0 cases=0
    tmp="$(mktemp -d)"

    _case() {
        local name="$1" expect_code="$2" fixture="$3"
        local out rc=0
        cases=$((cases + 1))
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

    # mode: clean | new-container | module-new-container:<모듈> | testcontainers-revived | fqn-testcontainers
    #       | unlisted-module | no-module | ghost-allow | listener-no-wait
    _fixture() {
        local dir="$1" mode="$2" m
        local t="$dir/order-service/src/test/java/com/peekcart"
        [[ "$mode" == "no-module" ]] && { mkdir -p "$dir/other"; return; }
        for m in "${MODULES[@]}"; do
            mkdir -p "$dir/$m/src/test/java/com/peekcart"
            echo "include '$m'" >> "$dir/settings.gradle"
        done
        # test 소스가 없는 모듈은 목록에 없어도 된다
        mkdir -p "$dir/no-test-module/src/main"
        echo "include 'no-test-module'" >> "$dir/settings.gradle"
        if [[ "$mode" == "unlisted-module" ]]; then
            mkdir -p "$dir/new-service/src/test/java"
            echo "include 'new-service'" >> "$dir/settings.gradle"
        fi
        cat > "$t/SomethingIntegrationTest.java" <<'JAVA'
@SpringBootTest
@Import(SharedContainers.class)
class SomethingIntegrationTest {
    // @Container 라는 문자열이 주석에 있어도 위반이 아니다
}
JAVA
        # 리스너를 켜고 할당을 기다리는 정상 형태 — clean 을 포함해 모든 픽스처에 있다
        cat > "$t/ListenerIntegrationTest.java" <<'JAVA'
@SpringBootTest
@TestPropertySource(properties = {"app.kafka.listener.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListenerIntegrationTest {
    @BeforeEach
    void setUp() {
        awaitListenerAssignment(listenerRegistry);
    }
}
JAVA
        if [[ "$mode" == "listener-no-wait" ]]; then
            cat > "$t/ListenerNoWaitIntegrationTest.java" <<'JAVA'
@SpringBootTest
// 주석 속 "app.kafka.listener.enabled=false" 는 판정에 쓰이지 않는다
@TestPropertySource(properties = {"spring.flyway.enabled=true", "app.kafka.listener.enabled=true"})
class ListenerNoWaitIntegrationTest {
}
JAVA
        fi
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
        if [[ "$mode" == "fqn-testcontainers" ]]; then
            cat > "$t/FqnTest.java" <<'JAVA'
@org.testcontainers.junit.jupiter.Testcontainers
class FqnTest {
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
    _fixture "$tmp/c5" fqn-testcontainers
    _case "FQN 으로 @Testcontainers 선언" ITC-002 "$tmp/c5"
    _fixture "$tmp/c6" unlisted-module
    _case "test 소스가 있는 모듈이 검사 목록에서 빠짐" ITC-005 "$tmp/c6"
    _fixture "$tmp/c3" no-module
    _case "검사 대상 모듈 소멸" ITC-001 "$tmp/c3"
    local m
    for m in "${MODULES[@]}"; do
        _fixture "$tmp/m-$m" "module-new-container:$m"
        _case "$m 테스트가 @Testcontainers 선언" ITC-002 "$tmp/m-$m"
    done
    _fixture "$tmp/c7" listener-no-wait
    _case "리스너를 켜고 할당 대기 없음" ITC-006 "$tmp/c7"
    _fixture "$tmp/c4" ghost-allow
    _case "화이트리스트가 유령 파일을 가리킴" ITC-004 "$tmp/c4"


    # 한 계약씩 훼손한다. 다른 위반 때문에 red가 되는 false-positive를 막기 위해
    # listener 케이스는 수명을 갖추고, 수명 케이스는 할당 대기를 갖춘다.
    _writer_case() {
        local name="$1" expected="$2" source="$3"
        local fixture="$tmp/writer-$cases"
        _fixture "$fixture" clean
        printf '%s\n' "$source" > "$fixture/order-service/src/test/java/com/peekcart/WriterTest.java"
        _case "$name" "$expected" "$fixture"
    }
    local property mode startup
    for property in 'app.kafka.listener.enabled=true' 'app.kafka.listener.enabled = true' 'app.kafka.listener.enabled:true'; do
        _writer_case "속성 표기: $property" ITC-006 "
@TestPropertySource(properties = {\"$property\"})
@DirtiesContext
class WriterTest {}"
    done
    for startup in \
        'containers.forEach(MessageListenerContainer::start);' \
        'containers.forEach(org.springframework.kafka.listener.MessageListenerContainer :: start);' \
        'MessageListenerContainer listener; void boot() { listener.start(); }' \
        'ConcurrentMessageListenerContainer<String, String> listener; void boot() { listener.start(); }' \
        'KafkaListenerEndpointRegistry registry; void boot() { registry.start(); }' \
        'KafkaListenerEndpointRegistry registry; void boot() { registry.getListenerContainer("dlq").start(); }' \
        'KafkaListenerEndpointRegistry registry; void boot() { registry.getListenerContainers().forEach(c -> c.start()); }' \
        'KafkaListenerEndpointRegistry registry; void boot() { var containers = registry.getListenerContainers().stream().filter(c -> true).toList(); containers.forEach(c -> c.start()); }'; do
        _writer_case "수동 기동 대기 누락: $startup" ITC-006 "
@DirtiesContext
class WriterTest { $startup }"
        _writer_case "수동 기동 수명 누락: $startup" ITC-007 "
class WriterTest { $startup void setup() { awaitListenerAssignment(registry); } }"
    done
    _writer_case '수동 기동 + 대기 + 클래스 수명 정상' NONE '
@DirtiesContext
class WriterTest {
    KafkaListenerEndpointRegistry registry;
    void setup() {
        var selected = registry.getListenerContainers();
        selected.forEach(MessageListenerContainer::start);
        awaitListenerAssignment(selected);
    }
}'
    for property in 'app.scheduling.enabled = true' 'app.scheduling.enabled:true' 'app.kafka.listener.enabled=true'; do
        for mode in '' '@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)' '@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)'; do
            _writer_case "writer 수명 누락/잘못된 mode: $property $mode" ITC-007 "
@TestPropertySource(properties = {\"$property\"})
$mode
class WriterTest { void setup() { awaitListenerAssignment(registry); } }"
        done
    done
    for mode in '@DirtiesContext' '@DirtiesContext()' '@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)' '@org.springframework.test.annotation.DirtiesContext(classMode = AFTER_CLASS)'; do
        _writer_case "AFTER_CLASS 정상: $mode" NONE "
@TestPropertySource(properties = {\"app.kafka.listener.enabled = true\", \"app.scheduling.enabled:true\"})
$mode
class WriterTest { void setup() { awaitListenerAssignment (registry); } }"
    done
    _writer_case '주석/문자열 속 대기는 호출이 아님' ITC-006 '
@TestPropertySource(properties = {"app.kafka.listener.enabled=true"})
@DirtiesContext
class WriterTest {
    // awaitListenerAssignment(registry);
    /* awaitListenerAssignment(registry); */
    String explanation = "awaitListenerAssignment(registry)";
}'
    _writer_case '주석/문자열/메서드 annotation은 클래스 수명 아님' ITC-007 '
@TestPropertySource(properties = {"app.scheduling.enabled=true"})
// @DirtiesContext
/* @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) */
class WriterTest {
    String explanation = "@DirtiesContext";
    @DirtiesContext void method() {}
}'
    _writer_case '주석/일반 문자열/false/무관한 start는 writer opt-in 아님' NONE '
/*
@TestPropertySource(properties = {"app.kafka.listener.enabled=true"})
@Testcontainers
MessageListenerContainer::start
*/
// @TestPropertySource(properties = {"app.scheduling.enabled=true"})
@TestPropertySource(properties = {"app.kafka.listener.enabled=false"})
class WriterTest {
    KafkaListenerEndpointRegistry registry;
    String explanation = "app.kafka.listener.enabled=true";
    String example = "MessageListenerContainer::start";
    void setup() { server.start(); backOff.start(); container.start(); }
}'
    _writer_case 'SpringBootTest 인라인 속성도 검사' ITC-007 '
@SpringBootTest(properties = {"app.scheduling.enabled:true"})
class WriterTest {}'
    _writer_case 'text block 인라인 속성도 검사' ITC-007 '
@TestPropertySource(properties = """
    app.scheduling.enabled = true
    """)
class WriterTest {}'

    rm -rf "$tmp"
    if [[ $failures -gt 0 ]]; then
        echo "self-test 실패 ${failures}건"
        return 1
    fi
    echo "self-test OK ($cases/$cases)"
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
else
    run_lint "$(pwd)"
fi
