-- Order↔Payment 동기 결합 제거 (strangler) — payment-side 취소 marker.
-- order.cancelled 가 order.created 보다 선도착(Payment 미존재)하면 취소 게이트가 유실될 수 있다
-- (재시도 window 초과 시 DLQ → 이후 Payment 가 PENDING+ready 로 생성되면 결제 통과 = silent charge).
-- → orderId 기준 취소 marker 를 영속화해, Payment 생성(handleOrderCreated) 시점에 즉시 CANCELLED 로 적용한다.
CREATE TABLE payment_cancellations (
    order_id     BIGINT      NOT NULL,
    cancelled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
);
