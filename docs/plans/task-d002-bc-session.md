# 계획 — D-002 잔여 3축 측정 세션 (b' / c + a 천장 분리)

> 선행: `task-d002-rescope.md`(범위 재정의) · `task-d002a-cache-speedup-session.md`(세션 3, [#117]).
> 이 세션은 D-002a 가 명시적으로 다음 세션에 넘긴 것만 한다.

## 1. 명제 (부정형 — 무엇이 성립하면 미완인가)

다음 중 **하나라도** 성립하면 D-002 는 미완이다.

- **N1** 캐시 OFF 조건의 처리량 천장을 설명하는 원인이 여전히 미분리다. CPU·Hikari 풀은
  D-002a 가 배제했고 "MySQL 내부" 는 가설일 뿐, 어떤 자원(디스크 I/O / 버퍼풀 / row lock /
  단일 커넥션 직렬화)인지 **측정으로 좁혀지지 않았다**.
- **N2** 주문 생성 경로의 기준선이 **현재 코드 기준으로** 존재하지 않는다. 원문 p95 30.21s 는
  재고 차감이 동기였던 시절의 값이고, 그 전제는 소멸했다(§2 V3).
- **N3** 재고 예약 락 경합이 **현재 형태로** 측정되지 않았다. 락은 동기 HTTP 경로를 떠나
  Kafka consumer 트랜잭션 안으로 들어갔고, 실패는 응답이 아니라 재시도/DLQ 로 나타난다(§2 V4).
- **N4** 측정 결과가 `docs/progress/evidence/` 에 증적으로 남지 않았거나, GCP 자원이 회수되지
  않아 과금이 0 이 아니다.

> N1~N3 의 결론이 "천장 원인은 X 다" 가 아니라 **"이 축은 이 스택에서 재현 불가다"** 여도 명제는
> 충족된다 — D-002a 가 D-002b 의 전제 소멸을 그렇게 종결했다. 미완인 것은 **미측정**이지 미해결이 아니다.

## 2. 배경 — 착수 전 코드 검증

**계획의 전제는 ADR 이 아니라 현재 코드다.** 초안 전에 직접 확인한 결과:

| # | 초안/원문이 전제한 것 | 코드 확인 결과 | 계획 영향 |
|---|---|---|---|
| **V1** | detail 이 캐시 적중해도 이득이 절반인 이유 미상 | **확증.** `ProductQueryService.getProduct:44` 가 `productCacheService.getProductInfo()` 로 상품은 캐시에서 읽고, **재고는 `inventoryRepository.findByProductId()` 로 매 호출 DB 에서 읽는다**. 캐시 경계 밖에 재고가 있다 | 측정 대상이 아니라 **설계 결함 후보** → §6 에서 D-021 승격 제안. 이번 세션은 측정만 |
| **V2** | (D-002a) 캐시 OFF 천장 = MySQL 내부 | CPU 배제(limit 1000m→3000m 로 325→715 rps), 풀 배제(Hikari 50 무개선) 는 증적에 기록됨(`evidence/d002a-...:111`). **MySQL 내부는 미측정 가설** | N1 은 "가설 검증" 이 아니라 **분해 측정**이어야 한다 → P5 |
| **V3** | 주문 생성이 재고를 차감한다 (원문 D-002b) | **반증 확정.** `OrderCommandService.createOrder:44~81` = cart 조회 → **로컬 가격 캐시** 단가 조회(`priceCacheRepository.findUnitPrice`, 미수신 시 `ORD-007`) → order insert → cart clear → outbox publish. 재고 차감·Product 동기 호출 **없음** | b' 는 재측정이 아니라 **신규 기준선**. 원문 30.21s 와 비교 금지 |
| **V4** | 분산 락을 Kafka consumer 가 부른다 | **확증, 그리고 더 나쁘다.** main 코드에서 `InventoryLockFacade` 호출자는 `StockReservationService:86` **단 1곳**이고, 그 진입점은 `StockReservationConsumer.handleOrderCreated`(`@Transactional`). 락 실패 `PRD-004` 는 HTTP 응답이 아니라 **consumer 예외** → 재시도/DLQ | c 의 관측 지표를 **응답 p95 → consumer lag·재시도·DLQ·예약 결과 지연**으로 교체 → P7 |
| **V5** | 락이 트랜잭션을 감싼다 (`InventoryLockFacade` javadoc: "락 획득 → 트랜잭션(커밋) → 락 해제") | **consumer 경로에서 성립하지 않는다.** `InventoryService.decreaseStock` 은 `@Transactional`(REQUIRED)이라 **consumer 트랜잭션에 참여**한다 → `finally { unlock }` 이 커밋보다 **먼저** 실행된다. 즉 분산 락은 이 경로에서 커밋을 직렬화하지 못한다 | **L-007 불변식이 새 형태로 깨져 있다.** 정합성은 `Inventory @Version`(낙관 락)이 홀로 지킨다 → P8 이 이것을 측정으로 드러낸다 |
| **V6** | 기존 동시성 테스트가 이 경로를 덮는다 | **덮지 않는다.** `InventoryConcurrencyTest:54` 는 `inventoryLockFacade` 를 **직접** 호출한다(외부 트랜잭션 없음) → V5 의 역전이 재현되지 않는 배선 | 테스트가 green 인 것은 반증이 아니다. P8 은 **consumer 경로**로 측정한다 |
| **V7** | d002a 처럼 gateway 를 빼고 서비스를 직접 때리면 된다 | **b'/c 에는 전이되지 않는다.** order/payment 는 `HeaderTrustSecurityConfigurer` → `InternalTokenAuthenticationFilter` 를 쓰고 기본 모드가 `SIGNED_ONLY` — **평문 `X-User-*` 를 무시**한다. product 읽기만 공개 경로였다 | gateway 를 **넣어야 한다** → 한도 문제를 P2 가 처리 |
| **V8** | RateLimiter 40 req/s 가 b'/c 측정을 무의미하게 만든다 | **키가 사용자별이다** — `application.yml:52` 등 `key-resolver: #{@userKeyResolver}`, `burstCapacity: 40`/`windowSeconds: 1`. 총량 한도가 아니다. `loadtest/scripts/users.csv` + `generate-users-csv.sh` 이미 존재 | **N 사용자 × 40 rps** 로 우회한다. 설정 변경 없이 부하를 올릴 수 있다 → P2 |
| **V10** ⚠️ | (D-002a 증적) 천장의 원인에서 **CPU 는 배제됐다** | **부분적으로만 사실.** 배제된 것은 **product-service 의 CPU** 다. `gke-d002a/kustomization.yml` 의 `patches` 는 **1건**(product-service)뿐이라 그 세션 내내 **MySQL 은 base 값 `limits.cpu: 500m`** 으로 돌았고 그 축은 한 번도 변경되지 않았다 | **P5 의 1순위 후보가 InnoDB 내부가 아니라 MySQL 자신의 CPU 상한이다.** overlay 에 손잡이를 만들되 기본값은 base 와 동일하게 둔다(안 그러면 P4 재현이 불가능) |
| **V11** | 상품은 d002a 처럼 SQL 로 시드하면 된다 | **불가.** 직접 INSERT 는 outbox 에 `product.updated` 를 남기지 않아 order 의 `product_price_cache` 가 빈다 → 주문이 전부 `ORD-007`(`OrderCommandService:57`). `V1__init_order.sql:7` 이 이 테이블을 "seed 제외 — cross-DB → product.updated replay" 로 못박아 둔 것과 같은 이유 | 상품 시드는 **admin API 경유** + 캐시 전파 게이트로 바꾼다 → P3 |
| **V12** | 로그인은 사용자별 40 req/s 안에서 자유롭다 | **로그인만 다르다.** `user-auth-preauth` 라우트는 **IP 키 · burstCapacity 10**(`application.yml:40-41`)이다. loadgen VM 은 단일 IP 이므로 N 사용자 로그인이 **10/s 로 묶인다** | k6 `setup()` 은 순차 + 150ms 간격으로 토큰을 모은다. N=200 이면 setup 약 30s |
| **V9** | Kafka 는 읽기 경로와 무관하니 뺄 수 있다 | d002a 가 이미 되넣었다(`gke-d002a/kustomization.yml` 주석 — product 가 Kafka 없이 부팅 실패). b'/c 는 **본질적으로** 필요 | 쿼터 계산에 Kafka 250m 포함 |

### 범위 변화

- **늘었다**: V5/V6 발견으로 P8(락 불변식 역전 실측)이 추가됐다. 원래 c 는 "경합 지연 측정" 이었으나,
  **락이 그 경로에서 의도대로 동작하지 않는다**는 코드 사실이 먼저다. 측정이 이걸 드러내야 한다.
- **줄었다**: V8 로 "RateLimiter 설정을 측정용으로 완화" 항목이 사라졌다. 설정을 만지지 않는다
  (ADR-0007 · fail-closed 계약을 측정 편의로 흔들지 않는다).
- **밖으로 나갔다**: V1(detail 재고 캐시 미적용)은 측정이 아니라 설계 표면 → §6.
- **구현 중 추가로 뒤집힌 전제 3건**(V10~V12): D-002a 가 배제한 CPU 는 product-service 의 것이었고
  MySQL CPU 상한은 미분리로 남아 있었다(V10) · 상품 SQL 시드는 가격 캐시를 비워 주문을 전멸시킨다(V11) ·
  로그인만 IP 키 10/s 라 부하 준비 단계가 별도 제약을 받는다(V12). **셋 다 계획을 고쳐서 반영했다.**

## 3. 작업 항목

### 준비

- [x] **P1.** 쿼터 배치 확정. 렌더 산출(`kubectl kustomize --load-restrictor LoadRestrictionsNone
  k8s/overlays/gke-d002bc`)에서 실측한 **CPU requests 합 = 3,600m**(gateway 500×2 + user/product/
  order/payment 500×4 + mysql 250 + kafka 250 + redis 100). 초안의 1.85 vCPU 는 **틀렸다** — base
  250m 로 계산했으나 gke 프로파일은 서비스당 requests 500m / limits 2000m 이고, 이 측정은 그 프로파일을
  써야 D-002a 및 운영과 비교 가능하다. 노드 `e2-standard-4` × 2(8 vCPU, allocatable ≈7.8) + loadgen VM
  `e2-medium`(2) = **10 / 12**. 3.6 < 7.8 이므로 들어간다.
  **부하발생기는 클러스터 밖 VM 을 유지한다**(세션 2 의 co-location 편차를 다시 들이지 않는다).
  들어가지 않으면 **축소하는 쪽을 고르고 그 사실을 증적에 남긴다** — 노드를 1개로 합쳐 경합을 섞지 않는다.
- [x] **P2.** 인증 부하 하네스. `users.csv` 의 N 사용자로 로그인(V12 때문에 순차 + 150ms) → VU 에
  1:1 고정. 목표 rps 상한을 **스크립트가 직접 강제**한다(`RPS > 40×USERS` 면 시작 전 throw) —
  사후에 429 를 세는 것보다 앞에서 막는 편이 낫다. `users.csv` 가 USERS 보다 적으면 계정이 겹쳐
  한도가 무너지므로 그것도 throw 한다. **gateway 설정·RateLimiter 는 건드리지 않는다.**
  threshold 로 `peekcart_429: count==0` 을 걸어 1건이라도 나오면 run 이 red 가 된다.
  산출물: `loadtest/scripts/d002bc-order-create.js` · `d002bc-contention.js` ·
  **`d002bc-ratelimit-probe.js`**(429 가 나와야 PASS — §4 P2 검증의 실행체).
- [x] **P3.** 측정 전용 overlay `k8s/overlays/gke-d002bc`. gateway + **user** + product + order +
  payment + mysql/kafka/redis. **user-service 는 뺄 수 없다** — V7 로 gateway 경유가 강제되고,
  gateway 는 사용자 토큰을 받아야 내부 토큰을 발행하므로 로그인 발급자가 필요하다.
  notification 제외, **HPA 제외 + replicas 고정**(오토스케일이 붙으면 처리량이 아니라 스케일러를
  재게 된다 — lint 범위가 `overlays/{minikube,gke}` 뿐이라 HPA-REPL 계약과 충돌하지 않는다).
  상단에 **측정 전용·운영 승격 금지** 주석. `patches/mysql-deployment.yml` 이 V10 의 손잡이다.
  시드는 `loadtest/sql/d002bc-user-seed.sql` + **`d002bc-seed-products.sh`**(admin API 경유 +
  `product_price_cache` 전파 대기 — V11). 절차는 `loadtest/README.md` §D-002b'/c.

### D-002a 잔여 — 천장 원인 분리 (N1)

- [ ] **P4.** 캐시 OFF 포화 조건을 d002a 와 동일 파라미터로 재현한다. 재현되지 않으면
  **이 세션의 나머지 a 축을 중단하고 그 사실을 기록한다**(다른 스택에서 잰 값과 섞지 않는다).
- [ ] **P5.** **먼저 MySQL 자신의 `limits.cpu`(500m)를 흔든다** — V10 이 이것이 미분리 상태였음을
  드러냈다. 그 다음 내부 지표를 분해한다: `SHOW ENGINE INNODB STATUS`(row lock 대기·
  버퍼풀 hit), `performance_schema` 대기 이벤트 top-N, `SHOW GLOBAL STATUS` 의
  `Innodb_buffer_pool_reads` vs `..._read_requests`(디스크 도달률), PD 디스크 IOPS.
  **가설을 고르지 않고 순위를 낸다.**
- [ ] **P6.** P5 에서 1순위가 나오면 **그 자원만 단독으로 완화**해 천장이 움직이는지 본다
  (예: 버퍼풀 크기, PD 타입). 움직이지 않으면 1순위가 아니었다는 뜻이고, 그것도 결과다.

### D-002b' — 주문 생성 신규 기준선 (N2)

- [ ] **P7.** 현재 경로(`POST /api/v1/orders`) 기준선을 잰다: TPS·p95, 그리고
  **`order.created` 발행 → `stock.reservation.result` 수신까지의 사가 완결 지연**을 따로 낸다.
  동기 응답만 재면 "빨라졌다" 는 착시가 된다 — 일이 비동기로 옮겨갔을 뿐이다.
  `ORD-007`(가격 캐시 미수신) 발생률을 함께 기록한다. 0 이 아니면 시드/워밍업 결함이다.

### D-002c — 비동기 예약 락 경합 (N3, V4/V5)

- [ ] **P8.** 동일 상품에 주문을 집중시켜 예약 경합을 만든다. 관측 지표는 응답이 아니라:
  consumer group lag(`product-svc-order-created-group`), 재시도 횟수,
  `OptimisticLockingFailureException` / `PRD-004` / `PRD-002` 발생 비율, DLQ 유입 건수,
  `saga` 메트릭(`reservationFailed`/`reservationSucceeded`), 예약 완결 지연 분포.
- [ ] **P9.** **V5 를 측정으로 드러낸다** — 경합 하에서 `PRD-004`(락 대기 3s 초과)와
  `OptimisticLockingFailureException` 의 **비율**을 본다. 락이 커밋을 직렬화한다면 낙관 락 충돌은
  드물어야 한다. 낙관 락 충돌이 유의하게 나오면 **락 해제가 커밋보다 앞선다는 코드 사실이
  런타임에서 확인된 것**이다.
- [ ] **P10.** 오버셀링 정합성을 확인한다 — 부하 종료 후 `stock` 최종값 == 초기값 − Σ(RESERVED 예약 수량),
  음수 재고 0건. (세션 2 가 "오버셀링 정합성 미검증" 으로 남긴 항목의 회수.)

### 종결

- [ ] **P11.** 증적을 `docs/progress/evidence/d002bc-gke-<YYYYMMDD-HHMM>.md` 에 남긴다 — 조건·원시 수치·
  **미분리로 남은 것**을 명시한다.
- [ ] **P12.** `loadtest/cleanup.sh` 계열로 회수하고 **과금 0 을 확인**한다(GKE 클러스터·VM·PD·LB·고정 IP).
- [ ] **P13.** `docs/TASKS.md` D-002 행과 구현표 `D-002 격리 재측정` 행을 갱신한다. 3축이 모두
  측정(또는 재현 불가 판정)되면 🔄 → ✅.

## 4. 검증 방법

"측정했다" 는 검증이 아니다. 각 항목은 **false-green 을 막는 반대 조건**을 함께 낸다.

| 항목 | 검증 — 실패/반대 조건을 주입해 확인 |
|---|---|
| P2 | 의도적으로 **단일 사용자**로 40 rps 초과 부하를 넣어 **429 가 나오는 것**을 먼저 확인한다. 429 가 안 나오면 한도 배선이 죽은 것이고, 그 상태의 측정은 무효다 |
| P4 | d002a 와 같은 조건에서 TPS 가 **±10% 안**으로 재현되는지. 벗어나면 스택이 다른 것이므로 a 축 중단 |
| P5 | 지표 수집이 부하 **전/후** 모두에서 이뤄져 **차분**이 있는지. 단일 스냅샷은 귀속 근거가 못 된다 |
| P6 | 완화 후 천장이 움직였다면, **되돌렸을 때 다시 내려오는지** 확인한다(단방향 관측은 다른 변화와 구분되지 않는다) |
| P7 | 동기 p95 와 사가 완결 지연을 **둘 다** 낸다. 사가 지연 항목이 비어 있으면 미검증 처리 |
| P8/P9 | 경합 **없는** 대조군(상품 분산)을 같은 부하량으로 돌려, 충돌 지표가 대조군에서 **0 에 가까운지** 확인한다. 대조군에서도 나오면 경합이 아니라 다른 원인이다 |
| P10 | 부하 전 재고를 **일부러 부족하게** 시드한 run 을 1회 넣어 `OUT_OF_STOCK` 수렴과 재고 음수 0 을 확인한다. 재고가 충분한 run 만으로는 all-or-nothing 이 검증되지 않는다 |
| P12 | `gcloud` 로 잔여 자원 **0건**을 조회한 출력과, 다음날 과금 조회를 증적에 붙인다 |

## 5. 완료 조건

- N1~N4 가 모두 거짓이다.
- P5/P6 의 결론이 "1순위 자원 X" 또는 **"이 스택에서는 분리 불가"** 중 하나로 확정됐다.
- P9 가 V5 를 확인했거나 반증했고, 어느 쪽이든 증적에 기록됐다.
- `docs/TASKS.md` D-002 행 갱신 · GCP 잔여 자원 0.

## 6. 미해결 — 범위 밖 처분

| 항목 | 처분 |
|---|---|
| **V1 detail 재고 캐시 미적용** (`ProductQueryService:44`) — 캐시 적중해도 재고는 매번 DB. detail 이득 ×1.23 vs list ×2.02 의 원인 | **D-021 승격 제안**(측정 아님, 설계 표면). 재고는 캐시 정합성 난이도가 상품 정보와 다르므로(쓰기 빈도·정확성 요구) 캐시 경계 변경은 **ADR 선행**이 맞다. 이번 세션에서 고치지 않는다 |
| **V5 락 ⊂ 트랜잭션 역전** (`InventoryLockFacade` javadoc ↔ consumer 경로 실제) — javadoc 이 보장한다고 쓴 순서가 그 경로에서 성립하지 않는다 | 이번 세션은 **측정으로 드러내기만**(P9) 한다. 수정은 별건 — `@Version` 이 정합성을 지키고 있어 긴급도가 낮고, 고치는 방향(락을 consumer 트랜잭션 밖으로 / 락 제거하고 낙관 락 일원화)은 트레이드오프가 있어 ADR 대상이다. **보류 L-007 과 같은 표면** |
| **V6 테스트 배선 갭** — `InventoryConcurrencyTest` 가 facade 직접 호출이라 consumer 경로의 역전을 재현하지 않는다 | V5 수정 건에 동반. 지금 테스트만 바꾸면 red 를 만들고 고치지 않는 상태가 된다 |
| **`OrderController.createOrder` swagger 설명이 낡았다** — `@Operation(description = "...재고가 즉시 차감된다.")` 인데 V3 대로 더는 차감하지 않는다. 공개 API 문서에 사실과 다른 기술이 남아 있다 | 이번 PR 범위 밖(측정 하네스 PR 이다). **별도 1줄 수정**으로 처리 — 여기서 고치면 측정 PR 이 도메인 코드를 건드리게 된다 |
| **`loadtest/README.md` 상단 Phase 3 절차가 DB-per-service 이후 동작하지 않는다**(`seed.sql` 이 단일 스키마 전제) | 지우지 않고 **아래에 Phase 4 절을 덧붙였다**. 기존 절은 Phase 3 리포트의 재현 근거라 삭제가 손실이다 |
| **1차 측정(Phase 3 모놀리스)과의 비교** | 계속 불가. b'/c 도 **새 기준선**이다 |
| **여러 인스턴스 물리 분리 후 재측정** | 구현 ② 비차단 후속(URL 교체 가역 승격). 이 세션은 1 인스턴스 + 5 스키마 상태로 잰다 |
