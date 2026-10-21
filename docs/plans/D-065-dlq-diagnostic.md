---
grade: M
---
# D-065 DLQ 첫 테스트 실패 진단

## 1. 명제와 코드 전제

실제 실패 경계를 확인하지 않고 timestamp 값/새 writer를 원인으로 단정하거나, timeout을 늘리거나 단순 재실행 성공으로 원인을 닫으면 미완이다. 이 단계는 payment 테스트의 관측 보강과 독립 재현한 만료 timestamp fixture 수정이며 제품 동작이나 성공 기준을 바꾸지 않는다. 원 CI 실패의 과거 broker 증거는 없어 그 원인은 확정하지 않는다.

| 근거 | 확인 |
|---|---|
| PR194 run39572731873 | head57afde97, checkout merge ac426be(57af into main4a4a36a), seed1792572889718, payment334개 중1실패 |
| 원본 XML | DlqIntegrationTest 첫 timestamp testcase20.470초 실패, 뒤 trace/route 두 testcase는 통과. XML2201bytes SHA25621f5668d9175942ae2fb54fb89be58678f674598f94121ecaa6b7a9faeea1762 |
| exact head와 checkout merge 소스 | 동일 SHA256f365af380d7fbcfb90c4be33ffb52a8d8ece19ebe5b9b8e91d40062f8ebc2eef. line193은 DLT records.anySatisfy, DB목록 assert는195. 원장[] 해석은 철회 |
| 마지막 await 실패 | 검증 consumer queue가 빈 상태. DB 존재/부재나 timestamp 값 비교는 이 실패로 측정되지 않음 |
| AbstractIntegrationTest.awaitListenerAssignment | running && assigned nonempty만 확인. 모든 구독 topic/partition이나 offset 초기화 완료를 확인하지 않음 |
| DlqIntegrationTest.setUp | registry 전체 할당 대기 후 queue clear와 ledger deleteAll. cleanKafkaTopics는 호출하지 않음 |
| payment consumer 설정 | auto-offset-reset=earliest. latest 초기화라고 가정하면 안 됨 |
| 테스트 error handler | test primary DefaultErrorHandler, 100/100/100ms 후 실제 DeadLetterPublishingRecoverer. 원장 listener는 별도 factory/error handler/group |
| 로그 설정 | AbstractIntegrationTest에는 @ActiveProfiles가 없고 payment base의 기본 profile은 local이다. logback은 local/default/k8s 분기다. CI workflow와 raw log는 SPRING_PROFILES_ACTIVE=test이고 첫 local baseline 실제 로그는 profile local이다. test profile에는 appender가 없어 banner-only 대 상세로그 차이를 설명한다. 첫 baseline은 CI 동등 실행이 아니다 |

등급 M: payment 통합 테스트의 lifecycle/실패 진단 계약에 영향이 있다. 공용 helper, 업무 코드, 새 schema, 운영 인프라는 현재 변경하지 않는다. 새 ADR 결정은 없다. shared helper 수정이나 제품 원인이 드러나면 범위를 재판정한다.

## 2. 작업 항목

- [x] P1. 실패 원본·seed·exact checkout source를 보존하고 DLT queue와 DB 경계를 구분한다.
- [x] P2. payment 테스트에 bounded 관측을 추가한다. setUp 전/할당 뒤/queue clear 뒤/producer ack/await 실패 시각, registry별 group·running·실제 assigned topic/partition, 원본 producer RecordMetadata, 마지막 queue 크기·key 일치 및 DB assert 도달 여부를 기록한다. 기존 동기화/timeout을 바꾸지 않는다.
- [x] P3. test-only logging으로 Kafka assignment/reset/offset 초기화, primary error handler의 DLT publish 시작/결과/예외, 독립 검증 listener 수신을 관측한다. payload/credential은 기록하지 않는다. KafkaAdmin 보충 조회가 필요하면 실패 이후에만 request/default-api timeout과 future get/close 상한을 두고, 진단 오류는 원본 실패를 덮지 않는다.
- [x] P4. root heavy 승인 후 원 seed 전체 payment를 실행하고 실제 최초 테스트와 뒤 두 테스트의 이벤트 순서를 비교한다. 원본 실패와 새 결과를 분리하며 재현되지 않아도 원인 미확정을 유지한다.
- [x] P5. observer drop 및 loopback Admin 무응답 음성으로 원20초 실패/진단오류/cleanup을 확인하고 원복hash를 보존한다. 독립 기본Kafka old/recent 대조로 old fixture의 retention 삭제를 재현한 근거에 따라 값만 실행마다 한 번 샘플링한 now-60s로 바꾼다. 원CI실패 원인확정과 구분한다.

