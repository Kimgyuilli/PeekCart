#!/usr/bin/env bash
# 완료된 계획서를 docs/plans/done/ 으로 옮기고 인바운드 참조를 고친다.
#
#   판정 신호는 **PR 머지 여부**다. 체크박스가 아니다.
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
# 사용:
#   plans-archive.sh              dry-run. 무엇을 옮길지만 보고한다 (기본)
#   plans-archive.sh --apply      실제로 git mv + 참조 재작성
#   plans-archive.sh --self-test  판정 로직이 의도대로 도는지 (fixture)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLANS="docs/plans"
DONE="${PLANS}/done"

# 계획서가 아닌 것. 완료 개념이 없으므로 절대 옮기지 않는다.
KEEP_ALWAYS="PLAN-BLINDSPOTS.md"

# pr_state <num> — MERGED | OPEN | CLOSED | UNKNOWN
pr_state() {
  local n="$1"
  if ! command -v gh >/dev/null 2>&1; then printf 'UNKNOWN\n'; return; fi
  gh pr view "$n" --json state -q '.state' 2>/dev/null || printf 'UNKNOWN\n'
}

# plan_prs <plan_file> — 계획서와 그 audit 에서 참조된 PR 번호를 모은다.
plan_prs() {
  local plan="$1" audit="${1%.md}.audit.md"
  {
    [ -f "$plan" ] && cat "$plan"
    [ -f "$audit" ] && cat "$audit"
  } 2>/dev/null | grep -oE 'pull/[0-9]+' | sed 's|pull/||' | sort -un
}

# 판정: 머지된 PR 이 하나라도 있으면 완료. 없으면 옮기지 않는다.
# "증거 없음"과 "미완"을 구분해서 보고한다 — 전자는 사람이 봐야 한다.
classify() {
  local plan="$1" prs state
  prs="$(plan_prs "$plan")"
  if [ -z "$prs" ]; then
    printf 'NO_EVIDENCE\t\n'
    return
  fi
  local merged="" open=""
  for n in $prs; do
    state="$(pr_state "$n")"
    case "$state" in
      MERGED) merged="${merged}${n} " ;;
      OPEN)   open="${open}${n} " ;;
    esac
  done
  if [ -n "$open" ]; then
    printf 'IN_FLIGHT\t%s\n' "$(echo $open)"
  elif [ -n "$merged" ]; then
    printf 'DONE\t%s\n' "$(echo $merged)"
  else
    printf 'NO_EVIDENCE\t%s\n' "$(echo $prs)"
  fi
}

# 인바운드 참조 재작성. docs/plans/<base> 를 docs/plans/done/<base> 로 바꾼다.
# 경로 없는 백틱 언급은 링크가 아니라 산문이므로 건드리지 않는다.
# docs/progress/evidence/ 는 제외한다 — 외부 원문(PR 본문 등)을 그대로 떠 둔 스냅샷이라
# 고치면 출처와 어긋난다. D-026 이동 때 PR #124 본문 스냅샷이 실제로 변조됐다.
rewrite_refs() {
  local base="$1"
  python3 - "$base" <<'PY'
import io, os, re, subprocess, sys
base = sys.argv[1]
files = subprocess.run(
    ['grep', '-rlF', 'docs/plans/%s' % base, '--exclude-dir=.git', '.'],
    capture_output=True, text=True).stdout.split()
n = 0
for f in files:
    if f.startswith('./.claude/scripts/tests'):
        continue
    if f.startswith('./docs/progress/evidence/'):
        continue
    try:
        s = io.open(f, encoding='utf-8').read()
    except Exception:
        continue
    new = re.sub(r'docs/plans/(?!done/)' + re.escape(base), 'docs/plans/done/' + base, s)
    if new != s:
        io.open(f, 'w', encoding='utf-8').write(new)
        n += 1
print(n)
PY
}

archive_one() {
  local plan="$1" apply="$2"
  local base; base="$(basename "$plan")"
  local stem="${base%.md}"
  local audit="${PLANS}/${stem}.audit.md"
  local moved=0 refs=0

  if [ "$apply" = "yes" ]; then
    mkdir -p "$DONE"
    git mv "$plan" "${DONE}/${base}" && moved=$((moved+1))
    refs=$(rewrite_refs "$base")
    if [ -f "$audit" ]; then
      git mv "$audit" "${DONE}/${stem}.audit.md" && moved=$((moved+1))
      refs=$(( refs + $(rewrite_refs "${stem}.audit.md") ))
    fi
  else
    moved=1; [ -f "$audit" ] && moved=2
    refs=$(grep -rlF "docs/plans/${base}" --exclude-dir=.git . 2>/dev/null \
           | grep -v '^./.claude/scripts/tests' | wc -l | tr -d ' ')
  fi
  printf '%s\t%s\n' "$moved" "$refs"
}

run() {
  local apply="$1"
  local n_done=0 n_flight=0 n_noev=0 f_moved=0 f_refs=0
  printf '=== 계획서 아카이브 (%s) ===\n\n' "$([ "$apply" = yes ] && echo 실행 || echo dry-run)"

  local plan base verdict prs res
  for plan in "$PLANS"/*.md; do
    base="$(basename "$plan")"
    case "$base" in
      *.audit.md) continue ;;
      "$KEEP_ALWAYS") continue ;;
    esac
    IFS=$'\t' read -r verdict prs <<EOF
$(classify "$plan")
EOF
    case "$verdict" in
      DONE)
        IFS=$'\t' read -r m r <<EOF
$(archive_one "$plan" "$apply")
EOF
        printf '  이동   %-46s PR %s (파일 %s · 참조 %s곳)\n' "$base" "$prs" "$m" "$r"
        n_done=$((n_done+1)); f_moved=$((f_moved+m)); f_refs=$((f_refs+r))
        ;;
      IN_FLIGHT)
        printf '  진행중 %-46s PR %s 미머지\n' "$base" "$prs"
        n_flight=$((n_flight+1))
        ;;
      NO_EVIDENCE)
        if [ -n "$prs" ]; then
          printf '  보류   %-46s PR %s 가 머지 상태가 아니다\n' "$base" "$prs"
        else
          printf '  보류   %-46s PR 참조 없음. 사람이 판단한다\n' "$base"
        fi
        n_noev=$((n_noev+1))
        ;;
    esac
  done

  printf '\n완료 %d · 진행중 %d · 보류 %d' "$n_done" "$n_flight" "$n_noev"
  [ "$apply" = yes ] && printf ' · 이동 파일 %d · 참조 수정 %d곳' "$f_moved" "$f_refs"
  printf '\n'
  [ "$apply" != yes ] && [ "$n_done" -gt 0 ] && printf '실제 이동: scripts/plans-archive.sh --apply\n'
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
case "$3" in
  777) echo MERGED ;;
  888) echo OPEN ;;
  *)   echo CLOSED ;;
esac
STUB
  chmod +x "$tmp/bin/gh"
  PATH="$tmp/bin:$PATH"

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

  # PLAN-BLINDSPOTS 는 순회 대상에서 빠져야 한다
  if run no 2>/dev/null | grep -q 'PLAN-BLINDSPOTS'; then
    echo "self-test 실패: PLAN-BLINDSPOTS.md 가 순회에 잡혔다"
    rc=1
  else
    echo "  ok  PLAN-BLINDSPOTS.md 제외"
  fi
  return "$rc"
}

cd "$ROOT" 2>/dev/null || true
case "${1:-}" in
  --apply)     run yes ;;
  --self-test) self_test ;;
  ""|--dry-run) run no ;;
  -h|--help)   sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
esac
