-- 결제 요청 시각 (strangler-3 게이트/타임아웃 기준).
-- PAYMENT_REQUESTED 타임아웃을 ordered_at 이 아닌 결제 요청 시점 기준으로 측정해
-- "생성 15분 경과 주문을 결제하면 진행 중 취소되는" 체계적 race 를 제거한다.
ALTER TABLE orders
    ADD COLUMN payment_requested_at DATETIME(6) NULL AFTER reservation_confirmed_at;

-- 배포 시점에 이미 PAYMENT_REQUESTED 인 기존 행이 새 쿼리에서 영구 제외(취소 안 됨)되는 회귀 방지 backfill.
UPDATE orders
SET payment_requested_at = ordered_at
WHERE status = 'PAYMENT_REQUESTED' AND payment_requested_at IS NULL;
