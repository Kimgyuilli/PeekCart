---
grade: L
---
# D-075 outbox 발행 처리율 기본값 처분 (+ D-106 ⑥)

기준: 2026-10-25, main `6905952`, 브랜치 `debt/d075-outbox-throughput`. 결정: ADR-0045.
원자료: `docs/plans/done/D-075.md`·`docs/plans/evidence/D-075/`(로컬 분해), `docs/progress/evidence/d078-gke-20261024.md`
§S3·§읽는 법 ⑤, `loadtest/reports/2026-10-24/s3/*`, `loadtest/reports/2026-10-24/session-issues.md` #6.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- order·product·payment 의 outbox poller 가 여전히 주기 5s·배치 100(서비스당 상한 20건/초)으로 돈다.
- ShedLock 최소 보존(`lockAtLeastFor`)이 애노테이션에 하드코딩돼 있거나, 주기보다 길어도 부팅이 성공한다
  (D-075 실측: delay 500ms 에 4s 보존이면 같은 인스턴스의 다음 실행이 매번 skip 되어 주기가 4s 로 늘어난다).
- 주기·최소 보존 키가 빠지거나 불변식(주기 0 초과, 0 이상 최소 보존 미만 주기, 최소 보존 미만 lockAtMostFor)을 어기는데
  부팅이 성공하고, 실행마다 예외로만 드러난다.
- 설정한 주기·최소 보존·배치가 실제 스케줄러 등록, ShedLock 의 lock 설정, poller 조회 한도에 들어가는지 보이는 테스트가 없다.
- 이벤트마다 broker ack 를 동기로 확인한 뒤 PUBLISHED 를 저장하는 발행 의미가 바뀐다(이번 범위는 설정만이다).
- 측정 overlay `gke-d078-contention` 이 order outbox 를 env 로 덮어써서, 재측정이 새 기본값이 아닌 값을 잰다.
- "운영 기본값 5s" 같은 주석·문서·테스트 전제가 남아 새 기본값과 어긋난다.
- 결정과 대안·재검토 조건이 ADR 로 남지 않는다.

D-106 ⑥(배수가 예약 미확정 만료보다 길 때 대량 취소)은 이 처분으로 닫는다. 실제 부하(170 주문/초, 60초)에서 만료 취소 0 과
배수 시간을 확인하는 것은 D-078 재측정 몫이며, 이 PR 은 그 결과를 주장하지 않는다.

등급 L: 공유 모듈(`peekcart-common-messaging`)과 4개 서비스 설정, 측정 overlay 를 건드린다. 설정 키(`app.outbox.polling.*`)는
4서비스가 공유하는 계약 표면이고 새 필수 키가 생긴다. 되돌리려면 이 작업 밖의 yml·overlay 도 같이 고쳐야 한다. 사용자 결정으로
ADR-0045 를 쓴다.

## 2. 배경

### 2.1 코드 검증

