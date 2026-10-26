# Survey B — docs/plans 계획·증적 corpus 전수 조사 (읽기 전용, main `91cfa1f`, 2026-10-26)

조사 방식: 파일 전수 열람·`grep`·python 집계, `gh pr view`/`gh api`(읽기), `bash scripts/plans-archive.sh`(dry-run), `bash scripts/plans-index.sh --check`, `python3 -B scripts/docs-preservation-ci.py --base HEAD`. 저장소를 바꾸는 명령은 돌리지 않았다. grep 은 `.claude/worktrees/`(에이전트 워크트리 4개, 637M, `.git/info/exclude`) 와 `docs/learning/drafts/tools/`(문서 스냅샷 사본 198개 md, 43M) 를 제외하거나 따로 표기했다 — 둘 다 corpus 사본을 품고 있어 제외하지 않으면 참조 집계가 두 배로 부푼다.

---

## 1. 요약 표

| 영역 | 파일 수 | 크기 | 발견 불일치(건) | 핵심 |
|---|---|---|---|---|
| `docs/plans/` 루트 (계획 4 + audit 4 + `PLAN-BLINDSPOTS.md`) | 9 | ~220K | 4 | D-108 `archive: hold 248` 이 머지된 #250 을 모름(후보로 뜸) · D-078-remeasure-plan 은 hold 없는데 TASKS 행은 ◐ · task-debt-orchestration 의 "열린 항목" 표 2/5 행이 TASKS-done 과 어긋남 · D-078-remeasure-plan §9 의 `d002bc-order-create.js` 60초 setup 한계는 어떤 TASKS 행에도 없음 |
| `docs/plans/done/` | 299 (계획 164 · audit 134 · `INDEX.md`) | 3.6M | 9 | 명명 스타일 8종(같은 D-ID 공간이 `task-dNNN-*` ↔ `D-NNN` 두 표기) · INDEX 분류 "기타" 102/164 · 고아 audit 0 · audit 없는 계획 30(M/L 인데 없음 1, S 인데 있음 2) · frontmatter 없음 46 · PR 링크 없음 9 · INDEX PR 빈칸 9/날짜 빈칸 9 · 증적 디렉터리 `D098` ↔ 계획 `D-098` · manifest 로 이동된 파일은 298 중 107 |
| `docs/plans/evidence/` | 26 dir · 369 file (gz 225 · zip 6) | 4.1M | 7 | 참조 없는 고아 dir 6 · 중첩 하위 dir 6 · README 없는 dir 3 · 코드/규약이 직접 의존하는 dir 4(`D-099` 는 **CI 가 실행**) · Actions 산출물 사본은 만료 임박(최단 2026-10-28) · 로컬 실행 XML 10개는 재생성 불가 · 같은 D-078 부채의 증적이 이 폴더(6 dir)와 `docs/progress/evidence/`(2 md)에 갈림 |
| `docs/evidence/` | 5 dir · 52 file (gz 10) | 884K | 3 | 2026-10-17 캠페인 worker 산출(plans/evidence 보다 4일 먼저 생김) · README 없는 dir 2(`D-076`, `D-084`) · 어떤 규약 문서도 이 위치를 정의하지 않음 · `scripts/ci-docs-only.sh` self-test 가 경로 리터럴을 기대값으로 품음 |
| `docs/progress/evidence/` | 15 md | 176K | 1 | 전부 산문 증적(GKE 세션·CI 실측·드릴·정정) · 파일명 4스타일 · 15개 모두 ADR/TASKS/PHASE/runbook 에서 참조됨 · 유일하게 코드가 기본 출력지로 삼는 위치(`scripts/gke-security-smoke.sh` `EVIDENCE_DIR`) |
| `docs/preservation/` | 14 json | 104K | 0 | 이동 manifest 13(source 107개) + corrections 전용 1 · 과거 manifest 수정 시 CI exit 1 |
| `docs/plans/fixtures/` (+`.audit/`) | 6 | 36K | 1 | `saga-contract-matrix.tsv` 만 스크립트가 읽음 · 스모크 계획 3 + `.audit/` 2 는 2026-04 하네스 프로토타입 잔재, 참조 0 · 추적 md 라 삭제엔 manifest 필요 |
| `docs/plans/.archive/` (gitignored) | 46 `*.state.*.json` | 356K | 0 | 2026-04-20~08-29 상태머신 산출 · 읽는 코드 없음 · git 에 없음 |

---

## 2. 루트 계획서 4건 판정

### 2.1 `D-078-remeasure-plan.md` (+ `.audit.md`) — 기계 판정 **후보**, 내용상 **계획 범위 완료**, TASKS 행은 ◐

