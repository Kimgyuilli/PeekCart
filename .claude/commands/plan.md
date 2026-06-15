# /plan — 계획 수립 + Codex 리뷰 루프

사용법:
- `/plan <task-id>` — 지정 task 의 계획서를 Codex 에 리뷰시키고 사용자 게이트로 반영
- `/plan` — 인자 없으면 `docs/TASKS.md` 의 "다음 항목" 을 선택

입력: `$ARGUMENTS`

본 커맨드는 `harness-plan.md` §6-3-1 (12-step) 을 구현한다.
**nested slash 불가** → `/sync`, `/next` 로직은 `.claude/scripts/shared-logic.sh` 의 함수로 대체한다.

---

## 실행 규칙

- 모든 Bash 호출은 `bash -c '...'` 로 실행 (zsh 가 아닌 bash 에서 `shared-logic.sh` 를 source)
- 각 Bash 호출의 시작부에 `set -euo pipefail && source .claude/scripts/shared-logic.sh` 를 둔다
- state.json 은 항상 `hpx_state_write` 로 원자적 치환
- state mutation 은 `hpx_state_init`, `hpx_state_patch`, `hpx_state_set_*`, `hpx_state_append_*` helper 로만 수행
- shell 에서 raw JSON 전체 문자열을 조립해 직접 overwrite 하지 않는다. direct write 우회 경로는 금지한다.
- 외부 부작용 직전 → state 기록 → 부작용 → state 재기록

## 12-step 절차

### Step 1. 인자 파싱 + task 확정
- `$ARGUMENTS` 가 비어있지 않으면 그 값이 `TASK_ID`
- 비어있으면 `hpx_sync_context` 의 TASKS.md 출력을 근거로 "다음 항목" 을 골라 사용자에게 제시하고 승인 받은 후 `TASK_ID` 확정
- **확정 직후 1회 검증 강제** (env-var 전달, 문자열 보간 금지): `TASK_ID="$TASK_ID" bash -c 'set -euo pipefail; source .claude/scripts/shared-logic.sh; hpx_task_id_validate "$TASK_ID"' || exit 1` — `TASK_ID` 가 inner shell 코드 문자열에 직접 보간되면 quote 포함 악성 입력이 validator 실행 *전에* 토큰을 깰 수 있어, 반드시 export 형태로 전달한다. 통과 후에만 이후 본문에서 `TASK_ID` 를 경로 보간에 사용한다 (allowlist `[A-Za-z0-9._-]+`, `..` 금지, 선두 `-`/`.` 금지)
- `docs/plans/${TASK_ID}.md` 파일의 존재를 확인. 없으면 `§10-1` 템플릿 기반 초안을 사용자와 대화하며 작성

### Step 2. lock 획득 + sync 로직
- `hpx_lock_acquire "$TASK_ID" plan` 로 lock 획득. 실패 시 meta.json 내용을 사용자에게 제시하고 중단
- 반환된 `session_id` 를 state 와 이후 Bash 호출에 전달 (재진입용). 현재 Claude 세션 동안 같은 task 를 다시 Bash 호출로 진입할 때는 `hpx_lock_acquire "$TASK_ID" plan "$session_id"` 로 re-enter
- `hpx_sync_context` 구조화 요약을 읽고 현재 Phase / 다음 task 후보 / 활성 state / 활성 ADR 을 파악 (내부적으로만 사용, 사용자에게 별도 보고 불필요)

### Step 3. state 파일 확인
- `hpx_state_exists "$TASK_ID"` 가 true 면 JSON 을 파싱해 `stage`, `loop_count_by_command.plan`, `attempts_by_command.plan`, `review_runs[]` 을 읽고 재개 지점 판단
- `stage == "plan.done"` 이고 추가 수정이 필요 없으면 종료 (완료 보고)
- `pending_run != null` 이면 §6-4-6 규약대로 먼저 finalize:
  1. `raw_path` 존재 + parseable JSON → `hpx_state_append_review_run "$TASK_ID" "$RUN_JSON" plan step3.finalize` 로 승격
  2. raw 없음 → `result="error"`, `error_reason="interrupted_before_output"` 를 포함한 finalize JSON 을 만들어 같은 helper 로 append
  3. 마지막 정리는 `hpx_state_set_pending_run "$TASK_ID" 'null' plan step3.finalize` 로 수행
