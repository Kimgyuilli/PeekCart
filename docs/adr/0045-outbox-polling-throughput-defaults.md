# ADR-0045: outbox poller 발행 처리율 기본값 (주기 500ms, 배치 500, 락 최소 보존 설정화)

- **Status**: Accepted
- **Date**: 2026-10-25
- **Deciders**: 프로젝트 오너 (2026-10-25 D-075 처분 결정)
- **관련 Phase**: Phase 5

## Context

### poller 의 구조

`OutboxPollingScheduler` 는 `fixedDelay` 와 ShedLock 으로 서비스당 한 실행만 돌린다. 본문
`OutboxPollingService.pollAndPublish` 는 PENDING 을 `created_at` 순으로 `batch-size` 만큼 읽고, 이벤트마다
`send().get(publish-timeout)` 으로 broker ack 를 기다린 뒤 PUBLISHED 를 저장한다. 전체에 트랜잭션이 없고 저장은
이벤트마다 자기 트랜잭션이다. 이 결정 전의 값은 다음과 같았다.

| 키 | 값 | 출처 |
|---|---|---|
| 주기 `app.outbox.polling.delay` | 5s | 애노테이션 인라인 기본값. 4서비스 yml 에 선언 없음 |
| 배치 `batch-size` | 100 | 4서비스 base yml |
| 최소 보존 `lockAtLeastFor` | 4s | 애노테이션 하드코딩 |
| 최대 보존 `lockAtMostFor` | 5m | 애노테이션 하드코딩 |
| `publish-timeout`·`cycle-timeout`·`max-retry` | 6s·4m·5 | 4서비스 base yml |

서비스당 상한은 100건/5초 = 20건/초다.

### 근거 ① D-002 GKE (2026-09-17, `docs/progress/evidence/d002bc-gke-20260917.md` §②)

사가 완결이 14.3~16.3건/초였고 MySQL CPU 를 4배로 늘려도 14% 오르는 데 그쳤다. 폴링을 500건·0.5초로 완화하자
77.3건/초가 됐다. consumer lag 은 0 이었고, 이벤트당 약 13ms 가 순차 동기 발행과 정합적이라는 관찰이 남았다.

### 근거 ② D-075 로컬 분해 (2026-10-21, `docs/plans/done/D-075.md`, `docs/plans/evidence/D-075/`)

300건 drain 중앙값은 baseline(5s·100·4s) 12.255초, delay-only(500ms·100·4s) 9.408초, batch500(500ms·500·4s) 1.825초,
minlock0(500ms·100·0s) 3.094초였다. delay-only 는 매 반복 lock skip 이 12회였다. **4초 최소 보존 때문에 주기 500ms 가 실행
주기 500ms 가 되지 않는다.** active 본문(300건 약 1.8~2.1초)에서 이벤트별 save 누적이 약 1.5~1.7초로 가장 컸고 publish timer 는
약 0.3~0.4초였다. DB 없는 producer-only 대조는 window 1 이 238.1ms, window 32 가 24.8ms 였다(300건).

### 근거 ③ D-078 GKE (2026-10-24, `docs/progress/evidence/d078-gke-20261024.md` §S3·§읽는 법 ⑤)

측정 overlay 가 order 만 500ms·500건으로 덮고 product 는 5s·100건이었다. product 발행률은 20.0~20.6건/초로 상한에 붙었다.

| run | 사가 왕복 p95 / p99 (s) | 배수(lag 포함) | 만료 취소 |
|---|---|---|---|
| contend r1 | 243.2 / 247.5 | 264s | 0 |
| contend r2 | 431.8 / 441.9 | 459s | 2,609 |
| contend r3 | 503.9 / 518.5 | 534s | 3,839 |
| spread r1 | 472.9 / 494.4 | 509s | 3,180 |

order 는 부하가 끝난 뒤 약 76~78건/초(r2·r3·spread)로 빠졌고 최대 85건/초였다. 반면 k6 종료 3~4초 뒤 첫 표본까지 발행된
order 이벤트는 r3 1,128건, spread 2,166건이라, 60초 부하 구간의 발행률(약 18~36건/초)이 부하 뒤보다 낮았다(원인 미분리).
r3 의 product backlog 는 최대 6,035건이었다.

### 취소 기전 (코드로 확인)

부하 스크립트(`loadtest/scripts/d002bc-contention.js`)는 결제를 부르지 않아 주문이 PENDING 에 머문다.
`OrderTimeoutScheduler.cancelUnconfirmedReservations`(60초 주기)가 `reservation_confirmed_at IS NULL` 이고 `ordered_at` 이
5분(하드코딩) 지난 PENDING 주문을 취소한다. 결제 요청 만료(15분)는 PAYMENT_REQUESTED 대상이고 예약 lease(30m)는 배수보다
길다. 따라서 사가 왕복이 5분을 넘은 주문이 취소됐고, p99 가 5분 아래였던 r1 만 0건이다.

