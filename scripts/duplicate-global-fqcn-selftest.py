#!/usr/bin/env python3
"""Compile real app mutations in an isolated repository copy; no Docker/test execution."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, default=Path(".cache/duplicate-global-fqcn-selftest"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    evidence = args.evidence_dir.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).split(b"\0")
    with tempfile.TemporaryDirectory(prefix="peakcart-fqcn-") as directory:
        fixture = Path(directory)
        for raw in tracked:
            if not raw:
                continue
            relative = Path(os.fsdecode(raw))
            target = fixture / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / relative, target)

        def run(label, diagnostics=(), compiled=()):
            log = evidence / f"{label}.log"
            with log.open("w") as output:
                result = subprocess.run(
                    ["bash", "gradlew", "assertNoDuplicateGlobalFqcn", "--no-parallel",
                     "--max-workers=1", "--no-daemon", "--console=plain"],
                    cwd=fixture, stdout=output, stderr=subprocess.STDOUT, timeout=300,
                )
            text = log.read_text()
            for module, class_path in compiled:
                if not (fixture / module / "build/classes/java/main" / class_path).is_file():
                    raise AssertionError(f"{label}: mutation not compiled: {module}/{class_path}; {log}")
            if diagnostics:
                if result.returncode == 0 or "> Task :assertNoDuplicateGlobalFqcn FAILED" not in text:
                    raise AssertionError(f"{label}: expected guard failure, rc={result.returncode}; {log}")
                if not any(all(part in line for part in diagnostics) for line in text.splitlines()):
                    raise AssertionError(f"{label}: missing exact guard diagnostic {diagnostics}; {log}")
            else:
                apps = "gateway·notification-service·order-service·payment-service·product-service·user-service"
                if result.returncode != 0 or f"assertNoDuplicateGlobalFqcn: OK ({apps} runtime" not in text:
                    raise AssertionError(f"{label}: expected all six apps success, rc={result.returncode}; {log}")
            print(f"{label}: OK (rc={result.returncode}, {log})", flush=True)

        def copy_class(source_module, target_module, class_path):
            relative = Path("src/main/java") / class_path
            target = fixture / target_module / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(fixture / source_module / relative, target)
            return target

        outbox = Path("com/peekcart/global/outbox/OutboxRecordKind.java")
        metrics = Path("com/peekcart/global/metrics/CommitAwareMetrics.java")
        user_build = fixture / "user-service/build.gradle"
        gateway_build = fixture / "gateway/build.gradle"
        contract_build = fixture / "internal-token-contract/build.gradle"
        original_user = user_build.read_text()
        original_gateway = gateway_build.read_text()
        original_contract = contract_build.read_text()

        run("01-baseline")
        user_copy = copy_class("peekcart-common-messaging", "user-service", outbox)
        user_compiled = (("user-service", outbox.with_suffix(".class")),)
        run("02-user-no-runtime-messaging", compiled=user_compiled)
        user_build.write_text(original_user + "\ndependencies { compileOnly project(':peekcart-common-messaging') }\n")
        run("03-user-compile-only", compiled=user_compiled)
        user_build.write_text(original_user + "\ndependencies { runtimeOnly project(':peekcart-common-messaging') }\n")
        run("04-user-runtime-only", ("[user-service] 중복 FQCN com.peekcart.global.outbox.OutboxRecordKind",
                                    ":user-service", ":peekcart-common-messaging"), user_compiled)
        user_build.write_text(original_user)
        user_copy.unlink()

        gateway_copy = copy_class("peekcart-common-observability", "gateway", metrics)
        gateway_compiled = (("gateway", metrics.with_suffix(".class")),)
        duplicate = ("[gateway] 중복 FQCN com.peekcart.global.metrics.CommitAwareMetrics",
                     ":gateway", ":peekcart-common-observability")
        run("05-gateway-direct-runtime", duplicate, gateway_compiled)
        dependency = "implementation project(':peekcart-common-observability')"
        if original_gateway.count(dependency) != 1:
            raise AssertionError("gateway observability dependency fixture drift")
        gateway_build.write_text(original_gateway.replace(dependency, ""))
        contract_build.write_text(original_contract + "\ndependencies { api project(':peekcart-common-observability') }\n")
        run("06-gateway-transitive-runtime", duplicate, gateway_compiled)
        gateway_build.write_text(original_gateway)
        contract_build.write_text(original_contract)
        gateway_copy.unlink()

        forbidden = fixture / "gateway/src/main/java/com/peekcart/global/jwt/JwtProvider.java"
        forbidden.parent.mkdir(parents=True, exist_ok=True)
        forbidden.write_text("package com.peekcart.global.jwt; public class JwtProvider {}\n")
        run("07-forbidden-jwt-provider", ("[gateway] 금지된 JwtProvider 잔존: com.peekcart.global.jwt.JwtProvider",),
            (("gateway", Path("com/peekcart/global/jwt/JwtProvider.class")),))
        forbidden.unlink()
        run("08-restored")
    print("duplicate-global-fqcn self-test OK (8 compiled scenarios)")


if __name__ == "__main__":
    main()
