# Survey C — 현행·이력 문서 인벤토리 (읽기 전용, 2026-10-26, main 91cfa1f)

범위: Layer 1(01~07, TASKS.md, consistency-hints.sh, README.md, CLAUDE.md, AGENTS.md) · Layer 2(docs/adr) · Layer 3(docs/progress) · runbooks · conventions · review · learning(목록만). plans/evidence/preservation/loadtest 는 다른 레인.
모든 명령은 읽기 전용으로 실행했다(`harness-context.sh`, `harness-context.sh --check`, `docs/consistency-hints.sh`, grep/python 검사). 저장소에 쓰는 명령은 돌리지 않았다.

---

## 1. 요약 표

| 영역 | 파일 수 | 크기(바이트) | 마지막 git 수정 | 불일치 건수(아래 절 참조) |
|---|---|---|---|---|
| Layer 1 설계 문서 `docs/01~07` | 7 | 112,790 | 01: 2026-04-24 · 07: 10-17 · 03: 10-21 · 02/04/05/06: 10-25 | stale 서술 24건, 절 번호 충돌 1건(§4 중복), 내부 모순 2건(04 §9-6↔§9-1 주석, 04 §10-6↔§9-1), 미반영 ADR 1건(ADR-0027 이 Layer 1 어디에도 없음) |
| 루트 안내 `README.md` `CLAUDE.md` `AGENTS.md` | 3 | 13,220 | 09-22 / 10-17 / 09-25 | README Phase 5·ADR·runbook·TASKS 무언급, CLAUDE.md 문서 맵이 TASKS/progress/runbooks/conventions/plans 누락, CLAUDE.md:129 모놀리스 경로 |
| `docs/TASKS.md` + `docs/consistency-hints.sh` | 2 | 25,130 | 10-26 / 09-30 | 문서 맵이 Phase 4 로드맵 2종만 등재, runbooks/ADR/plans/evidence 미등재 |
| Layer 2 `docs/adr/` | 44 ADR + README + template | ADR 본문 518,905 · README 9,694 · template 1,033 | README 10-25 · template 2026-04-06 | Status 는 44/44 README 일치 · README 제목≠H1 20건 · "관련 Layer 1" 열 불일치 29건 · 템플릿 이탈 ADR 10건 · Update Log 가 Decision 보다 큰 ADR 4건(+50% 초과 4건) · 0038/0039 결번 · immutable ADR 이 gitignore 파일을 증거로 인용 3건 |
| Layer 3 `docs/progress/*.md` | 13 | 989,000 | PHASE5/TASKS-done 10-26 · PHASE4 10-21 · PHASE3 06-13 · PHASE1 03-27 | PHASE5 엔트리 순서 3구역 혼재·헤딩 형식 2종, 하네스가 80개 중 68개만 셈 · TASKS-done 에 regex 비가시 행 1건(D-024) · 잡다 파일 5개 소속 불명 · 종결 뒤 추기(D-073) 7파일 |
| `docs/progress/evidence/` | 15 | 147,000 | 10-26 | 어떤 맵에도 없음 · 고아 1건 · evidence 루트가 3곳(progress/evidence, docs/evidence, plans/evidence) |
| `docs/runbooks/` | 7 | 75,129 | 09-19 ~ 10-25 | 어떤 맵에도 없음(TASKS D-109 행이 redis-cache-fallback 만 언급) · 참조 스크립트/경로는 전부 존재 |
| `docs/conventions/` | 5 | 37,575 | 09-30 ~ 10-26 | 어떤 맵에도 없음(README.md 가 writing.md 만) · 하네스 커맨드가 직접 의존 |
| `docs/review/` | 2 | 22,954 | 2026-04-10 / 04-14 | 어떤 맵에도 없음 · `ci-optimization-analysis.md` 인바운드 0 (완전 고아) |
| `docs/learning/` (gitignore) | 상위 25 + drafts 14 + tools 3 디렉터리 | 45MB(그중 tools 43MB) | 추적 안 됨 | 추적 문서 18곳이 참조(ADR 4건 포함) |

Markdown 링크 깨짐: 범위 내 99파일 737링크 중 실제 깨짐 0 (오탐 2: `PHASE5.md:190` 의 형식 예시 `[PR](...)`, `docs-preservation.md:68` 의 예시 `path#anchor`).
`docs/consistency-hints.sh` 항목 2(ADR 참조 파일 존재) 통과. `harness-context.sh --check` ok.

---

## 2. 문서 맵 vs 실제

### 2-1. 네 개의 맵이 가리키는 경로 (전부 존재)

| 맵 | 등재 경로 | 존재 |
|---|---|---|
| `CLAUDE.md` 문서 참조 맵 (L71-80) | `docs/01~07-*.md`, `docs/adr/README.md` | 8/8 |
| `docs/TASKS.md` 문서 맵 (L8-16) | `docs/progress/TASKS-archive-phase1-3.md`, `TASKS-archive-phase4.md`, `TASKS-done.md`, `phase4-design-roadmap.md`, `phase4-prep-debt-roadmap.md`(§2~5 헤딩 존재 확인), `PHASE1~5.md` | 7행/10파일 전부 |
| `docs/adr/README.md` 인덱스 (L26-69) | 44 ADR + `template.md` | 45/45 |
| `README.md` (L29-42, L20) | `docs/01~07-*.md`, `docs/conventions/writing.md` | 8/8 |

`CLAUDE.md` 가 인용한 절: `docs/02-architecture.md` Section 12(L148 `## 12. 패키지 구조` 존재) · `docs/04-design-deep-dive.md` Section 9-12(L335 존재) · `docs/01-project-overview.md` §4(L82 존재). `loadtest/README.md:3` 의 `04 §10-7`(L563)·`03 §7-1`(L83)도 존재. 140개의 `0N-*.md §X` 참조 중 깨진 것 4건: `docs/adr/0001:62 → 04 §1`(04 는 §8부터), `docs/adr/0010:49 → 04 §16`(§16 은 07), `docs/plans/done/task-adr0010-service-decomposition.md:50` 2건(줄번호를 § 로 적음).

### 2-2. 어떤 맵에도 없는 문서/디렉터리 (인바운드 참조 수는 `git ls-files` 전체에서 basename grep, 자기 자신 제외)

| 경로 | 인바운드 | 비고 |
|---|---|---|
| `docs/runbooks/approval-recovery.md` | 1 (`docs/03-requirements.md`) | 맵 없음 |
| `docs/runbooks/ci-publication-recovery.md` | 2 (ADR-0030, plans/done/D-089) | 맵 없음 |
| `docs/runbooks/dlq-recovery.md` | 25 (코드 javadoc, ADR-0020/0021/0022, evidence) | 맵 없음 |
| `docs/runbooks/outbox-failed-recovery.md` | 3 | 맵 없음 |
| `docs/runbooks/redis-cache-fallback.md` | 16 (`docs/TASKS.md` D-109 행, 04, ADR-0046) | TASKS 행 본문에서만 |
| `docs/runbooks/slack-webhook-secret.md` | 6 (k8s secret.yml, application-k8s.yml) | 맵 없음 |
| `docs/runbooks/user-jwt-key-rotation.md` | 8 (gateway 코드, PHASE4, evidence) | 맵 없음 |
| `docs/conventions/codex-review.md` | 8 (`.claude/commands/plan.md`·`work.md`) | 맵 없음, 하네스 의존 |
| `docs/conventions/docs-preservation.md` | 8 (`.claude/commands/{done,ship,sync}.md`, `.agents/skills/*`) | 맵 없음, 하네스 의존 |
| `docs/conventions/harness-rationale.md` | 11 | 맵 없음 |
| `docs/conventions/test-comments.md` | 2 (plans/done/D-102) | 맵 없음 |
| `docs/conventions/writing.md` | 9 (`README.md`, `.githooks/commit-msg`, `ship.md`) | README 만 |
| `docs/review/final-report.md` | 4 (ADR-0007, PHASE3, archive1-3, plans/done/D-052) | 맵 없음 |
| `docs/review/ci-optimization-analysis.md` | **0** | 완전 고아 |
| `docs/progress/d001-metrics-histogram-fix.md` | 1 (`TASKS-archive-phase1-3.md`) | 맵 없음 |
| `docs/progress/loadtest-tool-evaluation.md` | 2 (PHASE3, plans/done/task-jmeter-to-k6) | 맵 없음 |
| `docs/progress/design-archive-phase1-2.md` | 7 (02, 05, PHASE5, TASKS-done) | 맵 없음(Layer 1 본문이 가리킴) |
| `docs/progress/evidence/` 15파일 | 아래 §7-4 | 맵 없음. `impl4-c2b4b-original-timestamp-null-20260913.md` 인바운드 **0** |
| `docs/consistency-hints.sh` | 19 (`ship.md`, `.claude/scripts/lib/ship.sh`, ADR-0005, 계획서) | 맵 없음 |
| `docs/adr/template.md` | 10 (`done.md`, 계획서) | adr/README 만 |
| `docs/plans/`, `docs/evidence/`, `docs/preservation/` | 하네스 커맨드·스킬 | 네 맵 모두 없음(다른 레인) |
| `docs/learning/` | §2-5 | gitignore |

