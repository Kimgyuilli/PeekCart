# D-108 마지막 재측정 세션 시각 기록 (UTC, 날짜 2026-10-25)

계획 `docs/plans/D-108.md`. 세션 main `2ecc573876fa615dc8d5eaf249dd64abc5fcbcdf`(#249), main CI 40215609885 성공(publish 6개 성공).
기록 브랜치 `docs/d108-remeasure-session`.

| 시각 | 단계 | 내용 |
|---|---|---|
| ~17:3x | S0-1 | 계획 §2 전제를 세션 SHA 에서 다시 대조. 4293cba 이후 제품 코드 변경 없음(k8s·loadtest 는 D-108 추가분뿐). C2 렌더 차이는 product env 3줄 |
| ~17:3x | S0-2 | gcloud configuration `peekcart-d078` 활성화. `billingEnabled: False` 확인, 결제 연결은 사용자 실행 대기 |
| ~17:32 | S0-2 | 사용자가 결제 계정 연결(`billingEnabled: true`). 예산 "₩20,000 월 예산 알림" 50·90·100·150% 그대로, 세션 API 7개 켜짐 |
| 17:32~17:33 | S0-3 | GSA 2개 활성, 시크릿 4개 모두 버전 1·enabled(2026-10-22T16:58~17:44Z), 서명키 2개는 각자 자기 GSA 만 접근. 공개키 SPKI SHA-256 앞 16자 local-keys = ConfigMap(`908f1da22b4db672`·`17df680623f50fe6`) |
| 17:33:23~17:35:41 | S0-4 | AR 리포지토리 `peekcart` 재생성, 태그 `2ecc573…` 6개 승격(재시도 없음). AR digest 6개 = GHCR digest. `session-scripts/session-pin.sh` 로 치환 7개 파일(커밋하지 않음), S3 overlay 2개 렌더 자리표시 0·앱 이미지 전부 digest |
| 17:36:4x~17:40:49 | S1-1 | GKE `peekcart-d078` 생성. 1.35.6-gke.1250001(지난 세션과 같다), STABLE, `ADVANCED_DATAPATH`, WI `peekcart-d078.svc.id.goog`, 관리형 Secret Manager 애드온 없음, `e2-standard-4` × 2 |
| 17:36:49~17:37:02 | S1-3 | loadgen `peekcart-d078-loadgen` 생성(10.178.0.22, `e2-medium`, debian-12). IAP 첫 연결은 `4047 Failed to lookup instance`(생성 직후), 17:37:39Z 재시도에서 k6 v2.2.0(commit 00a9a1b7f5) 설치. 스크립트 2개·`users.csv`(451줄) sha256 로컬과 같음 |
| 17:41:08 | S1-1 | cleanup dry-run: 클러스터·loadgen 만 가리킴, 미부착 디스크·예약 IP 0. 노드 SA `roles/artifactregistry.reader`·GSA 2개 WI 바인딩은 프로젝트에 남아 있어 새로 부여하지 않음 |
| 17:41:21~17:41:35 | S1-1 | CSI chart 1.6.1(`syncSecret.enabled=false`, audience `peekcart-d078.svc.id.goog`), provider-gcp v1.17.0(플러그인 이미지 digest 고정 매니페스트) |
| ~17:40:5x~17:42:17 | S1-1 | `monitoring-pool`(`e2-standard-2` × 1, 라벨 `peekcart.io/role=monitoring`, `NoSchedule` taint) Ready |
| ~17:53 | S1-2 | 사용자가 `grafana-ops-webhook` 생성(runbook §2-1). 키 `url`, Slack 형식 1, 정본과 해시 앞 16자 같음 |
| 17:53:56~17:56:22 | S1-2 | `kube-prometheus-stack` 91.4.0(`--wait`), Grafana 13.2.1. Grafana·Prometheus·operator·kube-state-metrics 는 `monitoring-pool`. shared 적용 17:56:23(대시보드 4개 `peekcart-*`, 알림 규칙 16개). 3000·9090 port-forward |
| 17:57:53~18:03:17 | S3-1 | `deploy-overlay.sh k8s/overlays/gke-d078-contention`(C0, `GW_URL` 없이) exit 0. kafka 선행, 토픽 30개 생성·파티션 수 일치 |
| 18:03:47~18:08:04 | S3-1 | payment Secret 에 Toss smoke 값, payment 재기동, 전체 rollout. 기동 중 재시작: order·user 2회(MySQL 기동 전 `Communications link failure`, exit 1), product 3개 각 3회·새 payment 4회(동시 콜드 기동 중 startup probe 실패로 kubelet 종료, exit 143). 18:08 이후 전부 Ready. 측정 전이라 워밍업 기준점으로만 쓴다(재시작 수 order 2, product 3·3·3) |
| 18:08:43 | S3-2 | 사용자 시드 451(450 + admin). gateway port-forward 18080 |
| 18:08:47 | S3-2 | 인증 왕복 게이트 PASS |
| 18:08:58~18:11:43 | S3-2 | 상품 1,000개, 가격 캐시 1000/1000, `PRODUCT_IDS` 1,000개(3,893바이트) → loadgen `/tmp/product-ids.txt`(1,000개 확인) |
| ~18:12 | S3-2 | 429 프로브 PASS(429 4,924건), 측정 LB `10.178.0.28` |
| 18:12:21~18:12:56 | S3-3 | 토픽 상태 가드 exit 0(토픽 30개 파티션·config 일치, 업무 group 22개 전 파티션 할당) |
| 18:13:35~18:21:06 | S3 C0 | 워밍업 w1(contend, USERS 450·RPS 170·60s). 세션 래퍼 `session-scripts/run.sh` 가 run 앞뒤 order·product 재시작 수를 남긴다. k6 exit 99 무효, 수락 4,819, 드롭 5,382, 반복 p95 8.56s, HTTP 실패 0, 재시작 0. 배수 outbox 94초·lag 243초 |
| 18:21:27~18:28:37 | S3 C0 | 워밍업 w2. 무효, 수락 6,967(w1 대비 +44.6%, 미수렴), 드롭 3,234, 반복 p95 5.68s, 재시작 0. 배수 outbox 116초·lag 226초 |
| 18:28:53~18:36:36 | S3 C0 | 워밍업 w3. 무효, 수락 9,284(w2 대비 +33.3%, 미수렴), 드롭 916, 반복 p95 4.19s, 재시작 0. 배수 outbox 191초·lag 259초 |
| 18:37:02~18:44:28 | S3 C0 | 워밍업 w4(마지막). k6 exit 0, **부하 판정 유효**(드롭 0), 수락 10,201(목표 10,200), 반복 p95 2.2s(avg 0.9s, 바쁜 VU 최대 336), 재시작 0. 수렴 판정은 w3 대비 +9.9% 로 **미수렴**. 배수 outbox 147초·lag 244초 |
| 18:44:3x | S3 C0 | **판단 보류(결과를 본 뒤의 규칙 해석이라 사용자에게 확인)**: §3.1 문자 그대로면 "4회 안에 미수렴 → C0 미달 → C1". 같은 절의 근거 문장은 "170건에 닿는 설정은 수락이 목표(10,200)에 붙어 바로 수렴한다". w4 는 목표에 닿았지만 직전 run 이 목표 아래라 변화율이 5% 를 넘었다. 대기 중 클러스터 유지 |
| ~19:0x | S3 C0 | **사용자 결정(편차)**: w4 를 "목표에 붙어 수렴"(§3.1 근거 문장)으로 보고 C0 판정 run 으로 간다. 판정 run 규칙(유효면 통과, 두 번 무효면 미달 → C1)은 그대로다. 근거: 수락은 목표 10,200 을 넘을 수 없어 목표에 처음 닿은 run 은 직전 대비 변화율이 커진다. 규칙대로면 C1(USERS 200)인데 w4 바쁜 VU 최대 336 이라 k6 VU 부족 무효 위험과 "USERS 450 동시성" 오귀속 위험이 있었다 |
| 18:47:56~18:55:30 | S3 C0 | **판정 run c1 유효 → C0 통과**(측정 c1). 수락 10,200, 드롭 0, 반복 avg 0.35s·p95 0.8s, 바쁜 VU 최대 152, HTTP 실패 0, 429 0, 재시작 0. 전달 실패(optimistic_lock) 73, DLQ 5(0.049%), 예약 성공 10,195. 배수 outbox 154초·lag 254초. MySQL 표본 `c0/c1-mysql-sample.txt`. 사가 왕복 p50 128.5s·p99 240.7s, order outbox 대기 p50 93.9s·p99 136.1s. 만료 취소 3 은 모두 DLQ 5건(예약 결과 없음) 중 먼저 생긴 3건이다(예약 성공 건의 만료 취소 0) |
| 18:56:10~19:03:50 | S3 C0 | 측정 c2(contend) **무효**: 드롭 277 > 102, 수락 9,924, 반복 avg 1.16s·p95 3.43s, 바쁜 VU 최대 450(포화), HTTP 실패 0, 재시작 0. 전달 실패 62, DLQ 7. 원자료는 `c0/invalid/contend-retry1/` 로 옮김(§3.3 모드당 재시도 2회 중 1회) |
| 19:04:14~19:07:25 | S3 C0 | 측정 c2 재시도(contend 재시도 1) **무효**: 드롭 331 > 102, 수락 9,869, 반복 avg 1.45s·p95 3.59s, 바쁜 VU 최대 450(포화), HTTP 실패 0, 429 0, 재시작 0(run 앞뒤 같음) |
| 19:08:29 | S3 C0 | **MySQL 데이터 볼륨 가득 참**(OS errno 28 `No space left on device`). `payments.ibd` 확장 실패, `binlog.000002` 쓰기에서 "Disk is full ... Waiting for someone to free space". `mysql-pvc` 는 base 그대로 1Gi. 이 시각부터 order·product outbox PENDING 이 4,936·42 에서 멈춘다(배수 시작 61초 뒤) |
| 19:13:29 | S3 C0 | order-service `order-svc-stock-result-group` consumer 가 poll 시간 초과로 group 을 떠남(커밋 대기) |
| 19:22:27 | S3 C0 | 배수 900초 초과 → 지난 계획 §5 "배수 15분 초과면 그 run 의 정합성만 확인하고 S3 를 접는다" 발동. 정합성: 음수 재고 0, order outbox 행 9,869 = 주문 9,869, RESERVED 2,878 = product outbox 행 2,878 = 예약 성공 차분 2,878 |
| 19:22:27~20:16:31 | S3 C0 | run 스크립트의 초기화(`d002bc-reset.sql` DELETE)가 binlog 공간 대기로 멈춤. 20:15:49 order 초기화 호출, 20:16:31 product 초기화 호출을 끊었고 스크립트는 exit 3 으로 끝남. 약 53분 무인 정지(초기화에 시간 제한이 없음, 세션 문제로 적는다) |
| 20:16:59~20:19 | S3 C0 | 클러스터와 함께 사라질 로그만 읽기 전용으로 저장(`kubectl logs`·`top`, 클러스터 안 명령 없음): `c0/invalid/contend-retry2/stall-logs/`. 원자료는 `c0/invalid/contend-retry2/`. `.log` 원본은 저장소 규칙(`.gitignore`)상 로컬에만 두고, MySQL 첫 오류 줄과 오류 종류별 건수는 `stall-logs/mysql-errors-excerpt.txt` 에 발췌했다 |
| ~20:19~20:28 | S3 C0 | **사용자 지시로 원인 분석**(읽기 전용: `du`·`df`·`SHOW VARIABLES`, Prometheus `kubelet_volume_stats_*`·노드 디스크·Hikari). `binlog.000002` 544MiB(볼륨의 57%), MySQL 8.0.46 기본값(`log_bin` ON, ROW·FULL, `sync_binlog` 1, 보존 30일, 파일 상한 1GiB). 첫 run 전 214MiB, run 마다 83~123MiB. 주문 합계 10-24 S3 33,080·10-25 S3 34,307·이번 61,264. MySQL 디스크 쓰기 지연은 run 내내 0.60~0.70ms 로 평평해 c2 두 번의 무효는 디스크 탓이 아니다(order Hikari 대기 평균 c1 47 → 116·113). 자료 `stall-logs/mysql-disk.txt`·`mysql-vars.txt`·`mysql-pvc-used.tsv`·`run-window-metrics.tsv` |
| ~20:30 | S3 C0 | **사용자 결정(편차)**: 환경 결함으로 보고 "복구 후 재개". 근거: 원인이 측정 대상 밖(errno 28, 부하 구간 뒤 발생)이고, 볼륨 확장은 설정을 바꾸지 않는다(binlog 끄기는 커밋 경로가 바뀌어 배제). 계속 여부는 c1 값과 무관하게 정했다. c2 재시도는 부하 구간이 디스크가 차기 전에 끝나 부하 판정 무효를 그대로 두고 contend 재시도 예산 1회 차감을 유지한다(남은 기회 1회). 복구 뒤 배수·초기화·토픽 가드를 확인하고 재개하며, 복구 전후 run 을 구분해 적는다 |
| 20:30:33~20:31:06 | 복구 | `mysql-pvc` 1Gi → 10Gi 온라인 확장(PD CSI, Pod 재시작 없음). `df` 10,283,868KiB·사용 10% |
| 20:31:13~20:41:58 | 복구 | 멈춘 run 배수 625초(`session-scripts/recovery-drain.sh`, `recovery-drain.tsv`). 쓰기 재개 뒤 주문 9,869건 전부 만료 취소되어 order outbox 가 최고 7,918 까지 오른 뒤 0. lag 은 20:37:09~20:41:41 동안 3,822 → 3,816(`payment-svc-stock-result-group`, `stock.reservation.result` 3 파티션)에서 머물다 한 번에 0. 5분 감시(사용자 요청, 05:36 KST 부터)가 이 구간을 정지 후보로 잡아 group 별 lag 을 읽었다(`recovery-groups-2042.txt`) |
| 20:42:45~20:43:5x | 복구 | 무효 run 최종 상태(`c2-counts-after-recovery.txt`·`c2-verify-after-recovery.txt`·`integrity-anomaly.txt`): 주문 9,869 전부 CANCELLED, 음수 재고 0, 예약 RELEASED 7,034·CANCEL_REQUESTED 2,834·RESERVED 1, product DLQ 52(lock wait timeout 40 — 디스크 가득 참 구간, 낙관락 소진 12). **정합성 이상**: RESERVED 로 남은 1건(주문 54839)은 취소가 낙관락 소진으로 DLQ 에 갔고, product 1 재고가 원장 기대값보다 1 적다(diff −1). 측정 run 이 아니며 원인 미확인(로컬 분석 대상) |
| 20:44:28~20:44:33 | 복구 | order·product 초기화(`c2-reset-after-recovery.txt`, c1 초기화와 같은 출력) |
| 20:44:39~20:45:18 | 복구 | 토픽 가드 exit 0(`s3/topic-guard-after-recovery/`, 토픽 30개·업무 group 22개 전 파티션 할당). 여기부터 복구 뒤 run |
| 20:45:36~21:04:22 | S3 C0 | **측정 c2(contend 재시도 2, 복구 뒤) 부하 판정 유효**: 수락 10,194, 드롭 7, 반복 avg 0.78s·p95 2.67s, 바쁜 VU 최대 442, HTTP 실패 0, 429 0, 재시작 0. 전달 실패(optimistic_lock) 89, 회복 7, 예약 성공 10,187, DLQ 7(0.069%), 음수 재고 0, 원장 diff 없음. outbox 0 까지 199초. **lag 0 은 900초 안에 오지 않아 배수 중단(exit 3)**: 남은 lag 은 전부 `payment-svc-stock-result-group` 파티션 1·2(약 10,186). 무효 run 의 디스크 가득 참 구간에 payment 결제 생성 134건이 DLQ 로 갔고(`payments` 테이블 full), 그 주문들의 예약 결과가 "결제 정보를 찾을 수 없습니다"로 건마다 약 30~60초 재시도 뒤 DLQ 로 가며(21:03:50 까지 50건) 뒤의 c2 레코드를 막는다. order·product group lag 은 21:00 에 0. 20:56 감시 보고의 "lag 모두 0" 은 조회 빈 출력을 0 으로 읽은 오판이었다(21:00 에 정정) |
| ~21:07 | S3 C0 | **사용자 결정(편차)**: c2 는 측정 범위(order·product) 배수가 끝난 유효 run 으로 두고, payment lag 은 디스크 가득 참의 여파로 적는다. 고아 레코드(최대 약 84건, 약 50분)가 빠져 전 group lag 0 이 된 뒤 c3·s1 을 배수 정의를 바꾸지 않고 돈다. 기다리는 동안 c1·c2 export·캡처·환경 지문 |
| 21:07~21:2x | S3 C0 | c1 export(`d078-s3-export.sh`, `s3-export-c1.txt`), c2 export 는 t_full 대체값 1792961570(product 클라이언트 lag 0, `c0/c2-tfull-substitute.txt`)으로 같은 구간 규칙(`s3-export-c2.txt`). 캡처 18장(`grafana/capture-plan.tsv`, `session-scripts/capture.sh`, headless Chrome → 3001 인증 프록시 → JPEG). 환경 지문(`environment-fingerprint.txt`). c2 지연: 사가 왕복 p50 141.8s·p99 241.9s, order outbox 대기 p50 98.7s·p99 146.2s, product outbox p50 0.49s·p99 1.01s |
| 21:08:17~21:50:47 | S3 C0 | payment 고아 레코드 적체 대기 2,540초(`session-scripts/wait-payment-lag.sh`, `c0/payment-lag-wait.tsv`). payment `stock.reservation.result` DLQ 123건(결제 생성 실패 134건은 모두 19:09:32~20:31:40Z). 21:50:54 전 group lag 0(파티션 103개, `c0/groups-before-c3.txt`), order·product 건수·DLQ 0·재고 100,000, Pod 재시작 없음 |
| 21:51:34~21:59:20 | S3 C0 | 측정 c3(contend) **무효**: 드롭 431 > 102, 수락 9,770, 반복 avg 1.86s·p95 3.77s, 바쁜 VU 최대 450(포화), HTTP 실패 0, 429 0, 재시작 0. 배수 outbox 147초·lag 265초(정상), DLQ 8, 음수 재고 0, RESERVED 9,762 = product outbox = 예약 성공 차분. contend 재시도 2회를 다 써(c2 → 재시도 1 → 재시도 2) §3.3·지난 계획 §5 대로 **contend 를 접는다**: 유효 측정 c1·c2, c3 는 "부하 미달". 원자료 `c0/invalid/contend-c3-exhausted/` |
| 21:59:43~22:06:23 | S3 C0 | **측정 s1(spread) 유효**: 수락 10,200, 드롭 0, 반복 avg 0.46s·p95 0.99s, 바쁜 VU 최대 163, 주문 거절 1(HTTP 실패 1·check 실패 1, 상태 코드는 요약에 없음), 429 0, 재시작 0. 전달 실패 0, DLQ 0, 음수 재고 0, RESERVED 10,200. 배수 outbox 153초·lag 198초. 사가 왕복 p50 109.3s·p99 169.0s, order outbox 대기 p50 103.4s·p99 149.7s, product outbox p50 1.58s·p99 2.53s |
| 22:06~22:29 | S3 C0 | s1 export(`s3-export-s1.txt`). s1 캡처 첫 장에서 headless Chrome 이 끝나지 않음(22:07~22:17), 장당 120초 제한·1회 재시도를 넣어도 시간 초과. Grafana 3000 port-forward 가 `lost connection to pod` 로 죽어 있었다(Grafana Pod 재시작 0). port-forward 를 다시 띄운 뒤(사용자 선택) 8장 21초에 완료(22:28:32~22:28:53). run 별 부하 구간 지표 `c0/run-window-metrics.tsv`, order 풀 사용률 `c0/order-pool-utilization.tsv`(유효 run 점유 26.4~27.0ms·한계 371~379/s, 무효 run 28.2~28.7ms·348~354/s) |
| 22:30:09~22:31:06 | S5 | `kubectl delete ns peekcart`(`s5-ns-delete.txt`), peekcart PV 3개 회수. port-forward 3개·인증 프록시 종료 |
| 22:31:17~22:36:11 | S5 | `cleanup.sh`(`cleanup.txt`): 클러스터·loadgen 삭제, 미부착 디스크 1개(Prometheus PV) 회수, 7종 모두 `[0]`. 세션 치환 7개 파일 `git restore` |
| 22:36:30~22:36:51 | S5 | AR 리포지토리 `peekcart`·Secret Manager 시크릿 4개·GSA 2개 삭제, 다시 조회해 각 0개(`s5-delete.txt`). 5분 정지 감시 종료 |
| ~22:37 | S5 | 사용자가 결제 연결 해제(`billingEnabled: false`) |
| 22:38:25 | S5 | gcloud configuration `default` 로 원복(ADC quota project 불일치 경고는 세션 범위 밖이라 두었다). 클러스터 가동 17:36~22:35Z 약 4시간 58분, D-108 §7 단가로 약 $2.6~3.1 추정(결제 콘솔 확인 전) |
