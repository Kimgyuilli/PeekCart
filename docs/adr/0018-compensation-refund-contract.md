# ADR-0018: 보상/환불 트리거 계약 — 감지 3지점 → Payment 환불 실행 → 원장 종결

- **Status**: Partially Superseded by [ADR-0020](./0020-dlq-replay-contract.md)
- **Date**: 2026-08-15
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga

> **무효화 범위 (ADR-0020, DLQ replay 계약)**: 본 ADR 의 **`1 topic = 1 producer` 규약 유지** 결정(§Decision)이 **DLQ replay 에 한해** 무효화된다.
> - replay 는 **원장 소유 서비스가 남이 발행한 토픽에 write** 한다 — 소비 경로 원장의 `origin_topic` 은 정의상 남의 토픽이라 규약을 그대로 적용하면 replay 가능 집합이 공집합이 된다(ADR-0020 §D8-2).
> - **무효화되는 것은 write 권한의 배타성뿐**이다. **새 도메인 이벤트의 발행 주체를 1개로 두는 결정은 유효**하며, 본 ADR 의 환불 트리거 토픽 설계(Alternative A 기각 포함)는 전부 그대로다.
> - replay 예외의 fence(주체·목적지 토픽/파티션·`key`/`payload`/`eventId` 동일·payload 변경 금지)는 ADR-0020 §D8-3 이 정한다.
> 그 외 본 ADR 의 결정(감지 3지점·환불 상태머신·멱등/crash 경계·종결 표면·재시도·관측)은 유효하다.


## Context

ADR-0012 D3 ④는 saga 의 최악 경로(**결제는 승인됐는데 재고가 확정되지 않음**)의 수렴처를 "**환불 요청 + 운영 알림**(`order.cancelled` 또는 환불 트리거)"이라고만 적고 **트리거의 실체를 미확정으로 남겼다**. 그 결과 구현이 진행되면서 "환불이 필요하다"는 판단만 서비스마다 따로 쌓였고, 실제로 환불을 실행할 수 있는 Payment 는 그 판단을 **구조적으로 전달받지 못한다**.

### C1. 현재 감지 지점 — 3곳, 영속성 제각각 (코드 감사, 2026-08-14)

| # | 서비스 | 감지 조건 | 남기는 것 | 영속성 |
|---|---|---|---|---|
| ① | **Product** | 예약 원장이 `RELEASED/CANCEL_REQUESTED/FAILED` 인데 `payment.completed` 도착 (`StockReservationService.compensatePaidButUnreserved`) | `stock_reservations.compensated_at` + Slack | **감지 marker 1회성** — 환불 *전에* 찍히며 "요청됨/해결됨"을 구분하지 못한다 |
| ② | **Order** | 취소된 주문에 `payment.completed` 도착 (`OrderEventConsumer.recordPaidButCancelled`, ④-a) | `order_compensations(order_id, reason)` `OPEN` + Slack | **영속 원장** — 단 종결 전이 수단이 없어 OPEN 으로만 쌓인다(④-a §2.6 R-2) |
| ③ | **Payment** | `order.cancelled` 수신 시 `cancelBeforePayment()` 가 APPROVED 반환(과금-후-취소) (`PaymentEventConsumer.handleOrderCancelled`) | Slack + 로그 | **비영속** — 소비가 커밋되면 `processed_events` 때문에 재소비도 불가해 **신호가 소실된다** |

세 지점 모두 Slack 에 의존하는데, order/product/payment 의 `SlackPort` 는 **배포 구성상 no-op** 이다(PR3b 게이팅). 즉 현재 시스템에서 "환불 필요"는 **어디에서도 종결되지 않으며, ③은 아예 흔적도 남지 않는다**.

### C2. 환불 실행 측 현황

- `TossPaymentClient` 에는 `confirm(paymentKey, orderId, amount)` 만 있고 **취소/환불 API 미구현**
- `PaymentStatus` = `PENDING/APPROVED/FAILED/CANCELLED` 이며 **`APPROVED.canTransitionTo(*) = false`**(terminal) — 환불 상태를 넣으려면 상태머신 확장이 필요
- `payments` 는 `order_id`(unique)·`payment_key`(unique)·`amount`·`@Version` 을 가지므로 **Payment 는 추가 조회 없이 환불 가능**하고 금액 결정 주체도 Payment 다

### C3. 멱등성 인프라의 한계

