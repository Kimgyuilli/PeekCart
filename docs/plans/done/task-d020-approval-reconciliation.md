# D-020 — 결제 승인 경계: 승인 원장 + 멱등키 + 조회 기반 reconciliation

> 선행 ADR: [ADR-0023](../adr/0023-payment-approval-reconciliation.md) · 확장 대상: ADR-0018 D2/D3/D5
> 브랜치: `feat/d020-approval-reconciliation`
> **Codex 리뷰 미호출** (사용자 지시). 따라서 "P1 = 0" 수렴 판정이 이 PR 에 존재하지 않는다 — 대신 착수 전 코드 검증(C-1~C-10)으로 계획 전제를 코드 사실에 고정했다.

## 1. 착수 전 코드 검증 (C-1~C-10)

| # | 검증한 전제 | 결과 |
|---|---|---|
| C-1 | `confirmPayment` 이 `@Transactional` 안에서 PG 를 호출한다 | **유지** — `PaymentCommandService:44` 메서드 전체가 클래스 레벨 `@Transactional` |
| C-2 | `confirm()` 에 `Idempotency-Key` 가 없다 | **유지** — `IDEMPOTENCY_HEADER` 참조가 `cancel()` 에만 있다 |
| C-3 | 롤백이 `paymentKey` 까지 되돌려 사후 조회가 불가능해진다 | **확대** — 계획은 "과금 잔존" 만 봤으나, 진짜 결함은 **진실 확정 수단의 소실**이다(ADR-0023 C2). 이것이 Alternative A 를 기각시킨다 |
| C-4 | `WebhookService` 가 상태를 복구하지 않는다 | **유지** — `PaymentRepository` 를 주입조차 받지 않는다 |
| C-5 | `payments` 에 `@Version` 과 `uk_payments_order_id` 가 있다 | **유지** — `V1__init_payment.sql` |
| C-6 | 고아 과금을 환불로 라우팅하려면 새 진입점이 필요하다 | **유지** — `requestRefund` 가 `status != APPROVED` 에서 조기 반환 |
| C-7 | `PaymentRefundService.succeed()` 가 비-APPROVED 결제를 이미 견딘다 | **반증(계획에 유리)** — `if (status == APPROVED) markRefunded()` 가 이미 있어 D6 우회에 **추가 변경이 필요 없다** |
| C-8 | PG 타임아웃 배선이 이미 전역이다 | **유지** — `TossClientConfig` 의 `RestClientCustomizer` 가 `RefundProperties.pgTimeout` 을 connect/read 양쪽에 건다. 승인용 타임아웃 배선을 새로 만들지 않는다 |
| C-9 | `claimed_at = NULL` nudge 가 순회 우선순위로 동작한다 | **확대** — refund 의 `findReconcileCandidates` 는 `claimed_at IS NULL DESC` 로 NULL 우선이나, `CLAIMED` 분기 predicate 가 `claimed_at < :staleBefore` 라 NULL 을 못 잡는다. **승인 원장은 두 분기 모두 `claimed_at IS NULL` 을 포함해 작성**한다 |
| C-10 | `PAY-012` 가 비어 있다 | **유지** — `ErrorCode` 는 `PAY_011` 까지 |

## 2. 구현 (P1~P12)

