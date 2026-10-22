-- D-078 A: 사가 왕복 지연 원자료 (order 스키마). run 이 배수된 뒤, d002bc-reset.sql 로 지우기 **전에** 실행한다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -B -upeekcart_order -ppeekcart_order peekcart_order < d078-saga-latency.sql > <run>-saga.tsv
--
-- 왕복 = order.created 발행 → product 예약 → stock.reservation.result 발행 → order 확정 기록.
-- 두 시각 모두 order-service 가 기록한다. 경합 overlay 는 order replicas 1 이라 Pod 간 시계 차이가 없다.
-- 예약 실패(재고 부족)·미배수 주문은 reservation_confirmed_at 이 NULL 이다 — 버리지 않고 그대로 내보내고
-- 집계 쪽에서 제외 건수로 센다.
SELECT id,
       status,
       TIMESTAMPDIFF(MICROSECOND, ordered_at, reservation_confirmed_at) AS reserve_roundtrip_us
FROM orders
ORDER BY id;
