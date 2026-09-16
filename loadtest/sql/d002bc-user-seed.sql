-- D-002b'/c 측정용 사용자 시드 (user 스키마 전용).
--
-- loadtest/sql/seed.sql 은 **모놀리스 단일 스키마** 기준이라 DB-per-service(구현 ②) 이후에는 맞지 않는다
-- (d002a-product-seed.sql 과 같은 사유). 이 스크립트는 user 소유 테이블만 건드린다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -upeekcart_user -ppeekcart_user peekcart_user < d002bc-user-seed.sql
--
-- 사용자 수는 **부하 상한을 정한다** — gateway RateLimiter 가 사용자별 40 req/s 이므로
-- 주문 생성 목표 rps 는 40 × N 아래여야 한다(계획서 §2 V8). 기본 N=200.
--
-- 비밀번호 해시는 "LoadTest123!" 의 BCrypt(cost=10) — seed.sql 과 동일 값이라 users.csv 를 그대로 쓴다.

SET @password_hash = '$2a$10$uyo/cG3tOHyV36gx4aaH6OPKEonaX/ytclNITv/cJhopxacnjS5Qq';
SET @now = NOW(6);
SET SESSION cte_max_recursion_depth = 1000;

-- DELETE 다, TRUNCATE 아니다: 앱 계정에는 DROP 권한이 없다(migration-grant-lint 최소 권한).
-- 자식(refresh_tokens) 먼저.
DELETE FROM refresh_tokens;
DELETE FROM users;

-- 부하 사용자 200명. id 를 1..200 으로 고정해 k6 가 사용자↔VU 를 결정적으로 매핑할 수 있게 한다.
INSERT INTO users (id, email, password_hash, name, role, created_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT n,
       CONCAT('loaduser', LPAD(n, 4, '0'), '@peekcart.test'),
       @password_hash,
       CONCAT('loaduser', LPAD(n, 4, '0')),
       'USER', @now, @now
FROM seq;

-- 상품 시드용 ADMIN 1명. 상품은 SQL 이 아니라 **admin API 로** 만들어야 한다 —
-- 직접 INSERT 는 outbox 에 product.updated 를 남기지 않아 order 의 product_price_cache 가 비고,
-- 그러면 주문 생성이 전부 ORD-007 로 죽는다(OrderCommandService:57). V1__init_order.sql:7 이
-- 이 테이블을 "seed 제외 — cross-DB → product.updated replay" 로 못박아 둔 것과 같은 이유다.
INSERT INTO users (id, email, password_hash, name, role, created_at, updated_at)
VALUES (9001, 'loadadmin@peekcart.test', @password_hash, 'loadadmin', 'ADMIN', @now, @now);

SELECT COUNT(*) AS users, SUM(role = 'ADMIN') AS admins FROM users;
