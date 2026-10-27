# D-116-phase-close audit

## 2026-10-27 10:42 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트: `hpx_codex_allowed "D-116-phase-close"` 가 `blocked`(source file, origin `.cache/codex-off`). codex 명령은 실행하지 않았다.
- 등급 L 근거: 하네스가 읽는 단계 앵커(`## 현재 단계:`, `PHASE{N}.md`)와 다이제스트 파서, CI 게이트인 보존 검사기가 바뀐다(기준 2). 되돌리려면 TASKS·PHASE6·하네스·검사기를 함께 되돌려야 한다(기준 3).
- 사용자 사전 승인: 2026-10-26 CI 통과 뒤 직접 머지, 새 판단은 보수적 기본값으로 정하고 기록. 2026-10-27 대화에서 origin/main 에 머지된 원격 브랜치 26개 삭제 승인.
- 계획 전 확인(scratchpad 의 사본 저장소, 원본 무변경): TASKS 단계 서술 수정과 D-116 완료 이동을 함께 하면 보존 검사가 `Existing row/footer interleave changed: docs/TASKS.md` 로 실패한다. interleave 비교를 done 에만 두는 수정본과 corrections manifest 선언이 둘 다 통과하고, 수정본은 기존 셀프테스트 64건·실제 원문 셀프테스트를 통과하며 남은 행 순서 변경은 여전히 실패시킨다. PHASE6 작업 이력이 비면 다이제스트가 경고 없이 틀 헤딩 셋을 엔트리로 내고, PHASE6.md 가 없으면 exit 3 이다. P4b 세션의 준비 도구(`.cache/d116-p5-prep/`)는 절 끝 빈 줄 3개를 줄이고 `D-NNN — ` 제목 표기를 바꾼다.
- 상위 계획과 달라진 것: 보존 검사기 수정(P1)이 더해졌다. 잠긴 worktree 2개의 처분 조건을 "잠금 pid 가 죽었으면" 에서 "잠금 pid 가 이 세션이고 열린 파일 0" 으로 바꿨다(pid 95296 은 살아 있는 이 세션의 claude 프로세스). 이 PR 의 `/ship` 8단계 기록은 PHASE6 이 아니라 PHASE5 종결 엔트리에 둔다. `docs/plans/.archive/` 는 P2 세션에서 이미 지웠다.
- 로컬 정리 목록: 세션 scratchpad `plan/p6/`(로그 59, worktree 4, 로컬 브랜치 26, 원격 브랜치 26, `.cache` 77). 저장소에는 두지 않는다.

## 2026-10-27 10:48 — 오케스트레이터 계획 검토
- 상태: 수행(이 세션의 총괄 검토 1회)
- 확인: 보존 검사기의 TASKS interleave 비교는 행 이동이 있을 때만 돈다(`scripts/docs-preservation-check.py:429`). 규약 `docs/conventions/docs-preservation.md:12-14` 의 보존 대상은 done 행, 남은 TASKS 행의 순서, done footer 라서 P1 수정이 규약과 맞는다. 잠금 pid 95296 은 현재 셸의 부모 `claude` 프로세스이고 잠금 파일의 시작 시각과 같다. PHASE5 구역별 날짜는 옛 형식 09-22~09-30, 작업 이력 앞 10-14~10-21, 새 형식 10-21~10-27 이다.
- 반영 3건: §2.2 에 구역 사이 순서 규칙(겹치는 날짜는 2026-10-21 하나), P11 에 비추적 내용이 있는 worktree 의 `remove --force` 조건, §4-7 에 로컬 `./gradlew test --continue`(`/work` §8, P4b 와 같다).
