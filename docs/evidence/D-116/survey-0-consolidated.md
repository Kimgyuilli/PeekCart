# Phase 5 마감 정리 — 전수조사 통합본과 결정 질문지 (2026-10-26, main 91cfa1f)

레인별 원문: `survey-A-loadtest.md`(측정 산출물) · `survey-B-plans.md`(계획서·증적) · `survey-C-docs.md`(현행·이력 문서) · `survey-D-tooling.md`(경로 의존성·로컬 잔재). 이 파일은 네 보고서를 교차 대조한 요약과, 소유자가 정해야 하는 질문을 중복 제거해 묶은 것이다. 저장소는 아직 아무것도 바꾸지 않았다.

## 1. 수치 요약

| 영역 | 규모 | 추적 여부 | 핵심 사실 |
|---|---|---|---|
| `loadtest/reports/` | 44.6MB · 889파일 | 830파일 39.1MB 추적, `.log` 59개 2.3MB 비추적 | 지연 TSV 63개 15.1MB · k6 JSON 안 JWT 4.6MB · warmup 8.4MB · invalid 4.6MB · kafka-lag 시계열 2.8MB. `.git` 67MB 중 reports blob 38.4MB(작업트리 삭제로는 안 줄어듦) |
| `docs/plans/` | 8.4MB · 729파일 | 루트 계획 4+audit 4, done 164+134, evidence 26dir 369파일 4.1MB | 명명 8종, INDEX 분류 "기타" 102/164, frontmatter 없음 46, PR 링크 없음 9 |
| 증적 3축 | plans/evidence 4.1MB · docs/evidence 884K · progress/evidence 176K | 전부 추적 | 규약 없이 작성 주체·시기로 갈림. CI/규약이 직접 읽는 파일 4건(`D-099/check-mutants.py` 는 ci.yml 이 실행) |
| Layer 1 (01~07) | 113KB | 추적 | stale 24건, 문서 내 모순 2건(04 §9-6 전략 A↔B, 04 §10-6 분산락↔§9-1 제거), ADR-0027/0045/0046 미반영, §4 번호 중복(01·02) |
| ADR | 44건 + README | 추적 | Status 44/44 일치. README 제목≠H1 20건, "관련 Layer 1" 열 불일치 29건, 0038/0039 결번(커밋된 적 없음), gitignore 파일 인용 4건 |
| progress | PHASE5 133KB(80엔트리, 하네스는 68개만 셈, 순서 3구역·헤딩 2형식) · PHASE4 333KB · TASKS-done 128KB(92행, `**D-024**` 1행 regex 비가시) | 추적 | 잡다 파일 5개(review 2, d001, loadtest-tool-evaluation, design-archive)는 어떤 맵에도 없음 |
| 로컬 잔재 | worktree 637MB · .gradle-work 527MB · */build 668MB · docs/learning 45MB · .cache 8.8MB · plans/.archive 356K | 비추적 | worktree 4개 전부 머지·clean(2개는 현 세션 pid 잠금), 로컬 브랜치 20·원격 브랜치 20 전부 머지, 열린 PR 0 |

## 2. 정리 작업이 반드시 지켜야 할 계약 (레인 B·D 교차 확인)