| 전제 | 확인 | 결과 |
|---|---|---|
| poller 스케줄 | `OutboxPollingScheduler` | `@Scheduled(fixedDelayString = "${app.outbox.polling.delay:5s}")`, `@SchedulerLock(name = "${app.outbox.lock-name:outboxPollingJob}", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")`. 주석 "운영 기본값은 5s 로 불변이다" |
| poller 본문 | `OutboxPollingService` | `@Transactional` 없음. `findPendingEvents(batchSize)` 뒤 이벤트마다 `buildRecord` → `send().get(publishTimeout)` → `markPublished` → `save`. 실패는 `incrementRetry`·`save`, `maxRetry` 도달 시 FAILED + Slack. `cycleTimeout` 은 이벤트 시작 전에만 검사한다. 생성자 `@Value` 기본값 batch 100·retry 5·publish 6s·cycle 4m, 0 이하는 생성 시 예외 |
| 서비스 설정 | 4서비스 base `application.yml` `app.outbox` | batch-size 100, max-retry 5, publish-timeout 6s, cycle-timeout 4m, lock-name 서비스별. `delay` 는 선언 없음(기본 5s). 프로파일 yml(local·k8s)에는 `app.outbox` 키 없음 |
| 공유 범위 | `settings.gradle`, 각 `build.gradle` | `peekcart-common-messaging` 은 order·product·payment·notification 만 의존. user-service·gateway 는 poller 를 스캔하지 않는다 → 새 필수 키는 4개 yml 에 필요 |
| LockProvider | `ShedLockConfig`, shedlock-provider-jdbc-template 6.3.1 jar(javap) | `JdbcTemplateLockProvider.usingDbTime()` → MySQL 은 `MySqlServerTimeStatementsSource`. 획득 `UPDATE ... SET lock_until = UTC_TIMESTAMP(3)+lockAtMostFor, locked_at = UTC_TIMESTAMP(3), locked_by = :lockedBy WHERE name = :name AND lock_until <= UTC_TIMESTAMP(3)`, 해제 `SET lock_until = IF(locked_at+lockAtLeastFor > UTC_TIMESTAMP(3), locked_at+lockAtLeastFor, UTC_TIMESTAMP(3)) WHERE name = :name AND locked_by = :lockedBy`. 시각이 전부 DB 시각이라 노드 간 시계 차이가 들어가지 않는다. 해제가 `locked_by` 를 보므로 E2E 대조군 ① 이 심은 행(`locked_by='e2e-negative-control'`)을 poller 해제가 덮지 않는다 |
| lock 설정 검사 시점 | shedlock-core 6.3.1 `LockConfiguration`, shedlock-spring `SpringLockConfigurationExtractor`(javap) | `lockAtLeastFor > lockAtMostFor`(같음은 허용)·음수 `lockAtMostFor` 면 `IllegalArgumentException`. 이 객체는 **실행마다** 애노테이션에서 만든다 → 잘못된 값은 부팅이 아니라 매 실행 예외다. 음수 `lockAtLeastFor` 는 막지 않는다 |
| duration 형식 | shedlock-spring `StringToDurationConverter` | ISO-8601 과 `^([+-]?\d+)([a-zA-Z]{0,2})$`(ns·us·ms·s·m·h·d) → `400ms` 를 읽는다. Boot Duration 바인딩과 같은 형식 |
| 기존 관례 | `OrderSchedulerProperties`·`StockSchedulerProperties`, 두 `*PropertiesTest` | `@ConfigurationProperties`+`@Validated`+`@AssertTrue`(최소 보존 미만 최대, 주기 양수), 스케줄러에 `@EnableConfigurationProperties`, placeholder 인라인 기본값 없음, base yml 선언. 테스트는 `ApplicationContextRunner` 로 부팅 실패를 보고 yml 값을 고정한다. properties 빈은 주입처 없이 부팅 검증용으로만 존재한다 |
| 검증 의존성 | `common/build.gradle`, 4서비스 `build.gradle` | `:common` 은 `starter-validation` 을 compileOnly 로 둔다(런타임 검증은 서비스 몫). `peekcart-common-messaging` 에는 검증 의존성이 없다. 4서비스는 모두 `starter-validation` implementation |
| 조회 비용 | 4서비스 Flyway | `idx_outbox_status_created (status, created_at)` → `WHERE status='PENDING' ORDER BY created_at LIMIT n` 은 인덱스 범위 스캔. D-075 로컬 batch500 profile(300건을 1 cycle 에 비움)의 조회 누적 9.7ms, 이벤트당 본문 약 6ms |
| 발행 timeout | 서비스 yml, kafka-clients 3.9.2 `ProducerConfig`(javap) | order·payment 는 `max.block.ms 3000`·`delivery.timeout.ms 5000`. product·notification 은 덮지 않아 기본 `max.block.ms 60000`·`delivery.timeout.ms 120000` |
| 만료 취소 경로 | `OrderTimeoutScheduler`, `OrderJpaRepository`, `loadtest/scripts/d002bc-contention.js` | 부하 스크립트는 결제를 호출하지 않는다. `cancelUnconfirmedReservations`(60s 주기)가 PENDING·`reservation_confirmed_at IS NULL`·`ordered_at < now-5분`(하드코딩)을 취소한다. 결제 요청 만료는 15분(PAYMENT_REQUESTED 대상), lease 만료는 TTL 30m 이라 세션 배수(최대 534s) 안에서는 해당 없음. 세션 기록 #6 의 "lease 만료 뒤" 추정은 이 경로로 정정된다 |
| 세션 수치 | `s3/*-drain.tsv`·`*-counts.txt`, 증적 §S3 | 사가 왕복 p99 r1 247.5s(취소 0), r2·r3·spread p95 432~504s(취소 2,609·3,839·3,180). product 발행 20.0~20.6건/초. order(500ms·500) 부하 종료 시 PENDING 4,214·7,872·8,983·8,035, 부하 뒤 배수 79·104·116·103초(약 53·76·77·78건/초). k6 종료 3~4초 뒤 첫 표본까지 order 발행은 224·458·1,128·2,166건(부하 구간 약 4~36건/초) |
| 측정 overlay | `k8s/overlays/gke-d078-contention` | `patches/order-service-outbox-polling.yml` 이 env `APP_OUTBOX_POLLING_DELAY=500ms`·`APP_OUTBOX_POLLING_BATCHSIZE=500`. overlay 를 렌더하는 lint: `kustomize-namespace-lint.sh`·`servicemonitor-selector-lint.sh`, `ci-lint-health-selftest.py` 는 fixture 만 쓴다 |
| 낡을 전제 | grep | 테스트: `OutboxThroughputLocalProbeTest:80`(`lockAtLeastFor == "PT4S"`, opt-in), order 통합 테스트 주석 4곳("5초마다"·"@Scheduled 5초"·"fixedDelay = 5000"). E2E: `saga_e2e.py:1127` 예약 부재 창 30초를 "기본 5s × 6주기" 로 유도. 문서: `04-design-deep-dive.md` §9-10 예시(`PT4S`·`fixedDelay = 5000`), `06-testing-strategy.md` §13-1-b ① "poller 5초 × 6". ADR-0031 D2 는 불변이라 ADR-0045 에 영향을 적는다 |
| 배경 스케줄링을 켠 테스트 | grep `app.scheduling.enabled=true` | payment `ShedLockIntegrationTest`(lock 행 15초 대기), order `SchedulerPoolStarvationTest`·`OrderSchedulerWiringIntegrationTest`, product `StockSchedulerWiringIntegrationTest`. 주기가 짧아져도 단언을 깨지 않는다(행이 더 빨리 생기고, 풀 4 에서 빈 실행은 수 ms) |
| DLQ reconciler | `DeadLetterPublicationReconciler` | `deadLetterPublicationReconcileJob` 5s·4s, 주석 "outbox poller 와 같은 주기 축". 이번에 바꾸지 않는다(2.2) |

