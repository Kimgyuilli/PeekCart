#!/usr/bin/env python3
"""kafka-topic-state-guard 의 판정·진입점 fixture 와 config 기대값 드리프트 검사 (D-106 ⑩, 계획 V1~V4).

Docker 없이 돈다. 수집은 클러스터가 필요하지만 판정은 순수 함수이고, 진입점은 가짜 exec 로 관통한다.
토픽·구독 목록은 실제 lint emit 을 쓰므로 토폴로지가 바뀌면 fixture 도 따라 바뀐다.
"""
import importlib.util
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
GUARD_PATH = ROOT / "scripts/kafka-topic-state-guard.py"
spec = importlib.util.spec_from_file_location("guard", GUARD_PATH)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

# GKE S4(2026-10-24) 의 `kafka-configs.sh --describe --entity-type topics --all` 원 출력.
SNAPSHOT = ROOT / "loadtest/reports/2026-10-24/s4/d065-5-topic-configs-all.txt"
# 손 대조가 브로커 기본값으로 판정한 토픽(docs/progress/evidence/d078-gke-20261024.md §S4).
SNAPSHOT_DEFAULT_TOPICS = {"payment.completed", "payment.requested", "payment.completed.dlq",
                           "payment.failed.dlq", "payment.refunded.dlq", "payment.requested.dlq"}
# 선언 config 가 없으면 브로커 기본값과 달라지는 4키. 나머지 3키는 기본값이 선언과 같다.
DEFAULT_DIFF_KEYS = {"retention.bytes", "segment.bytes", "segment.ms", "message.timestamp.before.max.ms"}

DRIFT_SOURCES = [
    "common/src/main/java/com/peekcart/global/kafka/KafkaTopicConfigs.java",
    "common/src/main/java/com/peekcart/global/retention/IdempotencyRetentionProperties.java",
    "order-service/src/main/resources/application.yml",
    "product-service/src/main/resources/application.yml",
    "payment-service/src/main/resources/application.yml",
    "order-service/src/main/java/com/peekcart/order/infrastructure/kafka/OrderKafkaConfig.java",
    "product-service/src/main/java/com/peekcart/product/infrastructure/kafka/ProductKafkaConfig.java",
    "payment-service/src/main/java/com/peekcart/payment/infrastructure/kafka/PaymentKafkaConfig.java",
]
TOPIC_OWNERS = ("order", "product", "payment")


class DerivationError(Exception):
    pass


def _need(pattern, text, what):
    m = re.search(pattern, text, re.M)
    if m is None:
        raise DerivationError(f"유도 실패 — {what} 를 찾지 못했다")
    return m


def _duration_ms(amount, unit):
    return int(amount) * {"s": 1_000, "m": 60_000, "h": 3_600_000, "d": 86_400_000}[unit]


