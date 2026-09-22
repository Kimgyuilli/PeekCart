#!/usr/bin/env bash
# ci-release-gate-lint.sh — D-031 릴리스 게이트 도달성 검사
#
# 목적:
#   GHCR 로 이미지를 올리는 `publish` job 이 테스트 판정자인 `gate` 를 반드시 거치는지,
#   그리고 그 `gate` 가 실제로 실패를 전파하는지 검사한다.
#
# 왜:
#   D-031 이 `images` 의 needs 에서 gate 를 뺐다. 그 전까지 publish 는
#   publish -> images -> gate 경로로 **간접 게이트**되고 있었다. 그 경로가 끊긴 지금
#   publish 는 gate 를 직접 물어야 하는데, 이 성질은 YAML 을 읽는 사람만 안다.
#   누가 needs 를 다시 고쳐 끊어도 CI 는 전부 초록으로 통과하고, 테스트가 빨간 push 에서
#   GHCR 에 :latest 가 올라간다 (D-016/L-016a image promotion 계약 위반).
#
#   도달성만 보지 않고 전파 스텝까지 보는 이유: gate 는 `if: !cancelled()` 라 선행이
#   실패해도 **성공으로 끝날 수 있다**. 전파 스텝이 없으면 needs 로 연결돼 있어도
#   게이트가 아니다.
#
# 사용: bash scripts/ci-release-gate-lint.sh [--self-test]
#
# 종료:
#   0 - 릴리스 게이트 온전
#   1 - 위반
#   2 - 필요한 도구 부재
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! command -v python3 >/dev/null 2>&1; then
    echo "[CRG-000] python3 부재 — YAML 파싱에 필요하다" >&2
    exit 2
fi

LINT_PY="$(mktemp -t ci-release-gate-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import sys

try:
    import yaml
except ImportError:
    print("[CRG-000] pyyaml 부재 — CI 는 lint 잡에서 미리 설치한다", file=sys.stderr)
    sys.exit(2)

WORKFLOW = sys.argv[1]

# 이 셋은 계약의 고정 이름이다. job 을 rename 하면 검사 대상이 사라지는데, 그것을
# "위반 없음" 으로 읽으면 lint 자체가 vacuous-green 이 된다. 부재를 위반으로 센다.
PUBLISH = "publish"
GATE = "gate"
TEST = "test"

violations = []


def bad(code, message):
    violations.append("[%s] %s" % (code, message))


with open(WORKFLOW, encoding="utf-8") as fh:
    wf = yaml.safe_load(fh)

jobs = (wf or {}).get("jobs") or {}
if not jobs:
    bad("CRG-001", "%s 에서 jobs 를 찾지 못했다 — 워크플로 구조가 바뀌었다" % WORKFLOW)
    print("\n".join(violations))
    sys.exit(1)


def needs_of(job_name):
    """needs 는 문자열 하나일 수도 리스트일 수도 있다."""
    spec = jobs.get(job_name) or {}
    raw = spec.get("needs") or []
    if isinstance(raw, str):
        return [raw]
    return list(raw)


def closure(start):
    """start 의 전이 의존 폐포. start 자신은 포함하지 않는다."""
    seen = set()
    stack = list(needs_of(start))
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(needs_of(cur))
    return seen


for name in (PUBLISH, GATE, TEST):
    if name not in jobs:
        bad("CRG-001", "'%s' job 이 없다 — 검사 대상 부재를 통과로 읽지 않는다" % name)

if not violations:
    pub_closure = closure(PUBLISH)

    if GATE not in pub_closure:
        bad(
            "CRG-002",
            "'%s' 가 '%s' 에 도달하지 못한다 (전이 needs: %s). "
            "테스트가 실패한 push 에서도 GHCR 푸시가 나간다 — D-016/L-016a 계약 위반"
            % (PUBLISH, GATE, sorted(pub_closure) or "없음"),
        )

    gate_closure = closure(GATE)
    if TEST not in gate_closure:
        bad(
            "CRG-003",
            "'%s' 가 '%s' 를 needs 로 물지 않는다 (전이 needs: %s). "
            "게이트가 테스트 결과를 볼 수 없다" % (GATE, TEST, sorted(gate_closure) or "없음"),
        )

    # gate 는 if: !cancelled() 로 돌기 때문에 선행이 실패해도 성공으로 끝날 수 있다.
    # 그 상태를 결과에 반영하는 스텝이 없으면 needs 연결은 장식이다.
    steps = (jobs.get(GATE) or {}).get("steps") or []
    propagates = any(
        "needs.%s.result" % TEST in (st.get("run") or "")
        and "exit 1" in (st.get("run") or "")
        for st in steps
        if isinstance(st, dict)
    )
    if not propagates:
        bad(
            "CRG-004",
            "'%s' 에 선행 실패 전파 스텝이 없다 (needs.%s.result 를 읽고 exit 1 하는 run). "
            "gate 는 if: !cancelled() 라 선행이 빨개도 초록으로 끝난다" % (GATE, TEST),
        )