### 2.2 대안과 선택

**처분 방향.** 사용자 결정(2026-10-25): 3서비스 설정 상향, 이벤트별 동기 ack 의미 유지. 비동기 pipelining·저장 묶기는 발행·커밋
실패 의미가 바뀌어(ADR-0020 D1 at-least-once, ADR-0022 drain) 별도 설계가 필요하므로 ADR 의 재검토 조건으로 둔다.

**lockAtLeastFor 값.** 원래 규칙은 "주기보다 약간 짧게"였다(PHASE2: 5s 주기에 4s, 60s 에 30s). 여러 replica 의 실행을
주기당 한 번으로 묶는 효과다. 시계 차이 보정 역할은 DB 시각을 쓰므로 없다. 정합성 역할도 없다: 실행 중에는
lockAtMostFor 가 막고, 이벤트별 save 가 해제 전에 커밋된다. (가) 0s, (나) 400ms(규칙 유지, 주기의 80%). (나)를 고른다.
같은 인스턴스의 다음 시도는 앞 실행 종료 뒤 500ms 라 400ms 보존에 막히지 않고(여유 100ms 이상), 여러 replica 의
빈 실행은 초당 2.5회 이하로 묶인다. backlog 가 있으면 실행 시간(500건 약 6초)이 보존보다 길어 처리율에 영향이 없다.
불변식 `0 <= lockAtLeastFor < delay` 를 부팅 시 검사해 D-075 의 상황(주기만 내리고 보존을 그대로 둠)을 막는다.

