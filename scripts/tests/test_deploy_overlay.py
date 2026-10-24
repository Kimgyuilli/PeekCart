#!/usr/bin/env python3
"""deploy-overlay.sh 의 kafka 선행 적용과 토픽 사전 생성 (D-107, 계획 V1~V6).

가짜 kubectl(scripts/tests/fixtures/deploy-overlay-kubectl.py)을 PATH 앞에 두고 실제 래퍼를 돌린다.
클러스터에 닿는 명령은 전부 가짜가 받고 `kubectl kustomize` 만 실제 kubectl 로 오프라인 렌더한다.
토픽 목록은 실제 lint emit 이라 토폴로지가 바뀌면 기대값도 따라 바뀐다. 래퍼가 먼저 적용한 객체는
래퍼의 선택 코드와 다른 파서(pyyaml)로 읽어 같은 렌더의 객체와 대조한다.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WRAPPER = ROOT / "scripts/deploy-overlay.sh"
FIXTURE = ROOT / "scripts/tests/fixtures/deploy-overlay-kubectl.py"
LINT = ROOT / "scripts/kafka-subscription-contract-lint.sh"
REAL_KUBECTL = shutil.which("kubectl")
KAFKA_FIRST = {("Namespace", "peekcart"), ("Service", "kafka"), ("PersistentVolumeClaim", "kafka-pvc"),
               ("Deployment", "kafka")}
FULL = ["kustomize", "apply-f", "rollout", "exec:sh", "exec:kafka-topics.sh", "apply-k"]
# 래퍼가 읽거나 쓰는 환경 변수. 개발자 셸의 값이 테스트에 새지 않게 지운다.
WRAPPER_ENV = ("GW_URL", "NAMESPACE", "PEEKCART_NAMESPACE", "DRAIN_PREFLIGHT_SKIP_REASON",
               "TOPIC_PRECREATE_SKIP_REASON", "KUBECONFIG")


def app_overlays():
    """kustomize-namespace-lint.sh 가 렌더하는 앱 overlay 목록. 거기 더해지는 overlay 를 여기서도 본다."""
    text = (ROOT / "scripts/kustomize-namespace-lint.sh").read_text(encoding="utf-8")
    block = re.search(r"^OVERLAYS=\((.*?)^\)", text, re.M | re.S)
    overlays = re.findall(r'"(k8s/overlays/[^"]+)"', block.group(1)) if block else []
    if len(overlays) < 2:
        raise AssertionError("kustomize-namespace-lint.sh 의 OVERLAYS 를 읽지 못했다")
    return overlays


def render(overlay):
    out = subprocess.run([REAL_KUBECTL, "kustomize", str(ROOT / overlay)], capture_output=True, text=True,
                         check=True, env={**os.environ, "KUBECONFIG": os.devnull}).stdout
    return objects(out)


def objects(text):
    return {(doc["kind"], doc["metadata"]["name"]): doc for doc in yaml.safe_load_all(text) if doc}


class Run:
    def __init__(self, proc, calls, state):
        self.code, self.out, self.err = proc.returncode, proc.stdout, proc.stderr
        self.calls, self.state = calls, state

    def sequence(self):
        """가짜 브로커 CLI 호출(kafka-topics)을 뺀 kubectl 호출 순서."""
        return [("exec:" + c["command"]) if c["kind"] == "exec" else c["kind"]
                for c in self.calls if c["kind"] != "kafka-topics"]

    def kafka_first(self):
        applied = [c["text"] for c in self.calls if c["kind"] == "apply-f"]
        assert len(applied) == 1, applied
        return applied[0]


class WrapperCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if REAL_KUBECTL is None:
            raise AssertionError("kubectl 이 없다. overlay 렌더(kubectl kustomize)에 필요하다")
        out = subprocess.run(["bash", str(LINT), "--emit-topics"], capture_output=True, text=True, check=True).stdout
        cls.declared = {topic: int(parts) for topic, parts in (line.split("\t") for line in out.splitlines())}

    def run_wrapper(self, overlay="k8s/overlays/gke", state=None, inject=None, env=None, wrapper=WRAPPER):
        home = Path(tempfile.mkdtemp(prefix="deploy-overlay-"))
        self.addCleanup(shutil.rmtree, home, True)
        for target in (home / "bin/kubectl", home / "kafka-bin/kafka-topics.sh"):
            target.parent.mkdir(exist_ok=True)
            shutil.copy(FIXTURE, target)
            target.chmod(0o755)
        (home / "state.json").write_text(json.dumps(state or {}), encoding="utf-8")
        (home / "inject.json").write_text(json.dumps(inject or {}), encoding="utf-8")
        (home / "calls.jsonl").touch()
        run_env = {k: v for k, v in os.environ.items() if k not in WRAPPER_ENV}
        # KUBECONFIG 를 비워 둔다. 가짜를 거치지 않는 kubectl 호출이 생겨도 실제 클러스터에 닿지 않는다.
        run_env.update(PATH=f"{home / 'bin'}{os.pathsep}{os.environ['PATH']}", DEPLOY_FIXTURE_DIR=str(home),
                       REAL_KUBECTL=REAL_KUBECTL, KUBECONFIG=os.devnull,
                       DRAIN_PREFLIGHT_SKIP_REASON="test: 가짜 클러스터")
        for key, value in (env or {}).items():
            if value is None:
                run_env.pop(key, None)
            else:
                run_env[key] = value
        argv = ["bash", str(wrapper)] + ([overlay] if overlay is not None else [])
        proc = subprocess.run(argv, env=run_env, capture_output=True, text=True, timeout=300)
        calls = [json.loads(line) for line in (home / "calls.jsonl").read_text(encoding="utf-8").splitlines()]
        return Run(proc, calls, json.loads((home / "state.json").read_text(encoding="utf-8")))

    def assert_kafka_first_is_the_overlay_render(self, run, overlay):
        first = run.kafka_first()
        applied = objects(first)
        self.assertEqual(set(applied), KAFKA_FIRST)
        rendered = render(overlay)
        for key in KAFKA_FIRST:
            self.assertEqual(applied[key], rendered[key], key)
        # 빈 클러스터에서는 Namespace 가 먼저 생겨야 나머지가 만들어진다.
        self.assertEqual(next(d for d in yaml.safe_load_all(first) if d)["kind"], "Namespace")
        return applied

    def assert_blocked(self, run, code, needle, sequence):
        self.assertEqual(run.code, code, run.out + run.err)
        self.assertIn(needle, run.err)
        self.assertEqual(run.sequence(), sequence)


class PrecreateTest(WrapperCase):
    """V1·V2 — 빈 클러스터와 재배포."""

    def test_fresh_cluster_creates_declared_topics_before_apps(self):
        for overlay in ("k8s/overlays/gke", "k8s/overlays/gke-d002bc"):
            with self.subTest(overlay=overlay):
                run = self.run_wrapper(overlay)
                self.assertEqual(run.code, 0, run.err)
                self.assertEqual(run.sequence(), FULL)
                self.assertEqual(run.state, self.declared)
                self.assert_kafka_first_is_the_overlay_render(run, overlay)
                ops = [c.get("op") for c in run.calls if c["kind"] == "kafka-topics"]
                self.assertEqual(ops, ["create"] * len(self.declared) + ["describe"])
                self.assertEqual([c["topic"] for c in run.calls if c.get("op") == "create"], list(self.declared))
                self.assertIn(f"토픽 {len(self.declared)}개 파티션 수 일치", run.out)
                self.assertEqual([c["overlay"] for c in run.calls if c["kind"] == "apply-k"], [overlay])

    def test_redeploy_keeps_existing_topics(self):
        existing = {**self.declared, "__consumer_offsets": 50}
        run = self.run_wrapper(state=existing)
        self.assertEqual(run.code, 0, run.err)
        self.assertEqual(run.sequence(), FULL)
        self.assertEqual(run.state, existing)


class FailureTest(WrapperCase):
    """V3 — 실패마다 exit 코드와, 그 뒤 단계·앱 apply 가 일어나지 않는지."""

    def test_topic_creation_failure(self):
        run = self.run_wrapper(inject={"create_fail": ["order.created"]})
        self.assert_blocked(run, 1, "토픽 생성이 실패했다", ["kustomize", "apply-f", "rollout", "exec:sh"])
        self.assertNotIn("describe", [c.get("op") for c in run.calls])

    def test_partition_mismatch_blocks_apps(self):
        for topic, partitions in (("payment.requested", 1), ("order.created.dlq", 3)):
            with self.subTest(topic=topic):
                run = self.run_wrapper(state={**self.declared, topic: partitions})
                self.assert_blocked(run, 1, f"{topic}: {partitions} (선언 {self.declared[topic]})", FULL[:-1])
                self.assertIn("파티션 수가 선언과 다르다", run.err)

    def test_describe_failure_or_unreadable_output(self):
        for inject in ({"describe_exit": 1}, {"describe_output": ""},
                       {"describe_output": "Topic: order.created PartitionCount=3\n"}):
            with self.subTest(inject=inject):
                run = self.run_wrapper(inject=inject)
                needle = "describe 가 실패했다" if "describe_exit" in inject else "order.created: 없음 (선언 3)"
                self.assert_blocked(run, 1, needle, FULL[:-1])

    def test_kafka_not_ready(self):
        run = self.run_wrapper(inject={"rollout_exit": 1})
        self.assert_blocked(run, 1, "kafka 롤아웃이 300초 안에 끝나지 않았다", ["kustomize", "apply-f", "rollout"])

    def test_kafka_first_apply_failure(self):
        run = self.run_wrapper(inject={"apply_f_exit": 1})
        self.assert_blocked(run, 1, "kafka 선행 적용이 실패했다", ["kustomize", "apply-f"])

    def test_inventory_failure_is_a_precondition(self):
        for name, body in (("emit-fails", "exit 1"), ("not-tsv", "echo 'order.created three'"),
                           ("too-few", "printf 'order.created\\t3\\n'")):
            with self.subTest(case=name):
                repo = Path(tempfile.mkdtemp(prefix="deploy-overlay-repo-"))
                self.addCleanup(shutil.rmtree, repo, True)
                (repo / "scripts").mkdir()
                (repo / "k8s/overlays/gke").mkdir(parents=True)
                shutil.copy(WRAPPER, repo / "scripts/deploy-overlay.sh")
                (repo / "scripts/kafka-subscription-contract-lint.sh").write_text(
                    "#!/usr/bin/env bash\n" + body + "\n", encoding="utf-8")
                run = self.run_wrapper(wrapper=repo / "scripts/deploy-overlay.sh")
                self.assert_blocked(run, 2, "토픽 정본", [])

    def test_overlay_without_kafka_or_unrenderable(self):
        no_kafka = Path(tempfile.mkdtemp(prefix="overlay-no-kafka-"))
        self.addCleanup(shutil.rmtree, no_kafka, True)
        (no_kafka / "kustomization.yml").write_text(
            "apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ns.yml\n  - cm.yml\n",
            encoding="utf-8")
        (no_kafka / "ns.yml").write_text("apiVersion: v1\nkind: Namespace\nmetadata:\n  name: peekcart\n", encoding="utf-8")
        (no_kafka / "cm.yml").write_text("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: drill\n"
                                         "  namespace: peekcart\ndata:\n  a: b\n", encoding="utf-8")
        run = self.run_wrapper(overlay=str(no_kafka))
        self.assert_blocked(run, 2, "TOPIC_PRECREATE_SKIP_REASON", ["kustomize"])
        self.assertIn("Deployment/kafka", run.err)

        empty = Path(tempfile.mkdtemp(prefix="overlay-empty-"))
        self.addCleanup(shutil.rmtree, empty, True)
        run = self.run_wrapper(overlay=str(empty))
        self.assert_blocked(run, 2, "overlay 를 렌더하지 못했다", ["kustomize"])

    def test_drain_preflight_still_runs_first(self):
        run = self.run_wrapper(env={"DRAIN_PREFLIGHT_SKIP_REASON": None})
        self.assertEqual(run.code, 1, run.out + run.err)
        self.assertIn("preflight 가 막았다", run.err)
        self.assertEqual(set(run.sequence()), {"get"})


class ScopeTest(WrapperCase):
    """V4·V5·V6 — 적용 범위, 건너뛰기, 사용법."""

    def test_every_app_overlay_brings_up_its_own_kafka_first(self):
        overlays = app_overlays()
        self.assertIn("k8s/overlays/gke", overlays)
        for overlay in overlays:
            with self.subTest(overlay=overlay):
                # 롤아웃 실패를 주입해 선행 단계까지만 본다.
                run = self.run_wrapper(overlay, inject={"rollout_exit": 1})
                self.assertEqual(run.code, 1, run.err)
                self.assertEqual(run.sequence(), ["kustomize", "apply-f", "rollout"])
                applied = self.assert_kafka_first_is_the_overlay_render(run, overlay)
                if overlay == "k8s/overlays/gke":
                    # overlay patch 가 반영된 PVC 를 먼저 만든다. base PVC 를 먼저 만들면 이 값이 빠진다.
                    self.assertEqual(applied[("PersistentVolumeClaim", "kafka-pvc")]["spec"]["storageClassName"],
                                     "standard-rwo")

    def test_skip_reason_is_logged_and_skips_only_the_topic_step(self):
        run = self.run_wrapper(env={"TOPIC_PRECREATE_SKIP_REASON": "kafka 없는 드릴 overlay"})
        self.assertEqual(run.code, 0, run.err)
        self.assertEqual(run.sequence(), ["apply-k"])
        self.assertIn("!! 토픽 사전 생성을 건너뛴다 — 사유: kafka 없는 드릴 overlay", run.out)

    def test_usage_errors_keep_exit_2(self):
        for overlay in (None, "k8s/overlays/no-such-overlay"):
            with self.subTest(overlay=overlay):
                run = self.run_wrapper(overlay=overlay)
                self.assertEqual(run.code, 2, run.out + run.err)
                self.assertEqual(run.calls, [])


if __name__ == "__main__":
    unittest.main()