### ShedLock 최소 보존의 역할 (코드로 확인)

`ShedLockConfig` 는 `JdbcTemplateLockProvider.usingDbTime()` 이고 MySQL 에서는 획득과 해제가 모두 `UTC_TIMESTAMP(3)` 를 쓴다.
해제는 `lock_until = IF(locked_at + lockAtLeastFor > now, locked_at + lockAtLeastFor, now)`(`WHERE name AND locked_by`)다.

- 노드 간 시계 차이를 보정하는 역할은 없다. 시각이 전부 DB 시각이다.
- 정합성 역할도 없다. 실행 중에는 `lock_until = now + lockAtMostFor` 가 다른 replica 를 막고, 이벤트별 저장은 해제 전에 커밋된다.
- 남는 역할은 **여러 replica 의 실행을 그 간격에 한 번으로 묶는 빈도 상한**이다. Phase 2 의 규칙은 "주기보다 약간 짧게"
  (5초 주기에 4초)였다. 주기만 내리면(D-075 delay-only, D-078 overlay) 같은 인스턴스의 다음 실행이 자기 lock 에 막힌다.
- ShedLock 은 lock 설정을 **실행마다** 애노테이션에서 만든다. 최소 보존이 최대 보존보다 길면 실행마다 예외이고 부팅은 성공한다.

### 목표 (사용자 결정 2026-10-25)

재측정 부하(170 주문/초, 60초)에서 만료 취소 0, 배수가 예약 미확정 만료(5분)보다 충분히 짧을 것. 이벤트마다 동기로 ack 를
확인하는 발행 의미는 유지한다.

## Decision

1. **order·product·payment** 의 base `application.yml` 에 `app.outbox.polling.delay: 500ms`, `lock-at-least-for: 400ms`,
   `batch-size: 500` 을 둔다. `max-retry`·`publish-timeout`·`cycle-timeout` 은 그대로다.
2. ShedLock 최소 보존을 `app.outbox.polling.lock-at-least-for` 로 뺀다. 규칙은 원래대로 "주기보다 짧게"이고 400ms 는 주기의
   80%(종전 4s/5s 와 같은 비율)다. `lockAtMostFor` 는 5분 그대로 코드 상수로 둔다.
3. 주기와 최소 보존에는 인라인 기본값을 두지 않는다. `OutboxPollingProperties`(`@Validated`)가 부팅 시 검사한다:
   두 값 필수, 주기 0 초과, 0 이상이고 주기보다 짧은 최소 보존, 최대 보존(5분)보다 짧은 최소 보존. 어기면 부팅이 실패한다.
4. **notification** 은 replay 재발행 전용이라 값을 바꾸지 않는다. 인라인 기본값이 사라지므로 5s·4s·100 을 base yml 에 명시한다.
5. 이벤트마다 broker ack 를 기다린 뒤 PUBLISHED 를 저장하는 순차 발행 의미는 바꾸지 않는다.
6. 측정 overlay `gke-d078-contention` 의 order outbox env 덮어쓰기를 지운다. 재측정은 base 기본값을 잰다.

### 기대 효과 (추정이며 측정이 아니다)

- 서비스당 처리율은 순차 비용이 정한다. GKE 이벤트당 약 12~13ms(근거 ①③)면 약 80건/초다. 500건 한 실행이 약 6초라
  backlog 가 있는 동안 주기 500ms 와 보존 400ms 는 처리율에 들어가지 않는다.
- 170건/초 × 60초 = 10,200건이다. order 가 부하 중에도 80건/초를 내면 부하 종료 시 backlog 약 5,400건이 약 70초에 빠진다.
  D-078 처럼 부하 중 order 발행이 떨어지면 부하 종료 시 약 9,000건이고 약 2분이 걸린다.
- product 가 order 와 같은 속도로 발행하면 product backlog 는 쌓이지 않는다. 사가 왕복 최대는 order 대기(약 1~2분)에 한 홉을
  더한 값으로, 5분 컷오프의 절반 아래로 추정한다.
- 저부하의 한 홉 지연은 최대 약 5초(주기 5초, 보존 4초)에서 주기 0.5초와 실행 시간의 합으로 준다.

## Alternatives Considered

### Alternative A: 수용 (기본값 유지)
- **장점**: 변경이 없다.
- **단점**: 서비스당 20건/초가 그대로라 재측정 부하에서 수천 건 취소가 재현된다. 취소 경로가 경합 측정(⑤)의 충돌 수와 배수
  시간을 오염시킨다(D-078 r3 의 `order.cancelled` 낙관락 실패 11건).
