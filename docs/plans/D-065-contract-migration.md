---
grade: L
---
# D-065 ③ — replay 판별자 NOT NULL contract migration (코드 몫)

기준: 2026-10-25, main `6905952`, 브랜치 `debt/d065-contract-migration`(레인 B). 직접 입력은
`docs/plans/done/D-065-flyway-contract.md` §6 마지막 문단(실제 Flyway 실패·재개 증거 뒤 contract 번호를 최신 main 에서
확정하고, 네 서비스 schema parity·전체 migration·E2E EXPECTED_MIGRATIONS·JPA writer·incident/retention 회귀를 함께
검증한다)과 `docs/plans/done/D-065-constraints.md` §6 이다. 운영 writer 정지·전환 증빙, 실제 데이터 provenance·표본,
운영 DDL 적용은 사용자 결정(2026-10-25)으로 범위 밖이며 §미해결에 남긴다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- order·product·payment·notification 중 한 서비스라도 `dead_letter_records.root_record_id` 와 `outbox_events.record_kind`
  를 DEFAULT 없는 NOT NULL 로 닫는 배포 가능한 migration 이 없거나, 네 벌이 바이트 단위로 같지 않다.
- NULL root, 0 이하 root, DOMAIN·REPLAY 밖 kind(NULL·빈 문자열·대소문자 변형 포함)가 있는 DB 에서 contract DDL 이
  실행되거나, 그 값을 자동으로 보정하거나, 거부가 `flyway_schema_history` 에 실패 행을 남긴다.
- 가드가 stored routine·SIGNAL·임시 테이블 등 서비스 계정 8권한 밖의 구문을 쓰거나, 빈 DB(테이블 부재)·contract 이후
  DB 에서 부팅을 막거나, 거부 시 운영자가 원인(위반 행 수와 조치)을 오류 메시지로 알 수 없다.
- 정상 DB(빈 DB, 과거 backfill 로 정규화된 legacy DB)에 contract 가 적용되지 않거나, 적용 뒤 값을 빠뜨린 INSERT 가
  네 서비스 중 하나에서라도 성공한다.
- `rootRecordId IS NULL OR ...`, `CASE WHEN r.rootRecordId IS NULL ...` 처럼 NULL 을 전제로 한 조회·해석이 남거나, NULL
  해석을 고정하던 테스트가 계약 테스트로 바뀌지 않았다. 단순화 전후의 미결 incident 수가 다르다.
- parity lint 가 `MODIFY` 를 읽지 못하거나, contract·가드 파일의 서비스 간 drift, DEFAULT 추가, 다른 replay 축 컬럼의
  NOT NULL 승격, contract 누락을 변이로 넣었을 때 잡지 못한다. grant lint 가 가드 callback 을 검사하지 않는다.
- `dlq_contract_lab.py`·`FlywayContractIntegrationTest`·`saga_e2e.py` EXPECTED_MIGRATIONS·D-092 runbook SQL 이 새
  migration 과 맞지 않아 CI 에서 깨지거나, 깨지지 않더라도 contract 이전 상태만 검증한다.

등급 L: 네 서비스 DB 스키마(공유 계약 표면), 공유 모듈 `peekcart-common-messaging` 의 조회·엔티티, lint 3종, CI lab 2종을
함께 바꾸고, 되돌리려면 네 서비스 migration 과 lab·lint 를 같이 고쳐야 한다. 모듈 이동·경계 변경은 없다.

ADR 판단: 새 외부 의존성·경계·인프라 신호가 없다. NOT NULL·DEFAULT 없음·누락 INSERT 실패 테스트·집계 단순화는
ADR-0020 D3·D6-3 과 ADR-0043 이 이미 정했고, 자동 보정 없는 DDL 전 거부와 일반 SQL 가드는 사용자 확정(2026-10-25)이다.
가드의 형태는 그 결정의 구현이라 이 계획과 migration 주석에 둔다. 새 ADR 은 만들지 않는다.

