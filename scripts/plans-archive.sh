#!/usr/bin/env bash
# 완료된 계획서를 docs/plans/done/ 으로 옮기고 인바운드 참조를 고친다.
#
#   후보 신호는 PR 머지 여부다. 전체 완료는 선택자가 별도로 확인한다.
#
# 체크박스를 쓰지 않는 이유는 실측이다. 루트 계획서 22개 중 체크박스로 완료 판정되는 것은
# 8개뿐인데 참조된 PR 16개는 전부 머지 상태였다. `task-impl3-spring-cloud-gateway` 는
# 미완 체크박스가 47개인데 구현 ③ 은 PR 4개가 머지된 완료 작업이다. `/work` 3단계의
# 체크박스 갱신 규칙이 지켜지지 않았고, 지켜지지 않은 신호로 파일을 옮길 수는 없다.
#
# 이 스크립트가 참조 재작성까지 하는 이유도 실측이다. 과거 done/ 이동이 링크를 안 고쳐
# TASKS.md·PHASE4.md·ADR 5개 등 17곳이 조용히 깨져 있었다(2026-09-19 복구). 마크다운
# 링크는 깨져도 아무 소리가 나지 않으므로 이동과 재작성을 한 동작으로 묶는다.
#
# 실행 시점은 `/ship` 이 아니라 `/sync` 다. 머지는 PR 생성 이후, 대개 다음 세션 사이에
# 일어나므로 ship 시점에는 판정이 불가능하다. 다음 작업을 시작할 때 쓸어담는다.
#
# 부분 완료처럼 머지만으로 끝나지 않은 계획은 frontmatter 의 `archive: hold <PR...>` 로 후보에서 뺀다.
#
# 사용:
#   plans-archive.sh              dry-run. 무엇을 옮길지만 보고한다 (기본)
#   plans-archive.sh --apply --select D-085,D-090 --base origin/main \
#     --manifest docs/preservation/campaign.json   선택 git mv + 링크/보존 검사
#   plans-archive.sh --self-test  판정 로직이 의도대로 도는지 (fixture)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLANS="docs/plans"
DONE="${PLANS}/done"

# 계획서가 아닌 것. 완료 개념이 없으므로 절대 옮기지 않는다.
KEEP_ALWAYS="PLAN-BLINDSPOTS.md"

# PR 상태는 한 번에 받아 둔다. PR 마다 `gh pr view` 를 부르면 후보 4개(PR 42개)에 23초가 걸렸다.
# 목록에 없는 번호는 UNKNOWN 이 되어 후보에서 빠진다 — 옮기지 않는 쪽으로 실패한다.
PR_STATES=""
load_pr_states() {
  command -v gh >/dev/null 2>&1 || return 0
  PR_STATES="$(gh pr list --state all --limit 1000 --json number,state -q '.[] | "\(.number) \(.state)"' 2>/dev/null)"
}

# pr_state <num> — MERGED | OPEN | CLOSED | UNKNOWN
pr_state() {
  local s
  s="$(printf '%s\n' "$PR_STATES" | awk -v n="$1" '$1 == n { print $2; exit }')"
  printf '%s\n' "${s:-UNKNOWN}"
}

