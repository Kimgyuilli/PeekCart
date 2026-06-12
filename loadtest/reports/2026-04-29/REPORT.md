# PeekCart 부하 테스트 리포트 — 2026-04-29 (세션 C · 시나리오 2/3 + Task 3-5)

> 본 리포트는 `task-loadtest-session-c` 의 P7 산출물이다.
> 시나리오 1 (상품 조회 TPS, 캐시 OFF/ON) 은 **세션 B 의 결과를 인용**하며 재측정하지 않는다 (`task-loadtest-session-c.md` §1).
> 본 세션은 시나리오 2 (1,000 VUser 동시 주문 정합성), 시나리오 3 (Kafka Consumer Lag), Task 3-5 (HPA 런타임 검증), D-002 데이터 수집을 담당한다.

## (a) 환경 스펙

| 항목 | 값 |
|---|---|
| 측정 일시 | 2026-04-29 22:30 ~ 23:05 KST (Run 1 ~22:38, Run 2 ~22:55) |
| 측정자 | rlarbdlf223@gmail.com |
| GCP 프로젝트 | peekcart-loadtest |
| GKE 클러스터 | asia-northeast3-a, peekcart-loadtest (GKE Standard, release-channel=regular) |
| 노드 | e2-standard-4 × 1 (4 vCPU / 16 GiB), pd-standard 50GB |
| peekcart Pod 리소스 | req 500m/1Gi · lim 2000m/2Gi (overlay/gke patch) |
| peekcart 이미지 | `asia-northeast3-docker.pkg.dev/peekcart-loadtest/peekcart/peekcart:f7ea932` (digest `sha256:25068882...`) |
| MySQL / Redis / Kafka | base 매니페스트 리소스, PVC standard-rwo (mysql 1Gi / redis 512Mi / kafka 1Gi) |
| 모니터링 | kube-prometheus-stack (`k8s/monitoring/gke/values-prometheus.yml`, retention 24h, remote-write receiver enabled) |
| 부하 발생기 | loadgen VM e2-standard-2 (asia-northeast3-a, Ubuntu 22.04 LTS, pd-standard 20GB) |
| 캐시 상태 | `PEEKCART_CACHE_ENABLED=true` (default, 시나리오 1 캐시 ON 상태와 동일) |

## (b) 도구 버전

| 도구 | 버전 | 설정 요약 |
|---|---|---|
| k6 | v0.55.0 | loadgen VM 바이너리 직접 설치 (apt 의 v2.0.0-rc1 은 `experimental-prometheus-rw` output 미존재라 회피) |
| k6 output | `experimental-prometheus-rw` | VM loopback `http://localhost:9090/api/v1/write` (kubectl port-forward 경유) |
| kubectl | v1.36.0 (loadgen VM), v1.31.x (운영자 로컬) | gke-gcloud-auth-plugin v35.0.1-gke.0-140 |
| kube-prometheus-stack (Helm chart) | 최신 (`helm repo update` 시점) | values-prometheus.yml + `enableRemoteWriteReceiver: true` |
| Helm | v3.x | install.sh |
| gcloud CLI | 566.0.0 (loadgen VM snap 기준) |  |
| 앱 빌드 | `git rev-parse --short HEAD` = `f7ea932` (`docker buildx build --platform=linux/amd64`) |  |

## (c) 시나리오 파라미터

### 시나리오 1 — 상품 조회 TPS (캐시 OFF/ON)
**본 세션 비대상 — `loadtest/reports/2026-04-09/REPORT.md` (세션 B) 인용.**

