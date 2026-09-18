# ---------- Codex 응답 헬퍼 ----------

# ---------- Codex 호출 게이트 ----------

# hpx_codex_off_value <value>
# off 로 해석되는 값이면 0, 아니면 1.
hpx_codex_off_value() {
  case "$(printf '%s' "${1-}" | tr '[:upper:]' '[:lower:]')" in
    off|0|false|no) return 0 ;;
    *) return 1 ;;
  esac
}

# hpx_codex_on_value <value>
# 명시적 on 이면 0, 아니면 1.
hpx_codex_on_value() {
  case "$(printf '%s' "${1-}" | tr '[:upper:]' '[:lower:]')" in
    on|1|true|yes) return 0 ;;
    *) return 1 ;;
  esac
}

# hpx_codex_allowed [task_id]
# Codex 호출 허용 여부를 판정한다. /plan, /work 의 Codex 호출 단계 진입 전에
# 먼저 부르고, blocked 면 그 단계를 건너뛴다.
#
# 판정 순서 (먼저 걸리는 것이 이긴다):
#   1. HPX_CODEX 환경변수  — on/1/true/yes 면 즉시 허용(override), off/0/false/no 면 차단
#   2. .cache/codex-off 파일 존재 — 차단. 파일 첫 줄을 사유로 쓴다
#   3. docs/plans/<task_id>.md frontmatter 의 codex: off — 차단
#
# 출력:
#   1행 allowed | blocked
#   2행 source  (env | file | plan)   blocked 일 때만
#   3행 reason                        blocked 일 때만. 없으면 "(사유 미기재)"
#   4행 origin  (신호를 읽은 위치)     blocked 일 때만
#
# 범위: .cache/ 와 docs/plans/ 는 현재 **워크트리** 기준으로 해석된다. git worktree 를
# 여러 개 쓰면 스위치도 워크트리마다 따로다. 의도된 동작이다 — 한 워크트리에서 리뷰를
# 끈 것이 다른 워크트리의 제품 작업 리뷰까지 끄면 안 된다. 4행 origin 이 어느 파일을
# 실제로 읽었는지 알려주므로, 스위치가 안 먹는 것처럼 보일 때 그것부터 확인한다.
# exit: 허용 0 / 차단 1
hpx_codex_allowed() {
  local task_id="${1-}"

  if [ -n "${HPX_CODEX-}" ]; then
    if hpx_codex_on_value "$HPX_CODEX"; then
      printf 'allowed\n'
      return 0
    fi
    if hpx_codex_off_value "$HPX_CODEX"; then
      printf 'blocked\nenv\nHPX_CODEX=%s\n환경변수 (셸 세션)\n' "$HPX_CODEX"
      return 1
    fi
  fi

  if [ -f .cache/codex-off ]; then
    local reason
    reason="$(sed -n '1p' .cache/codex-off 2>/dev/null | tr -d '\r')"
    [ -n "$reason" ] || reason='(사유 미기재)'
    printf 'blocked\nfile\n%s\n%s/.cache/codex-off\n' "$reason" "$(pwd)"
    return 1
  fi

  if [ -n "$task_id" ]; then
    local fm_value
    fm_value="$(hpx_plan_frontmatter "$task_id" codex 2>/dev/null)"
    if [ -n "$fm_value" ] && hpx_codex_off_value "$fm_value"; then
      printf 'blocked\nplan\nfrontmatter codex: %s\n%s/docs/plans/%s.md\n' \
        "$fm_value" "$(pwd)" "$task_id"
      return 1
    fi
  fi

  printf 'allowed\n'
  return 0
}