**notification.** replay 재발행 전용이라 사가 경로가 아니고 운영자가 여는 양만 흐른다. 측정 근거가 없으므로 값을 바꾸지 않되,
인라인 기본값을 없애므로 5s·4s·100 을 base yml 에 명시한다. 재검토 조건: 대량 replay 로 drain(ADR-0022 ⓐ)이 길어질 때.

**검증 위치.** properties 클래스는 스케줄러와 같은 `peekcart-common-messaging` 에 둔다. 관례(`:common`)대로 검증 의존성은
compileOnly 로, 단위 테스트용 런타임 검증은 testImplementation 으로 추가한다. 서비스 yml 값은 서비스 모듈 테스트가 자기
`src/main/resources` 를 읽어 고정한다(다른 모듈 파일을 읽으면 Gradle 입력에 잡히지 않는다).

**overlay.** order env 덮어쓰기를 지운다. 새 base 값과 같아 효과가 없고, 남겨 두면 base 를 다시 바꿨을 때 재측정이 기본값이
아닌 값을 잰다. order.created 발행 속도(⑤ 경합의 입력)는 base 500ms·500 으로 Phase 4 조건과 같다.

**DLQ reconciler.** 5s·4s 를 유지한다. replay 원장 전이는 사가 경로가 아니고, ADR-0022 ⓐ' 가 그 5초 지연을 fail-closed 로
받아들였다. ADR-0045 에 "같은 주기 축" 주석이 3서비스에서 더는 성립하지 않는다고 적는다.

### 2.3 PLAN-BLINDSPOTS

- B1b: 락 이름 문자열(`*OutboxPollingJob`)은 바꾸지 않는다. E2E `saga-e2e-smoke.sh` 의 `orderOutboxPollingJob` 주입과 payment
  `ShedLockIntegrationTest` 의 이름 단언은 그대로 성립한다.
- B4: 키(`app.outbox.polling.delay`·`lock-at-least-for`·`batch-size`)와 파일을 §3 에 적었다.
- B6(b): 공유 모듈 빈이 필수 설정을 갖게 된다. 검증 의존성은 4서비스에 이미 있고, 키는 4개 yml 에 모두 선언한다.
  user-service·gateway 는 poller 를 스캔하지 않는다.
- B12: 대조군 ① 의 ShedLock 행 주입은 poller 실행 도중에 들어가도 해제(`locked_by` 조건)가 덮지 않는다. 주기가 짧아져도 창이 생기지 않는다.
- B13: 재측정이 이 변경 뒤의 기본값을 잰다. 재측정 계획(레인 A)이 overlay 의 order 덮어쓰기 삭제를 전제표에 반영해야 한다(보고로 넘긴다).
- B1·B2·B3·B5·B7~B11: 이동·추출·스키마·이벤트 순서 변경이 없어 해당 없음.

## 3. 작업 항목

- [x] P1. `docs/adr/0045-outbox-polling-throughput-defaults.md` 작성(Context·Decision·Alternatives·Consequences·재검토 조건·References)과
  `docs/adr/README.md` 인덱스 행.
