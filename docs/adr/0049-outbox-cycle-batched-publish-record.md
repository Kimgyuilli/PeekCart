# ADR-0049: outbox 발행 상태를 사이클 끝에 한 문장으로 기록한다

- **Status**: Accepted
- **Date**: 2026-11-19
- **Deciders**: 프로젝트 오너 (2026-11-19 D-119 범위 결정)
- **관련 Phase**: Phase 6

## Context

`OutboxPollingService.pollAndPublish` 는 PENDING 을 `batch-size` 만큼 읽고 이벤트마다 `send().get()` 으로 broker ack 를 기다린 뒤
`save()` 로 PUBLISHED 를 저장했다. 서비스 메서드에 트랜잭션이 없고 엔티티가 detached 라 save 는 merge 다. 이벤트 한 건마다
커넥션 획득 한 번과 트랜잭션 하나(SELECT·UPDATE·COMMIT)를 쓴다.

D-110 로컬 probe(`docs/evidence/D-110/`)에서 부하 중 order poller 의 발행률은 같은 반복의 배수 발행률의 0.03(풀 10)~0.13(풀 64)이었다.
풀을 늘리면 획득 대기는 사라지지만 점유가 늘어(13 → 61ms) 회복하지 않았다. poller 는 포화된 공유 자원을 64개 부하 스레드와
같은 몫으로 나눠 쓰는 순차 클라이언트이고, 획득 1회 비용(대기 + 점유)이 곧 이벤트 1건의 비용이다. 풀 크기나 poller 전용 풀은
대기 자리만 옮긴다. 회복하려면 이벤트당 트랜잭션 수를 줄여야 한다.

ADR-0045 는 이 방식(Alternative C 저장 묶기)을 "설정 상향으로 목표를 맞출 수 있다는 추정이 먼저" 라는 이유로 기각했고, 재검토 조건 1에서
별도 ADR 로 다루되 ADR-0012·0020·0022 의 발행·커밋 실패 의미를 함께 다시 정하라고 했다. 이 ADR 이 그 결정이다.

점유 증가가 MySQL 서버 안(커밋·잠금)인지 앱 CPU 인지(D-110 J4)는 로컬 MySQL 이 Testcontainers 기본 설정이라 판정하지 못했다.
어느 쪽이든 이벤트당 획득 횟수를 줄이는 것은 poller 의 몫을 늘린다. J4 는 이 결정을 고를지가 아니라 이것만으로 충분한지를 가른다.

로컬 효과는 결과 전에 고정한 규칙(D-119 계획서 V4)으로 판정했다. 같은 세션에서 풀 10, 부하 64 스레드일 때 부하 중 발행/s ÷ 주문/s 가
기준 코드 0.017 에서 이 결정 적용 후 0.986 이 됐다(`docs/evidence/D-119/README.md`). 풀은 그대로 포화돼 있었다(pending 평균 53).

## Decision

**poller 는 한 사이클에 broker ack 받은 이벤트의 id 를 모아, 루프를 나올 때 한 문장으로 PUBLISHED 를 기록한다.
보장 수준(publication at-least-once + 소비 멱등)은 바꾸지 않고, 중복 발행 범위가 이벤트 1건에서 사이클 1회로 커지는 것을 받아들인다.**

### D1. 기록 단위와 문장
- `UPDATE outbox_events SET status='PUBLISHED', published_at=:now WHERE id IN (:ids)` 를 사이클 끝에 한 번 실행한다
  (`OutboxEventJpaRepository.markPublished`, 자기 트랜잭션). 커넥션은 이 문장 동안만 잡는다. 발행 중에는 쥐지 않는다.
- 상태 조건을 두지 않는다. 이전의 merge 도 행 상태를 보지 않고 덮었다. ack 를 받았으면 PUBLISHED 가 사실이다.
- 루프를 어떻게 나오든(정상, `cycle-timeout`, interrupt, 실패 경로 예외) ack 받은 것이 있으면 기록한다. 실패 경로 예외로 나오는
  경우 기록 예외는 원래 예외의 suppressed 로 붙인다.
