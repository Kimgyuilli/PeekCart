# /work — 구현 + diff 리뷰 루프

사용법:
- `/work <task-id>` — 지정 task 의 계획을 구현하고 Codex diff 리뷰를 돌린다
- `/work` — 인자 없으면 active state (stage ∈ { `plan.done`, `work.*` }) 중 가장 최근 것을 선택

입력: `$ARGUMENTS`

본 커맨드는 `harness-plan.md` §6-3-2 (12-step) 를 구현한다.
`/ship` (커밋/푸시/PR) 는 별도 커맨드 (Phase 3). `/work` 는 `stage=work.done` 까지만 책임진다.

---

## 실행 규칙

- 모든 Bash 호출은 `bash -c '...'` 서브셸 (zsh vs bash `set -euo pipefail` 상이 대응)
- 각 Bash 호출 선두에 `set -euo pipefail && source .claude/scripts/shared-logic.sh`
- state.json 은 항상 `hpx_state_write` 로 원자적 치환
- state mutation 은 `hpx_state_patch`, `hpx_state_set_*`, `hpx_state_append_*` helper 로만 수행
- raw JSON heredoc 직접 조립이나 state direct overwrite 우회는 금지한다.
- 외부 부작용 직전 → state 예약 → 부작용 → state 재기록
- Codex timeout **기본 180s** (Phase 1 측정 결과 wall 78s / tokens 39,865 반영)
- 복잡한 heredoc 은 `/tmp/work-*.sh` 로 분리해 실행
- split 리뷰 결과 사용자 표시 id 는 **chunk prefix 형식** `c<N>:<id>` (예: `c1:1`, `c2:3`). state.json 내부 저장은 chunk-local `id` + `run_id` 조합 유지.

## 12-step 절차

### Step 1. 인자 파싱 + task 확정 + state 파일 확인
- `$ARGUMENTS` 가 있으면 `TASK_ID` 로 확정
- 없으면 `docs/plans/*.state.json` 중 `stage` 가 `plan.done` 또는 `work.*` 인 파일을 updated_at 내림차순 스캔해 후보 제시 → 사용자 승인
- **확정 직후 1회 검증 강제** (env-var 전달, 문자열 보간 금지): `TASK_ID="$TASK_ID" bash -c 'set -euo pipefail; source .claude/scripts/shared-logic.sh; hpx_task_id_validate "$TASK_ID"' || exit 1` — `TASK_ID` 가 inner shell 코드 문자열에 직접 보간되면 quote 포함 악성 입력이 validator 실행 *전에* 토큰을 깰 수 있어, 반드시 export 형태로 전달한다. 통과 후에만 이후 본문에서 `TASK_ID` 를 경로 보간 (RAW/ERR/diff path/계획서 path) 에 사용한다 (allowlist `[A-Za-z0-9._-]+`, `..` 금지, 선두 `-`/`.` 금지)
- `hpx_state_exists "$TASK_ID"` false → "계획서가 없거나 /plan 을 먼저 돌려야 합니다." 종료
- state 로드 후 다음 중 하나만 진행 허용:
  - `stage ∈ { plan.done, work.impl.inprogress, work.impl.completed, work.review.completed }`
  - 그 외 (`plan.draft.created`, `plan.review.completed` 등) → `/plan` 완료 안내 후 종료

### Step 2. lock 획득 + sync 컨텍스트
- `SID=$(hpx_lock_acquire "$TASK_ID" work)` — 실패 시 meta.json 제시 후 종료
- 동일 Claude 세션 내 재진입은 `hpx_lock_acquire "$TASK_ID" work "$SID"` 로 idempotent
- state 의 기존 `session_id` 를 덮어쓰지 않도록 주의 (lock 내 session 과 state.session_id 는 일치해야 함 — 다르면 사용자에게 보고 후 중단)
- `hpx_sync_context` 출력은 내부 참조용

