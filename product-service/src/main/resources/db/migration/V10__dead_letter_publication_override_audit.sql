-- 구현 ④-c-2b-4b P24: 발행 축 override 감사 컬럼 (ADR-0022 §D4)
--
-- 왜 필요한가 — `PUBLISH_UNKNOWN` 상태값 자체에는 DDL 이 필요 없다. `publication_status` 는 MySQL ENUM 이
--   아니라 VARCHAR(20) 이고 엔티티가 @Enumerated(EnumType.STRING) 이라 새 값이 그대로 들어간다.
--   필요한 것은 **그 전이의 감사 기록**이다.
--
--   이 전이는 운영자가 "broker 좌표와 소비 결과를 확인했고 더 기다릴 근거가 없다" 고 판정한 것이다.
--   그 판정을 로그에만 남기면 프로세스 로그가 사라진 뒤 원장에서 복원할 수 없고, 다음 운영자는
--   `PUBLISH_UNKNOWN` 행을 보고 **무엇을 확인하고 옮겼는지 알 수 없다** — 이 action 의 존재 이유가
--   감사인데 감사가 휘발된다. acknowledge/resolve/discard 가 `*_by`·사유를 영속하는 것과 같은 계약이다.
--
-- 왜 기존 컬럼을 쓰지 않는가 — `last_replay_by`(④-c-2b-4a)는 "마지막 **replay 요청**의 감사 주체" 다.
--   같은 컬럼에 담으면 재발행 이력이 해제 이력에 덮여 둘 다 못 읽는다.
--
-- 시각 컬럼을 만들지 않는 이유 — 해제와 **같은 트랜잭션**에서 `last_replay_settled_at` 을 찍는다
--   (발행 여부를 모르므로 drain ⓓ 앵커도 이때 필요하다). 시각이 이미 있으므로 `*_at` 은 중복이다.
--
-- nullable · DEFAULT 없음 — ④-c-2b-1 이 replay 축에 세운 규칙 그대로. 기존 행은 override 이력이 없다.
ALTER TABLE dead_letter_records
    ADD COLUMN publication_override_by VARCHAR(160) NULL AFTER last_replay_by;

ALTER TABLE dead_letter_records
    ADD COLUMN publication_override_reason VARCHAR(500) NULL AFTER publication_override_by;
