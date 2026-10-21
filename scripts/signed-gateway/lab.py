#!/usr/bin/env python3
"""Own a disposable signed HTTP stack; never target an existing Compose project."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker-compose.signed-gateway.yml"
SERVICES = {"order", "product", "payment", "notification"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def atomic_json(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n")
    temp.replace(path)


def output(args, timeout=10):
    return subprocess.check_output(args, text=True, timeout=timeout).strip()


def cleanup(manifest, evidence):
    owner = json.loads(manifest.read_text())
    nonce, project = owner["nonce"], owner["project"]
    require(re.fullmatch(r"[0-9a-f]{24}", nonce) is not None, "invalid ownership nonce")
    require(project == "signed-" + nonce, "invalid ownership project")
    removed = {"container": [], "network": [], "volume": []}
    for kind, flags in (("container", ["ps", "-aq", "--no-trunc"]),
                        ("network", ["network", "ls", "-q", "--no-trunc"]),
                        ("volume", ["volume", "ls", "-q"])):
        selected = output(["docker", *flags, "--filter", "label=peekcart.fixture=signed-gateway",
                           "--filter", "label=peekcart.fixture.nonce=" + nonce]).split()
        for identity in selected:
            inspect = json.loads(output(["docker", kind, "inspect", identity]))[0]
            labels = inspect.get("Config", {}).get("Labels", {}) if kind == "container" else inspect.get("Labels", {})
            require(labels.get("peekcart.fixture.nonce") == nonce and labels.get("com.docker.compose.project") == project,
                    "cleanup ownership mismatch")
            actual = inspect.get("Id") if kind != "volume" else inspect.get("Name")
            require(actual == identity, "cleanup immutable identity mismatch")
            command = ["docker", kind, "rm"] + (["-f"] if kind == "container" else []) + [identity]
            subprocess.run(command, check=True, timeout=30, stdout=subprocess.DEVNULL)
            removed[kind].append(identity)
    previous = evidence / "cleanup.json"
    if previous.exists():
        old = json.loads(previous.read_text()).get("removed", {})
        removed = {kind: sorted(set(ids + old.get(kind, []))) for kind, ids in removed.items()}
    atomic_json(previous, {"complete": True, "removed": removed})


def sanitize(evidence, nonce):
    """Remove unsafe artifacts before any uploader sees the directory; never echo matched data."""
    forbidden = re.compile(rb"-----BEGIN (?:RSA )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}")
    password = ("Fixture-" + nonce + "-123!").encode()
    private_path = str(Path(tempfile.gettempdir()) / ("signed-keys-" + nonce)).encode()
    removed = []
    for path in evidence.rglob("*"):
        if path.is_file() and (forbidden.search(path.read_bytes()) or password in path.read_bytes() or private_path in path.read_bytes()):
            removed.append(str(path.relative_to(evidence)))
            path.unlink()
    atomic_json(evidence / "artifact-scan.json", {"safe": not removed, "removedFiles": removed})
    require(not removed, "unsafe evidence removed before artifact upload")


class Lab:
    def __init__(self, service, evidence):
        require(service in SERVICES, "unsupported resource")
        self.service = service
        self.out = evidence.resolve()
        require(not self.out.exists(), "evidence directory already exists")
        self.out.mkdir(parents=True)
        self.nonce = secrets.token_hex(12)
        self.project = "signed-" + self.nonce
        self.manifest = self.out / "owner.json"
        # Persist intent before any resource creation; cleanup can recover a killed parent.
        atomic_json(self.manifest, {"nonce": self.nonce, "project": self.project, "service": service})
        self.private = Path(tempfile.gettempdir()) / ("signed-keys-" + self.nonce)
        self.private.mkdir(mode=0o700)
        self.env = dict(os.environ, SIGNED_SERVICE=service, SIGNED_NONCE=self.nonce,
                        SIGNED_PRIVATE_DIR=str(self.private), SIGNED_OUT_DIR=str(self.out), SIGNED_USER_TTL="1800000", SIGNED_IMAGE_TAG="ci",
                        SIGNED_KAFKA_CLUSTER_ID=base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("="))
        self.command = ["docker", "compose", "-f", str(COMPOSE), "-p", self.project]
        self.result = {"service": service, "complete": False, "phases": []}

    def run(self, args, log, timeout):
        with (self.out / log).open("wb") as stream:
            subprocess.run(args, env=self.env, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                           check=True, timeout=timeout)

    def text(self, name, value):
        path = self.private / name
        if path.exists():
            path.chmod(0o600)
        path.write_text(value)
        path.chmod(0o444)  # directory stays0700; only explicitly mounted issuer files are readable there.

    def prepare(self):
        # A foreign project with the same name must never be reused, even if it has no running containers.
        for flags in (["ps", "-aq"], ["network", "ls", "-q"], ["volume", "ls", "-q"]):
            require(not output(["docker", *flags, "--filter", "label=com.docker.compose.project=" + self.project]),
                    "existing Compose project refused")
        for name in ("user", "gateway", "foreign"):
            private = self.private / (name + "-private.pem")
            public = self.private / (name + "-public.pem")
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private)],
                           check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)],
                           check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            private.chmod(0o444)
            public.chmod(0o444)
        (self.private / "foreign-private.pem").unlink()  # only its public key is needed for the internal trust negative.
        self.text("resource-public.pem", (self.private / "gateway-public.pem").read_text())
        image_ids = {name: output(["docker", "image", "inspect", "--format", "{{.Id}}", name + ":ci"])
                     for name in ("user-service", "gateway", self.service + "-service")}
        atomic_json(self.out / "source-images.json", {"checkout": output(["git", "-C", str(ROOT), "rev-parse", "HEAD"]),
                                                     "images": image_ids})

    def phase(self, name):
        self.run(self.command + ["run", "--rm", "--no-deps", "runner", name], name + ".log", 90)
        report = json.loads((self.out / (name + ".json")).read_text())
        require(report.get("complete") is True and report.get("service") == self.service, "phase evidence incomplete")
        self.result["phases"].append(name)

    def restart(self, name, phase):
        self.run(self.command + ["up", "-d", "--force-recreate", "--no-deps", "--wait",
                                 "--wait-timeout", "300", name], phase + "-restart.log", 360)

    def execute(self):
        primary = None
        try:
            self.prepare()
            self.run(self.command + ["up", "-d", "--wait", "--wait-timeout", "420", "user-service", "resource-service", "gateway"],
                     "startup.log", 600)
            self.phase("baseline")
            self.text("resource-public.pem", (self.private / "foreign-public.pem").read_text())
            self.restart("resource-service", "wrong-key")
            self.phase("wrong-key")
            self.text("resource-public.pem", (self.private / "gateway-public.pem").read_text())
            self.restart("resource-service", "restored")
            self.phase("restored")
            self.env["SIGNED_USER_TTL"] = "10000"
            self.restart("user-service", "expired")
            self.phase("expired")
            self.env["SIGNED_USER_TTL"] = "1800000"
            self.restart("user-service", "final")
            self.phase("final")
            self.result["complete"] = True
        except BaseException as failure:
            primary = type(failure).__name__
            self.result["failureType"] = primary
        finally:
            try:
                self.run(self.command + ["logs", "--no-color"], "services.log", 15)
            except BaseException as failure:
                self.result["logCollectionFailure"] = type(failure).__name__
            try:
                cleanup(self.manifest, self.out)
            except BaseException as failure:
                self.result["cleanupFailure"] = type(failure).__name__
                primary = primary or "cleanup failure"
            shutil.rmtree(self.private)
            atomic_json(self.out / "result.json", self.result)
            try:
                sanitize(self.out, self.nonce)
            except BaseException:
                primary = primary or "unsafe evidence"
        require(primary is None, "signed fixture failed; preserved sanitized phase evidence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("run", "cleanup", "sanitize"))
    parser.add_argument("--service", choices=sorted(SERVICES))
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if args.operation == "run":
        Lab(args.service, args.evidence).execute()
    elif (args.evidence / "owner.json").exists():
        if args.operation == "cleanup":
            cleanup(args.evidence / "owner.json", args.evidence)
            nonce = json.loads((args.evidence / "owner.json").read_text())["nonce"]
            shutil.rmtree(Path(tempfile.gettempdir()) / ("signed-keys-" + nonce), ignore_errors=True)
        sanitize(args.evidence, json.loads((args.evidence / "owner.json").read_text())["nonce"])


if __name__ == "__main__":
    main()