### 시나리오 2 — 1,000 VUser 동시 주문 (`loadtest/scripts/order-concurrency.js`)
- VUser: ramping-vus 0 → **1,000** (30초 ramp-up) → **1,000 hold 1m** → 0 (30초 ramp-down) + gracefulRampDown 10s
- 주문 시도: VU 당 1회 (`__ITER === 0` 가드) — 총 1,000회 시도, 이후 sleep 10s 더미 반복
- 타깃: 경합 상품 `product_id 1001..1010` (각 재고 100, 총합 1,000)
- 3-step 플로우: login (`/api/v1/auth/login`) → cart (`/api/v1/cart/items`) → order (`/api/v1/orders`)
- Threshold: `http_req_failed{scenario:contention}: rate<0.1`, `peekcart_auth_failures: count<10`
- **2회 실측**: Run 1 (1 pod cold-start) + Run 2 (3 pods pre-warmed)

### 시나리오 3 — Kafka Consumer Lag
- 시나리오 2 실행 구간에 Prometheus 수집 (별도 부하 없음)
- 참조 metric: `kafka_consumer_fetch_manager_records_lag_max{application="peekcart"}` (Micrometer-Kafka client metric, kafka-exporter 미배포)
- 실측 토픽: `order_created`, `order_cancelled`, `payment_completed`, `payment_failed` (계획서 §3 P5 placeholder `order-created` / `payment.approved` 와 상이 — 실제 underscore 명명)
- 합격선: steady-state lag = 0 (NaN 또는 0), peak 후 5분 내 0 복귀

---

## (d) Baseline 수치 (캐시 OFF) — 시나리오 1

**세션 B 인용**: `loadtest/reports/2026-04-09/REPORT.md`. 본 세션 비대상.

핵심 인용 수치 (D-002 분석에 사용):
- 단일 Pod (2 vCPU 환경) · 50 VUser · 캐시 OFF → 캐시 ON 전환 시 TPS **×2.31** (목표 ×3 미달, D-002)

## (e) 개선 후 수치 — 시나리오 2 (동시 주문 정합성)

### Run 1 — 1 pod cold-start (HPA 1→3 검증 동시)

| 지표 | 값 |
|---|---|
| 시작 환경 | replicas=1, HPA 활성 (min=1, max=3, target CPU 60%) |
| http_reqs | 1,609 |
| http_req_failed{scenario:contention} | **60.59%** (975/1609) — Threshold `rate<0.1` ❌ 미달 |
| peekcart_auth_failures | **537** — Threshold `count<10` ❌ 미달 |
| http_req_duration p95 | 24.74s |
| iteration_duration p95 | 20.96s |
| login 성공 | 463/1000 = **46.3%** |
| cart 성공 | 146/463 = 31.5% (cart_failures 317) |
| order 성공 | 27/146 = 18.5% (5xx 0건 — 모든 응답 201/409/400) |
| 5xx 응답 | **0건** ✅ |
| 정합성 (`verify-concurrency.sql`) | 모든 상품 1001~1010 consistency=**OK**, oversell_check=**OK** ✅ |
| 총 성공 주문 | **25 PENDING** |
| 오버셀링 | **0건** ✅ |

스크린샷: `grafana/01-k6-overview-both-runs.png`, `grafana/03-pod-resources-hpa-scaleout.png`
원본 데이터: `run1/verify-concurrency.txt` 만 보존됨.

> **수치 신뢰 등급 — Run 1**: `k6-summary.json` 과 `k6-stdout.log` 는 Run 2 시작 직전 백업 단계에서 디렉토리 미선생성으로 mv 실패해 미보존. 위 표의 모든 k6 메트릭 (60.59%, 537, 24.74s 등) 은 **세션 채팅에 출력된 stdout 라이브 전사** 기준이며 원본 파일로 재검증 불가. 정합성 / HPA 타임라인은 별도 산출물 (`run1/verify-concurrency.txt`, `kubectl get hpa -w` 로그 + 스크린샷) 로 검증 가능. → Task 3-4 throughput Threshold 판정 근거에서 Run 1 수치는 보조 증거로만 사용, 본 판정은 Run 2 의 보존된 `run2/k6-summary.json` 을 1차 근거로 사용.

### Run 2 — 3 pods pre-warmed (HPA 일시 제거, manual scale=3)

