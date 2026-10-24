---
grade: L
---
# D-106 ⑩ — 토픽 생성 경합으로 선언 config 가 적용되지 않는 문제

기준: 2026-10-24, main `e57e294`. 원자료: `loadtest/reports/2026-10-24/s4/d065-5-*`,
`loadtest/reports/2026-10-24/session-issues.md` #10, `docs/progress/evidence/d078-gke-20261024.md` §S4.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- GKE S4 스냅샷의 세 갈래(7키 전부 동적 / 차분 4키만 동적 / 동적 0)를 로컬에서 **같은 코드 경로로**
  재현하지 못했거나, 다른 경로(초기화 전체 실패, 토픽 삭제)를 배제한 근거가 없다.
- 경합을 주입한 뒤 수정 경로를 거쳐도 그 토픽의 계약 키 7종 값과 파티션 수가 선언값과 같아지지 않는다.
- 토픽을 선언하는 3서비스(order, product, payment) 중 하나라도 재적용이 배선되지 않았거나,
  재적용 실패가 기동을 깨뜨린다.
- 재측정의 D-065 ⑤ 점검이 파티션 수를 보지 않는다는 사실이 기록되지 않았다.

등급 L: `:common` 공유 모듈에 클래스를 더하고(계약 표면) 4개 모듈을 건드린다. 되돌림은 이 작업 파일 안에서 끝난다.
ADR 선행 신호(새 외부 의존성, 경계 변경, 새 인프라) 없음. ADR-0020 은 Update Log 로 사실을 정정한다.

## 2. 배경

### 2.1 GKE 스냅샷 판독

S4 는 `kubectl delete ns peekcart` 뒤 첫 배포라 브로커가 비어 있었고 전 서비스가 함께 기동했다
(`docs/plans/done/D-078-session-plan.md` S4 1번). `d065-5-effective-sample.txt` 의 동적 설정은 세 갈래다.

| 갈래 | 토픽 | `DYNAMIC_TOPIC_CONFIG` | 해석 |
|---|---|---|---|
| A 생성 | `order.*` 6개, `payment.approval-recovery.*` 4개 | 7키 전부 | 소유 서비스 KafkaAdmin 이 config 를 실어 생성 |
| B 차분 갱신 | product 토픽 12개, `payment.failed`, `payment.refunded` | 기본값과 다른 4키만 (`retention.bytes`, `segment.bytes`, `segment.ms`, `message.timestamp.before.max.ms`) | 다른 서비스 컨슈머가 먼저 자동 생성, 소유 서비스 KafkaAdmin 이 modify 로 다른 키만 ALTER |
| C 미갱신 | `payment.completed`, `payment.requested`, `payment.{completed,failed,refunded,requested}.dlq` | 없음 | 자동 생성 뒤 한 번도 갱신되지 않음 |

payment-service 의 한 번의 적용 안에서 A·B·C 가 함께 나왔다. modify 단계는 돌았고 6개만 그 대상에서 빠졌다.

### 2.2 코드 검증

spring-kafka 소스는 의존성과 같은 3.3.14 sources jar 를 Maven Central 에서 받아 읽었다.

