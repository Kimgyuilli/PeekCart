#!/usr/bin/env bash
# D-043: PR 필수 gate 와 main 이미지 승격의 정적 계약 검사.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - ".github/workflows/ci.yml" "${1:-}" <<'PY'
import copy
import re
import sys

try:
    import yaml
except ImportError:
    print("[CRG-000] pyyaml 부재", file=sys.stderr)
    sys.exit(2)

REQUIRED = ("lint", "test", "guards", "images", "e2e")


def needs(job):
    value = job.get("needs", [])
    return [value] if isinstance(value, str) else value


def step(job, name):
    return next((s for s in job.get("steps", []) if s.get("name") == name), {})


def validate(doc):
    errors = []
    jobs = doc.get("jobs", {})

    def bad(code, message):
        errors.append(f"[{code}] {message}")

    for name in (*REQUIRED, "gate", "publish"):
        if name not in jobs:
            bad("CRG-001", f"필수 job 부재: {name}")
    if errors:
        return errors

    gate, images, e2e, publish = (jobs[name] for name in ("gate", "images", "e2e", "publish"))
    if set(needs(gate)) != set(REQUIRED) or len(needs(gate)) != len(REQUIRED):
        bad("CRG-002", f"gate.needs 는 {REQUIRED} 정확히여야 한다")
    if needs(images) != ["lint"] or needs(e2e) != ["images"]:
        bad("CRG-002", "images→e2e 의존과 JVM 테스트 병렬성이 바뀌었다")
    if needs(publish) != ["gate"]:
        bad("CRG-002", "publish 는 최종 gate 만 기다려야 한다")
    if gate.get("if") != "${{ !cancelled() }}":
        bad("CRG-003", "gate 의 실패 후 진단 실행 조건이 바뀌었다")
    if any(j.get("continue-on-error") for j in (gate, e2e, publish)):
        bad("CRG-003", "필수 job 에 continue-on-error 가 있다")
    prop = step(gate, "Propagate upstream failure")
    condition = prop.get("if", "")
    for name in REQUIRED:
        if not re.search(rf"needs\.{name}\.result\s*!=\s*'success'", condition):
            bad("CRG-003", f"{name} 실패·skip 을 gate 가 전파하지 않는다")
    if "exit 1" not in prop.get("run", "") or "!cancelled()" not in condition:
        bad("CRG-003", "gate 의 최종 실패 전파 스텝이 없다")
    if publish.get("if") != "github.event_name == 'push'":
        bad("CRG-002", "publish 는 main push 전용이어야 한다")
    if publish.get("permissions", {}).get("packages") != "write":
        bad("CRG-002", "publish 의 GHCR 쓰기 권한이 없다")
    for name in ("lint", "test", "guards", "gate", "images", "e2e"):
        if jobs[name].get("permissions", {}).get("packages") == "write":
            bad("CRG-002", f"{name} 에 불필요한 GHCR 쓰기 권한이 있다")
    if e2e.get("strategy", {}).get("matrix", {}).get("mode") != ["scenarios", "negative-control"]:
        bad("CRG-002", "e2e 두 모드가 모두 필수가 아니다")

    smoke_step = step(images, "Health smoke")
    smoke = smoke_step.get("run", "")
    save = step(images, "Save image (publish 또는 e2e 소비용)").get("run", "")
    upload = step(images, "Upload image artifact").get("with", {}).get("path", "")
    if "bash scripts/docker-health-smoke.sh" not in smoke or smoke_step.get("if"):
        bad("CRG-004", "이미지 health smoke 가 사라졌다")
    if "ci-image-identity.sh record" not in save or any(
        item not in upload for item in ("image.tar.gz", "image.tar.gz.sha256", "image-id.txt")
    ):
        bad("CRG-004", "이미지 ID/checksum artifact 생산이 빠졌다")
    if "ci-image-identity.sh load" not in step(e2e, "Load images").get("run", ""):
        bad("CRG-004", "e2e 이미지 동일성 검증이 빠졌다")
    if "ci-image-identity.sh load" not in step(publish, "Load image").get("run", ""):
        bad("CRG-004", "publish 이미지 동일성 검증이 빠졌다")

    push = step(publish, "Push image").get("run", "")
    for token in (
        "docker push \"$IMG:${{ github.sha }}\"",
        "ci-image-identity.sh verify-remote",
        "imagetools create --prefer-index=false",
        '"$IMG@$DIGEST"',
        '[[ "$promoted" != "$DIGEST" ]]',
        'imagetools inspect --format',
        '[[ "$latest" != "$DIGEST" ]]',
    ):
        if token not in push:
            bad("CRG-005", f"원격 digest 승격 계약 누락: {token}")
    if 'docker push "$IMG:latest"' in push or 'docker tag ${{ matrix.service }}:ci "$IMG:latest"' in push:
        bad("CRG-005", "latest 를 검증한 원격 digest 대신 별도 push 한다")
    if any("docker build " in (s.get("run") or "") for s in publish.get("steps", [])):
        bad("CRG-005", "publish 에서 이미지를 재빌드한다")
    return errors


