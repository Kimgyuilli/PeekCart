# PeekCart 부하 테스트 (Phase 3 Task 3-4)

> 측정 환경·방침: `docs/04-design-deep-dive.md` §10-7 · `docs/03-requirements.md` §7-1 · ADR-0004

본 디렉토리는 부하 테스트 시나리오·시드 데이터·리포트 템플릿·정리 스크립트를 포함합니다. 애플리케이션 코드·Flyway migration 과는 독립적으로 유지됩니다.

## 디렉토리 구조

```
loadtest/
├── README.md                          (이 문서)
├── cleanup.sh                         GCP 정리 (ADR-0004 운영 체크리스트)
├── captures.md                        측정 세션 캡처 기준 (D-078 ⑨)
├── sql/
│   ├── seed.sql                       시드 데이터 (users 1101, products 1010, 경합재고 1000)
│   └── verify-concurrency.sql         시나리오 2 정합성 검증 쿼리
├── scripts/
│   ├── ngrinder-product-query.groovy  시나리오 1 (상품 조회 TPS)
│   ├── order-concurrency.js           시나리오 2 (1,000 VUser 동시 주문)
│   ├── users.csv                      k6 입력 (샘플 5건)
│   └── generate-users-csv.sh          users.csv 재생성 (기본 1,100건)
└── reports/
    └── TEMPLATE.md                    측정 세션별 리포트 템플릿
```

## 세션 B/C 공통 운영 가드레일 (과금 세션 불변식)

측정 세션은 GCP 과금이 발생합니다. 다음 규칙은 세션 진입 후 **측정 결과와 무관하게 항상 유지**되어야 합니다.

1. **클러스터 안에서 디버깅하지 않는다.** TPS 0 / 에러율 이상 등 이상 상황에서는 스모크 1회 재시도 후에도 실패하면 즉시 F 단계(cleanup)로 진입하고, 원인 분석은 로컬에서 재현한다. 클러스터 시간 = 과금 시간.
2. **목표 수치 미달도 유효한 측정 결과다.** 세션 중 튜닝·재설계 금지. 수치는 그대로 기록하고 리포트에 원인 분석 노트만 추가한다. 개선 작업은 별도 Task 로 분리한다.
3. **cleanup 은 측정 실패·중단 여부와 무관하게 반드시 실행한다.** F 단계는 어떤 상황에서도 스킵하지 않는다.
4. **`k8s/overlays/gke/kustomization.yml` 이미지 치환은 커밋하지 않는다.** 세션 종료 시 `git restore k8s/overlays/gke/kustomization.yml` 로 복원한다 (operator 로컬 상태).
5. **같은 세션에서 다음 세션 범위로 진입하지 않는다.** 세션 B 는 시나리오 1 만, 세션 C 는 시나리오 2+3 만. 시간이 남아도 cleanup 후 종료하고 세션 C 를 위해 환경을 재프로비저닝한다 (3-세션 분할 근거: `docs/progress/PHASE3.md` 2026-04-08).

## 전제 조건 (측정 세션 진입 전)

1. GKE 클러스터 `peekcart-loadtest` 기동 (asia-northeast3-a, e2-standard-4 × 1)
2. peekcart 이미지가 Artifact Registry 에 push 되어 있음
3. `kustomize edit set image` 로 `PROJECT_ID_PLACEHOLDER` 가 실제 프로젝트 ID 로 치환됨 (**커밋 금지**, `k8s/overlays/gke/README.md` 참고)
4. 4단계 apply 완료 (namespace → infra → services → monitoring, `docs/02-architecture.md` §12)
5. 부하 발생기 VM `peekcart-loadgen` 에 JDK 11 (nGrinder agent 전용), nGrinder, k6 v0.49+ 설치 완료
6. billing alert ₩50,000 설정 확인

## 절차

### A. 시드 적용

```bash
# 1) k6 입력 CSV 재생성 (기본 1,100 users)
bash loadtest/scripts/generate-users-csv.sh

# 2) 클러스터의 MySQL Pod 로 seed.sql 실행
kubectl -n peekcart exec -i <mysql-pod> -- \
  mysql -upeekcart -p<password> peekcart < loadtest/sql/seed.sql

# 검증
kubectl -n peekcart exec <mysql-pod> -- \
  mysql -upeekcart -p<password> peekcart -e \
  "SELECT COUNT(*) users FROM users; SELECT COUNT(*) products FROM products; SELECT SUM(stock) contention_stock FROM inventories WHERE product_id > 1000;"
# users=1101 products=1010 contention_stock=1000
```