| 지표 | 값 |
|---|---|
| 시작 환경 | replicas=3 (manual scale, HPA 일시 삭제) |
| http_reqs | 2,479 (Run 1 대비 **+54%**) |
| http_req_failed{scenario:contention} | **35.90%** (890/2479) — Threshold ❌ 여전히 미달, 다만 -24.7pp 개선 |
| peekcart_auth_failures | **33** — Threshold ❌ 미달, -94% 개선 |
| http_req_duration avg / p95 | **13.63s / 30.21s** (p95 5.5s 악화 — DB 병목으로 깊은 단계까지 도달한 요청 비율 증가) |
| iteration_duration p95 | 40.97s |
| login 성공 (k6 check) | 967/1000 = **96.7%** ✅ (Run 1 대비 +50pp, **CPU 병목 해소 입증**) |
| cart 201 (k6 check) | 512/967 = 52.9% (cart_failures 455) |
| order 201\|409\|400 (k6 check) | 138/512 = 27.0% (k6 응답 코드 통과 기준) |
| 5xx 응답 | 0건 ✅ |
| 정합성 (`verify-concurrency.sql`) | 모든 상품 1001~1010 consistency=**OK**, oversell_check=**OK** ✅ |
| **DB 검증 기준 PENDING 주문** | **110 건** (Run 1 25건 대비 ×4.4) |
| 오버셀링 | **0건** ✅ |

> **138 vs 110 차이 해석**: k6 check 의 `order 201\|409\|400` 138 은 **HTTP 응답 코드가 허용 범위 내로 반환된 횟수** (timeout/connection 끊김은 fail). DB PENDING 110 은 **트랜잭션 커밋까지 도달한 주문 수**. 차이 28건은 응답은 받았지만 (예: 409 재고 부족) 주문 row 가 생성되지 않은 케이스 또는 응답 후 트랜잭션 롤백된 케이스로 추정. 정합성 판정은 DB PENDING 110 + oversell 0건 기준이 1차 근거.

스크린샷: `grafana/01-k6-overview-both-runs.png`, `grafana/02-pod-resources-run2.png`
원본 데이터: `run2/k6-summary.json`, `run2/k6-stdout.log`, `run2/verify-concurrency.txt`

---

## Task 3-5 — HPA 런타임 검증 (`docs/TASKS.md:420` 완료 기준)

### 결과: ✅ **PASS** (Run 1 단독 검증)

#### HPA 타임라인 (Run 1, `kubectl get hpa peekcart -w` 로그)

| 시점 (HPA AGE) | TARGETS (CPU) | REPLICAS |
|---|---|---|
| 133m | 3% | 1 |
| 134m | 3% | 1 |
| 137m | 5% | 1 |
| **138m** | **269%** | **1** ← saturation 시작 |
| 138m | **400%** | **1** ← Pod limit 도달 (request 500m 대비 400% = 2 vCPU) |
| 138m | 400% | **3** ← scale-out 발생 |
| 138m | 246% | 3 |
| 139m | 244% | 3 |
| 139m | 90% | 3 ← capacity 확보 후 안정화 |
| 140m | 15% | 3 ← 부하 종료 |

#### Pod 증설 타임라인

- 기존: `peekcart-5f4b46df95-r58t4` (1 pod)
- HPA 발동 후 신규 생성:
  - `peekcart-5f4b46df95-4z842` (Pending → ContainerCreating → Running, ~65s)
  - `peekcart-5f4b46df95-qxk7q` (동시, ~65s)
- 기존 r58t4: 부하 spike 동안 `0/1 Running` 으로 readiness 일시 손실, 약 63초 후 회복

#### Task 3-5 합격 평가

- ✅ replicas **1 → 3** 전이 확인 (`kubectl get hpa -w` 로그 + Grafana 스크린샷 양쪽)
- ✅ 신규 pod 65초 내 Ready
- ✅ 안정화 (90% → 15% CPU) 확인
- 스크린샷: `grafana/03-pod-resources-hpa-scaleout.png` (HPA Current Replicas 패널의 1→3 전이가 22:38 시점에 명확히 보임)

