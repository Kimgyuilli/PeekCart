# task-impl-product-peel — Product 서비스 모듈 peel

> 사가 클러스터 strangler 완결(#56~#61, Order↔Product production 동기 결합 0) 이후, **Product 도메인을 독립 `product-service` 모듈로 peel** 한다.
> 선행 ADR: ADR-0010(5개 서비스 경계), ADR-0011(멀티모듈 구조), ADR-0014(전환기 인증 검증 공유 모듈). **새 ADR 불필요** — 경계/구조 결정은 ADR-0010/0011 보유(roadmap §58). GP-1 자동통과(boundary 신호 발화, ADR 보유).
> peel 패턴 선례: notification-service(#53) · user-service(#55).

## 1. 목표

Product 도메인(`com.peekcart.product.*`)을 root 모놀리스에서 떼어 독립 `product-service` Gradle 모듈로 만들고, 독립 bootJar 로 부팅 가능하게 한다. peel 후에도 root app(Order/Payment/Cart)과 기존 5개 서비스 전체 빌드/테스트가 그린이어야 한다.

**성공 기준**: `./gradlew build test` 전체 BUILD SUCCESSFUL + `product-service:bootJar` 산출 + product-service 독립 컨텍스트 부팅(@PreAuthorize·@Cacheable·Kafka consumer 배선 포함) + 가드 3종(`assertNoServiceProjectDeps`·`assertNoDuplicateGlobalFqcn`·`assertNoOrderProductSourceCoupling`) 통과.

## 2. 배경 / 제약

### 범위 결정 (사용자 게이트 2026-06-18)
- **모듈 peel 만. DB 는 공유 root DB 유지(Flyway 런타임 disabled), 크로스 도메인 FK drop 안 함.** user/notification 선례와 동일 — 실 DB-per-service(② FK drop·별 datasource·baseline)는 5개 서비스 모두 peel 된 뒤 일괄 ②로 미룬다.
- roadmap §78 의 "Product→Order+Payment peel(② DB 분리 동반)" 문구 중 **② DB 분리는 본 PR 에서 분리**한다(선례 일관·저위험). roadmap/TASKS 갱신은 `/done`에서.

### 현재 코드 (grep 검증 완료)
- src/main 비-product → product 참조: **0건**(strangler-4 `assertNoOrderProductSourceCoupling` 가드가 강제). Order 는 Product 를 이벤트(`product.updated`)+ 로컬 가격 캐시로만 참조.
- 공유 이벤트 payload `ProductUpdatedPayload` 는 이미 `common/src/main/.../global/outbox/dto/` 소유 → 이동 불필요.
- product 도메인 모델: `Category·Product·Inventory·ProductStatus·StockReservation·ReservationStatus`(예약 원장은 product-side saga 소유).
- product Kafka: `ProductOutboxEventPublisher`(`product.updated` 발행) + 소비자 3종 `StockReservationConsumer`(order.created→예약)·`StockConfirmConsumer`(payment.completed→확정)·`StockReleaseConsumer`(payment.failed/order.cancelled→복구). **모두 product 와 함께 이동.**
- order Kafka(잔류): `OrderEventConsumer`·`ProductPriceCacheConsumer`(product.updated→가격 캐시). product.updated 발행자(product-service)와 소비자(order)는 peel 후 Kafka 토픽으로만 결합.
- Flyway: 전부 `common/src/main/resources/db/migration/`(V1~V9, shared schema, B5 단일 소유). product 테이블(categories/products/inventories/stock_reservations + product version)은 shared schema 에 잔류, **root 가 계속 마이그레이션 적용**, product-service Flyway 런타임 disabled.
- **outbox/idempotency 실행 인프라 위치(Codex P1 #1 — `:common` 가정 오류 정정)**: `global.outbox` 의 *payload DTO* 9종만 `common/src/main/.../global/outbox/dto/` 에 있고, **실행 세트 7종**(`OutboxEvent`·`OutboxEventStatus`·`OutboxEventRepository(+Impl,+JpaRepository)`·`OutboxPollingService`·`OutboxPollingScheduler`)은 **root `src/main/.../global/outbox/` 전속**. `global.idempotency` 5종(`ProcessedEvent`·`IdempotencyChecker` 등)도 root 전속이며 **notification-service 가 자기 복제본을 둠**(`notification-service/.../global/idempotency/*`, 5파일). `ShedLockConfig` 는 root `global.config` 전속. Product 코드(`ProductOutboxEventPublisher`·`StockReservationService`·consumer 3종)는 이 root 전속 패키지를 import → **product-service 가 모듈로 나가면 컴파일 실패**. Product 는 **첫 *발행* 서비스 peel**(notification 은 consume-only 라 idempotency 만 복제)이므로 outbox 실행 세트까지 복제 필요. ADR-0011 §67 이 `global.outbox`·`ShedLockConfig` 를 "발행 서비스 전속" 으로 둠, ADR-0012 §36 이 Product 를 `outbox_events/processed_events` 소유 producer/consumer 로 확정 → 복제가 ADR 정합.
- **disposition(Codex P1 #1)**: outbox 실행 세트 + idempotency + `ShedLockConfig` 를 product-service 에 **복제**(notification idempotency 복제 선례 — guard 주석 `build.gradle:230` 이 "서로 다른 앱은 같은 classpath 에 공존 안 함 → 위반 아님" 으로 명시 허용). 공통 추상화로 `:common` 이관(중복 제거) 대안도 있으나 root 까지 건드리는 큰 리팩터라 peel PR 범위 밖 — 복제로 가고 후속 통합은 별도. ShedLock 의존성을 product-service build.gradle 에 추가(poller 사용).

### B1 — 역의존 스윕 (peel 대상의 인바운드 간선 처분)
src/main 인바운드 0건(가드 확인). **src/test 인바운드 5건** + 처분:

| # | 인바운드 파일 | product 사용 | 처분 |
|---|---|---|---|
| 1 | `support/fixture/ProductFixture.java` | Product/Inventory/Category/DTO 팩토리 | **이동** → product-service test. 소비자 7개 전부 product 패키지 테스트(grep 확인), 비-product 소비 0 → `:common` testFixtures 아님(B3) |
| 2 | `product/**` 테스트 7개 (`ProductTest`·`InventoryTest`·`ProductCommandServiceTest`·`ProductQueryServiceTest`·`InventoryServiceTest`·`AdminProductControllerTest`·`ProductControllerTest`) | 도메인 단위/슬라이스 | **이동** → product-service test (도메인과 동반) |
| 3 | `order/infrastructure/OrderExpiredPaymentRequestedQueryIntegrationTest` | `Product.create`로 상품 행 시드 후 주문 생성 | **디커플** → Product 도메인 import 제거, 주문/캐시 시드를 직접 row 삽입 또는 가격 캐시 seed 로 대체(root-observable) |
| 4 | `order/infrastructure/ProductPriceCacheSagaIntegrationTest` | cross-service: `ProductCommandService`로 product.updated 발행 → order 가 소비해 캐시 적용 | **분할** → (a) product-side(CommandService 발행) 검증은 product-service test 로 이관, (b) order-side(consumer 가 `ProductUpdatedPayload` 적용→캐시) 는 order test 잔류하되 **payload 를 직접 주입**(ProductCommandService 의존 제거) |
| 5 | `global/outbox/OutboxKafkaIntegrationTest`·`global/idempotency/IdempotencyIntegrationTest` | `Product.create`로 시드해 outbox/멱등 검증 | **디커플** → Product 도메인 제거, root-observable 효과(order/outbox/processed_events)로 재작성. (선례 메모리: service peel 시 root 통합테스트 디커플) |

> **검증 강제**: 처분 후 `grep -rln "com\.peekcart\.product" src --include=*.java | grep -v "/product/"` → **0건**이어야 한다(테스트 포함). product 디렉토리는 P4 에서 모듈로 이동하므로 최종적으로 root `src` 전체에 product 잔존 0.

### B6 — product-service 가 :common 스캔으로 떠안는 횡단 빈 / 비전이 스타터
product-service 진입점은 `com.peekcart.*` component-scan + `:common` 의존이므로 :common 의 `@Component`/`@Configuration`을 떠안는다. product 가 **실제로 쓰는** 횡단 의존:
- **Redis(`@Cacheable`)**: `ProductCacheService` 가 `@Cacheable(cacheNames="product","products")`. → product-service 가 CacheManager/Redis 연결 + 캐시 설정 소유 필요. `spring-boot-starter-data-redis` 명시 선언.
- **SlackPort**: `StockReservationService`(commit-실패 운영 알림). `:common` 의 `SlackNotificationClient` 는 `@ConditionalOnProperty(slack.webhook.url)`(PR2b 도입) → product-service 에 `slack.webhook.url` 설정 필요(미설정 시 commit-실패 알림 무력화 — 부팅은 안전).
- **Bean Validation(`@Valid`)**: `AdminProductController`/`CreateProductRequest`/`UpdateProductRequest`. Boot3 web starter 는 validation 미포함 → `spring-boot-starter-validation` 명시 선언(선례 B6: user-service @Valid 500).
- **Method Security(`@PreAuthorize`)**: `AdminProductController`(admin). root `SecurityConfig`(`@EnableMethodSecurity`)는 잔류 app 소유 → product-service 는 **자체 `ProductSecurityConfig`** 필요: `@EnableMethodSecurity` + common-auth `JwtFilter` 배선 SecurityFilterChain. **product 는 발급자 아님**(user-service 와 달리 PasswordEncoder/sign 없음).
- **Kafka**: outbox publisher + consumer 3종. `:common`/observability 의 Kafka 설정이 api 노출인지 확인 후 미전이면 product-service 명시 선언.

### B5 — 공유 스키마/마이그레이션 물리 위치
shared schema 는 `:common` 단일 소유 유지. product-service Flyway 런타임 disabled, root 가 V1~V9 계속 적용. product-service 테스트는 `:common` testFixtures(`AbstractIntegrationTest`/`IntegrationTestConfig`)로 shared 마이그레이션 적용(user-service 선례). **제약**: 양 app(root·product-service)이 동일 DB 에 disjoint 엔티티를 매핑 → JPA `ddl-auto` 는 validate/none 유지(Flyway 소유), create 금지.

### B2 — ADR 타깃 ≠ 현재 코드 확인
ADR-0011 이 지목한 "서비스 모듈 = 독립 bootJar" 패턴은 user-service/notification-service 에 **이미 존재**(build.gradle 검증). product-service 는 user-service build.gradle 을 템플릿으로 미러(단, 발급/sign 의존 제외, Slack/Redis/Kafka 추가).

### 트레이드오프
- **이벤트 결합만 남음**: peel 후 order↔product 는 `product.updated`(가격 캐시)·`order.created`/`payment.*`(예약 saga) 토픽으로만 결합. eventual consistency 창은 strangler-2/4 에서 이미 수용(ORD-007/ORD-009).
- **DB 미분리**: 물리적으로 같은 DB 공유 → "DB-per-service" 미달이나, 5개 동시 peel 후 ②에서 일괄 처리가 FK drop/baseline 을 한 번에 정리해 안전. 본 PR 은 모듈 경계만 확정.

## 3. 작업 항목

- [ ] **P1.** `settings.gradle` 에 `include 'product-service'` 추가 + `product-service/build.gradle` 작성(user-service 미러: `:common`/observability/common-auth + validation·security·data-redis·kafka·micrometer-prometheus·logstash·mysql·flyway(disabled)·testFixtures(:common) + **ShedLock 의존성**(outbox poller·P3). **제외**: jjwt-sign/PasswordEncoder(발급 아님)). jacoco exclude 에 `ProductApplication*`.
- [ ] **P2.** Product 도메인 코드 이동: `src/main/java/com/peekcart/product/**` → `product-service/src/main/java/com/peekcart/product/**`(presentation/application/domain/infrastructure 전체 + Kafka consumer 3종·outbox publisher·StockReservation 원장). `ProductApplication.java` 진입점 + `application.yml`(profile 연결정보는 ADR-0007 준수) 신설. root `src/main` 에서 product 디렉토리 소멸.
- [ ] **P3.** **outbox/idempotency 실행 인프라 복제(Codex P1 #1)**: root 전속 `global.outbox` 실행 세트 7종(`OutboxEvent`·`OutboxEventStatus`·`OutboxEventRepository`+`Impl`+`JpaRepository`·`OutboxPollingService`·`OutboxPollingScheduler`) + `global.idempotency` 5종 + `global.config.ShedLockConfig` 를 product-service 로 **복제**(notification idempotency 복제 선례). consumer 3종이 자기 `processed_events` 기록.
- [ ] **P4.** **공유 DB poller 소유권 분리(Codex 2차 P1 #1)**: 현재 `OutboxEventJpaRepository` 는 `status='PENDING'` 만 필터(`build.gradle` 무관, `OutboxEventJpaRepository.java:11`), ShedLock 이름이 단일 `outboxPollingJob`(`OutboxPollingScheduler.java:15`) → root·product 두 poller 가 같은 공유 `outbox_events` 를 보면 소유권 붕괴/중복 발행. **스키마 변경 없이**(`OutboxEvent` 에 `aggregate_type`/`event_type` 컬럼 기존재) poller 별 **eventType allowlist 분리**: root poller = `order.created`/`order.cancelled`/`payment.completed`/`payment.failed` 만 조회, product poller = `product.updated`/`stock.reservation.result` 만 조회. ShedLock 이름도 분리(`rootOutboxPollingJob`/`productOutboxPollingJob`). 멱등 `processed_events` 는 앱별 소비 이벤트가 disjoint 라 자연 분리.
- [ ] **P5.** product-service 횡단 배선(B6): (a) `ProductSecurityConfig`(`@EnableMethodSecurity` + common-auth `JwtFilter` SecurityFilterChain), (b) Redis CacheManager/캐시 설정(`@Cacheable` "product"/"products"), (c) Kafka consumer/producer 설정 확인·필요시 선언, (d) `slack.webhook.url` 설정으로 SlackPort 활성. 부팅 스모크: `ProductApplicationTests` 컨텍스트 로드.
- [ ] **P6.** Product 테스트 이동(Codex P1 #2 — 실제 **13개**): `src/test/java/com/peekcart/product/**` 13개 전체 + `support/fixture/ProductFixture.java` → `product-service/src/test/...`(B1 #1·#2, B3 단일 소비자 → testFixtures 아님). 누락 6개 명시: `StockReservationSagaIntegrationTest`·`StockReservationServiceTest`·`InventoryLockFacadeTest`·`ProductOutboxEventPublisherTest`·`ProductCacheIntegrationTest`·`InventoryConcurrencyTest`(나머지 7: `ProductQueryServiceTest`·`InventoryServiceTest`·`ProductCommandServiceTest`·`ProductTest`·`InventoryTest`·`AdminProductControllerTest`·`ProductControllerTest`). 이동 테스트가 `:common` testFixtures(AbstractIntegrationTest) 사용, `:product-service:test` 에서 Redis/Kafka/outbox 검증 실행.
- [ ] **P7.** root 테스트 디커플(B1 #3·#4·#5): `OrderExpiredPaymentRequestedQueryIntegrationTest`·`OutboxKafkaIntegrationTest`·`IdempotencyIntegrationTest` 에서 Product 도메인 제거→root-observable 재작성. `ProductPriceCacheSagaIntegrationTest` 분할(product-side→product-service / order-side→payload 직접 주입). 종료 후 `grep` 인바운드 0건 확인.
- [ ] **P8.** 가드/빌드 검증: (a) `assertNoDuplicateGlobalFqcn`(Codex P1 #3) 의 `dependsOn`·`apps` 맵에 `:product-service` 추가 — 가능하면 `subprojects.findAll { it.name.endsWith('-service') }`(이미 `assertNoServiceProjectDeps` `build.gradle:208` 에서 사용하는 패턴) 기반 동적 구성으로 order/payment 후속 peel 누락 방지. (b) `assertNoOrderProductSourceCoupling`(src/main order↔product source-scan) 이 product 모듈 이동 후에도 유효하도록 **스캔 경로 갱신**. (c) `assertNoServiceProjectDeps` 통과. (d) root app 이 Order/Payment 로 부팅·컴파일.

## 4. 영향 파일

**신규**: `product-service/build.gradle` · `product-service/src/main/java/com/peekcart/product/ProductApplication.java` · `.../infrastructure/config/ProductSecurityConfig.java`(+ Cache/Kafka 설정 필요시) · `product-service/src/main/.../global/outbox/*`(복제 7종)·`global/idempotency/*`(복제 5종)·`global/config/ShedLockConfig.java`(복제) · `product-service/src/main/resources/application*.yml` · `product-service/src/test/...`(이동분 13개 + ProductFixture).
**이동**: `src/main/java/com/peekcart/product/**`(전체) → `product-service/src/main/...` · `src/test/java/com/peekcart/product/**`(13개) + `src/test/.../support/fixture/ProductFixture.java` → `product-service/src/test/...`.
**수정**: `settings.gradle`(include) · `build.gradle`(루트 — `assertNoOrderProductSourceCoupling` 스캔 경로 + `assertNoDuplicateGlobalFqcn` apps 맵/dependsOn) · **root `global/outbox/OutboxEventJpaRepository.java`(PENDING 쿼리에 root eventType allowlist 추가)·`OutboxPollingScheduler.java`(ShedLock 이름 `rootOutboxPollingJob`) — P4 소유권 분리** · root `src/test/.../order/infrastructure/OrderExpiredPaymentRequestedQueryIntegrationTest.java`·`ProductPriceCacheSagaIntegrationTest.java`·`global/outbox/OutboxKafkaIntegrationTest.java`·`global/idempotency/IdempotencyIntegrationTest.java`(디커플).
**불변**: `common/**`(ProductUpdatedPayload·Flyway·testFixtures 그대로) · order/payment/user/notification 도메인 src/main · root `global.idempotency`(잔류 — Order/Payment 멱등용).

## 5. 검증 방법
- `grep -rln "com\.peekcart\.product" src --include=*.java | grep -v "/product/"` → **0건**(P6 후, 테스트 포함).
- `./gradlew assertNoServiceProjectDeps assertNoDuplicateGlobalFqcn assertNoOrderProductSourceCoupling` → BUILD SUCCESSFUL(가드가 `:product-service` 를 classpath 에 포함).
- `./gradlew :product-service:bootJar` → product-service.jar 산출.
- `./gradlew :product-service:test` 분리 실행 — 이동된 13개 테스트 그린. **product-service 독립 producer/consumer 검증(Codex P2 #4, 컨텍스트 로드 스모크만으로 불충분)**:
  - `ProductOutboxEventPublisher` 가 `outbox_events` row 저장 → product-service poller(`OutboxPollingScheduler`)가 `product.updated`/`stock.reservation.result` 를 Kafka 로 실제 발행.
  - **공유 DB 소유권 분리(P4)**: product poller 가 order/payment 이벤트 행을 **무시**, root poller 가 product 이벤트 행을 **무시**, 두 앱 동시 실행 시 product 이벤트 **1회만** 발행(중복 없음).
  - consumer 3종(`StockReservationConsumer`/`StockConfirmConsumer`/`StockReleaseConsumer`)이 자기 `processed_events` 기록(멱등).
  - `@Cacheable`("product"/"products") 가 Redis CacheManager 사용, `@PreAuthorize` admin API 동작, ShedLock/Slack/metrics 부팅.
- `./gradlew build test` 전체 그린(root + product 포함 7모듈).
- root app 부팅: Order/Payment 가 product 빈 없이 정상 기동(이벤트+캐시만 참조).

## 6. 완료 조건
- product-service 독립 모듈로 분리, 독립 bootJar·컨텍스트 부팅 + **자체 outbox poller 로 발행, eventType allowlist 로 root poller 와 소유권 분리**(공유 DB 동시실행 중복/경합 0).
- B1 인바운드 5건 전부 처분 완료(이동/디커플/분할), root src product 잔존 0. 테스트 13개 + ProductFixture 이관.
- 가드 3종(`assertNoDuplicateGlobalFqcn` 이 `:product-service` 포함) + 전체 build/test 그린.
- DB 분리는 본 PR 범위 외(② 이연) 명시 — Order/Payment peel 및 Dockerfile/CI/k8s(PR3)는 후속.
