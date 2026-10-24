#!/usr/bin/env python3
"""kafka-topic-state-guard — 측정 run 전의 토픽·consumer group 상태 판정 (D-106 ⑩)

왜 필요한가:
  GKE 세션(2026-10-24) S4 에서 payment 토픽 6개가 선언 config 없이 브로커 기본값으로 남았다(D-106 ⑩).
  CI 에서는 구독 뒤 늘어난 파티션을 컨슈머가 metadata 갱신(기본 5분) 전까지 받지 못해 lab 이 실패했다
  (D-107 ②). 둘 다 run 이 시작된 뒤에는 처리량·lag 숫자로만 보여 측정 대상의 성질과 구분되지 않는다.
  손으로 하던 D-065 ⑤ 대조를 run 직전 검사로 굳힌다.

무엇을 보는가 (fail-closed — 명령 실패·빈 출력·형식 불명도 위반이다):
  ① 선언 토픽이 모두 있고 파티션 수가 선언과 같다          정본: kafka-subscription-contract-lint --emit-topics
  ② 계약 7키의 **실효값**이 선언과 같다                     정본: KafkaTopicConfigs + 서비스 yml (EXPECTED_CONFIGS)
  ③ 업무 group 이 구독 토픽의 **모든** 파티션을 할당받았다   정본: --emit-subscriptions
  ③ 은 offsets 뷰(`--describe`)로 판정한다. 할당도 커밋도 없는 파티션은 행이 없으므로 행 없음이 곧
  미할당이다. `--members --verbose` 의 ASSIGNMENT 는 3.8.1 에서 토픽 이름 없이 `(0)` 만 나올 수 있다
  (scripts/tests/replay-drain-docker-test.py).

한계 (PLAN-BLINDSPOTS B12): 진입 시점 검사다. 통과 뒤 재기동·리밸런스가 일어나면 run 중 상태는
  보장하지 않는다. 측정 블록의 첫 run 직전에 돌리고, run 뒤 배수·lag export 로 사후 확인한다.

사용:
  python3 scripts/kafka-topic-state-guard.py [--services order,product,payment] [--evidence-dir DIR]
  k8s 가 기본이다(PEEKCART_NAMESPACE, 기본 peekcart). E2E 스택은 exec 접두사를 바꾼다:
    --exec "docker compose -f docker-compose.e2e.yml -p <project> exec -T kafka"

Exit:
  0 — 위반 0건
  1 — 위반 1건 이상, 또는 수집 실패(fail-closed)
  2 — 전제 미충족(lint emit 실패, 알 수 없는 서비스, exec 실행 파일 없음)
"""
import argparse
import os
import re
import shlex
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINT = ROOT / "scripts/kafka-subscription-contract-lint.sh"
TAG = "[TOPIC-STATE-GUARD]"
# k8s Kafka 의 리스너는 29092 하나다(replay-entrypoint-lint REPLAY-ENTRY-007 이 대조). E2E compose 도 같다.
BOOTSTRAP = "localhost:29092"
COMMAND_TIMEOUT_SECONDS = 120

# 계약 7키의 기대값. 정본은 KafkaTopicConfigs(common) 와 order·product·payment application.yml 의
# app.idempotency.floor.kafka-topic-retention(7d)·app.idempotency.retention(9d) 이다.
# 실행 때 Java·yml 을 파싱하지 않는 이유: 그 파서가 과금 세션 도중에 깨질 수 있다. 대신
# scripts/tests/test_kafka_topic_state_guard.py 가 같은 값을 소스에서 유도해 대조하므로,
# 소스를 바꾼 PR 의 CI 가 실패한다.
_COMMON_CONFIGS = {
    "retention.ms": "604800000",                     # 7d
    "cleanup.policy": "delete",
    "segment.ms": "86400000",                        # 1d
    "message.timestamp.type": "CreateTime",
    "message.timestamp.before.max.ms": "777600000",  # 9d
}
EXPECTED_CONFIGS = {
    "business": {**_COMMON_CONFIGS, "retention.bytes": "8388608", "segment.bytes": "4194304"},
    "dlq": {**_COMMON_CONFIGS, "retention.bytes": "4194304", "segment.bytes": "2097152"},
}