> **계획서 §5 미달 조건**: replica 가 2에서 saturation 됐다면 "미완료" 유지 명시 — **본 세션은 3 도달했으므로 해당 없음**.

---

## (f) 시나리오 3 — Kafka Consumer Lag

### 결과: ✅ **PASS** (steady-state lag 0, peak 후 신속 복귀)

#### 측정 PromQL (계획서 §3 P5 placeholder 치환판)

```promql
# 부하 동안 lag 추이
kafka_consumer_fetch_manager_records_lag_max{application="peekcart"}

# steady-state 합격 검증 (k6 종료 후 5분 시점)
kafka_consumer_fetch_manager_records_lag_max{
  application="peekcart",
  client_id=~"consumer-payment-svc-order-created-group.*|consumer-order-svc-payment-completed-group.*|consumer-order-svc-payment-failed-group.*"
} > 0
```

**대시보드 id**: PeekCart — Kafka Consumer (`k8s/monitoring/shared/kafka-lag-dashboard.json`)
**참조 metric**: `kafka_consumer_fetch_manager_records_lag_max` (Micrometer-Kafka client metric, **kafka-exporter 미배포**)
**참조 label**: `client_id`, `topic` (예상 라벨 `kafka_consumer_group_id` 는 부재 — group 정보가 `client_id` 에 임베디드)

#### 확인된 consumer client_ids (7종 × 3 partitions = 21 series)

| client_id | topic |
|---|---|
| `consumer-notification-svc-payment-failed-group-2` | payment_failed |
| `consumer-payment-svc-order-created-group-1` | order_created |
| `consumer-notification-svc-order-cancelled-group-3` | order_cancelled |
| `consumer-notification-svc-order-created-group-6` | order_created |
| `consumer-notification-svc-payment-completed-group-7` | payment_completed |
| `consumer-order-svc-payment-completed-group-4` | payment_completed |
| `consumer-order-svc-payment-failed-group-5` | payment_failed |

#### 측정 결과

| 구간 | Lag |
|---|---|
| 부하 시작 전 (steady-state) | NaN (첫 fetch 미발생) — ✅ 합격 (NaN = 안전 상태) |
| Run 1 부하 구간 | 일부 client 에서 작은 spike (스크린샷 22:38 부근) |
| Run 2 부하 구간 | 일부 client 에서 작은 spike (22:55 부근) |
| k6 종료 후 5분 (PromQL `... > 0` 결과) | **빈 결과** — ✅ 합격 (모든 lag 0 또는 NaN) |

스크린샷: `grafana/04-kafka-lag-both-runs.png`

---

## D-002 데이터 포인트 (계획서 §1 D-002 데이터 수집)

> 결론은 본 task 비대상. 이하 수치만 기록.

### 세션 B 인용 (단일 Pod 기준, 2 vCPU)
- 캐시 OFF → 캐시 ON 전환 시 TPS **×2.31** (목표 ×3 미달)
- 50 VUser 부하 시 CPU **~175%** 도달 (캐시 히트에도 CPU 가 병목)

### 세션 C 신규 데이터 (e2-standard-4 / 4 vCPU 환경)

#### 1차 병목 — CPU saturation (Run 1, 1 pod cold-start)

- 단일 Pod CPU **400% saturation** (request 500m 대비 400% = 2 vCPU, Pod limit 2000m 도달)
- 1000 VU cold-start 부하를 단일 Pod 가 흡수 불가능
- HPA reaction window (~60s) 동안 timeout 폭증 (login 실패 537건, http_req_failed 60.59%)
- HPA 1→3 scale-out 후 CPU 90% → 15% 로 안정화
- → **CPU 가 명백한 1차 병목** (D-002 가설 확증)

#### 2차 병목 — DB/락 contention 또는 연결 안정성 (Run 2, 3 pods pre-warmed)

