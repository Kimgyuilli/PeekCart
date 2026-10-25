---
grade: M
---
# D-078 GKE 재측정 세션 계획 (D-106 ①⑦⑤ 설계 확정)

> **세션을 실행하기 전까지 이 계획서는 아카이브하지 않는다.** 2026-10-25 병렬 부채 캠페인(레인 A~D)이 끝나면 남는
> 작업은 이 세션 하나다. 세션 실행 기록(S0 digest 표 포함)도 이 파일에 남긴다.

기준: 2026-10-25, main `6905952`, 브랜치 `debt/d106-remeasure-design`(레인 A). 원자료: `loadtest/reports/2026-10-24/`
(`session-issues.md` #1·#5·#7·#11, `s2-runs/`, `s3/*.json`), `docs/progress/evidence/d078-gke-20261024.md`, 지난 계획
`docs/plans/done/D-078-session-plan.md`, 선행 수정 `docs/plans/done/D-106-remeasure-prereq.md`.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- (①) S2 가 ADR-0037 조회 bulkhead 를 둔 채로 물을 질문과 셀, 상한을 바꿀 때의 재기동·워밍업·캐시 상태 절차를 정하지
  않았다. 또는 상한 4 를 측정 overlay 로 렌더할 수 없거나, 그 env 이름이 코드의 바인딩과 다르거나, 하네스가 run 마다
  실제 상한을 기록·대조하지 않는다.
- (⑦) spread 대조군이 상품 1,000개를 실제로 순회하지 않거나, 시드·초기화·정합성 경로가 1,000개를 처리하지 못한다.
- (⑤) 부하 미달 run 을 가르는 규칙이 세션 전에 기계적으로 정해져 있지 않거나, 2026-10-24 원 요약을 넣었을 때 r1·r2 를
  무효, r3·spread 를 유효로 내지 않는다. USERS 산식과 RPS 170 의 값이 없거나, 그 값에서 k6 setup 이 기본 60초 제한에
  걸리거나, `users.csv`·사용자 시드 수와 어긋난다.
- 재측정 세션 절차(S0~S5)·중단 규칙·비용이 위 설계와 토픽 상태 가드, 워밍업 run 규칙(D-106 ⑪), 레인 C·D 의 처분을
  반영하지 않는다.

등급 M: 건드리는 곳이 loadtest 하네스·측정 overlay·lint 의 overlay 목록이다(판정 기준 1). 앱 모듈은 바꾸지 않고, 공개
API·이벤트·DB 스키마·설정 키도 그대로다(측정 overlay 가 기존 키에 값을 줄 뿐이다). 되돌림은 이 PR 파일 안에서 끝난다.
ADR 선행 신호(새 외부 의존성·경계 변경·새 인프라)가 없다. ADR-0037 의 기본값 1 은 바꾸지 않고, 이 측정은 ADR 이 요구한
"증액 입증" 의 입력이다. 구조 변경이 아니라 PLAN-BLINDSPOTS 대상이 아니다.

## 2. 배경과 설계

### 2.1 코드 검증

전제는 ADR 이 아니라 main `6905952` 의 코드와 원자료로 확인했다.

| 전제 | 확인한 사실 | 반영 |
|---|---|---|
| bulkhead 바인딩 | `ProductReadBulkheadConfig.java:24-28` 이 `@Value("${peekcart.cache.db-max-concurrency:1}")` 와 `@Value("${spring.datasource.hikari.maximum-pool-size:10}")` 를 받아 `maximum < 1 \|\| maximum >= poolSize` 면 `IllegalArgumentException`(기동 실패). 대상은 `ProductCacheService` 의 `getProductInfo`·`getStock`·`getProductList`, permit 을 못 얻으면 `PRD_005`(503, `ErrorCode.java:28`). product 의 yml·k8s 어디에도 두 키가 없다. 상한 1, 풀 10 | P3 |
| env 이름 | Spring Boot 3.5.12·spring-core 6.2.17 jar 로 `StandardEnvironment` 에 `SystemEnvironmentPropertySource` 와 `ConfigurationPropertySources.attach` 를 얹어 placeholder 를 풀었다(앱 기동과 같은 경로). `PEEKCART_CACHE_DBMAXCONCURRENCY=4` 는 4, `PEEKCART_CACHE_DB_MAX_CONCURRENCY=4` 도 4, 오타 `...DBMAXCONCURENCY` 는 **조용히 1** | P3: 레포 관례(`APP_OUTBOX_POLLING_BATCHSIZE`)대로 하이픈 제거형을 쓰고, 셀이 Pod env 를 대조한다 |
| 경로별 permit | `ProductQueryService.getProduct`(46-48행)가 `getProductInfo` 와 `getStock` 을 차례로 부른다(요청당 permit 2개). list 는 `getProductList` 1개. 캐시 advice 는 `order = -200`(`CacheConfig.java:50`), bulkhead -100, 트랜잭션이 가장 안쪽이라 hit 는 bulkhead·트랜잭션을 지나지 않는다. TTL 은 product 30분·products 10분·productStock 5초 | ① 셀 |
| 측정 하네스 | `gke-d002a` product patch 는 limit 3000m 과 캐시 env 만 준다. `d026-run-block.sh` 는 `gke-d002a-mysql-${MYSQL}` 만 apply 하고, `d026-run-cell.sh` meta 에 상한이 없고, `d002a-product-read.js` 는 200 여부만 센다 | P3 |
| 지난 S2 | 블록 A(MySQL 500m) OFF 셀의 200 비율 0.77~1.46%(list OFF r2 200 응답 14.5 rps), MySQL 스로틀 차분 0, 60초 MySQL CPU 3.37s. list ON 949.2 rps·200 100% | ① 재정의 근거 |
| spread 선택 | `d002bc-contention.js:94` 가 `PRODUCT_IDS[__ITER % PRODUCT_IDS.length]`. k6 문서: `__ITER` 는 VU 별 반복 번호, `exec.scenario.iterationInTest` 는 시나리오 전역 순번(v0.34+). 원래 스크립트를 USERS 450·RPS 170·60s 로 node 대역에서 돌리면 상품 1,000개 중 **23개**만 쓰인다 | P1 |
| 파티션 키 | `OutboxPollingService.java:133-134` 가 레코드 키로 `aggregateId` 를 쓰고 `OrderOutboxEventPublisher` 는 주문 id 를 넣는다. 같은 상품의 주문도 세 파티션에 흩어진다 | ⑦ 근거(키는 앱 계약이라 그대로) |
| 시드 1,000 | `d002bc-seed-products.sh` 는 admin API 순차 생성, admin 라우트는 사용자 키 40 req/s·1초 창(`gateway application.yml:70-81`), 전파 대기 60초 고정. product outbox 는 계획 작성 때 5s·100건(초당 20건)이었고 #242(ADR-0045) 뒤 500ms·배치 500 이다 | P2 |
| outbox 기본값 | ADR-0045(#242): order·product·payment 500ms·배치 500·락 최소 보존 400ms, notification 5s·4s·100. `gke-d078-contention` 은 order outbox env 를 더 이상 덮어쓰지 않는다 | S3 조건 |
| 초기화·정합성 | `d002bc-product-reset.sql` 은 inventories 전체를 `@stock` 100000 으로, `d002bc-reset.sql` 은 order 표 전체 삭제(price cache 유지), `d002bc-verify.sql` 은 inventories 전체를 상품별로 대조(`HAVING diff <> 0`, `@initial_stock` 100000). 셋 다 상품 수에 의존하지 않고 시드 기본 재고(100000)와 같다 | 변경 없음 |
| 지난 S3 k6 | 원 요약(`s3/*.json`): dropped r1 5,763(반복 4,438, 합 10,201)·r2 1,871·r3 89(합 10,200)·spread 없음(0건이면 metric 자체가 없다). 반복 p95 5.637·2.764·1.639·0.377s, `vus_max` 모두 200 | P1 |
| setup 시간 | 지난 run 의 전체 시간(반복 수/rate)은 114~118초다. 60초 부하를 빼면 setup 이 약 50~55초(200명 순차 로그인 + 150ms, 1명 약 0.25초). k6 `setupTimeout` 기본 60s, 넘으면 exit 100(k6 `errext/exitcodes`) | P1 |
| USERS 검사 | `d002bc-contention.js:36-37` RPS ≤ 40 × USERS, `:47` users.csv ≥ USERS. `d002bc-user-seed.sql:25` 는 `n < 200`, `users.csv` 200행 | P1·P2 |
| 토픽 가드 범위 | `kafka-topic-state-guard.py` `declared()` 는 `--services` 와 무관하게 선언 토픽 30개 전부를 보고 group 만 거른다. `gke-d002a` 에는 토픽 소유 서비스(order·payment)가 없어 S2 에서 돌리면 구조적으로 실패한다 | S2 는 가드를 돌리지 않는다 |
| drain preflight 범위 | `replay-drain-preflight.sh:40` `SERVICES=(order product payment notification)` | D-065 ② 는 base overlay(S4)에서만 |
| 관측 차트 | 지난 세션 `kube-prometheus-stack` 91.4.0. `k8s/monitoring/gke/install.sh:13` 은 버전을 고정하지 않는다(`D-106-topic-guard-throttle` §미해결 이 이 계획으로 넘김) | S1 명령에서 `--version 91.4.0` |
| 캡처 도구 | `d078-capture.py:38-49` 의 `d078-1`(RPS·p95·MySQL CPU·스로틀)·`d078-2`(Pod CPU·Hikari 대기·스로틀)·`d078-4`(노드 CPU)가 새 셀에 필요한 패널과 같다 | 코드는 그대로, `captures.md` 의 항목·시점만 고친다 |

### 2.2 설계 결정

#### ① S2 를 bulkhead 를 둔 채로 다시 묻는다

원래 ①(list 의 MySQL CPU 배속)과 ②(MySQL 2000m 의 새 천장)는 "캐시 miss 가 DB 동시성을 그대로 쓴다" 는 전제에 선
질문이었다. ADR-0037(2026-10-17)이 miss 경로 앞에 상한 1 의 bulkhead 를 두면서 OFF 셀은 MySQL 이 아니라 bulkhead 를
재게 됐다(§2.1 지난 S2). 이 둘은 원래 형태로 묻지 않고 **전제가 바뀌었다고 기록**한 뒤, ADR-0037 의 "측정으로 상한
증액을 입증하기 전에는 1" 에 답하는 상한 비교로 대체한다. ③④ 는 ON 셀로 잰다.

MySQL 은 2000m(base, ADR-0027)으로 고정한다. MySQL 한도가 더 이상 독립변수가 아니고, 블록 사이 MySQL 이 재기동하지
않으므로 A→B→A′ 되돌림이 필요 없다. 그 밖의 조건은 d026 과 같다(product limit 3000m, VU 400, 60s, IDS 100,
`d002a-product-seed.sql`).

| 블록 | overlay | 상한 | 셀(순서) | 묻는 것 |
|---|---|---|---|---|
| L1-OFF | `gke-d002a-mysql-2000m` | 1 | detail OFF, list OFF | 상한 비교의 기준 |
| L4-OFF | `gke-d078-read-bulkhead-4`(이 PR) | 4 | list OFF, detail OFF | 상한 비교 |
| L1-ON | `gke-d002a-mysql-2000m` | 1 | detail ON, list ON | ③ `product` counter, ④ 노드 CPU |

셀은 6개다. 상한 비교에 두 endpoint 를 다 쓰는 이유는 detail 이 요청당 permit 2개(정보 → 재고), list 가 무거운 질의
1개라 상한이 처리율을 묶는 방식이 다르기 때문이다. 하나만 재면 증액 판단을 다른 경로에 일반화해야 한다. ON 셀은 둘이
서로 다른 질문(③ detail, ④ list)이라 줄일 수 없다. ON 은 상한 1 로 돈다. hit 는 bulkhead 를 지나지 않지만 detail ON 은
productStock(TTL 5초)이 만료될 때 miss 가 bulkhead 를 지나므로 503 이 섞일 수 있고, 그것은 기록한다(ADR-0037
Consequences 의 "cold miss 가 겹치면 503").

**상한을 바꿀 때의 맞춤.** 상한은 overlay 로만 바꾼다(`d026-run-block.sh` 의 `OVERLAY`). product-service Deployment env 가
바뀌어 새 Pod 로 재기동하고, MySQL 은 같은 2000m patch 라 재기동하지 않는다(meta 의 `mysql_pod` 가 블록 사이 같아야 한다).
overlay 를 apply 하면 캐시 env 도 overlay 값(true)으로 돌아가므로 OFF 블록은 첫 셀이 false 로 바꾸며 한 번 더 재기동한다.
블록 안의 셀은 캐시 상태가 같아 그 뒤로는 재기동이 없다. 워밍업은 블록마다 각 셀의 첫 rep(r1)을 버리는 것으로 한다
(지난 계획과 같은 사전 규칙). MySQL 버퍼풀은 블록 시작의 풀스캔 3회와 무재기동으로 데워진 채 유지된다. 캐시는 OFF 셀이면
`NoOpCacheManager` 라 Redis 가 결과에 들어가지 않고(셀이 그래도 FLUSHALL 한다), ON 셀이면 run 마다 FLUSHALL 뒤 detail
1..100 과 list 1키를 순차로 채운다. 순차라 상한 1 에서도 워밍업 중 503 이 나지 않는다. 셀은 측정 Pod 의 env
`PEEKCART_CACHE_DBMAXCONCURRENCY` 를 meta 에 남기고, 블록이 준 기대값(`BULKHEAD`)과 다르면 그 run 을 돌리지 않는다.

**읽는 법(사전 규칙).** 블록·셀마다 측정 run 3개의 중앙값과 범위를 적는다: 200 응답 rps(`http_reqs.rate × ok_rate`),
503 비율(`status_503`, 이 PR), 그 밖의 오류 비율(1 − ok − 503), p95, MySQL cgroup CPU(사용 차분/60초)와 스로틀 차분,
product 스로틀 차분, Hikari 대기·active 최대(Prometheus). 아래 넷이 모두 성립하면 "상한 4 증액 근거 있음" 으로 적는다.

1. 두 endpoint 모두 L4 의 200 rps 범위가 L1 범위와 겹치지 않고 그 위에 있다.
2. L4 에서 503 이외 오류 비율이 L1 보다 늘지 않는다.
3. L4 에서 Hikari 대기(pending) 최대가 0 이다.
4. L4 에서 MySQL 스로틀 차분이 0 이다.

하나라도 어기면 "상한 4 는 근거 부족, 1 유지" 로 적는다. ADR-0037 갱신은 세션 뒤 사용자가 정한다. 한계: S2 스택에는
주문 경로가 없어 쓰기·메시지 소비가 거의 없다. 3 은 읽기 전용 부하에서의 풀 여유이고, 예약 소비와 겹친 여유는 재지 않는다.

③ 은 detail ON 측정 run 에서 `product` 이름 hit·miss counter 가 숫자로 읽히고 hit 차분이 0 보다 크면 측정된 것으로
본다. 워밍업이 run 전에 100키를 채우므로 run 중 miss 는 0 근처가 정상이다. hit·miss 차분과 hit 비율을 적는다.

④ 는 list ON 측정 run 의 노드별 CPU 사용률(15초 step) 최대와 그 노드의 Pod 배치, 같은 run 의 MySQL cgroup CPU 를 적는다.
product-service 가 있는 노드의 최대가 90% 미만이면 이 조건에서 노드 경합 가설을 기각하고, 90% 이상이면 ADR-0027
Alternative B 재개 판단의 입력으로 넘긴다. MySQL CPU 가 0.05코어(60초에 3초) 미만이면 hit 경로가 MySQL 을 거의 쓰지
않는다(D-072 의 트랜잭션 경로가 ADR-0037 로 닫혔다)는 관측으로 적는다. ADR-0027 미지수 3 의 반응 자체(500m 대 2000m)는
다시 재지 않는다.

#### ⑦ spread 대조군은 상품 1,000개를 전역 순번으로 돈다

상품을 1,000개 만든다(`d002bc-seed-products.sh --count 1000`, 재고 기본 100,000). contend 는 그 첫 값 하나에 몰린다(지난
세션과 같은 1개 경합). contend 상품은 run 마다 최대 10,200건이고 초기화가 run 마다 100,000 으로 되돌린다. spread 는
상품당 10~11건이다.

spread 는 `exec.scenario.iterationInTest` 로 상품을 한 바퀴씩 돈다. 이웃한 반복은 늘 다른 상품이다. 종전의 `__ITER` 는
VU 별 번호라 VU 당 반복 수(약 23)까지만 커져 앞쪽 23개만 쓰고, run 초반에는 모든 VU 가 같은 상품을 고른다.

PRODUCT_IDS 는 시드 출력의 `PRODUCT_IDS=` 줄을 파일로 저장해 넘긴다(스텁에서 1,000개 3,921바이트, 실제 id 자릿수에
따라 4~6KB). 셸 인자 한 개의 상한(Linux 128KiB)보다 훨씬 작다. k6 init 이 양의 정수가 아닌 값을 거부하고, 요약이 상품 수를
찍는다. 시드는 admin 라우트 한도 때문에 건마다 50ms 를 쉬고, 전파 대기는 `max(60, COUNT/10)` 초(1,000개면 100초)다.

**추정(측정 아님).** 지난 spread(상품 10개)의 전달 실패는 129건(created 93·cancelled 36)이었다. 충돌 수가 같은 상품 행에
동시에 쓸 확률에 비례하고 그 확률이 상품 수에 반비례한다고 보면 129 × 10 / 1,000 ≈ 1.3건, 즉 **1건 안팎**이다. 선택
방식이 바뀌었고, cancelled 36건은 만료 취소 3,180건이 만든 release 경로라 D-075 처분 뒤 줄어든다. 크기 감만 주는 숫자다.

**읽는 법(사전 규칙).** spread 측정 run 의 전달 실패가 contend 측정 run 중앙값의 10% 이하이면 대조군이 저경합 조건을
만족한 것으로 보고 "대조군의 충돌은 경합이 아닌 다른 원인" 해석을 쓴다. 넘으면 그 해석을 쓰지 않고 수치만 적는다.

#### ⑤ 부하 미달 run 은 무효다

**판정.** `dropped_iterations` 가 목표 반복 수(RPS × DUR)의 1% 를 넘으면 그 run 은 무효이고 버린 뒤 초기화하고 다시 돌린다.
`d002bc-contention.js` 가 threshold `dropped_iterations: count<=⌊RPS×DUR/100⌋` 로 기계화한다. 실패하면 k6 가 exit 99 로
끝나고 요약 첫 줄이 `부하 판정: 무효. dropped_iterations N > 한도 M` 을 찍는다. 0건이면 k6 요약에 metric 이 없으므로
0 으로 본다. 2026-10-24 원 요약에 대입하면(RPS 170·60s, 한도 102) r1 5,763 무효, r2 1,871 무효, r3 89 유효, spread 0
유효다(§7 의 node 검사가 원 JSON 으로 확인한다).

**USERS 산식.** `USERS = ⌈RPS × T95 × 1.5 / 50⌉ × 50`, T95 는 k6 `iteration_duration` p95(초). constant-arrival-rate 는
반복 하나가 VU 하나를 반복 시간 동안 잡으므로 필요한 VU 는 도착률 × 반복 시간이다. p95 를 쓰고 1.5 를 곱한 것이 여유다.
종전 기준 `USERS ≥ RPS/40` 은 사용자별 한도만 본 하한이라 VU 포화를 막지 못했다(r1 은 VU 200 이 모두 바빴다).

**T95 의 출처와 RPS 170 의 값.** 산식의 "워밍업 run 관측값" 은 워밍업이 끝난 run 의 관측값으로 읽는다. 지난 세션에서
워밍업이 끝난 경합 run 은 드롭이 1% 이하인 r3 하나다(T95 1.639s). **RPS 170 의 USERS 는 450** 이다. 같은 산식으로 버리는
run 인 r1(5.637s)을 넣으면 1,450, r2(2.764s)면 750 이다.

- 1,450 을 쓰지 않는 이유: r1 의 반복 시간은 order-service CPU 한도(1.89/2코어)와 콜드 JVM 이 만든 것이라 VU 를 늘려도 그
  run 의 처리율이 오르지 않는다. 또 setup 은 순차 로그인(1명 약 0.25초)이라 1,450명이면 run 마다 약 6분이 들고, setup
  데이터(토큰 배열)를 VU 마다 복사하므로 loadgen(e2-medium 4GB) 메모리가 USERS 의 제곱에 비례해 는다.
- 450 이 워밍업 직후 run(r2 같은 상태, p95 기준 필요 470)을 대략 덮는다는 근거: r3 는 p95 기준 필요 279 에 VU 200(필요의
  72%)으로 드롭 0.87% 였다. 450 은 r2 필요의 96% 다. 덮지 못하면 판정 규칙이 그 run 을 무효로 걸러 다시 돌린다.
- 세션 중에는 USERS 를 바꾸지 않는다(세션 중 튜닝 금지). 워밍업 run 을 포함해 run 마다 k6 요약이 그 run 의 T95 와 산식 값을
  찍으므로, 다음 세션은 이번 세션의 워밍업 뒤 유효 run 값으로 다시 잡는다.

**맞춤.** 사용자 시드 450명(`d002bc-user-seed.sql`), `users.csv` 450행, k6 의 users.csv ≥ USERS 검사 통과, RPS 170 ≤ 40 ×
450. setup 은 450명에 약 2분이 걸려 기본 60초면 setup 에서 죽으므로(exit 100) `setupTimeout` 을 USERS 초(450s)로 둔다.

### 2.3 트레이드오프

- 상한 env 를 `gke-d002a` 에 "1" 로 적으면 코드 기본값이 나중에 바뀌어도 측정 조건이 따라 바뀌지 않는다. 대가는 `gke-d002a`
  계열 3개 렌더에 env 한 줄이 늘어나는 것이고 앱 동작은 같다(기본값과 같은 값).
- 상한 4 를 `kubectl set env` 가 아니라 overlay 로 둔다. 조건이 파일에 남아 다음 세션이 어느 조건인지 안다(d026 N5).
  대가는 측정 overlay 가 하나 늘어 lint 3곳(namespace·ServiceMonitor·lint-health) 목록에 등록하는 것이다.
- 분석 도구(`d026-collect.py`)에 200 rps·503 열을 넣지 않는다. 원자료(k6 요약, meta)가 다 남으므로 집계는 세션 뒤 무료로
  한다. 세션 전에 필요한 것은 수집이 빠지지 않는 것이다.
- k6 검사는 `loadtest/scripts/test_*.py` 처럼 로컬 검사로 둔다. `ci-lint-wiring-lint.py` 는 `scripts/*-lint.sh` 만 CI 배선을
  요구하고, loadtest 검사는 지금까지 CI 에 배선하지 않았다.

## 3. 작업 항목

- [x] P1. `loadtest/scripts/d002bc-contention.js`: `dropped_iterations` threshold(한도 ⌊RPS×DUR/100⌋), 요약 첫 줄의 부하 판정,
  DUR 해석(정수 h·m·s 조합이 아니면 init 실패), USERS 산식 출력(`usersFor`), `setupTimeout` = max(60, USERS)초, spread 의
  전역 순번 선택(`exec.scenario.iterationInTest`), PRODUCT_IDS 양의 정수 검사. 기존 429 threshold 와 USERS 검사는 그대로.
- [x] P2. 시드: `d002bc-user-seed.sql` 사용자 200 에서 450, `users.csv` 450행 재생성, `d002bc-seed-products.sh` 의 건당 50ms
  간격과 전파 대기 `max(60, COUNT/10)` 초. 초기화·정합성 SQL 은 상품 수와 무관함을 확인만 한다.
- [x] P3. 읽기 하네스: `gke-d002a` product patch 에 `PEEKCART_CACHE_DBMAXCONCURRENCY: "1"`, 새 overlay
  `k8s/overlays/gke-d078-read-bulkhead-4`(상위 `gke-d002a-mysql-2000m`, 값 "4"), `d026-run-block.sh` 의 `OVERLAY`·`BULKHEAD`,
  `d026-run-cell.sh` 의 상한 기록(`db_max_concurrency`)과 불일치 거부, `d002a-product-read.js` 의 `status_503` Rate.
  새 overlay 를 `kustomize-namespace-lint.sh`·`servicemonitor-selector-lint.sh`·`ci-lint-health-selftest.py` 목록에 넣는다.
- [x] P4. 문서: `loadtest/README.md` D-002b'/c 의 §절차 머리(부하 판정·USERS 산식), §1 시드(450명·상품 1,000개·PRODUCT_IDS
  저장), §4 명령(USERS 450·RPS 170·60s, 대조군 설명, 같은 블록의 브로커 주소 9092 를 k8s 리스너 29092 로 정정).
  `loadtest/captures.md` 의 ①②④ 행 항목·시점.
- [x] P5. 검사: `loadtest/scripts/test_d002bc_contention.mjs`(node, k6 대역으로 원 스크립트 실행)와 §7 의 실패 주입.
- [x] P6. §4 세션 절차, §5 중단 규칙, §6 비용.

## 4. 재측정 세션 절차

진입 조건: 캠페인 PR 4개(레인 A 이 PR #243, B D-065 ③ NOT NULL contract #244, C D-075 outbox 처리율 기본값과 ADR-0045
#242, D D-107 배포 래퍼의 토픽 사전 생성 #241)가 main 에 병합되고 그 main CI 가 성공했다. 세션은 그 main SHA 하나로 돈다.

### S0. 클러스터 없이 하는 준비 (과금 미미)

1. 세션 main SHA 에서 이 계획서 §2.1 의 전제를 다시 grep 한다. 지난 세션의 사각(전제표가 기준 코드에서 bulkhead 를 대조하지
   않음)을 반복하지 않으려는 단계다. 레인 B·C·D 가 바꾼 것(outbox 기본값, 배포 래퍼, contract migration)이 S3·S4 절차와
   맞는지도 본다.
2. gcloud configuration `peekcart-d078` 로 바꾸고 결제 계정을 다시 연결한다(`gcloud billing projects link peekcart-d078
   --billing-account 01001D-E81E39-C489B0`). 예산 알림(월 ₩20,000, 50/90/100/150%)이 살아 있는지 콘솔에서 본다.
3. 보존한 Secret Manager 시크릿 4개와 GSA 2개가 있는지, 시크릿 버전이 2026-10-23 업로드 그대로인지 본다. `local-keys` 공개키
   fingerprint(`908f1da22b4db672`·`17df680623f50fe6`)가 커밋된 ConfigMap 과 같은지 다시 대조한다.
4. AR 리포지토리를 다시 만들고(지난 S5 에서 지웠다) `scripts/promote-images.sh --project peekcart-d078 --tag <main SHA>` 로
   6개를 승격한다. digest 를 이 계획서 아래 실행 기록에 남기고, overlay 이미지는 digest 로 치환한다(치환은 커밋하지 않는다).

**S0 실행 기록 (2026-10-25).** 세션 main `1933002`(#245). 문서 전용 병합이지만 main push 라 이미지를 빌드했다(ADR-0044 D1,
CI 40134547452 성공). 기록 브랜치 `docs/d078-remeasure-session`.

1. §2.1 전제를 이 SHA 에서 다시 대조했고 모두 같다. `ProductReadBulkheadConfig.java:24-28`·`ProductQueryService.java:46-48`·
   `CacheConfig.java:50`·`ErrorCode.java:28`·`OutboxPollingService.java:133-134`·`replay-drain-preflight.sh:40`·`install.sh:13` 은
   줄 번호까지 같다. `6905952` 이후 코드 변경은 #241·#242·#243·#244 뿐이다.
   - 레인 C: order·product base yml 의 outbox 가 500ms·배치 500·최소 보존 400ms 이고, `gke-d002bc` 패치·order ConfigMap·k8s
     프로파일 어디서도 덮어쓰지 않는다.
   - 레인 D: `gke-d002a`·`gke-d002bc` 가 `base/infra/kafka` 를 포함해 래퍼가 렌더에서 kafka 3종을 고를 수 있다(S2·S3 첫 배포가
     exit 2 로 막히지 않는다).
   - 레인 B: 가드는 `information_schema` 와 PREPARE/EXECUTE 만 쓰고 빈 DB 에서는 아무것도 하지 않는다. GKE 계정 권한
     (`init-configmap.yml` 의 8권한) 안이다.
2. 결제 계정 연결은 사용자가 직접 실행했다(자동 모드 검사가 결제 명령을 막았다). `billingEnabled: true`. 예산 알림 "₩20,000
   월 예산 알림"은 50·90·100·150% 그대로다. 세션에 필요한 API 7개(container·compute·artifactregistry·secretmanager·iam·
   iamcredentials·billingbudgets)가 켜져 있다.
3. GSA `peekcart-gateway-signer`·`peekcart-user-signer` 는 활성 상태로 있다. 공개키 SPKI SHA-256 앞 16자는 local-keys 와 커밋된
   ConfigMap 이 `908f1da22b4db672`·`17df680623f50fe6` 로 같다. 시크릿 4개는 모두 버전 1·enabled 이고 생성 시각이
   2026-10-22T16:58~17:44Z(KST 10-23)다. 서명키 2개는 각자 자기 GSA 만 접근 바인딩을 갖는다. 결제 연결 직후 첫 조회 1건은
   반영 지연으로 `BILLING_DISABLED` 였고 재조회에서 응답했다.
4. AR 리포지토리 `peekcart` 를 다시 만들었다(06:24:01Z). 태그 `19330024f242f99c7bd10e97e88f89ecec16d4d9` 6개를 승격했고 AR digest 가
   아래 GHCR 원본과 모두 같다. payment-service 는 첫 복사가 GHCR 쪽 네트워크 오류(`no route to host`)로 끊겨 그 서비스만 다시
   승격했다. overlay 치환(커밋하지 않음)은 7개 파일이다: `gke`·`gke-d002bc`·`gke-d002a` 의 이미지 digest, base 의 SecretProviderClass
   2개·ServiceAccount 2개의 프로젝트 자리표시. `gke-d002a-mysql-2000m`·`gke-d078-read-bulkhead-4`·`gke-d078-contention`·`gke` 렌더에서
   자리표시 0, 앱 이미지 전부 digest, bulkhead env 는 1·4 다.

| 이미지 | GHCR digest (= AR digest) |
|---|---|
| gateway | `sha256:ac1929491bbe823c752c106c4467abe0c63ff44385048e2f03aaa2ccec3b226e` |
| user-service | `sha256:04118de5fedf6e19b97c5c384b6c19d2a5d1f831fdd5427b343e54231b4fc605` |
| product-service | `sha256:60175d296688b02dc7f23dad821bacfd3c7f5309b09b18be4914907046ef17ad` |
| order-service | `sha256:c8193a34eb86a6233136928c97de3e595358803aae709111350b1c211c3f1470` |
| payment-service | `sha256:b33b016141b55fc7cec28e2e5f64e3375f0a515e293ddcdd43be68d46bc46e40` |
| notification-service | `sha256:9d7dc6612079c340e478dd0e9fdbbb1a74c0326f679b169fd7357b32455dd52d` |

### S1. 클러스터 (여기서부터 시간 과금)

1. 지난 계획 S1 의 1~5 를 그대로 한다: GKE `peekcart-d078`(asia-northeast3-a, stable, `e2-standard-4` × 2, `pd-balanced`
   50GB, workload pool, Dataplane V2), `monitoring-pool`(`e2-standard-2` × 1, label·taint), cleanup dry-run 을 먼저 확인,
   노드 SA `roles/artifactregistry.reader`, WI 바인딩 2개, CSI 드라이버 chart 1.6.1(`syncSecret.enabled=false`)과
   provider-gcp v1.17.0.
2. 관측: 사용자가 `grafana-ops-webhook` Secret 을 먼저 만든 뒤 `helm upgrade --install kube-prometheus-stack
   prometheus-community/kube-prometheus-stack --version 91.4.0 -n monitoring -f k8s/monitoring/gke/values-prometheus.yml
   -f k8s/monitoring/gke/values-measurement-isolation.yml`, 이어서 `kubectl apply -k k8s/monitoring/shared/`. 차트 버전은
   2026-10-24 와 같게 명령에서 고정한다(`install.sh` 는 고치지 않는다). 관측 Pod 가 `monitoring-pool` 에 있는지 본 뒤 Grafana
   3000·Prometheus 9090 을 port-forward 한다.
3. loadgen VM `peekcart-d078-loadgen`(`e2-medium`), k6 v2.2.0. `loadtest/scripts/d002a-product-read.js` 를
   `/tmp/d002a-product-read.js` 로, `d002bc-contention.js` 와 `users.csv` 를 같은 디렉터리로 복사한다(스크립트가
   `./users.csv` 를 연다).

**S1 실행 기록 (2026-10-25).**

1. GKE `peekcart-d078` 06:30:50~06:34:49Z. 1.35.6-gke.1250001(지난 세션과 같다), stable, Dataplane V2(`ADVANCED_DATAPATH`),
   WI `peekcart-d078.svc.id.goog`, `e2-standard-4` × 2, `pd-balanced` 50GB. 관리형 Secret Manager 애드온은 꺼져 있다
   (`secretManagerConfig` 없음). `monitoring-pool`(`e2-standard-2` × 1, 라벨·`NoSchedule` taint)은 06:36:38Z 에 Ready.
   cleanup dry-run 은 클러스터·loadgen 만 가리켰고 미부착 디스크·예약 IP 는 0 이다. 노드 SA(`default`)의
   `roles/artifactregistry.reader` 와 GSA 2개의 WI 바인딩은 프로젝트에 남아 있어 새로 부여하지 않았다. CSI chart 1.6.1
   (`syncSecret.enabled=false`, audience `peekcart-d078.svc.id.goog`)과 provider-gcp v1.17.0(플러그인 이미지가 digest 로 고정된
   매니페스트)을 설치했고 DaemonSet 은 둘 다 2/2 다.
2. `grafana-ops-webhook` 은 사용자가 runbook `slack-webhook-secret.md` §2-1 로 만들었다. 키는 `url` 이고 정본과 해시 앞 16자가
   같다. `kube-prometheus-stack` 91.4.0 은 06:43:02~06:44:54Z, Grafana 13.2.1. Grafana·Prometheus·operator·kube-state-metrics
   는 `monitoring-pool` 에 있다. shared 대시보드 4개(`peekcart-*` uid)와 알림 규칙·라우팅을 적용했고 3000·9090 을 port-forward 했다.
3. loadgen `peekcart-d078-loadgen` 은 10.178.0.13, debian-12, k6 v2.2.0(commit `00a9a1b7f5`, 지난 세션과 같다)이다. 스크립트 2개와
   `users.csv`(451줄)의 sha256 이 로컬과 같다.

### S2. 읽기 경로: bulkhead 상한·③·④ (약 1.2시간)

1. 빈 namespace 에 `DRAIN_PREFLIGHT_SKIP_REASON="S2 읽기 경로, order·payment·notification 없음" bash scripts/deploy-overlay.sh
   k8s/overlays/gke-d002a-mysql-2000m`(digest 치환본)으로 배포한다. 래퍼가 kafka 를 먼저 올리고 토픽을 사전 생성한다(D-107, #241).
   mysql·product-service rollout 을 기다린다. `loadtest/sql/d002a-product-seed.sql` 을 넣고, `product-service-measure` Internal LB IP 를 확인한다.
   Prometheus target 에서 product-service 가 up 인지 본다(측정 overlay 의 ServiceMonitor, D-106 ③). 토픽 상태 가드는 돌리지
   않는다(§2.1, 읽기 경로는 Kafka 를 쓰지 않는다).
2. `export LOADGEN=peekcart-d078-loadgen OUTDIR=$PWD/loadtest/reports/<날짜>/s2-runs` 뒤 블록 셋을 이 순서로 돈다.
   ```bash
   MYSQL=2000m LABEL=L1 BULKHEAD=1 CELLS="detail:off list:off" REPS=4 bash loadtest/scripts/d026-run-block.sh
   MYSQL=2000m LABEL=L4 BULKHEAD=4 OVERLAY=gke-d078-read-bulkhead-4 CELLS="list:off detail:off" REPS=4 \
     bash loadtest/scripts/d026-run-block.sh
   MYSQL=2000m LABEL=L1 BULKHEAD=1 CELLS="detail:on list:on" REPS=4 bash loadtest/scripts/d026-run-block.sh
   ```
   셀이 `!! bulkhead 상한 불일치` 로 거부하면 §5 를 따른다. 블록마다 `kubectl -n peekcart get pod -o wide` 를 남긴다(④ 의
   노드 배치, 블록 사이 `mysql` Pod 이름이 같은지).
3. run 마다 시작·끝 epoch 를 `runs.tsv` 에 적고 `d078-capture.py annotate --label "<블록> <셀> r<n>"` 으로 구간을 남긴다.
   Hikari active·대기는 run 구간으로 Prometheus 를 직접 조회해 JSON 으로 남긴다(`/api/v1/query_range`, 식
   `max(hikaricp_connections_active{application="product-service"})` 와 `..._pending`, step 15s). scrape 주기 15초보다 짧은
   순간값은 놓친다.
4. S2 가 끝나면 각 셀 r1 을 `s2-runs/warmup/` 으로 옮기고(사전 규칙) `d026-collect.py s2-runs` 로 중앙값·이름별 캐시 차분을
   낸다. 200 rps·503·그 밖의 오류는 k6 요약의 `ok_rate`·`status_503` 으로 계산한다. 집계의 d026 게이트·배속 절은 이번
   질문이 아니다.
5. 캡처: OFF 측정 run(L1·L4 각 셀 1개 이상)은 `d078-1`·`d078-2`, list ON 측정 run 은 `d078-4`, S2 측정 run 1개는
   `d078-jvm`. 같은 범위로 `export` 한다. 끝나면 `kubectl delete ns peekcart`.

**S2 실행 기록 (2026-10-25).** 원자료는 `loadtest/reports/2026-10-25/s2-runs/`(runs.tsv, meta, k6 요약, `hikari/`, `pods/`),
집계는 `s2-summary.tsv`·`s2-collect.txt`, export·캡처는 `grafana/` 에 있다.

1. 배포 06:47:37~06:52:51Z. 래퍼가 kafka 를 먼저 올리고 선언 토픽 30개를 만든 뒤 "파티션 수 일치"를 확인하고 앱을 적용했다
   (D-107 래퍼의 첫 실제 스택 사용). product-service 는 MySQL 기동 전 `Connection refused` 로 2회 재시작한 뒤 06:53:33Z 에 Ready.
   측정 LB `10.178.0.18`, Prometheus target product-service up(측정 overlay 의 ServiceMonitor).
2. 블록 b1(L1-OFF) ~07:06:48Z, b2(L4-OFF) ~07:18:18Z, b3(L1-ON) ~07:30:53Z. 24 run 모두 셀의 상한 대조를 통과했고(meta
   `db_max_concurrency` 가 블록 기대값), MySQL Pod 는 세 블록 내내 `mysql-74f6f4cb4f-96k27` 이다. 블록 준비는 `d026-run-block.sh`
   를 `CELLS=" "` 로 돌리고, 셀 루프만 세션 래퍼로 돌려 run 마다 k6 구간(로그 끝 시각과 `running (…)` 길이)·annotate·Hikari
   원값을 남겼다. 셀 r1 은 사전 규칙대로 `warmup/` 으로 옮겼다.
3. 읽는 법 대입. 측정 run(r2~r4) 중앙값, 대괄호는 범위. 503 이외 오류는 모든 셀에서 0, MySQL 스로틀 차분도 모든 셀에서 0 이다.

| 블록(상한) | 셀 | 200 rps | 503 % | p95 ms | MySQL CPU 코어 | Hikari active 최대 | Hikari 대기 최대 |
|---|---|---|---|---|---|---|---|
| L1(1) | detail OFF | 26.5 [20.7~30.6] | 98.9 | 195 | 0.154 | 2 | 0 |
| L1(1) | list OFF | 30.3 [25.7~30.4] | 98.5 | 222 | 0.124 | 2 | 0 |
| L4(4) | detail OFF | 115.9 [87.9~122.0] | 94.5 | 214 | 0.391 | 5 | 0 |
| L4(4) | list OFF | 123.7 [71.7~136.8] | 93.5 | 232 | 0.289 | 4 | 0 |
| L1(1) | detail ON | 2899.5 [2625.9~2941.8] | 5.9 | 186 | 0.082 | 1 | 0 |
| L1(1) | list ON | 2638.0 [2613.4~2694.2] | 0 | 279 | 0.047 | 1 | 0 |

   - 상한 비교: 두 endpoint 모두 L4 의 200 rps 범위가 L1 범위와 겹치지 않고 위에 있고, L4 에서 503 이외 오류가 늘지 않았고, Hikari
     대기 최대와 MySQL 스로틀 차분이 0 이다. 네 조건이 모두 성립해 "상한 4 증액 근거 있음" 으로 적는다. ADR-0037 갱신은 사용자가
     정한다. 한계는 §2.2 대로 읽기 전용 부하에서의 풀 여유이고, 15초 scrape 보다 짧은 순간값은 놓친다.
   - ③: detail ON 측정 run 의 `product` hit 차분 168k~186k, miss 0 이라 측정된 것으로 본다. productStock(TTL 5초) miss 가 상한 1 을
     지나며 503 이 4.8~6.1% 섞였다(ADR-0037 Consequences 의 "cold miss 가 겹치면 503").
   - ④: list ON 측정 run 에서 product-service 가 있는 노드(`-28hh`, 10.178.0.14)의 CPU 최대는 84.0~84.6%(15초 step, export 값)로
     90% 미만이라 이 조건에서 노드 경합 가설을 기각한다. 같은 run 의 MySQL CPU 는 0.047코어로 0.05 미만이라 hit 경로가 MySQL 을
     거의 쓰지 않는다는 관측으로 적는다. product 컨테이너는 list ON 에서 60초에 10.8초 스로틀됐다(한도 3000m). Grafana 노드 CPU
     패널 화면은 자동 rate 창이라 최대 약 65% 로 더 평평하게 보인다.
4. 캡처 17장은 블록 구간(첫 측정 run 시작 60초 전~마지막 run 끝 60초 뒤, run 구간은 annotation)으로 찍었다. 대상은 OFF 블록 2개의
   `d078-1`·`d078-2`, list ON 블록의 `d078-4`, detail ON r3 의 `d078-jvm` 이다. export 는 측정 run 마다 28회(파일 89개)다. CPU 스로틀
   패널은 이번에는 값이 나온다(D-106 ②). 07:33:36Z 에 `peekcart` namespace 를 지웠다.

### S3. 경합: ⑤⑥·A~C, D-106 ⑦⑤ 반영 (약 1.5시간)

S3 의 outbox 는 ADR-0045 의 base 기본값(500ms·배치 500·락 최소 보존 400ms)이다. 측정 overlay 는 order outbox 를 덮어쓰지 않는다(#242).

1. 배포: `DRAIN_PREFLIGHT_SKIP_REASON="측정 overlay, notification 없음" bash scripts/deploy-overlay.sh
   k8s/overlays/gke-d078-contention`(digest·PROJECT 치환본, `GW_URL` 없이). 래퍼가 kafka 를 먼저 올리고 선언 토픽을 사전
   생성한다(D-107, #241). 이어서 payment Secret 에 Toss smoke 값을 넣고 재기동한다(`loadtest/README.md` D-002b'/c §스택).
2. 시드: 사용자 450명 → 인증 왕복 게이트 → `users.csv` 450행 확인 → 상품 1,000개(`--count 1000 | tee .../s3/seed-products.log`,
   `PRODUCT_IDS` 를 파일로 저장해 loadgen `/tmp/product-ids.txt` 로 복사, 개수 1,000 확인) → 429 프로브 PASS.
3. 토픽 상태 가드: `python3 scripts/kafka-topic-state-guard.py --services order,product,payment --evidence-dir
   loadtest/reports/<날짜>/s3/topic-guard`. exit 0 이어야 첫 run(워밍업 포함)을 시작한다. exit 1 이면 README 처치(5분 대기 또는
   소유 서비스 재기동) 뒤 다시 돌린다. 실제 앱 스택 양성 대조의 첫 기록(D-106 ⑩)이자 D-107 재검토 조건의 관측이다.
   재기동이 있으면 가드를 다시 돌린다.
4. run 순서: 워밍업 `w1`(contend, 결과와 무관하게 버린다) → 측정 `c1`·`c2`·`c3`(contend) → 측정 `s1`(spread). spread 는
   같은 Pod 에서 contend 뒤에 돌아 따로 워밍업하지 않는다(사전 규칙). 명령(loadgen 에서):
   ```bash
   k6 run --summary-export=/tmp/<run>.json -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS="$(cat /tmp/product-ids.txt)" \
     -e USERS=450 -e RPS=170 -e DUR=60s -e MODE=<contend|spread> /tmp/d002bc-contention.js
   ```
   exit 코드와 요약의 `부하 판정` 을 run 마다 남긴다. 무효(exit 99, `부하 판정: 무효`)인 측정 run 은 `invalid/` 로 옮기고
   배수·초기화 뒤 같은 모드를 다시 돌린다. exit 99 인데 부하 판정이 유효면 기존 429 threshold 가 실패한 것이다. 사용자별
   한도가 섞인 run 이라 같은 방식으로 무효 처리한다. exit 100(setup 시간 초과)이면 그 run 은 측정이 시작되지 않은 것이다.
5. run 마다: 직전에 product Pod 3개의 `/actuator/prometheus` 원값, k6 뒤 배수(order·product outbox PENDING 0, 전 consumer
   group lag 0, 2초 간격), 직후 원값, 지연 원자료(`d078-saga-latency.sql`·`d078-outbox-latency.sql` → TSV), 정합성
   (`d002bc-verify.sql`, 음수 재고 0·품목 diff 0·예약 상태 합 + DLQ = 주문 수), 초기화(`d002bc-reset.sql`·
   `d002bc-product-reset.sql`). 워밍업 `w1` 은 k6 요약(T95·판정)만 남기고 나머지 원자료는 쓰지 않는다.
6. ⑥ 은 배수 구간의 `executor_active_threads`·`executor_queued_tasks` 와 backlog 가 있는데 발행률이 0 인 구간을 본다.
7. 캡처: 측정 run 마다 `d078-5`·`d078-5-drain`·`d078-6`, 경합 run 1개 `d078-jvm`.
8. 읽는 법: ⑤ 는 DLQ/주문 비율의 c1~c3 중앙값·범위를 Phase 4(0.072%)·2026-10-24(0.040~0.068%)와 나란히 적는다. 충돌 수는
   전달 실패 counter 로 적고 세는 단위가 다른 Phase 4 충돌 수와는 비교하지 않는다. ⑦ 은 §2.2 규칙. 만료 취소는 0 이
   기대값이다(ADR-0045). 만료 취소 1건 이상, 사가 왕복 p99 4분(5분 컷오프의 80%) 초과, product 발행률이 order 의 부하 뒤
   발행률보다 낮아 product backlog 가 쌓임 중 하나라도 나오면 ADR-0045 재검토 조건 1 의 입력으로 넘긴다(근거: saga 지연 TSV,
   outbox 지연·배수 TSV, counts). 만료 취소가 있으면 그 run 의 배수 시간과 ⑤ 에 취소 경로가 섞였다고 함께 적는다.
9. 끝나면 `kubectl delete ns peekcart`.

**S3 실행 기록 (2026-10-25). 측정 run 진입 불가, contend·spread 모두 "부하 미달"로 접음.** 원자료는
`loadtest/reports/2026-10-25/s3/`(관찰 run 은 `warmup/`), 진단 표본은 `warmup/w6-mysql-sample.txt`, export·캡처는 `grafana/` 다.

1. 진입: 래퍼 배포 07:33:44~07:38:41Z(exit 0, 토픽 30개 생성·파티션 일치). Toss smoke 주입 뒤 전체 rollout 07:40:51Z. 사용자 451명
   (450명과 admin), 인증 왕복 게이트 PASS(gateway port-forward 경유). 상품 1,000개(07:41:25~07:44:11Z, 가격 캐시 1000/1000,
   `PRODUCT_IDS` 3,892바이트). 429 프로브 PASS(5,260건). 토픽 가드 exit 0(30개·22 group, 실제 앱 스택 양성 대조의 첫 기록).
2. w1(07:45:41Z) 부하 중 order-service 가 liveness(timeout 1초 × 3회)에 답하지 못해 kubelet 이 재시작시켰다(07:48:36Z, exit 143).
   재기동 동안 gateway 가 Connection refused 로 `order-cart` 500 을 6,519건 냈다(k6 거절). 재기동 직후 run 도 같은 재시작
   (07:54:15Z)을 겪었다. README D-106 ⑪ 에 따라 결과를 보기 전에 그 run 을 워밍업 w2 로 돌렸다.
3. 사용자 결정으로 세션 안에서 고쳤다(§5 의 예외): `k8s/overlays/gke-d078-contention/patches/order-service-probes.yml`
   (liveness timeout 5초·12회, readiness 5초·6회, kubelet 판정만 바꾼다). 래퍼로 재적용(08:24:50~08:29:44Z), order-service 만
   재기동, 토픽 가드 재실행 exit 0(08:31:01Z). 이후 재시작 0, 거절 0.
4. 부하 판정은 계속 무효였다. 수락이 w3 3,108 → w4 5,663 → w5 7,055 → w6 7,341(목표 10,200)로 초당 약 122건에서 멈췄다.
   워밍업 1회 규칙이 부족해 결과를 보기 전에 "첫 유효 run 까지 워밍업으로 본다(연장 최대 3회)"로 바꿨고, 연장 3회가 모두 무효라
   contend 를 접었다. spread s1 1회도 무효(수락 7,748)라 같은 고정 상한으로 보고 재시도 없이 접었다(편차).
5. 상한의 위치: w5 부하 구간에서 order-service CPU 평균 0.60·최대 1.38코어(한도 2), Hikari active 10/10·pending 평균 153·최대 192,
   MySQL CPU 0.43코어. w6 부하 구간 MySQL 표본은 행 잠금 대기 175건/85초(평균 10ms, 표본 7회 모두 대기 0), 실행 스레드 대부분
   `waiting for handler commit`, 커밋 245/s·log fsync 276/s 였다. 커넥션은 잠금이 아니라 커밋(fsync) 완료를 기다린다. 부하 중
   order outbox 발행률은 초당 50~63건이라 backlog 가 5~7천 건 쌓였다가 부하 뒤 빠졌다. 2026-10-24 r3 는 같은 order 풀 10 으로
   초당 168건이었다. ADR-0045 로 product outbox 발행·커밋이 늘어 공유 MySQL 의 커밋 지연이 커졌다는 가설은 A/B 없이 검증하지 않았다.
6. 관찰(부하 판정 무효 run, 측정값으로 쓰지 않는다):

| run | 모드 | 수락 | 만료 취소 | 전달 실패 | DLQ | 배수 outbox / lag | 사가 왕복 p50 / p99 | order outbox 대기 p50 / p99 |
|---|---|---|---|---|---|---|---|---|
| w6 | contend | 7,341 | 0 | 36 | 5 (0.068%) | 124초 / 204초 | 122초 / 198초 | 83초 / 109초 |
| s1 | spread | 7,748 | 0 | 0 | 0 | 126초 / 171초 | 96초 / 127초 | 90초 / 120초 |

   - ⑦: 상품 1,000개 spread 는 전달 실패 0 으로 contend(36)의 10% 이하다. 대조군 설계는 의도대로 저경합이다.
   - ADR-0045: 만료 취소 0, 사가 왕복 p99 가 4분 아래라 재검토 조건 1 의 신호는 없다. 다만 사가 왕복의 대부분이 order outbox
     대기이고 부하 중 order 발행률이 수신을 못 따라간다(재검토 조건 2 의 관찰).
   - ⑥: taskScheduler active 최대는 order 3 / 풀 4(캡처 `d078-6`).
7. 캡처 18장(contend 관찰 블록 w4~w6, spread s1, w6 JVM)과 export 42개(관찰 run 5개)를 남겼다. 09:09:25~09:10:37Z 에 namespace 를
   지웠다.

### S4. base overlay: D-065 ②⑤ 재확인 (약 0.5시간)

1. 기본 노드풀을 3대로 늘린다(base requests 약 7.3 vCPU). notification Secret 의 Slack 두 값은 사용자가 넣고, payment 는
   Toss smoke 값이다.
2. 첫 배포는 `DRAIN_PREFLIGHT_SKIP_REASON="fresh namespace, no prior stack" bash scripts/deploy-overlay.sh k8s/overlays/gke`
   (`GW_URL` 없이) → 사용자 시드 → `GW_URL=<gateway> bash scripts/auth-roundtrip-gate.sh`.
3. D-065 ②: `bash scripts/replay-drain-preflight.sh` 가 exit 0 이어야 한다(replay 이력 없음, 쓰인 적 없는 DLQ 파티션
   `- 0 -` 은 lag 0, #232). 이어서 skip 없이 `GW_URL=<gateway> bash scripts/deploy-overlay.sh k8s/overlays/gke` 를 한 번 더
   돌려 preflight 가 래퍼 경로에서 실제 스택을 관통한 기록을 남긴다.
4. D-065 ⑤: `python3 scripts/kafka-topic-state-guard.py --evidence-dir loadtest/reports/<날짜>/s4/topic-guard`(`--services`
   생략, notification 포함 업무 group 27개)가 exit 0 이어야 한다. 토픽 30개(payment 6개 포함)의 파티션 수와 계약 7키
   실효값이 선언과 같다는 증거다. 각 항목의 완료 기준은 TASKS 의 원래 행을 따른다.
5. ⑧·D-092·E 는 2026-10-24 에 끝나 반복하지 않는다.

**S4 실행 기록 (2026-10-25).** 원자료는 `loadtest/reports/2026-10-25/s4/` 에 있다.

1. 기본 노드풀을 3대로 늘리고(09:10:42Z~) 첫 배포 래퍼(`fresh namespace` 사유로 preflight skip)가 exit 0, 토픽 30개 생성·일치. payment 는
   Toss smoke 값이다. notification Slack 두 값은 계획과 달리 실제 webhook 대신 base 기본값과 같은 loopback(`http://127.0.0.1:9/user`·
   `/ops`)을 넣었다. 이번 S4 는 D-092 를 반복하지 않아 실제 전달이 필요 없고, 사용자가 자리를 비운 동안 실제 시크릿 값을 다루지
   않기 위해서다. 전체 rollout 과 사용자 시드(451명) 09:19:05Z, 인증 왕복 게이트 PASS. gateway 는 Internal LB 라 로컬에서는
   `kubectl port-forward svc/gateway` 주소를 `GW_URL` 로 썼다.
2. D-065 ②: `replay-drain-preflight.sh` 단독 실행 exit 0("drain 4조건 충족", 09:19:39~09:22:03Z). 이어서 skip 없이
   `GW_URL=<port-forward> bash scripts/deploy-overlay.sh k8s/overlays/gke` 가 preflight OK, kafka 선행·토픽 일치, apply, 인증 게이트
   PASS 순으로 exit 0(09:22:13~09:29:18Z). 수집 계층이 실제 스택과 래퍼 경로 양쪽에서 관통했다(2026-10-24 는 Kafka 포트 고정으로 실패).
3. D-065 ⑤: `kafka-topic-state-guard.py`(`--services` 생략) exit 0. 토픽 30개(payment 6개 포함)의 파티션 수와 계약 7키 실효값이
   선언과 같고, notification 을 포함한 업무 group 27개가 모든 파티션을 할당받았다(09:29:31~09:30:04Z, violations 0).
4. 클러스터를 지우기 전 환경 fingerprint 를 `loadtest/reports/2026-10-25/environment-fingerprint.txt` 에 남겼다.

### S5. 정리 (어떤 경로에서도 실행)

0. 진입 조건: S2·S3 의 캡처·export·TSV 가 저장돼 있다. 중단 규칙으로 왔으면 남은 데이터만이라도 export 한다.
1. `CLUSTER_NAME=peekcart-d078 LOADGEN_NAME=peekcart-d078-loadgen bash loadtest/cleanup.sh`, 7종 전부 `[0]`.
2. AR 리포지토리를 지운다. 시크릿 4개·GSA 2개는 재측정이 마지막 과금 세션이면 지운다(사용자 확인).
3. `gcloud billing projects unlink peekcart-d078`, gcloud configuration 원복.

**S5 실행 기록 (2026-10-25).** 진입 조건(S2·S3 캡처 35장·export 131개·S3 TSV 56개)을 확인한 뒤 09:31:07Z 에 `cleanup.sh` 를 돌렸다.
클러스터·loadgen 삭제, 미부착 디스크 4개 회수, 7종 모두 `[0]`(`loadtest/reports/2026-10-25/cleanup.txt`). AR 리포지토리를 지우고
09:36:44Z 에 결제 연결을 끊었다(`billingEnabled: false`). gcloud configuration 은 `default` 로 되돌렸다. Secret Manager 시크릿 4개와
GSA 2개는 S3 가 측정 run 에 들어가지 못해 다음 과금 세션이 남으므로 지우지 않았다(사용자 확인 대기). 세션용 치환 7개 파일은
`git restore` 했고, 세션 허용 규칙(gcloud·kubectl·helm·crane)은 `.claude/settings.local.json` 에서 지웠다. 클러스터 가동은
06:30:50Z~09:3xZ 약 3시간 5분(S4 의 3번째 노드 약 25분)이라, §6 단가로 약 $1.7~2.0 추정이다. 결제 콘솔 확인 전 값이다.

## 5. 중단 규칙

- 클러스터 안에서 디버깅하지 않는다. 스모크 1회 재시도 후에도 실패하면 S5 로 간다(`loadtest/README.md` 가드레일).
- 목표 미달도 결과다. 세션 중 USERS·RPS·DUR·상한·REPS 를 바꾸지 않는다.
- 예산 50% 알림이 오거나 클러스터 생성 후 8시간이 지나면 남은 블록을 접고 S5 로 간다.
- S2: 셀이 상한 불일치로 측정을 거부하면 그 블록을 접는다(overlay·env 결함이라 세션 안에서 고치지 않는다). 다른 블록은
  진행한다.
- S3: 토픽 가드가 처치 1회 뒤에도 exit 1 이면 S3 를 접고 S4 로 간다. 가드 출력은 D-107 의 입력이다.
- S3: 부하 판정이 무효인 측정 run 은 모드마다 2회까지 다시 돌린다. 넘으면 그 모드를 접고 "부하 미달" 로 적는다.
- S3: 한 run 의 배수가 15분을 넘으면 그 run 의 정합성만 확인하고 S3 를 접는다(D-075 처분이 측정 조건에서 기대대로 동작하지
  않는다는 결과이며 세션 안에서 고치지 않는다). 15분은 세션 시간 가드다. 배수가 5분을 넘은 run 은 이미 예약 미확정 만료
  취소가 생기므로 그것만으로 ADR-0045 재검토 입력이다.
- S4: preflight 나 가드가 1회 재시도 뒤에도 실패하면 결과로 적고 S5 로 간다.

## 6. 비용 (서울 온디맨드, 2026-10-22 Cloud Billing 카탈로그)

시간당 단가는 지난 계획과 같다: `e2-standard-4` × 2 $0.344, `monitoring-pool` $0.086, loadgen $0.043, 디스크·LB 약 $0.06,
GKE 관리비 $0.10, 합계 약 $0.53~0.63. S4 의 3번째 노드 +$0.17.

| 단계 | 2026-10-24 계획 | 이번 계획 | 근거 |
|---|---|---|---|
| S1 | (준비·정리 1.5h 에 포함) | 0.3h | 2026-10-24 실측: 클러스터 생성 16:24Z, 첫 S2 run 16:37Z |
| S2 | 2h (9셀 × 4회 = 36 run, MySQL 재기동 3회) | 1.2h (6셀 × 4회 = 24 run, MySQL 고정) | 토글 없는 run 간격 78초(2026-10-24 S2), 블록 전환 3회 각 3~4분, 배포·시드 10분, 캡처 15분 |
| S3 | 1.5h | 1.5h (재시도 최악 2.2h) | run 5개 × 약 11분(k6 run 시작에서 배수 끝까지 6.4~10.9분 실측, setup 이 450명으로 약 1분 늘어남), 배포·시드·가드 20분, 캡처 15분. 재시도는 모드당 2회 × 2모드 × 약 11분. 실측 배수는 D-075 처분 전 값이라, ADR-0045 추정대로 부하 뒤 배수가 70초~2분이면 S3 는 약 1시간으로 준다. 표는 보수적 추정이다 |
| S4 | 1.5h (⑧·D-092·E 포함) | 0.5h | D-065 ②⑤ 만 |
| 캡처 | 0.5h | S2·S3 에 포함 | |
| 정리 | 준비와 합쳐 1.5h | 0.3h | |
| 합계 | 7~9h, $4.5~6 | 3.8h(최악 4.5h), 약 $2.1~2.9 | 부가세 포함 약 ₩3,000~4,200 |

S2 재정의로 과금 시간이 약 0.8시간(약 $0.4~0.5) 준다. S3 는 setup 이 run 마다 약 1분 늘고(USERS 450), 상품 1,000개 시드가
약 2분, 부하 판정 재시도가 최악 4회 × 약 11분 늘 수 있다. 예산 ₩20,000 의 4분의 1 안쪽이다. 2026-10-24 실제 세션은
S2 를 4 run 만에 접어 1.9시간·약 $1.1 이었다.

## 7. 검증 방법 (이 PR)

| 항목 | 실패 주입 → 확인 |
|---|---|
| P1 | `node --test loadtest/scripts/test_d002bc_contention.mjs`(8개)가 k6 모듈만 대역으로 바꾸고 원 스크립트를 실행한다. 원 요약 JSON 4개로 r1·r2 무효, r3·spread 유효이고 k6 threshold 식의 평가와 판정이 같다. 경계 102 유효·103 무효, DUR 3m·1m30s 한도. 같은 검사를 origin/main 의 스크립트에 돌리면 7개가 실패한다(spread 는 상품 23/1,000). 변이 7종(1% 를 10% 로, `<=` 를 `<` 로, 전역 순번을 `__ITER` 로, setupTimeout 삭제, 0건을 무효로, 여유 1.5 를 1.0 으로, PRODUCT_IDS 검사 삭제)이 각각 해당 검사로 실패한다. 로컬에 k6 가 없어 `k6 inspect` 는 하지 않았고, `__ITER`·`iterationInTest`·`setupTimeout` 기본값·exit 99/100 은 k6 문서와 소스로 확인했다 |
| P2 | 같은 검사가 `users.csv` 행 수·사용자 시드의 `n < 450`·USERS 를 대조한다(`users.csv` 를 449행으로 바꾸면 실패). 시드 스크립트를 지연 0 인 curl·kubectl 대역(40건/1초 창 강제)으로 `--count 1000` 실행해 exit 0, PRODUCT_IDS 1,000개, 캐시 조회가 id 1,000개를 받는지, 초당 최대 생성 수와 429 건수를 본다. 간격을 지운 사본의 초당 최대 생성 수도 잰다 |
| P3 | 상한 1·4 렌더 diff 가 env 값 한 줄뿐이고, origin/main 대비 8개 overlay 렌더에서 `gke-d002a` 계열만 env 2줄이 는다. 셀 스크립트를 kubectl 대역으로 돌려 Pod 값 1·미설정에 기대 4 면 측정 전에 exit 1, 4·기대 없음이면 다음 단계로 간다. Spring 바인딩은 §2.1 의 jar 검사. 새 overlay 에 namespace 없는 리소스나 ServiceMonitor 삭제를 넣으면 두 lint 가 overlay 이름을 대며 exit 1 이다 |
| P4 | README·captures 의 이름·값(USERS 450, 상품 1,000, `status_503`, `d078-1`·`-2`·`-4`, 29092)이 코드와 같은지 대조한다 |
| 공통 | `kustomize-namespace-lint.sh`, `servicemonitor-selector-lint.sh`(+ `--self-test`), `ci-lint-health-selftest.py`, `ci-lint-wiring-lint.py`, `bash -n`(수정한 셸 3개), `writing-lint.sh`, `docs-preservation-ci.py`. 모듈 전체와 E2E 는 PR CI 로 확인한다 |

## 8. 완료 조건

P1~P6 가 끝나고 §7 결과를 audit 에 남기고 PR CI 가 성공하면 D-106 ①⑦⑤ 의 설계를 확정으로 기록한다. 이 계획서는 그때도
아카이브하지 않고 세션 계획으로 남긴다. D-078 의 각 항목은 세션 결과로 닫는다.

## 9. 범위 밖과 미해결

- 세션 실행(과금)과 증적·TASKS·PHASE5 반영은 세션 후속이다.
- D-106 ⑥(배수가 예약 미확정 만료(5분, `OrderTimeoutScheduler.cancelUnconfirmedReservations` 60초 주기)보다 길 때 대량
  만료 취소)은 D-075 가 outbox 기본값으로 처분했다(#242, ADR-0045). `gke-d078-contention` 의 order outbox patch 도 #242 가
  지웠다. 예약 lease(30분)는 이 취소와 관계가 없다.
- 배포 래퍼의 토픽 사전 생성은 D-107(#241)이다. S2·S3·S4 의 첫 배포가 그 래퍼를 쓴다. 블록마다 하는 `d026-run-block.sh` 의
  raw 재적용은 토픽이 이미 있어 그대로 둔다.
- 상한 증액 결정(ADR-0037 갱신)은 세션 결과를 보고 사용자가 정한다. 예약 소비와 겹친 상태의 풀 여유는 재지 않는다.
- `d002bc-order-create.js` 도 같은 순차 로그인 setup 이라 USERS 를 늘리면 같은 60초 제한에 걸린다. 이번 세션은 그 스크립트를
  쓰지 않아(E 는 끝났다) 고치지 않는다.
- 해석 메모: 사용자 결정의 "반복 p95 시간(워밍업 run 관측값)" 을 버리는 워밍업 run 이 아니라 워밍업이 끝난 run 의 값으로
  읽었다. 버리는 run 의 값을 쓰면 USERS 는 1,450 이다(§2.2 에 이유).