`.claude/commands/ship.md:250` 은 Phase 4 시절 경로 `docs/progress/phase4-prep-debt-roadmap.md` 의 "해당 행에 ✅" 를 여전히 Step 으로 둔다.

### 2-3. 백틱 경로 중 존재하지 않는 것 (현행 문서만; 이력 문서는 당시 경로라 제외)

- `CLAUDE.md:129` `src/main/resources/db/migration/` — 루트 모놀리스 경로. 현재는 `<service>/src/main/resources/db/migration/` 5곳.
- `docs/adr/0045-outbox-polling-throughput-defaults.md:190` `docs/plans/D-075-outbox-throughput.md` — `docs/plans/done/` 으로 이동됨(ADR immutable, README 원칙상 "파일명 오류" 는 Update Log 정정 대상).
- `docs/02-architecture.md:272` 의 `src/main/resources/db/migration/` 은 "각 서비스의" 수식어가 있어 문맥상 맞음.
- 이력 문서(ADR-0003~0020, PHASE3/4, 아카이브, TASKS-done, review/final-report)에는 `k8s/app/peekcart-deployment.yml`, `src/main/java/com/peekcart/global/...`, `k8s/base/services/peekcart/...`, `docs/00-lagacy.md`, `docs/plans/.audit`, `docs/check-consistency.sh` 등 모놀리스·구구조 경로 100건 이상. 이력이므로 고칠 대상이 아니라 "이력임" 을 알리는 장치가 필요한지가 질문(§10 Q7).

### 2-4. 문서 레이어 원칙(CLAUDE.md:82) 위반 후보

원칙: Layer 1 = 현재 상태(What) · Layer 2 = 결정 근거(Why, immutable) · Layer 3 = 작업 이력(When).

(a) **결정 근거가 progress/TASKS 에만 있음**
- `docs/progress/loadtest-tool-evaluation.md` — JMeter→k6 도구 결정. 본문이 스스로 "ADR 급 결정 아님" 이라 적음(L5). 그러나 01/03/06 의 nGrinder/k6 서술이 이 문서를 근거로 바뀌었다(커밋 016a0a0).
- `docs/progress/phase4-design-roadmap.md §0` "서비스 경계 결정 (2026-06-13 확정)", `phase4-prep-debt-roadmap.md §0` "트리아지 의사결정 (2026-06-07 확정)" — 결정 표가 progress 에 있음(각각 ADR-0010 과 연결되어 있으나 트리아지 자체는 ADR 없음).
- Phase 5 "로드맵 없는 단계" 결정 — `docs/progress/PHASE5.md:147-163` "Phase 5 는 무엇이 다른가", `docs/TASKS.md:22-34`, `docs/07-roadmap-portfolio.md:117-124` 세 곳에 근거가 분산. ADR 없음.
- 2026-10-25 "사용자 결정" 묶음 — D-065·D-084 보류, D-108 ③ 범위 밖, "상한 1 유지" 가 `docs/TASKS.md` 행 본문과 `PHASE5.md:998-1078` 엔트리에 기록. "상한 1 유지" 만 ADR-0046 으로 승격됨. 나머지는 범위 결정이라 ADR 대상인지 애매(§10 Q9).
- `docs/progress/PHASE5.md:886` "D-075 후속 정책 결정 보류" — 정책 미결 상태가 PHASE5 에만.

(b) **현재 상태가 Layer 1 에 없고 progress/plans/TASKS 에만 있음**
- ADR-0027(base MySQL CPU 2000m)이 Layer 1 어느 문서에도 언급되지 않음(README 열은 02 라 적음). `grep ADR-0027 docs/0*.md` 0건, `2000m` 0건.
- D-078/D-108 측정 결과(초당 약 122건 정체, Hikari 풀 10 한계, DLQ/주문 0.059%, outbox 발행 대기 p50 83초)는 `TASKS.md` 행·`PHASE5.md`·`docs/plans/D-108.md`·`evidence/d078-gke-2026102{4,5}.md` 에만 있고, `04 §10-7` 은 Phase 3 환경까지만 기술. `03 §7-1:85` 는 "최종 목표값은 Phase 3 Task 3-4 baseline 으로 확정합니다" 라고 미래형으로 남아 있음.
- Phase 5 ADR(0028~0046)의 결과가 Layer 1 에 반영된 비율은 §6-3 참조(README 열 선언 대비 미언급 17건).

(c) **Layer 1 안의 이력 서술**: 02 §4-2 Phase 1~4 표, 03 §6-3/6-4/6-5/7-2 의 "Phase 1: `@TransactionalEventListener`" 불릿, 04 §9-4/§9-6/§10-5(면접 대응 포함), 07 §16 Phase 1~4 로드맵 전체. D-053 아카이브 분리는 다이어그램·패키지 구조·ERD 만 옮겼다.

(d) **레이어 미배정**: runbooks(운영 절차), conventions(하네스 규약), review(2026-04 리뷰 보고서), plans, evidence, preservation 은 세 레이어 어디에도 속하지 않는다. `docs/adr/README.md:15` 도 Layer 1/ADR 만 말한다.

### 2-5. `docs/learning/`

- `.gitignore:51` `docs/learning/` 전체 제외. 상위: 블로그 00~18편 완성본(`00-why-monolith-to-msa.md` … `18-phase4-prep-design-debt.md`), `debt-resolution-retrospective.md`, `k8s-fundamentals-reconciliation.md`, `learning-roadmap-phase4-prep.md`, `learning-roadmap-phase4-5.md`, `drafts/`.
- `drafts/`: 19~32편 초안 14개(`19-peekcart-monolith-peel-order.md` … `32-peekcart-review-loop-boundaries.md`) + `tools/`.
- `drafts/tools/blog31`(93항목)·`blog32`(93)·`blog33`(127): CI job 로그(`job-*.log`, `*-durations.txt`), `alert-run*.log`, `diff-*.patch`, `commits-*.txt`, `access-attempts.json`, `payload/`, `hash-inventory.json`, `flyway/` 등. **블로그 29~32편(ci-critical-path, e2e-verification-boundary, simplification-invariants, review-loop-boundaries) 집필 때 증거를 모은 작업 디렉터리**다. `docs/TASKS.md` 사본 등은 `blog33/current/` 아래 스냅샷으로 추정(내용은 더 보지 않음).
- 추적 저장소에서 `docs/learning` 을 참조하는 곳 18파일: **immutable ADR 4건**(`0022:81`, `0025:138,158`, `0032:114` → `drafts/tools/blog31/payload/`, `0033:129` → `blog32/hash-inventory.json`), `docs/plans/evidence/D-100/README.md:41`(`blog32/flyway/historical-dependency.log` 가 원본), PHASE3/PHASE4/TASKS-done, 계획서·audit 9건. 즉 ADR 과 증거 README 가 **저장소 밖(gitignore) 파일을 근거로 인용**한다. 또 `replay-entrypoint-lint.sh` 가 이 디렉터리 때문에 로컬에서 실패한다는 audit 기록 4건(`docs/plans/D-108.audit.md:13` 등).

---

## 3. 단계 종결 선례 체크리스트

### 3-1. Phase 1→2→3 (2026-03~04)

- `docs/progress/PHASE{1,2,3}.md` 머리의 `## Phase N 목표` Exit Criteria 체크박스를 `[x]` 로 바꾸고, PHASE3 는 L15 에 `→ **Phase 3 종결 (2026-04-29)**. 다음: Phase 4 MSA 분리 준비.` 한 줄 추가. PHASE3 L968 엔트리 제목에 `(Phase 3 종결)`, L1023 "Exit Criteria 모두 충족 → Phase 3 종결".
- `docs/TASKS.md`(당시 단일 파일) 헤더를 `## 현재 Phase: Phase 3 — 인프라 / 테스트 ✅ (2026-04-29 종결, throughput Threshold 는 D-002 추적으로 회부)` 로. 남은 항목은 D- 로 회부. `## 다음 Phase 예정` 절에 Phase 4 한 줄(현 `TASKS-archive-phase1-3.md:445`).
- 단계 종결 뒤에도 PHASE3.md 에 2026-06-07~06-13 의 Phase 4 진입 전 부채(D-012~D-019) 엔트리가 계속 append 됨(L1246~1470). 종결과 로그 마감이 분리되어 있었다.
- **분리(아카이브) 2026-06-07 `da04d69`**: 비대한 TASKS.md(514줄 삭감)를 `docs/progress/TASKS-archive-phase1-3.md`(머리 주석 "⚠️ 아카이브 문서: 2026-06-07 분리 … 이관") 로 통째 이동, 새 TASKS.md 는 D-002·D-012~014 만. 동시에 `phase4-prep-debt-roadmap.md` 신설. **보존 manifest 없음**(preservation CI 는 2026-10-21 D-098 도입).
- **진입 2026-06-14 `f14799a`**: TASKS.md 헤더 Phase 4 로, `PHASE4.md` 신설(목표·Exit Criteria `[ ]`·"작업 이력" 절), `phase4-design-roadmap.md` 신설.

