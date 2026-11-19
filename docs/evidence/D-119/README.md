# D-119 outbox 발행 상태의 사이클 단위 기록 — 로컬 효과

2026-11-19 KST 에 로컬에서 실행했다. 이 증적이 답하는 질문은 하나다. ADR-0049(ack 받은 이벤트를 사이클 끝에 한 문장으로 기록)가
D-110 에서 관측한 부하 중 order outbox 발행 굶음을 로컬에서 푸는가. 판정 규칙은 결과를 보기 전에
[`D-119` 계획서](../../plans/D-119.md) §검증 방법 V4 에 고정했다.

## 실행

```sh
# 기준 코드: main 8433ad29 를 별도 worktree 로 꺼내 같은 명령을 돌렸다
# 변경 코드: 이 브랜치
for P in 10 64; do   # 기준 코드는 10 만
  PEEKCART_POOL_PROBE=true PEEKCART_POOL_PROBE_SIZE=$P SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$P \
    ./gradlew :order-service:test --tests 'com.peekcart.order.OrderPoolContentionLocalProbeTest' --rerun --no-daemon --console=plain
done
python3 -B docs/evidence/D-119/extract.py docs/evidence/D-119 \
  base-pool10=docs/evidence/D-119/base-pool10.xml.gz c-pool10=docs/evidence/D-119/c-pool10.xml.gz c-pool64=docs/evidence/D-119/c-pool64.xml.gz
```

- probe 는 D-110 과 같은 `OrderPoolContentionLocalProbeTest` 다(구간·반복·부하 모델은 [D-110 README](../D-110/README.md) §실행).
  변경 코드 쪽 probe 는 타이머 대 DB PUBLISHED 차이의 허용치만 ±1 에서 ±`batch-size` 로 바꿨다. 기록이 사이클 끝으로 옮겨 가
  구간 경계에서 DB 가 진행 중 사이클의 ack 만큼 늦기 때문이다. 변경 코드 probe 소스 sha256
  `f84989afdb8a8334ada2933565f3ccaecd7ec9e0c619f0e7c8c3650a25b07757`.
- 세 run 은 22:40~23:02 에 순서대로 돌렸다. 동시에 돌리지 않았고, 그동안 같은 기계에서 다른 gradle 작업을 하지 않았다.
- 판정 지표: **따라잡기 비율** = 부하 구간 발행/s ÷ 같은 구간 반복(주문)/s, 반복 3회 중앙값, 풀 10.

## 결과 ([summary.md](summary.md), 원자료 [results.jsonl](results.jsonl))

| run | 풀 | 부하 점유 ms | 부하 획득 대기 ms | 부하 pending 평균 | 부하 반복/s | 부하 발행/s | 따라잡기 비율 (범위) |
|---|---|---|---|---|---|---|---|
| 기준 코드 | 10 | 27.50 | 151.2 | 53.6 | 180.0 | 3.0 | 0.017 (0.017~0.018) |
| 변경 코드 | 10 | 21.57 | 117.9 | 53.3 | 230.2 | 227.1 | **0.986** (0.984~0.995) |
| 변경 코드 | 64 | 98.63 | 0.5 | 0.3 | 322.4 | 320.9 | 0.992 (0.795~0.995) |

**판정: K1 충족.** 풀 10 에서 부하 중 발행이 주문 수신을 따라간다(0.986 ≥ 0.9). 같은 세션 기준 코드의 약 58배다.

- 풀 10 은 그대로 포화돼 있다(pending 평균 53, 획득 대기 118ms). 그런데도 발행이 따라가는 것은 poller 의 커넥션 획득이
  이벤트 수가 아니라 사이클 수에 비례하게 됐기 때문이다. D-110 의 해석(획득 1회 비용 = 이벤트 1건 비용)과 맞는다.
- 변경 코드에서 부하 반복/s 도 180 → 230 으로 올랐다. poller 가 획득 큐에 덜 서는 만큼 주문 경로가 덜 기다린 것으로 보이지만,
  이 실험은 그것을 따로 재지 않았다. 관측값으로만 적는다.
- 풀 64 의 1회차 따라잡기 비율은 0.795 다(2·3회차 0.992·0.995). 1회차 뒤 배수 구간이 3.7초 걸렸다. 반복 순서상 첫 부하 구간이라
  워밍업 영향으로 보이나 이 실험으로 가르지 않았다. 판정 대상(풀 10)의 세 반복은 0.984~0.995 로 고르다.

## 읽는 법의 주의

- **배수 발행/s 와 발행비는 변경 코드에서 처리 용량이 아니다.** 부하가 끝날 때 남은 backlog 가 한 사이클 안팎이라 배수 구간이
  0.1초 안팎에 끝난다. 그래서 배수 발행/s 가 63.8~1627 로 흩어진다([results.jsonl](results.jsonl) `drain` 행). D-110 의 발행비 판정을
  이 결과에 그대로 적용하지 않는 이유다. 판정 지표를 따라잡기 비율로 정한 것은 계획 단계에서였다.
- **기계 조건이 D-110 과 다르다.** 기준 코드 풀 10 의 부하 반복/s 는 180(D-110 373), 배수 발행/s 는 103.7(D-110 205)로 대략 절반이다.
  발행비 0.03 과 따라잡기 비율 0.017 은 D-110(0.03, 0.019)과 자릿수가 같아, 계획의 규칙대로 같은 세션 값끼리만 비교했다.

## 음성 대조와 정합성

- 세 run 모두 probe 단언을 통과했다(JUnit failures 0). 반복마다 `order.created` PUBLISHED 수가 주문 수와 같았고, PUBLISHED 가
  아닌 outbox 는 0 이었다. poller 를 끈 10초 부하 대조의 DB 발행은 0 이었다.
- 주문번호 충돌(D-120)은 세 run 모두 0건이었다.

## 한계

- 로컬 MySQL 은 Testcontainers 기본 `my.cnf` 다(D-110 §한계와 같다). 커밋 비용이 GKE 와 달라, GKE 에서도 같은 크기로 회복하는지는
  이 실험이 말하지 않는다. 점유 증가의 자리(J4)와 GKE 효과는 다음 과금 세션에서 잰다(ADR-0049 재검토 조건 1).
- 부하 모델은 닫힌 루프 64 스레드다. GKE 의 열린 루프(초당 170건)와 절대값을 비교하지 않는다.

## 원본 보존

- `{base,c}-pool*.xml.gz` 와 `*-gradle.log.gz` 는 세 run 의 JUnit XML 과 Gradle 로그다.
- XML 의 `Using generated security password:` 값만 `<redacted>` 로 바꿨다. 테스트 컨텍스트마다 무작위로 만든 일회용 값이다.