### Step 3. 재개 지점 결정 + pending_run finalize
- `pending_run != null` → §6-4-6 규약으로 finalize:
  1. `raw_path` 존재 + parseable JSON → `hpx_state_append_review_run "$TASK_ID" "$RUN_JSON" work step3.finalize` 로 승격
  2. raw 없음/깨짐 → `result="error"`, `error_reason="interrupted_before_output"` finalize JSON 을 같은 helper 로 append
  3. `pending_run` 정리는 `hpx_state_set_pending_run "$TASK_ID" 'null' work step3.finalize` 로 수행
- finalize payload 는 `python3` 로 구성하고, `pending_run` 일부 필드가 비어 있는 재진입 state 도 허용해야 한다.
- `hpx_state_init` 이외의 mutation helper 는 state 파일이 없으면 실패해야 한다.
- 현재 stage 에 따라 재진입 지점 결정:

| stage | 재진입 Step |
|-------|------------|
| `plan.done` | Step 4 (브랜치 결정) |
| `work.impl.inprogress` | Step 5 (구현 계속 또는 재검토) |
| `work.impl.completed` | Step 6 (diff 캡처) |
| `work.review.completed` | Step 11 (루프 판정) |
| `work.done` | 종료 보고 후 `/ship` 안내 |

### Step 4. 브랜치 결정 (GW-1 conditional)
- state.branch 가 있으면:
  - `git branch --show-current` 와 일치하는지 확인. 불일치 → 사용자에게 보고 후 중단
- state.branch 없으면:
  - 기본 제안: `feat/${TASK_ID}-$(계획서 §1 의 한 단어 요약 kebab-case)`
  - **GW-1 게이트** 노출 (브랜치 이름 확인/수정/취소). 수정 선택 시 사용자 입력 반영
  - `git switch -c <BRANCH>` 또는 기존 존재 시 `git switch <BRANCH>`
  - `hpx_state_patch "$TASK_ID" '{"branch":"<branch>","updated_at":"<now ISO8601>"}' work step4.branch` 로 저장
- gate 이벤트는 `hpx_gate_events_append` 로 TSV 기록 (자동 통과도 기록)

### Step 5. 계획 항목 구현
- 계획서 `docs/plans/${TASK_ID}.md` 의 작업 항목 (`P1.`, `P2.`, ...) 순회
- 항목 하나씩 Edit/Write 로 구현. 각 항목 완료 직후 `hpx_state_append_completed_item "$TASK_ID" "P<n>" work step5.item` 으로 append
- 진행 중 상태는 `hpx_state_patch "$TASK_ID" '{"stage":"work.impl.inprogress","updated_at":"<now ISO8601>"}' work step5.stage` 로 기록
- 전체 항목 완료 (또는 사용자 승인 하에 부분 완료) 후 상태는 `hpx_state_patch "$TASK_ID" '{"stage":"work.impl.completed","updated_at":"<now ISO8601>"}' work step5.stage` 로 전환
- `completed_plan_items[]` append 는 동일 stable id 재기록 시 중복 추가하지 않는 idempotent helper 계약을 따른다.
- 이후에도 추가 수정이 필요하면 다시 `work.impl.inprogress` 로 내려가 구현 이어간다

### Step 6. diff 캡처
```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
TS=$(hpx_epoch_ts)
DIFF_PATH=$(hpx_diff_capture "'"$TASK_ID"'" "$TS")
LINES=$(hpx_diff_lines "$DIFF_PATH")
echo "DIFF_PATH=$DIFF_PATH"
echo "LINES=$LINES"
git diff --stat "$(hpx_base_branch_discover)" | tail -20
'
```
- diff 가 empty (0 lines) → "변경사항이 없습니다. 구현 후 다시 호출하세요." 출력 후 종료 (stage 유지)
- state 갱신: `hpx_state_set_last_diff_path "$TASK_ID" "$DIFF_PATH" work step6.diff` 후 `updated_at` 은 `hpx_state_patch` 로 같이 기록

