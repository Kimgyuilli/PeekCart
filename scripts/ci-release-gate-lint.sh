#!/usr/bin/env bash
# D-043: PR 필수 gate 와 main 이미지 승격의 정적 계약 검사.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - ".github/workflows/ci.yml" "${1:-}" <<'PY'
import copy
import itertools
import re
import sys

try:
    import yaml
except ImportError:
    print("[CRG-000] pyyaml 부재", file=sys.stderr)
    sys.exit(2)

REQUIRED = ("lint", "test", "guards", "images", "e2e")
RESULTS = ("success", "failure", "skipped", "cancelled")


def propagation_condition(condition):
    # GitHub 식 전체를 흉내내지 않는다. 이 gate의 불리언 부분집합만 읽고
    # 알 수 없는 토큰은 거부한다. eval/문자열 치환은 사용하지 않는다.
    if not isinstance(condition, str):
        raise ValueError("조건식은 문자열이어야 한다")
    source = condition.strip()
    if source.startswith("${{") and source.endswith("}}"):
        source = source[3:-2].strip()
    token = re.compile(r"\s*(needs\.[a-z0-9]+\.result|'[a-zA-Z]+'|cancelled|true|false|!=|==|&&|\|\||[!()])")
    tokens = []
    offset = 0
    while offset < len(source):
        match = token.match(source, offset)
        if not match:
            raise ValueError(f"지원하지 않는 조건식: {source[offset:]}")
        tokens.append(match[1])
        offset = match.end()
    position = 0

    def take(expected=None):
        nonlocal position
        value = tokens[position] if position < len(tokens) else None
        if value is None or (expected is not None and value != expected):
            raise ValueError(f"조건식 토큰 오류: 기대 {expected}, 실제 {value}")
        position += 1
        return value

    def atom():
        value = take()
        if value == "!":
            if position < len(tokens) and tokens[position].startswith("needs."):
                raise ValueError("결과 비교의 부정에는 괄호가 필요하다")
            return ("!", atom())
        if value == "(":
            node = expression()
            take(")")
            return node
        if value == "cancelled":
            take("(")
            take(")")
            return ("cancelled",)
        if value in ("true", "false"):
            return (value,)
        if value.startswith("needs."):
            job = value.split(".")[1]
            operator, result = take(), take()
            if job in REQUIRED and operator in ("==", "!=") and result[1:-1].lower() in RESULTS and result.startswith("'"):
                return (operator, job, result[1:-1].lower())
        raise ValueError(f"지원하지 않는 조건식 항: {value}")

    def conjunction():
        node = atom()
        while position < len(tokens) and tokens[position] == "&&":
            take("&&")
            node = ("&&", node, atom())
        return node

    def expression():
        node = conjunction()
        while position < len(tokens) and tokens[position] == "||":
            take("||")
            node = ("||", node, conjunction())
        return node

    tree = expression()
    if position != len(tokens):
        raise ValueError(f"조건식 뒤의 잉여 토큰: {tokens[position:]}")
    # 상태 함수가 없으면 Actions가 success()를 암묵적으로 추가하므로 실패 후 실행되지 않는다.
    if "cancelled" not in tokens:
        raise ValueError("명시적인 cancelled() 상태 함수가 필요하다")
    return tree


