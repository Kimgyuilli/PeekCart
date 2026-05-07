# PeekCart 부하 테스트 (Phase 3 Task 3-4)

> 측정 환경·방침: `docs/04-design-deep-dive.md` §10-7 · `docs/03-requirements.md` §7-1 · ADR-0004

본 디렉토리는 부하 테스트 시나리오·시드 데이터·리포트 템플릿·정리 스크립트를 포함합니다. 애플리케이션 코드·Flyway migration 과는 독립적으로 유지됩니다.

## 디렉토리 구조

```
loadtest/
├── README.md                          (이 문서)
├── cleanup.sh                         GCP 정리 (ADR-0004 운영 체크리스트)
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
