-- Outbox 패턴: 이벤트 유실 방지를 위한 Outbox 테이블
CREATE TABLE outbox_events (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type   VARCHAR(50)  NOT NULL,
    aggregate_id     VARCHAR(50)  NOT NULL,
    event_type       VARCHAR(50)  NOT NULL,
    event_id         VARCHAR(36)  NOT NULL,
    payload          TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count      INT          NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6) NULL,
    created_at       DATETIME(6)  NOT NULL,
    published_at     DATETIME(6)  NULL,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

-- Consumer 멱등성: 중복 소비 방지 테이블
CREATE TABLE processed_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(36)  NOT NULL,
    consumer_group  VARCHAR(100) NOT NULL,
    processed_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_processed_event_consumer UNIQUE (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