`processed_events` UK = `(event_id, consumer_group)`(3서비스 공통). **서로 다른 `eventId` 로 온 같은 의미의 트리거는 막지 못한다.** 이건 이론이 아니라 실재 경로다 — 취소된 주문에 `payment.completed` 가 도착하면 ①과 ②가 **같은 사건에 대해 각각** 감지한다. 게다가 `dlq-replay-window: 7d`(ADR-0012 D5) 를 넘긴 재발행은 새 `eventId` 를 쓰므로 멱등 창 밖에서는 `processed_events` 가 아예 무력하다.

### C4. 경계

**D-020**(Toss 승인 성공 후 로컬 커밋 실패 → 외부 과금은 남고 로컬은 롤백, 웹훅 reconciliation 부재)은 인접하지만 **본 ADR 의 대상이 아니다**. 본 ADR 은 "**로컬에 `APPROVED` 로 커밋된 결제**"의 환불만 다룬다. 로컬에 흔적이 없는 과금은 원장으로 발견할 수 없으므로 PG 조회/웹훅 기반 reconciliation(D-020) 이 필요하다.

## Decision

**세 감지 지점이 각자 Outbox 로 환불 요청을 발행하고, Payment 가 단일 실행자로서 `payment_refunds` 원장 위에서 환불을 수행하며, 결과를 `payment.refunded` 로 회신해 각 원장을 종결시킨다.**

### D1. 환불 트리거 이벤트 계약

| 토픽 | producer | consumer | consumer group | 파티션 키 |
|---|---|---|---|---|
| `stock.compensation.requested` | **Product** | Payment | `payment-svc-stock-compensation-requested-group` | `orderId` |
| `order.compensation.requested` | **Order** | Payment | `payment-svc-order-compensation-requested-group` | `orderId` |
| `payment.refunded` | **Payment** | Order, Product, **Notification** | `order-svc-payment-refunded-group`, `product-svc-payment-refunded-group`, `notification-svc-payment-refunded-group` | `orderId` |

- **1 topic = 1 producer 규약 유지**(ADR-0012 D4 의 7개 토픽이 전부 따르는 패턴). 요청을 공용 토픽 1개로 합치면 producer 가 2개가 되어 `NewTopic` 프로비저닝 소유자(producer-owns-topic)와 payload 스키마 소유가 모호해진다 → Alternative A 로 기각
- **consumer group 명명**: `{consumer-svc}-svc-{topic-을 kebab 으로}-group` 을 **축약 없이** 적용한다. 기존 코드에 축약 사례가 하나 있으나(`stock.reservation.result` → `*-stock-result-group`), 이는 토픽명이 3토큰이라 생긴 예외이며 **신규 토픽에는 적용하지 않는다** — 축약은 규칙이 아니라 개별 판단이라 재현되지 않는다
- **DLQ**: 세 토픽 모두 `<topic>.dlq` 를 두고 producer 가 `NewTopic` 을 소유한다(기존 규약 동일). DLQ 유입은 종결이 아니라 **DLQ 원장(구현 ④ P9)의 입력**이다
- **Kafka 소비 재시도**: 세 신규 소비 경로 모두 기존 서비스의 `DefaultErrorHandler` + `FixedSequenceBackOff(1s, 5s, 30s)` 를 **그대로 재사용**한다(별도 정책을 두지 않는다). 소진 시 `<topic>.dlq` 전환. **이것은 D5 의 PG 호출 재시도와 다른 층위다** — Kafka 재시도는 *메시지 소비 실패*, D5 재시도는 *PG API 실패* 를 다룬다
- **envelope**: `KafkaEventEnvelope` `schemaVersion=1`. 하위호환 규칙은 ADR-0012 D2 를 그대로 따른다(필드 추가만 허용, 삭제·의미 변경 금지)
- **payload 필수 필드**(세 요청 토픽 공통): `orderId`, `reason`(`PAID_BUT_UNRESERVED` | `PAID_BUT_CANCELLED`), `detectedAt`. **금액은 싣지 않는다** — 금액 결정 주체는 Payment(C2)이며, 발행자가 실은 금액을 신뢰하면 두 소스가 갈라진다
- **`payment.refunded` payload**: `orderId`, **`userId`**, `result`(`SUCCEEDED` | `FAILED` | `UNRESOLVED`), `refundedAmount`(성공 시), `failureCode`(실패 시), `resolvedAt`. **`userId` 는 Notification 때문에 필수다** — `NotificationConsumer` 는 모든 알림에서 payload 의 `userId` 를 직접 읽고 `orderId` 로 사용자를 조회할 계약이 없다. Payment 는 `payments.user_id` 를 보유하므로 추가 조회 없이 채운다
- **retention**: Kafka 토픽 보존은 기존 토픽과 동일 정책. 멱등 창은 ADR-0012 D5 의 `dlq-replay-window: 7d` 를 따르되, **환불 1건 보장은 이 창에 의존하지 않는다**(D3 — 도메인 키가 창 밖에서도 유효)
- **원자성 불변식**: 감지 기록(`compensated_at` / `order_compensations` / Payment 측 원장)과 **요청 Outbox 행은 동일 트랜잭션에서 생성**된다. 부분 커밋은 "감지했는데 요청이 없는" 영구 미결을 만든다
- **backfill 과 그 멱등 근거**: 배포 시점에 이미 존재하는 `compensated_at IS NOT NULL` 행과 `order_compensations.status='OPEN'` 행은 **각 producer 의 마이그레이션에서 1회씩 요청 Outbox 를 생성**한다.
  - **멱등은 producer DB 안에서 성립해야 한다.** `payment_refunds.order_id` 유니크는 **Payment DB 의 실행 fence** 일 뿐, DB-per-service 경계상 Product/Order 가 Outbox 행을 다시 만드는 것을 막지 못한다(초안의 근거는 틀렸다)
  - 각 producer 는 `INSERT ... SELECT ... WHERE NOT EXISTS (해당 aggregate 의 요청 Outbox 행)` 기준으로 backfill 하며, 재실행 시 추가 발행 0 은 **이 조건이 보장**한다
  - 중복 발행이 그럼에도 발생하면(서로 다른 producer 의 동시 감지 등) **환불 실행이 1건인 것은 D3 fence 가 별도로 보장**한다 — 두 보장은 층위가 다르며 서로를 대체하지 않는다
