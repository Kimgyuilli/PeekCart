# PeekCart 부하 테스트 리포트 — YYYY-MM-DD

> 복사하여 `loadtest/reports/YYYY-MM-DD/REPORT.md` 로 저장 후 사용.
> 기록 항목은 `docs/04-design-deep-dive.md` §10-7 "Phase 3 — 부하 테스트 단계" 에 정의된 (a)~(f) 를 따른다.

## (a) 환경 스펙

| 항목 | 값 |
|---|---|
| 측정 일시 | YYYY-MM-DD HH:MM KST |
| GKE 클러스터 | asia-northeast3-a, peekcart-loadtest |
| 노드 | e2-standard-4 × 1 (4 vCPU / 16 GiB) |
| peekcart Pod | req 500m/1Gi · lim 2000m/2Gi · replicas=1 |
| MySQL / Redis / Kafka | PVC standard-rwo, 기본 매니페스트 리소스 |
| 모니터링 | kube-prometheus-stack (values-prometheus.yml, retention 24h) |
| 부하 발생기 | e2-standard-2 (동일 zone) |

## (b) 도구 버전

| 도구 | 버전 | 설정 요약 |
|---|---|---|
| nGrinder | X.Y | controller+agent 로컬 |
| k6 | v0.49+ | CLI (Prometheus remote-write) |
| kube-prometheus-stack (Helm chart) | X.Y |  |
| peekcart 이미지 | asia-northeast3-docker.pkg.dev/\<project\>/peekcart/peekcart:\<sha\> |  |

## (c) 시나리오 파라미터

### 시나리오 1 — 상품 조회 TPS
- VUser: 50 · warm-up 1분 → 본측정 5분
- 비율: 목록 80% / 상세 20%
- 랜덤 범위: categoryId 1..5 · page 0..49 · productId 1..1000

### 시나리오 2 — 1,000 VUser 동시 주문
- VUser: 1000 · ramp-up 30초 → 1m hold (Grafana 타임라인 관찰용) → 30s ramp-down
- 주문 시도: VU 당 1회 (k6 `__ITER === 0` 가드) — 총 주문 시도 1,000
- 타깃: 경합 상품 product_id 1001..1010 (각 재고 100, 총 1000)
- 기대: 성공 ≤ 1000, 오버셀링 0

### 시나리오 3 — Kafka Consumer Lag
- 시나리오 2 실행 구간에 Prometheus 수집. 별도 부하 없음.

---

## (d) Baseline 수치 (캐시 OFF / 기본 상태)

### 시나리오 1 baseline (캐시 OFF)

| 지표 | 값 |
|---|---|
| TPS | |
| MTT (평균) | |
| p95 | |
| p99 | |
| 에러율 | |

스크린샷: `grafana/scenario1-baseline.png`

### 시나리오 2

| 지표 | 값 |
|---|---|
| 전체 요청 | 1000 |
| 주문 성공 (201) | |
| 재고 부족 실패 (4xx) | |
| 서버 오류 (5xx) | |
| DB 정합성 (verify-concurrency.sql 결과) | `OK` / `MISMATCH` |
| 오버셀링 여부 | `OK` / `OVERSELL` |

스크린샷: `grafana/scenario2-dashboard.png` · `sql/verify-concurrency-output.txt` · `k6-summary.json`

### 시나리오 3 — Kafka Lag

| 지표 | 값 |
|---|---|
| 정상 구간 Lag | |
| 부하 구간 peak Lag | |
| 회복 시간 | |

스크린샷: `grafana/kafka-lag.png`

---

## (e) 개선 후 수치 (캐시 ON)

### 시나리오 1 (캐시 ON)

| 지표 | 값 |
|---|---|
| TPS | |
| MTT (평균) | |
| p95 | |
| p99 | |
| 에러율 | |

스크린샷: `grafana/scenario1-cache-on.png`

---

## (f) 개선 비율

| 지표 | Baseline (OFF) | 개선 후 (ON) | 비율 |
|---|---|---|---|
| TPS | | | ×N.NN |
| p99 | | | ×N.NN |

목표 대비 (`docs/03-requirements.md` §7-1):
- [ ] 상품 목록 p99 ≤ 100 ms
- [ ] Redis 캐시 TPS ≥ 3× baseline
- [ ] 동시 주문 정합성 100%
- [ ] Kafka Lag 정상 구간 0 유지

---

## 관측 / 이슈

- 측정 중 발생한 이슈, 주의 사항, 재측정 필요 여부
- Prometheus / Grafana 캡처 중 이상 신호

## 정리 체크리스트 (ADR-0004)

- [ ] `gcloud container clusters delete peekcart-loadtest --region=asia-northeast3-a`
- [ ] `gcloud compute instances delete peekcart-loadgen --zone=asia-northeast3-a`
- [ ] `gcloud compute disks list` — orphan PD 없음
- [ ] `gcloud compute addresses list` — 예약 IP 없음
- [ ] billing 콘솔 당일 과금 확인
