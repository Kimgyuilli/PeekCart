---
grade: M
---
# D-078 GKE 측정 세션 계획 (부분 작업)

## 1. 명제와 코드 전제

D-078 ①②③④⑤⑥⑧⑨ 와 README 보강 지표 A~E(사가 왕복 지연, outbox 발행 대기, 배수 시간, JVM, MSA HPA)를
과금 세션 한 번으로 잰다. A~E 는 기존 증적에 없는 것만 골랐다(`D-078-latency-metrics.md` §1). 순서를 잘못 잡거나 조건을 파일 밖에 두면 서로
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

**S0 실행 기록 (2026-10-23).** 프로젝트 `peekcart-d078`, gcloud configuration `peekcart-d078`(기본 configuration 은 그대로).
서명 키 2개는 업로드 전후 fingerprint 가 §1 과 같다. GSA 2개는 각자 자기 시크릿에만 `secretAccessor`.
Slack webhook 시크릿 `peekcart-slack-user-webhook`·`peekcart-slack-ops-webhook` 은 사용자가 값을 넣었다(`test` 워크스페이스 앱 `PeekCart Alerts`, 채널 2개). 값은 보지 않고 둘 다 Slack webhook 형식이며 서로 다름을 해시로 확인했다.
AR 승격 태그 `4bed3135e8213eb90b7bd95763a36a5e6e7f7109`, 6개 모두 GHCR digest 와 같다.
쿼터(asia-northeast3): E2 vCPU 24, SSD 500GB, 외부 IP 8. S4 최대 사용 E2 vCPU 16, SSD 약 220GB.

| 이미지 | digest |
|---|---|
| gateway | `sha256:8707b330ffbe90463c5b6ca951582d5fc89001dbf594fc0d5b2033e4c124322f` |
| user-service | `sha256:2e710d6ff4c9a26461dbf9c166ac8ea65f003d88d1ddb315b54ba29bb33af06a` |
| product-service | `sha256:1676fd57a17d31af65cd2baf1491ac474cce51f26948d5b8906c1ab5508e69e2` |
| order-service | `sha256:16920c6452e1c33c3404e79b98394b0d18027aa34e86a55617bd480ef5712261` |
| payment-service | `sha256:a4d00271813afe1394732341b09bc4583027adb6fb01cce82db959006dc6c2df` |
| notification-service | `sha256:7cfb7fd6c7bc865400a2206f30fc04acae698845558da4efccc858356d8ee90e` |

### S1. 클러스터 (여기서부터 시간 과금)

1. GKE `peekcart-d078`: `asia-northeast3-a`, 채널 stable, `e2-standard-4` × 2, 부팅 `pd-balanced` 50GB(SSD 쿼터), `--workload-pool=<P>.svc.id.goog`, `--enable-dataplane-v2`(S4 의 NetworkPolicy 용. 생성 후 못 켠다).
2. 노드풀 `monitoring-pool`: `e2-standard-2` × 1, `--node-labels=peekcart.io/role=monitoring`, `--node-taints=peekcart.io/role=monitoring:NoSchedule`.
3. **cleanup 을 먼저 건다.** `CLUSTER_NAME=peekcart-d078 LOADGEN_NAME=peekcart-d078-loadgen bash loadtest/cleanup.sh --dry-run` 이 대상을 정확히 가리키는지 본다.
4. 노드 SA 에 `roles/artifactregistry.reader`. WI 바인딩 `[peekcart/gateway]`·`[peekcart/user-service]`.
5. CSI: upstream `secrets-store-csi-driver` helm(`syncSecret.enabled=false`, `tokenRequests[0].audience=<P>.svc.id.goog`) + GCP provider. 버전을 기록한다.
6. 관측: `monitoring` NS → Grafana 운영 webhook Secret `grafana-ops-webhook`(키 `url`)을 **사용자가** Secret Manager `peekcart-slack-ops-webhook` 에서 만든다(값을 화면에 내지 않는 파이프, Claude 는 실행하지 않는다) → `helm ... -f values-prometheus.yml -f values-measurement-isolation.yml`(차트 버전 기록) → `kubectl apply -k k8s/monitoring/shared/`. Prometheus·Grafana Pod 가 `monitoring-pool` 에 있는지 `-o wide` 로 확인한다.
   Grafana 를 먼저 띄운 뒤 Secret 을 만들면 env 가 비어 있다. 순서를 지키지 못했으면 Grafana Pod 를 재시작한다.
   `kubectl port-forward` 로 Grafana `localhost:3000`, Prometheus `localhost:9090` 을 백그라운드로 연다. 사용자는 같은 주소로 실시간으로 본다.
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
- 캡처는 `loadtest/captures.md` 를 따른다. run 마다 `d078-capture.py annotate` 로 구간을 남기고, S2 가 끝나면 항목 ①②④ 의 `plan` URL 을 브라우저로 찍고 같은 범위를 `export` 한다. 측정 도중에는 찍지 않는다(Prometheus 보존 24h).

