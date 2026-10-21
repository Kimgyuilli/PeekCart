#!/usr/bin/env python3
"""Small hermetic controls plus actual artifact checks; never contacts Docker in self-tests."""
import importlib.util
import json
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import hashlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("collector", ROOT / "scripts/replay-drain-docker.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
CASES = {"baseline": 0, "pending": 1, "requested": 1, "recent-settled": 1, "positive-lag": 1,
         "missing-group": 1, "group-sql-failed": 1, "aggregate-sql-failed": 1, "second-sample-missing": 1,
         "no-committed-offset": 1, "restored": 0}


def read(path):
    return json.loads(path.read_text())


def verify(directory, root=ROOT):
    result = read(directory / "result.json")
    collector.require(result.get("complete") is True and result.get("cases") == list(CASES), "case inventory incomplete")
    collector.require(read(directory / "cleanup.json").get("complete") is True and not result.get("cleanupFailure"), "cleanup incomplete")
    collector.require(result["source"] == subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(), "checkout source differs")
    runtime = read(directory / "runtime.json")
    collector.require(runtime["mysqlVersion"].startswith("8.0.46") and runtime["kafkaVersion"].startswith("3.8.1"), "runtime version differs")
    collector.require(runtime["collectorSha256"] == hashlib.sha256((root / "scripts/replay-drain-preflight.sh").read_bytes()).hexdigest(), "collector source differs")
    expected_sql = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                    for service in collector.SERVICES for p in (root / (service + "-service/src/main/resources/db/migration")).glob("V*__*.sql")}
    collector.require(bool(expected_sql) and read(directory / "migration-provenance.json") == expected_sql, "production SQL inventory differs")
    for case, expected in CASES.items():
        folder = directory / case
        outcome = read(folder / "result.json")
        collector.require(outcome["exit"] == outcome["expected"] == expected, "wrong case exit")
        stdout = (folder / "collector.stdout").read_text()
        collector.require(outcome["needle"] in stdout and bool(outcome["needle"]), "case diagnosis absent")
        calls = [json.loads(line) for line in (folder / "calls.jsonl").read_text().splitlines()]
        collector.require({c["service"] for c in calls if c["kind"] == "aggregate"} == set(collector.SERVICES), "service aggregate missing")
        if expected == 0:
            groups = ["order-target"] + [service + "-svc-dlq-group" for service in collector.SERVICES]
            for group in groups:
                collector.require([c["sample"] for c in calls if c.get("group") == group] == [1, 2], "two actual samples absent")
            aggregate = next(i for i,c in enumerate(calls) if c.get("service") == "order" and c["kind"] == "aggregate")
            collector.require((folder / f"{aggregate:02d}.stdout").read_text().strip().endswith(".123456"), "actual SQL microseconds absent")
            lag = next(i for i,c in enumerate(calls) if c.get("group") == "order-target")
            rows = [line.split() for line in (folder / f"{lag:02d}.stdout").read_text().splitlines() if line.strip()]
            collector.require(len(rows) == 3 and {row[2] for row in rows[1:]} == {"0", "1"}, "actual two-partition rows absent")
        for index, call in enumerate(calls):
            collector.require((folder / f"{index:02d}.stdout").exists() and (folder / f"{index:02d}.stderr").exists(), "raw adapter observation missing")
    for case, kind in (("group-sql-failed", "groups"), ("aggregate-sql-failed", "aggregate")):
        folder = directory / case
        calls = [json.loads(line) for line in (folder / "calls.jsonl").read_text().splitlines()]
        index = next(i for i,c in enumerate(calls) if c.get("service") == "order" and c["kind"] == kind)
        collector.require(calls[index]["exit"] == 1 and "Unknown column" in (folder / f"{index:02d}.stderr").read_text(),
                          "actual MySQL column failure not observed")
    collector.require("collector.no-commit" in (directory / "actual-no-commit-members.stdout").read_text(), "actual no-commit assignment absent")
    collector.require((directory / "second-sample-missing/actual-group-delete.stdout").exists(), "actual second-sample deletion absent")
    for path in directory.rglob("*"):
        if path.is_file():
            collector.require(b"-ppeekcart_" not in path.read_bytes() and b"-----BEGIN PRIVATE KEY-----" not in path.read_bytes(),
                              "credential-bearing argv or secret artifact")