- [x] P6. 별도 PR-path/수동 workflow를 구현해 Ubuntu·Temurin17.0.20·test profile·원seed 전체 payment:build 검증을 준비한다. 최종head CI결과는 아래 미충족에 별도로 기록한다. 기존 CI randomization은 그대로 유지한다. contents:read/15분timeout/항상artifact와 신규 test-owned 컨테이너만 정리하는 범위이며 배포·secret이 없다.

## 3. 검증 방법과 결과 해석

첫 local-profile baseline은 `./gradlew :payment-service:test -PtestSeed=1792572889718 --no-daemon --no-parallel --max-workers=1`로 실행했다. CI는 `SPRING_PROFILES_ACTIVE=test`를 지정하므로 이 첫 실행은 CI 동등 재현이 아니다. 환경을 맞춘 다음 baseline 명령은 `SPRING_PROFILES_ACTIVE=test ./gradlew :payment-service:cleanTest :payment-service:test -PtestSeed=1792572889718 --no-daemon --no-parallel --max-workers=1 --console=plain`이다. CI의 `:payment-service:build`와 비교해 assemble은 제외한 test 범위다. heavy 슬롯 승인 전 실행하지 않는다. 최초 실패/현재 source hash/로그/XML/컨테이너 정리를 보존한다. 실제 기존 세 momens 컨테이너는 건드리지 않는다.

기존 await20초, send10초, assignment30초와 Slack/헤더/timestamp/원장 성공 조건은 그대로 유지한다. producer ack는 publication의 증거이며 DLT consume나 원장 적재까지 뜻하지 않는다. partial assignment, backlog/offset reset, handler publish 실패, DLQ observer와 ledger consumer 중 어느 경계가 늦거나 실패했는지 관측으로 분리한다. 첫 testcase 실패와 뒤 둘 통과만으로 startup race라고 확정하지 않는다.

로그 보강이 timing에 영향을 줄 수 있으므로 관측 실행 통과는 수정 성공이 아니다. consumer position을 다른 스레드에서 직접 호출하거나 새 warm-up 메시지/대기를 넣어 순서를 바꾸지 않는다. 진단용 원격 조회는 실제 test 소유 Kafka에만 제한하고 timeout을 둔다. 실제 writer 결함이 확인되면 독립 scope/PR로 고치고 반증을 포함해 검증한다.

원본 경로: `/private/tmp/d100-ci-payment-39572731873/payment-service/build/test-results/test/TEST-com.peekcart.global.kafka.DlqIntegrationTest.xml`, `/private/tmp/pr194-payment-failed.log`, `/private/tmp/pr194-DlqIntegrationTest.java`. 운영/외부 PG/GKE/실데이터는 범위 밖이다. 무료 후속 signed gateway/Flyway 작업은 이 공유 CI 실패 분리 뒤 이어간다.

## 4. 실제 결과와 남은 검증

- 원 CI: 334개 중1실패, 첫 timestamp20.470s, final poll은 DLT 검증queue[]였다. 원장 존재/부재나 timestamp equality는 이 실패로 측정되지 않았다.
- 첫 local-profile baseline334PASS(3m48s)는 CI동등 재현이 아니다. test-profile baseline334PASS(1m29s), 실제54 XML class순서동일, stdout295bytes도CI와동일했다. 로컬JDK17.0.11/macOS 대CI17.0.20/Linux 차이는 남는다.
- 진단 source dc00bbfa의 test-profile full334PASS(1m35s). observerdrop음성은 단일case21.084s expectedfail, 실제 offset/committed 조회약61ms. loopback무응답음성은22.403s expectedfail, 진단조회약1.020s후 ExecutionException/close, 원 AssertionError[] 유지. 정상dc00bbfa 복원확인.
- 별도 Kafka3.8.1 기본설정 probe1PASS(36.336s): topicretention/segment7일,delete,CreateTime,beforemaxLongMAX; brokercheck300000ms. fixedold와now-60s 모두offset0 ack 후 old만start0→1/empty, recent는start0/end1과정확timestamp읽기유지. broker로그에 largestRecordTimestamp1757000123456의retention위반 삭제가명시된다. rawsource/설정/시계열/삭제로그는 [증거](evidence/D-065-dlq-diagnostic/)에 보존했다.
- 모든로컬실험 자기fixtures정리 및 전체Docker inventory 전후동일. 임시probe/음성변이는 제거했다. 최종timestamp변경은 아직로컬full로검증하지 않았다. 사용자의 Actions우선 지시에 따라 최종head 정상CI와별도고정seed workflow에서검증한다.
- 미충족: 최종head 전체CI/원seedLinux재현job/독립리뷰. 과거CI원인은여전히미확정이다. 독립fixture결함수정을원CI원인확정으로쓰지않는다. D065전체완료,운영timestamp표본,실제GKE/PG/운영DDL/replay허용을뜻하지않는다.
