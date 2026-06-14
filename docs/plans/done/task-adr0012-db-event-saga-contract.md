# task-adr0012-db-event-saga-contract — Phase 4 DB-per-service + 이벤트/Saga 계약 ADR-0012

> 작성: 2026-06-14
> 관련 Phase: Phase 4 (MSA 분리) — 설계 단계 A3
> 로드맵: `docs/progress/phase4-design-roadmap.md §1 A3`
> 선행: ADR-0010(5서비스 경계·토픽 4개·Saga 골격·F2/F3), ADR-0011(common 모듈 이벤트 DTO 소유)
> 후속: 본 ADR 비준 후 구현 ②(서비스별 DB 분리)·④(Choreography Saga)·⑤(CQRS 로컬 캐시)
> 관련 ADR: 신규 = **ADR-0012** (Proposed → Accepted). ADR-0010/0011 구체화, ADR-0008(Outbox trace context) 정합

## 1. 목표

ADR-0010(서비스 경계)·ADR-0011(모듈 구조)이 **A3 로 위임한 데이터·이벤트 계약**을 확정하는 것이 본 task 의 primary 산출물(**ADR-0012**)이다. A1/A2 는 "스키마는 A3 non-authoritative", "재고 예약 경계는 A3", "retention 은 A3" 로 미뤘다. 본 ADR 이 이를 닫아 구현 ②/④/⑤ 의 단일 입력값(SSOT)이 된다.

세부 목표:

- **(D1) DB-per-service 경계** — 5개 서비스 독립 스키마(소유 테이블) + Flyway 서비스별 독립 마이그레이션 전략(현 단일 V1~V4 를 서비스별로 분할). 공유 테이블 없음 확정
- **(D2) 이벤트 스키마 계약** — 토픽 4개 페이로드의 필드·**버전**·**파티션 키** 확정(A1/A2 non-authoritative 해소). `KafkaEventEnvelope`(eventId/eventType/timestamp/payload) 의 버전 필드 + 호환성 규칙. `product.updated`(CQRS, F3) 스키마 정의
- **(D3) Saga 재고 예약/차감 경계 (F2 해소)** — ADR-0010 F2(주문 생성 시 Order 단일 트랜잭션 재고 차감 ↔ Product 재고 소유)를 choreography 이벤트 경계로 확정. `order.created → Product 재고 예약`, `payment.completed → 예약 확정`, `payment.failed`/`order.cancelled → 예약 해제/복구` 등. **`OrderCancelledPayload` 에 복구 대상 items 부재 갭** 포함 해소
- **(D4) retention = 멱등성 창 상한** — `outbox_events`(PUBLISHED 후 보존기간)·`processed_events`(보존기간 = 이벤트 재전달 최대 창 상한) 정책 확정. L-008/L-011
- **(D5) consumer group 라벨** — 현 `{svc}-svc-{topic}-group` 규약을 관측성 라벨(kafka-exporter/Micrometer tag)로 독립화. L-020-2, ADR-0009 Kafka lag surface 정합

본 task 는 **문서만 변경**한다. 실제 Flyway 분할 SQL·페이로드 코드 변경·Saga consumer 구현은 구현 ②/④/⑤(별도 task).

## 2. 배경 / 제약

### 현 데이터/이벤트 상태 (감사 — 실제 인용)

- **Flyway**(`src/main/resources/db/migration/`): `V1__init_schema`, `V2__outbox_processed_events`, `V3__shedlock`, `V4__outbox_trace_context` — 단일 DB
- **이벤트 페이로드**(`global.outbox.dto`):
  - `OrderCreatedPayload`(orderId, orderNumber, userId, totalAmount, **items**[productId/quantity/unitPrice], receiverName, address)
  - `OrderCancelledPayload`(orderId, orderNumber, userId) — **⚠️ items 없음** → Product 재고 복구 불가(D3 갭)
  - `PaymentCompletedPayload`(paymentId, orderId, userId, paymentKey, amount, method, approvedAt)
  - `PaymentFailedPayload`(paymentId, orderId, userId, paymentKey, amount)
  - `KafkaEventEnvelope`(eventId, eventType, timestamp, payload) — **버전 필드 없음**(D2 대상)