def derive_expected(root):
    """가드의 EXPECTED_CONFIGS 를 소스에서 다시 만든다. 선언 경로가 바뀌면 DerivationError."""
    src = (root / DRIFT_SOURCES[0]).read_text(encoding="utf-8")
    longs = {}
    for name, expr in re.findall(r"static final long (\w+) = ([0-9L*\s]+);", src):
        value = 1
        for factor in expr.split("*"):
            value *= int(factor.strip().rstrip("L"))
        longs[name] = value
    for name in ("BUSINESS_RETENTION_BYTES", "DLQ_RETENTION_BYTES", "BUSINESS_SEGMENT_BYTES", "DLQ_SEGMENT_BYTES"):
        if name not in longs:
            raise DerivationError(f"유도 실패 — {name} 상수를 찾지 못했다")
    roll_days = int(_need(r"SEGMENT_ROLL = Duration\.ofDays\((\d+)\);", src, "SEGMENT_ROLL").group(1))
    keys = re.findall(r'configs\.put\("([^"]+)"', src)
    if sorted(keys) != sorted(guard.EXPECTED_CONFIGS["business"]):
        raise DerivationError(f"유도 실패 — put 키 집합이 가드 표와 다르다: {sorted(keys)}")
    for fragment in ("configs(retention, timestampBeforeMax, BUSINESS_RETENTION_BYTES, BUSINESS_SEGMENT_BYTES)",
                     "configs(retention, timestampBeforeMax, DLQ_RETENTION_BYTES, DLQ_SEGMENT_BYTES)",
                     'configs.put("retention.ms", String.valueOf(retention.toMillis()))',
                     'configs.put("retention.bytes", String.valueOf(retentionBytes))',
                     'configs.put("segment.bytes", String.valueOf(segmentBytes))',
                     'configs.put("segment.ms", String.valueOf(SEGMENT_ROLL.toMillis()))',
                     'configs.put("message.timestamp.before.max.ms", String.valueOf(timestampBeforeMax.toMillis()))'):
        _need(re.escape(fragment), src, fragment)
    cleanup = _need(r'configs\.put\("cleanup\.policy", "([^"]+)"\)', src, "cleanup.policy 리터럴").group(1)
    ts_type = _need(r'configs\.put\("message\.timestamp\.type", "([^"]+)"\)', src, "timestamp.type 리터럴").group(1)

    props = (root / DRIFT_SOURCES[1]).read_text(encoding="utf-8")
    _need(r"public Duration topicMessageTimestampBeforeMax\(\) \{\s*return retention;\s*\}", props,
          "topicMessageTimestampBeforeMax() 의 return retention")

    retentions, before_maxes = set(), set()
    for owner, yml_path, config_path in zip(TOPIC_OWNERS, DRIFT_SOURCES[2:5], DRIFT_SOURCES[5:8]):
        yml = (root / yml_path).read_text(encoding="utf-8")
        retentions.add(_duration_ms(*_need(r"^\s+kafka-topic-retention:\s*(\d+)([smhd])\b", yml,
                                           f"{owner} kafka-topic-retention").groups()))
        before_maxes.add(_duration_ms(*_need(r"^  idempotency:\n    retention:\s*(\d+)([smhd])\b", yml,
                                             f"{owner} app.idempotency.retention").groups()))
        java = (root / config_path).read_text(encoding="utf-8")
        for kind in ("business", "dlq"):
            _need(rf"KafkaTopicConfigs\.{kind}\(retentionProperties\.getFloor\(\)\.getKafkaTopicRetention\(\),"
                  r"\s*retentionProperties\.topicMessageTimestampBeforeMax\(\)\)", java, f"{owner} {kind} 인자 경로")
        builders = re.findall(r'TopicBuilder\.name\("([^"]+)"\)[^;]*?\.configs\((business|dlq)Configs\(\)\)', java)
        if not builders or len(builders) != java.count("TopicBuilder.name("):
            raise DerivationError(f"유도 실패 — {owner} 의 TopicBuilder 중 config 종류를 읽지 못한 선언이 있다")
        for topic, kind in builders:
            if (kind == "dlq") != topic.endswith(".dlq"):
                raise DerivationError(f"유도 실패 — {topic} 의 config 종류({kind})가 .dlq 접미사 규칙과 다르다")
    if len(retentions) != 1 or len(before_maxes) != 1:
        raise DerivationError(f"유도 실패 — 서비스별 보존값이 갈린다: {retentions} / {before_maxes}")

    common = {"retention.ms": str(retentions.pop()), "cleanup.policy": cleanup,
              "segment.ms": str(roll_days * 86_400_000), "message.timestamp.type": ts_type,
              "message.timestamp.before.max.ms": str(before_maxes.pop())}
    return {"business": {**common, "retention.bytes": str(longs["BUSINESS_RETENTION_BYTES"]),
                         "segment.bytes": str(longs["BUSINESS_SEGMENT_BYTES"])},
            "dlq": {**common, "retention.bytes": str(longs["DLQ_RETENTION_BYTES"]),
                    "segment.bytes": str(longs["DLQ_SEGMENT_BYTES"])}}


# ---------------------------------------------------------------- 합성 출력 (Kafka 3.8.1 실제 형식)

def topics_out(counts):
    lines = []
    for topic, parts in counts.items():
        lines.append(f"Topic: {topic}\tTopicId: AAAAAAAAAAAAAAAAAAAAAA\tPartitionCount: {parts}\t"
                     f"ReplicationFactor: 1\tConfigs: cleanup.policy=delete")
        lines += [f"\tTopic: {topic}\tPartition: {p}\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: "
                  for p in range(parts)]
    return "\n".join(lines) + "\n"