### S3. 경합 — ⑤⑥

1. `kubectl delete ns peekcart` 로 읽기 경로 자원을 비운다(PVC 포함, 데이터 초기화).
2. `gke-d078-contention` 을 digest·PROJECT 치환 후 apply. `scripts/auth-roundtrip-gate.sh` 통과가 진입 조건이다.
3. 시드: `d002bc-seed-products.sh`, `d002bc-user-seed.sql`, `generate-users-csv.sh --count <USERS>`. `d002bc-ratelimit-probe.js` 로 429 관문을 먼저 통과한다.
4. 각 run 전후로 product Pod 3개의 `/actuator/prometheus` 원값을 저장한다(`kafka_listener_delivery_failed_total`, `kafka_listener_recovered_total`, `saga_reservation_result_total`). 차분의 부재는 0 이다.
5. `d002bc-contention.js MODE=contend DUR=60s`, RPS 는 Phase 4 주문 수(9,782/60s ≈ 163/s)에 맞춰 170, `USERS ≥ RPS/40`. 3회.
6. run 사이에 outbox backlog 0 과 consumer lag 0 을 기다린 뒤 `d002bc-reset.sql` 로 초기화한다. 대조군 `MODE=spread` 1회도 같은 절차. 섞이면 그 run 은 무효로 적는다.
7. 정합성: 음수 재고 0, 품목별 diff 0, 예약 상태 합 = 주문 수.
8. ⑥: 배수 구간에서 order·product 의 `executor_active_threads`/`executor_queued_tasks`, 그리고 backlog 가 있는데 발행률이 0 인 구간이 있는지(D-024 재발).
9. S3 가 끝나면 항목 ⑤·⑤ 배수·⑥·JVM 을 S2 와 같은 방식으로 찍고 export 한다.
10. 지연 원자료 (A·B): 경합 run 마다 배수 확인 뒤, 6 의 초기화 **전에** `d078-saga-latency.sql`(order)과
    `d078-outbox-latency.sql`(order·product)을 `mysql -B` 로 TSV 로 내보낸다. `d078-latency.py saga|outbox` 로 집계한다.
    왕복에는 order(500ms)와 product(기본 5s)의 폴링 대기가 한 번씩 들어 있으므로, 왕복 수치는 B 의 구간값과
    함께만 적는다. 설정이 지배하는 숫자를 처리 속도로 쓰지 않는다.
11. 배수 시간 (C): k6 종료 시각부터 order·product `outbox_backlog{status="pending"}` 가 모두 0 이 된 시각까지.
    `d078-5-drain` export 로 계산한다. Phase 4 의 552초는 폴링 기본값·2분 부하라 조건을 병기한다.

### S4. base 적용 — ⑧, D-065 ②⑤, D-092

운영 overlay 를 띄운 김에, 같은 스택이 필요한 다른 부채의 GKE 확인을 함께 한다.

