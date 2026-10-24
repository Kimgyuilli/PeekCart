-- D-065 contract guard: V*__dead_letter_replay_contract.sql 의 선행 조건을 DDL 전에 확인한다 (ADR-0020 D3·D6-3)
--
-- 언제 도는가: Flyway beforeMigrate callback 이라 매 migrate(=매 부팅) 시작에 pending migration 보다 먼저 돈다.
--   여기서 실패하면 migration 이 하나도 실행되지 않고 flyway_schema_history 에 실패 행도 남지 않는다.
--   versioned migration 안에서 거부하면 MySQL 은 DDL 을 되돌리지 못해 실패 행이 남고 repair 가 필요해진다.
--
-- 무엇을 거부하는가: 컬럼이 아직 nullable 일 때 이미 있는 다음 행.
--   dead_letter_records.root_record_id 가 NULL 이거나 0 이하
--   outbox_events.record_kind 가 NULL 이거나 정확히 'DOMAIN'·'REPLAY' 가 아님(빈 문자열, 'domain' 같은 대소문자 변형 포함)
--   이진 정렬로 비교한다. 기본 정렬(_ai_ci)은 'domain' 을 DOMAIN 과 같게 보지만 JPA(EnumType.STRING)는 읽지 못한다.
--   자동 보정은 하지 않는다. 뒤에 pending 인 과거 backfill 이 고칠 수 있는 NULL 도 거부한다. backfill 이전 버전 DB 는
--   직전 릴리스로 먼저 올린 뒤 적용한다.
--
-- 언제 아무것도 하지 않는가: 테이블이 아직 없는 빈 DB, 그리고 contract 가 적용된 뒤(컬럼이 NOT NULL).
--   그때는 information_schema 를 두 번 읽을 뿐이다. contract 이후 INSERT IGNORE 가 만든 0·빈 문자열은 부팅에서 막지 않는다.
--   그 위험은 writer 정지 전제(ADR-0043)가 다룬다.
--
-- 왜 동적 SQL 인가: 테이블을 정적으로 참조하면 빈 DB 에서 1146 으로 부팅이 깨진다. PREPARE/EXECUTE 는 권한이 필요 없다.
-- 왜 stored procedure·SIGNAL 이 아닌가: 서비스 계정에 CREATE ROUTINE 이 없다(PR #105, migration-grant-lint).
--
-- 거부할 때 운영자가 보는 것: 부팅이 "Error while executing beforeMigrate callback" 으로 멈추고, 이 파일 이름·줄 번호와
--   Error Code 1231(ER_WRONG_VALUE_FOR_VAR) 메시지 안에 'D065_CONTRACT_GUARD invalid_root_rows=N invalid_kind_rows=M (...)'
--   가 찍힌다. sql_mode 를 바꾸는 것이 목적이 아니다. 통과하면 같은 값을 다시 쓰고, 위반이 있으면 MySQL 이 그 값을
--   오류 메시지에 그대로 돌려준다. 메시지에 쉼표를 쓰지 않는다(MySQL 은 첫 쉼표 앞까지만 돌려준다).
--   조치: 위 조건으로 행을 조회하고, 승인된 provenance 로 그 행을 고치거나 처분한 뒤 재기동한다.
--   거부 시점에는 아무 행도 스키마도 바뀌지 않았다.
SET @dlq_contract_bad_root = NULL, @dlq_contract_bad_kind = NULL;

SET @dlq_contract_guard_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'dead_letter_records'
        AND COLUMN_NAME = 'root_record_id'
        AND IS_NULLABLE = 'YES') = 0,
    'SELECT 0 INTO @dlq_contract_bad_root',
    'SELECT COUNT(*) FROM dead_letter_records WHERE root_record_id IS NULL OR root_record_id <= 0 INTO @dlq_contract_bad_root');
PREPARE dlq_contract_guard FROM @dlq_contract_guard_sql;
EXECUTE dlq_contract_guard;
DEALLOCATE PREPARE dlq_contract_guard;

SET @dlq_contract_guard_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'outbox_events'
        AND COLUMN_NAME = 'record_kind'
        AND IS_NULLABLE = 'YES') = 0,
    'SELECT 0 INTO @dlq_contract_bad_kind',
    'SELECT COUNT(*) FROM outbox_events WHERE record_kind IS NULL OR record_kind COLLATE utf8mb4_0900_bin NOT IN (''DOMAIN'', ''REPLAY'') INTO @dlq_contract_bad_kind');
PREPARE dlq_contract_guard FROM @dlq_contract_guard_sql;
EXECUTE dlq_contract_guard;
DEALLOCATE PREPARE dlq_contract_guard;

SET SESSION sql_mode = IF(
    @dlq_contract_bad_root = 0 AND @dlq_contract_bad_kind = 0,
    @@SESSION.sql_mode,
    CONCAT('D065_CONTRACT_GUARD invalid_root_rows=', COALESCE(@dlq_contract_bad_root, 'unknown'),
           ' invalid_kind_rows=', COALESCE(@dlq_contract_bad_kind, 'unknown'),
           ' (need root_record_id>0 and record_kind DOMAIN or REPLAY. nothing changed. fix rows with provenance then restart)'));

SET @dlq_contract_bad_root = NULL, @dlq_contract_bad_kind = NULL, @dlq_contract_guard_sql = NULL;
