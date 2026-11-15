-- 예약 결과(stock.reservation.result reserved=true) 선도착 marker (D-112).
-- Payment 가 아직 없을 때 예약 결과를 orderId 기준으로 영속하고, order.created 처리 때 결제 준비로 적용한 뒤 지운다.
-- 재시도 대신 영속하므로 선행 order.created 가 DLQ 로 간 주문도 파티션을 막지 않는다.
CREATE TABLE payment_reservation_results (
    order_id               BIGINT      NOT NULL,
    reservation_expires_at DATETIME(6) NULL,          -- lease 미부여(구 메시지)면 NULL (ADR-0012 D2)
    received_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
);