def evaluate(node, results, cancelled):
    operator = node[0]
    if operator == "&&":
        return evaluate(node[1], results, cancelled) and evaluate(node[2], results, cancelled)
    if operator == "||":
        return evaluate(node[1], results, cancelled) or evaluate(node[2], results, cancelled)
    if operator == "!":
        return not evaluate(node[1], results, cancelled)
    if operator in ("==", "!="):
        equal = results[node[1]] == node[2]
        return equal if operator == "==" else not equal
    return {"cancelled": cancelled, "true": True, "false": False}[operator]


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
    try:
        tree = propagation_condition(condition)
        for values in itertools.product(RESULTS, repeat=len(REQUIRED)):
            results = dict(zip(REQUIRED, values))
            for cancelled in (False, True):
                expected = not cancelled and any(value != "success" for value in values)
                if evaluate(tree, results, cancelled) != expected:
                    raise ValueError(f"전파 판정 불일치: results={results}, cancelled={cancelled}, 기대={expected}")
    except ValueError as error:
        bad("CRG-003", str(error))
    if prop.get("continue-on-error", False) is not False:
        bad("CRG-003", "실패 전파 스텝은 continue-on-error 를 허용하지 않는다")
    if "exit 1" not in prop.get("run", ""):
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

    original = step(workflow["jobs"]["gate"], "Propagate upstream failure")["if"]
    comparisons = [f"needs.{job}.result != 'success'" for job in REQUIRED]
    positives = {
        "wrapper 없는 조건": original[3:-2].strip(),
        "순서와 공백 변경": "${{ ! cancelled ( ) && ( " + " ||\n".join(reversed(comparisons)) + " ) }}",
        "드모르간 동치": "${{ !cancelled() && !(" + " && ".join(
            f"needs.{job}.result == 'success'" for job in REQUIRED) + ") }}",
        "대문자 문자열 비교": original.replace("'success'", "'SUCCESS'"),
    }
    for label, condition in positives.items():
        changed = copy.deepcopy(workflow)
        change_step(changed, "gate", "Propagate upstream failure", "if", condition)
        errors = validate(changed)
        if errors:
            sys.exit(f"자체 정상 검사 실패: {label}: {errors}")
    changed = copy.deepcopy(workflow)
    change_step(changed, "gate", "Propagate upstream failure", "continue-on-error", False)
    if validate(changed):
        sys.exit("자체 정상 검사 실패: 명시적 step continue-on-error false")

    # 파서와 계약 oracle가 함께 틀리는 것을 막는 직접 판정 사례.
    parser_cases = (
        ("cancelled() || true && false", False, False),
        ("cancelled() || true && false", True, True),
        ("(cancelled() || true) && false", True, False),
        ("!cancelled() && !(false || false)", False, True),
        ("!cancelled() && needs.lint.result == 'FAILURE'", False, True),
        ("!cancelled() && needs.lint.result != 'failure'", False, False),
    )
    for condition, cancelled, expected in parser_cases:
        actual = evaluate(propagation_condition(condition), {"lint": "failure"}, cancelled)
        if actual is not expected:
            sys.exit(f"자체 파서 검사 실패: {condition}: 기대={expected}, 실제={actual}")

    mutations = {}
    for job in REQUIRED:
        mutations[f"gate.needs 에서 {job} 삭제"] = (
            lambda d, job=job: d["jobs"]["gate"].update(needs=[x for x in REQUIRED if x != job]), "CRG-002")
        mutations[f"{job} 실패 전파 삭제"] = (
            lambda d, job=job: change_step(
                d, "gate", "Propagate upstream failure", "if",
                step(d["jobs"]["gate"], "Propagate upstream failure")["if"].replace(
                    f"needs.{job}.result != 'success'", "false")), "CRG-003")
        for result in RESULTS[1:]:
            mutations[f"{job} {result} 전파 누락"] = (
                lambda d, job=job, result=result: change_step(
                    d, "gate", "Propagate upstream failure", "if", original.replace(
                        f"needs.{job}.result != 'success'",
                        f"(needs.{job}.result != 'success' && needs.{job}.result != '{result}')")), "CRG-003")
    for label, condition in {
        "OR 전체를 AND로 변경": original.replace("||", "&&"),
        "OR 하나를 AND로 변경": original.replace("||", "&&", 1),
        "취소 가드 괄호 삭제": original.replace("&& (", "&& ").replace(") }}", " }}"),
        "취소 가드 반전": original.replace("!cancelled()", "cancelled()"),
        "항상 전파": "${{ !cancelled() && true }}",
        "전파 비활성": "${{ !cancelled() && false }}",
        "상태 함수 없는 식": " || ".join(comparisons),
        "알 수 없는 함수": original.replace("!cancelled()", "always()"),
        "알 수 없는 job": original.replace("needs.lint.result", "needs.other.result"),
        "알 수 없는 연산자": original.replace("||", "|", 1),
        "불균형 괄호": original.replace("&& (", "&& (("),
        "잉여 토큰": original.replace(" }}", " true }}"),
        "단항 비교 모호성": original.replace("needs.lint.result != 'success'", "!needs.lint.result == 'success'"),
    }.items():
        mutations[label] = (lambda d, condition=condition: change_step(
            d, "gate", "Propagate upstream failure", "if", condition), "CRG-003")
    for value in (True, "${{ true }}", "${{ false }}"):
        mutations[f"전파 step continue-on-error {value}"] = (
            lambda d, value=value: change_step(
                d, "gate", "Propagate upstream failure", "continue-on-error", value), "CRG-003")
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
    print(f"ci-release-gate-lint self-test OK ({len(mutations)}/{len(mutations)} mutations, "
          f"{len(positives) + 2} valid workflows, {len(parser_cases)} parser cases, "
          f"{len(RESULTS) ** len(REQUIRED) * 2} result/cancellation combinations per valid condition)")
else:
    print("ci-release-gate-lint OK (최종 gate · 이미지 승격)")
PY
