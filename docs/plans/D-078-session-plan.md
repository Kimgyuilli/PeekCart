---
grade: M
---
# D-078 GKE 측정 세션 계획 (부분 작업)

## 1. 명제와 코드 전제

D-078 ①②③④⑤⑥⑧⑨ 를 과금 세션 한 번으로 잰다. 순서를 잘못 잡거나 조건을 파일 밖에 두면 서로
오염된다(Phase 4 `d002bc` 의 대조군이 직전 run 잔여와 섞여 무효가 된 선례). 이 문서는 세션 절차를
정하고, 절차가 요구하는 조건 파일 두 개를 둔다. 세션을 실행하지 않으며 D-078 의 어느 항목도 닫지 않는다.

기준 코드 `4bed313`.

| 전제 | 코드·증적 대조 |
|---|---|
| GCP | 계정 `rlarbdlf222@gmail.com`, 결제 계정 `01001D-E81E39-C489B0` 열림, 월 예산 ₩20,000(알림 50/90/100/150%). 지출 한도는 Compute·GKE 미지원이라 쓰지 않는다. Phase 4 의 `peekcart-gke` 는 소유 계정 삭제로 접근 불가 |
| 키쌍 | `local-keys/dev-gateway-internal-private.pem`·`dev-jwt-private.pem` 의 공개키 SPKI SHA-256 앞 16자(`908f1da22b4db672`·`17df680623f50fe6`)가 커밋된 `internal-token-keys`·`user-jwt-public-keys` ConfigMap 과 같다(2026-10-23 로컬 대조). 이 둘을 Secret Manager 에 올리면 ConfigMap 을 고칠 일이 없다 |
| CSI | 커밋 계약은 `secrets-store.csi.k8s.io` / `provider: gcp`. GKE 관리형 애드온은 드라이버명이 달라 쓰지 않는다(`pr3d-b2-gke-20260914-1320.md` 편차 1) |
| 노드 SA | 기본 컴퓨트 SA 에 AR pull 권한이 없어 403 이 났다(같은 증적 편차 3) |
| 이미지 | CI 가 GHCR 에 `<sha>`·`latest` 로 push 한다. `scripts/promote-images.sh` 가 AR 로 옮기고 digest 를 출력한다 |
| 읽기 하네스 | `d026-run-block.sh` 가 `gke-d002a-mysql-{500m,2000m}` 을 apply 하고 셀을 3회 돈다. 블록 워밍업은 상품 풀스캔뿐이라 list 첫 런이 낮았다(d026 블록 B list OFF 782.7 / 1406.4 / 1408.4) |
| 경합 조건 | Phase 4 경합 run 은 product replicas 3 을 `kubectl scale` 로, outbox 폴링 500ms·500건을 env 로 맞췄다(`d002bc-gke-20260917.md` §배포 편차 3). 둘 다 파일에 없었다 |
| 관측 스택 | d026 기준선은 Prometheus 없이 쟀다. 앱 노드에 Prometheus(limit 1500m)를 얹으면 ① 비교와 ④ 노드 CPU 가 오염된다 |

등급 M: 측정 전용 overlay 와 helm values 를 하나씩 추가하고 lint 목록을 고친다. 운영 매니페스트·앱 코드는
바꾸지 않아 새 ADR 은 없다.

## 2. 작업 항목

- [x] P1. `k8s/overlays/gke-d078-contention`: `gke-d002bc-mysql-2000m` 위에 product replicas 3, order outbox 폴링 500ms·500건. 렌더 diff 는 두 곳뿐. lint 2곳 등록.
- [x] P2. `k8s/monitoring/gke/values-measurement-isolation.yml`: 관측 스택을 taint 된 `monitoring-pool` 에만 둔다. node-exporter 는 앱 노드에서도 돈다.
- [x] P3. 아래 §3 세션 절차, §4 중단 규칙, §5 비용.

## 3. 세션 절차

### S0. 클러스터 없이 하는 준비 (과금 미미)