1. 추적된 **모든 `*.md`** 는 `docs/preservation/<이름>.json` manifest 없이 삭제·이동 불가. manifest 는 source 전 줄을 목적지에 정확히 한 번 배정해야 하므로 "삭제" 는 곧 "어딘가로 이동" 이다. 과거 manifest 14개는 바이트 불변.
2. manifest 가 선언할 수 있는 경로는 `docs/` 로 시작하는 `.md` 뿐(`safe_path`). `.md` 를 `docs/` 밖으로 내보내는 이동은 체커 수정 없이는 선언 불가.
3. `docs/plans/done/*.md`·`docs/progress/TASKS-archive-*.md` 는 prefix 보존+추기만. **이 두 경로를 옮기면 보호 regex 밖으로 나가 보호가 조용히 사라진다.** 옮기려면 같은 PR 에서 `docs-preservation-check.py:405-417` 과 셀프테스트 fixture 를 함께 고쳐야 한다.
4. `scripts/harness-context.sh --check --base` 는 `docs/TASKS.md`·`docs/progress/TASKS-done.md` 를 base 와 HEAD 양쪽에서 **같은 리터럴로** 읽는다. 이 둘을 옮기는 PR 은 한 PR 안에서도 통과 못 하고 2단계 전환(폴백)이 필요하다. `PHASE{N}.md` 파일명과 `## 현재 단계:` 의 `Phase N` 도 같은 스크립트가 묶는다.
5. 비-`.md`(json/tsv/jpg/gz/log) 는 보존 CI 밖이라 자유롭게 옮기고 지울 수 있다. 단 `scripts/tests/test_kafka_topic_state_guard.py:26` 이 `loadtest/reports/2026-10-24/s4/d065-5-topic-configs-all.txt` 를 fixture 로 읽는다(CI lint).
6. `loadtest/**` 변경은 docs-only 가 아니라 `code=true` 로 분류돼 test 6샤드·guards·images 6·e2e 3 이 전부 돈다. 순수 `docs/**`+루트 `*.md` PR 만 매트릭스를 건너뛴다.
7. 커밋 분류 훅(`.githooks/pre-commit`)은 docs/adr/chore/test/src 혼합 커밋을 막는다. `loadtest/reports` 삭제 커밋은 `.md`(docs)+`.yml`(chore)+그 외(src) 혼합이라 분류별로 쪼개거나 `HPX_ALLOW_MIXED=1` 이 필요하다.
8. 여러 PR 로 나눌 수 있다. 각 PR 이 자기 이동분 manifest 를 갖고, base SHA 고정 규약상 **순차 머지**(앞 PR 머지 뒤 다음 PR 생성)가 안전하다.

## 3. 선례 — Phase 4→5 마감이 실제로 한 일 (레인 C §3, 복제용)

| # | 한 일 | PR |
|---|---|---|
| 1 | 종결 계획서(등급 S) — 순서표 머지 근거표, 미착수 행 코드로 반증 | #133 |
| 2 | TASKS 헤더 `(✅ 완료, 날짜, PR)` + 종결 근거 blockquote | #133 |
| 3 | 미착수 행은 지우지 않고 "D-030 으로 승격 이월" 표기 | #133 |
| 4 | PHASE4 종결 엔트리 append(무엇/왜 지금/근거표/남은 처분/다음) | #133 |
| 5 | `harness-context.sh`·`plans-index.sh --check` 검증 | #133 |
| 6~9 | 기반 계획서, TASKS `## 현재 단계:` 전환, `PHASE5.md` 신설, 07 §16 절 추가 | #134 |
| 11 | TASKS 분리(D-052): `TASKS-archive-phase4.md` + `TASKS-done.md` 신설, 하네스·ship.sh 갱신 | #170 |
| 12 | Layer 1 분리(D-053): Phase 1·2 원문 → `design-archive-phase1-2.md`, 포인터 1줄 | #171 |
| 13 | 역사 추기 선례(D-073): 닫힌 문서 7곳에 머리 1줄 + 말미 정정 절 | #177~#180 |

11·12 는 manifest 없이 수행됐다(보존 CI 는 10-21 도입). 지금 같은 일을 하려면 manifest 가 필수다. Phase 5 는 Exit Criteria 가 없어 `[x]` 전환 단계가 없고, 다음 단계 이름은 어디에도 없다.

## 4. 재설계 초안 (질문 답에 따라 바뀜 — 권장 기본값 기준)

```
docs/
  README.md                  ← 신설: 단일 문서 맵(현재 CLAUDE.md·TASKS·README 에 흩어진 맵 통합)
  01~07-*.md                 ← Phase 5 종료 시점 현행으로 재기준선. Phase 1~4 서술은 archive 로 분리
  TASKS.md                   ← 위치·형식 고정(하네스 앵커)
  adr/                       ← 고정. README 열 정리, 템플릿 갱신, 0038/0039 결번 명시
  conventions/               ← 규약 + PLAN-BLINDSPOTS.md 이동
  runbooks/                  ← 그대로
  progress/
    PHASE{1..5}.md, PHASE6.md ← 고정(하네스 앵커). PHASE5 는 마감 시 순서·헤딩 통일
    TASKS-done.md            ← 고정
    TASKS-archive-*.md       ← 고정(보호 regex)
    archive/                 ← 신설: design-archive, phase4-*roadmap, review/ 2건, loadtest-tool-evaluation, d001-*
  plans/
    <active>.md, done/       ← 고정(하네스·보존 regex). done/INDEX 분류기 수정
  evidence/                  ← 단일 루트로 통합
    <D-ID>/                  ← plans/evidence 26 dir + docs/evidence 5 dir (README 필수)
    sessions/                ← progress/evidence 산문 15개 (이동 여부는 Q2-3)
  preservation/              ← 고정(불변 manifest)
scripts/
  tests/fixtures/            ← topic-configs 스냅샷 복사(코드 의존 제거)
  fixtures/saga-contract-matrix.tsv, check-mutants.py, consistency-hints.sh ← docs 안 실행물·입력물 이동
loadtest/
  README.md                  ← 현행 절차 중심 재작성(Phase 3 모놀리스 절은 archive 로)
  reports/<date>/
    REPORT.md | session-*.md ← 정본 유지
    summary/                 ← 신설: run 별 지연 집계(p50/p95/p99/max/n), 원본에만 있던 수치 13묶음
    raw/                     ← 유효 측정 run 원자료만
    grafana/                 ← 유효 run 의 jpg+json 짝
```

