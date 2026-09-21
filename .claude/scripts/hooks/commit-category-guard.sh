#!/usr/bin/env bash
# .claude/scripts/hooks/commit-category-guard.sh
#
# PostToolUse(Bash) 훅. 방금 만들어진 커밋이 "한 커밋 = 한 분류" 를 어겼으면 되먹인다.
#
# 왜 PostToolUse 인가: PreToolUse 는 도구 실행 **전에** 돌아서
# `git add -- x && git commit ...` 같은 복합 명령의 스테이징을 못 본다. 직전 상태를 보고
# 엉뚱한 판정을 낸다. 실제로 만들어진 커밋을 보는 쪽이 방식과 무관하게 정확하다.
#
# 왜 훅인가: /ship 문서에 "mixed 면 커밋하지 말라" 가 적혀 있었는데 지켜지지 않았다.
# hpx_staged_category_check 는 1 을 정확히 반환했고 호출자가 종료 코드를 무시했다.
# 문서가 유일한 강제 수단이면 건너뛰어진다.
#
# 탈출구: HPX_ALLOW_MIXED=1 이면 통과한다. 정당한 혼합 커밋에서 막다른 길이 되면
# 훅을 지우게 되고, 그러면 강제가 통째로 사라진다.
set -uo pipefail

INPUT="$(cat)"
CMD="$(printf '%s' "$INPUT" | jq -r '.tool_input.command // ""' 2>/dev/null)"

printf '%s' "$CMD" | grep -qE 'git[[:space:]]+commit' || exit 0
[ "${HPX_ALLOW_MIXED:-}" = "1" ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

# 명령이 실패해 커밋이 안 생겼을 수 있다. 그 경우 HEAD 는 이미 검사된 옛 커밋이므로
# 다시 신고하면 오탐이다. 방금(120초 이내) 만들어진 커밋만 본다.
AGE=$(( $(date +%s) - $(git log -1 --format=%ct 2>/dev/null || echo 0) ))
[ "$AGE" -le 120 ] || exit 0

# 머지 커밋은 분류 개념이 없다.
[ "$(git rev-list --parents -n1 HEAD | wc -w | tr -d ' ')" -le 2 ] || exit 0

FILES="$(git show --name-only --format= HEAD 2>/dev/null | grep -v '^$')"
[ -n "$FILES" ] || exit 0

# shellcheck source=/dev/null
. .claude/scripts/lib/ship.sh 2>/dev/null || exit 0
command -v hpx_commit_category >/dev/null 2>&1 || exit 0

CATS=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  c="$(hpx_commit_category "$f")"
  case " $CATS " in *" $c "*) ;; *) CATS="$CATS $c" ;; esac
done <<EOF
$FILES
EOF
CATS="$(echo $CATS)"
N="$(printf '%s\n' $CATS | grep -c .)"
[ "$N" -gt 1 ] || exit 0

DETAIL="$(while IFS= read -r f; do
  [ -n "$f" ] && printf '  %-6s %s\n' "$(hpx_commit_category "$f")" "$f"
done <<EOF2
$FILES
EOF2
)"

REASON="방금 만든 커밋 $(git rev-parse --short HEAD) 이 분류 ${N}개를 섞었다: ${CATS}

${DETAIL}

/ship 3 규약: 한 커밋 = 한 분류. 분류별로 나눠 다시 커밋한다.
push 전이면 git reset --soft HEAD~1 후 분류별로 스테이징한다.
의도한 혼합이면 HPX_ALLOW_MIXED=1 을 붙여 다시 실행한다."

jq -nc --arg r "$REASON" '{decision:"block", reason:$r}'
