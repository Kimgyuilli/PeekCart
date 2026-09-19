# ADR-0025: 재고 동시성 제어 — 분산 락 제거, `@Version` + jitter 재시도

- **Status**: Accepted
- **Date**: 2026-09-18
- **Deciders**: Kimgyuilli
- **관련 Phase**: Phase 4

## Context

재고 차감 경로에는 Redis 분산 락(`InventoryLockFacade`)과 JPA 낙관적 락(`Inventory.@Version`)이
**둘 다** 걸려 있었다. 전자는 "락 획득 → 트랜잭션 → 커밋 → 락 해제" 순서를 강제한다고 javadoc 에
적어두었으나, 실제로는 그 순서가 성립하지 않는다.

**측정이 먼저 있었다.** D-002 측정 세션([#119]/[#120]) GKE replicas=3 동일-상품 경합군에서
`PRD-004`(락 획득 실패) **0** ↔ `OptimisticLockingFailureException` **7** → DLQ 7 이 나왔다.
락은 항상 획득되는데 커밋은 충돌했고, 오버셀링 0 을 지킨 것은 `@Version` 이었다. 보류 항목 L-007
("주문 생성 경로 '락 ⊃ 트랜잭션' 불변식")은 이 측정으로 처분됐는데, 처분 사유가 "해소"가 아니라
**"표면 이동 후 역전"** 이었다 — 재고 차감은 `OrderCommandService` 에서 Product 의 예약 Saga
consumer 로 옮겨갔고(ADR-0012 D3), 옮겨간 자리에서는 불변식이 **반대로** 성립한다.

착수 전 코드 검증과 재현 테스트(계획서 `task-d025-inventory-lock-boundary.md` §2·§4-A)가
다음을 확정했다.

1. **락 구간에 쓰기가 없다.** `StockReservationConsumer`(`@Transactional`) 아래
   `StockReservationService` · `InventoryService` 가 전부 REQUIRED 로 **참여**하므로 물리
   트랜잭션은 하나다. facade 의 `finally { unlock }` 은 그 안에서 돌고, `InventoryService`
   에는 `save`/`flush` 가 없어 UPDATE 는 커밋 flush 에 나간다. 즉 락이 감싼 것은 **읽기뿐**이다.
   (다품목 주문은 auto-flush 로 item N 의 UPDATE 가 item N+1 의 락 아래 나갈 수 있다 — 양상만 다르고
   역시 틀렸다.)
2. **락은 재고 변경 3경로 중 1개만 덮는다.** `restoreStock`(취소·lease sweeper 경로)과
   all-or-nothing 선검사 `hasSufficientStock` 은 락 없이 `InventoryService` 를 직접 호출한다.
   sweeper 는 별도 스케줄러 스레드로 consumer 와 동시에 돈다(ShedLock 은 잡 중복만 막는다).
3. **lease 만료가 락을 조용히 무력화한다.** lease 5초 고정이고, 만료 후의 `unlock` 은
   `isHeldByCurrentThread()` 가 false 라 예외도 로그도 지표도 없이 통과한다.
4. **`tryLock` 은 Redis 장애 시 `true` 를 반환한다**(의도된 fallback). 그래서 `PRD-004`=0 은
   "경합 없음"과 "Redis 가 죽어 락을 건너뜀"을 구분하지 못한다.
5. **DLQ 전량 소진의 원인은 jitter 부재였다.** `ExceptionClassifier.defaultFatalExceptionsList()`
   를 바이트코드로 확인한 결과 낙관락 예외는 fatal 목록에 없다 → 재시도는 실제로 일어났다.
   그런데 `FixedSequenceBackOff(1s, 5s, 30s)` 에는 무작위 성분이 전혀 없어, 동시에 충돌한 소비자들이
   **같은 순간에 함께 깨어나 다시 충돌**한다. 재시도가 경합을 흩뜨리지 않고 보존한 것이다.

기존 테스트가 이 결함을 잡지 못한 이유도 확인됐다. `InventoryConcurrencyTest` 는 facade 를
**바깥 트랜잭션 없이** 직접 호출하는데, 그 구성에서만 javadoc 의 순서가 실제로 성립한다 —
불변식이 성립하는 유일한 구성만 시험하고 있었다.

ADR-0012 D3 는 예약 saga 경계는 규정했으나 **재고 동시성 제어 수단은 규정하지 않았다.**
이 ADR 은 기존 결정을 뒤집는 것이 아니라 비어 있던 그 칸을 채운다.

## Decision

**재고 동시성 제어를 `Inventory.@Version`(낙관적 락) 단일 수단으로 통일하고, Redis 분산 락을
재고 경로에서 제거한다. 낙관락 충돌은 jitter 를 가진 bounded 재시도로 흡수하고, 소진분은 DLQ 로 보낸다.**

구체 계약:

- **(D1) 수단 단일화** — `InventoryLockFacade` 를 삭제한다. 재고를 바꾸는 **3경로 전부**
  (`decreaseStock` · `restoreStock` · 선검사 `hasSufficientStock`)가 같은 수단(`@Version` + 트랜잭션
  원자성) 아래 놓인다. 선검사와 차감 사이의 창은 락이 아니라 **트랜잭션 롤백**이 닫는다 — 차감 중
  재고가 부족해지면 `PRD-002` 가 전파돼 트랜잭션 전체가 롤백되고, 재시도 시 선검사가 막는다.
  all-or-nothing 은 트랜잭션 원자성이 보장하지, 락이 보장하던 것이 아니다.
- **(D2) 재시도에 jitter 를 넣는다** — 충돌한 소비자들이 서로 다른 시각에 깨어나야 재시도가
  경합을 실제로 푼다. jitter 없는 고정 backoff 는 재시도 횟수를 늘려도 같은 충돌을 반복한다(Context 5).
  이는 **재고 전용이 아니라 `product-service` 공용 error handler 의 성질**로 적용한다 — lockstep 은
  낙관락에만 생기는 문제가 아니다.
- **(D3) 소진 후 처분은 DLQ 유지** — 보상으로 바꾸지 않는다. 낙관락 충돌은 재고가 실제로 부족한
  상황이 아니라 **쓰기 경합**이고, 재시도로 수렴하는 것이 정상이다. 수렴하지 않는 것은 그 자체로
  조사 대상이므로 DLQ + 기존 Slack 알림이 맞는 처분이다. DLQ replay 계약(ADR-0020/0021/0022)이
  그대로 적용된다.
- **(D4) `DistributedLockManager` 는 `:common` 에 보존한다** — 재고 경로에서만 뗀다. 삭제하지 않는
  이유는 이 ADR 이 판단한 것이 "분산 락 일반이 틀렸다"가 아니라 **"이 경로에서는 기여가 없었다"**
  이기 때문이다. 다른 서비스가 나중에 쓸 때 다시 만들 이유가 없다. 다만 재고 경로에서는 **미사용
  상태**가 된다.
- **(D4-1) `PRD-004` 는 존치한다.** 이 코드는 원래 **두 가지**에 쓰이고 있었다 — 분산 락 획득 실패
  (`InventoryLockFacade`)와 **낙관적 락 충돌 → HTTP 409**(`GlobalExceptionHandler`
  `OptimisticLockingFailureException` 핸들러). 앞의 용법은 D1 로 사라지지만 **뒤의 용법이 남고**,
  메시지("재고 변경 충돌이 발생했습니다. 다시 시도해주세요.")도 그쪽에 맞다. 오히려 이 ADR 이
  낙관락을 유일한 수단으로 정하면서 `PRD-004` 의 의미가 **하나로 좁혀진다**.
- **(D5) Redis 장애 fallback(`tryLock` → `true`)의 재고 경로 영향은 자연 소멸한다.** 재고가 더는
  락을 쓰지 않으므로 Redis 장애가 재고 정합성에 영향을 주지 않는다. Redis 는 재고 경로에서 **캐시
  전용**이 된다.
- **(D6) sweeper ↔ consumer 경합의 처분** — 별도 장치를 두지 않는다. 회수 권한은 이미
  `RESERVED → RELEASED` 원자 CAS 1건 성공으로만 부여되고(`markReleasedIfReserved`), 재고 복구는
  그 CAS 를 통과한 쪽만 수행한다. 이중 복구는 CAS 가 막고, 복구 자체의 쓰기 경합은 `@Version` 이
  막는다. **락을 씌워도 CAS 보다 더 강한 보장을 주지 못한다.**

## Alternatives Considered

### Alternative A: 락을 트랜잭션 밖으로 올린다 (경계 수정)
consumer 계층에서 productId 정렬 순으로 다중 락을 선획득한 뒤 트랜잭션을 열고, 커밋 후 해제한다.

- **장점**: javadoc 이 원래 약속한 설계를 실제로 성립시킨다. 충돌 자체가 발생하지 않아 재시도·DLQ
  churn 이 사라진다.
- **단점**: **lease 만료 창을 상속한다**(Context 3). 트랜잭션이 길어질수록 창이 커지므로 Redisson
  watchdog(`leaseTime=-1`) 또는 "lease > 트랜잭션 상한" 보장이 **필수 부속**이 된다. 다품목 데드락
  회피를 위한 정렬 획득, 3경로 확장까지 더하면 락을 제대로 돌리기 위한 장치가 계속 늘어난다.
  Redis 장애 fallback 의 "조용히 통과"(Context 4)도 그대로 남는다.
- **기각 사유**: 측정이 이 방향을 지지하지 않는다. 락은 **전원이 획득하면서 기여가 0** 이었고
  (`PRD-004`=0, 오버셀링 0 은 `@Version` 이 지킴), 정합성이 이미 다른 수단으로 보장되는 상태에서
  두 번째 수단을 **올바르게 만들기 위해** 부속 장치를 늘리는 것은 비용만 늘린다.

### Alternative C: 비관적 락 (`SELECT … FOR UPDATE`)
`@Lock(PESSIMISTIC_WRITE)` 조회로 교체해 DB 행 락이 커밋까지 유지되게 한다.

- **장점**: "락 ⊃ 트랜잭션"이 **구조적으로** 성립한다 — 순서를 사람이 맞출 필요가 없고, 3경로가
  자연히 같은 보장 아래 놓인다. Redis 의존과 lease 개념이 모두 사라지고 충돌 churn 도 없어진다.
- **단점**: 커넥션·행 락 점유 시간이 트랜잭션 전체로 늘어난다. 이 트랜잭션은 재고 차감뿐 아니라
  예약 원장 저장과 outbox insert 까지 포함하므로 점유 구간이 짧지 않다. 다품목 주문의 데드락 회피를
  위해 productId 정렬 획득이 여전히 필요하다. **부하 하 DB 경합이 미측정**이고, D-002 세션이
  이미 MySQL CPU 를 1차 병목으로 지목한 상태라 DB 쪽 부담을 늘리는 방향이다.
- **기각 사유**: 교과서적으로는 가장 깔끔하나, **현 시스템에서 정합성이 이미 지켜지고 있고**
  (오버셀링 0) 문제는 정합성이 아니라 churn 이다. churn 은 jitter 로 훨씬 싸게 줄일 수 있다.
  DB 를 더 누르는 변경은 그 효과가 부하로 측정된 뒤에 하는 것이 순서다. **기각이 아니라 유보** —
  B 운영 중 충돌률이 허용 범위를 넘으면 C 가 승격 경로다(Consequences 참조).

## Consequences

### 긍정적 영향
- 재고 동시성 제어 수단이 **둘에서 하나로** 줄고, 3경로가 같은 규칙 아래 놓인다. "어느 경로가
  무엇으로 보호되는가"를 설명할 필요가 없어진다.
- 재고 정합성이 **Redis 가용성과 무관**해진다. Redis 장애 fallback 이 만들던 "조용히 락 없이 통과"
  경로가 재고에서 사라진다.
- lease 만료라는 **관측 불가능한 실패 양상**이 재고 경로에서 소멸한다.
- jitter 도입으로 재시도가 실제로 경합을 푼다 — 같은 부하에서 DLQ 유입이 줄어야 한다.
- 삭제되는 코드가 순증 코드보다 많다.

### 부정적 영향 / 트레이드오프
- **충돌 자체는 남는다.** 동일 상품 경합이 심하면 낙관락 충돌 → 재시도가 계속 발생하고, jitter 는
  이를 완화할 뿐 제거하지 않는다. 처리량이 경합도에 반비례한다.
- **소진분은 여전히 DLQ 로 간다.** 재시도 예산(3회)을 넘는 경합이 지속되면 주문이 DLQ 에 쌓이고
  replay 운영이 필요하다.
- **"락 경합"이라는 관측 축이 없어진다.** 락 대기는 실패하기 전에도 관측할 수 있었지만, 낙관락
  충돌은 **실패한 뒤에만** 드러난다. `PRD-004` 자체는 남지만(D4-1) 가리키는 사건이 바뀐다.
- `DistributedLockManager` 가 `:common` 에 **사용처 없이** 남는다(D4). 의도된 보존이지만
  "쓰이지 않는 코드"라는 비용은 실재한다.
- `docs/learning/09-distributed-lock-optimistic-lock.md` 가 설명하는 설계가 더는 코드에 없다.

### 후속 결정에 미치는 영향
- **C 승격 게이트**: 부하 세션에서 동일-상품 경합 시 **낙관락 충돌률 또는 DLQ 유입이 허용 범위를
  넘으면** 비관적 락(Alternative C)으로 승격한다. 그때는 이 ADR 을 supersede 하는 것이 아니라
  **D1 을 부분 무효화**하는 후속 ADR 을 쓴다 — 기각 사유가 "틀렸다"가 아니라 "측정 전이었다"이므로.
- **재고 캐시 경계(D-026)** 와 만난다. `ProductQueryService.getProduct` 가 재고를 매 호출 DB 에서
  읽는 문제는 별건이지만, 읽기 경로의 일관성 수준을 정할 때 이 ADR 의 D1(단일 수단)이 전제가 된다.
- **jitter 는 product-service 공용 error handler 의 성질**이 되므로(D2), 다른 컨슈머의 재시도
  형태도 함께 바뀐다. 이후 서비스별 error handler 를 손댈 때 이 결정을 기준으로 삼는다.

## References

- 계획서: `docs/plans/done/task-d025-inventory-lock-boundary.md` (§2 코드 검증 V1~V10 · §4-A 재현 결과)
- 부채 항목: `docs/TASKS.md` D-025 · 처분된 보류 항목 L-007
- 측정 증적: `docs/progress/evidence/d002bc-gke-20260917.md` ([#119], [#120])
- 선행: ADR-0012 D3 (예약 Saga 경계 — 재고 차감의 현 위치) · ADR-0016 (예약 테이블 모델)
- DLQ 계약: ADR-0020 · ADR-0021 · ADR-0022
- 재현 테스트: `StockReservationLockBoundaryIntegrationTest` ·
  `DistributedLockLeaseExpiryIntegrationTest` · `StockConflictRetryPolicyTest`
- `docs/learning/09-distributed-lock-optimistic-lock.md` (이 결정으로 갱신 대상)
