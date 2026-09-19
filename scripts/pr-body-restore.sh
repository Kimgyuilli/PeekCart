#!/usr/bin/env bash
# PR 제목·본문을 스냅샷 원문으로 되돌린다.
#
#   백업은 `docs/progress/evidence/pr-bodies-snapshot-20260919.json` 이다. 계획서가 적었던
#   `.cache/pr-backup-latest` 는 존재하지 않는다. .cache 는 git 밖이라 세션 사이에 사라지고
#   실제로 사라졌다. 커밋된 스냅샷이 유일하게 남은 원문이므로 이쪽을 정본으로 본다.
#
# `task-pr-body-reformat` 이 PR 124개의 문체를 고쳤다. 고친 것이 원문보다 나쁘다고 판단되면
# 이 스크립트로 되돌린다. 되돌릴 경로 없이 124개를 고치지 않는다는 것이 그 계획서의 전제다.
#
# 되돌림은 항상 번호를 명시한다. 전체 복원은 작업 전체를 무르는 동작이라 `--all` 을 따로
# 요구한다. 손이 미끄러져 124개가 한 번에 되돌아가는 일이 없어야 한다.
#
# 사용:
#   pr-body-restore.sh 124 70        dry-run. 원문과 무엇이 다른지만 보고한다 (기본)
#   pr-body-restore.sh --apply 124   실제로 gh pr edit
#   pr-body-restore.sh --all         전체 드리프트 보고 (복원하려면 --apply --all)
#   pr-body-restore.sh --self-test   diff 판정과 인자 처리가 의도대로 도는지
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SNAPSHOT="${SNAPSHOT:-docs/progress/evidence/pr-bodies-snapshot-20260919.json}"