- **기각 사유**: 목표(만료 취소 0)를 만족할 수 없다.

### Alternative B: 비동기 pipelining (여러 건을 보내고 ack 콜백에서 저장)
- **장점**: 발행 대기를 겹친다. 로컬 producer-only 대조에서 window 32 가 window 1 보다 약 10배 빨랐다.
- **단점**: 로컬 비용의 대부분은 발행이 아니라 이벤트별 저장이었다(이벤트당 save 약 5ms, 발행 약 1ms). GKE 의 분해는 측정하지
  않았다. ack 와 저장의 순서, 실패 이벤트 뒤의 성공 ack, 늦은 ack, 중단 시 상태, 중복 범위가 바뀐다. ADR-0020 D1(at-least-once)과
  ADR-0022 의 drain·`PUBLISH_UNKNOWN` 판정 전제를 다시 정해야 한다.
- **기각 사유**: 설정 상향으로 목표를 맞출 수 있다는 추정이 먼저다. 재측정이 반증하면(재검토 조건 1) 별도 ADR 로 다룬다.

### Alternative C: 저장 묶기 (여러 이벤트의 PUBLISHED 를 한 트랜잭션·한 문장으로)
- **장점**: 로컬에서 가장 큰 비용(이벤트별 save)을 줄인다.
- **단점**: 커밋 실패 한 번이 묶음 전체를 PENDING 으로 되돌려 중복 발행 범위가 배치 단위로 커진다. 묶음 도중 중단되면 이미 ack 된
  이벤트의 상태가 남지 않는다.
- **기각 사유**: B 와 같다.

### Alternative D: 예약 미확정 만료(5분) 연장
- **장점**: 한 줄로 이번 측정의 취소가 사라진다.
- **단점**: 사가 왕복이 수 분인 상태를 그대로 숨긴다. 예약 결과 유실 같은 진짜 미도착을 감지하는 시간이 늦어진다. 결제 대기·lease
  만료와의 순서를 다시 따져야 한다.
- **기각 사유**: 원인이 아니라 증상을 옮긴다.

### Alternative E: 배치만 상향 (주기 5초 유지)
- **장점**: 값 하나만 바꾼다.
- **단점**: 500건 실행 약 6초 뒤에 주기 5초가 매번 더해져 약 45건/초(추정)에 머문다. 저부하 지연도 그대로다.
- **기각 사유**: 목표 처리율에 못 미친다.

### Alternative F: 주기만 하향 (배치 100, 보존 4초 유지)
- **장점**: 값 하나만 바꾼다.
- **단점**: 보존 4초가 실행 주기가 되어 100건/4초 수준이다. D-075 delay-only(9.408초, skip 12회)가 이 경우다.
- **기각 사유**: 주기 변경이 효과를 내지 못한다.

### Alternative G: 최소 보존 0
- **장점**: 규칙이 단순하다.
- **단점**: 여러 replica 의 빈 실행이 replica 수만큼 늘어난다(주기 500ms 면 replica 당 초당 2회). 원래 규칙(주기당 한 번)을 버린다.
- **기각 사유**: 400ms 와 처리율·지연 차이가 없다. 원래 규칙을 유지하는 편이 놀람이 적다.

### Alternative H: notification 도 같은 값
- **장점**: 4서비스 값이 같다.
- **단점**: 운영자가 여는 replay 양만 흐르는 outbox 에 측정 근거 없는 변경이다.
- **기각 사유**: 근거가 생기면(재검토 조건 5) 맞춘다.

## Consequences

### 긍정적 영향
- 서비스당 발행 상한이 설정(20건/초)이 아니라 순차 비용(약 80건/초, 추정)으로 정해진다.
- 주기와 최소 보존이 같은 자리에서 함께 바뀌고, 어긋나면 부팅이 실패한다. D-075 의 "주기를 내렸는데 효과가 없다" 가 재발하지 않는다.
- 키가 빠지면 부팅이 실패한다. D-002 처럼 인라인 기본값이 모르는 사이 쓰이는 일이 없다.
- 측정 overlay 가 base 와 같은 조건을 잰다.

### 부정적 영향 / 트레이드오프
- 유휴 폴링이 10배로 는다. 서비스마다 초당 약 2회(lock 획득 UPDATE, PENDING 인덱스 범위 조회, lock 해제 UPDATE)이고 다른 replica 의
  실패한 획득 시도가 더해진다. 결과가 비면 수 ms 다.
