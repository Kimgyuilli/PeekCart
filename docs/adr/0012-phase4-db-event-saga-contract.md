# ADR-0012: Phase 4 DB-per-service + 이벤트/Saga 계약

- **Status**: Partially Superseded by [ADR-0016](./0016-reservation-and-payment-table-model.md), [ADR-0020](./0020-dlq-replay-contract.md), [ADR-0034](./0034-approval-reservation-fence.md)
  - D3 재고 예약/승인 경계와 D4 payment.requested 구독은 ADR-0034의 APPROVAL_HELD 및 stock.approval.result 계약으로 확장·대체.

> **무효화 범위 (ADR-0016)**: 본 ADR 의 **D1 소유표 중 두 행**과 **D3 재고 예약 모델**이 구현 실제와 달라 ADR-0016 으로 재기록된다.
> - **D1 Product 행**: `inventories(+예약 컬럼, D3)` → 별도 **`stock_reservations`** 테이블(예약 원장)을 채택. inventories 에 예약 컬럼을 두지 않음.
> - **D1 Payment 행**: `payment_failures` 미구현 → 별도 목적의 **`payment_cancellations`**(취소 marker) 가 실재.
> - **D3 예약 모델**: "재검토" 대안(별도 reservation 테이블)이 strangler-1(#56)에서 채택됨.
> 그 외 본 ADR 의 결정(DB-per-service 경계 원칙·교차 FK 제거·이벤트/Saga 계약 D2/D4·retention floor D5)은 유효하다.

> **무효화 범위 (ADR-0020, DLQ replay 계약)**: **D1 Notification 행**과 **D4 producer 컬럼의 함의 일부**가 바뀐다.
> - **D1 Notification 행**: `processed_events` → **`processed_events, outbox_events`**. replay 재발행을 지휘하려면 Notification 도 outbox 를 소유해야 한다(ADR-0020 §D2).
> - **D4 producer 컬럼**: producer 지정은 두 가지를 함께 함의했다 — ① *이 토픽의 이벤트를 만드는 서비스* ② *이 토픽에 write 하는 유일한 서비스*. **①은 유효하고, ②만 무효화된다** — DLQ replay 는 원장 소유 서비스가 남의 토픽에 기록된 레코드를 재전달하므로 ②를 깬다(ADR-0020 §D8). 재전달의 fence(주체·목적지 토픽/파티션·byte-for-byte 동일·payload 변경 금지)는 ADR-0020 §D8-3 이 정한다.
> - **D5 는 무효화되지 않고 refine 된다**: "TTL 이후 수동 재처리는 새 `eventId` 발행" 대안은 **`replay_deadline` 밖 사건의 운영 우회로 유효하게 남되**, 그것은 ADR-0020 의 replay 가 아니며 원장 상태로 추적되지 않는다(ADR-0020 §D5-3).
> 그 외 D2·D3·D4 의 나머지 결정과 D5 의 retention floor 는 유효하다.

- **Decided**: 2026-06-14 (Proposed) → 2026-06-14 (Accepted) → 2026-06-22 (Partially Superseded by ADR-0016) → 2026-09-01 (Partially Superseded by ADR-0020: D1 Notification 행·D4 producer 함의 ②)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리)

## Context

ADR-0010(서비스 경계·토픽 4개·Saga 골격)·ADR-0011(모듈 구조·이벤트 DTO `common` 소유)이 **A3 로 위임한 데이터·이벤트 계약**을 닫는다. A1/A2 는 "이벤트 스키마는 A3 non-authoritative", "재고 예약 경계는 A3(F2)", "retention 은 A3" 로 미뤘다. 본 ADR 이 이를 확정해 구현 ②(DB 분리)·④(Saga)·⑤(CQRS)의 SSOT 가 된다.

### C1. 현 데이터/이벤트 상태 (감사 — 실제 인용)

