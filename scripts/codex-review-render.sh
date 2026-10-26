#!/usr/bin/env bash
# Codex 리뷰 JSON 을 severity 표로 렌더한다. (D-027 ②)
#
#   이 스크립트의 무게중심은 표가 아니라 **실패한 리뷰를 0건과 구분하는 것**이다.
#
# `/plan` §6 과 `/work` §6 은 "P0/P1 이 0건이면 자동 통과" 라고 적혀 있다. 그런데
# 실패한 런의 결과도 0건으로 오인될 수 있다. 당시 계획 정정 기록(2026-09-20)은
# 특정 시점 JSON 282개 중 빈 파일44개/null0개, usage limit9개를 보고한다.
# 현재 원본 cache corpus가 없어 전수 수치/원인 분류를 재검증한 결과는 아니다.
# 모집단·시점·초기45/null1 정정 근거는 docs/evidence/D-104/README.md 참조.
#
# 그래서 종료 코드를 셋으로 가른다. 호출처는 **2를 0으로 접으면 안 된다.**
#   0  정상 (지적 0건 포함)
#   1  사용법 오류 (인자 없음 · 파일 없음)
#   2  리뷰 실패 — 표를 렌더하지 않는다. 리뷰 상태는 `미결(리뷰 실패: ...)` 이다
#
# 선택 필드는 "있을 때만" 컬럼을 만든다. `file`/`line` 은 diff 리뷰에만 있고 plan 리뷰엔
# 없다. 당시 초기 집계는1267항목의 scope/disposition 결측을 보고했지만 원raw는 없다.
# required 누락400과 수정 후 필드를 채운 단일 schema probe는 당시 작업기록에 있다.
# 그 probe로 과거1267개 결측의 원인 전체를 확정하지 않는다. 과거 선택 필드 생략은
# 호환을 위해 허용하고 전량 결측이면 컬럼을 내지 않는다(D-103/D-104).
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
#   "마지막 유의미한 줄" 휴리스틱을 반증한 당시 작업기록(2026-09-20)은 schema400의 닫는 `}` 및 뒤에 echo된 diff 내용의 오인 사례를 보인다.
#   원raw 전수는 현재 없으므로44건의 빈도 일반화는 하지 않는다. 위치 대신 패턴으로 찾는다.
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

# 생산자 envelope/core 계약을 렌더 전에 검사한다. 과거 선택 필드 생략은 허용한다.
[ -s "$JSON" ] || fail_review '빈 응답 (0바이트)'
rendered="$(python3 - "$JSON" "$LABEL" <<'PYRENDER'
import io, json, sys
from decimal import Decimal, InvalidOperation

path, label = sys.argv[1], sys.argv[2]

def reject_constant(value):
    raise ValueError('non-JSON constant: ' + value)

try:
    with io.open(path, encoding='utf-8') as source:
        doc = json.load(source, parse_float=Decimal, parse_constant=reject_constant)
except (OSError, UnicodeError, ValueError, RecursionError, InvalidOperation):
    print('JSON 파싱 실패')
    sys.exit(2)


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def string(value):
    if not isinstance(value, str):
        return False
    value.encode('utf-8')
    return True


def integer(value, minimum):
    # JSON Schema integer에는 1.0도 속한다. bool은 JSON boolean이므로 제외한다.
    return type(value) in (int, Decimal) and value >= minimum and (
        type(value) is int or (value.is_finite() and value == value.to_integral_value()))

try:
    require(isinstance(doc, dict), '결과가 object가 아님')
    require(set(doc) == {'run_id', 'summary', 'items'}, '필수 metadata 누락 또는 알 수 없는 필드')
    require(string(doc['run_id']) and string(doc['summary']), 'run_id/summary가 string이 아님')
    require(isinstance(doc['items'], list), 'items가 배열이 아님')
    required = {'id', 'severity', 'category', 'finding', 'suggestion'}
    optional = {'file', 'line', 'scope', 'suggested_disposition'}
    enums = {'severity': {'P0', 'P1', 'P2'},
             'category': {'architecture', 'bug', 'security', 'test', 'doc', 'style', 'convention'},
             'scope': {'in_scope', 'out_of_scope', 'new_surface'},
             'suggested_disposition': {'reflect', 'defer', 'reject'}}
    for index, item in enumerate(doc['items']):
        prefix = 'items[%d]: ' % index
        require(isinstance(item, dict), prefix + 'object가 아님')
        require(required <= set(item) <= required | optional, prefix + '필수 필드 누락 또는 알 수 없는 필드')
        require(integer(item['id'], 1), prefix + 'id가 양의 integer가 아님')
        for key in ('finding', 'suggestion', 'file'):
            if key in item:
                require(string(item[key]), prefix + key + '가 string이 아님')
        if 'line' in item:
            require(integer(item['line'], 0), prefix + 'line이 0 이상 integer가 아님')
        for key, choices in enums.items():
            if key in item:
                require(string(item[key]) and item[key] in choices, prefix + key + '가 허용 enum이 아님')
except ValueError as error:
    print('결과 계약 위반: ' + str(error))
    sys.exit(2)

items = doc['items']

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
    # 검증된 정형 결과의 빈 배열이다. 파일 형식만으로 실제 모델 실행을 증명하지 않는다.
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

    긴 finding이 표 폭을 늘리는 것을 막는다. 과거 평균400자 보고의 원자료는
    현재 없으므로 여기서는 길이 평균을 검증 근거로 삼지 않는다.
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
    print('> 권장 처분(suggested_disposition) 미기재 — 과거 형식과의 호환을 위해 생략을 허용한다. '
          '처분은 전부 사람이 판정한다.')
PYRENDER
)"
render_rc=$?
[ "$render_rc" = 0 ] || fail_review "${rendered:-결과 처리 실패 (exit $render_rc)}"
printf '%s\n' "$rendered"