### 3-2. Phase 4→5 (2026-09-22 ~ 09-30), 복제용 체크리스트

| # | 한 일 | 파일 | PR/커밋 |
|---|---|---|---|
| 1 | 종결 계획서 작성(등급 S, 코드 0) — 순서표 ①~⑥ 머지 근거표, 미착수 행(L-004)을 코드로 반증 확인 | `docs/plans/done/task-phase4-closure.md` | #133 (`500cc09`) |
| 2 | TASKS.md 헤더 2곳 `(예정)` → `(✅ 완료, 2026-09-22, [#133])` + 종결 근거 blockquote | `docs/TASKS.md`(현 `TASKS-archive-phase4.md:17-22`) | #133 `0bb195a`, `ee04c8f`(PR 링크 반영) |
| 3 | 순서표 미착수 행을 **지우지 않고** `→ 미착수, D-030 으로 승격 이월` 표기 + D-030 행 신설 | 같은 파일 L32 | #133 |
| 4 | PHASE4.md 에 종결 엔트리 append(무엇/왜 지금/종결 근거표/남은 한 줄 처분/다음) | `docs/progress/PHASE4.md:3327-3375` | #133 |
| 5 | 검증: `harness-context.sh`(파서 안 깨짐·D-030 노출), `plans-index.sh --check` | — | #133 |
| 6 | 기반 세팅 계획서 | `docs/plans/done/task-phase5-baseline.md` | #134 (`8885555`) |
| 7 | TASKS.md `## 현재 단계:` 를 Phase 5 로 교체, Phase 4 설계·구현 표를 아래 `## Phase 4 — MSA 분리` 절로 합침, 부채표 제목 `개발 부채 / 작업 (Tech Debt & Backlog)`, 다음 번호 규칙 | `docs/TASKS.md` | #134 (`ccee171`, `c7843ad`) |
| 8 | `PHASE5.md` 신설(하네스가 `현재 단계` 줄에서 N 을 읽어 `PHASE{N}.md` 를 열므로 없으면 파서 고장) — "Phase 5 는 무엇이 다른가"·"이월 항목 (Phase 4 → 5)" 표·`## 작업 이력` | `docs/progress/PHASE5.md` | #134 |
| 9 | 07 §16 에 Phase 5 절 추가, 작업 목록·Exit Criteria 는 의도적으로 비움 | `docs/07-roadmap-portfolio.md:117-124` | #134 |
| 10 | 이월 항목 완료 반영·전수조사 부채 등록 | TASKS/PHASE5 | #161 (`0a6c093`) |
| 11 | **TASKS 분리(D-052)**: `TASKS-archive-phase4.md`(종결 Phase 4·버킷 1·보류 절 원문 이동, 머리 "⚠️ 아카이브 문서: 2026-09-30 분리 (D-052) … 이후 수정하지 않는다") + `TASKS-done.md`(✅ D-행 44개 원문 이동, 머리에 추가 규칙) 신설. V1(줄 집합)~V6 검증, `harness-context.sh` 완료 카운트·`ship.sh:88` 재진입 grep·`ship.md`/`done.md` 이동 규칙 갱신 | `docs/plans/done/D-052.md` | #170 (`2a2b6a8`) |
| 12 | **Layer 1 아카이브 분리(D-053)**: 02 §5/§12·05 §11 의 Phase 1·2 다이어그램·패키지 구조·ERD 를 `design-archive-phase1-2.md` 로 원문 이동(머리 "⚠️ 아카이브 문서: 2026-09-30 분리 (D-053)"), Layer 1 에 포인터 1줄, `consistency-hints.sh` 갱신 | `docs/plans/done/D-053.md` | #171 (`f04d23c`) |
| 13 | 역사 추기 선례: 2026-10-17 D-073 사실 정정을 닫힌 문서 7곳(PHASE4, TASKS-archive-phase4, TASKS-done, ADR-0025/0027, evidence, 계획서)에 **머리 1줄 안내 + 말미 `## 2026-10-17 역사 기록 정정 (D-073)` 절** 로 append | — | #177~#180 |

**11·12 모두 preservation manifest 없이 수행됨.** 지금은 `docs-preservation-ci.py` 가 추적 Markdown 삭제·`docs/progress/TASKS-archive-*.md`·`docs/plans/done/*.md` 변경을 막으므로, 같은 분리를 다시 하려면 `docs/preservation/<이름>.json` 의 sources/destinations(모든 원본 줄을 정확히 한 번 배정, 새 framing 은 literal 로 선언)이 필수다(§9).

### 3-3. Phase 5 가 선례와 다른 점

- Exit Criteria 가 없으므로 "체크박스 [x] 전환" 단계가 없다. `docs/TASKS.md:34` "단계 종결 조건 없음", `PHASE5.md:157` "Exit Criteria 가 없다. 소진할 목록이 없으므로 단계가 종결되지 않는다".
- `## 다음 Phase 예정` 에 해당하는 절이 TASKS/PHASE5/07 어디에도 없다(§4).
- PHASE5.md 는 종결 엔트리가 들어갈 자리(최신이 어디인지)가 세 구역으로 갈려 있다(§7-1).

---

## 4. 다음 단계 정의 유무

**없음.** 저장소 전체(docs, CLAUDE.md, README.md)에서 `Phase 6`, `다음 Phase`, `Phase 5 종결/종료/마감` 0건.

인용(원문):
- `docs/07-roadmap-portfolio.md:119-121`: "**사전 순서표가 없는 단계다.** Phase 1~4 로 계획된 로드맵은 소진됐고, 이후 작업은 필요하다고 판단한 시점에 추가한다. 따라서 이 문서에는 Phase 5 의 작업 목록도 Exit Criteria 도 적지 않는다 — 적는 순간 로드맵이 되기 때문이다."
- `docs/TASKS.md:34`: "**단계 종결 조건 없음.** Phase 4 처럼 순서표 소진으로 닫히지 않는다. 열린 표가 곧 현황이다."
- `docs/progress/PHASE5.md:157-158`: "**Exit Criteria 가 없다.** 소진할 목록이 없으므로 단계가 종결되지 않는다. `docs/TASKS.md` 의 열린 표가 곧 현황이다"

`다음 단계` 검색 결과 23건은 전부 배포 순서(02:207, runbook gate 표)·Phase 4 로드맵의 "§4/§6 다음 단계"·계획서 절 제목이다. 현재 열린 행 11개(D-065 ⏸, D-078 ◐, D-084 ⏸, D-108 ◐, D-109~D-115 🔲)는 "Phase 5 를 닫으려면" 의 조건으로 어디에도 묶여 있지 않다.

---

## 5. Layer 1 현행 문서 stale 항목 (파일별)

공통: 01~07 은 한 통합 문서를 쪼갠 것이라 `## N.` 번호가 파일을 가로질러 이어진다(01: §1~4 · 02: §4,5,12 · 03: §6~7 · 04: §8~10 · 05: §11 · 06: §13~14 · 07: §15~16). **§4 가 01(`## 4. 운영 환경`)과 02(`## 4. 아키텍처 방향`)에 중복**된다. 02 는 §5→§12 로 건너뛴다. Phase 5 에 추가된 절 10개(03 L145/151/157/175, 04 L598/606, 05 L325/333/337/347)는 번호 없이 `### 제목 (see ADR-00NN)` 형식이다. 07 L128 꼬리말 "*PeekCart 설계 통합 문서 v5.0 — 멀티 에이전트 리뷰 (Tier 1~3) 반영 완료*".

### `docs/01-project-overview.md` (2026-04-24, 6개월 미수정)
- L5 "작성 기준: Phase 1 (모놀리식) → Phase 4 (MSA) 전체 설계" — Phase 5 반영 없음.
- L58 `Redis 7.x | 상품 캐싱, 분산 락, 블랙리스트`, L78 `분산 락 | Redis (Redisson) | MySQL Named Lock` 채택 표 — ADR-0025 가 재고 경로 분산 락을 제거. 단 `common/src/main/java/com/peekcart/global/lock/DistributedLockManager.java`·`RedissonConfig.java` 는 코드에 남아 있어 "분산 락 자체가 없다" 고도 못 적는다(§10 Q11).
- L34/L64 "nGrinder / k6" — `loadtest/scripts/` 에 `ngrinder-product-query.groovy` 1개와 k6 `*.js` 다수. 이후 측정 세션(D-002/D-078)은 전부 k6. nGrinder 가 "현재 도구" 인지 애매.
- §4-1 표(L88-92)가 "Phase 3 (Task 3-4 ~) · Phase 4 | GCP/GKE" 에서 끝남. Phase 5 행 없음. GKE 환경은 D-078 세션에서 `e2-standard-4` 외 측정 overlay 8종(`k8s/overlays/gke-d002a*`, `gke-d078-*`)이 생겼으나 §4 는 모름.
- L101 ADR-0003 Deprecated 서술은 정확.