- **cross-topic 순서 무보장**: 파티션 키가 같은 `orderId` 라도 **서로 다른 토픽 사이의 순서는 보장되지 않는다**. 요청 2종의 도착 순서, 요청↔`payment.*` 의 순서에 의존하는 로직을 두지 않으며, 수렴 책임은 전적으로 D2 상태머신과 D3 fence 에 있다

### D2. Payment 환불 상태머신 — 별도 원장 + 종결 상태

**`payment_refunds` 원장(신설)** 이 환불의 진행 상태를 소유하고, `payments.status` 에는 **종결 상태 `REFUNDED` 만** 추가한다.

```
payments.status:  PENDING → APPROVED → REFUNDED        (신규 전이: APPROVED → REFUNDED)
                  PENDING → FAILED | CANCELLED         (기존 유지)

payment_refunds:  order_id UNIQUE · payment_key · amount · user_id
                  status  REQUESTED → CLAIMED → SUCCEEDED | FAILED | UNRESOLVED
                          UNRESOLVED → SUCCEEDED | FAILED        (reconciliation)
                          UNRESOLVED → FAILED                    (수동 종결, 감사 필드 필수)
                  attempts · claimed_at · last_error · pg_response
                  requested_at · resolved_at
```

| 상태 | 의미 | 나가는 전이 |
|---|---|---|
| `REQUESTED` | 요청 커밋 + fence 획득. **아직 PG 호출 전** | → `CLAIMED` (dispatcher 가 claim, D3) |
| `CLAIMED` | dispatcher 가 소유권을 잡고 **PG 호출 중** | → `SUCCEEDED` / `FAILED` / `UNRESOLVED` |
| `SUCCEEDED` | PG 취소 성공 확정 (**종결**) | 없음. `payments.status=REFUNDED` + `payment.refunded(SUCCEEDED)` 발행(동일 트랜잭션) |
| `FAILED` | **영구 실패** 확정 (**종결**) | 없음. `payment.refunded(FAILED)` 발행. 재시도하지 않는다 |
| `UNRESOLVED` | 결과 불명(타임아웃·응답 유실·재시도 소진). **종결 아님** | → `SUCCEEDED` / `FAILED` (reconciliation 이 PG 조회로 확정) · → `FAILED` (조회 상한 초과 시 **수동 종결**, `resolved_by`·사유 기록) |

