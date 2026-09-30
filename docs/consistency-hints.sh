#!/usr/bin/env bash
# docs/consistency-hints.sh
#
# 문서 일관성 **수동 점검 힌트** 출력 스크립트.
#
# 이 스크립트는 검증 도구가 아닙니다. 대부분의 항목은 grep/find 결과를
# 사람이 직접 읽고 판단하도록 출력만 합니다. 자동 판정은 항목 2(ADR 파일
# 존재 여부) 한 가지뿐이며, 그 외 항목은 PASS/FAIL 을 판정하지 않습니다.
#
# 출력 항목:
#   1. Layer 1 문서(01~07)에서 환경 단어(minikube, GKE, GCP) 등장 위치
#      → 해당 줄 근처에 ADR 참조가 있는지 사람이 확인
#   2. 모든 `ADR-NNNN` 참조의 실제 파일 존재 여부 (유일한 자동 판정)
#   3. k8s/ 디렉토리 트리 (02-architecture.md §12 와 사람이 비교)
#
# 사용법: bash docs/consistency-hints.sh
# 종료 코드: 항목 2 만 영향. 0 = ADR 참조 깨짐 없음, 1 = 깨진 ADR 참조 발견
#            (다른 항목의 의미적 일관성은 종료 코드에 반영되지 않음)

set -u
cd "$(dirname "$0")/.."

ADR_REF_FAIL=0
LAYER1_DOCS=(docs/01-*.md docs/02-*.md docs/03-*.md docs/04-*.md docs/05-*.md docs/06-*.md docs/07-*.md)
ENV_WORDS='minikube|GKE|GCP|Google Cloud'

echo "=== 1. [HINT] Layer 1 환경 단어 등장 위치 ==="
echo "  (자동 판정 없음. 사람이 각 줄 근처에 ADR 참조가 있는지 확인)"
for doc in "${LAYER1_DOCS[@]}"; do
  [ -f "$doc" ] || continue
  matches=$(grep -nE "$ENV_WORDS" "$doc" || true)
  [ -z "$matches" ] && continue
  echo "  [$doc]"
  echo "$matches" | sed 's/^/    /'
done
echo

echo "=== 2. [CHECK] ADR 참조 파일 존재 여부 (유일한 자동 판정) ==="
adr_refs=$(grep -rhoE 'ADR-[0-9]{4}' docs/ 2>/dev/null | sort -u || true)
for ref in $adr_refs; do
  num="${ref#ADR-}"
  if ! ls docs/adr/${num}-*.md >/dev/null 2>&1; then
    echo "  [MISS] $ref — 해당 ADR 파일 없음"
    ADR_REF_FAIL=1
  fi
done
[ $ADR_REF_FAIL -eq 0 ] && echo "  OK — 모든 ADR 참조 파일 존재 (이것이 의미적 일관성을 보장하지는 않음)"
echo

echo "=== 3. [HINT] k8s/ 디렉토리 트리 ==="
echo "  (자동 판정 없음. docs/02-architecture.md §12 의 트리 블록과 사람이 diff)"
if [ -d k8s ]; then
  find k8s -maxdepth 3 -type d | sort | sed 's/^/  /'
else
  echo "  k8s/ 디렉토리 없음"
fi
echo

if [ $ADR_REF_FAIL -ne 0 ]; then
  echo "HINTS — 일부 ADR 참조가 깨졌습니다. 위 [MISS] 항목 확인 필요."
  echo "        (다른 항목 1·3 의 일관성은 사람이 직접 확인해야 합니다.)"
  exit 1
fi
echo "HINTS — ADR 참조 파일은 모두 존재. 항목 1·3 은 사람이 위 출력을 읽고 판단하십시오."
