## 2026-06-15 20:57 — GP-2 (loop 1)
- 리뷰 항목: 8건 (P0:2, P1:3, P2:3)
- 사용자 선택: [2] 전체 반영 + 스코프 확장
- 반영: Order 동기 재고변경(차감+복구) 전면 제거·Product 이벤트 소유(release 가드용 예약 원장)·all-or-nothing(REQUIRES_NEW 실패발행)·수렴(타임아웃/운영한계)·envelope 호환생성자·DLQ/trace/멱등 테스트축·ADR 정합 표현 완화(임시 호환 단계)
- raw: .cache/codex-reviews/plan-task-impl-saga1-stock-reservation-1781548434.json
- run_id: plan:20260615T183324Z:381635b9-c30d-45d6-b6e9-4faf01d57d75:2
- note: attempt 1 은 codex 180s 타임아웃(RAW 빈), attempt 2(420s·읽기범위 명시)로 성공

## 2026-06-15 21:04 — GP-2 (loop 2)
- 리뷰 항목: 5건 (P0:1, P1:4) — 1차 5건 해소 확인 후 확장스코프 신규
- 사용자 선택: 전체 반영 (재리뷰 요청)
- 반영: 예약 원장을 orderId 상태머신 테이블(stock_reservations, Flyway)로 승격 — tombstone(CANCEL_REQUESTED) 로 cross-topic 순서(P0#1)·원자 CAS RESERVED→RELEASED 로 double-release(P1#2)·REQUIRES_NEW 실패발행 offset/예외 흐름+source_event_id UNIQUE(P1#3)·scheduler reservationConfirmedAt 조건(P1#4)·pay-before-result known-limit 명시(P1#5). 작업항목 P7→P8 확장
- raw: .cache/codex-reviews/plan-task-impl-saga1-stock-reservation-1781557115.json
- run_id: plan:20260615T205803Z:381635b9-c30d-45d6-b6e9-4faf01d57d75:3

## 2026-06-16 11:06 — GW-2 (work loop 1, split 3 chunks)
- 리뷰 run: work:20260616T103518Z:5a3003ea-81bd-4ecb-94df-22d5279e90cc:1:c1 / :c2 / :c3 (split, aggregate=ok, P0 0)
- 항목: 7건 (P1:4, P2:3)
- 수용: c1:1(reserved=false 가 이미 CANCELLED 주문에 ORD-002→무한retry, 가드 추가) · c3:1+c1:4(빈 items 가 allMatch true 로 예약 오수렴, 거부) · c1:3(누락 schemaVersion→1 정규화 @JsonCreator) · c1:2(부분: 가드/빈items 표적 단위테스트 추가, 전체 Kafka consumer E2E 멱등/replay 는 후속)
- 반려: c2:1(false positive — Order.reservationConfirmedAt 는 chunk1 에 있고 전체 빌드 그린) · c3:2(의도적 트레이드오프 — DLQ 메커니즘 테스트는 consumer 수 비결합 유지)
- 신규 테스트: OrderEventConsumerTest(가드 3케이스) · StockReservationServiceTest.reserve_emptyItems_rejected · KafkaEventEnvelopeTest(legacy→v1)
- verify: ./gradlew build 그린 (전체 테스트 통과)
- diff: .cache/diffs/diff-task-impl-saga1-stock-reservation-1781605406.patch (1709줄)
- note: codex work 리뷰 c2/c3 가 300s 타임아웃 → patch-only 프롬프트+450s 재시도로 성공

## 2026-06-16 11:19 — /ship --execute (PR https://github.com/Kimgyuilli/PeakCart/pull/56)
- 커밋 5 + done-reflection 1 (docs)
- 갱신: PHASE4.md(strangler-1 이력) · TASKS.md(사가 클러스터 행)
- 새 ADR 없음 (ADR-0010/0012 보유)

