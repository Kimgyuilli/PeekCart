# ADR-0010: Phase 4 서비스 분해 — 5개 마이크로서비스 경계 확정

- **Status**: Accepted
- **Decided**: 2026-06-13 (Proposed) → 2026-06-14 (Accepted)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리)

## Context

ADR-0002 는 "모놀리식 → MSA 4단계 진화"를 일반 전략으로 결정했으나, Phase 4 의 **서비스 경계 자체**(몇 개로, 어디를 기준으로 쪼개는가)는 미확정 상태였다. 더 큰 문제는 기존 설계 문서가 경계를 **서로 다르게** 말하고 있다는 점이다.

### C1. 서비스 경계 자기모순 (§4-5 ↔ §5)

| 위치 | 경계 |
|---|---|
| `docs/02-architecture.md §4-5` "MSA 분리 대상 서비스" (L47-49) | **3개**만 나열 — Order · Payment · Notification |
| `docs/02-architecture.md §5` Phase 4 다이어그램 (L95-164) | **5개 전부** 독립 서비스 — User · Product · Order · Payment · Notification, 각각 독립 DB + 이벤트 토폴로지 + Saga 체인 |

§5 다이어그램은 모호하지 않다: Gateway 가 5개 서비스 전부로 라우팅하고(L104-108), DataLayer 에 User/Product/Order/Payment DB 를 분리하며(L142-145), 토픽 4개의 producer/consumer 와 Saga 체인까지 그린다(L118-136). 반면 §4-5 는 "스케일·격리가 절실한 서비스"만 강조한 **불완전·드리프트된 목록**이다.

### C2. 도메인 감사 (현 모놀리스 패키지 — 실제 코드 인용)

| 도메인 | 패키지 | 소유 `@Entity` |
|---|---|---|
| User | `com.peekcart.user` | `User`, `Address`, `RefreshToken` |
| Product | `com.peekcart.product` | `Product`, `Category`, `Inventory` |
| Order | `com.peekcart.order` | `Cart`, `CartItem`, `Order`, `OrderItem` |
| Payment | `com.peekcart.payment` | `Payment`, `WebhookLog` |
| Notification | `com.peekcart.notification` | `Notification` |
| (공통) | `com.peekcart.global` | `OutboxEvent`, `ProcessedEvent` |

5개 도메인이 이미 4-Layered + DDD(ADR-0001) 패키지로 분리되어 있어, 서비스 경계는 패키지 경계를 그대로 승격하면 된다. 재고(`Inventory`)는 **Product 도메인 소유**라는 점이 핵심이다(아래 C4 충돌의 원인).

### C3. 이벤트/토픽 현황 (현 코드 — `KafkaConfig.java` L33-48)

토픽 4개가 이미 운영 중이며(각 partitions=3, + `.dlq`), Outbox 경유 발행:

| 토픽 | 발행(Outbox) | 소비 (현 코드) |
|---|---|---|
| `order.created` | Order (`OrderOutboxEventPublisher:24`) | Payment (`PaymentEventConsumer:31`), Notification (`NotificationConsumer:34`) |
| `payment.completed` | Payment (`PaymentOutboxEventPublisher:22`) | Order (`OrderEventConsumer:37`), Notification (`NotificationConsumer:51`) |
| `payment.failed` | Payment (`PaymentOutboxEventPublisher:23`) | Order (`OrderEventConsumer:54`), Notification (`NotificationConsumer:70`) |
| `order.cancelled` | Order (`OrderOutboxEventPublisher:25`) | Notification (`NotificationConsumer:87`) **— Product consumer 부재** |

### C4. 비준 전 정합해야 할 추가 불일치 3건

- **(F1) Notification DB 경계 불명확** — §5 DataLayer 는 User/Product/Order/Payment DB + Redis 만 그리고 **Notification DB 가 없으나**(L142-146), §4-5 는 Notification 을 분리 대상에 넣고 `05-data-design.md §Notification DB`(L362-380)에는 `notifications` + `processed_events` 가 정의돼 있다. Notification 도메인은 `Notification` 엔티티를 영속하므로 **상태가 있다**.
- **(F2) 재고 차감 소유 트랜잭션 경계 충돌** — `04-design-deep-dive.md §9-3`(L104-106)은 주문 생성 시 Order 가 "재고 차감 + 주문 저장 + Outbox 저장"을 **단일 트랜잭션**으로 처리한다고 명시한다. 그러나 §5 정본은 재고를 **Product 가 소유**(C2) → 분리 후 Order 가 Product 의 재고를 직접 차감할 수 없다. `03-requirements.md §7-2`(L105)의 "재고 차감 시점: 주문 생성 시 즉시 차감(전략 A)"도 동일 전제 위에 있다.
- **(F3) Product→Order 캐시 이벤트 누락** — CQRS 로컬 캐시(`07-roadmap §16` 구현 ⑤, `04-design-deep-dive.md §16` L362-373)는 `product.updated` 이벤트(Product 발행, Order 소비 → `product_cache` 갱신)를 전제하지만, 현 토폴로지(C3)에는 없다.

