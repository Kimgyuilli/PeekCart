-- ADR-0036: 기존 가격 전용/seed 행은 미수신 metadata(null)를 보존한다.
-- 다음 더 높은 product.updated version이 정보 전체를 채운다.
ALTER TABLE product_price_cache
    ADD COLUMN product_name VARCHAR(255) NULL,
    ADD COLUMN product_status VARCHAR(32) NULL;