### `docs/02-architecture.md` (2026-10-25)
- §4-2 표(L20-25) Phase 1~4 에서 끝남. §4-3 L31 "Phase 3 (Task 3-4 ~) · Phase 4" 역시.
- L34 "HPA 로 Order Service Pod 자동 수평 확장 검증" — `k8s/overlays/gke/hpa.yml` 은 order-service 와 gateway 둘(§12 트리 L194 가 스스로 적음). 본문과 트리가 다름.
- §12 트리(L226-270)는 `settings.gradle` 11모듈과 일치(검증함). L163 이 D-101 정정 전 기록을 GitHub blob URL 로 가리킴(이력 포인터 방식의 선례).
- L205 "최초 배포 순서 (fresh 클러스터, minikube 기준)" — minikube 가 상시 로컬 환경이라는 01 §4-2 와 정합. stale 아님.
- ADR-0027(MySQL 2000m) 미언급(README 열은 02 라 함).

### `docs/03-requirements.md` (2026-10-21)
- L22/L31/L41/L101/L105 "Phase 1: `@TransactionalEventListener`" — 현재 상태 문서 안의 Phase 1 서술.
- L85 "최종 목표값은 Phase 3 Task 3-4 부하 테스트의 baseline 측정 결과로 확정합니다" — Phase 3 종결(2026-04-29) 후 6개월, 확정값 미기재. L92/L96 "nGrinder".
- L115 "**재고 차감 시점**: 주문 생성 시 즉시 차감 (전략 A)" — ADR-0012 §D3/ADR-0016 예약 모델과 충돌. 04 §9-6 L230 주석은 "Phase 4 는 전략 B 계열로 전환" 이라 적는데 03 은 전략 A 를 현재로 둠.
- L130 "`application-dev` / `prod` profile" — 실제 프로파일 파일은 `application-k8s.yml`(notification-service 참조 확인). 미검증 항목이라 확인 필요.
- §6-6 API 표에 `/api/v1/payments/approval-recoveries`(controller `@RequestMapping` 존재)가 없음. D-065 가 말하는 `/api/v1/admin/deadletter/*` 도 없음(코드 쪽 매핑 방식은 미확인).

### `docs/04-design-deep-dive.md` (2026-10-25)
- §9-6 제목 "재고 차감 시점 — 주문 생성 시 즉시 차감 (전략 A)" + L232 "포트폴리오 범위에서는 전략 A를 채택합니다" vs 같은 절 L230 blockquote "Phase 4 … 전략 A 대신 **Product 예약 모델(전략 B 계열)** 로 전환한다". 제목·결론이 Phase 3 상태.
- §10-6 L547 "분산 락 Redis 장애 시 DB 낙관적 락 fallback이 설계되어 있지만(섹션 9-1)", L551 "분산 락: DB 낙관적 락(version)이 최후 방어선" — §9-1 L139-140 은 "Redis 분산 락과 `InventoryLockFacade`는 제거했습니다". **같은 문서 안 모순.**
- §10-1 L412 "폴링 주기(예: 1~5초)" vs L306-307 주석 "order·product·payment 500ms·400ms, notification 5s·4s (see ADR-0045)". 같은 문서에 옛 값과 새 값.
- §10-5 L539-542 "면접 대응:" 블록 — 설계 문서 안의 포트폴리오 대본.
- §10-7 환경별 측정 방침이 Phase 3 GKE 까지. Phase 4/5 측정(ADR-0027 2000m baseline, D-078 세션 overlay, 측정 전 토픽 가드)이 없음.
- §9-2 L164, §9-4, §9-7 등 "Phase 4 에서 … 한다" 미래형 다수(Phase 4 는 종결).

### `docs/05-data-design.md` (2026-10-25)
- 가장 현행에 가깝다. L333 `(ADR-0036)` 만 `see` 없이 표기(형식 불일치). Phase 5 추가 절 4개 번호 없음.

### `docs/06-testing-strategy.md` (2026-10-25)
- §13-1 표 L8 "Redis 캐시/락" 통합 테스트 대상에 락이 남음.
- §13-1-a 제목 "(구현 ④-d-2)" — 계획 내부 식별자가 현행 문서 제목에(`docs/conventions/test-comments.md` 는 코드 주석에서 이런 표식을 금한다).
- §14 표 L107/109/110 nGrinder 3행 — 실제 세션 스크립트는 k6(`loadtest/scripts/d002*.js`). "시나리오" 가 계획인지 실적인지 애매.
- §13-2 커버리지 목표(90/80%)는 측정 근거 참조 없음(미검증).

### `docs/07-roadmap-portfolio.md` (2026-10-17)
- L8 "nGrinder 부하 테스트 결과 TPS X → Y로 개선" — 자리표시자 X/Y 가 그대로.
- L9, L84 "`peekcart` Pod … (Phase 4 MSA 분리 이후 Order Service HPA 로 재정의 예정)" — Phase 4 종결 후 "예정" 미해소. 단일 `peekcart` Deployment 는 존재하지 않음.
- L25 "Redis | 캐싱/분산 락/블랙리스트/Grace Period" (L5 는 "재고 경로의 Redis 분산 락은 제거했습니다" 라 같은 절 안에서 상충).
- §16 Phase 1~4 로드맵 전체가 완료 표기 없이 "계획" 형태 그대로. L117-124 Phase 5 절은 §4 인용대로.
- L128 꼬리말 v5.0.

### `README.md` (2026-09-22)
- L5 "작성 기준: Phase 1 (모놀리식) → Phase 4 (MSA) 전체 설계", L58 인프라 행 Phase 3 서술. Phase 5·TASKS.md·ADR·runbooks·conventions(writing.md 제외)·progress 를 전혀 안내하지 않음. 훅 안내는 현행.

### `CLAUDE.md` (2026-10-17)
- L66 "모놀리식 → MSA (Phase 1~4)". L101 패키지 구조 `com.peekcart.global.{config|exception|jwt|response}` — `jwt` 는 `peekcart-common-auth`·`user-service` 의 `global/jwt` 에 있어 반쯤 맞음. L129 `src/main/resources/db/migration/` 모놀리스 경로(§2-3). L140 ADR-0009·ADR-0015 를 SSOT 로 인용하나 둘 다 Partially Superseded(0015 → 0019/0024). 문서 맵이 TASKS/progress/runbooks/conventions/plans 를 안 가리킴(§2-2).

### `AGENTS.md` (2026-09-25)
- `.agents/skills/{sync,next,plan,work,ship,done}/` 존재 확인. 현행.

### `loadtest/README.md` (다른 레인, 상단만)
- L1 "(Phase 3 Task 3-4)", L34 `docs/progress/PHASE3.md` 2026-04-08 근거, L39 "peekcart 이미지" 단일 이미지, L145 "위 절차는 **Phase 3 모놀리스** 기준이다(`seed.sql` 이 단일 스키마를 전제한다)". 디렉터리 트리(L8-23)는 `d002*`, `d026-*`, `d078-*` 스크립트 15개를 빠뜨림.

---

## 6. ADR 표 + 상태 불일치 + 0038/0039

### 6-1. 전체 표 (파일 Status 줄 ↔ `docs/adr/README.md` 인덱스)

