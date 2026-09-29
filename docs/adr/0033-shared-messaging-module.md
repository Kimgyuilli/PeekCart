# ADR-0033: Outbox · 멱등성 · DLQ 원장 실행 세트를 공유 모듈 `peekcart-common-messaging` 으로 승격

- **Status**: Accepted
- **Date**: 2026-09-29
- **Deciders**: Kimgyuilli
- **관련 Phase**: Phase 5

## Context

ADR-0011 §D2 는 `global.outbox`·`config.ShedLockConfig` 를 **발행 서비스 전속**(Order/Payment),
`global.idempotency` 를 **소비자별 복제**로 정했다. 당시 전제는 "outbox 는 발행자 두 곳만 갖고,
멱등성 테이블은 소비자마다 따로 둔다" 였다.

이후 전제가 바뀌었다.

| 시점 | 변화 |
|---|---|
| Product peel | 첫 발행 서비스 peel. outbox 실행 세트를 서비스에 복제 (PLAN-BLINDSPOTS B8) |
| 구현 ④-c (ADR-0018 · ADR-0020 · ADR-0022) | DLQ 원장(`global.deadletter`)이 4서비스 공통 경로가 됨. replay 재발행이 outbox 를 거치면서 notification 도 outbox 를 갖게 됨 |

결과로 order · payment · product · notification 4서비스가 `com.peekcart.global.{outbox,idempotency,deadletter}`
와 `global.config.ShedLockConfig` 를 **같은 FQCN 으로 각자** 가진다. 2026-09-29 기준 서비스당 35개
(notification 34개) 중 32개가 4벌 byte 동일하다. 서비스마다 다른 것은 셋뿐이다.

| 파일 | 서비스마다 다른 이유 |
|---|---|
| `DeadLetterConsumer` | 구독하는 `.dlq` 토픽 목록과 소유 group |
| `DeadLetterQuarantineConsumer` | 원본 토픽 발행 서비스에만 있음 (notification 없음) |
| `LedgerOwnerConfig` | `PeekcartService` 값 |

그리고 `OutboxEventStatus` 는 order·product 만 `BACKFILL` 을 가진다. 이 값은 order·product 의
V5 backfill 마이그레이션만 쓰고, 어떤 조회도 이 상태를 대상으로 하지 않는다.

사본이 갈라지지 않도록 `dead-letter-schema-parity-lint.sh` (3)·(4) 가 byte 동일성을 강제한다.
그래서 한 곳을 고치려면 4벌을 같이 고쳐야 하고, 복제의 비용은 줄지 않은 채 lint 가 그 비용을
확인만 한다.

`:common` 으로 옮기는 것은 답이 아니다. `user-service` 도 `:common` 에 의존하고, 진입점이
`com.peekcart.*` 를 component-scan 한다. `:common` 에 넣으면 user-service 가 JPA 엔티티·스케줄러·
DLQ 인프라를 떠안는다 (PLAN-BLINDSPOTS B6).

## Decision

**4서비스에 byte 동일하게 복제된 실행 세트를 새 plain jar 모듈 `peekcart-common-messaging` 으로
옮긴다.** 소비자는 이 세트를 실제로 쓰는 4서비스뿐이다.

### D1. 경계

| 위치 | 대상 |
|---|---|
| `peekcart-common-messaging` | `global.outbox` 9개 (`OutboxEventStatus` 포함) · `global.idempotency` 6개 · `global.deadletter` 중 서비스 무관 16개 · `global.config.ShedLockConfig` |
| 서비스 모듈 (유지) | `DeadLetterConsumer` · `DeadLetterQuarantineConsumer` · `LedgerOwnerConfig` |
| 서비스 모듈 (유지) | Flyway 마이그레이션 (`outbox_events` · `processed_events` · `dead_letter_records` · `shedlock`). DB-per-service (ADR-0012 D1) 이므로 스키마는 계속 서비스가 소유한다 |

