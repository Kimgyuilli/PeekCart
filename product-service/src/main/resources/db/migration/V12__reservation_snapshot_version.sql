-- D-084: bulk CAS and entity updates share one reservation snapshot version.
ALTER TABLE stock_reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE stock_reservations ADD COLUMN recovery_id VARCHAR(36) NULL;