## 5. 결정 질문지 (중복 제거, 권장안 표기)

### 0. 방향 — 답에 따라 나머지가 바뀐다

| # | 무엇 | 사실 | 선택지 | 권장 |
|---|---|---|---|---|
| 0-1 | Phase 5 "마감" 의 의미와 다음 단계 이름 | 07·TASKS·PHASE5 가 "종결 조건 없음" 을 결정으로 기록. 하네스는 `Phase N` → `PHASE{N}.md` | (a) Phase 6 선언: PHASE6.md 신설, 선례 13단계 복제, 열린 11행 이월 표기 (b) Phase 번호 아닌 다른 축(하네스 수정) (c) 단계는 두고 파일 정리만 | (a). 다음 단계가 로드맵형인지 수요형인지는 사용자가 정해야 함 |
| 0-2 | 재설계 범위 | 하네스·보존 CI 앵커 7곳(§2-3·4) | (A) 앵커 고정 + 나머지 재배치 (B) 앵커도 이동 + 스크립트 9곳·셀프테스트 3종·보호 regex 동반 수정, TASKS 류는 2단계 PR | (A) |
| 0-3 | git 이력 | 작업트리 정리로 `.git` 67MB 는 안 줄어듦. 이력 재작성은 공개 저장소·PR 링크·manifest hash 영향 | 작업트리만 / 이력도 | 작업트리만 |
| 0-4 | PR 분할 | §2-6·8 | 순차 다중 PR: ① loadtest prune ② 도구 파일 이동+스크립트 리터럴 ③ 문서 재배치(manifest) ④ Phase 마감 ⑤ 로컬 정리(PR 없음) / 단일 PR | 순차 다중 |

### 1. 측정 산출물 (`loadtest/reports/`)

| # | 무엇 | 사실 | 선택지 | 권장 |
|---|---|---|---|---|
| 1-1 | 지연 TSV 63개 15.1MB | 재생성 불가. 백분위는 `d078-latency.py` 로 재집계 가능하나 집계 파일은 저장소에 없고 p95/max 는 산문에도 없음 | (a) 전부 보존 (b) 집계 파일 커밋 + 유효 측정 run 7개(6.4MB)만 보존, 무효·워밍업 8.7MB 삭제 (c) 집계 커밋 + 전부 삭제 | (b) |
| 1-2 | k6 요약 JSON 22개 안 JWT 450개×22 = 4.56MB | 만료·키 삭제로 검증 불가하지만 자격증명 형태 데이터가 git 에 있음. metrics 는 run 당 7~10KB. D-108 P2 "원자료를 고치지 않는다" 와 충돌 | (a) 그대로 (b) 22개 전부 `setup_data` 제거 사본으로 교체 + session-issues 에 기록 (c) 측정 run 8개만 (b), 나머지 삭제 | (b) |
| 1-3 | 무효 run·워밍업·관찰 블록 원자료: warmup 8.35MB, invalid 4.62MB, 10-25 S3 grafana 2.52MB, kafka-lag json 2.79MB | 10-25 S3 는 run 전부 무효라 warmup 이 유일 데이터(evidence·TASKS 가 w5·w6 인용), 10-26 w4 는 수렴 판정 편차 근거, `invalid/contend-retry2/` 는 D-112~D-114 사고 증적 | (a) 전부 보존 (b) 판정 입력(json·counts·times·prom-delta·warmup.txt)과 인용된 run(10-25 w6, 10-26 w4, contend-retry2 증적)만 남기고 TSV·jpg·대형 json 삭제 (c) 전부 삭제 | (b) |
| 1-4 | 비추적 `.log` 59개 2.34MB(git 밖) | 대체물 없는 2개: `c2-payment-service-2100.log`(D-112 원 로그), `2026-04-29/run2/k6-stdout.log` | (a) 그대로 (b) 그 2개는 발췌를 추적 파일로 만들고 나머지 로컬 삭제 (c) 전부 `git add -f` | (b) |
| 1-5 | April 세션 소형 정리(png 2장 0.69MB, ngrinder-ui 중복 0.1MB) | REPORT 산출물 목록이 가리킴 | 유지 / 삭제 | 유지 |
| 1-6 | 중복(configs.txt 4중복 0.42MB 등)·소파일(.time, 0바이트 reset) | 가드 증적 묶음 규약 | 유지 / dedupe | 유지 |

