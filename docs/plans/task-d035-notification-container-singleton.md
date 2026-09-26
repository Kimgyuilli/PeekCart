---
grade: M
---
# task-d035-notification-container-singleton

D-035(컨테이너 싱글톤 4모듈 확산) 의 두 번째 PR. 범위는 notification-service 하나다.
선례는 user([#144](https://github.com/Kimgyuilli/PeakCart/pull/144)) 와 order(D-032). user 와 달리
notification 은 **자율 writer 를 가진 첫 확산 대상**이라 D-035 ③(opt-in 전수 조사)과
④(Kafka 토픽 누적 관측)가 처음으로 실제 작업이 된다.

## 1. 명제

아래 중 하나라도 성립하면 미완이다.

- notification-service 테스트가 `@Container` / `@Testcontainers` 로 컨테이너를 직접 선언한다
- `scripts/integration-test-container-lint.sh` 가 notification-service 를 검사하지 않는다
- 테스트에서 스케줄러나 `@KafkaListener` 가 기본으로 돈다(`NotificationApplication` 의 무조건
  `@EnableScheduling` 이 남아 있거나, `build.gradle` 에 off 프로퍼티가 없다)
- 리스너 소비를 검증하는 테스트가 opt-in 없이 "아무 일도 안 일어났다" 를 관측하며 통과하거나,
  opt-in 한 context 가 `@DirtiesContext` 없이 캐시에 남는다
- 클래스 순서를 섞었을 때 순서에 따라 결과가 달라진다
- lint 검사 대상을 넓힐 때마다 `--self-test` 픽스처를 손으로 고쳐야 한다(user PR 이 남긴 이월)

### 코드 검증 (2026-09-26, 현재 코드 기준)

| 전제 | 확인 결과 | 범위 영향 |
|---|---|---|
| 공유 인프라 | `SharedContainers` · `AbstractIntegrationTest`(`cleanDatabase`/`cleanKafkaTopics`) · `SchedulingConfig` · `KafkaListenerStartupConfig` 모두 common 에 있음. notification 은 base 패키지 `com.peekcart` 라 common 의 `global.config` 를 스캔함 | 새로 만들 것 없음 |
| 전환 대상 | `@Container` 선언 7파일, 전부 MySQL+Redis+Kafka `@ServiceConnection`: `NotificationObservabilityMetrics` · `NotificationSecurity` · `NotificationConsumer` · `NotificationOutbox` · `DeadLetterMetrics` · `DeadLetterLedger` · `NotificationCleanupMatrix` (각 `*IntegrationTest`) | — |
| 스케줄러 | `@Scheduled` 5개(outbox poller/cleanup · DLQ purge/alert · DLQ reconciler · processed cleanup). **`NotificationApplication` 에 무조건 `@EnableScheduling`** | 진입점에서 제거 → `SchedulingConfig` 게이트로 이관 (order 선례) |
| 리스너 | `NotificationConsumer`(5토픽, `kafkaListenerContainerFactory`) · `DeadLetterConsumer`(5 dlq 토픽, `deadLetterKafkaListenerContainerFactory`). 두 factory 모두 `AbstractKafkaListenerContainerFactory` 라 common BPP 가 타입으로 잡음 | 프로덕션 코드 수정 불필요 |
| opt-in 전수 조사(③) | 브로커 왕복으로 리스너 소비를 관측하는 클래스 = **`NotificationConsumerIntegrationTest`**(`kafkaTemplate.send` → await) · **`DeadLetterLedgerIntegrationTest`**(dlq send → await). 타이머 발화를 검증하는 클래스 = **0** (`CleanupMatrix` 는 빈 존재만 단언하고 스케줄러 빈은 무조건 등록). 나머지 5클래스는 리스너·타이머 비의존(`DeadLetterMetrics` 는 recorder 직접 호출, `Outbox` 는 `pollingService` 직접 호출, invalid userId 케이스는 consumer 메서드 직접 호출) | 리스너 opt-in 2 · 스케줄러 opt-in 0 |
| 조용한 green 여부 | 두 opt-in 클래스 모두 첫 단언이 "생성됨"(양성) 이라 리스너가 꺼지면 **red** 가 된다. Ledger 음성 케이스는 대조군(OWNED) 을 같이 보냄 | 검증 방법에서 실제로 끄고 red 확인 |
| 타이머 무력화 관용구 | `NotificationOutboxIntegrationTest` 의 `app.outbox.polling.delay=1h` · `app.dead-letter.reconcile.delay=1h`. order 는 D-032 에서 같은 관용구를 전부 걷어냄(`grep` 0건) | scheduling off 로 대체되므로 제거 |
| 고정 토픽 공유 | 리스너가 읽는 고정 토픽에 **다른 클래스가 발행하는 곳은 없다**(Consumer 만 원천 토픽, Ledger 만 `payment.completed.dlq`). consumer group offset 은 커밋되므로 같은 group 의 재구독은 이미 읽은 레코드를 다시 안 읽는다 | 선제 `cleanKafkaTopics` 는 넣지 않는다. 셔플(P6) 에서 누수가 나오면 그때 넣는다 |
| lint 대상 | `MODULES = ["order-service", "user-service"]`. self-test 픽스처가 모듈 디렉터리를 하드코딩(user PR §이월) | P4 에서 구조로 해소 |

### 트레이드오프

- **opt-in 두 클래스는 context 를 캐시하지 못한다**(`@DirtiesContext(AFTER_CLASS)`). ADR-0029 D3 의
  비용 그대로다. 리스너를 켠 context 가 남아 공유 브로커를 계속 읽는 것보다 싸다.
- **선제 Kafka cleanup 을 생략한다.** 위 표의 근거(발행자·소비자가 같은 클래스 안에 있음) 로 지금은
  누수 경로가 없다. 새 클래스가 같은 토픽에 발행하기 시작하면 그 PR 이 넣는다 — 규약은
  `AbstractIntegrationTest` javadoc 에 이미 있다.

## 2. 작업 항목

- [x] P1. 7개 클래스에서 `@Testcontainers` · `@Container` · `@ServiceConnection` 필드와 관련 import 를
  제거하고 `@Import` 에 `SharedContainers.class` 를 추가한다(`IntegrationTestConfig` 와 병기).
  `@DynamicPropertySource` 와 기존 `@TestPropertySource` 항목은 유지한다(P3 대상 제외).
  **(구현 중 추가)** `NotificationOutboxIntegrationTest` 의 raw consumer 두 곳이 필드 `kafka.getBootstrapServers()`
  를 읽고 있어 컴파일이 깨졌다. `SharedContainers.KAFKA` 정적 필드로 바꿨다(AbstractIntegrationTest 규약 그대로).
- [x] P2. `NotificationApplication` 의 `@EnableScheduling` 과 import 를 제거하고 javadoc 을 "구동은
  `SchedulingConfig`(`app.scheduling.enabled`) 가 게이트" 로 고친다. `build.gradle` `test` 태스크에 user 와
  같은 세 가지(scheduling off · listener off · 클래스 순서 랜덤 + 시드 출력)를 넣는다.
- [x] P3. opt-in:
  - `NotificationConsumerIntegrationTest` · `DeadLetterLedgerIntegrationTest` 에
    `app.kafka.listener.enabled=true` + `@DirtiesContext(AFTER_CLASS)` (order `DeadLetterLedger` 와 같은 주석)
  - `NotificationOutboxIntegrationTest` 의 `polling.delay=1h` · `reconcile.delay=1h` 제거. 그 자리 주석은
    "배경 poller 는 테스트 기본 off(ADR-0029) — 발행 주체는 테스트" 로 바꾼다
- [x] P4. lint: `MODULES` 에 `notification-service` 추가. self-test 픽스처가 **MODULES 전체를 순회**해
  디렉터리를 만들고, 모듈마다 "그 모듈에 `@Testcontainers` 를 넣으면 ITC-002" 케이스를 돌게 바꾼다.
  이후 payment/product PR 은 `MODULES` 한 줄만 고치면 된다. user 전용 케이스는 이 루프로 흡수한다.
- [x] P5. D-035 ④ 토픽 누적 관측: 실행 중 Kafka 컨테이너에서 `kafka-topics.sh --list` 를 샘플링해 최종
  토픽 수를 기록하고, 로그에서 `UNKNOWN_TOPIC_OR_PARTITION` / 메타데이터 타임아웃 발생 여부를 센다.
  결과만 기록한다(대응은 문제가 관측될 때).
- [x] P6. 셔플 검증: 서로 다른 시드 3개로 `:notification-service:test` 전부 초록.
- [x] P7. 전후 실측: `:notification-service:test --rerun` 벽시계 시간 전·후.

## 3. 검증 방법

- **opt-in 이 실제로 필요한지(조용한 green 차단)**: P3 의 `app.kafka.listener.enabled=true` 를 각 클래스에서
  하나씩 빼고 돌려 **red** 인지 본다. 초록이면 그 테스트는 리스너를 관측하지 않는 것이므로 opt-in 대상
  판정이 틀린 것이다. 확인 후 원복.
- **writer off 가 걸렸는지**: 스케줄러 — opt-in 없는 클래스 context 에 `SchedulingConfig` 빈 부재를 한 번
  확인하고, build.gradle 프로퍼티를 뺀 음성 대조에서 존재함을 본다. 리스너 — opt-in 없는 클래스에서
  `KafkaListenerEndpointRegistry` 의 컨테이너가 전부 `isRunning()=false` 인지 확인(일회성, 테스트로 남기지 않음).
- **`@EnableScheduling` 제거가 프로덕션 기동을 깨지 않았는지**: 프로퍼티 없이(`app.scheduling.enabled`
  미지정) 뜨는 context 에서 `SchedulingConfig` 빈이 존재하는지 — 위 음성 대조가 이것을 겸한다.
- **lint**: notification-service 테스트에 `@Testcontainers` 를 되돌리면 ITC-002 exit 1.
  - 정정 이력: 초안은 "`MODULES` 에서 notification-service 를 빼면 self-test 의 notification 케이스가
    false-green 으로 실패" 를 검증으로 적었다. P4 가 픽스처를 `MODULES` 순회로 바꿨으므로 모듈을 빼면 그 케이스도
    함께 사라져 **이 검증은 성립하지 않는다.** user PR 의 하드코딩 케이스가 막던 "목록에서 조용히 빠짐" 은
    이제 self-test 가 아니라 리뷰가 막는다. 대상 확대 때마다 픽스처를 고치는 비용과 맞바꾼 것이다(§미해결).
- **셔플 검출력**: user PR 에서 이미 입증했으므로 반복하지 않는다. 시드 3개 통과만 본다.
- 최종: `./gradlew test` 전량 (work.md §8).

### 검증 결과 (2026-09-26)

| 항목 | 결과 |
|---|---|
| 전후 실측(P7) | `:notification-service:test --rerun` 342초 → **69초**(시드 11). 테스트 47개 전부 통과 |
| 셔플(P6) | 시드 11 · 42 · 1790500000000 전부 초록(69s · 62s · 74s) |
| 토픽 누적(P5) | 실행 중 샘플링 최종 **12개** — 원천 5 · `.dlq` 5 · `notification.probe` · `__consumer_offsets`. UUID 토픽 0. 로그의 `UNKNOWN_TOPIC_OR_PARTITION`·메타데이터 타임아웃 0건. 전파 지연 악화는 notification 에서 관측되지 않음 |
| opt-in 필요성 | listener 를 `false` 로 되돌리면 Consumer 5/6 실패(통과 1건은 consumer 메서드 직접 호출 케이스) · Ledger 3/3 실패 → 조용한 green 아님 |
| writer off | 임시 probe: `SchedulingConfig` 빈 부재 + 리스너 컨테이너 6개 전부 `isRunning()=false`. build.gradle 프로퍼티 제거 시 `["schedulingConfig"]` 로 실패(음성 대조 성립). probe 는 삭제 |
| lint | notification 에 `@Testcontainers` 복원 → ITC-002 exit 1, 원복 → exit 0. `--self-test` 9/9 |
- 최종: `./gradlew test` 전량 (work.md §8).

## 미해결 / 이월

- 선제 `cleanKafkaTopics` — 발행자·소비자가 클래스 경계를 넘는 테스트가 생기면 그 PR 에서.
- lint 모듈 목록 누락 가드 — self-test 가 `MODULES` 를 순회하므로 목록에서 빠진 모듈은 검출하지 않는다.
  서비스 디렉터리 자동 탐지로 막을 수 있으나 gateway(컨테이너 0) 처리 규칙이 필요해 D-035 마지막 PR(product) 에서 판단.
- `SharedContainers` lazy 기동 — user PR 과 같은 판단. notification 은 Kafka 를 쓰므로 해당 없음.
