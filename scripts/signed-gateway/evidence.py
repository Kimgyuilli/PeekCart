#!/usr/bin/env python3
"""Reject incomplete signed-chain evidence; expected cases are independent of the runner."""
import hashlib
import json
from pathlib import Path
import sys

PHASES = {
    "baseline": {
        "admin-real-signed-chain": {200}, "user-role": {403},
        "user-with-forged-trusted-headers": {403}, "missing-bearer-with-forged-headers": {401},
        "malformed-bearer": {401}, "invalid-rsa-signature": {401}, "direct-plaintext-admin": {401},
        "non-numeric-route": {404}, "gateway-actuator-not-public": {404}, "encoded-traversal-route": {400, 404},
        "admin-restored-after-negative-requests": {200}},
    "wrong-key": {"wrong-internal-public-key": {401}},
    "restored": {"restored-real-chain": {200}},
    "expired": {"short-lived-real-token-before-expiry": {200}, "real-user-token-expired": {401}},
    "final": {"final-real-chain": {200}},
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read(path):
    return json.loads(path.read_text())


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False).encode()


def verify(directory, service, checkout, images, root):
    result = read(directory / "result.json")
    require(result.get("complete") is True and result.get("service") == service, "incomplete result")
    require(result.get("phases") == list(PHASES), "missing or reordered phases")
    source = read(directory / "source-images.json")
    require(source.get("checkout") == checkout, "source checkout mismatch")
    require(source.get("images") == images and len(images) == 3, "image identity mismatch")
    before = canonical(read(directory / "before.json"))
    require(len(read(directory / "before.json")["ledger"]) == 1, "known root absent")
    for phase, expected in PHASES.items():
        report = read(directory / (phase + ".json"))
        require(report.get("complete") is True and report.get("service") == service and report.get("phase") == phase,
                "incomplete phase")
        cases = report.get("cases", [])
        require(len(cases) == len(expected) and {case["case"] for case in cases} == set(expected), "case inventory differs")
        for case in cases:
            require(case["status"] in expected[case["case"]], "wrong HTTP outcome")
            if case["status"] == 200:
                require(case.get("disabled") is True, "replay closure unproven")
        require(canonical(read(directory / (phase + "-after.json"))) == before, "ledger/outbox changed")
        require(report.get("unchangedSha256") == hashlib.sha256(before).hexdigest(), "snapshot digest mismatch")
    provenance = read(directory / "migration-provenance.json")
    for target in ("user", service):
        files = (root / (target + "-service/src/main/resources/db/migration")).glob("V*__*.sql")
        expected = {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in files}
        require(bool(expected) and provenance.get(target) == expected, "migration provenance differs")
    require(read(directory / "cleanup.json").get("complete") is True, "cleanup incomplete")
    require(read(directory / "artifact-scan.json").get("safe") is True, "unsafe evidence")


if __name__ == "__main__":
    directory, service, checkout, artifacts = Path(sys.argv[1]), sys.argv[2], sys.argv[3], Path(sys.argv[4])
    images = {name: (artifacts / ("signed-image-" + name) / "image-id.txt").read_text().strip()
              for name in ("user-service", "gateway", service + "-service")}
    verify(directory, service, checkout, images, Path(__file__).resolve().parents[2])
    print("signed gateway evidence: 16 HTTP cases, five unchanged snapshots, source/images and cleanup PASS")
