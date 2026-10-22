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
- **저장 위치**: `loadtest/reports/<YYYY-MM-DD>/grafana/`. 파일명은 `<항목>-<셀 또는 단계>-<패널>.png`
  (예: `d078-5-contend-r1-delivery-failed.png`).

## 항목별 캡처

| 항목 | 대시보드 | 패널 | 언제 |
|---|---|---|---|
| ① list 배속 | PeekCart — API & JVM | Request Rate, API Response Time (p95) | 블록 A·B·A′ 각 셀 1런 이상 |
| ① list 배속 | PeekCart — Saga & Messaging | MySQL CPU 사용 / 한도, CPU 스로틀 | 같은 런 |
| ② 2000m 새 천장 | PeekCart — Pod Resources & HPA | Pod CPU Usage | 블록 B detail OFF 런 |
| ② 2000m 새 천장 | PeekCart — Saga & Messaging | HikariCP 대기 커넥션, CPU 스로틀 | 같은 런 |
| ④ 노드 경합 | PeekCart — Saga & Messaging | 노드 CPU 사용률 | 블록 B list ON 런 (캐시 miss 0 인데 MySQL 한도에 반응한 셀) |
| ⑤ 충돌·DLQ | PeekCart — Saga & Messaging | 소비 전달 실패율, 누적 실패 · DLQ 유입, 재고 예약 결과 | 경합 런마다 |
| ⑤ 사가 배수 | PeekCart — Saga & Messaging | Outbox 발행률, Outbox backlog | 경합 런 시작부터 backlog 0 복귀까지 |
| ⑥ 스케줄러 풀 | PeekCart — Saga & Messaging | taskScheduler active / pool, 대기 작업 | 경합 런 배수 구간 |
| 공통 | PeekCart — Kafka Consumer Lag | Kafka Consumer Lag (Max) | 경합 런 |

③ `product` 캐시와 ⑧ base 적용은 그래프가 아니라 counter 차분과 `kubectl get` 출력으로 남긴다.

## Phase 4 before 값과 함께 읽을 때

같은 GKE 라도 세션 간 절대값 비교는 오염된다(`docs/progress/evidence/d026-d002a-read-ceiling-20260920.md` §읽는 법 ①).
⑤ 의 before(충돌 7 · DLQ 7, 주문 9,782)와 after 를 나란히 둘 때는 주문 수 대비 비율로 적고, 두 세션의
노드·이미지·k6 버전을 함께 적는다.
