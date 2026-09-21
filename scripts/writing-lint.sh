#!/usr/bin/env bash
# 커밋 / PR 문체 lint — 금지 문자 · 제목 형식 · 볼드 밀도 · 귀속 트레일러.
#
#   docs/conventions/writing.md 가 정본이고 이 스크립트는 그중 기계로 검사 가능한 부분만 본다.
#
# 대상은 커밋 메시지와 PR 본문뿐이다. 설계 문서와 코드 주석은 검사하지 않는다.
# docs/TASKS.md 의 상태 이모지가 예외로 남는 이유도 이것이다 — 애초에 대상이 아니다.
#
# 오류(exit 1): 금지 문자, 귀속 트레일러, 제목 형식 위반,
#              PR 본문 어미(~습니다), PR 본문 볼드 과다
# 경고(exit 0): 제목 길이, 서술 종결(제목), 추적 태그 위치, 커밋 볼드 과다
#
# 사용:
#   writing-lint.sh --commits <range>   커밋 메시지 검사 (예: origin/main..HEAD)
#   writing-lint.sh --file <path>       PR 본문 등 파일 하나 검사
#   writing-lint.sh --self-test         lint 자신이 위반을 잡는지 (fixture 조작)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# 판정기는 파일에 써서 인자로 호출한다. `python3 - <<PY` 로 두면 heredoc 이 파이썬의
# stdin 을 차지해 검사 대상 텍스트가 도달하지 못하고 전부 통과한다(self-test 가 잡은 실제 버그).
PYSRC="$(mktemp -t writing-lint.XXXXXX)"
trap 'rm -f "$PYSRC"' EXIT
cat > "$PYSRC" <<'PY'
import io, re, sys

label, mode, path = sys.argv[1], sys.argv[2], sys.argv[3]
text = io.open(path, encoding='utf-8').read()

# 금지 문자. 각 항목은 (정규식, 사람이 읽는 이름, 대체 안내).
BANNED = [
    (re.compile(u'[—–]'), 'em/en dash', '쉼표나 마침표로 문장을 나눈다'),
    (re.compile(u'[←-⇿➡]'), '화살표', '"A 에서 B 로" 처럼 말로 푼다'),
    (re.compile(u'[∀-⋿×]'), '수학 기호', '"이상", "배", "그리고" 로 푼다'),
    (re.compile(u'[\U0001F000-\U0001FAFF☀-➿️]'), '이모지', '완료/대기 같은 텍스트로 쓴다'),
]
ATTRIB = re.compile(r'(?im)^\s*(Claude-Session\s*:|Co-Authored-By\s*:.*Claude|Generated with)')
TITLE_FORM = re.compile(r'^(feat|fix|refactor|test|docs|chore)(\([^)]+\))?: .+')

errors, warnings = [], []
lines = text.splitlines()

# 코드펜스 안은 검사하지 않는다. 명령 출력과 로그를 그대로 인용한 자리라 화살표나
# 이모지가 들어 있는 것이 정상이고, 고치면 인용이 사실과 달라진다.
# 실측: PR 백업 124개에서 펜스 안 93건(23개 PR), 펜스 밖 2377건.
fenced = set()
inside = False
for i, line in enumerate(lines, 1):
    if line.lstrip().startswith('```'):
        inside = not inside
        fenced.add(i)
        continue
    if inside:
        fenced.add(i)

for rx, name, hint in BANNED:
    for i, line in enumerate(lines, 1):
        if i in fenced:
            continue
        for m in rx.finditer(line):
            errors.append('%s:%d 금지 문자 %s (%r) — %s' % (label, i, name, m.group(), hint))

for i, line in enumerate(lines, 1):
    if ATTRIB.search(line):
        errors.append('%s:%d 귀속 트레일러 — 붙이지 않는다: %s' % (label, i, line.strip()))

