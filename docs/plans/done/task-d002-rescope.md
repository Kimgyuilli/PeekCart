# 계획 — D-002 재정의 (착수 전 코드 검증)

> D-002 는 "Phase 4 Order Service 분리 후 격리 재측정" 게이트로 추적돼 왔고, 그 게이트는 구현 ①
> 완료로 충족됐다. 그런데 **재측정 대상 자체가 코드에서 바뀌었다.** 유료 GKE 세션을 잡기 전에
> 범위를 다시 긋는다 — 구현 ④/⑤/⑥ 이 착수 전 코드 검증으로 범위를 재정의한 것과 같은 절차다.

## 1. D-002 원문은 두 축이 섞여 있다

> 캐시 TPS ×2.31(목표 ×3 미달). 1차 병목 CPU 확증, 2차 후보(MySQL 풀 / Redis 락 contention) 미분리

출처가 서로 다른 두 측정이다(`PHASE3.md` Part B/C):

| 축 | 원 측정 | 대상 경로 |
|---|---|---|
| **D-002a** | 캐시 전/후 TPS **×2.31** (목표 ×3) | product **읽기** 경로 (Redis `@Cacheable`) |
| **D-002b** | Run 2 p95 **30.21s**, 깊은 단계 timeout → "MySQL 풀 / Redis 락 contention" | 주문 **생성** 경로 (모놀리스) |

## 2. 코드 검증 — D-002b 의 전제는 소멸했다

1차 측정 당시 모놀리스의 주문 POST 는 요청 안에서 재고 차감(Redis 분산 락 + DB write)까지 했다.
지금은 아니다:

| 확인 | 현재 코드 | 근거 |
|---|---|---|
| 주문 생성이 재고를 차감하나 | **아니다** — order insert + outbox publish 로 끝난다 | `OrderCommandService.createOrder:44~79` (stock/lock 호출 없음) |
| 분산 락은 어디 있나 | **product-service**, `inventory-lock:{productId}` | `InventoryLockFacade:34` |
| 그 락은 무엇이 부르나 | **Kafka consumer** (동기 요청 아님) | `StockReservationConsumer` · `StockConfirmConsumer` · `StockReleaseConsumer` → `StockReservationService.reserve` |
| 주문 생성이 읽는 단가 | 로컬 테이블(`product_price_cache`, JPA) — **Redis 아님** | `OrderCommandService:36` `ProductPriceCacheRepository` |

즉 **"주문 생성 요청이 Redis 락 대기에서 timeout 된다"는 병목은 그 형태로 재현될 수 없다.**
락 경합은 남아 있지만 **비동기 예약 경로**로 이동했고, 그 경로의 실패는 지연이 아니라
**예약 실패/보상**(saga)으로 나타난다 — 관측 지점도 p95 가 아니라 `stock.reservation.result` 다.

> D-002b 를 "같은 것을 다시 측정" 으로 잡으면 **없는 경로를 측정**하게 된다.

## 3. 재정의

| 새 ID | 무엇을 측정하나 | 왜 지금 가능한가 | 비용 |
|---|---|---|---|
| **D-002a** (유지) | product 읽기 캐시 배속이 ×3 에 미달하는 원인 분리 | product-service 가 독립 배포 단위 → **격리 측정 가능**. 토글 `peekcart.cache.enabled`(`CacheConfig:69/111`) 로 대조군 확보 | GKE 세션 |
| **D-002b'** (대체) | 주문 생성 경로의 **현재** 지연 구성 — order insert + outbox insert 동일 트랜잭션, Hikari **기본 풀 10** | 경로가 짧아졌으므로 1차 측정과 비교 불가 → **신규 기준선** | GKE 세션 |
| **D-002c** (신규) | 비동기 예약 경로의 락 경합 — 처리량/예약 실패율 | 락이 여기로 이동했다 | GKE 세션 |

**Hikari 설정이 레포 어디에도 없다** — `grep -rn hikari` 결과 0건이므로 서비스마다 Spring 기본값
`maximum-pool-size: 10` 이다. 5서비스 분리 후에도 튜닝된 적이 없다. D-002b' 의 1순위 변수다.

## 4. 측정 설계 시 이미 아는 제약 (세션 2 실측)

- **CPU 쿼터 `CPUS-ALL-REGIONS` = 12** — 증설 두 번 자동 거부, 콘솔 조정 불가.
- **loadgen 을 클러스터 안 Job 으로 돌리면 앱과 CPU 를 경쟁**한다. 세션 2 는 그렇게 해서 노드 3대를
  확보했지만 "측정에 co-location 경합이 섞였다" 를 편차로 남겼다.
- **RateLimiter 가 fail-closed, 키당 40 req/s** — 한도 위로 밀면 60% 가 429 로 나오고 지연 수치가
  무의미해진다(세션 2 P14 1차 측정 무효화 사유).
- 기존 `loadtest/scripts/order-concurrency.js` 는 1000 VU · DB seed 의존의 **Phase 3 시나리오**다.
  위 3축과 목적이 달라 그대로 쓸 수 없다.

## 5. 다음 결정 (사용자)

이 재정의까지가 무료 구간이다. 실제 측정은 **GKE 과금 세션**이 필요하다:

1. 지금 세션을 잡는다 → D-002a/b'/c 를 한 번에 (쿼터 12 안에서 축을 몇 개까지 넣을지 설계 필요)
2. 미룬다 → D-002 를 재정의된 상태로 `🔄 추적` 유지, L-004(무료) 를 먼저

---

## 종결

머지 PR: [#117](https://github.com/Kimgyuilli/PeakCart/pull/117) — D-002 범위 재정의 + D-002a 캐시 배속 실측

계획서 아카이브 판정을 위해 PR 링크를 명시한다. 작업은 위 PR 로 종결됐고 본문의
체크박스 상태는 당시 갱신되지 않은 것이라 완료 여부의 근거가 아니다.
