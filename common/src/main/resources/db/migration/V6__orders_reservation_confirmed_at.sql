-- 재고 예약 확정 시각 (ADR-0012 D3, strangler-1).
-- stock.reservation.result(reserved=true) 수신 시 기록. 예약 미확정 PENDING 의 타임아웃 수렴 판정에 사용한다.
ALTER TABLE orders ADD COLUMN reservation_confirmed_at DATETIME(6) NULL AFTER ordered_at;
