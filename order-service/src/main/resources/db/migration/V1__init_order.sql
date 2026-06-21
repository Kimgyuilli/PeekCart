-- ============================================================
-- order-service 통합 베이스라인 (DB-per-service, peekcart_order)
-- 구현 ② PR2 — 단일 공유 스키마 V1~V13 누적 결과 중 order 소유 테이블 최종 형태.
-- 교차 도메인 FK 제외(PR1 V13: fk_carts_user·fk_cart_items_product·fk_orders_user·fk_order_items_product).
-- 동일 스키마 내 FK 만 유지(cart_items→carts, order_items→orders).
-- 소유 테이블: carts, cart_items, orders(+strangler 컬럼 V6/V9/V11), order_items,
--             product_price_cache(로컬 CQRS 캐시, V7 — seed 제외: cross-DB → product.updated replay),
--             outbox_events(+trace V4), processed_events, shedlock
-- ============================================================

CREATE TABLE carts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,                  -- ID 참조 (교차 FK 제거, ADR-0012 D1)
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carts_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cart_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    cart_id    BIGINT NOT NULL,
    product_id BIGINT NOT NULL,                       -- ID 참조 (교차 FK 제거)
    quantity   INT    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE orders (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    user_id                   BIGINT       NOT NULL,  -- ID 참조 (교차 FK 제거)
    order_number              VARCHAR(50)  NOT NULL,
    total_amount              BIGINT       NOT NULL,
    status                    VARCHAR(30)  NOT NULL,
    receiver_name             VARCHAR(100) NOT NULL,
    phone                     VARCHAR(20)  NOT NULL,
    zipcode                   VARCHAR(10)  NOT NULL,
    address                   VARCHAR(500) NOT NULL,
    ordered_at                DATETIME(6)  NOT NULL,
    reservation_confirmed_at  DATETIME(6)  NULL,      -- V6 (strangler-1)
    payment_requested_at      DATETIME(6)  NULL,      -- V9 (strangler-3)
    payment_requested_pending TINYINT(1)   NOT NULL DEFAULT 0,  -- V11 (선도착 수렴 marker)
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_number (order_number),
    KEY idx_orders_user_id_status (user_id, status),
    KEY idx_orders_status_ordered_at (status, ordered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,                       -- ID 참조 (교차 FK 제거)
    quantity   INT    NOT NULL,
    unit_price BIGINT NOT NULL COMMENT '주문 시점 가격 스냅샷',
    PRIMARY KEY (id),
    KEY idx_order_items_order_id (order_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Order 로컬 가격 캐시 (CQRS ⑤, strangler-2). product.updated 구독으로 채워짐.
-- 공유 DB seed(INSERT...SELECT FROM products)는 DB 분리로 cross-DB 불가 → 제거. product.updated 전량 replay 로 대체.
CREATE TABLE product_price_cache (
    product_id     BIGINT      NOT NULL PRIMARY KEY,
    unit_price     BIGINT      NOT NULL,
    source_version BIGINT      NOT NULL,              -- 마지막 적용 product.updated version (stale-skip 기준)
    updated_at     DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox 패턴 (ADR-0008 trace context 포함). order-service 발행 소유.
CREATE TABLE outbox_events (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,
    aggregate_id      VARCHAR(50)  NOT NULL,
    event_type        VARCHAR(50)  NOT NULL,
    event_id          VARCHAR(36)  NOT NULL,
    payload           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    published_at      DATETIME(6)  NULL,
    trace_id          VARCHAR(64)  NULL,
    user_id           VARCHAR(64)  NULL,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

CREATE TABLE processed_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id       VARCHAR(36)  NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_processed_event_consumer UNIQUE (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
