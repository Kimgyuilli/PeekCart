-- Order↔Payment 동기 결합 제거 (strangler) — payment.requested 선도착 수렴용 marker.
-- payment.requested(비동기) 가 Order 의 stock.reservation.result 보다 먼저 도착하면
-- markPaymentRequested() 는 reservationConfirmedAt 미확정으로 ORD-008 을 던진다(DLQ 직행 시 결제는 시작됐는데 앵커 전이 유실).
-- → 선도착 시 DLQ 대신 본 marker 를 기록하고, confirmReservation() 시점에 PAYMENT_REQUESTED 로 수렴한다.
ALTER TABLE orders
    ADD COLUMN payment_requested_pending TINYINT(1) NOT NULL DEFAULT 0 AFTER payment_requested_at;
