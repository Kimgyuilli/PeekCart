#!/usr/bin/env bats
# Tests for hpx_codex_allowed (H2 — Codex 호출 금지를 파일로 판정).
#
# 판정 순서: HPX_CODEX env > .cache/codex-off 파일 > 계획서 frontmatter.
# 기본값은 허용이다. 차단 신호가 하나도 없으면 종전 동작(호출)을 유지해야 한다.
#
# 격리: 실제 레포의 .cache/codex-off 를 건드리면 안 되므로 임시 디렉토리에
# 최소 트리(.cache, docs/plans)를 만들고 그 안에서 실행한다.

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.claude/scripts/shared-logic.sh"

  SANDBOX="$(mktemp -d)"
  mkdir -p "$SANDBOX/.cache" "$SANDBOX/docs/plans"
  cd "$SANDBOX"

  # 상속된 env 가 판정을 오염시키지 않도록 명시적으로 해제한다.
  unset HPX_CODEX
}

teardown() {
  cd /
  [ -n "${SANDBOX:-}" ] && rm -rf "$SANDBOX"
}

# ---- 기본값: 차단 신호 없음 -> 허용 ------------------------------------------

@test "codex_allowed: 신호가 없으면 허용 (기본값)" {
  run hpx_codex_allowed
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

@test "codex_allowed: task_id 를 줘도 계획서가 없으면 허용" {
  run hpx_codex_allowed "task-nonexistent"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

@test "codex_allowed: frontmatter 없는 계획서는 허용" {
  printf '# 계획서\n\n## 1. 명제\n' > docs/plans/task-foo.md
  run hpx_codex_allowed "task-foo"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

# ---- 신호 1: 환경변수 --------------------------------------------------------

@test "codex_allowed: HPX_CODEX=off 차단" {
  export HPX_CODEX=off
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ "${lines[0]}" = "blocked" ]
  [ "${lines[1]}" = "env" ]
  [[ "${lines[2]}" == *"HPX_CODEX=off"* ]]
  [[ "${lines[3]}" == *"환경변수"* ]]
}

@test "codex_allowed: off 동의어(0/false/no/OFF) 전부 차단" {
  for v in 0 false no OFF False; do
    export HPX_CODEX="$v"
    run hpx_codex_allowed
    [ "$status" -eq 1 ] || {
      echo "허용됨: HPX_CODEX=$v"
      return 1
    }
  done
}

@test "codex_allowed: HPX_CODEX=on 은 파일 신호를 덮어쓴다 (override)" {
  printf '측정 세션\n' > .cache/codex-off
  export HPX_CODEX=on
  run hpx_codex_allowed
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

@test "codex_allowed: 알 수 없는 HPX_CODEX 값은 판정에 영향 없음" {
  export HPX_CODEX=maybe
  run hpx_codex_allowed
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

# ---- 신호 2: .cache/codex-off 파일 -------------------------------------------

@test "codex_allowed: .cache/codex-off 존재 시 차단" {
  printf 'GKE 측정 세션이라 리뷰 불필요\n' > .cache/codex-off
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ "${lines[0]}" = "blocked" ]
  [ "${lines[1]}" = "file" ]
  [ "${lines[2]}" = "GKE 측정 세션이라 리뷰 불필요" ]
  [ "${lines[3]}" = "$SANDBOX/.cache/codex-off" ]
}

@test "codex_allowed: 빈 codex-off 파일도 차단하되 사유는 대체 문구" {
  : > .cache/codex-off
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ "${lines[1]}" = "file" ]
  [[ "${lines[2]}" == *"사유 미기재"* ]]
}

@test "codex_allowed: codex-off 사유는 첫 줄만 쓴다" {
  printf '첫째 줄\n둘째 줄\n' > .cache/codex-off
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ "${lines[2]}" = "첫째 줄" ]
}

# ---- 신호 3: 계획서 frontmatter ----------------------------------------------

@test "codex_allowed: frontmatter codex: off 차단" {
  printf -- '---\ncodex: off\n---\n\n# 계획서\n' > docs/plans/task-bar.md
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 1 ]
  [ "${lines[0]}" = "blocked" ]
  [ "${lines[1]}" = "plan" ]
  [[ "${lines[2]}" == *"codex: off"* ]]
  [ "${lines[3]}" = "$SANDBOX/docs/plans/task-bar.md" ]
}

@test "codex_allowed: frontmatter codex: on 은 허용" {
  printf -- '---\ncodex: on\n---\n\n# 계획서\n' > docs/plans/task-bar.md
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

@test "codex_allowed: 본문의 codex: off 는 읽지 않는다 (frontmatter 한정)" {
  printf '# 계획서\n\n어떤 문단에서 codex: off 라고 적었을 뿐이다.\n' > docs/plans/task-bar.md
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

@test "codex_allowed: frontmatter 가 닫힌 뒤의 codex: off 는 읽지 않는다" {
  printf -- '---\ntitle: foo\n---\n\ncodex: off\n' > docs/plans/task-bar.md
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

# ---- 우선순위 ----------------------------------------------------------------

@test "codex_allowed: env off 가 frontmatter on 을 이긴다" {
  printf -- '---\ncodex: on\n---\n' > docs/plans/task-bar.md
  export HPX_CODEX=off
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 1 ]
  [ "${lines[1]}" = "env" ]
}

@test "codex_allowed: 파일이 frontmatter 보다 먼저 판정된다" {
  printf '파일 사유\n' > .cache/codex-off
  printf -- '---\ncodex: off\n---\n' > docs/plans/task-bar.md
  run hpx_codex_allowed "task-bar"
  [ "$status" -eq 1 ]
  [ "${lines[1]}" = "file" ]
}

# ---- 경로 안전성 --------------------------------------------------------------

@test "codex_allowed: 부적격 task_id 는 계획서를 읽지 않고 허용으로 통과" {
  run hpx_codex_allowed "../../etc/passwd"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]
}

# ---- 워크트리 범위 ------------------------------------------------------------
# .cache/ 는 워크트리마다 별개다. 한 워크트리에서 리뷰를 끈 것이 다른 워크트리의
# 작업까지 끄면 안 된다. 실제로 이 레포는 본 체크아웃과 orca 워크트리에서 서로
# 다른 task 를 동시에 진행한다.

@test "codex_allowed: 다른 작업 트리의 codex-off 는 이 트리에 영향 없음" {
  local other
  other="$(mktemp -d)"
  mkdir -p "$other/.cache"
  printf '저쪽 트리에서 끈 스위치\n' > "$other/.cache/codex-off"

  # 이 트리(SANDBOX)에는 스위치가 없다.
  run hpx_codex_allowed
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "allowed" ]

  # 저쪽 트리에서는 차단된다.
  cd "$other"
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ "${lines[1]}" = "file" ]

  cd "$SANDBOX"
  rm -rf "$other"
}

@test "codex_allowed: origin 4행이 실제로 읽은 파일 경로를 가리킨다" {
  printf '사유\n' > .cache/codex-off
  run hpx_codex_allowed
  [ "$status" -eq 1 ]
  [ -f "${lines[3]}" ]
  [ "$(cat "${lines[3]}")" = "사유" ]
}
