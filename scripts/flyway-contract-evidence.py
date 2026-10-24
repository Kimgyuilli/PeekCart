#!/usr/bin/env python3
"""Fail closed on missing/partial Flyway evidence and omitted ordinary payment tests."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

SERVICES = {"order", "product", "payment", "notification"}
FLAGS = ("fresh", "missingDiscriminatorInsertRejected", "upgrade", "legacyNullRejectedWithoutHistory",
         "openIncidentsPreserved", "callbackRejectedWithoutHistoryThenResumed",
         "checksumMismatchRejectedThenOriginalRestored", "partialDdlAndDmlCommittedWithFailedHistory",
         "failedHistoryBlocksNewJvm")
CONTRACT_SUFFIX = "__dead_letter_replay_contract.sql"
GUARD = "beforeMigrate__dead_letter_replay_contract_guard.sql"
GUARD_MARKER = "D065_CONTRACT_GUARD"
LAB_CLASS = "com.peekcart.global.flyway.FlywayContractIntegrationTest"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def cases(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    require(bool(files), f"no JUnit XML: {directory}")
    result = []
    for path in files:
        root = ET.parse(path).getroot()
        require(int(root.get("failures", 0)) == 0 and int(root.get("errors", 0)) == 0,
                f"failing JUnit suite: {path.name}")
        for case in root.findall("testcase"):
            require(not any(case.find(tag) is not None for tag in ("skipped", "failure", "error")),
                    f"non-passing testcase: {path.name}")
            result.append(f'{case.attrib["classname"]}::{case.attrib["name"]}')
    require(bool(result), "zero testcases")
    return result


def verify(root, lab, lab_xml, normal_xml, baseline):
    normal = Counter(cases(normal_xml))
    expected = Counter(json.loads(baseline.read_text())["tests"])
    require(sum(expected.values()) == 334, "baseline inventory must preserve all 334 prior test occurrences")
    require(not (expected - normal), f"ordinary payment tests omitted: {list((expected - normal).elements())}")
    require(not any(name.startswith(LAB_CLASS + "::") for name in normal), "restart lab leaked into ordinary task")
    dedicated = cases(lab_xml)
    require(len(dedicated) == 1 and dedicated[0].startswith(LAB_CLASS + "::"), "dedicated task must run exactly the lab test")
    report = json.loads((lab / "report.json").read_text())
    require(report.get("complete") is True and report.get("restartStatePreserved") is True, "incomplete restart lab")
    require(report.get("mysqlImage") == "mysql:8.0.46", "unexpected MySQL image")
    require(set(report.get("services", {})) == SERVICES, "missing/extra service")
    for service, result in report["services"].items():
        require(all(result.get(flag) is True for flag in FLAGS), f"partial service: {service}")
        require(result.get("allocatorAfterLegacyMaximum", 0) > 21, f"allocator bootstrap missing: {service}")
        paths = sorted((root / f"{service}-service/src/main/resources/db/migration").glob("V*__*.sql"))
        actual = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        require(bool(actual) and result.get("productionMigrationSha256") == actual, f"production source manifest mismatch: {service}")
        contracts = [path for path in actual if path.endswith(CONTRACT_SUFFIX)]
        require(len(contracts) == 1, f"contract migration missing: {service}")
        restored = lab / f"{service}-checksum" / Path(contracts[0]).name
        require(restored.is_file() and hashlib.sha256(restored.read_bytes()).hexdigest() == actual[contracts[0]]
                == result.get("restoredContractSha256"), f"contract restore mismatch: {service}")
        guard = root / f"{service}-service/src/main/resources/db/migration" / GUARD
        require(guard.is_file() and hashlib.sha256(guard.read_bytes()).hexdigest() == result.get("guardSha256"),
                f"guard source mismatch: {service}")
    invocations = report.get("invocations", [])
    require(len(invocations) == 88, "missing Flyway JVM invocations")
    require(Counter(i.get("operation") for i in invocations) == Counter(migrate=60, validate=28), "missing operation coverage")
    require(sum(i.get("success") is False for i in invocations) == 32, "missing negative Flyway outcomes")
    guard_rejections = [i for i in invocations if i.get("sqlErrorCode") == 1231]
    require(len(guard_rejections) == 8 and all(i.get("success") is False and i.get("operation") == "migrate"
            and GUARD_MARKER in str(i.get("causeMessage", "")) for i in guard_rejections), "missing guard rejection evidence")
    require(len({i["pid"] for i in invocations}) == 88, "child JVM identity reused")
    require(all(i["pid"] != report["parentPid"] for i in invocations), "in-process Flyway substituted")
    require({i.get("flywayVersion") for i in invocations if "flywayVersion" in i} == {"11.7.2"}, "unexpected/missing actual Flyway version")
    for invocation in invocations:
        raw = json.loads((lab / (invocation["invocation"] + ".json")).read_text())
        require(all(invocation.get(k) == v for k, v in raw.items()), "invocation artifact mismatch")
        require(invocation.get("exitCode") == (0 if invocation.get("success") else 2), "child exit/result mismatch")
    first_endpoint = json.loads((lab / "before-restart-endpoint.json").read_text())
    next_endpoint = json.loads((lab / "after-restart-endpoint.json").read_text())
    require(first_endpoint.get("id") == next_endpoint.get("id") == report.get("containerId")
            and bool(report.get("containerId")), "restart container ownership mismatch")
    require(first_endpoint.get("status") == next_endpoint.get("status") == "running", "restart container not running")
    require(bool(first_endpoint.get("startedAt")) and next_endpoint.get("startedAt", "") > first_endpoint["startedAt"],
            "actual restart timestamp did not advance")
    require(int(first_endpoint["ports"]["3306/tcp"][0]["HostPort"]) == report.get("initialMappedPort")
            and int(next_endpoint["ports"]["3306/tcp"][0]["HostPort"]) == report.get("restartedMappedPort"), "restart endpoint mismatch")
    before = json.loads((lab / "before-restart.json").read_text())
    after = json.loads((lab / "after-restart.json").read_text())
    require(len(before) == 12 and before == after, "restart snapshot missing/changed")
    return {"ordinaryTests": sum(normal.values()), "dedicatedTests": len(dedicated),
            "services": sorted(SERVICES), "childJvms": len(invocations)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    root = args.root.resolve()
    summary = verify(root, root / "payment-service/build/flyway-contract",
                     root / "payment-service/build/test-results/flywayContractTest",
                     root / "payment-service/build/test-results/test",
                     root / "scripts/fixtures/flyway-contract/payment-baseline-tests.json")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
