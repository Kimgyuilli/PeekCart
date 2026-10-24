#!/usr/bin/env python3
"""deploy-overlay.sh 테스트용 가짜 kubectl 과 가짜 kafka-topics.sh (D-107).

argv 를 엄격히 대조한다. 클러스터에 닿는 명령은 전부 여기서 끝나고, `kubectl kustomize` 만 실제
kubectl(REAL_KUBECTL)로 오프라인 렌더한다. `exec deploy/kafka` 는 `/opt/kafka/bin/` 을 이 fixture 의
kafka-bin 디렉터리로 바꿔 래퍼가 넘긴 명령(생성 루프 포함)을 로컬 sh 로 그대로 실행한다.
같은 파일이 `kafka-topics.sh` 이름으로 불리면 브로커 흉내를 낸다.

DEPLOY_FIXTURE_DIR 아래 파일:
  state.json   {토픽: 파티션 수}. 가짜 브로커의 토픽
  inject.json  실패 주입. apply_f_exit, rollout_exit, create_fail(토픽 목록), describe_exit, describe_output
  calls.jsonl  호출 기록(순서 보존)
"""
import json
import os
from pathlib import Path
import subprocess
import sys

HOME = Path(os.environ["DEPLOY_FIXTURE_DIR"])
STATE = HOME / "state.json"
INJECT = json.loads((HOME / "inject.json").read_text(encoding="utf-8"))
VALUE_FLAGS = ("--bootstrap-server", "--topic", "--partitions", "--replication-factor")


def record(**call):
    with (HOME / "calls.jsonl").open("a", encoding="utf-8") as log:
        log.write(json.dumps(call, ensure_ascii=False) + "\n")


def fail(code, message):
    print(message, file=sys.stderr)
    sys.exit(code)


def kafka_topics(args):
    values, flags, i = {}, [], 0
    while i < len(args):
        if args[i] in VALUE_FLAGS:
            values[args[i]] = args[i + 1]
            i += 2
        else:
            flags.append(args[i])
            i += 1
    # k8s Kafka 의 리스너는 29092 하나다. 래퍼는 가드·preflight 와 같은 주소를 써야 한다.
    assert values.get("--bootstrap-server") == "localhost:29092", args
    state = json.loads(STATE.read_text(encoding="utf-8"))
    if flags == ["--describe"]:
        record(kind="kafka-topics", op="describe")
        if INJECT.get("describe_exit"):
            fail(INJECT["describe_exit"], "Error while executing topic command : Timed out waiting for a node assignment")
        if "describe_output" in INJECT:
            sys.stdout.write(INJECT["describe_output"])
            return
        # Kafka 3.8.1 형식. 요약 줄 다음에 파티션별 줄이 탭으로 시작한다.
        for topic in sorted(state):
            print(f"Topic: {topic}\tTopicId: AAAAAAAAAAAAAAAAAAAAAA\tPartitionCount: {state[topic]}\t"
                  f"ReplicationFactor: 1\tConfigs: ")
            for partition in range(state[topic]):
                print(f"\tTopic: {topic}\tPartition: {partition}\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: ")
        return
    assert flags[:1] == ["--create"] and set(flags) <= {"--create", "--if-not-exists"}, args
    assert values.get("--replication-factor") == "1", args
    topic, partitions = values["--topic"], int(values["--partitions"])
    record(kind="kafka-topics", op="create", topic=topic, partitions=partitions)
    if topic in INJECT.get("create_fail", []):
        fail(1, f"Error while executing topic command : injected failure for {topic}")
    if topic in state:
        if "--if-not-exists" in flags:
            return
        fail(1, f"Error while executing topic command : Topic '{topic}' already exists.")
    state[topic] = partitions
    STATE.write_text(json.dumps(state), encoding="utf-8")
    print(f"Created topic {topic}.")


def kubectl(args):
    if args[:1] == ["kustomize"]:
        assert len(args) == 2, args
        record(kind="kustomize", overlay=args[1])
        rendered = subprocess.run([os.environ["REAL_KUBECTL"], "kustomize", args[1]],
                                  env={**os.environ, "KUBECONFIG": os.devnull})
        sys.exit(rendered.returncode)
    if args[:2] == ["apply", "-f"]:
        assert len(args) == 3, args
        record(kind="apply-f", text=Path(args[2]).read_text(encoding="utf-8"))
        if INJECT.get("apply_f_exit"):
            fail(INJECT["apply_f_exit"], "error: injected kafka-first apply failure")
        print("kafka-first objects applied (fixture)")
        return
    if args[:2] == ["apply", "-k"]:
        assert len(args) == 3, args
        record(kind="apply-k", overlay=args[2])
        print("overlay applied (fixture)")
        return
    namespace = None
    if args[:1] == ["-n"]:
        namespace, args = args[1], args[2:]
    if args[:1] == ["get"]:
        # drain preflight 의 Secret 조회. 빈 namespace 처럼 실패시킨다.
        record(kind="get", namespace=namespace)
        fail(1, "Error from server (NotFound): secrets not found (fixture)")
    assert namespace == "peekcart", (namespace, args)
    if args[:2] == ["rollout", "status"]:
        assert args[2:] == ["deploy/kafka", "--timeout=300s"], args
        record(kind="rollout", target=args[2])
        if INJECT.get("rollout_exit"):
            fail(INJECT["rollout_exit"], "error: timed out waiting for the condition")
        print('deployment "kafka" successfully rolled out')
        return
    if args[:1] == ["exec"]:
        interactive = args[1:2] == ["-i"]
        rest = args[2:] if interactive else args[1:]
        assert rest[:2] == ["deploy/kafka", "--"] and len(rest) > 2, args
        kafka_bin = str(HOME / "kafka-bin") + "/"
        command = [part.replace("/opt/kafka/bin/", kafka_bin) for part in rest[2:]]
        record(kind="exec", command=Path(command[0]).name, interactive=interactive)
        done = subprocess.run(command, stdin=None if interactive else subprocess.DEVNULL)
        sys.exit(done.returncode)
    raise AssertionError(f"예상하지 못한 kubectl 호출: {args}")


if __name__ == "__main__":
    if Path(sys.argv[0]).name == "kafka-topics.sh":
        kafka_topics(sys.argv[1:])
    else:
        kubectl(sys.argv[1:])
