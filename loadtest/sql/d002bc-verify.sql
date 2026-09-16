-- D-002c P10 — 오버셀링 정합성 검증 (product 스키마).
--
-- 불변식: 최종 재고 == 초기 재고 − Σ(RESERVED 상태 예약 수량). 그리고 음수 재고 0건.
-- 점유 상태는 RESERVED 와 **CONFIRMED** 둘 다다 — CONFIRMED 는 commit 확정이라 재고가 그대로 빠져 있다
-- (V1__init_product.sql:47). RELEASED/FAILED/CANCEL_REQUESTED 만 합산에서 빠진다.
--
-- 적용: kubectl -n peekcart exec -i deploy/mysql -- \
--         mysql -upeekcart_product -ppeekcart_product peekcart_product < d002bc-verify.sql
--
-- @initial_stock 은 run 직전 d002bc-product-reset.sql 이 넣은 값과 같아야 한다.

SET @initial_stock = 100000;

-- 1. 음수 재고 — 0 이 아니면 그 자체로 오버셀링이다.
SELECT COUNT(*) AS negative_stock_rows FROM inventories WHERE stock < 0;

-- 2. 예약 상태 분포.
SELECT status, COUNT(*) AS cnt FROM stock_reservations GROUP BY status;

-- 3. 품목별 정합성. diff 가 0 이 아닌 행이 있으면 불변식 위반.
--    reserved 는 RESERVED/CONFIRMED 예약의 items JSON 을 펼쳐 productId 별 수량을 합산한다.
WITH reserved AS (
    SELECT j.product_id, SUM(j.quantity) AS reserved_qty
    FROM stock_reservations r
    JOIN JSON_TABLE(
        r.items, '$[*]' COLUMNS (
            product_id BIGINT PATH '$.productId',
            quantity   INT    PATH '$.quantity'
        )
    ) AS j ON TRUE
    WHERE r.status IN ('RESERVED', 'CONFIRMED')
    GROUP BY j.product_id
)
SELECT i.product_id,
       i.stock                                            AS final_stock,
       COALESCE(res.reserved_qty, 0)                      AS reserved_qty,
       @initial_stock - COALESCE(res.reserved_qty, 0)     AS expected_stock,
       i.stock - (@initial_stock - COALESCE(res.reserved_qty, 0)) AS diff
FROM inventories i
LEFT JOIN reserved res ON res.product_id = i.product_id
HAVING diff <> 0;
