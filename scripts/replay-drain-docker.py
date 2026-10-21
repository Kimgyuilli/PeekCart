#!/usr/bin/env python3
"""Actual collector CLI lab. A kubectl symlink dispatches only to its owned Docker fixtures."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("order", "product", "payment", "notification")
TOPIC = "collector.fixture"
GROUP = "order-target"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def save(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n")
    temp.replace(path)


def run(args, input=None, timeout=30, env=None):
    try:
        return subprocess.run(args, input=input, text=True, capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired as error:
        def text(value):
            return value.decode(errors="replace") if isinstance(value, bytes) else (value or "")
        return subprocess.CompletedProcess([], 124, text(error.stdout), text(error.stderr) + "\nfixture command timeout\n")


def read_owner(path):
    owner = json.loads(path.read_text())
    require(re.fullmatch(r"[0-9a-f]{24}", owner["nonce"]) is not None, "invalid fixture nonce")
    require(owner["project"] == "collector-" + owner["nonce"], "invalid fixture project")
    return owner


def labels_match(owner, labels):
    return (labels.get("peekcart.collector.nonce") == owner["nonce"]
            and labels.get("com.docker.compose.project") == owner["project"])


def owned_exec(owner, service, args, input=None, timeout=30):
    identity = owner["containers"][service]
    inspected = run(["docker", "container", "inspect", "--format",
                     '{{json .Config.Labels}}', identity], timeout=10)
    require(inspected.returncode == 0 and labels_match(owner, json.loads(inspected.stdout)), "exec ownership mismatch")
    return run(["docker", "exec", "-i", identity, *args], input, timeout)


def kafka(owner, args, input=None):
    return owned_exec(owner, "kafka", ["/opt/kafka/bin/" + args[0], "--bootstrap-server", "localhost:9092", *args[1:]], input)


def mysql(owner, service, sql):
    return owned_exec(owner, "mysql", ["mysql", "-upeekcart_" + service, "-ppeekcart_" + service,
                                      "-D", "peekcart_" + service, "-N", "-B"], sql)


def cleanup(path):
    if not path.exists():
        return
    owner = read_owner(path)
    removed = {"container": [], "network": [], "volume": []}
    for kind, listing in (("container", ["ps", "-aq", "--no-trunc"]),
                          ("network", ["network", "ls", "-q", "--no-trunc"]), ("volume", ["volume", "ls", "-q"])):
        selected = run(["docker", *listing, "--filter", "label=peekcart.collector.nonce=" + owner["nonce"]], timeout=10)
        require(selected.returncode == 0, "cleanup inventory failed")
        for identity in selected.stdout.split():
            inspected = run(["docker", kind, "inspect", identity], timeout=10)
            require(inspected.returncode == 0, "cleanup inspect failed")
            value = json.loads(inspected.stdout)[0]
            labels = value["Config"]["Labels"] if kind == "container" else value["Labels"]
            require(labels_match(owner, labels), "foreign resource cleanup refused")
            require(value["Name" if kind == "volume" else "Id"] == identity, "cleanup identity mismatch")
            deleted = run(["docker", kind, "rm", *(["-f"] if kind == "container" else []), identity])
            require(deleted.returncode == 0, "owned resource cleanup failed")
            removed[kind].append(identity)
    previous = path.parent / "cleanup.json"
    if previous.exists():
        old = json.loads(previous.read_text())["removed"]
        removed = {kind: sorted(set(items + old[kind])) for kind, items in removed.items()}
    save(previous, {"complete": True, "removed": removed})


def adapter(args):
    """No real kubectl, kubeconfig, arbitrary argv, or synthetic DB/Kafka output."""
    folder = Path(os.environ["COLLECTOR_CASE"])
    owner = read_owner(Path(os.environ["COLLECTOR_OWNER"]))
    require(args[:2] == ["-n", "fixture-only"], "unexpected kubectl namespace")
    args = args[2:]
    calls_file = folder / "calls.jsonl"
    calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
    if args[:2] == ["get", "secret"]:
        require(len(args) == 5 and args[3] == "-o", "unexpected Secret command")
        service = args[2].removesuffix("-service-secret")
        require(service in SERVICES and args[4] in ("jsonpath={.data.DB_USERNAME}", "jsonpath={.data.DB_PASSWORD}"), "unsupported fixture Secret")
        value = "peekcart_" + service
        # This is a new public fixture credential, never an external Secret read.
        print(base64.b64encode(value.encode()).decode())
        return 0
    if args[:4] == ["exec", "deploy/mysql", "--", "mysql"]:
        require(len(args) == 12 and args[6] == "-D" and args[8:11] == ["-N", "-B", "-e"], "unexpected mysql argv")
        service = args[7].removeprefix("peekcart_")
        require(service in SERVICES and args[4:6] == ["-upeekcart_" + service, "-ppeekcart_" + service], "unexpected fixture account")
        query = args[11]
        require(query.startswith("SELECT (SELECT COUNT(*) FROM outbox_events") or query ==
                "SELECT DISTINCT last_replay_target_group FROM dead_letter_records WHERE last_replay_target_group IS NOT NULL", "unexpected collector query")
        kind = "groups" if query.startswith("SELECT DISTINCT") else "aggregate"
        call = {"kind": kind, "service": service}
        result = mysql(owner, service, query)
    else:
        require(args[:8] == ["exec", "deploy/kafka", "--", "/opt/kafka/bin/kafka-consumer-groups.sh",
                            "--bootstrap-server", "localhost:9092", "--describe", "--group"] and len(args) == 9,
                "unexpected Kafka command")
        group = args[8]
        require(re.fullmatch(r"[a-z0-9.-]+", group) is not None, "unexpected fixture group")
        sample = 1 + sum(call.get("group") == group for call in calls)
        call = {"kind": "lag", "group": group, "sample": sample}
        if folder.name == "second-sample-missing" and group == GROUP and sample == 2:
            deleted = kafka(owner, ["kafka-consumer-groups.sh", "--delete", "--group", group])
            (folder / "actual-group-delete.stdout").write_text(deleted.stdout)
            (folder / "actual-group-delete.stderr").write_text(deleted.stderr)
            require(deleted.returncode == 0, "actual group deletion failed")
        result = kafka(owner, ["kafka-consumer-groups.sh", "--describe", "--group", group])
    index = len(calls)
    (folder / f"{index:02d}.stdout").write_text(result.stdout)
    (folder / f"{index:02d}.stderr").write_text(result.stderr)
    call["exit"] = result.returncode
    with calls_file.open("a") as stream:
        stream.write(json.dumps(call) + "\n")
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    return result.returncode


def lab(evidence):
    require(not evidence.exists(), "fresh evidence directory required")
    evidence.mkdir(parents=True)
    nonce = secrets.token_hex(12)
    owner = {"nonce": nonce, "project": "collector-" + nonce, "containers": {}}
    owner_path = evidence / "owner.json"
    save(owner_path, owner)  # intent survives interruption before Docker returns an ID.
    env = dict(os.environ, COLLECTOR_NONCE=nonce, E2E_RUN_ID=nonce,
               COLLECTOR_CLUSTER_ID=base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("="))
    compose = ["docker", "compose", "-f", str(ROOT / "docker-compose.e2e.yml"),
               "-f", str(ROOT / "docker-compose.collector.yml"), "-p", owner["project"]]
    result = {"complete": False, "cases": [], "source": subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()}
    failure = None

    def sql(service, statement):
        response = mysql(owner, service, statement)
        require(response.returncode == 0, "fixture SQL failed")
        return response.stdout

    def reset_group(group):
        response = kafka(owner, ["kafka-consumer-groups.sh", "--reset-offsets", "--to-latest", "--execute", "--group", group, "--topic", TOPIC])
        (evidence / (group + "-reset.stdout")).write_text(response.stdout)
        (evidence / (group + "-reset.stderr")).write_text(response.stderr)
        require(response.returncode == 0, "actual group reset failed")

    try:
        for args in (["ps", "-aq"], ["network", "ls", "-q"], ["volume", "ls", "-q"]):
            found = run(["docker", *args, "--filter", "label=com.docker.compose.project=" + owner["project"]], timeout=10)
            require(found.returncode == 0 and not found.stdout.strip(), "existing fixture project refused")
        started = run(compose + ["up", "-d", "--wait", "--wait-timeout", "180", "mysql", "kafka"], env=env, timeout=210)
        (evidence / "startup.stdout").write_text(started.stdout)
        (evidence / "startup.stderr").write_text(started.stderr)
        require(started.returncode == 0, "owned infrastructure startup failed")
        for service in ("mysql", "kafka"):
            selected = subprocess.run(compose + ["ps", "-q", service], env=env, text=True, capture_output=True, timeout=10)
            require(selected.returncode == 0 and len(selected.stdout.split()) == 1, "owned container identity missing")
            owner["containers"][service] = selected.stdout.strip()
        save(owner_path, owner)
        save(evidence / "runtime.json", {
            "mysqlVersion": mysql(owner, "order", "SELECT VERSION()").stdout.strip(),
            "kafkaVersion": owned_exec(owner, "kafka", ["/opt/kafka/bin/kafka-topics.sh", "--version"]).stdout.strip(),
            "collectorSha256": hashlib.sha256((ROOT / "scripts/replay-drain-preflight.sh").read_bytes()).hexdigest()})
        provenance = {}
        for service in SERVICES:
            files = sorted((ROOT / (service + "-service/src/main/resources/db/migration")).glob("V*__*.sql"),
                           key=lambda path: int(path.name.split("__")[0][1:]))
            require(bool(files), "empty production migration inventory")
            for path in files:
                sql(service, path.read_text())
                provenance[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
            sql(service, "INSERT INTO dead_letter_records (id,root_record_id,cluster_id,topic_generation,origin_topic,origin_partition,origin_offset,"
                "failed_consumer_group,origin_kind,status,attempt_count,occurred_at,last_replay_settled_at,last_replay_target_group) VALUES "
                "(1001,1001,'collector-fixture',1,'collector.fixture',0,1,'fixture-group','RESOLVED_ORIGIN','OPEN',1,UTC_TIMESTAMP(6),"
                "CONCAT(DATE(UTC_TIMESTAMP()-INTERVAL 1 DAY),' 00:00:00.123456')," + ("'" + GROUP + "'" if service == "order" else "NULL") + ")")
        save(evidence / "migration-provenance.json", provenance)
        response = kafka(owner, ["kafka-topics.sh", "--create", "--topic", TOPIC, "--partitions", "2", "--replication-factor", "1"])
        require(response.returncode == 0, "fixture topic creation failed")
        response = kafka(owner, ["kafka-console-producer.sh", "--topic", TOPIC], "one\ntwo\n")
        require(response.returncode == 0, "fixture produce failed")
        groups = [GROUP] + [service + "-svc-dlq-group" for service in SERVICES]
        for group in groups:
            reset_group(group)
        binary = evidence / "bin"
        binary.mkdir()
        (binary / "kubectl").symlink_to(Path(__file__).resolve())
        case_env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"], COLLECTOR_OWNER=str(owner_path),
                        PEEKCART_NAMESPACE="fixture-only", DRAIN_LAG_RECHECK_SECONDS="0")

        def scenario(name, expected, needle):
            folder = evidence / name
            folder.mkdir()
            (folder / "calls.jsonl").touch()
            output = run(["bash", str(ROOT / "scripts/replay-drain-preflight.sh")],
                         env=dict(case_env, COLLECTOR_CASE=str(folder)), timeout=240)
            (folder / "collector.stdout").write_text(output.stdout)
            (folder / "collector.stderr").write_text(output.stderr)
            save(folder / "result.json", {"exit": output.returncode, "expected": expected, "needle": needle})
            require(output.returncode == expected and needle in output.stdout, "collector case failed: " + name)
            result["cases"].append(name)

        scenario("baseline", 0, "OK — drain")
        sql("order", "INSERT INTO outbox_events (aggregate_type,aggregate_id,event_type,event_id,payload,status,created_at,record_kind) "
            "VALUES ('fixture','1001','__replay__','collector-pending','{}','PENDING',UTC_TIMESTAMP(6),'REPLAY')")
        scenario("pending", 1, "[ⓐ] order")
        sql("order", "DELETE FROM outbox_events WHERE event_id='collector-pending'")
        sql("order", "UPDATE dead_letter_records SET publication_status='REQUESTED' WHERE id=1001")
        scenario("requested", 1, "[ⓐ'] order")
        sql("order", "UPDATE dead_letter_records SET publication_status=NULL,last_replay_settled_at=UTC_TIMESTAMP(6) WHERE id=1001")
        scenario("recent-settled", 1, "[ⓓ] order")
        sql("order", "UPDATE dead_letter_records SET last_replay_settled_at=CONCAT(DATE(UTC_TIMESTAMP()-INTERVAL 1 DAY),' 00:00:00.123456') WHERE id=1001")
        response = kafka(owner, ["kafka-consumer-groups.sh", "--reset-offsets", "--to-earliest", "--execute", "--group", GROUP, "--topic", TOPIC])
        require(response.returncode == 0, "lag fixture reset failed")
        scenario("positive-lag", 1, "[ⓑⓒ] order")
        reset_group(GROUP)
        sql("order", "UPDATE dead_letter_records SET last_replay_target_group='order-missing' WHERE id=1001")
        scenario("missing-group", 1, "lag 조회 실패")
        sql("order", "UPDATE dead_letter_records SET last_replay_target_group='order-target' WHERE id=1001")
        sql("order", "ALTER TABLE dead_letter_records RENAME COLUMN last_replay_target_group TO fixture_hidden_group")
        scenario("group-sql-failed", 1, "표적 group 조회 실패")
        sql("order", "ALTER TABLE dead_letter_records RENAME COLUMN fixture_hidden_group TO last_replay_target_group")
        sql("order", "ALTER TABLE outbox_events RENAME COLUMN record_kind TO fixture_hidden_kind")
        scenario("aggregate-sql-failed", 1, "스키마 peekcart_order 조회 실패")
        sql("order", "ALTER TABLE outbox_events RENAME COLUMN fixture_hidden_kind TO record_kind")
        scenario("second-sample-missing", 1, "lag 재조회 실패")
        reset_group(GROUP)
        # A real assigned consumer deliberately never commits. This is not a fabricated '-' row.
        topic = "collector.no-commit"
        result["stage"] = "no-commit-topic-create"
        created = kafka(owner, ["kafka-topics.sh", "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1"])
        (evidence / "no-commit-create.stdout").write_text(created.stdout)
        (evidence / "no-commit-create.stderr").write_text(created.stderr)
        save(evidence / "no-commit-create.json", {"exit": created.returncode})
        require(created.returncode == 0, "no-commit topic creation failed")
        result["stage"] = "no-commit-consumer-start"
        started_consumer = run(["docker", "exec", "-d", owner["containers"]["kafka"], "sh", "-c",
                               "exec /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 "
                               "--topic collector.no-commit --group order-no-commit --consumer-property enable.auto.commit=false "
                               "--timeout-ms 120000 > /tmp/collector-no-commit.log 2>&1"])
        (evidence / "no-commit-start.stdout").write_text(started_consumer.stdout)
        (evidence / "no-commit-start.stderr").write_text(started_consumer.stderr)
        save(evidence / "no-commit-start.json", {"exit": started_consumer.returncode})
        require(started_consumer.returncode == 0, "no-commit consumer launch failed")
        result["stage"] = "no-commit-member-assignment"
        deadline = time.monotonic() + 40
        while True:
            members = kafka(owner, ["kafka-consumer-groups.sh", "--describe", "--members", "--verbose", "--group", "order-no-commit"])
            (evidence / "no-commit-members-last.stdout").write_text(members.stdout)
            (evidence / "no-commit-members-last.stderr").write_text(members.stderr)
            with (evidence / "no-commit-members-attempts.jsonl").open("a") as stream:
                stream.write(json.dumps({"exit": members.returncode, "remainingSeconds": deadline - time.monotonic()}) + "\n")
            if members.returncode == 0 and topic in members.stdout:
                (evidence / "actual-no-commit-members.stdout").write_text(members.stdout)
                break
            require(time.monotonic() < deadline, "actual no-commit partition assignment missing")
            time.sleep(1)
        sql("order", "UPDATE dead_letter_records SET last_replay_target_group='order-no-commit' WHERE id=1001")
        result["stage"] = "no-commit-collector"
        scenario("no-committed-offset", 1, "lag 조회 실패")
        sql("order", "UPDATE dead_letter_records SET last_replay_target_group='order-target' WHERE id=1001")
        scenario("restored", 0, "OK — drain")
        result["complete"] = True
        result["stage"] = "complete"
    except BaseException as error:
        failure = type(error).__name__
        result["failureType"] = failure
    finally:
        (evidence / "bin" / "kubectl").unlink(missing_ok=True)  # adapter source is not a runtime observation
        if result.get("stage", "").startswith("no-commit-") or result.get("complete"):
            try:
                log = owned_exec(owner, "kafka", ["cat", "/tmp/collector-no-commit.log"], timeout=5)
                (evidence / "no-commit-consumer.log").write_text(log.stdout)
                (evidence / "no-commit-consumer-log.stderr").write_text(log.stderr)
            except Exception as error:
                result["consumerLogFailure"] = type(error).__name__
        try:
            cleanup(owner_path)
        except BaseException as error:
            result["cleanupFailure"] = type(error).__name__
            failure = failure or "cleanup failure"
        if failure is not None:
            result["complete"] = False
        save(evidence / "result.json", result)
    require(failure is None, "collector lab failed; see bounded fixture evidence")


def main():
    if Path(sys.argv[0]).name == "kubectl":
        return adapter(sys.argv[1:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("run", "cleanup"))
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if args.operation == "run":
        lab(args.evidence.resolve())
    else:
        cleanup(args.evidence.resolve() / "owner.json")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Never print credential-bearing argv from subprocess exceptions.
        print("collector fixture failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