## Decision

**Phase 4 는 §5 다이어그램을 정본으로, 5개 독립 마이크로서비스 + 서비스별 DB 로 분해한다** (Alt A 채택). 본 ADR 은 **서비스 경계·이벤트 토폴로지·Saga 골격**만 확정하며, 멀티모듈 구조(A2)·이벤트 스키마/네이밍/retention 및 재고 예약 경계(A3)·Gateway 보안(A4)은 후속 ADR 로 위임한다.

### D1. 서비스 경계 (SSOT)

| 서비스 | 책임 | 소유 엔티티 | 소유 DB | 외부연동 |
|---|---|---|---|---|
| **User Service** | 회원, JWT 인증/인가, 주소 | `User`, `Address`, `RefreshToken` | User DB | Redis (토큰 블랙리스트) |
| **Product Service** | 상품, 카테고리, **재고(소유자)** | `Product`, `Category`, `Inventory` | Product DB | Redis (조회 캐시, 재고 분산 락) |
| **Order Service** | 주문, 장바구니 | `Cart`, `CartItem`, `Order`, `OrderItem` | Order DB (+ Outbox) | Redis (락) |
| **Payment Service** | 결제, Toss 연동, 웹훅 | `Payment`, `WebhookLog` | Payment DB (+ Outbox) | Toss Payments API |
| **Notification Service** | 알림 영속 + Slack 발송 (F1: **DB 소유로 확정**) | `Notification` | Notification DB (`notifications` + 자기 `processed_events`) | Slack Webhook |

- **`processed_events`(멱등성)는 공유 테이블이 아니라 이벤트를 소비하는 각 서비스(Order/Payment/Notification)가 자기 DB 에 보유**한다. 현 모놀리스의 단일 `global.ProcessedEvent` 는 분리 시 서비스별로 분할된다(물리 분할 방식은 A3).
- **F1 확정**: Notification Service 는 DB 를 소유한다 → §5 DataLayer 에 NotificationDB 노드를 추가해 `05-data-design.md` 와 정합시킨다(P11).

### D2. 이벤트 토폴로지 (결제/Saga 토픽 4개 확정)

C3 의 현 코드 토폴로지를 §5 정본으로 확정한다. **단, `order.cancelled` 의 소비자에 Product Service 를 추가**한다(현재는 Notification 만 — 재고 복구 주체가 Product 로 이동하기 때문, D3 참조).

| 토픽 | 발행 (Outbox) | 소비 (목표) | 트리거 | 데이터 의존성·식별자 (비스키마) |
|---|---|---|---|---|
| `order.created` | Order | Payment, Notification | 주문 생성 | orderId, userId, 주문 항목(productId·수량) |
| `payment.completed` | Payment | Order, Notification | 결제 성공 | orderId, paymentId |
| `payment.failed` | Payment | Order, Notification | 결제 실패/타임아웃 | orderId, 실패 사유 |
| `order.cancelled` | Order | **Product**, Notification | 주문 취소(보상) | orderId, 복구 대상 항목(productId·수량) |

- **스키마(필드명/필수·선택/버전/파티션 키/retention)는 본 ADR 에서 확정하지 않는다 → A3 입력.** 위 표의 마지막 컬럼은 서비스 간 데이터 의존성 식별 수준의 메모이며, 페이로드 골자/예시를 재확정하지 않는다.
- **A3 입력 (F3)**: CQRS 로컬 캐시를 위한 **Product→Order 캐시 이벤트(`product.updated` 계열)가 필요**하다. 토픽 이름·스키마·발행 시점은 A3 에서 확정(본 ADR 비확정).

### D3. Saga 체인 (Choreography, 중앙 오케스트레이터 없음)

`04-design-deep-dive.md §9-4`(L189-193)의 Phase 4 체인을 서비스 책임 경계로 확정한다:

1. **주문 생성** — Order: 주문 저장 + `order.created` Outbox 발행. ⚠️ **F2**: 현 모놀리스는 재고 차감을 같은 트랜잭션에 포함(§9-3 L104-106)하나, Product 가 재고 소유자이므로 분리 후 Order 의 직접 차감은 불가. **재고 예약/차감 이벤트 경계(예: `order.created` → Product 재고 예약, 또는 사전 예약 패턴)는 A3 에서 확정**한다(본 ADR 은 충돌 기록까지).
2. `order.created` → **Payment** 소비 → Toss 결제 요청
3. **성공** → `payment.completed` → **Order**(주문 확정) + **Notification**
4. **실패/타임아웃** → `payment.failed` → **Order** 소비 → 주문 취소 → `order.cancelled` 발행 → **Product** 소비 → **재고 복구** + **Notification**
   - 각 소비자는 자기 `processed_events` 로 멱등 처리(at-least-once)
   - 보상은 중앙 조정 없이 각 서비스가 이벤트에 자율 반응

