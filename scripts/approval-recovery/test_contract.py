#!/usr/bin/env python3
"""Small fixture boundary checks; actual recovery evidence is checked after Actions execution."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("recovery_evidence",ROOT/"scripts/approval-recovery/evidence.py")
evidence=importlib.util.module_from_spec(spec);spec.loader.exec_module(evidence)


class ContractTests(unittest.TestCase):
    def test_workflow_has_only_four_owned_images_and_no_publish(self):
        workflow=yaml.safe_load((ROOT/".github/workflows/approval-recovery.yml").read_text())
        self.assertEqual(workflow["permissions"],{"contents":"read"})
        self.assertNotIn("pull_request_target",workflow.get("on",workflow.get(True,{})))
        self.assertEqual(set(workflow["jobs"]),{"images","recovery"})
        self.assertEqual(set(workflow["jobs"]["images"]["strategy"]["matrix"]["service"]),
                         {"user-service","gateway","payment-service","product-service"})
        steps=workflow["jobs"]["recovery"]["steps"]
        cleanup=next(s for s in steps if s.get("name","").startswith("Recover only"))
        self.assertEqual(cleanup["if"],"always()")
        self.assertTrue(any("evidence.py" in s.get("run","") for s in steps))

    def test_overlay_reuses_owner_network_keys_and_explicit_separated_roles(self):
        base=yaml.safe_load((ROOT/"docker-compose.signed-gateway.yml").read_text())
        overlay=yaml.safe_load((ROOT/"docker-compose.approval-recovery.yml").read_text())
        self.assertTrue(base["networks"]["internal"]["internal"])
        for service in overlay["services"].values():self.assertNotIn("ports",service)
        payment=overlay["services"]["resource-service"]["environment"]
        self.assertEqual(payment["APP_APPROVAL_RECOVERY_APPROVERS"],"1")
        self.assertEqual(payment["APP_APPROVAL_RECOVERY_EXECUTORS"],"2")
        self.assertEqual(payment["TOSS_PAYMENTS_BASE_URL"],"http://pg-stub:8080")
        self.assertEqual(base["services"]["resource-service"]["environment"]["APP_DEAD_LETTER_REPLAY_ENABLED"],"false")
        self.assertEqual(base["services"]["resource-service"]["environment"]["APP_INTERNAL_TOKEN_MODE"],"SIGNED_ONLY")
        self.assertEqual(overlay["services"]["product-service"]["extends"]["service"],"resource-service")
        self.assertEqual(overlay["services"]["pg-stub"]["labels"]["peekcart.fixture"],"signed-gateway")

    def test_empty_partial_and_missing_image_evidence_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaises(FileNotFoundError):evidence.verify(root,"head",{})
            (root/"result.json").write_text(json.dumps({"complete":False,"phases":list(evidence.PHASES)}))
            with self.assertRaises(AssertionError):evidence.verify(root,"head",{})
            (root/"result.json").write_text(json.dumps({"complete":True,"phases":list(evidence.PHASES)}))
            (root/"source-images.json").write_text(json.dumps({"checkout":"head","images":{}}))
            with self.assertRaises(AssertionError):evidence.verify(root,"head",{})


if __name__=="__main__":unittest.main()
