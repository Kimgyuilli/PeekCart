# task-d020-approval-reconciliation — audit

## /ship (2026-09-14)

- **PR**: https://github.com/Kimgyuilli/PeakCart/pull/107
- **Precheck**: `ok` (warnings 0) — 게이트 미노출
- **커밋**: 4개로 재분할. 최초 단일 커밋(2e68257)이 adr/plan/docs/test/src 5분류를 섞고 있어
  `한 커밋 = 한 분류` · `ADR 과 계획서는 별도 커밋` 규칙을 위반했다(push 전이라 `reset --soft`).
  `19e5f46` docs(adr) · `a5c1d2d` docs(plan) · `540d0c8` feat(payment) · `e320c9d` test(payment)
  + `/done` 갱신분 1개
- **갱신 항목**: `docs/TASKS.md` D-020 행 `🔲` → `✅` + PR 링크 · `docs/adr/README.md` 인덱스에
  ADR-0023 추가 · `docs/progress/PHASE4.md` 이력 추가(미충족 포함)
- **편입 부채**: 없음 (D-020 자체가 대상). `phase4-prep-debt-roadmap.md` 갱신 대상 아님

### 리뷰 라운드

**없음.** 사용자 지시로 Codex 리뷰를 호출하지 않았다(계획 리뷰 · diff 리뷰 모두). 따라서
**이 작업에 "P1 = 0" 수렴 판정이 존재하지 않으며**, 계획서 완료 조건의 수렴 항목은 미충족이다.

대신 착수 전 코드 검증 C-1~C-10 으로 계획 전제를 코드 사실에 고정했다 — 유지 7 · 확대 2 · 반증 1.
- **C-3 (확대)**: 문제의 본체가 "과금 잔존" 이 아니라 **진실 확정 수단의 소실**임을 확인.
  Alternative A 를 기각시킨 근거가 됐다.
- **C-9 (확대)**: 환불 원장의 `claimForReconcile` predicate 가 `claimed_at IS NULL` 을 못 잡는
  것을 선발견해, 승인 원장을 두 분기 모두 NULL 을 포함하도록 작성했다.
- **C-7 (반증, 계획에 유리)**: `succeed()` 가 이미 비-APPROVED 결제를 견디고 있어 고아 라우팅에
  추가 변경이 필요 없었다.

### 미충족 (PR 본문과 동일)

- 실 PG 장애 주입 미검증 — TASKS.md 의 D-020 진입 조건이 그대로 남는다
- 리뷰 수렴 미판정
- T1 커밋 지연 벽시계 미측정
- `resolveManually` 운영 도달 경로 없음 (서비스 메서드로만 존재)
- 웹훅 서명 검증의 실 PG 관통 미검증 (선재 갭)
