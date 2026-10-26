# D-065 lineage writer 조사

## 2026-10-21 읽기 검증

- 기준 main `af60cd6`, tree `/private/tmp/peakcart-worker-c-lineage`, branch `debt/d065-lineage-writer`. 구현·DDL·Gradle·Docker 실행 없음. 별도 Codex 리뷰 미호출.
- DeadLetterRecordJpaRepository.insertIfAbsent는 INSERT IGNORE에서 id/root_record_id를 생략한다. DeadLetterRecorder는 좌표로 재조회한 뒤 독립 root의 assignSelfRoot 또는 child.linkToRoot를 호출한다. assignSelfRoot는 NULL만 수정하므로 NOT NULL에서 0으로 강등된 값은 남는다.
- root 집계는 NULL 또는 id=self를 받아 전환기 legacy를 흡수한다. 네 서비스의 기존 backfill migration은 NULL을 self/DOMAIN으로 채우며 별도 contract는 없다. OutboxEvent 도메인/replay factory는 kind를 명시하지만 DB 누락 INSERT는 아직 가능하다.
- ADR-0020 D3/D6-3 및 원 계획 R1은 expand→신 writer 전환→backfill→NOT NULL, DEFAULT 없음 계약이다. 단순 ALTER는 현 writer와 호환되지 않는다.

## 설계 후보와 아직 필요한 결정

- 최초 조사 후보는 서비스 DB별 단일 sequence 행의 잠금을 통해 양수 ID를 INSERT 전에 할당하고, native INSERT에 id와 root_record_id를 함께 넣는 방식이다. 독립 root는 allocated ID, 검증된 child는 잠근 기존 root ID를 넣는다. 전송 입력이 root를 직접 정하지 않는다.
- 최초 후보의 root 잠금→ID allocator 잠금→INSERT 순서는 아래 추가 조사에서 deadlock 가능성이 발견되어 채택하지 않는다. 실제 좌표 중복은 기존 행을 유지하고 attempt만 증가해야 한다. ID 충돌을 좌표 중복으로 오인하지 않도록 조회 결과 ID/좌표를 확인하며, INSERT IGNORE의 일반 오류 강등을 제거하는 방안(ON DUPLICATE KEY UPDATE 후 할당 ID 비교)을 우선 검토한다.
- AUTO_INCREMENT writer와 앱 선할당 writer의 혼합 실행은 안전하다고 가정하지 않는다. allocator bootstrap은 MAX(id) 기준으로 기존 writer를 멈춘 구간에서 수행하고 새 writer만 재개해야 한다. 기존 NULL schema에서도 새 writer가 자기 root를 명시해 쓰도록 먼저 검증한다. backfill과 contract는 구 writer 제거 확인 이후에만 적용한다.
- record_kind NOT NULL/no DEFAULT 및 root_record_id NOT NULL/양수 검사를 별도 contract에 두는 방안이다. 기존 parent가 없는 값이나 0을 NULL과 동일하게 조용히 정규화하지 않는다. 제약 전 이상 행을 거절하고 운영자 판단으로 남긴다.
- 새 ID 전략은 ADR-0020의 식별자 할당 구현을 바꾸므로 별도 ADR이 필요하다. 주 작업자가 ADR-0043을 예약했다. writer/allocator와 contract 적용을 한 자동 migration 묶음으로 넣으면 롤링 업그레이드를 깨므로 PR 및 배포 단계 분리를 검토해야 한다.

## 예정된 실제 MySQL 반증

- 현 writer + 단순 NOT NULL의 0 강등/조회 누락을 대조군으로 재현.
- 신규 root/상관 child/중복 재전달/동시 서로 다른 좌표와 동일 좌표, rollback 후 allocator 동작, 큰 기존 ID bootstrap, 의도된 ID 충돌의 비유실을 확인.
- nullable 전환기 schema와 contract schema에서 같은 writer의 root=self 또는 올바른 parent를 확인. raw 누락 INSERT 및 kind 누락/0 root를 거절하는지 확인.
- 기존 legacy backfill 재실행과 root 집계·재개방·purge 불변을 검증. 원본 timestamp 운영 표본과 GKE 조건은 별도 미검증이다.

## Signed gateway 별도 gap 목록

- gateway DeadLetterAdminRouteContractTest는 route matcher/rewrite만 확인한다. backend DlqReplayEntrypointIntegrationTest와 별도 권한 가드는 같은 HTTP 전체 경로의 증거가 아니다.
- scripts/e2e/saga_e2e.py의 http는 gateway를 거치지 않고 DUAL_ACCEPT X-User-* 헤더를 직접 보낸다. 기존 E2E를 signed ADMIN 경로 관통으로 주장할 수 없다.
- 후속 별도 PR은 실제 gateway의 외부 ADMIN 인증·내부 서명·SIGNED_ONLY backend·ADMIN 성공/USER 403/위조 헤더 거절까지 같은 요청으로 확인해야 한다. replay kill switch 개방이나 실제 replay 호출은 본 조사 범위 밖이다.

