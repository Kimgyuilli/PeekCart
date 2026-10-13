-- ADR-0034: 기존 CONFIRMED와 새 승인 fence의 환불 복구 권한을 구분한다.
ALTER TABLE stock_reservations ADD COLUMN approval_fenced BOOLEAN NOT NULL DEFAULT FALSE;
