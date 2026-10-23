#!/usr/bin/env bash
# saga-contract-matrix-lint 의 self-test (계획 P15)
#
# 게이트가 **위반을 실제로 잡는지** fixture 로 확인한다. 이게 없으면 lint 를 무력화하는
# 수정(예: 검사 분기를 조용히 지우기)이 전부 통과한다 — 이 프로젝트가 반복해서 겪은
# false-green 의 구조 그대로다.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT="scripts/saga-contract-matrix-lint.sh"
MATRIX="docs/plans/fixtures/saga-contract-matrix.tsv"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fails=0

# $1=이름 $2=기대(pass|fail) $3.. = 실행할 명령
expect() {
  local name="$1" want="$2"; shift 2
  local out rc
  set +e
  out="$("$@" 2>&1)"; rc=$?
  set -e
  if [[ "$want" == "pass" && $rc -eq 0 ]] || [[ "$want" == "fail" && $rc -ne 0 ]]; then
    echo "  ok   [$name]"
  else
    echo "  FAIL [$name] 기대=$want 실제 rc=$rc"
    echo "$out" | sed 's/^/         /' | head -5
    fails=$((fails + 1))
  fi
}

# 매트릭스를 python 으로 변형한다. $1=출력경로 $2=python 식(rows 를 조작)
mutate() {
  python3 - "$1" "$2" <<'PYEOF'
import sys
out, expr = sys.argv[1], sys.argv[2]
with open("docs/plans/fixtures/saga-contract-matrix.tsv", encoding="utf-8") as f:
    lines = [ln.rstrip("\n") for ln in f if ln.strip()]
header, rows = lines[0], [ln.split("\t") for ln in lines[1:]]
exec(expr, {"rows": rows, "header": header})
with open(out, "w", encoding="utf-8") as f:
    f.write(header + "\n")
    for r in rows:
        f.write("\t".join(r) + "\n")
PYEOF
}

structure() { SAGA_MATRIX="$1" bash "$LINT" --structure; }

echo "saga-contract-matrix-lint self-test"

# ---- 양성: 정본은 구조 검사를 통과해야 한다 (P17 산출 전에는 path 2건이 없으므로
#      그 상태에서는 fail 이 정상이다. 여기서는 '정본 그대로' 의 결과를 고정하지 않고
#      아래 조작 검사들이 **정본 대비 추가로** 실패하는지를 본다.)

# ---- 구조 훼손 6종 -------------------------------------------------------------
mutate "$TMP/dup-id.tsv" "rows.append(list(rows[0]))"
expect "id 중복" fail structure "$TMP/dup-id.tsv"

mutate "$TMP/bad-id.tsv" "rows[0][0] = 'not-a-saga-id'"
expect "id 형식 위반" fail structure "$TMP/bad-id.tsv"

mutate "$TMP/bad-type.tsv" "rows[0][1] = 'manual'"
expect "evidence_type 미허용값" fail structure "$TMP/bad-type.tsv"

mutate "$TMP/bad-path.tsv" "rows[0][2] = 'no/such/File.java'"
expect "path 가 실재하지 않음" fail structure "$TMP/bad-path.tsv"

mutate "$TMP/empty-fault.tsv" "rows[0][3] = '   '"
expect "fault 공백" fail structure "$TMP/empty-fault.tsv"

mutate "$TMP/dup-key.tsv" "rows[1][5] = rows[0][5]"
expect "evidence_key 중복" fail structure "$TMP/dup-key.tsv"

# ---- expected 문법 -------------------------------------------------------------
mutate "$TMP/prose.tsv" "rows[0][4] = '주문이 취소되어야 한다'"
expect "expected 가 자유 문장" fail structure "$TMP/prose.tsv"

mutate "$TMP/scalar.tsv" "rows[0][4] = '\"passed\"'"
expect "expected 최상위가 object 아님" fail structure "$TMP/scalar.tsv"

mutate "$TMP/unsorted.tsv" "rows[-1][4] = '{\"rows\":1,\"attempt_count\":1}'"
expect "expected key 미정렬(canonical 위반)" fail structure "$TMP/unsorted.tsv"

mutate "$TMP/spaced.tsv" "rows[-1][4] = '{\"attempt_count\": 1, \"rows\": 1}'"
expect "expected 공백 포함(canonical 위반)" fail structure "$TMP/spaced.tsv"