def configs_out(topics, override=None):
    """override: {topic: {key: value}} 로 값을 바꾼다. value 가 None 이면 그 키 줄을 뺀다."""
    lines = []
    for topic in topics:
        values = dict(guard.EXPECTED_CONFIGS["dlq" if topic.endswith(".dlq") else "business"])
        values.update((override or {}).get(topic, {}))
        lines.append(f"All configs for topic {topic} are:")
        lines.append("  compression.type=producer sensitive=false synonyms={DEFAULT_CONFIG:compression.type=producer}")
        lines += [f"  {k}={v} sensitive=false synonyms={{DYNAMIC_TOPIC_CONFIG:{k}={v}}}"
                  for k, v in sorted(values.items()) if v is not None]
    return "\n".join(lines) + "\n"


def groups_out(subscriptions, counts, assigned=None):
    """assigned: {group: [partition...]} 로 할당을 덮어쓴다. 빠진 파티션은 consumer `-` 행으로 둔다."""
    header = ("GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID")
    lines = []
    for _, topic, group in subscriptions:
        lines += ["", header]
        own = (assigned or {}).get(group, range(counts[topic]))
        for p in range(counts[topic]):
            consumer = f"consumer-{group}-1-0f3c" if p in own else "-"
            host, client = ("/10.0.0.7", f"consumer-{group}-1") if p in own else ("-", "-")
            lines.append(f"{group} {topic} {p} - 0 - {consumer} {host} {client}")
    return "\n".join(lines) + "\n"


class GuardTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.topics, cls.subs = guard.declared(None)
        cls.good = {"topics": topics_out(cls.topics), "configs": configs_out(cls.topics),
                    "groups": groups_out(cls.subs, cls.topics)}

    def judge(self, **outputs):
        merged = {**self.good, **outputs}
        return guard.judge(self.topics, self.subs, merged["topics"], merged["configs"], merged["groups"])


class DeclaredTopologyTest(GuardTestBase):
    def test_lint_emits_the_declared_topology(self):
        self.assertEqual(len(self.topics), 30)
        self.assertEqual({p for t, p in self.topics.items() if t.endswith(".dlq")}, {1})
        self.assertEqual({p for t, p in self.topics.items() if not t.endswith(".dlq")}, {3})
        groups = subprocess.run(["bash", str(guard.LINT), "--emit-groups"], capture_output=True, text=True,
                                check=True).stdout.split()
        self.assertEqual(sorted(g for _, _, g in self.subs), sorted(groups))
        self.assertTrue({t for _, t, _ in self.subs} <= set(self.topics))


class SnapshotTest(GuardTestBase):
    """V1 — 손 대조(24 일치, payment 6개 기본값)를 실제 원 출력에서 그대로 재현한다."""

    def test_gke_s4_snapshot_flags_exactly_the_default_config_topics(self):
        violations = self.judge(configs=SNAPSHOT.read_text(encoding="utf-8"))
        flagged = {}
        for v in violations:
            m = re.match(r"\[CONFIG\] (\S+): (.*)$", v)
            self.assertIsNotNone(m, v)
            flagged[m.group(1)] = {item.split("=")[0] for item in m.group(2).split(", ")}
        self.assertEqual(set(flagged), SNAPSHOT_DEFAULT_TOPICS)
        for topic, keys in flagged.items():
            self.assertEqual(keys, DEFAULT_DIFF_KEYS, topic)