- **Flyway**(`src/main/resources/db/migration/`): `V1__init_schema`, `V2__outbox_processed_events`, `V3__shedlock`, `V4__outbox_trace_context` — 단일 DB
- **Phase 4 ERD 초안**(`05-data-design.md §11 L174-382`): User/Product/Order/Payment/Notification DB 가 이미 그려짐. 단 **Product DB(L216-245)에 `outbox_events`/`processed_events` 부재**, `inventories`(L235-241)는 `stock`+`version` 만(예약 분리 없음)
- **이벤트 페이로드**(`global.outbox.dto`): `OrderCreatedPayload`(items 포함), **`OrderCancelledPayload`(orderId/orderNumber/userId — items 없음)**, `PaymentCompletedPayload`, `PaymentFailedPayload`, `KafkaEventEnvelope`(eventId/eventType/timestamp/payload — **버전 필드 없음**)
- **멱등성**(`04-design-deep-dive §9-7`): `processed_events` save-first + UK(event_id, consumer_group). **retention/cleanup 미정**(L-008/011)
- **DLQ**(`04 §64-70`): 확인 후 원본 토픽 재발행(수동 replay 창 존재)
- **consumer group**: `{svc}-svc-{topic}-group` 규약

### C2. 닫아야 할 갭 (ADR-0010 F2 + 본 감사)

- **F2**: 모놀리스는 주문 생성 시 Order 가 재고 차감 단일 트랜잭션(`04 §9-3 L104-106`). MSA 는 Product 가 재고 소유 → Order 직접 차감 불가
- `OrderCancelledPayload` items 부재 → `order.cancelled → Product 재고 복구`(ADR-0010 §5) 불가
- 재고 예약 실패를 Product 가 Order/Payment 로 알릴 발행 토픽이 ADR-0010 4토픽에 없음
- `product.updated`(CQRS, F3) 스키마 미정. envelope 버전·파티션 키 미정. retention 상한 미정

## Decision

### D1. DB-per-service 경계 (domain / infra 분리)

각 서비스가 자기 DB 를 단독 소유한다. 교차 FK 제거 → ID 참조 + 이벤트/로컬 캐시로 대체. Flyway 는 **서비스별 독립 마이그레이션 이력**(`db/migration/{service}/`).

| 서비스 | domain 테이블 | infra 테이블 |
|---|---|---|
| User | users, refresh_tokens, addresses | — |
| Product | products, categories, **inventories(+예약 컬럼, D3)** | **outbox_events, processed_events** (신규 — Product 가 producer/consumer) |
| Order | orders, order_items, carts, cart_items | outbox_events, processed_events |
| Payment | payments, payment_failures, webhook_logs | outbox_events, processed_events |
| Notification | notifications | processed_events, **outbox_events** (ADR-0020 D2 — replay 재발행 주체, Update Log 2026-09-02) |

> `05-data-design.md §11` Product DB 에 `outbox_events`/`processed_events` 추가 필요(Layer 1 정정). DDL 은 구현 ②.

### D2. 이벤트 스키마 계약

- **envelope 버저닝**: `KafkaEventEnvelope` 에 `schemaVersion`(int) 추가. 호환성 규칙 = **하위호환 필드 추가만 허용**, 필드 삭제·의미 변경 금지(필요 시 새 eventType/major version)
- **파티션 키 = aggregate id**(순서 보장 단위): `order.*` → `orderId`, `payment.*` → `orderId`(주문 단위 순서), `product.updated` → `productId`, `stock.reservation.result`(D3) → `orderId`
- **`product.updated` payload (필수 필드)**: `productId, name, price, availableStock, status(ACTIVE/INACTIVE/SOLD_OUT), categoryId, updatedAt` — Order 로컬 캐시(실제 테이블명 `product_price_cache`)와 장바구니 조회(`05 §249-250`)를 **모두 담을 수 있도록** 필드를 정한다. 삭제/품절은 status 로 표현. *(구현 ⑤ 시점 실제: Order 는 `price`/`version` 만 적재하며 장바구니 상품정보 조합은 미구현 — Update Log 참고)*
- **`OrderCancelledPayload` 보강**: 복구 대상 `items[](productId, quantity)` 추가(현 부재 갭 해소)
- **`stock.reservation.result` payload (신규, 필수 필드)**: `orderId`, `reserved`(bool), `items[](productId, quantity)`, `reason`(예약 실패 사유 — `OUT_OF_STOCK`/`PARTIAL`/`TIMEOUT` 등, 성공 시 null), `decidedAt`. 파티션 키 `orderId`(D4)
- 스키마 필드 DDL/코드 확정은 구현 ②/④/⑤, 본 ADR 은 필드 계약 확정

### D3. Saga 재고 예약/차감 경계 (F2 해소) — choreography