### Step 7. diff 크기 분기 + review plan 예약 (§7-4)
| 조건 | mode | 예약 동작 |
|------|------|----------|
| `LINES < 500` | `single` | `run_id=work:<utc>:<sid>:<attempt>` 1개 예약 |
| `500 ≤ LINES ≤ 2000` | 사용자 확인 후 `split` | `hpx_diff_split` 로 chunk ≤ 3개 생성, chunk 별 `run_id=work:<utc>:<sid>:<attempt>:c<N>` 예약 |
| `LINES > 2000` | 중단 권장 | 사용자가 강행 시 상위 3 chunk 만, 나머지는 `review_plan.unreviewed_scope[]` 에 기록 |

- `ATTEMPT=$(attempts_by_command.work + 1)`. `ATTEMPT > 3` 이면 사용자 명시 확인
- `codex_attempts_cycle_total > 5` 또한 경고
- split 일 때 `attempts_by_command.work` 및 `codex_attempts_cycle_total` 은 **실제 Codex subprocess 호출 예정 수** 만큼 증가 (single=+1, split=+chunk 수). `loop_count_by_command.work` 는 +1 (논리 loop 단위)
- review_plan 구조:
  ```json
  {
    "command": "work",
    "mode": "single|split",
    "aggregate_result": null,
    "budget_remaining": 3 - ATTEMPT,
    "unreviewed_scope": [],
    "chunks": [
      { "chunk_index": 1, "chunk_total": N, "run_id": "...", "patch_path": "...", "file_count": X, "line_count": Y, "status": "reserved" }
    ]
  }
  ```
- 예약 단계는 helper 로만 수행:
  - `hpx_state_set_pending_run "$TASK_ID" "$PENDING_RUN_JSON" work step7.reserve`
  - `hpx_state_set_review_plan "$TASK_ID" "$REVIEW_PLAN_JSON" work step7.reserve`
  - loop/attempt/budget 갱신은 `hpx_state_patch "$TASK_ID" "$COUNTER_PATCH_JSON" work step7.reserve`

### Step 8. Codex diff 리뷰 호출 (§7-2)
single / chunk 공통 템플릿. prompt 에 `${RUN_ID}` 와 `${DIFF_PATH}` (해당 chunk 경로) 치환.

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh

TS=$(hpx_epoch_ts)
TIMEOUT_PREFIX=$(hpx_timeout_prefix "$(hpx_codex_timeout_seconds work)")
mkdir -p .cache/codex-reviews

RAW=".cache/codex-reviews/diff-'"$TASK_ID"'-${TS}.json"
ERR=".cache/codex-reviews/diff-'"$TASK_ID"'-${TS}.stderr"
DIFF_META=$(hpx_diff_meta_summary "'"$DIFF_PATH"'")

eval "$TIMEOUT_PREFIX" codex exec \
    --cd "$(pwd)" \
    --output-schema .claude/schemas/diff-review.json \
    >"$RAW" 2>"$ERR" <<EOF
[역할] PeakCart 프로젝트의 시니어 코드 리뷰어
[참조 가능 파일] '"$DIFF_PATH"', docs/plans/'"$TASK_ID"'.md, docs/adr/
[원칙]
  - 추측 금지. diff 와 계획서, ADR 을 직접 읽고 인용
  - "괜찮아 보입니다" 같은 무내용 응답 금지
  - 한국어로 답변
[체크 항목]
  - 계획서 의도와 diff 의 일치 (누락/초과 구현)
  - 버그, race condition, null/empty 처리
  - 시큐리티 (입력 검증, 권한, 시크릿 노출)
  - 테스트 커버리지 (추가/수정 필요 여부)
  - 컨벤션 (네이밍, 패키지 위치, 로그 레벨)
[diff 메타데이터]
${DIFF_META}
[ADR 인덱스 핵심]
  - ADR-0001: 4-Layered + DDD
  - ADR-0002: 모놀리식 → MSA 단계적 진화
  - ADR-0004: Phase 3 GCP/GKE 전환 (Accepted)
  - ADR-0005: Kustomize base/overlays (Partially Superseded)
  - ADR-0006: Monitoring 스택 환경 분리
  - ADR-0007: YAML 프로파일 병합 원칙
  (전체 인덱스: docs/adr/README.md)
