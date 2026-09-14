# ADR-0023: 결제 승인 경계 계약 — 승인 원장 + 멱등키 + 조회 기반 reconciliation

- **Status**: Accepted
- **Date**: 2026-09-14
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 개발 부채 D-020

## Context

D-020 은 `PaymentCommandService.confirmPayment` 가 **DB 트랜잭션 안에서 외부 PG 승인을 호출**한다는 것이다. 코드 검증(2026-09-14)으로 고정한 사실:

### C1. 승인 호출이 트랜잭션 안에 있다

```java
@Transactional
public PaymentDetailDto confirmPayment(Long userId, ConfirmPaymentCommand command) {
    ...
    payment.assignPaymentKey(command.paymentKey());     // (a) 로컬
    TossConfirmResponse response = tossPaymentClient.confirm(...);   // (b) 외부 과금
    payment.approve(...);                                // (c) 로컬
    outboxEventPublisher.publishPaymentCompleted(...);   // (d) 로컬
}
```

(b) 가 성공한 뒤 커밋이 실패하면 — 커밋 실패, `@Version` 충돌(동시 `order.cancelled` 취소), 프로세스 사망, 커넥션 단절 — **외부 과금은 남고 로컬은 전부 롤백된다**. 결과 상태는 `payments.status = PENDING`, 주문은 미완료, 돈은 빠져나간 상태다.

### C2. 롤백이 진실 확정 수단까지 지운다

(a) 의 `assignPaymentKey` 는 Toss 가 발급한 실제 `paymentKey` 를 저장한다. 이것이 같은 트랜잭션에 있으므로, 롤백하면 `payments.payment_key` 는 **생성 시의 UUID placeholder 로 되돌아간다**. 그 UUID 로는 Toss 조회가 불가능하다 — 즉 **사후에 과금 여부를 확인할 수단 자체가 사라진다.** 이것이 D-020 의 핵심이며, 단순히 "트랜잭션을 짧게" 로 해결되지 않는 이유다.

### C3. 승인 호출에 멱등키가 없다

`TossPaymentClient.cancel` 은 `Idempotency-Key` 헤더를 보내지만(ADR-0018 D3), `confirm` 은 보내지 않는다(코드 확인: `IDEMPOTENCY_HEADER` 참조가 `cancel` 에만 있다). 또 `confirm` 은 `.retrieve()` 라 비-2xx 에서 예외를 던지므로 **일시 실패와 영구 실패가 구분되지 않고**, 호출부는 둘 다 `payment.fail()` 로 종결한다 — 타임아웃(과금 성립 가능)이 **영구 실패로 확정**된다.

### C4. 웹훅은 상태를 복구하지 않는다

`WebhookService.processWebhook` 은 서명 검증 + `webhook_logs` 적재까지만 한다. `PaymentRepository` 를 주입조차 받지 않는다. PG 가 "결제 완료" 를 알려도 로컬 상태는 그대로다.

### C5. 같은 문제를 이미 푼 표면이 있다 — 환불 경로

ADR-0018 D3 이 **동일한 crash 경계**를 풀었다: 원장 fence + claim(T1) / PG 호출(트랜잭션 밖) / 확정(T2) + fencing token(`generation`) + **조회 기반 reconciliation**. 승인 경로만 이 계약 밖에 남아 있다.

### C6. 고아 과금은 기존 환불 진입점에 들어가지 못한다

`PaymentRefundService.requestRefund` 는 `payment.getStatus() != APPROVED` 면 `false` 로 조기 반환한다. 코드 주석이 근거를 적어둔다 — "요청은 결제 승인 이후에만 발행되므로 감지 3지점 모두 `payment.completed` 이후". 그런데 본 ADR 이 만드는 reconciler 는 **`payments` 가 `CANCELLED` 인데 PG 에는 과금이 성립한 상태**를 발견한다. 이는 감지 4지점이며, 기존 게이트에 막힌다.

