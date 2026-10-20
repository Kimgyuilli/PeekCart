---
grade: L
---
# D-065 DLQ 원장의 최초 INSERT lineage 보장

## 1. 명제

새 원장을 적재한 첫 INSERT에 양수 id와 self/검증된 parent root_record_id가 없거나, 좌표 중복·ID 충돌·rollback을 신규 적재로 오인해 입력을 잃거나, 구 writer와의 혼합 실행을 근거 없이 안전하다고 주장하면 미완이다.

## 2. 범위와 확인한 전제

공유 messaging writer와 네 서비스의 schema 준비, 동시 중복 의미 및 운영 전환 경계가 바뀌므로 L이다. 기존 D-065의 collector 보정과 분리한다. root가 ADR-0043 번호를 예약했다. 아직 구현하지 않았으며 ID 발급 전략은 아래 실험과 코드 추적을 거쳐 결정한다.

| 전제 | 코드 확인 | 처분 |
|---|---|---|
| INSERT IGNORE가 id/root를 생략하고 나중에 self root를 배정한다 | DeadLetterRecordJpaRepository.insertIfAbsent, DeadLetterRecorder.record, DeadLetterRecord.assignSelfRoot | 첫 INSERT 전에 ID를 확보하며 일반 오류 강등을 제거 |
| NULL만 self로 바꾸므로 NOT NULL에서 강등된 0이 남는다 | assignSelfRoot, root 집계 query | 단순 ALTER 금지, 실제 MySQL 음성 대조 |
| 상관 root를 먼저 잠근 뒤 새 child를 적재한다 | DeadLetterRecorder.correlate/record | 새로운 할당 잠금과 기존 root/child/purge 순서를 함께 추적 |
| native duplicate affected-row는 JDBC found-rows 영향을 받는다 | repository Javadoc | 반환 행 수로 신규 판정 금지; allocated ID와 좌표 current read 비교 |
| 운영 writer는 shared recorder 한 곳이고 test fixture는 JPA save도 쓴다 | 네 서비스 main source 및 shared module 검색 | production 경로 전환, legacy test fixture는 명시적 전환기 증거로 유지 |
| 네 서비스 backfill은 존재하나 contract는 없다 | replay_backfill migrations | allocator/writer 우선, NOT NULL은 별도 단계 |

### ID 발급 대안 비교

1. 별도 AUTO_INCREMENT 예약 테이블의 단일 INSERT에서 generated key를 받고 자기 예약 행만 같은 트랜잭션에서 삭제한다. 공유 counter row를 root 대기 동안 잡지 않는다. MySQL 8의 단순 INSERT AUTO-INC 잠금 수명·빈 테이블 재시작 영속성·rollback gap을 실제로 확인한 뒤 우선 후보로 판단한다. REQUIRES_NEW/추가 connection은 쓰지 않는다.
2. 단일 counter row를 잠그는 allocator는 단순하지만 root→counter와 counter→기존 좌표 잠금이 교착할 수 있다. counter-first로 강제하면 다른 root의 DLQ 적재도 직렬화한다. 처리량 절충이 승인되지 않았으므로 우선하지 않는다.
3. MAX(id)+1 단독, information_schema의 다음 값, 이전 LAST_INSERT_ID 값은 동시 ID 예약이 아니다. 0/임시 root 후 UPDATE는 원하는 최초 INSERT 계약이 아니다. UUID로 ledger PK 타입 자체를 바꾸는 것은 기존 API·상관·보관 데이터까지 넓어지므로 이 범위를 벗어난다. trigger는 현재 GRANT와 자동 ID 할당 시점 제약 때문에 우선하지 않는다.

### 전환 경계

schema의 nullable root와 record_kind를 이번 PR에서 제약하지 않는다. 예약 테이블 bootstrap은 기존 ledger 최대 ID보다 커야 하며 구 AUTO_INCREMENT writer와 동시에 사용해도 안전하다고 가정하지 않는다. 실제 접수 중지나 배포는 하지 않고, 전환 절차와 rollback 한계를 문서로 남긴다. 예상 밖 PK 충돌은 좌표 중복으로 버리지 않고 트랜잭션을 실패시켜 입력을 재처리 대상으로 남긴다. gap-free 번호는 요구하지 않는다.

