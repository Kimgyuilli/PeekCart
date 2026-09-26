---
grade: M
---
# task-d035-payment-container-singleton

D-035(컨테이너 싱글톤 4모듈 확산) 의 세 번째 PR. 범위는 payment-service 하나다.
선례는 user([#144](https://github.com/Kimgyuilli/PeakCart/pull/144)) · notification([#145](https://github.com/Kimgyuilli/PeakCart/pull/145)) · order(D-032).
notification 과 달리 **테스트 클래스끼리 고정 토픽을 주고받는 첫 모듈**이다. 브로커를 공유하면
앞 클래스가 남긴 레코드를 뒤 클래스의 리스너가 읽는다. 그래서 notification 에서 "선제 cleanup 불필요"
로 넘겼던 판단을 여기서는 실제 누수 경로를 두고 다시 한다.

## 1. 명제

아래 중 하나라도 성립하면 미완이다.

- payment-service 테스트가 `@Container` / `@Testcontainers` 로 컨테이너를 직접 선언한다
- `scripts/integration-test-container-lint.sh` 가 payment-service 를 검사하지 않는다
- 테스트에서 스케줄러나 `@KafkaListener` 가 기본으로 돈다(`PaymentApplication` 의 무조건
  `@EnableScheduling` 이 남아 있거나, `build.gradle` 에 off 프로퍼티가 없다)
- 리스너 소비나 타이머 발화를 검증하는 테스트가 opt-in 없이 "아무 일도 안 일어났다" 를 관측하며
  통과하거나, opt-in 한 context 가 `@DirtiesContext` 없이 캐시에 남는다
- 앞 클래스가 공유 브로커에 남긴 레코드 때문에 클래스 순서에 따라 결과가 달라진다

### 코드 검증 (2026-09-26, 현재 코드 기준)

| 전제 | 확인 결과 | 범위 영향 |
|---|---|---|
| 공유 인프라 | `SharedContainers` · `AbstractIntegrationTest`(`cleanDatabase`/`cleanKafkaTopics`) · `SchedulingConfig` · `KafkaListenerStartupConfig` 는 common 에 있다. `PaymentApplication` base 패키지가 `com.peekcart` 라 common 의 `global.config` 를 스캔한다 | 새로 만들 것 없음 |
| 전환 대상 | `@Container` 선언 13파일, 전부 MySQL+Redis+Kafka `@ServiceConnection`: `PaymentApplicationTests` · `RefundLedger` · `CompensationRequestConsumer` · `ApprovalLedger` · `OutboxKafka` · `ShedLock` · `DeadLetterLedger` · `PaymentSecurity` · `Dlq` · `ObservabilityMetrics` · `DeadLetterMetrics` · `PaymentCleanupMatrix` · `Idempotency` | — |
| 스케줄러 | `@Scheduled` 9개(approval reconcile · refund dispatch/reconcile · outbox poll/cleanup · DLQ purge/alert · DLQ reconciler · processed cleanup), 전부 ShedLock. **`PaymentApplication` 에 무조건 `@EnableScheduling`**. 스케줄러 빈에 `@ConditionalOn*` 없음 | 진입점에서 제거하고 `SchedulingConfig` 게이트로 넘긴다(order·notification 선례) |
| 리스너 | `PaymentEventConsumer`(3토픽) · `CompensationRequestConsumer`(2토픽) → `kafkaListenerContainerFactory`. `DeadLetterConsumer` · `DeadLetterQuarantineConsumer` → `deadLetterKafkaListenerContainerFactory`. 테스트 소유 리스너 2개(`OutboxKafka.PaymentCompletedHeaderCapture` · `Dlq.DlqTestListener`)는 기본 factory 를 쓴다. 전부 `AbstractKafkaListenerContainerFactory` 라 common BPP 가 타입으로 잡는다 | 프로덕션 코드 수정 불필요 |
| opt-in 전수 조사: 리스너 | 브로커 왕복으로 소비를 관측하는 클래스 **5개**. `CompensationRequestConsumer`(L301 `kafkaTemplate.send` → await, 나머지 케이스는 consumer 메서드 직접 호출) · `OutboxKafka`(테스트 리스너로 발행 수신 확인) · `DeadLetterLedger`(dlq send → 원장 await) · `Dlq`(order.created send → DLQ 라우팅 + 원장 await) · `Idempotency`(order.created send → payment 행 await) | 리스너 opt-in 5 |
| opt-in 전수 조사: 스케줄러 | 타이머 발화를 기다리는 클래스 **1개**. `ShedLock` 은 `shedlock` 행이 생길 때까지 15초를 await 하며, 직접 호출은 없다. `OutboxKafka` · `ObservabilityMetrics` 는 `pollAndPublish()` 를 직접 부른다. `CleanupMatrix` 는 빈 존재만 단언하고 스케줄러 빈은 무조건 등록된다. `RefundLedger` · `ApprovalLedger` 는 `claimForReconcile` 등 서비스를 직접 부른다 | 스케줄러 opt-in 1 |
| 조용한 green 여부 | 리스너 opt-in 5클래스는 모두 양성 단언("생성됨/수신됨")을 먼저 한다. `ShedLock` 도 행 생성이라는 양성 단언이다. 끄면 red 가 되어야 한다 | 검증 방법에서 실제로 끄고 red 확인 |
| 타이머 무력화 관용구 | `RefundLedger` · `CompensationRequestConsumer` · `ApprovalLedger` 의 `app.refund.*-interval-ms=3600000` · `app.payment.approval.reconcile-interval-ms=3600000`. base yml 에 값이 있으므로(`application.yml` L94·108·109) 빼도 placeholder 는 깨지지 않는다 | scheduling off 로 대체되므로 제거 |
| **클래스 간 토픽 누수** | `DeadLetterLedger` 는 `order.created.dlq` · `payment.completed.dlq` 에 key `key-1` 레코드를 직접 보낸다. `Dlq.DlqTestListener`(group `test-dlq-verification-group`, `auto-offset-reset: earliest`) 는 그 두 토픽을 구독한다. `Dlq` 의 앞 두 케이스는 key 없이 `allSatisfy(topic == order.created.dlq, value == invalid-json)` 로 단언한다. **Ledger 가 먼저 돌면 Ledger 레코드가 큐에 섞여 red 가 된다.** 세 번째 케이스는 이미 고유 key 로 거른다 | P4 에서 해소 |
| 누수 아님(확인) | `order.created` 는 `Idempotency` 와 `Dlq` 가 둘 다 보내고 같은 group(`PaymentEventConsumer`) 이 소비한다. offset 이 커밋되므로 뒤 context 가 이미 읽은 레코드를 다시 읽지 않는다. `Idempotency` 는 자기 orderId 로 단언한다. `payment.completed` 발행자는 `OutboxKafka` 하나다(`ObservabilityMetrics` 는 probe 토픽). `DeadLetterLedger` 의 원장 소비자는 `Dlq` 와 group 이 같아 역방향도 커밋 offset 뒤부터 읽는다 | 조치 없음. 셔플(P6)로 확인 |
| lint 대상 | `MODULES=(order-service user-service notification-service)`. self-test 는 notification PR 에서 `MODULES` 순회로 바뀌었다 | 한 줄 추가 |

### 트레이드오프

- **opt-in 6클래스는 context 를 캐시하지 못한다**(`@DirtiesContext(AFTER_CLASS)`). 13개 중 6개라
  notification(7개 중 2개)보다 이득이 작다. ADR-0029 D3 가 받아들인 비용이다. 리스너가 켜진 context 가
  캐시에 남아 공유 브로커를 계속 읽는 쪽이 더 비싸다.
- **P4 는 `cleanKafkaTopics` 가 아니라 key 필터로 막는다.** `@BeforeEach` 에서 토픽을 비워도 `@KafkaListener`
  는 context 기동 시점(= `@BeforeEach` 이전)에 earliest 로 이미 옛 레코드를 읽기 시작한다. 그래서 비우는
  시점이 소비보다 늦을 수 있다. order 의 `cleanKafkaTopics` 선례는 raw consumer 의 `seekToBeginning` 용이다.
  `Dlq` 세 번째 케이스가 이미 쓰는 "고유 key 로 이 테스트가 만든 레코드만 고른다" 방식을 앞 두 케이스에
  맞추는 편이 소비 타이밍과 무관하게 성립한다.

## 2. 작업 항목

- [x] P1. 13개 클래스에서 `@Testcontainers` · `@Container` · `@ServiceConnection` 필드와 관련 import 를
  제거하고, `@Import` 에 `SharedContainers.class` 를 추가한다(기존 `@Import` 항목과 병기). 필드
  `kafka.getBootstrapServers()` 참조가 있으면 `SharedContainers.KAFKA` 로 바꾼다.
- [x] P2. `PaymentApplication` 의 `@EnableScheduling` 과 import 를 제거하고, javadoc 을 "구동은
  `SchedulingConfig`(`app.scheduling.enabled`) 가 게이트한다" 로 고친다. `build.gradle` `test` 태스크에
  notification 과 같은 세 가지(scheduling off · listener off · 클래스 순서 랜덤 + 시드 출력)를 넣는다.
- [x] P3. opt-in:
  - 리스너: `CompensationRequestConsumer` · `OutboxKafka` · `DeadLetterLedger` · `Dlq` · `Idempotency` 에
    `app.kafka.listener.enabled=true` + `@DirtiesContext(AFTER_CLASS)` (order `DeadLetterLedger` 와 같은 주석)
  - 스케줄러: `ShedLock` 에 `app.scheduling.enabled=true` + `@DirtiesContext(AFTER_CLASS)`
  - `RefundLedger` · `CompensationRequestConsumer` · `ApprovalLedger` 의 `*-interval-ms=3600000` 을 제거한다.
    그 자리 주석이 있으면 "배경 스케줄러는 테스트 기본 off(ADR-0029)" 로 바꾼다
  - **(구현 중 변경)** `RefundLedger` · `ApprovalLedger` 는 관용구를 지우면 그 줄에 붙어 있던 주석이 가리킬 속성이
    없어진다. 그래서 주석도 함께 지웠다. 근거는 `build.gradle` 의 D-035 주석에 남는다. `CompensationRequestConsumer`
    는 opt-in 속성이 들어가므로 그 자리에 "배경 스케줄러는 기본 off" 주석을 남겼다
- [x] P4. 누수 차단: `DlqIntegrationTest` 의 앞 두 케이스(`consumerFailure_routesToDlqAndSendsSlack` ·
  `dlqPreservesTraceHeaders`) 가 고정 `"test-key"` 대신 고유 key 로 보내고, 단언은 그 key 의 레코드로
  거른 뒤에 한다(세 번째 케이스와 같은 방식). `allSatisfy` 의 의미("이 메시지에서 나온 DLQ 레코드는 전부
  규약을 지킨다")는 유지한다.
- [x] P5. lint 는 `MODULES` 에 `payment-service` 를 추가한다. D-035 ④ 토픽 누적 관측은 notification 과 같은
  방식으로 한다. 실행 중 `kafka-topics.sh --list` 샘플링으로 최종 토픽 수를 적고, 로그에서
  `UNKNOWN_TOPIC_OR_PARTITION` / 메타데이터 타임아웃 건수를 센다. 결과만 적는다.
- [x] P6. 셔플 검증: 서로 다른 시드 3개로 `:payment-service:test` 를 돌려 전부 초록인지 본다.
- [x] P7. 전후 실측: `:payment-service:test --rerun` 의 벽시계 시간을 전후로 잰다.

## 3. 검증 방법

- **opt-in 이 실제로 필요한지(조용한 green 차단)**: P3 의 opt-in 을 클래스마다 하나씩 빼고 돌려 **red** 인지
  본다. 초록이면 그 테스트는 리스너나 타이머를 관측하지 않는다는 뜻이고, opt-in 대상 판정이 틀린 것이다.
  확인한 뒤 원복한다.
- **P4 누수가 실재했고 막혔는지**: P4 적용 **전에** Ledger → Dlq 순서를 강제해 두 클래스만 돌린다
  (`--tests` 두 개, `ClassOrderer$ClassName` 은 `DeadLetterLedger` < `DlqIntegration`). Dlq 앞 두 케이스가
  red 인지 확인한다. P4 적용 후 같은 순서로 green 인지 본다. 초안 단계에서 red 가 안 나오면 누수 가설이
  틀린 것이므로 P4 를 빼고 그 사실을 정정 이력에 남긴다.
  - 정정 이력: 클래스 순서만 강제한 첫 실행은 **green** 이었다. 누수가 없어서가 아니었다. 임시 출력으로 큐를
    찍어 보니 Ledger 의 `key-1` 레코드 6건이 `@BeforeEach` 의 `clear()` **뒤에** 도착했다. 그 실행에서 먼저 돈
    메서드가 이미 key 로 거르는 timestamp 케이스여서 드러나지 않았을 뿐이다. JUnit 기본 메서드 순서는 결정적이지만
    드러나지 않게 설계돼 있어, 메서드 이름이 바뀌면 거르지 않는 케이스가 그 자리에 올 수 있다. 그래서 **메서드 순서도
    강제**(`MethodOrderer$MethodName`, `consumerFailure` 가 먼저)해야 검증이 성립한다. 초안의 검증 절차는 이 축을 빠뜨렸다
- **writer off 가 걸렸는지**: opt-in 없는 클래스 context 에서 `SchedulingConfig` 빈이 없는지, 그리고
  `KafkaListenerEndpointRegistry` 컨테이너가 전부 `isRunning()=false` 인지 임시 probe 로 확인한다.
  build.gradle 프로퍼티를 뺀 음성 대조에서는 빈이 존재해야 한다(프로덕션 기동 경로 확인을 겸한다). probe 는 남기지 않는다.
- **lint**: payment-service 테스트에 `@Testcontainers` 를 되돌리면 ITC-002 로 exit 1 이 나야 한다. `--self-test` 통과.
- 최종: `./gradlew test` 전량 (work.md §8).

### 검증 결과 (2026-09-26)

| 항목 | 결과 |
|---|---|
| 전후 실측(P7) | `:payment-service:test --rerun` 1919초(약 32분) → **55초**(시드 11). 테스트 239개 전부 통과 |
| 셔플(P6) | 시드 11 · 42 · 1790500000000 전부 초록(55s · 54s · 57s) |
| 토픽 누적(P5) | 실행 중 샘플링 최종 **20개**: 원천 9 · `.dlq` 9 · `observability.outbox.probe` · `__consumer_offsets`. UUID 토픽 0. 실행 7회 로그의 `UNKNOWN_TOPIC_OR_PARTITION` · 메타데이터 타임아웃 0건. notification(12개)보다 늘었지만 전부 고정 이름이라 누적이 아니다 |
| P4 누수 | 수정 전 Ledger → Dlq + 메서드 이름순 강제 → `consumerFailure` **red**(Ledger 레코드, 원본 offset 43 이 섞임). 수정 후 같은 순서 **green**. 임시 순서 지정은 원복 |
| opt-in 필요성 | 6클래스의 opt-in 을 한꺼번에 `false` 로 → ShedLock 2/2 · DeadLetterLedger 4/4 · Idempotency 2/2 · Dlq 3/3 · OutboxKafka 4/4 · Compensation 1/14 실패(실패 1건이 브로커 왕복 케이스, 나머지는 consumer 메서드 직접 호출). 조용한 green 없음 |
| writer off | 임시 probe: `SchedulingConfig` 빈 부재 + 리스너 컨테이너 7개 전부 `isRunning()=false`. build.gradle 프로퍼티를 빼면 `[schedulingConfig]` · running 7 로 실패(음성 대조 성립). probe 삭제 |
| lint | ShedLock 에 `@Testcontainers` 복원 → ITC-002 exit 1, 원복 → exit 0. `--self-test` 10/10. `scripts/*-lint.sh` 23개 전부 0 |
| 전량 | `./gradlew test` BUILD SUCCESSFUL (order 420 · payment 239 · product 196 · gateway 120 · common 109 · user 65 · common-auth 57 · notification 47, 실패 0) |

토픽 샘플링은 컨테이너 안에서 `localhost:9093`(testcontainers 브로커 리스너)으로 붙어야 한다. 9092 는 호스트 매핑
주소를 광고해 목록이 비어 나오고, 브로커가 뜨기 전에 붙으면 `kafka-topics.sh` 가 무한 재시도하므로 `timeout` 이
필요하다. product PR 에서 같은 관측을 반복할 때 쓴다.

## 미해결 / 이월

- lint 모듈 목록 누락 가드는 notification PR 의 이월 그대로다. D-035 마지막 PR(product) 에서 판단한다.
- `Dlq` 이외의 테스트 소유 리스너(`OutboxKafka.PaymentCompletedHeaderCapture`)는 이미 key 로 거르고, 같은
  토픽의 다른 발행자가 없으므로 조치하지 않는다. 새 발행자가 생기면 그 PR 에서 본다.
- lint 는 줄 선두의 짧은 이름(`@Testcontainers` · `@Container`)만 본다. `@org.testcontainers.junit.jupiter.Testcontainers`
  처럼 FQN 으로 쓰면 통과한다(검증 중 확인). 원래 있던 사각지대이고 이번 변경과 무관하다. 목록 누락 가드와 함께
  product PR 에서 판단한다.