- state 가 없으면 `NOW=$(date -u +%FT%TZ)` 후 `hpx_state_init "$TASK_ID" "$SID" "$NOW" plan step3.init` 로 초기 state 생성
- `hpx_state_init` 이외의 mutation helper 는 state 파일이 없으면 실패해야 한다. partial state 자동 생성은 허용하지 않는다.
- 초기 생성 직후 필요한 추가 field 가 있으면 `hpx_state_patch` 로만 보강한다. 전체 state JSON heredoc 재조립은 금지한다.

### Step 4. ADR 선행 판단 (GP-1 conditional)
다음 중 **하나라도** 감지되면 GP-1 게이트 노출. 신호 없으면 자동 통과 (§6-2).
- 계획서가 새로운 외부 의존성 (DB/큐/SaaS 등) 을 제안
- 계획서가 아키텍처 경계 (레이어/모듈/바운디드 컨텍스트) 를 변경
- 계획서가 새로운 환경/인프라 (GCP 리소스, k8s 네임스페이스 등) 를 도입

게이트 질문:
> "위 신호로 ADR 선행이 필요해 보입니다. ADR 작성 후 다시 /plan 을 호출할까요? [예/아니오]"

"예" → 종료. "아니오" → 진행.
게이트 이벤트는 `hpx_gate_events_append` 로 TSV 1행 기록. 자동 통과도 기록 (`shown=false`, `auto_passed=true`).

**구조 변경(모듈/경계 변경·코드 이동·peel·rename) 신호가 감지되면 (위 둘째 항목) 추가로 강제**: 초안 작성 전 `docs/plans/PLAN-BLINDSPOTS.md` 의 각 항목(특히 B1 역의존 스윕)을 수행해 그 결과를 계획서 §2(배경) 에 기록한다. 핵심은 **옮기는 대상을 밖에서 참조하는 인바운드 의존**(테스트 포함)을 `grep` 으로 뽑아 처분(이동/디커플/유지)을 한 줄씩 적는 것. 이 표가 없으면 Step 5 에서 누락으로 간주한다. (compound — 새 누락 발견 시 PLAN-BLINDSPOTS.md 에 항목 추가)

### Step 5. 계획서 초안 작성/확인
- **원칙**: 계획의 전제는 설계문서(ADR)가 아니라 **현재 코드로 검증**한다. 특히 (a) 의존 *방향*(무엇이 무엇을 참조하나)과 (b) 인용한 구성요소가 *이미 존재하는가* 는 추측 금지 — 직접 grep/파일 확인.
- `docs/plans/${TASK_ID}.md` 가 비어있거나 템플릿 섹션 중 필수(§1, §2, §4) 항목이 빠져있으면 초안 작성
- **Step 4 GP-1 이 구조 변경으로 발화했으면**: `docs/plans/PLAN-BLINDSPOTS.md` 의 각 항목(B1~Bn)을 계획서에서 다뤘는지 점검. 미흡하면 보강 후 진행.
- 작업 항목은 **stable id** (`P1.`, `P2.`, ...) 강제
- Codex 호출 전에 `hpx_plan_lint "$TASK_ID"` 실행
  - 통과 시 `"OK"`
  - 실패 시 누락 섹션 / stable id 오류를 사용자에게 먼저 제시하고 종료
  - 이 경우 Codex 리뷰는 건너뛴다 (형식 오류에 토큰을 쓰지 않기 위함)
- 작성/확인 완료 후 상태는 `hpx_state_set_stage "$TASK_ID" "plan.draft.created" plan step5.stage` 로 보장하고 `updated_at` 은 `hpx_state_patch` 로 함께 갱신