| # | 파일 Status | README Status | 일치 | 비고 |
|---|---|---|---|---|
| 0001 | Accepted | Accepted | ✓ | README L1 열 "02, 04" 인데 두 문서 모두 ADR-0001 미언급 |
| 0002 | Accepted | Accepted | ✓ | 02, 07 미언급 |
| 0003 | Deprecated — 내용 ADR-0004 §Context 로 흡수 (2026-04-07) | Deprecated | ✓ | 01·04 가 언급하나 README 열은 "(ADR-0004 흡수)" |
| 0004 | Accepted | Accepted | ✓ | 02·03 도 언급(열 미기재) · Update Log(1,171B) > Decision(857B) |
| 0005 | Partially Superseded by ADR-0006 | PS | ✓ | 0006 존재 |
| 0006 | Accepted | Accepted | ✓ | |
| 0007 | Accepted | Accepted | ✓ | README 제목 ≠ H1 |
| 0008 | Accepted | Accepted | ✓ | 02 미언급 |
| 0009 | Partially Superseded by ADR-0015 | PS | ✓ | 0015 존재 · 04·CLAUDE.md 도 언급 |
| 0010 | Accepted | Accepted | ✓ | 04 미언급 |
| 0011 | Partially Superseded by ADR-0014, ADR-0033 | PS | ✓ | 둘 존재 · 제목≠H1 |
| 0012 | Partially Superseded by [ADR-0016], [ADR-0020], [ADR-0034] | PS | ✓ | 셋 존재 · Update Log 3건 4.8KB(Decision 8.3KB 의 58%) · 0018/0021/0023 도 "ADR-0012 D3 구체화/재기록" 을 주장하나 Status 줄에 없음 |
| 0013 | Accepted | Accepted | ✓ | |
| 0014 | Accepted | Accepted | ✓ | |
| 0015 | Partially Superseded by ADR-0019, ADR-0024 | PS | ✓ | 둘 존재 · 제목≠H1 · 02 미언급, CLAUDE.md 언급 |
| 0016 | Accepted | Accepted | ✓ | 제목≠H1 |
| 0017 | Accepted | Accepted | ✓ | 제목≠H1 · 06 도 언급 |
| 0018 | Partially Superseded by [ADR-0020] | PS | ✓ | 제목≠H1 · 02 도 언급 · 본문 30.8KB(Decision 16KB) |
| 0019 | Accepted | Accepted | ✓ | 02 미언급 |
| 0020 | Partially Superseded by [ADR-0021] · [ADR-0022] | PS | ✓ | 제목≠H1 · 02 미언급, 05 언급 · 51.4KB(Decision 33.4KB, 최대) |
| 0021 | Accepted | Accepted | ✓ | 제목≠H1 · 02·05 미언급 · Consequences 가 Alternatives 앞 |
| 0022 | Accepted | Accepted | ✓ | 제목≠H1 · 04·05 미언급, 06 언급 · References 절 없음 |
| 0023 | Partially Superseded by [ADR-0034] | PS | ✓ | 제목≠H1 · 03·04 미언급 · "관련 문서" 절 |
| 0024 | Accepted | Accepted | ✓ | 제목≠H1 · 02 미언급, 04 언급 |
| 0025 | Accepted | Accepted | ✓ | 제목≠H1 · 02·05 미언급, 03·07 언급 · Status 위에 D-073 안내 1줄 · `## 2026-10-17 사실 정정 (D-073)` 절이 Update Log 가 아닌 별도 절 |
| 0026 | Accepted | Accepted | ✓ | 제목≠H1 · 05 미언급 |
| 0027 | Accepted | Accepted | ✓ | 02 미언급(Layer 1 어디에도 없음) · Update Log(1,039B) > Decision(885B) · D-073 안내+정정 절 |
| 0028 | Accepted | Accepted | ✓ | Update Log 3건 2,855B > Decision 2,395B |
| 0029 | Accepted | Accepted | ✓ | |
| 0030 | Partially Superseded by [ADR-0044] | PS | ✓ | 제목≠H1 · Update Log 2건 1,349B(Decision 의 71%) |
| 0031 | Accepted | Accepted | ✓ | 제목≠H1 |
| 0032 | Accepted | Accepted | ✓ | 제목≠H1 · Update Log 1,498B(80%) · L114 gitignore 파일 인용 |
| 0033 | Accepted | Accepted | ✓ | 제목≠H1 · "Related" 절 · Update Log 1,371B(71%) · L129 gitignore 파일 인용 |
| 0034 | Accepted | Accepted | ✓ | 06 도 언급 |
| 0035 | Accepted | Accepted | ✓ | 절 구성 이탈(Limits and operations/Compatibility/Rollout diagnostics, Alternatives·Consequences 없음) |
| 0036 | Accepted | Accepted | ✓ | 03 미언급 · References 없음 |
| 0037 | `- Status: Accepted`(볼드 없음) | Accepted | ✓ | 04 미언급 · "Alternatives"/"Consequences and verification" |
| 0040 | Accepted | Accepted | ✓ | 제목≠H1 · 전부 한국어 절(결정/대안과 제한/필수 반례/미해결/구현된 프로토콜/실행 전 독립 검토 반영), Context 없음, `- **수용 범위**`·`- 배경:` 필드 |
| 0041 | Accepted | Accepted | ✓ | |
| 0042 | `- 상태: Accepted`(한국어 라벨, 결정일/관련) | Accepted | ✓ | "Alternatives" |
| 0043 | Accepted | Accepted | ✓ | 제목≠H1 |
| 0044 | Accepted | Accepted | ✓ | 제목≠H1 |
| 0045 | Accepted | Accepted | ✓ | "재검토 조건" 절 추가 · L190 이동된 계획서 경로 |
| 0046 | Accepted | Accepted | ✓ | 04 미언급 · "재검토 조건" 절 |

- **Status 일치 44/44.** 비-Accepted 10개(0003 Deprecated, 0005/0009/0011/0012/0015/0018/0020/0023/0030 PS)는 모두 대체 ADR 을 이름으로 적고 그 파일이 존재한다. 하네스 digest 도 같은 10개를 낸다.
- **README 제목 ≠ 파일 H1: 20건**(0007, 0011, 0015, 0016, 0017, 0018, 0020, 0021, 0022, 0023, 0024, 0025, 0026, 0030, 0031, 0032, 0033, 0040, 0043, 0044). README 가 "(ADR-00NN 부분 무효화)" 같은 주석을 덧붙이거나 축약한 경우.
- **"관련 Layer 1 문서" 열 ↔ 실제 `ADR-NNNN` 언급 불일치 29건**(위 표 비고). 열의 의미가 "그 문서가 이 ADR 을 인용한다" 인지 "이 ADR 이 그 문서에 영향을 준다" 인지 정의가 없다(§10 Q4).
- **템플릿 이탈**: `template.md` 는 Status 선택지에 `Partially Superseded` 가 없고 `관련 Phase` 가 Phase 4 까지(Phase 5 없음). 40/44 만 `관련 Phase` 필드를 가짐. 절 구성 이탈 ADR 10건(0004·0006·0018 추가 절, 0021 순서, 0022 References 없음, 0023 "관련 문서", 0033 "Related", 0035, 0037, 0040, 0042, 0045/0046 "재검토 조건").
- **Update Log 가 Decision 보다 큰 ADR 4건**: 0003, 0004, 0027, 0028. **Decision 의 50% 초과 4건**: 0012(3건), 0030(2건), 0032, 0033. 0025/0027 은 Update Log 외에 `## 2026-10-17 사실 정정 (D-073)` 절을 따로 둠(README 원칙 L11-13 은 "Update Log 절" 하나를 말함). 어느 것이 새 ADR 사유인지는 판단하지 않음(§10 Q5).

### 6-2. 0038/0039 결번

- `git log --all --diff-filter=D -- 'docs/adr/003[89]*'` 0건, `git log --all -- 'docs/adr/003[89]*'` 0건 — **한 번도 커밋된 적 없음**(삭제·개명 아님). 저장소 어디에도 `ADR-003[89]` 문자열 없음.
- 주변 생성 커밋: 0034 `cb8056d`(2026-10-14), 0035 `9275556`(10-17), 0036 `0d32915`(10-17), 0037 `a3604b7`(10-17), **0040 `296a82c`(10-17 "propose guarded approval recovery")**, 0041 `d86d375`(10-21). 2026-10-17 하루에 0035~0037·0040 이 서로 다른 작업(병렬 레인)에서 생성됐고 0040 이 두 번호를 건너뛰었다. 병렬 레인 메모리(`project_parallel_subagent_lanes.md`)의 상황과 맞다. 결번을 채울지 결번으로 둘지는 소유자 결정(§10 Q6).

### 6-3. ADR 이 Layer 1 에 반영되지 않은 사례(README 열 기준 미언급 17건)
0001(02,04) 0002(02,07) 0008(02) 0010(04) 0015(02) 0019(02) 0020(02) 0021(02,05) 0022(04,05) 0023(03,04) 0024(02) 0025(02,05) 0026(05) **0027(02 — Layer 1 전체 0건)** 0036(03) 0037(04) 0046(04 — D-109 가 "docs/04 갱신" 을 작업 항목으로 들고 있음).

---

## 7. progress 현황

