# Harness Bats 회귀 테스트

`shared-logic.sh` 의 회귀 방지 베이스라인.

2026-09-18 정리로 죽은 helper 51개를 제거하면서 그것들을 대상으로 하던 테스트
4개(`lock_state_paths`, `plan_audit_paths`, `diff_capture`, `timeout_wrapper`)도 함께
지웠다. 대상이 없는 테스트는 통과해도 아무것도 보장하지 않는다.

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
| `codex_gate.bats` | `hpx_codex_allowed` 의 차단 신호 3종, 우선순위, 기본 허용 |
| `plan_grade.bats` | `hpx_plan_grade` 의 등급 파싱과 L 기본값, frontmatter 범위 |
| `review_health.bats` | `hpx_review_health` 의 규율 붕괴 신호 3종과 권고 성격(exit 0) |

## 주의

- 모든 테스트는 `BATS_TEST_TMPDIR` 또는 `mktemp -d` 로 격리된 디렉토리에서 동작한다.
- `codex_gate.bats` 는 `mktemp -d` 안에 `.cache/`, `docs/plans/` 를 만들어 실행한다.
  실제 레포의 `.cache/codex-off` 를 읽거나 쓰지 않는다. 상속된 `HPX_CODEX` 도 setup 에서 해제한다.
- 테스트는 dev-only. CI 자동 실행 미연동 (Phase 4 진입 시 재검토).