class DriftTest(unittest.TestCase):
    """V2 — 가드 표가 소스의 선언과 같고, 그 선언은 앱이 실제 브로커에 쓴 값과 같다."""

    def test_expected_table_matches_source(self):
        self.assertEqual(derive_expected(ROOT), guard.EXPECTED_CONFIGS)

    def test_source_values_match_what_the_app_wrote_on_gke(self):
        dynamic = {}
        current = None
        for line in SNAPSHOT.read_text(encoding="utf-8").splitlines():
            m = re.match(r"All configs for topic (\S+) are:$", line)
            if m:
                current = dynamic.setdefault(m.group(1), {})
                continue
            m = re.match(r"  ([a-z0-9.]+)=\S* sensitive=\S+ synonyms=\{DYNAMIC_TOPIC_CONFIG:[a-z0-9.]+=([^,}]*)", line)
            if m and current is not None:
                current[m.group(1)] = m.group(2)
        derived = derive_expected(ROOT)
        # order-service 토픽은 GKE 에서 7키가 전부 동적으로 적용됐다(경합 없는 갈래).
        for topic, kind in (("order.cancelled", "business"), ("order.cancelled.dlq", "dlq")):
            self.assertEqual({k: dynamic[topic][k] for k in derived[kind]}, derived[kind], topic)

    def _mutated(self, path, old, new):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        for rel in DRIFT_SOURCES:
            (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / rel, tmp / rel)
        text = (tmp / path).read_text(encoding="utf-8")
        self.assertEqual(text.count(old), 1, old)
        (tmp / path).write_text(text.replace(old, new), encoding="utf-8")
        return tmp

    def test_changed_constant_breaks_the_match(self):
        tmp = self._mutated(DRIFT_SOURCES[0], "BUSINESS_RETENTION_BYTES = 8L * 1024 * 1024",
                            "BUSINESS_RETENTION_BYTES = 16L * 1024 * 1024")
        self.assertNotEqual(derive_expected(tmp), guard.EXPECTED_CONFIGS)

    def test_changed_yml_retention_breaks_the_match(self):
        tmp = self._mutated(DRIFT_SOURCES[3], "kafka-topic-retention: 7d", "kafka-topic-retention: 8d")
        with self.assertRaisesRegex(DerivationError, "갈린다"):
            derive_expected(tmp)

    def test_removed_declaration_fails_derivation(self):
        tmp = self._mutated(DRIFT_SOURCES[0], "SEGMENT_ROLL = Duration.ofDays(1);", "SEGMENT_ROLL = ROLL;")
        with self.assertRaisesRegex(DerivationError, "SEGMENT_ROLL"):
            derive_expected(tmp)


class JudgeTest(GuardTestBase):
    """V3 — 주입 하나에 해당 위반 하나. 주입 없는 입력은 위반 0."""

    def test_clean_input_passes(self):
        self.assertEqual(self.judge(), [])

    def test_partition_mismatch(self):
        counts = {**self.topics, "order.created": 1}
        violations = self.judge(topics=topics_out(counts), groups=groups_out(self.subs, counts))
        self.assertEqual(violations, ["[PARTITIONS] order.created: 1 (선언 3)"])

    def test_missing_topic(self):
        counts = {t: p for t, p in self.topics.items() if t != "product.updated.dlq"}
        self.assertEqual(self.judge(topics=topics_out(counts)), ["[TOPIC] product.updated.dlq: 브로커에 없다"])

    def test_config_value_mismatch(self):
        configs = configs_out(self.topics, {"stock.approval.result": {"retention.bytes": "-1"}})
        self.assertEqual(self.judge(configs=configs),
                         ["[CONFIG] stock.approval.result: retention.bytes=-1 (선언 8388608)"])

    def test_config_key_missing(self):
        configs = configs_out(self.topics, {"order.created": {"segment.ms": None}})
        self.assertEqual(self.judge(configs=configs), ["[CONFIG] order.created: segment.ms=<없음> (선언 86400000)"])

    def test_partition_zero_only(self):
        # D-107 ②: 구독 뒤 늘어난 파티션을 metadata 갱신 전까지 받지 못한 상태.
        violations = self.judge(groups=groups_out(self.subs, self.topics,
                                                  {"product-svc-payment-completed-group": [0]}))
        self.assertEqual(violations, ["[GROUP] product-svc-payment-completed-group (product): "
                                      "payment.completed 파티션 1,2 미할당"])

    def test_rows_without_consumer(self):
        violations = self.judge(groups=groups_out(self.subs, self.topics, {"order-svc-stock-result-group": []}))
        self.assertEqual(violations, ["[GROUP] order-svc-stock-result-group (order): "
                                      "stock.reservation.result 파티션 0,1,2 미할당"])

    def test_missing_rows_count_as_unassigned(self):
        text = "\n".join(line for line in self.good["groups"].splitlines()
                         if not line.startswith("payment-svc-order-created-group order.created 2 "))
        self.assertEqual(self.judge(groups=text), ["[GROUP] payment-svc-order-created-group (payment): "
                                                   "order.created 파티션 2 미할당"])

    def test_missing_group(self):
        subs = [s for s in self.subs if s[2] != "notification-svc-payment-failed-group"]
        self.assertEqual(self.judge(groups=groups_out(subs, self.topics)),
                         ["[GROUP] notification-svc-payment-failed-group (notification): group 이 없다"])

    def test_empty_outputs_fail(self):
        self.assertEqual(len(self.judge(topics="")), len(self.topics))
        self.assertEqual(len(self.judge(configs="")), len(self.topics))
        self.assertEqual(len(self.judge(groups="")), len(self.subs))

    def test_unknown_formats_raise(self):
        with self.assertRaises(guard.ParseError):
            self.judge(topics="Topic: order.created PartitionCount=3\n")
        with self.assertRaises(guard.ParseError):
            self.judge(groups="order-svc-stock-result-group stock.reservation.result 0 - 0 -\n")
        with self.assertRaises(guard.ParseError):
            self.judge(groups="Warning: Consumer group 'x' is rebalancing.\n")