usage() { sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# 본문은 반드시 파일로 주고받는다. `$(...)` 는 끝의 개행을 잘라내므로 셸 변수를 거치면
# 복원본이 원문보다 1바이트 짧아진다. P2 검증(망가뜨린 뒤 복원해 바이트 비교)이 실제로
# 이것을 잡았다. 제목은 개행이 없어 변수로 다뤄도 안전하다.

# snap_title <num> — 스냅샷 제목. 그 번호가 없으면 exit 1.
snap_title() {
  python3 - "$SNAPSHOT" "$1" <<'PY'
import json, sys
path, num = sys.argv[1], int(sys.argv[2])
for x in json.load(open(path)):
    if x['number'] == num:
        sys.stdout.write(x['title'] or '')
        sys.exit(0)
sys.exit(1)
PY
}

# snap_body_file <num> <dest> — 스냅샷 본문을 바이트 그대로 파일에 쓴다.
snap_body_file() {
  python3 - "$SNAPSHOT" "$1" "$2" <<'PY'
import io, json, sys
path, num, dest = sys.argv[1], int(sys.argv[2]), sys.argv[3]
for x in json.load(open(path)):
    if x['number'] == num:
        io.open(dest, 'w', encoding='utf-8', newline='').write(x['body'] or '')
        sys.exit(0)
sys.exit(1)
PY
}

# live_body_file <num> <dest> — GitHub 현재 본문을 바이트 그대로 파일에 쓴다.
# `gh ... -q .body` 는 끝에 개행을 덧붙이므로 JSON 에서 직접 꺼낸다.
#
# gh 출력을 python 에 파이프로 물리면 안 된다. heredoc 이 python 의 stdin 을 차지해
# 파이프가 도달하지 못한다(writing-lint.sh 가 같은 함정을 주석으로 남겨 뒀고, 이 스크립트의
# 자기시험이 실제로 다시 잡았다). 파일로 떨어뜨리고 경로를 인자로 넘긴다.
live_body_file() {
  local raw rc=0
  raw="$(mktemp -t pr-body-json.XXXXXX)"
  if ! gh pr view "$1" --json body > "$raw" 2>/dev/null; then rm -f "$raw"; return 1; fi
  python3 - "$raw" "$2" <<'PY' || rc=1
import io, json, sys
io.open(sys.argv[2], 'w', encoding='utf-8', newline='').write(json.load(open(sys.argv[1]))['body'] or '')
PY
  rm -f "$raw"
  return $rc
}

snap_numbers() {
  python3 - "$SNAPSHOT" <<'PY'
import json, sys
for x in sorted(json.load(open(sys.argv[1])), key=lambda v: v['number']):
    print(x['number'])
PY
}

# restore_one <num> <apply> — 0 = 원문과 같음, 1 = 다름(또는 되돌림), 2 = 스냅샷에 없음
restore_one() {
  local n="$1" apply="$2" st='' lt='' sb='' lb='' rc=1
  if ! st="$(snap_title "$n")"; then
    printf '  없음   PR %-4s 스냅샷에 없다. 되돌릴 원문이 없다\n' "$n"
    return 2
  fi
  lt="$(gh pr view "$n" --json title -q '.title' 2>/dev/null)" || { printf '  실패   PR %-4s gh 조회 실패\n' "$n"; return 2; }

  sb="$(mktemp -t pr-body-snap.XXXXXX)"
  lb="$(mktemp -t pr-body-live.XXXXXX)"
  snap_body_file "$n" "$sb"
  live_body_file "$n" "$lb"

  local what=''
  [ "$st" != "$lt" ] && what='제목'
  cmp -s "$sb" "$lb" || what="${what:+${what}·}본문"

  if [ -z "$what" ]; then
    printf '  동일   PR %-4s 이미 원문이다\n' "$n"
    rm -f "$sb" "$lb"; return 0
  fi

  if [ "$apply" != yes ]; then
    printf '  드리프트 PR %-4s %s 이 원문과 다르다\n' "$n" "$what"
    rm -f "$sb" "$lb"; return 1
  fi

  if gh pr edit "$n" --title "$st" --body-file "$sb" >/dev/null 2>&1; then
    printf '  복원   PR %-4s %s\n' "$n" "$what"
  else
    printf '  실패   PR %-4s gh pr edit 실패\n' "$n"
    rc=2
  fi
  rm -f "$sb" "$lb"
  return $rc
}

main() {
  local apply=no all=no; local -a nums=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --apply) apply=yes ;;
      --all) all=yes ;;
      --self-test) self_test; return $? ;;
      -h|--help) usage; return 0 ;;
      [0-9]*) nums+=("$1") ;;
      *) printf '알 수 없는 인자: %s\n' "$1" >&2; usage >&2; return 2 ;;
    esac
    shift
  done

  cd "$ROOT"
  [ -f "$SNAPSHOT" ] || { printf '스냅샷이 없다: %s\n' "$SNAPSHOT" >&2; return 2; }

  if [ "$all" = yes ]; then
    [ ${#nums[@]} -eq 0 ] || { printf '%s\n' '--all 과 번호를 함께 줄 수 없다' >&2; return 2; }
    while IFS= read -r n; do nums+=("$n"); done < <(snap_numbers)
  fi
  [ ${#nums[@]} -gt 0 ] || { printf '되돌릴 PR 번호를 지정한다 (전체는 --all)\n' >&2; usage >&2; return 2; }

  printf '=== PR 원문 복원 (%s) ===\n\n' "$([ "$apply" = yes ] && echo 실행 || echo dry-run)"
  local same=0 drift=0 bad=0 rc
  for n in ${nums[@]+"${nums[@]}"}; do
    restore_one "$n" "$apply"; rc=$?
    case $rc in 0) same=$((same+1)) ;; 1) drift=$((drift+1)) ;; *) bad=$((bad+1)) ;; esac
  done
  printf '\n원문과 동일 %d · %s %d · 처리 불가 %d\n' "$same" "$([ "$apply" = yes ] && echo 복원 || echo 드리프트)" "$drift" "$bad"
  [ "$apply" != yes ] && [ "$drift" -gt 0 ] && printf '실제 복원: scripts/pr-body-restore.sh --apply <번호>\n'
  return 0
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d)"

  cat > "$tmp/snap.json" <<'JSON'
[{"number":1,"title":"fix(a): 원래 제목","body":"원래 본문\n","state":"MERGED","url":"x"},
 {"number":2,"title":"fix(b): 안 바뀐 제목","body":"그대로\n","state":"MERGED","url":"x"}]
