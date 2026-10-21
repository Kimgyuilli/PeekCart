---
grade: L
---
# D-065 constraints — 로컬 분류·backfill 안전성 검증 계획

## 1. 명제

구형 writer의 누락 INSERT가 성공으로 처리되거나, 근거 없는 legacy 행을 self root/DOMAIN으로 보정하거나, 중간 실패 뒤 원본과 실제 schema 상태를 설명할 수 없으면 NOT NULL 전환 준비는 미완이다. 이 계획의 첫 산출물은 offline fixture snapshot 분류기와 로컬 backfill 반례 검증이며, 운영 적용 가능한 DDL 배포를 완료했다고 선언하지 않는다.

2026-10-21 로컬 구현 진행 중. 최초 plan-only 기록은 audit에 보존한다. 기준 main은 `a98ae7d51e3f266a0d9a89996f4a2dd2483af4eb`; writer 선행 PR [201](https://github.com/Kimgyuilli/PeekCart/pull/201)의 검토 head는 `b288e3a1ff5975ed0edf2e26d02d0b286992a529`다. 선행 PR의 실제 MySQL 71개 결과는 writer 증거이며 이 계획의 backfill·운영 전환 증거로 재사용하지 않는다. PR201은 실제 main `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`에 병합되었고 이 작업 tree에도 정상 통합했다. 선행 merge 통합의 파일 delta는 0이다.

## 2. 배경과 코드 확인

| 확인 대상 | 코드에서 확인한 사실 | 이번 처분 |
|---|---|---|
| `DeadLetterRecordJpaRepository.insertIfAbsent` (기준 main) | `INSERT IGNORE`가 ID/root를 생략하고 recorder가 나중에 self root를 설정 | 이 구문을 실제 MySQL 음성 대조로 보존. 단순 NOT NULL은 누락 root를 0으로 바꾸거나 INSERT를 경고/무시 처리할 수 있으므로 구 writer의 안전한 차단 증거가 아님 |
| PR201 `DeadLetterIdAllocator`/recorder/repository | 같은 트랜잭션에서 예약 ID를 발급하고 첫 INSERT에 root 명시. affected count와 저장 ID를 함께 확인 | 모든 DLQ writer 전환 및 allocator bootstrap 선행. 혼합 writer 안전성을 주장하지 않음 |
| `OutboxEvent.create`/`replay` | 각각 DOMAIN/REPLAY 명시, `isReplay`는 legacy NULL을 domain으로 해석 | runtime writer 계약 유지; NULL이라는 사실만으로 역사적 출처를 확정하지 않음 |
| order V11 / product V9 / payment V9 / notification V7 `dead_letter_replay_backfill.sql` | NULL root→id, NULL kind→DOMAIN 후 NULL 잔여 검증. 제약은 아직 nullable | 과거 migration 수정 금지. 이후 유입된 NULL도 같은 증거를 가진다는 가정 금지 |
| `DeadLetterRecordJpaRepository` 집계·`deleteIncident` | NULL/self root 집계와 root+child 단위 삭제 | 전환 전후 incident 수·상태·retention 결과 대조. NULL 분기 제거는 제약 검증 이후 별도 검토 |
| `OutboxEventCleanupIntegrationTest`, `OutboxReplayPublicationIntegrationTest`, `OutboxAtLeastOnceIntegrationTest` | raw INSERT fixture가 kind를 생략 | 유효 신규 fixture와 의도적 legacy fixture 구분. 빠진 필드를 모두 DOMAIN으로 치환해 음성 대조를 없애지 않음 |
| order V5 / product V4 과거 migration | kind 컬럼 도입 전 INSERT | 역사적 순서를 보존하고 새 DB 전체 migration도 검증 |
| `scripts/mysql-init/01-init-databases.sql` | 서비스 계정은 자기 schema의 SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/REFERENCES만 보유 | root 전용 실험 결과만으로 실행 권한을 주장하지 않음. procedure/temp-table 권한 추가 금지 |

대상 컬럼은 **dead_letter_records.root_record_id**와 **outbox_events.record_kind**로 서로 다른 테이블이다. ADR-0020 D3/D6-3은 expand→writer→backfill→contract를 이미 정했고, ADR-0021은 outbox 보존 기간 때문에 kind/행 존재를 replay 상관의 정본으로 쓰지 않는다. PR201의 ADR-0043은 정지 구간 bootstrap와 모든 writer 전환, 이후 별도 contract를 요구한다.

등급 L: 네 서비스 DB 및 공유 writer/조회 계약을 다루며, schema 전환의 복구 범위가 단일 파일을 넘는다. 모듈 이동은 없어 B1/B8 이동 처분은 해당 없음. PLAN-BLINDSPOTS B1b의 SQL·문자열 참조, B4의 실제 실패 메커니즘, B12의 검사 후 새 writer 유입 구간을 검증 대상으로 반영한다.

### 분류와 중단 조건

- preflight는 schema/nullability/default/index/권한, 좌표 중복, 양수 ID, root 관계, kind 및 replay 관련 컬럼의 일관성을 읽기 전용으로 확인한다. canonical self root 외의 cycle, orphan, 다단계 root, 0/음수 ID, 잘못된 kind는 자동 수정하지 않고 중단한다.
- legacy NULL은 자동 변환 허가가 아니다. 코드 버전·migration 순서·원본 snapshot 등으로 해당 행의 생성 경로를 입증한 fixture/행 목록만 후보로 삼는다. replay 모양의 메타데이터와 NULL kind, lineage를 복원할 수 없는 NULL root는 중단한다. 메타데이터 부재 역시 단독으로 DOMAIN/self의 증거가 아니다.
- 실제 데이터의 안전 분류를 코드/보존 증거로 할 수 없으면 변경 전 행·관계·관련 원본의 증빙 목록과 승인된 처분을 확보한 뒤 다시 preflight해야 한다. 자동 합성, orphan 삭제, payload/header를 믿은 parent 연결은 제공하지 않는다.
- SQL은 old binary가 더 이상 실행되지 않는다는 사실을 증명할 수 없다. replay kill-switch false만으로 DLQ 적재·outbox writer·기존 연결이 정지하지 않는다. 인벤토리와 모든 writer 정지/재개 절차는 외부 운영 전제이며 이번 로컬 성공으로 충족 처리하지 않는다.

### 복구 경계와 ADR 판단

기존 ADR의 NOT NULL/no DEFAULT 방향은 유지한다. 새 기본값·FK/cascade·CHECK·식별자 변경·자동 복구 의미를 추가하려면 구현 전 별도 ADR 판단 및 번호 조정을 한다. 우선 로컬 preflight/backfill 검증에는 새 외부 의존성이나 운영 환경이 없다.

DML backfill은 명시적 트랜잭션 안에서 검증된 NULL 후보에만 적용하고 before-image와 결과 hash를 대조한다. MySQL ALTER TABLE은 implicit commit을 일으키므로 DML과 여러 DDL을 묶은 전체 rollback을 약속하지 않는다. 단일 DDL의 crash atomicity와 여러 DDL의 업무 단위 원자성은 다르다. [MySQL atomic DDL](https://dev.mysql.com/doc/refman/8.0/en/atomic-ddl.html), [implicit commit](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html).

첫 ALTER 성공/두 번째 실패를 로컬에서 주입하여 실제 schema checkpoint와 원본 데이터가 보존됨을 확인한다. 재개 판단은 현재 schema·Flyway 이력·before-image를 읽어 명시하며 자동 nullable 복귀, 숨은 Flyway repair, 무조건 재실행은 금지한다. 실제 contract DDL은 이 증거를 root가 검토한 뒤 별도 변경으로 제안한다. 무료 로컬 구현은 이미 승인되었으며 P6는 사용자 재승인 요청이 아니다.

### 구현 범위 조정

최초 P2의 read-only preflight는 **offline fixture snapshot classifier**로 좁힌다. `scripts/dlq_contract_preflight.py`는 JSON 입력만 읽으며 live DB 연결이나 mutation 기능이 없다. 실제 SQL 조회는 `scripts/tests/dlq_contract_lab.py`가 자신이 만든 격리 MySQL에서만 수행한다. export의 완전성·최신성을 독립 인증하지 않으며, 여러 조회 사이의 동시 쓰기를 막는 일관 snapshot도 아니다. 행 hash는 입력이 바뀌었는지 확인할 뿐 provenance 진위를 인증하지 않는다.

분류 대상은 ledger/outbox뿐이다. allocator counter, 실제 writer 실행 버전/부재, 실행 중 연결은 검증하지 않는다. backfill 직전 export hash 비교 역시 트랜잭션 fence가 아니다. actual MySQL CLI로 migration SQL을 순서대로 적용했지만 Flyway failed-history/repair/재개를 실행한 증거는 아니다. 이 차이는 P6의 무료 후속 검증과 외부 운영 전제로 나누어 유지한다.

## 3. 작업 항목

- [x] P1. 선행 writer, 과거 backfill, 조회/retention, raw INSERT와 권한 경계를 소스로 확인하고 계획을 기록한다.
- [x] P2. 로컬 fixture 전용 offline snapshot classifier를 구현한다. 잘못된 schema/권한/중복/관계/모호한 legacy를 구별해 실패하고 비밀·payload 원문을 출력하지 않는다.
- [x] P3. 승인된 fixture 후보 목록과 before-image를 사용한 트랜잭션 backfill을 구현한다. 이미 유효한 행 및 lineage/audit/payload/timestamp는 보존한다.
- [x] P4. 실제 MySQL 8.0.46에서 구 INSERT IGNORE, mixed writer, NULL/0, malformed 관계, rollback, 반복 실행을 검증한다. 서비스와 동일한 최소 권한 계정도 사용한다.
- [x] P5. 로컬 임시 schema에서 contract 후보 및 첫/둘째 ALTER 실패 경계를 검증한다. no DEFAULT 누락 INSERT와 non-strict/IGNORE 대조를 포함한다. 이는 배포 migration 작성 승인이 아니다.
- [x] P6. 이번 로컬 증거에 따른 후속 contract 검증 조건을 아래에 제안한다. 실제 Flyway/contract 구현·운영 적용은 이 체크의 완료 의미에 포함하지 않는다. 무료 구현 권한은 유지되며 다음 PR의 범위와 heavy 순서를 root와 조정한다.

## 4. 검증 방법

| 항목 | 잘못된 구현을 드러내는 대조 | 관측할 결과 |
|---|---|---|
| P2 | 0/음수 root, orphan, 2-cycle, 다단계 root, index 제거 후 같은 좌표 중복, unknown kind, 모호한 NULL | nonzero 종료, 원본 hash/행 수 불변, 어떤 증빙이 필요한지 제한된 진단 |
| P3 | 유효 legacy fixture+기존 root/child/domain/replay 혼합; 중간 DML 오류 | 성공 시 허용한 두 필드만 변경; 실패 시 before-image 완전 일치; 두 번째 실행 0 변경 |
| P4 | 과거 INSERT IGNORE 그대로 실행, nullable/NOT NULL 각각과 strict/non-strict 비교 | SQL warning·affected count·실제 저장 root·recorder 후속 동작까지 기록. 성공 ack처럼 보이는 반례를 통과로 세지 않음 |
| P4 | old writer를 preflight 뒤/backfill 뒤/DDL 사이에 삽입; ID allocator와 legacy 자동증가 혼합 | 새 NULL/0/PK 충돌 또는 모호함 발견 시 중단. 단 한 번 NULL count=0을 rollout fence로 인정하지 않음 |
| P5 | 첫 ALTER 직후 두 번째 ALTER 실패; wrong/default schema; 트랜잭션 ROLLBACK 시도 | 실제 남은 DDL 상태와 DML 경계를 기록. 자동 원복됐다는 잘못된 단언이 실패해야 함 |
| P6 | 새 DB 전체 migration와 업그레이드 DB, 최신 writer 실제 JPA duplicate/rollback/replay child/retention | 기존 incident 집계·감사·부모/자식 보존, 누락 kind/root INSERT 거부를 별도 확인 |

모든 실제 Docker/Gradle은 root와 heavy 슬롯을 조정한 뒤 수행한다. 테스트 자기 컨테이너만 정리하고 기존 세 momens 컨테이너를 보존한다. 최초 계획 때 MySQL/Gradle 실행은 없었으며, 구현 후 실제 결과는 audit와 증거 디렉터리에 별도로 기록한다.

## 5. 완료 조건과 미해결

로컬 offline classifier/backfill 반례와 복구 경계 증거가 있어야 조정된 P2–P5를 닫는다. live DB preflight 완료나 export 완전성·최신성의 독립 증명으로 확대하지 않는다. 결과가 mixed writer 또는 모호한 legacy를 안전하게 처리하지 못하면 contract 적용 불가를 그대로 기록한다. 운영 writer 중지·배포·실데이터 표본·DDL, GKE ACL/좌표, P22 timestamp 비율, replay 개방은 범위 밖이며 D-065 전체는 PARTIAL이다. signed gateway 경로는 별도 계획으로 분리한다. TASKS/PHASE/master 완료 변경은 root 소유다.

## 6. 실제 결과와 무료 후속

- 실제 MySQL 8.0.46 SQL 대조 33개, 108.805초 통과. 네 서비스 migration 14/13/15/9개를 최소 권한 계정으로 적용했다. 경량 Python 10개와 fixture export의 offline CLI 양성 호출도 통과했다. 상세 결과와 55개 source hash는 [증거](evidence/D-065-constraints/README.md)에 있다.
- strict/non-strict old INSERT IGNORE는 warning 1364와 affected count 1로 root 0을 저장했다. 기존 NULL-only self update는 고치지 못했다. kind IGNORE는 빈 문자열을 저장했다. 따라서 NOT NULL만으로 구 writer를 안전하게 거부한다는 주장은 반증됐다.
- DML 실패 1146은 양 테이블 before-image와 일치하게 rollback됐다. 둘째 ALTER 실패 1054는 첫 NOT NULL을 되돌리지 않았고, ALTER 전에 수행한 DML도 implicit commit되어 ROLLBACK으로 되돌아오지 않았다. old writer의 새 NULL은 strict ALTER를 1138로 막았다.
- 관측된 backfill 트랜잭션 pause 중 별도 old writer가 NULL을 넣었다. legacy AUTO_INCREMENT는 allocator의 예약 ID 22를 소비했다. 후속 SQL upsert의 count 2에도 새 좌표는 없었으며 rollback 후 원본을 보존했다. live writer 정지 증거는 여전히 외부 전제다.
- pipeline 실패 주입은 exit 1을 유지했다. SIGKILL로 finally를 우회해도 별도 cleanup은 기록된 자기 ID/name/label만 제거했다. 기존 세 momens 컨테이너는 보존했다. 전체 CI는 PR 최신 head에서 별도로 확인하며 로컬 결과로 대체하지 않는다.

다음 무료 단계는 실제 Flyway runner와 최소 권한 계정으로 새 DB/기존 schema 업그레이드, failed schema-history 기록, repair 없이 안전하게 재개할 수 있는 경계와 불가능한 경계, restart 뒤 데이터·schema·allocator 보존을 검증하는 별도 계획/PR이다. 실패한 비트랜잭션 migration이 history에 남는 경우 이를 숨기거나 자동 repair하지 않는다. 필요하면 backfill/contract migration 분리 또는 명시적 수동 복구 요구를 새 증거와 함께 root 설계검토에 올린다. 이 단계는 무료 로컬로 할 수 있으므로 운영 제약에 묶어 미루지 않는다.

실제 Flyway 실패/재개 증거를 확보한 뒤에만 별도 contract migration 번호를 최신 main에서 확정하고, 네 서비스 schema parity·전체 migration·E2E EXPECTED_MIGRATIONS·JPA writer와 incident/retention 회귀를 함께 검증한다. 외부에 남는 것은 실제 운영 writer 정지/전환 증빙, 실제 데이터 provenance·표본, 실제 DDL 적용이다.
