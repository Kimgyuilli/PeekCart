---
grade: L
---
# D-065 실제 Flyway migration·재시작 경계 검증

## 1. 명제와 범위

mysql CLI가 SQL을 적용한 결과만으로 Flyway 실패 이력이나 재시작 후 재개를 검증했다고 쓰면 미완이다. failed migration을 자동 repair·삭제하거나 여러 MySQL DDL의 rollback을 가정해 green으로 만들면 미완이다. 이 작업은 자기 Testcontainers에서 실제 Flyway를 실행하는 검증 전용 변경이며 운영용 contract migration을 추가하지 않는다.

새 tree `/private/tmp/peakcart-worker-c-flyway`, branch `debt/d065-flyway-contract`, 기준 main `f4ee03b97b0bec0c9be7cd3077911fb67b578b74`다. PR202의 offline classifier/mysql CLI 실험과 실패 메커니즘을 근거로 사용하되 그 코드에 의존하거나 복사하지 않는다. PR202가 미병합이어도 실행 가능하다. PR205의 원 CI 원인 미확정 및 timestamp fixture 변경은 별도이며 리뷰 수정 요청을 우선한다.

사용자 지시에 따라 실제 중량 검증은 Actions에서 실행한다. 로컬은 소스·정적 검사만 수행한다. 제품 schema, 실제 운영 DB·writer, 배포, GKE, PG, replay 허용은 범위 밖이다. root 소유 TASKS/progress/master는 수정하지 않는다.

## 2. 코드 전제와 설계

| 근거 | 확인한 사실 | 검증 설계 |
|---|---|---|
| Spring Boot3.5.12 BOM, payment build | 실제 Flyway core/mysql11.7.2, 프로젝트 JDBC runtime 사용 | 별도 CLI 이미지 대신 동일 runtime 의존성 사용. resolved 버전 기록 |
| 서비스 migration 원문 | order14/product13/payment15/notification9개. 과거 backfill은11/9/9/7 | 각 실제 디렉터리를 읽고 파일 목록·SHA256·적용 history 비교. fixture SQL과 분리 |
| 기존 SharedContainers | MySQL8.0.46/Redis7/Kafka3.8.1을 모듈 JVM singleton으로 소유 | 재사용하되 새 lab tag 전용 JVM에서만 DB restart. 추가 Redis/Kafka 기동 비용은 제한된 CI 예산에 포함 |
| MySQL 서비스 계정 | SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/REFERENCES | root는 자기 schema/계정 fixture 준비만. 실제 Flyway 호출은 최소 권한 계정 |
| 원래 migration 계약 | nullable expand와 과거 알려진 legacy backfill, 최신 allocator bootstrap | 과거 backfill 직전 target에서 명시적으로 만든 NULL fixture만 최신 경로로 올림. 모호한 실제 legacy 자동 보정 주장 금지 |
| MySQL DDL | 여러 DDL은 전체 rollback 원자 단위가 아님 | 첫 ALTER와 앞 DML이 남고 둘째 ALTER가 실패하는 fixture를 actual Flyway로 실행 |

등급 L: 네 서비스 migration과 공유 DB 재시작/이력 경계를 검증한다. 제품 계약 변경은 없고 ADR0020/0043의 기존 expand→writer→backfill→contract 전제를 검증하므로 신규 ADR은 필요하지 않다. 실제 contract DDL을 제안할 근거가 생기면 별도 변경으로 root에 보고한다. B1b의 파일경로/SQL문자열, B4 실패 메커니즘, B12 부분 DDL 후 현재 상태를 직접 검사한다.

파일 범위는 payment build의 전용 task/tag, `FlywayContractIntegrationTest` 및 새 JVM용 test helper, fixture SQL, 별도 `flyway-contract.yml`, 이 plan/audit/evidence다. 기존 ci.yml은 수정하지 않는다. 일반 test에서 제외하는 것은 새 `flyway-contract-lab` tag뿐이며 기존 테스트를 제외하거나 randomization을 제거하지 않는다. 전용 task는 이 tag만 실행하며 0 tests/누락 서비스는 artifact 검증에서 실패해야 한다.

