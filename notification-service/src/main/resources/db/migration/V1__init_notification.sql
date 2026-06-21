-- ============================================================
-- notification-service 통합 베이스라인 (DB-per-service, peekcart_notification)
-- 구현 ② PR2 — 단일 공유 스키마 V1~V13 누적 결과 중 notification 소유 테이블 최종 형태.
-- 교차 도메인 FK 제외(PR1 V13: fk_notifications_user).
-- 소유 테이블: notifications, processed_events (ADR-0012 D1 — 소비 전용, outbox/shedlock 없음)
-- ============================================================

CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,                 -- ID 참조 (교차 FK 제거, ADR-0012 D1)
    type       VARCHAR(50)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notifications_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Consumer 멱등성 (notification 은 소비 전용 → processed_events 만 소유, outbox 없음).
CREATE TABLE processed_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id       VARCHAR(36)  NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_processed_event_consumer UNIQUE (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
