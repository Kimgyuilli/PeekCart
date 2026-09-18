# task-adr0018-compensation-refund-contract — 보상/환불 트리거 계약 ADR-0018

> 작성: 2026-08-14 (GP-2 반영 개정)
> 관련 Phase: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga
> 선행: ADR-0010(토픽 4개·D3 saga 골격), ADR-0012(D2 이벤트 스키마·D3 예약/보상·D4 토픽 매트릭스·D5 retention), ADR-0015(per-service 관측 계약), ADR-0016(예약/Payment 테이블 모델)
> 후속: 구현 **④-c-1**(P8 환불 요청 경로 구현)
> 관련 ADR: 신규 = **ADR-0018** (Proposed → Accepted)

## 1. 목표

구현 ④ 계획서 **P8**(환불 요청 경로)이 요구하는 **크로스서비스 보상 계약**을 확정한다.

ADR-0012 D3 ④는 최악 경로(결제 승인 후 재고 미확정)의 수렴처를 "**환불 요청 + 운영 알림**(`order.cancelled` 또는 환불 트리거)"이라고만 적고 **트리거의 실체를 미확정으로 남겼다**. 그 결과 현재 코드는 **세 곳**에서 "환불이 필요하다"는 사실을 각자 로컬에 남기고 멈춰 있다(§2.1-a). 세 경로의 영속성 수준도 제각각이라 — 하나는 영속 원장, 하나는 1회성 marker, 하나는 **로그와 Slack 뿐** — 무엇이 종결이고 무엇이 미결인지 계약이 없다. 본 ADR 이 그 연결과 종결을 확정한다.

결정할 항목:

- **(D1) 환불 트리거 이벤트** — 토픽·producer·consumer·consumer group·파티션 키·envelope/payload·DLQ·retention. **감지 기록과 트리거 Outbox 의 원자성**과 **기존 행 backfill** 까지 계약에 포함한다
- **(D2) Payment 환불 상태머신** — `APPROVED` 가 terminal 인 현 enum(§2.1-c)에 환불을 어떻게 넣을지. 상태 집합·전이표·주문당 cardinality(전액 1건)
- **(D3) 멱등성과 crash 경계** — 중복 트리거에서 **동일 논리 환불 1건**을 보장하는 키와, **외부 PG 호출 경계의 crash matrix**(fence 후 호출 전 사망 / PG 성공 후 커밋 전 사망)의 복구 규칙
- **(D4) 종결 표면** — 세 감지 지점의 기록이 각각 무엇을 의미하며, 누가 무엇을 근거로 "해결됨"으로 전이하는가. 회신 이벤트 필요 여부
- **(D5) 재시도·영구 실패 종결** — transient/영구 실패 구분, 재시도 상한, 소진 후 **종결 상태**
- **(D6) 알림·관측 계약** — 운영 알림(Slack)·사용자 알림(Notification)의 역할 분리와, **미해결 backlog 를 관측 가능하게 만드는 카운터/게이지 계약**(ADR-0015 per-service)

본 task 는 **문서만 변경**한다. Toss cancel 클라이언트·Flyway·consumer·상태 전이 코드는 구현 ④-c-1(별도 task)이다.

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (grep, 2026-08-14) — ADR 문구가 아니라 현재 코드 기준