JSON

  # gh 스텁. PR 1 은 드리프트, PR 2 는 원문과 같다. `--json body` 는 실제 gh 처럼 JSON 을
  # 내보낸다. 평문으로 흉내내면 끝 개행 손실(P2 가 잡은 결함)을 자기시험이 못 본다.
  # `--body-file` 로 넘어온 내용은 그대로 떠 둬 바이트 비교에 쓴다.
  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
num="$3"
case "$*" in
  *"--json title"*) [ "$num" = 1 ] && echo "fix(a): 고친 제목" || echo "fix(b): 안 바뀐 제목" ;;
  *"--json body"*)  [ "$num" = 1 ] && printf '{"body":"고친 본문\\n"}' || printf '{"body":"그대로\\n"}' ;;
  *edit*)
    echo "$num" >> "$EDITED"
    for ((i=1; i<=$#; i++)); do
      if [ "${!i}" = --body-file ]; then j=$((i+1)); cp "${!j}" "$SENT"; fi
    done
    ;;
esac
STUB
  chmod +x "$tmp/bin/gh"

  export PATH="$tmp/bin:$PATH" SNAPSHOT="$tmp/snap.json" EDITED="$tmp/edited" SENT="$tmp/sent"
  : > "$EDITED"

  check() {
    local label="$1" want="$2" got="$3"
    if printf '%s' "$got" | grep -q "$want"; then
      printf '  ok   %s\n' "$label"
    else
      printf '  FAIL %s — %s 를 찾지 못했다\n     받은 것: %s\n' "$label" "$want" "$got"; rc=1
    fi
  }

  # 빈 출력은 grep 으로 볼 수 없다. "편집이 한 번도 안 일어났다" 가 이 스크립트의 핵심
  # 안전 성질이므로 따로 판정한다.
  check_empty() {
    local label="$1" got="$2"
    if [ -z "$got" ]; then
      printf '  ok   %s\n' "$label"
    else
      printf '  FAIL %s — 비어 있어야 하는데 편집됨: %s\n' "$label" "$got"; rc=1
    fi
  }

  local out
  out="$("$ROOT/scripts/pr-body-restore.sh" 1 2 2>&1)"
  check 'dry-run 이 드리프트 1건과 동일 1건을 가른다' '원문과 동일 1 · 드리프트 1' "$out"
  check_empty 'dry-run 은 gh pr edit 를 부르지 않는다' "$(cat "$EDITED")"

  out="$("$ROOT/scripts/pr-body-restore.sh" --apply 1 2>&1)"
  check '--apply 가 드리프트를 되돌린다' '복원 1' "$out"
  check '--apply 가 그 PR 만 편집한다' '^1$' "$(cat "$EDITED")"

  : > "$EDITED"
  out="$("$ROOT/scripts/pr-body-restore.sh" --apply 2 2>&1)"
  check_empty '원문과 같으면 편집하지 않는다' "$(cat "$EDITED")"

  # 끝 개행 보존. `$(...)` 로 본문을 나르면 1바이트가 잘려 나간다. 실물 왕복(PR #1)이
  # 잡은 결함이라 여기에 못을 박는다.
  printf '원래 본문\n' > "$tmp/want"
  if cmp -s "$tmp/want" "$SENT"; then
    printf '  ok   %s\n' '복원 본문이 스냅샷과 바이트 동일하다 (끝 개행 포함)'
  else
    printf '  FAIL %s — 보낸 것 %s바이트, 스냅샷 %s바이트\n' '복원 본문 바이트 동일' \
      "$(wc -c < "$SENT" | tr -d ' ')" "$(wc -c < "$tmp/want" | tr -d ' ')"; rc=1
  fi

  out="$("$ROOT/scripts/pr-body-restore.sh" 2>&1)"; check '번호 없이는 거부한다' '되돌릴 PR 번호' "$out"
  out="$("$ROOT/scripts/pr-body-restore.sh" --all 1 2>&1)"; check '--all 과 번호를 섞으면 거부한다' '함께 줄 수 없다' "$out"
  out="$("$ROOT/scripts/pr-body-restore.sh" 99 2>&1)"; check '스냅샷에 없는 번호를 보고한다' '스냅샷에 없다' "$out"

  rm -rf "$tmp"
  printf '\n'
  [ $rc -eq 0 ] && printf 'self-test 통과\n' || printf 'self-test 실패\n'
  return $rc
}

main "$@"
