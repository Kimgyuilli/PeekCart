# ADR-0043: DLQ 최초 INSERT에 lineage를 완성하는 ID 예약

- **Status**: Accepted
- **Date**: 2026-10-21
- **Deciders**: 사용자 승인 범위의 구현 작업자와 주 작업자
- **관련 Phase**: Phase 5, D-065

## Context

DLQ recorder는 INSERT IGNORE에서 id와 root_record_id를 생략하고 생성된 행을 찾은 뒤 self root 또는 검증된 parent를 배정한다. root_record_id를 단순 NOT NULL로 바꾸면 MySQL은 IGNORE 때문에 누락 값을 0으로 강등할 수 있다. NULL만 갱신하는 assignSelfRoot는 이 값을 고치지 않으며 root 집계에서도 빠진다. 기존 ADR-0020의 expand→writer→backfill→contract 단계 중 writer 준비가 필요하다.

원장 ID는 long이며 API, replay 상관과 incident purge에 쓰인다. 좌표 유니크 충돌은 정상 재전달이고 신규 알림·root 재개방을 반복하지 않아야 한다. JDBC found-rows에서는 no-op ON DUPLICATE KEY UPDATE도 1을 반환하므로 영향 행 수로 신규를 판단할 수 없다.

## Decision

### ID는 같은 트랜잭션의 별도 AUTO_INCREMENT 테이블에서 예약한다

각 서비스 DB에 dead_letter_record_ids(id BIGINT AUTO_INCREMENT PRIMARY KEY)를 추가한다. recorder 트랜잭션에서 한 행을 INSERT하고 JDBC generated key를 받은 뒤 그 예약 행만 DELETE한다. 별도 connection이나 REQUIRES_NEW는 쓰지 않는다. 예약 값은 0보다 크고 Long.MAX_VALUE보다 작아야 하며 범위를 벗어나면 ledger INSERT 전에 실패시킨다. MySQL의 overflow 오류 발생 여부에 의존하지 않는다.

실제 MySQL 8.0.46에서 signed 최대값을 seed한 뒤 행을 지웠을 때 최대값이 다시 반환되는 것을 관측했다. 따라서 명시적인 범위 검사가 필요하다. rollback이나 좌표 중복으로 생기는 ID gap은 허용한다. 번호의 연속성은 기존 계약이 아니다. 예약 테이블에 TRUNCATE 또는 AUTO_INCREMENT 하향 reset을 수행하지 않는다.

### 첫 ledger INSERT에서 id와 root를 함께 쓴다

검증된 replay parent가 있으면 기존 root 잠금과 상관 대조를 유지한다. 독립 incident는 예약 ID를 root_record_id에도 쓰고, child는 잠근 canonical parent ID를 쓴다. INSERT IGNORE는 제거하고 ON DUPLICATE KEY UPDATE id=id로 좌표 중복만 보존한다. 이후 좌표를 잠금 조회하여 저장 ID가 예약 ID인지 비교한다. 신규만 알림·상관 계측·필요한 root 재개방을 수행하고 기존 행은 attempt만 증가시킨다.

좌표 조회는 REPEATABLE READ의 과거 snapshot에 의존하지 않는 current read다. 예상 밖 primary-key 충돌로 해당 좌표가 없으면 예외를 전파하고 트랜잭션을 rollback한다. 이 경우 입력을 정상 적재로 ack하지 않는다. 일반 SQL 제약 오류를 catch해서 정상 중복으로 처리하지 않는다. Spring/JPA rollback-only 의미는 실제 통합 테스트로 따로 검증한다.

예약 테이블의 단순 INSERT는 단일 counter row를 트랜잭션 끝까지 잡는 방식과 다르다. 실제 prototype에서 예약 행을 삭제한 트랜잭션이 미완료이거나 root를 기다리는 동안에도 다른 connection의 예약이 진행됐다. 기존 root→child/outbox 잠금 순서를 유지한다.

### schema와 writer를 먼저 전환하고 contract는 분리한다

네 서비스 migration은 예약 테이블을 만들고 기존 ledger MAX(id)를 seed한 뒤 seed 행을 삭제한다. 빈 ledger는 seed하지 않는다. 이 expand 단계는 root_record_id와 record_kind를 계속 nullable로 유지한다. 새 writer는 예약 테이블이 필요하므로 migration보다 먼저 시작하지 않는다.

