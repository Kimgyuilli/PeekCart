#!/usr/bin/env bash
# codex-review-render.sh 의 판정 로직이 의도대로 도는지. (D-027 ②)
#
# 전부 **실패를 주입해** 기대한 실패가 나오는지 본다. "렌더된다" 는 검증이 아니다.
# 핵심은 V1~V3 — 빈 응답 · items:null · 빈 배열 셋이 **서로 다른 결과**를 내야 한다.
# 이 셋이 같은 결과로 접히는 것이 이 작업이 고치려는 false-green 그 자체다.
#
#   codex-review-render-selftest.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RENDER="${ROOT}/scripts/codex-review-render.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0; skip=0

# check <이름> <기대exit> <파일> [기대문자열...]
check() {
  local name="$1" want="$2" file="$3"; shift 3
  local out rc
  out="$("$RENDER" "$file" 2>&1)"; rc=$?
  local bad=""
  [ "$rc" = "$want" ] || bad="exit ${rc} (기대 ${want})"
  local needle
  for needle in "$@"; do
    case "$out" in *"$needle"*) ;; *) bad="${bad}${bad:+, }'${needle}' 없음" ;; esac
  done
  if [ -z "$bad" ]; then
    printf '  ok   %s\n' "$name"; pass=$((pass+1))
  else
    printf '  FAIL %s — %s\n' "$name" "$bad"; fail=$((fail+1))
    printf '%s\n' "$out" | sed 's/^/        /' | head -8
  fi
}

# refute <이름> <기대exit> <파일> <있으면 안 되는 문자열>
#   종료 코드를 함께 본다. 출력 문자열만 보면 렌더러가 죽어 아무것도 못 찍어도 ok 가 된다.
refute() {
  local name="$1" want="$2" file="$3" needle="$4"
  local out rc
  out="$("$RENDER" "$file" 2>&1)"; rc=$?
  if [ "$rc" != "$want" ]; then
    printf '  FAIL %s — exit %s (기대 %s)\n' "$name" "$rc" "$want"; fail=$((fail+1)); return
  fi
  case "$out" in
    *"$needle"*) printf '  FAIL %s — '"'"'%s'"'"' 가 나오면 안 된다\n' "$name" "$needle"; fail=$((fail+1)) ;;
    *) printf '  ok   %s\n' "$name"; pass=$((pass+1)) ;;
  esac
}

item() { # <id> <sev> <cat> [extra-json]
  printf '{"id":%s,"severity":"%s","category":"%s","finding":"f%s","suggestion":"s%s"%s}' \
    "$1" "$2" "$3" "$1" "$1" "${4-}"
}

echo "=== codex-review-render 자체 검증 ==="

# V1 — 빈 응답. 실제 이력 44개가 이 형태다.
: > "${TMP}/plan-v1.json"
printf 'ERROR: You'"'"'ve hit your usage limit.\ntokens used\n78,700\n' > "${TMP}/plan-v1.stderr"
check 'V1 빈 응답 → exit 2 + usage limit 인용' 2 "${TMP}/plan-v1.json" 'usage limit' '리뷰 실패'
refute 'V1 빈 응답이 자동 통과로 새지 않는다' 2 "${TMP}/plan-v1.json" '자동 통과'

# V11 — 실제 codex stderr 형태. 원인 줄이 **파일 중간**에 있고 마지막 줄은 `}` 다.
#       "마지막 유의미한 줄" 휴리스틱이면 `}` 를 인용한다.
: > "${TMP}/diff-v11.json"
cat > "${TMP}/diff-v11.stderr" <<'ERRFIX'
hook: SessionStart Completed
ERROR: {
  "type": "error",
  "error": {
    "code": "invalid_json_schema",
    "message": "Missing 'scope' in required.",
    "param": "text.format.schema"
  },
  "status": 400
}
ERRFIX
check 'V11 여러 줄 ERROR 블록에서 message 를 뽑는다' 2 "${TMP}/diff-v11.json" "Missing 'scope' in required."
refute 'V11 이 닫는 중괄호를 인용하지 않는다' 2 "${TMP}/diff-v11.json" 'stderr: }'

# V12 — 원인 줄 뒤에 리뷰 대상 diff 내용이 echo 된 경우. 실측 44건 중 다수가 이 형태다.
: > "${TMP}/diff-v12.json"
printf 'ERROR: You'"'"'ve hit your usage limit.\n+  error::단일 peekcart 라벨 잔존\n' \
  > "${TMP}/diff-v12.stderr"
check 'V12 뒤따르는 diff 내용 대신 원인 줄을 인용한다' 2 "${TMP}/diff-v12.json" 'usage limit'
refute 'V12 가 diff 내용을 사유로 오인하지 않는다' 2 "${TMP}/diff-v12.json" '라벨 잔존'

# V2 — items: null. 실제 이력 1개가 이 형태다.
echo '{"run_id":"x","summary":"s","items":null}' > "${TMP}/plan-v2.json"
check 'V2 items:null → exit 2' 2 "${TMP}/plan-v2.json" '리뷰 실패'
refute 'V2 가 총 0건으로 렌더되지 않는다' 2 "${TMP}/plan-v2.json" '총 0건'