**`UNRESOLVED` 는 반드시 종결로 수렴한다** — reconciliation 잡(payment-service 소유, 5분 주기)이 `CLAIMED`(임계 시간 초과분)과 `UNRESOLVED` 를 PG 조회로 확정한다. **조회 상한 = 24시간**이며, 그 안에 확정되지 않으면 운영 알림 + **수동 종결 경로**로 넘어간다(감사 필드에 종결자·근거 기록). 즉 미결이 무기한 남는 상태는 계약상 존재하지 않으며, ADR-0012 D3 ④의 "종료 상태를 미결로 남기지 않음"이 유지된다.

- **불변식**: **주문당 전액 환불 1건**. 부분 환불·복수 환불은 본 계약의 범위 밖이다(`payments.order_id` unique = 주문 1건 = 결제 1건 = 전액). 금액은 `payments.amount` 를 Payment 가 스스로 읽는다
- `payments.status` 를 `REFUNDED` 로 옮기는 이유: 기존 `APPROVED` 를 그대로 두면 "과금이 살아있는 결제"와 "환불된 결제"를 상태로 구분할 수 없다. 반대로 진행 상태(`REQUESTED`/`UNRESOLVED`)까지 `payments` 에 넣지 않는 이유는 Alternative C 참조

### D3. 멱등성 키와 crash 경계 — 보장 문구의 정의

> **보장하는 것**: **동일 논리 환불 1건** — 같은 주문에 대해 성립하는 환불 결과가 최대 1건이다.
> **보장하지 않는 것**: PG API 호출 횟수 1회. 로컬 수단으로는 외부 호출 경계의 crash 를 덮을 수 없다(아래 matrix).

- **fence = `payment_refunds.order_id` UNIQUE**. 세 진입점(요청 토픽 2종 + Payment 로컬 감지)은 원장 행 삽입만 시도하고, 삽입 실패(중복 키)는 **정상 처리된 no-op** 으로 종료한다 — 예외로 던져 DLQ 로 보내지 않는다
- **진입점은 PG 를 호출하지 않는다.** 소비 트랜잭션 안에서 외부를 호출하면 (a) 트랜잭션이 외부 지연만큼 길어지고 (b) **PG 성공 후 로컬 롤백 시 유니크 행이 사라져 다음 트리거가 다시 호출**한다 — fence 가 무력화되는 경로다. 그래서 **책임을 둘로 나눈다**:
  1. **진입점(consumer 또는 로컬 감지)**: `REQUESTED` 행을 커밋하고 끝낸다
  2. **dispatcher(payment-service 스케줄러, `@SchedulerLock`)**: `REQUESTED → CLAIMED` **조건부 UPDATE(CAS)** 로 행을 claim 한 쪽만 PG 를 호출한다. claim 은 `claimed_at` 을 남겨 lease 로 동작하며, 임계 시간을 넘긴 `CLAIMED` 는 reconciliation 이 회수한다
  - 이 분리로 **세 진입점(이벤트 2 + 로컬 1)이 하나의 실행 경로로 합류**하며, Payment 로컬 감지와 뒤늦게 도착한 `order.compensation.requested` 가 이중 처리되지 않는다(둘 다 같은 유니크 키에서 1행으로 접힌다)
- **"조회 후 호출"은 fence 가 아니다**(`PLAN-BLINDSPOTS.md` B12, ④-a 가 P0 로 맞은 항목). 두 트리거가 같은 스냅샷을 읽고 둘 다 "없음"으로 판정하는 창을 막는 것은 조회가 아니라 **유니크 제약**이다
- **`processed_events` 로는 불충분**: 두 요청은 서로 다른 `eventId`(다른 토픽·다른 producer)이고, 7일 창을 넘긴 재발행도 새 `eventId` 다(C3). fence 는 **eventId 가 아니라 도메인 키**여야 한다
- **PG 멱등키**: Toss 취소 호출에 **`orderId` 기반의 안정적 idempotency 키**를 사용한다(재시도 시 동일 값). 이는 중복 호출이 실제 이중 환불이 되지 않게 하는 **PG 측 방어선**이며, 로컬 fence 와 독립적으로 존재한다

**crash matrix** — 각 칸의 복구 규칙:

| # | 시점 | 관측되는 상태 | 위험 | 복구 규칙 |
|---|---|---|---|---|
| a | claim 커밋 후 **PG 호출 전** 사망 | `CLAIMED` (`claimed_at` 오래됨) | 환불 **유실**(아무도 재시도하지 않음) | reconciliation 이 임계 시간 초과 claim 을 회수 → **PG 조회로 실제 취소 여부 확인** 후 미취소면 재호출(같은 멱등키), 취소됐으면 `SUCCEEDED` 확정 |
| b | PG 호출 **성공 후 로컬 커밋 전** 사망 | `CLAIMED` (`claimed_at` 오래됨) | 재시도 시 **중복 호출** | 동일 경로. 재호출은 **PG 멱등키로 이중 환불이 되지 않으며**, 조회가 이미 취소됨을 알려주면 호출 없이 `SUCCEEDED` 확정 |
| c | PG 호출 **타임아웃/응답 유실** | `UNRESOLVED` | 결과 불명 | 재시도 소진 시 `UNRESOLVED` 로 영속 → reconciliation + 운영 알림 → 24h 내 미확정 시 수동 종결. **로그가 아니라 상태로 남긴다** |
| d | 진입점이 `REQUESTED` 커밋 **직후** 사망 | `REQUESTED` | 없음 | dispatcher 가 다음 주기에 claim 한다 — 진입점이 PG 를 호출하지 않기 때문에 이 칸은 **위험이 아니다**(책임 분리의 이득) |

세 칸의 복구가 전부 **"PG 조회로 진실을 확정한다"** 로 수렴한다. 따라서 조회 API 사용은 선택이 아니라 본 계약의 **필수 구성요소**다.

### D4. 종결 표면 — 회신 이벤트로 각 원장을 닫는다

**결과에 따라 원장의 종착 상태가 다르다.** `RESOLVED` 는 "**환불 완료**"를 의미하며, 환불이 실패했는데 원장이 해결됨으로 닫히면 그 원장은 거짓말을 한다.

| `payment.refunded.result` | Order `order_compensations` | Product |
|---|---|---|
| `SUCCEEDED` | `OPEN → RESOLVED` (+`resolved_at`) | 종결 시각 기록 |
| `FAILED` | `OPEN → REFUND_FAILED` (+`failure_code`) — **미해결 종착**(자동 재시도 없음, 운영 대상) | 동일하게 실패로 기록 |
| `UNRESOLVED` | **전이하지 않는다**(OPEN 유지) — Payment 가 확정한 뒤 다시 발행한다 | 동일 |

- **Order**: 위 표대로 전이한다. **④-a 의 R-2 는 `SUCCEEDED` 경로에서 닫히고**, `FAILED` 는 "닫혔지만 해결되지 않음"을 구분해 남긴다
- **Product**: `compensated_at` 은 **감지 marker 이지 종결 표시가 아니다**(C1 ①). 종결은 별도 컬럼에 기록한다(컬럼 신설은 구현 ④-c-1 소관, 본 ADR 은 "marker ≠ 종결"과 종결 주체·결과별 의미만 확정)
- **Payment**: 자기 경로(C1 ③)의 감지를 **영속화한다**. 현재의 Slack + 로그는 종결은커녕 기록도 아니다 — Payment 는 감지 즉시 **자기 트랜잭션에서 `payment_refunds` 를 `REQUESTED` 로 생성**하고 끝낸다(이벤트를 우회하는 것은 자기 자신이 실행자이기 때문이며, **PG 호출은 하지 않는다** — D3 의 dispatcher 가 유일한 호출자다). Order 의 요청이 나중에 도착해도 같은 유니크 키에서 1행으로 접힌다
- 알림은 종결 근거가 **아니다**. `SlackPort` 가 no-op 이어도 원장으로 상태를 알 수 있어야 한다(④-a 가 이미 내린 판정)

### D5. 재시도 · 영구 실패 종결

> 여기서 말하는 재시도는 **PG API 호출 재시도**다. Kafka 소비 실패 재시도(`FixedSequenceBackOff(1s,5s,30s)` → `.dlq`)는 D1 소관의 다른 층위다.

- **transient**(자동 재시도 대상): 네트워크 오류, 5xx, 타임아웃 → 재시도 상한 **3회**(지수 백오프). 소진 시 `UNRESOLVED`
- **영구 실패**(재시도 금지): 취소 가능 기간 초과, 금액 불일치, 인증 실패 등 4xx 계열 → 즉시 `FAILED` 확정 + `payment.refunded(FAILED)` 발행. **재시도는 상태를 바꾸지 못한다**
- **`ALREADY_CANCELED` 는 실패가 아니다** — 이전 호출이 성공하고 응답만 유실됐거나(crash matrix b) 외부에서 수동 취소된 경우이므로, **이미 목표 상태에 도달했을 가능성이 높다**. 즉시 `FAILED` 로 닫으면 실제로는 환불된 결제를 `APPROVED` 로 남기고 실패 이벤트를 발행하게 된다. 따라서 **PG 조회로 취소 금액을 확인**해 전액 취소면 `SUCCEEDED`(→ `payments.status=REFUNDED`), 금액 불일치면 `FAILED`, 조회 불가면 `UNRESOLVED` 로 분기한다. D3 의 "조회로 진실을 확정한다" 원칙이 여기에도 적용된다
- **DLQ 는 종결이 아니다** — 소비 자체가 실패해 DLQ 로 간 경우는 구현 ④ P9(DLQ 원장)의 입력이며, 원장에 남은 `REQUESTED`/`UNRESOLVED` 는 reconciliation 잡이 계속 본다. **두 경로 모두 "사람이 볼 수 있는 상태"로 끝난다**