# ---- required-ID 정본이 lint 안에 있는가 (계획 N4) -------------------------------
mutate "$TMP/drop-required.tsv" "rows[:] = [r for r in rows if r[0] != 'SAGA-REFUND-CRASH-A']"
expect "필수 행 삭제(검사 대상 축소)" fail structure "$TMP/drop-required.tsv"

mutate "$TMP/drop-many.tsv" "rows[:] = rows[:1]"
expect "거의 전부 삭제" fail structure "$TMP/drop-many.tsv"

# 누락만 검사하면 **행 추가**로 매트릭스를 부풀릴 수 있다 — 정확 일치를 요구한다(계획 §7)
mutate "$TMP/extra-id.tsv" \
  "rows.append(['SAGA-MADE-UP-ROW','jvm','scripts/saga-contract-matrix-lint.sh','주입','{\"outcome\":\"passed\"}','com.example.X#SAGA-MADE-UP-ROW'])"
expect "정본에 없는 계약 ID 추가" fail structure "$TMP/extra-id.tsv"

# e2e 행을 REQUIRED_E2E_KEYS 등록 없이 추가하면 그 행만 축소 검사에서 빠진다
mutate "$TMP/unreg-e2e.tsv" \
  "rows.append(['SAGA-E2E-MADE-UP','e2e','scripts/e2e/saga_e2e.py','주입','{\"x\":1}','z.made_up'])"
expect "REQUIRED_E2E_KEYS 미등록 e2e 행" fail structure "$TMP/unreg-e2e.tsv"

# ---- expected 축소 차단 (diff 리뷰 #1) -----------------------------------------
# 행을 남긴 채 관측 항목만 지우는 우회. required-ID 만으로는 막지 못한다.
mutate "$TMP/shrink-e2e.tsv" \
  "[r.__setitem__(4, '{\"refund_rows\":1}') for r in rows if r[0] == 'SAGA-E2E-REFUND-CHAIN']"
expect "e2e expected 축소(필수 관측 키 제거)" fail structure "$TMP/shrink-e2e.tsv"

mutate "$TMP/shrink-a.tsv" \
  "[r.__setitem__(4, '{\"order_status\":\"CANCELLED\"}') for r in rows if r[0] == 'SAGA-E2E-PAYMENT-FAILED-CONVERGE']"
expect "e2e expected 축소(A 시나리오)" fail structure "$TMP/shrink-a.tsv"

# ---- P13 키 형식 ---------------------------------------------------------------
mutate "$TMP/key-noclass.tsv" "rows[0][5] = 'SAGA-REFUND-RESULT-ORDER-SUCCEEDED'"
expect "jvm 키에 FQCN 없음" fail structure "$TMP/key-noclass.tsv"

mutate "$TMP/key-mismatch.tsv" "rows[0][5] = rows[0][5].rsplit('#',1)[0] + '#SAGA-OTHER-ID'"
expect "키의 contract ID 가 id 와 불일치" fail structure "$TMP/key-mismatch.tsv"

mutate "$TMP/key-path.tsv" "rows[0][5] = 'order-service/src/Foo.java#' + rows[0][0]"
expect "키의 클래스명이 경로" fail structure "$TMP/key-path.tsv"

# ---- 열 구조 -------------------------------------------------------------------
python3 - "$TMP/short-col.tsv" <<'PYEOF'
import sys
with open("docs/plans/fixtures/saga-contract-matrix.tsv", encoding="utf-8") as f:
    lines = [ln.rstrip("\n") for ln in f if ln.strip()]
with open(sys.argv[1], "w", encoding="utf-8") as f:
    f.write(lines[0] + "\n")
    f.write("\t".join(lines[1].split("\t")[:4]) + "\n")
PYEOF
expect "열 개수 부족" fail structure "$TMP/short-col.tsv"

python3 - "$TMP/bad-header.tsv" <<'PYEOF'
import sys
with open("docs/plans/fixtures/saga-contract-matrix.tsv", encoding="utf-8") as f:
    lines = [ln.rstrip("\n") for ln in f if ln.strip()]
with open(sys.argv[1], "w", encoding="utf-8") as f:
    f.write("id\tkind\tpath\tfault\texpected\tevidence_key\n")
    f.write("\n".join(lines[1:]) + "\n")
PYEOF
expect "헤더 열 이름 변경" fail structure "$TMP/bad-header.tsv"

