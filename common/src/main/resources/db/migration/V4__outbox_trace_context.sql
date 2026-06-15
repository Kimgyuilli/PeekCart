-- D-010: Outbox 이벤트에 trace context (traceId / userId) 영속화
-- 결정 근거: ADR-0008
-- 기존 행은 NULL 허용 — backfill 불필요, 신규 이벤트부터 채워짐
ALTER TABLE outbox_events
    ADD COLUMN trace_id VARCHAR(64) NULL,
    ADD COLUMN user_id  VARCHAR(64) NULL;
