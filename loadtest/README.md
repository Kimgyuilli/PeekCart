# PeekCart 부하 측정

> 측정 환경·방침: `docs/04-design-deep-dive.md` §10-7 · `docs/01-project-overview.md` §4-3 · `docs/03-requirements.md` §7-1 · ADR-0004
> Phase 3 모놀리스 절차와 D-002b'/c(Phase 4) 세션 절차의 원문은 [loadtest README 아카이브](../docs/progress/archive/loadtest-readme-phase3-4.md)에 있다.

GKE 측정 세션의 k6 시나리오, 시드·초기화·검증 SQL, run·캡처·집계 스크립트와 세션 원자료를 둔다. 애플리케이션 코드·Flyway migration 과는 따로 관리한다. 세션마다 무엇을 어떤 순서로 재는지는 그 세션의 계획서(`docs/plans/`)가 정하고, 이 문서는 세션이 공통으로 따르는 절차와 규칙을 적는다.

## 디렉토리 구조

```
loadtest/
├── README.md                          (이 문서)
├── captures.md                        캡처 기준과 세션 뒤 정리 규칙
├── cleanup.sh                         GCP 정리 (ADR-0004 운영 체크리스트)
├── scripts/
│   ├── d002a-product-read.js          읽기 경로 k6 (상품 목록·상세)
│   ├── d026-run-cell.sh               읽기 경로 셀 1회 (조건 캡처, 캐시 초기화·워밍업, counter 차분)
│   ├── d026-run-block.sh              MySQL CPU 조건 블록 1개 (셀 반복)
│   ├── d026-collect.py                읽기 경로 집계 (셀 중앙값·산포·게이트)
│   ├── d002bc-seed-products.sh        admin API 로 상품 시드, 가격 캐시 전파 대기
│   ├── d002bc-ratelimit-probe.js      rate limit 배선 확인 (429 가 나와야 통과)
│   ├── d002bc-order-create.js         주문 생성 k6 (기준선·HPA)
│   ├── d002bc-contention.js           재고 경합 k6 (MODE=contend·spread, 부하 판정 포함)
│   ├── d078-s3-run.sh                 경합 run 1개 (원값 전후, k6, 배수, 지연 원자료, 정합성, 초기화)
│   ├── d078-mysql-sampler.sh          run 중 MySQL 진단 표본
│   ├── d078-s3-export.sh              run 구간 원 시계열 export
│   ├── d078-capture.py                Grafana annotate·캡처 URL·export
│   ├── d078-latency.py                사가 왕복·outbox 발행 대기 집계
│   ├── d078-warmup-check.py           워밍업 수렴 판정
│   ├── generate-users-csv.sh          users.csv 재생성
│   ├── users.csv                      k6 입력 (사용자)
│   ├── test_*.py · test_*.mjs         스크립트 단위 테스트
│   ├── ngrinder-product-query.groovy  Phase 3 세션 B 스크립트 (이력)
│   └── order-concurrency.js           Phase 3 세션 C 스크립트 (이력)
├── sql/
│   ├── d002a-product-seed.sql         읽기 경로 상품 시드 (product 스키마)
│   ├── d002bc-user-seed.sql           사용자 시드 (450명 + admin 1)
│   ├── d002bc-reset.sql               run 사이 order 스키마 초기화
│   ├── d002bc-product-reset.sql       run 사이 product 스키마 초기화
│   ├── d002bc-verify.sql              재고 정합성 검증
│   ├── d078-saga-latency.sql          사가 왕복 지연 원자료 (order)
│   ├── d078-outbox-latency.sql        outbox 발행 대기 원자료 (order·product)
│   ├── seed.sql                       Phase 3 모놀리스 시드 (이력)
│   └── verify-concurrency.sql         Phase 3 정합성 검증 (이력)
└── reports/
    ├── TEMPLATE.md                    Phase 3 세션 리포트 템플릿 (이력)
    └── <YYYY-MM-DD>/                  세션 원자료와 기록
```