# hold_prs <plan> — frontmatter `archive: hold <PR...>` 의 PR 번호. 선언이 없으면 실패.
# 부분 병합·후속 세션 대기처럼 PR 머지만으로 끝나지 않는 계획은 계획서가 직접 선언한다.
# 선언이 본문 산문에만 있으면 판정자가 매 sync 마다 계획서를 다시 읽었다(한 번에 약 50K 토큰).
# 적힌 PR 은 "완료로 치지 않는다"는 뜻이다. 그 밖의 머지 PR 이 생기면 hold 가 풀려 다시 판정한다.
# 머지 뒤 세션을 이어가지 않으므로, 사람이 hold 를 지우는 단계에 기대지 않는다.
hold_prs() {
  awk 'NR == 1 && $0 != "---" { exit } NR > 1 && $0 == "---" { exit }
       /^archive:[[:space:]]*hold([[:space:]]+#?[0-9]+)*[[:space:]]*$/ {
         found = 1
         for (i = 2; i <= NF; i++) { sub(/^#/, "", $i); if ($i ~ /^[0-9]+$/) print $i }
         exit
       }
       END { exit !found }' "$1"
}

# plan_prs <plan_file> — 계획서와 그 audit 에서 참조된 PR 번호를 모은다.
plan_prs() {
  local plan="$1" audit="${1%.md}.audit.md"
  {
    [ -f "$plan" ] && cat "$plan"
    [ -f "$audit" ] && cat "$audit"
  } 2>/dev/null | grep -oE 'pull/[0-9]+' | sed 's|pull/||' | sort -un
}

# 후보: merged PR이 있고 open PR이 없어도 부분 작업일 수 있다. 명시 선택 없이 이동하지 않는다.
# "증거 없음"과 "미완"을 구분해서 보고한다 — 전자는 사람이 봐야 한다.
classify() {
  local plan="$1" prs state held hold=no
  if held="$(hold_prs "$plan")"; then hold=yes; fi
  prs="$(plan_prs "$plan")"
  local merged="" open="" fresh=""
  for n in $prs; do
    state="$(pr_state "$n")"
    case "$state" in
      MERGED) merged="${merged}${n} " ;;
      OPEN)   open="${open}${n} " ;;
    esac
  done
  for n in $merged; do
    printf '%s\n' $held | grep -qx "$n" || fresh="${fresh}${n} "
  done
  if [ -n "$open" ]; then
    printf 'IN_FLIGHT\t%s\n' "$(echo $open)"
  elif [ "$hold" = yes ] && [ -z "$fresh" ]; then
    printf 'HOLD\t%s\n' "$(echo $held)"
  elif [ -n "$merged" ]; then
    printf 'DONE\t%s\t%s\n' "$(echo $merged)" "$([ "$hold" = no ] || echo "hold 이후 새 머지 PR $(echo $fresh)")"
  else
    printf 'NO_EVIDENCE\t%s\n' "$(echo $prs)"
  fi
}

# Apply is always selected and goes through the ordered-content manifest checker.
SELECTED=()
MANIFEST=""
BASE=""

