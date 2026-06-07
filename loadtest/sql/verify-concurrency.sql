-- ============================================================
-- 시나리오 2 (1,000 VUser 동시 주문) 정합성 검증 쿼리
--
-- 사전 조건: seed.sql 적용 직후 1회만 시나리오 2 실행.
--            경합 상품 = products.id IN (1001..1010), 초기 재고 = 각 100, 총합 1000
--
-- 확인 항목:
--  A. 경합 상품별 판매량 vs 재고 소진량 일치 (오버셀링 0건)
--  B. 전체 경합 상품 판매량 ≤ 1000 (초기 재고 총합)
--  C. 주문 상태별 분포 (성공/실패/타임아웃)
-- ============================================================

-- A. 경합 상품별 판매량 = (초기재고 100 - 현재재고)
SELECT
    p.id                                      AS product_id,
    p.name,
    100                                       AS initial_stock,
    i.stock                                   AS current_stock,
    (100 - i.stock)                           AS stock_decreased,
    COALESCE(SUM(CASE WHEN o.id IS NOT NULL THEN oi.quantity ELSE 0 END), 0)
                                              AS items_sold,
    CASE
        WHEN (100 - i.stock) = COALESCE(SUM(CASE WHEN o.id IS NOT NULL THEN oi.quantity ELSE 0 END), 0) THEN 'OK'
        ELSE 'MISMATCH'
    END                                       AS consistency
FROM products p
JOIN inventories i ON i.product_id = p.id
LEFT JOIN order_items oi ON oi.product_id = p.id
LEFT JOIN orders o ON o.id = oi.order_id AND o.status NOT IN ('CANCELLED', 'PAYMENT_FAILED')
WHERE p.id BETWEEN 1001 AND 1010
GROUP BY p.id, p.name, i.stock
ORDER BY p.id;

-- B. 경합 상품 총 판매량 (≤ 1000 이어야 함)
SELECT
    COUNT(*)                                  AS active_order_items,
    SUM(oi.quantity)                          AS total_quantity_sold,
    CASE
        WHEN SUM(oi.quantity) <= 1000 THEN 'OK'
        ELSE 'OVERSELL'
    END                                       AS oversell_check
FROM order_items oi
JOIN orders o ON o.id = oi.order_id AND o.status NOT IN ('CANCELLED', 'PAYMENT_FAILED')
WHERE oi.product_id BETWEEN 1001 AND 1010;

-- C. 주문 상태 분포
SELECT status, COUNT(*) AS cnt
FROM orders
GROUP BY status
ORDER BY cnt DESC;
