---
grade: L
codex: off
---
# D-032 — 통합 테스트 컨테이너 모듈 싱글톤 전환 (order-service)

ADR-0028 의 결정을 구현한다. 이 계획서는 **order-service 한 모듈**을 범위로 한다.
나머지 4개 모듈 확산은 §후속 에 둔다.

## 1. 명제

다음 중 하나라도 참이면 미완이다.

- order-service 테스트 클래스가 여전히 `@Container` 를 직접 선언한다
- `:order-service:test` 가 컨테이너를 클래스마다 다시 띄운다
- 컨테이너 공유 후 클래스 실행 순서에 따라 결과가 달라진다
- 새 통합 테스트가 `@Container` 를 선언해도 CI 가 통과한다
- 위 성질이 order-service 에서만 성립하고 확산 경로가 정의되지 않았다

## 2. 배경 — 전제의 코드 검증

### 2-1. 표적의 크기 (ADR-0028 §Context 재확인)

`:order-service:test` 975초 중 JUnit XML 이 보고하는 클래스 시간은 131초이고, 나머지
**844초(87%)** 가 컨테이너 부팅·Flyway·context 기동이다. 컨테이너 보유 클래스 27개로
나누면 클래스당 약 31초다.

전 모듈 분포는 아래와 같다. 확산 규모의 근거다.

| 모듈 | `@Container` 선언 | `@SpringBootTest` 클래스 |
|---|---|---|
| order-service | **77** | **25** (+ 비-Spring 2) |
| product-service | 60 | 21 |
| payment-service | 39 | 14 |
| notification-service | 21 | 8 |
| user-service | 8 | 4 |
| gateway | 0 | 4 |
| 합계 | 205 | 77 |

### 2-2. 선언이 이미 수렴해 있다

order-service 의 `@Container` 77개를 원문으로 집계했다.

| 선언 | 횟수 |
|---|---|
| `new KafkaContainer("apache/kafka:3.8.1")` | 26 |
| `new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test")` | 26 (줄바꿈 변형 2 포함) |
| `new GenericContainer<>("redis:7").withExposedPorts(6379)` | 25 |
| `new MySQLContainer<>("mysql:8.0.46")` | **1** (`OrderCursorQueryPlanTest`) |

예외는 하나뿐이다. 이 결정은 새 구조 도입이 아니라 **수렴해 있는 것을 합치는** 일이다.

`@SpringBootTest` 25개의 프로퍼티 지문도 **17개가 완전히 동일**(`spring.flyway.enabled`,
`spring.flyway.locations` 뿐)하다. 컨테이너를 합치면 context 캐시가 따라온다.

### 2-3. 공유 상태의 실제 위험 (핵심 미지수였던 것)

| 공유 자원 | 확인 | 결과 |
|---|---|---|
| MySQL | `cleanDatabase()` 호출 여부 | **18개 호출**. 미호출 7개는 아래 2-4 |
| Kafka | 토픽 이름 생성 방식 | **위험 없음.** 토픽을 만드는 2개 클래스(`OriginalRecordReaderIntegrationTest`, `KafkaTopicConfigMechanismIntegrationTest`)가 **전부 `UUID.randomUUID()`** 를 쓴다. 충돌 불가 |
| Redis | 직접 사용 클래스 | `OrderApplicationTests` **1개**뿐 |

`KafkaTopicConfigMechanismIntegrationTest` 는 D-021·D-028 에서 두 번 flake 를 낸 이력이
있어 특히 확인했다. 토픽 이름이 전부 UUID 라 공유 브로커에서 이름 충돌은 생기지 않는다.
다만 그 두 부채가 **브로커 메타데이터 전파 지연**이었으므로, 브로커를 공유하면 토픽 수가
누적돼 `describe()` 지연이 악화될 수 있다. 이것은 이름 충돌과 다른 축이고 §검증 V-4 가 본다.


### 2-3b. 놓쳤던 두 축 (구현 중 발견, 2026-09-22)