def negative_controls(directory):
    verify(directory)
    changes = [
        ("result.json", lambda value: dict(value, cases=value["cases"][:-1])),
        ("result.json", lambda value: dict(value, source="other-checkout")),
        ("cleanup.json", lambda value: dict(value, complete=False)),
        ("baseline/result.json", lambda value: dict(value, exit=1)),
        ("runtime.json", lambda value: dict(value, collectorSha256="wrong")),
        ("migration-provenance.json", lambda value: {}),
    ]
    for name, mutate in changes:
        with tempfile.TemporaryDirectory() as temp:
            copy = Path(temp) / "evidence"
            shutil.copytree(directory, copy)
            collector.save(copy / name, mutate(read(copy / name)))
            try:
                verify(copy)
            except (AssertionError, FileNotFoundError, KeyError):
                continue
            raise AssertionError("altered actual evidence accepted: " + name)
    for name in ("actual-no-commit-members.stdout", "baseline/00.stdout", "second-sample-missing/actual-group-delete.stdout"):
        with tempfile.TemporaryDirectory() as temp:
            copy = Path(temp) / "evidence"
            shutil.copytree(directory, copy)
            (copy / name).unlink()
            try:
                verify(copy)
            except (AssertionError, FileNotFoundError):
                continue
            raise AssertionError("missing actual evidence accepted: " + name)
    print("actual artifact negative controls: nine rejected mutations PASS")


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.owner = {"nonce": "a" * 24, "project": "collector-" + "a" * 24, "containers": {"mysql": "b" * 64}}
        self.path = self.root / "owner.json"
        collector.save(self.path, self.owner)

    def test_timeout_does_not_disclose_credential_argv(self):
        with patch.object(collector.subprocess, "run", side_effect=subprocess.TimeoutExpired(["mysql", "-psecret"], 1)):
            result = collector.run(["mysql", "-psecret"])
        self.assertEqual(result.returncode, 124)
        self.assertNotIn("secret", result.stderr)

    def test_foreign_exec_and_cleanup_refused(self):
        response = subprocess.CompletedProcess([], 0, json.dumps({"com.docker.compose.project": self.owner["project"]}), "")
        with patch.object(collector, "run", return_value=response) as run:
            with self.assertRaises(AssertionError): collector.owned_exec(self.owner, "mysql", ["mysql"])
            self.assertEqual(run.call_count, 1)
        listing = subprocess.CompletedProcess([], 0, "b" * 64, "")
        inspect = subprocess.CompletedProcess([], 0, json.dumps([{"Id": "b" * 64, "Config": {"Labels": {}}}]), "")
        with patch.object(collector, "run", side_effect=[listing, inspect]) as run:
            with self.assertRaises(AssertionError): collector.cleanup(self.path)
            self.assertEqual(run.call_count, 2)

    def test_cleanup_before_and_after_partial_creation(self):
        empty = subprocess.CompletedProcess([], 0, "", "")
        with patch.object(collector, "run", return_value=empty):
            collector.cleanup(self.path)
        self.assertTrue(read(self.root / "cleanup.json")["complete"])
        identity = "c" * 64
        labels = {"peekcart.collector.nonce": self.owner["nonce"], "com.docker.compose.project": self.owner["project"]}
        responses = [subprocess.CompletedProcess([],0,identity,""),
                     subprocess.CompletedProcess([],0,json.dumps([{"Id":identity,"Config":{"Labels":labels}}]),""), empty,empty,empty]
        with patch.object(collector, "run", side_effect=responses) as run:
            collector.cleanup(self.path)
            self.assertEqual(run.call_args_list[2].args[0], ["docker", "container", "rm", "-f", identity])

    def test_manifest_failure_and_missing_evidence_never_green(self):
        self.path.write_text(json.dumps({"nonce":"other","project":"collector-other"}))
        with self.assertRaises(AssertionError): collector.read_owner(self.path)
        with self.assertRaises(FileNotFoundError): verify(self.root)
        collector.save(self.root / "result.json", {"complete":True,"cases":list(CASES)[:-1]})
        with self.assertRaises(AssertionError): verify(self.root)

    def test_adapter_never_delegates_unknown_kubectl(self):
        (self.root / "calls.jsonl").touch()
        with patch.dict(collector.os.environ, COLLECTOR_OWNER=str(self.path), COLLECTOR_CASE=str(self.root)):
            with patch.object(collector, "run") as run:
                with self.assertRaises(AssertionError): collector.adapter(["-n","production","get","secret","real"])
                with self.assertRaises(AssertionError): collector.adapter(["-n","fixture-only","apply","-f","manifest"])
                run.assert_not_called()


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--evidence":
        negative_controls(Path(sys.argv[2]))
        print("actual collector evidence: 11 cases, four schemas, two samples, raw CLI and cleanup PASS")
    else:
        unittest.main()
