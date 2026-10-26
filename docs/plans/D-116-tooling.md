---
grade: L
---
# D-116-tooling — 도구 경로와 계획 폴더 정비 (D-116 P2)

상위 계획: `docs/plans/D-116.md` §3 P2. 기준 main `060dea1`(P1 #251 머지 뒤). 결정 원문은 `docs/evidence/D-116/decisions.md` 의 2-1·2-2·2-4·2-5·2-8·2-9 규칙·2-10·4-4 와 기본값(ignore·INDEX·ship.md)이다. 사용자는 2026-10-26 에 이 PR 부터 P5 까지 CI 통과와 리뷰 수렴 뒤 직접 머지하도록 승인했고, 새 판단은 보수적 기본값으로 정해 기록하라고 했다. 그렇게 정한 것은 §2.4 에 모으고 PR 본문에 "사용자 확인 필요" 로 옮긴다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 끝난 계획 4개(`D-078-remeasure-plan`, `D-108`, `D-116-loadtest`, `task-debt-orchestration`)가 `docs/plans/` 루트에 남았다. 또는 옮긴 계획의 frontmatter 에 `archive: hold` 가 남았거나, `task-debt-orchestration` 이 종결 절 없이 옮겨졌다.
- CI·규약·하네스가 실행하거나 읽는 파일이 `docs/` 안에 남았다. 대상은 `D-099/check-mutants.py`, D-102 검사 도구와 baseline, saga 계약 매트릭스, `consistency-hints.sh` 다. 또는 옛 경로를 실행하거나 기대값으로 가진 곳이 하나라도 남았다.
- `docs/plans/fixtures/` 가 남았다. 또는 스모크 fixture 5개의 줄 가운데 `docs/progress/archive/` 문서에 정확히 한 번 들어가지 않은 것이 있다.
- `docs/plans/PLAN-BLINDSPOTS.md` 가 남았다. 또는 `/plan`·`/work` 지시문, 스킬, 하네스 스크립트, 메모리 가운데 옛 경로를 가리키는 곳이 있다.
- INDEX 가 `D-NNN` 계획을 "기타" 로 분류한다.
- `replay-entrypoint-lint.sh` 가 추적되지 않은 파일 때문에 로컬에서 실패한다. 또는 추적 파일의 위반을 놓친다.
- `.dockerignore` 가 `loadtest`·`local-keys`·`.cache` 를 빌드 컨텍스트에 넣는다. `.gitignore` 의 `gradle-wrapper.jar` 부정 규칙이 효력이 없다. 프로토타입 하네스 규칙(42-45행)과 루트 `src/` 규칙(26-27행)이 남았다.
- `ship.md` §8 에 Phase 4 로드맵 갱신 단계가 남았다.

등급 L: 코드 이동과 rename 이 들어간 구조 변경이다. CI 워크플로, 셀프테스트 기대값, 하네스 커맨드, 스크립트가 경로를 문자열로 읽고, 되돌리려면 그 파일들을 같이 고쳐야 한다. 앵커 7곳과 `docs/preservation/` 의 기존 파일은 옮기거나 지우지 않는다.

## 2. 배경

### 2.1 코드 검증 (base `060dea1`)

| 전제 | 확인한 곳 | 결과와 반영 |
|---|---|---|
| hold 판정 | `scripts/plans-archive.sh` `hold_prs`·`classify` | `task-debt-orchestration` 은 `archive: hold 177 179 180 181` 이고 그 밖의 머지 PR 이 없어 `유지` 다. hold 줄을 지우면 머지 PR 4개로 후보가 된다. 체크박스는 판정에 쓰지 않는다 |
| hold 줄 삭제 수단 | `scripts/docs-archive-apply.py:35-39,75-90`, `scripts/docs-preservation-check.py:191-230` | 기존 줄을 지우려면 base 경로 기준 `corrections` 가 필요하다. apply 는 corrections 만 담긴 manifest 를 입력으로 받아 그 위에 sources·destinations 를 쓴다. `new` 는 빈 문자열일 수 없어 `grade:` 줄을 포함한 두 줄을 한 줄로 바꾼다 |
| /sync 가 stage 한 아카이브 | 작업트리 | D-078-remeasure-plan·D-108·D-116-loadtest 를 hold 줄 정정 없이 옮겼다. D-108 정정을 넣으려면 이 stage 를 풀고 4개를 한 번에 다시 옮긴다 |
| 오케스트레이션 종결 근거 | `docs/plans/task-debt-orchestration.md:185-219`, TASKS | 본문은 "D075/D084와 제외3건은 열린 TASKS에 남기며 계획을 아카이브하지 않는다" 다. 지금 D-075 는 #242, D-064 는 #249 로 `TASKS-done.md` 에 있고 D-084·D-065 는 보류, D-078 은 ◐ 로 각자 행이 추적한다 |
| CI 가 docs 안 코드를 실행 | `.github/workflows/ci.yml:251`, `scripts/harness-context-selftest.py:180`, `docs/plans/evidence/D-099/check-mutants.py:9` | 워크플로 스텁 테스트가 도구 호출 문자열을 기대값으로 가진다. `ROOT = parents[4]` 다 |
| 변이 anchor | `check-mutants.py:11-48` | `harness-context.sh` 와 `ci.yml` 의 소스 문자열을 anchor 로 쓴다. 이 PR 이 고치는 `harness-context.sh:214` 와 `ci.yml:251` 은 anchor 가 아니다 |
| D-102 도구 | `docs/plans/evidence/D-102/check.py:12,30`, `check-integration.py`, `docs/conventions/test-comments.md:14,18,20` | `parents[4]` 이고 형제 파일 `JavaTokenScanner.java` 를 컴파일한다. `check-integration.py` 는 형제 `check.py` 를 import 한다. 규약은 `--scan` 실행과 `accepted-candidates.tsv` baseline 을 지시한다. 기본 모드와 `check-integration.py` 는 고정 base 와 현재 Java 파일 집합을 비교하므로 지금 main 에서 `JAVA TEST FILE ADDED OR REMOVED` 로 끝나는 것이 설계다(README). `--scan` 은 20건으로 README 와 같다 |
| 매트릭스 | `scripts/saga-contract-matrix-lint.sh:4,19`, `scripts/saga-contract-matrix-selftest.sh:12,39,130,140`, `scripts/ci-docs-only.sh:6-7,20,52` | 기본 경로와 셀프테스트 4곳이 하드코딩이다. `ci-docs-only.sh` 의 `docs/plans/fixtures/*` 예외는 매트릭스 때문에 있고 디렉터리가 비면 죽은 규칙이 된다 |
| consistency-hints | `docs/consistency-hints.sh:2,16,21`, `.claude/scripts/lib/ship.sh:4,17,22`, `.claude/commands/ship.md:85` | `cd "$(dirname "$0")/.."` 라 `scripts/` 로 옮겨도 루트로 간다. ship 은 파일이 없으면 `unavailable` 로 조용히 건너뛰므로 경로를 같이 고치지 않으면 검사가 꺼진다 |
| 스모크 fixture | `docs/plans/fixtures/{task-harness-smoke,task-work-smoke,task-ship-smoke-fresh}.md`, `.audit/{task-harness-smoke,task-work-smoke}.md` | 5개 모두 `.md` 이고 링크가 없다. `.claude`·`scripts`·CI 참조 0건 |
| PLAN-BLINDSPOTS | `scripts/plans-archive.sh:32,124,183,221-226`, `scripts/harness-context.sh:213-214`, `.claude/commands/plan.md:77`, `.claude/commands/work.md:160`, `.agents/skills/plan/SKILL.md:12` | 이 파일만 위해 `KEEP_ALWAYS`, 다이제스트 제외 조건, 셀프테스트 1건이 있다. 옮기면 셋 다 필요 없다. 이 파일을 가리키는 Markdown 링크는 0건이다 |
| INDEX 분류 | `scripts/plans-index.sh:64-69` | `^task-adr\d+`·`^task-impl`·`^task-d\d+` 만 안다. 지금 "기타" 105개 |
| replay lint | `scripts/replay-entrypoint-lint.sh:13,58-68` | 이미 `git rev-parse` 로 루트를 찾는다. (3) 검사는 `grep -rn … docs scripts` 라 gitignore 된 `docs/learning/` 사본 2건으로 로컬 exit 1 이다. 셀프테스트는 (3) 을 다루지 않는다 |
| 빌드 컨텍스트 | `.dockerignore`, `Dockerfile:21-47`, `ci.yml:536,566` | CI 이미지 빌드 컨텍스트는 `.` 이다. Dockerfile 은 gradle 파일과 모듈 디렉터리만 COPY 한다. `loadtest`·`local-keys`·`.cache` 를 COPY 하는 곳은 없다 |
| ignore 규칙 | `.gitignore`, `git ls-files` | `!gradle/wrapper/gradle-wrapper.jar`(5행)가 `*.jar`(35행)보다 앞이라 효력이 없다. 26-27행은 루트 `src/` 가 없어 매치가 없다. 모듈별 `application-local.yml` 5개는 2026-06 부터 추적 중이고 개발용 DB 계정만 담는다. 운영 프로필은 `application-k8s.yml` 이고 `application-prod.yml` 은 없다. 42-45행은 2026-04 상태 파일 하네스용이고 `docs/plans/.archive/` 밖에 매치가 없다. 46행 `.cache/` 는 지금 쓴다. 49행 `loadtest/reports/local/` 은 `loadtest/scripts/order-concurrency.js:12-14` 절차가 쓴다 |
| ship 지시문 | `.claude/commands/ship.md:250` | §8 의 2단계가 `docs/progress/phase4-prep-debt-roadmap.md` 갱신이다. 단계 번호를 가리키는 곳은 없다 |
| 커밋 분류 | `.githooks/pre-commit`, `.claude/scripts/lib/ship.sh:118-126` | `git diff --cached --name-only` 를 쓰므로 rename 은 새 경로로만 잡힌다. `docs/` 에서 `scripts/` 로 옮긴 파일은 chore 다. `.gitignore`·`.dockerignore` 는 src 다 |

### 2.2 B1 역의존 스윕

옮기는 경로마다 추적 파일 전체를 `git grep -F` 로 뽑았다. 이력 문서(`docs/plans/done/`, `docs/progress/PHASE*.md`, `docs/progress/TASKS-archive-*`, `docs/evidence/D-116/`)의 언급은 역사 원문이라 고치지 않는다.

| 옮기는 경로 | 인바운드 | 처분 |
|---|---|---|
| `docs/plans/evidence/D-099/check-mutants.py` → `scripts/harness-context-mutants.py` | `ci.yml:251`, `harness-context-selftest.py:180`, `D-099/README.md:22`, `D-099/{integration-main205,manifest,review-fix-manifest}.json` | 앞의 둘은 새 경로로 고친다. README 는 명령을 새 경로로 고치고 이동 사실 한 줄을 추기한다. json 은 당시 변경 목록이라 그대로 둔다 |
| `docs/plans/evidence/D-102/{check.py,check-integration.py,JavaTokenScanner.java,accepted-candidates.tsv}` → `scripts/test-comments/` | `docs/conventions/test-comments.md:18,20`, `D-102/README.md:15-17,41` 와 README 의 baseline 링크 | 규약과 README 의 명령·링크를 새 경로로 고친다. `test-comments.md:14` 의 README 링크는 README 가 남으므로 그대로 둔다. `comment-inventory.tsv`·`integration-results.txt`·README 는 증적이라 P3 이 옮긴다 |
| `docs/plans/fixtures/saga-contract-matrix.tsv` → `scripts/saga-contract-matrix.tsv` | lint `:4,19`, selftest `:12,39,130,140`, `ci-docs-only.sh:52`, `docs/06-testing-strategy.md:14` | 전부 새 경로로 고친다. ADR-0044:30 은 당시 결정 기록이라 그대로 둔다(§2.3 B2) |
| `docs/consistency-hints.sh` → `scripts/consistency-hints.sh` | `ship.sh:4,17,22`, `ship.md:85`, 스크립트 머리 주석 `:2,16` | 새 경로로 고친다. ADR-0005 는 파일명만 적어 그대로 맞다 |
| `docs/plans/fixtures/*.md`, `docs/plans/fixtures/.audit/*.md` → `docs/progress/archive/harness-smoke-fixtures-2026-04.md` | 0건 | manifest 로 옮긴다 |
| `docs/plans/PLAN-BLINDSPOTS.md` → `docs/conventions/plan-blindspots.md` | 경로: `plan.md:77`, `work.md:160`, `.agents/skills/plan/SKILL.md:12`, `plans-archive.sh:32,124,183,221-226`, `harness-context.sh:214`, 메모리 `feedback_plan_verify_against_code.md`, `D-102/accepted-candidates.tsv:2`, `docs/adr/0018-compensation-refund-contract.md:248`. 이름만: ADR-0018:109·0033, `build.gradle:197`, `gateway/build.gradle:3`, `GatewayReactiveBootstrapTest.java:17`, `PaymentRefundRepository.java:14`, `kafka-topic-state-guard.py:18`, `ci.yml:193` | 지시문·스킬·스크립트·메모리의 경로는 고친다. `accepted-candidates.tsv:2` 는 test 주석의 보존 사유 원문이고 ADR-0018:248 의 백틱 경로는 불변 문서라 그대로 둔다. 이름 언급(`PLAN-BLINDSPOTS B6` 등)은 이동과 무관하다 |
| 아카이브 4개 | Markdown 링크는 `docs-archive-apply.py` 가 재작성한다. 코드 주석·백틱 경로(loadtest 스크립트, overlay 주석, TASKS 행)는 역사 원문이다 | 그대로 둔다(조사 D 1-6 "스테일(편의)") |

### 2.3 PLAN-BLINDSPOTS 점검

- B1·B1b: §2.2. 문자열 결합(워크플로 스텁 기대값, `ci-docs-only.sh` 셀프테스트 경로, ship 의 존재 검사)이 핵심이다.
- B2: ADR-0044 Decision 의 "`docs/plans/fixtures/**` 제외" 는 당시 규칙이다. 이 PR 은 매트릭스를 `scripts/` 로 옮겨 경로 규칙만으로 코드로 분류되게 한다. "CI 입력은 문서가 아니다" 는 원칙은 그대로라 새 ADR 이나 Update Log 사유가 아니다(ADR README §원칙: Update Log 는 사실 오류 정정용이다).
- B4: 경로·명령·manifest 이름을 §3 에 적었다.
- B11: 경로 0건 검사는 옛 경로 문자열 전체로 `git grep -F` 하고, 가짜 참조를 넣어 검사가 잡는지 먼저 본다.
- B3·B5~B10·B12·B13: 해당 없음. 런타임 코드, 빈, 이벤트, 측정 경로를 바꾸지 않는다.

### 2.4 해석과 기본값 (PR 본문에 "사용자 확인 필요" 로 남긴다)

1. hold 줄은 `task-debt-orchestration` 과 `D-108` 둘 다 지운다. 상위 계획 문장은 세 계획을 옮긴다고 한 뒤 hold 줄을 지운다고 했다. 아카이브된 계획에 보류 표시가 남은 경우는 base 에서 0건이었다. "그 사유와 함께" 는 corrections 의 `reason` 으로 읽었다. 본문의 "아카이브하지 않는다" 문장은 판단 이력이라 지우지 않고 종결 절이 그것을 닫는다.
2. 모듈별 `application-local.yml` 은 ignore 하지 않는다. 5개가 이미 추적 중이고 로컬 실행에 쓰인다. ignore 하면 조사 D §5-1 이 지적한 "추적되면서 무시되는 파일" 이 5개 늘어난다. 루트 `src/` 규칙 2줄만 지운다.
3. "죽은 규칙" 은 26-27행과 42-45행으로 한정한다. IDE·OS·`.env`·`logs/`·`*.class`·`*.py[cod]` 는 지금 매치가 없어도 방어 규칙이라 남긴다. `.cache/` 는 남기고 머리 주석만 현행으로 고친다.
4. `docs/plans/.archive/` 로컬 삭제를 P6 에서 이 PR 로 당긴다. ignore 규칙을 먼저 지우면 그 안의 파일이 추적 후보로 드러난다. 지우기 전에 세션 scratchpad 에 tar 사본을 둔다.
5. D-102 는 `check.py`·`JavaTokenScanner.java`·`check-integration.py`·`accepted-candidates.tsv` 를 `scripts/test-comments/` 로 옮긴다. `check-integration.py` 는 형제 `check.py` 를 import 하므로 같이 간다. 남는 inventory·결과·README 는 P3 이 옮긴다.
6. 매트릭스는 `scripts/saga-contract-matrix.tsv` 에 둔다. lint·셀프테스트 옆이고, `scripts/fixtures/` 는 셀프테스트 fixture 자리라 정본 매트릭스와 섞지 않는다.

## 3. 작업 항목

- [x] P1. 아카이브. /sync 가 stage 한 이동을 풀고(D-116 §3 P1 상자 갱신은 유지) 다시 한다. `docs/preservation/plans-d116-tooling-archive-20261026.json` 에 corrections 2건을 먼저 쓴다. `docs/plans/D-108.md` 는 `grade: M` 과 `archive: hold 248` 두 줄을 `grade: M` 한 줄로, `docs/plans/task-debt-orchestration.md` 는 `grade: L` 과 `archive: hold 177 179 180 181` 두 줄을 `grade: L` 한 줄로 바꾼다. 두 파일에서 그 줄을 지우고 `task-debt-orchestration.md` 끝에 `## 종결 (2026-10-26, D-116)` 절을 추기한다. 종결 절에는 D-075(#242, ADR-0045)와 D-064(#249)가 닫힌 것, D-084·D-065 보류와 D-078 ◐ 은 각자 TASKS 행과 `docs/plans/D-084.md` 가 추적한다는 것, 본문의 "아카이브하지 않는다" 가 이 절로 풀린다는 것을 적는다. PR 은 번호만 쓴다. 그다음 `scripts/plans-archive.sh --apply --select D-078-remeasure-plan,D-108,D-116-loadtest,task-debt-orchestration --base 060dea1 --manifest docs/preservation/plans-d116-tooling-archive-20261026.json` 과 `scripts/plans-index.sh` 를 돈다.
- [x] P2. 도구 이동. `git mv` 로 옮기고 같은 커밋에서 경로를 고친다.
  - `docs/plans/evidence/D-099/check-mutants.py` → `scripts/harness-context-mutants.py`(`parents[1]`). `ci.yml:251`, `harness-context-selftest.py:180`, `D-099/README.md:22`.
  - `docs/plans/evidence/D-102/{check.py,check-integration.py,JavaTokenScanner.java,accepted-candidates.tsv}` → `scripts/test-comments/`(`parents[2]`). `docs/conventions/test-comments.md:18,20`, `D-102/README.md` 의 명령 4줄과 baseline 링크.
  - `docs/plans/fixtures/saga-contract-matrix.tsv` → `scripts/saga-contract-matrix.tsv`. lint 2곳, selftest 4곳, `docs/06-testing-strategy.md:14`.
  - `docs/consistency-hints.sh` → `scripts/consistency-hints.sh`. 머리 주석 2곳, `ship.sh:4,17,22`, `ship.md:85`.
  - `scripts/ci-docs-only.sh`: `docs/plans/fixtures/*` 규칙과 6-7행 설명을 지우고, 52행 기대값을 새 매트릭스 경로로 바꾼다(여전히 code=true).
  - 두 README 에는 "2026-10-26 D-116 P2 에서 도구를 `scripts/…` 로 옮겼다" 는 한 줄을 추기한다.
- [x] P3. 스모크 fixture 병합. `docs/progress/archive/harness-smoke-fixtures-2026-04.md` 를 만든다. 머리(H1, 합친 이유와 결정 2-8, 참조 0건)와 원본마다 "원래 경로" 문단을 literal 로 두고, 원본 5개의 전 줄을 순서대로 넣는다(harness 계획·audit, work 계획·audit, ship 계획). 원본은 지운다. manifest 는 `docs/preservation/d116-tooling-moves-20261026.json` 이다.
- [x] P4. PLAN-BLINDSPOTS 이동. `docs/conventions/plan-blindspots.md` 로 전 줄을 옮긴다(P3 과 같은 manifest). `plans-archive.sh` 의 `KEEP_ALWAYS`(32·124행)와 셀프테스트 fixture·검사(183·221-226행), `harness-context.sh:214` 의 제외 조건을 지운다. `plan.md:77`, `work.md:160`, `.agents/skills/plan/SKILL.md:12` 의 경로를 고친다. 메모리 `feedback_plan_verify_against_code.md` 와 `MEMORY.md` 의 경로를 고친다.
- [x] P5. INDEX 분류기. `scripts/plans-index.sh` 의 `bucket()` 에 `^D-\d+` 를 "부채 · 측정" 으로 더하고 INDEX 를 다시 만든다.
- [x] P6. replay lint. (3) 검사를 `git grep -n -E … -- 'docs/*.md' 'scripts/*.sh' 'scripts/*.py'` 로 바꾸고 `docs/plans/` 제외 파이프는 둔다. 주석에 추적 파일만 보는 이유(gitignore 된 로컬 사본은 저장소 문서가 아니다)를 적는다.
- [x] P7. ignore. `.dockerignore` 에 `loadtest`·`local-keys`·`.cache` 를 더한다. `.gitignore` 는 `!gradle/wrapper/gradle-wrapper.jar` 를 `*.jar` 뒤로 옮기고 25-27행과 41-45행을 지운다(`.cache/` 는 현행 주석과 함께 남긴다). 그 전에 `docs/plans/.archive/` 를 scratchpad 에 tar 로 남기고 지운다.
- [x] P8. `.claude/commands/ship.md` §8 의 2단계(Phase 4 로드맵 갱신)를 지우고 번호를 당긴다.

## 4. 검증 방법

실패를 먼저 주입하고, 고친 뒤 통과를 본다. base 는 `060dea1` 이다.

1. 보존: `python3 -B scripts/docs-preservation-ci.py --base 060dea1` PASS. 먼저 (a) P3 destination 에서 원본 줄 하나를 뺀 사본, (b) corrections `old` 한 글자를 바꾼 사본으로 FAIL 을 본다.
2. 아카이브: `plans-archive.sh` dry-run 에 `D-084` 와 `D-116` 만 `유지` 로 남는다. `grep -c '^archive:' docs/plans/done/*.md` 가 전부 0 이다. 종결 절에 `pull/` 이 0건이다.
3. 변이 도구: `python3 -B scripts/harness-context-mutants.py` 가 "11 CLI and 5 exact-workflow" PASS 이고 `harness-context-selftest.py` 전체가 통과한다. 먼저 `ci.yml` 의 새 경로만 옛 경로로 되돌린 사본으로 `test_workflow_normal_shallow_and_full_states` 가 FAIL 하는지 본다.
4. D-102: 이동 뒤 `scripts/test-comments/check.py --scan` 출력이 이동 전 20줄과 바이트 단위로 같다. `check-integration.py --base 4a4a36a12a3d914d6bc0379ded94bc0123c4d370` 은 이동 전과 같은 `JAVA TEST FILE ADDED OR REMOVED` 로 끝난다(시점 고정 재현이라 설계상 실패이고, 경로 오류가 아니어야 한다). `parents` 를 하나 틀린 사본은 다른 오류로 실패한다.
5. 매트릭스: `saga-contract-matrix-lint.sh --structure`·`--self-test` 와 `saga-contract-matrix-selftest.sh` 가 통과한다. `SAGA_MATRIX` 를 옛 경로로 준 실행은 실패한다. `ci-docs-only.sh --self-test` 11/11. `docs/plans/fixtures/x.tsv` 는 이제 문서로 판정된다(예외 규칙이 사라진 대조).
6. consistency: `hpx_consistency_precheck D-116-tooling` 1행이 `ok` 다. 새 파일을 잠시 치우면 `unavailable` 이 된다.
7. PLAN-BLINDSPOTS: `plans-archive.sh --self-test` 가 통과한다. 추적 파일에서 `docs/plans/PLAN-BLINDSPOTS` 가 §2.2 의 그대로 두는 곳 밖에 0건이다.
8. INDEX: `plans-index.sh --check` 최신. "기타" 가 `D-NNN` 수만큼 줄고 "부채 · 측정" 이 같은 수만큼 는다. 정규식을 되돌린 사본으로는 `--check` 가 1 이다.
9. replay lint: 로컬 exit 0. 추적 문서(`docs/runbooks/dlq-recovery.md`)에 위반 SQL 한 줄을 잠시 넣으면 REPLAY-ENTRY-003 으로 실패하고, 같은 줄을 추적되지 않은 `docs/x-untracked.md` 에 넣으면 통과한다. `--self-test` OK.
10. ignore: `git ls-files -i -c --exclude-standard` 에서 `gradle-wrapper.jar` 가 빠지고 D-063 로그 9개만 남는다. `git check-ignore -v --no-index gradle/wrapper/gradle-wrapper.jar` 가 부정 규칙을 가리킨다. `git status --porcelain` 의 추적되지 않은 항목이 늘지 않는다.
11. 경로 0: 옛 경로 문자열 5종(`docs/plans/evidence/D-099/check-mutants.py`, `docs/plans/evidence/D-102/check`, `docs/plans/fixtures/`, `docs/consistency-hints.sh`, `docs/plans/PLAN-BLINDSPOTS`)을 `git grep -F` 로 찾아 §2.2 처분 밖 0건이다. 가짜 참조 한 줄을 넣어 먼저 잡히는지 본다.
12. CI lint 잡 명령 전체를 로컬에서 돌린다(보존, 정책 lint, 하네스, DLQ 계약, 매트릭스 커버리지). 하네스는 `harness-context.sh --check --base 060dea1` 이다.
13. 앵커: `git diff --name-status 060dea1` 에 앵커 7곳과 `docs/preservation/` 기존 파일의 이동·삭제가 0건이다.

## 5. 완료 조건

P1~P8 상자가 채워지고 §4 결과를 audit 에 남기고 PR CI(전체 매트릭스)가 성공하면 머지한다. 머지 뒤 다음 단계의 /sync 가 이 계획을 아카이브하고 상위 계획 §3 의 P2 상자를 채운다.

## 6. 범위 밖과 미해결

- `docs/plans/evidence/D-078/check-mutants.py` 는 CI 가 실행하지 않는 증적 재현 도구라 P3 증적 이동과 함께 다룬다.
- D-099·D-102 증적의 README·결과 파일과 `docs/plans/evidence/` 전체 이동은 P3 이다.
- 옮긴 계획을 옛 경로로 적은 코드 주석·백틱(loadtest 스크립트, overlay 주석, TASKS 행, `scripts/toss-sandbox/sandbox.py`)은 역사 원문이라 고치지 않는다.
- `docs/progress/archive/` 는 이 PR 에서 처음 생긴다. 문서 맵 등재는 P3 의 `docs/README.md` 가 한다.
- 측정 run 의 kafka-lag export 7개는 남긴다(2026-10-26 사용자 확인).
