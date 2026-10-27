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

## 2026-10-27 13:01 — 로컬 정리 (상위 P6)
- 상태: 수행. P9·P10 은 서브에이전트가, P11~P13 은 P10 사고 뒤 사용자 확인을 받아 오케스트레이터가 직접 실행했다.
- P9 사전 대조: #251~#256 MERGED. 다시 산출한 로그 59·로컬 브랜치 26·원격 브랜치 26·`.cache` 77 이 계획 목록과 같았다. worktree `agent-ab66e457f5b9f33aa` 의 크기만 475,040K 에서 475,020K 로 달랐고 추적 변경·열린 파일은 0 이었다.
- P10 로그: 59개를 지웠다(2,451,870B). 계획한 57개(1,966,934B)에 더해 유지 대상 2개(`loadtest/reports/2026-04-29/run2/k6-stdout.log` 132,995B, `loadtest/reports/2026-10-26/s3/c0/c2-payment-service-2100.log` 351,941B)가 지워졌다.
  - 원인: 처분 열을 `awk -F'\t' '$1=="삭제"'` 로 걸렀다. macOS awk(20200816)는 en_US.UTF-8 에서 `"유지"=="삭제"` 를 참으로 낸다(`LC_ALL=C` 에서는 거짓, 재현함). 건수 출력과 삭제가 한 명령 안에 있어 59 를 보고 멈출 수 없었다.
  - 복구: 불가. git 에 추적된 적이 없고 Time Machine 이 없다. 로컬 스냅샷 3개는 시스템 볼륨(`disk3s3s1`) 것이고 Data 볼륨(`disk3s1`)에는 스냅샷이 없다. 디스크의 다른 위치에도 사본이 없었다.
  - 남은 것: #251 의 발췌 2개(머리에 원본 크기·줄 수·sha256)와, 두 로그가 근거였던 `loadtest/reports/2026-04-29/REPORT.md`·`loadtest/reports/2026-10-26/session-issues.md` 5번·TASKS 의 D-112 행. 잃은 것은 발췌 밖 원문(경고 822줄의 개별 시각, INFO 251줄의 레코드별 세부, 스택트레이스 중간 프레임)이다.
  - 이후 단계: 삭제 목록은 Python 정확 비교로 만들고, 건수·집합 대조 관문을 삭제와 다른 명령으로 두었다.
- P11 worktree: 4개를 지웠다(652,416K). 잠긴 2개의 잠금 pid 95296 은 이 세션의 claude 프로세스였다. 넷 다 HEAD 가 origin/main 의 조상이고 추적 변경 0, 열린 파일 0 이었다. 비추적 내용은 ignore 대상(빌드 산출물, `.gradle`, `__pycache__`, `.cache` 의 consistency 로그·PR 본문·lint 산출물·아카이브 백업·레인 B 작업 사본)뿐이었다. 레인 B 사본을 가리키는 저장소 문서는 D-065 audit 의 출처 메모 한 줄뿐이다.

| worktree | 브랜치 | HEAD | 크기 |
|---|---|---|---|
| `agent-a69830a224f94b6c8` | `debt/d106-remeasure-design` | `cd7e6bc22ac5473f48cd3c917a82972562495520` | 32352K |
| `agent-a869a8e39f4a9af7b` | `docs/campaign-records-20261025` | `d617b82deb0a1e67855cecb06af7b25148e64dff` | 112872K |
| `agent-ab66e457f5b9f33aa` | `debt/d075-outbox-throughput` | `46af6864632c1ad0d18a3028d3c0a9ecd53a1e94` | 475020K |
| `agent-ac4c301e228122a5d` | `debt/d107-deploy-topics` | `1f6bbdaf5276247a9a0ef97d13992c1ec8d41747` | 32172K |

- P11·P12 브랜치: 로컬 26개를 `git branch -d` 로(실패 0), 원격 26개를 `git push origin --delete --force-with-lease=<브랜치>:<SHA>` 로 하나씩 지웠다(lease 거부 0, pre-push 훅 없음). 원격은 2026-10-27 사용자가 승인한 26개와 정확히 같았고, 남은 원격은 main 뿐이다(이 PR 브랜치는 push 전). 합집합 31개다.

