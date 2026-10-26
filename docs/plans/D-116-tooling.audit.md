# D-116-tooling audit

## 2026-10-26 21:35 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트: `hpx_codex_allowed D-116-tooling` → blocked, origin `.cache/codex-off`.
- 등급 L 근거: 코드 이동·rename 이 들어간 구조 변경(정의상 L). CI 워크플로·셀프테스트 기대값·하네스 커맨드가 경로를 문자열로 읽어 되돌림이 이 작업 밖 파일에 걸친다.
- 범위 변화: 상위 계획 P2 대비 두 가지. `docs/plans/.archive/` 로컬 삭제를 P6 에서 당겼다(ignore 규칙 삭제와 순서 때문). 모듈별 `application-local.yml` ignore 추가는 하지 않는다(5개가 추적 중). 근거는 계획 §2.4.
- 사용자 사전 승인(2026-10-26): CI 통과·리뷰 수렴 뒤 직접 머지, 새 판단은 보수적 기본값으로 진행하고 기록.

## 2026-10-26 21:58 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증(계획 §4, 대조군은 괄호):
  1. 보존: `--base 060dea1` PASS (P3 destination 한 줄 삭제 → `Destination ordered content differs`, corrections `old` 변조 → `Correction old text is not unique`).
  2. 아카이브: dry-run 이 유지 2(D-084·D-116)와 보류 1(D-116-tooling, PR 전)만 낸다. done/ 의 `archive:` 줄 0, 종결 절의 `pull/` 0.
  3. 변이 도구: `scripts/harness-context-mutants.py` "11 CLI and 5 exact-workflow" PASS, 하네스 셀프테스트 OK (ci.yml 경로만 옛 경로로 되돌린 사본 → `test_workflow_normal_shallow_and_full_states` 두 subTest FAIL).
  4. D-102: `--scan` 20줄이 이동 전과 바이트 동일. `check-integration.py` 는 이동 전과 같은 `JAVA TEST FILE ADDED OR REMOVED` 로 끝난다 (`parents` 를 하나 틀린 사본 → 0줄로 기준과 달라짐).
  5. 매트릭스: `--structure` 35행 OK, lint·selftest 셀프테스트 통과 (`SAGA_MATRIX` 옛 경로 → 구조 위반 exit 1). `ci-docs-only` 11/11, `docs/plans/fixtures/x.tsv` 는 code=false, 새 매트릭스 경로는 code=true.
  6. consistency: `hpx_consistency_precheck` ok (파일을 치우면 unavailable).
  7. `plans-archive.sh --self-test` ok 11·실패 0 (PLAN-BLINDSPOTS 사례 1건은 의도대로 지웠다).
  8. INDEX: base 164개(기타 102) → 168개(부채·측정 122, 기타 16). 옛 분류기라면 기타 106. `--check` 최신 (정규식을 되돌린 사본 → exit 1).
  9. replay lint: 로컬 exit 0 (추적 문서에 위반 SQL → REPLAY-ENTRY-003, 추적 안 된 파일 → 통과). `--self-test` OK. pathspec 이 하위 폴더까지 덮는다(docs md 445/445, sh 45/45, py 47/47).
  10. ignore: 추적-무시 10 → 9(`gradle-wrapper.jar` 빠짐, D-063 로그 9개 남음). `check-ignore` 가 부정 규칙(31행)을 가리킨다. untracked 증가 0.
  11. 옛 경로 5종: 계획한 예외 밖 0건 (가짜 참조 1줄 → 1건 검출).
  12. CI lint 잡 단계를 ci.yml 원문대로 실행: Document preservation·Run CI policy lints·CI test matrix coverage lint·Harness archive health·Verify local DLQ contract safety·Clean up 전부 PASS. DLQ 단계는 첫 실행에서 P1 세션이 남긴 `.cache/dlq-contract/evidence`(19:59) 때문에 `FileExistsError` 로 실패했다. lab 이 기존 증적을 덮어쓰지 않는 설계라, 그 디렉터리를 scratchpad 로 옮긴 뒤 통과했다.
  13. 앵커: 앵커 7곳과 기존 manifest 의 이동·삭제 0건.
- `./gradlew test --continue`: BUILD SUCCESSFUL, 54개 작업 전부 up-to-date (Java·리소스 입력 무변경).
- 계획과 달라진 것: 없음. 절차 메모: apply 가 만든 manifest 는 stage 해야 `docs-preservation-ci.py` 가 본다(untracked 는 `git diff <base>` 에 잡히지 않는다). apply 출력이 이미 안내하므로 따로 저장하지 않는다.
- 로컬 정리: `docs/plans/.archive/` 46파일을 scratchpad tar 로 남긴 뒤 지웠다.

## 2026-10-26 22:12 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/252 (base main `060dea1`)
- 커밋 9개(docs 6 · chore 2 · src 1). 분류 검사 통과, writing-lint(커밋 9건·본문·제목) 통과
- precheck: consistency ok(warnings 0), `hpx_review_health` ok, 보존 검사 `--base 060dea1` PASS, `harness-context.sh --check --base 060dea1` ok
- 갱신: TASKS D-116 행에 PR 링크(5개 PR 중 두 번째라 진행 중 유지), PHASE5 엔트리. 상위 계획의 P2 상자는 머지 뒤 다음 단계가 채운다
