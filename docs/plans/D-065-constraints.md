---
grade: L
---
# D-065 constraints — 로컬 분류·backfill 안전성 검증 계획

## 1. 명제

구형 writer의 누락 INSERT가 성공으로 처리되거나, 근거 없는 legacy 행을 self root/DOMAIN으로 보정하거나, 중간 실패 뒤 원본과 실제 schema 상태를 설명할 수 있으면 NOT NULL 전환 준비는 미완이다. 이 계획의 첫 산출물은 로컬 preflight와 backfill 반례 검증이며, 운영 적용 가능한 DDL 배포를 완료했다고 선언하지 않는다.

2026-10-21 현재 **plan-only**. 기준 main은 `a98ae7d51e3f266a0d9a89996f4a2dd2483af4eb`; writer 선행 PR [201](https://github.com/Kimgyuilli/PeekCart/pull/201)의 검토 head는 `b288e3a1ff5975ed0edf2e26d02d0b286992a529`다. 선행 PR의 실제 MySQL 71개 결과는 writer 증거이며 이 계획의 backfill·운영 전환 증거로 재사용하지 않는다. 구현 착수 전 실제 병합 main과 authored delta를 다시 맞춘다.

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

첫 ALTER 성공/두 번째 실패를 로컬에서 주입하여 실제 schema checkpoint와 원본 데이터가 보존됨을 확인한다. 재개 판단은 현재 schema·Flyway 이력·before-image를 읽어 명시하며 자동 nullable 복귀, 숨은 Flyway repair, 무조건 재실행은 금지한다. 실제 contract DDL은 이 증거를 검토한 뒤 별도 변경으로 제안한다.

## 3. 작업 항목

- [x] P1. 선행 writer, 과거 backfill, 조회/retention, raw INSERT와 권한 경계를 소스로 확인하고 계획을 기록한다.
- [ ] P2. 로컬 fixture 전용 read-only preflight를 구현한다. 잘못된 schema/권한/중복/관계/모호한 legacy를 구별해 실패하고 비밀·payload 원문을 출력하지 않는다.
- [ ] P3. 승인된 fixture 후보 목록과 before-image를 사용한 트랜잭션 backfill을 구현한다. 이미 유효한 행 및 lineage/audit/payload/timestamp는 보존한다.
- [ ] P4. 실제 MySQL 8.0.46에서 구 INSERT IGNORE, mixed writer, NULL/0, malformed 관계, rollback, 반복 실행을 검증한다. 서비스와 동일한 최소 권한 계정도 사용한다.
- [ ] P5. 로컬 임시 schema에서 contract 후보 및 첫/둘째 ALTER 실패 경계를 검증한다. no DEFAULT 누락 INSERT와 non-strict/IGNORE 대조를 포함한다. 이는 배포 migration 작성 승인이 아니다.
- [ ] P6. 증거를 바탕으로 별도 contract PR의 필요 제약·전환·재개 조건을 제안한다. 승인된 후에만 실제 최신 migration 번호, E2E EXPECTED_MIGRATIONS, writer/조회/retention 회귀와 전체 CI 범위를 확정한다.

## 4. 검증 방법

| 항목 | 잘못된 구현을 드러내는 대조 | 관측할 결과 |
|---|---|---|
| P2 | 0/음수 root, orphan, 2-cycle, 다단계 root, index 제거 후 같은 좌표 중복, unknown kind, 모호한 NULL | nonzero 종료, 원본 hash/행 수 불변, 어떤 증빙이 필요한지 제한된 진단 |
| P3 | 유효 legacy fixture+기존 root/child/domain/replay 혼합; 중간 DML 오류 | 성공 시 허용한 두 필드만 변경; 실패 시 before-image 완전 일치; 두 번째 실행 0 변경 |
| P4 | 과거 INSERT IGNORE 그대로 실행, nullable/NOT NULL 각각과 strict/non-strict 비교 | SQL warning·affected count·실제 저장 root·recorder 후속 동작까지 기록. 성공 ack처럼 보이는 반례를 통과로 세지 않음 |
| P4 | old writer를 preflight 뒤/backfill 뒤/DDL 사이에 삽입; ID allocator와 legacy 자동증가 혼합 | 새 NULL/0/PK 충돌 또는 모호함 발견 시 중단. 단 한 번 NULL count=0을 rollout fence로 인정하지 않음 |
| P5 | 첫 ALTER 직후 두 번째 ALTER 실패; wrong/default schema; 트랜잭션 ROLLBACK 시도 | 실제 남은 DDL 상태와 DML 경계를 기록. 자동 원복됐다는 잘못된 단언이 실패해야 함 |
| P6 | 새 DB 전체 migration와 업그레이드 DB, 최신 writer 실제 JPA duplicate/rollback/replay child/retention | 기존 incident 집계·감사·부모/자식 보존, 누락 kind/root INSERT 거부를 별도 확인 |

모든 실제 Docker/Gradle은 root와 heavy 슬롯을 조정한 뒤 수행한다. 테스트 자기 컨테이너만 정리하고 기존 세 momens 컨테이너를 보존한다. 계획 작성 현재 실행한 MySQL/Gradle 검증은 없다.

## 5. 완료 조건과 미해결

로컬 preflight/backfill 반례와 복구 경계 증거가 있어야 P2–P5를 닫는다. 결과가 mixed writer 또는 모호한 legacy를 안전하게 처리하지 못하면 contract 적용 불가를 그대로 기록한다. 운영 writer 중지·배포·실데이터 표본·DDL, GKE ACL/좌표, P22 timestamp 비율, replay 개방은 범위 밖이며 D-065 전체는 PARTIAL이다. signed gateway 경로는 별도 계획으로 분리한다. TASKS/PHASE/master 완료 변경은 root 소유다.