# V3 — 빈 배열. 모델은 돌았고 지적이 없었다. V1·V2 와 달라야 한다.
echo '{"run_id":"x","summary":"s","items":[]}' > "${TMP}/plan-v3.json"
check 'V3 빈 배열 → exit 0 + 자동 통과' 0 "${TMP}/plan-v3.json" '지적 0건 (자동 통과)' '총 0건'

# V4 — 깨진 JSON.
printf '{"items":' > "${TMP}/plan-v4.json"
check 'V4 깨진 JSON → exit 2' 2 "${TMP}/plan-v4.json" '파싱 실패'

# V5 — plan 형태 3건. file 컬럼이 없어야 한다.
printf '{"run_id":"r","summary":"s","items":[%s,%s,%s]}' \
  "$(item 1 P0 bug)" "$(item 2 P1 test)" "$(item 3 P2 doc)" > "${TMP}/plan-v5.json"
check 'V5 plan 3건 → 헤더 집계' 0 "${TMP}/plan-v5.json" '총 3건: P0 1 / P1 1 / P2 1' 'Codex 계획 리뷰'
refute 'V5 plan 리뷰에 위치 컬럼이 없다' 0 "${TMP}/plan-v5.json" '위치'

# V6 — diff 형태. file/line 이 있으면 위치 컬럼이 생긴다.
printf '{"run_id":"r","summary":"s","items":[%s,%s]}' \
  "$(item 1 P0 bug ',"file":"src/Foo.java","line":42')" \
  "$(item 2 P1 test ',"file":"src/Bar.java","line":7')" > "${TMP}/diff-v6.json"
check 'V6 diff → 위치 컬럼 + diff 라벨' 0 "${TMP}/diff-v6.json" '위치' 'src/Foo.java:42' 'Codex diff 리뷰'

# V7 — scope/disposition 전량 결측이면 컬럼을 내지 않는다. 실측상 1267건 전부 이 상태다.
refute 'V7 disposition 전량 결측 → 권장 컬럼 없음' 0 "${TMP}/plan-v5.json" '| 권장 |'
check 'V7 결측 사실을 각주로 알린다' 0 "${TMP}/plan-v5.json" 'suggested_disposition) 미기재'

# V8 — 한 건이라도 있으면 컬럼이 생기고 나머지는 '-'.
printf '{"run_id":"r","summary":"s","items":[%s,%s]}' \
  "$(item 1 P1 bug ',"suggested_disposition":"defer"')" "$(item 2 P2 doc)" \
  > "${TMP}/plan-v8.json"
check 'V8 1건만 있어도 권장 컬럼 생성' 0 "${TMP}/plan-v8.json" '| 권장 |' 'defer'
refute 'V8 에는 결측 각주가 붙지 않는다' 0 "${TMP}/plan-v8.json" '미기재'

# V10 — 긴 finding. 표 칸은 잘리고 전문 블록에는 전체가 실린다.
LONG="$(python3 -c 'print("가"*400)')"
printf '{"run_id":"r","summary":"s","items":[{"id":1,"severity":"P1","category":"bug","finding":"%s","suggestion":"s1"}]}' \
  "$LONG" > "${TMP}/plan-v10.json"
V10OUT="$("$RENDER" "${TMP}/plan-v10.json" 2>&1)"
V10TABLE="$(printf '%s\n' "$V10OUT" | grep '^| 1 |')"
V10FULL="$(printf '%s\n' "$V10OUT" | grep '^  발견:')"
if [ "${#V10TABLE}" -lt 120 ] && [ "${#V10FULL}" -gt 400 ]; then
  printf '  ok   V10 표 칸은 잘리고 전문은 온전하다 (표 %d자 · 전문 %d자)\n' \
    "${#V10TABLE}" "${#V10FULL}"; pass=$((pass+1))
else
  printf '  FAIL V10 표 %d자 (기대 <120) · 전문 %d자 (기대 >400)\n' \
    "${#V10TABLE}" "${#V10FULL}"; fail=$((fail+1))
fi

# V13 — items 가 배열이 아닌 falsey/객체 값. 라운드 1 리뷰가 잡은 false-green 경로다.
#        파이썬의 `or []` 가 이것들을 빈 배열로 접어 "지적 0건" 으로 새게 했다.
for bad in '{}' 'false' '0' '""' '"x"'; do
  printf '{"run_id":"x","summary":"s","items":%s}' "$bad" > "${TMP}/plan-v13.json"
  check "V13 items=${bad} → exit 2" 2 "${TMP}/plan-v13.json" '리뷰 실패'
  refute "V13 items=${bad} 가 자동 통과로 새지 않는다" 2 "${TMP}/plan-v13.json" '자동 통과'
done