### Step 6. run 예약 + attempts 증가
- `ATTEMPT=$(현재 attempts_by_command.plan + 1)`
- 상한 확인: `ATTEMPT <= 3` 아니면 사용자에게 "더 호출할까요?" 확인 (§7-6)
- `RUN_ID=$(hpx_run_id_new plan "$SID" "$ATTEMPT")`
- state 갱신은 helper 로만 수행:
  - `hpx_state_set_pending_run "$TASK_ID" "$PENDING_RUN_JSON" plan step6.reserve`
  - `hpx_state_patch "$TASK_ID" "$COUNTER_PATCH_JSON" plan step6.reserve`
- `PENDING_RUN_JSON` 과 `COUNTER_PATCH_JSON` 은 `python3 - <<'PY'` 로 구조화 생성하고, shell 문자열 이어붙이기로 직접 JSON 을 만들지 않는다.

### Step 7. Codex 리뷰 호출 (§7-1)
Bash 블록 단위로 실행:
```bash
set -euo pipefail
source .claude/scripts/shared-logic.sh

TS=$(hpx_epoch_ts)
TIMEOUT_PREFIX=$(hpx_timeout_prefix "$(hpx_codex_timeout_seconds plan)")
RAW=".cache/codex-reviews/plan-${TASK_ID}-${TS}.json"
ERR=".cache/codex-reviews/plan-${TASK_ID}-${TS}.stderr"
mkdir -p .cache/codex-reviews

# 프롬프트는 heredoc (unquoted EOF) — ${VAR} 치환 허용
eval "$TIMEOUT_PREFIX" codex exec \
    --cd "$(pwd)" \
    --output-schema .claude/schemas/plan-review.json \
    >"$RAW" 2>"$ERR" <<EOF
[역할] PeakCart 프로젝트의 시니어 아키텍처 리뷰어
[참조 가능 파일] docs/adr/, docs/01-project-overview.md ~ docs/07-roadmap-portfolio.md, docs/plans/PLAN-BLINDSPOTS.md
[원칙]
  - Layer 1 = What, ADR = Why (결정 근거는 ADR 인용)
  - Phase Exit Criteria 와의 정합성 우선
  - 추측 금지. 파일을 직접 읽고 인용
  - "괜찮아 보입니다" 같은 무내용 응답 금지
  - 한국어로 답변
[ADR 인덱스 핵심]
  - ADR-0001: 4-Layered + DDD
  - ADR-0002: 모놀리식 → MSA 단계적 진화
  - ADR-0004: Phase 3 GCP/GKE 전환 (Accepted)
  - ADR-0005: Kustomize base/overlays (Partially Superseded)
  - ADR-0006: Monitoring 스택 환경 분리
  - ADR-0007: YAML 프로파일 병합 원칙 — 연결 정보 vs 동작 정책
  (전체 인덱스: docs/adr/README.md)
[리뷰 대상] docs/plans/${TASK_ID}.md
[체크 항목]
  - ADR 결정과의 충돌
  - 누락된 작업 항목 (테스트, 마이그레이션, 문서)
  - 트레이드오프 누락
  - 검증 방법의 구체성
  - 작업 항목 id 규약 (P1., P2., ...) 준수
  - (구조 변경·이동·peel 계획이면) docs/plans/PLAN-BLINDSPOTS.md 의 각 항목을 계획이 다뤘는가 — 특히 B1 역의존 스윕(옮기는 대상을 밖에서 참조하는 인바운드 의존, 테스트 포함)이 계획서에 처분과 함께 기록됐는지. 누락이면 해당 Bn 을 인용해 지적.
[필수 필드] 응답 최상위 "run_id" 는 반드시 "${RUN_ID}" 와 문자 그대로 일치. items[].id 는 1부터 시작해 본 응답 내에서만 유일.
EOF
CODEX_EXIT=$?
echo "codex exit=$CODEX_EXIT"
ls -la "$RAW" "$ERR" 2>/dev/null || true
```

### Step 8. 결과 파싱 + 스키마 검증
- `hpx_json_validate "$RAW"` 로 JSON 파싱 가능성 체크
- 최상위 `run_id` 가 `$RUN_ID` 와 일치하는지 확인 (불일치 시 §7-5-A fallback)
- `items[]` 추출. severity 별 그룹핑
- `result` 분류:
  - exit=0 + parseable + run_id match → `result=ok`
  - exit≠0 + parseable → `result=error`, `error_reason=nonzero_exit_with_json`
  - exit=0 + empty stdout → `result=empty`
  - parse fail → `result=json_parse_failed`
  - timeout (exit=124) → `result=timeout`