### 7-1. `PHASE5.md`
- 133,429 바이트 · 1,129줄 · `## ` 헤딩 80개. 헤딩 날짜 2026-09-22~2026-10-26, 본문 최소 날짜 2026-07-24(참조).
- **하네스는 68개로 센다**: `harness-context.sh:199-200` 이 `## 작업 이력`(L188) 뒤 헤딩만 엔트리로 보기 때문. 앞 12개 헤딩 = 2026-10-14~10-21 엔트리 10개(L8, 67, 69, 101, 111, 119, 133, 141, 165) + 틀 2개("Phase 5 는 무엇이 다른가" L147, "이월 항목 (Phase 4 → 5)" L179). L67 과 L69 는 둘 다 H2 지만 L69 "최종 부채·소유권·증거 대조" 는 L67 의 하위 내용.
- **순서 3구역**: L8-186 최신→과거(10-21→10-14) · L192-789 최신→과거(09-30→09-22) · L790-1128 과거→최신(10-21→10-26). 하네스는 첫·끝 날짜 비교(`dates[0] > dates[-1]`)로 방향을 추정하는데 지금은 09-30 < 10-26 이라 "오래된 것부터" 로 판정해 마지막 4개를 거꾸로 뽑는다(결과는 우연히 맞음). 10-14~10-21 엔트리 10개는 digest 에 절대 안 나온다.
- **헤딩 형식 2종**: L190 이 "엔트리 형식은 PHASE4.md 와 동일: `## <제목> ([PR](...), YYYY-MM-DD)`" 라 선언했지만 L790 이후 37개는 `## YYYY-MM-DD — <제목>`(PR 링크 없음).
- 5KB 초과 엔트리 2개: L8-66 "무료 범위 부채 병렬 실행 재개 (2026-10-21)" 10,540B(D-085/D-105/D-097/D-090/D-064… 하위 절 — 사실상 캠페인 실행 기록), L687-754 "CI 그래프 직렬화 해소 + 컨테이너 수명 결정 (#135)" 5,079B(결정 서술, 이후 ADR-0028 로 승격됨).

### 7-2. 다른 PHASE 파일
- `PHASE4.md` 333,101B · 3,392줄 · H2 69개 · 5KB 초과 29개(최대 L98 "구현 단계 (①~⑥) — 코드" 10.3KB). 종결 엔트리 L3327 뒤에 L3377 "2026-10-17 역사 기록 정정 (D-073)"·L3389 "2026-10-17 후속 부채 해소 연결" 이 append(닫힌 로그에 사후 추기 선례).
- `PHASE3.md` 132,489B · 1,470줄 · H2 33개. `## 작업 이력`(L20-219) 한 절이 17.7KB. Phase 3 종결(04-29) 뒤 06-07~06-13 의 D-012~D-019 엔트리 8개가 PHASE3 에 기록됨(PHASE4 가 06-14 에 생기기 전).
- `PHASE2.md` H2 2개(`## 작업 이력` 27KB 한 덩이), `PHASE1.md` H2 5개. 하네스 형식(`## ` 엔트리) 이전 세대.

### 7-3. TASKS 계열
- `TASKS-done.md` 128,600B. ROW regex(`^\|\s*(D-[0-9]{3,})\s*\|`)에 잡히는 행 92개, 전부 유일, `TASKS.md` 11행과 중복 없음, 번호 D-002~D-115. 순서는 완료 순(비단조)이며 보존 CI 가 그 순서를 지킨다.
- **`TASKS-done.md:34` `| **D-024** | …`**: ID 가 볼드라 `docs-preservation-check.py` ROW 와 `harness-context.sh` `debt_rows` 모두 **매치 실패**(검증함). 이 행은 ID 유일성·순서·본문 보존 검사 대상 밖이고 digest 의 "완료 표기 행 93" 에만 ✅ 로 들어간다(92+1).
- D-002·D-012 행은 `TASKS-done.md` 와 `TASKS-archive-phase1-3.md`(D 행 12개) 양쪽에 있음. D-003/D-004 는 행 없이 요약 1줄(L12). D-016 은 어떤 표에도 행이 없음(`phase4-prep-debt-roadmap.md:102` 표와 `TASKS-archive-phase4.md:44` A2 행에만).
- `TASKS-done.md` 말미 L122 `## 2026-10-17 역사 기록 정정 (D-073)` footer(보존 CI 가 interleave 를 보호).
- `TASKS-archive-phase4.md` 83,397B: D 행 0개(절 단위 이동), H2 5개 + D-073 정정 절. `TASKS-archive-phase1-3.md` 65,833B: Task 절 + `## 개발 부채 (Tech Debt)` + `## 다음 Phase 예정` + `## 완료된 작업`.

### 7-4. 잡다 파일과 evidence

| 파일 | 무엇 | 인바운드 |
|---|---|---|
| `d001-metrics-histogram-fix.md`(2026-04-10, 4.1KB) | D-001 Grafana p95/p99 "No data" 해결 보고서(ADR-0007 의 배경 사건) | `TASKS-archive-phase1-3.md` 1곳 |
| `loadtest-tool-evaluation.md`(2026-04-18, 13.6KB) | k6 도입 검토, "ADR 급 결정 아님" 자가 선언 | PHASE3, `plans/done/task-jmeter-to-k6.md` |
| `phase4-design-roadmap.md`(2026-06-13, 8.7KB) | Phase 4 ADR 시퀀싱·구현 순서(§4 "다음 단계" 포함) | TASKS 맵, ADR-0010/0011, PHASE4 머리, 계획서 9건 |
| `phase4-prep-debt-roadmap.md`(2026-06-07, 14KB) | L-001~L-022 트리아지 3버킷·D- 승격 매핑 | TASKS 맵 2행, `ship.md:250`, 아카이브, 계획서 |
| `design-archive-phase1-2.md`(2026-09-30, 15.3KB) | 02 §5/§12·05 §11 의 Phase 1·2 원문(D-053) | 02:59,150 · 05:3 · PHASE5 · TASKS-done |

`docs/progress/evidence/` 15파일: `ci-test-matrix-20260901`(3) · `d002a-gke-20260916-0030`(14, ADR-0026/0027·k8s overlay 주석·k6 스크립트) · `d002bc-gke-20260917`(18, ADR-0025/0027/0045·테스트 코드) · `d026-d002a-read-ceiling-20260920`(9) · `…-preflight-20260920`(1) · `d064-toss-sandbox-20261026`(4, ADR-0018) · `d073-source-corrections`(7) · `d078-gke-20261024`(10, `TASKS.md`·ADR-0045) · `d078-gke-20261025`(4, `TASKS.md`·ADR-0046) · **`impl4-c2b4b-original-timestamp-null-20260913`(0)** · `impl6-cursor-queryplan-20260830`(2) · `pr3c-gke-smoke-20260808-1445`(2) · `pr3d-b2-gke-20260914-1320`(7) · `pr3d-b2-gke-20260915-0854`(8, runbook·k8s README) · `user-key-rotation-drill-20260915-2040`(2, runbook).
`TASKS.md` 행은 이 파일을 `evidence/d078-gke-20261025.md` 처럼 **상대 이름으로만** 적는데, 저장소에는 evidence 루트가 3개다: `docs/progress/evidence/`(측정 세션 기록), `docs/evidence/D-0NN/`(D-063/076/080/084/093 산출물), `docs/plans/evidence/<stem>/`(26개). D-078 은 progress/evidence 와 plans/evidence 양쪽, D-084 는 docs/evidence 와 plans/evidence 양쪽에 있다.

---

## 8. 중복 기술 지도

| 사실(검색 문자열) | 등장 위치(건수) | SSOT 로 보이는 곳 |
|---|---|---|
| `waiting for handler commit`(D-108 ① MySQL 커밋 대기) | `loadtest/reports/2026-10-26/s3/c0/c1-mysql-sample.txt`(9), `…/2026-10-25/s3/warmup/w6-mysql-sample.txt`(6), `loadtest/reports/2026-10-25/session-{timeline,issues}.md`, `docs/TASKS.md` D-108 행, `evidence/d078-gke-20261025.md`, `docs/plans/D-078-remeasure-plan.md` | 원자료는 loadtest, 해석은 evidence, 처분은 TASKS 행 — 3층 |
| `db-max-concurrency` / `PEEKCART_CACHE_DBMAXCONCURRENCY` | `loadtest/scripts/d026-run-cell.sh`, `D-078-remeasure-plan.md`(3), `D-108.md`, ADR-0037, `evidence/d078-gke-20261024.md`, `loadtest/reports/2026-10-24/session-issues.md` | ADR-0037 (설정 키 이름은 코드) |
| `archive: hold`(계획서 보류 frontmatter) | `.claude/commands/sync.md`(2), `done.md`, `docs/conventions/docs-preservation.md`, `PHASE5.md`(2), `D-108.audit.md`(2), `D-108.md`, `D-084.md`, `task-debt-orchestration.md`, `plans/done/sync-context-cost.md` | 규약은 docs-preservation.md + 커맨드 2곳(3중 서술) |
| `ADR-0045`(outbox 500ms/500) | `D-078-remeasure-plan.md`(11), `plans/done/D-075-outbox-throughput.md`(9), `PHASE5.md`(7), `D-108.md`(6), `evidence/d078-gke-20261025.md`(4), `TASKS.md`(3), loadtest(3+2+1), `TASKS-done.md`(2), `docs/06`(1), ADR 자신 | ADR-0045 · Layer 1 반영은 04:306-307 주석 1곳(04 §10-1 은 옛 값) |
| `ADR-0046` / `상한 4 증액` | `TASKS.md`(2/2), `PHASE5.md`(1/2), `D-108.md`, `evidence/d078-gke-20261025.md`(2), `D-078-remeasure-plan.md`(2), ADR-0046(2) | ADR-0046 · Layer 1 0곳(D-109 가 04 갱신을 예정) |
| `0.059%`(D-078 ⑤ DLQ/주문 중앙값) | `TASKS.md`(2: D-078·D-111 행), `PHASE5.md`, `D-108.md` | 측정값인데 Layer 1·evidence 없음, TASKS 행이 1차 기록 |
| `초당 약 122건`(D-108 ① 정체) | `evidence/d078-gke-20261025.md`(3), `D-108.md`(3), `TASKS.md`(2), `PHASE5.md`(2), loadtest(2), `D-078-remeasure-plan.md` | evidence |
| `p50 83초` | `TASKS.md`, `PHASE5.md`, `evidence/d078-gke-20261025.md` | evidence |
| `2000m`(ADR-0027) | `PHASE4.md`(17), `plans/done/task-d029-…`(14), `D-078-remeasure-plan.md`(12), ADR-0027(12), evidence 4파일(21), TASKS-done(4), overlay 이름 | ADR-0027 · Layer 1 0곳 |
| `ADR-0037` | `D-078-remeasure-plan.md`(10), ADR-0046(9), `TASKS.md`(3), evidence(5), loadtest 스크립트(4), `PHASE5.md`(2), ADR-0026(2), `TASKS-done.md`, preservation json | ADR-0037 · Layer 1 는 03:14 1곳 |