[필수 필드] 응답 최상위 "run_id" 는 반드시 "'"$RUN_ID"'" 와 문자 그대로 일치. items[].id 는 1부터 시작해 본 응답 내에서만 유일.
EOF
CODEX_EXIT=$?
echo "codex exit=$CODEX_EXIT raw=$RAW err=$ERR"
'
```

- split 이면 chunk 개수만큼 위 블록 반복 실행 (chunk 마다 `DIFF_PATH`, `RUN_ID`, `RAW`, `ERR` 다름)
- 모든 호출이 terminal 이 된 뒤 Step 9 로 이동

### Step 9. 결과 파싱 + 스키마 검증 + aggregate
각 run 에 대해:
- `hpx_json_validate "$RAW"` 로 parse 체크
- 최상위 `run_id` 가 예약값과 일치하는지 확인. 불일치 → §7-5-A fallback 진입
- `result` 분류 (§7-3-1):
  - exit=0 + parseable + run_id match → `ok`
  - exit≠0 + parseable → `error`, `error_reason=nonzero_exit_with_json`
  - exit=0 + empty stdout → `empty`
  - parse fail → `json_parse_failed`
  - exit=124 → `timeout`
- stderr 에서 `hpx_extract_tokens_used "$ERR"` 로 tokens 파싱

split aggregate 규칙 (§7-4):
- `aggregate_result` = chunk 결과 중 가장 심각한 값 (`timeout` > `json_parse_failed` > `empty` > `error` > `ok`)
- 사용자 표시 id 는 **`c<N>:<local_id>`** 형식 (예: `c1:1`, `c2:3`)
- state 저장은 chunk-local `id` + `run_id` 쌍 유지 (변환 없이 원본)

### Step 10. GW-2 / GW-2b 게이트
- `aggregate_result == "ok"`:
  - P0/P1 ≥ 1건 → **GW-2 게이트** 노출. §8-1 표 형식, 선택지 [1~5]. split 이면 번호는 `c<N>:<id>` 로 표기
  - P0/P1 0건 → 자동 통과. 1줄 요약 + aggregate run_id 나열 표시
- `aggregate_result != "ok"` → **GW-2b 게이트** (degraded):
  - `hpx_risk_classify` 출력으로 `risk_level` 결정. `risk_signals` 에 `diff_large_800`, `auth_touch`, `payment_touch`, `config_infra_touch` 중 해당 있으면 high
  - high → 기본 선택지 `중단/재시도`, `계속 진행` 은 사유 입력 필수
  - low/medium → `진행` 허용
- 모든 게이트 표출/자동통과는 `hpx_gate_events_append` 로 TSV 1행 기록

### Step 11. 결정 반영 + state 갱신
- 사용자가 선택한 항목 (`c<N>:<id>` 포함 가능) 을 코드에 반영 (직접 Edit)
- audit log append:
  ```markdown
  ## YYYY-MM-DD HH:MM — GW-2 (loop N)
  - 리뷰 run: <single run_id 또는 chunk run_id 나열>
  - 항목: X건 (P0:N, P1:N, P2:N)
  - 사용자 선택: [번호] 설명
  - P0 무시 사유 (있을 때만): ...
  - diff: <last_diff_path>
  - raw: .cache/codex-reviews/diff-*.json
  ```
- state 갱신 (원자):
  - 각 run 은 `hpx_state_append_review_run "$TASK_ID" "$RUN_JSON" work step11.finalize` 로 append
  - `pending_run = null` 은 `hpx_state_set_pending_run "$TASK_ID" 'null' work step11.finalize`
  - `review_plan.aggregate_result`, `stage`, `updated_at` 은 `hpx_state_patch "$TASK_ID" "$FINAL_PATCH_JSON" work step11.finalize`
- `review_runs[]` append 는 동일 `run_id` 재기록 시 중복 추가하지 않는 idempotent helper 계약을 따른다.
- `hpx_metrics_append` 로 각 run 에 대해 `_metrics.tsv` 1행씩 기록

### Step 12. 루프 판정 / 종료
Step 6 (diff 캡처) 로 복귀 조건 — **모두** 만족:
- 이번 loop 에서 실제 수정 발생 (`accepted_ids` ≥ 1 또는 사용자가 추가 수정 수행)
- 사용자가 "수정 후 재리뷰" 명시 선택
- `attempts_by_command.work < 3` 이고 `codex_attempts_cycle_total < 5`

아니면 종료:
- `hpx_state_patch "$TASK_ID" '{"stage":"work.done","updated_at":"<now ISO8601>"}' work step12.done` 저장
- `hpx_lock_force_release "$TASK_ID"`
- **compound capture**: 구현이 계획과 **크게 달랐던**(계획이 놓쳤던) 항목이 있으면, 그 원인을 `docs/plans/PLAN-BLINDSPOTS.md` 에 `Bn`(Trigger·Check·출처) 한 줄로 append 한다. 자동 검사(테스트/린트)로 막은 것이면 "승격됨" 절에 기록. — 다음 구조 변경 계획의 plan-review 입력으로 재사용됨.
- 사용자 보고: 구현 완료 요약, 최종 accepted/rejected/deferred/degraded, 다음 단계 `/ship` 안내

---

## 사용자 게이트 UX (§8)

### GW-2 (single review)

```
=== Codex 리뷰 결과 — diff (N건) ===
run_id: work:<utc>:<sid>:<attempt>
요약: <summary>