- 바뀐 행 수가 id 수보다 적으면 warn 로그만 남긴다. ADR-0022 가 허용하는 outbox 행 강제 삭제 뒤에 생길 수 있고, 발행은 이미 끝났다.

### D2. 발행 실패 경로는 그대로 이벤트마다 저장한다
`retryCount` 증가와 소진 시 `FAILED`·Slack 알림은 이벤트마다 저장한다. 실패는 드물고, 묶으면 알림과 저장의 순서를 다시 정해야 한다.

### D3. 실패 의미 (ADR-0020 D1 refine)
- 기록 문장이 실패하거나 프로세스가 ack 와 기록 사이에서 죽으면 그 사이클에 ack 받은 이벤트 **전부**가 PENDING 으로 남아 다음 사이클에
  다시 발행된다. 중복 범위의 상한은 1건에서 `batch-size`(현재 500)로 커진다.
- 기록 실패는 삼키지 않고 사이클 밖으로 던진다. 삼키면 PENDING 인 채 실패가 숨는다.
- ADR-0020 D1 의 보장 문구("at-least-once, 중복은 일어날 수 있다, 소비 효과 1회는 `eventId` + `processed_events`")는 그대로다.
  D1 은 중복 수에 상한을 두지 않으므로 이 변경은 D1 의 범위 안이다. D1 이 근거로 든 crash window(ack 뒤 별도 저장)가 한 건에서
  한 사이클로 넓어진다.

### D4. `published_at` 은 PUBLISHED 를 기록한 시각이다
ack 시각이 아니다. 차이는 한 사이클 길이 이하다. 이 값을 읽는 쪽은 다음과 같다.
- retention(`published_at < cutoff`): 보존이 그만큼 길어진다.
- 측정 SQL `loadtest/sql/d078-outbox-latency.sql`: 발행 대기가 한 사이클 이하로 길게 잡힌다. 과거 측정과 비교할 때 전제로 적는다.

### D5. 새 설정 키를 두지 않는다
묶음 크기는 `batch-size` 를 따른다. 따로 두면 동작 정책 키가 4서비스 base yml 에 하나 더 생기고(ADR-0007·ADR-0045 의 키 누락 검사 포함)
얻는 것은 중복 범위 축소뿐이다.

### 다른 소비자에 미치는 영향
| 소비자 | 영향 | 방향 |
|---|---|---|
| reconciler `findStatusById`(ADR-0020 D6-4) | replay 행이 PUBLISHED 로 보이는 시점이 한 사이클 늦다 | 원장 종착이 늦어진다(fail-closed) |
| drain ⓐ `record_kind='REPLAY' AND status='PENDING'` 0건(ADR-0022 D2) | 한 사이클 늦게 0 이 된다 | 게이트가 늦게 열린다(fail-closed) |
| ShedLock `lockAtMostFor` 5분 | 기록이 루프 뒤에 한 문장 더해진다. 지금도 마지막 save 가 같은 자리에 있었다 | 변화 없음 |
| `outbox.publish` 타이머 | ack 기준 그대로다. DB PUBLISHED 수와는 한 사이클 이하로 어긋난다 | 관측 해석만 바뀐다 |

## Alternatives Considered

### Alternative A: N 건마다 기록
- **장점**: 중복 범위가 N 으로 줄고 PUBLISHED 가 덜 늦다.
- **단점**: 상수(또는 설정 키) 하나와 경계 처리가 늘어난다. 정상 사이클이 1초 안팎이라 얻는 것이 적다.
- **기각 사유**: 중복은 계약 안이고 소비가 멱등이다. 중복 범위를 줄일 근거(재발행 폭증 관측)가 생기면 다시 본다.

