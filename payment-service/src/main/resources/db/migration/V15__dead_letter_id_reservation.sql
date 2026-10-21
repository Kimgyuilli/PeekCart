-- ADR-0043: 기존 DLQ writer를 멈춘 전환 구간에서 적용한다.
-- root_record_id/record_kind는 이 expand 단계에서 계속 nullable이다.
CREATE TABLE dead_letter_record_ids (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
) ENGINE=InnoDB;

-- 기존 ID를 바꾸지 않고 그 다음 번호부터 예약한다. 빈 원장은 seed하지 않는다.
INSERT INTO dead_letter_record_ids (id)
SELECT MAX(id) FROM dead_letter_records HAVING MAX(id) IS NOT NULL;
DELETE FROM dead_letter_record_ids;
-- 이 테이블의 TRUNCATE/AUTO_INCREMENT 하향 reset은 허용하지 않는다.