[P0] N건 — 머지 차단
  1. <file:line> <finding>
     → <suggestion>

[P1] N건 — 강력 권고
  2. <file:line> <finding>
     → <suggestion>

[P2] N건 — nit
  3. <file:line> <finding>
     → <suggestion>

어떻게 처리할까요?
  [1] P0/P1만 반영
  [2] 전체 반영
  [3] 항목 선택 (예: "1,3")
  [4] 다 무시하고 진행  ← P0 ≥ 1 건이면 사유 필수
  [5] 종료
>
```

### GW-2 (split review, chunk prefix)

```
=== Codex 리뷰 결과 — diff split (c1..cN) ===
aggregate_result: ok
- c1 (run_id=..., files=X, lines=Y) — P0:a, P1:b, P2:c
- c2 (run_id=..., files=X, lines=Y) — P0:a, P1:b, P2:c

[P0] 머지 차단
  c1:1 <file:line> <finding>
     → <suggestion>
  c2:1 <file:line> <finding>
     → <suggestion>

[P1] 강력 권고
  c1:2 ...

어떻게 처리할까요?
  [1] 전체 P0/P1만 반영
  [2] 전체 반영
  [3] 항목 선택 (예: "c1:1,c2:1")
  [4] 다 무시하고 진행  ← P0 ≥ 1 건이면 사유 필수
  [5] 종료
>
```

### GW-2b (degraded)

```
=== Codex 리뷰 실패 — aggregate_result=<error|timeout|...> ===
risk_level: high
risk_signals: diff_large_800, payment_touch
- c1 timeout (2회 재시도 실패)
- c2 ok

high risk 이므로 기본 선택은 [재시도] 입니다.
  [1] 중단 — state 는 현재 상태로 보존
  [2] 재시도 — 잔여 예산 <X>회
  [3] 계속 진행 (degraded)  ← 사유 필수
>
```

기본 선택은 **[1] 재시도 또는 중단** (가장 안전). 기본값 아닌 선택은 명시 입력을 요구한다.

---

## 비용/빈도 제어 (§7-6)
- `attempts_by_command.work ≤ 3`, `codex_attempts_cycle_total ≤ 5` 권장 상한
- 초과 시 자동 진행 금지. 사용자에게 명시 확인
- split 분할 진입 시 timeout 재시도는 중단하고 잔여 예산을 chunk 에 배분 (§7-5-B)
