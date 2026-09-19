#!/usr/bin/env bash
# Codex 리뷰 JSON 을 severity 표로 렌더한다. (D-027 ②)
#
#   이 스크립트의 무게중심은 표가 아니라 **실패한 리뷰를 0건과 구분하는 것**이다.
#
# `/plan` §6 과 `/work` §6 은 "P0/P1 이 0건이면 자동 통과" 라고 적혀 있다. 그런데
# 사용량 한도로 죽은 런의 결과도 0건이다. 실측(2026-09-20) 으로 `.cache/codex-reviews/`
# 의 JSON 282개 중 **45개(16%)가 그 상태**였다 — 빈 파일 44개 + `items: null` 1개이고,
# 동명 `.stderr` 에는 전부 `ERROR: You've hit your usage limit` 가 찍혀 있었다.
# 즉 "리뷰가 깨끗했다" 와 "리뷰가 아예 안 돌았다" 가 지금까지 구분되지 않았다. false-green 이다.
#
# 그래서 종료 코드를 셋으로 가른다. 호출처는 **2를 0으로 접으면 안 된다.**
#   0  정상 (지적 0건 포함)
#   1  사용법 오류 (인자 없음 · 파일 없음)
#   2  리뷰 실패 — 표를 렌더하지 않는다. 리뷰 상태는 `미결(리뷰 실패: ...)` 이다
#
# 선택 필드는 "있을 때만" 컬럼을 만든다. `file`/`line` 은 diff 리뷰에만 있고 plan 리뷰엔
# 없다. `scope` 와 `suggested_disposition` 은 **과거 이력 1267건 전부에서 결측**이었는데,
# 스키마의 `required` 에 없어서 Codex 가 내보내지 않았기 때문이다. 같은 작업에서
# `required` 를 고쳤으므로(P6) 새 리뷰부터는 채워져 온다. 과거 파일은 그대로 비어 있으니
# 전량 결측이면 컬럼을 내지 않는다 — 빈 칸 줄만 늘기 때문이다.
#
# 사용:
#   codex-review-render.sh <json> [--round N] [--kind plan|diff|adr]
#
#   --round  라운드 번호. JSON 에 없는 값이라 호출처가 준다. 없으면 헤더에서 생략
#   --kind   리뷰 종류. 없으면 파일명 접두사(plan-/diff-/adr-)에서 뽑는다
set -uo pipefail

JSON=""
ROUND=""
KIND=""

# 값이 필요한 옵션은 값이 실제로 있는지 먼저 본다. `shift 2` 는 인자가 하나뿐이면
# 실패만 하고(set -e 가 없다) 루프는 그대로 돌아 **무한 루프**가 된다. 리뷰에서 잡혔다.
need_value() {
  [ "$2" -ge 2 ] || { printf '%s 에 값이 필요합니다\n' "$1" >&2; exit 1; }
}

while [ $# -gt 0 ]; do
  case "$1" in
    --round) need_value "$1" $#; ROUND="$2"; shift 2 ;;
    --kind)  need_value "$1" $#; KIND="$2";  shift 2 ;;
    -h|--help)
      sed -n '/^# 사용:/,/^set /p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'
      exit 0 ;;
    -*) printf '알 수 없는 옵션: %s\n' "$1" >&2; exit 1 ;;
    *)
      [ -z "$JSON" ] || { printf '위치 인자는 하나뿐입니다: %s\n' "$1" >&2; exit 1; }
      JSON="$1"; shift ;;
  esac
done

case "${KIND}" in
  ''|plan|diff|adr) ;;
  *) printf '알 수 없는 --kind: %s (plan|diff|adr)\n' "$KIND" >&2; exit 1 ;;
esac

if [ -z "$JSON" ]; then
  printf '사용: codex-review-render.sh <json> [--round N] [--kind plan|diff|adr]\n' >&2
  exit 1
fi
if [ ! -f "$JSON" ]; then
  printf '파일 없음: %s\n' "$JSON" >&2
  exit 1
fi