with open(sys.argv[1], encoding="utf-8") as source:
    workflow = yaml.safe_load(source)

errors = validate(workflow)
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)

if sys.argv[2] == "--self-test":
    def change_step(doc, job, name, field, value):
        step(doc["jobs"][job], name)[field] = value

    mutations = {}
    for job in REQUIRED:
        mutations[f"gate.needs 에서 {job} 삭제"] = (
            lambda d, job=job: d["jobs"]["gate"].update(needs=[x for x in REQUIRED if x != job]), "CRG-002")
        mutations[f"{job} 실패 전파 삭제"] = (
            lambda d, job=job: change_step(
                d, "gate", "Propagate upstream failure", "if",
                step(d["jobs"]["gate"], "Propagate upstream failure")["if"].replace(
                    f"needs.{job}.result != 'success'", "false")), "CRG-003")
    mutations.update({
        "필수 gate 이름 변경": (lambda d: d["jobs"].__setitem__("final_gate", d["jobs"].pop("gate")), "CRG-001"),
        "publish gate 우회": (lambda d: d["jobs"]["publish"].update(needs=["images"]), "CRG-002"),
        "PR publish 허용": (lambda d: d["jobs"]["publish"].__setitem__("if", "always()"), "CRG-002"),
        "gate 실패 무시": (lambda d: d["jobs"]["gate"].update(**{"continue-on-error": True}), "CRG-003"),
        "e2e 대조군 삭제": (lambda d: d["jobs"]["e2e"]["strategy"]["matrix"].update(mode=["scenarios"]), "CRG-002"),
        "health smoke 삭제": (lambda d: change_step(d, "images", "Health smoke", "run", "true"), "CRG-004"),
        "health smoke skip": (lambda d: change_step(d, "images", "Health smoke", "if", "false"), "CRG-004"),
        "이미지 ID 기록 삭제": (lambda d: change_step(d, "images", "Save image (publish 또는 e2e 소비용)", "run", "docker save example:ci"), "CRG-004"),
        "e2e ID 검사 삭제": (lambda d: change_step(d, "e2e", "Load images", "run", "docker load"), "CRG-004"),
        "publish ID 검사 삭제": (lambda d: change_step(d, "publish", "Load image", "run", "docker load"), "CRG-004"),
        "원격 config 검사 삭제": (lambda d: change_step(d, "publish", "Push image", "run", step(d["jobs"]["publish"], "Push image")["run"].replace("ci-image-identity.sh verify-remote", "echo")), "CRG-005"),
        "latest 별도 push": (lambda d: change_step(d, "publish", "Push image", "run", step(d["jobs"]["publish"], "Push image")["run"] + '\ndocker push "$IMG:latest"'), "CRG-005"),
        "승격 digest 비교 삭제": (lambda d: change_step(d, "publish", "Push image", "run", step(d["jobs"]["publish"], "Push image")["run"].replace('[[ "$promoted" != "$DIGEST" ]]', '[[ false ]]')), "CRG-005"),
        "원격 latest 조회 삭제": (lambda d: change_step(d, "publish", "Push image", "run", step(d["jobs"]["publish"], "Push image")["run"].replace('imagetools inspect --format', 'echo --format')), "CRG-005"),
    })
    for label, (change, code) in mutations.items():
        changed = copy.deepcopy(workflow)
        change(changed)
        if not any(f"[{code}]" in error for error in validate(changed)):
            print(f"자체 검사 실패: {label} — {code} 미검출", file=sys.stderr)
            sys.exit(1)
    print(f"ci-release-gate-lint self-test OK ({len(mutations)}/{len(mutations)})")
else:
    print("ci-release-gate-lint OK (최종 gate · 이미지 승격)")
PY