# ================================================================= JVM 증적 파서
# fixture JUnit XML 로 missing·failure·error·skipped·duplicate·중첩클래스·
# DisplayName 유무를 검사한다 (계획 P13·P15).

XML_DIR="$TMP/jvm"
mkdir -p "$XML_DIR"

# 이 검사만 쓰는 작은 매트릭스 — 정본 전체를 쓰면 required-ID 때문에 항상 실패한다.
mini() {  # $1=출력 $2=evidence_key
  {
    printf 'id\tevidence_type\tpath\tfault\texpected\tevidence_key\n'
    printf 'SAGA-FIXTURE-ONE\tjvm\tscripts/saga-contract-matrix-lint.sh\t주입\t{"outcome":"passed"}\t%s\n' "$2"
  } > "$1"
}

junit() {  # $1=파일 $2=classname $3=name $4=결과요소(빈문자열이면 성공)
  cat > "$XML_DIR/TEST-$1.xml" <<XMLEOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="어떤 표시명이든 무관하다" tests="1">
  <testcase classname="$2" name="$3">$4</testcase>
</testsuite>
XMLEOF
}

jvm() { SAGA_MATRIX="$1" bash "$LINT" --jvm-evidence "$XML_DIR"; }

mini "$TMP/m-one.tsv" 'com.example.FooTest#SAGA-FIXTURE-ONE'

rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 정상" ""
expect "jvm: 통과 증적" pass jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
expect "jvm: 증적 파일 없음(missing)" fail jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 실패" '<failure message="x">t</failure>'
expect "jvm: failure" fail jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 오류" '<error message="x">t</error>'
expect "jvm: error" fail jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 건너뜀" '<skipped/>'
expect "jvm: skipped" fail jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 통과본" ""
junit b "com.example.FooTest" "[SAGA-FIXTURE-ONE] 실패본" '<failure message="x">t</failure>'
expect "jvm: 같은 키가 상반된 결과(duplicate)" fail jvm "$TMP/m-one.tsv"