- CPU 분산 → login 96.7% 성공 (1차 병목 해소)
- BUT cart 실패율 47%, order 실패율 73% 잔존
- p95 latency 30.21s 로 오히려 증가 — 깊은 단계 (cart insert / inventory decrement) 까지 도달한 요청들이 timeout
- 처리 성공 주문 110건 (Run 1 의 ×4.4)
- **`run2/k6-stdout.log` 의 실패 원인 분포** (`grep -oE '(EOF|connection refused|dial.*timeout)' | sort | uniq -c`):
  - `EOF` 519건 — 서버가 요청 도중 연결 끊음
  - `connection refused` 173건 — Pod listener 미수신 (readiness 손실 또는 LB endpoint 빠짐 의심)
  - `dial: i/o timeout` 130건 — 연결 자체 수립 실패 (네트워크/Pod 도달성)
- → **2차 병목 후보 (가설 좁힘 BUT 단정 불가)**:
  - (a) **MySQL 커넥션 풀 / Redis 분산 락 contention** — D-002 원본 가설 3종에 부합
  - (b) **Pod readiness / 연결 안정성** — connection refused/dial timeout 이 다수 — Pod overload 시 readinessProbe 일시 fail → endpoint 에서 빠지는 패턴 가능
  - 두 가설 분리 검증을 위해선 HikariCP wait time / Redisson lock acquisition latency / Pod readiness transition 로그를 동시 수집해야 함 (본 세션 비대상)

#### 종합 — Run 1 vs Run 2 비교표

| 메트릭 | Run 1 (1 pod) | Run 2 (3 pods) | 변화 |
|---|---|---|---|
| login 성공률 | 46.3% | 96.7% | +50.4pp |
| http_req_failed{contention} | 60.59% | 35.90% | -24.7pp |
| peekcart_auth_failures | 537 | 33 | -94% |
| http_req_duration p95 | 24.74s | 30.21s | +5.5s (악화) |
| 처리 성공 주문 | 25 | 110 | ×4.4 |
| 정합성 (oversell) | OK (0건) | OK (0건) | 유지 |

#### D-002 후속 추적 권장

- Phase 4 MSA 분리 시 Order Service 만 별도 측정 (DB 분리 후 락 contention 영향 격리)
- MySQL 리소스 상향 + HikariCP 풀 크기 튜닝 후 재측정
- Redis 분산 락 대기 시간 metric (`shedlock` / 분산 락 acquisition latency) 별도 수집

---

## 관측 / 이슈

### 측정 자체에 영향 없음
- **k6 v0.55.0 설치** — apt 의 default `dl.k6.io/deb stable` 채널이 v2.0.0-rc1 (RC) 를 푸시했고, RC 빌드는 `experimental-prometheus-rw` output 을 빌트인 outputs 목록에서 누락. v0.55.0 GitHub release 바이너리 직접 다운로드로 회피 (loadgen VM 셋업 단계).
- **계획서 §3 P5 의 PromQL placeholder 와 실제 metric 불일치** — 계획서는 `kafka_consumergroup_lag` (kafka-exporter 패턴) 가정했으나 본 프로젝트는 **kafka-exporter 미배포**, `kafka_consumer_fetch_manager_records_lag_max` (Micrometer client) 사용. 토픽 명도 `order-created` (dash) → `order_created` (underscore), `payment.approved` 부재. 본 리포트 §(f) 에 실 PromQL 기록.
- **`k8s/monitoring/shared/kafka-lag-dashboard.json` 의 legend** `{{kafka_consumer_group_id}}` 는 본 프로젝트에서 빈 문자열로 렌더됨 (해당 라벨 부재). 별도 수정 안건 — 본 task 비대상.

### Run 1 산출물 일부 누락
- Run 2 시작 직전 산출물 백업 단계에서 VM 측 파일 mv 가 실패한 것으로 보임 (디렉토리 미선생성). Run 1 의 `k6-summary.json`, `k6-stdout.log` 는 `loadtest/reports/2026-04-29/run1/` 에 부재.
- **다만 핵심 수치는 본 리포트 §Run 1 표에 채팅/k6 stdout 라이브 출력 기준으로 기록**되어 있어 분석 가치 손실 없음.

