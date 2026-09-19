---
grade: S
---
# task-d028-topic-describe-setup-race

D-028. `KafkaTopicConfigMechanismIntegrationTest` 의 **셋업 단언**이 붐비는 CI shard 에서
깨진다. `createOrModifyTopics` 가 리턴해도 브로커 메타데이터에 토픽이 아직 안 보이는 창이
있고, 그 창에 `describe` 가 들어가 `.all().get()` 이 `UnknownTopicOrPartitionException` 을
`cause` 로 감싼 `ExecutionException` 을 던진다 (PR #125 CI run `35366517910`, order shard
418건 중 2건, 실패 지점 `:72`).

D-021 이 넣은 `awaitConfig` 는 **한 칸 뒤**의 문제를 덮는다 — "토픽은 있는데 값이 구값".
`untilAsserted` 는 `AssertionError` 만 삼키므로 "토픽 자체가 없음" 은 관통한다. 이번 것은
그 처방이 닿지 않는다.

## 명제

`applyTopics` 가 갓 만든 토픽을 뒤따르는 `describe` 가 브로커 메타데이터 지연 때문에
실패할 수 있으면 미완이다. 단, 그 대기가 **계약 단언**(modify 이후 config 값이 무엇이어야
하는가)까지 감싸면 그것도 미완이다 — 감싸면 "언젠가 맞으면 통과" 가 되어 하드닝이 풀린다
(D-019 와 같은 선).

## 검증으로 드러난 사실 (계획 전 코드 확인)

| 전제 | 결과 |
|---|---|
| 실패 지점 `:72` = `describeConfigs(...).all().get()` | 확인 (`describe()` L69–76) |
| `awaitConfig` 가 토픽 부재를 못 덮는다 | 확인 — `untilAsserted` 는 `AssertionError` 만 삼킨다 |
| 노출 지점이 V-P4-1/V-P4-2 둘 | **반증** — `describe` 가 공용 헬퍼라 L117·L148·L169·L188·L236 다섯 곳 |
| `ignoreException(UnknownTopicOrPartitionException)` 로 해결 | **반증** — 아래 |

`.all().get()` 이 던지는 것은 `UnknownTopicOrPartitionException` 이 아니라 그것을 `cause` 로
감싼 `ExecutionException` 이다. Awaitility 4.x 의 `ignoreException(Class)` 는 던져진 예외
자체만 보고 `cause` 를 풀지 않는다(jar `javap` 확인). 그래서 TASKS.md 가 적은 첫 처방 후보는
**조용히 안 먹는다**. `ignoreExceptions()` 로 넓히면 모든 브로커 오류를 삼켜 D-019 선을
넘는다. 두 번째 후보("`describe` 전 토픽 존재 대기")를 택한다.

`applyTopics` 안에 대기를 두는 이유: 노출 지점 5곳에 하나씩 붙이면 새 테스트가 추가될 때
빠뜨린다. 생성 직후 한 곳이 유일한 관문이다. 이미 존재하는 토픽에 `modify` 로 부를 때는
즉시 통과하므로 비용이 없다.

## 작업 항목

- [x] P1. `awaitTopicVisible(String topic)` 추가 — `describe` 가 `ExecutionException(cause=UnknownTopicOrPartitionException)` 을 던지는 동안만 재시도. **다른 예외는 그대로 던진다**(진짜 브로커 오류가 10초 타임아웃으로 뭉개지지 않게)
- [x] P2. `applyTopics` 말미에서 넘긴 `NewTopic` 각각에 대해 `awaitTopicVisible` 호출
- [x] P3. 계약 단언 경로(L194·L203·L241, 기존 토픽을 보는 `describe`)는 **무변경**임을 확인. 대기는 토픽 존재에만 걸고 config 값에는 걸지 않는다

## 검증

1. **실패 주입 — 대기가 실제로 그 예외를 잡는가**: `awaitTopicVisible` 의 타임아웃을 0에
   가깝게 낮추고 `describe` 가 첫 폴에서 `UnknownTopicOrPartitionException` 을 던지도록
   스텁해 `ConditionTimeoutException` 으로 떨어지는지 본다. 원본 예외가 관통하면 catch 가
   `cause` 를 못 푼 것이다
2. **실패 주입 — 다른 예외를 삼키지 않는가**: `cause` 가 다른 예외일 때 대기가 즉시
   재던지는지 확인한다. 삼키면 10초 뒤 타임아웃으로 원인이 사라진다
3. **계약 단언이 여전히 red 를 낼 수 있는가**: `KafkaAdmin.setModifyTopicConfigs(true)` 를
   `false` 로 되돌려 V-P4-3 이 red 가 되는지 본다. green 이면 대기가 계약 단언을 감쌌다는
   뜻이므로 P3 이 깨진 것이다
4. 5개 테스트 전부 통과 (`:order-service:test --tests '*KafkaTopicConfigMechanism*'`)

머지 PR: [#126](https://github.com/Kimgyuilli/PeakCart/pull/126) — D-028 종결
