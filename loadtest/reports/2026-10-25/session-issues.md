# D-078 재측정 세션 발견 문제 (2026-10-25)

1. [S3 차단 → 세션 중 수정] 콜드 JVM 에 RPS 170·USERS 450 을 넣으면 order-service(한도 2000m)의 CPU 가 포화되고, 같은 8080 으로 가는
   liveness(기본 timeout 1초 × 3회)가 실패해 kubelet 이 컨테이너를 죽였다(w1 07:48:36Z, w2 07:54:15Z, exit 143). 재기동 동안 gateway 가
   Connection refused 로 `order-cart` 500 을 냈다(w1 6,519건). 재기동 직후 run 은 다시 워밍업이어야 해서 측정 run 에 들어갈 수 없는
   순환이었다. 2026-10-24(USERS 200)에는 재시작이 없었다. 사용자 결정으로 측정 overlay 에 프로브 완화 patch 를 더했다
   (`gke-d078-contention/patches/order-service-probes.yml`). 운영 프로브 정책(관리 포트 분리 등)은 별도 결정이다.
2. [부하 미달, 원인 미분리] 프로브 완화 뒤 재시작·거절은 0 이 됐지만 수락이 초당 약 122건(w5 7,055 · w6 7,341, spread s1 7,748)에서
   멈춰 부하 판정이 계속 무효였다. 그때 order CPU 평균 0.60·최대 1.38코어, Hikari active 10/10·pending 평균 153·최대 192, MySQL CPU
   0.43코어였다. MySQL 표본(w6)은 행 잠금 대기 175건/85초(평균 10ms), 실행 스레드 대부분 `waiting for handler commit`, 커밋 245/s·
   log fsync 276/s 다. 커넥션은 잠금이 아니라 커밋(fsync) 완료를 기다린다. 2026-10-24 r3 는 같은 order 풀 10 으로 168/s 였다.
   가설: ADR-0045 로 product outbox 발행(건마다 UPDATE 커밋)이 20/s 상한에서 풀려 공유 MySQL 의 커밋 지연이 커졌다. A/B
   (product outbox 5s·100 대조)로만 확인할 수 있다. ADR-0045 재검토 조건 2(부하 중 order 발행률 저하)의 입력이기도 하다.
3. [사전 규칙 부족] 워밍업 1회로는 재기동된 JVM 이 정상 상태에 이르지 않았다(w3 3,108 → w4 5,663 → w5 7,055). 결과를 보기 전에
   "첫 유효 run 까지 워밍업, 연장 최대 3회"로 바꿨다. 다음 세션 계획은 워밍업 종료를 부하 판정 같은 객관 기준으로 사전 등록한다.
4. [사가 지연의 구성] 관찰 run 의 사가 왕복 p50 은 contend 122초·spread 96초이고 대부분이 order outbox 발행 대기(p50 83~90초)다.
   부하 중 order 발행률 50~63건/초가 수신(약 122건/초)을 못 따라가 backlog 가 5~7천 건 쌓였다가 부하 뒤 빠진다. 만료 취소는 0,
   사가 왕복 p99 198초로 4분 아래라 ADR-0045 재검토 조건 1 의 신호는 없다.
5. [대조군 확인] 상품 1,000개 spread(s1)는 전달 실패 0·DLQ 0 으로 contend 관찰 run(w6: 36·5)의 10% 이하다. D-106 ⑦ 의 설계는 의도대로
   저경합이다(부하 판정 무효 run 의 관찰).
6. [하네스] `loadtest/scripts/d026-run-block.sh` 버퍼풀 워밍업의 세 번째 문장 `SELECT SUM(quantity) FROM inventories` 는 컬럼이 `stock`
   이라 ERROR 1054 로 실패하고 출력을 버려 조용히 지나간다(앞의 `COUNT(*)` 둘만 돈다). 지난 세션도 같아 비교 조건은 같다.
7. [하네스] `d026-run-block.sh` 는 셀을 자기 안에서 돌려 run 단위 기록(k6 구간·annotate·Hikari)을 끼울 자리가 없다. 이번에는 블록 준비만
   그 스크립트로(`CELLS=" "`) 하고 셀 루프를 세션 래퍼로 돌렸다. S3 의 run 절차(원값 전후·배수·지연 TSV·건수·정합성·초기화)도 저장소에
   스크립트가 없어 세션 스크립트로 돌렸다.
8. [관측] Grafana 노드 CPU 패널 화면(자동 rate 창)은 list ON run 의 product 노드를 최대 약 65% 로 보이게 하지만, 캡처 도구 export
   (1m 창·15초 step)는 84% 다. 판정은 export 값으로 했다.
9. [운영 절차] 결제 계정 연결은 자동 모드 검사가 막아 사용자가 직접 실행했다. 세션 동안 gcloud·kubectl·helm·crane 허용 규칙을
   `.claude/settings.local.json` 에 임시로 두었다(S5 에서 제거).
10. [S4 편차] notification Slack 두 값은 실제 webhook 대신 base 기본값과 같은 loopback 을 넣었다(D-092 를 반복하지 않음).

## 사후 정리 (2026-10-26, D-116)

Phase 5 마감 정리(D-116)에서 이 폴더의 원자료 일부를 걷어냈다. 결정과 근거는 `docs/evidence/D-116/decisions.md` 1-1~1-3, 계획은 `docs/plans/D-116-loadtest.md` 다. 지우거나 고친 파일은 [pruned.tsv](pruned.tsv) 에 경로·크기·base blob 과 함께 있고 `git show 91cfa1f:<경로>` 로 되살린다.

- 지연 TSV 21개(`s3/s1`, `s3/warmup/w1`~`w6` 의 saga·outbox-order·outbox-product)를 지웠다. 이 세션 S3 는 측정 run 에 들어가지 못했다. run 별 백분위·최댓값·표본 수·제외 건수는 [latency-summary.txt](latency-summary.txt) 에 남겼다.
- 무효 관찰 블록의 캡처 jpg 16개(`contend-obs-block`·`s1-spread`)와 파티션별 kafka-lag export 4개(`s1`·`w3`·`w4`·`w5`)를 지웠다. 같은 구간의 run 별 export json 은 남아 있다. 지운 lag export 는 series 별 최댓값·그 시각·마지막 값을 [grafana/kafka-lag-summary.tsv](grafana/kafka-lag-summary.tsv) 에 남겼다. `w6` 는 진단 표본(`warmup/w6-mysql-sample.txt`)이 인용된 run 이라 export 와 JVM 캡처를 그대로 둔다. `grafana/s3-capture-plan.tsv` 는 촬영 계획이라 지운 jpg 의 이름도 그대로 적혀 있다.
- k6 요약 7개(`s3/s1.json`, `s3/warmup/w1.json`~`w6.json`)의 `setup_data.tokens`(사용자 450명의 RS256 JWT)를 `"tokens_removed": 450` 한 줄로 바꿨다. 토큰은 30분 만료라 이미 쓸 수 없지만 자격증명 모양의 데이터이고 파일 용량의 95% 였다. 다른 키와 값, `startedAt` 은 그대로다. D-108 P2 의 "원자료를 고치지 않는다" 에 대한 예외이며 2026-10-26 사용자 결정이다.