각 Flyway 호출은 test helper를 별도 Java 프로세스로 실행해 JVM 재시작과 단순 객체 재생성을 구분한다. 명령은 migrate/validate/info로 한정하고 repair/clean/baseline을 제공하지 않는다. 제한된 프로세스 대기, JDBC connect/socket/query timeout, 자기 MySQL restart 후 유한 readiness 대기를 둔다. helper 오류는 exit/result로 보존하고 진단이나 cleanup이 원 실패를 덮지 않는다. 계정 비밀번호는 자기 fixture에서 생성해 자식 환경으로만 전달하고 로그·명령 인자에 넣지 않는다.

## 3. 작업 항목

- [x] P1. 실제 migration/버전/권한/기존 SQL 실험의 증거 범위와 독립 전용 JVM 설계를 확인한다.
- [x] P2. 네 서비스 fresh 및 backfill 직전 upgrade 경로를 최소 권한 actual Flyway로 실행하고 전체 파일 hash/history/schema/data/allocator 계약을 검증한다.
- [x] P3. 새 JVM의 반복 migrate·validate와 자기 MySQL 실제 restart 뒤 성공 이력·데이터·schema·allocator 보존을 검증한다.
- [x] P4. migration 실행 전 callback 거부는 candidate failed history를 남기지 않고, 명시적 fixture 입력 수정 후 repair 없이 재개 가능한 양성 경계를 검증한다. 이는 운영 preflight/writer 정지 증명이 아니다.
- [x] P5. 둘째 DDL 실패는 첫 DDL·앞 DML과 failed history를 남기고 새 JVM/DB restart 후 validate/migrate가 차단되는 음성을 검증한다. history 삭제나 repair는 하지 않는다. 성공한 fixture SQL을 변조한 checksum 음성 및 원문 복원 후 validate 양성을 검증한다.
- [x] P6. 별도 Actions 작업과 완전성 검사를 연결한다. 실제 4서비스 결과 및 최소 테스트 수가 없으면 실패하고 일반 payment 테스트의 기존 목록이 빠지지 않았음을 확인한다. 항상 artifact와 자기 fixture 정리를 실행한다.
- [ ] P7. 최종 head 정상 CI/전용 workflow 결과와 독립 검토를 반영해 PR을 준비한다. 실패·미충족을 전체 D065 완료로 바꾸지 않는다.

## 4. 완료 기준과 증거 한계

Actions는 Ubuntu/Java17, contents:read, 배포·secret·유료 기능 없이 전용 task를 실행한다. 원본 migration은 변경하지 않고 독립 fixture 디렉터리에만 candidate SQL을 만든다. 실제 서비스 파일 manifest와 Flyway history를 비교하고 before/after 데이터·schema·history를 보존한다. 결과 JSON과 JUnit XML을 함께 검사해 task 누락/0test/부분 서비스 결과의 green을 막는다.

실패 반례는 예외 발생만 보지 않고 남은 DDL/commit된 DML/failed history 및 다음 프로세스의 차단을 대조해야 한다. callback 이전 거부와 비트랜잭션 migration 실패는 다른 재개 조건이다. checksum 복원은 fixture 파일의 원래 bytes 복원이며 history repair가 아니다. 제품 migration 수정·자동 nullable 복귀·운영 DB rollback/재개 명령은 제공하지 않는다.

로컬 SQL33, writer JPA71, 이 작업의 actual Flyway 결과는 서로 다른 증거다. 실제 운영 writer 중지/전환, 실제 legacy provenance와 표본, 운영 DDL 및 P22/GKE는 여전히 별도다. fresh/upgrade 성공이 mixed writer 안전성이나 운영 rollout 준비를 뜻하지 않는다. 로컬 중량 실행은 하지 않으며 최종 실제 결과는 Actions artifact와 audit에 기록한다.

## 5. 실행 결과

수정headf834의Actions39583989047에서실제4서비스/76child/일반334/전용1/재시작보존검증이통과했다. 첫실패와수정후actualport변경증거는audit와 `evidence/D-065-flyway-contract/README.md`에분리보존했다. P2부터P6은이실제실행범위로완료이며P7의최종main통합CI/독립검토는아직미완이다. 운영계약완료로확대하지않는다.
