# D-078 세션 발견 문제 (2026-10-24) — 고친 뒤 재측정
1. [S2 차단] ADR-0037 product 조회 DB 진입 bulkhead(`peekcart.cache.db-max-concurrency` 기본 1, tryAcquire 즉시 PRD-005 503, 최대 < Hikari 10)가 d026 이후(09ed188, 2026-10-17) 들어와 OFF·miss 경로가 직렬화. A list OFF r1/r2·detail OFF r1 의 200 비율 0.77%·?·1.46%. list ON(hit 경로)은 949.2 rps 100%(d026 A list ON 894.9 대비 +6%). 계획서 전제표가 기준 코드 4bed313 에서 bulkhead 를 대조하지 않음. 사용자 결정: S2 미측정, 수정 후 재측정.
2. [캡처] saga 대시보드 p16 "CPU 스로틀 (컨테이너별)" 이 `container_cpu_cfs_throttled_seconds_total` 을 쓰는데 이 시계열이 Prometheus 에 없다(`..._periods_total` 은 있음). kube-prometheus-stack 기본 kubelet cAdvisor metricRelabelings 가 drop 하는 것으로 보임(확인 필요). cgroup cpu.stat 차분은 하네스가 따로 수집하므로 수치 정본은 영향 없음.
3. [배포 편차] 측정 overlay(gke-d002a, gke-d078-contention)에 ServiceMonitor 가 없어 앱 메트릭이 scrape 되지 않는다 → base servicemonitor.yml 을 단계마다 수동 apply.
4. [계획 누락] S3(gke-d078-contention)에 Toss smoke 값 주입 단계가 없어 payment-service 가 TOSS_SECRET_KEY placeholder 미해결로 CrashLoop. Phase 4 와 같이 smoke 값(test_sk_smoke/test_webhook_smoke) Secret patch 후 재기동. 외부 연동 미검증.
5. [부하 미달] contend-r1 목표 170/s 에 수락 4,438건(74/s 상당). dropped_iterations 5,763, maxVUs=USERS=200 포화, order_create p50 1.2s·p95 3.3s. 피크 CPU order-service 1.89코어(한도 2), 노드 1대 93%. Phase 4(9,782건) 대비 주문 수 45%. 측정 중 조정 없음 — 비교는 주문 수 대비 비율로.
6. [배수 오염·대량 취소] r2(8,330건)도 2,609건 취소(order.cancelled 낙관락 실패는 r3 에서만 11). contend-r3(10,111건)은 배수가 534s 걸려 주문 만료 스케줄러(cancel-expired 60s 주기)가 배수 도중 3,839건을 취소했다 → order.cancelled 3,839 발행·product RELEASED 3,835·order.cancelled 낙관락 실패 11. r3 의 배수 시간(C)·충돌 수(⑤)에 취소 경로가 섞였다. r1(264s)만 취소 0. 정합성은 유지(RESERVED 6272+RELEASED 3835+CANCEL_REQUESTED 4 = 10111, diff 0, 음수 0).
   추정 기전: product outbox 기본 폴링(5s·100건 ≈ 20건/초)이 stock.reservation.result 발행을 수 분 지연(r3 p50 154s·p95 309s) → 예약 lease 만료 뒤 확정·결제 대기 주문이 만료 스케줄러에 취소. 배수가 lease 보다 길면 경합 run 의 수천 건이 취소된다 = D-075(폴링 기본값) 입력.
7. [대조군 전제] spread-r1(10,201건) 낙관락 실패 129(created 93·cancelled 36) > contend r1~r3(31·41·61), DLQ 0. 상품 10개 분산은 replica 3 에서 저경합이 아니다 — '대조군 충돌 = 다른 원인' 전제가 이 조건에서 성립하지 않음. 파티션 키·상품 수 설계 재검토 필요.
8. [계획 순서] S4.3 은 deploy-overlay 에 GW_URL 을 줘 인증 게이트를 돌리라 했지만 빈 base 스택엔 시드 계정(loadadmin)이 없어 게이트가 실패한다 → GW_URL 없이 배포, user 시드 후 게이트 별도 실행.
9. [D-065 ② 결함] scripts/replay-drain-preflight.sh:301 이 kafka-consumer-groups --bootstrap-server localhost:9092 고정. k8s Kafka 리스너는 29092(KAFKA_LISTENERS PLAINTEXT://0.0.0.0:29092) → 실제 GKE 스택에서 DLQ group 4개 lag 조회 전부 실패, exit 1 fail-closed. deploy-overlay 의 drain preflight 가 GKE 에서는 구조적으로 항상 막혀 skip 만 가능. 추가 확인 필요: 한 번도 소비 안 된 파티션의 LAG '-' 를 파서가 어떻게 다루는지.
10. [D-065 ⑤] 앱 토픽 30개 중 6개(payment.completed, payment.requested, payment.{completed,failed,refunded,requested}.dlq)가 브로커 기본값(retention.bytes -1, segment.bytes 1GiB, segment.ms 7d, timestamp.before.max 무제한). 나머지 24개는 선언값과 effective 일치(product 토픽은 기본값과 같은 키는 동적 설정에 없음 — 차분만 ALTER). 원인 미분리(자동 생성 선점 후 미갱신 추정) — 로컬 재현 필요.
11. [워밍업 규칙 부재] S3 경합 run 의 order_create p50 1205→701→151ms(contend r1~r3), spread 51ms. 배포 직후 첫 run 이 JVM·커넥션 미예열로 주문 수(4,438 vs 10,111)까지 달라졌다. S2 는 '첫 회 버림' 규칙이 있었지만 S3 에는 없었다 — 재측정 계획에 워밍업 run 을 사전 규칙으로 넣는다.

## 사후 정리 (2026-10-26, D-116)

Phase 5 마감 정리(D-116)에서 이 폴더의 원자료 일부를 고쳤다. 결정과 근거는 `docs/evidence/D-116/decisions.md` 1-1·1-2, 계획은 `docs/plans/D-116-loadtest.md` 다. 고친 파일은 [pruned.tsv](pruned.tsv) 에 경로·크기·base blob 과 함께 있고 `git show 91cfa1f:<경로>` 로 원래 바이트를 되살린다.

- k6 요약 5개(`s3/contend-r1.json`·`contend-r2.json`·`contend-r3.json`·`spread-r1.json`, `s4/e-hpa.json`)의 `setup_data.tokens`(사용자 200명의 RS256 JWT)를 `"tokens_removed": 200` 한 줄로 바꿨다. 토큰은 30분 만료라 이미 쓸 수 없지만 자격증명 모양의 데이터이고 파일 용량의 95% 였다. 다른 키와 값, `startedAt` 은 그대로다. D-108 P2 의 "원자료를 고치지 않는다" 에 대한 예외이며 2026-10-26 사용자 결정이다.
- 이 날짜의 지연 TSV 는 전부 측정 run 이라 남겼다. run 별 백분위·최댓값·표본 수·제외 건수를 [latency-summary.txt](latency-summary.txt) 에 따로 모았다.
- `s4/d065-5-topic-configs-all.txt` 는 `scripts/tests/test_kafka_topic_state_guard.py` 가 읽던 fixture 다. 같은 바이트를 `scripts/tests/fixtures/` 로 복사해 테스트가 이 폴더를 읽지 않게 했다. 원본은 증적이라 그대로 둔다.