- frontmatter `grade: M`, `archive:` 키 없음. 499줄, 53K. PR 링크(계획+audit): #243 MERGED 2026-10-24, #246 MERGED 2026-10-25. `plans-archive.sh` dry-run: `후보 D-078-remeasure-plan.md PR 243 246`.
- 다루는 것: D-106 ①⑦⑤ 설계 확정(§2) · 작업 항목 P1~P6 **전부 `[x]`**(§3) · 재측정 세션 절차 S0~S5(§4) · 중단 규칙/비용(§5~6) · **세션 실행 기록 S0~S5(2026-10-25, L205~L437, S0 digest 표 L230 포함)** · §9 범위 밖.
- 반드시 살아야 하는 "세션 기록" 절: L205 `**S0 실행 기록 (2026-10-25).**`, L253 `**S1 실행 기록**`, L294 `**S2 실행 기록**`(원자료 `loadtest/reports/2026-10-25/s2-runs/`), L363 `**S3 실행 기록 … 측정 run 진입 불가, contend·spread 모두 "부하 미달"로 접음.**`, L411 `**S4 실행 기록**`, L432 `**S5 실행 기록**`. 계획서 자체 머리말: "세션 실행 기록(S0 digest 표 포함)도 이 파일에 남긴다."
- 아카이브 금지 문구는 **세션 실행 전**에 한정: L6 "**세션을 실행하기 전까지 이 계획서는 아카이브하지 않는다.**", §8 "이 계획서는 그때도 아카이브하지 않고 세션 계획으로 남긴다. D-078 의 각 항목은 세션 결과로 닫는다." 세션은 실행·기록됐고, `D-108.audit.md` 2026-10-26 07:59 /ship 이 "계획서는 세션 기록을 마쳐 다음 `/sync` 의 아카이브 판정 대상이다" 라고 적었다.
- 기계적 차단은 해소됨: `PHASE5.md:1076` "`D-078-remeasure-plan` 아카이브는 본문의 여러 줄 inline code(198·245·271·333·338행)를 보존 검사기가 지원하지 않아 하지 않았다" → 커밋 `2de1db6`(2026-10-26 "줄을 넘는 인라인 코드 정리"). 현재 백틱 개수가 홀수인 줄은 코드 펜스 4줄(L276/281/343/346)뿐이다. 실제 통과는 `--apply` 때 검사기가 확정한다.
- TASKS 대조: `docs/TASKS.md:52` D-078 행 `◐ 부분 완료` — "남은 것은 ⑦(결제 콘솔 확인, D-108 세션 비용 포함 사용자 직접)". ⑤⑥ 은 D-108 세션(#250)에서 측정 완료로 기록됨.
- 계획이 넘긴 것(handoff) 인용, §9 "범위 밖과 미해결":
  - "세션 실행(과금)과 증적·TASKS·PHASE5 반영은 세션 후속이다." → 실행·기록 완료(#246).
  - "D-106 ⑥ … 은 D-075 가 outbox 기본값으로 처분했다(#242, ADR-0045)." → TASKS-done D-075 ✅.
  - "배포 래퍼의 토픽 사전 생성은 D-107(#241)이다." → TASKS-done D-107 ✅.
  - "상한 증액 결정(ADR-0037 갱신)은 세션 결과를 보고 사용자가 정한다." → TASKS D-078 행 "2026-10-25 사용자 결정으로 상한은 1 을 유지하고 캐시 계층 보강으로 대신한다: ADR-0046, D-109".
  - S3 측정 실패 → TASKS D-108 행(신규 등록, #246).
  - **TASKS 행 없음**: "`d002bc-order-create.js` 도 같은 순차 로그인 setup 이라 USERS 를 늘리면 같은 60초 제한에 걸린다. 이번 세션은 그 스크립트를 쓰지 않아(E 는 끝났다) 고치지 않는다." — `docs/TASKS.md` 에 `order-create` 언급 0건. 의도적 "고치지 않음" 결정이므로 부채가 아닐 수 있으나, 추적 행이 없다는 사실은 남긴다.
- 판정: 계획서 범위(설계+세션+기록)는 끝났고 PR 도 전부 머지. 남은 ⑦ 은 계획서 밖(사용자 행동)이며 TASKS 행이 추적한다. **아카이브 가능 여부는 "TASKS 행이 ◐ 인 계획서를 옮기는가" 의 소유자 결정**(§6 Q1).

### 2.2 `D-108.md` (+ `.audit.md`) — 기계 판정 **후보(hold 밖 머지 #250)**, 내용상 **계획 범위 완료**, TASKS 행은 ◐(③ 대기)

- frontmatter `grade: M`, `archive: hold 248`. 208줄. PR: #248 MERGED 2026-10-25, #250 MERGED 2026-10-25(UTC). dry-run: `후보 D-108.md PR 248 250 (hold 이후 새 머지 PR 250 …)`.
- 다루는 것: 사전 규칙 §3(워밍업 종료 ④, 수신 상한 대조 ①, 측정 블록 ⑤⑥, 종료 규칙) · 작업 항목 P1·P2 **`[x]`** · §5 세션 절차 + **세션 실행 기록(2026-10-25 UTC, L123~L166: run 표 c1/c2/s1, D-078 ⑤·⑥ 결과, ADR-0045 재검토 입력, D-108 ① 분석, 측정 밖 관찰, S5)** · §9 범위 밖 · §10 완료 조건 · `PR: [#248]` 줄(본문엔 #250 없음, audit 에만).
- §10 "D-078 ⑤⑥ 과 D-108 ① 은 세션 결과로 닫는다." → §5 기록에서 닫힘. 머리말 "**세션을 실행하기 전까지 이 계획서는 아카이브하지 않는다.**" 조건도 해소.
- 넘긴 것 인용 — §9 "범위 밖과 미해결":
  - "D-108 ②(order outbox 발행 지연): C2 대조가 입력이다. 따로 작업하지 않는다." → TASKS D-108 행 "② 는 D-110 으로"; D-110 행 "흡수: D-108 ②".
  - "D-108 ③: 측정 overlay 의 완화로 처리했고 base·운영 프로브 정책은 정하지 않는다(TASKS 에 기록)." → TASKS D-108 행 "남은 것은 ③(base·운영 프로브 정책, 대기)" — **새 행 없이 D-108 행에 남아 있음**.
  - "D-108 ⑤ 중 `d026-run-block.sh` 워밍업 SQL 오류와 run 단위 기록 자리: S2 하네스라 이번 세션에 쓰지 않는다." → TASKS "⑤ 는 D-115 로"; D-115 행 "흡수: D-108 ⑤".
  - "D-108 ⑥(노드 CPU 패널과 export 값 차이): 판정은 export 값으로 한다." → TASKS "⑥ 은 판정을 export 값으로 하기로 해 종결".
  - "D-109(ADR-0046): 측정과 무관해 대기로 둔다." → TASKS D-109 🔲.
  - "D-078 ⑦(결제 콘솔 확인): 사용자가 직접 한다." → TASKS D-078 행 ⑦ 남음.
  - 세션 중 발견 → TASKS D-108 행 "세션 중 MySQL 볼륨 가득 참(D-114), payment 재시도 막힘(D-112), 재고 원장 diff −1(D-113)"; D-078 ⑤ 결과의 C 승격 판단 → D-111; ADR-0045 재검토 입력 → D-110. D-109~D-115 모두 `docs/TASKS.md:55~61` 에 🔲 대기로 존재.
- 불일치: `archive: hold 248` 은 #250 머지를 반영하지 않아 스크립트가 후보로 올린다. hold 규약상 "적힌 번호 밖의 PR 이 머지되면 다시 후보가 된다" 가 설계 의도이므로 오동작은 아니고, **"③ 대기가 이 계획서를 루트에 붙잡는 사유인가"** 가 미결(§6 Q2).

### 2.3 `D-084.md` (+ `.audit.md`) — **보류(유지)**, 세 곳 일치

- `grade: L`, `archive: hold 181`. #181 MERGED 2026-10-17. dry-run `유지 … hold PR 181 이후 새 머지 PR 없음`.
- P1 `[ ]`("신뢰 증빙·예약/환불 대사 계약 및 ADR-0040 확정. 영구 404는 별도 열린 조건 유지"), P2~P5 `[x]`. §상태와 범위: "전체 D-084는 영구 404 증빙 조건 때문에 미완이며 아카이브하지 않는다."
- TASKS `docs/TASKS.md:53`: "⏸ 보류(2026-10-25 사용자 결정 … 재개 조건: 영구 404 의 PG 발급 무과금 증빙 원천·형식 확보)". 계획서·hold·TASKS 가 모두 "부분 병합, 미완" 으로 일치.
- 부수: 하위 계획 `done/D-084-signed-recovery.md` 는 이미 아카이브(manifest `plans-merged-subplans-20261024.json`); 증적은 `docs/plans/evidence/D-084-signed-recovery/`(참조 0) 와 `docs/evidence/D-084/focused-499d8e1.json`(참조: `D-084.audit.md`, blog31 json) 두 곳.

### 2.4 `task-debt-orchestration.md` (+ 66K `.audit.md`) — **오케스트레이션 문서**, hold 유지 중이나 내부 표가 낡음

- `grade: L`, `archive: hold 177 179 180 181`(4개 모두 MERGED 2026-10-16/17). dry-run `유지`.
- 2026-10-17 병렬 부채 캠페인의 상위 실행 계획. P1~P3·P5·P7·P8 `[x]`, **P4 `[ ]`**("메시징/처리율 D-076/075 및 saga E2E D-061/062" — "실행 처분": D076/D061/D062 완료, D075 사용자 보류로 전체 미완), **P6 `[ ]`**(D-084).
- "최종 부채·소유권·증거 대조" 열린 항목 표(L213~L219) vs 현재 TASKS:
  | 표의 열린 항목 | 표의 처분 | 현재 상태 |
  |---|---|---|
  | D-075 | 사용자 명시 보류 | **TASKS-done ✅ (#242, ADR-0045, 2026-10-25)** — 표가 낡음 |
  | D-084 | #181 부분 병합, 전체 미완 | TASKS ⏸ 보류 — 일치 |
  | D-064 | 사용자 제외 | **TASKS-done ✅ (#249, 2026-10-26)** — 표가 낡음 |
  | D-065 | 사용자 제외 | TASKS ⏸ 보류 — 일치 |
  | D-078 | 사용자 제외 | TASKS ◐ — 일치(열림) |
- 남은 항목은 전부 TASKS 행(열림 또는 done)에 대응한다. 다만 본문 L187 "이 상위 계획과 D075/D084는 아카이브하지 않는다" 와 L219 "D075/D084와 제외3건은 열린 TASKS에 남기며 계획을 아카이브하지 않는다" 는 D-075·D-064 종결 전에 쓴 문장이다. hold 로직은 계획+audit 에 적힌 PR 만 보므로 #242/#249 머지로는 풀리지 않는다 — 이 문서는 사람이 판단하지 않으면 영구히 `유지` 다(§6 Q3).

---

## 3. `done/` 명명·쌍·INDEX 불일치

집계: 계획 164 · audit 134 · 고아 audit(계획 없음) **0** · audit 없는 계획 30 · `INDEX.md --check` → `INDEX.md 최신`(exit 0). INDEX 재생성은 **CI 에 없고**(`ci.yml` 에 `plans-index` 호출 없음) `/sync`·`/ship` 수동 절차만 있다.

### 3.1 명명 스타일 8종 (stem 기준)
| 스타일 | 수 | 예 |
|---|---|---|
| `D-NNN` | 55 | `D-049`~`D-105` |
| `D-NNN-suffix` | 32 | `D-065-collector-adapter`, `D-078-session-plan` |
| `task-dNNN-*` | 33 | `task-d002-bc-session` … `task-d048-e2e-stack-startup` |
| `task-impl*` | 23 | `task-impl1-gradle-multimodule`, `task-impl4-c1-refund-path` |
| `task-adr*` | 8 | `task-adr0010-service-decomposition`, `task-adr-observability-ssot` |
| `task-<기타>` | 10 | `task-ci-test-matrix`, `task-phase4-closure`, `task-debt-20261021` |
| `ci-*` | 1 | `ci-critical-path` |
| 기타 | 2 | `digest-open-rows`, `sync-context-cost` |

- 같은 D-ID 공간이 두 표기: `task-dNNN-…` 은 D-002~D-048, `D-NNN` 은 D-049~D-105(표기 전환 시점 = D-049). 서브플랜은 `D-NNN-suffix` 로만 존재(`task-d035-{notification,payment,product,user}-container-singleton` 처럼 옛 표기도 서브플랜 4개).
- 증적 디렉터리 `docs/plans/evidence/D098/` ↔ 계획 `done/D-098.md`(계획 L49 가 `docs/plans/evidence/D098/` 로 가리킴, 깨진 링크는 아님). 26개 중 유일한 하이픈 누락.

### 3.2 중복성 stem 가족 (제목 그대로)
- `D-060` "승인과 예약 lease 회수 사이의 fence" / `D-060-followup` "PR #175 기반 보호 승인 복구 보강"
- `D-064` "Toss 테스트 환경 실호출 검증…" / `D-064-merchant-order-id` / `D-064-pg-contract`
- `D-065-*` 11개(`access-denied-mvc`, `collector`, `collector-adapter`, `constraints`, `contract-migration`, `dlq-diagnostic`, `flyway-contract`, `lineage-writer`, `signed-gateway`, `signed-gateway-execution`, 외) — **기본 `D-065.md` 없음**, TASKS D-065 행은 ⏸ 열림
- `D-075` "outbox 로컬 병목 분해 (부분 분석)" / `D-075-outbox-throughput` "발행 처리율 기본값 처분 (+ D-106 ⑥)"
- `D-078` "캐시 이름별 수집 공백의 로컬 보완 (부분 작업)" + `D-078-{capture-prep,latency-metrics,product-actions,retry-load,scheduler-capacity,session-dashboard,session-plan,session-prep}` 8개 + 루트 `D-078-remeasure-plan` — 9개 done stem 모두 "(부분 작업)" 성격이고 TASKS D-078 행은 ◐ 열림. `done/D-078.md` 라는 파일명이 "D-078 부채 종결" 로 읽힐 소지.
- `D-084-signed-recovery`(done) ↔ `D-084`(루트, 열림)
- `D-087` / `D-087-profile-cache` · `D-089` / `D-089-stale-state` · `D-092` / `D-092-ops-closure` / `D-092-slack-split`
- `D-106-{remeasure-prereq,topic-config-race,topic-guard-throttle}`(기본 없음, TASKS-done D-106 ✅) · `D-107-{deploy-topics,recovery-lab-topics}`(기본 없음, TASKS-done D-107 ✅)
- `task-d002-*` 3, `task-d026-*` 2, `task-d027-*` 2, `task-d035-*` 4

### 3.3 frontmatter · PR · audit
- frontmatter 없음 **46**(전부 `task-*` 옛 표기; `R-plan-1` 규칙상 등급은 L 로 간주). 등급 분포(있는 118): M 62 · L 30 · S 26. `archive:` 키가 남은 done 파일 0.
- 등급-audit 규칙 어긋남: `task-debt-20261021`(L, audit 없음) · `D-073`, `D-077`(S 인데 audit 있음). 그 외 audit 없는 계획 30 중 12개는 `D-NNN`(S 등급과 일치 여부는 위 둘 외 문제 없음).
- 계획+audit 어디에도 `pull/N` 링크가 없는 done 계획 **9**: `task-adr0010-service-decomposition`, `task-adr0011-multimodule-structure`, `task-adr0012-db-event-saga-contract`, `task-adr0013-gateway-security`, `task-d010-outbox-trace-context`, `task-d011-harness-hardening`, `task-hpa-manifest`, `task-impl-saga3-2phase-payment-gate`, `task-loadtest-session-c` → INDEX PR 열 빈칸 9개와 동일 집합. 날짜 빈칸 9: `task-d002-rescope`, `task-d027-rationale-split`, `task-d028-topic-describe-setup-race`, `D-051`, `D-054`, `D-078-product-actions`, `D-087-profile-cache`, `task-phase5-baseline`, `task-publish-diagnostics`.
- `PR:` 정본 줄(`/ship` 이 남김)이 있는 done 계획은 80/164.

### 3.4 INDEX 분류 축이 명명 전환을 모른다
`scripts/plans-index.sh` `bucket()` 은 `^task-adr\d+` / `^task-impl` / `^task-d\d+` 만 알아 결과가 `ADR 설계 (7)` · `구현 (23)` · `부채 · 측정 (32)` · **`기타 (102)`** 다. `D-*` 87개 전부와 `task-adr-observability-ssot`(숫자 없음) 가 기타로 간다. `--check` 는 "생성물과 같다" 만 보므로 이 상태로 최신 판정이다.

### 3.5 TASKS ↔ done 대조
- TASKS-done D 행 92(중복 0). 계획 파일이 어떤 표기로도 없는 done 행 14: `D-012`, `D-013`, `D-014`, `D-015`, `D-017`, `D-018`, `D-019`, `D-021`, `D-022`, `D-023`, `D-034`, `D-036`, `D-038`, `D-045`(하네스 이전/계획서 없이 처리).
- done 에 기본 계획이 있는데 TASKS 행이 열린 ID: `D-078`(위 3.2). 서브플랜 부모가 열린 ID: `D-065`, `D-078`, `D-084`.
- manifest 로 이동한 파일 107(2026-10-21 이후 13 manifest) vs done/ 298 — 나머지 ~191 은 D-098 보존 체제 이전의 `git mv`(첫 정리 커밋 `8e8857d` 2026-04-23) 이력이다.

### 3.6 done 안의 증적 링크
done 계획/audit 가 가리키는 `docs/plans/evidence/<dir>` 17개는 전부 존재한다(깨진 디렉터리 링크 없음 — 정규식 스캔 기준). `evidence/D-063`·`D-080`·`D-093`·`D-076` 참조는 `docs/evidence/` 를, `evidence/d078-gke-*.md` 등은 `docs/progress/evidence/` 를 가리키는 것으로 세 위치 혼용이 done 문서 안에 그대로 드러난다.

---

## 4. 증적 3개 위치 — 사실 정리

### 4.1 규약 상태
- `docs/conventions/*.md`, `CLAUDE.md`, `.claude/commands/{plan,work,ship,done,sync}.md` 어디에도 "증적은 어디에 쓴다" 는 규칙이 없다. 커맨드는 `docs/plans/${TASK_ID}.md`·`.audit.md`·`docs/progress/TASKS-done.md`·`docs/progress/PHASE{N}.md` 만 지정한다.
- 코드가 기본값으로 지정한 유일한 위치: `scripts/gke-security-smoke.sh:30` `EVIDENCE_DIR="${EVIDENCE_DIR:-docs/progress/evidence}"`.
- `scripts/ci-docs-only.sh` 는 `docs/**` 전체를 문서로 보되 `docs/plans/fixtures/**` 만 코드로 본다. self-test 기대값에 `docs/evidence/D-063/evaluated-grafana-alerts.yml`(문서) 경로 리터럴이 들어 있다 — 그 파일이 움직이면 self-test 문자열도 낡는다(실패는 아님).

### 4.2 `docs/plans/evidence/` (26 dir, 369 file, 4.1M, gz 225 · zip 6)
생성 시점 2026-10-21~22(첫 커밋 `d740e64`, 커밋 102건), 2026-10-21 병렬 부채 캠페인 worker 산출. 디렉터리 이름 = 계획 stem(`D-NNN[-suffix]`) 24 + 비계획 2(`campaign-records-review`, `D-078-product-barrier-review`).

| dir | 크기 | 구성 | 성격 | 참조하는 문서(디렉터리 밖) | Actions run id |
|---|---|---|---|---|---|
| `D-087` | **1.5M** / 142 | `actions-39589286318/`(107: 6서비스 job log gz 55~57K, lint log, manifest 30K, summary 28K, `build-profile-*` 6 하위) · `first-attempt/` 8 · `second-attempt/` 19(`image.dockerbuild.gz` 36K) · README 11.6K | CI 원 로그·메타 사본 + 사람 README | `done/D-087-profile-cache.audit.md`, `done/task-d039-gradle-setup.md`(D039 정정) — **`done/D-087.md` 자체는 경로를 안 적음** | 36393965323(2026-09-28) · 39587257345 · 39588545903 · 39589286318 |
| `D-087-profile-cache` | **508K** / 16 | user-profile artifact **zip 4**(66~80K; Gradle profile HTML·provenance.json) + user job log gz 4(45~48K) + api json gz | Actions artifact 사본 | `done/D-087-profile-cache.audit.md` | (artifact id 로만: PR189/214/216) |
| `D-078-product-actions` | **480K** / 12 | artifact **zip 2**(183K/188K: `gradle.log` 147~176K + JUnit XML) + job log gz 34K/41K + result/manifest json + README | Actions artifact 사본 | **없음(고아)** — 계획 `done/D-078-product-actions.md` 는 `D-078`·`D-078-product-barrier-review` 만 가리킴 | 39614791169 · 39615963575 · 39615964190 |
| `D-065-collector-adapter` | 212K / 11 | Actions artifact tar.gz 3(20~25K) + manifest json 3(33~45K) + 로컬 테스트 로그 gz | 사본+로컬 | `done/D-065-collector-adapter.audit.md` | 39591368823 · 39592563904 · 39593585908 |
| `D-065-signed-gateway` | 212K / 5 | tar.gz 2(90K/98K) + manifest 2 + README | Actions 사본 | **없음(고아)** | 39587253206 · 39589065938 |
| `D-065-flyway-contract` | 136K / 9 | tar.gz 68K + manifest 40K + `first-attempt/` 6 + README | Actions 사본 | `done/D-065-flyway-contract.md` | 39582861319 · 39583989047 |
| `D-102` | 116K / 7 | `comment-inventory.tsv` 79K, `accepted-candidates.tsv`, `check.py`, `check-integration.py`, `JavaTokenScanner.java`, README | **도구+baseline(유일)** | `docs/conventions/test-comments.md`(실행 지시 `python3 docs/plans/evidence/D-102/check.py --scan`), `done/D-102(.audit).md`, `done/D-106-topic-config-race.md` | — |
| `D-065-constraints` | 112K / 21 | 로컬 실험 json/gz + `cleanup-fix/` 6 + README | 로컬(유일) | `done/D-065-constraints.md` | — |
| `D-089` | 112K / 20 | `raw/` 17 gz(Actions API json·publish log) + json + README | Actions API 사본 | `docs/runbooks/ci-publication-recovery.md` | 38973369532 · 39586719840 |
| `D-075` | 104K / 13 | `raw-results.jsonl` 15K, `results.json` 27K, 추출 스크립트 py 2, 로컬 gradle 로그 gz, README 10K, `summary.md` | 로컬 측정(유일)+도구 | `docs/adr/0045-…`, `done/D-075.md`, `done/D-075-outbox-throughput.md` | (레거시 id 다수, GitHub run 아님) |
| `D-078-retry-load` | 88K / 10 | job log gz 3 + XML gz/tar.gz + json 3 + README | Actions 사본 | `done/D-078-retry-load(.audit).md` | 39627945741 · 39628723409 · 39630546332 |
| `D-084-signed-recovery` | 88K / 5 | tar.gz 65K + manifest + 로컬 로그 gz 2 | 사본+로컬 | **없음(고아)** | 39597261792 · 39605979637 |
| `D-065-dlq-diagnostic` | 76K / 13 | XML gz 5(로컬 1·CI 4) + txt 5 + `SHA256.json` + README | 혼합 | `done/D-065-dlq-diagnostic.md` | — |
| `D-104` | 76K / 7 | `source-scan.json` 43K, `recalculate.py`, `verify-scope.py`, README 6K | 도구+재계산 근거(유일) | `docs/conventions/harness-rationale.md`, `scripts/codex-review-render.sh:10`(주석), `docs/progress/PHASE4.md` | — |
| `D-099` | 64K / 13 | **`check-mutants.py`**, `selftest-results.txt`, json 4, README | **도구** | **`.github/workflows/ci.yml:251` 이 실행**, `scripts/harness-context-selftest.py:180`, `done/D-099.md` | — |
| `D-078-scheduler-capacity` | 60K / 13 | api json gz 4 + log gz 2 + XML gz 2 + json 3 + README | Actions 사본 | `done/D-078-scheduler-capacity.md`, `docs/preservation/plans-merged-subplans-20261024.json` | 39622466849 · 39625382103 |
| `campaign-records-review` | 48K / 2 | `c3a180f-api-results.json` 42K + `c3a180f-review.md`(헤딩 없음, 독립 리뷰 메모) | 사람 리뷰(유일) | `done/task-debt-20261021.md` | 16개 run id 인용 |
| `D-094` | 44K / 9 | XML gz 4(로컬) + `.patch` 2 + json + README | 로컬(유일) | `done/D-094.md` | — |
| `D-065-lineage-writer` | 40K / 7 | XML gz(로컬) + `sql-prototype.py` + README | 로컬(유일)+도구 | `done/D-065-lineage-writer(.audit).md` | — |
| `D098` | 36K / 8 | txt 5 + json 2 + README | 로컬 실행 기록(유일) | `done/D-098.md` | — |
| `D-103` | 32K / 7 | json 3 + txt 3 + README | 로컬(유일) | **없음(고아)** | — |
| `D-088` | 28K / 5 | `inventory.json` 11K + json 2 + txt + README | 재계산 근거(유일) | **없음(고아)** | — |
| `D-065-access-denied-mvc` | 24K / 5 | XML gz 2(CI) + platform log gz + manifest 2 | Actions 사본, **md 없음** | **없음(고아)** | 39588457231 · 39588911211 |
| `D-078` | 24K / 6 | `check-legacy.py`, `check-mutants.py`, 결과 txt 3, README | 도구+로컬(유일) | done 6건(`D-078.md`, `D-078-product-actions.md`, `D-078-retry-load(.audit)`, `D-078-scheduler-capacity.md`, `task-debt-20261021.md`) + manifest | — |
| `D-100` | 12K / 2 | `historical-dependency.txt` + README | 근거(유일) | `done/D-050.md`(정정), `done/D-100.md` | — |
| `D-078-product-barrier-review` | 8K / 1 | README(독립 읽기 검토 원문) | 사람 리뷰(유일) | `done/D-078-product-actions.md`, `done/task-debt-20261021.md` | — |

- 고아(디렉터리 밖 참조 0): **6** — `D-065-access-denied-mvc`, `D-065-signed-gateway`, `D-078-product-actions`, `D-084-signed-recovery`, `D-088`, `D-103`. 각각 대응 계획/audit 는 done/ 에 있으나 경로를 적지 않았다.
- md 없는 dir 3: `D-065-access-denied-mvc`, `D-065-collector-adapter`, `D-084-signed-recovery`. 헤딩 없는 md 1: `campaign-records-review/c3a180f-review.md`.
- 중첩 하위 dir 6: `D-087/{actions-39589286318,first-attempt,second-attempt}`, `D-089/raw`, `D-065-constraints/cleanup-fix`, `D-065-flyway-contract/first-attempt`.
- **재생성 가능성**:
  - gz 225개의 대다수는 GitHub Actions job 로그(`Current runner version…` 헤더), Actions API 응답(`artifacts.json.gz`, `jobs.json.gz`), 또는 artifact 안 JUnit XML 이다. zip 6개·tar.gz 7개는 artifact 원본 그대로. 다운로드 시점에 manifest 가 SHA256 을 묶어 두었다(각 dir 의 `manifest.json`/`*-manifest.json`/`SHA256.json`/`xml-sha256.json`).
  - **지금은 받을 수 있지만 곧 못 받는다.** `gh api` 확인: run 39615964190 artifact `product-barrier-original-seed` `expires_at=2026-10-28`; run 39589286318 `build-profile-*` 2026-11-04, `e2e-evidence-*` 2026-10-28, `*.dockerbuild` 2027-01-19; run 36393965323(2026-09-28) artifact 들 2026-12-27. 로그 보존은 저장소 기본 90일(API 로 확인 불가) → 2026-09-28 run 로그는 2026-12 말 소멸 예정. 따라서 이 사본들은 "현재 유일본은 아니지만 2~3개월 뒤 유일본" 이다.
  - **재생성 불가(유일)**: 로컬 머신에서 돈 XML 10개(`hostname="KimGyuills-MacBook-Pro.local"`: `D-065-dlq-diagnostic/baseline-test`, `D-065-lineage-writer/allocator-jpa`, `D-075/probe`, `D-094/*` 4 등), 로컬 실험 로그/json(`D-065-constraints`, `D098`, `D-103`, `D-099/selftest-results.txt`), 사람 리뷰 메모 2(`campaign-records-review`, `D-078-product-barrier-review`), 모든 `README.md`, 재계산 입력(`D-088/inventory.json`, `D-104/source-scan.json`, `D-102/*.tsv`), 도구 스크립트 py 9개(`D-075` 2, `D-078` 2, `D-099` 1, `D-102` 2, `D-104` 2, `D-065-lineage-writer` 1).
- 코드·규약이 경로를 고정 참조하는 dir: `D-099`(**CI 실행**), `D-102`(규약 지시), `D-104`(규약 링크+스크립트 주석), `D-089`(runbook), `D-075`(ADR-0045). 이동하면 `ci.yml`·`harness-context-selftest.py`·`test-comments.md`·`harness-rationale.md`·runbook·ADR 을 함께 고쳐야 한다(ADR 은 immutable 레이어).

### 4.3 `docs/evidence/` (5 dir, 52 file, 884K)
| dir | 크기/파일 | 내용 | README | 참조 |
|---|---|---|---|---|
| `D-063` | 504K / 30 | run1~3(`execution.log`, `containers.log.gz`, `observations.jsonl.gz`, `manifest.json`, `summary.json`), `evaluated-grafana-alerts.yml`, `provisioned-alerts.yaml`, `environment-equivalence.md`, `independent-pre-execution-review.md`, `artifact-index.json` | 있음 | `done/D-063(.audit).md`, `done/D-070.audit.md`, `docs/learning/drafts/30-…md`, `scripts/ci-docs-only.sh`(self-test 리터럴) |
| `D-093` | 280K / 16 | baseline/·timeout/(`containers.log.gz`, `observations.jsonl.gz`, `provisioned-rules.json`, `summary.json`) | 있음 | `done/D-093.audit.md` |
| `D-080` | 40K / 3 | `class-timings.tsv`, `shutdown-stack.txt` | 있음 | `docs/adr/0028-…`, `PHASE5.md`, `TASKS-done.md`, `done/D-080(.audit)`, `done/task-d032-…(.audit)`, blog 스냅샷 |
| `D-084` | 32K / 1 | `focused-499d8e1.json` | **없음** | `docs/plans/D-084.audit.md`(루트), blog31 json |
| `D-076` | 28K / 2 | `ci-policy-commands.txt`, `ci-policy-local.txt` | **없음** | `done/D-076.audit.md`, `docs/plans/task-debt-orchestration.audit.md` |
- 첫 커밋 2026-10-17(`27fd5d5`, `332a800`, `e258930`…), 2026-10-21(`18026a6` D-093) — 2026-10-17 캠페인(`task-debt-orchestration`) worker 가 만든 위치. 4일 뒤 2026-10-21 캠페인은 `docs/plans/evidence/` 를 새로 만들었다. 둘의 내용 성격(로컬 실행 원자료 + README + manifest)은 같고 위치만 다르다.
- `D-063`·`D-093` 의 `*.log.gz`/`*.jsonl.gz` 는 로컬 Docker 실행 원자료 → 재생성 불가.

### 4.4 `docs/progress/evidence/` (15 md, 176K)
- 전부 산문 Markdown(원자료 없음). 2026-08-09(`pr3c-gke-smoke-20260808-1445.md`) ~ 2026-10-26(`d064-toss-sandbox-20261026.md`). 파일명 4스타일: `dNNN-…-YYYYMMDD`, `pr3c/pr3d-…`, `impl4/impl6-…`, `user-key-…`, `ci-test-matrix-…`, `d073-source-corrections`(날짜 없음).
- 15개 전부 참조됨(ADR-0018/0025/0026/0027/0028/0045/0046, `TASKS.md`, `TASKS-done.md`, `PHASE4/5.md`, runbook 2, done 계획 다수, `scripts/tests/test_kafka_topic_state_guard.py`(d078-gke-20261024)). `TASKS-done.md` 말미 footer 가 `evidence/d073-source-corrections.md` 를 상대 링크로 가리킨다(보존 검사 대상 링크).
- 같은 D-078 부채: 세션 증적 `d078-gke-20261024.md`·`d078-gke-20261025.md` 는 여기, 서브플랜 증적 6 dir 은 `docs/plans/evidence/`, 원자료는 `loadtest/reports/2026-10-2{4,5,6}/`. 세 곳.

### 4.5 불일치 요지
- 세 위치가 공존하는 이유는 규약이 아니라 **작성 주체·시기**다: progress/evidence = `/ship` 세션 증적(2026-08~), docs/evidence = 2026-10-17 캠페인 worker, plans/evidence = 2026-10-21 캠페인 worker. 어떤 문서도 셋을 구분 정의하지 않는다.
- 이름 규칙도 셋: `D-NNN`(docs/evidence), `D-NNN[-suffix]` + 비계획 이름 + `D098`(plans/evidence), 소문자 `dNNN-주제-날짜`(progress/evidence).

---

## 5. 보존 CI 제약 요약 + 도구 가용성

정리 작업이 지켜야 할 규칙(`docs/conventions/docs-preservation.md`, `scripts/docs-preservation-check.py`, `scripts/docs-preservation-ci.py`):
1. 추적된 **모든 `*.md`**(`git ls-files '*.md'`)는 manifest 선언 없이 삭제·이동할 수 없다 — `docs/plans/fixtures/*.md`, `docs/progress/evidence/*.md`, `docs/plans/evidence/*/README.md`, `docs/plans/evidence/D-075/summary.md`, `campaign-records-review/c3a180f-review.md` 도 포함. 비-md(gz/json/py/tsv/zip)는 검사 범위 밖이다(이동해도 CI 가 모른다).
2. `docs/plans/done/*.md`(INDEX 제외)와 `docs/progress/TASKS-archive-*.md` 는 원문 prefix 보존 + 뒤에 추기만 가능; 사실 정정은 manifest `corrections`(old 정확히 1회, 파일당 20줄/2000자 이하, 원본 절반 미만).
3. 이동/분리/병합은 `docs/preservation/<고유이름>.json`(version 1, source SHA256 = 비교 base 원문 바이트, 모든 source 줄을 정확히 한 번 배정)로 선언하고 **명시 stage** 해야 wrapper 가 발견한다. 과거 manifest 14개는 바이트 하나도 바꿀 수 없다(wrapper: `Historical manifest must remain unchanged` → exit 1). 재구성은 **manifest 를 추가**로만 한다.
4. 비교 base 는 최종 PR 의 base SHA 로 고정(편집 후 HEAD 를 base 로 고르지 않음). 링크는 Markdown 상대 링크만 재작성하고 코드·산문 경로·과거 blob URL 은 역사 원문으로 둔다. 여러 줄 backtick span, HTML link/comment, 들여쓴 list 의 link 등은 검사 실패 → 원문을 우회 편집하지 말고 별도 명시 이전으로 처리.
5. 계획 아카이브는 `plans-archive.sh --apply --select … --base … --manifest …` 만(전체 일괄 없음); 부분 완료·orchestration 은 `archive: hold <PR…>` 로 제외; 그 뒤 `plans-index.sh` 재생성과 `docs-preservation-ci.py --base <같은 base>` 통과. INDEX 는 손으로 고치지 않는다.

도구 가용성(읽기 전용 실행 결과):
- `python3 -B scripts/docs-preservation-ci.py --base HEAD` → `[preservation] PASS: task rows, declared ordered transfers, protected history and local links`, exit 0. `--base HEAD~1` 도 PASS. 종료 코드 규약 0/1/2, 실패 시 위반 항목을 stderr 한 줄씩. CI(`ci.yml` lint job "Document preservation")는 같은 wrapper + selftest 2종 + `plans-archive.sh --self-test`.
- `bash scripts/plans-index.sh --check` → `INDEX.md 최신`, exit 0.
- `bash scripts/plans-archive.sh`(dry-run) → 후보 2(`D-078-remeasure-plan`, `D-108`) · 유지 2(`D-084`, `task-debt-orchestration`) · 후보 파일 4.
- `gh` 인증 정상(PR 8건·run/artifact 조회 성공).

---

## 6. 질문 목록 (소유자 결정 필요 — 무엇 / 왜 애매한가 / 선택지)

**계획서 처분**
1. **`D-078-remeasure-plan` 아카이브 시점** / 계획 범위(P1~P6·세션·기록)는 끝났고 PR 도 머지됐지만 TASKS D-078 행은 ⑦(사용자 결제 콘솔 확인) 때문에 ◐ 이다. 규약은 "부분 완료 계획은 옮기지 않는다" 인데 이 계획서는 ⑦ 을 소유하지 않는다. / (a) 지금 아카이브(이후 ⑦ 결과는 TASKS 행에만) (b) `archive: hold 243 246` 를 달고 ⑦ 까지 대기 (c) ⑦ 결과를 계획서에 추기한 뒤 아카이브.
2. **`D-108` hold 갱신 vs 아카이브** / `archive: hold 248` 이 #250 을 모르고, TASKS 행은 ③(base·운영 프로브 정책) 대기로 ◐ 다. 계획서 §9 는 ③ 을 범위 밖으로 선언했다. / (a) 아카이브(③ 은 TASKS 행만) (b) `hold 248 250` 으로 갱신하고 ③ 결정까지 유지 (c) ③ 을 새 D- 행으로 떼어 D-108 행을 닫고 아카이브.
3. **`task-debt-orchestration` 종결 방식** / hold 4 PR 밖에서 D-075(#242)·D-064(#249)가 닫혀 본문 "열린 항목" 표가 낡았고, P4·P6 미체크의 실질 잔여는 D-084(⏸ 사용자 보류)뿐이다. 자동으로는 영원히 `유지`. / (a) D-084 해소까지 루트 유지 (b) 2026-10-26 기준 종결 절을 추기(D-075/D-064 처분 반영)하고 `hold` 에 #242 #249 를 더해 유지 (c) 추기 후 아카이브(이후엔 추기만 가능).
4. **`D-084` 재범위** / ⏸ 보류가 사용자 결정이고 P1 만 열림. 계획서를 그대로 둘지, "P1 분리 → 나머지 완료" 로 재범위할지. / (a) 그대로(보류 상태 유지) (b) P1 을 별도 행/계획으로 떼고 D-084 종결 (c) 결정 보류.
5. **D-078 §9 의 `d002bc-order-create.js` 60초 setup 한계** / "고치지 않는다" 로 끝났고 TASKS 어디에도 없다. 부채로 등재할지 의도된 종결로 볼지. / (a) D-115 하네스 행에 흡수 (b) 기록만(계획서 §9 로 충분) .

**증적 위치·명명**
6. **세 증적 위치의 정본** / 규약 없이 작성 주체·시기로 갈렸다(§4.5). 합칠지, 성격(산문 vs 원자료)으로 나눌지, 그대로 둘지. 원자료 이동은 md 가 아니어서 CI 가 지켜주지 않는다. / (a) 한 위치로 통합 (b) 산문(progress/evidence)·원자료(plans/evidence) 2분법으로 docs/evidence 만 흡수 (c) 현상 유지 + 규약 문서에 셋을 정의.
7. **"도구" 성격의 증적 처분** / `D-099/check-mutants.py` 는 CI 가 실행하고, `D-102/check.py`·`accepted-candidates.tsv` 는 규약이 실행 지시하며, `D-104/README.md` 는 규약·스크립트 주석이 참조한다. 증적 폴더 안에 살아 있는 도구가 있다. / (a) 그대로(이동 시 ci.yml·규약·스크립트 동시 수정) (b) `scripts/` 로 승격하고 증적엔 결과만 남김 (c) 증적 폴더 전체를 "이동 금지" 로 못 박음.
8. **고아 증적 6 dir** / 대응 계획이 done/ 에 있으나 경로를 적지 않았다(`D-065-access-denied-mvc`, `D-065-signed-gateway`, `D-078-product-actions`, `D-084-signed-recovery`, `D-088`, `D-103`). / (a) 각 done 계획에 한 줄 추기로 연결 (b) INDEX 같은 생성 목록으로 연결 (c) 두기.
9. **Actions 사본의 보존 범위(4.1M 중 ~2.5M)** / 로그·artifact 사본은 2026-10-28~2027-01-19 사이에 GitHub 에서 사라져 그 뒤엔 유일본이 된다. 반대로 지금 "결과" 로 쓰이는 것은 README/manifest/result json 이고 원로그는 재검증용이다. / (a) 전부 보존(현재) (b) manifest 의 SHA256 만 남기고 원로그 gz/zip 삭제(재검증 불가 감수) (c) 큰 3개(`D-087`, `D-087-profile-cache`, `D-078-product-actions`)만 선별.
10. **`D098` 디렉터리 이름** / 유일한 하이픈 누락. 바꾸면 `done/D-098.md` L49 의 경로 산문도 낡는다(done 은 append-only). / (a) 디렉터리 rename + D-098.md 에 추기 (b) 두기.
11. **`docs/evidence/D-076`, `D-084` README 부재·`campaign-records-review` 헤딩 부재** / 무엇인지 열어봐야 안다. / (a) README 추가(새 파일은 자유) (b) 두기.

**done/ 명명·INDEX**
12. **`task-dNNN-*` ↔ `D-NNN` 표기 통일** / 같은 ID 공간이 두 표기. done/ 파일 rename 은 이동이라 manifest + 인바운드 링크 재작성(TASKS-archive·PHASE·ADR 다수)이 필요하고, 과거 manifest 13개의 destination 경로는 그대로 남아 역사와 현재가 어긋난다. / (a) 통일 안 함(INDEX 로 해결) (b) 통일(큰 manifest 작업) (c) 새 파일만 `D-NNN` 으로.
13. **INDEX 분류 축 갱신** / `plans-index.sh bucket()` 이 `D-*` 를 몰라 기타 102/164. 스크립트 수정은 코드 변경(문서 전용 아님). / (a) `D-\d+` 버킷 추가·`task-adr-` 비숫자 포함 (b) 분류 자체 제거(단일 표) (c) 두기.
14. **frontmatter 없는 46개·PR 없는 9개 done 계획** / prefix 편집 금지라 frontmatter 를 끼워 넣을 수 없다. / (a) 두기(INDEX/스크립트가 공백 허용) (b) corrections manifest 로 끼우기 — 규약 취지(사실 정정)에 안 맞을 수 있음.
15. **`done/D-078.md` 등 "부분 작업" 기본 stem** / 파일명이 부채 종결처럼 읽히지만 내용은 "(부분 작업)". TASKS D-078 행은 열림. / (a) 두기 (b) 해당 done 파일에 "이 파일은 부분 작업 N 이며 부채 종결 아님" 추기.

**fixtures · .archive · 기타**
16. **스모크 fixture 3 md + `.audit/` 2** / 2026-04 하네스 프로토타입(상태머신) 잔재, 참조 0, 삭제엔 manifest 선언 필요. `saga-contract-matrix.tsv` 는 살아 있는 정본(lint 입력). / (a) 삭제 선언 (b) 하네스 역사로 유지 (c) tsv 만 다른 위치(`scripts/fixtures/`)로.
17. **`.archive/` 46개 state json 로컬 삭제** / gitignored, 읽는 코드 없음(`hpx_state_*` 2026-09-18 삭제), 하지만 git 어디에도 없는 세션 이력(pr_url·세션 id). / (a) 삭제 (b) 로컬 보관.
18. **`PLAN-BLINDSPOTS.md` 위치·갱신** / 살아 있는 규율 문서(B1~B13 + 승격/후보 절, 2026-10-24 B13 추가). `plan.md`·`work.md`·`plans-archive.sh KEEP_ALWAYS`·`harness-context.sh` 가 경로를 고정 참조. TASKS D-114 가 "디스크 예산 점검(PLAN-BLINDSPOTS)" 추가를 요구 중. / (a) 루트 유지 (b) `docs/conventions/` 로 이동(4곳 경로 수정) .
19. **TASKS-done 14행(계획 파일 없음)·manifest 이전 191 파일** / 보존 체제 이전 역사. / (a) 그대로 인정(기록만) (b) 소급 manifest 는 base 가 없어 불가 — 확인만.

**범위 밖이나 정리 작업에 영향**
20. `.claude/worktrees/` 4개(637M; `debt/d106-remeasure-design`·`debt/d075-outbox-throughput`·`debt/d107-deploy-topics` 는 머지된 브랜치, 2개 locked) — grep 집계를 오염시킨다. 제거할지.
21. `docs/learning/drafts/tools/blog3{1,2,3}/`(43M, 문서 스냅샷 md 198개) 는 `docs/progress/evidence/*` 와 `docs/evidence/D-080` 경로를 다량 인용한다. 링크 재작성 때 이 사본을 건드리면 안 되는지(동결 여부) 확인이 필요하다.