- **consumer group**: `{svc}-svc-{topic}-group` 규약 존재(예: `order-svc-payment-completed-group`, `notification-svc-order-cancelled-group`)
- **멱등성**(`04-design-deep-dive §9-7`): `processed_events` save-first + UK(event_id, consumer_group) 선점. **retention/cleanup 미정**(L-008/L-011)
- 토픽: KafkaConfig partitions=3 (현재 파티션 키 전략 미명시 — D2 대상)

### F2 갭 (ADR-0010 §C4 인용 + 본 감사)

- 모놀리스: 주문 생성 시 Order 가 재고 차감 단일 트랜잭션(`04 §9-3`). MSA: Product 가 재고 소유 → 직접 차감 불가
- 추가 발견: `OrderCancelledPayload` 에 items 가 없어, `order.cancelled → Product 재고 복구`(ADR-0010 §D3/§5 토폴로지) 가 **현 페이로드로는 불가능**. D3 에서 페이로드 보강 결정 필요

### 제약 / 비대상

- 코드/SQL 변경 0건 (문서만). 실제 분할·페이로드 변경·consumer 구현은 구현 ②/④/⑤
- 분산 트랜잭션 2PC 도입 비대상 (choreography saga 유지 — ADR-0010)
- A4(Gateway 보안)·실제 schema registry 도구 도입(Avro/Confluent 등)은 비대상 (계약 규칙만)

### ADR 관계

- ADR-0010(경계/토폴로지/Saga 골격)·ADR-0011(모듈/이벤트 DTO common 소유) 구체화. 본 ADR 은 스키마·DB·Saga 이벤트 경계의 SSOT
- ADR-0008(Outbox trace context) — 이벤트 envelope 의 trace 헤더 전파와 정합(버전 필드 추가 시 trace 헤더 규약 유지)

## 3. 작업 항목

### Part A — 사전 감사

- [ ] **P1.** DB 소유 테이블 감사 — `05-data-design.md` ERD + 현 `V1~V4` Flyway 를 5개 서비스로 매핑. **domain 테이블 / infra 테이블(outbox_events·processed_events) 분리**해 기록:
  - User: (domain) users/refresh_tokens/addresses
  - Product: (domain) products/categories/inventories (+ **infra** outbox_events·processed_events — Product 가 producer(`product.updated`)/consumer 가 되므로 필요, P6 결과 의존)
  - Order: (domain) orders/order_items/carts/cart_items (+ infra outbox_events·processed_events)
  - Payment: (domain) payments/payment_failures(`05 §324-330`)/webhook_logs (+ infra outbox_events·processed_events)
  - Notification: (domain) notifications (+ infra processed_events)
  - 공유/교차 참조(FK) 식별 → DB 분리 시 ID 참조+이벤트/캐시로 대체. **infra 테이블 소유권은 A3 에서 확정, DDL 은 구현 ②**
- [ ] **P2.** 이벤트 페이로드/envelope 현황 감사 — 4개 payload 필드 + `KafkaEventEnvelope` 인용. 버전 필드 부재, `OrderCancelledPayload` items 부재, 파티션 키 미명시를 §Context 갭으로 기록
- [ ] **P3.** 멱등성/retention 현황 — `processed_events`(UK event_id+consumer_group) + `outbox_events` 상태 전이. 현 cleanup 부재(L-008/011) + 재전달 창(Kafka retention, consumer lag) 관계 정리

### Part B — Decision

