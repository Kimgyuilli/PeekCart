-- D-002b'/c run 사이 초기화 (product 스키마). 재고를 되돌리고 예약 원장을 비운다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -upeekcart_product -ppeekcart_product peekcart_product < d002bc-product-reset.sql
--
-- products/categories 는 **지우지 않는다** — 지우면 admin API 로 다시 만들어야 하고
-- (d002bc-seed-products.sh), order 의 product_price_cache 가 새 productId 를 모르게 된다.
-- 재고만 초기값으로 되돌린다.
--
-- STOCK 초기값을 바꾸려면 아래 @stock 을 고친다. P10(오버셀링 정합성)의 "재고 부족 run" 은
-- 이 값을 일부러 낮춰서(예: 50) 만든다 — 계획서 §4 P10.

SET @stock = 100000;

DELETE FROM stock_reservations;
DELETE FROM outbox_events;
DELETE FROM processed_events;
DELETE FROM dead_letter_records;

UPDATE inventories SET stock = @stock, updated_at = NOW(6);

SELECT (SELECT COUNT(*) FROM products)           AS products,
       (SELECT MIN(stock) FROM inventories)      AS min_stock,
       (SELECT COUNT(*) FROM stock_reservations) AS reservations;