§2-3 의 "Kafka 위험 없음" 판정은 **틀렸다.** 확인한 것이 테스트 헬퍼가 만드는 토픽
(`newTopic()`, UUID 이름)뿐이었고, **애플리케이션 자신의 고정 토픽**을 보지 않았다.

```
order.created · order.cancelled · order.compensation.requested
order.created.dlq · order.cancelled.dlq · ...
```

이름이 고정이라 브로커를 공유하면 앞 클래스가 발행한 레코드가 뒤 클래스의
`seekToBeginning` 에 그대로 읽힌다. `cleanDatabase()` 는 MySQL 만 비운다.

그런데 그것을 고친 뒤에도 실패가 남았고, **실행마다 실패 대상이 바뀌었다**(run1 3건 /
run2 1건 / run3 2건). 순서·타이밍 의존이라는 뜻이고, 세 번째 축이 있었다.

**진짜 원인: 테스트에서 백그라운드 스케줄러가 기본으로 켜져 있었다.**

`OrderApplication` 에 `@EnableScheduling` 이 무조건 붙어 있어 `OutboxPollingScheduler`·
`OrderTimeoutScheduler` 등이 테스트에서도 돌았다. 결과가 둘이다.

| | 내용 |
|---|---|
| 자율 writer | 아무도 요청하지 않았는데 DB 를 고치는 주체가 테스트와 경주한다. **컨테이너를 클래스마다 새로 띄우던 동안에는 각자 자기 DB 를 고쳐서 보이지 않았을 뿐**, 문제는 원래 있었다 |
| context 파편화 | 각 테스트가 `app.outbox.polling.delay=1h` 같은 프로퍼티로 개별 무력화해 왔다. 그 프로퍼티가 곧 context 캐시 키라, 타이머를 끄려는 행위가 context 를 쪼갠다. §2-2 의 "지문이 다른 9개" 가 이것이다 |

즉 **비결정성과 context 파편화가 같은 원인**에서 나왔다. 개별 무력화는 opt-out 이라
빠뜨린 테스트가 노출되고, 빠뜨리지 않아도 캐시를 깬다.

**처분: 기본값을 뒤집는다(P9).** 테스트에서 스케줄링을 기본 off 로 하고, 타이머 발화
자체를 검증하는 테스트만 명시적으로 켠다. 컨테이너 공유와 무관하게 그 자체로 옳다 —
통합 테스트가 배경 타이머와 경주하면서 `delay=1h` 로 달래는 것은 결정성을 버리는 설계다.

실측이 이 진단을 뒷받침한다. 스케줄링을 끄자 Kafka·DB 오염 실패가 **전부 사라지고**
타이머 발화를 검증하는 테스트 3건만 남았다(run4). 오염원이 하나였다는 증거다.

**꼬리 하나가 더 있었다.** opt-in 만으로는 부족했다 — context 캐시 때문에 그 클래스의
타이머가 자기 테스트가 끝난 뒤에도 계속 돌며 공유 DB 를 고쳤다(200ms 주기라 이후 전
클래스가 노출). `@DirtiesContext(AFTER_CLASS)` 로 클래스 종료 시 context 를 닫아 멈췄다.
컨테이너는 static 이라 영향받지 않는다. **자율 writer 를 켜는 테스트는 그 수명을 자기
클래스로 가둔다** 가 일반 규칙이고, 확산 단계에도 그대로 적용한다.


### 2-3c. 셔플(V-4)이 드러낸 결함 4건 (2026-09-23)

P7 의 클래스 순서 셔플을 켜자 **순차 실행에서는 한 번도 보이지 않던 결함 4건**이 나왔다.
run6 의 "420건 전건 통과" 는 순서가 운 좋았던 것이다 — ADR-0028 §Consequences 가 적어둔
"격리가 필요 없었다 와 이번 순서에서만 운이 좋았다 를 구별하지 못한다" 가 그대로 실증됐다.

