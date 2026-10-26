---
grade: L
---
# D-116-docs-move — 이력 문서 재배치와 문서 맵 (D-116 P3b)

상위 계획: `docs/plans/D-116.md` §3 P3(뒤의 네 항목). 기준 main `5261510`(P3a #253 머지 뒤). 결정 원문은 `docs/evidence/D-116/decisions.md` 의 3-2·3-6·3-7·3-8(일부)과 0-2 다. 사용자 사전 승인(2026-10-26)에 따라 CI 통과와 리뷰 수렴 뒤 머지하고, 새 판단은 보수적 기본값으로 정해 §2.4 와 PR 본문 "확인 요청" 에 남긴다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 이력 잡다 문서 7개(`docs/progress/` 의 `design-archive-phase1-2.md`·`phase4-design-roadmap.md`·`phase4-prep-debt-roadmap.md`·`loadtest-tool-evaluation.md`·`d001-metrics-histogram-fix.md`, `docs/review/` 의 `final-report.md`·`ci-optimization-analysis.md`)가 옛 자리에 있다. 또는 그 줄 가운데 `docs/progress/archive/` 에 정확히 한 번 들어가지 않은 것이 있다.
- 옮긴 문서를 가리키는 링크가 옛 경로로 남았다. TASKS 문서 맵이 옛 경로를 적는다. `docs/review/` 옛 자리에 대응표가 없다.
- ADR 0022·0025·0032·0033 과 D-100 증적 README 가 `docs/learning` 경로를 인용한다. 또는 지운 사실이 ADR 의 Update Log 에 없다.
- `TASKS-done.md` 의 D-024 행 ID 가 굵게 적혀 보존 검사와 하네스의 행 정규식에 보이지 않는다.
- `docs/README.md` 문서 맵이 없거나 `docs/` 아래 폴더 가운데 설명하지 않는 것이 있다. 또는 `CLAUDE.md` 의 문서 참조 맵이 문서 맵과 따로 논다.

등급 L: 문서만 바꾸지만 에이전트 지시 표면인 `CLAUDE.md` 가 바뀌고, 되돌리려면 옮긴 문서를 가리키는 링크와 보존 manifest 를 함께 고쳐야 한다. 코드·CI·하네스 스크립트는 건드리지 않아 CI 는 문서 전용으로 돈다.

## 2. 배경

### 2.1 코드 검증 (base `5261510`)

| 전제 | 확인한 곳 | 결과와 반영 |
|---|---|---|
| 옮길 문서 | `git ls-files` | 7개 모두 있다(410·79·155·231·105·184·295줄). 파싱 가능하다 |
| 인바운드 링크 | 정밀화한 사전 필터와 링크 해석 | `docs/02-architecture.md` 의 `progress/design-archive-phase1-2.md#02-architecturemd-12-패키지-구조` 하나다. 보호 문서·파싱 불가 문서는 걸리지 않는다 |
| TASKS 문서 맵 | `docs/TASKS.md:13-15` | `phase4-design-roadmap.md`, `phase4-prep-debt-roadmap.md`(2행)를 백틱 경로로 적는다. 행 정규식(`D-NNN`) 밖이라 보존 검사의 task 행 대상이 아니다 |
| `docs/learning` 인용 | `git grep -F docs/learning -- docs/adr docs/evidence/D-100` | `0022:81`(나열 중 하나), `0025:138`(문장 주어)·`0025:158`(참고 목록 한 줄), `0032:114`(괄호 인용), `0033:129`(근거 나열), `D-100/README.md:41`(원본 경로) |
| ADR 정정 형식 | `docs/adr/README.md:11-14`, 각 ADR 말미 | 사실 오류 정정은 본문을 고치고 `## Update Log` 에 일자·사유를 적는다. 0022 는 bullet, 0032·0033 은 `###` 소제목 형식이고 0025 는 Update Log 가 없다 |
| D-024 행 | `docs/progress/TASKS-done.md:34` | `| **D-024** | **Resilience / Availability** |` 로 ID 와 분류가 굵다. 이웃 행은 둘 다 평문이다. 보존 검사의 ROW 와 `harness-context.sh` 의 행 정규식이 모두 이 행을 못 본다 |
| D-024 정정 수단 | `docs-preservation-check.py` `tasks()`·`corrected()` | 완료 행 본문 변경은 `Task row body changed` 로 막힌다. corrections 로 base 원문을 정정하면 `tasks()` 가 정정본을 원본으로 읽는다 |
| 문서 맵 | `docs/README.md` 부재, `CLAUDE.md` "문서 참조 맵" 표 | 폴더는 adr·conventions·evidence·learning(로컬)·plans·preservation·progress·review·runbooks 와 Layer 1 7개, TASKS 다 |

### 2.2 B1 역의존 스윕

| 옮기는 경로 | 인바운드 | 처분 |
|---|---|---|
| 이력 문서 7개 | `docs/02-architecture.md` 링크 1개(앵커), TASKS 문서 맵 백틱 3행, 이력 문서의 산문 언급 | 링크는 새 경로로, 문서 맵은 새 경로로 고친다. 산문은 역사 원문이다 |
| `docs/review/` 폴더 | ADR-0007:108, PHASE3, TASKS-archive, done/D-052 의 산문 언급 | 옛 자리에 대응표 `docs/review/README.md` 를 둔다(결정 0-2) |

### 2.3 PLAN-BLINDSPOTS 점검

- B1·B1b: §2.2. 하네스·CI 가 이 문서들을 문자열로 읽는 곳은 없다(조사 D 1-15).
- B2: ADR 본문은 사실 정정만 한다. 주장은 남기고 추적되지 않는 경로만 지운다.
- B11: 옛 경로 0건 검사는 전체 경로 문자열과 링크 해석을 둘 다 보고, 가짜 참조로 먼저 확인한다.
- 나머지: 해당 없음.

### 2.4 해석과 기본값 (PR 본문 "확인 요청")

1. `ADR-0025:158` 의 참고 목록 줄은 경로 자체가 내용이라 줄을 지운다. 나머지는 문장을 남기고 경로만 뺀다.
2. D-024 정정은 ID 와 분류의 굵게 표시만 걷어낸다. 제목의 굵게 표시는 다른 행과 같은 형식이라 둔다.
3. `docs/review/` 는 폴더째 옮기므로 옛 자리에 대응표를 둔다. `docs/progress/` 의 5개는 파일 이동이라 대응표 대신 아카이브 색인에 원래 경로를 적는다.
4. `CLAUDE.md` 의 표는 포인터 한 줄로 바꾸고, 레이어 원칙 인용문은 에이전트 규칙이라 남긴다.

## 3. 작업 항목

- [x] P1. 이력 문서 이동. 7개를 같은 이름으로 `docs/progress/archive/` 에 옮기고 manifest `docs/preservation/d116-docs-move-20261026.json` 에 선언한다. `docs/02-architecture.md` 의 링크를 새 경로로 고친다. `docs/progress/archive/README.md` 색인(8개 문서의 원래 경로·옮긴 때·내용)과 `docs/review/README.md` 대응표를 만든다.
- [x] P2. TASKS 문서 맵의 3행을 새 경로로 고치고 문서 맵(`docs/README.md`) 행을 더한다.
- [x] P3. `docs/learning` 인용 삭제. ADR 4개는 본문을 고치고 Update Log 에 일자·지운 경로·사유(추적되지 않는 로컬 자료라 저장소에서 확인할 수 없다, 결정 3-6)를 적는다. D-100 README 는 원본 경로만 뺀다.
- [x] P4. D-024 정정. 같은 manifest 의 corrections 로 `| **D-024** | **Resilience / Availability** |` 를 `| D-024 | Resilience / Availability |` 로 바꾼다.
- [x] P5. 문서 맵. `docs/README.md` 에 레이어, Layer 1 문서와 참조 시점, 작업 추적, 계획, 증적, 규약·운영, 하네스 앵커, 로컬 전용 폴더를 적는다. `CLAUDE.md` 의 문서 참조 맵 표를 이 파일을 가리키는 포인터로 바꾼다.

## 4. 검증 방법

base 는 `5261510` 이다. 실패를 먼저 주입한다.

1. 보존: `docs-preservation-ci.py --base 5261510` PASS. 옮긴 문서에서 줄 하나를 빼면 FAIL, D-024 정정의 `old` 를 바꾸면 FAIL.
2. 링크: `docs/02-architecture.md` 의 링크가 새 문서의 같은 앵커로 해석된다. 파싱 가능한 추적 md 에서 옛 7개 경로로 해석되는 링크 0건, 새 문서의 링크 대상 실재.
3. 행: D-024 가 보존 검사의 task 행과 하네스 다이제스트에 보인다. `harness-context.sh --check --base 5261510` ok, 완료 행 수가 하나 늘어난 것 말고 다이제스트가 같다.
4. 인용: ADR 4개 본문과 D-100 README 의 `docs/learning` 인용 0건. 남는 것은 ADR Update Log 의 삭제 기록, gitignore 설명, 계획·이력 문서다.
5. 문서 맵: `docs/` 아래 모든 폴더가 `docs/README.md` 에 있다. 맵의 링크 대상이 모두 있다.
6. CI lint 잡 단계를 ci.yml 원문대로 로컬 실행, `plans-index.sh --check`, consistency precheck, 앵커 이동·삭제 0건.

## 5. 완료 조건

P1~P5 상자가 채워지고 §4 결과를 audit 에 남기고 PR CI(문서 전용)가 성공하면 머지한다. 머지 뒤 다음 단계가 이 계획을 아카이브하고 상위 계획 §3 의 P3b 칸과 P3 상자를 채운다.

## 6. 범위 밖과 미해결

- Layer 1(`docs/01~07`) 재작성, ADR 색인 정비, 루트 `README.md`·`loadtest/README.md` 재작성은 P4 다. `docs/02-architecture.md` 는 이 PR 에서 링크 한 줄만 고친다.
- PHASE5 재정렬과 Phase 6 개시는 P5 다. 그때 `docs/README.md` 에 Phase 6 을 반영한다.
