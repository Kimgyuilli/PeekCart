# ---------- /ship: consistency precheck / commit plan / PR body ----------

# hpx_consistency_precheck <task_id>
# bash docs/consistency-hints.sh 실행. 결과 3줄 출력:
#   line1: status (ok|warnings|unavailable|script_error)
#   line2: log path (stdout+stderr 합쳐 기록)
#   line3: exit code
# §7-5-E 분기 근거 제공. script 부재는 unavailable (호출자가 skip), exec 실패는 script_error.
hpx_consistency_precheck() {
  local task_id="$1"
  hpx_task_id_validate "$task_id" || return 1
  local ts
  ts="$(hpx_epoch_ts)"
  local log_path=".cache/consistency-${task_id}-${ts}.log"
  mkdir -p .cache >/dev/null 2>&1 || true

  if [ ! -f docs/consistency-hints.sh ]; then
    printf 'unavailable\n%s\n0\n' "$log_path"
    return 0
  fi

  bash docs/consistency-hints.sh >"$log_path" 2>&1
  local ec=$?

  local status
  case "$ec" in
    0) status="ok" ;;
    1) status="warnings" ;;
    *) status="script_error" ;;
  esac
  printf '%s\n%s\n%s\n' "$status" "$log_path" "$ec"
}

# ---------- /ship 사실 판정 ----------
# 아래 셋은 전부 결정적이다. 종전에는 커맨드 문서가 LLM 에게 명령을 하나씩 돌리고
# 해석하라고 지시했는데, 그 과정에서 이번 세션에 스테이징 실수가 두 번 났다.
# 하나는 이미 삭제된 경로를 git add 에 넘겨 fatal 이 나면서 수정분이 통째로 빠진 채
# 커밋된 것이고, 다른 하나는 add 범위가 넓어 한 커밋에 두 분류가 섞인 것이다.

# hpx_plan_todo_in_scope <plan_file> <scope_spec>
# 계획서의 미완 체크박스 중 scope 안에 있는 것만 `행번호:내용` 으로 출력한다.
# scope_spec 은 "P1-P5" 또는 "P1-P5,P9" 형태다. 항목 id 는 `- [ ] **P<N>.**` 에서 읽는다.
#
# 범위 밖 항목은 **아직 안 한 일이지 막을 일이 아니다.** 그 구분이 이 함수의 전부다.
# id 를 못 읽는 줄(`- [ ] 자유서술`)은 안전한 쪽으로 **범위 안으로 친다** — 선언되지 않은
# 항목이 조용히 통과하면 ship_scope 가 검사를 무력화하는 도구가 된다.
hpx_plan_todo_in_scope() {
  local plan="${1-}" spec="${2-}"
  [ -f "$plan" ] || return 0

  grep -n '^[[:space:]]*- \[ \]' "$plan" 2>/dev/null | awk -v spec="$spec" '
    BEGIN {
      n = split(spec, parts, ",")
      for (i = 1; i <= n; i++) {
        gsub(/[[:space:]]/, "", parts[i])
        if (parts[i] ~ /^P[0-9]+-P[0-9]+$/) {
          split(parts[i], se, "-")
          lo[++r] = substr(se[1], 2) + 0; hi[r] = substr(se[2], 2) + 0
        } else if (parts[i] ~ /^P[0-9]+$/) {
          lo[++r] = substr(parts[i], 2) + 0; hi[r] = lo[r]
        }
      }
      ranges = r
    }
    {
      # 항목 id 는 `- [ ] **P12.**` 의 P<숫자><마침표> 뿐이다. 그 형태가 아니면 id 를 못 읽은 것이고,
      # 못 읽은 줄은 **범위 안으로 친다** — 선언 안 된 항목이 조용히 통과하면 ship_scope 가
      # 검사를 무력화하는 도구가 된다.
      if (match($0, /P[0-9]+\./)) {
        id = substr($0, RSTART + 1, RLENGTH - 2) + 0
        for (i = 1; i <= ranges; i++) if (id >= lo[i] && id <= hi[i]) { print; next }
        next
      }
      print
    }
  '
}

