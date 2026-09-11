-- 구현 ④-c-2b-4a P23: replay 축 backfill + drain 앵커 컬럼 (ADR-0020 D3 expand→contract · §10.1 #6)
--
-- (1) drain 앵커 `last_replay_settled_at`
--   롤백 전 "재발행분의 소비 재시도가 끝났는가"(drain ⓓ)를 판정할 **내구적 기준시각**이다.
--   reconciler 가 발행 축을 종착시킬 때 DB 시각으로 기록한다(④-c-2b-4a, writer 는 그 하나뿐).
--
--   왜 기존 컬럼을 쓸 수 없는가 — 후보 둘이 계획 리뷰에서 연속 반증됐다:
--     · `replay_deadline` 은 ADR-0020 §D5-3 이 `original_timestamp + dlq-replay-window`(7d 멱등 안전창)로
--       이미 정의했고 재계산을 금지한다. drain 용으로 덮으면 안전창이 초 단위로 축소된다.
--     · `outbox_events.created_at` 은 ① 행이 강제 삭제되면 **부재가 fail-open** 이고(부재는 실패의 증거가
--       아니다 — reconciler 가 그 상태를 강등하지 않고 남긴다) ② INSERT 시각이지 **발행 시각이 아니다**
--       (적체된 PENDING 이 drain 직전에 발행되면 이미 과거다).
--
--   `PUBLISHED` 뿐 아니라 `PUBLISH_FAILED` 로 종착할 때도 기록한다 — poller 는 broker ack 를 받은 뒤
--   상태 저장을 따로 하므로, 저장이 실패해 재시도가 소진되면 **이미 전달된 행이 최종 FAILED** 가 된다.
--   `PUBLISH_FAILED` 는 "발행되지 않았다" 의 증명이 아니라 "발행 여부를 모른다" 이므로 보수적으로 찍는다.
--
--   nullable · DEFAULT 없음 — ④-c-2b-1 이 replay 축에 세운 규칙 그대로.
ALTER TABLE dead_letter_records
    ADD COLUMN last_replay_settled_at DATETIME(6) NULL AFTER last_replay_payload_digest;

-- (2) backfill — ADR-0020 D3 의 expand 단계에서 남긴 NULL 을 정본 값으로 채운다.
--   NOT NULL contract(contract 단계)는 이번 범위가 아니다(계획 §10 R1) — 집계 조건의 IS NULL 분기는 남는다.
--   재실행 안전: 두 UPDATE 모두 IS NULL 조건이라 두 번째 실행은 0행이다.
--
--   root_record_id: NULL 은 "자기 자신이 root" 를 뜻했다(④-c-2b-1 P2). 명시값으로 정규화한다.
UPDATE dead_letter_records SET root_record_id = id WHERE root_record_id IS NULL;

--   record_kind: NULL 은 구버전 writer 가 만든 행이며 도메인으로 해석된다(ADR-0020 D3).
UPDATE outbox_events SET record_kind = 'DOMAIN' WHERE record_kind IS NULL;

-- (3) 잔여 0 검증 — 마이그레이션 도중 구버전 writer 가 INSERT 하면 잔여가 남는다.
--   그때는 **실패시킨다**. 조용히 통과시키면 정규화됐다는 전제로 짠 집계·상관이 그 행에서만 어긋나고,
--   어긋난 사실은 그 행이 문제를 일으킬 때까지 드러나지 않는다.
DROP PROCEDURE IF EXISTS pc_assert_replay_backfill_complete;

DELIMITER //
CREATE PROCEDURE pc_assert_replay_backfill_complete()
BEGIN
    DECLARE ledger_remaining INT DEFAULT 0;
    DECLARE outbox_remaining INT DEFAULT 0;

    SELECT COUNT(*) INTO ledger_remaining FROM dead_letter_records WHERE root_record_id IS NULL;
    SELECT COUNT(*) INTO outbox_remaining FROM outbox_events WHERE record_kind IS NULL;

    IF ledger_remaining > 0 OR outbox_remaining > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'replay backfill 잔여가 0 이 아니다 — 구버전 writer 가 아직 INSERT 하고 있다';
    END IF;
END //
DELIMITER ;

CALL pc_assert_replay_backfill_complete();

DROP PROCEDURE pc_assert_replay_backfill_complete;