### D6. 알림 · 관측 계약

- **운영 알림(Slack)**: 보조 신호. no-op 배포가 존재하므로 **어떤 종결 판정도 Slack 에 의존하지 않는다**
- **사용자 알림(Notification)**: `payment.refunded(SUCCEEDED)` 만 소비해 환불 완료를 알린다(`NotificationType.PAYMENT_REFUNDED` 신설). `FAILED`/`UNRESOLVED` 는 **사용자에게 알리지 않는다** — 내부 미결 상태를 사용자 문제로 전가하지 않기 위함이며, 운영이 해소한 뒤 성공 알림으로 수렴한다
- **메트릭**(소유 = payment-service, 태그 규약 = ADR-0015 `application=payment-service`):

| 메트릭 | 타입 | 태그 | 의미 |
|---|---|---|---|
| `payment.refund.requested` | Counter | `reason` | 환불 요청 수신(fence 획득분) |
| `payment.refund.result` | Counter | `result`(succeeded/failed) | 확정된 결과 |
| `payment.refund.retry.exhausted` | Counter | — | 재시도 소진 → `UNRESOLVED` 전이 |
| `payment.refund.backlog` | Gauge | `status`(requested/claimed/unresolved) | **미해결 원장 건수** |
| `payment.refund.oldest.age` | Gauge | `status` | **미해결 최장 age(초)** — 백로그가 늙고 있는지 |

backlog/age 게이지가 없으면 "미결로 남기지 않는다"는 계약을 **검증할 수단이 없다**. Slack 은 관측성의 대체물이 아니다.

## Alternatives Considered

### Alternative A: 공용 요청 토픽 1개 (`refund.requested`, producer 2곳)
- **장점**: 신규 토픽 2개(요청 1 + 회신 1)로 최소. 소비자는 1개만 구독
- **단점**: ADR-0012 D4 의 7개 토픽이 전부 지키는 **1 topic = 1 producer 패턴을 깨는 첫 사례**. `NewTopic` 프로비저닝 소유자(producer-owns-topic)를 임의 지정해야 하고, payload 스키마를 두 서비스가 공동 소유해 변경 조정 비용이 생긴다
- **기각 사유**: 절약되는 토픽 1개보다 **소유권 규약의 일관성**이 크다. 규약 예외는 그 자체로 후속 의사결정마다 재논쟁을 부른다

### Alternative B: Payment 로컬 시작 (Order 는 발행하지 않음)
- **장점**: Payment 가 이미 `order.cancelled` 를 소비하며 과금-후-취소를 감지하므로(C1 ③) 추가 토픽 없이 시작 가능
- **단점**: **Order 경로만 전달 보장을 잃는다.** Product·Payment 는 Outbox(at-least-once)로 보장되는데 Order 경로만 "Payment 의 소비가 성공해야" 시작된다 — 그 소비가 DLQ 로 빠지면 `order_compensations` 는 OPEN 인 채 **재트리거 수단이 없다**. 감지 3지점 ↔ 신호 경로 2종의 비대칭도 남는다
- **기각 사유**: R-2(원장이 OPEN 으로 쌓임)를 닫는 것이 이 계약의 목적인데, 그 경로가 유일하게 보장 없는 경로가 된다

### Alternative C: `PaymentStatus` enum 확장만 (별도 원장 없음)
비교축별 대조:

| 축 | enum 확장만 | **별도 `payment_refunds`(채택)** |
|---|---|---|
| 상태 격리 | `payments` 에 진행 상태 혼입 | 결제 종결 상태와 환불 진행 상태 분리 |
| 시도 이력 | 컬럼 덮어쓰기 → 1회분만 | `attempts`·`last_error` 누적 |
| 재시도/오류 저장 | 로그에만 | 원장에 영속 → D3 복구 근거 |
| PG 응답 감사 | 불가 | `pg_response` 보관 |
| 주문당 cardinality | 암묵 | `order_id` UNIQUE 로 **명시** |
| `@Version` ↔ fence | 낙관 락은 경합을 알려줄 뿐 유니크 fence 가 아님 | 유니크 제약이 곧 D3 fence |
| `APPROVED` 의미 호환 | 상태 폭증으로 기존 쿼리·테스트 영향 | `REFUNDED` 1개만 추가 |

- **기각 사유**: crash matrix(D3)의 복구가 **"과거 시도 기록"에 의존**하는데 enum 확장은 그 저장소를 제공하지 않는다. 무엇보다 fence 로 쓸 유니크 키가 생기지 않는다

### Alternative E: 종결 표면 — 회신 이벤트 대신 다른 방식 (D4 전용)

| 축 | ① **회신 이벤트(채택)** | ② 원장은 감사 기록, 종결은 Payment 상태로만 | ③ 운영자 수동 종결 |
|---|---|---|---|
| 전달 보장 | Outbox at-least-once | 없음(원장 소유자는 결과를 영영 모름) | 사람에 의존 |
| 원장 의미 | `OPEN`/`RESOLVED`/`REFUND_FAILED` 가 실제 상태와 일치 | Order 원장이 **영구 OPEN** — R-2 가 안 닫힘 | 지연·누락이 상태로 나타나지 않음 |
| 장애 복구 | 회신 유실 시 Payment 원장이 진실이라 재발행 가능 | Payment 만 알고 있어 교차 검증 불가 | 불가 |
| 운영 개입 | 예외 경로(`UNRESOLVED` 24h 초과)에만 필요 | 상시 필요(원장을 신뢰할 수 없음) | 상시 |
| 표면 비용 | 토픽 1 + 소비자 3 | 0 | 0 |

- **기각 사유 ②**: 이 계약의 목적 자체가 ④-a 의 R-2(원장이 OPEN 으로만 쌓임)를 닫는 것이다. 원장을 감사 기록으로 고정하면 목적이 달성되지 않는다
- **기각 사유 ③**: "미결로 남기지 않는다"(ADR-0012 D3 ④)를 사람의 성실성에 위임하는 것이며, Slack 이 no-op 인 배포에서는 통지조차 오지 않는다

### Alternative D: 기존 토픽 재사용 (`order.cancelled` 를 환불 트리거로)
- **장점**: 신규 토픽 0
- **기각 사유**: Product 의 `PAID_BUT_UNRESERVED` 는 **취소와 무관한 경로**에서도 발생한다(release 이후 `payment.completed` 도착). 취소 이벤트에 환불 의미를 겹치면 ④-b 가 막 확정한 "`order.cancelled` = 취소 lifecycle" 계약이 다시 흐려진다

## Consequences

### 긍정적 영향
- 세 감지 지점이 **하나의 실행자(Payment)와 하나의 원장**으로 수렴한다. "환불이 필요한데 아무도 모르는" 상태가 제거된다
- ④-a 의 **R-2(원장 OPEN 적체)가 닫힌다** — `payment.refunded` 소비로 `OPEN → RESOLVED`
- Payment 의 비영속 감지(C1 ③)가 영속 원장으로 승격돼, 소비 커밋 후 신호가 사라지는 구멍이 사라진다
- 미해결 backlog 가 **게이지로 노출**되어 "미결로 남기지 않는다"는 계약이 처음으로 검증 가능해진다

