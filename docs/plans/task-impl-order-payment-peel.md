# task-impl-order-payment-peel — Order/Payment 서비스 peel + root app 해체

> 사가 클러스터 strangler 완결(#56~#63, Order↔Product·Order↔Payment src 동기 결합 0) 이후, **마지막 두 도메인 Order/Payment 를 독립 모듈로 peel** 하고 root 모놀리스 app 을 해체한다. 이로써 5개 서비스 풀 분해(ADR-0010 §5) 완료.
> 선행 ADR: ADR-0010(5개 서비스 경계), ADR-0011(멀티모듈 구조), ADR-0012(이벤트/Saga·outbox 소유), ADR-0014(전환기 인증 공유 모듈). **새 ADR 불필요** — 경계/구조/이벤트 결정 보유. GP-1 자동통과(boundary 신호 발화·ADR 보유, product peel 선례 #62 동일).
> peel 선례: notification(#53)·user(#55)·product(#62, 첫 *발행* 서비스). 본 작업은 **publisher+consumer 양방향 서비스 2개 + root 해체**라 product 보다 크다.
> **PR 분할(사용자 게이트 2026-06-18)**: 2 PR — **PR-a Order peel**(root 는 Payment+global 유지·계속 부팅) → **PR-b Payment peel + root 해체**(root src 통째 삭제·boot앱→aggregator 전환·잔여 global 테스트 rehome). 1서비스=1PR 관례 일치, 중간 그린 체크포인트 확보, 저위험.

## 1. 목표

Order(`com.peekcart.order.*`)·Payment(`com.peekcart.payment.*`) 도메인을 root 모놀리스에서 떼어 독립 `order-service`·`payment-service` Gradle 모듈로 만들고, 각각 독립 bootJar 로 부팅 가능하게 한다. **두 도메인이 모두 나가면 root `src/` 는 비므로 root 를 Spring Boot 앱에서 순수 빌드 aggregator 로 전환**한다. peel 후 전체 7→8모듈(common·common-auth·common-observability·notification·user·product·order·payment) 빌드/테스트가 그린이어야 한다.

**성공 기준 (PR-a)**: `./gradlew build test` BUILD SUCCESSFUL + `order-service:bootJar` 산출 + order-service 독립 컨텍스트 부팅(@PreAuthorize·Kafka consumer 4종·outbox poller 배선) + root app 은 Payment 로 계속 부팅 + 가드(`assertNoServiceProjectDeps`·`assertNoDuplicateGlobalFqcn`·`assertNoOrderPaymentSourceCoupling`(스캔경로 갱신)·`assertNoOrderProductSourceCoupling`) 통과.

**성공 기준 (PR-b)**: `payment-service:bootJar` 산출 + payment-service 독립 부팅 + **root `src/main`·`src/test` 소멸, root = aggregator(bootJar 없음)** + 잔여 global 테스트 전부 rehome(:common test / payment-service) + shared schema 런타임 마이그레이션 소유권 재지정(B5) + 가드 전부 통과 + `./gradlew build test` 8모듈 그린.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-06-18)

- **root `src/main` 잔여 = order·payment·global 3개뿐**(`PeekcartApplication.java` 진입점 1).
  - `com.peekcart.order.**`(presentation/application/domain/infrastructure) — 발행 `order.created`/`order.cancelled`(aggregateType `ORDER`), 소비 `payment.requested`/`payment.completed`/`payment.failed`/`stock.reservation.result`(`OrderEventConsumer` 4 listener).
  - `com.peekcart.payment.**` — 발행 `payment.completed`/`payment.failed`/`payment.requested`(aggregateType `PAYMENT`), 소비 `order.created`/`stock.reservation.result`/`order.cancelled`(`PaymentEventConsumer`).
  - `com.peekcart.global.*` 3묶음(**root-전속, :common 아님**): `outbox`(실행세트 7종: `OutboxEvent`·`OutboxEventStatus`·`OutboxEventRepository`+`Impl`+`JpaRepository`·`OutboxPollingService`·`OutboxPollingScheduler`), `idempotency`(5종: `ProcessedEvent`·`IdempotencyChecker`·repo 3), `config`(`KafkaConfig`·`SecurityConfig`·`ShedLockConfig`).
- **order↔payment src/main 상호 참조 0건**(`assertNoOrderPaymentSourceCoupling` 가드 강제, strangler-5 #63). order·payment 는 이벤트(Kafka)로만 결합 → 독립 모듈로 분리 가능.
- **outbox poller 소유권은 이미 config-driven**: `OutboxPollingService` 가 `@Value("${app.outbox.polling.aggregate-types}")` 로 allowlist 주입, `findPendingEvents(aggregateTypes, …)` 가 자기 aggregateType 만 발행. ShedLock 이름은 `${app.outbox.lock-name:outboxPollingJob}`. 현재 root = `aggregate-types: ORDER,PAYMENT`/`lock-name: rootOutboxPollingJob`(product peel #62 P4 가 도입). → peel 시 **쿼리/코드 변경 없이 yml 분기만**: order-service=`ORDER`/`orderOutboxPollingJob`, payment-service=`PAYMENT`/`paymentOutboxPollingJob`.
- `assertNoDuplicateGlobalFqcn` 는 이미 **동적**(`subprojects.findAll { name.endsWith('-service') }` 자동 포함, product peel 에서 하드코딩 회귀 방지). order-service·payment-service 자동 편입. 단 `apps['root-app'] = [':'] + commonDeps` 하드코딩 항목은 PR-b 에서 root 가 global 클래스를 잃으면 무해해지나 `dependsOn ':classes'` 가 root java 플러그인 잔존을 전제 → PR-b 에서 처분(아래 B-root).
- global.kafka·global.cache **SUT 는 전부 `:common`** 소유(`MdcRecordInterceptor`·`MdcSnapshot`·`FixedSequenceBackOff`·`KafkaMessageParser`·`KafkaEventEnvelope`(outbox/dto)·`CachedPage`·`HarnessSmokeTtl`). 이들 테스트가 root test 트리에 있는 건 잔존일 뿐 → `:common` test 로 이동(B3).
- Flyway: 공유 스키마 `common/src/main/resources/db/migration/`(V1~V9, B5 단일 소유). **현재 root app 이 런타임 마이그레이션 적용** + root gradle `flywayMigrateShared` 태스크 존재. 서비스들은 Flyway 런타임 disabled.

### B1 — 역의존 스윕 (peel 대상의 인바운드 간선 처분, 테스트 포함)

src/main 인바운드(비-order/payment → order/payment): **0건**(global 은 도메인 무지, 가드 강제). src/test 인바운드 + 처분:

| # | 인바운드 (root test) | order/payment 사용 | PR | 처분 |
|---|---|---|---|---|
| 1 | `support/fixture/OrderFixture.java` | Order 도메인/DTO 팩토리. 소비처: order test 12 + support 1(자기참조). 비-order 소비 0 | a | **이동** → order-service test (단일 도메인 소비 → :common testFixtures 아님, B3) |
| 2 | `support/fixture/PaymentFixture.java` | Payment 팩토리. 소비처: payment test 5 + support 1 | b | **이동** → payment-service test |
| 3 | `order/**` 테스트(단위/슬라이스/통합 — **16개**, `find` 확인) | Order 도메인 | a | **이동** → order-service test (도메인 동반) |
| 4 | `payment/**` 테스트(**9개**, `find` 확인) | Payment 도메인 | b | **이동** → payment-service test |
| 5 | `global/outbox/OutboxKafkaIntegrationTest`(INTEG) | order+payment **둘 다** 시드해 outbox→Kafka e2e | a→b | **PR-a: order 의존 제거**(payment-observable 로 재작성, root 잔류). **PR-b: payment 와 함께 → payment-service** 로 이동 |
| 6 | `global/idempotency/IdempotencyIntegrationTest`(INTEG) | order+payment 시드 | a→b | #5 와 동일(PR-a payment-observable, PR-b → payment-service) |
| 7 | `global/config/ShedLockIntegrationTest`(INTEG) | 공유 poller ShedLock | b | **이동** → payment-service(또는 order-service) test. 복제 poller 검증 |
| 8 | `global/observability/ObservabilityMetricsIntegrationTest`(INTEG) | **outbox publish-timer probe 가 `"ORDER"` aggregateType row 저장**(`:84-85`, root poller 발행 의존). HTTP 부분은 이미 `/actuator/health`(`:61`, `/api/v1/*` 아님 — product peel 에서 이전됨) | a→b | **PR-a: outbox probe aggregateType `ORDER`→`PAYMENT` 로 변경**(root poller 가 PR-a 에서 `PAYMENT` 로 좁혀짐 → `ORDER` probe 는 미발행→timer 미증가→실패, B1b 재발). **PR-b: payment-service 로 이동(probe `PAYMENT` 유지)** |
| 9 | `global/kafka/DlqIntegrationTest`(INTEG) | consumer 실패→DLQ | b | **이동** → payment-service test(또는 order). 복제 consumer 인프라 검증 |
| 10 | `global/outbox/OutboxPollingServiceTest`(UNIT) | `OutboxPollingService`(서비스로 복제됨, :common 아님) | a | **이동** → order-service test (복제본 1곳 커버 충분) |
| 11 | `global/outbox/KafkaEventEnvelopeTest`·`global/kafka/{MdcSnapshotTest,MdcRecordInterceptorTest,FixedSequenceBackOffTest}`·`global/cache/HarnessSmokeTtlTest`(UNIT) | SUT 가 **:common** 소유 | b | **이동** → `:common` test (SUT 모듈 동반, B3) |
| 12 | `RootContextBootSmokeTest`(INTEG) | root app 부팅·`/api/v1/orders`·SlackPort 검증 | b | **삭제** — root app 소멸. 대체: `OrderApplicationTests`(PR-a)·`PaymentApplicationTests`(PR-b) 서비스별 부팅 스모크 |

> **B1b(string-level 결합)**: `/api/v1/orders`·`/api/v1/payments`(SecurityConfig permitAll·ObservabilityMetrics 프록시)·ShedLock 이름·캐시 이름·aggregateType 리터럴(`ORDER`/`PAYMENT`)을 함께 스윕. #8 ObservabilityMetricsIntegrationTest 가 컴파일 통과하나 런타임에 사라진 엔드포인트 프록시하는 패턴(product peel 회귀) 명시 처분.
> **검증 강제**: PR-a 후 `grep -rln "com\.peekcart\.order" src --include="*.java" | grep -vE "/order/"` → 0건. PR-b 후 `src/` 전체 소멸 → 잔존 0 자명.

### B8 — producer 전속 실행 인프라 복제 + 공유 DB poller 소유권 분리
order·payment 는 **둘 다 publisher+consumer** → 각자 outbox 실행세트 7종 + idempotency 5종 + `ShedLockConfig` + `KafkaConfig` 를 서비스로 **복제**(product peel 선례, guard 주석이 "다른 앱은 같은 classpath 공존 안 함 → 위반 아님" 허용). consume-only 였던 notification(idempotency 만 복제)과 달리 발행도 하므로 outbox 실행세트까지 필요(product 와 동일).
- **공유 DB 전환기 소유권 분리(DB 물리분리 ② 이연)**: root 해체 후 product-service·order-service·payment-service **3 poller 가 같은 `outbox_events` 공유**. 각 yml allowlist disjoint: `PRODUCT`(기존)·`ORDER`·`PAYMENT`. ShedLock 이름도 disjoint: `productOutboxPollingJob`·`orderOutboxPollingJob`·`paymentOutboxPollingJob`. **PR-a 중간상태**: root poller allowlist `ORDER,PAYMENT`→`PAYMENT` 로 좁힘(order 가 order-service 로 자가발행). `processed_events` 는 앱별 소비 이벤트 disjoint 라 자연 분리.

### B6 — 서비스가 :common 스캔으로 떠안는 횡단 빈 / 비전이 스타터
order-service·payment-service 진입점은 `com.peekcart.*` component-scan + `:common`/`:common-auth`/`:common-observability` 의존. product-service `build.gradle` 미러(발급/sign 제외 — order·payment 둘 다 비발급). 각 서비스 실제 사용 횡단 의존을 **/work 착수 시 grep 확정**(아래 후보):
- **Method Security(`@PreAuthorize`)**: order/payment controller 의 인증 — `OrderSecurityConfig`/`PaymentSecurityConfig`(`@EnableMethodSecurity` + common-auth `JwtFilter` SecurityFilterChain) 신설. root `SecurityConfig` 는 root 와 함께 소멸(둘로 분할). 발급자 아님(PasswordEncoder/sign 없음).
- **Bean Validation(`@Valid`)**: controller 요청 DTO → `spring-boot-starter-validation` 명시(Boot3 web 미포함, user/product B6 선례 500).
- **Redis(무조건 필수 — Codex P1#1)**: `spring-boot-starter-data-redis` 는 **조건부 아님**. ADR-0014 가 5개 서비스 전부 common-auth Redis blacklist read + **fail-closed** 를 결정(`docs/adr/0014-transitional-auth-module.md:66-75`)했고, `RedisTokenBlacklistLookupAdapter` 가 `RedisTemplate` 을 **필수 생성자 의존**으로 받는다(`peekcart-common-auth/.../RedisTokenBlacklistLookupAdapter.java:18-39`). product-service 도 무조건 data-redis 선언(선례). 추가로 order 단가 로컬 캐시가 `@Cacheable`(Redis)면 CacheConfig 도. → **order/payment build.gradle 에 data-redis + Redis 접속 설정 무조건 고정**, 부팅 시 blacklist adapter 가 RedisTemplate 으로 기동되는지 검증.
- **Kafka(+ NewTopic owner — Codex P1#3, 2차 P1#2 정정)**: consumer/outbox producer → `:common` kafka(api) 전이 확인, 미전이분 명시. **현재 토픽 생성자는 root `KafkaConfig` 단독**(`grep NewTopic` 확인: 메인 7 + 토픽별 `.dlq` 7 = **14 `NewTopic` bean** + `DeadLetterPublishingRecoverer`; product/user/notification 은 전환기 root 소유 전제로 listener-only). PR-b 에서 root 소멸 시 토픽 생성 책임 공백. → **producer-owns-topic 분산(ADR-0011 §71·ADR-0012 D4 정합, "새 ADR 불필요" 유지)**: 각 서비스가 *자기가 발행하는* 토픽의 `NewTopic`(메인+`.dlq`)을 선언한다.
  - order-service: `order.created`(+`.dlq`)·`order.cancelled`(+`.dlq`) — 4 bean
  - payment-service: `payment.completed`·`payment.failed`·`payment.requested`(각 +`.dlq`) — 6 bean
  - product-service: `product.updated`·`stock.reservation.result`(각 +`.dlq`) — 4 bean. **product 는 현재 listener-only(root 무임승차) → root 소멸 시 자기 토픽 생성자 부재 → PR-b 에서 product-service 에 이 4 bean 추가**(소량·ADR-정합).
  - `DeadLetterPublishingRecoverer`/error-handler 는 consumer 측 → 각 서비스 자기 consumer 설정에 보유(product `ProductKafkaConfig` 선례). **order-service 단독 owner(1차 안) 폐기** — ADR-0011/0012 의 발행 서비스 전속 원칙과 충돌·SPOF.
- **ShedLock**: poller → `shedlock-spring`·`shedlock-provider-jdbc-template` 명시.
- **Slack**: order/payment 가 `SlackPort` 사용 시 `:common` `SlackNotificationClient`(`@ConditionalOnProperty(slack.webhook.url)`) → 설정 필요(미설정 시 알림 무력화·부팅 안전).
- **Toss(payment-service 필수 — Codex P1#2)**: Payment 코드가 `toss.payments.secret-key`·`toss.payments.webhook-secret` 를 `@Value` 로 **필수** 요구(`TossPaymentClient.java:21`·`WebhookService.java:27-30`). root yml(`application.yml:70~`)에만 존재 → **payment-service `application*.yml` 로 placeholder 이관**(P10). `PaymentApplicationTests` 는 stub 없이 부팅시켜 누락 시 실패하도록.
- **@EnableScheduling(양 진입점 필수 — Codex P1#5)**: outbox poller(`@Scheduled`) + Order 의 `OrderTimeoutScheduler`(`@Scheduled` 2종, ShedLock 잡 `orderTimeoutCancelJob`·`orderReservationTimeoutJob`)가 동작하려면 진입점에 `@EnableScheduling` 필요. root·product 진입점에 존재(검증) → `OrderApplication`/`PaymentApplication` 에도 **명시**(P2/P10). 미설정 시 컴파일은 통과하나 런타임에 스케줄러 전부 무력화.

### B5 — 공유 스키마 물리 위치 + ⚠️신규: 런타임 마이그레이션 소유권
shared schema 는 `:common` 단일 소유 유지(V1~V9). 서비스 Flyway 런타임 disabled, 테스트는 `:common` testFixtures(`AbstractIntegrationTest`)로 적용(선례). **양 app 이 동일 DB 에 disjoint 엔티티 매핑 → `ddl-auto` validate/none 유지.**
- **⚠️ 신규 블라인드스팟(B10 후보)**: **현재 런타임 마이그레이션을 root app 이 적용**한다. PR-b 에서 root app 이 소멸하면 **공유 스키마를 런타임에 적용할 주체가 사라진다.**
- **선결 사실(이미 존재하는 의존 — Codex P1#4 응답)**: 이 ordering 의존은 peel 이 *신규 도입*하는 게 아니라 **현재도 존재**한다 — notification/user/product 는 이미 `flyway.enabled:false`+`ddl-auto:validate`(`product-service/.../application.yml:6-13`)라 **root 가 먼저 마이그레이션해야** validate-부팅한다. peel 은 "migrator = root" 를 "migrator = order-service" 로 옮길 뿐.
- **처분(택1, /work 착수 시 확정)**: (a) **order-service Flyway 런타임 enable**(전환기 마이그레이터, 기존 root 역할 승계, ② DB 분리 시 자연 정리) — 나머지 disabled 유지. (b) `flywayMigrateShared` 를 **배포 전 migration Job/init 단계로 승격**, 앱 전부 disabled(cold-start ordering 완전 제거). **권고: (a)** — 기존 패턴 최소변경. 단 **cold-start ordering 을 닫기 위해**: 빈 DB 에서 migrator(order-service) 가 먼저 마이그레이션 후 타 서비스가 validate-부팅하는 순서를 보장해야 한다. 런타임 순서/readiness 강제(initContainer·depends_on)는 **PR3(k8s/compose)에서 형식화**, 본 작업 검증은 "빈 DB→order-service 마이그레이션→payment/product validate-부팅 성공"(P13/P17)으로 확인.

### B-root — root 프로젝트 boot앱→aggregator 전환 (PR-b)
root `build.gradle`(339줄)은 `org.springframework.boot` 적용 + bootJar + web/jpa/redis/security 의존 + `PeekcartApplication` mainClass + jacoco(root sourceSets). root src 소멸 후:
- `bootJar` 비활성(`bootJar { enabled = false }`) 또는 spring-boot 플러그인 `apply false` 화. root 는 빌드/가드 aggregator 로만 존속(`subprojects` 공통 설정·guard 태스크 소유).
- root 전용 런타임 의존(web/jpa/redis/cache/actuator) 제거(서비스로 이미 복제). jacoco root sourceSets 참조 제거.
- 가드 갱신: `assertNoDuplicateGlobalFqcn` 의 `apps['root-app']`/`dependsOn ':classes'` 처분(root 가 java 비프로젝트화하면 제거; global 클래스 0 이라 무해하나 task 의존 깨짐 방지). `assertNoOrderPaymentSourceCoupling` 스캔 경로를 두 모듈(`order-service/src/main`·`payment-service/src/main`)로 재타깃(또는 `assertNoServiceProjectDeps`(서비스↔서비스 project 의존 금지)로 충분 시 retire 검토 — src FQCN 가드는 유지 권고).

### B2 — ADR 타깃 ≠ 현재 코드
"서비스 모듈 = 독립 bootJar"(ADR-0011)·"order/payment outbox_events·processed_events 소유"(ADR-0012 §36)는 user/product 에 **이미 존재**(검증). order/payment build.gradle 은 product-service 미러. SecurityConfig 분할 대상(root `SecurityConfig`)은 현존 확인됨 → "만든다" 명시 항목(P5/P12).

### 트레이드오프
- **이벤트 결합만 남음**: peel 후 order↔payment 는 `order.created`/`order.cancelled`/`payment.requested`/`payment.completed`/`payment.failed`/`stock.reservation.result` 토픽으로만 결합(eventual consistency, strangler-3/5 에서 수용·영속 marker 로 선도착 봉합).
- **DB 미분리**: 5개 서비스 모두 같은 DB 공유(② 이연). 본 작업은 모듈 경계 + root 해체까지. ②에서 FK drop/datasource 분리/baseline 일괄.
- **2 PR vs 1 PR**: 2 PR 선택 — PR-a 후 root 가 Payment 로 부팅되는 중간 그린 체크포인트 확보(저위험), 대신 cross-domain root 통합테스트(#5/#6/#8)를 PR-a(payment-observable 재작성)→PR-b(payment-service 이동) 2단 처리.

## 3. 작업 항목

### PR-a — Order peel (root 는 Payment+global 유지·계속 부팅)

- [ ] **P1.** `settings.gradle` `include 'order-service'` + `order-service/build.gradle`(product-service 미러: `:common`/`:common-auth`/`:common-observability` + validation·security·**data-redis(무조건, ADR-0014 blacklist fail-closed)**·kafka·shedlock(spring+jdbc)·mysql·flyway(disabled)·micrometer-prometheus·logstash·testFixtures(:common). **제외**: jjwt-sign/PasswordEncoder). jacoco exclude `OrderApplication*`.
- [ ] **P2.** Order 도메인 이동: `src/main/java/com/peekcart/order/**` → `order-service/src/main/...`(presentation/application/domain/infrastructure 전체 + `OrderEventConsumer` 4 listener·`OrderOutboxEventPublisher`·`OrderTimeoutScheduler`). `OrderApplication.java` 진입점(**`@EnableScheduling` 명시 — Codex P1#5**) + `application*.yml`(ADR-0007 준수, `app.outbox.polling.aggregate-types: ORDER`·`app.outbox.lock-name: orderOutboxPollingJob`). root `src/main/.../order` 소멸.
- [ ] **P3.** outbox/idempotency/ShedLock/Kafka 인프라 **복제** → order-service: `global.outbox` 실행세트 7종 + `global.idempotency` 5종 + `global.config.ShedLockConfig` + Kafka config(product `ProductKafkaConfig` 선례대로 도메인-로컬 listener/error-handler config). **NewTopic(producer-owns-topic, Codex 2차 P1#2)**: order-service 는 자기 발행 토픽 `order.created`(+`.dlq`)·`order.cancelled`(+`.dlq`) **4 `NewTopic` bean** 선언(전체 14 복제 아님). consumer 4종이 자기 `processed_events` 기록.
- [ ] **P4.** root poller allowlist 좁힘: root `application.yml` `app.outbox.polling.aggregate-types: ORDER,PAYMENT` → `PAYMENT`(order 가 order-service 로 자가발행). lock-name `rootOutboxPollingJob` 유지(PR-b 에서 제거). → root·order-service·product-service 3 poller disjoint(`PAYMENT`/`ORDER`/`PRODUCT`).
- [ ] **P5.** order-service 횡단 배선(B6): `OrderSecurityConfig`(`@EnableMethodSecurity`+common-auth `JwtFilter` SecurityFilterChain) + Kafka(listener/error-handler + **order.* NewTopic 4 bean, P3**)/**Redis(무조건, common-auth blacklist)**/Slack(조건부) 설정. `OrderApplicationTests` 컨텍스트 로드 스모크(blacklist adapter 가 RedisTemplate 으로 기동).
- [ ] **P6.** Order 테스트 이동(B1 #1·#3·#10 — **실제 16개**, `find src/test/java/com/peekcart/order -type f` 기준): `src/test/.../order/**`(16) + `support/fixture/OrderFixture.java` + `global/outbox/OutboxPollingServiceTest`(UNIT, 복제 poller 커버) → order-service test. `:common` testFixtures(`AbstractIntegrationTest`)로 shared 마이그레이션 적용. **누락 방지: 이동 전 `find` 목록을 기준으로 전수 대조.**
- [ ] **P7.** root 통합테스트 order 디커플(B1 #5·#6·#8): `OutboxKafkaIntegrationTest`·`IdempotencyIntegrationTest` 에서 Order 시드 제거→**payment-observable** 재작성(root 잔류, Payment 로 outbox/멱등 e2e 검증). **`ObservabilityMetricsIntegrationTest`: outbox probe row 의 aggregateType `"ORDER"`→`"PAYMENT"`(`:84-85`, Codex 2차 P1#1 — PR-a 에서 root poller 가 `PAYMENT` 로 좁혀져 `ORDER` probe 는 미발행→publish timer 미증가→실패). HTTP 부분은 이미 `/actuator/health` 라 무변.** `RootContextBootSmokeTest` 의 `/api/v1/orders` probe→`/actuator/health`(또는 payment 엔드포인트, PR-b 에서 삭제). 종료 후 `grep order src` 인바운드 0.
- [ ] **P8.** 가드/빌드(PR-a): `assertNoOrderPaymentSourceCoupling` order 스캔경로 `src/main/.../order`→`order-service/src/main/.../order`(payment 측은 root 잔류). `assertNoOrderProductSourceCoupling` order 경로 동일 갱신. `assertNoDuplicateGlobalFqcn`(동적, order-service 자동편입) + `assertNoServiceProjectDeps` 통과. root app Payment 로 부팅·컴파일.

### PR-b — Payment peel + root 해체

- [ ] **P9.** `settings.gradle` `include 'payment-service'` + `payment-service/build.gradle`(order-service 미러).
- [ ] **P10.** Payment 도메인 이동: `src/main/.../payment/**` → `payment-service/src/main/...` + `PaymentApplication.java`(**`@EnableScheduling` 명시 — outbox poller, Codex P1#5**) + `application*.yml`(`aggregate-types: PAYMENT`·`lock-name: paymentOutboxPollingJob` + **Toss `secret-key`/`webhook-secret` placeholder 이관 — Codex P1#2**). root `src/main/.../payment` 소멸.
- [ ] **P11.** outbox/idempotency/ShedLock/Kafka 복제 → payment-service(P3 미러). **NewTopic(producer-owns-topic)**: payment-service 는 `payment.completed`·`payment.failed`·`payment.requested`(각 +`.dlq`) **6 bean** 선언. **추가(Codex 2차 P1#2): product-service 에 자기 토픽 `product.updated`·`stock.reservation.result`(각 +`.dlq`) 4 bean 신설** — 현재 listener-only(root 무임승차)라 root 소멸 시 생성자 부재. 이후 root `global.*` 전부(outbox·idempotency·config 3) + `PeekcartApplication.java` **삭제** → root `src/main` 소멸.
- [ ] **P12.** payment-service 횡단 배선(B6): `PaymentSecurityConfig` + Kafka(listener-only)/**Redis(무조건, common-auth blacklist)**/Slack(조건부). `PaymentApplicationTests` 부팅 스모크(Toss 프로퍼티 stub 없이 — 누락 시 실패).
- [ ] **P13.** **B5 런타임 마이그레이션 소유권 재지정(Codex P1#4)**: root app 소멸로 공유 스키마 런타임 적용 주체 상실 → **order-service Flyway 런타임 enable**(전환기 공유 스키마 마이그레이터, 기존 root 역할 승계). 나머지 서비스 disabled+`ddl-auto:validate` 유지. root gradle `flywayMigrateShared` 는 CI/로컬용 존속. **cold-start 검증**: 빈 DB 에서 order-service 마이그레이션 후 payment/product 가 validate-부팅 성공(P17). 런타임 순서/readiness 형식화는 PR3(k8s/compose).
- [ ] **P14.** Payment 테스트 이동(B1 #2·#4 — **실제 9개**, `find src/test/java/com/peekcart/payment -type f` 기준) + cross-domain 통합테스트 이관(B1 #5·#6·#7·#8·#9): `src/test/.../payment/**`(9) + `PaymentFixture` + `OutboxKafkaIntegrationTest`·`IdempotencyIntegrationTest`·`ShedLockIntegrationTest`(잡 이름 `paymentOutboxPollingJob` 기준 재작성)·`ObservabilityMetricsIntegrationTest`·`DlqIntegrationTest` → payment-service test. **누락 방지: `find` 목록 전수 대조.**
- [ ] **P15.** `:common` 유닛 테스트 이관(B1 #11): `KafkaEventEnvelopeTest`·`MdcSnapshotTest`·`MdcRecordInterceptorTest`·`FixedSequenceBackOffTest`·`HarnessSmokeTtlTest` → `:common` test(SUT 모듈 동반). `RootContextBootSmokeTest` 삭제(B1 #12).
- [ ] **P16.** **root boot앱→aggregator 전환(B-root)**: root `build.gradle` `bootJar { enabled = false }`(또는 spring-boot `apply false`), root 런타임 의존(web/jpa/redis/cache/actuator) 제거, jacoco root sourceSets 참조 제거. 가드 갱신: `assertNoDuplicateGlobalFqcn` `apps['root-app']`·`dependsOn ':classes'` 처분(root global 클래스 0). `assertNoOrderPaymentSourceCoupling` 두 모듈 경로 재타깃(또는 retire 검토).
- [ ] **P17.** 가드/빌드(PR-b): `assertNoServiceProjectDeps`·`assertNoDuplicateGlobalFqcn`(8모듈) 통과. `grep -rn "com.peekcart" src` → src 디렉토리 소멸 확인. `./gradlew build test` 8모듈 그린. 각 서비스 독립 bootJar·부팅.
- [ ] **P18.** **Layer 1 문서 동기화(Codex 2차 P2#3)**: `docs/02-architecture.md` §5 Phase 4 주석의 **"토픽 6개"→"7개"** + `payment.requested` 토픽 반영(`:99`·`:124` 다이어그램 골격, ADR-0012 D4 refine 이 직접 지시). NewTopic producer 분산(order/payment/product)도 What 레이어에 반영. (peel 이 토픽 소유를 코드로 확정하므로 본 PR 에서 동기화.)

## 4. 영향 파일

**신규(PR-a)**: `order-service/build.gradle` · `order-service/.../OrderApplication.java`(`@EnableScheduling`) · `.../config/OrderSecurityConfig.java`(+Kafka(order.* NewTopic 4)/Cache) · `order-service/.../global/outbox/*`(복제 7)·`global/idempotency/*`(복제 5)·`global/config/ShedLockConfig.java`·Kafka config(복제) · `order-service/src/main/resources/application*.yml` · order-service test(이동분 16·OrderFixture·OutboxPollingServiceTest).
**신규(PR-b)**: `payment-service/**` 대칭 신규(`@EnableScheduling`·payment.* NewTopic 6·Toss yml) + payment-service test(payment 9·PaymentFixture·cross-domain 통합 5) + `:common` test(유닛 5 이관).
**이동**: `src/main/.../order/**`→order-service(PR-a) · `src/main/.../payment/**`→payment-service(PR-b) · root test 전부 rehome.
**수정(PR-a)**: `settings.gradle` · `build.gradle`(가드 스캔경로) · root `application.yml`(poller allowlist `PAYMENT`) · root 통합테스트 4(디커플/probe aggregateType `ORDER`→`PAYMENT`).
**수정(PR-b)**: `product-service` Kafka config(자기 토픽 `product.updated`·`stock.reservation.result` +`.dlq` NewTopic 4 bean 신설 — root 무임승차 해소) · `docs/02-architecture.md` §5(6→7 토픽, `payment.requested`).
**삭제(PR-b)**: root `src/main/.../global/**`·`PeekcartApplication.java`·`src/test` 잔여·`RootContextBootSmokeTest`. root `build.gradle` boot앱 설정.
**불변**: `common/**`(payload DTO·Flyway·:common SUT·testFixtures) · notification/user/product 모듈 · product-service poller(`PRODUCT` allowlist 무변).

## 5. 검증 방법
- **PR-a**: `grep -rln "com\.peekcart\.order" src --include="*.java" | grep -vE "/order/"` → 0. `./gradlew :order-service:bootJar` 산출. `./gradlew :order-service:test` 그린(이동 16·OutboxPollingServiceTest). **order-service 독립 발행 검증**: `OrderOutboxEventPublisher`→`outbox_events`(ORDER)→order poller 가 `order.created`/`order.cancelled` Kafka 발행, payment/product 행 **무시**(allowlist). consumer 4종 멱등. `OrderApplicationTests` 부팅 시 common-auth blacklist adapter 가 RedisTemplate 으로 기동. root app Payment 부팅(`/api/v1/payments`·actuator). 가드 4종(경로 갱신 포함) 그린.
- **PR-b**: `./gradlew :payment-service:bootJar`. `./gradlew :payment-service:test` 그린(payment 9·cross-domain 통합 5). `:common:test` 그린(유닛 5 이관). `PaymentApplicationTests` 가 Toss 프로퍼티 없이 실패(필수성 확인). **3 poller 공유 DB 소유권 검증(Codex P2#6 — 단일모듈 3-classpath 불가, ADR-0011 §D3 서비스↔서비스 의존 금지)**: (1) **모듈별** — 각 서비스 test 에서 자기 allowlist repository query(`findPendingEvents(["ORDER"]/["PAYMENT"]/["PRODUCT"])`)가 타 도메인 행을 반환 안 함 + ShedLock 이름 disjoint 단언. (2) **black-box** — order/payment/product **bootJar 3개를 같은 MySQL/Kafka 에 띄우고**(docker compose, PR3 와 공유) `ORDER`/`PAYMENT`/`PRODUCT` outbox row 주입 → 토픽별 **1회만** 발행(중복 0) 확인. **B5 cold-start**: 빈 DB → order-service 마이그레이션 → payment/product validate-부팅 성공. **NewTopic(producer-owns-topic)**: order 기동→order.*(+dlq) 생성, payment→payment.*(+dlq), product→product.updated/stock.reservation.result(+dlq) — 14 토픽 전체가 생성자 보유(공백 0), 각 서비스 컨텍스트 로드 시 AdminClient 가 자기 토픽 생성 확인. `find src -type f` → 0(root src 소멸). root `bootJar` 태스크 부재/disabled. `./gradlew build test` 8모듈 그린. 각 서비스 독립 부팅.

## 6. 완료 조건
- order-service·payment-service 독립 모듈 분리, 각 독립 bootJar·컨텍스트 부팅 + 자체 outbox poller(`ORDER`/`PAYMENT` allowlist)로 발행, product 포함 3 poller 공유 DB 소유권 분리(중복/경합 0).
- **root `src/` 소멸, root = 빌드/가드 aggregator**(boot앱 해체). 5개 서비스 풀 분해(ADR-0010 §5) 달성.
- B1 인바운드 12건 전부 처분(이동/디커플/분할/삭제), root src 잔존 0. global 테스트 rehome(:common test / 서비스).
- B5 공유 스키마 런타임 마이그레이션 소유권 재지정(order-service enable).
- 가드(`assertNoServiceProjectDeps`·`assertNoDuplicateGlobalFqcn` 8모듈·source-coupling 갱신) + 전체 build/test 그린.
- DB 물리분리(②)·Dockerfile/CI/k8s(PR3)는 후속(범위 외 명시).
