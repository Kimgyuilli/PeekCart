# D-108 재측정 세션 발견 문제 (2026-10-25 UTC)

1. [S3 정지 → 사용자 결정으로 복구] MySQL 데이터 볼륨이 가득 찼다(19:08:29Z, OS errno 28). `mysql-pvc` 는 base 그대로 1Gi(ext4 973MiB)이고
   GKE overlay 는 storageClassName 만 바꾼다. 첫 run 전 고정분이 214MiB(redo 100MiB·undo 64MiB·시스템 테이블)였고 run 마다 83~123MiB
   늘었다. 가득 찬 때 `binlog.000002` 가 544MiB(57%)였다. MySQL 8.0.46 기본값(`log_bin` ON, ROW·FULL, `sync_binlog` 1, 보존 30일,
   파일 상한 1GiB)이라 세션 안에서 지워지지도 회전하지도 않는다. 초기화 DELETE 는 테이블 안 공간만 비우고 지운 행을 binlog 에 다시
   써서 디스크를 되찾지 못한다. payment 스키마는 초기화 대상이 아니라 77MiB 까지 자랐다. 늘어난 743MiB 중 binlog 73%·order·product
   테이블 고점 12%·payment 10%·undo 4%, 주문 1건당 약 12KiB 다. 같은 1Gi 에서 10-24 S3 는 33,080건(4 run), 10-25 S3 는 34,307건
   (7 run, 초당 122건 정체)이었고 이번은 C0 가 170건을 내 61,264건(7 run)이었다. 1Gi 의 한계는 약 6만 건이라 C0 통과 때의 계획 run 수
   (§7: 6~8 + 재시도)는 처음부터 들어가지 않았다. 디스크 쓰기 지연은 사용량 214→892MiB 내내 0.60~0.70ms 로 평평해 가득 차기 전 run 의
   측정값에는 영향이 없다(`stall-logs/run-window-metrics.tsv`). 처리: 지난 계획 §5 의 배수 15분 규칙이 걸렸으나 그 규칙의 전제(D-075
   처분 실패)와 원인이 달라 사용자 결정으로 환경 결함 편차로 다루고 `mysql-pvc` 를 10Gi 로 온라인 확장했다(설정 변경 없음, binlog 끄기는
   커밋 경로가 바뀌어 배제). 원자료 `s3/c0/invalid/contend-retry2/stall-logs/`.
2. [하네스] run 스크립트의 초기화(`d002bc-reset.sql`)에 시간 제한이 없어 binlog 공간 대기에서 약 53분(19:22~20:15Z) 무인으로 멈췄다.
   백그라운드 작업 알림은 종료 때만 오므로 그 사이 아무도 몰랐다. 이후 사용자 요청으로 5분 주기 정지 감시를 붙였다.
3. [하네스] `lag_total` 은 `kafka-consumer-groups.sh --describe` 출력이 비면 0 을 돌려 배수 완료로 오판할 수 있다. 감시 중 수동 조회가
   빈 출력을 0 으로 읽어 "lag 모두 0" 으로 잘못 보고한 일이 있었고(20:56Z, 21:00Z 정정), 복구 배수가 20:41:58Z 에 lag 0 으로 끝난 것도
   같은 순간 수동 조회가 payment group lag 3,815 를 보였으므로 빈 출력 0 이었을 가능성이 크다(같은 컨테이너에서 admin 도구 두 개를 겹쳐
   돌린 때, 원인 미확인). 앞선 run(w4·c1·첫 c2)의 스크립트 t_full 은 product 클라이언트 lag 0 과 1~14초 안에서 같아 그 run 들에는
   오판이 없었다. 출력이 비면 0 이 아니라 "모름"으로 다뤄야 한다(`session-scripts/wait-payment-lag.sh` 는 그렇게 했다).
4. [하네스] outbox 가 0 에 닿지 못하고 배수가 중단되면 콘솔이 "outbox 0 까지 0초" 로 찍는다(`t_outbox0` 이 없을 때 t1 을 쓴다).
5. [환경 여파] 디스크 가득 참 구간에 payment 결제 생성 134건이 DLQ 로 갔고(`payments` 테이블 full), 그 주문들의 예약 결과가
   "결제 정보를 찾을 수 없습니다"로 건마다 약 30~60초 재시도한 뒤 DLQ 로 간다. 컨슈머 1개가 3 파티션을 맡고 재시도가 파티션을
   막아 분당 약 1.4~1.7건씩만 넘어가며, 뒤의 정상 레코드(c2 의 약 1만 건)를 막아 c2 배수의 전 group lag 0 이 900초 안에 오지 않았다.
   order·product 쪽 배수는 끝나 측정값에는 영향이 없다. 고아 주문의 "결제 없음"은 재시도로 풀리지 않는 오류인데 일시 오류처럼
   재시도된다(분류 검토 후보, 세션 안에서 고치지 않음).