# V14 — 값이 빠진 옵션. `shift 2` 만 믿으면 루프가 전진하지 않아 무한 루프가 된다.
#        exit 코드가 아니라 **끝나는지**를 본다.
for opt in --round --kind; do
  ( "$RENDER" "${TMP}/plan-v3.json" "$opt" >/dev/null 2>&1 ) &
  vp=$!
  for _ in 1 2 3; do kill -0 "$vp" 2>/dev/null || break; sleep 1; done
  if kill -0 "$vp" 2>/dev/null; then
    kill -9 "$vp" 2>/dev/null
    printf '  FAIL V14 %s 값 누락 — 3초 후에도 살아 있다 (무한 루프)\n' "$opt"; fail=$((fail+1))
  else
    wait "$vp"; vrc=$?
    if [ "$vrc" = 1 ]; then
      printf '  ok   V14 %s 값 누락 → exit 1\n' "$opt"; pass=$((pass+1))
    else
      printf '  FAIL V14 %s 값 누락 → exit %s (기대 1)\n' "$opt" "$vrc"; fail=$((fail+1))
    fi
  fi
done

# V15 — 잘못된 --kind 와 위치 인자 중복은 조용히 넘어가지 않는다.
# argv 가 케이스마다 달라 check() 를 쓰지 않고 직접 돌린다.
argcase() { # <이름> <기대exit> <기대문자열> <렌더러 인자...>
  local name="$1" want="$2" needle="$3"; shift 3
  local out rc
  out="$("$RENDER" "$@" 2>&1)"; rc=$?
  if [ "$rc" = "$want" ] && case "$out" in *"$needle"*) true ;; *) false ;; esac; then
    printf '  ok   %s\n' "$name"; pass=$((pass+1))
  else
    printf '  FAIL %s — exit %s (기대 %s) / 출력: %s\n' "$name" "$rc" "$want" "$(printf '%s' "$out" | head -1)"
    fail=$((fail+1))
  fi
}
argcase 'V15 잘못된 kind → exit 1' 1 '알 수 없는 --kind' "${TMP}/plan-v3.json" --kind nope
argcase 'V15 위치 인자 중복 → exit 1' 1 '위치 인자는 하나뿐' "${TMP}/plan-v3.json" "${TMP}/plan-v5.json"
argcase 'V15 인자 없음 → exit 1' 1 '사용:'
argcase 'V15 없는 파일 → exit 1' 1 '파일 없음' "${TMP}/nope.json"

# V9 — 실제 이력 회귀.
#
#   건수를 하드코딩하지 않는다. 초안은 238/44 를 박아두었는데, 리뷰를 한 번 더 돌리자마자
#   .cache 가 늘어 그 자리에서 깨졌다. 스냅샷은 계약이 아니다.
#   대신 **불변식**을 건다 — 종료 코드는 0 아니면 2 뿐이고(1=사용법 오류는 나오면 안 된다),
#   exit 2 인 집합은 "빈 파일 또는 파싱 불가 또는 items null" 과 정확히 일치해야 한다.
#   기대값을 렌더러가 아닌 별도 경로(test + jq)로 계산해 대조한다.
CACHE="${ROOT}/.cache/codex-reviews"
if [ -d "$CACHE" ] && ls "${CACHE}"/*.json >/dev/null 2>&1; then
  ok9=0; f9=0; mism=0; other9=0
  for f in "${CACHE}"/*.json; do
    # 기대값을 렌더러와 무관하게 계산한다.
    # 기대값은 렌더러와 **다른 표현**으로 계산한다. 같은 jq 식을 복붙하면 둘 다 틀려도
    # 통과한다 — 라운드 1에서 실제로 그랬다(items 가 객체여도 양쪽 다 정상으로 봤다).
    if [ ! -s "$f" ] || [ "$(jq -r 'try (.items|type) catch "ERR"' "$f" 2>/dev/null)" != "array" ]; then
      want=2
    else
      want=0
    fi
    "$RENDER" "$f" >/dev/null 2>&1; got=$?
    case "$got" in 0) ok9=$((ok9+1)) ;; 2) f9=$((f9+1)) ;; *) other9=$((other9+1)) ;; esac
    if [ "$got" != "$want" ]; then
      mism=$((mism+1))
      printf '       불일치: %s (기대 %s, 실제 %s)\n' "$(basename "$f")" "$want" "$got"
    fi
  done
  if [ "$mism" = 0 ] && [ "$other9" = 0 ]; then
    printf '  ok   V9 실제 이력 %d개 불변식 일치 — 정형 %d / 실패 %d\n' \
      "$((ok9+f9+other9))" "$ok9" "$f9"; pass=$((pass+1))
  else
    printf '  FAIL V9 불일치 %d건 · 예상 밖 exit %d건\n' "$mism" "$other9"; fail=$((fail+1))
  fi
else
  # 조용히 통과시키지 않는다. .cache/ 는 gitignore 대상이라 CI 에는 없다.
  printf '  skip V9 실제 이력 회귀 — %s 없음\n' "$CACHE"; skip=$((skip+1))
fi

printf '\n통과 %d · 실패 %d · 건너뜀 %d\n' "$pass" "$fail" "$skip"
[ "$fail" = 0 ]
