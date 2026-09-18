# /ship — 커밋 / 푸시 / PR 생성 / done 갱신

사용법:
- `/ship` — 현재 브랜치의 task 를 찾아 **dry-run** (기본)
- `/ship <task-id>` — 지정 task 로 dry-run
- `/ship --execute` — 실제 commit / push / `gh pr create` / `/done` 수행
- `/ship <task-id> --execute` — 지정 task 에 대해 execute

> **축소 이력 (2026-08-30)**: 11-step 상태머신(lock · `state.json` · drift detector · resume cursor · gate events · archive)을 제거했다.
> 사유 — `/plan`·`/work` 는 2026-08-26 축소에서 이미 `state.json` 을 버렸는데 `/ship` 만 그것을 **필수 전제**로 남겨두어, Step 1 이 "state 가 없습니다. /work 를 먼저 완료하세요" 로 **정상 흐름을 차단**했다. 구현 ⑤(#94) 에서 실제로 이 불일치에 부딪혀 Step 1/10 을 건너뛰고 수행했다.
> 진행 상태는 `state.json` 이 아니라 **git 과 gh 의 사실**(브랜치·커밋·원격·PR)로 판정한다. 이력은 계획서·audit 파일·git 이력에 남는다.
>
> 이 축소로 `hpx_ship_pr_body_data` 는 호출처가 사라진다. `hpx_state_*`·`hpx_lock_*`·`hpx_gate_events_append`·`hpx_diff_absorption_status` 는 `/plan`·`/work` 축소 때 이미 죽어 있었다. 셸 라이브러리 정리는 별도 task 로 한 번에 한다 — 이 커맨드는 호출을 멈출 뿐 삭제하지 않는다.

`/ship` 은 Codex 를 호출하지 않는다 (shell precheck / commit / push / gh 만).

---

## 모드 구분

- **dry-run (기본)**: 1~5 실행(부작용 없음), 6~8 은 "무엇을 할지" 만 보고. `.cache/` 산출물은 생성.
- **execute**: 전 단계 실행. push · PR 생성 · 문서 갱신 발생.

dry-run 통과 후 `--execute` 로 재호출하면 같은 판정을 다시 계산해 실행한다.

---

## 실행 규칙

- **`git add -A` 금지.** 커밋할 파일을 명시한다.
- push · PR 생성은 되돌리기 어려운 외부 작업이다. **execute 모드에서만** 수행하고, 그 전에 사용자 승인을 받는다.
- 머지는 하지 않는다.
- 실패하면 그 지점에서 멈추고 보고한다. 자동 rebase·force push 금지.

---

## 절차

### 1. task 확정 + 사전 확인

- 인자에서 `--execute` 를 떼고 남은 것이 `TASK_ID`. 없으면 현재 브랜치명과 `docs/plans/*.md` 에서 추론해 제시하고 승인받는다.
- `TASK_ID` 는 `[A-Za-z0-9._-]+` 만 허용하고 `..` · 선두 `-`/`.` 를 금지한다.
- 다음을 확인하고 어긋나면 **중단하고 보고**한다:
  - `docs/plans/${TASK_ID}.md` 존재 (없으면 `/plan` 부터)
  - 현재 브랜치가 `main` 이 **아님** (main 에서 직접 ship 금지)
  - 계획서의 작업 항목 체크박스가 전부 `- [x]` (미완이면 어느 항목이 남았는지 보고)

등급을 읽어 PR 본문 구성에 쓴다. 등급이 낮다고 PR 본문을 줄이지는 않는다 — PR 은 사람이
읽는 자리이고, 리뷰 절차와 무관하다. 다만 없는 리뷰를 있었던 것처럼 적지 않기 위해 필요하다.

```bash
bash -c 'source .claude/scripts/shared-logic.sh; hpx_plan_grade "<TASK_ID>"'
```

```bash
git branch --show-current
git status -sb
git log --oneline "$(git merge-base HEAD origin/main)"..HEAD
```

### 2. Consistency precheck

```bash
bash -c 'set -uo pipefail
source .claude/scripts/shared-logic.sh
RES=$(hpx_consistency_precheck "<TASK_ID>")
echo "$RES" | head -3
LOG=$(echo "$RES" | sed -n 2p); [ -f "$LOG" ] && tail -30 "$LOG"
'
```

- `ok` (warnings 0) → 자동 통과. 게이트 노출하지 않는다
- `warnings` → 항목을 제시하고 선택받는다:
  ```
  === Consistency precheck — warnings ===
  [MISS] ADR-NNNN — ...
    [1] 수정 (편집 후 재실행)
    [2] 무시하고 진행 (사유 필수)
    [3] 종료
  ```
  `[2]` 선택 시 사유를 **PR 본문의 "Skipped consistency checks" 섹션**에 적는다
- `script_error` → stderr 와 exit code 를 그대로 제시하고 동일 3선택지
- `unavailable` → "`docs/consistency-hints.sh` 없음. skip" 한 줄 안내 후 진행

### 3. 커밋 정리

`/work` 가 이미 분류별로 커밋했으면 이 단계는 **확인만** 한다. 커밋을 다시 만들지 않는다.

```bash
git status --porcelain          # 미커밋 잔여
git log --reverse --format='%h  %s' "$(git merge-base HEAD origin/main)"..HEAD
```

- **미커밋 잔여가 없으면**: 기존 커밋 목록을 보여주고 "한 커밋 = 한 분류" 가 지켜졌는지 확인만 한다
- **미커밋 잔여가 있으면**: 분류별로 나눠 커밋한다
  - category ∈ `{adr, docs, test, chore, src}`. 한 커밋 = 한 분류 (mixed 금지)
  - **ADR 과 계획서는 별도 커밋.** ADR 본문 정정은 `fix(adr):` 접두사 (see `docs/adr/README.md` §원칙)
  - 100파일 초과 시 재분할
  - 커밋 메시지: `feat(<scope>)` / `fix(<scope>)` / `refactor(<scope>)` / `test(<scope>)` / `docs(<scope>)` / `chore(<scope>)`
  - 제목과 본문 문체는 `docs/conventions/writing.md` 를 따른다. em dash, 화살표, 이모지,
    귀속 트레일러를 쓰지 않고 제목은 명사형 50자 내외로 끝낸다. Step 4-1 lint 가 검사한다
  - `git add -- <파일 명시>` 후 `git diff --cached --quiet` 이면 중단 (스테이징이 비었다는 뜻)

분할이 필요하면 승인 게이트를 노출한다:
```
=== 커밋 분할 제안 (N 개) ===
p1. feat(cache): ...  (+24)
p2. test(cache): ...  (+23)
  [1] 승인  [2] 수정  [3] 종료
```

### 4. PR 본문 생성

`state.json` 이 아니라 **계획서·audit·git 이력**에서 조립한다:

| 섹션 | 출처 |
|---|---|
| Why | 계획서 §1 명제 · §2 배경(범위가 바뀌었으면 그 사실) |
| What | 계획서 §3 작업 항목 중 실제 구현된 것 |
| How | 핵심 결정과 근거. ADR 이 있으면 `(see ADR-NNNN)` |
| Test plan | 계획서 §검증 방법의 각 행 + **실제 실행 결과** |
| 리뷰 이력 | `docs/plans/${TASK_ID}.audit.md` 의 라운드별 요약 |
| 관련 | Task · Plan · ADR · 부채 ID · runbook |

**리뷰 이력 섹션은 audit 의 `상태:` 줄을 그대로 옮긴다.** 4값 중 앞의 셋은 정상 종결이므로
한 줄로 끝낸다. 없는 라운드를 "라운드 0, 미실시" 로 적지 않는다.

```markdown
## 리뷰 이력

- 등급: M
- 계획 리뷰: 해당 없음(등급 M)
- diff 리뷰: 수행(1라운드). P0 0건, P1 3건 반영, P2 1건 기각
```

등급을 첫 줄에 적는다. 이것이 있어야 "계획 리뷰가 왜 없나"가 본문 안에서 설명된다.

**조건부 섹션** — 해당하면 반드시 넣는다:
- **Skipped findings** — diff 리뷰에서 기각한 항목. **사유와 재검토 조건**을 함께 적는다
- **Skipped consistency checks** — Step 2 에서 `[2]` 를 골랐으면 그 사유
- **미충족** — 계획서 §미해결 + 작업 중 드러난 한계. "완료했다" 로 뭉개지 않는다

**미충족에 넣지 않는 것** — 리뷰 상태가 `해당 없음(등급 G)` 이거나 `의도적 생략` 인 경우. 이것은
판단의 결과이지 남은 일이 아니다. 리뷰 이력 한 줄로 끝내고 미충족에는 적지 않는다.
`미결` 만 미충족으로 올린다.

본문은 `.cache/pr-body-${TASK_ID}.md` 에 저장한다 (재시도 시 재사용).

#### 4-1. 문체 lint (생략 금지)

본문을 사람에게 보이기 **전에** 돌린다. 정본은 `docs/conventions/writing.md`.

```bash
mkdir -p .cache
scripts/writing-lint.sh --commits "$(git merge-base HEAD origin/main)..HEAD"
scripts/writing-lint.sh --file ".cache/pr-body-${TASK_ID}.md"
```

- `오류` 가 나오면 **고치고 다시 돌린다.** 사람에게 보이지 않는다. 커밋 메시지가 걸렸으면
  아직 push 전이므로 `git commit --amend` 또는 `git rebase -i` 로 고친다
- `경고` 는 진행을 막지 않는다. 다만 제목 길이와 추적 태그 위치는 대개 고치는 편이 낫다
- 오류를 못 고칠 사정이 있으면 그 사유를 승인 게이트에 함께 제시하고 사용자가 판단한다.
  조용히 넘기지 않는다

### 5. 본문 승인 게이트 (always)

```
=== PR 본문 미리보기 (.cache/pr-body-<task>.md, N 줄) ===
...
  [1] 승인  [2] 수정  [3] 종료
```

**dry-run 은 여기까지.** 아래를 보고하고 종료한다:
```
=== /ship dry-run 완료 ===
- 커밋: N개 (분할 필요 M개)
- Push 예정: origin/<branch>
- PR: 신규 생성 (또는 기존 #NN 갱신)
- 본문: .cache/pr-body-<task-id>.md
- 실제 실행: /ship <task-id> --execute
```

### 6. Push (execute 전용)

```bash
git push -u origin "$(git branch --show-current)"
```

- `Everything up-to-date` 도 성공으로 본다 (멱등)
- 실패 시:
  - non-fast-forward → `git fetch origin` 후 **사용자에게 rebase 여부를 묻는다**. 자동 rebase 금지
  - 인증 실패 → `gh auth login` 안내. 자동 재시도 금지
  - 네트워크 → 30초 후 1회만 재시도
- 어느 경우든 실패하면 멈추고 보고한다. 다음 호출은 이 단계부터 재개된다

### 7. PR 생성 (execute 전용)

```bash
BRANCH=$(git branch --show-current)
gh pr list --head "$BRANCH" --state open --json url -q ".[0].url"   # 선조회
gh pr create --base "$(hpx_base_branch_name)" --head "$BRANCH" \
  --title "<한 줄 요약>" --body-file ".cache/pr-body-<TASK_ID>.md"
```

- 이미 열린 PR 이 있으면 **생성하지 않고** 그 URL 을 쓴다
- 실패 시:
  - `gh: command not found` → 본문 파일 경로와 수동 생성 URL 을 제시하고 종료
  - 인증 만료 → 갱신 안내 후 재시도 선택
  - 5xx/네트워크 → 60초 후 1회 재시도
  - rate limit → `Retry-After` 고지 후 수동 재시도
- 3회 실패하면 수동 생성 안내 후 종료한다. **문서는 갱신하지 않는다**

### 8. `/done` 갱신 (PR URL 확정 후에만)

1. `docs/TASKS.md` — 해당 Task 행에 **PR 링크**를 달고 `🔄`/`🔲` → `✅`.
   범위가 착수 전과 달라졌으면 **그 사실과 근거를 행에 기록한다** (구현 ④·⑤ 선례)
2. 편입 부채가 있으면 `docs/progress/phase4-prep-debt-roadmap.md` 의 해당 행에 ✅ + PR 번호
3. `docs/progress/PHASE{N}.md` — 작업 이력에 PR URL. **미충족 항목을 함께 남긴다**
4. 결정 사항 분류:
   - 대안 비교·후속 전제가 있으면 → ADR (Layer 2)
   - ADR 의 **사실 진술**이 틀렸으면 → 새 ADR 이 아니라 **Update Log + `fix(adr):`** (`docs/adr/README.md` §원칙)
   - 구현 디테일 → progress (Layer 3)
   - 확신이 없으면 사용자에게 묻는다
5. Layer 1(01~07) 이 코드 사실과 어긋나면 **What 만** 정정. Why 는 ADR
6. `docs/plans/${TASK_ID}.audit.md` 에 `/ship` 결과 1블록 append (PR URL · precheck 결과 · 갱신 항목).
   **등급 S 는 audit 파일이 없으므로 이 단계를 건너뛴다** — PR URL 은 `docs/TASKS.md` 와
   progress 에 이미 남는다. 없는 파일을 만들자고 audit 을 되살리지 않는다

갱신분은 별도 커밋 후 push 한다.

### 9. 완료 보고

```
=== /ship 완료 ===
Task: <task-id>
PR:   <pr_url>
Commits: N개
Branch:  <branch> (origin 동기화됨)
미충족: <있으면 나열>
```

머지는 하지 않았음을 명시한다.

---

## 재진입

`state.json` 이 아니라 **git/gh 사실**로 판정한다. 같은 명령을 다시 부르면 아래를 확인해 남은 지점부터 진행한다.

| 확인 | 판정 | 재개 지점 |
|---|---|---|
| `git status --porcelain` 비어있지 않음 | 커밋 안 된 변경 있음 | Step 3 |
| `git ls-remote --heads origin <branch>` 없음 | 미push | Step 6 |
| `gh pr list --head <branch> --state open` 비어있음 | PR 없음 | Step 7 |
| PR 있고 TASKS 에 PR 링크 없음 | `/done` 미적용 | Step 8 |
| 위 전부 충족 | 완료 | 보고만 |
