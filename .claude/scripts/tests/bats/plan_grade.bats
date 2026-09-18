#!/usr/bin/env bats
# Tests for hpx_plan_frontmatter / hpx_plan_grade (H4 — 작업 등급 S/M/L).
#
# 등급 정본은 계획서 frontmatter 의 grade 키다. 기본값은 L 이어야 한다 — 기존 계획서
# 수십 개에 frontmatter 가 없으므로, 기본이 S 나 M 이면 그것들의 절차가 조용히 얕아진다.

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.claude/scripts/shared-logic.sh"

  SANDBOX="$(mktemp -d)"
  mkdir -p "$SANDBOX/docs/plans"
  cd "$SANDBOX"
}

teardown() {
  cd /
  [ -n "${SANDBOX:-}" ] && rm -rf "$SANDBOX"
}

# ---- 기본값 -------------------------------------------------------------------

@test "plan_grade: 계획서가 없으면 L" {
  run hpx_plan_grade "task-missing"
  [ "$status" -eq 0 ]
  [ "$output" = "L" ]
}

@test "plan_grade: frontmatter 없는 기존 계획서는 L" {
  printf '# 계획서\n\n## 1. 명제\n' > docs/plans/task-legacy.md
  run hpx_plan_grade "task-legacy"
  [ "$output" = "L" ]
}

@test "plan_grade: frontmatter 는 있으나 grade 키가 없으면 L" {
  printf -- '---\ncodex: off\n---\n\n# 계획서\n' > docs/plans/task-nograde.md
  run hpx_plan_grade "task-nograde"
  [ "$output" = "L" ]
}

# ---- 정상 등급 ----------------------------------------------------------------

@test "plan_grade: S / M / L 을 그대로 읽는다" {
  for g in S M L; do
    printf -- '---\ngrade: %s\n---\n' "$g" > docs/plans/task-g.md
    run hpx_plan_grade "task-g"
    [ "$output" = "$g" ] || {
      echo "grade: $g 인데 출력은 $output"
      return 1
    }
  done
}

@test "plan_grade: 소문자도 받는다" {
  printf -- '---\ngrade: s\n---\n' > docs/plans/task-lower.md
  run hpx_plan_grade "task-lower"
  [ "$output" = "S" ]
}

@test "plan_grade: 값 뒤 공백을 버린다" {
  printf -- '---\ngrade:   M   \n---\n' > docs/plans/task-space.md
  run hpx_plan_grade "task-space"
  [ "$output" = "M" ]
}

# ---- 부적격 값 ----------------------------------------------------------------

@test "plan_grade: 부적격 값은 L 로 떨어지고 stderr 로 알린다" {
  printf -- '---\ngrade: XL\n---\n' > docs/plans/task-bad.md
  run hpx_plan_grade "task-bad"
  [ "${lines[-1]}" = "L" ]
  [[ "$output" == *"부적격"* ]]
}

@test "plan_grade: 오타가 조용히 흡수되지 않는다 (경고 없으면 실패)" {
  printf -- '---\ngrade: smal\n---\n' > docs/plans/task-typo.md
  run hpx_plan_grade "task-typo"
  [[ "$output" == *"부적격"* ]]
}

# ---- frontmatter 범위 ----------------------------------------------------------

@test "plan_frontmatter: 본문의 grade 는 읽지 않는다" {
  printf '# 계획서\n\ngrade: S 라고 본문에 적었을 뿐이다.\n' > docs/plans/task-body.md
  run hpx_plan_grade "task-body"
  [ "$output" = "L" ]
}

@test "plan_frontmatter: frontmatter 가 닫힌 뒤의 grade 는 읽지 않는다" {
  printf -- '---\ntitle: foo\n---\n\ngrade: S\n' > docs/plans/task-after.md
  run hpx_plan_grade "task-after"
  [ "$output" = "L" ]
}

@test "plan_frontmatter: 임의 키를 읽을 수 있다" {
  printf -- '---\ngrade: M\ncodex: off\n---\n' > docs/plans/task-multi.md
  run hpx_plan_frontmatter "task-multi" codex
  [ "$status" -eq 0 ]
  [ "$output" = "off" ]
}

@test "plan_frontmatter: 없는 키는 1 을 반환한다" {
  printf -- '---\ngrade: M\n---\n' > docs/plans/task-multi.md
  run hpx_plan_frontmatter "task-multi" nosuch
  [ "$status" -eq 1 ]
  [ -z "$output" ]
}

# ---- 경로 안전성 --------------------------------------------------------------

@test "plan_frontmatter: 부적격 task_id 는 파일을 읽지 않는다" {
  run hpx_plan_frontmatter "../../etc/passwd" grade
  [ "$status" -eq 1 ]
}

@test "plan_grade: 부적격 task_id 는 L" {
  run hpx_plan_grade "../../etc/passwd"
  [ "$output" = "L" ]
}