### 2. 계획서·증적

| # | 무엇 | 사실 | 선택지 | 권장 |
|---|---|---|---|---|
| 2-1 | `D-078-remeasure-plan`·`D-108` 아카이브 | 계획 범위·PR 전부 완료. TASKS 행은 ◐(⑦ 사용자 결제 콘솔 확인, ③ 프로브 정책 대기)이나 둘 다 계획서가 소유하지 않는 항목 | (a) 지금 아카이브 (b) hold 로 ⑦·③ 까지 대기 (c) ③ 을 새 D- 행으로 떼고 아카이브 | (a) |
| 2-2 | `task-debt-orchestration` | D-075(#242)·D-064(#249)가 hold 밖 PR 로 닫혀 표가 낡음. 실질 잔여는 D-084(⏸)뿐 | (a) D-084 까지 유지 (b) 종결 절 추기 + hold 갱신 (c) 종결 절 추기 후 아카이브 | (c) |
| 2-3 | 증적 3축 통합 | §1 표. 원자료(비-md)는 자유 이동, 산문 15개 md 는 manifest + 인바운드(ADR 포함 수십 곳) 재작성 | (a) 원자료만 `docs/evidence/<D-ID>/` 로 통합, 산문은 `progress/evidence` 유지 + 규약에 역할 정의 (b) 산문까지 단일 루트 (c) 현상 유지 + 규약만 | (a) |
| 2-4 | CI·규약이 실행·참조하는 docs 안 파일 | `D-099/check-mutants.py`(ci.yml 실행, `parents[4]` 깊이 의존), `D-102/check.py`, `D-104/README.md`, `fixtures/saga-contract-matrix.tsv`(gate 입력), `docs/consistency-hints.sh`, 테스트 fixture `d065-5-topic-configs-all.txt` | (a) 실행물·입력물은 `scripts/` 로 이동 + 참조 갱신 (b) 그대로 | (a) |
| 2-5 | `task-dNNN-*`(37파일) → `D-NNN` 명명 통일 | rename = manifest 이동 + 인바운드 재작성 | (a) 안 함, INDEX 분류기 수정으로 대체 (b) 통일 | (a) |
| 2-6 | Actions 사본 증적 ~2.5MB(gz/zip) | GitHub 원본 2026-10-28~2027-01-19 만료 → 그 뒤 유일본 | 보존 / SHA256 만 남김 | 보존 |
| 2-7 | 고아 증적 dir 6·README 없는 dir 2·`D098` | 대응 계획이 경로 미기재 | README·추기로 연결 + `D098`→`D-098` | 실행 |
| 2-8 | 스모크 fixture md 3 + `.audit/` 2(참조 0, 2026-04 잔재) | 삭제엔 manifest(=아카이브 문서로 병합) | 병합 후 제거 / 유지 | 병합 후 제거 |
| 2-9 | `docs/plans/.archive/` 46 state json + `.gitignore:42-45` | reader 0 | 삭제 / 보관 | 삭제 |
| 2-10 | `PLAN-BLINDSPOTS.md` 위치 | plans-archive KEEP_ALWAYS·harness 제외 규칙·plan.md/work.md 참조 4곳 | conventions 로 이동 / 유지 | 이동 |

### 3. 현행·이력 문서

| # | 무엇 | 사실 | 선택지 | 권장 |
|---|---|---|---|---|
| 3-1 | Layer 1 재기준선 범위 | stale 24, 모순 2, ADR-0027/0045/0046 미반영, nGrinder·분산락·Phase 1 서술 잔존 | (a) Phase 5 종료 시점 현행으로 재작성 + Phase 1~4 서술 design-archive 분리(D-053 방식) (b) 사실 정정만 | (a) |
| 3-2 | 이력 잡다 문서 거처 | review 2, design-archive, phase4-*roadmap 2, loadtest-tool-evaluation, d001-* | `docs/progress/archive/` 신설 이동(manifest) / 유지 | 이동 |
| 3-3 | PHASE5.md 순서 3구역·헤딩 2형식 | digest 가 엔트리 10개 못 봄 | (a) 마감 시 한 방향 재정렬 + 헤딩 통일 + 하네스가 `작업 이력` 앞도 세게 (b) 하네스만 | (a) |
| 3-4 | ADR 부속 | README "관련 Layer 1" 29건 불일치, 제목≠H1 20건, 0038/0039 결번, 템플릿에 Partially Superseded·Phase 5 없음 | 열 재생성(인용 기준) 또는 삭제 / 제목 통일 / 결번 README 1줄 + 0047 부터 / 템플릿 갱신 | 전부 실행(열은 인용 기준 재생성) |
| 3-5 | 2026-10-25 사용자 범위 결정(D-065·D-084 보류, D-108 ③)·Phase 5 마감 결정의 기록 위치 | TASKS 행·PHASE5 에만 | 마감 ADR 1건으로 승격 / PHASE5 로 충분 | ADR 승격 |
| 3-6 | gitignore 된 `docs/learning` 파일을 인용하는 ADR 4건(0022·0025·0032·0033)·D-100 README | clone 에서 검증 불가 | 인용 파일만 `docs/evidence/` 로 들여오고 Update Log 추기 / "로컬 전용" 명시 | 들여오기 |
| 3-7 | `TASKS-done.md:34` `**D-024**` | CI·하네스 regex 비가시 | corrections manifest 정정 / regex 확장 | corrections |
| 3-8 | 진입점 | README.md 가 Phase 5·ADR·TASKS·runbooks 무언급, CLAUDE.md 맵 누락 다수 | `docs/README.md` 단일 맵 신설 + 루트 README 재작성 + CLAUDE.md 맵은 포인터 | 실행 |

### 4. 로컬 잔재 (git 밖, PR 없음 — 삭제는 명시 승인 뒤에만)

| # | 무엇 | 사실 | 권장 |
|---|---|---|---|
| 4-1 | worktree 4개 637MB · 로컬 브랜치 20 · 원격 브랜치 20 | 전부 머지·clean, worktree 2개는 현 세션 pid 잠금 | 세션 종료 후 worktree remove + 브랜치 삭제 |
| 4-2 | `.cache/` 8.8MB | `codex-off`(유지 필수), `d078-s0.txt`(유일 로컬 메모), 나머지는 머지 완료 산출물 | 둘만 남기고 삭제 |
| 4-3 | `.gradle-work/` 527MB · `.gradle/` 155MB · `*/build/` 668MB | 재생성 가능 | `.gradle-work` 만 삭제 |
| 4-4 | `docs/learning/` 45MB | 블로그 작업 디렉터리(저장소 스크립트 생성 아님). `replay-entrypoint-lint.sh` 가 로컬에서 이 때문에 exit 1 | 유지하되 lint 를 `git ls-files` 기반으로 |

### 5. 반대 없으면 기본값으로 진행할 항목

- `.dockerignore` 에 `loadtest`·`local-keys`·`.cache` 추가(이미지 6개 빌드마다 45MB 컨텍스트)
- `.gitignore` 정비: 죽은 규칙 제거, 루트 `src/` 전제 규칙을 모듈별 `*/src/main/resources/application-local.yml` 로, `gradle-wrapper.jar` 부정 규칙 순서
- `plans-index.sh` 분류기에 `D-\d+` 버킷 추가
- `.claude/commands/ship.md:250` Phase 4 전용 Step 제거
- 테스트 fixture 를 `scripts/tests/fixtures/` 로 복사·경로 수정(loadtest/reports 의 코드 의존 제거)
- `loadtest/README.md` 현행 절차 중심 재작성, Phase 3 모놀리스 절은 archive
- 커밋은 분류 훅에 맞춰 docs/chore/src 별로 쪼갬

## 6. 원본에만 있고 문서에 없는 수치 (TSV·JSON 삭제 전 집계 파일로 옮겨야 할 것, 레인 A §3)

10-26 측정 run 지연 p95·max·표본수 · 10-24 outbox p99·max · 10-25 w1~w5 지연 전부 · 10-26 무효 run 지연 · S2 per-run 값 28개 · c1 MySQL 표본 차분(Com_commit 등) · 배수 곡선 · run-window-metrics 열 · HPA 5초 watch · grafana export 시계열 · k6 전 metric · April nGrinder 2초 시계열 · hikari 15초 시계열.
