#!/usr/bin/env bash
# .claude/scripts/hooks/commit-verify-guard.sh
#
# PreToolUse(Bash) 훅. 커밋 검사를 **우회하는 경로**를 막는다.
# 분류 검사 자체는 `.githooks/pre-commit` 이 한다. 여기서 중복하지 않는다.
#
# 막는 것 둘:
#   1. `git commit --no-verify` / `-n` — git 훅을 통째로 건너뛴다
#   2. `core.hooksPath` 미설정 — pre-commit 이 조용히 안 돌게 된다
#
# PreToolUse 인 이유: 두 검사 다 **명령 문자열과 git config 만** 보므로 스테이징
# 타이밍과 무관하다. 그래서 알림이 아니라 실제 차단이 된다.
#
# 탈출구: HPX_ALLOW_MIXED=1 이면 통과한다. pre-commit 도 같은 변수를 존중하므로
# 정당한 혼합 커밋은 --no-verify 없이 그 변수만으로 끝난다.
set -uo pipefail

deny() {
  jq -nc --arg r "$1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

CMD="$(cat | jq -r '.tool_input.command // ""' 2>/dev/null)"
printf '%s' "$CMD" | grep -qE 'git[[:space:]]+commit' || exit 0
[ "${HPX_ALLOW_MIXED:-}" = "1" ] && exit 0

if printf '%s' "$CMD" | grep -qE 'git[[:space:]]+commit[^|;&]*([[:space:]]--no-verify|[[:space:]]-[a-zA-Z]*n)'; then
  deny "git commit 에 --no-verify (또는 -n) 가 붙어 있다. 그 플래그는 .githooks/pre-commit 을
통째로 건너뛰어 분류 검사를 무력화한다.

검사를 통과할 수 없는 사정이면 플래그가 아니라 의도를 적는다:
  HPX_ALLOW_MIXED=1 git commit ...

pre-commit 도 같은 변수를 존중하므로 우회 없이 끝난다."
fi

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0
HP="$(git config core.hooksPath 2>/dev/null || true)"
if [ "$HP" != ".githooks" ]; then
  deny "core.hooksPath 가 '.githooks' 가 아니다(현재: '${HP:-미설정}').
이 상태로 커밋하면 .githooks/pre-commit 이 돌지 않아 분류 검사가 조용히 빠진다.

  git config core.hooksPath .githooks

를 먼저 실행한다."
fi
exit 0