| # | 결함 | 원인 | 처분 |
|---|---|---|---|
| 1 | `brokerRecordCount()` 가 앞 클래스 발행분을 함께 셈 | `cleanKafkaTopics` 의 `deleteRecords` 는 **low watermark 만** 올리고 end offset 은 그대로 둔다. `seekToBeginning` 기반 drain 은 고쳐지지만 **end offset 절대값을 세는 단언은 전혀 안 고쳐진다** | `end - beginning` 상대값으로 수정 |
| 2 | `DlqReplayEntrypoint` drain 이 15초씩 타임아웃 | 같은 원인. 잘린 로그에서 도달 불가능한 절대 end offset 을 목표로 폴링 | 동일 수정. **run2 의 2424초가 이것** |
| 3 | 컨테이너가 stop·재생성되어 이후 전 클래스가 `ConnectException` | `TestcontainersLifecycleBeanPostProcessor` 가 **`DestructionAwareBeanPostProcessor`** 다. 컨테이너를 `@Bean` 으로 노출하면 빈 파괴 시 stop 하고 다음 context 에서 start 해 **새 포트**를 만든다. 캐시된 다른 context 는 옛 포트를 쥔 채 남는다 | `@Bean(destroyMethod = "")` 로는 **막히지 않는다**. 컨테이너를 빈으로 노출하지 않고 `ConnectionDetails` 빈만 노출하도록 재작성 |
| 4 | `preconditionUsesRealOrderAdapter` 결정적 실패 | **`@KafkaListener` 가 자율 writer 다.** 테스트가 `stock.reservation.result` 에 발행하면 실제 `OrderEventConsumer` 가 먼저 소비해 `confirmReservation()` 을 호출하고, 뒤이은 replay precondition 이 "이미 예약이 확정됐다" 로 거부한다 | **미해결.** §미해결 과 D-036 참조 |

#### 진단 과정에서 틀린 가설 2건 (기록)

- `@Bean(destroyMethod = "")` 로 고쳤다고 판단했으나 **틀렸다.** Spring Boot 의
  `TestcontainersLifecycleBeanFactoryPostProcessor` 가 이미 inferred destroy method 를 빈
  문자열로 덮고 있어 그 경로가 아니었다. 바이트코드를 열어 확인했다.
- `@DynamicPropertySource` 로 접속 정보만 넘기는 방식도 시도했으나 **`@Import` 로 들어온
  클래스에서는 TestContext 프레임워크가 읽지 않는다**(datasource URL 미설정으로 부팅 실패).
  실측으로 확인하고 버렸다.

원인을 가른 것은 추론이 아니라 **관측**이었다. `docker ps` 를 5초마다 폴링해
`mysql=1 → 0 → 1`(redis·kafka 는 유지) 전이를 잡았고, 그 시각이 context 종료와 맞았다.

#### 자율 writer 의 범위를 잘못 잡았다

§2-3b 는 자율 writer 를 **스케줄러로만** 규정했다. 결함 4가 보여준 것은 **`@KafkaListener`
도 같은 성격**이라는 것이다. 브로커를 공유하면서 그 경합이 상시화됐다. 스케줄러와 대칭으로
리스너도 기본 off 로 두는 것이 방향이지만, 리스너 소비를 검증하는 테스트가 다수라 opt-in
대상이 넓고 전수 조사가 필요하다. **D-036 ADR 의 범위를 "테스트에서 자율 writer(스케줄러 +
Kafka 리스너)를 어떻게 다룰 것인가" 로 넓혀 거기서 결정한다.**

### 2-4. `cleanDatabase()` 미호출 클래스의 처분 (P1 완료, 2026-09-22)

본문을 전부 읽었다. **DB 의존이 하나도 없다.**