1. 프로젝트 생성 → 결제 계정 연결 → `container`·`compute`·`artifactregistry`·`secretmanager`·`iam` API 활성화. gcloud 설정을 새 configuration 으로 만들어 기본값(삭제된 프로젝트)과 분리한다.
2. 예산 알림이 걸려 있는지 콘솔에서 다시 본다.
3. Secret Manager 에 `peekcart-gateway-internal-signing-key`(← `dev-gateway-internal-private.pem`), `peekcart-user-jwt-signing-key`(← `dev-jwt-private.pem`). 올리기 직전에 §1 의 fingerprint 를 다시 대조한다.
4. GSA `peekcart-gateway-signer`·`peekcart-user-signer` 생성, 각자 자기 시크릿에만 `secretAccessor`(교차 금지, ADR-0017 D3).
5. AR 리포지토리 생성, `scripts/promote-images.sh --project <P> --tag <main SHA>` 로 6개 승격. 출력 digest 를 기록하고 overlay 에는 digest 로 고정한다(`latest` 금지).
6. 세션용 이미지·키·ConfigMap 치환은 커밋하지 않는다. 끝나면 `git restore`.

### S1. 클러스터 (여기서부터 시간 과금)

1. GKE `peekcart-d078`: `asia-northeast3-a`, 채널 stable, `e2-standard-4` × 2, 부팅 `pd-balanced` 50GB(SSD 쿼터), `--workload-pool=<P>.svc.id.goog`, `--enable-dataplane-v2`(S4 의 NetworkPolicy 용. 생성 후 못 켠다).
2. 노드풀 `monitoring-pool`: `e2-standard-2` × 1, `--node-labels=peekcart.io/role=monitoring`, `--node-taints=peekcart.io/role=monitoring:NoSchedule`.
3. **cleanup 을 먼저 건다.** `CLUSTER_NAME=peekcart-d078 LOADGEN_NAME=peekcart-d078-loadgen bash loadtest/cleanup.sh --dry-run` 이 대상을 정확히 가리키는지 본다.
4. 노드 SA 에 `roles/artifactregistry.reader`. WI 바인딩 `[peekcart/gateway]`·`[peekcart/user-service]`.
5. CSI: upstream `secrets-store-csi-driver` helm(`syncSecret.enabled=false`, `tokenRequests[0].audience=<P>.svc.id.goog`) + GCP provider. 버전을 기록한다.
6. 관측: `monitoring` NS → `helm ... -f values-prometheus.yml -f values-measurement-isolation.yml`(차트 버전 기록) → `kubectl apply -k k8s/monitoring/shared/`. Prometheus·Grafana Pod 가 `monitoring-pool` 에 있는지 `-o wide` 로 확인한다.
7. loadgen VM `peekcart-d078-loadgen`(`e2-medium`, 같은 VPC), k6 v2.2.0(Phase 4 와 같은 버전).

### S2. 읽기 경로 — ①②③④

`d026-run-block.sh` 를 그대로 쓴다. 조건은 d026 과 같다(product limit 3000m · 400 VU · 60s · IDS 100 · `d002a-product-seed.sql`).

| 블록 | MySQL | 셀 |
|---|---|---|
| A | 500m | list OFF · list ON · detail OFF |
| B | 2000m | list OFF · list ON · detail OFF · detail ON |
| A′ | 500m | list OFF · detail OFF |

- 블록마다 **REPS=4, 첫 회는 버린다.** 버퍼풀 워밍업 부족이 d026 list 산포 44.5% 의 원인이었으므로 사전에 정한 규칙으로 처리한다. 사후에 골라 버리지 않는다.
- 게이트: A detail OFF·list OFF 가 d026 A(393.4·367.6) ±10% → 실패 시 S2 중단하고 S3 으로 간다. A′ 가 A 의 ±10% 를 못 지키면 list 배속은 이번에도 확정하지 않는다.
- ③: `run-cell` 의 이름별 counter 차분(`product`·`productStock`·`products`)이 ON 셀에서 hit·miss 둘 다 증가했는지.
- ②: B detail OFF 런에서 Pod 별 CPU, HikariCP 대기, 컨테이너별 스로틀. 원인 분리는 증적에서 하며 세션 중 튜닝하지 않는다.
- ④: B list ON 런의 노드 CPU 사용률. 캐시 miss 0 인 셀이 MySQL 한도에 반응한 이유를 가른다.
- 캡처는 `loadtest/captures.md` 를 따른다.

### S3. 경합 — ⑤⑥

