# ADR-0029: 테스트에서 자율 writer(스케줄러 · Kafka 리스너)는 기본 off + opt-in

- **Status**: Accepted
- **Date**: 2026-09-24
- **Deciders**: Kimgyuilli
- **관련 Phase**: Phase 5

## Context

ADR-0028 로 통합 테스트 컨테이너를 모듈 싱글톤으로 바꾸자, 그 전까지 보이지 않던 실패군이
드러났다. 원인은 컨테이너 공유 자체가 아니라 **아무도 요청하지 않았는데 도메인 상태를 고치는
주체가 테스트와 경주한다**는 것이다. 이 주체를 이하 **자율 writer** 라 부른다.

컨테이너를 클래스마다 새로 띄우던 동안에는 자율 writer 가 각자 자기 DB·자기 브로커를 고쳐서
**문제가 없었던 것이 아니라 보이지 않았을 뿐**이다.

자율 writer 는 둘이다.

### ① `@Scheduled` 스케줄러 (D-032 구현 중 발견, 2026-09-22)

각 서비스 진입점에 `@EnableScheduling` 이 무조건 붙어 있어 `OutboxPollingScheduler`·
`OrderTimeoutScheduler` 등이 테스트에서도 돌았다. 결과가 둘이었다.

| | 내용 |
|---|---|
| 비결정성 | 배경 잡이 테스트가 단언하는 행을 고친다. 실행마다 실패 대상이 바뀌었다(run1 3건 / run2 1건 / run3 2건) |
| context 파편화 | 각 테스트가 `app.outbox.polling.delay=1h` 같은 프로퍼티로 개별 무력화해 왔다. **그 프로퍼티가 곧 context 캐시 키**라, 타이머를 끄려는 행위가 context 를 쪼갠다 |

즉 비결정성과 context 파편화가 **같은 원인**에서 나왔다. 스케줄링을 끄자 오염 실패가 전부
사라지고 타이머 발화를 검증하는 테스트 3건만 남았다(run4) — 오염원이 하나였다는 증거다.

### ② `@KafkaListener` 리스너 (D-032 V-4 셔플이 발견, 2026-09-23)

클래스 순서 셔플을 켜자 `DlqReplayEntrypointIntegrationTest.preconditionUsesRealOrderAdapter`
가 시드 4개에서 **결정적으로** 실패했다. 테스트가 `stock.reservation.result` 에 fixture 를
발행하면 실제 `OrderEventConsumer` 가 먼저 소비해 `confirmReservation()` 을 적용하고, 뒤이은
replay precondition 이 "이미 예약이 확정됐다" 로 거부한다.

이 테스트에는 **이미 워크어라운드가 있었다** — `@BeforeEach` 에서 `KafkaListenerEndpointRegistry`
를 훑어 업무 리스너 컨테이너를 `stop()` 한다(2026-09-11, `89955c1`). 그런데도 실패한다.

**워크어라운드가 못 막는 이유를 실측으로 갈랐다.** `@KafkaListener` 의 `groupId` 는 하드코딩된
상수라 **모든 캐시된 context 가 같은 그룹으로 같은 브로커에 붙는다.** 세 클래스만 돌린
런에서:

```
consumer-order-svc-stock-result-group-36
consumer-order-svc-stock-result-group-44      ← 서로 다른 context
...
Successfully joined group with generation Generation{generationId=4, memberId='consumer-order-svc-stock-result-group-44-...'}
Adding newly assigned partitions: stock.reservation.result-0
```

자기 context 의 컨테이너를 stop 해도 **다른 캐시된 context 의 consumer 가 파티션을 넘겨받아
계속 소비한다.** 컨테이너를 클래스마다 새로 띄우던 동안에는 브로커가 달라 그룹이 겹치지
않았고, 공유하는 순간 경합이 상시화됐다. **per-context 로는 구조적으로 막을 수 없다** —
이것이 이 ADR 이 필요한 이유다.

`spring.kafka.listener.auto-startup` 도 듣지 않는다. 각 서비스가
`ConcurrentKafkaListenerContainerFactory` 를 **직접 만들어**(`OrderKafkaConfig` 등) Boot 의
auto-configured factory 를 쓰지 않기 때문이다.

### 왜 지금 결정하는가

D-032 구현이 `SchedulingConfig` 신설 + `OrderApplication` 의 `@EnableScheduling` 제거로
**프로덕션 코드를 이미 건드렸는데 결정 기록이 없다.** ADR-0028 은 *컨테이너 수명* 결정이지
*자율 writer 정책* 이 아니다. D-035 가 이 변경을 4개 서비스에 반복하므로, 확산 전에 남기지
않으면 코드가 5벌 퍼진 뒤 근거를 쓰게 된다 — ADR-0028 이 이미 겪은 실패다.

