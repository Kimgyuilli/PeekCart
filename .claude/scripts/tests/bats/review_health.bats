#!/usr/bin/env bats
# Tests for hpx_review_health (리뷰 처분 규율 점검).
#
# 실측 배경: 18개 계획서 56라운드 442건에서 P0 는 2.0%, 기각률은 0.3% 였다. 머지를 막지 않는
# 지적을 거의 전부 그 자리에서 구현했고 그것이 라운드마다 범위가 커진 직접 원인이었다.
# 이 함수는 그 상태로 되돌아가는 것을 눈에 보이게 한다. 권고이므로 항상 exit 0 이다.

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

# ---- 정상 ---------------------------------------------------------------------

@test "review_health: audit 파일이 없으면 ok" {
  run hpx_review_health "task-none"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "ok" ]
}

@test "review_health: 1라운드에 처분이 갈렸으면 ok" {
  cat > docs/plans/task-good.md.tmp <<'EOF'
## 2026-09-18 — diff 리뷰 라운드 1
- 처리: 반영 3건 / 이월 3건 / 기각 1건
EOF
  mv docs/plans/task-good.md.tmp docs/plans/task-good.audit.md
  run hpx_review_health "task-good"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "ok" ]
}

@test "review_health: 2라운드라도 이월이 있으면 ok" {
  cat > docs/plans/task-ok2.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 4건 / 이월 2건 / 기각 0건
## 라운드 2
- 처리: 반영 1건 / 이월 3건 / 기각 0건
EOF
  run hpx_review_health "task-ok2"
  [ "${lines[0]}" = "ok" ]
}

# ---- 규율 붕괴 신호 ------------------------------------------------------------

@test "review_health: 상한 초과 라운드를 잡는다" {
  cat > docs/plans/task-over.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 2건 / 이월 2건 / 기각 0건
## 라운드 2
- 처리: 반영 1건 / 이월 1건 / 기각 0건
## 라운드 3
- 처리: 반영 1건 / 이월 1건 / 기각 0건
EOF
  run hpx_review_health "task-over"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "warnings" ]
  [[ "$output" == *"라운드 3 도달"* ]]
}

@test "review_health: 2라운드 이상인데 이월과 기각이 0이면 잡는다" {
  cat > docs/plans/task-allin.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 5건 / 기각 0건
## 라운드 2
- 처리: 반영 4건 / 기각 0건
EOF
  run hpx_review_health "task-allin"
  [ "${lines[0]}" = "warnings" ]
  [[ "$output" == *"이월과 기각이 0건"* ]]
}

@test "review_health: 처분 10건 이상이 전부 반영이면 잡는다" {
  cat > docs/plans/task-bulk.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 12건 / 기각 0건
EOF
  run hpx_review_health "task-bulk"
  [ "${lines[0]}" = "warnings" ]
  [[ "$output" == *"전부 반영"* ]]
}

@test "review_health: 1라운드 소량 전부 반영은 잡지 않는다" {
  cat > docs/plans/task-small.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 3건 / 기각 0건
EOF
  run hpx_review_health "task-small"
  [ "${lines[0]}" = "ok" ]
}

# ---- 집계 --------------------------------------------------------------------

@test "review_health: 블록 수와 최대 라운드를 구분해 집계한다" {
  cat > docs/plans/task-two.audit.md <<'EOF'
## 계획 리뷰 라운드 1
- 처리: 반영 2건 / 이월 0건 / 기각 0건
## 계획 리뷰 라운드 2
- 처리: 반영 2건 / 이월 0건 / 기각 0건
## diff 리뷰 라운드 1
- 처리: 반영 2건 / 이월 0건 / 기각 0건
EOF
  run hpx_review_health "task-two"
  [[ "$output" == *"리뷰 블록 3개"* ]]
  [[ "$output" == *"최대 라운드 2"* ]]
}

@test "review_health: 볼드 표기된 건수도 센다" {
  cat > docs/plans/task-bold.audit.md <<'EOF'
## 라운드 1
- 처리: 반영 3건 / **이월 4건** / 기각 0건
EOF
  run hpx_review_health "task-bold"
  [ "${lines[0]}" = "ok" ]
}

# ---- 안전성 ------------------------------------------------------------------

@test "review_health: 부적격 task_id 는 ok 로 통과하고 파일을 읽지 않는다" {
  run hpx_review_health "../../etc/passwd"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "ok" ]
}

@test "review_health: 경고가 있어도 exit 0 이다 (권고)" {
  cat > docs/plans/task-warn.audit.md <<'EOF'
## 라운드 3
- 처리: 반영 20건 / 기각 0건
EOF
  run hpx_review_health "task-warn"
  [ "$status" -eq 0 ]
  [ "${lines[0]}" = "warnings" ]
}
