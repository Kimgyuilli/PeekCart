-- Order 로컬 가격 캐시 (CQRS ⑤, strangler-2) + Product 순서 키.
-- Order 가 product.updated 를 구독해 단가를 로컬 캐시하고, 주문 생성 시 동기 호출 없이 단가를 읽는다.

-- Product 변경 순서 판정용 낙관락 버전 (product.updated 의 stale-skip 기준).
-- @Version Long: 기존 행은 0 으로 backfill.
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 가격 캐시 read model. source_version 으로 역순/replay 이벤트의 과거 version 덮어쓰기를 막는다.
CREATE TABLE product_price_cache (
    product_id     BIGINT       NOT NULL PRIMARY KEY,
    unit_price     BIGINT       NOT NULL,
    source_version BIGINT       NOT NULL,            -- 마지막 적용 product.updated 의 version (stale-skip 기준)
    updated_at     DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- strangler-only seed: 모놀리스 공유 DB 한정. 현 products 가격/version 을 복사한다.
-- source_version=실제 product.version 이라 이후 실제 product.updated(version 증가)가 자연히 덮는다.
-- peel(DB 분리) 시 cross-DB SELECT 불가 → product.updated 전량 replay 로 대체한다.
INSERT INTO product_price_cache (product_id, unit_price, source_version, updated_at)
SELECT id, price, version, NOW(6) FROM products;