### B. 시나리오 1 — 상품 조회 TPS (캐싱 전/후)

```bash
# Baseline (캐시 OFF)
kubectl -n peekcart set env deployment/peekcart PEEKCART_CACHE_ENABLED=false
kubectl -n peekcart rollout status deployment/peekcart

# nGrinder controller UI:
#   script = loadtest/scripts/ngrinder-product-query.groovy
#   property: grinder.peekcart.baseUrl = http://<gke-internal-lb-or-cluster-ip>:8080
#   warm-up: 1분 / 10 VUser
#   run:     5분 / 50 VUser
# 실행 후 TPS · MTT · p95 · p99 · 에러율 기록

# 개선 후 (캐시 ON) — 동일 스크립트 재실행
kubectl -n peekcart set env deployment/peekcart PEEKCART_CACHE_ENABLED=true
kubectl -n peekcart rollout status deployment/peekcart
# 동일 warm-up + run 반복
```

### C. 시나리오 2 — 1,000 VUser 동시 주문

```bash
# 시드 재적용 (시나리오 1 잔여물 제거)
kubectl -n peekcart exec -i <mysql-pod> -- \
  mysql -upeekcart -p<password> peekcart < loadtest/sql/seed.sql

# Prometheus remote-write receiver 포트포워드 (values-prometheus.yml 에서 enableRemoteWriteReceiver: true 전제)
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &

# <internal-lb> 는 세션 C 과금 전에 `kubectl -n default get svc` 로 확보
mkdir -p loadtest/reports/YYYY-MM-DD/sql loadtest/reports/YYYY-MM-DD/grafana
k6 run \
  --summary-export=loadtest/reports/YYYY-MM-DD/k6-summary.json \
  -e BASE_URL=http://<internal-lb>:8080 \
  -o experimental-prometheus-rw=http://localhost:9090/api/v1/write \
  loadtest/scripts/order-concurrency.js

# Grafana k6 대시보드 import (최초 1회)
#   Grafana UI → Dashboards → Import → "19665" 입력 → Prometheus datasource 선택

# 정합성 검증
kubectl -n peekcart exec -i <mysql-pod> -- \
  mysql -upeekcart -p<password> peekcart < loadtest/sql/verify-concurrency.sql \
  | tee loadtest/reports/YYYY-MM-DD/sql/verify-concurrency-output.txt
```

### D. 시나리오 3 — Kafka Consumer Lag

시나리오 2 실행 구간 동안 Grafana "Kafka Lag" 대시보드를 관찰하며 PNG 캡처. 별도 부하 발생 없음.

### E. 리포트 작성

1. `loadtest/reports/TEMPLATE.md` 를 `loadtest/reports/YYYY-MM-DD/REPORT.md` 로 복사
2. (a)~(f) 항목을 채움
3. Grafana 스크린샷을 `loadtest/reports/YYYY-MM-DD/grafana/` 에 저장
4. k6-summary.json · Grafana k6 dashboard PNG · verify-concurrency-output.txt 첨부

### F. 정리 (절대 스킵 금지)

```bash
bash loadtest/cleanup.sh --dry-run    # 먼저 확인
bash loadtest/cleanup.sh              # 실제 삭제
```

종료 후 billing 콘솔에서 당일·익일 과금 확인.

## 캐시 토글 동작 확인

`peekcart.cache.enabled=false` 시 `NoOpCacheManager` 가 주입되어 `@Cacheable` 이 pass-through. 기본값은 `true`. 구현은 `src/main/java/com/peekcart/global/config/CacheConfig.java` 의 `@ConditionalOnProperty` 참고.

## 비고

- 절대값이 아닌 **개선 비율** 에 초점 (§10-7 공통 원칙)
- 환경·도구·시나리오 파라미터를 리포트에 반드시 함께 기록하여 재현 가능성 확보
- Task 3-5 (HPA 검증) 는 본 디렉토리 범위 외 — 별도 Task 로 수행

---

# D-002b'/c 측정 세션 (Phase 4)

> 위 절차는 **Phase 3 모놀리스** 기준이다(`seed.sql` 이 단일 스키마를 전제한다). DB-per-service
> 이후에는 쓸 수 없다. 이 절에서 쓰는 산출물은 전부 `d002bc-` 접두사를 가진다.
> 계획서: `docs/plans/done/task-d002-bc-session.md`