- 실패가 즉시 나는 이벤트(예: replay 헤더 계약 위반)는 재시도 5회를 약 25초가 아니라 약 2.5초에 소진하고 FAILED(자동 재시도 없음,
  `peekcart-outbox-failed` 알림)가 된다. broker 장애처럼 이벤트당 수 초가 걸리는 실패는 실행이 길어 주기의 영향이 작다.
- DB 장애 때 poller 오류 로그가 주기마다 남으므로 10배로 는다.
- 배치 500 은 한 번에 500행(payload 포함)을 메모리에 올린다. 커넥션은 문장마다 잡고 놓으므로 배치 동안 쥐지 않고, 이벤트당 저장
  횟수도 같다. 조회는 `idx_outbox_status_created` 범위 스캔이다.
- 최악 실행 시간은 배치와 무관하다. `cycle-timeout`(4분)을 이벤트 시작 전에 검사하므로 마지막 이벤트 하나의 최장 시간만 더해진다.
  order·payment(`max.block.ms` 3초, `delivery.timeout.ms` 5초, `publish-timeout` 6초)는 약 4분 10초로 `lockAtMostFor` 5분 안이다.
  product·notification 은 producer timeout 을 덮지 않아 `max.block.ms` 기본 60초라, 메타데이터를 못 받는 장애에서는 마지막
  이벤트가 60초를 더 써 5분에 닿을 수 있다. 이 결정 전부터 있던 한계이며 이 결정이 바꾸지 않는다.
- 부하 중 order 발행률 저하(근거 ③)는 설정이 아니라 같은 Pod 의 자원 공유로 보이며 이 결정이 고치지 않는다. 기대 효과의 오차 요인이다.

### 후속 결정에 미치는 영향
- ADR-0031 D2 의 E2E 예약 부재 창 30초는 5초 주기 6회에서 유도했다. 새 주기에서는 60회분이라 창은 유효하고(검출 범위만 넓다) 유지한다.
- DLQ publication reconciler(`deadLetterPublicationReconcileJob`, 5s·4s)는 "outbox poller 와 같은 주기 축"에 둔다고 적혀 있으나
  바꾸지 않는다. ADR-0022 ⓐ' 가 그 5초 지연을 fail-closed 로 받아들였고, 3서비스에서는 그 주석의 등가가 더는 성립하지 않는다.
- D-078 재측정이 이 기본값을 잰다. 측정 overlay 는 order outbox 를 덮지 않는다.

## 재검토 조건

1. D-078 재측정(170 주문/초, 60초)에서 만료 취소가 1건 이상이거나, 사가 왕복 p99 가 4분(5분 컷오프의 80%)을 넘거나, product
   발행률이 order 의 부하 뒤 발행률보다 낮아 product backlog 가 쌓이면 비동기 pipelining(B) 또는 저장 묶기(C)를 별도 ADR 로
   설계한다. ADR-0012·0020·0022 의 발행·커밋 실패 의미를 함께 다시 정한다.
2. 부하 중 order 발행률 저하가 지배적이면 원인(커넥션 대기, CPU 경합)부터 분리한다. 발행 방식보다 자원 경계가 먼저일 수 있다.
3. 짧은 일시 장애로 `peekcart-outbox-failed` 알림이 늘면 재시도 간격을 다시 본다.
4. 유휴 폴링이 DB 부하로 관측되면(replica 증가 등) 주기와 최소 보존을 다시 정한다.
5. 대량 replay 로 notification drain(ADR-0022 ⓐ)이 길어지면 notification 값을 3서비스와 맞춘다.

## References

- `peekcart-common-messaging/src/main/java/com/peekcart/global/outbox/OutboxPollingScheduler.java`·`OutboxPollingProperties.java`·`OutboxPollingService.java`
- `peekcart-common-messaging/src/main/java/com/peekcart/global/config/ShedLockConfig.java` — DB 시각 LockProvider
- order·product·payment·notification `src/main/resources/application.yml` `app.outbox.polling`
- `k8s/overlays/gke-d078-contention/kustomization.yml`
- `docs/plans/D-075-outbox-throughput.md` — 이 결정의 계획서와 코드 검증 표
- `docs/plans/done/D-075.md`, `docs/plans/evidence/D-075/README.md` — 로컬 분해
- `docs/progress/evidence/d002bc-gke-20260917.md` §②, `docs/progress/evidence/d078-gke-20261024.md` §S3·§읽는 법 ⑤,
  `loadtest/reports/2026-10-24/session-issues.md` #6
- ADR-0007 — 동작 정책은 base yml 소유
- ADR-0012·ADR-0020 D1·ADR-0022 — 발행 보장과 replay drain (Alternative B·C 의 전제)
- ADR-0031 D2 — E2E 부재 창 유도