if mode == 'title_body' and lines:
    title = lines[0].strip()
    if not TITLE_FORM.match(title):
        errors.append('%s:1 제목 형식 위반 — "<type>(<scope>): <내용>" 이어야 한다: %s' % (label, title))
    if len(title) > 60:
        warnings.append('%s:1 제목 %d자 — 50자 내외로 줄인다' % (label, len(title)))
    subject = title.split(': ', 1)[1] if ': ' in title else title
    if re.search(r'(다|다\.|니다|니다\.)$', subject):
        warnings.append('%s:1 서술 종결 — 명사형으로 끝낸다: %s' % (label, subject))
    if re.search(r'\((?:[A-Z]{2,}-\d+|#\d+)[^)]*\)\s*$', subject):
        warnings.append('%s:1 추적 태그가 제목에 있다 — 본문 마지막 줄로 옮긴다' % label)
    if len(lines) > 1 and lines[1].strip():
        errors.append('%s:2 제목 다음 줄은 비운다' % label)

# 어미. PR 본문은 "~습니다" 로 고정한다(writing.md §3 첫 줄).
#
# 왜 오류인가: 이 규칙이 lint 에 없어서 PR #129 본문이 46문장 전부 "~다." 로 나갔고,
# lint 는 통과를 냈다. 검사기가 규약을 덮지 않는데 초록불을 규약 준수로 읽은 사고다.
# 기존 `서술 종결` 검사는 `mode == 'title_body'` 안에 있어 **커밋 제목 전용**이었다.
#
# body 모드에서만 돈다. 커밋 본문은 "~함/~임" 이라 같은 규칙을 적용할 수 없다.
if mode == 'body':
    # 표·헤딩·목록 표지·인용은 문장이 아니다. 펜스는 위에서 이미 걸러진다.
    SENT_END = re.compile(r'([\uac00-\ud7a3A-Za-z0-9)\]`"\']+)\ub2e4\.(?=\s|$)')

    def is_polite(frag):
        # 경어체는 어간에 "-ㅂ니다" 가 붙는다. 목록으로는 못 덮는다 —
        # 습니다/입니다뿐 아니라 탑니다·아닙니다·깨집니다·걸립니다가 전부 같은 형태다.
        # 판정은 "니다." 로 끝나고 그 앞 음절의 **종성이 ㅂ**(종성 index 17)인가로 한다.
        # 이렇게 하면 평서형 "아니다." 는 걸리고(니 는 종성 없음) 경어체는 통과한다.
        if not frag.endswith(u'\ub2c8\ub2e4.') or len(frag) < 4:
            return False
        ch = frag[-4]
        if not (u'\uac00' <= ch <= u'\ud7a3'):
            return False
        return (ord(ch) - 0xAC00) % 28 == 17

    for i, line in enumerate(lines, 1):
        if i in fenced:
            continue
        st = line.strip()
        if not st or st.startswith('#') or st.startswith('|') or st.startswith('>'):
            continue
        for m in SENT_END.finditer(line):
            if is_polite(line[:m.end()]):
                continue
            errors.append(u'%s:%d \uc5b4\ubbf8 — PR \ubcf8\ubb38\uc740 "~\uc2b5\ub2c8\ub2e4" \ub85c \ub05d\ub0b8\ub2e4: ...%s'
                          % (label, i, m.group(0)))

# 볼드 밀도. 헤딩으로 구획을 나누고 구획당 2개를 넘으면 경고한다.
section, count, start = '(문서 시작)', 0, 1
def flush(sec, cnt, ln):
    # writing.md §3 은 "한 섹션에 최대 2개" 로 **규정**한다. 경고로 두면 넘어간다.
    # 실제로 PR #129 가 경고 3건을 남긴 채 통과했다. PR 본문에서는 오류다.
    # 커밋 메시지는 경고로 남긴다. 제목+본문 구조라 구획 개념이 약하다.
    if cnt > 2:
        msg = '%s:%d 볼드 %d개 — 구획당 2개 이하로 줄인다 (%s)' % (label, ln, cnt, sec)
        (errors if mode == 'body' else warnings).append(msg)
for i, line in enumerate(lines, 1):
    if line.startswith('#'):
        flush(section, count, start)
        section, count, start = line.strip(), 0, i
    else:
        count += len(re.findall(r'\*\*[^*\n]+\*\*', line))
flush(section, count, start)

for e in errors:
    print('오류  %s' % e)
for w in warnings:
    print('경고  %s' % w)
sys.exit(1 if errors else 0)
PY

# check_text <label> <mode:title_body|body> <path>
check_text() {
  python3 "$PYSRC" "$1" "$2" "$3"
}

