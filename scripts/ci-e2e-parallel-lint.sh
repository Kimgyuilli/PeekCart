#!/usr/bin/env bash
# CI e2e 시나리오와 음성 대조군이 PR/push 모두에서 병렬 필수인지 검사한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="${CI_WORKFLOW:-$ROOT/.github/workflows/ci.yml}"
python3 - "$WORKFLOW" "${1:-}" <<'PY'
import copy
import sys

import yaml


def validate(doc):
    errors = []
    jobs = doc.get("jobs", {})
    lint = jobs.get("lint", {})
    lint_commands = "\n".join(step.get("run", "") for step in lint.get("steps", []))
    if "bash scripts/ci-e2e-parallel-lint.sh --self-test" not in lint_commands:
        errors.append("lint 잡에서 자체 변이 검사를 실행하지 않는다")

    job = jobs.get("e2e", {})
    needs = job.get("needs", [])
    if isinstance(needs, str):
        needs = [needs]
    if needs != ["images"]:
        errors.append("e2e 의존이 images 단독이 아니다")
    if job.get("if"):
        errors.append("e2e 잡에 이벤트 필터가 있어 PR 또는 push를 건너뛸 수 있다")

    strategy = job.get("strategy", {})
    if strategy.get("fail-fast") is not False:
        errors.append("e2e fail-fast가 false가 아니다")
    modes = strategy.get("matrix", {}).get("mode", [])
    if modes != ["scenarios", "negative-control"]:
        errors.append("e2e 모드가 시나리오·음성 대조군 정확히 2개가 아니다")

    run_id = job.get("env", {}).get("E2E_RUN_ID", "")
    if any(token not in run_id for token in
           ("${{ github.run_id }}", "${{ github.run_attempt }}", "${{ matrix.mode }}")):
        errors.append("e2e run ID가 실행·재시도·모드별로 고유하지 않다")

    steps = {step.get("name"): step for step in job.get("steps", [])}
    required = {
        "시나리오 4종": ("scenarios", "bash scripts/saga-e2e-smoke.sh"),
        "음성 대조군": ("negative-control", "bash scripts/saga-e2e-smoke.sh --negative-control"),
        "saga contract matrix gate (structure + e2e evidence)":
            ("scenarios", "bash scripts/saga-contract-matrix-lint.sh --e2e-evidence"),
    }
    for name, (mode, command) in required.items():
        step = steps.get(name, {})
        if step.get("if") != "matrix.mode == '%s'" % mode:
            errors.append("%s 조건이 %s 전용이 아니다" % (name, mode))
        if command not in step.get("run", ""):
            errors.append("%s 실행 명령이 없다" % name)

    artifact = steps.get("증적 업로드", {})
    if artifact.get("if") != "always()":
        errors.append("실패한 e2e의 증적이 업로드되지 않는다")
    if "${{ matrix.mode }}" not in artifact.get("with", {}).get("name", ""):
        errors.append("e2e 증적 artifact 이름이 모드별로 고유하지 않다")
    return errors


with open(sys.argv[1], encoding="utf-8") as workflow:
    source = yaml.safe_load(workflow)

errors = validate(source)
for error in errors:
    print("[ci-e2e-parallel] " + error, file=sys.stderr)
if errors:
    sys.exit(1)

if sys.argv[2] == "--self-test":
    def step(doc, name):
        return next(item for item in doc["jobs"]["e2e"]["steps"] if item.get("name") == name)

    mutations = {
        "대조군 모드 삭제": lambda d: d["jobs"]["e2e"]["strategy"]["matrix"].update(mode=["scenarios"]),
        "대조군 명령 삭제": lambda d: step(d, "음성 대조군").update(run="true"),
        "모드 조건 삭제": lambda d: step(d, "시나리오 4종").pop("if"),
        "이미지 의존 삭제": lambda d: d["jobs"]["e2e"].update(needs=[]),
        "게이트 대기 추가": lambda d: d["jobs"]["e2e"].update(needs=["images", "gate"]),
        "증적 게이트 삭제": lambda d: step(d, "saga contract matrix gate (structure + e2e evidence)").update(run="true"),
        "증적 이름 충돌": lambda d: step(d, "증적 업로드")["with"].update(name="e2e-evidence"),
    }
    for name, mutate in mutations.items():
        changed = copy.deepcopy(source)
        mutate(changed)
        if not validate(changed):
            print("[ci-e2e-parallel] 자체 검사 실패: " + name, file=sys.stderr)
            sys.exit(1)
    print("ci-e2e-parallel self-test OK (%d/%d)" % (len(mutations), len(mutations)))
else:
    print("ci-e2e-parallel OK (scenarios + negative-control)")
PY