### Alternative B: 한 트랜잭션 안에서 이벤트별 UPDATE(saveAll)
- **장점**: `published_at` 이 ack 시각을 유지한다. 커밋은 한 번이다.
- **단점**: 문장 왕복이 이벤트 수만큼 남아 커넥션 점유가 길어진다. 포화 상태에서 점유가 늘어나는 것이 D-110 의 관측이다.
- **기각 사유**: 줄이려는 비용(획득 + 점유)의 절반만 줄인다.

### Alternative C: 비동기 pipelining (ADR-0045 Alternative B)
- **장점**: 발행 대기를 겹친다.
- **단점**: 저장 횟수를 줄이지 않는다. ack 와 저장 순서, 늦은 ack 처리가 바뀐다.
- **기각 사유**: 이 병목의 수정안이 아니다(D-110 J3). 저장을 묶은 뒤 순차 send 가 다음 병목이면 그때 다룬다(재검토 조건 2).

### Alternative D: 용량 확보(풀 상향, poller 전용 풀, MySQL 상향)
- **장점**: 코드 변경이 없다.
- **단점**: 풀은 대기 자리만 옮긴다(D-110: 풀 64 에서 대기 0.9ms, 점유 61ms, 발행비 0.13). MySQL 상향은 과금 환경 결정이다.
- **기각 사유**: 단독 수정안이 아니다. GKE J4 가 DB 쪽으로 나오면 이 결정과 함께 검토한다(재검토 조건 1).

## Consequences

### 긍정적 영향
- poller 의 커넥션 획득이 이벤트 수가 아니라 사이클 수에 비례한다. 포화된 공유 자원에서 poller 의 몫이 부하 스레드 하나와 같은 수준으로
  떨어지던 구조가 풀린다.
- 4서비스(order·product·payment·notification)가 같은 공유 poller 를 쓰므로 함께 적용된다.

### 부정적 영향 / 트레이드오프
- 중복 발행 범위가 최대 `batch-size` 다. 소비자는 `processed_events` 로 효과를 접지만 broker 트래픽과 소비 처리량은 그만큼 더 쓴다.
- `published_at` 이 ack 시각보다 한 사이클 이하로 늦다. 과거 측정과 직접 비교할 수 없다.
- reconciler·drain 게이트가 한 사이클 늦게 움직인다(fail-closed).

### 후속 결정에 미치는 영향
- ADR-0045 의 Alternative C 기각과 "이벤트당 저장 횟수도 같다" 서술을 이 ADR 이 대체한다(ADR-0045 Partially Superseded).
- ADR-0020 D1 은 보장 문구가 같아 상태를 바꾸지 않는다. crash window 의 폭만 이 ADR D3 이 refine 한다.

## 재검토 조건

1. GKE 과금 세션에서 J4(부하 중 점유 증가가 MySQL 서버 안인지 앱 CPU 인지)를 판정한다. 이 결정 뒤에도 부하 중 order 발행률이 수신을
   따라가지 못하고 원인이 DB 쪽이면 용량(Alternative D)을 함께 본다.
2. 로컬 또는 GKE 에서 저장을 묶은 뒤 남는 병목이 순차 send(`send().get()`)이면 pipelining(Alternative C)을 별도 ADR 로 다룬다.
3. 재발행 폭증(같은 `eventId` 의 중복 소비 skip 이 사이클 단위로 반복)이 관측되면 N 건마다 기록(Alternative A)을 다시 본다.

## References

- `peekcart-common-messaging/src/main/java/com/peekcart/global/outbox/OutboxPollingService.java`·`OutboxEventJpaRepository.java`
- `docs/plans/D-119.md` — 계획서와 코드 검증 표, 로컬 판정 규칙
- `docs/evidence/D-110/README.md` — 부하 중 발행 저하의 구조 분석
- `docs/evidence/D-119/README.md` — 이 결정의 로컬 효과 측정
- ADR-0012, ADR-0020 D1·D6-4, ADR-0022 D2 — 발행 보장, reconciler, drain
- ADR-0045 — 주기·배치 기본값, Alternative C, 재검토 조건 1