6. [정합성 관찰, 측정 run 아님] 복구 뒤 무효 run 의 최종 상태에서 음수 재고는 0 이지만 product 1 재고가 원장 기대값보다 1 적고(diff −1),
   CANCELLED 주문 54839 의 예약 1건이 RESERVED 로 남았다. 그 주문의 취소는 Inventory#1 낙관락 소진으로 DLQ 에 갔다(설계상 DLQ 재처리
   대상). diff −1 은 최종 상태만으로 설명되지 않는다. DB 커밋 정지 중 실패한 처리 경로를 로컬에서 재현해 볼 대상이다
   (`invalid/contend-retry2/integrity-anomaly.txt`).
7. [측정 경계] contend 는 부하 판정이 경계에서 갈린다. 유효는 c1(반복 avg 0.35s)·복구 뒤 c2(0.78s)·spread s1(0.46s), 무효는 복구 전
   c2·c2 재시도·c3(1.16·1.45·1.86s)였다. 주문 1건당 order 커넥션 획득이 약 2.1회라 초당 약 360회가 필요한데, 풀 10 의 한계(10 ÷ 점유
   시간)가 유효 run 은 점유 26.4~27.0ms 로 371~379/s, 무효 run 은 28.2~28.7ms 로 348~354/s 였다(`s3/c0/order-pool-utilization.tsv`).
   점유 시간 1~2ms 차이로 한계가 필요량 위아래를 오가고, 아래로 가면 대기(무효 run 평균 113~150, 최대 191 포화)가 비선형으로 커져
   VU 450 이 차고 드롭이 난다. 디스크 쓰기 지연은 c3 가 0.58ms 로 오히려 가장 낮았고 order CPU 는 모든 run 에서 2코어 중 1.7코어대다.
   유효·무효가 순서와 무관하게 섞여 있어 누적 악화가 아니라 한계선 근처의 run 간 흔들림으로 본다. 점유 시간이 흔들리는 원인은
   분리하지 못했다.
8. [사전 규칙 부족] 워밍업 w4 는 목표(10,200)에 닿았지만 w3 대비 +9.9% 라 §3.1 수렴 판정 문구로는 미수렴이었다. 수락이 목표를 넘을 수
   없어 목표에 처음 닿은 run 은 변화율이 커지는 구조다. 사용자 결정으로 §3.1 근거 문장("목표에 붙어 바로 수렴")에 따라 판정 run 으로
   갔다.
9. [기동] 첫 배포에서 order·user 가 MySQL 기동 전 `Communications link failure`(exit 1)로 2회, product 3개가 각 3회·payment 가 4회
   startup probe 실패로 재시작했다(동시 콜드 기동). 측정 전이라 워밍업 기준점으로만 썼다.
10. [관측] `kafka-pvc` 사용량이 세션 내내 0MiB 였다. 매니페스트는 `/var/lib/kafka/data` 를 마운트하지만 log.dirs 설정이 없어 Kafka 가
    이미지 기본 경로(컨테이너 저장소)에 쓰는 것으로 보인다(확인 안 함).
11. [운영 절차] 결제 계정 연결·해제와 `grafana-ops-webhook` 생성은 사용자가 직접 실행했다. loadgen 생성 직후 IAP 첫 연결은
    `4047 Failed to lookup instance` 였고 재시도에서 붙었다.
12. [하네스] Grafana 3000 port-forward 가 `lost connection to pod` 로 죽자(Grafana Pod 재시작 0) headless Chrome 캡처가 끝나지 않고
    10분 멈췄다(22:07~22:17Z). 캡처 스크립트에 시간 제한이 없었다. 장당 120초 제한·1회 재시도를 넣었고(`session-scripts/capture.sh`),
    port-forward 를 다시 띄우자 8장이 21초에 끝났다.
13. [기록 편차] c2(복구 뒤)의 export 는 스크립트 t_full 이 없어(payment 적체) product 클라이언트 lag 0 시각(t1+242초)으로 대신했다.
    앞 세 run 에서 스크립트 t_full 과 그 시각이 1~14초 안에서 같았다(`s3/c0/c2-tfull-substitute.txt`).