## 2026-10-21 대안 비교와 승인 전 경계

- 단일 counter 행을 트랜잭션 끝까지 잡으면 독립/중복 INSERT가 기존 root 좌표를 기다리는 동안, 그 root를 잡고 counter를 기다리는 recorder와 교착될 수 있다. allocator-first로 통일해도 DB 전체 DLQ 적재가 root 대기에 직렬화된다. 이 처리량 절충은 승인되지 않았으며 우선 후보에서 내린다.
- 대안은 별도 AUTO_INCREMENT 예약 테이블에 한 행을 INSERT하고 JDBC generated key를 받는 것이다. 예약 행은 같은 트랜잭션에서 자기 ID로만 DELETE한다. MySQL의 AUTO-INC 잠금이 이 단순 INSERT 문 이후 풀리는지와 이후 root 대기 중 다른 ID 할당이 진행되는지 실제 MySQL에서 반증한다. REQUIRES_NEW나 별도 connection은 사용하지 않는다.
- 빈 예약 테이블의 MySQL 8 재시작 후 counter 보존, rollback 후 ID gap/재사용 여부, duplicate 적재에서도 ID gap 허용을 실측해야 한다. gap-free는 기존 계약이 아니며 요구하지 않는다. 예약 테이블 counter를 기존 ledger MAX보다 크게 bootstrap하는 방법과 영속성을 검증한다.
- native ledger INSERT는 예약 ID 및 self/검증된 parent를 동시에 기록한다. ON DUPLICATE KEY UPDATE의 affected-row 수를 신규 판정에 쓰지 않는다. 좌표를 current read로 조회해 예약 ID 일치 여부로 신규/기존을 나누며, 다른 좌표의 PK 충돌이면 오류를 전파해 입력을 ack하지 않는다. 이 비교와 rollback은 실제 Connector/J/MySQL 테스트가 필요하다.
- SELECT MAX(id)+1 단독, information_schema.AUTO_INCREMENT 예측, LAST_INSERT_ID의 이전 값 재사용은 동시 안전성을 제공하지 않는다. 0/임시 root 후 UPDATE는 최종 NOT NULL/양수 의미를 만족시키는 첫 INSERT가 아니므로 제외한다. trigger는 현재 배포 GRANT와 자동 ID 할당 시점 제약이 있고 더 큰 운영 변경이므로 우선하지 않는다.
- 기존 AUTO_INCREMENT ledger writer와 예약 테이블 writer의 혼합 안전성은 주장하지 않는다. writer 전환 및 allocator bootstrap의 운영 경계는 문서로 제시할 뿐 이번 작업에서 실제 접수를 중지하거나 운영 자원을 수정하지 않는다. NOT NULL contract는 writer 검증 뒤 별도 단계로 유지한다.
- 196 E2E가 우선이며 다음 실제 MySQL 실험은 heavy slot을 다시 승인받은 후 수행한다. 이 문서는 설계 조사로서 구현 승인 또는 테스트 통과를 뜻하지 않는다.