| 클래스 | 성격 | 처분 |
|---|---|---|
| `OrderCleanupMatrixIntegrationTest` | `ctx.getBeanNamesForType(...)` 2줄 | 유지 |
| `CommitAwareMetricsIntegrationTest` | `@BeforeEach` 에서 `SimpleMeterRegistry` 신규 생성 | 유지 |
| `OriginalRecordReaderIntegrationTest` | Kafka 전용, 테스트마다 UUID 토픽 생성 | 유지 |
| `OrderApplicationTests` | 빈 존재 단언(`InternalTokenVerifier`·`SecurityFilterChain`·`RedisTemplate` **빈 이름**). Redis 키를 쓰지 않는다 | 유지 |
| `OrderApiDocsContractTest` | MockMvc 로 OpenAPI JSON 을 읽어 파라미터 집합 단언 | 유지 |
| `OrderSecurityIntegrationTest` | `TestRestTemplate` 로 401/403/OK 상태코드만 단언 | 유지 |
| `SchedulerPoolStarvationTest` | `TaskScheduler` 풀 기아 검증. DB 무관 | 유지하되 **V-4 관찰 대상** |

`SchedulerPoolStarvationTest` 만 조건부다. DB 는 안 쓰지만 **2초 데드라인**으로 풀 점유를
단언한다. 공유 JVM 에서 앞 클래스가 남긴 스케줄 작업이 풀을 물고 있으면 타이밍이 달라질 수
있다. 공유 컨테이너가 아니라 **공유 JVM** 축의 위험이고, V-4 가 드러내야 하는 것이다.

`LedgerOwnerWiringTest` 는 이 목록에서 **빠졌다.** 최초 집계가 오탐이었다 — javadoc 의
`{@code @SpringBootTest}` 문자열에 grep 이 걸렸다. 실제로는 `ApplicationContextRunner`
기반이고 컨테이너도 Spring 컨텍스트도 쓰지 않는다. 이 전환의 대상이 아니다.

### 2-4b. 컨테이너 보유 클래스의 정확한 분류 (P1 에서 정정)

최초 집계는 `grep -rl "@Container\|@SpringBootTest"` 였고 javadoc 오탐을 포함했다.
어노테이션 기준으로 다시 세었다.

| 분류 | 수 | 전환 방식 |
|---|---|---|
| `@SpringBootTest` + `@Container` | 25 | `@Import(SharedContainers.class)` |
| `@Testcontainers` 만 (Spring 컨텍스트 0) | 1 — `KafkaTopicConfigMechanismIntegrationTest` | **`@Import` 불가.** 정적 필드 직접 참조 |
| `@DataJpaTest` + `mysql:8.0.46` | 1 — `OrderCursorQueryPlanTest` | **전환 제외** (버전 고정이 의도) |
| 합계 | **27** | |

`KafkaTopicConfigMechanismIntegrationTest` 는 `@Autowired`·`ApplicationContext`·
`@SpringBootTest` 가 **0건**이고 raw `Admin` 클라이언트만 쓴다. `@Bean` 주입 경로가 없으므로
`SharedContainers` 의 정적 필드를 직접 읽어야 한다. 이 때문에 `SharedContainers` 는
컨테이너를 **`@Bean` 과 정적 필드 양쪽으로 노출**해야 한다(§2-6 반영).

### 2-5. B3 — 공유 테스트 인프라 소유처

`PLAN-BLINDSPOTS` B3 은 "여러 모듈에서 쓰이면 `:common` testFixtures" 다. 인바운드를
스윕한 결과 이미 그렇다.

| 심볼 | 인바운드 |
|---|---|
| `AbstractIntegrationTest` | notification 4 · order 21 · payment 10 · product 17 (**4모듈 52곳**) |
| `IntegrationTestConfig` | notification 7 · order 23 · payment 6 · product 13 (**4모듈 49곳**) |

따라서 싱글톤 설정은 **서비스별 5벌이 아니라 `:common/src/testFixtures` 단일 소유**다.

### 2-6. B4 — 구체 메커니즘

기존 `IntegrationTestConfig` 에 컨테이너를 **넣지 않는다.** 그 클래스는 4모듈 49곳이
이미 import 하고 있어서, 거기에 컨테이너를 얹으면 order-service 하나만 검증하겠다는
단계 구분이 무너진다(전 모듈이 동시에 전환된다). 별도 클래스를 신설해 import 하는 쪽만
전환되게 한다.