- [x] P2. `peekcart-common-messaging`:
  - `build.gradle`: `compileOnly`·`testImplementation 'org.springframework.boot:spring-boot-starter-validation'`.
  - `OutboxPollingProperties`(prefix `app.outbox.polling`): `delay`·`lockAtLeastFor` `@NotNull`, `@AssertTrue` 셋(주기 0 초과,
    `0 <= lockAtLeastFor < delay`, `lockAtLeastFor < lockAtMostFor`).
  - `OutboxPollingScheduler`: `@Scheduled(fixedDelayString = "${app.outbox.polling.delay}")`,
    `lockAtLeastFor = "${app.outbox.polling.lock-at-least-for}"`(인라인 기본값 없음), `lockAtMostFor` 는 상수 `LOCK_AT_MOST_FOR = "PT5M"`
    로 두고 properties 가 같은 값으로 검사, `@EnableConfigurationProperties(OutboxPollingProperties.class)`, 주석 갱신.
  - `OutboxPollingService` 는 바꾸지 않는다(발행 의미 유지).
- [x] P3. 4서비스 base `application.yml` `app.outbox.polling`: order·product·payment `delay: 500ms`·`lock-at-least-for: 400ms`·`batch-size: 500`,
  notification `delay: 5s`·`lock-at-least-for: 4s`·`batch-size: 100`(명시). 근거 주석은 ADR-0045 참조.
- [x] P4. 테스트:
  - common-messaging `OutboxPollingPropertiesTest`: 불변식(V1)과 placeholder 형식(V2).
  - common-messaging `OutboxPollingSchedulerWiringTest`: 실제 스케줄러·ShedLock 프록시·poller(V3).
  - 서비스별 `{Order,Product,Payment,Notification}OutboxPollingPolicyTest`: base yml 값과 프로파일 비선언(V4).
  - `OutboxThroughputLocalProbeTest` 의 `PT4S` 단언을 placeholder 단언으로 바꾼다.
  - order 통합 테스트 주석 4곳의 "5초" 를 주기 표현으로 바꾼다.
- [x] P5. `k8s/overlays/gke-d078-contention`: `patches/order-service-outbox-polling.yml` 삭제, kustomization patches 항목·주석 갱신.
- [x] P6. 낡은 전제: `scripts/e2e/saga_e2e.py` 예약 부재 창 주석(30초 유지 근거), `docs/06-testing-strategy.md` §13-1-b ① 창 칸,
  `docs/04-design-deep-dive.md` §9-10 outbox 예시.

## 검증 방법

- V1 (P2 불변식, 단위): `ApplicationContextRunner`(+`ConfigurationPropertiesAutoConfiguration`·`ValidationAutoConfiguration`)로 바인딩한다.
  500ms·400ms 는 부팅 성공과 값 일치. 실패 주입: 보존 = 주기(500ms·500ms), 보존 > 주기(500ms·4s, D-075 조건), 보존 5m(주기 10m),
  보존 음수, 주기 0, 주기 키 누락, 보존 키 누락 → 각각 부팅 실패. 변이: `lockAtLeastFor < delay` 검사를 지우면 D-075 조건 테스트가 실패해야 한다.
- V2 (P2 placeholder, 단위): 리플렉션으로 `@Scheduled.fixedDelayString`·`@SchedulerLock.lockAtLeastFor` 가 정확히
  `${app.outbox.polling.delay}`·`${app.outbox.polling.lock-at-least-for}`(기본값 없음)이고 `lockAtMostFor` 가 `PT5M` 이며
  properties 검사 상한과 같은 값인지 본다. 변이: `:5s` 인라인 기본값을 되살리면 실패해야 한다.
- V3 (P2 배선, 단위): `@EnableScheduling`·`@EnableSchedulerLock` 문맥에 실제 `OutboxPollingScheduler`·`OutboxPollingService`, mock
  repository·KafkaTemplate, 기록용 `LockProvider` 를 둔다. 값 300ms·200ms·batch 37·lock-name `probeOutboxPollingJob` 으로 띄우면
  등록된 작업이 하나이고 `FixedDelayTask` 간격 300ms, 실제 발화에서 ShedLock 이 받은 lock 설정이 (`probeOutboxPollingJob`, 5분, 200ms),
  repository 가 `findPendingEvents(37)` 을 받는다. 변이: 애노테이션을 `lockAtLeastFor = "PT4S"` 로 되돌리면 실패해야 한다.