- stderr 에서 `hpx_extract_tokens_used "$ERR"` 로 tokens 파싱

### Step 9. GP-2 / GP-2b 게이트
- `result == "ok"`:
  - P0 또는 P1 ≥ 1건 → **GP-2 게이트 노출**. §8-1 형식으로 표 제시 + 선택지 [1~5]
  - P0/P1 0건 → 자동 통과. 1줄 요약 + run_id + `[세부 보기] [강제 검토]` 표시 (§6-2 가시화 규칙)
- `result != "ok"` → **GP-2b 게이트** (degraded). `risk_level` 계산 (§6-2):
  - 계획에 `adr_boundary_change=true` 이면 `high`, 아니면 `low`
  - high → 기본 선택지 `중단/재시도`; low → `진행` 허용
- 게이트 이벤트는 `hpx_gate_events_append` 로 TSV 1행 기록

### Step 10. 결정 반영 + state 갱신
- 사용자가 선택한 항목을 계획서에 반영 (직접 Edit 적용). 수정 후 `completed_plan_items` 는 변경하지 않음 (항목 구현은 /work 에서)
- audit log 엔트리 작성:

```markdown
## YYYY-MM-DD HH:MM — GP-2 (loop N)
- 리뷰 항목: X건 (P0:N, P1:N, P2:N)
- 사용자 선택: [번호] 설명
- P0 무시 사유 (있을 때만): ...
- raw: .cache/codex-reviews/plan-${TASK_ID}-${TS}.json
- run_id: ${RUN_ID}
```

- state 원자 갱신도 helper 로만 수행:
  - `hpx_state_append_review_run "$TASK_ID" "$REVIEW_RUN_JSON" plan step10.finalize`
  - `hpx_state_set_pending_run "$TASK_ID" 'null' plan step10.finalize`
  - `hpx_state_patch "$TASK_ID" '{"stage":"plan.review.completed","updated_at":"<now ISO8601>"}' plan step10.finalize`
- `review_runs[]` append 는 동일 `run_id` 재기록 시 중복 추가하지 않는 idempotent helper 계약을 따른다.
- `hpx_metrics_append` 로 `_metrics.tsv` 1행 기록

### Step 11. 루프 판정
아래 3조건 **모두** 만족 시 Step 6 으로 복귀:
- 이번 run 에서 실제 수정 발생 (`accepted_ids` 최소 1건)
- 사용자가 명시적으로 "수정 후 재리뷰" 선택
- `attempts_by_command.plan < 3`

아니면 Step 12 로.

### Step 12. 종료
- state 갱신: `hpx_state_patch "$TASK_ID" '{"stage":"plan.done","updated_at":"<now ISO8601>"}' plan step12.done`
- lock 해제: `hpx_lock_force_release "$TASK_ID"`
- 사용자에게 완료 보고: 계획서 경로, 수용/거부 요약, 다음 단계 (/work) 안내

---

## 사용자 게이트 UX (§8)

리뷰 결과 표시 예시 (P0/P1 있을 때):

```
=== Codex 리뷰 결과 — 계획서 (N건) ===
요약: <summary>

[P0] N건 — 머지 차단
  1. <finding>
     → <suggestion>

[P1] N건 — 강력 권고
  2. <finding>
     → <suggestion>

[P2] N건 — nit
  3. <finding>
     → <suggestion>

어떻게 처리할까요?
  [1] P0/P1만 반영
  [2] 전체 반영
  [3] 항목 선택 (예: "1,3")
  [4] 다 무시하고 진행  ← P0 ≥ 1 건이면 사유 필수
  [5] 종료
>
```

기본 선택은 **[1]** (가장 안전). 기본값 아닌 선택은 명시 입력을 요구한다.

---

## 비용/빈도 제어 (§7-6)
- `attempts_by_command.plan <= 3`, `codex_attempts_cycle_total <= 5` 권장 상한
- 초과 시 자동 진행 금지. 사용자에게 명시 확인