lint_commits() {
  local range="$1" rc=0 hash subject
  if ! git rev-parse "$range" >/dev/null 2>&1; then
    echo "오류  범위를 해석할 수 없다: $range" >&2
    return 2
  fi
  local hashes msg
  hashes="$(git log --format='%H' "$range")"
  if [ -z "$hashes" ]; then
    echo "검사 대상 커밋 0개 ($range)"
    return 0
  fi
  msg="$(mktemp -t writing-lint-msg.XXXXXX)"
  local skipped=0
  while IFS= read -r hash; do
    [ -n "$hash" ] || continue
    # 머지 커밋은 건너뛴다. 제목이 구조적으로 정해지는 자리라 <type>(<scope>) 규약 대상이
    # 아니다. 오탐이 잦으면 lint 자체를 무시하게 된다.
    if [ "$(git rev-list --parents -n1 "$hash" | wc -w | tr -d ' ')" -gt 2 ]; then
      skipped=$((skipped+1)); continue
    fi
    subject="$(git log -1 --format='%s' "$hash")"
    git log -1 --format='%B' "$hash" > "$msg"
    if ! check_text "${hash:0:7}" title_body "$msg"; then
      rc=1
      echo "      ^ $subject"
    fi
  done <<EOF
$hashes
EOF
  rm -f "$msg"
  [ "$rc" -eq 0 ] && echo "커밋 문체 lint 통과 ($(printf '%s\n' "$hashes" | grep -c .)건 중 검사 $(( $(printf '%s\n' "$hashes" | grep -c .) - skipped ))건, 머지 ${skipped}건 제외)"
  return "$rc"
}

lint_file() {
  local path="$1"
  if [ ! -f "$path" ]; then
    echo "오류  파일이 없다: $path" >&2
    return 2
  fi
  if check_text "$path" body "$path"; then
    echo "본문 문체 lint 통과 ($path)"
    # 통과는 규약 준수가 아니다. 아래 셋은 기계로 판정할 수 없어 검사에서 빠져 있다.
    # writing.md §6 에도 적혀 있으나 그 문서는 자동으로 읽히지 않는다. 판단이 일어나는
    # 자리에 띄우는 편이 싸고 확실하다 — PR #129 가 lint 통과를 규약 준수로 읽고 나갔다.
    cat <<'UNCOVERED'
  검사에 없음, 눈으로 볼 것 (writing.md §6)
    1. 수사·반전 금지 — 섹션 제목이 주장이 아니라 대상을 가리키는가
    2. 시행착오·자기 서사 배제 — 그것은 audit 몫이다
    3. 문단 3~4줄, 검증 안 한 인과를 단정하지 않았는가
UNCOVERED
    return 0
  fi
  return 1
}

