# task-impl4-c1-refund-path — 구현 ④-c-1: 환불 요청 경로 (ADR-0018 이행)

> 작성: 2026-08-15 (GP-2 반영 개정)
> 관련 Phase: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga
> 선행: **ADR-0018**([#86](https://github.com/Kimgyuilli/PeakCart/pull/86), Accepted) · ADR-0012 D3 ④/D4/D5 · ADR-0016 · ADR-0015(관측) · ADR-0007(설정 소유)
> 부모 계획서: `docs/plans/task-impl4-choreography-saga.md` **P8**
> 후속: ④-c-2(부모 P9·P10·P13 나머지 — DLQ 원장/runbook) → ④-d(부모 P11·P12·P14·P15)

## 1. 목표

ADR-0018 이 확정한 보상/환불 계약을 코드로 구현한다. **④-a 가 남긴 R-2**(`order_compensations` 가 `OPEN` 으로 쌓이기만 하고 환불은 수동)를 닫는 것이 이 작업의 존재 이유다.

명제: **결제가 승인됐는데 재고가 확정되지 않았거나 주문이 취소된 경우, 시스템은 사람의 개입 없이 환불을 실행하고 그 결과를 원장에 종결 상태로 남긴다.** 확정할 수 없는 구간(외부 PG 결과 불명)은 없애는 것이 아니라 **관측 가능한 미해결 상태**로 만든다.

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (grep, 2026-08-15) — ADR 문구가 아니라 현재 코드 기준

| # | 검증 | 결과 |
|---|---|---|
| a | 감지 3지점 | ADR-0018 C1 대로 존재. Product `StockReservationService.compensatePaidButUnreserved`(marker `compensated_at`) · Order `OrderEventConsumer.recordPaidButCancelled`(`order_compensations` OPEN) · Payment `PaymentEventConsumer.handleOrderCancelled` APPROVED 분기(**Slack·로그뿐, 비영속**) |
| b | Toss 클라이언트 | `TossPaymentClient` 에 `confirm` 만. **취소·조회 메서드 없음**. `RestClient` 기반, baseUrl `https://api.tosspayments.com/v1`, Basic Auth |
| c | Payment 상태 | `PaymentStatus` = PENDING/APPROVED/FAILED/CANCELLED, **APPROVED terminal**. `payments.status` 는 **VARCHAR(30)** 이라 값 추가에 DDL 불필요 |
| d | Payment 스케줄러 인프라 | **이미 있다** — `ShedLockConfig`, `OutboxPollingScheduler`, `ProcessedEventCleanupScheduler`(`@Scheduled` + `@SchedulerLock`) |
| e | Payment Outbox | `PaymentOutboxEventPublisher`(publishPaymentRequested/Completed/Failed) + 자기 스키마 `outbox_events`. **poller 는 `eventType` 을 그대로 토픽명으로 발행**(`OutboxPollingService`) → **토픽이 없으면 발행이 성립하지 않는다**(§2.4 분할 근거) |
| f | NewTopic 소유 | 각 서비스가 자기 발행 토픽 + `.dlq` 소유(producer-owns-topic). 신규 3토픽도 각 producer 모듈에 추가 |
| g | Kafka 에러 핸들러 | `DefaultErrorHandler` + `FixedSequenceBackOff(1s, 5s, 30s)` + `.dlq` + Slack — **4서비스 공통**(payment·order·product·**notification**). 신규 listener 5개(요청 2 + 회신 3)는 각 서비스 기본 factory 를 그대로 탄다 |
| h | Order 원장 | `order_compensations`(V4) + `OrderCompensation` 엔티티 + `CompensationStatus{OPEN, RESOLVED}`. `resolved_at` 은 있으나 **`failure_code` 는 DDL·엔티티 모두 없음** → Order V5 에서 신설 |
| i | Product 원장 | `stock_reservations.compensated_at`(감지 marker) 뿐. **종결 컬럼 전무** → Product V4 에서 신설 |
| j | Notification | `NotificationType` 4종. `notifications.type` **VARCHAR(50)** → `PAYMENT_REFUNDED` 추가에 DDL 불필요. `NotificationConsumer` 는 payload `userId` 를 직접 읽는다 |
| k | 메트릭 패턴 | `Counter.builder`/`Gauge.builder` + 태그. `application=payment-service` 는 `application.yml` 이 이미 부여(ADR-0015) |
| l | **`payments.user_id` 가 nullable** | V1 DDL·엔티티 모두 nullable이고 NOT NULL 전환은 후속으로 남아 있다(`PHASE4.md`). **ADR-0018 은 Notification 때문에 `payment.refunded.userId` 를 필수로 결정** → null 레거시 행 처분이 필요(P2·§6) |
| m | 멱등 저장 패턴 | `ProcessedEventRepositoryImpl` 은 단순 `save`. **JPA save 의 유니크 위반은 flush/commit 시점에 터져 catch 를 우회**하고 트랜잭션을 rollback-only 로 만든다 → fence 삽입은 **단일 원자 쿼리**여야 한다(P3) |
| n | 기존 전이 테스트 | `PaymentStatusTest` 가 "APPROVED 는 모든 전이 거부"를 단언 → P2 가 **즉시 깨뜨린다**. `Payment.cancelBeforePayment` 의 APPROVED 판정도 영향면 |
| o | Outbox aggregateType | Payment=`PAYMENT` · Order=`ORDER` · Product=`PRODUCT`(stock 이벤트 포함). 신규 이벤트도 같은 규약을 따르며 **backfill SQL 의 NOT EXISTS 키와 런타임 발행 키가 같아야 한다**(P11) |
| p | 부모 계획서 상태 | §3 P8 에 ADR-0018 선행 표기 없음, §7 은 ④-c 단일 PR, **부모 §3 P12 E2E 는 `payment.failed` 경로만** → 환불 체인 미포함. 본 task 에서 갱신(P13) |

### 2.2 제약 / 트레이드오프

- **Toss 취소 API 실호출 불가** — 승인된 실거래가 필요하다. 검증은 **클라이언트 계약 테스트(`MockRestServiceServer`) + 상태머신/fence/crash 복구 통합테스트**로 한정한다. "실제 환불이 된다"를 검증했다고 기록하지 않는다
- **즉시성 포기** — 진입점은 `REQUESTED` 커밋만 하고 dispatcher 주기에 환불이 실행된다. ADR-0018 D3 의 명시적 교환
- **스케줄러 2개 추가**(dispatcher·reconciliation) — 둘 다 `@SchedulerLock`
- **`APPROVED` 를 종결로 가정한 기존 코드** — `REFUNDED` 추가가 `PaymentStatusTest` 를 즉시 깨뜨린다(§2.1-n). 처분은 **P2**
- **`payments.user_id` nullable**(§2.1-l) — 계약이 요구하는 필수 필드를 스키마가 보장하지 않는다. P2 에서 처분
- **D-020 경계 유지** — 로컬에 흔적 없는 과금은 본 작업 대상이 아니다

### 2.3 구조 변경 여부 (GP-1 판단)

새 테이블 1개 + 토픽 3개 + 스케줄러 2개이며 **모듈 경계·의존 방향·패키지 이동은 없다**. `PLAN-BLINDSPOTS.md` B1(역의존 스윕)은 이동 대상 없음. **B12**(계약 검사와 fence 혼동)는 P3/P4 의 축이라 인용한다.

### 2.4 PR 재분할 (부모 §7 의 ④-c 를 더 쪼갠다)

| PR | 범위 | 분할 근거 |
|---|---|---|
| **④-c-1a** | P1~P9, P14(일부) | **payment-service 단독으로 부팅·동작하는 단위**. 원장·상태·fence 진입점(로컬)·dispatcher·Toss client·reconciliation·메트릭 + **`payment.refunded` payload DTO 와 NewTopic(+dlq)**. poller 가 `eventType` 을 그대로 토픽으로 쓰므로(§2.1-e) **회신 토픽이 1a 안에 있어야 발행이 성립**한다 |
| **④-c-1b** | P10~P13, P14(나머지), P15 | **크로스서비스 계약**. 요청 토픽 2개·트리거 발행 2경로·backfill·회신 소비 3곳·Notification·문서. 리뷰 축이 "계약과 하위호환" |

- 분할 기준 = "**한 서비스 안에서 그린이 되는가**"(PR3d-a/b 와 동일)
- **1a 는 회신을 발행하되 소비자가 없다** — 토픽이 선생성되고 소비자 group 은 `auto-offset-reset=earliest` 라 1b 배포 후 **과거 메시지를 처음부터 소비**한다. 1a 단독 기간에는 Payment 로컬 경로의 환불만 실행되고 **Order/Product 원장은 닫히지 않는다**
- **rollout gate**: 1a 와 1b 는 **같은 릴리스 주기**에 배포한다(부모 계획 R-2 와 동일 제약). 1b 지연 시 회신이 쌓이는 기간이 Kafka retention 을 넘지 않아야 한다

## 3. 작업 항목

### ④-c-1a — payment-service 내부 (환불 실행 엔진)

- [ ] **P1.** **`payment_refunds` 스키마 + 엔티티/repository** — Flyway `V4__payment_refund_ledger.sql`.
  - 컬럼: `order_id`(**UNIQUE = fence**) · `payment_key` · `user_id` · `amount` · `status` · `attempts` · `claimed_at` · `last_error` · `pg_response` · `requested_at` · `resolved_at` · **`resolved_by`(actor)** · **`resolution_reason`(감사 사유, 수동 종결 시 필수)**
  - 인덱스: `(status, claimed_at)`(dispatcher·stale claim 회수), `(status, requested_at)`(backlog·age 게이지)
  - `RefundStatus{REQUESTED, CLAIMED, SUCCEEDED, FAILED, UNRESOLVED}` + **전이 규칙을 enum 이 직접 보유**(허용: `REQUESTED→CLAIMED`, `CLAIMED→{SUCCEEDED,FAILED,UNRESOLVED}`, `UNRESOLVED→{SUCCEEDED,FAILED}`, `CLAIMED→CLAIMED`(재claim) / 그 외 거부)
- [ ] **P2.** **상태·스키마 정합** — `PaymentStatus.REFUNDED` 추가 + `APPROVED → REFUNDED` 만 허용.
  - **영향면 처분**(§2.1-n): `PaymentStatusTest` 의 "APPROVED 전이 전부 거부" 단언 갱신 · `PaymentStatus.values()` **전수 전이표 테스트** · `REFUNDED` 에서 `cancelBeforePayment()` no-op · 조회/응답 직렬화 회귀
  - **`payments.user_id` 처분**(§2.1-l): 같은 마이그레이션에서 `user_id IS NULL` 건수를 확인하고 **0이면 NOT NULL 로 contract**. 0이 아니면 NOT NULL 전환을 보류하되 **null 인 결제는 fence 진입 자체를 차단**하고(환불 미시작) 운영 알림 + `UNRESOLVED` 로 남긴다 — ADR 을 바꾸지 않는 범위의 명시적 차단
- [ ] **P3.** **fence 진입점(로컬 감지)** — `PaymentEventConsumer.handleOrderCancelled` 의 APPROVED 분기를 Slack-only 에서 **`payment_refunds` `REQUESTED` 영속**으로 교체(소비와 동일 트랜잭션).
  - **fence 획득은 단일 원자 쿼리**(`INSERT ... ON DUPLICATE KEY UPDATE id=id` 또는 `INSERT IGNORE`)로 하고 **영향 행 수 1/0 으로 분기**한다. JPA `save` + 예외 catch 는 flush 시점 위반과 rollback-only 때문에 **금지**(§2.1-m)
  - 0 = 이미 존재 → **정상 no-op 종료**(예외 금지, DLQ 금지). **PG 호출은 하지 않는다**
- [ ] **P4.** **dispatcher** — `@Scheduled` + `@SchedulerLock`. **트랜잭션 경계를 3단계로 고정**한다(ADR-0018 D3):
  1. **T1(별도 트랜잭션)**: `REQUESTED → CLAIMED` 조건부 UPDATE(CAS) + `claimed_at` 기록 → **커밋**
  2. **트랜잭션 밖**: PG 취소 호출(재시도 포함, P5)
  3. **T2(별도 트랜잭션)**: 결과 확정 — `SUCCEEDED` → `payments.status=REFUNDED` + `payment.refunded(SUCCEEDED)` Outbox / `FAILED` → `payment.refunded(FAILED)` Outbox / `UNRESOLVED` → **회신 발행 없음**. **셋 다 원장 전이와 Outbox 가 같은 트랜잭션**
  - self-invocation 회피를 위해 **별도 bean 또는 `TransactionTemplate`** 으로 경계를 만든다(같은 클래스 `@Transactional` 메서드 호출은 프록시를 타지 않음)
  - **B12**: 조회 후 호출이 아니라 CAS 로 소유권을 잡는다. 배치 상한(`batch-size`·`max-batches-per-run`) 적용
- [ ] **P5.** **Toss 클라이언트 확장 + 재시도** — `cancel(paymentKey, cancelReason, idempotencyKey)` + `find(paymentKey)`(조회).
  - **멱등키 = `orderId` 기반 안정 값**. **모든 재시도가 동일 키를 보낸다**(계약 테스트로 고정)
  - **PG 호출 재시도 = 최대 3회**(첫 호출 포함/제외를 명시), **지수 백오프**, 재시도 가능 예외 집합(연결·타임아웃·5xx)을 열거. 재시도 주체는 dispatcher 의 호출 단계(§P4-2). 소진 시 `UNRESOLVED` + `attempts=3`
  - 오류 분류(HTTP status·Toss 코드 → **transient / 영구 실패 / 결과 불명**)를 클라이언트 경계에 표로 고정. **`ALREADY_CANCELED` 는 실패가 아니라 조회 분기**
- [ ] **P6.** **reconciliation 잡** — `@Scheduled`(주기 정책값) + `@SchedulerLock`. 대상: ① **claim lease 임계 초과 `CLAIMED`**(crash matrix a/b) ② `UNRESOLVED`(c).
  - **조회 결과 4분기**: 전액 취소됨 → `SUCCEEDED` / **미취소 → 동일 멱등키로 cancel 재호출**(ADR crash matrix a) / 부분·금액 불일치 → `FAILED` / 조회 불가 → `UNRESOLVED` 유지
  - 확정 시 **P4-3 과 동일하게 회신 Outbox 를 같은 트랜잭션에서 발행**한다(reconciliation 도 원장을 닫는 주체다)
  - **24h 상한 초과** → 운영 알림 + **수동 종결 경로**: 허용 전이 `UNRESOLVED → FAILED` 만, `resolved_by`(actor)·`resolution_reason` 필수, 중복 종결은 no-op. 진입은 **운영 전용 application service**(외부 API 미노출)
- [ ] **P7.** **메트릭 5종**(ADR-0018 D6) — `payment.refund.requested{reason}` · `payment.refund.result{result}` · `payment.refund.retry.exhausted` · `payment.refund.backlog{status}`(Gauge) · `payment.refund.oldest.age{status}`(Gauge). `observability-*-lint` 그린 유지
- [ ] **P8.** **회신 계약 선행분** — `:common` 에 `PaymentRefundedPayload(orderId, userId, result, refundedAmount, failureCode, resolvedAt)` + `RefundResult` enum, `PaymentKafkaConfig` 에 `payment.refunded`·`payment.refunded.dlq` **NewTopic**. (§2.4 — poller 가 토픽을 선요구하므로 1a 에 포함)
- [ ] **P9.** **설정 소유(ADR-0007)** — `RefundProperties`(`@ConfigurationProperties`)에 정책값을 모은다: dispatcher 주기 · claim lease 임계 · `batch-size`/`max-batches-per-run` · PG retry 횟수·백오프 · reconciliation 주기 · 미확정 상한(24h) · ShedLock 시간. **payment-service `application.yml`(base) 단독 소유**, 프로파일 재선언 금지. 양수·상호관계(`claim lease > PG 타임아웃 총합` 등) **`@AssertTrue` fail-fast** + 부팅 테스트

### ④-c-1b — 크로스서비스 계약

- [x] **P10.** **요청 이벤트 DTO + 토픽/NewTopic + 소비** — `:common` 에 `CompensationRequestedPayload(orderId, reason, detectedAt)` + `CompensationReason{PAID_BUT_UNRESERVED, PAID_BUT_CANCELLED}`. NewTopic: Product `stock.compensation.requested`(+dlq) · Order `order.compensation.requested`(+dlq). Payment 소비 2경로(정규명 group, ADR-0018 D1) → **P3 과 동일한 원자 fence 로 수렴**
- [x] **P11.** **트리거 발행 2경로 + 원자성 + backfill** —
  - Product `compensatePaidButUnreserved` / Order `recordPaidButCancelled` 가 **감지 기록과 동일 트랜잭션에서** 요청 Outbox 생성
  - **aggregateType/aggregateId 표 고정**(§2.1-o): Product=`PRODUCT`/`orderId`, Order=`ORDER`/`orderId`. **런타임 발행과 backfill SQL 이 같은 키를 쓴다**
  - backfill: Product `compensated_at IS NOT NULL` · Order `status='OPEN'` 기존 행 → 각 producer 마이그레이션(Product V4·Order V5)에서 요청 Outbox 1회 생성. 멱등 조건 = **`NOT EXISTS (aggregate_type + aggregate_id + event_type 일치)`**(Payment 유니크는 DB 경계를 넘지 못한다). 필요 시 이 조회를 위한 복합 인덱스 추가
- [x] **P12.** **회신 소비 3곳 + 종결 스키마** —
  - **Order V5**: `order_compensations.failure_code` 신설 + 엔티티 매핑, `CompensationStatus.REFUND_FAILED` 추가. 소비: `SUCCEEDED→RESOLVED` / `FAILED→REFUND_FAILED`(+failure_code) / `UNRESOLVED→전이 없음`
  - **Product V4**: `stock_reservations` 에 종결 컬럼(`refund_result`·`refund_resolved_at`·`refund_failure_code`, 길이·null 규칙 명시) + 동일 규칙
  - **Notification**: `SUCCEEDED` 만 `PAYMENT_REFUNDED` 알림(payload `userId` 사용)
  - 세 소비 모두 멱등(`processed_events`) + 구 메시지 내성
- [x] **P13.** **부모 계획서 동기화** — `task-impl4-choreography-saga.md` §3 P8 에 **선행 ADR-0018 명시** · §7 을 ④-c-1a/1b·④-c-2 로 갱신 · **부모 §3 P12(E2E)에 환불 체인 추가**(Product/Order 트리거 → Payment fence/dispatcher → `payment.refunded` → 3곳 종결) · **부모 §3 P14(saga-contract 게이트)에 결과/crash 매트릭스 편입** — 실행은 ④-d 지만 **요구사항을 지금 등재**한다

### 공통

- [x] **P14.** **테스트** — 전부 **MySQL Testcontainers + Spring 프록시** 기준(단위 mock 은 트랜잭션 경계를 증명하지 못한다 — ④-b 전례).
  - **fence 동시성**: 두 스레드 · 각자 실제 트랜잭션 · barrier/latch → 원장 1행, 패자는 no-op
  - **claim CAS**: dispatcher 2개 동시 → 정확히 1개만 `CLAIMED` 획득(1/0 판정)
  - **crash matrix**: (a) claim 커밋 직후 예외 (b) PG 성공 후 finalize/Outbox 저장 실패(`@MockitoSpyBean`) (c) 3회 타임아웃 → 각각 상태 재확인 + reconciliation 이 확정
  - **동일 트랜잭션**: 원장·`payments`·Outbox 가 함께 커밋/롤백됨을 **DB 재조회로** 판정
  - **cross-topic 순서**(ADR-0018 D1): stock 요청 · order 요청 · 로컬 감지 **모든 선후·중복 조합**에서 원장 1행 + 동일 최종 결과(결정적 테스트). Payment 상태 미준비 순서가 있으면 DLQ 의존 대신 **영속 pending marker/재평가 규칙**을 정의
  - **backfill**: 동일 DML 2회 실행 → 2회차 0건
  - **listener 배선**: 신규 5개 listener 가 각 서비스 기본 `kafkaListenerContainerFactory` 를 실제로 사용
- [x] **P15.** **문서**(1b 귀속) — `docs/TASKS.md`(④-c-1 완료·R-2 해소) · `docs/progress/PHASE4.md` · Layer 1 `05`(신규 테이블/컬럼)·`03`/`04`(saga 흐름·토픽). ADR 신규 없음

## 4. 영향 파일

| 구분 | 경로 | 항목 |
|---|---|---|
| Payment 원장 | `payment-service/.../domain/model/PaymentRefund.java`·`RefundStatus.java` · `domain/repository/PaymentRefundRepository.java` · `infrastructure/PaymentRefund{JpaRepository,RepositoryImpl}.java` | P1 |
| Payment 마이그레이션 | `payment-service/.../db/migration/V4__payment_refund_ledger.sql` | P1·P2 |
| Payment 상태 | `.../domain/model/PaymentStatus.java` · `Payment.java` | P2 |
| Payment 진입/실행 | `.../infrastructure/kafka/PaymentEventConsumer.java` · `.../application/PaymentRefundService.java`·`RefundDispatchService.java`(신설) · `.../infrastructure/scheduler/RefundDispatcher.java`·`RefundReconciliationScheduler.java`(신설) | P3·P4·P6 |
| Toss 클라이언트 | `.../infrastructure/toss/TossPaymentClient.java` · `TossCancelResponse`·`TossPaymentQueryResponse`(신설) | P5 |
| 설정 | `.../application/RefundProperties.java`(신설) · `payment-service/src/main/resources/application.yml` | P9 |
| 회신 DTO/토픽 | `common/.../outbox/dto/PaymentRefundedPayload.java`·`RefundResult.java` · `payment .../kafka/PaymentKafkaConfig.java` | P8 |
| 요청 DTO/토픽 | `common/.../outbox/dto/CompensationRequestedPayload.java`·`CompensationReason.java` · `product/order .../kafka/*KafkaConfig.java` | P10 |
| 트리거 발행 | `product-service/.../application/StockReservationService.java` · `order-service/.../infrastructure/kafka/OrderEventConsumer.java` + 각 `*OutboxEventPublisher` | P11 |
| 마이그레이션(1b) | `product-service/.../V4__stock_reservation_refund_result.sql` · `order-service/.../V5__order_compensation_failure_code.sql` (**컬럼 추가 + backfill DML 동일 버전**) | P11·P12 |
| 회신 소비 | `order/product/notification .../kafka/` · `CompensationStatus.java` · `NotificationType.java` | P12 |
| 메트릭 | `payment-service/.../application`·`infrastructure` | P7 |
| 테스트 | 4개 서비스 `src/test` (통합 위주) | P14 |
| 문서 | `docs/TASKS.md` · `docs/progress/PHASE4.md` · `docs/03`/`04`/`05` · `docs/plans/task-impl4-choreography-saga.md` | P13·P15 |

> 마이그레이션 **3파일**: payment V4 · product V4 · order V5 (인덱스는 각 파일에 포함)

## 5. 검증 방법

| 항목 | 통과 기준 |
|---|---|
| P1 | 전이 규칙이 enum 에 있고 허용 외 전이가 예외. `order_id` UNIQUE 가 실제 DDL 에 존재. 인덱스 2종 존재 |
| P2 | `PaymentStatus.values()` 전수 전이표 · `APPROVED→REFUNDED` 만 허용 · `REFUNDED` 에서 `cancelBeforePayment()` no-op · 기존 `PaymentStatusTest` 갱신 · **`payments.user_id IS NULL` 건수 0 확인**(아니면 차단 경로 테스트) |
| P3 | 두 스레드 동시 진입에서 **원장 1행** · 패자는 예외 없이 no-op · **단일 원자 쿼리**(1/0 분기)임을 코드로 확인, JPA save+catch 아님 |
| P4 | dispatcher 2개 동시 → 정확히 1개만 claim. **claim 커밋과 PG 호출이 다른 트랜잭션**임을 (a) 주입으로 증명(claim 이 롤백되지 않고 `CLAIMED` 로 남음). 결과 확정 3분기 각각 원장·`payments`·Outbox 동시 커밋/롤백(DB 재조회 판정) |
| P5 | 오류 분류 표 존재 + 각 분기 테스트. **모든 재시도의 idempotency key 동일**(`MockRestServiceServer` 헤더 검증). 소진 시 `UNRESOLVED`+`attempts=3`. `ALREADY_CANCELED` → 조회 → 3분기 |
| P6 | crash matrix a/b/c 를 **상태로 재현**하고 reconciliation 이 확정 · **미취소 시 동일 멱등키 재호출** 검증 · claim lease 임계 경계(Clock 고정) · 24h 초과 → 수동 종결 경로에서 `resolved_by`·`resolution_reason` 없으면 거부 · 중복 종결 no-op |
| P7 | 각 카운터 증가, backlog/age 게이지가 원장 상태 반영. `observability-*-lint` 그린 |
| P8 | `payment.refunded`(+dlq) NewTopic 이 payment 모듈에 존재 → 1a 단독 부팅/발행 성립 |
| P11 | 감지 기록과 요청 Outbox 동일 트랜잭션(Outbox 실패 주입 시 **감지 기록도 롤백**) · aggregateType/Id 가 표와 일치 · backfill DML 2회 실행 시 2회차 **0건** |
| P12 | 결과 3종에 대해 Order·Product 종착 상태가 ADR-0018 D4 표와 일치 · Notification 은 `SUCCEEDED` 에만 1건 · 구 메시지(필드 부재) 내성 |
| P14 | 위 전부가 **Testcontainers 통합테스트**로 실행됨(단위 mock 단독 판정 금지) · cross-topic 순서 조합 전수 |
| P9 | 정책 키가 base yml 단독 소유(프로파일 재선언 0, `observability-ssot-lint` 성격의 확인) · 잘못된 값에서 **부팅 실패** |
| 전체 | 10모듈 빌드+테스트 그린 · lint 10종 그린 · 마이그레이션 3종 적용 |

## 6. 완료 조건

### ④-c-1a
- Payment **로컬 감지 경로**가 fence → dispatcher → PG 호출 → 원장 종결 → **회신 발행**까지 도달
- crash matrix a/b/c 가 통합테스트로 재현·복구되고, 미확정은 `UNRESOLVED` 로 **관측 가능**
- 메트릭 5종 노출 · `RefundProperties` fail-fast · `payment.refunded` 토픽 존재
- **R-2 는 아직 닫히지 않는다**(회신 소비가 1b) — 1a 완료 보고에 이 사실을 명시한다

### ④-c-1b
- **R-2 해소** — `order_compensations` 가 회신 소비로 `RESOLVED`/`REFUND_FAILED` 도달(수동 개입 없이)
- 요청 2경로 + backfill 이 기존 행까지 트리거하고 재실행 0건
- 회신 소비 3곳이 결과별로 정확히 분기, Notification 은 성공만
- 부모 계획서 §3 P8·§7·부모 P12·P14 갱신(P13) + 문서 동기화(P15)

### 공통
- ADR-0018 D1~D6 각 결정에 대응하는 코드가 존재하고 **대응 없는 결정 0**
- 10모듈 빌드/테스트 · lint 그린

## 7. 잔여 위험 (착수 시점 인지)

- **R-1**: Toss 실호출 미검증 — 클라이언트 계약 테스트로 대체. 실거래 검증은 D-020 과 같은 게이트에 묶인다
- **R-2**: dispatcher 주기만큼 환불 지연 — 정책값(P9)과 메트릭(P7)으로 관측
- **R-3**: `UNRESOLVED` 24h 초과 수동 종결은 **사람 절차** — 절차 문서는 ④-c-2 runbook(P11)과 함께
- **R-4**: 1a↔1b 사이 기간에 회신이 소비되지 않고 쌓인다 — **같은 릴리스 주기 배포**가 전제이며, 지연 시 Kafka retention 을 넘지 않아야 한다(§2.4)
- **R-5**: 크로스서비스 E2E 는 ④-d(부모 P12) — 본 작업은 서비스별 통합테스트까지다