## Decision

**승인 경로를 ADR-0018 D3 과 같은 형태로 재구성한다: `payment_approvals` 원장을 fence 로 두고, 승인을 T1(claim·키 커밋) / PG 호출(트랜잭션 밖·안정 멱등키) / T2(확정) 로 분해하며, 미확정은 PG 조회로 확정하는 reconciliation 이 종결시킨다.**

### D1 — 트랜잭션 분해: T1 / PG / T2

`confirmPayment` 에서 `@Transactional` 을 **제거**하고 세 구간으로 나눈다. 각 구간은 `PaymentApprovalService` 의 독립 트랜잭션(`REQUIRES_NEW`)이며, PG 호출은 **그 사이**에서 일어난다.

| 구간 | 트랜잭션 | 하는 일 |
|---|---|---|
| **T1** `beginApproval` | 독립 | 소유자·금액·`ensureConfirmable` 검증 → **`assignPaymentKey` 커밋** → `payment.requested` outbox → `payment_approvals` fence INSERT(`CLAIMED`, `generation=1`, `claimed_at=now`) |
| **PG** | 없음 | `TossPaymentClient.confirm(..., idempotencyKey)` → `TossOutcome` 분류 |
| **T2** `finalizeApproval` | 독립 | generation 가드 → `payment.approve`/`fail` → `payment.completed`/`payment.failed` outbox → 원장 `SUCCEEDED`/`FAILED`/`UNRESOLVED` |

**T1 이 `paymentKey` 를 커밋하는 것이 이 결정의 본체다** (C2). T1 이 커밋된 뒤에는 어떤 실패가 나도 `payment_approvals.payment_key` 로 PG 에 진실을 물을 수 있다.

**하나의 트랜잭션으로 묶지 않는 이유**는 ADR-0018 D3 과 같다 — 묶으면 PG 성공 후 롤백 시 fence 행 자체가 사라져 fence 가 무효가 되고, "호출했는지 모르는" 관측 상태가 존재하지 않게 되어 reconciliation 이 성립하지 않는다.

### D2 — 승인 원장 `payment_approvals`

`order_id` UNIQUE 가 "동일 논리 승인 1건" fence 다. 상태는 4개다.

| 상태 | 의미 |
|---|---|
| `CLAIMED` | T1 커밋됨. PG 호출 중이거나, 호출 직전/직후에 죽었다 |
| `SUCCEEDED` | 승인 확정 (종결) |
| `FAILED` | 승인 실패 확정 (종결) |
| `UNRESOLVED` | 결과 불명. **종결이 아니다** — reconciliation 또는 수동 종결로만 벗어난다 |

**환불 원장의 `REQUESTED` 에 대응하는 상태를 두지 않는다.** 환불은 비동기 트리거라 "요청은 커밋됐고 실행자는 아직 안 집었다" 는 구간이 실재하지만, 승인은 사용자 HTTP 요청이 곧 실행 시작이라 그 구간이 없다. 상태를 만들면 도달하지 않는 상태가 생긴다.

fence 획득은 `INSERT IGNORE` 단일 원자 쿼리다 — JPA `save` 의 유니크 위반은 flush 시점에 터져 트랜잭션을 rollback-only 로 오염시킨다(ADR-0018 D3 과 동일 근거). `ON DUPLICATE KEY UPDATE id = id` 를 쓰지 않는 이유도 같다(Connector/J found-rows 시맨틱이 중복을 1로 보고한다).

### D3 — 안정 멱등키 `approve-{orderId}`

`TossPaymentClient.confirm` 에 `Idempotency-Key` 헤더를 추가하고, 키는 **주문 단위로 안정적**이다 — 최초 호출·사용자 재시도·reconciliation 재호출이 모두 같은 값을 쓴다. 다른 키를 쓰면 PG 측 중복 방어가 무력해진다.

