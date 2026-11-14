# D-110 로컬 order 풀 경합과 outbox 발행

2026-11-14 KST에 로컬에서 실행했다. 이 증적이 답하는 질문은 하나다. GKE 측정(2026-10-26)에서 부하 중 order outbox가
초당 약 15건으로 굶었는데, 그 원인이 order Hikari 풀 대기인가. 판정 규칙은 결과를 보기 전에
[`D-110` 계획서](../../plans/D-110.md) §3에 고정했다.

## 실행

```sh
for P in 10 20 64; do
  PEEKCART_POOL_PROBE=true PEEKCART_POOL_PROBE_SIZE=$P SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$P \
    ./gradlew :order-service:test --tests 'com.peekcart.order.OrderPoolContentionLocalProbeTest' --rerun --no-daemon --console=plain
done
python3 -B docs/evidence/D-110/extract.py docs/evidence/D-110 docs/evidence/D-110/pool{10,20,64}.xml.gz
```

- probe 소스: `order-service/src/test/java/com/peekcart/order/OrderPoolContentionLocalProbeTest.java`
  (sha256 `c7a2f4fb21628ad1345bb7d272e2acc873c23319c1ac46c57ee5a63094dc6c41`). env가 없으면 skip되어 CI에서는 돌지 않는다.
- 부하: 실제 `CartCommandService.addItem` + `OrderCommandService.createOrder`를 사용자별 닫힌 루프 64개 스레드로 돌린다.
  GKE 부하 스크립트의 주문당 요청 두 번과 같다.
- poller: 실제 `OutboxPollingService` 빈(배치 500)을 별도 스레드에서 반복한다. 발행할 것이 없던 사이클 뒤에만 500ms 쉰다.
  ShedLock은 뺐다(단일 인스턴스, 사이클당 획득 2회 차이).
- 구간: 반복마다 세 구간을 차례로 잰다.
  1. 단독: K=1, 15초, poller 끔
  2. 부하: K=64, 60초, poller 켬
  3. 배수: poller만 켜고 PENDING 0까지
  
  반복은 3회다. 반복마다 정합성을 확인한 뒤 DB와 `order.created` 토픽을 비운다.
- 계수: 발행 수와 backlog는 풀 밖의 별도 JDBC 연결로 센다. 그래서 측정이 부하 뒤에 줄 서지 않는다. pending은
  `HikariPoolMXBean.getThreadsAwaitingConnection()`을 1초마다 표본한다. 이 값은 GKE의 `hikaricp_connections_pending`과 같다.
- 환경: Docker VM 8 CPU, `SharedContainers`(MySQL 8.0.46, Kafka 3.8.1 단일 broker). JVM은 호스트에서 돈다.

## 결과 ([summary.md](summary.md), 원자료 [results.jsonl](results.jsonl))

| 풀 | 단독 점유 ms | 부하 점유 ms | 부하 획득 대기 ms | 부하 pending 평균 | 부하 반복/s | 부하 발행/s | 배수 발행/s | 발행비 (범위) |
|---|---|---|---|---|---|---|---|---|
| 10 | 6.40 | 13.26 | 72.9 | 53.6 | 372.9 | 7.0 | 205.0 | 0.03 (0.03~0.04) |
| 20 | 5.37 | 25.34 | 57.0 | 44.2 | 390.9 | 6.4 | 171.5 | 0.04 (0.03~0.04) |
| 64 | 7.20 | 61.20 | 0.9 | 0.9 | 507.6 | 23.8 | 183.6 | 0.13 (0.13~0.13) |

발행비 = 부하 구간 poller 발행률 ÷ 같은 반복의 배수 발행률이며, 반복 3회의 중앙값이다.

- **J1 (풀 경계)**: 미충족. 풀 10에서 poller는 굶는다(0.03). GKE c1의 부하 중 약 15/s 대 부하 뒤 약 71/s, 즉 비율 약 0.2와
  같은 방향이다. 그러나 풀 64에서 획득 대기가 사라져도 발행비는 0.13에 그쳤다. 대기가 풀 큐에서 커넥션 점유
  (13 → 25 → 61ms)로 옮겨갈 뿐이다.
- **J2 (점유 증가)**: 풀 10의 점유비는 2.07이다. 부하가 점유를 늘린다. 다만 늘어난 시간이 MySQL 서버 안(커밋, 잠금)인지
  앱 쪽(JVM CPU)인지는 이 실험으로 가르지 못했다(아래 한계).