## 스택

overlay `k8s/overlays/gke-d002bc` — gateway + user + product + order + payment + mysql/redis/kafka.
notification 제외, HPA 제외(replicas 고정 — 오토스케일이 붙으면 처리량이 아니라 스케일러를 재게 된다).

측정 overlay 는 앱마다 ServiceMonitor 를 포함한다(D-106 ③). apply 전에 관측 스택(kube-prometheus-stack,
ServiceMonitor CRD)을 먼저 설치한다 — `k8s/overlays/gke/README.md` 1~3단계. CRD 가 없으면 apply 가 실패한다.

```bash
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/overlays/gke-d002bc | kubectl apply -f -
```

`--load-restrictor LoadRestrictionsNone` 은 `gke-d002a` 와 같은 이유로 필요하다(base 파일을
디렉터리가 아니라 파일 단위로 참조한다).

apply 직후 payment Secret 에 Toss **smoke 값**을 넣고 재기동한다(D-106 ④). k8s 프로파일은
`TOSS_SECRET_KEY` 기본값이 없어 fail-fast 하고(CrashLoop), 매니페스트에는 이 값을 두지 않는다(GP-2 #3).
값은 `scripts/docker-health-smoke.sh` 와 같다. 실제 자격증명이 아니며 Toss 연동은 검증하지 않는다.

```bash
kubectl -n peekcart patch secret payment-service-secret --type merge \
  -p '{"stringData":{"TOSS_SECRET_KEY":"test_sk_smoke","TOSS_WEBHOOK_SECRET":"test_webhook_smoke"}}'
kubectl -n peekcart rollout restart deploy/payment-service
```

사용자 시드(§1) 직후 **인증 왕복 게이트를 반드시 통과시킨다** (D-022). 게이트의 기본 계정
`loadadmin@peekcart.test` 는 `d002bc-user-seed.sql` 이 넣으므로, 빈 스택에서 시드 전에 돌리면
로그인 단계에서 실패한다(D-106 ⑧). 명령은 §1 에 있다.

Phase 4 세션이 이 게이트를 건너뛰어 **키쌍 불일치 2건**(사용자 토큰·내부 토큰)을 배포 후에야 발견했고,
원인을 좁히는 데 40분이 들었다. 게이트는 실패 시 어느 도메인이 어긋났는지 지목한다.

**gateway 를 뺄 수 없다.** order/payment 는 `InternalTokenAuthenticationFilter`(기본 `SIGNED_ONLY`)
라 평문 `X-User-*` 를 무시한다 — gateway 가 서명한 내부 토큰 없이는 401 이다. 따라서 이 세션은
`gke-d002a` 와 달리 Secret Manager CSI + Workload Identity 설정이 **필요하다**(gateway 개인키).

## 절차

§3·§4 의 run 은 **배포·재기동 직후 같은 시나리오의 워밍업 run 을 먼저 돌리고 버린다**(D-106 ⑪).
횟수는 세션 계획에 사전 기록하고, 사후에 골라 버리지 않는다. 워밍업 run 뒤에도 run 사이 초기화는 같다.
2026-10-24 세션은 이 규칙이 없어 경합 run 의 주문 p50 이 r1→r2→r3 에서 1,205→701→151ms 로 떨어졌고,
첫 run 의 주문 수는 r3 의 44% 였다. 한 번만 버려도 충분한지는 그 기록만으로 알 수 없다.

§4 의 run 은 **목표 부하가 들어간 run 만 표본으로 쓴다**(D-106 ⑤). `d002bc-contention.js` 는 `dropped_iterations` 가
목표 반복 수(RPS × DUR)의 1% 를 넘으면 threshold 실패로 exit 99 를 내고 요약 첫 줄에 `부하 판정: 무효` 를 찍는다.
그 run 은 버리고 초기화 뒤 다시 돌린다(재시도 상한은 세션 계획). 2026-10-24 세션에 대입하면 RPS 170·60s 의 한도는
102 이고 r1(5,763)·r2(1,871)는 무효, r3(89)·spread(0)는 유효다.

USERS(VU 수)는 **RPS × 반복 p95 × 1.5 를 50 단위로 올린 값**이다. 반복 p95 는 워밍업이 끝난 run 의 k6
`iteration_duration` p95 이고, 요약이 run 마다 이 산식의 값을 찍는다. RPS 170 은 2026-10-24 contend r3(1.64s)로 450 이다.
종전 기준 `USERS ≥ RPS/40` 은 사용자별 한도만 보는 하한이라 VU 포화를 막지 못했다(r1 은 VU 200 이 모두 바빴다).
사용자 시드와 `users.csv` 는 450명이고, setup 의 순차 로그인이 길어져 `setupTimeout` 을 USERS 초로 둔다.

배포·재기동 뒤 첫 run(워밍업 포함) 전에는 **토픽 상태 가드를 통과시킨다**(D-106 ⑩). 선언 토픽 30개의 파티션 수와
계약 7키의 실효값, 업무 consumer group 이 구독 토픽의 모든 파티션을 할당받았는지 본다. 손으로 하던 D-065 ⑤ 대조를
대신하며, 원 출력과 판정이 증적으로 남는다.

```bash
# 이 overlay 는 notification 을 띄우지 않으므로 그 group 을 뺀다. base overlay 에서는 --services 를 생략한다.
python3 scripts/kafka-topic-state-guard.py --services order,product,payment \
  --evidence-dir loadtest/reports/<날짜>/<블록>/topic-guard
```

exit 1 이면 run 을 시작하지 않는다. `[GROUP] ... 미할당` 은 구독 뒤 늘어난 파티션을 컨슈머가 metadata 갱신(기본 5분)
전까지 받지 못한 상태일 수 있다(D-107 ②). 5분을 기다리거나 그 서비스를 재기동한 뒤 다시 돌린다. `[CONFIG]`·`[PARTITIONS]`
는 생성 경합으로 선언이 빠진 토픽이다(D-106 ⑩). 그 토픽을 선언한 서비스를 재기동하면 기동 중 적용과 기동 뒤 재적용이
다시 돈다. 재기동했으면 가드를 다시 돌린다. 가드는 진입 시점 검사라 통과 뒤의 리밸런스는 막지 못한다. run 뒤에는
배수·lag export 로 확인한다.

### 1. 시드

```bash
# 사용자 (450명 + admin 1)
kubectl -n peekcart exec -i deploy/mysql -- \
  mysql -upeekcart_user -ppeekcart_user peekcart_user < loadtest/sql/d002bc-user-seed.sql

# 인증 왕복 게이트 — 시드 계정(loadadmin)으로 로그인하므로 사용자 시드 뒤에 돈다
GW_URL=http://<gateway-internal-lb>:8080 bash scripts/auth-roundtrip-gate.sh

# k6 입력 CSV — **USERS 와 같은 수로** 재생성한다(적으면 계정이 겹쳐 사용자별 한도가 무너진다)
bash loadtest/scripts/generate-users-csv.sh --count 450

# 상품 — SQL 직접 INSERT 금지. admin API 를 태워야 product.updated 가 발행되고
# order 의 product_price_cache 가 찬다. 스크립트가 캐시 전파까지 기다린다.
# 대조군이 상품 1,000개를 쓰므로(D-106 ⑦) 1,000개를 만든다. contend 는 그중 첫 값만 쓴다.
GW=http://<gateway-internal-lb>:8080 bash loadtest/scripts/d002bc-seed-products.sh --count 1000 \
  | tee loadtest/reports/<날짜>/s3/seed-products.log
#   → 출력의 PRODUCT_IDS=... 줄(1,000개, 약 4~6KB)을 k6 에 그대로 넘긴다.
PRODUCT_IDS=$(sed -n 's/^PRODUCT_IDS=//p' loadtest/reports/<날짜>/s3/seed-products.log)
```

### 2. RateLimiter 배선 확인 (측정 전 필수)

```bash
k6 run -e GW=http://<gateway-lb>:8080 loadtest/scripts/d002bc-ratelimit-probe.js
```

**429 가 나와야 PASS 다.** 0 건이면 한도가 안 걸리는 것이고, 그 상태에서 "429 없음" 은 한도
아래에서 쟀다는 증거가 되지 못한다. PASS 전에는 본 측정을 시작하지 않는다.

### 3. D-002b' — 주문 생성 신규 기준선

```bash
k6 run -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS=<위 출력> -e USERS=200 -e RPS=2000 \
  --summary-export=loadtest/reports/<date>/d002bc-order-create.json \
  loadtest/scripts/d002bc-order-create.js
```

k6 는 **동기 구간만** 낸다. 사가 완결 지연은 DB 에서 잰다:

```sql
-- order.created 발행 → stock.reservation.result 반영까지. order 스키마에서.
SELECT COUNT(*) AS orders,
       AVG(TIMESTAMPDIFF(MICROSECOND, o.ordered_at, o.updated_at)) / 1000 AS avg_ms
FROM orders o WHERE o.updated_at IS NOT NULL;
```

run 사이 초기화:

```bash
kubectl -n peekcart exec -i deploy/mysql -- mysql -upeekcart_order   -ppeekcart_order   peekcart_order   < loadtest/sql/d002bc-reset.sql
kubectl -n peekcart exec -i deploy/mysql -- mysql -upeekcart_product -ppeekcart_product peekcart_product < loadtest/sql/d002bc-product-reset.sql
```

### 4. D-002c — 예약 락 경합 (+ 대조군)

```bash
# 경합 — 전 주문이 상품 1개(PRODUCT_IDS 의 첫 값)로
k6 run -e GW=... -e PRODUCT_IDS="$PRODUCT_IDS" -e USERS=450 -e RPS=170 -e DUR=60s -e MODE=contend loadtest/scripts/d002bc-contention.js
# 대조군 — 같은 부하량, 상품 1,000개를 전역 반복 순번으로 한 바퀴씩(상품당 약 10건)
k6 run -e GW=... -e PRODUCT_IDS="$PRODUCT_IDS" -e USERS=450 -e RPS=170 -e DUR=60s -e MODE=spread  loadtest/scripts/d002bc-contention.js
```

대조군을 상품 10개로 돌린 2026-10-24 세션은 대조군의 전달 실패(129)가 경합 run(31~61)보다 많았다(D-106 ⑦).
`order.created` 의 키가 주문 id 라 같은 상품의 주문도 세 파티션에 흩어지고, product replica 3 이 같은 상품 행에
동시에 쓸 수 있다. 상품 수를 늘려 그 확률을 낮춘다.

두 run 모두 끝난 뒤 서버에서 관측한다 — **응답 p95 는 이 축의 지표가 아니다**:

```bash
kubectl -n peekcart exec deploy/kafka -- \
  kafka-consumer-groups.sh --bootstrap-server localhost:29092 \
  --describe --group product-svc-order-created-group           # lag
kubectl -n peekcart logs deploy/product-service | grep -c OptimisticLockingFailureException
kubectl -n peekcart logs deploy/product-service | grep -c PRD-004
kubectl -n peekcart exec -i deploy/mysql -- \
  mysql -upeekcart_product -ppeekcart_product peekcart_product < loadtest/sql/d002bc-verify.sql
```

`PRD-004`(락 대기 초과) 대비 `OptimisticLockingFailureException` 비율이 계획서 P9 의 관심사다.

### 5. MySQL 천장 분해 (D-002a 잔여)

D-002a 가 배제한 "CPU" 는 **product-service 의 CPU** 였다. MySQL 은 그 세션 내내
`limits.cpu: 500m` 이었고 그 축은 변경된 적이 없다 — `gke-d002a` 는 infra 를 patch 하지 않았다.
따라서 1순위 손잡이는 InnoDB 내부가 아니라 이 숫자다:

```bash
# 손잡이: k8s/overlays/gke-d002bc/patches/mysql-deployment.yml 의 limits.cpu
# 올렸다가 **되돌려서** 천장이 다시 내려오는지도 본다(단방향 관측은 근거가 약하다).
kubectl -n peekcart exec deploy/mysql -- mysql -uroot -p<pw> -e "SHOW ENGINE INNODB STATUS\G" 
kubectl -n peekcart exec deploy/mysql -- mysql -uroot -p<pw> -e \
  "SELECT event_name, count_star, sum_timer_wait FROM performance_schema.events_waits_summary_global_by_event_name ORDER BY sum_timer_wait DESC LIMIT 15;"
kubectl -n peekcart exec deploy/mysql -- mysql -uroot -p<pw> -e \
  "SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';"
```

부하 **전/후 두 번** 떠서 차분을 본다. 단일 스냅샷은 귀속 근거가 못 된다.

### 6. 정리 (절대 스킵 금지)

`loadtest/cleanup.sh` + Internal LB·고정 IP·PD 잔여 0 확인. 익일 과금까지 확인한다.