- [ ] **P4.** DB-per-service 경계 표(D1) — 서비스 × 소유 테이블 + Flyway 분할 전략(서비스별 `db/migration/{service}/` 독립 이력 vs 모듈별 분리). 교차 FK 제거 방식(ID 참조 + 이벤트/캐시) 명시
- [ ] **P5.** 이벤트 스키마 계약(D2) — (a) `KafkaEventEnvelope` 에 `schemaVersion` 추가 + 호환성 규칙(하위호환 필드 추가만, 삭제/의미변경 금지) (b) 토픽별 **파티션 키 = aggregate id**(order.* → orderId, payment.* → orderId, product.updated → productId) — 순서 보장 단위 (c) 4개 payload 확정 필드 + **`product.updated` 신규 payload 필수 필드 표**(`productId, name, price, availableStock, status(품절/비활성 포함 의미), categoryId, updatedAt` — Order 의 `product_cache` 갱신·장바구니 조회 `05 §249-250` 충족분, "등" 금지)
- [ ] **P6.** Saga 재고 예약/차감 경계 확정(D3, F2 해소) — choreography 단계별 이벤트·소유 트랜잭션:
  - `order.created` → **Product** 재고 예약(reserve), Order 는 차감하지 않음
  - `payment.completed` → Product 예약 확정(commit) / `payment.failed`·`order.cancelled` → Product 예약 해제·복구(release)
  - **`OrderCancelledPayload`(+`order.created`)에 복구·예약 대상 items(productId/quantity) 포함** 확정
  - 예약 모델(별도 reservation 상태 vs available/reserved 컬럼) 골격 — 상세 구현은 ④
  - **(토폴로지 매트릭스)** 본 결정은 Product 를 `order.created`(+`payment.completed`/`payment.failed`) 소비자로 만든다 → **ADR-0010 토폴로지(Product=`order.cancelled`만 소비)를 확장**. 토픽 × producer × consumer × consumer-group 매트릭스를 ADR §Decision 에 명시하고, 이 확장이 ADR-0010 §D2 와 모순 아닌 refine 임을 기록 (P8/P12 와 정합)
  - **(실패 경로 계약)** ① `order.created` 는 Payment 도 소비 → Product 예약 확정 전 결제 시작 가능성: 결제 시작 조건/순서 또는 보상 규칙 명시 ② 예약 실패(부분 품목 포함) 시 흐름 ③ 예약 타임아웃 ④ `payment.completed` 후 commit 실패 ⑤ 수동 취소 ↔ `payment.failed` 동시 도착 시 멱등(예약 해제 1회성) 규칙
  - **(토픽 집합 결정 — A/B 택1, 미결 금지)** ADR §Decision 은 예약 실패 신호 경로를 **A) 4토픽 유지**(Product 가 Order 로 실패를 알릴 발행 토픽이 없음을 인지하고 `order.cancelled` 경유 보상이 성립하는지 논증) 또는 **B) 신규 토픽 추가**(예: `stock.reservation.result`, Product 발행 → Order 소비) 중 **하나로 확정**한다. **B 선택 시 조건부 필수**: 그 토픽의 payload 필드(P5)·파티션 키(P5)·producer(Product)/consumer(Order)/consumer group(P8)·ADR-0010 토폴로지 refine·Layer 1 반영(P12, `02 §5`)을 4토픽과 동일 수준으로 함께 확정 (누락 시 완료 조건 미충족)
- [ ] **P7.** retention 정책(D4, L-008/011) — `outbox_events`: PUBLISHED 후 보존 N일 후 삭제(감사 vs 용량 트레이드오프). `processed_events`: **보존기간 ≥ max(Kafka topic retention, 최대 consumer 다운타임, DLQ 수동 재처리 허용 창(`04-design-deep-dive §64-70`), 운영 backfill/replay 창)** 이어야 멱등성 유지(창보다 짧으면 DLQ 재발행/replay 시 중복 처리) → 상한 산정 규칙 명시. 대안: "TTL 이후 수동 재처리는 새 eventId 또는 운영자 중복 확인" 절차 고정. cleanup 메커니즘(스케줄러/배치) 골격
- [ ] **P8.** consumer group 라벨(D5, L-020-2) — `{svc}-svc-{topic}-group` 규약 유지 + 관측성 라벨 독립화(kafka-exporter consumer group / Micrometer `consumer.group` tag). ADR-0009 Kafka lag surface 정합. **P6 토폴로지 매트릭스에 추가되는 Product consumer group(`product-svc-order-created-group` 등)까지 포함**

### Part C — ADR-0012 작성

