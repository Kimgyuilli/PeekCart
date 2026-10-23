#!/usr/bin/env bash
# CI e2e 시나리오 러너 둘과 음성 대조군이 PR/push 모두에서 병렬 필수인지,
# 두 시나리오 러너가 시나리오 전체를 정확히 한 번씩 나눠 갖는지 검사한다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="${CI_WORKFLOW:-$ROOT/.github/workflows/ci.yml}"
python3 - "$WORKFLOW" "${1:-}" "$ROOT" <<'PY'
import copy
import shlex
import sys

import yaml

sys.path.insert(0, sys.argv[3] + "/scripts/e2e")
from saga_e2e import SCENARIO_ORDER  # noqa: E402 — 시나리오 목록의 정본

SCENARIO_STEPS = {"시나리오 a·b·c·d·f": "scenarios", "시나리오 e (fence)": "fence"}
EVIDENCE_STEP = "saga contract matrix — e2e evidence"


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
    if modes != ["scenarios", "fence", "negative-control"]:
        errors.append("e2e 모드가 scenarios·fence·negative-control 정확히 3개가 아니다")

    run_id = job.get("env", {}).get("E2E_RUN_ID", "")
    if any(token not in run_id for token in
           ("${{ github.run_id }}", "${{ github.run_attempt }}", "${{ matrix.mode }}")):
        errors.append("e2e run ID가 실행·재시도·모드별로 고유하지 않다")

    steps = {step.get("name"): step for step in job.get("steps", [])}
    required = {name: mode for name, mode in SCENARIO_STEPS.items()}
    required["음성 대조군"] = "negative-control"
    for name, mode in required.items():
        step = steps.get(name, {})
        if step.get("if") != "matrix.mode == '%s'" % mode:
            errors.append("%s 조건이 %s 전용이 아니다" % (name, mode))
    if steps.get("음성 대조군", {}).get("run", "") != "bash scripts/saga-e2e-smoke.sh --negative-control":
        errors.append("음성 대조군 실행 명령이 없다")

    # 러너별 --scenarios 목록의 합이 시나리오 전체와 정확히 같아야 한다. 빠진 시나리오는
    # gate 의 증적 대조가 잡지만 그건 e2e 가 끝난 뒤다 — 여기서 정적으로 먼저 잡는다.
    assigned = []
    for name in SCENARIO_STEPS:
        tokens = shlex.split(steps.get(name, {}).get("run", ""))
        if tokens[:2] != ["bash", "scripts/saga-e2e-smoke.sh"] or len(tokens) != 4 or tokens[2] != "--scenarios":
            errors.append("%s 실행 명령이 saga-e2e-smoke.sh --scenarios <목록> 형태가 아니다" % name)
            continue
        assigned.extend(tokens[3].split(","))
    if sorted(assigned) != sorted(SCENARIO_ORDER):
        errors.append("시나리오 러너들의 목록 합 %s 가 시나리오 전체 %s 와 다르다(누락·중복)"
                      % (sorted(assigned), SCENARIO_ORDER))

    gate_steps = {step.get("name"): step for step in jobs.get("gate", {}).get("steps", [])}
    evidence = gate_steps.get(EVIDENCE_STEP, {})
    download = gate_steps.get("Download e2e evidence", {})
    condition = "${{ !cancelled() && needs.e2e.result == 'success' }}"
    if evidence.get("if") != condition or download.get("if") != condition:
        errors.append("gate 의 e2e 증적 대조가 e2e 성공 시 항상 돌지 않는다")
    if "bash scripts/saga-contract-matrix-lint.sh --e2e-evidence" not in evidence.get("run", ""):
        errors.append("gate 에 e2e 증적 대조 명령이 없다")
    if "-{%s}/" % ",".join(SCENARIO_STEPS.values()) not in evidence.get("run", ""):
        errors.append("gate 의 e2e 증적 대조가 시나리오 러너 전부의 증적을 읽지 않는다")

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

    def gate_step(doc, name):
        return next(item for item in doc["jobs"]["gate"]["steps"] if item.get("name") == name)

    mutations = {
        "대조군 모드 삭제": lambda d: d["jobs"]["e2e"]["strategy"]["matrix"].update(mode=["scenarios"]),
        "대조군 명령 삭제": lambda d: step(d, "음성 대조군").update(run="true"),
        "모드 조건 삭제": lambda d: step(d, "시나리오 a·b·c·d·f").pop("if"),
        "fence 모드 삭제": lambda d: d["jobs"]["e2e"]["strategy"]["matrix"].update(
            mode=["scenarios", "negative-control"]),
        "시나리오 누락": lambda d: step(d, "시나리오 a·b·c·d·f").update(
            run="bash scripts/saga-e2e-smoke.sh --scenarios a,b,c,d"),
        "시나리오 중복": lambda d: step(d, "시나리오 e (fence)").update(
            run="bash scripts/saga-e2e-smoke.sh --scenarios d,e"),
        "기본 전체 실행으로 회귀": lambda d: step(d, "시나리오 e (fence)").update(
            run="bash scripts/saga-e2e-smoke.sh"),
        "이미지 의존 삭제": lambda d: d["jobs"]["e2e"].update(needs=[]),
        "게이트 대기 추가": lambda d: d["jobs"]["e2e"].update(needs=["images", "gate"]),
        "증적 게이트 삭제": lambda d: gate_step(d, EVIDENCE_STEP).update(run="true"),
        "증적 게이트 skip": lambda d: gate_step(d, EVIDENCE_STEP).update({"if": "false"}),
        "fence 증적 제외": lambda d: gate_step(d, EVIDENCE_STEP).update(
            run=gate_step(d, EVIDENCE_STEP)["run"].replace("{scenarios,fence}", "scenarios")),
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
    print("ci-e2e-parallel OK (scenarios + fence + negative-control)")
PY