## Decision

**테스트에서 자율 writer 는 기본 off 이고, 그 동작을 검증하는 테스트만 명시적으로 켠다.
켠 테스트는 `@DirtiesContext(AFTER_CLASS)` 로 자율 writer 의 수명을 자기 클래스에 가둔다.**

세 가지를 함께 정한다.

**D1. 대칭 게이트.** 스케줄러와 리스너를 같은 형태로 다룬다.

| 축 | 프로퍼티 | 게이트 지점 |
|---|---|---|
| 스케줄러 | `app.scheduling.enabled` | `common` 의 `SchedulingConfig` (`@ConditionalOnProperty`, `matchIfMissing = true`) |
| Kafka 리스너 | `app.kafka.listener.enabled` | 각 서비스 container factory 의 `setAutoStartup(...)` |

**프로덕션 기본값은 켜짐**(`matchIfMissing = true`)이라 운영 동작은 변하지 않는다. 끄는 것은
Gradle `test` 태스크의 시스템 프로퍼티뿐이다. 리스너 게이트를 Boot 속성이 아니라 factory 의
`autoStartup` 에 거는 것은 위 Context 의 실측 결론이다 — 서비스가 factory 를 직접 만드는 한
Boot 속성은 도달하지 않는다.

**D2. opt-in 은 `@TestPropertySource` 인라인 프로퍼티로 한다.** 인라인이 시스템 프로퍼티보다
우선한다. opt-out(각 테스트가 `delay=1h` 로 무력화)이 아닌 이유는 둘이다 — 빠뜨리면 노출되고,
무력화 프로퍼티가 context 캐시 키라 켜는 쪽이든 끄는 쪽이든 파편화를 만드는데 **켜는 쪽이
수가 적다**(order-service 기준 스케줄러 2 · 리스너 약 6 클래스).

**D3. 자율 writer 를 켜는 테스트는 `@DirtiesContext(AFTER_CLASS)` 로 수명을 가둔다.** opt-in
만으로는 부족하다 — context 캐시 때문에 그 클래스의 타이머·리스너가 **자기 테스트가 끝난 뒤에도
계속 돌며** 공유 DB·브로커를 고친다(200ms 주기라 이후 전 클래스가 노출). 컨테이너는 static
이라 context 파괴의 영향을 받지 않는다(ADR-0028).

**D4. DLQ 리스너(`dlq-*`)는 게이트 대상에 포함하되, 관통 검증이 필요한 테스트가 개별로 켠다.**
`DeadLetterContainerGuard.LISTENER_ID_PREFIX` 로 식별되는 컨테이너가 여기 해당한다.

## Alternatives Considered

### Alternative A: 현행 유지 — 각 테스트가 `KafkaListenerEndpointRegistry` 로 stop()
- **장점**: 이미 구현돼 있고 프로덕션 코드를 안 건드린다
- **단점**: 자기 context 만 멈춘다
- **기각 사유**: **실측으로 무효가 확인됐다.** `groupId` 가 상수라 캐시된 다른 context 의
  consumer 가 같은 그룹으로 파티션을 넘겨받는다(위 `generation 4` 로그). 셔플 시드에 따라
  실패가 갈리는 것이 그 결과다. per-context 수단으로 전역 문제를 막을 수 없다

### Alternative B: `spring.kafka.listener.auto-startup=false`
- **장점**: 코드 변경 0, Boot 표준 속성
- **단점**: 서비스가 container factory 를 직접 정의한다
- **기각 사유**: **적용되지 않는다.** 5개 서비스 전부 `ConcurrentKafkaListenerContainerFactory`
  를 `@Bean` 으로 손수 만든다. 더 나쁜 것은 **일부만 듣는 것처럼 보이는 상태** 다 — Boot
  auto-config 로 만들어진 컨테이너가 섞이면 "껐는데 일부가 돈다" 가 되어 진단이 더 어려워진다

### Alternative C: context 마다 `groupId` 를 랜덤화해 격리
- **장점**: 리스너를 끄지 않아 프로덕션과 같은 경로가 돈다. 그룹 간 경합이 사라진다
- **단점**: 리스너는 여전히 자율 writer 다. 브로커에 그룹이 무한 누적된다
- **기각 사유**: **격리 축이 틀렸다.** 문제는 "다른 context 의 consumer 가 먹는다" 가 아니라
  "요청하지 않은 소비가 도메인 상태를 바꾼다" 다. 그룹을 갈라도 자기 context 의 리스너가
  fixture 를 먹는 것은 그대로다. 게다가 `groupId` 를 테스트에서만 바꾸면 **그룹 이름 자체가
  계약인 DLQ replay 좌표**(ADR-0020/0022)의 검증이 무의미해진다

