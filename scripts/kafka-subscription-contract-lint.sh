#!/usr/bin/env bash
# kafka-subscription-contract-lint — @KafkaListener ↔ DlqTopology 정합 (계획 P5)
#
# 무엇을 막는가:
#   DlqTopology.CONSUMPTION 의 group 은 "이 .dlq 의 이 실패 group 을 내가 소유한다" 는 선언이고,
#   그 group 문자열은 **업무 consumer 의 groupId 와 정확히 같아야** 한다. 한쪽만 바뀌면
#   DLQ 원장이 그 실패분을 아무도 소유하지 않는 것으로 판정해 조용히 미결로 쌓인다.
#   E2E readiness 도 같은 집합을 입력으로 쓰므로 어긋나면 준비 판정 자체가 거짓이 된다.
#
# 왜 테스트가 아니라 lint 인가:
#   업무 group 상수는 각 서비스 모듈의 private static final 이라 common 의 테스트가 볼 수 없고,
#   서비스 모듈의 테스트는 다른 서비스를 볼 수 없다. 비교 대상이 4개 모듈의 소스이므로 정적 검사다.
#   (dead-letter-schema-parity-lint 와 같은 판단.)
#
# 무엇을 비교하는가:
#   (1) 업무: 각 서비스 @KafkaListener 의 (topic, groupId) == DlqTopology.businessSubscriptions
#   (2) DLQ intake / quarantine: groupId 가 DlqTopology 상수를 **참조**하는지 + 구독 토픽 집합 일치
#
# 사용: bash scripts/kafka-subscription-contract-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t kafka-subscription-contract-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import os
import re
import sys

root = sys.argv[1]
SERVICES = {
    "order-service": "ORDER",
    "product-service": "PRODUCT",
    "payment-service": "PAYMENT",
    "notification-service": "NOTIFICATION",
}

violations = []

# ---------- DlqTopology 정본 파싱 ----------
topology_path = os.path.join(root, "common/src/main/java/com/peekcart/global/kafka/DlqTopology.java")
topology_src = open(topology_path, encoding="utf-8").read()


def parse_block(kind):
    """CONSUMPTION.put(PeekcartService.X, ...) / QUARANTINE.put(...) 블록을 서비스별로 뽑는다."""
    out = {}
    for m in re.finditer(
        r"%s\.put\(PeekcartService\.([A-Z]+),(.*?)\)\)\);" % kind, topology_src, re.S
    ):
        out[m.group(1)] = m.group(2)
    # notification quarantine 처럼 Set.of() 로 끝나는 형태
    for m in re.finditer(r"%s\.put\(PeekcartService\.([A-Z]+), Set\.of\(\)\);" % kind, topology_src):
        out[m.group(1)] = ""
    return out


consumption_blocks = parse_block("CONSUMPTION")
quarantine_blocks = parse_block("QUARANTINE")

business_expected = {}   # svc -> set of (topic, group)
for svc, block in consumption_blocks.items():
    pairs = set()
    for topic, group in re.findall(r'sub\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', block):
        pairs.add((topic, group))
    business_expected[svc] = pairs

quarantine_expected = {}  # svc -> set of dlq topics
for svc, block in quarantine_blocks.items():
    quarantine_expected[svc] = {
        "%s.dlq" % t for t in re.findall(r'dlq\(\s*"([^"]+)"\s*\)', block)
    }

if not business_expected:
    violations.append("DlqTopology 의 CONSUMPTION 블록을 파싱하지 못했다 — 파서가 낡았다")

# ---------- 서비스 소스의 @KafkaListener 파싱 ----------
# 어노테이션 본문에는 괄호가 없다. `)` 뒤에 @Transactional 같은 다른 어노테이션이 끼므로
# 메서드 선언을 앵커로 삼으면 매칭이 통째로 실패한다(초안이 그랬다).
LISTENER_RE = re.compile(r"@KafkaListener\s*\(([^()]*)\)", re.S)
TOPICS_RE = re.compile(r"topics\s*=\s*(\{[^}]*\}|\"[^\"]*\")", re.S)
GROUP_RE = re.compile(r"groupId\s*=\s*([A-Za-z_][A-Za-z0-9_.]*|\"[^\"]*\")")
CONST_RE = re.compile(r'static\s+final\s+String\s+([A-Z_][A-Z0-9_]*)\s*=\s*\n?\s*"([^"]+)"', re.S)


