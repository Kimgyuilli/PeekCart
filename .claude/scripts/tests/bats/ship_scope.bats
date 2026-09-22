#!/usr/bin/env bats
# Tests for hpx_plan_todo_in_scope / hpx_ship_preflight 의 ship_scope 처리.
#
# 배경: preflight 는 미완 체크박스가 하나라도 있으면 막았다. 그런데 이 레포의 확립된
# 패턴은 "계획서 1개 → PR 여러 개"(구현 ①~⑥ 전부)라 그 게이트가 정상 흐름을 막는다.
# ship_scope 는 "이번 PR 이 책임지는 범위"를 선언해 그 범위 안의 미완만 막게 한다.
#
# 이 테스트가 지켜야 할 두 가지는 대칭이다:
#   (1) 범위 밖 미완은 막지 않는다      — 안 그러면 다중 PR 이 불가능하다
#   (2) 범위 안 미완은 반드시 막는다    — 안 그러면 ship_scope 가 검사 무력화 도구가 된다

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.claude/scripts/shared-logic.sh"

  SANDBOX="$(mktemp -d)"
  mkdir -p "$SANDBOX/docs/plans"
  cd "$SANDBOX"
  git init -q . && git commit -q --allow-empty -m init
}

teardown() {
  cd /
  [ -n "${SANDBOX:-}" ] && rm -rf "$SANDBOX"
}

write_plan() { # <task_id> <frontmatter_lines> ; 본문은 P1..P4, P1/P2 완료
  printf -- '---\n%s\n---\n\n# 계획\n\n- [x] **P1.** 완료\n- [x] **P2.** 완료\n- [ ] **P3.** 미완\n- [ ] **P4.** 미완\n' \
    "$2" > "docs/plans/$1.md"
}

# ---- hpx_plan_todo_in_scope ---------------------------------------------------

@test "todo_in_scope: 범위 밖 미완은 나오지 않는다" {
  write_plan task-a "grade: L"
  run hpx_plan_todo_in_scope docs/plans/task-a.md "P1-P2"
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}

@test "todo_in_scope: 범위 안 미완은 나온다" {
  write_plan task-a "grade: L"
  run hpx_plan_todo_in_scope docs/plans/task-a.md "P1-P3"
  [[ "$output" == *"P3."* ]]
  [[ "$output" != *"P4."* ]]
}

@test "todo_in_scope: 쉼표로 여러 구간" {
  write_plan task-a "grade: L"
  run hpx_plan_todo_in_scope docs/plans/task-a.md "P1-P2,P4"
  [[ "$output" == *"P4."* ]]
  [[ "$output" != *"P3."* ]]
}

@test "todo_in_scope: id 를 못 읽는 줄은 범위 안으로 친다" {
  printf -- '---\ngrade: L\n---\n\n- [ ] 자유서술 항목\n' > docs/plans/task-b.md
  run hpx_plan_todo_in_scope docs/plans/task-b.md "P1-P2"
  [[ "$output" == *"자유서술"* ]]
}

# ---- hpx_ship_preflight -------------------------------------------------------

@test "preflight: ship_scope 가 범위를 덮으면 통과한다" {
  git checkout -q -b feat/x
  write_plan task-a "grade: L
ship_scope: P1-P2"
  run hpx_ship_preflight task-a
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "ok" ]
}

@test "preflight: 범위 안에 미완이 있으면 막고 ship_scope 를 사유에 적는다" {
  git checkout -q -b feat/x
  write_plan task-a "grade: L
ship_scope: P1-P3"
  run hpx_ship_preflight task-a
  [ "$status" -eq 1 ]
  [ "${lines[0]}" = "blocked" ]
  [[ "$output" == *"ship_scope: P1-P3"* ]]
  [[ "$output" == *"1개"* ]]
}

@test "preflight: ship_scope 가 없으면 종전대로 전 항목을 본다" {
  git checkout -q -b feat/x
  write_plan task-a "grade: L"
  run hpx_ship_preflight task-a
  [ "$status" -eq 1 ]
  [[ "$output" == *"2개"* ]]
}

@test "preflight: 전 항목 완료면 ship_scope 없이도 통과" {
  git checkout -q -b feat/x
  printf -- '---\ngrade: L\n---\n\n- [x] **P1.** 완료\n' > docs/plans/task-c.md
  run hpx_ship_preflight task-c
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "ok" ]
}
