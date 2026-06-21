# ADR-0016: 재고 예약 모델(별도 stock_reservations 테이블) + Payment 취소 테이블 모델 — ADR-0012 D1/D3 재기록

- **Status**: Accepted
- **Date**: 2026-06-22
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리)
- **관계**: Partially Supersedes [ADR-0012](./0012-phase4-db-event-saga-contract.md) (D1 Product 행·D1 Payment 행·D3 예약 모델)

## Context

ADR-0012 는 DB-per-service 의 테이블 소유표(D1)와 재고 예약 모델(D3)을 결정했다. 그러나 구현 ①(strangler 시리즈, #56~#65)에서 **실제로 빌드된 모델이 ADR-0012 의 결정 표와 두 군데에서 다르다**. 구현 ② PR2(서비스별 DB 물리 분리)에서 각 서비스 마이그레이션을 작성하려면 "코드 정본"과 "ADR 결정"의 드리프트를 먼저 닫아야 한다.

드리프트는 **단순 사실 오류(파일명·수치)가 아니라 D1/D3 결정 자체의 변경**이다. ADR 거버넌스(`README.md` §8·§11-14)는 결정 변경·대안 채택을 **Update Log 로 우회하지 말고 새 ADR + Partially Superseded 로 기록**하라고 규정한다. 본 ADR 이 그 재기록이다.

### 코드 정본 (grep 확정, 2026-06-22)

- **재고 예약**: `product.domain.model.StockReservation` 엔티티 + `stock_reservations` 테이블(V5 신설, V8 2-phase 컬럼)이 실재. orderId 단위 상태머신(RESERVED/CANCEL_REQUESTED/RELEASED/FAILED/CONFIRMED) 원장. `inventories` 에는 예약 컬럼이 없다(stock+version 만).
- **Payment 취소**: `payment_cancellations` 테이블(V12, order.cancelled 선도착 silent-charge 방지 marker)이 실재. `payment_failures` 는 **마이그레이션 0건 = 미구현**.

## Decision

ADR-0012 D1/D3 를 다음으로 재기록한다(코드 정본 채택).

1. **재고 예약 = 별도 `stock_reservations` 테이블**(Product 소유). ADR-0012 D3 가 "재검토 가능"으로 남긴 대안(별도 reservation 테이블)을 정식 채택한다. `inventories` 는 예약 컬럼을 갖지 않는다.
2. **Payment 취소 = `payment_cancellations` 테이블**(Payment 소유). ADR-0012 D1 의 `payment_failures` 는 채택하지 않는다(미구현).
3. ADR-0012 D1 소유표의 Product/Payment 행을 위 코드 정본으로 본다. 그 외 ADR-0012 결정(DB-per-service 경계·교차 FK 제거·이벤트/Saga 계약·retention floor)은 유효.

## Alternatives Considered

### Alternative A: ADR-0012 본문을 Update Log 로 정정
- **장점**: 새 파일 불필요.
- **기각 사유**: 예약 모델 변경(inventories 예약 컬럼 → 별도 테이블)·Payment 테이블 교체는 **결정 변경**이지 사실 오류가 아니다. `README.md` §11-14 가 결정 변경의 Update Log 우회를 금지한다.

### Alternative B: ADR-0012 를 전면 Superseded
- **장점**: 단순.
- **기각 사유**: ADR-0012 의 대부분(경계 원칙·이벤트/Saga D2/D4·retention D5)은 유효하다. 전면 무효화는 과도 → `Partially Superseded` 가 정확.

## Consequences

### 긍정적 영향
- 구현 ② PR2 의 per-service 마이그레이션이 **코드 정본**(stock_reservations / payment_cancellations)을 정확히 반영한다.
- ADR ↔ 코드 드리프트를 거버넌스 규약대로 봉합 → 후속 독자가 "결정"과 "구현"을 혼동하지 않는다.

### 부정적 영향 / 트레이드오프
- ADR-0012 를 읽을 때 본 ADR 의 무효화 범위를 함께 봐야 한다(Partially Superseded 의 본질적 비용).

### 후속 결정에 미치는 영향
- Layer 1(`05-data-design.md §11`)은 Product DB 에 `stock_reservations`·`outbox_events`·`processed_events`, Payment DB 에 `payment_cancellations` 를 반영하고 `(see ADR-0012, ADR-0016)` 로 참조한다.

## References

- [ADR-0012](./0012-phase4-db-event-saga-contract.md) D1/D3
- 구현 코드: `product-service` `StockReservation`(V5/V8) · `payment-service` `payment_cancellations`(V12)
- strangler-1 #56(예약 원장) · 결제 게이트 #57~#63
- `docs/plans/task-impl2-db-per-service.md` P14