- 2026-10-21 [MySQL 8 공식 AUTO_INCREMENT 문서](https://dev.mysql.com/doc/refman/8.0/en/innodb-auto-increment-handling.html)를 직접 확인했다. MySQL 8 counter는 redo/data dictionary에 보존되지만 비정상 종료 전 redo 미flush 값의 재사용 가능성은 별도로 명시한다. rollback gap과 정상 재시작 보존을 가정으로 섞지 않고, ledger와 예약의 같은 트랜잭션 commit 및 실제 재시작 검증 범위를 구분한다. signed 정수 최대치를 넘어간 AUTO_INCREMENT 동작도 의존하지 않는다.

## 2026-10-21 실제 MySQL prototype

- root가 승인한 단독 heavy slot에서 MySQL 8.0.46, innodb_autoinc_lock_mode=2, innodb_flush_log_at_trx_commit=1로 실행했다. 네트워크 d065-prototype-c와 컨테이너 d065-prototype-c-mysql만 생성했고 host port는 열지 않았다.
- 첫 실행은 기존 PyMySQL runner에 cryptography가 없어 caching_sha2_password handshake에서 실패했다. SQL을 실행하기 전 실패였으며 로컬 fixture를 mysql_native_password로 다시 생성했다. `/private/tmp/d065-sql-prototype-auth-failure.log` 보존.
- 다음 실행은 9개 대조를 통과한 뒤 최대 ID의 서버 오류를 예상한 assertion에서 실패했다. MySQL은 signed MAX seed/delete 뒤 MAX를 다시 반환했고 application 범위 guard가 ledger INSERT 전에 차단했다. 이 관측에 따라 overflow 오류 가정을 버리고 명시적 0<id<Long.MAX_VALUE guard를 결정했다. `/private/tmp/d065-sql-prototype-boundary-discovery.log` 보존.
- 최종 12개 pre-restart 대조와 정상 재시작 후 19→20 예약을 통과했다. 8개 동시 같은 좌표는 found-rows가 모두 1인데도 ID 비교로 신규 1/중복 7을 구별하고 attempts=8을 보존했다. 다른 root 대기 중 독립 ID 예약, RR snapshot 이후 current read, rollback gap, PK 충돌 비유실, 높은 legacy bootstrap, 테스트 전용 NOT NULL writer/누락 거절을 확인했다. 예약 테이블 잔여 행은 0이다.
- 증거: `/private/tmp/d065-sql-prototype.log` SHA256 `78d2c35fe7a70dfceaabfa6fe252b5a754b057bfc139859aeada32c61c560ec6`, restart log `07944daf85fbd6bd360fe9952748590b65965149ea3ecf64cd60285b723c85af`, 임시 `.cache/d065-prototype/probe.py` `4402537cf733de44594d96c7343ac17de62307c5149519387e7393c340ef1ce0`은 boundary 대조를 수정한 최종 실행본이다.
- 자기 MySQL·network를 제거했고 기존 momens 3개만 실행 중임을 확인하여 heavy slot을 반환했다. SQL prototype는 JPA/전체 애플리케이션 검증이 아니며 이후 production 통합 테스트가 필요하다.
- root 승인으로 frozen #196 `5030e31`과 current main `545e5dc`를 정상 merge했다. 두 merge 후 baseline은 `d6cea4d`다. #196에 의존하는 별도 PR이며 실제 main merge 후 다시 맞추고 최종 review/CI를 확인한다. 실제 merge commit에 한해 `HPX_ALLOW_MIXED=1`을 사용했다.


## 2026-10-21 production writer의 1차 실제 Spring/MySQL 검증과 보완

- `./gradlew :peekcart-common-messaging:test --tests '*DeadLetterIdAllocatorTest' :order-service:test --tests '*DeadLetterIdReservationIntegrationTest' --tests '*DeadLetterLedgerIntegrationTest' --tests '*DlqReplayCorrelationIntegrationTest' --tests '*DeadLetterIncidentAggregationIntegrationTest' --no-daemon --no-parallel --max-workers=1`을 실행했다.
- 첫 실행은 ID 충돌을 주입하는 Mockito spy 설정이 MANDATORY transaction 밖에 있어 2개가 실패했다. 실제 allocator의 transaction guard는 유지하고 spy 설정을 transaction 안에서 수행하도록 고쳤다. `/private/tmp/d065-writer-focused-injection-failure.log` 보존.
- 다음 실행은 49초, common unit 4개와 order 실제 MySQL 64개(신규 allocator 12, ledger 11, correlation 28, aggregation 13) 통과했다. 로그 `/private/tmp/d065-writer-focused.log`, 당시 새 파일 포함 source snapshot SHA256 `64468d5e72047c7509c37976d1b852c36e88e67d229d8428e45aaf1de0236371`. 테스트 컨테이너 정리 후 기존 momens 3개만 확인하고 heavy slot을 반환했다.
- 이 통과 뒤 자체 검토에서 no-op upsert + ID 비교만으로는 allocator가 이미 존재하는 같은 ID·같은 좌표를 다시 반환할 때 신규로 오인하는 반례를 발견했다. 최초 테스트 결과를 최종 구현의 통과로 주장하지 않는다.
- root 승인 후 ADR-0043 본문을 보존하고 Update Log를 먼저 추가했다(`f6214ee`). 기존 모든 중복의 attempt 증가를 upsert 자체로 옮긴다. 새 insert count=1/실제 중복 증가 count=2를 좌표 current read의 ID 및 예약 ID 점유 여부와 함께 대조한다. row count만으로 분류하지 않으며 예상 밖 ID 충돌은 전체 rollback한다.
- 동일 ID/같은 좌표 재사용, attempt INT 최대 overflow 뒤 rollback/정상 재시도, NOT NULL attempt 거절 대조를 추가했다. 이 보완 이후 테스트는 아직 실행하지 않았다. #196의 product CI 실패 조사가 우선이며 다음 승인된 heavy slot에서 실제 MySQL 최종 검증을 수행한다.


## 2026-10-21 보완 계약의 최종 실제 MySQL 검증

- 선행 PR #196의 최종 고정 head `3bec8aef2e3f02a7d30dd50009aebfdbf1ef0870`를 정상 merge했다. 실제 main `a7ffa11` 및 product 진단을 포함한 기준은 `1bcbe867bb35c4d500d0035c19888a53fcaceb2c`이며 자기 dirty D065 파일은 그대로 보존했다. #196의 미확정 product timeout 잔여도 상속하고 merchant/lineage 해결로 주장하지 않는다.
- 위 집중 Gradle 명령을 같은 4개 order suite와 common allocator unit에 다시 실행했다. 세션 69939, 49초 exit 0, `/private/tmp/d065-writer-final-focused.log`. 실제 XML은 common 4개와 order 67개(신규 allocator 15, ledger 11, correlation 28, aggregation 13), 실패/오류/skip 모두 0이었다. `/private/tmp/d065-writer-final-test-results`에 XML을 별도 보존했다.
- 실제 실행 source snapshot(새 파일 포함 src/scripts/Gradle 749개)은 SHA256 `caf2f01196f32011bf67f922988174a5c59c89b3d0c8e01236420e35f6e14d73`, manifest `/private/tmp/d065-writer-final-source-manifest.txt`다. 동일 ID·같은 좌표 재사용, 다른 좌표 PK 충돌, 정상 concurrent duplicate의 attempt 증가, INT 최대 overflow rollback/재시도와 NULL 거절을 실제 MySQL로 확인했다. 결과는 SQL prototype를 Spring/JPA 증거로 오인한 것이 아니다.
- 테스트 컨테이너 종료 뒤 기존 momens 3개만 확인하고 heavy slot을 반환했다. smoke self-test 59개(`/private/tmp/d065-writer-smoke-selftest.log`), migration-grant 53개, integration-container lint 및 diff 검사도 통과했다. 전체 CI와 독립 검토는 아직 대기이며 실제 운영 전환·NOT NULL contract·signed gateway ADMIN 전체 경로·P22/GKE/replay 개방은 수행하지 않았다.

- 최종 PR196 head `6b2b534e8c512868a1330a3aaa8399c5ea84ca7a`를 정상 merge한 기준은 `63ce6bf`다. 통합 전 전체 source snapshot은 여전히 `caf2f011...`과 일치했고, 통합 뒤 D065 소유 writer/migration/test/script 12개를 각 파일 SHA256으로 대조해 모두 불변임을 확인했다(`/private/tmp/d065-owned-tested-source.json`). 상위 product test/doc 변경은 최종 전체 CI에서 확인한다. D065 제품 코드는 `f4a0b25`, 테스트 `8a06507`, E2E 기대 목록 `3f4ea2b`로 분리했다.
- 일시 경로만으로 증거가 사라지지 않도록 [증거 묶음](../../evidence/D-065-lineage-writer/README.md)에 당시 SQL prototype, 최종 SQL/restart 로그, 최종 집중 실행 로그 및 신규 JPA 15개 원본 XML과 해시를 보존했다. 역사적 no-op prototype가 최종 판정 계약이 아님을 명시했다.


## 2026-10-21 /ship 실행

- 초안 PR: https://github.com/Kimgyuilli/PeekCart/pull/201, branch `debt/d065-lineage-writer`, target main. 선행 #196 최종 `6b2b534`에 의존하며 실제 merge 뒤 main 재통합·최종 독립 검토·전체 CI가 필요하다.
- ADR은 구현 전에 `564edc0`, 기술 전제 보완은 `f6214ee`로 기록했다. 제품 writer는 `f4a0b25`, 테스트는 `8a06507`, E2E 기대 목록은 `3f4ea2b`, 문서·원본 증거는 `3c0a4bb`로 분리했다. 검증한 source와 코드 commit의 byte 변경은 없다.
- ship preflight, grade L, consistency, explicit staged category, PR title/body writing 및 diff 검사를 통과했다. 별도 Codex 리뷰 미호출. 세션 독립 검토와 전체 필수 CI는 대기다. ADR/index·05/06 승인 영역과 계획·감사는 갱신했으며 TASKS·진행·마스터 계획은 root 소유다.
- merge는 승인되지 않았고 draft를 유지한다. 전체 D-065 및 NOT NULL contract·signed gateway·운영 증적은 미완으로 남긴다.


## 병합 후 확인 (2026-10-21)

최종 head `b288e3a1ff5975ed0edf2e26d02d0b286992a529`의 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/201#issuecomment-6311540940)는 unit 4개와 실제 MySQL 67개를 직접 실행해 failure/error/skip 없이 통과했다. [PR CI39568431878](https://github.com/Kimgyuilli/PeekCart/actions/runs/39568431878) 성공 후 정상 병합 SHA는 `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`다. [main CI39570795532](https://github.com/Kimgyuilli/PeekCart/actions/runs/39570795532)는 전체 검사와 여섯 publish가 성공했다. writer 범위만 부분 완료이며 offline fixture 제약 검증은 PR202, 실제 Flyway contract/restart와 signed gateway는 무료 후속으로 남는다. 운영 표본·GKE·운영 DDL·replay 개방은 실행하지 않았다.