운영 전환은 DLQ 적재 writer를 멈춘 구간에서 bootstrap하고 모든 writer를 새 구현으로 바꾼 뒤 재개해야 한다. 구 ledger AUTO_INCREMENT writer와 새 예약 테이블 writer의 혼합 안전성은 주장하지 않는다. 구 writer가 이후 새 ID를 소비했다면 재전환 전에 다시 정지하고 allocator를 ledger 최대값보다 높게 맞추는 검증이 필요하다. 기존 행 ID를 다시 쓰거나 자동으로 충돌을 무시하지 않는다. 이번 작업은 실제 접수 중지·배포·운영 데이터 변경을 수행하지 않는다.

writer와 실제 MySQL 검증이 끝난 뒤 backfill 확인 및 NOT NULL/no DEFAULT contract를 별도 변경으로 다룬다. 운영 timestamp 표본, GKE 권한/좌표, signed gateway 전체 경로와 replay 개방은 이번 결정의 완료 증거가 아니다.

## Alternatives Considered

- 단일 counter row 잠금: 구현은 단순하지만 root→counter와 counter→기존 좌표 대기 사이 교착 위험이 있고, counter-first로 고정하면 root 대기까지 모든 적재를 직렬화한다. 선택하지 않는다.
- MAX(id)+1, information_schema의 다음 ID, 이전 LAST_INSERT_ID: 동시 예약이 아니거나 이전 INSERT의 값을 재사용하므로 선택하지 않는다.
- 임시 0 root 후 UPDATE: 최초 INSERT의 lineage 의미를 충족하지 못한다.
- UUID로 ledger PK 교체: API·상관·기존 데이터 전환까지 범위가 넓어져 선택하지 않는다.
- trigger: 현재 배포 GRANT와 자동 ID 할당 시점 제약에 더해 별도 운영 변경이 필요하므로 선택하지 않는다.

## Consequences

적재마다 예약 INSERT/DELETE 두 문장이 추가되고 번호에 gap이 생긴다. 최대 ID 고갈은 명시적 오류이며 정상 적재처럼 숨기지 않는다. 예약 metadata와 ledger는 함께 백업·복구해야 하며 allocator reset은 허용하지 않는다.

로컬 prototype는 실제 MySQL SQL 동작과 정상 restart를 확인했지만 Spring/JPA, Kafka ack, 비정상 crash recovery 또는 운영 전환의 증거가 아니다. 구현 통합 테스트와 전체 CI를 별도로 요구한다. D-065 전체와 최종 NOT NULL contract는 부분 완료로 유지한다.

## References

- [MySQL 8 AUTO_INCREMENT](https://dev.mysql.com/doc/refman/8.0/en/innodb-auto-increment-handling.html): 잠금 모드, rollback gap, counter 영속성과 비정상 종료 시 미flush 값 재사용 조건. 2026-10-21 확인.
- [조사·실측 기록](../plans/done/D-065-lineage-writer.audit.md)
- ADR-0020, ADR-0021, ADR-0022, ADR-0033

## Update Log

- 2026-10-21, 정정 대상 `564edc0`: no-op upsert와 저장 ID 비교만으로 신규를 판별할 수 있다는 기술 전제가 불충분했다. 예약 counter가 잘못 재설정되어 같은 ID·같은 좌표를 다시 내주면 기존 행도 신규처럼 보인다. 선택한 예약 테이블·최초 lineage·운영 전환 결정은 유지하고, 첫 INSERT 절의 중복 판정 구현을 다음과 같이 정정한다.
- 중복 분기는 기존에 별도 UPDATE로 수행하던 attempt_count 증가를 upsert 안에서 수행한다. 실제 값이 증가한 duplicate는 found-rows 여부와 무관하게 affected=2이고 신규 INSERT는 1이어야 한다. 이 값만으로 판정하지 않으며 current read의 저장 ID와 예약 ID를 함께 비교한다. 신규는 affected=1/ID 동일, 정상 좌표 중복은 affected=2/ID 다름/예약 ID 미점유여야 한다. 같은 ID를 재사용했거나 다른 행과 PK 충돌한 경우 예외와 rollback으로 임시 증가까지 취소한다.
- 정상 재전달의 attempt 증가 횟수와 알림·재개방 억제는 유지한다. overflow·NULL 제약 오류를 IGNORE/catch로 성공 처리하지 않으며 실패 뒤 retry도 검증한다. INSERT 전 미존재 PK의 FOR UPDATE는 gap lock을 더해 정상 동시 적재를 교착시킬 수 있어 선택하지 않는다. 별도 token 컬럼은 이 보완에 필요하지 않다. 수정 계약은 실제 MySQL/JPA 양음 대조와 전체 CI 후에만 완료로 기록한다.
