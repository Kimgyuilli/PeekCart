# 조사 D — 도구·CI·하네스의 경로 의존성과 로컬 잔재 (읽기 전용 조사, 2026-10-26, main `91cfa1f`)

범례: **계약** = CI 잡·훅·게이트가 그 경로를 읽어 실패를 낸다(하드 실패). **편의** = 로컬 커맨드·LLM 지시문·주석·링크(끊겨도 CI 는 통과, 동작이 어긋나거나 링크가 죽는다).
"깨지는 방식": `하드` = 스크립트 exit≠0 / 파일 없음, `스테일` = 링크·주석·지시문이 낡는다, `무력` = 검사가 조용히 대상에서 빠진다.

---

## 1. 경로 의존성 지도 (대상 경로별)

### 1-1. `docs/TASKS.md` — 위치 + 행 형식

| 의존 파일:라인 | 리터럴 패턴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/harness-context.sh:81` | `read('docs/TASKS.md')` | 하드(`--check` 시 "읽기 실패" → exit 3; CI `ci.yml:249`) | 계약 |
| `scripts/harness-context.sh:82-85` | 헤딩 `^## 현재 단계:\s*(.+)$`, 그 값에서 `Phase\s*(\d+)` 추출(190-192) | 하드(헤딩 없으면 problem → exit 3; Phase 번호 없으면 PHASE 문서 추론 실패 → exit 3) | 계약 |
| `scripts/harness-context.sh:53-58` | D행 `^\s*\|\s*(D-[0-9]{3,})\s*\|`, 셀 5개 이상, 상태는 **마지막 칸**(112-114), 열린 행 = 마지막 칸이 `✅` 로 시작하지 않는 전부(119-121) | 하드(형식 손상 → "손상된 D행 형식") | 계약 |
| `scripts/harness-context.sh:146-153` | `git show <base>:docs/TASKS.md` 로 base 비교(D-ID 소실 검사) | 하드(경로가 바뀌면 base 쪽은 옛 경로, HEAD 쪽은 새 경로라 둘 다 같은 리터럴이면 한쪽이 실패) | 계약 |
| `scripts/docs-preservation-check.py:19,21,346-403` | `TASKS = 'docs/TASKS.md'`, `ROW = ^\|\s*(D-[0-9]{3,})\s*\|`; 새 ID 는 TASKS 에만(355), 이동은 TASKS→DONE 만(357), 기존 행·footer 순서 보존(403) | 하드(CI `ci.yml:59`) | 계약 |
| `.claude/scripts/lib/ship.sh:110` | `grep -qsF "/pull/N)" docs/TASKS.md docs/progress/TASKS-done.md` (재진입 Step 판정) | 편의(두 파일 없으면 항상 Step 8 로 판정) · 셀프테스트 `scripts/ship-resume-selftest.sh:53,92` 가 CI(`ci.yml:125`)에서 같은 경로를 임시 repo 에 만든다 | 편의(+CI 셀프테스트는 자기완결) |
| `scripts/harness-context-selftest.py:15` · `scripts/docs-preservation-selftest.py:13` | `TASKS = 'docs/TASKS.md'` (임시 repo fixture) | 자기완결이지만 **체커 상수를 바꾸면 함께 바꿔야 CI 통과** | 계약(간접) |
| `.claude/commands/{plan,next,done,ship,sync}.md`, `.agents/skills/{next,plan,done}/SKILL.md` | `docs/TASKS.md` 를 읽어라/행을 옮겨라 (plan.md:5,15 · next.md:5 · done.md:8,19 · ship.md:246-252 · sync.md:50) | 스테일 | 편의 |

### 1-2. `docs/progress/TASKS-done.md` — 위치 + "## 완료 행" 형식

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/harness-context.sh:133-135, 62-71` | `'docs/progress/TASKS-done.md'`; 헤딩 `^## 완료 행\s*$` 과 `^\s*\|\s*ID\s*\|` 헤더 필수(64), D행 0개 금지(66), 모든 행 마지막 칸 `✅`(69) | 하드(exit 3, CI) | 계약 |
| `scripts/harness-context.sh:146-162` | base 와 비교해 "완료 archive 감소" 검사 | 하드 | 계약 |
| `scripts/docs-preservation-check.py:20,346-403` | `DONE = 'docs/progress/TASKS-done.md'`; 완료 이동 행은 기존 done 행 뒤 append, base TASKS 순서 유지 | 하드 | 계약 |
| `scripts/fixtures/harness-context/TASKS-done-*.txt` + `scripts/harness-context-selftest.py:345-352` | 과거 `docs/progress/TASKS-done.md` 바이트를 **git blob sha1 로 고정**한 fixture(0d4de41c…, 1fe0093a…, 592412c4…) | 내용 고정이라 현재 경로 이동과 무관(안전) | 계약(자기완결) |
| `.claude/scripts/lib/ship.sh:110`, `ship.md:248,261`, `done.md:19`, `.agents/skills/*` | 완료 행 이동 지시 | 스테일 | 편의 |

### 1-3. `docs/progress/PHASE{N}.md` — 파일명 규칙 + 헤딩 형식

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/harness-context.sh:190-209` | `'docs/progress/PHASE%s.md' % pnum`(N 은 TASKS.md "## 현재 단계" 의 `Phase N`); 엔트리 = `^## (.+)$`; `작업 이력` 헤딩 뒤만 엔트리(199); 날짜 `\d{4}-\d{2}-\d{2}` 로 정순/역순 판별(202-203); 파일 없으면 problem(209) | 하드(`--check` exit 3, CI) — **PHASE5.md 를 하위 폴더로 옮기거나 이름을 바꾸면 CI lint 가 깨진다** | 계약 |
| `.claude/commands/done.md:21`, `ship.md:251`, `sync.md:51`, `.agents/skills/done/SKILL.md:8` | `docs/progress/PHASE{N}.md` 갱신 지시 | 스테일 | 편의 |
| `ship.md:250` | `docs/progress/phase4-prep-debt-roadmap.md` 편입 부채 행 갱신 | 스테일 | 편의 |

### 1-4. `docs/progress/TASKS-archive-*.md` (보호 아카이브)

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/docs-preservation-check.py:405-417` | `git ls-tree -r --name-only <base> docs` 전수에서 `re.fullmatch(r'docs/progress/TASKS-archive-[^/]+\.md')` → 원문 prefix 보존 강제 | 하드(수정 시) · **이동하면 새 경로는 regex 밖이라 보호가 조용히 사라진다(무력)** | 계약 |
| `docs/conventions/docs-preservation.md:56` | 같은 규칙의 산문 정본 | 스테일 | 편의 |

### 1-5. `docs/adr/README.md` 표 형식 + `docs/adr/NNNN-*.md` 파일명

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/harness-context.sh:171-181` | `read('docs/adr/README.md')`; ADR 행 `^\|\s*\[\d{4}\]`; 상태 = 3번째 칸, `'Accepted'` 비교; 행 0개면 problem(173-174) | 하드(exit 3, CI) | 계약 |
| `docs/consistency-hints.sh:39-46` | `grep -rhoE 'ADR-[0-9]{4}' docs/` → `ls docs/adr/${num}-*.md` 존재 검사(유일한 자동 판정, exit 1) | 하드(로컬 `/ship` precheck 만; CI 아님) | 편의(강한 편의) |
| `.claude/commands/{next,done,ship,plan}.md`, `.agents/skills/*` | `docs/adr/README.md` 인덱스 · `docs/adr/template.md`(done.md:11) · `fix(adr):` 규약 | 스테일 | 편의 |
| `.claude/scripts/lib/ship.sh:120` | `hpx_commit_category`: `docs/adr/*` → `adr` 분류(한 커밋 = 한 분류, `.githooks/pre-commit:30`) | 무력(ADR 이 다른 경로면 `docs` 로 분류되어 "ADR 과 계획서는 별도 커밋" 규칙이 훅에서 안 걸린다) | 편의(훅) |

