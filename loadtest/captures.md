# 측정 세션 캡처 기준 (D-078 ⑨)

수치만 남기고 그림을 남기지 않으면 README 와 블로그에 실을 근거가 없다. 클러스터를 지운 뒤에는
다시 찍을 수 없으므로, 캡처는 cleanup 전에 끝낸다.

## 원칙

- **수치의 정본은 캡처가 아니다.** 건수는 세션 전후 `/actuator/prometheus` 원값 차분(시계열 부재는 0)과
  DB 조회로 센다. Prometheus `increase()` 는 첫 증가에 생성되는 카운터의 첫 1건을 놓친다. 그래프는
  그 수치가 어떤 모양으로 나왔는지를 보여 주는 보조 증거다.
- **시간 범위를 고정한다.** 런 시작 1분 전부터 종료 2분 후까지를 Grafana 절대 시간으로 지정한다.
  `now-30m` 같은 상대 범위로 찍으면 같은 패널을 다시 찍을 수 없다.
- **조건을 그림 안에 남긴다.** 대시보드 상단에 annotation 으로 셀 이름(예: `B list OFF r2`)을 단다.
  파일명만으로는 MySQL 한도·캐시 상태를 복원할 수 없다.
- **저장 위치**: `loadtest/reports/<YYYY-MM-DD>/grafana/`. 파일명은 `<항목>-<셀 또는 단계>-<대시보드>-p<패널 id>.jpg`
  (예: `d078-5-contend-r1-saga-p7.jpg`). 같은 이름의 `.json` 에 그 패널의 Prometheus 원 시계열을 둔다.

## 도구

`loadtest/scripts/d078-capture.py` 가 아래 표를 코드로 갖고 있다. 표를 고치면 스크립트의 `CAPTURES` 도 고친다.

1. run 이 끝나면 `annotate --label <셀> --from <시작> --to <끝>` 으로 대시보드 4개에 구간을 남긴다.
2. 단계(S2, S3)가 끝나면 항목마다 `plan` 으로 패널 단독 화면 URL 과 파일명을 받아 브라우저로 찍는다.
   Grafana 는 `kubectl port-forward` 로 `localhost:3000` 에 연다.
3. 같은 범위로 `export` 를 돌려 원 시계열을 저장한다. 클러스터를 지운 뒤에는 이것만 남는다.
4. 캡처와 export 가 모두 저장된 것을 확인한 뒤에만 정리 단계로 간다.

패널은 제목으로 찾아 id 로 가리킨다. 대시보드 패널에는 고정 `id` 가 있어야 한다 — 없으면 Grafana 가 로드할
때 번호를 붙여 단독 화면 URL 이 다른 패널을 가리킬 수 있다.

## 항목별 캡처

| 항목 | 대시보드 | 패널 | 언제 |
|---|---|---|---|
| ①② bulkhead 상한 (`d078-1`) | PeekCart — API & JVM | Request Rate, API Response Time (p95) | S2 OFF 셀(상한 1·4)마다 측정 런 1개 이상 |
| ①② bulkhead 상한 (`d078-1`) | PeekCart — Saga & Messaging | MySQL CPU 사용 / 한도, CPU 스로틀 | 같은 런 |
| ①② bulkhead 상한 (`d078-2`) | PeekCart — Pod Resources & HPA | Pod CPU Usage | 같은 런 |
| ①② bulkhead 상한 (`d078-2`) | PeekCart — Saga & Messaging | HikariCP 대기 커넥션, CPU 스로틀 | 같은 런 |
| ④ 노드 경합 (`d078-4`) | PeekCart — Saga & Messaging | 노드 CPU 사용률 | S2 list ON 측정 런 (캐시 hit 경로) |
| ⑤ 충돌·DLQ | PeekCart — Saga & Messaging | 소비 전달 실패율, 누적 실패 · DLQ 유입, 재고 예약 결과 | 경합 런마다 |
| ⑤ 사가 배수 | PeekCart — Saga & Messaging | Outbox 발행률, Outbox backlog | 경합 런 시작부터 backlog 0 복귀까지 |
| ⑥ 스케줄러 풀 | PeekCart — Saga & Messaging | taskScheduler active / pool, 대기 작업 | 경합 런 배수 구간 |
| 공통 | PeekCart — Kafka Consumer Lag | Kafka Consumer Lag (Max) | 경합 런 |
| D JVM | PeekCart — API & JVM | JVM Heap Memory, JVM GC Pause | S2 측정 런 1개, 경합 런 1개 (`d078-jvm`) |
| E HPA | PeekCart — Pod Resources & HPA | HPA Current Replicas, Pod CPU Usage | S4 주문 부하 시작 1분 전부터 안정화까지 (`d078-hpa`) |

③ `product` 캐시와 ⑧ base 적용은 그래프가 아니라 counter 차분과 `kubectl get` 출력으로 남긴다.
A 사가 왕복 지연과 B outbox 발행 대기는 그래프가 아니라 DB 원자료다. 경합 런이 배수된 뒤, 초기화 SQL 전에
`loadtest/sql/d078-saga-latency.sql`(order)과 `d078-outbox-latency.sql`(order·product)을 `mysql -B` 로 TSV 로
내보내고 `loadtest/scripts/d078-latency.py` 로 집계한다. 측정 run 의 TSV 원본도 같은 디렉터리에 둔다. 워밍업·무효 run 은
아래 §세션 뒤 정리를 따른다.

## Phase 4 before 값과 함께 읽을 때

같은 GKE 라도 세션 간 절대값 비교는 오염된다(`docs/progress/evidence/d026-d002a-read-ceiling-20260920.md` §읽는 법 ①).
⑤ 의 before(충돌 7 · DLQ 7, 주문 9,782)와 after 를 나란히 둘 때는 주문 수 대비 비율로 적고, 두 세션의
노드·이미지·k6 버전을 함께 적는다.

## 세션 뒤 정리

기록을 마친 뒤 커밋 전에 아래를 따른다. D-116(2026-10-26)이 10월 세션 세 개에 적용한 기준이고, 결정 근거는
`docs/evidence/D-116/decisions.md` 1-1~1-3 이다.

- 지연 TSV 는 측정 run 것만 남긴다. 워밍업·무효 run 은 `d078-latency.py` 집계를 날짜 폴더의 `latency-summary.txt` 에
  run 마다 실행 명령과 함께 남기고 TSV 는 커밋하지 않는다.
- k6 `--summary-export` 의 `setup_data.tokens` 는 커밋 전에 걷어내고 개수만 `tokens_removed` 로 남긴다. 사용자 수만큼
  JWT 가 들어 있어 파일 용량의 대부분을 차지하고, 만료됐어도 자격증명 모양이다.
- 무효 관찰 블록의 jpg 캡처는 남기지 않는다. run 별 export json 은 남긴다. 파티션별 lag export 처럼 큰 것은
  series 별 최댓값·시각을 요약해 남긴다. 판정 입력(k6 요약, `-counts`·`-times`·`-prom-delta`, 워밍업 판정)은 run 이
  무효여도 남긴다.
- 이미 커밋한 원자료를 지우거나 고치면 날짜 폴더의 `pruned.tsv` 에 경로·크기·base blob·근거를 적는다. 되살리기는
  `git show <base>:<경로>` 다.
