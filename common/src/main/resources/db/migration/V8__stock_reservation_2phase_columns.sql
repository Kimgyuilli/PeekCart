-- 2-phase 예약 확정/보상 컬럼 (ADR-0012 D3/④, strangler-3).
-- confirmed_at  : RESERVED → CONFIRMED 확정(commit) 시각. CONFIRMED 는 종결 상태로 release 가 와도 복구하지 않는다.
-- compensated_at: payment.completed 후 원장이 RELEASED/CANCEL_REQUESTED(결제됐으나 재고 미확정) 인 commit-실패 보상 1회성 marker.
ALTER TABLE stock_reservations
    ADD COLUMN confirmed_at   DATETIME(6) NULL AFTER reserved_at,
    ADD COLUMN compensated_at DATETIME(6) NULL AFTER released_at;