패턴: 최근 결정 하나가 **TASKS 행 본문(수천 자) + PHASE5 엔트리 + 계획서 §5 + evidence + loadtest 리포트 + ADR** 여섯 곳에 같은 숫자로 반복되고, Layer 1 에는 거의 안 들어간다. TASKS 행 본문이 가장 길고 가장 자주 갱신된다(D-065 행 약 3,000자, D-078 행 약 3,900자).

---

## 9. 하네스가 의존하는 정확한 포맷

### `scripts/harness-context.sh` (CI `ci.yml:249` 가 `--check --base` 로 실행)
- `docs/TASKS.md`: `^## 현재 단계:\s*(.+)$` 줄 필수. 그 줄에서 `Phase\s*(\d+)` 로 N 추론 → `docs/progress/PHASE{N}.md` 존재 필수(없으면 problem, exit 3).
- D 행: `^\s*\|\s*D-` 로 후보, `^\s*\|\s*(D-[0-9]{3,})\s*\|` + 칸 5개 이상이어야 정상(아니면 "손상된 D행"). 상태는 **마지막 칸**만. ✅ 로 시작하지 않으면 열린 행. 범례 `🔲 🔄 ◐ ⏸`.
- `docs/progress/TASKS-done.md`: `^## 완료 행\s*$` 헤딩 + `^\s*\|\s*ID\s*\|` 표 헤더 + D 행 1개 이상 + 모든 D 행 마지막 칸에 ✅. TASKS+done 의 D-ID 유일.
- `--base` 지정 시 base 대비 D-ID 소실·done 행 감소 금지.
- `docs/adr/README.md`: `^\|\s*\[\d{4}\]` 행이 1개 이상, 3번째 칸이 Status.
- `PHASE{N}.md`: `^## (.+)$` 헤딩; `작업 이력` 헤딩이 있으면 그 뒤만 엔트리; 헤딩 안의 `\d{4}-\d{2}-\d{2}` 로 방향 판정.
- `docs/plans/*.md`(`.audit.md`·`PLAN-BLINDSPOTS` 제외) = 진행 중, `docs/plans/done/*.md`(INDEX 제외) = 아카이브 수.

### `scripts/docs-preservation-ci.py` → `docs-preservation-check.py` (CI `ci.yml:59`, base = PR base SHA)
- `docs/preservation/*.json` 신규 파일은 stage 필수, 기존 manifest 바이트 변경 금지.
- `TASKS = 'docs/TASKS.md'`, `DONE = 'docs/progress/TASKS-done.md'` 하드코딩. ROW `^\|\s*(D-[0-9]{3,})\s*\|`(볼드 ID 비가시). 규칙: ID 유일, 기존 ID 소실 금지, 새 ID 는 TASKS 에만, 이동은 TASKS→DONE append 만, 잔존 행 순서 보존, 이동 행 본문(`rsplit('|',2)[0]`) 보존, 이동 행 상태에 ✅ + `https://github.com/.../pull/N`, 기존 행 사이 text 줄 interleave 보존.
- 보호 파일: `docs/plans/done/*.md`(INDEX.md 제외) · `docs/progress/TASKS-archive-*.md` — canonical 텍스트 **prefix 보존 + 추기만**. 사실 정정은 manifest `corrections`(old 유일, 20줄/2000자/절반 미만).
- `docs/**/*.md` 삭제는 manifest `sources` 선언 필수. source 는 destination 이 아니면 남아 있을 수 없다. destination 이 base 에 이미 있으면 그것도 source 로 선언. 모든 source 줄을 정확히 한 번 배정, destination 내 순서 보존, 새 틀 글은 `literal`. 경로는 `docs/` 로 시작하는 `.md` 만(`safe_path`) — **`docs/` 밖으로 옮기거나 `.md` 가 아닌 이름으로는 선언 불가**.
- 링크: 지원 범위 밖 Markdown(HTML 주석, `href=`, 들여쓴 코드 안 링크, 중첩 라벨, 여러 줄 백틱) 이 **이동 대상·인바운드 파일**에 있으면 fail-closed. 분리 문서 whole-file 링크는 첫 줄 소유 목적지로, heading 링크는 그 heading 줄 소유 목적지로 재지정. 추적 `*.md` 전체(`git ls-files '*.md'`)의 인바운드 링크 수가 유지돼야 함.
- `scripts/plans-archive.sh --apply --select … --base … --manifest …` 가 plan/audit 이동 manifest 를 만든다(다른 레인).

### 그 밖의 경로 결합
- `.claude/scripts/lib/ship.sh:88`: `grep -qsF "/pull/<n>)" docs/TASKS.md docs/progress/TASKS-done.md`(재진입 판정). `ship.sh` 가 `docs/consistency-hints.sh` 호출(3곳).
- `.claude/commands/{done,ship,sync,next,plan,work}.md`·`.agents/skills/*/SKILL.md`: `docs/TASKS.md`, `docs/progress/PHASE{N}.md`, `docs/progress/TASKS-done.md`, `docs/adr/README.md`, `docs/adr/template.md`, `docs/conventions/{docs-preservation,codex-review,harness-rationale,writing}.md`, `docs/plans/`, `docs/plans/done/INDEX.md`, `docs/plans/PLAN-BLINDSPOTS.md`, `docs/preservation/*.json`, `docs/02~05-*.md`(sync.md), `docs/progress/phase4-prep-debt-roadmap.md`(ship.md:250).
- `.githooks/commit-msg` → `docs/conventions/writing.md`. `scripts/d092-ops-fixture.py` → runbook 2개 경로. `scripts/codex-review-render.sh` → `docs/plans/evidence/D-104/README.md`. `.github/workflows/ci.yml:…` → `docs/plans/evidence/D-099/check-mutants.py`.
- `scripts/ci-docs-only.sh`: `docs/**` 와 루트 `*.md` 는 문서 전용(CI 대부분 skip), 단 `docs/plans/fixtures/**` 는 코드. 문서 재배치 PR 은 docs-only 로 분류되지만 preservation 검사·harness `--check` 는 돈다(`ci.yml:59,249`).
- `docs/consistency-hints.sh`: Layer 1 파일 glob `docs/0[1-7]-*.md`, `docs/` 전체의 `ADR-[0-9]{4}` 참조가 `docs/adr/NNNN-*.md` 로 존재하는지(유일한 자동 판정), `k8s/` 트리 힌트.
- `scripts/writing-lint.sh`: 모드 `--commits <range>`, `--file <path>`(PR 본문), `--title <text>`, `--msg <path>`(commit-msg 훅), `--self-test`. **docs 모드 없음.** `writing.md:3`·lint 머리 L6-7 이 설계 문서를 명시적으로 대상 밖으로 둔다(`docs/TASKS.md` 이모지 예외). 그래서 돌리지 않았고 대신 같은 정규식으로 펜스 밖 금지 문자를 셌다(정보용): `PHASE4.md` 1,449(dash 730·arrow 633) · `PHASE3.md` 541 · `TASKS-archive-phase4.md` 526 · `TASKS-done.md` 425 · `TASKS-archive-phase1-3.md` 345(emoji 181) · `docs/runbooks/user-jwt-key-rotation.md` 45 · `dlq-recovery.md` 45 · `docs/TASKS.md` 40(이모지 10은 상태) · `docs/04` 36 · `docs/adr/README.md` 25(제목의 em dash) · `redis-cache-fallback.md` 22 · `docs/02` 22 · `docs/07` 21 · `docs/03` 19 · `docs/06` 15 · `docs/05` 12 · `CLAUDE.md` 11 · `README.md` 6 · `docs/01` 3. 함의: ADR 제목·PHASE 헤딩을 커밋/PR 본문에 그대로 옮기면 lint 가 막는다(em dash·화살표).