### D4. Phase 4 Exit Criteria coverage matrix

본 ADR-0010 은 **경계·토폴로지·Saga 골격만 확정**하며 Exit Criteria 를 직접 닫지 않는다. 각 기준의 owner:

| Phase 4 Exit Criteria (`07-roadmap §16`) | owner |
|---|---|
| 모든 서비스 독립 배포 | A2(멀티모듈) + 구현 ① |
| Saga 보상 트랜잭션 검증(결제실패→주문취소→재고복구) | A3(이벤트 계약) + 구현 ④ |
| Gateway 라우팅 + JWT 인증 | A4(Gateway 보안) + 구현 ③ |
| 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 | A3(`product.updated`) + 구현 ⑤ CQRS |

## Alternatives Considered

### Alternative A: 5개 풀 분해 (§5 다이어그램) — **채택**
- **장점**: §5 가 이미 DB-per-service·토픽 토폴로지·Saga·Gateway 라우팅을 5개 기준으로 완성 → downstream 재설계 0. Gateway 는 어차피 User/Product 라우팅 필요. CQRS 로컬 캐시(구현 ⑤)는 Product 독립을 전제. ADR-0002 의 "MSA 역량 증명" 서사에 부합
- **단점**: 운영·관측성 부담이 서비스 수만큼 증가, 분산 트랜잭션 복잡도
- **채택 사유**: 단점은 Phase 4 의 목적(MSA 경험) 자체와 정합하며, downstream 정합 비용이 0

### Alternative B: 3개만 분리 (§4-5) — User/Product 모놀리스 유지
- **장점**: 운영 부담 최소, 분리 표면 작음
- **단점**: §5 다이어그램·DB 분리·Gateway 라우팅·CQRS(Product→Order)를 전부 3개 기준으로 재작도해야 함. Product 가 모놀리스에 남으면 CQRS 로컬 캐시(구현 ⑤)의 전제가 깨짐. 포트폴리오 "MSA 역량" 서사 약화
- **기각 사유**: downstream 재설계 비용이 크고, 이미 5개로 완성된 설계 자산을 버리는 손해. CQRS 구현 항목과 충돌

## Consequences

### 긍정적 영향
- A2/A3/A4 및 구현 ①~⑥ 의 **단일 입력값(SSOT)** 확보 — 서비스 경계가 문서 전체에서 일관
- §4-5↔§5, F1/F2/F3, `03-requirements §7-2` 의 도식·서술 드리프트를 본 ADR 비준으로 일괄 정합
- ADR-0002 의 추상 전략을 구체 경계로 내려, Phase 4 task 가 추측 없이 착수 가능

### 부정적 영향 / 트레이드오프
- **분산 트랜잭션 복잡도** — 단일 트랜잭션이던 "주문 생성 + 재고 차감"이 서비스 경계를 가로질러 Saga/이벤트로 분해됨(F2). 재고 예약 경계 재설계 필요(A3)
- **관측성 N배** — ADR-0009 가 정의한 관측성 surface(per-service `application=` 태그, ServiceMonitor, outbox 계측)가 서비스별 owner 로 분화. A2/A3 의 입력으로 연결되어야 함
- **운영 표면 증가** — 5개 배포 단위 + 서비스별 DB + consumer group N개화(L-020-2)

### 후속 결정에 미치는 영향
- **A2 (멀티모듈)**: D1 의 5개 서비스 = 5개 서비스 모듈 + `common`. 편입 부채 L-016a, D-016
- **A3 (DB-per-service + 이벤트/Saga 계약)**: D2 스키마/네이밍/retention + F2 재고 예약 경계 + F3 `product.updated` 확정. 편입 부채 L-008/011, L-020-2
- **A4 (Gateway 보안)**: D1 의 User Service 인증을 Gateway 로. 편입 보안 묶음 L-001/002/003/019

## References
- ADR-0001 (4-Layered + DDD), ADR-0002 (모놀리식→MSA 진화 — 본 ADR 이 Phase 4 구체화/Refines), ADR-0009 (관측성 계약 SSOT — Phase 4 surface owner)
- `docs/progress/phase4-design-roadmap.md §0~1` (경계 결정·ADR 시퀀싱)
- `docs/02-architecture.md §4-5`(L47-49) / §5 다이어그램(L95-164) — 정본
- `docs/03-requirements.md §7-2`(L94-106) — Saga/재고 서술 정정 대상
- `docs/04-design-deep-dive.md §9-3`(L104-111, 재고 차감 트랜잭션) / §9-4(L189-193, Phase 4 Saga 체인) / §16(L362-373, CQRS `product.updated`)
- `docs/05-data-design.md §Notification DB`(L362-380)
- 코드: `KafkaConfig.java`(L33-48), `OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`, `OrderEventConsumer`, `PaymentEventConsumer`, `NotificationConsumer`