rm -f "$XML_DIR"/*.xml
junit a "com.example.OtherTest" "[SAGA-FIXTURE-ONE] 다른 클래스" ""
expect "jvm: 클래스가 다르면 증적이 아니다" fail jvm "$TMP/m-one.tsv"

# **같은 결과로 두 번 나와도 duplicate 다**(계획 P15·N5) — 한 contract ID 가 여러 테스트에
# 붙어 있으면 어느 테스트가 그 계약을 증명하는지 불확정이고, 하나를 지워도 통과한다.
rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 통과본 1" ""
junit b "com.example.FooTest" "[SAGA-FIXTURE-ONE] 통과본 2" ""
expect "jvm: 같은 키가 같은 결과로 중복" fail jvm "$TMP/m-one.tsv"

# **testsuite@name 은 키가 아니다** — 클래스 @DisplayName 으로 덮이기 때문이다(계획 P13).
# testcase@classname 만 맞으면 통과해야 한다.
rm -f "$XML_DIR"/*.xml
junit a "com.example.FooTest" "[SAGA-FIXTURE-ONE] 표시명이 한글이어도" ""
expect "jvm: testsuite@name 무관" pass jvm "$TMP/m-one.tsv"

# 중첩 클래스 Outer$Inner
mini "$TMP/m-nested.tsv" 'com.example.FooTest$Inner#SAGA-FIXTURE-ONE'
rm -f "$XML_DIR"/*.xml
junit a 'com.example.FooTest$Inner' "[SAGA-FIXTURE-ONE] 중첩" ""
expect "jvm: 중첩 클래스 Outer\$Inner" pass jvm "$TMP/m-nested.tsv"

# ================================================================= E2E manifest
MAN="$TMP/manifests"
mkdir -p "$MAN"

emini() {  # $1=출력 $2=expected $3=evidence_key
  {
    printf 'id\tevidence_type\tpath\tfault\texpected\tevidence_key\n'
    printf 'SAGA-FIXTURE-E2E\te2e\tscripts/e2e/saga_e2e.py\t주입\t%s\t%s\n' "$2" "$3"
  } > "$1"
}

manifest() {  # $1=scenario $2=result $3=evidence json $4=commit
  cat > "$MAN/manifest-$1.json" <<JEOF
{"run_id":"r","commit_sha":"$4","scenario_id":"$1","result":"$2","evidence":$3}
JEOF
}

e2e() { SAGA_MATRIX="$1" bash "$LINT" --e2e-evidence "$MAN"; }

emini "$TMP/e-ok.tsv" '{"a":1,"b":"x"}' 'z.thing'

manifest z success '{"thing":{"actual":{"a":1,"b":"x"}}}' ""
expect "e2e: 정확 일치" pass e2e "$TMP/e-ok.tsv"

# 같은 의미의 다른 표현(키 순서·공백)은 통과해야 한다 — canonical 비교이기 때문이다
manifest z success '{"thing":{"actual":{"b":"x", "a":1}}}' ""
expect "e2e: 키 순서 달라도 통과" pass e2e "$TMP/e-ok.tsv"

# 타입 불일치는 잡아야 한다 ("1" vs 1)
manifest z success '{"thing":{"actual":{"a":"1","b":"x"}}}' ""
expect "e2e: 타입 불일치(\"1\" vs 1)" fail e2e "$TMP/e-ok.tsv"

# subset 이 아니라 exact equality
manifest z success '{"thing":{"actual":{"a":1,"b":"x","extra":true}}}' ""
expect "e2e: 관측이 더 많음(subset 금지)" fail e2e "$TMP/e-ok.tsv"

manifest z success '{"thing":{"actual":{"a":1}}}' ""
expect "e2e: 관측이 모자람" fail e2e "$TMP/e-ok.tsv"

manifest z failure '{"thing":{"actual":{"a":1,"b":"x"}}}' ""
expect "e2e: result=failure" fail e2e "$TMP/e-ok.tsv"

manifest z success '{"other":{"actual":{"a":1,"b":"x"}}}' ""
expect "e2e: evidence_key 부재" fail e2e "$TMP/e-ok.tsv"

manifest z success '{"thing":{"a":1,"b":"x"}}' ""
expect "e2e: {actual:...} 구조 아님" fail e2e "$TMP/e-ok.tsv"

# 같은 시나리오의 성공 manifest 가 둘이면 어느 실행의 증적인지 불확정이다
manifest z success '{"thing":{"actual":{"a":1,"b":"x"}}}' ""
cp "$MAN/manifest-z.json" "$MAN/manifest-z2.json"
python3 - "$MAN/manifest-z2.json" <<'PYEOF2'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
json.dump(d, open(p, "w"))
PYEOF2
expect "e2e: 같은 시나리오 manifest 2개" fail e2e "$TMP/e-ok.tsv"
rm -f "$MAN/manifest-z2.json"

# CI 는 시나리오를 러너별 디렉터리로 나눠 넘긴다 — 합집합으로 대조하고, 디렉터리를
# 넘나드는 중복도 디렉터리 안의 중복처럼 잡아야 한다
MAN2="$TMP/manifests-2"
mkdir -p "$MAN2"
mv "$MAN/manifest-z.json" "$MAN2/manifest-z.json"
expect "e2e: 증적이 다른 디렉터리에 있음" pass \
  env SAGA_MATRIX="$TMP/e-ok.tsv" bash "$LINT" --e2e-evidence "$MAN,$MAN2"
expect "e2e: 증적 디렉터리 누락" fail e2e "$TMP/e-ok.tsv"
manifest z success '{"thing":{"actual":{"a":1,"b":"x"}}}' ""
expect "e2e: 디렉터리 간 같은 시나리오 manifest 2개" fail \
  env SAGA_MATRIX="$TMP/e-ok.tsv" bash "$LINT" --e2e-evidence "$MAN,$MAN2"
expect "e2e: 없는 디렉터리 지정" fail \
  env SAGA_MATRIX="$TMP/e-ok.tsv" bash "$LINT" --e2e-evidence "$MAN,$TMP/absent"
rm -f "$MAN2"/*.json

rm -f "$MAN"/*.json
expect "e2e: manifest 없음" fail e2e "$TMP/e-ok.tsv"

# stale — 다른 commit sha 의 manifest 는 증적이 아니다
manifest z success '{"thing":{"actual":{"a":1,"b":"x"}}}' "deadbeef"
expect "e2e: stale(commit sha 불일치)" fail \
  env SAGA_COMMIT_SHA=cafe1234 SAGA_MATRIX="$TMP/e-ok.tsv" bash "$LINT" --e2e-evidence "$MAN"

if [[ $fails -gt 0 ]]; then
  echo "self-test 실패 ${fails}건"
  exit 1
fi
echo "self-test 통과"
