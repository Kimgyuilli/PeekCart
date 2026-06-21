-- ============================================================
-- payment-service 통합 베이스라인 (DB-per-service, peekcart_payment)
-- 구현 ② PR2 — 단일 공유 스키마 V1~V13 누적 결과 중 payment 소유 테이블 최종 형태.
-- 교차 도메인 FK 제외(PR1 V13: fk_payments_order).
-- 소유 테이블: payments(+strangler 컬럼 V10), webhook_logs, payment_cancellations(V12),
--             outbox_events(+trace V4), processed_events, shedlock
-- ============================================================

CREATE TABLE payments (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL,          -- ID 참조 (교차 FK 제거, ADR-0012 D1)
    user_id           BIGINT       NULL,              -- V10 payment-로컬 소유자 검증 (Seam 1)
    payment_key       VARCHAR(255) NOT NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(30)  NOT NULL,
    ready_for_payment TINYINT(1)   NOT NULL DEFAULT 0,-- V10 reserve→pay 게이트
    method            VARCHAR(30)  NULL,
    approved_at       DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,-- V10 @Version 낙관적 락
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_payment_key (payment_key),
    UNIQUE KEY uk_payments_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE webhook_logs (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key     VARCHAR(255) NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    received_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_logs_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- payment-side 취소 marker (strangler, V12). order.cancelled 선도착 silent-charge 방지.
CREATE TABLE payment_cancellations (
    order_id     BIGINT      NOT NULL,
    cancelled_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Outbox 패턴 (ADR-0008 trace context 포함). payment-service 발행 소유.
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
