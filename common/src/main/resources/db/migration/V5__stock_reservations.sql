-- 재고 예약 원장 (ADR-0012 D3, strangler-1).
-- orderId 단위 상태머신: 비동기 예약/복구의 cross-topic 순서·double-release 를 원자 CAS 로 막는다.
CREATE TABLE stock_reservations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,            -- RESERVED / CANCEL_REQUESTED / RELEASED / FAILED
    items           TEXT         NULL,                -- 예약 품목 JSON (복구 시 사용). tombstone 은 NULL
    source_event_id VARCHAR(36)  NULL,                -- order.created eventId (예약 중복 방지). tombstone 은 NULL
    reserved_at     DATETIME(6)  NULL,
    released_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    CONSTRAINT uk_stock_reservation_order UNIQUE (order_id),
    CONSTRAINT uk_stock_reservation_source_event UNIQUE (source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
