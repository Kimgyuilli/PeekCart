# 계획 — D-025 재고 락 경계 (분산 락이 커밋을 감싸지 못한다)

> 출처: D-002 측정 세션([#119]/[#120]) 착수 전 코드 검증(V5/V6) + GKE replicas=3 실측.
> 보류 L-007 이 [#120] 에서 **D-025 로 흡수**되며 "재고 차감 retry 정책" 결정도 함께 넘어왔다.
> 순서: **재현 테스트 → ADR-0025 → 구현** (한 계획서, 사용자 확정 2026-09-18).

## 1. 명제 (부정형)

다음 중 **하나라도** 성립하면 미완이다.

- **N1** consumer 경계(바깥 `@Transactional`)에서 락이 직렬화에 실패하는 것이 **재현되지 않는다**
  — 즉 실패를 주입해도 테스트가 그린이다.
- **N2** 락 해제가 커밋보다 먼저 일어난다는 사실이 **타이밍이 아닌 순서 단언**으로 고정되지 않았다.
- **N3** 재고를 바꾸는 경로 중 **락이 덮지 않는 경로**(복구·선검사)가 그대로 남아 있고, 그것이
  의도인지 결함인지 문서에 결정으로 적히지 않았다.
- **N4** 낙관락 충돌의 retry 정책(현재: 공용 `FixedSequenceBackOff` 3회 → DLQ)이 **재고 경합에
  적합한가**가 판정되지 않았다. 특히 실측 7건이 **왜 4회 시도를 전부 소진했는지** 설명되지 않았다.
- **N5** 위 결정들이 ADR-0025 로 기록되지 않았거나, 구현이 그 결정과 다르다.
- **N6** 수정 후에도 `InventoryConcurrencyTest` 가 **불변식이 성립하는 구성만** 시험한다
  (facade 직접 호출 = 바깥 트랜잭션 없음).

## 2. 배경 — 착수 전 코드 검증

| # | 전제 | 확인 결과 | 영향 |
|---|---|---|---|
| **V1** | `InventoryService` 가 REQUIRED 로 consumer 트랜잭션에 참여한다 | **확증.** `StockReservationConsumer:33 @Transactional` → `StockReservationService:32 @Transactional`(클래스) → `InventoryLockFacade:40` → `InventoryService:16 @Transactional`. 전부 기본 REQUIRED → 단일 물리 트랜잭션. `finally { unlock }`(`InventoryLockFacade:43`)은 그 안에서 돈다 | D-025 원문대로 |
| **V2** | 락 구간이 "커밋을 못 감쌀 뿐" 쓰기는 들어 있다 | **반증 — 더 나쁘다.** `InventoryService.decreaseStock` 은 `findByProductId` + `inventory.decrease()`(managed 엔티티 필드 변경)뿐이고 `save`/`flush` 호출이 **없다**(`InventoryRepositoryImpl` 에 flush 경로 없음). 참여 트랜잭션이라 UPDATE 는 커밋 flush 에 나간다 → **락 구간 안에는 읽기만 있고 쓰기는 전부 밖** | 명제가 "커밋 미포함"에서 **"쓰기 미포함"** 으로 강해진다. §1 N2 의 단언 대상도 이것 |
| **V2b** | (V2 의 예외) 다품목 주문은 어떤가 | Hibernate 는 같은 테이블을 건드리는 후속 쿼리 앞에서 auto-flush 한다 → item N 의 UPDATE 가 item N+1 의 `findByProductId` 시점에 나갈 수 있다. 그때 보유 중인 락은 **item N+1 의 락**이다 | 단품목=쓰기 전부 밖 / 다품목=**다른 상품의 락 아래** 쓰기. 둘 다 틀렸고 양상만 다르다 |
| **V3** | 락이 재고 변경 경로를 덮는다 | **부분만.** `InventoryLockFacade` 를 거치는 것은 `decreaseStock` **1개**뿐이다. `StockReservationService:236 restoreStock`(release·sweeper 경로)과 `:74 hasSufficientStock`(all-or-nothing 선검사)은 `inventoryService` 를 **직접** 호출한다 | 범위 확대. 락 설계를 고치든 버리든 **3경로 전부**에 대한 결정이 필요 |
| **V4** | sweeper 는 consumer 와 같은 경합면에 있다 | **그렇다.** `StockReservationLeaseSweeper:34` → `sweepExpiredLeases` → `tryReleaseReserved` → `restoreStock`. ShedLock 은 **잡 중복 실행**만 막고 consumer 와의 경합은 막지 않는다. 별도 스케줄러 스레드 | V3 의 복구 경로가 진짜로 동시 실행된다 |
| **V5** | 락 획득 실패는 `PRD-004` 로 관측된다 | **조건부.** `DistributedLockManager.tryLock:39-42` 는 **Redis 예외 시 `true` 를 반환**한다(의도된 fallback, javadoc 명시). 즉 `PRD-004`=0 은 "경합 없음"과 "Redis 가 죽어 락을 건너뜀"을 **구분하지 못한다** | 실측 `PRD-004`=0 의 해석에 단서가 붙는다. ADR 이 이 fallback 을 유지할지도 결정 대상 |
| **V6** | 락은 트랜잭션 끝까지 유지된다 | **아니다.** `LOCK_LEASE_TIME = 5`(초, `InventoryLockFacade:20`) 고정. 트랜잭션이 5초를 넘기면 lease 만료로 락이 풀리고, 그 뒤 `unlock` 은 `isHeldByCurrentThread()`(`DistributedLockManager:53`)가 false 라 **조용히 no-op** 한다. 오류도 지표도 남지 않는다 | 경계를 바깥으로 옮기는 선택지(A)는 lease 문제를 **상속한다** — 트랜잭션이 길어질수록 창이 커진다 |
| **V7** | `InventoryConcurrencyTest` 가 이 역전을 재현하지 않는다 | **확증, 그리고 false-green 의 형태가 구체적이다.** `:150` 은 바깥 트랜잭션 **없이** `inventoryLockFacade.decreaseStock` 을 직접 부른다. 그 구성에서는 facade 안쪽이 트랜잭션을 열고 닫으므로 javadoc 순서(락→트랜잭션→커밋→해제)가 **실제로 성립**하고 50/50 통과한다 | 지금 그린인 것은 우연이 아니라 **불변식이 성립하는 유일한 구성만 시험**하기 때문이다 |
| **V8** | 낙관락 충돌은 재시도로 흡수된다 | **실측과 안 맞는다.** 공용 핸들러는 `FixedSequenceBackOff(1s, 5s, 30s)`(`ProductKafkaConfig:113`) = 초기 1 + 재시도 3 = **4회 시도**. 롤백 시 `processed_events` 선점 행도 함께 롤백되므로(`IdempotencyChecker:43`+javadoc) 재시도는 깨끗하다. 그런데 실측은 충돌 7 → **DLQ 7**(전량 소진) | **미해명**. 재현 테스트가 답해야 할 질문이고 N4 의 핵심. 정책을 고르기 전에 원인을 알아야 한다 |
| **V9** | `DistributedLockManager` 의 다른 소비자가 있다 | **없다.** 전 모듈 grep 결과 `InventoryLockFacade` **1곳**뿐(테스트 제외) | 선택지 B(락 제거)를 고르면 `DistributedLockManager`·`PRD_004`·`InventoryLockFacade` 가 통째로 고아가 된다. 처분을 명시해야 한다 |
| **V10** | ADR-0012 D3 가 락 방식을 규정한다 | 예약 saga 경계는 규정하나 **재고 동시성 제어 수단은 규정하지 않는다.** 락/낙관락 선택은 미결로 남아 있었고, L-007 이 그 미결의 이름이었다 | ADR-0025 는 기존 결정을 **뒤집는** 게 아니라 **비어 있던 칸을 채운다** → `Accepted` 신규, 무효화 대상 없음 |

### 역의존 스윕 (B1 — 구조 변경은 아니나 선택지 B 가 컴포넌트를 없애므로 수행)

| 대상 | 인바운드 참조 | 처분 |
|---|---|---|
| `InventoryLockFacade` | main: `StockReservationService:38,84` 1곳 / test: `InventoryLockFacadeTest`·`InventoryConcurrencyTest:54,150`·`StockReservationServiceTest:44`(@Mock)·`StockReservationLeaseSweepTest:43`(@Mock) | A/C: 유지(경계만 이동) · B: 삭제 + 5개 테스트 참조 정리 |
| `DistributedLockManager`(`:common`) | main: `InventoryLockFacade` **단독**(V9) | B 선택 시 `:common` 에서 고아 — 삭제 vs 보존을 ADR 에 적는다(다른 서비스의 향후 사용 가능성) |
| `ErrorCode.PRD_004` | ~~main: `InventoryLockFacade:38` 단독~~ → **정정(구현 중 발견): `GlobalExceptionHandler:76` 도 쓴다**(낙관락 충돌 → 409). 초안 스윕이 `grep ... | head` 로 잘려 이 참조를 놓쳤다. **Layer 1 문서에는 없음**(재확인). 그 외 TASKS·progress·evidence·`docs/learning/09-*` | **존치**(ADR-0025 D4-1) — 락 획득 실패 용법만 사라지고 낙관락 충돌 409 용법이 남는다. **learning 09 는 본문이 틀리게 되므로** P13 대상 |
| `Inventory.@Version` | 어느 선택지에서도 유지 | 실측상 오버셀링 0 을 지킨 주체 — 제거 대상 아님 |

### 범위 변화

- **넓어졌다**: V3/V4 로 대상이 `decreaseStock` 1경로 → **재고 변경 3경로**(차감·복구·선검사) + sweeper 동시성.
- **강해졌다**: V2 로 명제가 "락이 커밋을 못 감싼다" → **"락 구간에 쓰기가 아예 없다"**.
- **새로 붙었다**: V5(Redis fallback 이 `PRD-004`=0 을 오염), V6(lease 5초 만료 → 조용한 no-op),
  V8(**DLQ 7 전량 소진 미해명**).
- **줄지 않았다**: 문서가 주장한 것 중 반증된 것은 없다. V2 만 방향이 같고 정도가 더 나쁘다.

## 3. 작업 항목

### 3-A. 재현 (ADR 선행 — 어느 선택지를 고르든 필요한 증거)

- [x] **P1.** consumer 경계 재현 테스트 `StockReservationLockBoundaryIntegrationTest`(신설,
  `product-service/src/test/.../infrastructure/`). `InventoryConcurrencyTest` 와 달리 **바깥
  `@Transactional` 이 있는 경로**(= `StockReservationService.reserve` 를 consumer 와 같은 전파로)로
  동일 상품에 동시 예약을 건다.
  **단언**: `PRD-004` 0건 **이면서** `ObjectOptimisticLockingFailureException` ≥ 1건.
  — 즉 "락은 전부 획득됐는데 직렬화는 실패했다"를 한 테스트 안에서 동시에 고정한다.
- [x] **P2.** 순서 단언 테스트(타이밍 비의존). `DistributedLockManager` 를 `@MockitoSpyBean` 으로 감싸
  `unlock` 호출 시각과 `TransactionSynchronization.afterCommit` 을 **발생 순서로** 기록하고,
  `unlock` 이 `afterCommit` **이전**임을 단언한다. sleep/await 를 쓰지 않는다.
  → N2. 이 테스트는 수정 후 **반대로 뒤집혀야** 한다(수정의 회귀 가드가 된다).
- [x] **P3.** 락 미적용 경로 고정. `restoreStock`(release 경로)과 `hasSufficientStock` 실행 중
  `tryLock` 이 **한 번도 호출되지 않음**을 spy 로 단언한다 → V3 를 테스트로 박제.
  이것은 "고칠 것"이 아니라 **현재 상태의 기록**이다 — 의도/결함 판정은 ADR(P6)이 한다.
- [x] **P4.** lease 만료 재현. `DistributedLockManager` 를 **직접** 대상으로(프로덕션 상수 변경 없이)
  짧은 lease 로 락을 잡고 만료 후 (a) 다른 스레드가 같은 키를 획득할 수 있고 (b) 원 스레드의
  `unlock` 이 예외 없이 **no-op** 임을 단언한다 → V6.
- [x] **P5.** **V8 해명** — 충돌 7건이 4회 시도를 전부 소진한 이유를 재현으로 좁힌다.
  가설 후보를 각각 배제/확증한다:
  (a) 재시도 간에도 경합 부하가 지속돼 매 시도가 충돌 — backoff 1s/5s/30s 대비 부하 지속시간 대조
  (b) 재시도 대상 예외가 애초에 DLQ 직행 분류 — `DefaultErrorHandler` 기본 non-retryable 목록에
  `ObjectOptimisticLockingFailureException` 또는 그 래핑 예외가 걸리는지 **spring-kafka 소스/동작으로
  직접 확인**(기억으로 적지 않는다)
  (c) 컨테이너 재시도가 트랜잭션 롤백 후 **같은 오프셋 재처리**가 아닌 다른 경로
  → 결론을 §5 에 기록한다. **이 결론 없이 P6 의 retry 결정을 내리지 않는다.**

### 3-B. ADR-0025 (P1~P5 의 결과를 입력으로)

- [x] **P6.** `docs/adr/0025-inventory-concurrency-control.md` 작성 (template 준수, `Accepted`,
  무효화 대상 없음 — V10). 아래 **세 선택지를 트레이드오프와 함께** 기록하고 하나를 고른다.

  | | A — 락을 트랜잭션 밖으로 | B — 락 제거, `@Version` + bounded retry | C — 비관적 락(DB `FOR UPDATE`) |
  |---|---|---|---|
  | 방식 | consumer 계층에서 **productId 정렬** 다중 락 선획득 → 트랜잭션 → 커밋 → 해제 | `InventoryLockFacade` 삭제, 충돌은 재시도로 흡수 | `@Lock(PESSIMISTIC_WRITE)` 조회로 교체 |
  | 불변식 | 명시적으로 성립시킨다 | 불변식 자체를 폐기(낙관적 제어로 전환) | **DB 가 구조적으로 보장** — 행 락이 커밋까지 유지 |
  | 남는 창 | **lease 만료**(V6). 트랜잭션 > lease 면 같은 결함이 재발 → watchdog(leaseTime=-1) 또는 lease > 트랜잭션 상한 보장 필요 | 없음(창 개념 부재). 대신 충돌률이 부하에 비례 | 없음 |
  | Redis 의존 | 유지 + fallback(V5)의 "조용히 통과" 그대로 | **제거**(V9 — `DistributedLockManager` 고아) | 제거 |
  | 복구·선검사 경로(V3) | 함께 락 안에 넣어야 일관 — 범위 증가 | 자연히 동일 정책(`@Version`) 적용 | 자연히 동일 정책(행 락) |
  | 근거/반증 | 실측상 락은 기여 0(`PRD-004`=0, 오버셀링 0 은 `@Version` 이 지킴) | 같은 실측이 **직접 지지** | 실측 없음 — 부하 하 DB 경합 미측정 |
  | 비용 | 다중 락 데드락 회피(정렬)·watchdog·3경로 확장 | retry 정책 확정 + 삭제 후속(V9 표) | 다품목 데드락 순서(정렬 인덱스)·커넥션 점유 시간 증가 |

  ADR 이 **반드시 명시할 것**(비우면 미완 — B4):
  - (i) 재고 변경 **3경로 각각**의 동시성 제어 수단 (V3)
  - (ii) 낙관락 충돌 retry 정책: 재시도 횟수·backoff·**jitter 유무**·소진 후 처분(DLQ vs 보상).
    공용 핸들러를 그대로 쓸지, 재고 경합 전용 분기를 둘지. **P5 결론이 입력**
  - (iii) `tryLock` 의 Redis 장애 fallback(V5)을 유지할지 — 유지 시 `PRD-004`=0 의 해석 한계를
    관측성 쪽에 어떻게 남길지
  - (iv) 선택지 B/C 채택 시 `DistributedLockManager`·`InventoryLockFacade`·`PRD_004` 처분 (V9/B1 표)
  - (v) sweeper ↔ consumer 경합(V4)의 처분

### 3-C. 구현 — **선택지 B 확정** (ADR-0025, 사용자 결정 2026-09-18)

> **P10/P11 은 계획 초안과 달라진다.** 초안은 "facade 직접 호출 구성을 **삭제하지 않고** consumer
> 경계 구성을 추가" 였는데, B 에서는 facade 자체가 사라지므로 그 구성이 **성립하지 않는다**.
> 남길 수 없는 것을 남기겠다고 적어두는 대신 여기서 고쳐 적는다.

- [x] **P7.** `InventoryLockFacade` 를 삭제하고 `StockReservationService:84` 가
  `inventoryService.decreaseStock` 을 직접 호출하게 한다(ADR-0025 D1). `InventoryService` 의
  javadoc 에서 "반드시 `InventoryLockFacade` 를 통해 호출" 규약을 제거한다 — 그 규약이 지켜지지
  않는다는 것이 D-025 의 내용이었다.
- [x] **P8.** 3경로가 같은 수단 아래 놓였음을 **코드에 남긴다**. `StockReservationService.reserve`
  의 선검사(`:74`)와 차감(`:84`) 사이 창을 닫는 것은 락이 아니라 **트랜잭션 롤백**이라는 점을
  주석으로 명시한다(B12 — "검사와 부작용 사이가 열려 있다"의 처분을 말로 때우지 않는다).
  `restoreStock`(`:236`) 경로는 `RESERVED → RELEASED` CAS 가 권한을 주고 `@Version` 이 쓰기를
  막는다(ADR-0025 D6).
- [x] **P9.** jitter 재시도를 배선한다(ADR-0025 D2). `:common` 에 `JitteredSequenceBackOff` 를
  추가하고 **`ProductKafkaConfig.kafkaErrorHandler` 만** 교체한다. 기존 `FixedSequenceBackOff` 는
  **건드리지 않는다** — 5개 서비스 · 9개 배선이 공유하므로 클래스를 바꾸면 D-025 범위 밖 4개
  서비스의 동작이 함께 바뀐다(CLAUDE.md §3). 나머지 배선의 같은 lockstep 은 §5 에 후속으로 남긴다.
- [x] **P10.** `InventoryConcurrencyTest` 의 분산 락 테스트(`:136`
  `concurrentDecrease_distributedLock_preventsOverselling`)를 **consumer 경계 오버셀링 테스트로
  교체**한다. 낙관적 락 테스트(`:83`)는 그대로 둔다 — 그쪽이 이제 유일한 수단의 시험이다.
- [x] **P11.** 재현 테스트를 **수정 후 계약으로 뒤집는다**. P2(순서 단언)는 순서 문제가 아니라
  **부재**가 계약이 되므로 "재고 경로에서 `tryLock`/`unlock` 호출 0" 으로 바꾼다. P1 은 **그대로
  둔다** — B 에서도 충돌은 나고(그게 설계다) 오버셀링 0 과 `@Version` 의 역할은 변하지 않는다.
  P3 은 3경로 전부로 확장한다.
- [x] **P12.** 고아 처분 (V9/B1 표) — `InventoryLockFacade` + `InventoryLockFacadeTest` 삭제,
  `StockReservationServiceTest`·`StockReservationLeaseSweepTest` 의 `@Mock` 참조 제거,
  `ErrorCode.PRD_004` 제거. `DistributedLockManager` 는 **보존**한다(ADR-0025 D4).
- [x] **P13.** 문서 동기화 — `docs/TASKS.md` D-025 행 종결 · `docs/progress/PHASE4.md` 절 추가 ·
  `docs/adr/README.md` 인덱스에 0025 · L-007 흡수분(retry 정책)이 **닫혔음**을
  `phase4-prep-debt-roadmap.md` 에 반영 · **`docs/learning/09-distributed-lock-optimistic-lock.md`**
  — 선택지 B/C 면 이 문서가 설명하는 설계가 더는 코드에 없다. 지우지 말고 **"왜 그렇게 짰고,
  측정이 무엇을 뒤집었는가"로 갱신**한다(learning 레이어의 성격이 이력이므로).

## 4. 검증 방법

전부 **실패를 주입한 뒤 상태로 확인**한다. "존재한다"·"배선됐다"는 검증이 아니다.

| 항목 | 주입하는 실패 | 확인하는 상태 |
|---|---|---|
| P1 | 동일 상품에 동시 예약 N건(바깥 트랜잭션 있는 경로) | DB `inventories.stock` + 예외 집계: `PRD-004`=0 **∧** 낙관락 충돌 ≥1. **수정 전 red 가 아니라 "green 인데 충돌이 잡힌다"가 정상** — 이 테스트는 현상을 고정하는 것이 목적이고, P11 에서 기대값이 바뀐다 |
| P2 | 없음(순서 관찰) | spy 호출 순서 리스트: `unlock` 인덱스 < `afterCommit` 인덱스 |
| P3 | 없음(경로 관찰) | `tryLock` 호출 횟수 = 0 |
| P4 | lease 만료(대기) | (a) 타 스레드 `tryLock` **성공** (b) 원 스레드 `unlock` 무예외·무효과 |
| P5 | 지속 경합 + 각 가설별 조건 분리 | DLQ 토픽 도달 건수 vs 재시도 성공 건수의 **대조**. 가설 (b)면 재시도가 **0회**로 관측돼야 한다 |
| P7~P9 | 동일 상품 동시 예약 + (해당 시) Redis 다운 | 오버셀링 **0** ∧ `inventories.stock` = 초기 − 성공건수 ∧ DLQ 건수가 ADR 이 정한 상한 이하 |
| P8 | 선검사 통과 직후 타 트랜잭션이 재고 소진 | all-or-nothing 위반(부분 차감) **0건** — 위반 시 `stock` 이 음수거나 일부 품목만 감소로 나타난다 |
| P10/P11 | 두 구성(facade 직접 / consumer 경계) 각각 | 구성별로 서로 다른 기대값이 **각각** 단언됨. 한쪽을 지우고 통과시키지 않는다 |
| 전체 | — | `:product-service:test` 그린 + 기존 lint 세트 유지 |

## 4-A. 재현 결과 (P1~P5 실행, 2026-09-18)

전부 그린. `StockReservationLockBoundaryIntegrationTest` 는 **3회 연속** 동일 결과(결정적, flake 아님).

| 항목 | 결과 | 확인된 것 |
|---|---|---|
| P1 | ✅ | 같은 실행 안에서 `PRD-004`=0 **∧** 낙관락 충돌 ≥1 **∧** 오버셀링 0. **배리어가 열렸다** — 락이 커밋까지 유지됐다면 두 번째 스레드가 도달하지 못했을 자리다 |
| P2 | ✅ | `unlock` 인덱스 < `afterCommit` 인덱스. 락 ⊂ 트랜잭션이 **순서 단언**으로 고정(sleep 없음) |
| P3 | ✅ | release 경로에서 `tryLock`·`unlock` 호출 **0회**, 그럼에도 재고는 복구됨 |
| P4 | ✅ | lease 만료 후 타 스레드가 같은 키 획득, 보유자의 `unlock` 은 무예외 no-op, 그 no-op 이 탈취자 락을 풀지도 않음 |
| P5 | ✅ | 아래 §V8 해명 |

### V8 해명 — DLQ 7 전량 소진의 원인 (N4 의 선결 조건, 이제 충족)

- **가설 (b) 반증.** `ExceptionClassifier.defaultFatalExceptionsList()` 를 **바이트코드로 직접 확인**했다
  (`spring-kafka-3.3.14.jar`). 목록은 `DeserializationException` · `MessageConversionException` ·
  `ConversionException` · `MethodArgumentResolutionException` · `NoSuchMethodException` ·
  `ClassCastException` **6개뿐**이고 낙관락 예외도 그 상위 타입도 없다. → **재시도는 실제로 일어났다.**
  이 사실을 `StockConflictRetryPolicyTest` 의 실행 가능한 단언으로 고정했다(라이브러리 업그레이드가
  이 전제를 바꾸면 거기서 먼저 깨진다).
- **가설 (a) 채택 — 메커니즘은 "jitter 부재로 인한 lockstep".** `FixedSequenceBackOff` 에는 무작위
  성분이 전혀 없다(`common/.../FixedSequenceBackOff.java`). 동시에 충돌한 N 개 소비자는 **똑같이**
  1s → 5s → 30s 를 기다렸다가 **같은 순간에 함께 깨어나** 다시 충돌한다. 재시도가 경합을 흩뜨리는 게
  아니라 **그대로 보존**한다. 소진 후 전량 DLQ 라는 실측 형태(충돌 7 → DLQ 7)와 일치한다.
- **P6 (ii) 에 대한 함의**: 재시도 횟수를 늘리는 것은 처방이 아니다. **jitter 가 없으면 몇 번을 돌려도
  같은 충돌을 반복**한다. 어느 선택지를 고르든 jitter 는 별도로 필요하다.
- **한계**: (a) 의 메커니즘은 단위 테스트로 보였고, GKE 실측 7건이 **정확히 이 경로였다는 인과**까지
  로컬에서 확증하지는 못했다. 증적은 형태 일치(전량 소진)까지다.

### 작업 중 정정 — P4 초안의 테스트 버그

P4 첫 실행이 red 였고 **원인은 프로덕션이 아니라 내 테스트**였다. 단계 (c)("보유자의 unlock 이 남의
락을 풀지 않는다")를 단계 (a) 의 탈취자와 **같은 단일 스레드 executor** 에 제출해, Redisson 의
**재진입**으로 `true` 가 나왔다. 소유권이 스레드 단위라는 성질을 내가 놓친 것이다. 제3의 스레드로
분리해 수정했다. 프로덕션 코드는 건드리지 않았다.

## 5. 미해결 / 범위 밖

- ~~**V8 해명 결과**~~ — ✅ P5 가 채웠다(위 §4-A). P6 (ii) 의 선결 조건 해소.
- **D-026**(detail 재고 캐시 미적용) — 같은 `InventoryRepository` 를 건드리지만 **캐시 경계**
  결정이라 별건 ADR. 본 계획에서 `getProduct` 경로는 **건드리지 않는다**(CLAUDE.md §3).
- **부하 하 재측정** — 선택지 C 는 DB 경합 비용이 미측정이다. ADR 은 이를 **가정으로 명시**하고,
  실측은 다음 부하 세션으로 이관한다. 로컬 Testcontainers 결과를 클러스터 결과로 주장하지 않는다
  ([[project_multimodule_dockerfile_context]] 와 같은 축).
- **크로스서비스 E2E** — Order↔Product 경합 전체 경로의 검증은 기존 saga E2E 게이트 범위.
  본 계획은 product-service 내부 경계에 한정한다.
- **`OrderController:91` 취소 설명** — [#120] 에서 인접 코드로 남겨둔 낡은 서술. 본 계획과 무관.
- **Codex 계획 리뷰 미호출** (사용자 지시, 2026-09-18). 따라서 **"P0/P1 = 0" 주장 없음** —
  이 계획서는 제3자 검토를 받지 않았다. `/plan` §5~§7(리뷰·수렴 판정)이 통째로 생략됐고,
  과거 이 프로젝트에서 계획 리뷰가 실제로 잡아낸 부류(전제 반증·누락 항목)는 **미검출 상태**다.

## 6. 완료 조건

1. §1 의 N1~N6 이 **전부 거짓**이다.
2. P1~P5 의 재현이 ADR 작성 **이전에** 존재하고, P6 의 결정이 그 결과를 인용한다.
3. ADR-0025 가 (i)~(v) 를 **비우지 않고** 채웠다.
4. 구현이 ADR 선택과 일치하고, P11 회귀 가드가 그 일치를 **테스트로** 고정한다.
5. `:product-service:test` 그린 + 문서 동기화(P13) 완료.