동시에 `confirm` 을 `.retrieve()` 에서 `.exchange()` 분류로 바꿔 `TossOutcome` 을 돌려준다. 분류는 `cancel` 과 같은 `toOutcome` 을 공유한다 — HTTP status·PG 오류코드 해석은 외부 연동 지식이므로 클라이언트 경계에 둔다(ADR-0018 D5).

`ALREADY_PROCESSED_PAYMENT` 는 `Kind.ALREADY_PROCESSED` 로 신설한다. **`ALREADY_CANCELED` 를 재사용하지 않는다** — 둘 다 "이미 일어났으니 조회하라" 로 수렴하지만, 의미가 다른 값을 겹쳐 쓰면 감사 로그의 `code` 가 거짓말을 한다.

### D4 — 승인 재시도는 요청 안에서 하지 않는다 (`max-attempts = 1`)

환불은 백그라운드 dispatcher 라 지수 백오프 재시도가 자연스럽지만, 승인은 **사용자가 기다리는 동기 요청**이다. 요청 안에서 재시도하면 응답 지연이 시도 수에 비례해 늘고, 그동안 예약 lease 가 만료된다(`leaseApprovalMargin` 의 전제가 깨진다).

따라서 **승인은 1회만 호출**하고, 일시 실패(`TRANSIENT`/`UNKNOWN`)는 곧바로 `UNRESOLVED` 로 커밋한 뒤 **reconciler 에게 넘긴다.** 재시도의 소유자를 요청 경로가 아니라 배경 잡으로 옮기는 결정이다.

### D5 — Reconciliation: 조회가 먼저다

`ApprovalReconciliationScheduler` 가 **lease 만료 `CLAIMED` + `UNRESOLVED`** 를 claim 하고 `TossPaymentClient.find(paymentKey)` 로 진실을 확정한다. 조회 없이 재호출하면 이미 성립한 과금을 다시 부른다(ADR-0018 D3 crash matrix b 와 같은 논거).

PG 상태 → 원장 처분:

| PG `status` | 처분 | 근거 |
|---|---|---|
| `DONE` | `SUCCEEDED` — 로컬 `payment.approve` | 과금이 성립했다 |
| `CANCELED`, `PARTIAL_CANCELED`, `ABORTED`, `EXPIRED` | `FAILED` — 로컬 `payment.fail` | 과금이 성립하지 않았거나 이미 되돌려졌다 |
| `READY`, `IN_PROGRESS`, `WAITING_FOR_DEPOSIT`, 기타 | `UNRESOLVED` 유지 | **아직 확정되지 않았다** |
| 조회 실패 (404·타임아웃·파싱 불가) | `UNRESOLVED` 유지 | 진실 미확정 |

**중간 상태를 `FAILED` 로 닫지 않는다.** 닫으면 뒤늦게 성립한 과금이 로컬 `FAILED` 와 영구히 어긋난다. 대신 `unresolved-limit` 초과 시 Slack + 수동 종결 대상으로 남긴다 — ADR-0018 D2 와 동일한 종결 표면이다.

**reconciler 는 승인을 재호출하지 않는다.** 환불의 `verifyThenExecute` 는 "취소된 적 없음" 이 확정되면 재호출하지만, 승인은 다르다 — 재호출은 **없던 과금을 새로 만드는 행위**이고, 그 시점엔 사용자 세션도 예약 lease 도 이미 없다. 조회로 확정만 한다.

### D6 — 고아 과금(PG 성공 ↔ 로컬 비-`PENDING`)은 환불 경로로 라우팅한다

T2/reconcile 에서 PG 는 `DONE` 인데 로컬이 `PENDING` 이 아닌 경우의 처분:

| 로컬 상태 | 처분 |
|---|---|
| `APPROVED` | 원장만 `SUCCEEDED`. 로컬은 이미 정합 — no-op |
| `CANCELLED` | **고아 과금** → 환불 요청 fence 삽입 + 원장 `SUCCEEDED` |
| `REFUNDED` | 원장 `SUCCEEDED`. 이미 되돌려졌다 |
| `FAILED` | **고아 과금** → 환불 요청 fence 삽입 + 원장 `SUCCEEDED` |

고아 과금을 위해 `PaymentRefundService.requestRefundForOrphanedCharge` 를 신설한다 — **`APPROVED` 게이트만 우회**하고 fence·중복 no-op·종결 재발행·회신 발행은 기존 `requestRefund` 와 완전히 같은 경로를 쓴다(C6). 우회가 안전한 근거는 `succeed()` 가 이미 `payments` 상태를 확인하고 `APPROVED` 일 때만 `markRefunded()` 한다는 것이다 — `CANCELLED`/`FAILED` 결제의 환불은 원장에만 종결되고 `payments` 를 건드리지 않는다.

**원장을 `SUCCEEDED` 로 두는 이유**: 승인은 실제로 성공했다. 그 뒤 환불이 필요하다는 것은 **환불 원장이 소유하는 별개의 축**이다. 승인 원장을 `FAILED` 로 적으면 감사 기록이 거짓이 된다.

### D7 — 웹훅은 nudge 만 한다

`WebhookService` 가 `PAYMENT_STATUS_CHANGED` 를 받으면 해당 `paymentKey` 의 **`UNRESOLVED` 승인 원장의 `claimed_at` 을 `NULL` 로 만든다** — 다음 reconcile 순회에서 최우선으로 잡힌다.

**웹훅 트랜잭션 안에서 PG 를 부르지 않는다** — 소비 경로에서 외부를 호출하지 않는다는 규약(ADR-0018 D3)이 여기에도 적용된다. 웹훅은 "지금 물어보면 답이 나온다" 는 **신호**일 뿐, 진실의 출처가 아니다. 웹훅 payload 를 그대로 믿고 상태를 바꾸면 서명이 유효한 재전송/순서 역전이 로컬 상태를 되돌릴 수 있다.

**`CLAIMED` 는 nudge 하지 않는다.** 진행 중인 호출과 경쟁시킬 이유가 없고, lease 만료로 어차피 회수된다.

### D8 — 사용자 응답: `UNRESOLVED` 는 실패가 아니다

현재 컨트롤러는 `FAILED` 를 `PAY-005` 로 돌려준다. `UNRESOLVED` 를 여기에 합류시키면 **과금이 성립했을 수 있는 건을 실패라고 단언**하게 된다. `PAY-012`(409)를 신설한다 — "결제 결과를 확인 중입니다."

## Alternatives Considered

### Alternative A: `@Transactional` 만 떼고 호출 순서를 바꾼다 (PG 먼저, 그 다음 짧은 트랜잭션)

- **장점**: 변경이 작다. 원장·스케줄러가 필요 없다.
- **단점**: `paymentKey` 를 커밋할 시점이 PG 호출 **이후**뿐이라 C2 가 그대로 남는다 — 호출 직후 사망하면 조회 수단이 없다. "호출했는지 모르는" 상태를 관측할 표면도 없다.
- **기각 사유**: D-020 이 지목한 두 결함 중 하나(외부 과금 잔존)만 완화하고, 다른 하나(사후 확인 불가)는 그대로다.

### Alternative B: 승인을 비동기화한다 (요청은 접수만, 승인은 dispatcher 가)

- **장점**: 환불 경로와 완전히 같은 형태가 된다. `REQUESTED` 상태가 실재하게 된다.
- **단점**: `POST /confirm` 의 응답 계약이 깨진다(결제 결과를 즉시 못 준다). 프런트 전면 변경이 필요하고, 예약 lease 가 dispatcher 지연만큼 더 소모된다.
- **기각 사유**: API 계약 변경은 D-020 의 범위가 아니다. T1/PG/T2 분해만으로 crash 경계는 동일하게 닫히며, 세 구간 모두 요청 안에서 동기로 돌아도 문제가 없다 — 분해의 목적은 비동기화가 아니라 **커밋 경계를 PG 호출 앞뒤로 쪼개는 것**이다.