def java_files(module):
    for dirpath, _, names in os.walk(os.path.join(root, module, "src/main/java")):
        for n in names:
            if n.endswith(".java"):
                yield os.path.join(dirpath, n)


for module, svc in SERVICES.items():
    business_actual = set()
    intake = {}       # group expr -> topics
    quarantine = {}

    for path in java_files(module):
        src = open(path, encoding="utf-8").read()
        if "@KafkaListener" not in src:
            continue
        consts = dict((m.group(1), m.group(2)) for m in CONST_RE.finditer(src))

        for m in LISTENER_RE.finditer(src):
            body = m.group(1)
            tm = TOPICS_RE.search(body)
            gm = GROUP_RE.search(body)
            if not tm or not gm:
                violations.append("%s: @KafkaListener 에 topics/groupId 를 파싱하지 못했다" % path)
                continue
            topics = re.findall(r'"([^"]+)"', tm.group(1))
            group_expr = gm.group(1)

            is_dlq_listener = "deadletter" in path.lower()
            if is_dlq_listener:
                bucket = quarantine if "Quarantine" in os.path.basename(path) else intake
                bucket[group_expr] = set(topics)
                continue

            if group_expr.startswith('"'):
                violations.append(
                    "%s: 업무 listener 가 group literal 을 쓴다 (%s) — 상수로 두어야 대조가 성립한다"
                    % (os.path.relpath(path, root), group_expr))
                group = group_expr.strip('"')
            elif group_expr in consts:
                group = consts[group_expr]
            else:
                violations.append("%s: groupId 상수 %s 의 값을 찾지 못했다"
                                  % (os.path.relpath(path, root), group_expr))
                continue
            for t in topics:
                business_actual.add((t, group))

    expected = business_expected.get(svc, set())
    missing = expected - business_actual
    extra = business_actual - expected
    if missing:
        violations.append("%s: DlqTopology 에는 있으나 @KafkaListener 에 없다 → %s"
                          % (module, sorted(missing)))
    if extra:
        violations.append("%s: @KafkaListener 에는 있으나 DlqTopology 에 없다 → %s"
                          % (module, sorted(extra)))

    # DLQ intake — group 은 DlqTopology 상수를 참조해야 한다(literal 이면 갈라져도 안 잡힌다)
    if len(intake) != 1:
        violations.append("%s: DLQ intake listener 가 정확히 1개여야 한다 (현재 %d)" % (module, len(intake)))
    else:
        (expr, topics), = intake.items()
        # 접두사만 보면 order 와 product 가 **서로의 상수를 바꿔 써도** 통과한다.
        # 서비스별 정확한 상수명으로 동등 비교한다.
        want = "DlqTopology.%s_DLQ_GROUP" % svc
        if expr != want:
            violations.append("%s: DLQ intake group 이 %s 가 아니다 (%s)" % (module, want, expr))
        expected_topics = {t + ".dlq" for t, _ in expected}
        if topics != expected_topics:
            violations.append("%s: DLQ intake 구독 토픽 불일치 — 누락 %s / 초과 %s"
                              % (module, sorted(expected_topics - topics), sorted(topics - expected_topics)))

    # quarantine — notification 은 발행 토픽 0개라 listener 가 없어야 한다
    q_expected = quarantine_expected.get(svc, set())
    if not q_expected:
        if quarantine:
            violations.append("%s: quarantine 대상이 없는데 listener 가 있다" % module)
    else:
        if len(quarantine) != 1:
            violations.append("%s: quarantine listener 가 정확히 1개여야 한다 (현재 %d)" % (module, len(quarantine)))
        else:
            (expr, topics), = quarantine.items()
            want_q = "DlqTopology.%s_DLQ_QUARANTINE_GROUP" % svc
            if expr != want_q:
                violations.append("%s: quarantine group 이 %s 가 아니다 (%s)" % (module, want_q, expr))
            if topics != q_expected:
                violations.append("%s: quarantine 구독 토픽 불일치 — 누락 %s / 초과 %s"
                                  % (module, sorted(q_expected - topics), sorted(topics - q_expected)))