| 확인한 전제 | 현재 코드 | 계획 반영 |
|---|---|---|
| 기존 토픽 갱신 배선 | 3서비스 base YAML `spring.kafka.admin.modify-topic-configs: true`, `*KafkaConfigTopicConfigContractTest` 가 배선 고정 | 배선 문제가 아니다 |
| 생성 경로 | `KafkaAdmin.addOrModifyTopicsIfNeeded`: describe 에 실패한 토픽을 `topicsToAdd` 로 createTopics 한 뒤, modify 검사 대상을 `toCheck.removeAll(topicsToAdd)` 로 만든다 | createTopics 가 실패한 토픽도 modify 대상에서 빠진다 |
| 경합 예외 | `addTopics`: `TopicExistsException` 은 DEBUG 로그만 남기고 삼킨다(주석 "Possible race with another app instance"). 다른 예외는 `KafkaException` 이 되어 `initialize()` 가 ERROR 로그 | C 는 운영 로그 수준에서 보이지 않는다 |
| 파티션 | `checkPartitions` 는 describe 에 성공한 토픽만 증설한다 | C 의 업무 토픽 2개는 파티션도 자동 생성 기본값으로 남았을 것이다. S4 덤프에 파티션 수가 없어 GKE 에서는 미확인 |
| 상위 버전 | 3.3.16 의 `KafkaAdmin.java` 는 3.3.14 와 동일, 4.1.2 의 `addTopics`·`toCheck` 도 같다 | 버전 올림으로 해소되지 않는다 |
| 자동 생성 주체 | 브로커 `auto.create.topics.enable` 미설정(기본 true, `k8s/base/infra/kafka/kafka.yml`·compose 동일), 컨슈머 `allow.auto.create.topics` 미설정(기본 true). order `DeadLetterConsumer` 가 payment `.dlq` 4개를, order `OrderEventConsumer`·product·notification 컨슈머가 payment 업무 토픽을 구독한다 | 빈 스택에서 다른 서비스 컨슈머가 소유 서비스보다 먼저 토픽을 만드는 것은 흔한 경로다(B 가 흔적) |
| 자동 생성 응답 (구현 중 확인) | KRaft 브로커는 자동 생성을 컨트롤러에 넘기고 그 metadata 응답에 `UNKNOWN_TOPIC_OR_PARTITION` 을 준다(kafka 3.8.1 `AutoTopicCreationManager.sendCreateTopicRequest`). 클라이언트 `TopicMetadataFetcher` 는 그 응답을 부재로 보고 재시도하지 않는다(kafka-clients 3.9.2). P1 첫 실행에서 `partitionsFor` 가 빈 목록을 돌려 관측했다 | 자동 생성이 진행 중인 동안 KafkaAdmin 의 describe 도 토픽을 못 본다. 경합 창이 describe 와 create 사이 왕복 한 번보다 넓다 |
| 다른 경로 배제 | 초기화 전체 실패는 `KafkaException` 이라 modify 단계 전체를 건너뛴다. 그 실행에서는 B 도 생기지 않고, 뒤에 성공한 실행이 있으면 C 도 갱신된다. S4 에는 토픽 삭제 단계가 없다 | 세 갈래를 한 실행에서 만드는 경로는 `TopicExistsException` 하나다 |
| 재호출 | `KafkaAdmin.initialize()` 는 public final 이고, 브로커가 없었을 때 다시 부르라고 문서화돼 있다. 컨텍스트 초기화 밖에서는 실패를 `IllegalStateException` 으로 던진다(`initializingContext=false`) | 재호출은 예외를 잡아야 기동 동작이 바뀌지 않는다 |
| 테스트 주입점 | `KafkaAdmin.createAdmin()` 은 3.3.0 부터 protected, kafka-clients 3.9.2 에 `ForwardingAdmin` 이 있다. 테스트 브로커는 `SharedContainers.KAFKA`(`apache/kafka:3.8.1`) | createTopics 직전에 토픽을 자동 생성시켜 경합을 결정적으로 만든다 |
| 공용 빈 등록 | user-service 는 `KafkaAutoConfiguration` 을 제외해 KafkaAdmin 이 없다. `OriginalRecordReader` 는 같은 이유로 `:common` 클래스와 서비스별 `@Bean` 으로 등록한다 | 같은 방식. `@Component` 로 두면 user-service 기동이 깨진다(PLAN-BLINDSPOTS B6) |
| 기존 재적용 수단 | `initialize()` 재호출과 `ApplicationReadyEvent` 사용은 main 코드에 없다(gateway `JwksReadinessConfig` 만) | 새로 만든다 |
| 기동 순서 | `ApplicationReadyEvent` 리스너는 readiness `ACCEPTING_TRAFFIC` 전에 동기로 돈다. liveness 는 그 전에 CORRECT. payment `startupProbe` 는 liveness 경로 | 브로커가 없으면 재적용이 operationTimeout(기본 30초)만큼 readiness 를 늦춘다. liveness 는 영향 없다 |

PLAN-BLINDSPOTS 대조: B2(ADR-0020 §D4-1 은 modify 로 기존 토픽이 반영된다고 적었으나 경합 토픽은 예외, Update Log 로 정정),
B4(파일·배선 명시), B6(`@Component` 금지), B12(아래 2.4). 나머지는 이동·peel·upsert·측정 재현이 아니라 해당 없다.

### 2.3 대안과 선택

| 안 | 내용 | 처분 |
|---|---|---|
| 브로커 자동 생성 끄기 | `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` 로 두 번째 생성 주체를 없앤다 | 보류. 소유 서비스 초기화가 실패하면 토픽이 아예 없고(지금은 기본값으로라도 생긴다), 소비자는 소유자 기동을 기다리며, compose·E2E 동등성 결정이 따라온다. 별도 결정이 필요하다 |
| 컨슈머 자동 생성 끄기 | 4서비스 base YAML `allow.auto.create.topics: false` | 기각. 통합 테스트가 다른 서비스 토픽을 자동 생성에 기대는 범위가 넓고, 생산자 경로는 그대로 남는다 |
| 기동 완료 뒤 재적용 | `ApplicationReadyEvent` 에서 `kafkaAdmin.initialize()` 를 한 번 더 부른다 | 채택. 1차에서 경합한 토픽은 그때 이미 있으므로 describe 에 잡혀 파티션 증설과 config 갱신을 받는다 |

부수 효과: C 의 업무 토픽도 B 처럼 기동 직후 파티션이 1개에서 3개로 늘어난다. 기존 B 경로와 같은 성질이고,
이 저장소의 GKE·E2E 스택은 매번 빈 브로커에서 시작한다.

### 2.4 남는 창 (B12)

2차 적용이 결정적인 것은 1차가 끝까지 돈 경우다. 그때 모든 선언 토픽은 1차가 만들었거나 경합 상대가 만들었다.
1차가 통째로 실패하면(브로커 미가용) 2차가 첫 생성을 맡고, 같은 경합을 다시 겪을 수 있다. 이 안은 창을 줄일 뿐
fence 가 아니며, 그 경우 남은 C 는 여전히 DEBUG 로만 보인다.

