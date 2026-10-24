# D-065 contract migration audit

## 2026-10-25 03:55 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트: `hpx_codex_allowed` 1행 `blocked`, source `file`, origin `.cache/codex-off`. 등급 `hpx_plan_grade` L.
- 전제 확인: 최신 migration 번호(V14/V13/V15/V9), expand·backfill·ID 예약 네 벌의 sha256 동일, writer 의 root·kind 명시,
  NULL 전제 조회·테스트 위치, D-092 SQL 결합(runbook·통합 테스트·SQLite fixture), lab 과 Flyway lab 이 contract 이전
  상태만 가정하는 지점, PR 트리거 경로를 파일로 확인했다. Flyway 11.7.2 의 `BEFORE_MIGRATE` 호출 위치와 실패 행 기록
  조건, SQL callback 탐색·중복 이름 예외는 gradle 캐시 jar 를 디컴파일해 확인했다.
- 레인 경계: 레인 C 소유 `OutboxPollingService` 의 NULL 주석은 고치지 않고 §미해결에 남긴다.

## 2026-10-25 04:19 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 구현은 base `6905952` 에서 커밋 7건(plans·db·lint·deadletter·test·lab·data)으로 했고, push 전에 main `2ffa8d6`(#240,
  harness 파일만 변경, 겹치는 파일 없음) 위로 충돌 없이 rebase 했다. contract·가드 sha256 은 네 벌 각각 `1dba5ea1`·`78113d63`
  로 같다.
- 검증 V4(parity lint): 본 실행 통과, self-test 31종 통과(기존 19 + contract 10 + MODIFY 2). 변이 3종을 직접 넣어 확인했다.
  MODIFY 를 읽지 않게 되돌린 lint 는 실제 레포에서 029·OUTBOX-006 을 8건 내고 self-test 1 에서 실패했다. 029 검사를 끈 lint 는
  contract-7, 가드 존재 검사를 끈 lint 는 contract-9 에서 실패했다.
- 검증 V5(grant lint): 대상이 V 파일 53개에서 `db/migration` 의 `.sql` 61개(contract 4·가드 4 포함)로 늘었고 통과했다.
  self-test 8종 통과. glob 을 `V*.sql` 로 되돌린 변이는 self-test 7(callback 의 CREATE PROCEDURE)에서 실패했다.
- 검증 V1·V3 의 실행 몫: Flyway 검증기 hermetic 대조 19종(신규 4종: 가드 원문 변조, contract 복원 불일치, 가드 거부 증거
  누락, 메시지 marker 누락) 통과. `test_dlq_contract_preflight` 10, `test_dlq_contract_cleanup -O` 7(Docker 2 skip),
  `d092-ops-fixture`(legacy NULL kind 행 재개 0행), `saga-e2e-smoke --self-test`(네 서비스 manifest 일치·누락·추가·대체 거부)
  통과. `migration_set` 이 네 서비스 모두 contract 를 마지막 단일 migration 으로 판정했다.
- ci.yml lint job 명령 61개를 로컬에서 차례로 실행해 61개 모두 exit 0(문서 보존 base `6905952`, harness context 포함).
  consistency precheck ok(warnings 0). 커밋 문체 lint 7건 통과.
- Java: 네 서비스와 공유 모듈의 main·test 컴파일 성공. `:peekcart-common-messaging:test` 20 tests, 실패 0.
  Testcontainers 통합 테스트, Flyway lab, DLQ lab, E2E 는 로컬에서 돌리지 않았다(사용자 지시 2026-10-21). PR CI 로 확인한다.
- 계획 대비 변경: P10 을 로컬 검증(P10)과 PR CI 확인(P11)으로 나눴다. 가드 거부 오류 코드 1231 과 메시지 전문은 실제
  MySQL 에서 아직 관측하지 않았다. CI 결과로 확정한다.

## 2026-10-25 05:13 — PR CI 1차 실패와 수정
- PR #244 head `e2decca`. 통과: CI 의 lint(DLQ lab 포함)·guards·images 6·test(user, platform)·e2e 3러너, Actual replay
  collector adapters(40125691929), Signed approval recovery(40125691909), Signed gateway admission(40125691994).
  실패: CI 40125691901 의 test(order·product·payment·notification)와 gate, Flyway contract 40125691919 의 actual-flyway.