## 3. 작업 항목

- [x] P1. 모든 production insert·상관 child·재전달·root 종결/purge 잠금을 추적하고 ID 할당 대안 및 롤아웃 안전 설계를 확정한다. ADR-0043을 구현 전에 작성한다.
- [x] P2. 승인된 heavy slot에서 실제 MySQL 실험으로 단순 NOT NULL의 현재 결함, ID 예약 잠금/rollback/restart/bootstrap 및 PK 충돌 처분을 검증한다. 결과가 후보를 반증하면 구현 전에 설계를 수정한다.
- [ ] P3. 네 서비스의 additive allocator schema와 공유 writer를 구현한다. 첫 ledger INSERT에 id/root를 명시하고 duplicate/new 판정을 affected-row 수와 분리한다. NOT NULL contract는 넣지 않는다.
- [ ] P4. 실제 MySQL에서 신규 root·상관 child·동시 같은/다른 좌표·원자 rollback·중복 재개방 금지·PK 충돌 비유실·보관/purge 관계를 검증한다. nullable 전환기와 테스트 전용 constrained schema 모두에서 writer를 확인한다.
- [ ] P5. migration 목록을 소비하는 E2E readiness 기대값 및 파일 목록 양음 대조를 갱신한다. migration-grant, integration-container, consistency, writing, diff와 전체 CI를 수행한다.
- [ ] P6. 승인받은 문서 영역·계획·감사에 실제 증거와 운영 미실행을 기록하고 분류 커밋·초안 PR을 만든다. 최종 head의 독립 검토와 전체 CI 후 root만 merge한다.

## 4. 검증과 완료 경계

P2 실험은 로컬 MySQL 8 컨테이너만 사용한다. 특히 한 트랜잭션이 root를 기다리는 동안 다른 ID 예약이 진행되는지를 latch/독립 connection으로 확인한다. rollback 뒤 예약 테이블 잔여 행과 ledger 행이 없어야 하며 이후 유효 ID 할당은 가능해야 한다. 컨테이너 재시작은 소유 테스트 컨테이너만 대상으로 한다. TRUNCATE로 counter를 리셋하지 않으며, 빈 DB 및 signed BIGINT 최대 경계에서 bootstrap/할당이 fail-closed인지 확인한다. RR snapshot이 시작된 뒤 다른 트랜잭션이 좌표를 먼저 적재한 경우 current read로 그 승자를 찾아야 한다. Spring/JPA repository 제약 예외를 catch해서 계속 진행하면 rollback-only가 남을 수 있으므로 prototype SQL 통과와 별도로 실제 Spring 트랜잭션을 검증한다. 기존 최대 ID가 큰 DB와 비어 있는 DB를 각각 bootstrap하고 기존 행 ID를 바꾸지 않는다.

기존 모든 JVM/통합 테스트는 변경 영향에 맞게 실행하고 full CI를 필수로 유지한다. E2E A~F/음성 대조는 CI에서 검증하며 단일 로컬 시나리오를 전체 증거로 확대하지 않는다. 실제 운영 데이터·GKE ACL·P22 timestamp 표본·replay 개방은 수행하지 않는다. record_kind/root_record_id NOT NULL contract 및 signed gateway ADMIN 전체 경로는 후속 별도 작업이며 D-065 전체는 부분 완료로 남는다. TASKS·진행 문서·master plan은 root 소유다.

## 결정 및 prototype 결과

ADR-0043을 production 코드 전에 작성했다. 별도 AUTO_INCREMENT 예약 테이블을 선택했고 명시적 signed 최대값 guard를 둔다. 실제 MySQL SQL prototype 12개와 정상 restart counter 보존을 통과했으나 JPA/Kafka 증거는 아니다. 상세 실패·반증·cleanup은 audit에 기록했다. frozen #196 `5030e31`과 main `545e5dc`를 정상 merge한 baseline `d6cea4d` 위에서 구현한다.