FAKE_EXEC = """\
import os, sys
argv = " ".join(sys.argv[1:])
for name, tool in (("topics", "kafka-topics.sh"), ("configs", "kafka-configs.sh"),
                   ("groups", "kafka-consumer-groups.sh")):
    if tool in argv:
        code = int(os.environ.get("FAKE_KAFKA_EXIT_" + name.upper(), "0"))
        if code:
            sys.stderr.write("connection refused\\n")
            sys.exit(code)
        with open(os.path.join(os.environ["FAKE_KAFKA_DIR"], name + ".txt")) as f:
            sys.stdout.write(f.read())
        sys.exit(0)
sys.exit(99)
"""


class EntrypointTest(GuardTestBase):
    """V4 — 실제 CLI 를 가짜 exec 로 관통한다."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        (self.dir / "fake_kafka.py").write_text(FAKE_EXEC, encoding="utf-8")
        self.exec = f"{shlex.quote(sys.executable)} {shlex.quote(str(self.dir / 'fake_kafka.py'))}"
        self.write(**self.good)

    def write(self, **outputs):
        for name, text in outputs.items():
            (self.dir / f"{name}.txt").write_text(text, encoding="utf-8")

    def run_main(self, *args, env=None):
        with patch.dict(os.environ, {"FAKE_KAFKA_DIR": str(self.dir), **(env or {})}):
            return guard.main(["--exec", self.exec, *args])

    def test_cli_process_passes_and_fails(self):
        env = {**os.environ, "FAKE_KAFKA_DIR": str(self.dir)}
        ok = subprocess.run([sys.executable, str(GUARD_PATH), "--exec", self.exec], capture_output=True, text=True,
                            env=env)
        self.assertEqual(ok.returncode, 0, ok.stderr)
        self.assertIn("OK", ok.stdout)
        self.write(configs=SNAPSHOT.read_text(encoding="utf-8"))
        bad = subprocess.run([sys.executable, str(GUARD_PATH), "--exec", self.exec], capture_output=True, text=True,
                             env=env)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("[CONFIG] payment.completed:", bad.stderr)

    def test_command_failure_is_a_violation(self):
        evidence = self.dir / "evidence"
        self.assertEqual(self.run_main("--evidence-dir", str(evidence), env={"FAKE_KAFKA_EXIT_GROUPS": "1"}), 1)
        verdict = (evidence / "verdict.txt").read_text(encoding="utf-8")
        self.assertIn("[COLLECT] groups: exit 1 — connection refused", verdict)
        self.assertEqual((evidence / "topics.txt").read_text(encoding="utf-8"), self.good["topics"])

    def test_parse_error_is_a_violation(self):
        self.write(groups="Warning: Consumer group 'x' is rebalancing.\n")
        self.assertEqual(self.run_main(), 1)

    def test_services_scope_excludes_absent_service_groups(self):
        without_notification = [s for s in self.subs if s[0] != "notification"]
        self.write(groups=groups_out(without_notification, self.topics))
        self.assertEqual(self.run_main(), 1)
        self.assertEqual(self.run_main("--services", "order,product,payment"), 0)

    def test_unknown_service_and_missing_exec_are_preconditions(self):
        self.assertEqual(self.run_main("--services", "order,bogus"), 2)
        self.assertEqual(guard.main(["--exec", str(self.dir / "no-such-binary")]), 2)


if __name__ == "__main__":
    unittest.main()