## 과금 세션 규칙

측정 세션은 GCP 과금이 발생한다. 아래 규칙은 결과와 무관하게 지킨다.

1. **클러스터 안에서 디버깅하지 않는다.** 이상이 생기면 스모크 1회 재시도 뒤에도 실패할 때 바로 정리 단계로 가고, 원인은 로컬에서 재현한다. 클러스터 시간이 곧 과금 시간이다.
2. **목표 수치 미달도 유효한 결과다.** 세션 중 튜닝·재설계를 하지 않는다. 수치는 그대로 기록하고 개선은 별도 작업으로 뗀다.
3. **판정 규칙은 세션 전에 고정한다.** 워밍업 횟수, 무효 판정, 재시도 상한, 설정 전환은 세션 계획에 미리 적고 결과를 본 뒤 바꾸지 않는다.
4. **정리는 측정 실패·중단과 무관하게 반드시 실행한다.**
5. **세션에서 치환한 매니페스트는 커밋하지 않는다.** 이미지·`PROJECT_ID` 치환은 로컬 상태이고 세션이 끝나면 `git restore` 로 되돌린다.

## 전제 조건

1. GKE 클러스터와 같은 VPC 의 부하 발생기 VM. 구성은 `docs/01-project-overview.md` §4-3 이고 VM 에는 k6 를 설치한다.
2. 이미지 6개를 Artifact Registry 로 승격하고(`scripts/promote-images.sh`) overlay 의 `PROJECT_ID_PLACEHOLDER` 를 로컬에서 치환한다(`k8s/overlays/gke/README.md`, 커밋 금지).
3. 관측 스택(kube-prometheus-stack, ServiceMonitor CRD)을 먼저 설치한다(`k8s/overlays/gke/README.md` 1~3단계). 측정 overlay 는 앱마다 ServiceMonitor 를 포함하므로 CRD 가 없으면 적용이 실패한다.
4. 예산 알림과 클러스터 수명 한도를 확인한다.

## 측정 overlay