if violations:
    print("\n".join(violations))
    sys.exit(1)

print("ci-release-gate-lint: publish -> gate -> test 도달성과 실패 전파 OK")
PYEOF

run_lint() {
    python3 "$LINT_PY" "$1"
}

self_test() {
    local tmp failures=0
    tmp="$(mktemp -d)"

    _case() {
        local name="$1" expect_code="$2" fixture="$3"
        local out rc=0
        out="$(run_lint "$fixture" 2>&1)" || rc=$?
        if [[ "$expect_code" == "NONE" ]]; then
            if [[ $rc -ne 0 ]]; then
                echo "  ✗ $name: 통과해야 하는데 실패했다"
                printf '%s\n' "$out" | sed 's/^/      /'
                failures=$((failures + 1))
            else
                echo "  ✓ $name"
            fi
            return
        fi
        if [[ $rc -eq 0 ]]; then
            echo "  ✗ $name: 위반인데 통과했다 (false-green)"
            failures=$((failures + 1))
            return
        fi
        if ! printf '%s\n' "$out" | grep -q "\[${expect_code}\]"; then
            echo "  ✗ $name: ${expect_code} 를 기대했으나 다른 위반이 나왔다"
            printf '%s\n' "$out" | sed 's/^/      /'
            failures=$((failures + 1))
            return
        fi
        echo "  ✓ $name (${expect_code})"
    }

    # mode: full | no-gate-in-publish | no-test-in-gate | no-propagate | rename-publish
    _fixture() {
        local path="$1" mode="$2"
        local publish_needs="[images, gate]"
        local gate_needs="[test, guards]"
        local publish_name="publish"
        [[ "$mode" == "no-gate-in-publish" ]] && publish_needs="[images]"
        [[ "$mode" == "no-test-in-gate" ]] && gate_needs="[guards]"
        [[ "$mode" == "rename-publish" ]] && publish_name="release"
        {
            echo "name: fixture"
            echo "on: [push]"
            echo "jobs:"
            echo "  lint:"
            echo "    runs-on: ubuntu-latest"
            echo "    steps: [{run: 'true'}]"
            echo "  test:"
            echo "    runs-on: ubuntu-latest"
            echo "    steps: [{run: 'true'}]"
            echo "  guards:"
            echo "    runs-on: ubuntu-latest"
            echo "    steps: [{run: 'true'}]"
            echo "  gate:"
            echo "    needs: ${gate_needs}"
            echo "    runs-on: ubuntu-latest"
            echo "    steps:"
            if [[ "$mode" != "no-propagate" ]]; then
                echo "      - name: Propagate upstream failure"
                echo "        run: |"
                echo "          echo \"test=\${{ needs.test.result }}\""
                echo "          exit 1"
            else
                echo "      - run: 'true'"
            fi
            echo "  images:"
            echo "    needs: [lint]"
            echo "    runs-on: ubuntu-latest"
            echo "    steps: [{run: 'true'}]"
            echo "  ${publish_name}:"
            echo "    needs: ${publish_needs}"
            echo "    runs-on: ubuntu-latest"
            echo "    steps: [{run: 'true'}]"
        } > "$path"
    }

    echo "ci-release-gate-lint --self-test"
    _case "정상 배선(현 저장소)" NONE ".github/workflows/ci.yml"
    _fixture "$tmp/c0.yml" full
    _case "정상 배선(픽스처)" NONE "$tmp/c0.yml"
    _fixture "$tmp/c1.yml" no-gate-in-publish
    _case "publish.needs 에서 gate 제거" CRG-002 "$tmp/c1.yml"
    _fixture "$tmp/c2.yml" no-test-in-gate
    _case "gate.needs 에서 test 제거" CRG-003 "$tmp/c2.yml"
    _fixture "$tmp/c3.yml" no-propagate
    _case "실패 전파 스텝 삭제" CRG-004 "$tmp/c3.yml"
    _fixture "$tmp/c4.yml" rename-publish
    _case "publish job rename (대상 소멸)" CRG-001 "$tmp/c4.yml"

    rm -rf "$tmp"
    if [[ $failures -gt 0 ]]; then
        echo "self-test 실패 ${failures}건"
        return 1
    fi
    echo "self-test OK (6/6)"
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
else
    run_lint ".github/workflows/ci.yml"
fi