# hpx_ship_preflight <task_id>
# 출력 1행: ok | blocked
#      이후: 막은 사유
# exit: 통과 0 / 차단 1
hpx_ship_preflight() {
  local task_id="${1-}" blocked=""
  hpx_task_id_validate "$task_id" 2>/dev/null || { printf 'blocked\n부적격 task_id\n'; return 1; }

  local plan="docs/plans/${task_id}.md"
  [ -f "$plan" ] || plan="docs/plans/done/${task_id}.md"
  [ -f "$plan" ] || blocked="${blocked}계획서 없음: docs/plans/${task_id}.md (/plan 먼저)\n"

  local branch; branch="$(git branch --show-current 2>/dev/null)"
  [ "$branch" = "$(hpx_base_branch_name)" ] && blocked="${blocked}현재 브랜치가 ${branch} 다. main 에서 직접 ship 금지\n"
  [ -n "$branch" ] || blocked="${blocked}detached HEAD\n"

  if [ -f "$plan" ]; then
    # 이 레포의 확립된 패턴은 "계획서 1개 → PR 여러 개" 다(구현 ①~⑥ 전부). 계획서 전체가
    # 끝나야만 ship 할 수 있게 하면 그 패턴이 통째로 막힌다. 그래서 frontmatter 의
    # ship_scope 가 **이번 PR 이 책임지는 항목 범위**를 선언하고, 그 범위 안의 미완만 막는다.
    #   ship_scope: P1-P5        범위 하나
    #   ship_scope: P1-P5,P9     여러 구간 (쉼표 구분)
    # 선언이 없으면 종전대로 **전 항목**을 검사한다 — 기본값은 엄격한 쪽이다.
    local scope; scope="$(hpx_plan_frontmatter "$task_id" ship_scope 2>/dev/null)"
    local todo_lines
    if [ -n "$scope" ]; then
      todo_lines="$(hpx_plan_todo_in_scope "$plan" "$scope")"
    else
      todo_lines="$(grep -n '^\s*- \[ \]' "$plan" 2>/dev/null)"
    fi

    local todo; todo="$(printf '%s' "$todo_lines" | grep -c . )"
    todo="${todo:-0}"
    if [ "$todo" -gt 0 ]; then
      local scope_note=""
      [ -n "$scope" ] && scope_note=" (ship_scope: ${scope})"
      blocked="${blocked}계획서 미완 체크박스 ${todo}개${scope_note}:\n$(printf '%s\n' "$todo_lines" | head -5 | sed 's/^/  /')\n"
    fi
  fi

  if [ -n "$blocked" ]; then
    printf 'blocked\n'; printf "$blocked"; return 1
  fi
  printf 'ok\n'; return 0
}

# hpx_ship_resume_point <task_id>
# 재진입 지점을 git/gh 사실로 판정한다. 출력 1행: 재개할 Step 번호, 2행: 근거.
hpx_ship_resume_point() {
  local task_id="${1-}" branch
  branch="$(git branch --show-current 2>/dev/null)"

  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    printf '3\n커밋 안 된 변경이 있다\n'; return 0
  fi
  if [ -z "$(git ls-remote --heads origin "$branch" 2>/dev/null)" ]; then
    printf '6\n원격에 브랜치가 없다 (미push)\n'; return 0
  fi
  local pr
  pr="$(gh pr list --head "$branch" --state open --json url -q '.[0].url' 2>/dev/null)"
  if [ -z "$pr" ]; then
    printf '7\n열린 PR 이 없다\n'; return 0
  fi
  if ! grep -q "$(basename "$pr")" docs/TASKS.md 2>/dev/null; then
    printf '8\nPR %s 는 있는데 TASKS.md 에 링크가 없다 (/done 미적용)\n' "$pr"; return 0
  fi
  printf '0\n완료. 보고만 한다 (PR %s)\n' "$pr"
}

# hpx_commit_category <path>
# 파일 경로 하나를 커밋 분류로 사상한다. adr | docs | test | chore | src
hpx_commit_category() {
  case "${1-}" in
    docs/adr/*)                       printf 'adr\n' ;;
    *src/test/*|*src/testFixtures/*|*.bats) printf 'test\n' ;;
    *.md|docs/*)                      printf 'docs\n' ;;
    .claude/*|scripts/*|*.gradle|gradle/*|.github/*|*.yml|*.yaml|Dockerfile*) printf 'chore\n' ;;
    *)                                printf 'src\n' ;;
  esac
}

# hpx_staged_category_check
# 스테이징된 파일이 한 분류인지 본다. 섞였으면 분류별 목록을 보여주고 1 을 반환한다.
# "한 커밋 = 한 분류" 는 사람이 눈으로 지킬 규칙이 아니다.
hpx_staged_category_check() {
  local files; files="$(git diff --cached --name-only 2>/dev/null)"
  if [ -z "$files" ]; then
    printf 'empty\n스테이징이 비어 있다\n'; return 1
  fi
  local cats="" f c
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    c="$(hpx_commit_category "$f")"
    case " $cats " in *" $c "*) ;; *) cats="$cats $c" ;; esac
  done <<EOF
$files
EOF
  cats="$(echo $cats)"
  local n; n="$(printf '%s\n' $cats | grep -c .)"
  if [ "$n" -gt 1 ]; then
    printf 'mixed\n분류 %d개가 섞였다: %s\n' "$n" "$cats"
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      printf '  %-6s %s\n' "$(hpx_commit_category "$f")" "$f"
    done <<EOF2
$files
EOF2
    return 1
  fi
  printf '%s\n' "$cats"
  printf '%s 파일 %s개\n' "$cats" "$(printf '%s\n' "$files" | grep -c .)"
  return 0
}
