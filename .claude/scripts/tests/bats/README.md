# Harness Bats 회귀 테스트

`shared-logic.sh` 와 `scripts/timeout_wrapper.py` 의 회귀 방지 베이스라인.
task-d011 (Harness Hardening) 변경분 + 핵심 helper 5종의 계약을 잠근다.

## 설치

```bash
brew install bats-core   # macOS
# 또는
apt-get install bats     # Debian/Ubuntu
```

## 실행

리포지토리 루트에서:

```bash
bats .claude/scripts/tests/bats/
```

특정 파일만:

```bash
bats .claude/scripts/tests/bats/task_id_validate.bats
```

## 테스트 파일

| 파일 | 대상 |
|------|------|
| `task_id_validate.bats` | `hpx_task_id_validate` allowlist 정상/거부 |
| `lock_state_paths.bats` | lock/state helper 의 sanitize 진입점 + idempotent 동작 |
| `plan_audit_paths.bats` | `hpx_plan_lint`, `hpx_audit_append` 외부 경로 거부 |
| `diff_capture.bats` | `hpx_diff_capture` 의 `.git/index` 격리 + 변경분 누락 0건 |
| `timeout_wrapper.bats` | `scripts/timeout_wrapper.py` 의 seconds 검증 (0/음수/NaN/Inf) |
| `codex_gate.bats` | `hpx_codex_allowed` 의 차단 신호 3종, 우선순위, 기본 허용 |

## 주의

- 모든 테스트는 `BATS_TEST_TMPDIR` 또는 `mktemp -d` 로 격리된 디렉토리에서 동작한다.
- `diff_capture.bats` 는 임시 git repo 를 만들어 격리 검증한다 — 현재 작업 트리에 영향 없음.
- `codex_gate.bats` 는 `mktemp -d` 안에 `.cache/`, `docs/plans/` 를 만들어 실행한다.
  실제 레포의 `.cache/codex-off` 를 읽거나 쓰지 않는다. 상속된 `HPX_CODEX` 도 setup 에서 해제한다.
- 테스트는 dev-only. CI 자동 실행 미연동 (Phase 4 진입 시 재검토).