Product 가 재고 소유자이므로 Order 는 차감하지 않고 **Product 가 예약(reserve)** 한다. `inventories` 에 예약 개념 도입(available/reserved 또는 reservation 상태 — 모델 구현은 ②).

**예약 실패 신호 경로: 옵션 B 채택 — 신규 토픽 `stock.reservation.result`** (Product 발행 → Order/Payment 소비). 이유: Product 가 예약 결과를 알릴 발행 토픽이 4토픽에 없고(`order.cancelled` 는 Order 소유), Payment 가 예약 확정 전 결제하는 것을 막으려면 결과 이벤트가 필요(P1#3). 옵션 A(4토픽 유지)는 Product→Order 실패 신호 불가로 기각.

choreography 단계:
1. `order.created`(Order 발행) → **Product** 소비 → 재고 예약 시도
2. Product → `stock.reservation.result`(reserved: true/false, items) 발행
3. `reserved=true` → **Payment** 소비 → Toss 결제 / `reserved=false` → **Order** 소비 → 주문 취소 → `order.cancelled`
4. `payment.completed` → Product 소비 → 예약 확정(commit) / `payment.failed`·`order.cancelled` → Product 소비 → 예약 해제·복구(release)

**실패 경로 계약**: ① 결제는 `reserved=true` 수신 후에만 시작(순서 보장) ② 부분 품목 예약 실패 = all-or-nothing(일부 실패 시 전체 예약 롤백 + reserved=false) ③ 예약 타임아웃 → 미확정 예약 만료 복구(스케줄러, 구현 ④) ④ **`payment.completed` 후 예약 commit 실패(최악 경로 — 결제 승인됐으나 재고 미확정)**: Product commit 은 idempotent 재시도 가능 operation 으로 두고, 재시도 한계 초과 시 **보상 경로 = 환불 요청 + 운영 알림**(`order.cancelled` 또는 환불 트리거)으로 수렴. 종료 상태를 미결로 남기지 않음. eventId(`payment.completed` 의 event_id) 기준 `processed_events` 멱등 ⑤ 수동 취소 ↔ `payment.failed` 동시 → 예약 해제는 `processed_events` 멱등으로 1회성

### D4. 토픽 × producer × consumer × consumer group 매트릭스

| 토픽 | producer | consumer | consumer group | 파티션 키 |
|---|---|---|---|---|
| `order.created` | Order | Product, Payment, Notification | `product-svc-order-created-group`, `payment-svc-order-created-group`, `notification-svc-order-created-group` | orderId |
| `stock.reservation.result` (신규) | Product | Order, Payment | `order-svc-stock-result-group`, `payment-svc-stock-result-group` | orderId |
| `payment.requested` (신규, Order↔Payment 디커플) | Payment | Order | `order-svc-payment-requested-group` | orderId |
| `payment.completed` | Payment | Order, Product, Notification | `order-svc-payment-completed-group`, `product-svc-payment-completed-group`, `notification-svc-payment-completed-group` | orderId |
| `payment.failed` | Payment | Order, Product, Notification | `order-svc-payment-failed-group`, `product-svc-payment-failed-group`, `notification-svc-payment-failed-group` | orderId |
| `order.cancelled` | Order | Product, Payment, Notification | `product-svc-order-cancelled-group`, `payment-svc-order-cancelled-group`, `notification-svc-order-cancelled-group` | orderId |
| `product.updated` (신규, CQRS) | Product | Order | `order-svc-product-updated-group` | productId |

> **ADR-0010 토폴로지 refine**: ADR-0010 §D2 는 Product 가 `order.cancelled` 만 소비했으나, 재고 예약 모델상 Product 가 `order.created`/`payment.completed`/`payment.failed` 도 소비하고 `stock.reservation.result`/`product.updated` 를 발행한다. 이는 ADR-0010 의 Saga 골격을 구체화한 refine 이며 모순이 아니다(Layer 1 `02 §5` 토폴로지 갱신). consumer group 라벨은 ADR-0009 Kafka lag surface(L-020-2) 로 독립 관측.
>
> **Order↔Payment 동기 결합 제거 refine (2026-06-18, peel 선행 strangler)**: Payment 가 Order 의 동기 인-프로세스 빈 `OrderPort`(`verifyOrderOwner`·`transitionToPaymentRequested`)에 묶여 독립 peel 불가였다 → 두 호출을 payment-로컬 상태 + 이벤트로 대체한다.
> - **7번째 토픽 `payment.requested`**(Payment 발행 → Order 소비): 동기 `transitionToPaymentRequested` 대체. Order 는 이를 소비해 `PAYMENT_REQUESTED` 전이(`paymentRequestedAt` 앵커). 예약 미확정 선도착 시 ORD-008 DLQ 직행 대신 order 로컬 pending marker 로 `confirmReservation()` 시점 수렴.
> - **ORD-008 reserve→pay 게이트는 불변**: 동기 `markPaymentRequested`(PENDING + `reservationConfirmedAt!=null`) 게이트를 payment-로컬로 복원 — Payment 가 `stock.reservation.result(reserved=true)` 소비로 `ready_for_payment` 표시(이 행은 본 ADR 이 이미 Payment consumer 로 둠), `order.cancelled` 소비로 취소 게이트. reserve→pay 순서(§D3 ①) 보존.
> - **잔여 race**: 게이트 통과↔Toss 사이 취소 커밋은 `@Version` 으로 last-write-wins 차단하되 과금-후-취소(APPROVED) 는 덮지 않고 §D3 ④ 환불+운영 알림으로 수렴. 새 ADR 불요(GP-1, 본 refine 으로 수렴). `02 §5` 토픽 수 6→7 갱신은 Layer 1 동기화로 처리.
> - **D4 정합 보정**: 기존 표가 `order.created` consumer 에 Payment(`payment-svc-order-created-group`, Payment(PENDING) 생성)를 누락하고 있던 드리프트를 함께 정정.

### D5. retention = 멱등성 창 상한 (L-008/011)

- `processed_events` 보존기간 ≥ **max(Kafka topic retention, 최대 consumer 다운타임, DLQ 수동 재처리 허용 창(`04 §64-70`), 운영 backfill/replay 창)**. 이보다 짧으면 DLQ 재발행/replay 시 동일 eventId 중복 처리 → 멱등성 깨짐
- `outbox_events`: PUBLISHED 후 보존 N일 후 삭제(감사 vs 용량). cleanup = ShedLock 기반 스케줄러(구현 ②)
- 대안: TTL 이후 수동 재처리는 새 eventId 발행 또는 운영자 중복 확인 절차

## Alternatives Considered

### 재고 예약 실패 신호 — Alt A: 4토픽 유지 vs **Alt B: `stock.reservation.result` 추가 (채택)**
- A 장점: 토픽 최소. 단점: Product 가 Order/Payment 로 예약 결과를 알릴 발행 토픽 부재 → Payment 가 예약 전 결제하는 race 차단 불가
- B 채택: 결과 이벤트로 순서(예약→결제) 보장 + 실패 시 명확한 취소 경로. 토픽 1개 추가 비용 수용

### 재고 예약 모델 — available/reserved 컬럼 vs 별도 reservation 테이블
- 컬럼 방식 채택 방향(단순), 상세는 구현 ④. 별도 테이블은 예약 이력 추적 필요 시 재검토

### 스키마 버저닝 — envelope `schemaVersion` (채택) vs schema registry(Avro)
- registry 는 Phase 4 초기 부담 과다 → 규칙 먼저, 도구는 Phase 5+ 후보

## Consequences

### 긍정적 영향
- 구현 ②/④/⑤ 의 SSOT — DB 경계·이벤트 스키마·Saga 흐름·retention 이 1:1 도출
- F2/`OrderCancelled` items/예약 실패 신호 갭 해소
- 파티션 키 = orderId 로 주문 단위 이벤트 순서 보장

### 부정적 영향 / 트레이드오프
- 결과적 일관성 — 예약/결제/취소가 이벤트로 분산, 일시적 불일치 가능(타임아웃 복구로 수렴)
- 토픽 6개 + Product 가 consumer/producer 양쪽 → 운영·관측 표면 증가
- 예약 상태 관리 복잡도(타임아웃·commit 실패·동시성)

### 후속 결정에 미치는 영향
- **구현 ②**: Flyway 서비스별 분리, Product outbox/processed + inventories 예약 컬럼, 교차 FK 제거
- **구현 ④**: 예약/확정/복구 consumer + `stock.reservation.result` + `OrderCancelledPayload` 보강 + 타임아웃 스케줄러
- **구현 ⑤**: `product.updated` 발행/소비 + 로컬 캐시(`product_price_cache`)

## References
- ADR-0008(Outbox trace context), ADR-0009(Kafka lag surface), ADR-0010(경계·토폴로지·F2/F3), ADR-0011(이벤트 DTO common 소유)
- `docs/05-data-design.md §11`(Phase 4 ERD), `docs/04-design-deep-dive.md`(§9-3 재고차감, §9-7 멱등성, §64-70 DLQ, §16 CQRS), `docs/03-requirements.md §7-2`
- 코드: `global.outbox.dto.*`, `KafkaConfig`, `src/main/resources/db/migration/V1~V4`

## Update Log

### 2026-08-30 — 구현 ⑤ 종결 시점 사실 정정 (`fix(adr):`)

**변경 사유**: 구현 ⑤ 착수 전 코드 검증에서 본 ADR 의 두 사실 진술이 실제 구현과 어긋난 것이 확인됐다. 결정 내용(이벤트로 동기 호출을 대체한다 · `product.updated` 7 필수필드 · 파티션 키)은 바뀌지 않았고, 어긋난 것은 **테이블명과 소비 범위 서술**이다 — README §원칙의 "사실 오류(파일명·Phase 귀속·수치) 정정" 경로를 사용한다. 트레이드오프·Consequences 는 손대지 않았다.

| 위치 | 기존 | 정정 |
|---|---|---|
| §D2 `product.updated` payload | "Order `product_cache`/장바구니 조회 충족" | 테이블명을 실제(`product_price_cache`)로 고치고, 필드 집합이 두 용도를 **담을 수 있게** 정해졌다는 취지임을 명시. 구현 ⑤ 시점의 실제 소비 범위(`price`/`version` 만 적재, 장바구니 조합 미구현)를 괄호로 병기 |
| §후속 결정에 미치는 영향 · 구현 ⑤ | "`product_cache`" | "로컬 캐시(`product_price_cache`)" |

**바뀌지 않은 것**: 7 필수필드는 실제로 전량 발행된다(`ProductUpdatedPayload`). 소비 측이 일부만 적재할 뿐이므로 payload 계약 자체는 유효하다. 장바구니 상품정보 조합은 별도 task 로 분리됐다(진행 이력은 `docs/progress/PHASE4.md`).

**커밋**: `9f74bd6` — `fix(adr): ADR-0012 §D2·구현⑤ 산출물의 사실 정정 + Update Log (P11)`
(해시는 자기 자신을 참조할 수 없어 후속 커밋 `7a7b719` 에서 채웠다 — diff 리뷰 1R #3)

### 2026-09-02 — Notification `outbox_events` 신설 반영 (`fix(adr):`)

**변경 사유**: ADR-0020 §D2 가 notification-service 에 `outbox_events` 를 신설하기로 결정했고(본 ADR §D1 의
Notification 행을 개정하도록 명시), 구현 ④-c-2b-2 P9 가 그 테이블·poller·ShedLock 잡을 실제로 만들었다.
표를 그대로 두면 **ADR 이 거짓을 말하는 구간**이 후속 3개 PR 동안 이어진다.

| 위치 | 기존 | 정정 |
|---|---|---|
| §D1 표 Notification 행 · infra 테이블 | `processed_events` | `processed_events, outbox_events` |

**왜 소비 전용 서비스가 outbox 를 갖게 됐나**: DLQ replay 는 **원장 소유 서비스가 자기 원장 행을 재발행**하는
것이다(ADR-0020 §D8-3 fence). notification 도 자기 DLQ 원장을 가지므로 재발행 주체가 되고, 재발행은 별도
테이블이 아니라 **기존 outbox 경로**를 탄다(§D3 — `replay_outbox` 분리는 Alternative D 로 기각됐다).

**바뀌지 않은 것**: DB-per-service 경계, 교차 FK 금지, 이벤트/Saga 계약. notification 은 여전히 **도메인
이벤트를 발행하지 않는다** — 이 테이블에 들어가는 것은 replay 재발행뿐이다. 본 ADR §D4 의 producer 컬럼이
함의하던 "이 토픽에 write 하는 유일한 서비스" 는 ADR-0020 §D8-4 가 이미 부분 무효화했다.

**커밋**: 구현 ④-c-2b-2 (`fix(adr):` — 해시는 자기 자신을 참조할 수 없어 후속 커밋에서 채운다)