### Threshold 미달 해석
- `http_req_failed{scenario:contention}: rate<0.1` Threshold 는 Run 1/2 둘 다 미달. 본 인프라 (e2-standard-4 × 1 node, MySQL 250m-500m CPU) 에서 1000 VU 동시 주문은 본질적으로 처리 불가능 — 인프라 변경 없이는 합격 불가.
- **달성 범위**: 시나리오 2 정합성 (oversell 0건, consistency=OK), Task 3-5 HPA 1→3 자동 증설, 시나리오 3 Kafka Lag steady-state 0 복귀, D-002 데이터 수집 (1차 CPU + 2차 DB/연결 안정성 후보).
- **미달 범위**: 시나리오 2 throughput Threshold `http_req_failed<0.1` (Run 1 60.59%, Run 2 35.90%) — 계획서 §6 의 "k6 Threshold 통과" 항목.
- 미달은 §관측/이슈 + §(f) 비고 처리, **D-002 추적 행 갱신 시 본 데이터 인용**. Task 3-4 / Phase 3 의 ✅ 처리는 미달 범위를 명시한 단서 (TASKS.md §Task 3-4 상태 라인) 와 함께만 유효.

### cleanup.sh 버그 (별도 수정 안건)
- `loadtest/cleanup.sh` 의 변수 `loadgen=loadgen` 이 실제 VM 이름 `peekcart-loadgen` 과 불일치 → VM 삭제 단계 실패 → 운영자 수동 보완 필요했음. 본 task 비대상이며 후속 fix 권장.

---

## 정리 체크리스트 (ADR-0004)

- [x] `gcloud container clusters delete peekcart-loadtest --zone=asia-northeast3-a --quiet` (cleanup.sh)
- [x] `gcloud compute instances delete peekcart-loadgen --zone=asia-northeast3-a --quiet` (수동, cleanup.sh 버그 보완)
- [x] `gcloud compute disks list` — 빈 결과 ✅ (5개 모두 수동 삭제: 1× pd-standard 20GB + 4× pvc-* pd-balanced)
- [x] `gcloud compute addresses list` — 빈 결과 ✅
- [x] `gcloud container clusters list` — 빈 결과 ✅
- [ ] billing 콘솔 당일/익일 과금 재확인 (운영자 수동, 본 리포트 외부)

## 첨부

- `run1/verify-concurrency.txt` — Run 1 정합성 검증 (25 PENDING, 모든 상품 OK)
- `run2/k6-summary.json` — Run 2 k6 메트릭 (JSON)
- `run2/k6-stdout.log` — Run 2 k6 실행 로그 (132 KB, THRESHOLDS / TOTAL RESULTS / 에러 분포 포함). **`.gitignore` 의 `*.log` 패턴으로 commit 비대상** — 측정 직후 운영자 로컬 디스크에만 보존. EOF/refused/timeout 분포 등 본 리포트 §D-002 #2차 병목 근거 수치는 본 파일에서 추출됨
- `run2/verify-concurrency.txt` — Run 2 정합성 검증 (110 PENDING, 모든 상품 OK)
- `grafana/01-k6-overview-both-runs.png` — k6 Performance Overview (양 run 의 VU/RPS 추이)
- `grafana/02-pod-resources-run2.png` — Pod CPU/Memory/Restarts (Run 2, HPA 패널은 No data — Run 2 동안 HPA 일시 삭제 상태)
- `grafana/03-pod-resources-hpa-scaleout.png` — HPA Current Replicas 패널의 1→3 전이 (Task 3-5 핵심 산출물)
- `grafana/04-kafka-lag-both-runs.png` — Kafka Consumer Lag (Max) 양 run 구간 + Records Consumed Rate / Consumer Fetch Rate