self_test() {
  local tmp rc=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  expect_fail() {
    local name="$1" file="$2" mode="$3"
    if check_text "$name" "$mode" "$file" >/dev/null 2>&1; then
      echo "self-test 실패: '$name' 위반을 잡지 못했다"
      rc=1
    else
      echo "  ok  $name"
    fi
  }
  expect_pass() {
    local name="$1" file="$2" mode="$3"
    if check_text "$name" "$mode" "$file" >/dev/null 2>&1; then
      echo "  ok  $name"
    else
      echo "self-test 실패: '$name' 가 오탐으로 걸렸다"
      check_text "$name" "$mode" "$file"
      rc=1
    fi
  }

  printf 'fix(product): 락 제거 \xe2\x80\x94 버전으로 통일\n' > "$tmp/emdash"
  expect_fail "em dash" "$tmp/emdash" title_body

  printf 'fix(product): 락 제거\n\n배속이 \xc3\x972.31 로 나옴\n' > "$tmp/times"
  expect_fail "곱셈 기호" "$tmp/times" title_body

  printf 'fix(product): 락 제거\n\nA \xe2\x86\x92 B 로 바뀜\n' > "$tmp/arrow"
  expect_fail "화살표" "$tmp/arrow" title_body

  printf 'fix(product): 락 제거\n\n\xe2\x9c\x85 완료\n' > "$tmp/emoji"
  expect_fail "이모지" "$tmp/emoji" title_body

  printf 'fix(product): 락 제거\n\n본문\n\nClaude-Session: https://claude.ai/code/session_x\n' > "$tmp/attrib"
  expect_fail "귀속 트레일러" "$tmp/attrib" title_body

  printf '락을 제거했다\n' > "$tmp/noform"
  expect_fail "제목 형식 위반" "$tmp/noform" title_body

  # 어미. PR #129 가 이 검사 부재로 46문장 전부 "~다." 로 나갔다.
  printf '## Why\n\n배속이 오르지 않았다.\n' > "$tmp/ending"
  expect_fail "PR 본문 어미" "$tmp/ending" body

  printf '## Why\n\n배속이 오르지 않았습니다.\n' > "$tmp/ending_ok"
  expect_pass "PR 본문 어미(정상)" "$tmp/ending_ok" body

  # 종성 판정이 경어체를 목록 없이 알아보는가. 초안은 5개 목록이라 전부 오탐이었다.
  printf '## Why\n\n버스를 탑니다. 그것이 아닙니다. 캐시가 깨집니다. 락이 걸립니다.\n' > "$tmp/ending_bp"
  expect_pass "PR 본문 어미(ㅂ니다 활용형)" "$tmp/ending_bp" body

  # 평서형 "아니다." 는 걸려야 한다. "니다." 로 끝나지만 앞 음절에 종성이 없다.
  printf '## Why\n\n그것은 문제가 아니다.\n' > "$tmp/ending_plain"
  expect_fail "PR 본문 어미(아니다 평서형)" "$tmp/ending_plain" body

  # 커밋 본문은 "~함/~임" 이라 같은 규칙을 적용하지 않는다.
  printf 'fix(product): 락 제거\n\n배속이 오르지 않았다.\n' > "$tmp/ending_commit"
  expect_pass "커밋 본문은 어미 검사 제외" "$tmp/ending_commit" title_body

  # 표와 헤딩과 인용은 문장이 아니다. 오탐이 나면 안 된다.
  printf '## Why\n\n| a | b |\n|---|---|\n| 되돌림 오차가 작다 | 확정한다 |\n\n> 인용이다\n\n설명입니다.\n' > "$tmp/ending_tbl"
  expect_pass "표·인용은 어미 검사 제외" "$tmp/ending_tbl" body

  # 볼드. PR 본문에서는 오류다.
  printf '## Why\n\n**하나** **둘** **셋** 입니다.\n' > "$tmp/bold_body"
  expect_fail "PR 본문 볼드 과다" "$tmp/bold_body" body

  printf 'fix(product): 락 제거\n바로 본문\n' > "$tmp/noblank"
  expect_fail "제목 다음 빈 줄 없음" "$tmp/noblank" title_body

  printf 'fix(product): 재고 동시성 제어를 낙관적 락으로 단일화\n\n분산 락을 제거하고 버전 기반 제어로 통일함.\n\nRefs: D-025\n' > "$tmp/good"
  expect_pass "정상 커밋" "$tmp/good" title_body

  printf '## Why\n\n분산 락을 제거했습니다.\n\n## What\n\n계획서 P1 부터 P13 까지 전부입니다.\n' > "$tmp/goodbody"
  expect_pass "정상 PR 본문" "$tmp/goodbody" body

  # 코드펜스 안의 인용 출력은 통과해야 한다 (명령 로그에 화살표와 이모지가 정상적으로 들어간다)
  printf '## Test plan\n\n```\nA \xe2\x86\x92 B \xe2\x9c\x85 \xe2\x80\x94 done\n```\n\n본문입니다.\n' > "$tmp/fence"
  expect_pass "코드펜스 안 인용" "$tmp/fence" body

  # 펜스가 닫힌 뒤는 다시 검사 대상이다
  printf '```\nA \xe2\x86\x92 B\n```\n\n밖에서 \xe2\x86\x92 쓰면 걸려야 합니다.\n' > "$tmp/afterfence"
  expect_fail "펜스 밖은 검사" "$tmp/afterfence" body

  return "$rc"
}

case "${1:-}" in
  --commits) [ $# -ge 2 ] || { usage; exit 2; }; lint_commits "$2" ;;
  --file)    [ $# -ge 2 ] || { usage; exit 2; }; lint_file "$2" ;;
  --self-test) self_test ;;
  -h|--help|"") usage; exit 0 ;;
  *) echo "알 수 없는 인자: $1" >&2; usage; exit 2 ;;
esac
