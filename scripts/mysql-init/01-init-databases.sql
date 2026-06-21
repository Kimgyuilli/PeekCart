-- ============================================================
-- DB-per-service (구현 ② PR2) — 로컬/CI smoke 용 MySQL init.
-- compose mysql 첫 부팅 시 /docker-entrypoint-initdb.d 로 실행되어
-- 5 스키마 + 5 계정 + 격리 GRANT(자기 스키마에만) 를 생성한다.
-- ⚠️ 로컬/CI 전용 — 비밀번호 literal 은 application-local.yml/application-k8s.yml 의 기본값(peekcart_<svc>)과 일치.
--    k8s 운영은 ConfigMap(.sh) + Secret(env) 경로로 비밀번호를 주입한다(literal 금지).
-- ============================================================

CREATE DATABASE IF NOT EXISTS `peekcart_user`         CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `peekcart_product`      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `peekcart_order`        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `peekcart_payment`      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `peekcart_notification` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'peekcart_user'@'%'         IDENTIFIED BY 'peekcart_user';
CREATE USER IF NOT EXISTS 'peekcart_product'@'%'      IDENTIFIED BY 'peekcart_product';
CREATE USER IF NOT EXISTS 'peekcart_order'@'%'        IDENTIFIED BY 'peekcart_order';
CREATE USER IF NOT EXISTS 'peekcart_payment'@'%'      IDENTIFIED BY 'peekcart_payment';
CREATE USER IF NOT EXISTS 'peekcart_notification'@'%' IDENTIFIED BY 'peekcart_notification';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `peekcart_user`.*         TO 'peekcart_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `peekcart_product`.*      TO 'peekcart_product'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `peekcart_order`.*        TO 'peekcart_order'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `peekcart_payment`.*      TO 'peekcart_payment'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `peekcart_notification`.* TO 'peekcart_notification'@'%';

FLUSH PRIVILEGES;
