#!/usr/bin/env python3
"""Hermetic artifact-verifier controls; these do not execute or simulate Flyway SQL."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("flyway-contract-evidence.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
BASELINE = Path(__file__).parent / "fixtures/flyway-contract/payment-baseline-tests.json"


class EvidenceControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lab = self.root / "lab"
        self.lab.mkdir()
        self.normal_xml = self.root / "normal"
        self.lab_xml = self.root / "dedicated"
        self.normal_xml.mkdir()
        self.lab_xml.mkdir()
        self.tests = json.loads(BASELINE.read_text())["tests"]
        self.xml(self.normal_xml, self.tests)
        self.xml(self.lab_xml, [MODULE.LAB_CLASS + "::contract()"])
        services = {}
        for name in sorted(MODULE.SERVICES):
            source = self.root / f"{name}-service/src/main/resources/db/migration/V1__fixture.sql"
            source.parent.mkdir(parents=True)
            source.write_text("SELECT 1;")
            candidate = self.lab / f"{name}-candidate/V999__fixture_contract.sql"
            candidate.parent.mkdir()
            candidate.write_text("SELECT 2;")
            services[name] = dict.fromkeys(MODULE.FLAGS, True)
            services[name].update(allocatorAfterLegacyMaximum=22,
                                  productionMigrationSha256={str(source.relative_to(self.root)): self.hash(source)},
                                  candidateSha256=self.hash(candidate))
        invocations = []
        for i in range(76):
            raw = dict(pid=1000+i, invocation=f"child-{i}", success=i >= 28,
                       operation="migrate" if i < 48 else "validate", flywayVersion="11.7.2")
            (self.lab / f"child-{i}.json").write_text(json.dumps(raw))
            invocations.append(dict(raw, exitCode=0 if raw["success"] else 2))
        self.report = dict(complete=True, restartStatePreserved=True, mysqlImage="mysql:8.0.46",
                           parentPid=900, services=services, invocations=invocations)
        state = {str(i): {"knownFixture": True} for i in range(12)}
        for name in ("before-restart.json", "after-restart.json"):
            (self.lab / name).write_text(json.dumps(state))

    @staticmethod
    def hash(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def xml(directory, names):
        suite = ET.Element("testsuite", tests=str(len(names)), failures="0", errors="0")
        for full in names:
            cls, name = full.split("::", 1)
            ET.SubElement(suite, "testcase", classname=cls, name=name)
        ET.ElementTree(suite).write(directory / "TEST-fixture.xml", encoding="utf-8")

    def verify(self):
        (self.lab / "report.json").write_text(json.dumps(self.report))
        return MODULE.verify(self.root, self.lab, self.lab_xml, self.normal_xml, BASELINE)

    def test_complete_artifact_is_accepted(self):
        self.assertEqual(self.verify()["ordinaryTests"], 334)

    def test_zero_lab_tests_rejected(self):
        self.xml(self.lab_xml, [])
        with self.assertRaisesRegex(ValueError, "zero testcases"): self.verify()

    def test_missing_service_rejected(self):
        del self.report["services"]["product"]
        with self.assertRaisesRegex(ValueError, "missing/extra service"): self.verify()

    def test_partial_service_rejected(self):
        self.report["services"]["payment"]["failedHistoryBlocksNewJvm"] = False
        with self.assertRaisesRegex(ValueError, "partial service"): self.verify()

    def test_missing_ordinary_duplicate_occurrence_rejected(self):
        duplicate = next(name for name, count in Counter(self.tests).items() if count > 1)
        self.tests.remove(duplicate)
        self.xml(self.normal_xml, self.tests)
        with self.assertRaisesRegex(ValueError, "ordinary payment tests omitted"): self.verify()

    def test_skipped_ordinary_case_rejected(self):
        path = self.normal_xml / "TEST-fixture.xml"
        tree = ET.parse(path)
        ET.SubElement(tree.getroot().find("testcase"), "skipped")
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "non-passing testcase"): self.verify()

    def test_lab_leak_into_normal_task_rejected(self):
        self.xml(self.normal_xml, self.tests + [MODULE.LAB_CLASS + "::contract()"])
        with self.assertRaisesRegex(ValueError, "leaked"): self.verify()

    def test_migration_source_mutation_rejected(self):
        (self.root / "order-service/src/main/resources/db/migration/V1__fixture.sql").write_text("SELECT 3;")
        with self.assertRaisesRegex(ValueError, "source manifest mismatch"): self.verify()

    def test_missing_child_invocation_rejected(self):
        self.report["invocations"].pop()
        with self.assertRaisesRegex(ValueError, "missing Flyway JVM"): self.verify()

    def test_same_jvm_substitution_rejected(self):
        self.report["invocations"][1]["pid"] = self.report["invocations"][0]["pid"]
        with self.assertRaisesRegex(ValueError, "identity reused"): self.verify()

    def test_missing_negative_outcomes_rejected(self):
        self.report["invocations"][0]["success"] = True
        with self.assertRaisesRegex(ValueError, "missing negative"): self.verify()

    def test_restart_data_loss_rejected(self):
        (self.lab / "after-restart.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "restart snapshot"): self.verify()

    def test_child_result_tamper_rejected(self):
        (self.lab / "child-0.json").write_text(json.dumps(dict(self.report["invocations"][0], success=True)))
        with self.assertRaisesRegex(ValueError, "artifact mismatch"): self.verify()


if __name__ == "__main__":
    unittest.main()
