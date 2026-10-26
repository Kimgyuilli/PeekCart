# D-116 증적 — Phase 5 마감 정리 전수조사와 결정

Phase 5 마감 정리를 시작하기 전(2026-10-26, main `91cfa1f`)에 저장소를 읽기 전용으로 전수조사한 보고서다. 그 결과로 받은 사용자 결정도 함께 있다. 계획은 `docs/plans/D-116.md` 다.

| 파일 | 내용 |
|---|---|
| `decisions.md` | 요청·답변 원문과 해석 |
| `survey-0-consolidated.md` | 네 레인 교차 대조, 정리가 지켜야 할 계약, 재설계 초안, 결정 질문지(§5) |
| `survey-A-loadtest.md` | `loadtest/reports/` 인벤토리와 처분 후보 |
| `survey-B-plans.md` | `docs/plans/` 계획·증적과 증적 세 위치 |
| `survey-C-docs.md` | 현행·이력 문서, Layer 1 stale 항목, ADR 색인, 단계 종결 선례 |
| `survey-D-tooling.md` | 도구·CI·하네스의 경로 의존성과 로컬 잔재 |
| `loadtest-reports-inventory-91cfa1f.tsv` | 정리 전 `loadtest/reports/` 파일 889개(바이트 · T 추적/U 비추적 · 경로) |

## 만든 방법

main `91cfa1f` 에서 조사 레인 4개를 병렬로 돌렸다. 레인은 저장소에 쓰는 명령을 쓰지 않았다. 각 보고서 머리에 실행한 명령이 있다. 통합본은 네 보고서를 교차 대조해 질문을 중복 없이 묶었다.

보고서는 세션 임시 폴더에 쓴 원문을 그대로 옮겼다. 본문의 `/private/tmp/…/scratchpad/…` 경로는 그 임시 폴더다. 같은 파일이 이 폴더에 있다. 예외는 `latency-aggregates.txt` 로, 같은 집계를 `loadtest/reports/<날짜>/latency-summary.txt` 가 대신한다. 두 집계는 71행이 모두 같다.

원문과 다른 곳은 한 군데다. `survey-C-docs.md` §6-2 에서 결번 두 개(0038·0039)를 ADR 접두사와 함께 하나씩 적은 표기를 `ADR-003[89]` 로 줄여 적었다. `/ship` 사전 점검(`docs/consistency-hints.sh`)이 없는 ADR 번호를 적은 글을 깨진 참조로 잡기 때문이다. 뜻은 같다.

## 읽는 법

- 보고서는 조사 시점의 사실이다. 정리 PR 이 지나면 경로가 바뀐 것이 있다. 현재 위치는 `docs/plans/D-116.md` §3 과 각 하위 계획에 있다.
- 정정 하나: 보고서는 "추적된 모든 `.md` 는 manifest 없이 지우거나 옮길 수 없다" 고 적었다. 실제 보존 검사는 `docs/` 아래 `.md` 만 본다(`docs/plans/D-116.md` §2.3).
- 인벤토리의 U(비추적) 59개는 `.gitignore` 의 `*.log` 규칙에 걸린 로컬 로그라 git 에 없다.