패키지는 바꾸지 않는다. FQCN 이 그대로라 서비스 코드의 import 는 한 줄도 바뀌지 않는다.

### D2. `OutboxEventStatus` 는 합집합 한 벌

`BACKFILL` 을 포함한 order·product 판을 공유한다. 공유 대상인 `OutboxEvent` 가 이 enum 을
참조하므로 서비스별로 둘 수 없다. payment·notification 에서 이 값은 쓰이지 않고, `status` 컬럼이
`VARCHAR` 라 DDL 도 필요 없다.

### D3. 의존 규칙

ADR-0011 §D3 의 서비스 허용 의존에 `:peekcart-common-messaging` 을 더한다.
`peekcart-common-messaging` 은 `:common` 과 `:peekcart-common-observability` 에 의존한다.
공개 시그니처가 두 모듈의 타입(`SlackPort`, `LedgerOwner`, `CommitAwareMetrics` 등)을 노출하므로 `api` 로 둔다.

### D4. 재복제 방지

byte 동일성 lint 를 없애고 **중복 FQCN 게이트**(`assertNoDuplicateGlobalFqcn`)의 공유 모듈 목록에
새 모듈을 넣는다. 서비스에 같은 FQCN 이 다시 생기면 빌드가 실패한다. 스키마 parity lint
(SQL 쪽)는 유지한다. 스키마는 여전히 서비스마다 있기 때문이다.

## Alternatives Considered

### Alternative A: `:common` 에 편입
- **장점**: 모듈 수가 늘지 않는다
- **단점**: user-service 가 엔티티·스케줄러·DLQ 리스너 컨테이너 설정을 스캔한다. 막으려면
  `@ConditionalOnProperty` 나 스캔 제외를 32개 클래스 둘레에 둘러야 한다
- **기각 사유**: 조건부 활성화 장치가 곧 새 복잡성이다. 소비자 집합이 다르면 모듈을 나누는 것이
  ADR-0011 §D1 에서 관측성·인증 모듈을 뗀 것과 같은 판단이다

### Alternative B: 현 상태 유지 (복제 + byte parity lint)
- **장점**: 서비스가 서로 다른 속도로 진화할 수 있다
- **단점**: lint 가 byte 동일을 강제하는 한 그 자유는 실제로 없다. 비용(4벌 수정)만 남는다
- **기각 사유**: 복제를 정당화하던 "서비스별로 달라질 수 있다" 가 lint 로 스스로 부정돼 있다

### Alternative C: `DeadLetterConsumer` 까지 공유 (토픽 목록을 설정으로 주입)
- **장점**: 서비스별 파일이 하나 더 준다
- **단점**: `@KafkaListener` 토픽이 SpEL 이나 프로퍼티로 바뀌어 `kafka-subscription-contract-lint.sh`
  가 소스에서 구독을 읽지 못한다
- **기각 사유**: 정적으로 보이는 구독 계약을 잃는 대가가 파일 하나보다 크다

## Consequences

### 긍정적 영향
- 복제 사본 약 96개 파일이 사라진다. 공유 로직 수정은 한 곳에서 끝난다
- byte parity lint 의 java 검사가 사라진다. 컴파일러와 중복 FQCN 게이트가 그 자리를 대신한다

### 부정적 영향 / 트레이드오프
- 한 서비스만 다르게 동작하게 하려면 이제 공유 코드에 분기를 넣거나 파일을 서비스로 되돌려야 한다.
  지금까지 그런 요구는 위 3개 파일로 전부 해소됐다
- 공유 모듈 변경이 4서비스 이미지를 모두 다시 빌드하게 한다. `:common` 과 같은 조건이다
- Dockerfile COPY 목록에 모듈이 하나 늘어난다

## Related

- ADR-0011 §D2·§D3 (부분 대체)
- ADR-0012 D1 (DB-per-service — 스키마는 서비스 소유로 남는 근거)
- ADR-0018 · ADR-0020 · ADR-0022 (DLQ 원장과 replay 가 4서비스 공통 경로가 된 경위)
- D-049