## 3. 작업 항목

- [x] P1. 경합 재현 통합 테스트. `order-service/src/test/java/com/peekcart/global/kafka/KafkaTopicConfigMechanismIntegrationTest.java`
  에 생성 경합 절을 더한다(같은 적용 경로 테스트이고 `describe`·`awaitTopicVisible` 헬퍼를 그대로 쓴다).
  KafkaAdmin 하위 클래스가 `createAdmin()` 으로 `ForwardingAdmin` 을 돌려주고, 그 `createTopics` 는 위임 직전에
  컨슈머 `partitionsFor` 로 경합 대상 토픽을 브로커 자동 생성시킨다. 미리 자동 생성한 토픽(B), 경합 토픽(C),
  없는 토픽(A)을 한 번의 적용에 넣어 세 갈래를 재현한다. Spring 컨텍스트는 `AnnotationConfigApplicationContext`
  로 직접 만들고 브로커는 `SharedContainers.KAFKA` 정적 필드로 읽는다(ADR-0028).
- [x] P2. `common/src/main/java/com/peekcart/global/kafka/KafkaTopicReconciler.java`. `ApplicationListener<ApplicationReadyEvent>`
  로 `kafkaAdmin.initialize()` 를 한 번 부르고, 예외는 ERROR 로그로 남긴 뒤 삼킨다. `@Component` 를 붙이지 않는다.
- [x] P3. 배선. `OrderKafkaConfig`·`ProductKafkaConfig`·`PaymentKafkaConfig` 에 `@Bean`. 각 서비스
  `*KafkaConfigTopicConfigContractTest` 에 등록 단언을 더한다. notification 은 선언 토픽이 없어 제외한다.
- [x] P4. P1 테스트에 수정 경로. 같은 경합 뒤 `ApplicationReadyEvent` 를 발행하면 C 의 7키 값과 파티션 3 이
  선언값과 같아진다. 같은 테스트에서 이벤트 전에는 C 가 그대로라는 것을 먼저 단언한다.
- [x] P5. 재적용 실패가 기동을 깨지 않는다. `common` 단위 테스트에서 `initialize()` 가 예외를 던져도 리스너가 전파하지 않는다.
- [x] P6. ADR-0020 Update Log(§D4-1 의 modify 가 생성 경합 토픽을 갱신하지 않는다는 사실과 보완).
  (정정: 초안은 TASKS·progress 갱신까지 P6 에 넣어 PR URL 이 나오기 전에는 닫을 수 없는 항목을 만들었다.
  그 기록은 §완료 조건으로 옮겼다.)

## 검증 방법

- P1: 경합 주입 뒤 `describeConfigs`·`describeTopics` 로 브로커 상태를 판정한다. A 는 7키 전부 동적,
  B 는 기본값과 다른 4키만 동적이고 파티션 3, C 는 동적 0 이고 파티션이 선언 3 이 아니다. 주입 래퍼가 기록한
  createTopics 결과에서 C 가 `TopicExistsException` 으로 실패했음을 함께 단언한다. 주입이 빗나가면 C 가 A 로
  만들어져 단언이 깨진다.
- P4: 같은 실행에서 이벤트 전 C 잔존(red 대비)과 이벤트 뒤 7키 값·파티션 수렴(green)을 순서대로 단언한다.
- P3: 한 서비스에서 `@Bean` 을 지우면 그 서비스 계약 테스트가 실패하는지 변이로 확인한다.
- P5: `initialize()` 예외 주입 뒤 `onApplicationEvent` 가 예외 없이 끝난다.
- 로컬은 `:common:test`, `:order-service:test` 의 해당 클래스와 3서비스 계약 테스트만 돌리고, 모듈 전체는 PR CI 로 확인한다.
  테스트 주석은 `python3 docs/plans/evidence/D-102/check.py --scan` 으로 새 후보를 읽는다.
- 실제 스택 확인은 D-078 재측정 S4 의 D-065 ⑤ 점검(30개 전부 선언값, 업무 토픽 파티션 3)으로 넘긴다.

## 완료 조건

- P1~P6 완료, PR CI gate 성공, 머지 뒤 main CI 성공.
- `/ship` 의 done 갱신에서 TASKS D-106 ⑩ 상태와 PR 링크, D-065 ⑤ 재측정 점검에 파티션 수 대조가 필요하다는 사실,
  progress 이력을 남긴다.

## 미해결

- 브로커 자동 생성 끄기: 생성 주체를 하나로 만드는 근본 처방. 초기화 실패 처리와 E2E 동등성을 함께 정해야 해 별도 결정으로 둔다.
- 2.4 의 남은 창(1차 전체 실패 뒤 2차 경합)은 관측 수단이 없다. 재측정 점검이 잡는다.
- GKE 에서 C 업무 토픽의 파티션 수는 클러스터 삭제로 확인할 수 없다.
- E2E 스택에서 선언 토픽 config·파티션을 자동 대조하는 점검은 후속 후보다.
