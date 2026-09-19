# 구현 ④ Choreography Saga — 잔여 수렴 갭 종결

> 선행 ADR: ADR-0012(D2 이벤트 스키마 · D3 Saga 재고 예약/차감 경계 · D4 refine · D5 retention) · ADR-0010(F2·D4) · ADR-0016
> 편입 부채: L-013(주문 상태 전이 동시성 — 게이트 실측 후 승격 판단)
> 상태: 계획 수립 (2026-08-13) · GW-1 리뷰 12건 전량 반영

---

## 1. 목표

구현 ④ 의 실질 잔여는 "saga 를 새로 만드는 것"이 아니라 **이미 동작하는 saga 가 미결 종료 상태를 남기는 구멍을 닫는 것**이다. strangler-1~5(#56·#57·#58·#61·#63)에서 예약/확정/복구/보상 골격이 선구현됐고, 본 작업은 그 위에 남은 수렴 갭만 좁힌다.

완료 시 만족해야 할 명제: **모든 saga 인스턴스는 유한 시간 안에 종료 상태(CONFIRMED / RELEASED / FAILED / 환불완료)에 도달하거나, 도달 실패가 관측 가능한 신호 + 처리 가능한 원장으로 드러난다.**

"관측 가능"의 하한은 §5 가 정의한다 — 로그/알림만으로는 종료 상태로 치지 않는다.

---

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (grep, 2026-08-13) — ADR 문구가 아니라 현재 코드 기준

ADR-0012 §Consequences 는 구현 ④ 를 "예약/확정/복구 consumer + `stock.reservation.result` + `OrderCancelledPayload` 보강 + 타임아웃 스케줄러" 로 지정했다. 실제 코드 대조 결과:

| ADR-0012 ④ 산출물 | 코드 상태 | 근거 (파일) |
|---|---|---|
| 예약/확정/복구 consumer | ✅ 완료 | `StockReservationService`(reserve/release/confirm, CAS 3종) · `StockConfirmConsumer` · `StockReleaseConsumer` · `OrderEventConsumer`(4토픽) |
| `stock.reservation.result` | ✅ 완료 | `StockReservationResultPayload` · `ProductKafkaConfig#stockReservationResultTopic`(+`.dlq`) · order/payment 양측 소비 |
| `OrderCancelledPayload` 보강 | ❌ 미이행 | `(orderId, orderNumber, userId)` 3필드 — ADR-0012 D2 가 요구한 `items[]` 부재 |
| 예약 타임아웃 복구 스케줄러 | ⚠️ 부분 | order 측 `OrderTimeoutScheduler` 2잡 존재 · **product 예약 원장 측 sweeper 부재** · **두 잡 모두 커버 못 하는 구간 존재(§2.3-A)** |

D3 실패경로 ①~⑤ 대조: ① 순서 보장 ✅ · ② all-or-nothing ✅ · ③ 타임아웃 ⚠️(§2.3-A) · ④ **미완** · ⑤ 멱등 ✅(`processed_events`).

> **GW-1 #1 정정** — 초안은 D3 ④(PAID_BUT_UNRESERVED)를 완료로 판정했으나 오판이다. `StockReservationService#compensatePaidButUnreserved` 는 `markCompensatedIfAbsent` marker + Slack 알림까지만 하고 **자동 환불 플로우가 없다**(코드 주석이 "수동 환불로 수렴"이라 자인). ADR-0012 D3 ④ 의 계약은 "**환불 요청** + 운영 알림"이므로 환불 요청 경로가 빠진 현재 상태는 §1 명제를 만족하지 못한다 → **P8 로 승격**.

### 2.2 ADR 항목 밖에서 발견한 수렴 갭

1. **DLQ 재소비/영구 원장 0건** — `.dlq` 를 읽는 운영 `@KafkaListener` 가 0건이다(테스트 listener 1건: `DlqIntegrationTest`). **단, 라우팅 시점 관측은 이미 존재한다** — 각 서비스 `kafkaErrorHandler` 가 DLQ 발행과 함께 `log.error` + `SlackPort` 알림을 수행한다. DLQ 발행 서비스는 order·product·payment·**notification 4곳**이다. 따라서 정확한 갭은 "아무도 보지 않는다"가 아니라 **재처리 가능한 영구 원장과 처리 상태 추적이 없다**는 것 — 알림은 휘발성이라 미결 건의 잔존 여부를 판정할 수 없다. (GW-1 #6 정정)
2. **saga 메트릭 0건** — `product-service`/`order-service` main 에 saga 관련 `Counter`/`Timer` 없음(outbox·cache 메트릭만 존재). 예약 실패율·보상 발생·타임아웃 취소가 관측 표면에 없다.
3. **`Order` 엔티티 `@Version` 부재** — `Payment`·`Product`·`Inventory` 는 보유하나 `Order` 는 없다. TASKS 보류 항목 **L-013** 이 이 표면.
4. **`payment.failed` 취소가 `order.cancelled` 를 발행하지 않는다** — `OrderEventConsumer#handlePaymentFailed` 는 `order.cancel()` 만 호출한다. Product 는 `payment.failed` 를 직접 소비해 release 하므로 재고는 복구되지만, `order.cancelled` 소비자인 **notification·payment 는 결제 실패발 취소를 통지받지 못한다**. (GW-1 #5)

### 2.3 제약 / 트레이드오프

**A. 예약 만료는 고정 TTL sweeper 로 풀 수 없다 (GW-1 #2, P0).**
초안은 `reservedAt + 30분` sweeper 를 제안했으나 이는 **oversell 을 만든다**. `OrderJpaRepository` 확인 결과:
- `findExpiredPaymentRequested` → `PAYMENT_REQUESTED` 만 대상(15분)
- `findUnconfirmedReservationBefore` → `PENDING AND reservationConfirmedAt IS NULL` 만 대상(5분)

즉 **예약은 확정됐으나 결제를 시작하지 않은 `PENDING` 주문은 수명 상한이 없다.** 여기에 고정 TTL sweeper 를 걸면 살아있는 주문의 재고가 해제되고, `Payment` 는 여전히 승인 가능한 상태라 **결제 승인 + 재고 재판매**가 동시에 성립한다.

→ **명시적 lease 계약**으로 전환한다(P4). 만료 시각을 Product 가 혼자 추측하지 않고 saga 참여자가 공유하며, 만료 이후에는 Payment 가 승인을 거부한다. sweeper 는 그 lease 를 근거로만 회수한다.

**B. DLQ 소비는 자동 재처리하지 않는다.** 자동 재발행은 원인이 영구적일 때 무한 순환이 된다. `docs/04-design-deep-dive.md:445-466` 의 기존 결정과도 정합. 재처리는 원장 + runbook 기반 수동 수렴(P10).

**C. DLQ 는 공유 토폴로지다 (GW-1 #7).** 한 원본 토픽을 여러 consumer group 이 소비하고 실패는 모두 같은 `topic.dlq` 로 간다. 원장 식별자에 **실패한 consumer group** 이 없으면 서비스 간 중복 기록·중복 알림이 발생한다. 또 DLQ listener 가 기본 error handler 를 쓰면 실패 시 `topic.dlq.dlq` 로 재귀한다 → DLQ 전용 container factory 필요.

**D. L-013 게이트 존중.** 실측(P1) 없이 `@Version` 을 먼저 넣지 않는다. 단 재현 테스트는 **barrier/latch 로 두 트랜잭션이 같은 버전을 읽도록 강제한 결정적 테스트**여야 한다 — 확률적 재현의 음성은 기각 근거가 못 된다(GW-1 #10).

**E. `@Version` 만으로는 수렴 규칙이 서지 않는다 (GW-1 #10).** 낙관 락은 "먼저 커밋한 쪽이 이긴다"일 뿐이다. 결제 consumer 가 지면 재조회 시 `CANCELLED` 를 읽고 `transitionTo(PAYMENT_COMPLETED)` 가 `ORD-003` 으로 실패해 DLQ 로 간다 → **취소 선커밋 / 결제 선커밋 양 순서 모두**에서 "결제 완료 또는 환불 보상"으로 수렴함을 정의·검증해야 한다.

**F. 관측성 SSOT.** saga 메트릭 추가는 ADR-0015 per-service 계약 표면을 건드린다. 본 작업은 **메트릭 노출 + 기존 observability lint 정합**까지 책임지고, alert/dashboard 계약 변경이 필요한 부분은 PR4(관측성 S9)로 넘긴다.

### 2.4 범위 밖 — 신규 부채로 등재 (GW-1 #3)

**결제 승인 ↔ 로컬 커밋 불일치 (D-020 신설)**: `PaymentCommandService` 는 DB 트랜잭션 안에서 Toss 승인을 호출하고 그 뒤 Payment 상태·Outbox 를 저장한다. 승인 성공 후 커밋이 실패하면 **외부 과금은 남고 로컬은 롤백**된다. `WebhookService` 는 웹훅을 로그로만 적재하고 상태 복구를 하지 않는다.

지적은 타당하나 이는 **choreography saga 가 아니라 PG reconciliation** 표면이다(Toss idempotency key · 웹훅 기반 상태 동기화 · 장애 주입 환경). ④ 에 넣으면 범위가 다시 부풀고 외부 의존이 붙는다 → **D-020 으로 TASKS 에 등재하고 ④ 범위 밖임을 명시**한다(P13). §1 명제는 "Kafka 이벤트 체인" 범위로 한정하며, 이 한정을 문서에 남긴다.

### 2.6 잔여 위험 (④-a 구현 후 · GW-2 리뷰 반영)

**R-1. 승인 ↔ 회수 사이에 fence 가 없다 (미해소, 창만 축소).**
초안은 P4 로 oversell 이 닫힌다고 적었으나 **틀렸다**. `PaymentCommandService.confirmPayment` 는 `ensureConfirmable()` 이후 **같은 트랜잭션 안에서** PG 를 호출하고, 그 시점 주문은 대개 아직 `PENDING` 이다(`payment.requested` 는 outbox→poller→Kafka 를 거쳐야 `PAYMENT_REQUESTED` 가 된다). 따라서 검사 통과 → lease 만료 잡이 주문 취소 → Product release + 재고 복구 → 타 주문이 재예약 → PG 승인 성공, 이 순서가 성립한다.

- **④-a 의 조치**: PG 호출 전 "남은 lease > `app.payment.lease-approval-margin`(기본 2분)" 을 요구해 경합 창을 마진 이내로 줄였다. 이는 **완화이지 제거가 아니다** — PG 호출이 마진을 초과하면 창은 다시 열린다.
- **근본 해결**: 예약을 승인 전용 상태(`PAYMENT_IN_PROGRESS` 등)로 CAS 전이하고 release/sweeper 가 그 상태를 임의 회수하지 못하게 하는 fence. saga 프로토콜에 라운드트립을 추가하므로 **ADR-0012 D3 재기록(새 ADR)** 이 선행돼야 한다 → **④-a 범위 밖, 별도 PR**.
- **판정**: 계획 §1 명제는 이 경로에 한해 **미달성**이며, 그 사실을 여기에 명시한다.

**R-2. ④-a 는 단독 배포 시 환불이 자동으로 일어나지 않는다.**
`PAID_BUT_CANCELLED` 는 `order_compensations` 원장에 `OPEN` 으로 남을 뿐 환불 요청은 P8(④-c) 소관이다. ④-a 만 배포하면 원장이 쌓이고 사람이 처리해야 한다 → **배포 의존성: ④-a 이후 ④-c 를 같은 릴리스 주기에 배포**한다. (알림에 의존하지 않는 이유는 order-service `SlackPort` 가 배포 구성상 no-op 이기 때문이다.)

**R-3. 롤아웃 창의 구 메시지.**
배포 시점의 in-flight 행은 마이그레이션 backfill 로 lease 를 소급 부여했다(orders/stock_reservations/payments). 다만 롤아웃 중 구 Product 가 발행한 `stock.reservation.result`(lease 필드 부재)를 신 Order/Payment 가 소비하면 그 건은 lease 없이 남는다 — 만료 판정에서 제외되므로 기존 동작으로 되돌아간다(무한 상한). 롤아웃 창에 한정되며, Product 를 먼저 배포하면 창이 닫힌다.

### 2.5 구조 변경 여부 (GP-1 재판단)

모듈/경계 이동·peel·rename 없음. 새 외부 의존성·신규 인프라 없음(기존 Kafka·Micrometer·ShedLock 재사용) → `PLAN-BLINDSPOTS.md` B1 역의존 스윕 대상 아님.

**새 ADR 필요성**: P4 lease 계약과 P5 `items[]` 는 ADR-0012 D2 의 "하위호환 필드 추가만 허용" 범위 안이고, D3 가 "예약 타임아웃 → 만료 복구"를 구현 ④ 로 위임했으므로 그 위임 범위 내 refine 으로 본다(strangler-5 가 D4 를 새 ADR 없이 refine 한 전례, PR #63). **단 /work 중 "Payment 가 만료 lease 로 승인을 거부한다"가 D3 위임 밖의 결제 정책 변경으로 번지면 그 시점에 ADR 승격**한다.

---

## 3. 작업 항목

### L-013 게이트 (실측 선행)

- [ ] **P1.** `payment.completed` 소비 ↔ `OrderTimeoutScheduler` 취소의 상태 모순을 **결정적으로** 실측한다. barrier/latch 로 두 트랜잭션이 동일 `Order` 버전을 읽도록 강제하고, 취소 선커밋·결제 선커밋 양 순서를 각각 재현한다. 결과(최종 상태·모순 여부)를 §5 표에 기록하고 **양성이면 P2 승격, 음성이면 근거와 함께 기각**한다. 확률적 미재현은 기각 근거로 쓰지 않는다.
- [ ] **P2.** (P1 양성 조건부) `Order` `@Version` + Flyway `orders.version` + **낙관 락 충돌 정책**. 충돌 예외별 처리(재조회 후 상태 재판단 / 재시도 포기)를 명시하고, 결제 consumer 가 충돌에서 진 뒤 `CANCELLED` 를 읽었을 때 `ORD-003` 으로 DLQ 에 빠지지 않고 **환불 보상 경로로 수렴**하게 한다(P8 과 접속). L-013 을 TASKS 보류에서 해소로 이동.

### 예약 lease — oversell 차단

- [ ] **P3.** 예약 확정 후 결제 미시작 `PENDING` 주문의 **수명 상한 부재**를 닫는다. order 측 만료 판정에 이 구간을 포함시키고, 만료 시 `order.cancelled`(reason=`TIMEOUT`) 를 발행해 기존 release saga 로 회수되게 한다.
- [ ] **P4.** **예약 lease 계약** 도입 — `stock.reservation.result` 에 `reservationExpiresAt` 을 실어 Order·Payment·Product 가 만료 시각을 공유한다. Payment 는 **만료된 lease 에 대한 결제 승인을 거부**하고, Product sweeper 는 `reservedAt` 고정 TTL 이 아니라 **lease 만료 + 원장 `RESERVED`** 를 근거로만 회수한다(`@SchedulerLock` 필수, 복구는 기존 `markReleasedIfReserved` CAS 재사용). 정상 흐름에서 sweeper 회수 건수는 0 이어야 하며, 0 이 아니면 그 자체가 알림 대상이다. **만료 ↔ 결제 승인 경합을 양 순서 모두 검증**한다.

### 이벤트 계약 (ADR-0012 D2 이행)

- [ ] **P5.** `OrderCancelledPayload` 에 **`items[](productId, quantity)` 추가** — ADR-0012 D2 의 명문 결정이며 ADR-0016 이 유효로 유지한 계약이다. 원장 기반 release 라 Product 에 당장 불필요하다는 이유로 생략하지 않는다(구현 편의는 ADR 변경 근거가 아니다).
- [ ] **P6.** `OrderCancelledPayload` 에 취소 사유(`reason` enum: 사용자 취소 / 예약 실패 / 결제 실패 / 타임아웃) 추가 + **각 취소 진입점이 사유를 명시적으로 전달**하도록 `publishOrderCancelled` API 변경. 소비자 3곳(product·notification·payment)이 구 메시지(필드 부재)에도 깨지지 않아야 한다(하위 호환).
- [ ] **P7.** `payment.failed` 취소 경로가 `order.cancelled` 를 발행할지 **먼저 결정**한다(ADR-0010 D3 · Product 의 중복 release 의미 검토). 발행하기로 하면 동일 트랜잭션에서 Outbox 가 생성되는지 검증하고, 발행하지 않기로 하면 notification·payment 가 결제 실패발 취소를 어떻게 인지하는지 대안을 명시한다.

### 미결 종료 상태 제거

- [x] **P8.** **PAID_BUT_UNRESERVED 환불 요청 경로** 신설(ADR-0012 D3 ④ 이행) — **선행 ADR-0018**([#86](https://github.com/Kimgyuilli/PeakCart/pull/86), Accepted)이 보상/환불 트리거 계약을 확정했고, 구현은 자식 계획서 `task-impl4-c1-refund-path.md`(④-c-1a/1b)가 수행한다. **감지는 3지점**(Product marker · Order 원장 · Payment 로컬)이며 P8 초안이 지목한 Product 단독 범위보다 넓다 — Payment 소유의 환불 요청 상태/Outbox, `orderId` 또는 `paymentKey` 기반 멱등성, 성공·실패 종결 상태, 재시도 소진 시 운영 처리까지 정의한다. 현행 marker + Slack 은 종료 상태가 아니다.
- [ ] **P9.** **DLQ 원장 + 전용 소비 경로** — 원장 식별자는 `originalTopic + eventId + failedConsumerGroup`(공유 DLQ 토폴로지, §2.3-C). DLQ listener 는 **재-DLQ 하지 않는 전용 container factory/error handler** 를 쓰고, 영속 실패 시 fallback 을 정의한다. 자동 재발행 금지. 알림은 기존 PAID_BUT_UNRESERVED 와 동일하게 멱등.
- [ ] **P10.** **DLQ runbook** — 원장 상태머신(`OPEN`/`ACKED`/`REPLAYED`/`RESOLVED`) + 감사 필드, 7일(ADR-0012 D5 `processed_events` retention) 이내 동일 `eventId` 재발행 규칙, 창 초과 시 새 `eventId` + 중복 확인 절차, 담당자·SLA. 문서 경로를 §4 에 고정한다.

### 관측 / 검증 / 문서

- [ ] **P11.** saga 메트릭 노출 — 예약 성공/실패, 확정, 복구, 보상, 환불 요청, 타임아웃 취소, lease sweeper 회수, DLQ 유입. 태그·명명은 ADR-0015 per-service 규약(`<svc>-service`), 기존 observability lint 그린.
- [ ] **P12.** **cross-service saga E2E** — Payment·Order·Product 를 Kafka + 각자 DB 에 연결해 실제 플로우를 검증한다. `payment.failed` 발행 후 제한 시간 내 `Order=CANCELLED` · `StockReservation=RELEASED` · `Inventory` 원복 · 관련 Outbox `PUBLISHED` · `processed_events` 기록을 확인한다. **환불 체인도 같은 방식으로 포함한다**(④-c-1b 등재): Product/Order 트리거 발행 → Payment fence(`payment_refunds` 1행) → dispatcher → `payment.refunded` → Order `RESOLVED`/`REFUND_FAILED` · Product 종결 컬럼 · Notification 성공분 1건. **"이벤트 계약 수준 검증으로 대체 가능" 문구는 폐기** — DTO/consumer 단위 계약은 Kafka 전달·Outbox·DB 트랜잭션·재시도를 함께 증명하지 못하며 `07 §110-115`/ADR-0010 D4 의 Exit Criteria 를 닫지 못한다(GW-1 #9).
- [ ] **P13.** 마이그레이션·인덱스 확정 — DLQ 원장 테이블/엔티티/repository/Flyway(서비스별, unique key 포함) · `stock_reservations` 에 `(status, reserved_at)` 인덱스(현재 부재, 매분 무제한 조회 시 풀스캔) · sweeper 정렬·`batch-size`·실행당 최대 batch 수.
- [ ] **P14.** **saga-contract 검증 게이트** — "코드 경로 × 주입 장애 × 기대 종결 상태" 매트릭스를 기계 판독 가능한 형태로 두고, 누락 경로나 미실행 테스트가 CI 를 실패시키게 한다. §5 의 "금지" 규칙을 prose 가 아니라 실행 가능한 검사로 만든다(GW-1 #12). **환불 매트릭스를 편입한다**(④-c-1b 등재, 실행은 ④-d): 결과 3종(`SUCCEEDED`/`FAILED`/`UNRESOLVED`) × 소비자 3곳의 종착 상태 표(ADR-0018 D4), 그리고 crash matrix 4칸(claim 후 사망 · PG 성공 후 커밋 전 사망 · 타임아웃 · `REQUESTED` 직후 사망, ADR-0018 D3).
- [ ] **P15.** 문서 동기화 — TASKS ④ 완료 + L-013 처분 + **D-020 신규 등재**(§2.4), `PHASE4.md` 이력, Layer 1(`02`/`03`/`04`) saga 흐름·토픽·payload 정정, ADR-0012 ④ 산출물 대비 실제 범위 차이(§2.1 표) 기록.

---

## 4. 영향 파일

| 구분 | 경로 | 항목 |
|---|---|---|
| Order 도메인 | `order-service/.../domain/model/Order.java` · `.../infrastructure/OrderJpaRepository.java` | P2 `@Version` · P3 만료 조회 |
| Order 마이그레이션 | `order-service/src/main/resources/db/migration/V*__*.sql` | P2 `orders.version` |
| Order 취소/발행 | `.../application/OrderCommandService.java` · `.../infrastructure/outbox/OrderOutboxEventPublisher.java` · `.../infrastructure/kafka/OrderEventConsumer.java` · `.../scheduler/OrderTimeoutScheduler.java` | P3·P6·P7 |
| 이벤트 DTO | `common/.../global/outbox/dto/OrderCancelledPayload.java` · `StockReservationResultPayload.java` | P4·P5·P6 |
| Product 예약 | `product-service/.../application/StockReservationService.java` · `domain/repository/StockReservationRepository.java` · `infrastructure/StockReservationJpaRepository.java` · `infrastructure/scheduler/`(신설) | P4·P8 |
| Product 마이그레이션 | `product-service/src/main/resources/db/migration/V*__*.sql` | P4 lease 컬럼 · P13 인덱스 |
| Payment | `payment-service/.../application/PaymentCommandService.java` · `domain/model/` · `infrastructure/kafka/` | P4 만료 승인 거부 · P8 환불 요청 |
| DLQ | order/product/payment/notification `infrastructure/kafka/` + 원장 엔티티·repository·Flyway | P9·P13 |
| 메트릭 | product/order/payment `application`·`infrastructure` | P11 |
| 테스트 | 각 서비스 `src/test` + cross-service E2E | P1·P12·P14 |
| CI | `.github/workflows/` · `scripts/lint/` | P14 |
| 문서 | `docs/TASKS.md` · `docs/progress/PHASE4.md` · `docs/02`/`03`/`04` · **DLQ runbook**(경로: 계획 §6 또는 `docs/runbooks/`) | P10·P15 |

---

## 5. 검증 방법

**공통 금지 (P14 로 기계화)**: "스케줄러가 존재한다" · "메트릭이 노출된다" · "listener 가 배선됐다" 를 수렴 검증으로 기록하지 않는다. 각 항목은 **실패 경로를 실제로 주입한 뒤** 종료 상태 도달을 DB 상태로 확인한다(음성 대조 포함).

### 5.1 P1 실측 결과 (2026-08-13) — **양성 → P2 승격**

`OrderStateTransitionRaceProbeTest`(측정용, 측정 후 삭제)로 `@Version` 도입 **전** 동작을 기록했다. 두 `EntityManager` 가 커밋 전 같은 스냅샷을 읽도록 강제한 결정적 재현이라 스케줄링 운에 의존하지 않는다.

| 커밋 순서 | 기대(정합) | **실측 최종 상태** | 판정 |
|---|---|---|---|
| 취소 선커밋 → 결제 완료 커밋 | `CANCELLED` 유지 | `PAYMENT_COMPLETED` | **모순** — 취소 유실 |
| 결제 완료 선커밋 → 취소 커밋 | `PAYMENT_COMPLETED` 유지 | `CANCELLED` | **모순** — 과금된 주문이 취소로 표시 |

두 순서 **모두** lost update. `OrderStatus.canTransitionTo` 가 각 트랜잭션의 *스냅샷* 기준으로만 평가되므로 전이 가드는 이를 막지 못한다(가드는 통과하는데 결과가 틀린다). 확률적 재현이 아니라 **결정적 재현이므로 L-013 은 실측으로 확정**되었고 P2 를 승격했다. 도입 후 동작은 `OrderStateTransitionRaceIntegrationTest` 가 고정한다 — 진 쪽 커밋이 조용히 덮는 대신 `OptimisticLockException`(root: `StaleObjectStateException`)으로 드러난다.

### 5.2 항목별 검증

| 항목 | 검증 |
|---|---|
| P1 | barrier/latch 결정적 재현. 양 순서 각각의 최종 상태를 표로 기록 → P2 승격/기각 근거 (**완료 — §5.1**) |
| P2 | 동일 재현 테스트 전/후 대조. 양 순서 모두 "결제 완료 또는 환불 보상"으로 수렴. 기존 데이터 마이그레이션 그린 |
| P3 | 예약 확정 후 결제 미시작 `PENDING` 이 상한 내 취소되고 재고가 회수됨 |
| P4 | lease 미만료 예약은 불변 / 만료분만 회수 / `RESERVED` 아닌 상태 불변 / 다중 실행 멱등. **만료 ↔ 결제 승인 경합 양 순서**에서 oversell 0 |
| P5·P6 | 4가지 사유가 각 발행 경로에서 정확히 매핑 + 구 메시지(필드 부재) 하위 호환 + `items[]` 가 실제 예약 품목과 일치 |
| P7 | 결정에 따른 발행/미발행이 동일 트랜잭션 Outbox 로 증명되거나, 미발행 시 대안 경로가 검증됨 |
| P8 | PAID_BUT_UNRESERVED → **환불 요청 → 종결 상태** E2E. 재시도 소진 경로 포함, 중복 환불 0 |
| P9 | 의도적 실패 메시지가 재시도 소진 후 `.dlq` 도달 → 원장 1행(`originalTopic+eventId+failedConsumerGroup`) + 알림 **1회만**. 다른 서비스 실패와 중복 기록 0. DLQ listener 실패가 `topic.dlq.dlq` 를 만들지 않음 |
| P10 | runbook 절차대로 원장 1건을 `OPEN → RESOLVED` 로 종결시키는 리허설 |
| P11 | 각 카운터가 해당 경로 실행 시 증가 + `observability-*-lint` 그린 |
| P12 | 결제 실패 체인·예약 실패 체인이 제한 시간 내 종료 상태 수렴(§3 P12 의 5개 상태 전부 확인) |
| P13 | 인덱스 적용 후 sweeper 조회 실행계획 · 배치 상한 동작 |
| P14 | 매트릭스에 누락 경로가 있거나 해당 테스트가 실행되지 않으면 **CI 실패** |
| 전체 | 10모듈 빌드+테스트 그린, 기존 lint 10종 그린 |

---

## 6. 완료 조건

- P1 실측 결과가 §5 에 기록되고 P2 가 승격 또는 근거 있는 기각으로 처분됨
- P3~P14 전부 그린 + §5 의 음성 대조 포함
- §1 명제를 **코드 경로별로** §5/P14 매트릭스에 대응시킨 표가 존재하고, 대응 없는 경로가 0
- PAID_BUT_UNRESERVED 가 환불 종결 상태에 도달(P8) — marker+알림은 완료로 치지 않음
- DLQ 원장이 존재하고 runbook 리허설 1회 완료(P10)
- 10모듈 빌드/테스트 · lint · saga-contract 게이트 그린
- P15 문서 동기화 완료, TASKS ④ ✅ 전환 + **D-020 등재**

---

## 7. PR 분할 (잠정 — /work 착수 시 확정)

| PR | 범위 | 분할 근거 |
|---|---|---|
| ④-a | P1·P2·P3·P4·P13(일부) | 상태 수렴 + lease. 스키마 변경·동시성이 같은 위험군이고 oversell 차단이 단일 서사 |
| ④-b | P5·P6·P7 | 이벤트 계약(ADR-0012 D2 이행). 도메인 로직보다 계약·하위호환이 리뷰 축 |
| ④-c | P8·P9·P10·P13(나머지) | 미결 종료 상태 제거(환불·DLQ 원장·runbook). 새 테이블·운영 절차가 묶임 — **아래로 재분할됨** |
| ④-d | P11·P12·P14·P15 | 관측·E2E·게이트·문서 |

P2 가 기각되면 ④-a 는 P1·P3·P4 로 축소된다.

**④-c 재분할 (확정)** — 착수 전 코드 검증에서 감지 3지점·Toss 취소/조회 API 부재가 드러나 선행 ADR-0018 을 먼저 세웠고, 그 구현을 자식 계획서 `task-impl4-c1-refund-path.md` 로 분리했다.

| PR | 범위 | 분할 근거 | 상태 |
|---|---|---|---|
| ④-c-1a | 자식 P1~P9 | **payment-service 단독으로 부팅·동작하는 단위** — 원장·fence·dispatcher·PG 클라이언트·reconciliation | ✅ [#87](https://github.com/Kimgyuilli/PeakCart/pull/87) |
| ④-c-1b | 자식 P10~P15 | **크로스서비스 계약** — 요청 토픽 2개·트리거 발행 2경로·backfill·회신 소비 3곳. 리뷰 축이 "계약과 하위호환" | 본 PR |
| ④-c-2 | 부모 P9·P10·P13(나머지) | DLQ 원장·runbook. 운영 절차가 별개 서사 | 🔲 대기 |

- 분할 기준 = "**한 서비스 안에서 그린이 되는가**"(PR3d-a/b 와 동일)
- **rollout gate**: 1a 와 1b 는 **같은 릴리스 주기**에 배포한다 — 1a 는 회신을 발행하되 소비자가 없어, 지연되면 회신이 쌓이는 기간이 Kafka retention 을 넘길 수 있다
