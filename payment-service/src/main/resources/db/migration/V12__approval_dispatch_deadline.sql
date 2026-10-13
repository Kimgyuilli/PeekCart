-- ADR-0034: 기존 CLAIMED/UNRESOLVED는 조회 전용이며 deadline을 요구하지 않는다.
ALTER TABLE payment_approvals ADD COLUMN dispatch_deadline DATETIME(6) NULL;