> **초안 정정 (GP-2 #2)**: 초안은 감지 지점을 2곳으로 적었으나 **3곳**이다. Payment 가 자기 경로에서는 이미 감지하고 있다는 사실이 D1 의 대안 지형을 바꾼다.

| # | 검증 | 결과 |
|---|---|---|
| a | 환불 필요의 **감지 지점** | **3곳**이다. ① **Product** `StockReservationService.compensatePaidButUnreserved()` — 원장이 `RELEASED/CANCEL_REQUESTED/FAILED` 인데 `payment.completed` 도착 → `stock_reservations.compensated_at` **1회성 marker** + Slack. ② **Order** `OrderEventConsumer.recordPaidButCancelled()` — 취소된 주문에 `payment.completed` 도착 → `order_compensations(order_id, reason)` 유니크 **영속 원장 `OPEN`** + Slack (④-a 신설). ③ **Payment** `PaymentEventConsumer.handleOrderCancelled()` — `cancelBeforePayment()` 가 APPROVED 를 반환(과금-후-취소) → **Slack + 로그만**(영속 기록 0) |
| a2 | 세 지점의 영속성 비대칭 | Order=영속 원장 / Product=marker(감지 시각만) / **Payment=비영속**. Payment 경로는 소비가 커밋되면 `processed_events` 때문에 재소비도 불가하므로 **현재 상태로는 신호가 소실된다**(④-a 가 Order 측에서 이미 내린 판정과 동일한 문제) |
| b | 계획서 P8 문구 범위 | P8 은 "PAID_BUT_UNRESERVED"(Product)만 지목한다. ④-a 가 Order 측 `PAID_BUT_CANCELLED` 를 추가했고 Payment 측 비영속 경로도 존재하므로 **실제 범위는 3 경로**다. ④-a §2.6 **R-2**(원장이 OPEN 으로 쌓이기만 함)가 닫히려면 Order 경로가 반드시 포함된다 |
| c | Payment 환불 실행 수단 | **없다**. `TossPaymentClient` 에 `confirm(paymentKey, orderId, amount)` 만 존재, 취소 API(`/payments/{paymentKey}/cancel`) 미구현. `PaymentStatus` = `PENDING/APPROVED/FAILED/CANCELLED` 이며 **`APPROVED.canTransitionTo(*) = false`**(terminal) |
| d | Payment 가 가진 환불 입력 | `payments` 에 `payment_key`(unique)·`amount`·`order_id`(unique)·`@Version`. **Payment 는 추가 조회 없이 환불 가능**하며 금액 결정 주체도 Payment 다 |
| e | 토픽 매트릭스 | ADR-0012 D4 의 7개 토픽 전부 **producer 가 정확히 1개**(코드로 재확인). 환불 트리거를 Product·Order 둘 다 발행하면 **이 패턴을 벗어나는 첫 사례**가 된다 |
| f | Outbox 소유 | Product·Order·Payment 모두 자기 스키마에 `outbox_events` 보유(구현 ② PR2). 세 서비스 모두 발행 가능 |
| g | 멱등성 인프라 | `processed_events` UK = `(event_id, consumer_group)` (3서비스 동일). 소비 1회성은 보장하나 **"서로 다른 eventId 로 온 같은 의미의 트리거"는 막지 못한다** |
| h | 인접 부채 경계 | **D-020**(Toss 승인 후 로컬 커밋 실패 → 과금 잔존, 웹훅 reconciliation 부재)은 인접하나 TASKS 에서 Phase 4 후속으로 분리. 본 ADR 은 **"로컬이 APPROVED 로 커밋된 결제"의 환불**만 다루고, 로컬에 흔적이 없는 과금은 D-020 소관 |
| i | retention 창 | ADR-0012 D5 + 각 서비스 `application.yml` = `dlq-replay-window: 7d`. 멱등 창을 넘긴 재발행은 새 `eventId` 라 **`processed_events` 로는 중복을 못 막는다** → D3 의 도메인 키가 그 창 밖에서도 유효해야 한다 |

### 2.2 계약이 답해야 하는 갭 (→ 대응 항목)

1. **이중 트리거** (→ P3) — Product 와 Order 가 **같은 주문에 대해 동시에** 감지할 수 있다. 취소된 주문에 `payment.completed` 가 도착하면 Order 는 `PAID_BUT_CANCELLED` 를, 같은 이벤트를 소비한 Product 는 원장이 `RELEASED` 라 `PAID_BUT_UNRESERVED` 를 기록한다 → 환불 요청 2건. eventId 가 다르므로 `processed_events` 로는 못 막는다(§2.1-g·i)
2. **외부 호출 crash 경계** (→ P3) — 로컬 fence(유니크/CAS)는 동시성만 직렬화한다. **fence 커밋 후 PG 호출 전 사망 → 환불 유실**, **PG 성공 후 로컬 완료 커밋 전 사망 → 재시도 시 중복 호출**. 로컬 수단만으로 "API 호출 1회"는 달성 불가능하다
3. **원장 소유자 ≠ 환불 실행자** (→ P5) — 종결 전이를 시키려면 Payment 의 결과가 원장 소유자에게 돌아와야 한다. 회신 이벤트를 만들 것인가, 원장을 감사 기록으로 고정할 것인가
4. **감지 기록의 의미 불일치** (→ P5) — Product `compensated_at` 은 **환불 전에 찍히는 감지 marker**이지 완료 표시가 아니다. 현 컬럼만으로는 "요청됨"과 "해결됨"을 구분할 수 없다
5. **기존 행 이관** (→ P2) — 배포 시점에 이미 존재하는 `compensated_at`/`OPEN` 행은 신규 감지 시점에만 발행하는 구현에서는 **영원히 트리거를 만들지 못한다**
6. **부분 환불 없음** (→ P4) — 주문 1건 = 결제 1건 = 전액(`payments.order_id` unique). 부분·복수 환불이 범위 밖임을 불변식으로 못박아야 후속 오해가 없다
7. **환불 실패의 종결** (→ P6) — 영구 실패(이미 취소됨/기간 초과)는 재시도로 수렴하지 않는다. **무한 재시도도 DLQ 방치도 종결이 아니다**(ADR-0012 D3 ④)
8. **미해결 backlog 의 관측** (→ P7) — Slack/Notification 은 상태 관측성의 대체물이 아니다. 미해결 건수·최장 age 가 보이지 않으면 종결 계약은 검증 불가다

### 2.3 제약 / 트레이드오프

- **ADR immutable 원칙**: ADR-0012 D4 매트릭스에 토픽을 추가하므로 본문 수정이 아니라 새 ADR + Status 판정이 필요하다(P8)
- **④-a 전례**: lease 계약은 *기존 토픽에 필드 추가*라 ADR 없이 refine 으로 갔다. 본 건은 **새 토픽 + 새 상태머신**이라 같은 취급을 할 수 없다는 것이 이 ADR 의 존재 이유다
- **테스트 환경**: 실제 Toss 취소 API 는 호출할 수 없다(승인된 실거래 필요). 검증은 **클라이언트 계약 테스트 + 상태머신/멱등/crash 복구 테스트**로 한정되며, 외부 PG 장애 주입은 D-020 과 같은 게이트에 묶인다 — Consequences 에 이 한계를 명시한다
- **Slack 가용성**: order/product/payment 의 `SlackPort` 는 배포 구성상 no-op(PR3b 게이팅). **운영 알림은 종결 근거가 될 수 없다** — ④-a 가 원장을 만든 이유와 동일
- **cross-topic 순서 무보장**: 파티션 키가 같은 `orderId` 라도 **서로 다른 토픽 간 순서는 보장되지 않는다**. 요청↔회신, 요청↔기존 `payment.*` 사이의 순서는 상태머신으로 수렴시켜야 한다(P2)

### 2.4 구조 변경 여부 (GP-1 판단)

새 토픽 1개 + Payment 상태머신 확장이며 **모듈 경계·의존 방향·패키지 이동은 없다**. `PLAN-BLINDSPOTS.md` B1(역의존 스윕)은 이동 대상이 없어 해당 없음. **B12**(계약 검사와 fence 혼동)는 P3 의 핵심 축이라 그대로 인용한다 — ④-a 에서 "진입 시 검사는 fence 가 아니다"로 P0 를 맞은 항목이다.

## 3. 작업 항목

- [ ] **P1.** ADR-0018 초안 작성 — `docs/adr/0018-compensation-refund-contract.md`. `template.md` 구조(Context/Decision/Alternatives/Consequences/References) 준수. Context 에 §2.1 코드 검증 표(감지 3지점·영속성 비대칭)를 근거로 인용

- [ ] **P2.** **(D1) 환불 트리거 이벤트 계약 확정** — 다음을 **전부** 결정한다.
  - 토픽 이름 · producer · consumer · consumer group(`{svc}-svc-{topic}-group`) · 파티션 키 · **envelope `schemaVersion` 과 하위호환 규칙**(ADR-0012 D2) · **필수 payload 필드** · **DLQ 토픽·재시도** · **retention 과 D5 7일 창의 관계**(§2.1-i) · **다중 producer 일 때 `NewTopic` 프로비저닝 owner**(현 규약 = producer-owns-topic)
  - **대안 비교 필수**: ① 공용 토픽 1개(2 producer) ② producer 별 토픽 2개 ③ **Payment 로컬 시작**(Order 는 발행하지 않고, Payment 가 이미 소비 중인 `order.cancelled` 의 APPROVED 분기에서 자기 환불을 시작 — §2.1-a③ 이 열어준 대안. Product 만 신규 트리거 발행) ④ 기존 토픽 재사용. 각각 기각 사유를 ADR-0012 D4 의 "1 topic = 1 producer" 패턴(§2.1-e)과 대조해 기록
  - **원자성 불변식**: 감지 기록(marker/원장)과 트리거 Outbox 는 **동일 트랜잭션**이어야 한다(부분 커밋 시 신호 소실)
  - **기존 행 backfill 계약**(§2.2-⑤): 배포 전 생성된 `compensated_at`/`OPEN` 행을 1회씩 트리거하는 방법(마이그레이션 backfill 또는 주기 scanner)을 결정하고, **재실행 시 추가 트리거 0** 을 보장하는 근거를 적는다
  - **cross-topic 순서 무보장**을 명시하고 수렴 책임이 상태머신에 있음을 못박는다

- [ ] **P3.** **(D3) 멱등성 키 + crash 경계 확정** — 보장 문구를 "환불 API 호출 1회"가 아니라 **"동일 논리 환불 1건"**(= 같은 주문에 대해 성립하는 환불 결과가 최대 1건)으로 정의한다.
  - **도메인 키**: `orderId` 또는 `paymentKey` 기반 **DB 유니크 제약 또는 조건부 UPDATE(CAS)**. `processed_events`(eventId 기준)로는 불충분함을 §2.2-①·§2.1-i 로 논증
  - **B12 인용**: "조회 후 호출"은 fence 가 아니다. 동시 두 트리거가 같은 스냅샷을 읽는 경합을 무엇이 막는지 명시
  - **crash matrix**(§2.2-②): (a) fence 커밋 후 PG 호출 전 사망 (b) PG 호출 성공 후 로컬 완료 커밋 전 사망 (c) PG 호출 중 타임아웃(결과 불명) — **각 칸의 복구 규칙**을 결정한다. PG 멱등키(Toss `idempotency` 헤더 또는 안정적 요청 키) 사용 여부, 결과 조회(reconciliation) 로 상태를 확정할지, 그때의 재시도 안전성을 계약으로 적는다
  - 결과가 불명확한 상태를 **어떤 상태로 영속하고 누가 해소하는지** 정의(미결 금지)

- [ ] **P4.** **(D2) Payment 환불 상태머신 확정** — `APPROVED`(terminal) 에서 환불로 나가는 전이를 정의하고 **상태 전이표**(시작·종료 상태 전부)를 만든다.
  - **불변식**: 주문당 전액 환불 **1건**, 부분·복수 환불은 범위 밖(§2.2-⑥). 금액 결정 주체는 Payment(`payments.amount`, §2.1-d)
  - **대안 비교 축을 명시**: ① `PaymentStatus` enum 확장 ② 별도 `payment_refunds` 테이블 — 비교축 = **상태 격리 · 환불 시도 이력 보존 · 재시도/오류 저장 · PG 응답 감사 · 주문당 cardinality · 낙관 락(@Version)과 유니크 fence 의 상호작용 · 기존 `APPROVED` 의미의 호환성**
  - ADR-0016 의 `payment_cancellations` 는 **승인 전 취소 선도착 marker**이지 승인된 결제의 환불 이력이 아니므로, "별도 테이블 선례"로 단순 원용하지 않는다(GP-2 #9)

- [ ] **P5.** **(D4) 종결 표면 확정** — 세 감지 기록의 **현재 의미를 먼저 정정**한 뒤 종결 주체를 정한다.
  - Product `compensated_at` = **환불 전에 찍히는 감지 marker**(완료 아님, §2.2-④) / Order `order_compensations` = 영속 원장 `OPEN` / Payment = **비영속**(§2.1-a2)
  - **대안 비교**: ① 회신 이벤트(예: 환불 결과 토픽)로 원장 소유자가 `OPEN→RESOLVED` 전이 ② 원장은 감사 기록으로 고정하고 종결은 Payment 상태로 정의 ③ 운영자 수동 종결
  - 회신 이벤트를 채택하면 **producer·consumer·group·키·schemaVersion·성공/실패 payload·중복/선도착 처리**까지 D1 과 같은 수준으로 작성한다
  - Payment 측 비영속 경로(§2.1-a③)를 영속화할지 결정한다 — "알림은 종결 근거가 못 된다"(§2.3)와 일관되어야 한다

- [ ] **P6.** **(D5) 재시도·영구 실패 종결 확정** — transient(네트워크·5xx·타임아웃)와 영구 실패(이미 취소됨·기간 초과·금액 불일치)를 **예시와 함께** 구분하고, 재시도 상한과 소진 후 **종결 상태**를 정의한다. DLQ 는 종결이 아니라 **P9(DLQ 원장) 입력**임을 명시하고 ④-c-2 접속점을 적는다

- [ ] **P7.** **(D6) 알림·관측 계약 확정** — 운영 알림(Slack, no-op 가능)과 사용자 알림(Notification)의 역할을 분리하고 각각의 멱등 근거를 명시한다. 사용자 환불 알림 여부를 결정하고, 알린다면 소비 토픽·`NotificationType` 을 지정한다.
  - **관측 계약**(GP-2 #8): 환불 **요청/성공/실패/재시도 소진** 카운터와 **미해결 원장 건수·최장 age** 게이지를 계약 수준에서 정한다. 소유 서비스·메트릭 명명은 ADR-0015 per-service 규약(`<svc>-service`). 구현은 ④-c-1/④-d 로 위임하되 **무엇을 노출해야 하는지는 본 ADR 이 확정**한다

- [ ] **P8.** **ADR-0012 상태 처분 판정** — D4 토픽 매트릭스 추가가 (a) D3 ④가 예고한 트리거의 **구체화(refine, 상태 변경 없음)** 인지 (b) D4 의 **부분 무효화**(`Partially Superseded by ADR-0018`)인지 판정하고 근거를 본문에 남긴다. ADR-0012 는 이미 ADR-0016 이 `Partially Superseded` 를 걸어둔 상태이므로 **무효화 범위가 겹치지 않게** 서술한다

- [ ] **P9.** ADR 인덱스 갱신 — `docs/adr/README.md` 표 맨 아래 0018 행 추가(README 원칙상 ADR 신규 작성의 필수 절차), P8 판정 결과에 따라 ADR-0012 Status 줄 갱신. **Layer 1(`02`/`03`/`04`/`05`) 갱신은 구현 ④-c-1 이후**(코드가 존재할 때)이며 본 task 범위 밖

> **범위 제한 (GP-2 #6 부분 반영)**: 구현 계획서(`task-impl4-choreography-saga.md`)의 P8 선행 표기·④-c 재분할 반영은 **④-c-1 착수 시**로 이연한다. 본 task 산출물은 ADR-0018 + 인덱스/Status 까지다.

## 4. 영향 파일

| 구분 | 경로 | 항목 |
|---|---|---|
| 신규 ADR | `docs/adr/0018-compensation-refund-contract.md` | P1~P8 |
| ADR 인덱스 | `docs/adr/README.md` | P9 |
| 기존 ADR Status | `docs/adr/0012-phase4-db-event-saga-contract.md` (판정 결과에 따라) | P8·P9 |
| audit | `docs/plans/task-adr0018-compensation-refund-contract.audit.md` | 리뷰 이력 |

> 코드 파일은 변경하지 않는다. 구현은 ④-c-1.

## 5. 검증 방법

| 항목 | 통과 기준 (ADR 본문에 존재해야 하는 것) |
|---|---|
| P2 | 토픽이 ADR-0012 D4 형식(토픽·producer·consumer·group·파티션 키) 표로 기재 + `schemaVersion`/payload 필수 필드/DLQ/retention/프로비저닝 owner 전부 명시 · 대안 **4개** 각각 기각 사유 · **감지↔Outbox 동일 트랜잭션 불변식** · **backfill 계약**("기존 행에서도 트리거 1건, 재실행 시 추가 0건"이 판정 가능한 형태) · cross-topic 순서 무보장 문구 |
| P3 | 이중 트리거(§2.2-①)를 **구체적 경로로 서술** + 선택 키가 그 경로와 7일 창 밖에서도 1건을 보장함을 논증 · **crash matrix 3칸 각각의 복구 규칙** · 결과 불명 상태의 영속 형태와 해소 주체 · "조회 후 호출" 류면 **B12 위반으로 기각** |
| P4 | 상태 전이표에 시작·종료 상태가 전부 있고 `APPROVED` 출구가 명시 · 주문당 전액 1건 불변식 · 대안 2개가 **7개 비교축**으로 대조 · `payment_cancellations` 를 선례로 원용하지 않았음 |
| P5 | 세 감지 기록의 현재 의미가 코드대로 정정 기술 · 각각의 종결 주체 지정 · 대안 3개 비교 · 회신 이벤트 채택 시 D1 수준의 계약 존재 · Payment 비영속 경로 처분 명시 |
| P6 | transient/영구 실패 구분 기준 + 예시 · 재시도 상한 · 소진 후가 **종결 상태**로 정의(DLQ 는 종결 아님) · ④-c-2 접속점 |
| P7 | Slack/Notification 역할 분리 + 멱등 근거 · 사용자 알림 여부 결정 · **카운터 4종 + 미해결 backlog 게이지**의 이름·소유 서비스·ADR-0015 규약 준수 명시 |
| P8 | ADR-0012 처분 판정 근거가 본문에 있고 ADR-0016 무효화 범위와 겹치지 않음 |
| 전체 | `docs/adr/README.md` 인덱스에 0018 행 존재 · 기존 ADR 본문 무수정(Status 줄 제외) · **미정(TBD) 항목 0** · `hpx_plan_lint` 그린 · 코드 변경 0 |

## 6. 완료 조건

- ADR-0018 이 `Accepted` 로 작성되고 **D1~D6 전부 결정**됨(미정 항목 0)
- 각 결정에 **대안 비교 + 기각 사유**가 있고, 대안 비교에 **비교축이 명시**됨(형식적 나열 금지)
- Consequences 에 §2.3 검증 한계(Toss 취소 실호출 불가)와 §2.2-② crash 잔여 위험이 명시됨
- ADR-0012 처분이 판정되고 필요 시 Status 갱신 + README 인덱스 갱신
- 코드 변경 0 (문서 전용 PR)

## 7. 다음 단계

- **④-c-1** (P8 구현): Toss cancel 클라이언트 · Payment 환불 상태/Flyway · 트리거 발행 · 소비/멱등/crash 복구 · 종결 전이 · 관측 카운터. **착수 시 구현 계획서 §3 P8 선행 표기 + §7 재분할을 함께 반영**(본 task 에서 이연)
- **④-c-2** (P9·P10·P13 나머지): DLQ 원장 + 전용 소비 경로 + runbook + 인덱스