run() {
  local apply="$1" plan base verdict prs note n_done=0 n_hold=0 n_flight=0 n_noev=0 files=0
  local selection found chosen=()
  if [ "$apply" = yes ]; then
    [ "${#SELECTED[@]}" -gt 0 ] && [ -n "$MANIFEST" ] && [ -n "$BASE" ] || {
      echo "--apply requires --select <stems> --base <comparison-commit> --manifest docs/preservation/<name>.json" >&2
      return 2
    }
  fi
  for selection in "${SELECTED[@]}"; do
    [[ "$selection" =~ ^[A-Za-z0-9._-]+$ ]] && [[ "$selection" != .* ]] && [[ "$selection" != -* ]] \
      && [[ "$selection" != *..* ]] && [[ "$selection" != *.audit ]] \
      && [ -f "$PLANS/$selection.md" ] || { echo "invalid/missing selected plan: $selection" >&2; return 2; }
  done
  printf '=== 계획서 아카이브 (%s) ===\n\n' "$([ "$apply" = yes ] && echo 선택실행 || echo dry-run)"
  load_pr_states
  for plan in "$PLANS"/*.md; do
    base="$(basename "$plan")"
    case "$base" in *.audit.md|"$KEEP_ALWAYS") continue ;; esac
    if [ "${#SELECTED[@]}" -gt 0 ]; then
      found=no
      for selection in "${SELECTED[@]}"; do [ "$base" = "$selection.md" ] && found=yes; done
      [ "$found" = yes ] || continue
    fi
    IFS=$'\t' read -r verdict prs note <<EOF
$(classify "$plan")
EOF
    case "$verdict" in
      DONE)
        chosen+=("${base%.md}")
        files=$((files+1)); [ ! -f "${plan%.md}.audit.md" ] || files=$((files+1))
        printf '  후보   %-46s PR %s (%s전체 완료는 선택자가 확인)\n' "$base" "$prs" "${note:+$note · }"
        n_done=$((n_done+1)) ;;
      HOLD)
        printf '  유지   %-46s hold PR %s 이후 새 머지 PR 없음\n' "$base" "${prs:-없음}"
        n_hold=$((n_hold+1)) ;;
      IN_FLIGHT)
        printf '  진행중 %-46s PR %s 미머지\n' "$base" "$prs"
        n_flight=$((n_flight+1)) ;;
      NO_EVIDENCE)
        printf '  보류   %-46s PR 근거 없음/미머지 %s\n' "$base" "$prs"
        n_noev=$((n_noev+1)) ;;
    esac
  done
  printf '\n후보 %d · 유지 %d · 진행중 %d · 보류 %d · 후보 파일 %d\n' "$n_done" "$n_hold" "$n_flight" "$n_noev" "$files"
  if [ "$apply" = yes ]; then
    [ "${#chosen[@]}" -eq "${#SELECTED[@]}" ] || {
      echo "선택 목록 전체가 후보가 아니므로 아무 파일도 이동하지 않는다" >&2; return 1; }
    python3 "$ROOT/scripts/docs-archive-apply.py" --root "$PWD" --base "$BASE" \
      --manifest "$MANIFEST" --select "${chosen[@]}" || return $?
  elif [ "$n_done" -gt 0 ]; then
    echo '실행에는 전체 완료를 확인한 --select, 비교 --base, 보존 --manifest가 필요하다. 전체 일괄 apply는 없다.'
  fi
  return 0
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/docs/plans/done"
  cd "$tmp"

  # gh 를 스텁으로 갈아끼워 네트워크 없이 판정 로직만 시험한다.
  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '776 MERGED\n777 MERGED\n888 OPEN\n999 CLOSED\n'
STUB
  chmod +x "$tmp/bin/gh"
  PATH="$tmp/bin:$PATH"
  load_pr_states

  printf 'PR: https://github.com/x/y/pull/777\n' > docs/plans/task-merged.md
  printf 'PR: https://github.com/x/y/pull/888\n' > docs/plans/task-open.md
  printf 'PR: https://github.com/x/y/pull/999\n' > docs/plans/task-closed.md
  printf '계획만 있고 PR 없음\n'                   > docs/plans/task-bare.md
  printf '%s\n' '- [ ] 미완'                       > docs/plans/PLAN-BLINDSPOTS.md

  check() {
    local file="$1" want="$2" got
    got="$(classify "docs/plans/$file" | cut -f1)"
    if [ "$got" = "$want" ]; then
      echo "  ok  $file -> $got"
    else
      echo "self-test 실패: $file 기대 $want 인데 $got"
      rc=1
    fi
  }
  check task-merged.md DONE
  check task-open.md   IN_FLIGHT
  check task-closed.md NO_EVIDENCE
  check task-bare.md   NO_EVIDENCE

  # 체크박스가 판정에 영향을 주지 않아야 한다 (실측상 신뢰 불가 신호)
  printf 'PR: https://github.com/x/y/pull/777\n- [ ] 미완 1\n- [ ] 미완 2\n' > docs/plans/task-unchecked.md
  check task-unchecked.md DONE

  # hold 에 적힌 머지 PR 만 있으면 유지, 그 밖의 머지 PR 이 생기면 다시 후보가 된다.
  # 본문에 같은 글자가 있어도 hold 가 아니다
  printf -- '---\ngrade: L\narchive: hold 777\n---\nPR: https://github.com/x/y/pull/777\n' > docs/plans/task-hold.md
  printf -- '---\narchive: hold #777\n---\n.../pull/777 .../pull/776\n' > docs/plans/task-hold-fresh.md
  printf -- '---\narchive: hold\n---\nPR: https://github.com/x/y/pull/777\n' > docs/plans/task-hold-bare.md
  printf -- '---\narchive: hold\n---\nPR 없음\n'                       > docs/plans/task-hold-nopr.md
  printf -- '---\ngrade: L\n---\narchive: hold 777\nPR: https://github.com/x/y/pull/777\n' > docs/plans/task-body-hold.md
  check task-hold.md       HOLD
  check task-hold-fresh.md DONE
  check task-hold-bare.md  DONE
  check task-hold-nopr.md  HOLD
  check task-body-hold.md  DONE

  # 목록에 없는 PR 번호는 머지로 보지 않는다
  printf 'PR: https://github.com/x/y/pull/555\n' > docs/plans/task-unknown.md
  check task-unknown.md NO_EVIDENCE

  # PLAN-BLINDSPOTS 는 순회 대상에서 빠져야 한다
  if run no 2>/dev/null | grep -q 'PLAN-BLINDSPOTS'; then
    echo "self-test 실패: PLAN-BLINDSPOTS.md 가 순회에 잡혔다"
    rc=1
  else
    echo "  ok  PLAN-BLINDSPOTS.md 제외"
  fi
  return "$rc"
}

cd "$ROOT" || exit 2
MODE=no
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apply) MODE=yes ;;
    --dry-run) MODE=no ;;
    --self-test) self_test; exit $? ;;
    --select)
      [ "$#" -ge 2 ] || { echo "--select needs comma-separated plan stems" >&2; exit 2; }
      IFS=',' read -r -a selection_args <<< "$2"
      SELECTED+=("${selection_args[@]}"); shift ;;
    --base|--manifest)
      [ "$#" -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }
      if [ "$1" = --base ]; then BASE="$2"; else MANIFEST="$2"; fi
      shift ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
  shift
done
run "$MODE"
