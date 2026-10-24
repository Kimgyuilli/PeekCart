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
spec=importlib.util.spec_from_file_location("recovery_lab",ROOT/"scripts/approval-recovery/lab.py")
lab=importlib.util.module_from_spec(spec);spec.loader.exec_module(lab)


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
        self.assertEqual(payment["TOSS_PAYMENTS_BASE_URL"],"http://pg-stub:8080/v1")
        self.assertEqual(base["services"]["resource-service"]["environment"]["APP_DEAD_LETTER_REPLAY_ENABLED"],"false")
        self.assertEqual(base["services"]["resource-service"]["environment"]["APP_INTERNAL_TOKEN_MODE"],"SIGNED_ONLY")
        self.assertEqual(overlay["services"]["product-service"]["extends"]["service"],"resource-service")
        self.assertEqual(overlay["services"]["pg-stub"]["labels"]["peekcart.fixture"],"signed-gateway")

    def test_pg_runtime_and_client_prefix_reuse_existing_working_fixture(self):
        overlay=yaml.safe_load((ROOT/"docker-compose.approval-recovery.yml").read_text())
        pg=overlay["services"]["pg-stub"]
        self.assertEqual(pg["extends"],{"file":"docker-compose.e2e.yml","service":"pg-stub"})
        self.assertNotIn("build",pg)
        existing=yaml.safe_load((ROOT/pg["extends"]["file"]).read_text())["services"]
        self.assertEqual(existing["pg-stub"]["command"],["python3","stub.py"])
        self.assertEqual(existing["pg-stub"]["volumes"],["./scripts/e2e/pg-stub:/app:ro"])
        self.assertTrue((ROOT/"scripts/e2e/pg-stub/stub.py").is_file())
        self.assertEqual(overlay["services"]["resource-service"]["environment"]["TOSS_PAYMENTS_BASE_URL"],
                         existing["payment-service"]["environment"]["TOSS_PAYMENTS_BASE_URL"])
        workflow=(ROOT/".github/workflows/approval-recovery.yml").read_text()
        self.assertNotIn("docker build -t approval-recovery-pg",workflow)

    def test_empty_partial_and_missing_image_evidence_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaises(FileNotFoundError):evidence.verify(root,"head",{})
            (root/"result.json").write_text(json.dumps({"complete":False,"phases":list(evidence.PHASES)}))
            with self.assertRaises(AssertionError):evidence.verify(root,"head",{})
            (root/"result.json").write_text(json.dumps({"complete":True,"phases":list(evidence.PHASES)}))
            (root/"source-images.json").write_text(json.dumps({"checkout":"head","images":{}}))
            with self.assertRaises(AssertionError):evidence.verify(root,"head",{})

    def test_declared_topics_cover_recovery_topics_with_declared_partitions(self):
        # Pre-creation can only prevent the late partition growth if the inventory carries these topics.
        declared=dict(lab.declared_topics())
        for topic in ("payment.approval-recovery.requested","payment.approval-recovery.execute",
                      "stock.approval-recovery.snapshot","stock.approval-recovery.result"):
            self.assertEqual(declared[topic],"3")
            self.assertEqual(declared[topic+".dlq"],"1")

    def test_partition_counts_reads_topic_summary_lines_only(self):
        # kafka-topics.sh 3.8.1 --describe; counting per-partition lines would hide a one-partition topic.
        described=("Topic: payment.approval-recovery.execute\tTopicId: OlQ3ByFwTx6sgIFiZvVtTA\tPartitionCount: 3\tReplicationFactor: 1\tConfigs: \n"
                   "\tTopic: payment.approval-recovery.execute\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: \n"
                   "\tTopic: payment.approval-recovery.execute\tPartition: 1\tLeader: 1\tReplicas: 1\tIsr: 1\tElr: \tLastKnownElr: \n"
                   "Topic: payment.approval-recovery.execute.dlq\tTopicId: kYw2Y3m0R3C0x7m0yQx3Ow\tPartitionCount: 1\tReplicationFactor: 1\tConfigs: \n")
        self.assertEqual(lab.partition_counts(described),
                         {"payment.approval-recovery.execute":"3","payment.approval-recovery.execute.dlq":"1"})


if __name__=="__main__":unittest.main()