### 1-6. `docs/plans/<stem>.md` · `<stem>.audit.md` (진행 중 계획서 루트)

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `.claude/scripts/lib/plan.sh:14-17` | `docs/plans/${task_id}.md` → 없으면 `docs/plans/done/${task_id}.md` (frontmatter `grade:`/`codex:` 읽기) | 하드(/plan·/work·/ship 의 등급 판정이 L 로 떨어짐) | 편의 |
| `.claude/scripts/lib/audit.sh:19-20` | `docs/plans/${task_id}.audit.md` → `done/` fallback | 편의 | 편의 |
| `.claude/scripts/lib/codex.sh:69-70` | 같은 fallback(frontmatter `codex: off` 위치 보고) | 편의 | 편의 |
| `.claude/scripts/lib/ship.sh:48-50,56-63` | 계획서 존재 + 미완 체크박스 `^\s*- \[ \]` 카운트 | 하드(ship preflight blocked) | 편의 |
| `scripts/plans-archive.sh:28-29,113-123` | `PLANS="docs/plans"`, `DONE="${PLANS}/done"`, 선택 stem 은 `$PLANS/$selection.md` 여야 함; `KEEP_ALWAYS="PLAN-BLINDSPOTS.md"`(32); frontmatter `archive: hold <PR...>`(61) | 하드(apply 거부) · `--self-test`(CI `ci.yml:62`)는 임시 트리 자기완결 | 계약(간접)/편의 |
| `scripts/docs-archive-apply.py:51-56` | `f'docs/plans/{stem}.md'`, `.audit.md`, `routes = path.replace('docs/plans/', 'docs/plans/done/', 1)` | 하드 | 계약(간접) |
| `scripts/harness-context.sh:213-214` | `glob('docs/plans/*.md')` (audit·PLAN-BLINDSPOTS 제외) → 다이제스트 "진행 중 N개" | 스테일(숫자만 틀림, 실패 아님) | 편의 |
| `scripts/replay-entrypoint-lint.sh:66-68` | `grep -rn … docs scripts --include=*.md/*.sh/*.py \| grep -v "^docs/plans/"` → `docs/plans/` **접두사만** 예외 | **무력→하드**: 계획서가 `docs/plans/` 밖(예: `docs/archive/plans/`)으로 가면 현재 2건이 즉시 REPLAY-ENTRY-003 위반 — `docs/plans/done/task-impl4-c2b-dlq-replay.md:1284`, `docs/plans/done/task-impl4-c2a-dlq-ledger.audit.md:60` (CI `ci.yml:159`) | 계약 |
| `.claude/scripts/tests/bats/{plan_grade,codex_gate,review_health}.bats` | 샌드박스에 `docs/plans` 생성(자기완결) | 안전 | 편의 |
| `.claude/commands/plan.md:17,164,204` · `work.md:16,87,141` · `ship.md:38,132,260` · `.agents/skills/{plan,work}/SKILL.md` | `docs/plans/${TASK_ID}.md` / `.audit.md` | 스테일 | 편의 |
| `scripts/toss-sandbox/sandbox.py:2,577` | docstring `docs/plans/D-064.md` (이미 `done/` 으로 이동됨 → 이미 스테일) | 스테일 | 편의 |
| `loadtest/scripts/*` 주석: `d002a-product-read.js:1,43`, `d002bc-order-create.js:1`, `d002bc-contention.js:1,16`, `d026-run-block.sh:17`, `d026-run-cell.sh:5`, `d078-mysql-sampler.sh:6`, `d078-s3-run.sh:2`, `d078-warmup-check.py:2`, `test_d002bc_contention.mjs:2` | `docs/plans/D-078-remeasure-plan.md`, `docs/plans/D-108.md`, `docs/plans/done/task-d002-bc-session.md` 등 | 스테일 | 편의 |

### 1-7. `docs/plans/done/` (+ `INDEX.md`)

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/docs-preservation-check.py:409-411` | 보호 대상 = `path.startswith('docs/plans/done/') and path.endswith('.md') and not path.endswith('/INDEX.md')` → prefix 보존 강제 | **이동 시 보호 소실(무력)**; 그 자리에서 수정 시 하드 | 계약 |
| `scripts/docs-archive-apply.py:56-58` | 목적지 `docs/plans/done/<stem>.md` 고정, 이미 있으면 거부 | 하드 | 계약(간접) |
| `scripts/plans-index.sh:24-25,33-35,66-69,91` | `DONE="docs/plans/done"`, `OUT=INDEX.md`; `*.audit.md`·`INDEX.md` 제외; 버킷은 파일명 접두사 `^task-adr\d+`/`^task-impl`/`^task-d\d+`; PR URL 베이스 `https://github.com/Kimgyuilli/PeakCart/pull/%d` 하드코딩 | 하드(생성물 경로) · `--check` 는 `/sync` 전용(`sync.md:30-33`), CI 미연결 | 편의 |
| `scripts/harness-context.sh:220-221` | `glob('docs/plans/done/*.md')` 아카이브 개수 | 스테일 | 편의 |
| `.claude/scripts/lib/{plan,audit,codex,ship}.sh` fallback (1-6 참고) | `docs/plans/done/${task_id}.md` | 편의 | 편의 |
| `.claude/scripts/tests/bats/task_id_validate.bats:32-35` | 실제 `docs/plans/done/*.md` basename 전수를 `hpx_task_id_validate` 에 통과시킴 | 하드(로컬 bats 만) | 편의 |
| `scripts/docs-preservation-selftest.py:39,89-93,141-145,385-398` | fixture `'docs/plans/done/history.md'` 가 보호 경로라는 전제로 음성 대조 | 체커 regex 를 바꾸면 **CI 셀프테스트(`ci.yml:60`)도 같이** 고쳐야 함 | 계약(간접) |
| `scripts/docs-preservation-real-selftest.py:13,58-72` | 고정 커밋 `a7ffa11f…` 의 `docs/plans/D-097.md` → `docs/plans/done/` 이동을 임시 clone 에서 재현 | 과거 커밋 체크아웃이라 **현재 레이아웃 변경에 영향 없음** | 계약(자기완결) |
| `k8s/overlays/gke/README.md:116`, `loadtest/README.md:147`, `loadtest/scripts/*.js` 주석, PHASE*/TASKS-done 본문 수백 곳 | `docs/plans/done/task-*.md` 링크/언급 | 스테일 (plans-archive 는 `git ls-files *.md` 전수를 재작성하지만, 그건 **plans 루트→done 이동**에만 적용. done/ 자체를 옮기는 건 도구 범위 밖) | 편의 |

### 1-8. `docs/plans/PLAN-BLINDSPOTS.md`

| `scripts/plans-archive.sh:32` `KEEP_ALWAYS="PLAN-BLINDSPOTS.md"`(절대 이동 안 함) · `scripts/harness-context.sh:214` 제외 규칙 · `.claude/commands/plan.md:77`, `work.md:160`, `.agents/skills/plan/SKILL.md:12` 지시문 | 스테일/편의 |

