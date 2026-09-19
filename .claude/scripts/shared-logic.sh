#!/usr/bin/env bash
# shared-logic.sh — Claude × Codex 하네스 공용 shell 함수 (모듈 로더)
#
# Phase 0a 결과 B (nested slash 불가) 로 인해 /plan, /work, /ship 에서
# /sync, /next, /done 을 직접 호출할 수 없으므로 공용 로직을 본 파일군에 둠.
#
# 2026-09-18 정리: 2026-08-26/08-30 축소에서 호출처가 사라진 helper 51개를 제거했다.
# lock.sh / state.sh / sync.sh 는 통째로 죽어 파일을 삭제했다. 이력은 git 에 남는다.
#
# 실제 구현은 lib/*.sh 로 분리되어 있고, 본 파일은 이를 source 하는 얇은 로더다.
# 소비처(커맨드·bats)는 종전처럼 본 파일만 source 하면 된다:
#   source .claude/scripts/shared-logic.sh
#   hpx_plan_grade <task-id>
#   ...
#
# 모듈 구성 (.claude/scripts/lib/):
#   common.sh — PATH 방어 + task_id 검증 (가장 먼저 source)
#   audit.sh  — 리뷰 처분 규율 점검
#   codex.sh  — Codex 응답 헬퍼 + 호출 게이트
#   plan.sh   — 계획서 frontmatter / 작업 등급
#   work.sh   — /work: base branch 해석
#   ship.sh   — /ship: consistency precheck
#
# 함수 접두사: hpx_  (harness prototype)

# lib 디렉토리 해석.
# - bash: BASH_SOURCE 로 본 파일 위치 기준 해석 (cwd 비의존).
# - zsh/sh 등 BASH_SOURCE 미지원 셸(예: bash -c 래핑 누락): 하네스 cwd=repo root
#   불변식에 따라 고정 상대경로로 폴백. 원본 monolith 의 zsh 내성을 유지한다.
if [ -n "${BASH_SOURCE:-}" ]; then
  _hpx_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib"
else
  _hpx_lib_dir=".claude/scripts/lib"
fi

# common.sh 가 PATH 방어 preamble 을 포함하므로 반드시 가장 먼저 source.
# 나머지는 함수 정의만 담겨 source 순서에 무관(상호호출은 런타임 해석).
. "${_hpx_lib_dir}/common.sh"
. "${_hpx_lib_dir}/audit.sh"
. "${_hpx_lib_dir}/codex.sh"
. "${_hpx_lib_dir}/plan.sh"
. "${_hpx_lib_dir}/work.sh"
. "${_hpx_lib_dir}/ship.sh"

unset _hpx_lib_dir
