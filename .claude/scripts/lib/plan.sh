# ---------- 계획서 메타데이터 ----------

# hpx_plan_frontmatter <task_id> <key>
# 계획서 `docs/plans/<task_id>.md` 선두 `---` 블록에서 key 의 값을 읽어 출력한다.
# 본문은 보지 않는다 — frontmatter 가 닫힌 뒤의 같은 키는 무시한다.
# 없으면 아무것도 출력하지 않고 1 을 반환한다.
hpx_plan_frontmatter() {
  local task_id="${1-}" key="${2-}"
  [ -n "$task_id" ] && [ -n "$key" ] || return 1
  hpx_task_id_validate "$task_id" 2>/dev/null || return 1

  local plan_file="docs/plans/${task_id}.md"
  [ -f "$plan_file" ] || return 1

  local value
  value="$(awk -v key="$key" '
    NR==1 && $0!="---" { exit }
    NR==1 { infm=1; next }
    infm && $0=="---" { exit }
    infm {
      pattern = "^[[:space:]]*" key "[[:space:]]*:"
      if ($0 ~ pattern) {
        sub(pattern "[[:space:]]*", "")
        gsub(/[[:space:]]*$/, "")
        print
        exit
      }
    }
  ' "$plan_file" 2>/dev/null)"

  [ -n "$value" ] || return 1
  printf '%s\n' "$value"
}

# hpx_plan_grade <task_id>
# 작업 등급(S | M | L)을 출력한다.
#
# 정본은 계획서 frontmatter 의 `grade:` 키다. 대화로 받은 등급은 세션이 끝나면 사라지므로
# 파일에 적는다 (Codex 게이트가 `.cache/codex-off` 를 쓰는 것과 같은 이유).
#
# frontmatter 가 없거나 값이 부적격이면 **L 로 떨어진다.** 기존 계획서 수십 개에
# frontmatter 가 없고, 모르는 작업을 가볍게 처리하는 쪽보다 무겁게 처리하는 쪽이 안전하다.
# 부적격 값은 stderr 로 알린다 — 오타가 조용히 L 로 흡수되면 등급제가 무력해진다.
hpx_plan_grade() {
  local task_id="${1-}" raw
  raw="$(hpx_plan_frontmatter "$task_id" grade 2>/dev/null)"

  case "$(printf '%s' "$raw" | tr '[:lower:]' '[:upper:]')" in
    S) printf 'S\n' ;;
    M) printf 'M\n' ;;
    L) printf 'L\n' ;;
    '') printf 'L\n' ;;
    *)
      printf 'plan_grade: 부적격 grade 값 %s — L 로 처리한다\n' "$raw" >&2
      printf 'L\n'
      ;;
  esac
}