```
common/src/testFixtures/java/com/peekcart/support/SharedContainers.java

@TestConfiguration(proxyBeanMethods = false)
public class SharedContainers {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static { /* 셋을 병렬 기동 */ }

    @Bean @ServiceConnection MySQLContainer<?> mysql() { return MYSQL; }
    @Bean @ServiceConnection(name = "redis") GenericContainer<?> redis() { return REDIS; }
    @Bean @ServiceConnection KafkaContainer kafka() { return KAFKA; }
}
```

정적 필드를 `public` 으로 두는 것은 §2-4b 때문이다. `KafkaTopicConfigMechanismIntegrationTest`
는 Spring 컨텍스트가 없어 `@Bean` 주입 경로가 닫혀 있고, `SharedContainers.KAFKA` 를 직접
읽어야 한다. 그 클래스가 정적 초기화를 트리거하도록 참조 지점을 명시한다.

`@Testcontainers`/`@Container` 를 쓰지 않는 것이 핵심이다. 그 extension 라이프사이클이
곧 per-class 수명이다. static 필드 + static initializer 로 **JVM 당 1회**로 만든다.
Gradle 은 모듈마다 test JVM 을 따로 띄우므로 결과적으로 모듈당 1세트다.

## 3. 작업 항목

- [x] **P1.** §2-4 의 미확인 클래스 본문을 읽고 표를 채운다. **완료** — DB 의존 0건이라
      `cleanDatabase()` 추가가 필요한 클래스는 없다. 부수로 집계 오탐 2건을 정정했다(§2-4b).

- [x] **P2.** `common/src/testFixtures/.../SharedContainers.java` 신설 (§2-6 형태).
      `common/build.gradle` 의 `testFixtures` 에 testcontainers·spring-boot-testcontainers
      의존이 이미 있는지 확인하고, 없으면 추가한다.

- [x] **P3.** order-service 25개 `@SpringBootTest` 클래스에서 `@Testcontainers`·`@Container` 선언과
      컨테이너 필드를 제거하고 `@Import(SharedContainers.class)` 로 교체한다.
      `OrderCursorQueryPlanTest` 는 **제외** — `mysql:8.0.46` 고정이 의도이므로 현행
      per-class 선언을 유지하고 그 사유를 클래스 주석에 남긴다.

- [x] **P3b.** `KafkaTopicConfigMechanismIntegrationTest` 를 `SharedContainers.KAFKA`
      정적 참조로 전환한다(§2-4b). Spring 컨텍스트가 없어 `@Import` 가 안 되는 유일한 경우다.

- [x] **P9.** 테스트 스케줄링 기본값 반전 (§2-3b). `common/.../SchedulingConfig` 로
      `@EnableScheduling` 을 `@ConditionalOnProperty(matchIfMissing = true)` 게이트하고
      `OrderApplication` 에서 어노테이션을 뗀다. `order-service/build.gradle` 의 `test` 태스크가
      `app.scheduling.enabled=false` 를 시스템 프로퍼티로 준다(파일 shadowing 위험 없음,
      `@TestPropertySource` inlined 가 우선). 타이머를 검증하는 2개 클래스는 명시 opt-in +
      `@DirtiesContext(AFTER_CLASS)`.

- [x] **P9b.** `AbstractIntegrationTest.cleanKafkaTopics()` 신설 (§2-3b 앞부분).
      `Admin.deleteRecords()` 로 로그를 end offset 까지 자른다. 토픽을 **삭제하지 않아**
      D-021·D-028 의 메타데이터 전파 문제를 피한다.

- [x] **P4.** `AbstractIntegrationTest` 의 클래스 주석에서 "컨테이너 선언은 각 자식
      클래스에서 per-class 수명으로 유지한다" 를 새 규약으로 고친다. 이 문장이 현행
      설계의 출처다.

- [x] **P5.** `scripts/integration-test-container-lint.sh` 신설.
      서비스 모듈 테스트에서 `@Container` 직접 선언을 금지한다. 화이트리스트는
      `OrderCursorQueryPlanTest` 하나이고 **파일 안에 사유와 함께** 둔다.
      `--self-test` 로 조작 입력 최소 4종(신규 선언 추가 · 화이트리스트 위조 ·
      `@Testcontainers` 부활 · 검사 대상 디렉터리 소멸)에서 실패하는지 고정한다.