COMMANDS = {
    "topics": ["/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", BOOTSTRAP, "--describe"],
    "configs": ["/opt/kafka/bin/kafka-configs.sh", "--bootstrap-server", BOOTSTRAP,
                "--describe", "--entity-type", "topics", "--all"],
    "groups": ["/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", BOOTSTRAP,
               "--describe", "--all-groups"],
}


class Precondition(Exception):
    pass


class ParseError(Exception):
    pass


def lint_rows(flag):
    proc = subprocess.run(["bash", str(LINT), flag], capture_output=True, text=True,
                          timeout=COMMAND_TIMEOUT_SECONDS)
    if proc.returncode != 0 or not proc.stdout.strip():
        raise Precondition(f"{LINT.name} {flag} 실패(exit {proc.returncode}): {proc.stderr.strip()[-300:]}")
    return [line.split("\t") for line in proc.stdout.splitlines() if line.strip()]


def declared(services):
    """(토픽→선언 파티션, [(service, topic, group)]) — services 가 None 이면 전 서비스."""
    topics = {name: int(parts) for name, parts in lint_rows("--emit-topics")}
    subscriptions = [tuple(row) for row in lint_rows("--emit-subscriptions")]
    known = {service for service, _, _ in subscriptions}
    if services is not None:
        unknown = set(services) - known
        if unknown:
            raise Precondition(f"알 수 없는 서비스 {sorted(unknown)} (가능: {sorted(known)})")
        subscriptions = [s for s in subscriptions if s[0] in services]
    return topics, subscriptions


def parse_partition_counts(out):
    """`kafka-topics.sh --describe` 의 토픽 요약 줄만 센다. 파티션별 줄은 탭으로 시작한다."""
    counts = {}
    for line in out.splitlines():
        if not line.startswith("Topic: "):
            continue
        m = re.match(r"Topic: (\S+)\t(?:.*\t)?PartitionCount: (\d+)\t", line)
        if m is None:
            raise ParseError(f"토픽 요약 줄의 형식이 다르다: {line[:160]!r}")
        counts[m.group(1)] = int(m.group(2))
    return counts


def parse_topic_configs(out):
    """`kafka-configs.sh --describe --entity-type topics --all` → {topic: {key: 실효값}}."""
    configs, current = {}, None
    for line in out.splitlines():
        m = re.match(r"All configs for topic (\S+) are:$", line)
        if m:
            current = configs.setdefault(m.group(1), {})
            continue
        m = re.match(r"  ([a-z0-9.]+)=(\S*) sensitive=", line)
        if m and current is not None:
            current[m.group(1)] = m.group(2)
    return configs


def parse_group_rows(out):
    """offsets 뷰 → {group: {(topic, partition): consumer_id}}. 미할당 파티션의 consumer_id 는 `-` 다."""
    rows = {}
    for line in out.splitlines():
        fields = line.split()
        if not fields or fields[0] == "GROUP":
            continue
        # GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
        if len(fields) != 9 or not fields[2].isdigit():
            raise ParseError(f"group 행의 형식이 다르다: {line[:160]!r}")
        rows.setdefault(fields[0], {})[(fields[1], int(fields[2]))] = fields[6]
    return rows