- [ ] **P9.** `docs/adr/0012-phase4-db-event-saga-contract.md` 신규 — `docs/adr/template.md`/ADR-0011 형식
  - Status: Proposed / Decided: 2026-06-14 / Phase 4
  - Context: 현 데이터·이벤트 상태(P1~P3) + F2 갭 + ADR-0010/0011 입력
  - Decision: DB 경계(P4) + 이벤트 스키마(P5) + Saga 재고 경계(P6) + retention(P7) + consumer group 라벨(P8)
  - Alternatives: 재고 예약 모델(예약상태 분리 vs available/reserved 컬럼), 스키마 버저닝(envelope 필드 vs schema registry)
  - Consequences: 긍정(구현 ②/④/⑤ SSOT) / 부정(분산 데이터 정합 복잡도, 예약 상태 관리, 결과적 일관성) / 후속(구현 ②/④/⑤)
  - References: ADR-0008/0010/0011, 05-data-design, 04-design-deep-dive(§9-3/재고차감/§9-4/§16), payload/Flyway 인용
- [ ] **P10.** ADR Status `Proposed` → `Accepted`

### Part D — 인덱스/참조 동기화

- [ ] **P11.** `docs/adr/README.md` INDEX 행 추가
- [ ] **P12.** Layer 1 정합 — `05-data-design.md`(서비스별 DB 경계 + `OrderCancelled` items / `product.updated` 반영 + `see ADR-0012`), `03-requirements.md §7-2`(재고 예약 경계가 ADR-0010 정정과 정합 재확인), `02-architecture.md §5`(토폴로지 — Product 가 `order.created`/`payment.*` 소비 + `product.updated` 반영), **`04-design-deep-dive.md`**(§9-3 "재고 차감+주문 저장" L104-106, §재고 차감 "전략 A 즉시 차감" L204-218, §9-4 Saga 흐름, §16 CQRS, 파티션 키 전략 — Product 예약 모델·버저닝·파티션 키로 동기화 + `see ADR-0012`)
- [ ] **P13.** `bash docs/consistency-hints.sh` exit 0

### Part E — 문서 동기화

- [ ] **P14.** `docs/progress/phase4-design-roadmap.md §1 A3` 상태 갱신 + `docs/TASKS.md` A3 행 ✅ + `docs/progress/PHASE4.md` A3 엔트리 + 편입 부채(L-008/011/020-2) 처리 표기

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `docs/adr/0012-phase4-db-event-saga-contract.md` | 신규 (Proposed → Accepted) | C |
| `docs/adr/README.md` | INDEX 행 | D (P11) |
| `docs/05-data-design.md` | 서비스별 DB 경계 + 페이로드 반영 + `see ADR-0012` | D (P12) |
| `docs/03-requirements.md` | §7-2 재고 예약 경계 정합 재확인 | D (P12) |
| `docs/02-architecture.md` | §5 토폴로지(Product 소비 확장 + `product.updated`) 반영 | D (P12) |
| `docs/04-design-deep-dive.md` | §9-3/§재고차감 전략 A/§9-4 Saga/§16 CQRS/파티션 키 — Product 예약 모델로 동기화 | D (P12) |
| `docs/progress/phase4-design-roadmap.md` | A3 상태 갱신 | E (P14) |
| `docs/TASKS.md` | A3 행 ✅ + 부채 표기 | E (P14) |
| `docs/progress/PHASE4.md` | A3 엔트리 | E (P14) |

코드/SQL 변경: **0건** (Flyway 분할·payload·consumer 는 구현 ②/④/⑤).

## 5. 검증 방법

### 자동
- `bash docs/consistency-hints.sh` exit 0
- `./gradlew test` 불필요 (코드 변경 0건)

### 수동 (ADR 본문 품질 — 모두 통과해야 Accepted)

**§Context:**
- [ ] **C1**: 현 Flyway/payload/멱등성 상태가 실제 파일 인용으로 기록 (P1~P3)
- [ ] **C2**: F2 갭 + `OrderCancelledPayload` items 부재가 명시됨

