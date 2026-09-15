-- D-002a 읽기 캐시 배속 측정용 시드 (product 스키마 전용).
--
-- 기존 loadtest/sql/seed.sql 은 **모놀리스 단일 스키마**(users/orders/payments 동거) 기준이라
-- DB-per-service(구현 ②) 이후의 peekcart_product 에는 맞지 않는다. 이 스크립트는 product 소유
-- 테이블만 건드린다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -upeekcart_product -ppeekcart_product peekcart_product < d002a-product-seed.sql

-- TRUNCATE 가 아니라 DELETE 다: 앱 계정(peekcart_product)에는 DROP 권한이 없다
-- (migration-grant-lint 가 강제하는 최소 권한). TRUNCATE 는 DROP 을 요구해 1142 로 거부된다.
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM inventories;
DELETE FROM products;
DELETE FROM categories;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO categories (id, name, parent_id) VALUES (1, 'loadtest', NULL);

-- 상품 1..100 — k6 가 productId 를 1..100 으로 순회하므로 캐시 키가 100개로 분산된다.
SET SESSION cte_max_recursion_depth = 200;
INSERT INTO products (id, category_id, name, description, price, image_url, status, created_at, version)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100)
SELECT n, 1, CONCAT('loadtest-product-', n), CONCAT('D-002a 측정용 상품 ', n),
       1000 + n, NULL, 'ON_SALE', NOW(6), 0
FROM seq;

INSERT INTO inventories (product_id, stock, version, updated_at)
SELECT id, 100000, 0, NOW(6) FROM products;

SELECT COUNT(*) AS products, (SELECT COUNT(*) FROM inventories) AS inventories FROM products;