def judge(declared_topics, subscriptions, topics_out, configs_out, groups_out):
    counts = parse_partition_counts(topics_out)
    configs = parse_topic_configs(configs_out)
    rows = parse_group_rows(groups_out)

    violations = []
    for topic, want in sorted(declared_topics.items()):
        have = counts.get(topic)
        if have is None:
            violations.append(f"[TOPIC] {topic}: 브로커에 없다")
            continue
        if have != want:
            violations.append(f"[PARTITIONS] {topic}: {have} (선언 {want})")
        actual = configs.get(topic)
        if actual is None:
            violations.append(f"[CONFIG] {topic}: config 출력에 없다")
            continue
        expected = EXPECTED_CONFIGS["dlq" if topic.endswith(".dlq") else "business"]
        diff = [f"{key}={actual.get(key, '<없음>')} (선언 {value})"
                for key, value in expected.items() if actual.get(key) != value]
        if diff:
            violations.append(f"[CONFIG] {topic}: {', '.join(diff)}")

    for service, topic, group in subscriptions:
        group_rows = rows.get(group)
        if group_rows is None:
            violations.append(f"[GROUP] {group} ({service}): group 이 없다")
            continue
        # 브로커에 실제로 있는 파티션을 기준으로 본다. 선언과 다른 파티션 수는 위에서 따로 잡는다.
        partitions = counts.get(topic)
        if partitions is None:
            continue
        missing = [p for p in range(partitions) if group_rows.get((topic, p), "-") == "-"]
        if missing:
            violations.append(f"[GROUP] {group} ({service}): {topic} 파티션 "
                              f"{','.join(map(str, missing))} 미할당")
    return violations


def collect(exec_prefix):
    outputs = {}
    for name, argv in COMMANDS.items():
        try:
            outputs[name] = subprocess.run(exec_prefix + argv, capture_output=True, text=True,
                                           timeout=COMMAND_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            outputs[name] = subprocess.CompletedProcess(argv, None, "", f"{COMMAND_TIMEOUT_SECONDS}초 timeout")
    return outputs


def write_evidence(directory, exec_prefix, outputs, violations):
    directory.mkdir(parents=True, exist_ok=True)
    for name, proc in outputs.items():
        (directory / f"{name}.txt").write_text(proc.stdout or "", encoding="utf-8")
        if proc.stderr:
            (directory / f"{name}.stderr").write_text(proc.stderr, encoding="utf-8")
    verdict = [f"time={datetime.now(timezone.utc).isoformat(timespec='seconds')}",
               f"exec={shlex.join(exec_prefix)}",
               f"violations={len(violations)}", *violations]
    (directory / "verdict.txt").write_text("\n".join(verdict) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description="측정 run 전 토픽·consumer group 상태 판정 (D-106 ⑩)")
    parser.add_argument("--services", help="업무 group 을 볼 서비스(쉼표 구분). 기본은 전 서비스")
    parser.add_argument("--exec", dest="exec_prefix",
                        help="Kafka 컨테이너 안에서 명령을 실행할 접두사. 기본은 kubectl exec deploy/kafka")
    parser.add_argument("--evidence-dir", type=Path, help="원 출력과 판정을 저장할 디렉터리")
    args = parser.parse_args(argv)

    services = None
    if args.services:
        services = {s.strip() for s in args.services.split(",") if s.strip()}
    exec_prefix = (shlex.split(args.exec_prefix) if args.exec_prefix else
                   ["kubectl", "-n", os.environ.get("PEEKCART_NAMESPACE", "peekcart"),
                    "exec", "deploy/kafka", "--"])

    try:
        declared_topics, subscriptions = declared(services)
        outputs = collect(exec_prefix)
    except Precondition as e:
        print(f"{TAG} {e}", file=sys.stderr)
        return 2
    except FileNotFoundError as e:
        print(f"{TAG} exec 실행 파일이 없다 — 판정 불가: {e}", file=sys.stderr)
        return 2

    violations = [f"[COLLECT] {name}: exit {proc.returncode} — {proc.stderr.strip()[-300:]}"
                  for name, proc in outputs.items() if proc.returncode != 0]
    if not violations:
        try:
            violations = judge(declared_topics, subscriptions, outputs["topics"].stdout,
                               outputs["configs"].stdout, outputs["groups"].stdout)
        except ParseError as e:
            violations = [f"[COLLECT] {e}"]

    if args.evidence_dir:
        write_evidence(args.evidence_dir, exec_prefix, outputs, violations)
    if violations:
        print(f"{TAG} 위반 {len(violations)}건 — 측정 run 을 시작하지 않는다", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    print(f"{TAG} OK — 토픽 {len(declared_topics)}개 파티션·config 일치, "
          f"업무 group {len(subscriptions)}개 전 파티션 할당")
    return 0


if __name__ == "__main__":
    sys.exit(main())