- 원인 1(8건): 네 서비스 `ReplayContractSchemaIntegrationTest` 의 원장·outbox 누락 INSERT 테스트. JUnit artifact 의 메시지는
  `UncategorizedSQLException ... SQL state [HY000]; error code [1364]; Field 'root_record_id' doesn't have a default value`
  (kind 도 같은 형태)다. DB 거부는 계약대로였고, 단언한 예외 타입이 틀렸다. spring-jdbc 6.2.17 의
  `JdbcAccessor.getExceptionTranslator` 바이트코드는 사용자 `sql-error-codes.xml` 이 없으면 `SQLExceptionSubclassTranslator`
  를 고른다. 계획 §2.1 의 전제를 정정했다.
- 원인 2(7건): order `OrderCompensationRefundIntegrationTest` 의 backfill 4건, product `StockCompensationRefundIntegrationTest`
  의 backfill 3건. V5·V4 migration 의 backfill INSERT 를 현재 스키마에서 원문 그대로 실행해
  `Caused by: java.sql.SQLException: Field 'record_kind' doesn't have a default value` 로 실패했다. 계획의 raw INSERT 스윕이
  main 리소스의 SQL 을 읽어 실행하는 테스트를 놓쳤다.
- Flyway contract: step 안의 `:payment-service:test` 가 원인 1의 2건으로 먼저 실패해 lab 이 돌지 않았다(338 tests, 2 failed).
- 그 밖의 결과: lint job 의 DLQ lab 은 46 pass(119.085초, mysql 8.0.46, cleanup true)로, 서비스별 contract 적용과 누락 INSERT
  1364, order 위반 7종과 수리 전 legacy NULL 의 1231 거부·행/스키마 불변, contract 이후 가드 무동작을 실제 MySQL 에서 보였다.
  order 의 classpath 가드 발견 음성 대조(`classpathGuardRejectsBeforeAnyMigration`)도 CI 에서 통과했다.
- 수정(`58c5b2e`): 계약 테스트는 `DataAccessException` 과 MySQL 오류 코드(1364, 1048)를 단언한다. 두 backfill 테스트는 INSERT
  동안만 `record_kind` 에 DEFAULT 'DOMAIN' 을 걸고 finally 에서 지운다(V5·V4 는 record_kind 이전 판본이고 실제 체인에서는
  뒤 backfill 이 DOMAIN 으로 채운다). Flyway lab snapshot 은 예약 테이블이 있을 때만 읽는다.
- 로컬 검증(사용자 허용 범위, 이 worktree 브랜치에서 직접 실행): order `ReplayContractSchemaIntegrationTest` 4·
  `OrderCompensationRefundIntegrationTest` 15, product 계약 3·`StockCompensationRefundIntegrationTest` 14, payment 계약 3,
  notification 계약 3 모두 실패 0. `:payment-service:flywayContractTest` 1 test 통과(151.946초). 검증기의 lab 측 규칙으로
  report 를 대조해 invocation 88(migrate 60·validate 28)·의도된 실패 32·고유 pid 88·Flyway 11.7.2, 가드 거부 8건 모두 1231 과
  marker, 네 서비스 플래그 9종·가드 원문 hash·복원 contract hash 일치, 재시작 snapshot 12개 동일(실제 StartedAt 증가, host
  port 55846 에서 56635)을 확인했다. 실제 메시지: `Variable 'sql_mode' can't be set to the value of 'D065_CONTRACT_GUARD
  invalid_root_rows=1 invalid_kind_rows=1 (need root_record_id>0 and record_kind DOMAIN or REPLAY. nothing changed. fix rows
  with provenance then restart)'`. 일반 payment 334 명단 대조는 CI 에서 한다.
- 이전 로컬 lint 결과의 출처: 앞선 61개 명령은 `scratchpad/laneB/` 의 래퍼(이 worktree 로 cd)로 돌렸고, 레인 A 가 덮어쓴
  `scratchpad/hpx.sh` 는 쓰지 않았다. 아래 /ship 전 재확인은 worktree 에서 직접 실행한 결과만 적는다.
