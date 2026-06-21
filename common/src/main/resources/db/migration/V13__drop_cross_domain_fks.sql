-- 구현 ② DB-per-service (PR1) — 교차 도메인 FK 제거 (ADR-0012 D1).
-- DB-per-service 로 스키마가 분리되면 교차 도메인 FK 는 유지 불가(다른 스키마 참조).
-- 무결성은 ID 참조 + 이벤트/로컬 캐시(ADR-0012 D2/D3)로 대체한다(strangler 에서 이미 수용).
-- 컬럼(user_id/product_id/order_id)은 유지하고 제약만 제거한다(ID 참조 보존).
-- 동일 스키마 내 FK(refresh_tokens/addresses→users, products→categories, inventories→products,
-- cart_items→carts, order_items→orders 등)는 유지한다.
ALTER TABLE carts       DROP FOREIGN KEY fk_carts_user;
ALTER TABLE cart_items  DROP FOREIGN KEY fk_cart_items_product;
ALTER TABLE orders      DROP FOREIGN KEY fk_orders_user;
ALTER TABLE order_items DROP FOREIGN KEY fk_order_items_product;
ALTER TABLE payments    DROP FOREIGN KEY fk_payments_order;
ALTER TABLE notifications DROP FOREIGN KEY fk_notifications_user;