### Alternative C: 웹훅을 진실의 출처로 삼는다 (조회 없이 웹훅 payload 로 상태 전이)

- **장점**: reconciliation 스케줄러가 필요 없다. 지연이 가장 짧다.
- **단점**: 웹훅은 유실·중복·순서 역전이 모두 가능하고, 전달 보장이 PG 측 정책에 달려 있다. 웹훅이 안 오는 건은 영원히 미결로 남는다.
- **기각 사유**: "미결로 남기지 않는다"(ADR-0012 D3 ④)를 외부 시스템의 전달 보장에 위임하게 된다. 웹훅은 nudge 로만 쓴다(D7).

### Alternative D: PG 승인을 2PC/Saga 참여자로 만든다

- **장점**: 이론적으로 원자성이 보장된다.
- **단점**: Toss 는 2PC 를 지원하지 않는다. 외부 PG 는 트랜잭션 참여자가 될 수 없다.
- **기각 사유**: 실현 불가능하다. 외부 경계의 원자성은 **조회로 확정 가능한 안정 멱등키**로 근사하는 것이 유일한 수단이다.

## Consequences

### 긍정적 영향

- "외부 과금은 남고 로컬은 롤백" 이 구조적으로 불가능해진다 — 어떤 실패 경로에서도 `payment_approvals` 행과 실제 `paymentKey` 가 커밋돼 있어 사후 확정이 가능하다.
- 일시 실패가 영구 실패로 확정되지 않는다(C3). 타임아웃은 `UNRESOLVED` 로 남고 조회가 가른다.
- 승인/환불 두 외부 호출 경계가 **같은 계약**(fence + claim/generation + T1/PG/T2 + 조회 확정 + 수동 종결)을 쓴다. 운영자가 배워야 할 모델이 하나다.
- 미해결 승인 건수와 나이가 메트릭으로 보인다 — 계약의 검증 가능성(ADR-0018 D6 과 동일 논거).

### 부정적 영향 / 트레이드오프

- **승인 경로에 커밋이 2회 생긴다.** T1 커밋 비용이 사용자 응답 지연에 더해진다. 외부 호출(수백 ms)에 비하면 작지만 0 은 아니다.
- **`UNRESOLVED` 응답이 사용자에게 노출된다.** 기존에는 (거짓으로) 성공 아니면 실패였다. 정직해진 대신 프런트가 처리해야 할 상태가 하나 늘었다.
- **PG 재호출을 하지 않으므로**(D5) 일시 실패로 시작된 결제는 사용자가 다시 시도해야 한다. reconciler 는 과금 여부를 확정할 뿐 결제를 완성시켜 주지 않는다.
- **테이블이 하나 더 늘고 스케줄러가 하나 더 돈다.** payment-service 의 스케줄러는 이제 5개다(outbox polling · processed_events cleanup · outbox cleanup · refund dispatch/reconcile · approval reconcile).
- **실 PG 장애 주입으로 검증되지 않았다.** 본 ADR 의 crash 경계는 `MockRestServiceServer` 와 Testcontainers 로만 관측했다. TASKS.md 의 D-020 은 "외부 PG 장애 주입 환경 필요" 를 진입 조건으로 적었고, 그 조건은 여전히 미충족이다.

## 관련 문서

- [ADR-0012](./0012-phase4-db-event-saga-contract.md) — D3 ④ saga 최악 경로의 수렴처
- [ADR-0018](./0018-compensation-refund-contract.md) — 본 ADR 이 승인 경로로 확장하는 환불 경계 계약(D2/D3/D5)
- `docs/plans/task-d020-approval-reconciliation.md` — 구현 계획
