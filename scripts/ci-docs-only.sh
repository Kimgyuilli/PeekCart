#!/usr/bin/env bash
# PR 변경 경로가 문서뿐인지 판정한다. stdin 의 경로 목록을 읽어 GITHUB_OUTPUT 형식으로
# `code=true|false` 를 낸다. 목록이 비면 code=true 다 — 판정이 틀릴 때는 검사를
# 건너뛰는 쪽이 아니라 도는 쪽으로 틀려야 한다.
#
# 문서로 보는 경로: docs/** 와 루트의 *.md. docs/plans/fixtures/** 는 제외한다 —
# saga 계약 매트릭스가 그 아래 있고, gate 의 JVM·E2E 증적 대조가 그것을 입력으로 읽는다.
#
#   git diff --no-renames --name-only A B | bash scripts/ci-docs-only.sh
#   bash scripts/ci-docs-only.sh --self-test
set -euo pipefail

classify() {
  local path code=false seen=0
  # 마지막 줄에 개행이 없어도 읽는다 — 버리면 그 경로가 코드여도 문서 전용으로 판정된다.
  while IFS= read -r path || [[ -n "$path" ]]; do
    [[ -n "$path" ]] || continue
    seen=1
    case "$path" in
      docs/plans/fixtures/*) code=true ;;
      docs/*) ;;
      */*) code=true ;;
      *.md) ;;
      *) code=true ;;
    esac
  done
  [[ $seen -eq 1 ]] || code=true
  echo "code=$code"
}

if [[ "${1:-}" != "--self-test" ]]; then
  classify
  exit 0
fi

fails=0
expect() {  # $1=기대 $2=설명 $3=경로 목록(줄바꿈 구분)
  local got
  got="$(printf '%b' "$3" | classify)"
  if [[ "$got" == "code=$1" ]]; then
    echo "  ok   [$2]"
  else
    echo "  FAIL [$2] 기대 code=$1, 실제 $got"
    fails=$((fails + 1))
  fi
}

expect false "docs 아래 문서" 'docs/TASKS.md\ndocs/adr/0001-x.md'
expect false "루트 마크다운" 'README.md\nCLAUDE.md'
expect false "docs 아래 비-마크다운 증적" 'docs/evidence/D-063/evaluated-grafana-alerts.yml'
expect true "빈 목록" ''
expect true "saga 계약 매트릭스" 'docs/plans/fixtures/saga-contract-matrix.tsv'
expect true "문서와 코드 혼합" 'docs/TASKS.md\norder-service/src/main/java/A.java'
expect true "모듈 안의 마크다운" 'order-service/README.md'
expect true "워크플로" '.github/workflows/ci.yml'
expect true "스크립트" 'scripts/ci-docs-only.sh'
expect true "루트 빌드 파일" 'build.gradle'
expect true "docs 를 접두사로만 공유하는 경로" 'docsx/a.md'

if [[ $fails -gt 0 ]]; then
  echo "ci-docs-only self-test 실패 ${fails}건" >&2
  exit 1
fi
echo "ci-docs-only self-test OK (11/11)"