### Alternative D: 테스트 전용 프로파일/`application-test.yml` 로 끈다
- **장점**: YAML 한 곳에서 관리된다
- **단점**: 프로파일이 런타임 동작 정책을 쥔다
- **기각 사유**: **ADR-0007 위반.** 프로파일은 "환경마다 달라지는 연결 정보" 만 선언하고
  동작 규약은 base 또는 Java Config 로 간다. 자율 writer on/off 는 명백히 동작 규약이다

### Alternative E: 스케줄러/리스너 빈을 `@MockBean` 으로 대체
- **장점**: 테스트가 의도를 국소적으로 표현한다
- **단점**: `@MockBean` 은 context 캐시 키다
- **기각 사유**: **원래 문제를 그대로 재현한다.** `delay=1h` 프로퍼티가 캐시를 쪼갠 것과 같은
  이유로 파편화를 만든다. ADR-0028 이 context 캐시 적중을 얻으려고 한 전환을 무효화한다

## Consequences

### 긍정적 영향
- 통합 테스트에서 **아무도 요청하지 않은 상태 변경이 사라진다.** 테스트가 단언하는 상태를
  바꾸는 주체가 테스트 자신뿐이 된다 — 컨테이너 공유와 무관하게 그 자체로 옳다
- **context 캐시 적중이 올라간다.** 타이머를 끄려고 박던 프로퍼티가 사라지면서 지문이 수렴한다
  (ADR-0028 §Context 의 "지문이 다른 9개" 가 이것이었다)
- 셔플(ADR-0028 §Decision 4)이 의미를 갖는다. 자율 writer 가 남아 있으면 셔플은 자율 writer 의
  타이밍만 흔들어 **매번 다른 곳이 깨지는 잡음**을 낸다

### 부정적 영향 / 트레이드오프
- **프로덕션 코드가 테스트를 위해 게이트를 갖는다.** `matchIfMissing = true` 로 운영 기본값은
  보존하지만, "테스트 때문에 프로덕션에 조건이 붙었다" 는 사실은 남는다. 대안 A~E 가 전부
  더 나쁘다는 판단으로 받아들인다
- **opt-in 을 빠뜨리면 조용히 green 이 된다.** 리스너 소비를 검증해야 하는 테스트가 리스너를
  안 켜면 "아무 일도 안 일어났다" 를 관측하며 통과한다. opt-out 의 실패 모드(red)보다 나쁜
  방향이다 — 전환 시 **대상 전수 조사**가 필수이고, 이것이 D-032/D-035 의 작업 항목이 된다
- `@DirtiesContext(AFTER_CLASS)` 를 붙인 클래스는 context 를 재생성한다. order-service 기준
  8클래스 안팎이라 수용 가능하나, 확산 시 이 수가 늘면 전환 이득을 깎는다

### 후속 결정에 미치는 영향
- **D-032 의 V-4 blocker 가 해소된다.** 결함 4의 처분이 D1(리스너 게이트)이다
- **D-035(4모듈 확산)의 작업 항목이 확정된다.** 서비스마다 ① `@EnableScheduling` 제거
  ② container factory 에 `autoStartup` 게이트 ③ opt-in 대상 전수 조사 ④ `@DirtiesContext`
- 게이트 우회(`@Container` 직접 선언 금지 lint 와 같은 성격)의 lint 화는 확산 후 재판단한다.
  지금 만들면 대상이 order-service 하나뿐이라 자기 충족적이다

## References

- [ADR-0028](./0028-integration-test-container-lifecycle.md) — 통합 테스트 컨테이너 수명 = 모듈 싱글톤
- [ADR-0007](./0007-yaml-profile-merge-principle.md) — YAML 프로파일 병합 원칙 (Alternative D 기각 근거)
- [ADR-0020](./0020-dlq-replay-contract.md) · [ADR-0022](./0022-replay-entrypoint-rollout-and-drain.md) — `groupId` 가 replay 좌표 계약인 근거 (Alternative C 기각)
- `docs/plans/task-d032-integration-test-container-singleton.md` §2-3b, §2-3c — 발견 경위와 틀린 가설 기록
- `common/src/main/java/com/peekcart/global/config/SchedulingConfig.java`
- `order-service/src/main/java/com/peekcart/order/infrastructure/kafka/OrderKafkaConfig.java`
