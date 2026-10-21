-- ADR-0042: 기존 NULL은 외부 인증 때 사용한 숫자 order_id 계약이다. 재작성하지 않는다.
ALTER TABLE payments ADD COLUMN pg_order_id VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_payments_pg_order_id ON payments (pg_order_id);
ALTER TABLE payment_approvals ADD COLUMN pg_order_id VARCHAR(64) NULL;
