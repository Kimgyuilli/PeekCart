-- Order↔Payment 동기 결합 제거 (strangler, PR2 사가 클러스터).
-- expand-contract 1단계: payments 에 payment-로컬 게이트용 컬럼 추가.
--   user_id           : 소유자 검증을 OrderPort.verifyOrderOwner 동기 호출 대신 payment-로컬로 (Seam 1).
--   ready_for_payment : stock.reservation.result(reserved=true) 소비로 세팅, reserve→pay 게이트 (ADR-0012 §D3).
--   version           : approve ↔ order.cancelled→CANCELLED 동시 전이의 last-write-wins 차단 (@Version 낙관적 락).
-- NOT NULL 전환(user_id)은 코드 배포·consumer lag 0·null 0 확인 후 후속 마이그레이션 (expand-contract 2단계, V12 이상 — Flyway 순차 적용).

ALTER TABLE payments
    ADD COLUMN user_id           BIGINT  NULL     AFTER order_id,
    ADD COLUMN ready_for_payment TINYINT(1) NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN version           BIGINT  NOT NULL DEFAULT 0;

-- 기존 결제 행의 user_id 를 주문 소유자로 backfill (전환기 — 신규 order.created 는 코드가 채움).
UPDATE payments p
    JOIN orders o ON p.order_id = o.id
SET p.user_id = o.user_id
WHERE p.user_id IS NULL;
