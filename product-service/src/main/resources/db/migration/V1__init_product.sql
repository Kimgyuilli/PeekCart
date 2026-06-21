-- ============================================================
-- product-service 통합 베이스라인 (DB-per-service, peekcart_product)
-- 구현 ② PR2 — 단일 공유 스키마 V1~V13 누적 결과 중 product 소유 테이블 최종 형태.
-- 교차 도메인 FK 제외(PR1 V13). 동일 스키마 내 FK 만 유지.
-- 소유 테이블: categories, products(+version V7), inventories,
--             stock_reservations(+2phase V8), outbox_events(+trace V4), processed_events, shedlock
-- ============================================================

CREATE TABLE categories (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    name      VARCHAR(100) NOT NULL,
    parent_id BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE products (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    category_id BIGINT       NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT         NULL,
    price       BIGINT       NOT NULL,
    image_url   VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_products_category_status (category_id, status),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventories (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    stock      INT         NOT NULL,
    version    INT         NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventories_product_id (product_id),
    CONSTRAINT fk_inventories_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 재고 예약 원장 (ADR-0012 D3·ADR-0016, strangler-1/3). orderId 단위 상태머신.
CREATE TABLE stock_reservations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,            -- RESERVED / CANCEL_REQUESTED / RELEASED / FAILED / CONFIRMED
    items           TEXT         NULL,                -- 예약 품목 JSON (복구 시 사용). tombstone 은 NULL
    source_event_id VARCHAR(36)  NULL,                -- order.created eventId (예약 중복 방지). tombstone 은 NULL
    reserved_at     DATETIME(6)  NULL,
    confirmed_at    DATETIME(6)  NULL,                -- RESERVED → CONFIRMED 확정(commit) 시각 (V8)
    released_at     DATETIME(6)  NULL,
    compensated_at  DATETIME(6)  NULL,                -- commit-실패 보상 1회성 marker (V8)
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    CONSTRAINT uk_stock_reservation_order UNIQUE (order_id),
    CONSTRAINT uk_stock_reservation_source_event UNIQUE (source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox 패턴 (ADR-0008 trace context 포함). product-service 발행 소유.
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

-- Consumer 멱등성.
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
