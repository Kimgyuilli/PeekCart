---
grade: M
---
# task-d035-product-container-singleton

D-035(컨테이너 싱글톤 4모듈 확산) 의 네 번째이자 마지막 PR. 범위는 product-service 와 lint 스크립트다.
선례는 user([#144](https://github.com/Kimgyuilli/PeakCart/pull/144)) · notification([#145](https://github.com/Kimgyuilli/PeakCart/pull/145)) · payment([#146](https://github.com/Kimgyuilli/PeakCart/pull/146)) · order(D-032).
앞 모듈과 다른 점이 둘 있다. **Redis 를 Toxiproxy 뒤에 두는 클래스가 있고**, 캐시와 락을 쓰므로 **Redis 상태도
클래스 사이로 이어진다.** 앞 PR 들이 이 PR 로 넘긴 lint 이월 두 건(모듈 목록 누락 가드 · FQN 사각지대)도 여기서 닫는다.

## 1. 명제

아래 중 하나라도 성립하면 미완이다.

- product-service 테스트가 `@Container` / `@Testcontainers` 로 컨테이너를 직접 선언한다
- `scripts/integration-test-container-lint.sh` 가 product-service 를 검사하지 않는다. 또는 `settings.gradle` 에
  새 모듈이 추가돼도 목록에서 빠진 것을 잡지 못한다. 또는 FQN 표기(`@org.testcontainers...Testcontainers`)를 통과시킨다
- 테스트에서 스케줄러나 `@KafkaListener` 가 기본으로 돈다(`ProductApplication` 의 무조건 `@EnableScheduling` 이
  남아 있거나, `build.gradle` 에 off 프로퍼티가 없다)
- 리스너 소비나 타이머 발화를 검증하는 테스트가 opt-in 없이 "아무 일도 안 일어났다" 를 관측하며 통과하거나,
  opt-in 한 context 가 `@DirtiesContext` 없이 캐시에 남는다
- `ProductCacheFallbackIntegrationTest` 의 앱 트래픽이 Toxiproxy 를 거치지 않고 공유 Redis 로 직결된다
- 앞 클래스가 공유 DB·Redis·브로커에 남긴 상태 때문에 클래스 순서에 따라 결과가 달라진다

### 코드 검증 (2026-09-26, 현재 코드 기준)

| 전제 | 확인 결과 | 범위 영향 |
|---|---|---|
| 공유 인프라 | `SharedContainers` · `AbstractIntegrationTest`(`cleanDatabase`/`cleanKafkaTopics`/`cleanCaches`) · `SchedulingConfig` · `KafkaListenerStartupConfig` 는 common 에 있다. `ProductApplication` base 패키지가 `com.peekcart` 라 common 의 `global.config` 를 스캔한다 | 새로 만들 것 없음 |
| 전환 대상 | `@Container` 선언 **20파일**. 19개는 MySQL+Redis+Kafka `@ServiceConnection` 그대로다. `ProductCacheFallback` 만 MySQL·Kafka 는 `@Container`, Redis·Toxiproxy 는 static 블록에서 띄운다 | — |
| **Toxiproxy 클래스** | `ProductCacheFallback` 은 `@DynamicPropertySource` 로 `spring.data.redis.host/port` 를 프록시로 돌린다. 그런데 Boot 3.5.12 jar 를 열어 보면 `RedisAutoConfiguration#redisConnectionDetails`(`PropertiesRedisConnectionDetails`) 가 `@ConditionalOnMissingBean` 이다. **`SharedContainers` 를 import 하면 공유 Redis 의 `RedisConnectionDetails` 빈이 이기고 프로퍼티는 무시된다.** 앱이 프록시를 우회하므로 장애 주입이 전부 무효가 된다. `LettuceConnectionConfiguration` 과 common `RedissonConfig` 는 둘 다 `RedisConnectionDetails` 하나를 생성자로 받는다 | P2: 프로퍼티 대신 `@Primary RedisConnectionDetails` 빈으로 프록시를 가리킨다. `proxyIsActuallyOnThePath()` 가 이미 음성 대조 역할을 한다 |
| 스케줄러 | `@Scheduled` 7개(lease sweep · outbox poll/cleanup · DLQ reconcile/purge/alert · processed cleanup), 전부 ShedLock. **`ProductApplication` 에 무조건 `@EnableScheduling`**. 스케줄러 빈에 `@ConditionalOn*` 없음 | 진입점에서 제거하고 `SchedulingConfig` 게이트로 넘긴다 |
| 리스너 | `StockReservation`(order.created) · `StockConfirm`(payment.completed) · `StockRelease`(order.cancelled · payment.failed) · `RefundResult`(payment.refunded) → `kafkaListenerContainerFactory`(`ProductKafkaConfig`). `DeadLetterConsumer` · `DeadLetterQuarantineConsumer` → `deadLetterKafkaListenerContainerFactory`. 둘 다 `ConcurrentKafkaListenerContainerFactory` 라 common BPP 가 타입으로 잡는다. 테스트 소유 리스너 · raw consumer 는 없다 | 프로덕션 코드 수정 불필요 |
| opt-in 전수 조사: 리스너 | 브로커 왕복으로 소비를 관측하는 클래스 **2개**. `StockCompensationRefund`(L269 `payment.refunded` send → await, 나머지 케이스는 `refundResultConsumer` 직접 호출) · `DeadLetterLedger`(dlq send → 원장 await). `StockReservationSaga` · `ProductSagaMetrics` 는 consumer 메서드를 직접 부른다 | 리스너 opt-in 2 |
| opt-in 전수 조사: 스케줄러 | 타이머 발화를 기다리는 클래스 **1개**. `StockSchedulerWiring` 은 delay 200ms 로 sweeper 가 직접 호출 없이 회수하는 것을 await 한다. `OutboxEventCleanup` · `ProcessedEventCleanup` 은 `scheduler.cleanup()` 을, `ProductOutboxOwnership` 은 `outboxPollingService.pollAndPublish()` 를 직접 부른다 | 스케줄러 opt-in 1 |
| 조용한 green 여부 | 세 클래스 모두 양성 단언("종결 기록됨" · "원장 생성됨" · "RELEASED 로 회수됨")이다. 끄면 red 가 되어야 한다 | 검증 방법에서 실제로 끄고 red 확인 |
| 타이머 무력화 관용구 | `StockCompensationRefund` 의 `@MockitoBean OutboxPollingScheduler`. 5초마다 도는 poller 가 spy 와 경합하는 것을 막는 장치다. 이 클래스는 리스너만 켜므로 scheduling 은 off 로 남는다 | 제거한다(payment 의 `*-interval-ms` 제거와 같은 판단) |
| **공유 `shedlock` 행** | `cleanDatabase` 는 `shedlock` 을 지우지 않는다. 락 메서드를 직접 부르는 곳은 `OutboxEventCleanup` 과 `ProcessedEventCleanup` 의 `cleanup()`(lockAtLeastFor 1m)이다. 각각 한 클래스에서 한 번만 부른다. sweeper 락(30s)은 `StockSchedulerWiring` context 만 잡는다(그 context 는 0s 로 덮는다). `pollAndPublish` 호출은 서비스 경로라 락이 없다 | 조치 없음. 두 번째 호출자가 생기면 그때 본다 |
| **공유 Redis** | 캐시를 검증하는 `ProductCache` · `ProductDetailQueryCost` · `ProductStockCacheStaleness` 는 이미 `@BeforeEach` 에서 `cleanCaches` 를 부른다. 락 키는 클래스별 고유 문자열이다. `cleanDatabase` 는 `DELETE` 라 AUTO_INCREMENT 가 이어지고, 그래서 다른 클래스가 남긴 캐시 키와 id 가 겹치지 않는다 | 조치 없음. 셔플(P7)로 확인 |
| 공유 DB: 잔여 행 | `StockReservationSweeperExplain` 은 `cleanDatabase` 를 부르지 않고 EXPLAIN 을 단언한다. 종전에는 늘 빈 테이블에서 돌았다. 다른 클래스는 `@BeforeEach` 로 시작 시에만 비우므로 마지막 테스트의 행이 남는다 | P4: 빈 테이블 전제를 `@BeforeEach cleanDatabase()` 로 되살린다 |
| 누수 아님(확인) | 브로커에 쓰는 곳은 `StockCompensationRefund`(payment.refunded) · `DeadLetterLedger`(*.dlq) · `ProductOutboxOwnership`(probe 토픽, 구독자 없음)이다. 리스너를 켜는 context 는 둘뿐이다. 두 context 는 서로의 토픽을 같은 상수 group 으로 구독하므로, 뒤 context 는 앞 context 가 커밋한 offset 뒤부터 읽는다 | 조치 없음. 셔플로 확인 |
| lint 대상 | `MODULES=(order-service user-service notification-service payment-service)`. 줄 선두의 짧은 이름만 매칭한다(`^\s*@Container\b`). `settings.gradle` include 는 10개다(정정: 초안은 9개로 셌다). service 외 모듈(common · observability · common-auth · gateway · internal-token-contract)의 `src/test` 에는 현재 직접 선언이 없다. `SharedContainers` 는 `common/src/testFixtures` 에 있어 `src/test` 스캔 대상이 아니다 | P5 |

### 트레이드오프

- **opt-in 3클래스는 context 를 캐시하지 못한다**(`@DirtiesContext(AFTER_CLASS)`). 20개 중 3개라 이득이 가장 크다.
  `ProductCacheFallback` 은 `@MockitoSpyBean` 과 고유 설정 때문에 어차피 자기 context 를 갖는다. 그래도 MySQL·Kafka
  기동과 Flyway 는 공유로 아낀다.
- **P2 를 ALLOWED 예외로 처리하지 않는다.** 예외로 두면 사유가 "Redis 를 프록시 뒤에 둬야 한다" 인데, 그것은 MySQL·Kafka
  를 따로 띄울 이유가 되지 못한다. `@Primary` 빈 하나로 Redis 만 다르게 할 수 있다. 부작용은 공유 Redis 컨테이너도 이 context
  에서 한 번 뜬다는 점이다. 싱글톤이라 비용이 없다.
- **P5 목록 가드는 자동 탐색이 아니라 대조로 한다.** `MODULES` 를 `settings.gradle` 에서 파싱해 만들면 가드가 필요 없어진다.
  대신 ITC-001(대상 부재 = 위반)의 전제가 사라지고, self-test 픽스처도 settings 파일을 흉내 내야 한다. 명시 목록을 유지하되,
  "include 됐고 `src/test` 가 있는데 목록에 없는 모듈" 을 ITC-005 로 세는 편이 기존 구조를 덜 흔든다. 목록은 test 소스가 있는 8개
  모듈 전부로 넓힌다(직접 선언 0건이라 오늘 결과는 같다).

## 2. 작업 항목

- [x] P1. `ProductCacheFallback` 을 뺀 19개 클래스에서 `@Testcontainers` · `@Container` · `@ServiceConnection` 필드와 관련 import 를
  제거하고, `@Import` 에 `SharedContainers.class` 를 추가한다(기존 `@Import` 항목과 병기, 없으면 새로 단다).
- [x] P2. `ProductCacheFallback`: MySQL·Kafka `@Container` 와 `@Testcontainers` 를 제거하고 `SharedContainers` 를 import 한다. Redis·Toxiproxy
  static 기동은 유지한다. `@DynamicPropertySource redisThroughProxy` 를 Toxiproxy host/mapped port 를 돌려주는 `@Primary RedisConnectionDetails`
  빈으로 바꾼다(nested `@TestConfiguration`). javadoc 의 "`@ServiceConnection` 을 붙이지 않는다" 단락은 이유를 "공유 Redis 빈을
  `@Primary` 로 덮는다" 로 고친다.
- [x] P3. writer off + opt-in:
  - `ProductApplication` 의 `@EnableScheduling` 과 import 를 제거하고, javadoc 을 "구동은 `SchedulingConfig`(`app.scheduling.enabled`)
    가 게이트한다" 로 고친다. `build.gradle` `test` 태스크에 payment 와 같은 세 가지(scheduling off · listener off · 클래스 순서
    랜덤 + 시드 출력)를 넣는다.
  - 리스너: `StockCompensationRefund` · `DeadLetterLedger` 에 `app.kafka.listener.enabled=true` + `@DirtiesContext(AFTER_CLASS)`
  - 스케줄러: `StockSchedulerWiring` 에 `app.scheduling.enabled=true` + `@DirtiesContext(AFTER_CLASS)`
  - `StockCompensationRefund` 의 `@MockitoBean OutboxPollingScheduler` 와 javadoc 을 제거한다. 그 자리에 "배경 스케줄러는 테스트 기본
    off(ADR-0029)" 한 줄을 남긴다
- [x] P4. `StockReservationSweeperExplain` 에 `@BeforeEach cleanDatabase()` 를 넣는다. 주석은 "EXPLAIN 은 빈 테이블 전제 — 공유 DB 의 잔여 행이
  옵티마이저 판단을 바꿀 수 있다" 로 단다.
- [x] P5. lint:
  - `MODULES` 를 `settings.gradle` 의 include 중 `src/test` 가 있는 8개 전부로 넓힌다
    (정정: 초안은 "9개 전부" 였으나 `peekcart-common-observability` · `internal-token-contract` 는 `src/test` 가 없다.
    ITC-001 이 목록의 모듈마다 `src/test` 존재를 요구하므로 넣을 수 없고, ITC-005 도 test 소스 없는 모듈은 세지 않는다)
  - ITC-005: `settings.gradle` 에 include 됐고 `src/test` 가 있는데 `MODULES` 에 없으면 위반이다
  - 매칭을 `^\s*@(?:org\.testcontainers\.[\w.]+\.)?(Container|Testcontainers)\b` 로 넓힌다
  - self-test 에 두 케이스를 추가한다: 목록 누락 모듈(ITC-005) · FQN 선언(ITC-002). 픽스처에 `settings.gradle` 을 만든다
- [x] P6. D-035 ④ 토픽 누적 관측. 방식은 payment 와 같다(컨테이너 안 `localhost:9093` · `timeout` 필수). 결과만 적는다.
- [x] P7. 셔플 검증: 서로 다른 시드 3개로 `:product-service:test` 를 돌려 전부 초록인지 본다.
- [x] P8. 전후 실측: `:product-service:test --rerun` 의 벽시계 시간을 전후로 잰다.

## 3. 검증 방법

- **opt-in 이 실제로 필요한지(조용한 green 차단)**: P3 의 opt-in 을 `false` 로 바꿔 돌리고 **red** 인지 본다. 대상은
  `StockCompensationRefund` 의 broker 왕복 케이스 · `DeadLetterLedger` · `StockSchedulerWiring` 이다. 초록이면 그 테스트는
  리스너나 타이머를 관측하지 않는다는 뜻이고, 판정이 틀린 것이다. 확인한 뒤 원복한다.
- **P2 프록시 경유**: P2 적용 후 `ProductCacheFallback` 전체가 초록인지 본다. 이어서 `@Primary` 빈만 빼고 돌린다.
  `proxyIsActuallyOnThePath` 와 장애 주입 케이스가 **red** 여야 한다(공유 Redis 직결). 이 음성 대조가 초록이면 빈이 먹지 않거나
  테스트가 프록시를 관측하지 않는 것이다. 원복한다.
- **P4 잔여 행이 실재 위험인지**: P4 적용 **전에** `stock_reservations` 에 RESERVED 행을 몇 개 넣은 상태를 흉내 내 EXPLAIN 이
  바뀌는지 본다(임시 seed). 바뀌지 않아도 P4 는 유지한다. 단 그 사실은 정정 이력에 적는다 — 이 경우 P4 의 근거는 "관측된 실패" 가
  아니라 "전제 복원" 이다.
- **writer off 가 걸렸는지**: opt-in 없는 클래스 context 에서 `SchedulingConfig` 빈이 없는지, `KafkaListenerEndpointRegistry` 컨테이너가
  전부 `isRunning()=false` 인지 임시 probe 로 확인한다. build.gradle 프로퍼티를 뺀 음성 대조에서는 빈이 존재해야 한다. probe 는 남기지 않는다.
- **lint**: product 테스트 하나에 `@Testcontainers` 를 되돌리면 ITC-002, FQN 으로 되돌려도 ITC-002, `MODULES` 에서 한 모듈을 빼면
  ITC-005 로 exit 1 이 나야 한다. `--self-test` 통과. `scripts/*-lint.sh` 전부 0.
- 최종: `./gradlew test` 전량 (work.md §8).

### 검증 결과 (2026-09-27)

| 항목 | 결과 |
|---|---|
| 전후 실측(P8) | `:product-service:test --rerun` 1098초(약 18분, main 워크트리) → **156초**(시드 11). 테스트 196개 전부 통과 |
| 셔플(P7) | 시드 11 · 42 · 1790500000000 전부 초록(156s · 246s · 162s). 42 는 5초 간격 토픽 샘플러가 함께 돌았다. 전량 실행의 시드 1790437307806 도 초록 |
| 토픽 누적(P6) | 실행 중 샘플링(19회) 최종 **19개**: 원천 8 · `.dlq` 8 · `order.test.probe` · `product.test.probe` · `__consumer_offsets`. UUID 토픽 0. 실행 4회 로그의 `UNKNOWN_TOPIC_OR_PARTITION` · 메타데이터 타임아웃 0건. D-035 ④ 는 4모듈 전부 악화 없음으로 닫는다 |
| opt-in 필요성 | 3클래스의 opt-in 을 `false` 로 → DeadLetterLedger 4/4 · StockSchedulerWiring 2/2 · StockCompensationRefund 1/14 실패(실패 1건이 broker 왕복 케이스, 나머지는 consumer 직접 호출). 조용한 green 없음 |
| P2 프록시 경유 | 적용 후 7/7 초록. `RedisThroughProxy` 를 빼면(공유 Redis 직결) 7개 중 5개 red, V0(프록시 경유 확인) 포함 |
| P4 잔여 행 | P4 없이 만료 RESERVED 를 섞어 seed → 3행은 인덱스를 탔고 **30행(RESERVED 20)에서 `access_type: ALL` 로 red**. 행 수에 따라 옵티마이저 판단이 뒤집히므로 P4 는 관측된 위험을 막는다 |
| writer off | 임시 probe: `SchedulingConfig` 빈 부재 + 리스너 컨테이너 7개 전부 `isRunning()=false`. build.gradle 프로퍼티를 빼면 `[schedulingConfig]` · running 7 로 실패(음성 대조 성립). probe 삭제 |
| lint | 저장소에 주입 — `@Testcontainers` 복원 ITC-002 · FQN 복원 ITC-002 · `MODULES` 에서 product 제거 ITC-005, 전부 exit 1. 원복 exit 0. `--self-test` 16/16. `scripts/*-lint.sh` 23개 전부 0 |
| 전량 | `./gradlew test` BUILD SUCCESSFUL (order 420 · payment 239 · product 196 · gateway 120 · common 109 · user 65 · common-auth 57 · notification 47, 실패 0) |

토픽 샘플링의 `timeout` 은 macOS 에 기본으로 없다. 없으면 샘플러가 명령을 못 찾아 조용히 빈 결과를 낸다(첫 실행에서 겪음).
`perl -e 'alarm 10; exec @ARGV' docker exec ...` 로 대신했다.

## 미해결 / 이월

- `shedlock` 을 `cleanDatabase` 에서 제외하는 규약은 그대로 둔다. 같은 락 메서드를 두 클래스가 직접 부르게 되면 뒤 클래스가 lockAtLeastFor
  동안 조용히 skip 된다. 오늘은 호출자가 각 한 곳이라 성립하지 않는다. 생기면 그 PR 에서 본다.
- Redis keyspace 전체 정리(`flushDb`) 헬퍼는 만들지 않는다. 캐시 검증 클래스는 이미 `cleanCaches` 를 쓴다. 셔플에서 Redis 누수가 드러나면 이 판단을 뒤집는다.