**§Decision:**
- [ ] **D1**: DB-per-service 표 — 5서비스 **domain/infra(outbox·processed) 테이블 분리**, 교차 FK 제거 방식, Flyway 분할 전략 확정
- [ ] **D2**: 이벤트 스키마 — envelope 버전 필드 + 호환성 규칙 + 토픽별 파티션 키 + `product.updated` **필수 필드 표**(필드명 명시, "등"/"TBD" 금지)
- [ ] **D3**: Saga 재고 예약/차감 경계 choreography 단계별 확정 + `order.cancelled`/`order.created` items 포함 + **실패 경로 계약**(예약 실패·부분 실패·타임아웃·commit 실패·취소↔결제실패 동시 멱등). **토픽×producer×consumer×group 매트릭스** 포함, ADR-0010 토폴로지 확장(refine) 명시. **예약 실패 신호 토픽 집합 A/B 택1 확정**(B 선택 시 신규 토픽 스키마·파티션키·group·Layer1 동일 수준)
- [ ] **D4**: retention — `processed_events` 보존 ≥ max(topic retention, consumer 다운타임, **DLQ 수동 재처리 창**, backfill 창) 산정 규칙, `outbox_events` 보존 정책 + cleanup 골격
- [ ] **D5**: consumer group 라벨 독립화(Product 신규 group 포함) + ADR-0009 Kafka lag surface 정합
- [ ] **D6**: ADR-0008(trace context)·ADR-0010·ADR-0011 과 모순 없음. `04-design-deep-dive` 포함 Layer 1 드리프트 동기화

**§Alternatives:**
- [ ] **A1**: 재고 예약 모델 / 스키마 버저닝 대안이 동일 비교축, 채택/기각 대칭

**§Consequences:**
- [ ] **CQ1**: 구현 ②/④/⑤ 가 본 ADR 의 어느 표를 입력으로 쓰는지 도출 가능
- [ ] **CQ2**: 결과적 일관성·예약 상태 관리 등 부정 영향 구체적

## 6. 완료 조건

- [ ] P1 ~ P14 전부 체크
- [ ] ADR-0012 파일 존재 + Status: Accepted
- [ ] `bash docs/consistency-hints.sh` exit 0
- [ ] §5 수동 체크리스트 (C1~C2, D1~D6, A1, CQ1~CQ2) 전부 통과
- [ ] `OrderCancelledPayload` items 부재 갭이 D3 에서 해소(페이로드 보강 결정)
- [ ] 토픽×producer×consumer×group 매트릭스 + Product 소비 확장(ADR-0010 refine) 명시
- [ ] 예약 실패 신호 경로 A(4토픽)/B(신규 토픽) 중 택1 확정 — B 선택 시 신규 토픽의 payload·파티션키·producer/consumer/group·Layer1 반영까지 동일 수준 확정
- [ ] 재고 예약 Saga 실패 경로 계약(예약/부분/타임아웃/commit/동시 멱등) 명시
- [ ] retention 보존기간 ≥ max(…, DLQ 수동 재처리 창) 규칙이 명시
- [ ] Layer 1(05/04/03/02) 정합 + ADR-0012 참조 (04-design-deep-dive 포함)
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 (계획 시점) | 기각 대안 | 근거 |
|------|------|-----------|------|
| 재고 차감 경계 | **order.created → Product 예약(reserve)** (choreography) | Order 직접 차감 유지 | Product 가 재고 소유(ADR-0010). Order 직접 차감은 경계 위반 |
| 스키마 버저닝 | **envelope `schemaVersion` + 하위호환 규칙** | schema registry(Avro/Confluent) 즉시 도입 | Phase 4 초기 부담 과다. 규칙 먼저, 도구는 후속 |
| Flyway 분할 | **서비스별 독립 이력** | 단일 이력 유지 | DB-per-service 면 마이그레이션도 서비스별 독립이 정합 |
| `processed_events` retention | **보존 ≥ 재전달 최대 창** | 짧은 고정 TTL | 창보다 짧으면 멱등성 깨짐(중복 처리) — 상한이 곧 안전선 |
| 산출물 범위 | 계약·경계만 (A3) | Flyway SQL·payload·consumer 까지 | 실제 구현은 ②/④/⑤. 리스크 분리 |

## 8. 후속 (Out-of-Scope)

- 구현 ② — 서비스별 DB 분리 (Flyway 분할 SQL, 교차 FK 제거)
- 구현 ④ — Choreography Saga (재고 예약/확정/복구 consumer, `OrderCancelledPayload` 보강)
- 구현 ⑤ — CQRS 로컬 캐시 (`product.updated` 발행/소비, product_cache)
- A4 — Gateway 보안
- schema registry 도입 — Phase 5+ 후보