# 종류는 파일명 접두사에서 뽑는다. 못 뽑으면 "리뷰" 로 둔다 — 표 내용에 영향이 없으므로
# 여기서 실패시키지 않는다.
if [ -z "$KIND" ]; then
  case "$(basename "$JSON")" in
    plan-*) KIND=plan ;;
    diff-*) KIND=diff ;;
    adr-*)  KIND=adr ;;
  esac
fi
case "$KIND" in
  plan) LABEL='Codex 계획 리뷰' ;;
  diff) LABEL='Codex diff 리뷰' ;;
  adr)  LABEL='Codex ADR 리뷰' ;;
  *)    LABEL='Codex 리뷰' ;;
esac
[ -n "$ROUND" ] && LABEL="${LABEL} 라운드 ${ROUND}"

# stderr_excerpt <json_path>
# 동명 .stderr 에서 실패 사유로 쓸 한 줄을 뽑는다.
#
#   "마지막 유의미한 줄" 휴리스틱은 실제 데이터에서 무너진다. 실측(2026-09-20) 으로
#   실패 44건의 stderr 마지막 줄은 `}` 이거나 **리뷰 대상 diff 에서 echo 된 내용**인 경우가
#   많았다. 원인 줄은 대개 파일 중간에 있다. 그래서 위치가 아니라 **패턴**으로 찾는다.
#
# 우선순위:
#   1. API 에러 본문의 "message" 필드 — `ERROR: {...}` 여러 줄 블록의 알맹이
#   2. 알려진 치명 마커가 있는 줄 (usage limit / at capacity / invalid_json_schema)
#   3. `ERROR:` 로 시작하는 줄
#   4. 마지막 비어 있지 않은 줄 (codex 의 `tokens used` 꼬리는 제외)
stderr_excerpt() {
  local err="${1%.json}.stderr"
  [ -f "$err" ] || { printf '(stderr 없음)\n'; return; }
  local line

  line="$(grep -oE '"message"[[:space:]]*:[[:space:]]*"[^"]+"' "$err" 2>/dev/null \
    | head -n 1 | sed 's/.*"message"[[:space:]]*:[[:space:]]*"//; s/"$//')"
  [ -n "$line" ] || line="$(grep -iE 'usage limit|at capacity|invalid_json_schema' "$err" 2>/dev/null | head -n 1)"
  [ -n "$line" ] || line="$(grep -E '^ERROR:' "$err" 2>/dev/null | head -n 1)"
  [ -n "$line" ] || line="$(grep -v '^[[:space:]]*$' "$err" 2>/dev/null \
    | grep -vE '^[[:space:]]*(tokens used|[0-9,]+)[[:space:]]*$' | tail -n 1)"
  [ -n "$line" ] || line='(stderr 에서 원인 줄을 찾지 못함)'

  # 한 줄로 접고 길이를 제한한다. 원문은 파일에 있으니 여기서 자른다고 잃는 게 없다.
  printf '%s\n' "$line" | tr '\n' ' ' | cut -c1-300
}

fail_review() {
  printf '=== %s — 리뷰 실패 ===\n' "$LABEL"
  printf '원인: %s\n' "$1"
  printf '파일: %s\n' "$JSON"
  printf 'stderr: %s\n' "$(stderr_excerpt "$JSON")"
  printf '\n지적 0건이 아니다. 리뷰 상태는 `미결(리뷰 실패)` 이고, 통과로 집계하지 않는다.\n'
  exit 2
}

# --- 실패 판별 셋. 전부 exit 2 로 모은다. ---
[ -s "$JSON" ] || fail_review '빈 응답 (0바이트)'
jq -e . "$JSON" >/dev/null 2>&1 || fail_review 'JSON 파싱 실패'
# 타입까지 본다. `items` 가 객체(`{}`)나 `false` 여도 파이썬 쪽에서 `or []` 로 접혀
# **빈 배열과 구별되지 않는다** — 실패가 "지적 0건" 으로 새는 바로 그 경로다. 리뷰에서 잡혔다.
jq -e '(.items | type) == "array"' "$JSON" >/dev/null 2>&1 \
  || fail_review 'items 가 배열이 아님 (null / 객체 / 누락)'

python3 - "$JSON" "$LABEL" <<'PY'
import io, json, sys

path, label = sys.argv[1], sys.argv[2]
doc = json.load(io.open(path, encoding='utf-8'))
items = doc.get('items') or []

order = ['P0', 'P1', 'P2']
counts = {s: 0 for s in order}
for it in items:
    sev = it.get('severity')
    if sev in counts:
        counts[sev] += 1

print('=== %s (총 %d건: %s) ===' % (
    label, len(items), ' / '.join('%s %d' % (s, counts[s]) for s in order)))
run_id = doc.get('run_id')
if run_id:
    print('run_id: %s' % run_id)
summary = (doc.get('summary') or '').strip()
if summary:
    print('요약: %s' % ' '.join(summary.split()))

if not items:
    # 여기 오는 것은 items 가 **빈 배열**일 때뿐이다. null 과 빈 파일은 위에서 exit 2 로
    # 걸렀으므로, 이 줄은 "모델이 돌았고 지적이 없었다" 만 의미한다.
    print()
    print('지적 0건 (자동 통과)')
    sys.exit(0)

def cell(v):
    if v is None:
        return '-'
    # 마크다운 표가 깨지지 않도록 줄바꿈을 접고 파이프를 이스케이프한다.
    return ' '.join(str(v).split()).replace('|', '\\|') or '-'


def clip(v, n=70):
    """표 칸은 훑는 용도다. 전문은 아래 블록에 그대로 실리므로 여기서 잘라도 잃는 게 없다.

    실측: 실제 이력의 finding 은 평균 400자여서, 자르지 않으면 한 행이 화면을 몇 배
    넘기고 '훑으면서 처분을 고른다'는 표의 용도 자체가 죽는다.
    """
    s = cell(v)
    return s if len(s) <= n else s[:n - 1] + '…'


# 선택 필드는 한 건이라도 채워져 있을 때만 컬럼을 만든다.
has_loc = any(it.get('file') for it in items)
has_scope = any(it.get('scope') for it in items)
has_disp = any(it.get('suggested_disposition') for it in items)


def loc(it):
    return '%s:%s' % (cell(it.get('file')), it.get('line', '?'))


cols = [('id', lambda it: cell(it.get('id'))),
        ('sev', lambda it: cell(it.get('severity'))),
        ('category', lambda it: cell(it.get('category')))]
if has_loc:
    cols.append(('위치', loc))
if has_scope:
    cols.append(('scope', lambda it: cell(it.get('scope'))))
if has_disp:
    cols.append(('권장', lambda it: cell(it.get('suggested_disposition'))))
cols.append(('발견', lambda it: clip(it.get('finding'))))

rank = {s: i for i, s in enumerate(order)}
rows = sorted(items, key=lambda it: (rank.get(it.get('severity'), 9), it.get('id') or 0))

print()
print('| %s |' % ' | '.join(h for h, _ in cols))
print('|%s|' % '|'.join('---' for _ in cols))
for it in rows:
    print('| %s |' % ' | '.join(f(it) for _, f in cols))

print()
print('--- 항목 전문 ---')
for it in rows:
    head = '[%s] %s · %s' % (cell(it.get('id')), cell(it.get('severity')),
                             cell(it.get('category')))
    if it.get('file'):
        head += ' · %s' % loc(it)
    if it.get('scope'):
        head += ' · %s' % cell(it.get('scope'))
    if it.get('suggested_disposition'):
        head += ' · 권장 %s' % cell(it.get('suggested_disposition'))
    print()
    print(head)
    print('  발견: %s' % cell(it.get('finding')))
    print('  제안: %s' % cell(it.get('suggestion')))

if not has_disp:
    print()
    print('> 권장 처분(suggested_disposition) 미기재 — 스키마 `required` 확장 이전에 생성된 '
          '응답이다. 처분은 전부 사람이 판정한다.')
PY
