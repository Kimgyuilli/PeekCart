#!/usr/bin/env python3
"""Hermetic controls only; actual signed HTTP evidence is produced by Actions."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml
import evidence
import lab


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "evidence"
        self.directory.mkdir()
        self.images = {name: "sha256:" + "a" * 64 for name in ("gateway", "user-service", "order-service")}
        self.write("result.json", {"complete": True, "service": "order", "phases": list(evidence.PHASES)})
        self.write("source-images.json", {"checkout": "source", "images": self.images})
        before = {"ledger": [{"id": 1001}], "outbox": []}
        self.write("before.json", before)
        for phase, cases in evidence.PHASES.items():
            self.write(phase + ".json", {"complete": True, "service": "order", "phase": phase,
                "cases": [{"case": name, "status": min(status), "disabled": True} for name, status in cases.items()],
                "unchangedSha256": hashlib.sha256(evidence.canonical(before)).hexdigest()})
            self.write(phase + "-after.json", before)
        provenance = {}
        for service in ("user", "order"):
            folder = self.root / (service + "-service/src/main/resources/db/migration")
            folder.mkdir(parents=True)
            (folder / "V1__fixture.sql").write_text("SELECT 1;")
            provenance[service] = {"V1__fixture.sql": hashlib.sha256(b"SELECT 1;").hexdigest()}
        self.write("migration-provenance.json", provenance)
        self.write("cleanup.json", {"complete": True})
        self.write("artifact-scan.json", {"safe": True})

    def write(self, name, value):
        (self.directory / name).write_text(json.dumps(value))

    def verify(self):
        evidence.verify(self.directory, "order", "source", self.images, self.root)

    def test_positive_and_missing_case(self):
        self.verify()
        report = evidence.read(self.directory / "baseline.json")
        report["cases"].pop()
        self.write("baseline.json", report)
        with self.assertRaises(AssertionError): self.verify()

    def test_mutations(self):
        for filename, key, value in (("result.json", "complete", False), ("result.json", "service", "payment"),
                                     ("source-images.json", "checkout", "other"), ("cleanup.json", "complete", False),
                                     ("artifact-scan.json", "safe", False), ("baseline.json", "unchangedSha256", "bad")):
            with self.subTest(filename=filename, key=key):
                original = evidence.read(self.directory / filename)
                changed = dict(original, **{key: value})
                self.write(filename, changed)
                with self.assertRaises(AssertionError): self.verify()
                self.write(filename, original)
        for name, change in (("status", 429), ("disabled", False)):
            report = evidence.read(self.directory / "baseline.json")
            changed = copy.deepcopy(report)
            changed["cases"][0][name] = change
            self.write("baseline.json", changed)
            with self.assertRaises(AssertionError): self.verify()
            self.write("baseline.json", report)
        self.write("final-after.json", {"ledger": [], "outbox": [{"id": 1}]})
        with self.assertRaises(AssertionError): self.verify()

    def test_missing_phase_and_source(self):
        (self.directory / "wrong-key.json").unlink()
        with self.assertRaises(FileNotFoundError): self.verify()


class OwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.nonce = "a" * 24
        self.owner = self.directory / "owner.json"
        self.owner.write_text(json.dumps({"nonce": self.nonce, "project": "signed-" + self.nonce}))

    def test_creation_interrupted_cleanup_uses_persisted_nonce(self):
        identity = "b" * 64
        def response(args):
            if args[1] == "ps": return identity
            if args[1:3] == ["container", "inspect"]:
                return json.dumps([{"Id": identity, "Config": {"Labels": {
                    "peekcart.fixture.nonce": self.nonce, "com.docker.compose.project": "signed-" + self.nonce}}}])
            return ""
        with patch.object(lab, "output", side_effect=response), patch.object(lab.subprocess, "run") as remove:
            lab.cleanup(self.owner, self.directory)
            self.assertEqual(remove.call_args.args[0], ["docker", "container", "rm", "-f", identity])
        self.assertTrue(evidence.read(self.directory / "cleanup.json")["complete"])

    def test_foreign_collision_never_deleted(self):
        def response(args):
            if args[1] == "ps": return "foreign"
            return json.dumps([{"Id": "foreign", "Config": {"Labels": {"peekcart.fixture.nonce": "other"}}}])
        with patch.object(lab, "output", side_effect=response), patch.object(lab.subprocess, "run") as remove:
            with self.assertRaises(ValueError): lab.cleanup(self.owner, self.directory)
            remove.assert_not_called()

    def test_before_creation_cleanup_is_empty_and_idempotent(self):
        with patch.object(lab, "output", return_value=""), patch.object(lab.subprocess, "run") as remove:
            lab.cleanup(self.owner, self.directory)
            lab.cleanup(self.owner, self.directory)
            remove.assert_not_called()

    def test_unsafe_artifacts_removed(self):
        for content in (b"-----BEGIN PRIVATE KEY-----", b"eyJ" + b"a" * 20 + b"." + b"a" * 20 + b"." + b"a" * 20,
                        ("Fixture-" + self.nonce + "-123!").encode()):
            (self.directory / "unsafe.log").write_bytes(content)
            with self.assertRaises(ValueError): lab.sanitize(self.directory, self.nonce)
            self.assertFalse((self.directory / "unsafe.log").exists())
        lab.sanitize(self.directory, self.nonce)

    def test_prepare_failure_and_request_timeout_preserve_failure_and_cleanup(self):
        for failure_at in ("prepare", "phase"):
            with self.subTest(failure_at=failure_at):
                instance = lab.Lab("order", self.directory / failure_at)
                self.assertTrue(instance.manifest.exists())
                with patch.object(instance, "prepare"), patch.object(instance, "run"), \
                     patch.object(instance, "phase"), patch.object(lab, "cleanup") as clean:
                    getattr(instance, failure_at).side_effect = TimeoutError("fixture boundary")
                    with self.assertRaises(ValueError): instance.execute()
                    clean.assert_called_once_with(instance.manifest, instance.out)
                result = evidence.read(instance.out / "result.json")
                self.assertEqual(result["failureType"], "TimeoutError")
                self.assertFalse(result["complete"])
                self.assertFalse(instance.private.exists())

    def test_compose_is_closed_and_issuer_keys_are_separate(self):
        config = yaml.safe_load(lab.COMPOSE.read_text())
        self.assertTrue(config["networks"]["internal"]["internal"])
        for service in config["services"].values():
            self.assertNotIn("ports", service)
            self.assertNotIn("container_name", service)
        for name in ("user-service", "resource-service"):
            env = config["services"][name]["environment"]
            self.assertEqual(env["APP_INTERNAL_TOKEN_MODE"], "SIGNED_ONLY")
            self.assertEqual(env["APP_DEAD_LETTER_REPLAY_ENABLED"], "false")
        for name in ("resource-service", "runner"):
            self.assertFalse(any("private.pem" in mount for mount in config["services"][name]["volumes"]))
        for name in ("user-service", "gateway", "resource-service"):
            config_json = config["services"][name]["environment"]["SPRING_APPLICATION_JSON"]
            json.loads(config_json.replace("${SIGNED_USER_TTL:-1800000}", "1800000"))


if __name__ == "__main__":
    unittest.main()