| 브랜치 | 로컬 SHA | 원격 SHA |
|---|---|---|
| `Kimgyuilli/ci-slim-down-audit` | — | `cf9d48317e3a4c785303ea058bd38669afea1cf8` |
| `Kimgyuilli/sync-context-usage-diagnosis` | — | `2d0ac20ddcd4f77cce3955baad5a082cecbfed2f` |
| `debt/d065-contract-migration` | `08d887d5aaa2f3ac1a405c3d3537798db734249c` | `08d887d5aaa2f3ac1a405c3d3537798db734249c` |
| `debt/d075-outbox-throughput` | `46af6864632c1ad0d18a3028d3c0a9ecd53a1e94` | `d4f216d85cbadd436d2061c218aa2d4c93230da4` |
| `debt/d106-d107-followup` | `9af34cfb662c999fd911092a342b05d15a32c0c0` | — |
| `debt/d106-remeasure-design` | `cd7e6bc22ac5473f48cd3c917a82972562495520` | `cd7e6bc22ac5473f48cd3c917a82972562495520` |
| `debt/d106-remeasure-prereq` | `534164b945af287825702d961a6a8e800e8adc68` | `534164b945af287825702d961a6a8e800e8adc68` |
| `debt/d106-topic-config-race` | `1ee78bea941c678344839a53f4e7acc182495a87` | `1ee78bea941c678344839a53f4e7acc182495a87` |
| `debt/d107-deploy-topics` | `1f6bbdaf5276247a9a0ef97d13992c1ec8d41747` | `1f6bbdaf5276247a9a0ef97d13992c1ec8d41747` |
| `debt/d107-recovery-lab-topics` | `935516ea37e63d5ba6e49dc655d0d4f218346942` | `935516ea37e63d5ba6e49dc655d0d4f218346942` |
| `debt/d116-docs-move` | `f89347baff11cdd5515c3c02a5136c9c5d436741` | `f89347baff11cdd5515c3c02a5136c9c5d436741` |
| `debt/d116-evidence` | `647ef1df8d638f424d9c35d5228cbddc879589d1` | `647ef1df8d638f424d9c35d5228cbddc879589d1` |
| `debt/d116-layer1` | `2149072750d1d9969658755fe59679c3fda28213` | `2149072750d1d9969658755fe59679c3fda28213` |
| `debt/d116-layer1-archive` | `83ebc1924444b3ea9a6fb58547618d8d234d4ab7` | `83ebc1924444b3ea9a6fb58547618d8d234d4ab7` |
| `debt/d116-loadtest-prune` | `423ee32df0378654dc71c4734fc5908d28b82989` | `423ee32df0378654dc71c4734fc5908d28b82989` |
| `debt/d116-tooling` | `d4e1b6a9adfe130e42ef0cef984226f0ce407324` | `d4e1b6a9adfe130e42ef0cef984226f0ce407324` |
| `docs/archive-merged-subplans` | `27db0aa64ddfc716e52946ae330c84b3a5552434` | `27db0aa64ddfc716e52946ae330c84b3a5552434` |
| `docs/campaign-records-20261025` | `d617b82deb0a1e67855cecb06af7b25148e64dff` | `d617b82deb0a1e67855cecb06af7b25148e64dff` |
| `docs/d078-gke-session` | `61a2c9fe7ae3dfa0fd95f3549de6f2d10e6e2ba4` | `61a2c9fe7ae3dfa0fd95f3549de6f2d10e6e2ba4` |
| `docs/d078-remeasure-session` | `76eff5bac1201bbec1d8a12b0d5c4e560f71bfe8` | `76eff5bac1201bbec1d8a12b0d5c4e560f71bfe8` |
| `docs/d108-remeasure-prep` | `ffc71d08107c039c19bfe8dfbd3a291aa28f7665` | `ffc71d08107c039c19bfe8dfbd3a291aa28f7665` |
| `docs/d108-remeasure-session` | `2025bc6103b72fb65ca3c93231e69f4bd312eaf1` | `2025bc6103b72fb65ca3c93231e69f4bd312eaf1` |
| `docs/debt-records-20261024` | — | `83cd9dd9c0f4439a0098de117b80a1a874047a99` |
| `feat/D-064-toss-sandbox` | `48e1a685b7a8c63b294c0ba7b5fda93d33d048a6` | `48e1a685b7a8c63b294c0ba7b5fda93d33d048a6` |
| `feat/D-089-stale-state` | — | `090e8285b820d08b5b5c992fb9cb78cafeb7c486` |
| `feat/D-092-ops-closure` | — | `99916a784df22df1fda94d76b52c8295ac463651` |
| `feat/digest-open-rows` | `deb48f5880205ceb730d78cc70d674116ebc9560` | `deb48f5880205ceb730d78cc70d674116ebc9560` |
| `worktree-agent-a69830a224f94b6c8` | `6905952dfbcddff3510b734ddf2a9cb76d0d1cb6` | — |
| `worktree-agent-a869a8e39f4a9af7b` | `6905952dfbcddff3510b734ddf2a9cb76d0d1cb6` | — |
| `worktree-agent-ab66e457f5b9f33aa` | `6905952dfbcddff3510b734ddf2a9cb76d0d1cb6` | — |
| `worktree-agent-ac4c301e228122a5d` | `6905952dfbcddff3510b734ddf2a9cb76d0d1cb6` | — |

- P13: `.gradle-work/` 539,720K 를 지웠다(열린 파일·사용 프로세스 0). `.cache/` 75개(10,028KB)를 지우고 `codex-off`·`d078-s0.txt` 를 남겼다.
- P14: 사후 목록은 세션 scratchpad `plan/p6/after.txt` 다. 표의 SHA 객체는 모두 있다(`git cat-file -e`). 계획 §4-6 의 기대값과 같고, 원격의 이 PR 브랜치만 push 전이라 없다.