### 1-9. `docs/plans/fixtures/` — **docs 안에 있는 CI 입력**

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/saga-contract-matrix-lint.sh:19` | `MATRIX="${SAGA_MATRIX:-docs/plans/fixtures/saga-contract-matrix.tsv}"` — gate 잡 `ci.yml:444,460,466-467` (`--jvm-evidence`/`--e2e-evidence`/`--structure`/`--self-test`) | 하드(gate 실패) | 계약 |
| `scripts/saga-contract-matrix-selftest.sh:12,39,130,140` | 같은 경로 하드코딩 4곳 | 하드 | 계약 |
| `scripts/ci-docs-only.sh:20,52` | `docs/plans/fixtures/*) code=true` 분류 예외 + 셀프테스트 기대값(`ci.yml:109`) | 파일이 이동하면 예외가 **dead rule** 이 됨(무력); 셀프테스트는 문자열 테스트라 통과 | 계약 |
| `docs/06-testing-strategy.md:14,20`, `docs/plans/done/ci-critical-path.md:27` 등 | 산문 언급 | 스테일 | 편의 |
| `docs/plans/fixtures/task-harness-smoke.md`, `task-ship-smoke-fresh.md`, `task-work-smoke.md`, `docs/plans/fixtures/.audit/*` (2026-04) | `.claude`·`scripts` 어디서도 참조 0건 (grep) | 사용처 없음 — 구 state.json 하네스 fixture 잔재 | (정리 후보) |

### 1-10. `docs/plans/evidence/` — **CI 가 실행하는 코드가 docs 안에 있음**

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `.github/workflows/ci.yml:251` | `python3 -B docs/plans/evidence/D-099/check-mutants.py` ("Harness archive health" 스텝) | 하드 | 계약 |
| `docs/plans/evidence/D-099/check-mutants.py:9` | `ROOT = Path(__file__).resolve().parents[4]` — **디렉터리 깊이 4 에 의존** | 하드(깊이가 바뀌면 ROOT 오산) | 계약 |
| `docs/plans/evidence/D-099/check-mutants.py:11-48` | `scripts/harness-context.sh` 와 `ci.yml` 의 **정확한 소스 문자열**을 변이 anchor 로 사용(예: `'archived_debts = archive_health(archive, archive_path)'`, `'bash scripts/harness-context.sh --check --base "$HARNESS_BASE"'`) → `assert old in source` | 하드: harness-context.sh 의 TASKS/DONE/PHASE 경로 줄을 고치는 순간 anchor 가 어긋날 수 있음(해당 줄은 아니지만 인접 수정 주의) | 계약 |
| `scripts/harness-context-selftest.py:178-180` | 워크플로 스텁 실행 후 기대 도구 목록에 `'tool:python3:-B docs/plans/evidence/D-099/check-mutants.py'` 리터럴 | 하드(ci.yml:251 을 바꾸면 이 줄도) | 계약 |
| `scripts/codex-review-render.sh:10` | 주석 `docs/plans/evidence/D-104/README.md` | 스테일 | 편의 |
| `scripts/harness-context-selftest.py:180` 외, `docs/plans/evidence/*` 하위 README 는 plans-archive 링크 재작성 대상(백업 `55sep8zp` 에 `docs/plans/evidence/D-078/README.md`, `D-065-lineage-writer/README.md` 가 재작성본으로 들어 있음) | — | — |

### 1-11. `docs/preservation/*.json`

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/docs-preservation-ci.py:21-22,28-38` | `git diff --name-only -z --diff-filter=ACMRD <base> -- docs/preservation`; `.json` 만; base 에 있던 manifest 는 **바이트 불변** 강제(33-36); 새 manifest 를 체커에 전달 | 하드 — 디렉터리를 옮기면 과거 manifest 가 "삭제"로 잡혀 `Missing/unsafe preservation manifest` | 계약 |
| `scripts/docs-archive-apply.py:30-32` | `output.parent == Path('docs/preservation')` 강제 | 하드 | 계약(간접) |
| `scripts/docs-preservation-check.py:40-43` (`safe_path`) | manifest 의 모든 source/destination/correction 경로는 **`docs/` 로 시작 + `.md` 로 끝나야** 함 | 하드 — `.md` 를 `docs/` 밖으로 내보내는 이동은 선언 자체가 불가 | 계약 |
| `.claude/commands/{ship,done,sync}.md`, `.agents/skills/{sync,done,ship}/SKILL.md` | `--manifest docs/preservation/<이름>.json` | 스테일 | 편의 |

### 1-12. `docs/runbooks/*.md`

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/d092-ops-fixture.py:12,16,82,102,112,238,271` | `docs/runbooks/outbox-failed-recovery.md`, `docs/runbooks/slack-webhook-secret.md` 를 `read_text()` 후 regex 로 **내용 계약** 검사 (CI `ci.yml:191`) | 하드 | 계약 |
| `scripts/replay-drain-preflight.sh:356` | 메시지 문자열 `docs/runbooks/dlq-recovery.md §6-R` | 스테일 | 편의 |
| `scripts/drill/user-key-rotation-drill.sh:4`, `k8s/overlays/minikube-rotation-drill/README.md:3` | `docs/runbooks/user-jwt-key-rotation.md` 주석/링크 | 스테일 | 편의 |

### 1-13. `docs/conventions/*.md`

`.githooks/commit-msg:4,52`(`writing.md` 안내문), `README.md:20`, `.claude/commands/{plan,work,ship,done,sync}.md` 다수(`harness-rationale.md`, `codex-review.md`, `docs-preservation.md`, `writing.md`), `.agents/skills/*`(`docs-preservation.md`), `scripts/writing-lint.sh:4` 주석 — 전부 산문 포인터. 스테일/편의. (writing-lint 는 커밋 메시지·PR 본문만 검사하므로 문서 위치와 무관: `writing-lint.sh:6`.)

### 1-14. `docs/consistency-hints.sh` (docs 안에 있는 실행 스크립트)

| `docs/consistency-hints.sh:21` `cd "$(dirname "$0")/.."` — **레포 루트 바로 아래 한 단계(docs/)에 있다는 가정**; 더 깊이 넣으면 cd 가 docs/ 에서 멈춰 전 항목이 빈 결과 | 하드(로컬) | 편의 |
| `docs/consistency-hints.sh:24` `LAYER1_DOCS=(docs/01-*.md … docs/07-*.md)`, `:42` `docs/adr/${num}-*.md`, `:53` `find k8s` | 하드/무력 | 편의 |
| `.claude/scripts/lib/ship.sh:17,22` `bash docs/consistency-hints.sh`(없으면 `unavailable` 로 skip — 조용히 무력), 로그 `.cache/consistency-<task>-<ts>.log`(14); `ship.md:85` | 무력 | 편의 |

### 1-15. Layer 1 `docs/0N-*.md` · 루트 링크

`README.md:29-41,58`(7개 링크), `loadtest/README.md:3,41`, `loadtest/reports/TEMPLATE.md:4,107`, `k8s/overlays/gke/README.md:61`, `CLAUDE.md` 문서 참조 맵, `.claude/commands/sync.md:57-60` — 전부 링크/지시문(스테일/편의). `scripts/docs-preservation-real-selftest.py:29,40` 은 고정 커밋의 `docs/02-architecture.md`·`README.md` 를 쓰므로 현재 변경과 무관.

### 1-16. `docs/evidence/` · `docs/progress/evidence/` · `docs/review/`

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| `scripts/gke-security-smoke.sh:30` | `EVIDENCE_DIR="${EVIDENCE_DIR:-docs/progress/evidence}"` (기본 **출력** 경로) | 스테일(새 위치에 쓰기 시작) | 편의 |
| `scripts/tests/test_kafka_topic_state_guard.py:27`, `loadtest/scripts/d026-collect.py:18`, `loadtest/captures.md:54`, `k8s/overlays/minikube-rotation-drill/README.md:11` | `docs/progress/evidence/*.md` 주석/링크 | 스테일 | 편의 |
| `scripts/ci-docs-only.sh:50` | 셀프테스트 예시 문자열 `docs/evidence/D-063/evaluated-grafana-alerts.yml` | 문자열 테스트라 영향 없음 | — |
| `docs/evidence/D-063/run{1,2,3}/{cleanup,execution,startup}.log` 9개 | **tracked-but-ignored**(`.gitignore:30 *.log`) — `git mv` 는 되지만 `cp` + `git add` 는 무시됨(`-f` 필요) | 이동 절차 함정 | — |
| `docs/review/{final-report,ci-optimization-analysis}.md` | 참조는 이력 문서만: `docs/adr/0007-…:108`, `docs/progress/PHASE3.md:573,612`, `TASKS-archive-phase1-3.md:494`, `docs/plans/done/D-052.md:53` | 스테일 | 편의 |
| preservation 체커 `declared_deletions`(`docs-preservation-check.py:449-454`) | `git diff --no-renames --diff-filter=D <base> -- docs` 에서 **`.md` 만** manifest 요구 → yml/json/tsv/log 등 비-.md 는 계약 밖 | — | 계약(범위 메모) |

### 1-17. `docs/learning/` (gitignored, 추적 0건) 을 가리키는 **추적 문서**

`docs/adr/0025-…:138,158`(`docs/learning/09-distributed-lock-optimistic-lock.md`), `docs/adr/0032-…:114`(`docs/learning/drafts/tools/blog31/payload/`), `docs/adr/0033-…:129`(`docs/learning/drafts/tools/blog32/hash-inventory.json`) — immutable ADR 이 **로컬에만 있는 증적**을 인용. 또 `docs/plans/D-108.audit.md:13`, `docs/plans/done/D-064.audit.md:22`, `done/D-106-remeasure-prereq.audit.md:9` 가 "`replay-entrypoint-lint.sh` 가 `docs/learning/` 로컬 사본 때문에 로컬에서 exit 1" 을 기록 — 그 lint 가 `docs` 를 **추적 여부와 무관하게** grep 하기 때문(`replay-entrypoint-lint.sh:66-68`).

### 1-18. `docs/**` 전체를 한 덩어리로 보는 규칙

| 규칙 위치 | 내용 | 영향 |
|---|---|---|
| `scripts/ci-docs-only.sh:19-25` | `docs/plans/fixtures/*`→code, `docs/*`→docs, `*/*`→code, 루트 `*.md`→docs, 그 외→code; 빈 목록→code(27) | 2장 참고 |
| `scripts/docs-preservation-check.py:422-447` (`inbound_links`) | 이동(source)이 선언되면 **`git ls-files *.md` 전체**(README.md, loadtest/README.md, k8s READMEs 포함)에서 그 source 를 가리키던 링크가 목적지로 재표적됐는지 검사 | 하드 |
| `scripts/docs-archive-apply.py:96-102` | 같은 전수 집합의 상대 링크를 자동 재작성 | — |
| `.dockerignore:7-9` | `docs`, `k8s`, `*.md` 는 빌드 컨텍스트 제외. **`loadtest` 는 제외되지 않음** → `loadtest/reports` 44MB 가 `images` 잡 6회·e2e compose build 의 컨텍스트에 매번 들어감 | 비용 |
| `.claude/scripts/lib/ship.sh:118-126` + `.githooks/pre-commit:30` | 커밋 분류: `docs/adr/*`→adr, `*.md\|docs/*`→docs, `.claude/* .githooks/* scripts/* *.gradle gradle/* .github/* *.yml *.yaml Dockerfile*`→chore, `*src/test/*`→test, 그 외→**src**. 따라서 `loadtest/reports/<date>/` 삭제 커밋은 `.md`(docs)+`.yml`(chore)+`.json/.tsv/.txt/.sh/.png`(src) 혼합 → 훅이 막음(`HPX_ALLOW_MIXED=1` 필요) | 절차 |
| `.githooks/commit-msg:29,39` → `scripts/writing-lint.sh --msg` | 제목 `^(feat\|fix\|refactor\|test\|docs\|chore)(\([^)]+\))?: .+`, em dash·화살표·이모지 금지 | 절차 |
| `.claude/settings.json` PreToolUse → `.claude/scripts/hooks/commit-verify-guard.sh:28,41` | `--no-verify` 차단, `core.hooksPath=.githooks` 강제 | 절차 |

### 1-19. `loadtest/reports/<date>/…`

| 의존 파일:라인 | 리터럴 | 깨지는 방식 | 분류 |
|---|---|---|---|
| **`scripts/tests/test_kafka_topic_state_guard.py:26`** | `SNAPSHOT = ROOT / "loadtest/reports/2026-10-24/s4/d065-5-topic-configs-all.txt"` — 187·207·349 에서 `read_text()` (CI lint `ci.yml:167`) | **하드: `2026-10-24/s4` 를 지우면 CI lint 실패** | 계약 |
| `order-service/src/test/java/com/peekcart/global/kafka/KafkaTopicConfigMechanismIntegrationTest.java:340` | 주석 `loadtest/reports/2026-10-24/s4/d065-5-effective-sample.txt` | 스테일 | 편의 |
| `docs/progress/evidence/d078-gke-20261024.md:6,166,196` · `d078-gke-20261025.md:6,154,183` · `docs/progress/PHASE5.md:1020,1101` · `docs/plans/D-108.md:11,38,124` · `docs/plans/D-078-remeasure-plan.md:9,294,364,411,423,433` · `docs/adr/0045-…:193` · `docs/plans/done/D-075-outbox-throughput.md:8`, `D-106-topic-config-race.md:6-7`, `.audit.md:26`, `D-106-topic-guard-throttle.md:6-7` · `docs/TASKS.md:52-61`(D-078·D-108·D-110·D-112~D-115 행) | "원자료: `loadtest/reports/2026-10-2{4,5,6}/…`", `session-issues.md`, `environment-fingerprint.txt`, `s3/*`, `grafana/*kafka-lag*.json`, `session-scripts/*.sh` | 스테일(증적 포인터가 죽음) — **열린 부채 행 D-108~D-115 가 2026-10-25/26 원자료를 가리킴** | 편의(증적 무결성) |
| `docs/progress/PHASE3.md:521-523,974,1213,1253,1428,1433` · `TASKS-archive-phase1-3.md:384-496` · `docs/progress/TASKS-done.md:24(D-018)` · `phase4-prep-debt-roadmap.md:41,85` · `loadtest-tool-evaluation.md:161,171` · `docs/plans/done/task-jmeter-to-k6.md:21,146`, `task-loadtest-session-c.md:271` | `loadtest/reports/2026-04-09/*`, `2026-04-29/REPORT.md` | 스테일 | 편의 |
| `loadtest/scripts/d078-mysql-sampler.sh:5-7`, `d078-s3-export.sh:3,8`, `d078-s3-run.sh:3,6` | `SESSION_DIR=loadtest/reports/<날짜>` 인자 규약 + "2026-10-25 세션 스크립트(`loadtest/reports/2026-10-25/session-scripts/*.sh`)를 옮겼다" 출처 주석 | 스테일 | 편의 |
| `loadtest/scripts/order-concurrency.js:12-22`, `d002bc-order-create.js:14`, `loadtest/README.md:95-119,209-254`, `loadtest/captures.md:15`, `loadtest/reports/TEMPLATE.md:3` | `loadtest/reports/YYYY-MM-DD/{REPORT.md,sql,grafana}`, `loadtest/reports/local/` 출력 규약 | 스테일 | 편의 |
| 로컬 전용 `*.log` 59개(2.5MB) — `loadtest/reports/2026-10-2{4,5,6}/**/*.k6.log`, `stall-logs/*.log`, `seed-products.log`, `2026-04-29/run2/k6-stdout.log` | `.gitignore:30 *.log` 에 걸려 **git 에 없음**; `docs/progress/evidence/d078-gke-20261025.md:6` 이 이를 명시 | 로컬 디렉터리 삭제 시 영구 소실 / git 만 prune 하면 고아로 남음 | — |
| `scripts/fixtures/harness-context/TASKS-done-*.txt:21` | 과거 D-018 행의 문자열 | blob 고정 fixture, 영향 없음 | — |

### 1-20. `loadtest/scripts/*`, `loadtest/cleanup.sh`, `loadtest/README.md`

- 스크립트 간 상호 참조는 `loadtest/scripts/<name>` 리터럴(`d026-run-block.sh:75` → `$ROOT/loadtest/scripts/d026-run-cell.sh`, `d078-s3-run.sh:78,138,142` → `loadtest/scripts/d078-warmup-check.py`·`d078-capture.py`, `d078-s3-export.sh:27`), SQL 은 `loadtest/sql/*.sql`(`d078-s3-run.sh:123-135`). 출력은 `.cache/d026-runs`(`d026-run-block.sh:32`, `d026-run-cell.sh:39`, `d026-collect.py:16`).
- `loadtest/cleanup.sh` 를 가리키는 곳: `scripts/gke-security-smoke.sh:311`(안내 문구), `k8s/overlays/gke/README.md:127`, `loadtest/README.md:125-126,320`, `TEMPLATE.md:122` — 전부 산문.
- 테스트: `node --test loadtest/scripts/test_d002bc_contention.mjs`, `loadtest/scripts/test_d026_collect.py`, `test_d078_*.py` — CI 미연결(grep 결과 ci.yml 에 없음). 로컬 전용.
- `loadtest/captures.md:20,37` ↔ `loadtest/scripts/d078-capture.py` 표를 "손으로 동기" 하는 규약.

### 1-21. 조사했지만 의존이 없는 곳
`build.gradle`, `settings.gradle`, `*/build.gradle`: `docs/`·`loadtest/` 참조 0건. `.claude/settings.json`·`settings.local.json`·`.claude/schemas/*.json`: 0건. `Dockerfile`·`docker-compose*.yml`: 0건. `.github/workflows/{approval-recovery,flyway-contract,payment-dlq-reproduction,product-barrier-reproduction,replay-collector,signed-gateway}.yml`: `paths:` 필터가 `scripts/**` 만 가리키고 docs/loadtest 는 없음. `AGENTS.md` 본문: 경로 없음(`.agents/skills/*` 로 위임).

---

## 2. docs-only PR 의 CI 동작과 보존 CI 의 base 요구

### 2-1. 무엇이 docs-only 인가 (`scripts/ci-docs-only.sh:13-29`, `ci.yml:17-42`)
- `changes` 잡이 PR 이벤트에서만 `git diff --no-renames --name-only HEAD^1 HEAD`(merge 커밋의 첫 부모 = base) 로 변경 경로를 뽑아 분류한다. push 는 무조건 `code=true`(`ci.yml:37-39`).
- 분류(순서대로 첫 매치): `docs/plans/fixtures/*`→코드 / `docs/*`→문서 / `*/*`(슬래시 포함 나머지)→코드 / 루트 `*.md`→문서 / 그 외→코드. 목록이 비면 코드(27).
- `--no-renames` 라 이동은 D+A 로 잡힌다. `docs/a.md → docs/b.md` 는 양쪽 다 `docs/*` 라 문서 유지.

### 2-2. "docs/ 이동 + loadtest/reports/ 삭제" PR 은 매트릭스를 건너뛰는가
- **아니다.** `loadtest/reports/...` 는 `*/*` 에 걸려 `code=true` → `test`(6 shard)·`guards`·`images`(6)·`e2e`(3) 전부 돈다(`ci.yml:257-259,337-339,498-500,626`).
- loadtest prune 을 별도 PR 로 떼어도 그 PR 은 여전히 `code=true`(loadtest 는 docs 가 아님). docs-only 로 만들려면 `ci-docs-only.sh` 분류 자체를 바꿔야 하는데, 그 변경(`scripts/*`)도 코드다.
- 경로 리터럴을 고치는 스크립트 수정(`scripts/*.py|sh`, `.claude/**`, `ci.yml`, `.gitignore`)이 섞인 PR 도 전부 `code=true`. 순수 `docs/**` + 루트 `*.md` 만 바꾸는 PR 만 매트릭스를 건너뛴다.

### 2-3. docs-only PR 에서도 도는 것 (`lint` 잡은 `needs`/`if` 없음, `ci.yml:45`)
1. Document preservation(`ci.yml:55-62`): `docs-preservation-ci.py --base $PRESERVATION_BASE`, `docs-preservation-selftest.py`, `docs-preservation-real-selftest.py`, `plans-archive.sh --self-test`.
2. Run CI policy lints(`ci.yml:82-198`) 전부 — 문서 레이아웃과 직접 닿는 것: `replay-entrypoint-lint.sh`(159; docs 전수 grep), `d092-ops-fixture.py`(191; runbook 2개 내용 검사), `test_kafka_topic_state_guard.py`(167; `loadtest/reports/2026-10-24/s4/…` 읽기), `ci-docs-only.sh --self-test`(109), `ci-lint-wiring-lint.py`(95; `scripts/*-lint.sh` ↔ ci.yml 배선, `.claude/commands/ship.md` 코드블록에서 `writing-lint.sh` 호출 존재 확인 — 74-83).
3. Verify local DLQ contract safety(201-223, docker 사용).
4. CI test matrix coverage lint(228-231).
5. Harness archive health(233-251): `harness-context.sh --check --base $HARNESS_BASE`, `harness-context-selftest.py`, `docs/plans/evidence/D-099/check-mutants.py`.
6. `gate`(395-491): `code=false` 이면 test/guards/images/e2e 가 **정확히 `skipped`** 여야 하고(488), `changes`·`lint` 는 success 여야 한다.

### 2-4. 보존 CI 의 base — 한 PR 이어야 하는가
- `ci.yml:57` `PRESERVATION_BASE: ${{ github.event.pull_request.base.sha || github.event.before }}`, `ci.yml:235` `HARNESS_BASE` 동일. `docs-preservation-ci.py:15-22` 는 그 base 로 `git rev-parse` 후 `git diff <base> -- docs/preservation` 으로 **이 PR 이 새로 추가한 manifest** 를 찾고, base 에 있던 manifest 는 바이트 불변을 강제(33-36). 체커(`docs-preservation-check.py:456-461`)는 `declared_deletions → transfers → tasks → protected → inbound_links` 를 base↔HEAD 로만 본다.
- 따라서 **여러 PR 로 나눌 수 있다.** 단 조건: (a) 각 PR 이 자기 이동·삭제분(`docs/**/*.md` 전부)을 그 PR 의 새 manifest 에 source(base 버전 sha256)+destination(전 라인 parts) 으로 선언한다(`declared_deletions` 449-454, `transfers` 279-336). (b) manifest 경로는 `docs/preservation/<이름>.json` 이어야 하고 안의 경로는 `docs/…*.md` 만 허용(`safe_path` 40-43) — `.md` 를 `docs/` 밖으로 내보내는 이동은 체커 수정 없이는 **선언 불가 → 차단**. (c) PR 의 `base.sha` 가 이전 PR 머지 **이전** 시점이면 이전 PR 의 manifest 가 이 PR 에서 "새 manifest" 로 다시 검증된다(통과는 하지만 소스 파일을 두 PR 이 겹쳐 건드리면 꼬인다). 규약(`docs/conventions/docs-preservation.md:7`, `ship.md:63`)도 "최종 PR base 를 편집 전에 고정, 편집 후 HEAD 로 바꾸지 말 것" 이라 **순차 머지(앞 PR 머지 → 다음 PR 생성/리베이스)** 가 안전하다. (d) main push 실행은 `github.event.before`(직전 main) 기준이라 머지 직후 같은 검사를 한 번 더 통과해야 한다.
- 비-.md(yml/json/tsv/png/log) 이동·삭제는 보존 계약 밖 — 자유롭게 PR 을 쪼갤 수 있다. 다만 `test_kafka_topic_state_guard.py:26` 의 fixture(1-19)는 별개 하드 의존.
- `harness-context.sh --check --base` 는 `docs/TASKS.md`·`docs/progress/TASKS-done.md` 두 경로를 **base 와 HEAD 양쪽에 같은 리터럴로** 읽으므로, 이 두 파일을 옮기는 PR 은 스크립트 수정과 **같은 PR** 이어야 한다(그래도 base 쪽 `git show base:새경로` 가 실패 → 같은 PR 안에서도 통과 불가; 2단계 전환(구경로 유지 후 다음 PR 에서 삭제)이나 스크립트에 폴백이 필요).

---

## 3. 로컬 잔재 인벤토리 (gitignored/untracked 만)

| 경로 | 크기 | 정체 | 생성 주체 | 읽는 스크립트 | 삭제 안전성 |
|---|---|---|---|---|---|
| `.claude/worktrees/` | 637M (ab66 464M · a869 110M · a698 32M · ac4c 31M) | Claude 에이전트 worktree 4개(4장 표) | Claude Code(`.git/info/exclude:11` 자동 등록) | 없음 | 전부 clean·전 브랜치 머지. 2개는 pid 95296(현재 `claude` 세션, Oct 24 01:18 시작) 잠금 → 세션 종료 후 `git worktree remove`. 큰 용량은 ab66 의 `*/build/` |
| `.cache/codex-off` | 112B | "사용자 지시(2026-09-22): Codex 리뷰 호출 금지" 스위치 | 사용자 | `.claude/scripts/lib/codex.sh:57-61`, bats | **유지**(메모리: 워크트리마다 따로) |
| `.cache/codex-reviews/` | 88K (patch 2, 10-24) | /work diff 리뷰용 패치 | `work.md:50-51` | `scripts/codex-review-render-selftest.sh:273`(V9, 없으면 skip) | 삭제 안전(PR #232/#237 머지) |
| `.cache/consistency-*.log` | 17개 ≈150K | /ship precheck 로그 | `ship.sh:14` | 없음 | 삭제 안전(전부 머지된 작업) |
| `.cache/docs-archive-backup-*` | 9개 ≈1.0M | `plans-archive.sh --apply` 전 원문 preimage | `docs-archive-apply.py:105-111` | 없음 | **전부 머지 확인** → 삭제 안전: `55sep8zp`→#230, `3k6cohik`·`i4wqy1ki`·`yp631bt3`→#233, `i73pdmsm`→#236, `s4d7wnm6`→#239, `g9e_bsco`→#240, `vi3ua0ft`→#246, `l4hu4n0i`→#250 (모든 source 가 `docs/plans/done/` 에 존재) |
| `.cache/e2e/` | 7.4M (8 run, 09-28~10-17) | 로컬 saga e2e 증적 | `scripts/saga-e2e-smoke.sh:217` | `saga-contract-matrix-lint.sh:431` 기본 경로(로컬) | 삭제 안전(재실행으로 재생성; D-041/D-048 결과는 docs 에 요약) |
| `.cache/pr-body-*.md` | 15개 ≈70K | PR 본문 초안(재시도 재사용) | `ship.md:161` | `ship.md:179` writing-lint | 삭제 안전(15개 모두 머지: #226~#250) |
| `.cache/ship-D-106-topic-config-race/`, `ship-D-107-recovery-lab-topics/` | 44K+8K | 커밋별 메시지 초안 `cN-<분류>.txt` + `.lint` 출력 | 스크립트 아님(ship.md/ship.sh 에 없음) → /ship 세션이 수동 생성 | 없음 | 삭제 안전(#237/#238 머지) |
| `.cache/promql-syntax-rules.yml`, `promtool.err` | 3K, 0B | promtool 입력/에러 | `scripts/observability-promql-lint.sh:532,1053` | 같은 스크립트가 매번 덮어씀 | 삭제 안전 |
| `.cache/d078-s0.txt` | 1.2K | D-078 S0 GCP 상태 메모(project `peekcart-d078`, billing id, Secret fingerprint, GSA) | 세션 수동 메모 | 없음 | **유일 로컬 메모**이나 fingerprint 는 `docs/plans/done/D-078-session-plan.md:18` 에도 있음 → 소유자 판단 |
| `docs/plans/.archive/` | 356K, 46 `*.state.json`(2026-04-20~08-29) | 구 state.json 하네스의 작업 상태 | 구 `/plan`·`/work`·`/ship`(현 커맨드는 "state.json 을 쓰지 않는다": `plan.md:8`, `work.md:8`, `ship.md:10,124,285`) | `.claude/scripts`·`scripts` 에 reader 0건 | 삭제 안전(지속 이력은 `done/*.audit.md`). `.gitignore:42-45` 규칙은 이것만을 위해 남아 있음 |
| `docs/learning/` | 45M (추적 0) | 블로그 초안·근거 묶음 | 에이전트 세션 수동(2026-10-18/20); 레포 스크립트 생성 아님(grep 0건) | `replay-entrypoint-lint.sh` 가 **의도치 않게** 스캔(로컬 exit 1 기록 3건) | 소유자 판단. 내역: `drafts/tools/blog31` 16M(CI job 로그 72·JUnit xml 211·json 63, README 있음), `blog32` 17M(java 603·md 151·sql 134·gradle 75·patch 9, README), `blog33` 10M(md 134·json 79·patch 46·stdout/stderr 84, README); `drafts/*.md` 14편 ≈1.3M; 상위 `*.md` 25편 ≈0.9M. **ADR-0032:114·ADR-0033:129 가 blog31/32 안 파일을 증적으로 인용** |
| `.gradle-work/` | 527M | 임시 `GRADLE_USER_HOME`(`docs/progress/PHASE3.md:1327` 1회 사용 기록; caches 2026-04-14, `.tmp` 09-08) | 수동 환경변수 | 설정 참조 0건(.gitignore 만) | 삭제 안전(재생성 가능 캐시) |
| `.gradle/` | 155M | 프로젝트 로컬 Gradle 캐시(8.14.4) | Gradle | — | 삭제 안전 |
| `build/` + `*/build/` | 332K + 668M(총) | 빌드 산출물(order 153M·payment 129M·product 122M·notification 112M·user 110M·gateway 52M) | Gradle | CI 는 자기 러너에서 생성 | `./gradlew clean` 으로 재생성 |
| `.idea/` | 96K | IntelliJ 설정(`modules/*.iml` 10개 포함) | IDE | — | IDE 사용 중이면 유지 |
| `scripts/__pycache__/` 등 7곳 | 84K+88K+… | 로컬 python 실행 바이트코드(CI 는 `-B`) | python | — | 삭제 안전 |
| `local-keys/` | 40K, pem 10개(07-05~09-15) | dev RS256 개인/공개키(gateway·user·rot2/rot3) | 수동 생성(ADR-0013 D2) | `gateway/src/main/resources/application.yml:289` 기본 경로, 드릴 README | **유일 로컬 상태 — 유지**(공개키가 커밋된 ConfigMap 과 짝) |
| `loadtest/reports/local/` | 없음 | — | — | — | — |
| `loadtest/reports/**` 의 ignored `*.log` | 59개 2.5M | k6 로그·stall 로그·seed 로그 | 측정 세션 스크립트 | 없음(`d078-gke-20261025.md:6` 가 "저장소가 `*.log` 를 무시" 명시) | git 에 없음 → 로컬 삭제 시 소실 |
| `.claude/scheduled_tasks.lock` | 4K | Claude Code 런타임 잠금(pid 95296 생존) | Claude Code(`.git/info/exclude:8`) | — | 세션 살아 있는 동안 건드리지 않음 |
| `k8s/overlays/gke-probe-state1/` | 24K | 측정용 Internal LB 복원 overlay(operator-local) | 수동(`PHASE4.md:737`, `.git/info/exclude:18`) | 없음 | 유일 로컬 상태 → 소유자 판단 |
| `.sdkmanrc` | 17B | SDKMAN 로컬 설정(`.gitignore:36`) | 사용자 | sdk | 유지 |
| `scripts/e2e/business-groups.txt` | — | consumer group 목록 | `saga-e2e-smoke.sh:309` 생성 | `scripts/e2e/saga_e2e.py:247` | 재생성 가능 |
| `.claude/settings.local.json` | — | 로컬 권한(`Bash(bash *)`) | 사용자 | Claude Code | 유지(글로벌 ignore `~/.config/git/ignore:1`) |

`git status --ignored` 상위 20(KB): `.claude/worktrees/` 652,480 · `.gradle-work/` 539,720 · `.gradle/` 158,724 · `order-service/build/` 153,160 · `payment-service/build/` 129,284 · `product-service/build/` 121,624 · `notification-service/build/` 112,232 · `user-service/build/` 110,272 · `gateway/build/` 52,232 · `docs/learning/` 45,820 · `.cache/` 8,976 · `common/build/` 2,116 · `peekcart-common-messaging/build/` 1,376 · `peekcart-common-auth/build/` 1,200 · `loadtest/reports/2026-10-26/s3/c0/invalid/contend-retry2/stall-logs/product-service.log` 796 · 같은 디렉터리 `mysql-since-1750.log` 408 · `docs/plans/.archive/` 356 · `loadtest/reports/2026-10-26/s3/c0/c2-payment-service-2100.log` 344 · `build/` 332 · `loadtest/reports/2026-04-29/run2/k6-stdout.log` 132. (ignored 항목 총 91줄; 미추적-비무시 파일 0건; stash 0건.)

---

## 4. worktree / 브랜치 현황

### 4-1. worktree (`git worktree list --porcelain`; prune dry-run 결과 비어 있음 = 디렉터리 전부 존재)

| worktree | 브랜치 | HEAD | main 에 머지 | clean | 잠금 | 크기 | PR |
|---|---|---|---|---|---|---|---|
| `.claude/worktrees/agent-a69830a224f94b6c8` | `debt/d106-remeasure-design` | `cd7e6bc` | 예(main 보다 0 앞) | 예(porcelain 0) | **locked** "claude agent … pid 95296"(생존) | 32M | #243 |
| `.claude/worktrees/agent-a869a8e39f4a9af7b` | `docs/campaign-records-20261025` | `d617b82` | 예 | 예 | **locked** pid 95296 | 110M(build 포함) | #245 |
| `.claude/worktrees/agent-ab66e457f5b9f33aa` | `debt/d075-outbox-throughput` | `46af686` | 예(origin 보다 behind 8) | 예 | 없음 | 464M(build 포함) | #242 |
| `.claude/worktrees/agent-ac4c301e228122a5d` | `debt/d107-deploy-topics` | `1f6bbda` | 예 | 예 | 없음 | 31M | #241 |

각 worktree 안에도 자기 `.cache/`, `__pycache__`, `build/` 가 따로 있다(ignored).

### 4-2. 로컬 브랜치 — `git branch --merged main` 결과 **전부 머지됨**(main 제외 20개)
`debt/d065-contract-migration`(#244) · `debt/d075-outbox-throughput`(#242, wt) · `debt/d106-d107-followup`(#239, **upstream gone**) · `debt/d106-remeasure-design`(#243, wt) · `debt/d106-remeasure-prereq`(#232) · `debt/d106-topic-config-race`(#237) · `debt/d107-deploy-topics`(#241, wt) · `debt/d107-recovery-lab-topics`(#238) · `docs/archive-merged-subplans`(#233) · `docs/campaign-records-20261025`(#245, wt) · `docs/d078-gke-session`(#230) · `docs/d078-remeasure-session`(#246) · `docs/d108-remeasure-prep`(#248) · `docs/d108-remeasure-session`(#250) · `feat/D-064-toss-sandbox`(#249) · `feat/digest-open-rows`(#240) · `worktree-agent-a69830a224f94b6c8` · `worktree-agent-a869a8e39f4a9af7b` · `worktree-agent-ab66e457f5b9f33aa` · `worktree-agent-ac4c301e228122a5d`(4개 모두 `6905952`=#239 를 가리키는 Claude worktree 생성 잔재, 원격 없음).

### 4-3. 원격 브랜치 — `git branch -r --merged origin/main` 22줄(HEAD·main 제외 **20개, 미머지 0개**)
`origin/Kimgyuilli/ci-slim-down-audit`(#231) · `origin/Kimgyuilli/sync-context-usage-diagnosis`(#247) · `origin/debt/{d065-contract-migration,d075-outbox-throughput,d106-remeasure-design,d106-remeasure-prereq,d106-topic-config-race,d107-deploy-topics,d107-recovery-lab-topics}` · `origin/docs/{archive-merged-subplans,campaign-records-20261025,d078-gke-session,d078-remeasure-session,d108-remeasure-prep,d108-remeasure-session,debt-records-20261024}` · `origin/feat/{D-064-toss-sandbox,D-089-stale-state,D-092-ops-closure,digest-open-rows}`. 열린 PR 0건(`gh pr list --state open`).

---

## 5. `.gitignore` 불일치

1. **tracked-but-ignored 10개**(`git ls-files -i -c --exclude-standard`): `docs/evidence/D-063/run{1,2,3}/{cleanup,execution,startup}.log`(9, `*.log` 규칙 30행) · `gradle/wrapper/gradle-wrapper.jar`(`*.jar` 35행). 후자는 5행 `!gradle/wrapper/gradle-wrapper.jar` 가 있지만 **35행 `*.jar` 가 뒤에 와서 부정 규칙이 무효**(현재 추적 중이라 당장은 무해, 재추가 시 `-f` 필요).
2. **매치 대상이 없는 규칙**: `src/main/resources/application-local.yml`, `src/main/resources/application-prod.yml`(26-27; 루트 `src/` 없음 — **모듈별 `*/src/main/resources/application-local.yml` 은 보호 안 됨**), `*.iws`, `*.ipr`, `out/`, `.classpath`, `.project`, `.settings/`, `.vscode/`, `logs/`(`.git/` 안에만 존재), `*.class`(build/ 가 이미 덮음), `.env`, `docs/plans/**/*.state.json`, `docs/plans/**/*.state.json.tmp.*`, `docs/plans/**/*.lock/`(42-44; `.archive/` 외 실파일 0), `loadtest/reports/local/`(49; 디렉터리 없음), `*.py[cod]`(56; `__pycache__/` 가 덮음), `.DS_Store`. `*.iml` 은 `.idea/` 안에서만 매치.
3. `*.log`(30행)가 **측정 세션 로그 59개를 추적에서 빼고 있다**(3장 표). 의도인지 확인 필요.
4. 버전관리 밖 ignore: `.git/info/exclude`(Claude Code 가 쓴 `**/.claude/{scheduled_tasks.lock,worktrees,…}` 10줄 + 수동 `k8s/overlays/gke-probe-state1/`), 글로벌 `~/.config/git/ignore`(`**/.claude/settings.local.json`). 다른 클론에서는 재현되지 않음.
5. `.dockerignore` 는 `docs`·`k8s`·`*.md` 를 빼지만 `loadtest`(45M)·`local-keys`·`.cache` 를 빼지 않는다.

---

## 6. 질문 목록 (소유자 결정 항목)

| # | 무엇 | 왜 애매한가 | 선택지 |
|---|---|---|---|
| Q1 | `docs/TASKS.md`·`docs/progress/TASKS-done.md`·`docs/progress/PHASE{N}.md`·`docs/adr/README.md` 를 옮기거나 헤딩/표 형식을 바꿀 것인가 | `harness-context.sh`(81,133,171,192) · `docs-preservation-check.py:19-20` · `ship.sh:110` · 셀프테스트 3종 · `check-mutants.py` anchor 가 리터럴로 묶여 있고, `--check --base` 는 base 쪽도 같은 경로를 읽어 **한 PR 안에서도 단순 이동이 통과 못 함** | ① 네 파일·형식은 고정(권장 전제) ② 옮기되 스크립트에 구/신 경로 폴백 + 2단계 PR |
| Q2 | `docs/plans/done/` 와 `docs/progress/TASKS-archive-*.md` 를 다른 폴더로 옮길 것인가 | 보호 regex(`docs-preservation-check.py:409-411`)·아카이브 routes(`docs-archive-apply.py:56`)·`plans-index.sh:24`·lib 4곳 fallback·bats 가 리터럴. 옮기면 **보호가 조용히 사라지고** `replay-entrypoint-lint` 예외(`^docs/plans/`)도 벗어나 즉시 2건 위반 | ① 유지 ② 이동 + 스크립트 9곳·셀프테스트 동기 수정(한 PR, `code=true`) |
| Q3 | `docs/plans/evidence/D-099/check-mutants.py`(CI 가 실행하는 코드)와 `docs/plans/fixtures/saga-contract-matrix.tsv`(gate 입력)를 `scripts/` 쪽으로 뺄 것인가 | 문서 폴더 안의 실행물/입력물. 뺄 경우 `ci.yml:251`·`harness-context-selftest.py:180`·`parents[4]` / `saga-contract-matrix-lint.sh:19`·selftest 4곳·`ci-docs-only.sh:20,52` 수정 | ① 현 위치 유지 ② 이동(각 1 PR) |
| Q4 | `loadtest/reports/2026-10-24/s4/d065-5-topic-configs-all.txt` 처분 | `test_kafka_topic_state_guard.py:26` 이 CI 에서 읽는 fixture. 2026-10-24 를 prune 하면 lint 실패 | ① 그 파일만 남김 ② `scripts/tests/fixtures/` 로 복사 후 경로 수정 ③ 2026-10-24 유지 |
| Q5 | `loadtest/reports/<date>/` prune 범위 | 열린 부채 D-108·D-110·D-112~D-115(`docs/TASKS.md:52-61`)와 evidence 문서가 2026-10-24/25/26 를 "원자료" 로 가리킴; 2026-04-09/29 는 PHASE3·TASKS-archive 이력만 참조. 로컬 전용 `*.log` 59개는 git 에 없음 | ① 날짜 폴더 통삭제(링크 스테일 감수) ② 큰 tsv/log/json 만 축소, `REPORT.md`·`session-issues.md`·`environment-fingerprint.txt` 유지 ③ 유지 + `.dockerignore` 에 `loadtest` 추가로 비용만 해결 |
| Q6 | 로컬 전용 `*.log` 59개(2.5M)를 git 에 넣을지 버릴지 | `.gitignore:30` 이 막고 있고 evidence 문서가 그 사실을 적어 둠 | ① 그대로(로컬 삭제 시 소실 인지) ② `git add -f` ③ 규칙을 `loadtest/reports/**/*.log` 예외로 |
| Q7 | `.md` 를 `docs/` 밖으로(예: `loadtest/`) 또는 비-.md 를 `docs/` 안으로 옮기는 계획이 있는가 | 보존 체커는 `docs/**/*.md` 만 선언 가능(`safe_path`). `.md` 를 docs 밖으로 내보내면 `declared_deletions` 가 막고 선언 방법이 없다 | ① 그런 이동 없음 ② 체커 확장 선행 |
| Q8 | 한 PR 인가 여러 PR 인가 | 2-4 참고: 여러 PR 가능하되 각 PR 이 자기 이동분 manifest 를 가져야 하고, base.sha 고정 규약상 순차 머지가 안전. Q1 류 이동은 스크립트와 같은 PR | ① 순차 다중 PR(문서 이동 / 스크립트 리터럴 / loadtest prune 분리) ② 단일 PR |
| Q9 | `docs/learning/drafts/tools/blog31,32`(ADR-0032·0033 이 인용하는 증적) | immutable ADR 이 로컬에만 있는 파일을 가리킴 | ① 인용 파일만 `docs/evidence/` 로 추적 ② ADR Update Log 로 외부 위치 명시 ③ 그대로 |
| Q10 | `docs/learning/` 때문에 `replay-entrypoint-lint.sh` 가 로컬에서 깨지는 상태 | lint 가 추적 여부를 안 보고 `docs` 를 grep(66-68) | ① lint 를 `git ls-files` 기반으로 ② `docs/learning` 을 레포 밖으로 ③ 감수 |
| Q11 | `.cache/` 정리 | `codex-off` 외 전부 머지된 작업의 산출물(3장) | ① `codex-off` 만 남기고 삭제 ② `d078-s0.txt` 만 추가로 보존 |
| Q12 | `docs/plans/.archive/` 46개 + `.gitignore:42-45` 규칙 | reader 0, 현 하네스가 state.json 미사용 | ① 삭제 + 규칙 제거 ② 보관 |
| Q13 | worktree 4개·로컬 브랜치 20개·원격 브랜치 20개 | 전부 머지·clean. worktree 2개는 현재 세션 pid 가 잠금 | ① 세션 종료 후 일괄 정리 ② 잠금 해제 후 지금 |
| Q14 | `.gradle-work/`(527M)·`.gradle/`(155M)·`*/build/`(668M) | 재생성 가능 캐시/산출물 | ① 삭제 ② 유지 |
| Q15 | `docs/evidence/` · `docs/progress/evidence/` · `docs/plans/evidence/` 세 증적 축 통합 여부 | `gke-security-smoke.sh:30` 기본 출력이 `docs/progress/evidence`; D-063 로그 9개는 tracked-but-ignored(이동은 `git mv` 로만); 링크 수십 곳 | ① 유지 ② 한 축으로 통합(출력 기본값·링크 재작성 동반) |
| Q16 | `docs/consistency-hints.sh` 위치 | `cd "$(dirname "$0")/.."` 가 docs/ 직하 가정; `ship.sh:17,22` 리터럴; 없으면 조용히 skip | ① 유지 ② `scripts/` 로 이동 + ship.sh·ship.md 수정 |
| Q17 | `docs/review/` 2개, `docs/plans/fixtures/task-*-smoke.md`·`.audit/`(사용처 0), `docs/progress/{d001-metrics-histogram-fix,loadtest-tool-evaluation,phase4-design-roadmap}.md` 류 단독 문서의 거처 | 이력 문서만 참조 또는 참조 0 | ① 삭제(`.md` 는 manifest 선언 필요) ② 아카이브 폴더로 이동 ③ 유지 |
| Q18 | 커밋 분류 훅과의 충돌 | `loadtest/reports` 삭제·fixture 이동 커밋은 docs+chore+src 혼합 → `.githooks/pre-commit` 차단 | ① 분류별로 커밋 쪼개기 ② `HPX_ALLOW_MIXED=1` 로 의도 표시 |
| Q19 | `.gitignore` 정비 | 5장: 죽은 부정 규칙, 루트 `src/` 전제 규칙, 모듈별 `application-local.yml` 미보호 | ① 정비 PR 포함 ② 범위 밖 |
| Q20 | `.dockerignore` 에 `loadtest`·`local-keys`·`.cache` 추가 | 이미지 6개 빌드마다 45M+ 컨텍스트 전송 | ① 추가 ② 범위 밖 |
