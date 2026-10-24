-- D-065 contract: replay 축 판별자 두 컬럼을 NOT NULL 로 닫는다 (ADR-0020 D3·D6-3, ADR-0043)
--
-- 순서: expand(V*__dead_letter_replay_axis.sql·V*__outbox_replay_columns.sql, nullable) → writer 전환(ADR-0043,
--   첫 INSERT 에 root·kind 명시) → backfill(V*__dead_letter_replay_backfill.sql) → 이 파일(contract).
--   네 서비스(order·product·payment·notification)가 같은 바이트의 사본을 갖는다(dead-letter-schema-parity-lint).
--
-- DEFAULT 를 두지 않는다(ADR-0020 D3). DEFAULT 가 있으면 writer 가 값을 빠뜨려도 DB 가 조용히 채워
--   누락을 실패시키려던 명시 계약이 약해진다. strict sql_mode 에서 값을 빠뜨린 INSERT 는 1364 로 실패한다.
--   INSERT IGNORE·non-strict 세션은 0·빈 문자열로 강등할 수 있으므로 NOT NULL 이 구 writer 를 막는다고 보지 않는다.
--
-- 선행 조건은 같은 디렉터리의 beforeMigrate__dead_letter_replay_contract_guard.sql 이 이 파일보다 먼저 확인한다.
--   NULL·0 이하 root 나 DOMAIN/REPLAY 밖 kind 가 있으면 그 callback 이 실패하고 history 에 아무 행도 남지 않는다.
--   자동 보정은 하지 않는다.
--
-- STRICT_ALL_TABLES 를 다시 거는 이유: non-strict 세션의 MODIFY ... NOT NULL 은 남은 NULL 을 0·빈 문자열로 바꾸며
--   성공한다. strict 에서는 가드 이후 끼어든 NULL 도 이 DDL 을 실패시킨다(1138).
--
-- 두 ALTER 는 서로 다른 테이블이라 하나의 원자 단위가 아니다. 둘째가 실패하면 첫째는 남고 failed history 가 남는다.
--   MODIFY 는 같은 정의로 다시 실행해도 결과가 같으므로, 원인을 고친 뒤 실패 행을 repair 로 지우고 재기동한다.
--
-- 롤백 제약: 이 계약 이후 ADR-0043 writer 이전 이미지(INSERT IGNORE 로 root 를 생략)로 내리면 root 가 0 으로
--   강등된다. 그 이미지로는 내리지 않는다.
SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',STRICT_ALL_TABLES');

ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL;

ALTER TABLE outbox_events MODIFY record_kind VARCHAR(10) NOT NULL;