| # | 작업 | verify |
|---|---|---|
| P1 | `V11__payment_approval_ledger.sql` — `payment_approvals` + 인덱스 3종 | Flyway 통합테스트 그린 |
| P2 | `ApprovalStatus` enum (CLAIMED/SUCCEEDED/FAILED/UNRESOLVED) + 전이 규칙 | 전이 전수 단위테스트 |
| P3 | `PaymentApproval` 엔티티 — `markSucceeded/markFailed/markUnresolved/resolveManually/ownsGeneration/isTerminal` | 단위테스트 |
| P4 | `PaymentApprovalJpaRepository` — `insertClaimedIfAbsent`(INSERT IGNORE) · `claimForReconcile` · `findReconcileCandidates` · `nudgeUnresolved` · `findByOrderIdForUpdate` | 통합테스트 |
| P5 | `TossOutcome.Kind.ALREADY_PROCESSED` 신설 + `TossPaymentClient.confirm(paymentKey, orderId, amount, idempotencyKey)` 를 `exchange` 분류로 전환 | `MockRestServiceServer` 계약테스트 |
| P6 | `ApprovalOutcome` (SUCCEEDED/FAILED/UNRESOLVED + method/approvedAt) | — |
| P7 | `ApprovalExecutor` — `execute`(1회 호출·D4) · `verify`(조회 전용·D5) · `idempotencyKey` | 단위테스트 |
| P8 | `PaymentApprovalService` — `beginApproval`(T1) · `finalizeApproval`(T2) · `claimForReconcile` · `resolveManually` · 고아 라우팅(D6) | 단위 + 통합 |
| P9 | `PaymentCommandService` 재작성 — `@Transactional` 제거, T1/PG/T2 오케스트레이션만 | 기존 테스트 재작성 |
| P10 | `ApprovalReconciliationScheduler` + `ApprovalBacklogMetrics` + Slack 에스컬레이션 | 통합테스트 |
| P11 | `PaymentRefundService.requestRefundForOrphanedCharge` + `WebhookService` nudge(D7) | 단위테스트 |
| P12 | `PAY_012` + 컨트롤러 분기 + `app.payment.approval.*` 정책값 & 부팅 불변식 | 슬라이스 + 바인딩 테스트 |

## 3. 검증 시나리오 (V-1~V-9)

| # | 시나리오 | 기대 |
|---|---|---|
| V-1 | PG 성공 → T2 커밋 | `payments` APPROVED · 원장 SUCCEEDED · `payment.completed` outbox 1건 |
| V-2 | PG 4xx(영구) | `payments` FAILED · 원장 FAILED · `payment.failed` outbox |
| V-3 | PG 타임아웃 | `payments` **PENDING 유지** · 원장 UNRESOLVED · outbox 0 · 응답 `PAY-012` |
| V-4 | T1 커밋 후 T2 유실(crash 모사) → reconcile, PG=DONE | 원장 SUCCEEDED · `payments` APPROVED · `payment.completed` 발행 |
| V-5 | 동일, PG=ABORTED | 원장 FAILED · `payments` FAILED |
| V-6 | 동일, PG=IN_PROGRESS | **UNRESOLVED 유지** (닫지 않는다) |
| V-7 | 조회 실패 | UNRESOLVED 유지 + `attempts` 누적 |
| V-8 | 고아 과금: 로컬 CANCELLED + PG=DONE | 원장 SUCCEEDED · `payment_refunds` REQUESTED 1건 생성 |
| V-9 | 웹훅 수신 → UNRESOLVED nudge | `claimed_at IS NULL` · 다음 순회에서 최우선 claim |
| V-10 | 멱등키 안정성 | 최초 호출과 reconcile 재호출이 같은 `Idempotency-Key` (변이: 키에 timestamp 섞으면 red) |

## 4. 완료 조건

1. P1~P12 전부 구현 + V-1~V-10 전부 green
2. payment-service 모듈 그린 + 전 모듈 빌드 그린
3. 미충족 항목을 PR 본문에 명시

## 5. 알려진 미충족 (선기록)

- **실 PG 장애 주입 미검증** — TASKS.md 의 D-020 진입 조건("외부 PG 장애 주입 환경 필요")은 여전히 미충족이다. 모든 crash 경계는 `MockRestServiceServer` 와 트랜잭션 분리로 **모사**했다.
- **리뷰 수렴 미판정** — Codex 리뷰 미호출(사용자 지시). 완료 조건에 "P1 = 0" 이 없다.
- **T1 커밋 지연의 실측 없음** — 승인 응답에 커밋 1회가 추가되지만 벽시계 측정을 하지 않았다.
- **웹훅 서명 검증의 실 PG 관통 미검증** — 기존 표면이며 본 작업의 회귀는 아니다.