---

## 10. 질문 목록 (소유자 결정 필요)

| # | 무엇 | 왜 애매한가 | 선택지 |
|---|---|---|---|
| Q1 | Phase 5 를 "닫는다" 의 의미 | 07/TASKS/PHASE5 세 곳이 "종결 조건 없음" 을 결정으로 적어 뒀다(§4). 닫으면 그 결정과 상충한다 | (a) 종결 조건 없는 단계라는 결정을 유지하고 "Phase 5 기록 마감(로그 분리)" 만 한다 (b) 새 결정(ADR 또는 PHASE5/07/TASKS 동시 갱신)으로 Phase 5 종결을 선언하고 열린 11행을 D-030 선례처럼 이월 표기 (c) 다음 단계 이름·성격을 먼저 정의 |
| Q2 | 다음 단계의 이름과 성격 | 어디에도 정의가 없다(§4). TASKS `## 현재 단계:` 줄은 하네스가 `Phase N` 을 읽어 `PHASE{N}.md` 를 연다 | Phase 6(숫자 유지, `PHASE6.md` 필요) / 다른 축(예: 연도·주제) — 후자는 `harness-context.sh:190-192` 수정 필요 |
| Q3 | `PHASE5.md` 의 세 구역·두 헤딩 형식(§7-1) | 하네스 digest 가 10개 엔트리를 못 보고 있고, 어느 형식이 정본인지 L190 선언과 실제가 다르다 | (a) 그대로 두고 하네스만 고침 (b) 재정렬·형식 통일(이력 문서를 고치는 것이라 preservation 는 막지 않지만 원문 변경) (c) 다음 PHASE 파일부터만 형식 고정 |
| Q4 | `docs/adr/README.md` "관련 Layer 1 문서" 열의 의미 | 29건이 실제 언급과 어긋난다(§6-1) | "그 문서가 ADR 을 인용한다" 로 정의하고 열을 재생성 / "영향 받는 문서" 로 정의하고 Layer 1 에 `(see ADR-NNNN)` 을 채움 / 열 삭제 |
| Q5 | Update Log 가 Decision 을 넘는 ADR 0003/0004/0027/0028(+0012/0030/0032/0033) | README 원칙 L14 는 "트레이드오프 변경·대안 추가·Consequences 재해석은 새 ADR" 이라 하는데 어느 추기가 그 선을 넘었는지는 읽는 사람 판단 | 각 Update Log 를 소유자가 분류 / 그대로 둠 |
| Q6 | ADR 0038/0039 결번 | 병렬 레인에서 생긴 공백(§6-2). 채우면 번호 순서와 날짜 순서가 어긋난다 | 결번으로 두고 README 에 한 줄 / 다음 ADR 을 0038 로 / 그냥 0047 부터 |
| Q7 | 이력 문서 안의 사라진 경로·모놀리스 서술 100건+ (§2-3) | 이력이므로 고치지 않는 것이 규약(ADR immutable, archive append-only)인데, 읽는 사람은 현행과 구분할 수 없다 | 파일 머리에 "이력 문서, 경로는 당시 기준" 안내 literal 추가(manifest 필요 없음, archive 는 prefix 보존 규칙상 **머리 추가 불가**, 말미만 가능) / 폴더 이름으로 구분 / 그대로 |
| Q8 | `TASKS-done.md:34` 볼드 ID `**D-024**` | 보존 CI·하네스 둘 다 안 보는 행. 고치려면 완료 행 본문 변경이라 `Task row body changed` 로 CI 가 막는다(corrections manifest 로만 가능) | corrections 로 `| **D-024** |` → `| D-024 |` 정정 / 그대로 두고 regex 를 넓힘 |
| Q9 | 2026-10-25 "사용자 결정" 묶음(D-065·D-084 보류, D-108 ③ 범위 밖)의 기록 위치 | TASKS 행과 PHASE5 엔트리에만 있다. 범위 결정이라 ADR 대상인지 애매하고, TASKS 행이 TASKS-done 으로 가면 "열린 결정" 이 아카이브로 들어간다 | ADR(또는 결정 기록 전용 문서) 승격 / PHASE5 엔트리로 충분 / TASKS 행 유지 |
| Q10 | Layer 1 안의 Phase 1~4 이력 서술(§2-4(c), §5) | D-053 이 다이어그램·ERD 만 아카이브했다. 나머지 Phase 1/2/3 서술(04 §9-4/9-6/10-5, 03 Phase 1 불릿, 07 §16 로드맵)은 "현재 상태" 가 아니지만 포트폴리오 스토리라인 자료이기도 하다 | design-archive 로 2차 분리(manifest 필요) / 현행 문장으로 재작성 / 유지 |
| Q11 | 분산 락(Redisson) 서술 | ADR-0025 는 재고 경로만 제거, 코드엔 `DistributedLockManager` 가 남아 있다. 01/07/06 이 "분산 락" 을 Redis 역할로 적는 것이 stale 인지 아닌지 코드 용도를 확인해야 한다 | 현재 사용처 확인 후 Layer 1 문구 결정 |
| Q12 | nGrinder 서술(01/03/06/07) | `ngrinder-product-query.groovy` 는 있지만 모든 측정 세션은 k6. "도구 계획" 과 "실제 도구" 가 섞여 있다 | 현행 도구 = k6 로 재작성 / 세션 B 이력으로 각주 |
| Q13 | runbooks·conventions·review·evidence 의 레이어 소속 | CLAUDE.md 레이어 원칙에 자리가 없다. review/ 2개는 2026-04 보고서(이력), conventions 는 하네스 규약(현행), runbooks 는 운영 절차(현행) | 레이어 정의 확장(What/Why/When + How-to/규약) / review 를 progress 로 / 그대로 |
| Q14 | evidence 루트 3곳(`docs/progress/evidence`, `docs/evidence/D-*`, `docs/plans/evidence/*`) | D-078/D-084 가 두 곳에 걸쳐 있고 TASKS 행은 `evidence/…` 상대 이름으로 적는다. preservation 는 `.md` 만 다루므로 json/yml/tsv 증적은 이동 선언이 불가능하다(`safe_path`) | 하나로 모음(비-md 파일은 보존 CI 밖에서 이동) / 셋의 역할을 정의만 / 그대로 |
| Q15 | `docs/learning` 을 인용하는 immutable ADR 4건과 D-100 evidence README | 증거가 gitignore 밑에 있어 clone 에서는 검증 불가. ADR 본문은 건드릴 수 없고 Update Log 는 "사실 오류" 용 | 해당 파일을 `docs/evidence/` 로 들여오고 Update Log 로 경로 정정 / 그대로 두고 README 에 "로컬 전용" 명시 |
| Q16 | 고아 문서 `docs/review/ci-optimization-analysis.md`, `docs/progress/evidence/impl4-c2b4b-original-timestamp-null-20260913.md` | 아무도 가리키지 않지만 삭제는 manifest 필요(.md) | 어딘가에서 링크 / 아카이브 폴더로 이동(manifest) / 유지 |
| Q17 | `.claude/commands/ship.md:250` 의 `phase4-prep-debt-roadmap.md` Step | Phase 4 전용 절차가 현행 커맨드에 남아 있다 | 제거 / 일반화 |
| Q18 | ADR 템플릿 갱신 여부 | `template.md` 에 Partially Superseded·Phase 5 가 없고, 최근 ADR 10건이 절 구성을 달리한다 | 템플릿을 현재 관행으로 갱신 / 관행을 템플릿에 맞춤 / 둘 다 유지 |
| Q19 | `README.md` 의 역할 | 2026-09-22 이후 변경 없음. Phase 5·ADR·TASKS·runbooks 안내 없음 | 문서 진입점으로 재작성 / 훅 안내 + 01~07 링크로 유지 |
| Q20 | 분리 manifest 작성 비용 | §9 의 preservation 규칙상 모든 `.md` 이동·분리는 줄 단위 manifest 가 필요하고, 미지원 Markdown(HTML 주석 등)이 있는 파일은 fail-closed 다. 어느 파일에 그런 구문이 있는지는 실제 manifest 를 만들어 보기 전엔 모른다 | 재배치 범위를 `.md` 이동 최소화로 잡음 / 이동보다 포인터·머리말 추가 중심 |