if os.environ.get("EMIT_TOPICS") == "1":
    # 토픽별 **선언된 파티션 수**. 정본은 각 서비스의 TopicBuilder 호출이다.
    #
    # E2E 가 이걸 필요로 하는 이유: 브로커의 `KAFKA_NUM_PARTITIONS=1` 은 **자동 생성**에만
    # 적용된다. 앱의 KafkaAdmin 이 나중에 같은 토픽을 partitions(3) 으로 만들거나 확장하면,
    # 이미 구독 중이던 소비자는 새 파티션을 **메타데이터 갱신 주기(기본 300s)** 가 지나야
    # 발견한다. 그 사이 그 파티션으로 간 메시지는 소비되지 않는다 —
    # 실측: `order.created` 발행 18:59:39 → 소비 19:03:02 (약 203초).
    # 그래서 E2E 는 앱 기동 **전에** 선언된 파티션 수 그대로 토픽을 만들어 둔다.
    topic_re = re.compile(
        r'TopicBuilder\s*\.\s*name\s*\(\s*"([^"]+)"\s*\)\s*\.\s*partitions\s*\(\s*(\d+)\s*\)')
    found = {}
    for module in SERVICES:
        for path in java_files(module):
            with open(path, encoding="utf-8") as f:
                for name, parts in topic_re.findall(f.read()):
                    prev = found.get(name)
                    if prev is not None and prev != int(parts):
                        print("토픽 %s 의 파티션 선언이 %s 와 %s 로 갈린다" % (name, prev, parts),
                              file=sys.stderr)
                        sys.exit(1)
                    found[name] = int(parts)
    if not found:
        print("TopicBuilder 선언을 하나도 못 찾았다 — 파서가 깨졌다", file=sys.stderr)
        sys.exit(1)
    for name in sorted(found):
        print("%s\t%d" % (name, found[name]))
    sys.exit(0)

if os.environ.get("EMIT_GROUPS") == "1":
    # readiness 가 쓸 업무 group 목록. 별도 상수 파일을 두면 정본이 둘이 되므로
    # 같은 파서가 DlqTopology 에서 유도한 값을 그대로 내보낸다(계획 P5).
    if violations:
        print("\n".join("  - %s" % v for v in violations), file=sys.stderr)
        sys.exit(1)
    for svc in sorted(business_expected):
        for _, group in sorted(business_expected[svc]):
            print(group)
    sys.exit(0)

if os.environ.get("EMIT_SUBSCRIPTIONS") == "1":
    # 측정 전 토픽 상태 가드(scripts/kafka-topic-state-guard.py)가 쓴다. group 이 구독 토픽의
    # **모든** 파티션을 받았는지 보려면 group 마다 토픽을 알아야 하고, 측정 overlay 처럼 서비스가
    # 빠진 스택에서는 그 서비스의 group 을 제외해야 한다. 정본은 --emit-groups 와 같은 파서다.
    if violations:
        print("\n".join("  - %s" % v for v in violations), file=sys.stderr)
        sys.exit(1)
    for svc in sorted(business_expected):
        for topic, group in sorted(business_expected[svc]):
            print("%s\t%s\t%s" % (svc.lower(), topic, group))
    sys.exit(0)

if violations:
    print("\n".join("  - %s" % v for v in violations))
    sys.exit(1)

total = sum(len(v) for v in business_expected.values())
print("OK — 업무 구독 %d쌍 · DLQ intake 4 · quarantine 3 정합" % total)
PYEOF