| overlay | 무엇을 재나 | 구성 |
| --- | --- | --- |
| `k8s/overlays/gke-d002a` | 읽기 경로(상품 목록·상세) | product + MySQL + Redis. 인증·rate limit 이 섞이지 않게 gateway 와 NetworkPolicy 를 뺐고, 조회 DB 진입 상한 1 을 고정한다 |
| `gke-d002a-mysql-500m` · `gke-d002a-mysql-2000m` | 읽기 경로의 MySQL CPU 상한 대조 | 위에 MySQL limits 를 500m(대조군)과 2000m 로 고정 |
| `gke-d078-read-bulkhead-4` | 조회 DB 진입 상한 4 (ADR-0037·ADR-0046) | `gke-d002a-mysql-2000m` 위에 상한 4 |
| `gke-d002bc` | 주문·경합 경로 | gateway·user·product·order·payment + MySQL·Redis·Kafka. notification 과 HPA 를 빼고 MySQL 을 500m 로 내린다(D-002b'/c 재현) |
| `gke-d002bc-mysql-2000m` | ADR-0027 이후 조건의 주문·경합 | `gke-d002bc` 위에 MySQL 2000m |
| `gke-d078-contention` | 동일 상품 경합의 낙관락 충돌·DLQ | `gke-d002bc-mysql-2000m` 위에 product replicas 3 을 고정 |
| `gke-d078-contention-product-outbox-5s` | product outbox 주기 대조 | `gke-d078-contention` 위에 product outbox 를 ADR-0045 이전 값(5s·4s·100)으로 |
| `gke` | HPA 와 base 적용 확인 | 운영 overlay (notification·HPA 포함) |

## 배포

```bash
DRAIN_PREFLIGHT_SKIP_REASON="측정 overlay, notification 없음" bash scripts/deploy-overlay.sh k8s/overlays/<overlay>
```

배포 래퍼가 kafka 를 먼저 올려 선언 토픽을 만들고 파티션 수를 대조한 뒤 앱을 적용한다(D-107). 빈 namespace 의 첫 배포와 notification 이 없는 측정 overlay 는 drain preflight 를 돌릴 수 없으므로 `DRAIN_PREFLIGHT_SKIP_REASON` 에 사유를 남겨 건너뛴다(`k8s/overlays/gke/README.md` 4' 단계).

apply 직후 payment Secret 에 Toss **smoke 값**을 넣고 재기동한다(D-106 ④). k8s 프로파일은 `TOSS_SECRET_KEY` 기본값이 없어 fail-fast 하고, 매니페스트에는 이 값을 두지 않는다. 값은 `scripts/docker-health-smoke.sh` 와 같으며 실제 자격증명이 아니다. Toss 연동은 검증하지 않는다.

```bash
kubectl -n peekcart patch secret payment-service-secret --type merge \
  -p '{"stringData":{"TOSS_SECRET_KEY":"test_sk_smoke","TOSS_WEBHOOK_SECRET":"test_webhook_smoke"}}'
kubectl -n peekcart rollout restart deploy/payment-service
```

주문 경로 overlay 에서는 **gateway 를 뺄 수 없다.** order·payment 는 gateway 가 서명한 내부 토큰만 인증 주체로 받으므로(`SIGNED_ONLY`) 평문 `X-User-*` 로는 401 이다. 그래서 이 overlay 들은 Secret Manager CSI 와 Workload Identity 설정이 필요하다(gateway 개인키).

## 측정 전 게이트

배포·재기동 뒤 첫 run(워밍업 포함) 전에 아래를 차례로 통과시킨다.

### 1. 시드와 인증 왕복

```bash
# 사용자 (450명 + admin 1)
kubectl -n peekcart exec -i deploy/mysql -- \
  mysql -upeekcart_user -ppeekcart_user peekcart_user < loadtest/sql/d002bc-user-seed.sql

# 인증 왕복 게이트 — 시드 계정(loadadmin)으로 로그인하므로 사용자 시드 뒤에 돈다
GW_URL=http://<gateway-internal-lb>:8080 bash scripts/auth-roundtrip-gate.sh

# k6 입력 CSV — USERS 와 같은 수로 재생성한다(적으면 계정이 겹쳐 사용자별 한도가 무너진다)
bash loadtest/scripts/generate-users-csv.sh --count 450

# 상품 — SQL 직접 INSERT 금지. admin API 를 태워야 product.updated 가 발행되고
# order 의 product_price_cache 가 찬다. 스크립트가 캐시 전파까지 기다린다.
GW=http://<gateway-internal-lb>:8080 bash loadtest/scripts/d002bc-seed-products.sh --count 1000 \
  | tee loadtest/reports/<날짜>/s3/seed-products.log
PRODUCT_IDS=$(sed -n 's/^PRODUCT_IDS=//p' loadtest/reports/<날짜>/s3/seed-products.log)
```

인증 왕복 게이트는 키쌍 불일치(사용자 토큰·내부 토큰)를 어느 도메인이 어긋났는지까지 지목한다. Phase 4 세션은 이 게이트를 건너뛰어 불일치 2건을 배포 뒤에야 찾았다(D-022). 읽기 경로 overlay(`gke-d002a*`)는 gateway 가 없으므로 이 단계 대신 `loadtest/sql/d002a-product-seed.sql` 을 product 스키마에 넣는다.

### 2. rate limit 배선 확인

```bash
k6 run -e GW=http://<gateway-lb>:8080 loadtest/scripts/d002bc-ratelimit-probe.js
```

**429 가 나와야 통과다.** 0 건이면 한도가 안 걸리는 것이고, 그 상태의 "429 없음" 은 한도 아래에서 쟀다는 증거가 되지 못한다.

### 3. 토픽 상태 가드

```bash
# notification 이 없는 overlay 는 그 group 을 뺀다. base overlay 에서는 --services 를 생략한다.
python3 scripts/kafka-topic-state-guard.py --services order,product,payment \
  --evidence-dir loadtest/reports/<날짜>/<블록>/topic-guard
```

선언 토픽 30개의 파티션 수와 계약 7키의 실효값, 업무 consumer group 이 구독 토픽의 모든 파티션을 할당받았는지 본다(D-106 ⑩). exit 1 이면 run 을 시작하지 않는다. `[GROUP] ... 미할당` 은 구독 뒤 늘어난 파티션을 consumer 가 metadata 갱신(기본 5분) 전까지 받지 못한 상태일 수 있다(D-107 ②). 5분을 기다리거나 그 서비스를 재기동한 뒤 다시 돌린다. `[CONFIG]`·`[PARTITIONS]` 는 생성 경합으로 선언이 빠진 토픽이다. 그 토픽을 선언한 서비스를 재기동하고 가드를 다시 돌린다. 가드는 진입 시점 검사라 통과 뒤의 리밸런스는 막지 못하므로, run 뒤에는 배수·lag export 로 확인한다.

## run 규칙

- **워밍업**: 배포·재기동 직후 같은 시나리오의 워밍업 run 을 먼저 돌리고 버린다(D-106 ⑪). 횟수는 세션 계획에 미리 적고 사후에 골라 버리지 않는다. 수렴은 `d078-warmup-check.py <직전 run.json> <이번 run.json>` 이 주문 수락 건수 변화율(기본 5% 이내)로 판정하며, run 사이에 order·product 재시작이 있었으면 수렴으로 보지 않는다.
- **부하 판정**: 목표 부하가 들어간 run 만 표본으로 쓴다(D-106 ⑤). `d002bc-contention.js` 는 `dropped_iterations` 가 목표 반복 수(RPS × DUR)의 1% 를 넘으면 exit 99 를 내고 요약 첫 줄에 `부하 판정: 무효` 를 찍는다. 그 run 은 버리고 초기화 뒤 다시 돌린다(재시도 상한은 세션 계획).
- **USERS**: RPS × 반복 p95 × 1.5 를 50 단위로 올린 값이다. 반복 p95 는 워밍업이 끝난 run 의 k6 `iteration_duration` p95 이고, 요약이 run 마다 이 산식의 값을 찍는다. RPS 170 이면 450 이다. 사용자 시드와 `users.csv` 를 같은 수로 맞추고 `setupTimeout` 은 USERS 초다.
- **run 사이 초기화**: order·product 스키마를 초기화한다.

```bash
kubectl -n peekcart exec -i deploy/mysql -- mysql -upeekcart_order   -ppeekcart_order   peekcart_order   < loadtest/sql/d002bc-reset.sql
kubectl -n peekcart exec -i deploy/mysql -- mysql -upeekcart_product -ppeekcart_product peekcart_product < loadtest/sql/d002bc-product-reset.sql
```

## run 실행

### 읽기 경로

`d026-run-block.sh` 가 MySQL CPU 조건 하나의 블록을, `d026-run-cell.sh` 가 셀 1회를 돈다. 블록 순서는 A → B → A′ 다. MySQL Deployment 가 Recreate 전략이라 CPU 를 바꾸면 버퍼풀이 식으므로, 한 방향으로만 재면 CPU 효과와 재시작 효과가 섞인다.

```bash
MYSQL=500m CELLS="detail:off detail:on list:off list:on" bash loadtest/scripts/d026-run-block.sh
EP=detail CACHE=on MYSQL=500m REP=1 bash loadtest/scripts/d026-run-cell.sh
python3 loadtest/scripts/d026-collect.py .cache/d026-runs
```

`BULKHEAD` 로 조회 DB 진입 상한의 기대값을 주면 Pod env 와 다를 때 측정하지 않는다. 캐시 ON·OFF 는 product-service 의 `peekcart.cache.enabled`(env `PEEKCART_CACHE_ENABLED`)로 바꾸며, false 면 `NoOpCacheManager` 가 들어가 `@Cacheable` 이 그대로 통과된다.

### 주문·경합

```bash
# 경합 run 1개. MODE=contend 는 전 주문을 상품 1개로, spread 는 상품 1,000개를 전역 반복 순번으로 돈다
SESSION_DIR=loadtest/reports/<날짜> CONFIG=c0 RUN=r1 PREV_RUN=w1 MODE=contend GW_IP=<gateway-ip> \
  bash loadtest/scripts/d078-s3-run.sh

# 판정 run 과 함께 MySQL 진단 표본을 백그라운드로 남긴다
OUT=loadtest/reports/<날짜>/s3/c0/r1-mysql-sample.txt bash loadtest/scripts/d078-mysql-sampler.sh &
```

`d078-s3-run.sh` 는 product·order Pod 원값(전), k6(loadgen), 배수(outbox PENDING 0 그리고 전 group lag 0), 원값(후)과 차분, 지연 원자료 TSV, 건수·정합성, 초기화, annotate 순서로 돈다. 기본값은 USERS 450, RPS 170, DUR 60s 이고 `DRY_RUN=1` 이면 산출 경로와 k6 명령만 찍는다.

대조군(spread)을 상품 10개로 돌린 2026-10-24 세션은 대조군의 전달 실패가 경합 run 보다 많았다(D-106 ⑦). `order.created` 의 키가 주문 id 라 같은 상품의 주문도 세 파티션에 흩어지고, product replica 셋이 같은 상품 행에 동시에 쓸 수 있다. 그래서 대조군은 상품 1,000개를 쓴다.

### 주문 생성 기준선과 HPA

```bash
k6 run -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS="$PRODUCT_IDS" -e USERS=200 -e RPS=100 -e DUR=3m \
  --summary-export=loadtest/reports/<날짜>/s4/e-hpa.json loadtest/scripts/d002bc-order-create.js
```

k6 는 **동기 구간만** 낸다. 사가 완결 지연은 DB 원자료(`d078-saga-latency.sql`)로 잰다. HPA 측정은 `k8s/overlays/gke` 에서 돌리고 HPA 이벤트와 Pod 생성·Ready 시각을 함께 남긴다.

## 기록과 집계

- **캡처와 원 시계열**: `loadtest/captures.md` 의 기준을 따른다. `d078-capture.py` 가 annotate·캡처 URL·export 를, `d078-s3-export.sh` 가 run 구간 export 를 맡는다. 캡처와 export 가 저장된 것을 확인한 뒤에만 정리 단계로 간다.
- **건수**: 그래프가 아니라 `/actuator/prometheus` 원값 차분과 DB 조회로 센다.
- **지연**: 배수 뒤 초기화 전에 `d078-saga-latency.sql`(order)과 `d078-outbox-latency.sql`(order·product)을 TSV 로 내보내고 `d078-latency.py` 로 집계한다.
- **세션 기록**: `loadtest/reports/<날짜>/` 에 타임라인·이슈 기록과 원자료를 둔다. 커밋 전 정리(워밍업·무효 run TSV, k6 요약의 토큰, 큰 export)는 `loadtest/captures.md` §세션 뒤 정리와 날짜 폴더의 `pruned.tsv` 를 따른다.

## 정리 (절대 스킵 금지)

```bash
CLUSTER_NAME=<클러스터> LOADGEN_NAME=<부하 발생기 VM> bash loadtest/cleanup.sh --dry-run   # 먼저 확인
CLUSTER_NAME=<클러스터> LOADGEN_NAME=<부하 발생기 VM> bash loadtest/cleanup.sh             # 실제 삭제
```

기본 대상은 `peekcart-loadtest`·`peekcart-loadgen`(zone `asia-northeast3-a`)이므로 다른 이름으로 만든 세션은 환경변수로 넘긴다. 클러스터·VM·미부착 PD·예약 IP 잔여가 0 인지 확인하고, 세션에서 치환한 파일을 `git restore` 한 뒤, 익일 과금까지 확인한다.