### 부정적 영향 / 트레이드오프
- **신규 토픽 3개 + DLQ 3개 = 6개**가 추가된다. 관측·운영 표면이 그만큼 늘고, `observability-*-lint` 의 기대집합도 갱신 대상이 된다
- **PG 조회(reconciliation) 잡이 필수 구성요소**가 된다 — 없으면 crash matrix a/b/c 가 전부 미복구로 남는다. 구현 부담이 D3 에서 실질적으로 커졌다
- **스케줄러가 2개 늘어난다**(dispatcher · reconciliation, 둘 다 `@SchedulerLock`). 소비 시점에 바로 환불하지 않으므로 **환불에 최대 1 dispatcher 주기의 지연**이 생긴다 — 즉시성을 포기하고 crash 안전성과 fence 무결성을 얻는 교환이다
- **수동 종결 경로가 계약에 포함**된다(`UNRESOLVED` 24h 초과). 자동화가 닿지 않는 구간이 있음을 인정하는 것이며, 그 구간을 **없다고 적지 않는 대신 감사 필드로 추적**한다
- `payments.status` 에 `REFUNDED` 가 추가돼 **`APPROVED` 를 종결로 가정한 기존 쿼리·테스트**를 점검해야 한다
- **검증 한계**: 실제 Toss 취소 API 는 승인된 실거래가 필요해 호출할 수 없다. 구현 ④-c-1 의 검증은 **클라이언트 계약 테스트 + 상태머신/fence/crash 복구 테스트**로 한정되며, 외부 PG 장애 주입은 **D-020 과 같은 게이트**(외부 PG 환경)에 묶인다. 이 한계를 "검증됨"으로 기록하지 않는다
- **잔여 위험**: PG 멱등키를 쓰더라도 "PG 가 성공했는데 우리 조회도 실패하는" 동시 장애 구간에서는 `UNRESOLVED` 가 사람 손을 요구한다. 이 계약은 그 구간을 **없애는 것이 아니라 보이게 만든다**

### 후속 결정에 미치는 영향
- **구현 ④-c-1**: Toss cancel/조회 클라이언트, `payment_refunds` Flyway, 트리거 발행 2경로 + Payment 로컬 1경로(+각 producer backfill), 소비/fence, **dispatcher(claim→PG 호출)**, **reconciliation 잡**, 회신 소비 **3곳(Order·Product·Notification)**, `NotificationType.PAYMENT_REFUNDED`, 메트릭 5종
- **구현 ④-c-2**(P9/P10): DLQ 원장은 본 계약의 `.dlq` 3개를 포함해야 한다
- **구현 ④-d**(P11/P12): 메트릭 lint 기대집합 갱신, E2E 에 환불 체인 포함
- **D-020**: 로컬에 흔적 없는 과금은 본 계약으로 발견 불가 — 웹훅/조회 reconciliation 은 여전히 별도 부채로 남는다

## ADR-0012 와의 관계 — 처분 판정

**판정: refine (ADR-0012 의 Status 를 변경하지 않는다).**

근거:
- ADR-0012 **D3 ④**는 최악 경로의 수렴처를 "**환불 요청 + 운영 알림**(`order.cancelled` 또는 환불 트리거)"으로 **이미 결정**했다. 본 ADR 은 그 "환불 트리거"의 실체(토픽·payload·상태머신·멱등)를 채우는 **구체화**이며, D3 ④의 결정을 뒤집지 않는다
- **D4 토픽 매트릭스는 확장**된다(3개 추가). 그러나 D4 가 정한 기존 7개 토픽의 producer/consumer/group/파티션 키는 **하나도 무효화되지 않으며**, 본 ADR 은 D4 가 세운 규약(`{svc}-svc-{topic}-group`, 파티션 키 = aggregate id, 1 topic = 1 producer)을 **그대로 따른다**. 기존 결정을 부정하지 않고 같은 규칙으로 항목을 더하는 것은 부분 무효화가 아니다
- ADR-0012 는 이미 **ADR-0016 이 `Partially Superseded`** 를 걸어둔 상태다(D1 예약 테이블 모델·D3 Payment 취소 테이블 범위). 본 ADR 이 건드리는 D3 ④(보상 수렴)·D4(토픽 목록)는 **ADR-0016 의 무효화 범위와 겹치지 않는다**

## References

- ADR-0010 §D2/D3 — 서비스 경계, saga 체인 골격
- ADR-0012 §D2(이벤트 스키마)·§D3 ④(보상 수렴)·§D4(토픽 매트릭스)·§D5(retention) — 본 ADR 이 구체화하는 대상
- ADR-0015 — per-service 관측 계약(`application=<svc>-service`)
- ADR-0016 — 예약/Payment 취소 테이블 모델(`payment_cancellations` = **승인 전** 취소 선도착 marker, 본 ADR 의 환불 원장과 다른 것)
- 코드: `StockReservationService.compensatePaidButUnreserved` · `OrderEventConsumer.recordPaidButCancelled` · `PaymentEventConsumer.handleOrderCancelled` · `TossPaymentClient` · `PaymentStatus`
- 계획서: `docs/plans/done/task-adr0018-compensation-refund-contract.md` · 구현 `docs/plans/done/task-impl4-choreography-saga.md` P8
- `docs/plans/PLAN-BLINDSPOTS.md` **B12** — 계약 검사와 fence 의 혼동(D3 의 축)