1. `kubectl delete ns peekcart`, 기본 노드풀을 3대로 늘린다(정상 상태 requests 약 7.3 vCPU, `pr3d-b2` 편차 2).
2. notification-service Secret 의 `SLACK_USER_WEBHOOK_URL`·`SLACK_OPS_WEBHOOK_URL` 은 **사용자가** Secret Manager 두 시크릿에서 넣는다(서로 다른 값이어야 시작한다, `D-092-slack-split.md`). Toss 자격증명은 smoke 값이다(`pr3d-b2` 편차 4).
3. 첫 배포: `DRAIN_PREFLIGHT_SKIP_REASON="fresh namespace, no prior stack" GW_URL=<gateway LB> bash scripts/deploy-overlay.sh k8s/overlays/gke`. drain preflight 는 fail-closed 라 빈 namespace 에서는 수집이 실패해 배포를 막는다. 건너뛴 사유가 로그에 남는다.
4. ⑧: 렌더한 mysql `limits.cpu` 와 실행 Pod 의 값이 `2` 로 같은지, 인증 왕복 게이트 통과.
5. D-065 ②: 스택이 뜬 뒤 `bash scripts/replay-drain-preflight.sh` 를 실제 클러스터에서 실행한다. replay 이력이 없으므로 exit 0 이 기대값이다. 수집 계층(`kubectl exec` DB·Kafka 조회)이 실제 스택을 관통한 첫 기록이다.
6. D-065 ⑤: 브로커의 토픽 설정(`kafka-configs.sh --describe --entity-type topics`)을 서비스가 선언한 값(`KafkaTopicConfigs` business·dlq, retention floor)과 표로 대조한다. 앱이 기동 중 토픽 설정을 실제로 바꿨는지(`ALTER_CONFIGS`)의 증거다.
7. D-092: (가) Grafana contact point 테스트 API 로 운영 채널에 메시지 1건, (나) gateway 로 주문 1건을 넣어 `order.created` 소비 → 사용자 채널에 메시지 1건. **사용자가 두 채널에서 각각 한 건씩, 서로 다른 채널로 왔는지 확인한다.**
8. E (MSA order-service HPA): 7 까지 끝난 뒤 시드(`d002bc-seed-products.sh`, 사용자 시드)를 넣고
   `d002bc-order-create.js` 를 RPS 100 · USERS 200 · 3분으로 건다. HPA 목표는 CPU 60%(request 500m 기준 300m)이고
   Phase 4 에서 order 는 약 158건/초에 1,784m 를 썼으므로 이 부하로 확장이 일어난다. `kubectl get hpa order-service -w`
   로그와 신규 Pod 의 Ready 시각을 남기고 `d078-hpa` 를 찍는다. 기록할 값: 확장 시작까지 걸린 시간, 1→3 도달 시각,
   신규 Pod Ready 소요, 부하 중 5xx·429. 이 run 에서는 D-092 사용자 채널로 주문 알림이 대량으로 간다 — 7 의 확인을
   먼저 끝낸다. 비교 대상은 모놀리식 1→3·65초 Ready(2026-04-29 리포트)이며 구성이 달라 조건을 병기한다.

### S5. 정리 — 어떤 경로에서도 실행

0. 진입 조건: S2·S3 캡처와 export 파일이 저장돼 있다. 중단 규칙으로 S5 에 온 경우에는 남은 데이터만이라도 export 한 뒤 진행한다.
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

S2 약 2시간, S3 약 1.5시간(지연 원자료 추출 포함), S4 약 1.5시간(E 포함, 3번째 노드 +$0.17/h), 캡처 약 0.5시간,
준비·정리 약 1.5시간. 7~9시간 기준 $4.5~6, 부가세 포함 약 ₩6,500~8,500. 예산 ₩20,000 의 절반 안쪽이다.
중단 규칙의 8시간 한도는 클러스터 생성 시점부터 센다 — 넘기면 E 를 먼저 접는다.

## 6. 검증 방법 (이 PR)

- 두 overlay 의 렌더 diff 가 의도한 줄뿐인지(product replicas, order env).
- `helm template` (차트 91.4.0) 로 관측 스택 6개의 nodeSelector·toleration, node-exporter 의 무조건 toleration 확인.
- namespace lint, lint-health selftest.
- 키쌍 fingerprint 대조는 §1 에 적은 대로 로컬에서 했다. 키 원문은 출력·커밋하지 않았다.

## 7. 범위 밖

- 세션 실행과 증적 작성, TASKS·PHASE5 반영은 세션 후속이다.
- ⑦(#129 익일 Billing)은 무료 크레딧 계정이라 확인하지 않기로 했다(사용자 결정, 2026-10-23).
- outbox 폴링 기본값의 처분은 D-075 다. 경합 overlay 의 500ms·500건은 before 조건 재현일 뿐이다.
- D-065 는 ②⑤ 만 다룬다. replay 개방(4단계), 게이트웨이 관리자 라우트 관통(①), NOT NULL 적용(③), 운영 표본(④), `OriginalRecordReader` 의 실제 좌표 읽기는 replay 를 닫은 채로는 확인할 수 없어 남는다.
- D-092 는 전달 경로만 본다. Outbox `FAILED` 운영 재발행과 Secret 소유·교체 절차는 남는다.