- V4 (P3, 서비스 단위): 각 서비스 테스트가 `src/main/resources/application.yml` 을 Boot YAML 로더와 `Binder` 로 읽어 properties 로
  바인딩하고 검증 위반 0, 값 고정(3서비스 500ms·400ms·500, notification 5s·4s·100)을 본다. `application-*.yml` 은 `app.outbox.polling`
  을 선언하지 않는다. 변이: product yml 의 delay 를 5s 로 바꾸면 그 서비스 테스트가 실패해야 한다.
- V5 (P5): `kubectl kustomize k8s/overlays/gke-d078-contention` 의 order-service 컨테이너 env 에 `APP_OUTBOX_POLLING_*` 가 없고,
  상위 `gke-d002bc-mysql-2000m` 과의 렌더 차이가 product replicas 뿐이다. `kustomize-namespace-lint.sh`·`servicemonitor-selector-lint.sh` 통과.
- V6 (P6 sweep): 현행 코드·문서·스크립트에서 outbox 주기를 5초로 전제하는 문장(`기본 5s`·`5초마다`·`poller 5초`·`fixedDelay = 5000`·
  `PT4S` 의 poller 언급)이 0건이다. 이력 문서(`docs/progress/`·`docs/plans/done/`·`docs/plans/evidence/`·`loadtest/reports/`)와
  불변 ADR 본문은 제외하고, 남긴 것은 audit 에 사유를 적는다.
- V7 (로컬 범위): `:peekcart-common-messaging:test`, 4서비스의 새 단위 테스트(`--tests`), 4서비스 `compileTestJava`,
  `scheduler-lock-contract-lint.sh`, `ci-lint-health-selftest.py`, writing-lint(커밋·본문·제목), consistency precheck,
  `docs-preservation-ci.py --base 6905952`. Docker·Testcontainers·E2E 는 로컬에서 돌리지 않는다(사용자 지시 2026-10-21).
- V8 (PR CI): 서비스 통합 테스트(새 필수 키로 4서비스 컨텍스트 부팅, payment `ShedLockIntegrationTest`)와 E2E 시나리오·음성 대조군
  (① ShedLock 주입이 500ms poller 를 막고 해제 뒤 예약 도착)을 포함한 gate 성공.

## 완료 조건

- P1~P6 완료, V1~V7 결과를 audit 에 기록, PR CI gate 성공(실행 ID 기록). 병합은 하지 않는다.
- TASKS·PHASE5 갱신은 root 의 기록 PR 이 한다. 넘길 사실: D-075 처분(설정 상향·락 최소 보존 설정화, ADR-0045), D-106 ⑥ 종결,
  부하 검증은 D-078 재측정, overlay 의 order 덮어쓰기 삭제.

## 미해결

- 실제 부하 검증(만료 취소 0, 배수 시간, product 발행률)은 D-078 재측정이다. 판정 기준과 재검토 조건은 ADR-0045 에 있다.
- 비동기 pipelining·저장 묶기는 ADR-0045 재검토 조건이 충족될 때 별도 설계(ADR-0012·0020·0022 의미 재검토 동반)로 한다.
- 부하 중 order 발행률 저하(부하 구간 약 4~36건/초, 부하 뒤 약 77건/초)의 원인(Hikari 대기·CPU 경합 등)은 분리하지 않았다. 재측정 관측 항목이다.
- product·notification 은 producer timeout 을 덮지 않아 `max.block.ms` 기본 60s 다. 메타데이터를 못 받는 장애에서는 cycle 마지막
  이벤트 하나가 60s 를 더 쓸 수 있어 최악 사이클이 lockAtMostFor 5분에 닿는다. 배치 크기와 무관한 기존 한계라 이번에 고치지 않고
  ADR-0045 에 적는다. 부채 등록 여부는 root 가 정한다.
- E2E ①② 예약 부재 창 30초는 유지한다(새 주기의 60배라 검출 범위만 넓다). 창 단축은 범위 밖이다.