if [[ "${1:-}" == "--self-test" ]]; then
  TMP="$(mktemp -d)"
  cleanup() { rm -rf "$TMP" "$LINT_PY"; }
  trap cleanup EXIT
  fails=0

  seed() {  # 원본 트리를 복사한 뒤 변형한다 — 작업 트리를 건드리지 않는다
    rm -rf "$TMP/repo"; mkdir -p "$TMP/repo"
    for m in common order-service product-service payment-service notification-service; do
      mkdir -p "$TMP/repo/$m"
      cp -R "$m/src" "$TMP/repo/$m/src"
    done
  }

  check() {
    local name="$1"
    if python3 "$LINT_PY" "$TMP/repo" >/dev/null 2>&1; then
      echo "  FAIL [$name] 통과해서는 안 되는 입력이 통과했다"; fails=$((fails + 1))
    else
      echo "  ok   [$name]"
    fi
  }

  echo "kafka-subscription-contract-lint self-test"

  seed
  perl -0pi -e 's/"order-svc-payment-failed-group"/"order-svc-payment-failed-group-TYPO"/' \
    "$TMP/repo/common/src/main/java/com/peekcart/global/kafka/DlqTopology.java"
  check "DlqTopology group 오타 (업무 listener 와 불일치)"

  seed
  perl -0pi -e 's/GROUP_PAYMENT_FAILED = "order-svc-payment-failed-group"/GROUP_PAYMENT_FAILED = "order-svc-payment-failed-group-X"/' \
    "$TMP/repo/order-service/src/main/java/com/peekcart/order/infrastructure/kafka/OrderEventConsumer.java"
  check "업무 consumer group 변경 (DlqTopology 와 불일치)"

  seed
  perl -0pi -e 's/groupId = DlqTopology\.ORDER_DLQ_GROUP/groupId = "order-svc-dlq-group"/' \
    "$TMP/repo/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterConsumer.java"
  check "DLQ intake group 을 literal 로 되돌림"

  seed
  perl -0pi -e 's/groupId = DlqTopology\.ORDER_DLQ_GROUP/groupId = DlqTopology.PRODUCT_DLQ_GROUP/' \
    "$TMP/repo/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterConsumer.java"
  check "DLQ intake 상수를 다른 서비스 것으로 교환"

  seed
  perl -0pi -e 's/groupId = DlqTopology\.PAYMENT_DLQ_QUARANTINE_GROUP/groupId = DlqTopology.ORDER_DLQ_QUARANTINE_GROUP/' \
    "$TMP/repo/payment-service/src/main/java/com/peekcart/global/deadletter/DeadLetterQuarantineConsumer.java"
  check "quarantine 상수를 다른 서비스 것으로 교환"

  seed
  perl -0pi -e 's/"product\.updated\.dlq",?\n//' \
    "$TMP/repo/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterConsumer.java"
  check "DLQ intake 구독 토픽 1개 누락"

  seed
  perl -0pi -e 's/"order\.compensation\.requested\.dlq"/"order.compensation.requested.dlq", "bogus.dlq"/' \
    "$TMP/repo/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterQuarantineConsumer.java"
  check "quarantine 구독 토픽 초과"

  seed
  perl -0pi -e 's/sub\("product\.updated", "order-svc-product-updated-group"\)//' \
    "$TMP/repo/common/src/main/java/com/peekcart/global/kafka/DlqTopology.java"
  check "DlqTopology 에서 업무 구독 1개 삭제"

  seed
  if python3 "$LINT_PY" "$TMP/repo" >/dev/null 2>&1; then
    echo "  ok   [원본 통과]"
  else
    echo "  FAIL [원본 통과] 정상 입력이 실패했다"
    python3 "$LINT_PY" "$TMP/repo" || true
    fails=$((fails + 1))
  fi

  if [[ $fails -gt 0 ]]; then echo "self-test 실패 ${fails}건"; exit 1; fi
  echo "self-test 통과 (9종)"
  exit 0
fi

if [[ "${1:-}" == "--emit-groups" ]]; then
  EMIT_GROUPS=1 python3 "$LINT_PY" "$(pwd)"
  exit 0
fi

if [[ "${1:-}" == "--emit-topics" ]]; then
  EMIT_TOPICS=1 python3 "$LINT_PY" "$(pwd)"
  exit 0
fi

if [[ "${1:-}" == "--emit-subscriptions" ]]; then
  EMIT_SUBSCRIPTIONS=1 python3 "$LINT_PY" "$(pwd)"
  exit 0
fi

if ! python3 "$LINT_PY" "$(pwd)"; then
  echo "::error::[kafka-subscription-contract-lint] @KafkaListener 와 DlqTopology 가 어긋났다 (계획 P5)" >&2
  exit 1
fi