1. `kubectl delete ns peekcart` 로 읽기 경로 자원을 비운다(PVC 포함, 데이터 초기화).
2. `gke-d078-contention` 을 digest·PROJECT 치환 후 apply. `scripts/auth-roundtrip-gate.sh` 통과가 진입 조건이다.
3. 시드: `d002bc-seed-products.sh`, `d002bc-user-seed.sql`, `generate-users-csv.sh --count <USERS>`. `d002bc-ratelimit-probe.js` 로 429 관문을 먼저 통과한다.
4. 각 run 전후로 product Pod 3개의 `/actuator/prometheus` 원값을 저장한다(`kafka_listener_delivery_failed_total`, `kafka_listener_recovered_total`, `saga_reservation_result_total`). 차분의 부재는 0 이다.
5. `d002bc-contention.js MODE=contend DUR=60s`, RPS 는 Phase 4 주문 수(9,782/60s ≈ 163/s)에 맞춰 170, `USERS ≥ RPS/40`. 3회.
6. run 사이에 outbox backlog 0 과 consumer lag 0 을 기다린 뒤 `d002bc-reset.sql` 로 초기화한다. 대조군 `MODE=spread` 1회도 같은 절차. 섞이면 그 run 은 무효로 적는다.
7. 정합성: 음수 재고 0, 품목별 diff 0, 예약 상태 합 = 주문 수.
8. ⑥: 배수 구간에서 order·product 의 `executor_active_threads`/`executor_queued_tasks`, 그리고 backlog 가 있는데 발행률이 0 인 구간이 있는지(D-024 재발).

### S4. base 적용 — ⑧

1. `kubectl delete ns peekcart`, 기본 노드풀을 3대로 늘린다(정상 상태 requests 약 7.3 vCPU, `pr3d-b2` 편차 2).
2. `GW_URL=<gateway LB> bash scripts/deploy-overlay.sh k8s/overlays/gke`. 외부 자격증명은 smoke 값으로 넣는다(`pr3d-b2` 편차 4와 같은 방식).
3. 렌더한 mysql `limits.cpu` 와 실행 Pod 의 값이 `2` 로 같은지, 인증 왕복 게이트 통과.

### S5. 정리 — 어떤 경로에서도 실행

1. `CLUSTER_NAME=peekcart-d078 LOADGEN_NAME=peekcart-d078-loadgen bash loadtest/cleanup.sh`. 7종 전부 `[0]` 이어야 끝난다.
2. AR 리포지토리와 Secret Manager 시크릿 삭제.
3. `gcloud billing projects unlink <P>`. 남은 자원이 있어도 과금이 멈춘다. 프로젝트 삭제는 사용자가 정한다.
4. gcloud configuration 을 원래대로 돌린다.
5. 다음 날 결제 콘솔에서 금액과 잔여 청구를 본다.

## 4. 중단 규칙

- 클러스터 안에서 디버깅하지 않는다. 스모크 1회 재시도 후에도 실패하면 S5 로 간다(`loadtest/README.md` 가드레일).
- 목표 미달도 결과다. 세션 중 튜닝·재설계 금지.
- 예산 50% 알림이 오거나 클러스터 생성 후 8시간이 지나면 남은 블록을 접고 S5 로 간다.
- S2 재현 게이트 실패는 S2 만 접는다. S3·S4 는 조건이 달라 진행할 수 있다.

## 5. 비용 (서울 온디맨드, 2026-10-22 Cloud Billing 카탈로그)

| 항목 | 시간당 |
|---|---|
| `e2-standard-4` × 2 | $0.344 |
| `monitoring-pool` `e2-standard-2` × 1 | $0.086 |
| loadgen `e2-medium` | $0.043 |
| 디스크·LB | 약 $0.06 |
| GKE 관리비 | $0.10 (zonal 1개 무료 크레딧 여부는 S0 에서 확인) |
| 합계 | 약 $0.53 ~ $0.63 |

S2 약 2시간, S3 약 1.5시간, S4 약 0.5시간(3번째 노드 +$0.17), 준비·정리 약 1.5시간. 6~8시간 기준 $3.5~5,
부가세 포함 약 ₩5,000~7,000. 예산 ₩20,000 의 절반 안쪽이다.

## 6. 검증 방법 (이 PR)

- 두 overlay 의 렌더 diff 가 의도한 줄뿐인지(product replicas, order env).
- `helm template` (차트 91.4.0) 로 관측 스택 6개의 nodeSelector·toleration, node-exporter 의 무조건 toleration 확인.
- namespace lint, lint-health selftest.
- 키쌍 fingerprint 대조는 §1 에 적은 대로 로컬에서 했다. 키 원문은 출력·커밋하지 않았다.

## 7. 범위 밖

- 세션 실행과 증적 작성, TASKS·PHASE5 반영은 세션 후속이다.
- ⑦(#129 익일 Billing)은 무료 크레딧 계정이라 확인하지 않기로 했다(사용자 결정, 2026-10-23).
- outbox 폴링 기본값의 처분은 D-075 다. 경합 overlay 의 500ms·500건은 before 조건 재현일 뿐이다.
