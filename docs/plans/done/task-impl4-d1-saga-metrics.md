# ④-d-1 — saga 관측성 (메트릭 · alert)

> 부모 계획: `docs/plans/done/task-impl4-choreography-saga.md` **P11**
> 형제: **④-d-2** (P12 E2E · P14 게이트 · P15 종결) — `task-impl4-d2-saga-e2e-gate.md`
> 선행: ④-a~④-c-1b(머지) · **④-c-2a([#90], 리뷰 중 — P3 의존)**
> 리뷰 이력: `task-impl4-d1-saga-metrics.audit.md` (d-1/d-2 공통)
> **이 PR 은 ④ 를 종결하지 않는다.** 종결은 ④-d-2 소관이다.

---

## 0. 분할 근거

계획 리뷰 2라운드 9건의 영역 분포가 갈렸다 — **E2E 하네스·시나리오 5건 · 매트릭스 게이트 3건 · 메트릭/alert 1건.** 그리고 P11 은 E2E 인프라에 의존하지 않는다.

④-d-2 가 흡수한 선결 과제(전부 E2E 쪽): `RefundDispatcher` 비활성화 프로퍼티(CI 가 실제 Toss 를 호출하는 문제) · E2E 전용 compose 격리 · 예약 실패 체인 시나리오 · 증적 프로토콜 3분기.

---

## 1. 명제

**saga 의 실패·보상·타임아웃·DLQ 유입이 로그가 아니라 메트릭 표면에서 보이고, 미결이 쌓이는 표면은 alert 가 잡는다.**

부정형 — 아래가 하나라도 성립하면 미완이다.

1. saga 실패·보상·타임아웃·DLQ 유입 중 **하나라도 메트릭 표면에 없다** (로그를 읽어야만 알 수 있다)
2. 타임아웃 취소 3종이 **한 값으로 뭉쳐** 어느 잡이 도는지 구분되지 않는다
3. 미결 **잔량**을 알 수 없다 (누적 Counter 만 있어 alert 를 만들 데이터가 없다)
4. 신규 alert 가 **라벨을 빼거나 삭제돼도 lint 가 통과한다**
5. 메트릭·alert 를 추가했는데 기존 observability 계약(ADR-0015)과 어긋난다

---

## 2. 배경 — 착수 전 코드 검증

### 2.1 부모 P11 경로 중 환불 요청 계열만 구현돼 있다

> **초안 정정**: 초안 제목은 "saga 메트릭은 실제로 0건" 이었다. **틀렸다** — 본문에서 환불 5종을 인정하면서 제목이 0건이라 자기모순이었다. 경로 기준으로 다시 쓴다.

**현재 등록된 메트릭 전수** (`Counter/Timer/Gauge.builder` grep):

| 메트릭 | 타입 | 서비스 | 등록 위치 |
|---|---|---|---|
| `outbox.backlog{status}` | Gauge ×2 (pending·failed) | order · product · payment | `OutboxPollingService#registerBacklogGauge` |
| `outbox.publish{result}` | Timer ×2 (success·failure) | order · product · payment | `OutboxPollingService:53-60` |
| `payment.refund.requested` | Counter | payment | `PaymentRefundService:81` |
| `payment.refund.result{...}` | Counter | payment | `PaymentRefundService:243` |
| `payment.refund.retry.exhausted` | Counter | payment | `PaymentRefundService:231` |
| `payment.refund.backlog` · `payment.refund.oldest.age` | Gauge | payment | `RefundBacklogMetrics:29-38` |

notification 은 메트릭 0건이다.

**부모 P11 경로 대비**:

| 부모 P11 경로 | 상태 | 계측 지점 (실측) |
|---|---|---|
| 환불 요청 | ✅ 구현됨 (④-c-1a) | — |
| 예약 성공/실패 | ❌ | `StockReservationService#reserve` (product) |
| 예약 확정 | ❌ | `StockReservationService#confirm` |
| 재고 복구 | ❌ | `StockReservationService#release` |
| lease sweeper 회수 | ❌ | `StockReservationService#sweepExpiredLeases` |
| 보상 감지 | ❌ | product: `#compensatePaidButUnreserved` · order: **`OrderEventConsumer` 의 보상 감지·save 분기**(`:227-238`) |
| 타임아웃 취소 | ❌ | `OrderTimeoutScheduler#cancelExpiredOrders` / `#cancelUnconfirmedReservations` / `#cancelExpiredReservationLeases` |
| DLQ 유입 | ❌ | `DeadLetterRecorder#record` (4서비스, ④-c-2a) |

> **초안 정정 2**: 초안은 order 보상 계측 지점을 `OrderCompensation` 원장으로 적었다. 그건 엔티티/테이블이지 실행 분기가 아니다.

### 2.2 Counter 만으로는 alert 를 못 만든다

"보상 원장 미해소" 를 알려면 현재 `OPEN`/`REFUND_FAILED` **잔량**이 필요한데, 적재 Counter 는 누적값이라 잔량을 표현하지 못한다. `payment.refund.backlog` 가 이미 같은 이유로 Gauge 다(④-c-1a 선례).
→ `saga.compensation.backlog{status}` Gauge 를 함께 둔다.

### 2.3 관측성 lint 의 현재 적용 범위 (2R #4)

`observability-promql-lint.sh` 는 **모든 PromQL 을 syntax 검사**하지만(`:156-233`), **라벨 invariant 는 기존 UID 4종에만** 적용한다 — `high-error-rate` · `slow-response` · `target-down` · `scrape-absent-*`. required UID 집합도 그 4종뿐이다(`:234-244`).

→ **신규 alert 를 추가하고 lint 를 그대로 두면, 그 alert 에서 `application` 필터·`by(application)` 을 빼거나 alert 자체를 삭제해도 통과한다.** §1 부정형 4번이 그대로 성립한다.
→ **lint 확장이 본 PR 범위다**(P5). 신규 UID 를 required 집합에 넣고 각각의 라벨 계약을 검사하며, 삭제·라벨 제거·서비스 집합 축소 self-test 를 둔다.

### 2.4 제약 / 트레이드오프

**A. alert 는 최소만 추가한다.** 메트릭을 늘린다고 alert 를 그만큼 만들지 않는다 — 울리지 않는 alert 는 소음이고, lint 의 required 집합을 그만큼 넓힌다. **미결이 쌓이는 표면만** 대상이다: `saga.compensation.backlog` · `dlq.backlog`. 나머지는 조회·대시보드용 메트릭으로 둔다.

**B. alert 발화는 검증하지 않는다 (1R #11).**
`ADR-0015:72-74` 가 정적 lint 범위를 PromQL syntax·라벨 invariant 까지로 규정했고, Grafana `__expr__`·실제 series·발화 동작은 범위 밖이라고 명시한다. 실제 평가를 보려면 Grafana/Prometheus 기동 + fixture series + 평가 API 가 필요하다 — 본 PR 범위를 넘는다.
→ 성공 기준은 **정적 lint 그린까지**로 두고, **"발화 미검증" 을 §6 에 명시**한다. 초안은 이걸 성공 기준에 올려놨는데 수행 수단이 없었다.

**C. DLQ 메트릭은 ④-c-2a 의존.** `dlq.backlog`·`dlq.oldest.age` 는 [#90] 의 `DeadLetterRecordJpaRepository#countUnresolved`/`findOldestUnresolvedOccurredAt` 를 쓴다. **머지 순서: #90 → 본 PR.** 미머지 상태로 착수하면 P3·P4(dlq alert)만 마지막에 붙인다.

**D. 태그 카디널리티를 통제한다.** `dlq.intake{topic,group}` 처럼 토픽·group 을 태그로 달면 시계열이 (토픽 × group) 로 늘어난다. 본 PR 은 **잔량 Gauge 2종만** 두고 토픽별 분해는 두지 않는다 — 필요해지면 그때 근거와 함께 추가한다.

### 2.5 범위 밖

- **P12 E2E · P14 게이트 · P15 문서 종결 · ④ 종결 선언** — ④-d-2
- **alert 실제 발화 검증** — §2.4-B
- **DLQ replay** — ④-c-2b (ADR 선행)
- **Grafana 대시보드 패널 추가** — 본 PR 은 메트릭·alert 계약까지. 대시보드는 후속

---

## 3. 작업 항목

- [x] **P1.** **saga 메트릭 — product** — `saga.reservation.result{outcome}`(성공/실패) · `saga.reservation.confirmed` · `saga.reservation.released` · `saga.reservation.sweeper.reclaimed` · `saga.compensation.detected`. 계측 지점은 §2.1 표. 태그는 ADR-0015 per-service 규약.
- [x] **P2.** **saga 메트릭 — order** — `saga.order.timeout.cancel{reason}` **3종을 사유별로 구분**(합치면 §1 부정형 2) · `saga.compensation.detected`(`OrderEventConsumer:227-238`) · **`saga.compensation.backlog{status}` Gauge**(`OPEN`/`REFUND_FAILED` 잔량, §2.2).
- [x] **P3.** **DLQ 유입 메트릭** — `dlq.backlog` · `dlq.oldest.age` Gauge 4서비스. ④-c-2a 의 `actuator/deadletter` 와 **같은 쿼리**를 쓴다(두 표면이 갈라지면 어느 쪽이 맞는지 알 수 없다). 조회 표면은 유지.
- [x] **P4.** **alert 2종 추가** — `saga.compensation.backlog` · `dlq.backlog`. per-service 라벨 규약(`application` 필터 + `by(application)`) 준수.
- [x] **P5.** **`observability-promql-lint` 확장** (§2.3) — 신규 alert UID 를 required 집합에 추가하고, 각각 허용 application 집합·`by(application)`·metric/상태 필터를 검사. **self-test 추가**: alert 삭제 · `application` 라벨 제거 · 서비스 집합 축소 각각 non-zero 종료.
- [x] **P6.** **검증** — §5 전부 그린 + 10모듈 테스트 0 실패 + lint 전종 그린.

---

## 4. 메트릭 계약 (P1~P3 의 정본)

| 메트릭 | 타입 | 서비스 | 태그 | 증가/변동 조건 |
|---|---|---|---|---|
| `saga.reservation.result` | Counter | product | `outcome=success\|failure` | `reserve` 성공/실패 각 1회 |
| `saga.reservation.confirmed` | Counter | product | — | `confirm` 이 실제 전이했을 때만 |
| `saga.reservation.released` | Counter | product | — | `release` 가 CAS 승자일 때만 (double-release 는 증가 0) |
| `saga.reservation.sweeper.reclaimed` | Counter | product | — | 회수 **건수만큼** (실행당 1 아님) |
| `saga.compensation.detected` | Counter | product · order | — | marker CAS / 원장 적재가 **실제로 새 행을 만들었을 때만** |
| `saga.order.timeout.cancel` | Counter | order | `reason=expired_payment\|unconfirmed_reservation\|expired_lease` | 잡별로 취소한 **건수만큼** |
| `saga.compensation.backlog` | Gauge | order | `status=open\|refund_failed` | 원장 잔량 |
| `dlq.backlog` | Gauge | order·product·payment·notification | — | `countUnresolved()` |
| `dlq.oldest.age` | Gauge | 위 4서비스 | — | 가장 오래된 미결의 경과 초 |

**"실제로 전이/적재했을 때만" 이 반복되는 이유**: 멱등 경로(CAS no-op · `INSERT IGNORE` 중복)에서도 증가하면 메트릭이 실제 사건 수를 부풀린다. 그러면 alert 임계값이 무의미해진다.

---

## 5. 검증 방법

부모 §5 의 **공통 금지** 승계 — "메트릭이 노출된다" 를 수렴 검증으로 기록하지 않는다. **경로를 실제로 실행한 뒤 값으로** 확인한다.

| 항목 | 성공 기준 |
|---|---|
| P1 | 각 카운터가 해당 경로 실행 시 증가. **멱등 no-op 에서는 증가 0**(double-release · CAS 패자 · 중복 marker) — 음성 대조 |
| P1 sweeper | 회수 3건이면 `+3` (실행당 1 이 아님). 회수 0건이면 증가 0 |
| P2 | 타임아웃 3종이 `reason` 으로 **구분**됨. 한 잡만 돌렸을 때 다른 `reason` 은 증가 0 |
| P2 backlog | 원장에 `OPEN` 2건·`REFUND_FAILED` 1건일 때 Gauge 가 각각 2·1. 종결하면 감소 |
| P3 | DLQ 1건 유입 후 `dlq.backlog`=1, `DISCARDED` 후 0. **`actuator/deadletter` 의 값과 일치**(같은 쿼리를 쓰는지 확인) |
| P4 | alert 가 `/api/v1/provisioning` 렌더에 존재하고 PromQL syntax 통과 |
| P5 | self-test 3종이 각각 non-zero: **alert 삭제** · `application` 라벨 제거 · 서비스 집합 축소. 기존 lint 4종 UID 검사도 그대로 그린 |
| P6 | 10모듈 테스트 0 실패 · lint 전종 그린 |

**false-green 방어**: 메트릭 테스트는 "등록됐다"(`meterRegistry.find(...)` non-null)로 통과시키지 않는다 — **경로 실행 전후 값 차이**를 단언한다. 등록만 확인하면 계측 지점이 틀려도 통과한다.

---

## 6. 완료 조건

- §1 부정형 5개가 전부 불성립임을 §5 각 행으로 증명
- P1~P6 그린 + 음성 대조 포함
- §4 메트릭 계약 전 행이 테스트로 고정됨
- **미충족 명시**: alert **발화** 미검증(§2.4-B) · ④ 종결은 ④-d-2 소관 · DLQ replay 는 ④-c-2b

---

## 7. 미해결 / 후속

| # | 항목 | 처분 |
|---|---|---|
| 1 | P12 E2E · P14 게이트 · P15 종결 | ④-d-2 |
| 2 | alert 실제 발화 검증 | Grafana/Prometheus 기동 + fixture 필요 — 관측성 후속 |
| 3 | 토픽별 DLQ 분해 메트릭 | 만들지 않음 — 카디널리티 (§2.4-D). 필요해지면 근거와 함께 |
| 4 | Grafana 대시보드 패널 | 후속 |
| 5 | DLQ replay · 브로커 retention | ④-c-2b (ADR 선행) |
| 6 | PG stub + Toss base URL 설정화 | D-020 과 묶는다 (④-d-2 §7 에도 등재) |

---

## 종결

머지 PR: [#91](https://github.com/Kimgyuilli/PeakCart/pull/91) — 구현 ④-d-1 — saga 관측성

계획서 아카이브 판정을 위해 PR 링크를 명시한다. 작업은 위 PR 로 종결됐고 본문의
체크박스 상태는 당시 갱신되지 않은 것이라 완료 여부의 근거가 아니다.