- [x] **P6.** P5 를 `lint` 잡의 `Run CI policy lints` 에 `--self-test` 와 함께 추가한다.

- [x] **P7.** (구현 완료. 검증 V-4 는 미통과 — §V-4 실행 결과)
- [x] **P7-orig.** 클래스 실행 순서 셔플을 `:order-service:test` 에 도입한다.
      `junit.jupiter.testclass.order.default` 또는 Gradle `test { ... }` 의 난수 시드로
      **CI 에서 항상 섞이게** 한다. 시드를 실패 출력에 찍어 재현 가능하게 한다.

- [x] **P8.** `CLAUDE.md` §테스트 규칙에 싱글톤 규약을 명문화한다 (ADR-0028 §Decision 3).

## 검증 방법

각 행은 **실패를 주입한 뒤** 확인한다.

| # | 대상 | 실패 주입 | 기대 |
|---|---|---|---|
| V-1 | P5 lint | 테스트 클래스에 `@Container` 한 줄 추가 | exit != 0, 해당 파일을 지목 |
| V-2 | P5 lint | 화이트리스트에 임의 클래스 추가 | exit != 0 (사유 없는 항목 거부) |
| V-3 | P5 `--self-test` | 단독 실행 | 4종 전부 탐지 |
| V-4 | P7 셔플 | 시드를 바꿔 **10회 반복 실행** | 전 회차 green. 실패하면 그 시드로 재현해 원인 클래스를 특정하고 `cleanDatabase()` 를 추가한다 |
| V-5 | 컨테이너 수 | `:order-service:test` 로그에서 컨테이너 기동 로그 수 | mysql·redis·kafka 각 **1회** (+ `OrderCursorQueryPlanTest` 의 mysql 1회, 총 mysql 2) |
| V-6 | context 캐시 | 테스트 로그의 `Starting OrderApplication` 출현 수 | 25 에서 유의미하게 감소 (지문 동일 17개가 1개로) |
| V-7 | 효과 | `:order-service:test` 벽시계 | 975초에서 **250초 이하** |
| V-8 | 회귀 | order-service 테스트 전건 | 통과 수가 전환 전과 동일 |

### V-4 실행 결과 (2026-09-23, 로컬 5회)

계획서는 10회를 적었으나 **5회로 줄였다.** 1회가 4~5분이라 10회면 한 시간이고, 셔플이 이제
CI 의 모든 실행에 켜져 있어 이후 매 run 이 추가 표본이 된다. 이 축소를 조용히 하지 않는다.

| run | seed | 결과 | 소요 | tests/fail/err |
|---|---|---|---|---|
| 1 | 2039100001 | FAIL | 284초 | 420/1/0 |
| 2 | 1971500002 | FAIL | 295초 | 420/1/0 |
| 3 | 3124400003 | FAIL | 244초 | 420/1/0 |
| 4 | 3178300004 | FAIL | 250초 | 420/1/0 |
| 5 | 2896200005 | FAIL | **7018초** | 420/27/0 |

**V-4 미통과.** 실패 1건이 시드 4개에서 동일하게 재현된다 — 순서 의존이 아니라 **결정적**이다
(§2-3c 결함 4). 소요는 244~295초로 베이스라인(CI 975초) 대비 크게 줄었다.

run5 의 7018초/27건은 run1~4 의 28배로 **이상치**다. 연속 5회 약 2시간 실행 뒤라 로컬
Docker·머신 자원 고갈이 유력하나 간헐 결함 가능성을 배제하지 못했다. 로컬에서 2시간짜리
실행을 반복해 가르는 것은 비용 대비가 나쁘므로 **CI 에서 판별한다** — CI 는 매 run 이 새
러너라 자원 고갈 축이 없다.

### 실행 기록 (2026-09-22, 로컬)