## 2. 배경

### 2.1 코드 검증

| 전제 | 확인 | 결과 |
|---|---|---|
| 최신 migration 번호 | `ls */src/main/resources/db/migration` | order V14·product V13·payment V15·notification V9 → contract 는 V15·V14·V16·V10 |
| expand 상태 | `V*__dead_letter_replay_axis.sql`(`ADD COLUMN root_record_id BIGINT NULL AFTER id`), `V*__outbox_replay_columns.sql`·notification `V5__outbox_events.sql`(`record_kind VARCHAR(10) NULL`) | 네 벌 바이트 동일(sha256 `6f6b0c5e`·`bcc7c90d`). DEFAULT 없음 |
| 과거 backfill | `V*__dead_letter_replay_backfill.sql`(order V11·product V9·payment V9·notification V7) | NULL root→id, NULL kind→DOMAIN 뒤 STRICT 조건부 UPDATE 로 잔여 NULL 검증. 수정 금지 대상 |
| ID 예약 | `V*__dead_letter_id_reservation.sql`(ADR-0043) | 이 단계까지 두 컬럼 nullable. 네 벌 동일(`3de12b88`) |
| writer | `DeadLetterRecordJpaRepository.insertIfAbsent`(:23-51), `OutboxEvent.create`·`replay`(:105-162) | root·kind 를 첫 INSERT 에 명시. INSERT IGNORE 없음 |
| NULL 전제 조회 | `DeadLetterRecordJpaRepository` :78-110(집계 5종), :123(`CASE WHEN r.rootRecordId IS NULL`), :143, :184, :333(`r.id = :rootId OR`), `DeadLetterRecord.isRoot`(:302) | 단순화 대상. `deleteIncident` 의 `r.id = :rootId` 도 javadoc 상 NULL root 때문에 둔 조건 |
| kind 해석 | `OutboxEvent.isReplay`(:169, `== REPLAY`), `OutboxRecordKind` javadoc | 로직은 그대로 두고 javadoc 만 contract 상태로. `OutboxPollingService`:126-127 주석도 NULL 을 말하지만 레인 C 소유 파일이라 고치지 않는다 |
| JPA 검증 | `spring.jpa.hibernate.ddl-auto: validate`, 4서비스 `spring-boot-starter-validation` | validate 는 nullability 를 보지 않고 check_nullability 는 기본 off. `@Column(nullable = false)` 는 메타데이터다 |
| NULL 전제 테스트 | order `OutboxReplayPublicationIntegrationTest` V-30(:104-123), `DeadLetterIncidentAggregationIntegrationTest` V-18(:155-185, `UPDATE ... root_record_id = NULL`), `DlqReplayCorrelationIntegrationTest` 주석(:47-50, :659-660) | 계약 테스트로 교체·주석 정정 |
| 공유 DB 를 nullable 로 되돌리는 테스트 | order `DeadLetterIdReservationIntegrationTest`:69-89 | finally 에서 `MODIFY root_record_id BIGINT NULL` 로 되돌린다. contract 뒤에는 공유 DB 계약을 깨므로 CHECK 만 추가·제거하도록 바꾼다 |
| kind 를 빠뜨린 raw INSERT | product `OutboxEventCleanupIntegrationTest`:46-53 | contract 뒤 1364 로 실패 → `record_kind` 명시. order `OutboxAtLeastOnceIntegrationTest` 는 이미 명시 |
| D-092 재개 SQL | `NotificationOutboxIntegrationTest`:128, `docs/runbooks/outbox-failed-recovery.md`:56·98, `scripts/d092-ops-fixture.py`(SQL 일치 대조, legacy NULL 행을 재개 대상으로 기대) | `record_kind IS NULL OR` 분기를 셋 함께 제거. NULL 행은 재개하지 않는 음성 대조로 바꾼다 |
| Flyway 버전·순서 | Boot 3.5.12 BOM, gradle 캐시 `flyway-core-11.7.2`. 디컴파일: `Flyway.migrate`(validateOnMigrate 먼저), `DbMigrate.migrate`:66-67(`BEFORE_MIGRATE` 를 lock·pending 전에 호출), :226-238(versioned 실패만 MySQL 에서 실패 행 기록) | beforeMigrate callback 실패는 history 를 남기지 않는다(PR207 실측과 같다). 매 migrate(=매 부팅) 실행된다 |
| SQL callback 탐색 | `SqlScriptCallbackFactory`(같은 locations 에서 이름 접두사로 탐색, 같은 이름이 둘이면 예외), `DefaultCallbackExecutor`:133-134(`Error while executing beforeMigrate callback: ...` 로 감쌈) | 가드는 `db/migration` 안 `beforeMigrate__*.sql`. lab 에서 같은 이름 callback 을 두 location 에 두면 안 된다 |
| 권한 | `scripts/mysql-init/01-init-databases.sql` | SELECT·INSERT·UPDATE·DELETE·CREATE·ALTER·INDEX·REFERENCES 만. CREATE ROUTINE·CREATE TEMPORARY TABLES 없음(PR #105) |
| grant lint 범위 | `scripts/migration-grant-lint.sh`:63 | `V*.sql` 만 본다 → callback 이 검사 밖 |
| parity lint | `scripts/dead-letter-schema-parity-lint.sh` `table_columns`(:92-100 `ADD COLUMN` 만), OUTBOX-PARITY-004(:342, replay 9컬럼 전부 NOT NULL 금지), DLQ-PARITY-011(:195-205, expand 파일 nullable) | MODIFY 미해석, record_kind NOT NULL 이 004 위반이 된다. 011 은 expand 파일 규칙이라 유지 |
| lab | `scripts/tests/dlq_contract_lab.py` `reset`(:91-118, 모든 `V*.sql` 적용 뒤 NULL seed), manifest(:424 `V*.sql`) | contract 가 들어가면 NULL seed 가 실패 → contract 이전 버전까지만 적용하도록 바꾼다 |
| Flyway lab | `FlywayContractIntegrationTest` LATEST(:34-35), nullable YES 단언(:122-123), fixture 가드(:147-166), partial 실패(:177-201) / `scripts/flyway-contract-evidence.py` 76·48·28·28 | 실제 가드·contract 로 재구성. 검증기 수치 함께 갱신 |
| E2E | `scripts/e2e/saga_e2e.py`:68-81, `saga-e2e-smoke.sh --self-test`(ci.yml:465, 파일 목록과 대조) | 네 서비스에 새 번호 추가 |
| PR 트리거 | `.github/workflows/*.yml` paths | `flyway-contract.yml`·`replay-collector.yml`(migration 경로), `approval-recovery.yml`·`signed-gateway.yml`(`*-service/src/main/**`·`peekcart-common-*/src/main/**`), `ci.yml`(항상, lint job 이 DLQ lab 실행) 모두 이 PR 에서 돈다 |
| sql_mode | 레포 설정 없음 | MySQL 8.0 기본(STRICT_TRANS_TABLES). Spring 6.2 `sql-error-codes.xml` 은 1364 를 DataIntegrityViolation 으로 분류 |
| 다른 레인 | 레인 C 는 `OutboxPollingScheduler`·`OutboxPollingService`·outbox 설정 | 이 계획은 `OutboxEvent`·`OutboxRecordKind`·조회·테스트만 바꾼다 |

PLAN-BLINDSPOTS: 구조 변경이 아니어서 B1 이동 처분은 해당 없다. B1b(문자열 결합)는 SQL 문자열을 grep 으로 쓸어 위 표에
반영했다(runbook·d092 fixture·lab·E2E). B4 는 아래 §2.2 에 파일·문장 단위로 적는다. B12 는 §2.3 에 적는다.

### 2.2 설계

**contract migration.** `V{n}__dead_letter_replay_contract.sql`(order V15·product V14·payment V16·notification V10), 네 벌
바이트 동일. `SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',STRICT_ALL_TABLES')` 뒤 `ALTER TABLE dead_letter_records
MODIFY root_record_id BIGINT NOT NULL` 와 `ALTER TABLE outbox_events MODIFY record_kind VARCHAR(10) NOT NULL`. DEFAULT 는 두지
않는다(ADR-0020 D3). strict 를 다시 거는 이유는 non-strict 세션의 MODIFY 가 NULL 을 0·빈 문자열로 바꾸며 성공하기 때문이다.
두 ALTER 는 비트랜잭션이라 둘째가 실패하면 첫째가 남고 실패 행이 남는다(PR207). MODIFY 는 같은 정의로 재실행해도 결과가
같으므로 원인 제거 뒤 repair 와 재기동으로 복구한다고 주석에 적는다.

**가드.** `beforeMigrate__dead_letter_replay_contract_guard.sql`, 네 벌 바이트 동일. 일반 SQL 만 쓴다.

1. `information_schema.COLUMNS` 에서 해당 컬럼이 **있고 nullable 일 때만** 검사문을 고른다. 빈 DB(테이블 부재)와
   contract 이후(NOT NULL)에는 `SELECT 0 INTO @...` 를 고른다. 테이블을 정적으로 참조하면 빈 DB 에서 1146 으로 부팅이
   깨지므로 `PREPARE`/`EXECUTE` 로 고른 문장만 실행한다(권한 불필요).
2. 위반 정의: `root_record_id IS NULL OR root_record_id <= 0`, `record_kind IS NULL OR record_kind COLLATE
   utf8mb4_0900_bin NOT IN ('DOMAIN', 'REPLAY')`. 이진 정렬을 쓰는 이유는 기본 정렬(`_ai_ci`)이 `domain` 을 DOMAIN 과 같게
   보기 때문이다. JPA `EnumType.STRING` 은 `domain` 을 읽지 못한다.
3. 거부: `SET SESSION sql_mode = IF(두 수 = 0, @@SESSION.sql_mode, CONCAT('D065_CONTRACT_GUARD invalid_root_rows=', ...))`.
   통과면 sql_mode 를 같은 값으로 다시 쓰는 무해한 문장이고, 위반이면 MySQL 이 ER_WRONG_VALUE_FOR_VAR(1231)로 그 값을
   돌려준다. 운영자는 부팅 로그에서 `Error while executing beforeMigrate callback: Script
   beforeMigrate__dead_letter_replay_contract_guard.sql failed ... Error Code : 1231 Message : Variable 'sql_mode' can't be
   set to the value of 'D065_CONTRACT_GUARD invalid_root_rows=N invalid_kind_rows=M (...)'` 와 위치·문장을 본다. 메시지에는
   쉼표를 쓰지 않는다(MySQL 이 첫 쉼표 앞 토큰만 돌려준다). 카운트가 비면 `unknown` 으로 표시하고 거부한다(fail-closed).
4. 매 부팅 실행 의미: contract 이전에는 DDL 선행 조건 검사, 이후에는 information_schema 두 번 조회뿐이다. contract 이후의
   0·빈 문자열(INSERT IGNORE 강등)은 부팅에서 막지 않는다. 행 하나로 전 Pod 부팅을 막는 것은 contract 의 목적(DDL 선행
   조건)보다 넓고, 큰 outbox 를 매 부팅 전량 스캔하게 된다. 그 위험은 writer 정지 전제(ADR-0043)로 남긴다.
5. migrate 시작 시점에 **이미 있는** NULL 은 그 뒤 과거 backfill 이 정규화할 수 있어도 거부한다(자동 보정 없음). backfill
   이전 버전 DB 는 직전 릴리스로 먼저 올린 뒤 이 릴리스를 적용한다. 컬럼이 아직 없는 버전(V8 이전)의 행은 같은 실행 안에서
   ADD COLUMN 이 NULL 로 만들고 과거 backfill 이 정규화한다. 그 행은 replay 축이 생기기 전에 쓰였으므로 self root·DOMAIN 이다.

**조회 단순화.** 집계 5종·`findRootIdByReplayAttemptId`·`findPurgeableRootIds` 를 `r.rootRecordId = r.id` 로,
`findRootIdOf` 를 `SELECT r.rootRecordId`, `deleteIncident` 를 `WHERE r.rootRecordId = :rootId` 로, `isRoot()` 를
`id != null && id.equals(rootRecordId)` 로 바꾼다. 엔티티 `root_record_id`·`record_kind` 에 `nullable = false` 를 적는다.

**계약 테스트 위치.** 서비스마다 `global/deadletter/ReplayContractSchemaIntegrationTest`(기존 서비스별
`DeadLetterLedgerIntegrationTest` 관례)를 둔다. 같은 raw INSERT 에서 컬럼만 빠뜨리면 1364, 명시하면 성공(양성 대조),
NULL 로 되돌리는 UPDATE 는 실패. order 에만 Spring Boot classpath 로 가드가 발견되는지 보는 음성 대조를 둔다(공유 DB 를
잠시 nullable 로 바꾸고 finally 에서 복구).

**Flyway lab 재구성(서비스당).** fresh(빈 DB 에 contract 적용, 누락 INSERT 1364, 재검증·재migrate 0건) · upgrade(backfill
직전 버전 + legacy NULL seed 에서 실제 가드가 history 없이 거부, 직전 릴리스 사본으로 backfill 까지 올린 뒤 contract 적용,
옛 조건과 새 조건의 미결 incident 수 일치) · guard(contract 직전 버전 + 서비스별 위반 1종: order root 0, product root -1,
payment kind 빈 문자열, notification kind `domain` 을 넣어 거부, 명시적 fixture 수리 뒤 재개, 실제 contract 파일 사본의
checksum 변조 차단과 원문 복원) · failed(contract 이후 스키마에서 marker 컬럼 추가 뒤 둘째 DDL 실패로 실패 행과 차단).
invocation 은 서비스당 22(migrate 15·validate 7, 의도된 실패 8)로 총 88·60·28·32, 가드 거부 8건(1231)이다.

### 2.3 남는 창(B12)

가드 검사(migrate 시작)와 ALTER 완료 사이에는 수 초의 창이 있다. 그 사이 구 writer 가 값을 빠뜨린 INSERT 를 하면
strict ALTER 가 1138 로 실패해 실패 행이 남고, INSERT IGNORE 를 하면 0·빈 문자열이 contract 를 통과한다. 가드는 창을
줄일 뿐 fence 가 아니며, fence 는 모든 writer 정지(운영 전제, ADR-0043)다. contract 이후 ADR-0043 writer 이전
이미지(INSERT IGNORE 로 root 생략)로 롤백하면 root 가 0 으로 강등되므로 그 이미지로는 내려가지 않는다.

## 3. 작업 항목

- [x] P1. 네 서비스에 contract migration 과 가드 callback 을 바이트 동일 사본으로 추가한다.
- [x] P2. parity lint: `MODIFY [COLUMN]` 해석, contract·가드 파일 존재·바이트 parity, 최종 스키마 규칙(root·kind 는 NOT NULL·
  DEFAULT 없음, 나머지 replay 축은 nullable), 변이 self-test.
- [x] P3. grant lint: `db/migration` 의 모든 `.sql`(callback 포함) 검사와 callback 음성 self-test.
- [x] P4. `peekcart-common-messaging`: 조회 단순화, `isRoot`, 두 엔티티 `nullable = false`, 관련 javadoc.
- [x] P5. JVM 테스트: 서비스별 계약 테스트 4종(order 에 가드 발견 음성 대조), V-30·V-18 교체, 상관 테스트 주석,
  ID 예약 테스트의 NULL 복귀 제거, product cleanup fixture 의 kind 명시.
- [x] P6. Flyway lab(`FlywayContractIntegrationTest`·`FlywayContractProcess`)을 실제 가드·contract 로 재구성하고 검증기와
  검증기 테스트를 갱신한다.
- [x] P7. `dlq_contract_lab.py`: contract 직전까지 적용, 서비스별 가드·contract 적용과 누락 INSERT 거부, order 에서 위반 7종
  거부와 행·스키마 불변, contract 이후 가드 비활성 확인, manifest 에 callback 포함.
- [x] P8. D-092 재개 SQL 의 NULL 분기 제거(runbook·통합 테스트·SQLite fixture).
- [x] P9. EXPECTED_MIGRATIONS 와 `docs/05-data-design.md` 현재 상태 정정.
- [x] P10. 로컬 검증(lint·self-test·unittest·컴파일)을 실행한다.
- [x] P11. PR CI(ci·flyway-contract·replay-collector·approval-recovery·signed-gateway)를 최신 head 에서 확인한다.

## 4. 검증 방법

| 항목 | 실패 주입 | 관측 |
|---|---|---|
| V1 가드 거부 (P1·P6·P7) | contract 직전 DB 에 NULL root·root 0·root -1·NULL kind·빈 kind·`domain`·`UNKNOWN` 을 각각 넣고 가드+contract 실행(lab, 서비스 계정 8권한), backfill 직전 legacy NULL 과 서비스별 위반 1종으로 실제 Flyway migrate | lab: 종료 코드 비0, stderr 에 1231·`D065_CONTRACT_GUARD`, 행·스키마 export 불변. Flyway: invocation 실패, `sqlErrorCode` 1231, history 포함 snapshot 불변(실패 행 0), 명시적 수리 뒤 같은 location 으로 성공 |
| V2 정상 적용 (P1·P6·P7) | 빈 DB, 과거 backfill 로 정규화된 legacy DB, fixture 증거로 수리한 DB | contract 적용·history 일치·두 컬럼 NO·DEFAULT NULL. 옛 조건(`IS NULL OR = id`) 미결 수 = 새 조건(`= id`) 미결 수, 기존 컬럼 값 보존 |
| V3 누락 INSERT (P5·P6·P7) | 네 서비스에서 같은 raw INSERT 의 root 또는 kind 만 빠뜨림, root 를 NULL 로 UPDATE | JVM·Flyway lab·lab 모두 1364(UPDATE 는 1048), 같은 문장에 값을 넣으면 성공 |
| V4 parity lint (P2) | 한 서비스 contract 누락·주석 drift, 네 서비스 root/kind DEFAULT 추가, 다른 replay 컬럼 NOT NULL, root/kind MODIFY 삭제, 가드 누락·drift, 한 서비스에만 `MODIFY`(`COLUMN` 유무 각각) | 각각 지정 코드로 실패(025~031, OUTBOX-PARITY-004·005·006, 002·016). 정상 fixture 는 통과 |
| V5 grant lint (P3) | callback 파일에 `CREATE PROCEDURE` | MIG-GRANT-003. 실제 가드·contract 는 통과 |
| V6 가드 발견·contract 이후 무해 (P5) | order: 공유 DB 의 root 를 잠시 nullable 로 바꾸고 NULL 행을 넣은 뒤 Spring 의 `Flyway.migrate()` | 1231 로 실패, history 행 수 불변, 컬럼 nullable 유지. 복구 뒤 모든 통합 테스트 context 가 가드를 지나 부팅 |
| V7 조회 단순화 (P4) | 기존 집계·상관·purge 회귀(order) | 단순화된 조건으로 green. NULL 은 V3 로 도달 불가 |
| V8 실제 이미지 (P10) | approval-recovery·signed-gateway·E2E 가 서비스 계정으로 빈 DB 부팅 | 새 migration 이 history 에 있고 부팅 성공 |

## 5. 완료 조건

로컬: parity·grant lint 와 self-test, `flyway-contract-evidence-test.py`, `test_dlq_contract_preflight.py`,
`test_dlq_contract_cleanup.py -O`, `d092-ops-fixture.py`, `saga-e2e-smoke.sh --self-test`, `integration-test-container-lint.sh`,
writing lint, consistency precheck, docs-preservation, Java 컴파일과 Docker 없는 단위 테스트가 통과한다.
PR CI 최신 head 에서 ci.yml(lint 의 DLQ lab 포함, test 6 shard, guards, images, e2e, gate), flyway-contract.yml(88 invocation
검증기 통과), replay-collector·approval-recovery·signed-gateway 가 성공한다. Docker·Testcontainers·lab·E2E 는 로컬에서
돌리지 않는다(사용자 지시 2026-10-21).

## 미해결

- 운영 writer 정지·전환 증빙, 실제 데이터 provenance·표본, 운영(GKE) DDL 적용과 가드 거부 시 대응 절차: 사용자 결정
  (2026-10-25)으로 범위 밖. D-065 ③ 의 운영 적용 몫으로 남는다.
- contract 이후 INSERT IGNORE·non-strict 강등(0·빈 문자열)은 DB 가 막지 않는다. writer 정지와 ADR-0043 이전 이미지 롤백
  금지가 운영 전제로 남는다.
- `docs/runbooks/dlq-recovery.md` §7 의 "적재가 INSERT IGNORE 라" 는 ADR-0043 이후 낡은 문구다(이 작업과 무관, 보고만).

## 정정 이력

PR CI 첫 실행(head `e2decca`, run 40125691901·40125691919)이 초안의 두 전제를 반증했다. 상세는 audit 에 있다.

- §2.1 의 "Spring 6.2 `sql-error-codes.xml` 은 1364 를 DataIntegrityViolation 으로 분류" 는 틀렸다. 그 파일은 jar 안의
  기본 목록일 뿐이고, `JdbcAccessor.getExceptionTranslator` 는 사용자 `sql-error-codes.xml` 이 classpath 루트에 없으면
  `SQLExceptionSubclassTranslator` 를 쓴다(spring-jdbc 6.2.17 바이트코드로 확인). 1364 는 SQLState HY000 이라
  `UncategorizedSQLException` 이 된다. 계약 테스트는 `DataAccessException` 과 MySQL 오류 코드로 단언한다.
- §2.1 의 raw INSERT 스윕은 `src/test` 의 문자열만 봤다. order `OrderCompensationRefundIntegrationTest` 와 product
  `StockCompensationRefundIntegrationTest` 는 V5·V4 migration 파일의 backfill DML 을 읽어 현재 스키마에서 실행하는데,
  그 INSERT 는 `record_kind` 이전 판본이라 contract 뒤 1364 로 실패한다(B1b 의 문자열 결합이 main 리소스에 있던 경우).
  INSERT 동안만 실제 체인의 결과인 DOMAIN 을 DEFAULT 로 빌리고 바로 지운다.
- Flyway lab 은 CI 에서 실행되지 않았다(같은 step 의 payment 일반 테스트가 먼저 실패). 로컬 실행에서 backfill 직전 버전의
  snapshot 이 아직 없는 `dead_letter_record_ids` 를 읽어 실패했고, 테이블이 있을 때만 읽도록 고쳤다.
- 사용자 지시 변경(2026-10-25, 레인 재개): 원인 확인에 필요한 서비스·클래스 단위 Testcontainers 실행을 로컬에서 허용한다.
  전체 검증은 계속 PR CI 로 한다.
- §2.1 에서 레인 C 소유라 미룬 `OutboxPollingService`:126-127 주석은 레인 C 의 #242 가 이 파일을 바꾸지 않았음을 root 가
  확인한 뒤 contract 상태로 정정했다(코드 동작 무변경).