- **해석**: poller는 이벤트마다 트랜잭션 하나를 쓰는 순차 클라이언트다. 공유 자원이 포화되면 그 자원이 풀이든 DB든,
  poller는 64개 부하 스레드와 같은 몫으로 줄을 선다. 획득 1회 비용(대기 + 점유)이 이벤트 1건의 비용이 된다.
  - 풀 10: 73 + 13 = 86ms → 약 12/s 상한. 관측은 7.0/s다.
  - 풀 64: 0.9 + 61 = 62ms → 약 16/s. 관측은 23.8/s다.
  
  두 경우 모두 자릿수가 맞는다. 그래서 풀 크기를 바꾸거나 poller 전용 풀을 두는 것만으로는 회복되지 않는다. 회복하려면
  이벤트당 트랜잭션 수를 줄여야 한다(C 저장 묶기). 이 판단에는 ADR이 필요하다. 다른 길은 공유 자원이 포화되지 않게
  용량을 확보하는 것이다.

## 음성 대조와 정합성

- 반복마다 phase 행에서 DB의 PUBLISHED 증가분과 poller success 타이머 증가분이 ±1 안에서 일치했다. 단일 poller가
  타이머를 save 직전에 올리므로 경계 오차는 최대 1이다.
- poller를 끈 10초 부하(`load-no-poller`)의 PUBLISHED 증가분은 0이었다.
- warmup, 반복 1~3, 대조 뒤에 각각 정합성을 확인했다. `order.created` PUBLISHED 수가 주문 수와 같았고, PUBLISHED가 아닌
  outbox는 0이었다.
- 풀 크기 단언: 테스트는 `PEEKCART_POOL_PROBE_SIZE`와 실제 `HikariDataSource.getMaximumPoolSize()`가 다르면 실패한다.
  세 run 모두 통과했다.
- `extract.py`는 다음 경우를 실패로 처리한다: integrity 행 누락(미완 run), phase 행 수 불일치, 대조 행에서 발행이 0이 아닌 경우.

## 한계

- **로컬 MySQL은 GKE와 다르다.** 공유 컨테이너는 Testcontainers 기본 `my.cnf`로 뜬다.
  - `innodb_buffer_pool_size=16M`, `innodb_log_file_size=5M`, `table_open_cache=4`
  - binlog 없음, `performance_schema` 테이블 없음
  - 그래서 점유 증가의 자리(DB인지 앱인지)를 GKE로 옮길 수 없다.
- 2차 실행(round 2)에서는 `performance_schema`로 서버 문장 시간을 재려 했으나 테이블이 없어 세 run 모두 실패했다
  ([round2-pool10-failed.xml.gz](round2-pool10-failed.xml.gz)). 공유 컨테이너 설정을 바꾸면 모든 통합 테스트에 영향이 가므로
  그 계측을 걷어 냈다. 위 소스 해시는 1차 결과를 만든 코드와 같은 상태다.
- 이 상태로는 J4(점유 증가가 DB 쪽인지 앱 쪽인지)가 미판정이다. GKE에서 MySQL 서버 지표로 확인해야 한다(D-114 선행).
- 로컬 JVM에는 CPU limit이 없다. GKE order Pod는 2코어 limit 중 1.7코어를 썼다.
- 부하 모델이 다르다. 로컬은 닫힌 루프 64개이고, GKE는 열린 루프 초당 170건(VU 최대 450)이다. 절대 처리율은 비교하지 않고,
  비율과 방향만 비교한다.

## 측정 밖 관찰: 주문번호 충돌

풀 10과 풀 20 run에서 `Duplicate entry 'ORD-…' for key 'orders.uk_orders_order_number'`가 1건씩 났다. probe는 이를 세고
계속 진행한다. 다른 무결성 위반은 실패로 처리한다.

`OrderCommandService.generateOrderNumber`는 날짜와 UUID 앞 8자리(32비트)로 주문번호를 만든다. 같은 날 n건이면 충돌 확률이
약 1 − exp(−n²/2³³)다. n이 1만이면 약 1.2%, 6만이면 약 34%다. 충돌한 주문 요청은 500으로 실패한다. 첫 시도는 반복마다
DB를 비우지 않아 누적 주문 약 1.8만 건에서 이 오류로 중단됐다. 그 뒤 반복마다 비우도록 고쳤다.

## 원본 보존

- `pool{10,20,64}.xml.gz`와 `pool{10,20,64}-gradle.log.gz`는 1차 결과의 JUnit XML과 Gradle 로그다.
- XML의 `Using generated security password:` 값만 `<redacted>`로 바꿨다. Spring Security가 테스트 컨텍스트마다 무작위로
  만든 일회용 값이고, 그 밖의 바이트는 원본과 같다.
- 첫 시도(SQL 로그 포함 112MB, 주문번호 충돌로 실패)는 보존하지 않았다. 그 run은 64개 스레드의 `show-sql` 출력이
  `System.out`에서 직렬화되어 점유를 부풀렸다. 그래서 probe에서 `spring.jpa.show-sql=false`로 껐다.