| 실행 | 조치 | 실패 | 소요 |
|---|---|---|---|
| 1 | 싱글톤 전환만 | 3 | 6분17초 |
| 2 | `cleanKafkaTopics()` 2곳 | 1 | 6분46초 |
| 3 | Outbox 에도 적용 | 2 (대상 바뀜) | 6분34초 |
| 4 | **스케줄링 기본 off** | 3 (타이머 검증 테스트로 이동) | 6분22초 |
| 5 | 그 3건 opt-in | 1 | **4분 4초** |
| 6 | opt-in 클래스에 `@DirtiesContext` | **0** | 5분35초 |

420 테스트 전건 통과. 베이스라인 975초(CI) 대비 로컬 335초다. **로컬과 CI 는 러너가
다르므로 직접 비교가 아니다** — V-7 의 판정은 CI 실측으로 한다.

실행 4와 5 사이가 이 작업의 분기점이다. 스케줄링을 끄자 오염 실패가 전부 사라졌다.

V-4 가 이 계획의 중심이다. 순차 실행 1회 green 은 "격리가 필요 없었다" 와 "이번 순서에서만
운이 좋았다" 를 구별하지 못한다.

## 미해결

- **V-4 미통과 (blocking). 처분 확정 — 적용 대기 (2026-09-24).** `preconditionUsesRealOrderAdapter`
  가 시드 4개에서 결정적으로 실패한다. 원인은 `@KafkaListener` 가 자율 writer 라는 것
  (§2-3c 결함 4)이고, **처분을 ADR-0029 가 정했다** — 리스너를 container factory 의
  `autoStartup` 으로 게이트해 테스트 기본 off, 소비를 검증하는 테스트만 opt-in +
  `@DirtiesContext(AFTER_CLASS)`.

  ADR 작성 중 **진단이 한 단계 더 내려갔다.** 이 테스트에는 이미 `@BeforeEach` 에서 자기
  context 의 리스너를 `stop()` 하는 워크어라운드가 있었다(`89955c1`). 그런데도 실패하는 이유는
  `groupId` 가 하드코딩 상수라 **캐시된 다른 context 의 consumer 가 같은 그룹으로 파티션을
  넘겨받기** 때문이다 — 세 클래스 런에서 `order-svc-stock-result-group` 에 context 2개의
  consumer(`-36`, `-44`) 공존과 `generation 4` 리밸런스를 관측했다. per-context 수단은
  구조적으로 무효다. 남은 작업: **P10** (리스너 게이트 구현 + opt-in 대상 전수 조사) → V-4 재실행.
  **이 항목이 닫히기 전에는 머지하지 않는다.**
- **run5 이상치(7018초/27건)** 의 원인 미판정. CI 에서 재현되는지로 가른다.
- **확산 4모듈**(product 60 · payment 39 · notification 21 · user 8 선언)은 이 계획서
  범위 밖이다. order-service 에서 V-1~V-8 이 닫힌 뒤 착수한다. 별도 task 로 등록할지
  이 task 의 2차 PR 로 갈지는 그때 판단한다.
- **Kafka 토픽 누적** — 브로커를 공유하면 UUID 토픽이 한 모듈 실행 동안 계속 쌓인다.
  이름 충돌은 없으나 D-021·D-028 이 겪은 메타데이터 전파 지연이 악화될 수 있다.
  V-4 가 드러내지 못하면 확산 단계에서 다시 본다.
- **A6(컨테이너 불필요 테스트의 단위 강등)** 은 범위 밖이다. `OrderCleanupMatrixIntegrationTest`
  처럼 컨테이너 3종을 띄워 빈 이름 2줄만 단언하는 것들이 실재하나, 싱글톤 후에는 그
  비용이 사라지므로 시급성이 없다.

## 완료 조건

P1~P8(P3b 포함)이 전부 체크되고, V-1~V-8 이 통과하며, V-7 의 실측값이 계획서에 기록된 상태.

**현재 (2026-09-23): 미완.** 구현 항목은 전부 체크됐으나 **V-4 가 미통과**다. 결함 4의
처분이 D-036 에 달려 있어 이 계획서만으로 닫을 수 없다.
