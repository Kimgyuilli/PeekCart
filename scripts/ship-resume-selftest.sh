#!/usr/bin/env bash
# D-105: 조회 성공의 부재와 실패를 구분한다. 외부 네트워크/저장소는 사용하지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHIP_LOGIC="${SHIP_LOGIC:-$ROOT/.claude/scripts/shared-logic.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/repo/docs/progress" "$TMP/replies"

# 실제 argv를 검사한다. 존재하지 않는 명령(특히 push/create)은 언제나 실패한다.
cat > "$TMP/bin/git" <<'STUB'
#!/usr/bin/env bash
set -eu
printf 'git %s\n' "$*" >> "$STUB_CALLS"
case "$*" in
  'branch --show-current') reply=branch ;;
  'status --porcelain') reply=status ;;
  'ls-remote --heads origin debt/d105-resume') reply=remote ;;
  *) printf 'unexpected git command: %s\n' "$*" >&2; exit 97 ;;
esac
cat "$STUB_REPLIES/$reply.out"
cat "$STUB_REPLIES/$reply.err" >&2
exit "$(cat "$STUB_REPLIES/$reply.rc")"
STUB
cat > "$TMP/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -eu
printf 'gh %s\n' "$*" >> "$STUB_CALLS"
if [ "$*" != "pr list --head debt/d105-resume --state open --json url -q .[0].url" ]; then
  printf 'unexpected gh command: %s\n' "$*" >&2; exit 97
fi
cat "$STUB_REPLIES/pr.out"
cat "$STUB_REPLIES/pr.err" >&2
exit "$(cat "$STUB_REPLIES/pr.rc")"
STUB
chmod +x "$TMP/bin/git" "$TMP/bin/gh"
export STUB_REPLIES="$TMP/replies" STUB_CALLS="$TMP/calls"
export PATH="$TMP/bin:$PATH"

reply() { # command, exit, stdout, stderr
  printf '%s\n' "$2" > "$STUB_REPLIES/$1.rc"
  printf '%s' "$3" > "$STUB_REPLIES/$1.out"
  printf '%s' "$4" > "$STUB_REPLIES/$1.err"
}

reset_case() {
  reply branch 0 'debt/d105-resume' ''
  reply status 0 '' ''
  reply remote 0 $'eb5dee5\trefs/heads/debt/d105-resume\n' ''
  reply pr 0 'https://github.com/example/PeakCart/pull/185' ''
  : > "$STUB_CALLS"
  : > "$TMP/repo/docs/TASKS.md"
  : > "$TMP/repo/docs/progress/TASKS-done.md"
}

pass=0
check() { # name, expected exit, first line, number of commands, stdout reason, stderr cause
  local name="$1" want_rc="$2" want_step="$3" want_calls="$4" reason="$5" cause="${6:-}"
  local rc=0
  (cd "$TMP/repo"; bash -c 'set -euo pipefail; source "$1"; hpx_ship_resume_point D-105' _ "$SHIP_LOGIC") \
    > "$TMP/stdout" 2> "$TMP/stderr" || rc=$?
  local step out err calls
  step="$(head -1 "$TMP/stdout")"
  out="$(cat "$TMP/stdout")"; err="$(cat "$TMP/stderr")"
  calls="$(wc -l < "$STUB_CALLS" | tr -d ' ')"
  if [ "$rc" != "$want_rc" ] || [ "$step" != "$want_step" ] || [ "$calls" != "$want_calls" ] \
    || [[ "$out" != *"$reason"* ]] || [[ "$err" != *"$cause"* ]]; then
    printf 'FAIL %s: exit=%s step=%s calls=%s\nstdout: %s\nstderr: %s\n' "$name" "$rc" "$step" "$calls" "$out" "$err" >&2
    cat "$STUB_CALLS" >&2
    return 1
  fi
  printf 'ok %s\n' "$name"
  pass=$((pass+1))
}

reset_case
reply status 0 ' M docs/plans/D-105.md' ''
check 'dirty returns Step 3 before remote queries' 0 3 2 '커밋 안 된 변경'

reset_case
reply remote 0 '' ''
check 'successful empty remote returns Step 6 without gh' 0 6 3 '미push'

reset_case
reply pr 0 '' ''
check 'successful empty PR returns Step 7' 0 7 4 '열린 PR'

reset_case
check 'existing PR without completion link returns Step 8' 0 8 4 '/done 미적용'

for file in docs/TASKS.md docs/progress/TASKS-done.md; do
  reset_case
  printf '[PR #185](https://github.com/example/PeakCart/pull/185)\n' > "$TMP/repo/$file"
  check "completion link in $file returns Step 0" 0 0 4 '완료.'
done

for partial in '' $'eb5dee5\trefs/heads/debt/d105-resume\n'; do
  reset_case
  reply remote 128 "$partial" 'fatal: unable to access origin: Could not resolve host'
  check 'remote failure is unknown even with partial stdout' 1 unknown 3 'git ls-remote (exit 128)' 'Could not resolve host'
done

for partial in '' 'https://github.com/example/PeakCart/pull/185'; do
  reset_case
  reply pr 1 "$partial" 'HTTP 401: Bad credentials'
  check 'PR failure is unknown even with partial stdout' 1 unknown 4 'gh pr list (exit 1)' 'Bad credentials'
done

reset_case
reply remote 128 '' ''
check 'remote failure without stderr retains command and exit' 1 unknown 3 'git ls-remote (exit 128)'

reset_case
reply pr 127 '' 'gh: command not found'
check 'unavailable gh is unknown' 1 unknown 4 'gh pr list (exit 127)' 'command not found'

reset_case
reply pr 1 '' ''
check 'PR failure without stderr retains command and exit' 1 unknown 4 'gh pr list (exit 1)'

reset_case
reply branch 128 '' 'fatal: not a git repository'
check 'branch read failure stops before status and remote' 1 unknown 1 'git branch (exit 128)' 'not a git repository'

reset_case
reply branch 0 '' ''
check 'detached HEAD stops before status and remote' 1 unknown 1 '브랜치가 비어 있다'

reset_case
reply status 128 '' 'fatal: unable to read index'
check 'status read failure stops before remote' 1 unknown 2 'git status (exit 128)' 'unable to read index'

reset_case
reply remote 0 '' 'warning: redirecting to canonical URL'
check 'successful remote warning does not become a branch' 0 6 3 '미push'

reset_case
reply pr 0 '' 'warning: GitHub API deprecation'
check 'successful PR warning does not become a PR' 0 7 4 '열린 PR'

printf 'ship resume self-test: %s passed\n' "$pass"
