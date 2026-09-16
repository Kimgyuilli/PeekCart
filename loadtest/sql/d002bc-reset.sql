-- D-002b'/c run 사이 초기화 (order 스키마). run 마다 실행해 이전 run 의 주문/사가 상태를 지운다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -upeekcart_order -ppeekcart_order peekcart_order < d002bc-reset.sql
--
-- product_price_cache 는 **지우지 않는다** — 지우면 product.updated 를 다시 replay 해야 하고
-- (재시드), 그 사이 주문이 전부 ORD-007 로 죽는다. 캐시는 run 간 유지가 맞다.

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM cart_items;
DELETE FROM carts;
DELETE FROM outbox_events;
DELETE FROM processed_events;
DELETE FROM order_compensations;
DELETE FROM dead_letter_records;
SET FOREIGN_KEY_CHECKS = 1;

SELECT (SELECT COUNT(*) FROM orders)               AS orders,
       (SELECT COUNT(*) FROM outbox_events)        AS outbox,
       (SELECT COUNT(*) FROM dead_letter_records)  AS dlq,
       (SELECT COUNT(*) FROM product_price_cache)  AS price_cache_kept;
