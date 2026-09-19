# task-adr0018-compensation-refund-contract — 리뷰 audit

## 2026-08-14 23:47 — GP-2 (loop 1)

- 리뷰 run: `plan:20260814T144229Z:f83a6aa8-df4f-4060-b59a-94332129c76e:1` (exit 0)
- 항목: 9건 (P0:1, P1:5, P2:3) — **사용자 선택: [2] 전체 반영 (#6 은 부분)**
- raw: `.cache/codex-reviews/plan-task-adr0018-compensation-refund-contract-1786718550.json`

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | **P0** | "환불 API 호출 1회 보장"은 로컬 fence 로 달성 불가 — fence 커밋 후 PG 호출 전 사망(유실) / PG 성공 후 커밋 전 사망(중복) | **반영**. 보장 문구를 **"동일 논리 환불 1건"**으로 재정의하고 P3 에 **crash matrix 3칸**(호출 전 사망·성공 후 커밋 전 사망·타임아웃 결과 불명) 각각의 복구 규칙 + PG 멱등키/조회 reconciliation 결정을 필수화. §2.2-② 신설 |
| 2 | P1 | 감지 지점이 2곳이 아니라 **3곳** — `PaymentEventConsumer.handleOrderCancelled` 의 APPROVED 분기가 이미 환불 필요를 감지(Slack+로그) | **반영**. §2.1-a 를 3지점으로 정정하고 **§2.1-a2 영속성 비대칭**(Order=영속 원장 / Product=marker / Payment=비영속) 추가. P2 대안에 **③ Payment 로컬 시작**(Order 미발행) 추가 — 2-producer 전제 자체를 재평가 대상으로 |
| 3 | P1 | 감지 기록↔트리거 Outbox 원자성 계약 부재 + **기존 `compensated_at`/`OPEN` 행 backfill 부재**(배포 전 생성분은 영구 미트리거) | **반영**. P2 에 동일 트랜잭션 불변식 + backfill 계약 추가, 검증표에 "기존 행에서도 트리거 1건, 재실행 시 추가 0건" |
| 4 | P1 | `compensated_at` 을 종결 표면처럼 다뤘으나 실제로는 **환불 전에 찍히는 감지 marker** — 요청됨/해결됨 구분 불가 | **반영**. P5 를 "세 감지 기록의 의미를 먼저 정정한 뒤 종결 주체 결정"으로 재작성 |
| 5 | P1 | 이벤트 계약 범위가 ADR-0012 D2/D5 보다 좁음 — schemaVersion·DLQ·retention·프로비저닝 owner·cross-topic 순서 누락 | **반영**. P2 를 전 항목 열거형으로 확장 + §2.3 에 cross-topic 순서 무보장 제약 추가 |
| 6 | P1 | 산출물 범위 초과(ADR 1개인데 README·ADR-0012·구현 계획서까지) | **부분 반영**. README 인덱스·ADR-0012 Status 는 `docs/adr/README.md` 원칙이 ADR 신규 작성 시 **의무로 규정한 절차**라 유지. **구현 계획서 동기화(구 P10)만 ④-c-1 착수 시로 이연** |
| 7 | P2 | §2.2-③ 부분 환불이 어느 항목에도 미대응 | **반영**. P4 에 "주문당 전액 1건, 부분·복수 환불 범위 밖" 불변식 + 금액 결정 주체(Payment) |
| 8 | P2 | P7 검증 기준 부재 + parent P11 이 요구한 **관측 계약** 미결정 (Slack 은 관측성 대체물 아님) | **반영**. P7 에 카운터 4종(요청/성공/실패/소진) + **미해결 backlog 건수·최장 age 게이지**를 ADR-0015 규약으로 확정, 검증표 행 신설 |
| 9 | P2 | P4 대안 비교에 비교축 없음 + `payment_cancellations` 는 승인 전 marker 라 선례 오원용 | **반영**. 비교축 7개 명시(상태 격리·시도 이력·재시도/오류 저장·PG 응답 감사·cardinality·@Version↔유니크 fence·APPROVED 호환성) + 선례 원용 금지 문구 |

### 검증 메모 (GP-2)

**#2 는 내 코드 검증 표가 틀린 것**이었다 — `payment-service/.../PaymentEventConsumer.java:106` 의 `cancelBeforePayment()` 반환 true 분기를 직접 확인해 3번째 감지 지점임을 확정했다. 이 오류는 단순 누락이 아니라 **대안 지형을 바꾼다**: Payment 가 이미 `order.cancelled` 를 소비하며 과금-후-취소를 감지하고 있으므로, "Order 가 트리거를 발행한다"는 초안 전제 없이도 환불을 시작할 수 있는 경로가 존재한다(P2 대안 ③).

**#1(P0) 은 ④-a 의 P0 와 같은 종류다** — 그때는 "진입 시 검사는 fence 가 아니다"(B12), 이번은 "로컬 fence 는 외부 호출 경계를 못 덮는다". 계획 단계에서 **달성 불가능한 통과 조건**을 적으면 구현이 그것을 충족했다고 착각하게 된다.

---

## 2026-08-15 00:15 — GW-2 (loop 1) — ADR-0018 본문

- 리뷰 run: `work:20260814T151051Z:f83a6aa8-df4f-4060-b59a-94332129c76e:1` (single, 394줄 / 4파일, exit 0)
- 항목: 9건 (P0:0, P1:7, P2:2) — **사용자 선택: [2] 전체 반영**
- diff: `.cache/diffs/diff-task-adr0018-compensation-refund-contract-1786720212.patch`
- raw: `.cache/codex-reviews/diff-task-adr0018-compensation-refund-contract-1786720252.json`

**중점 검증 통과분**: C1 감지 3지점·영속성 분류 / C2 Toss 취소 API 부재·APPROVED terminal / 기존 7토픽 단일 producer / immutable·README 규약 — 전부 실제 코드와 일치 확인됨.

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P1 | `payment.refunded` consumer 에 Notification 누락 + payload 에 `userId` 없음. `NotificationConsumer` 는 payload `userId` 를 직접 읽고 orderId→user 조회 계약이 없어 **D6 사용자 알림이 구현 불가** | **반영**. D1 표에 Notification + group 추가, payload 에 `userId` 필수(Payment 가 `payments.user_id` 보유) |
| 2 | P1 | consumer group 이 `.requested` 를 축약 — ADR 이 주장하는 `{svc}-svc-{topic}-group` 과 불일치 | **반영**. 축약 없는 정규명으로 교체 + 기존 `stock-result` 축약은 **예외이며 신규에 적용 안 함**을 명문화 |
| 3 | P1 | backfill 재실행 멱등을 `payment_refunds.order_id` 유니크로 주장 — **DB-per-service 경계상 성립 불가**(Payment 유니크가 Product/Order 의 Outbox 재생성을 못 막음). 같은 문장이 "중복 발행이 있어도"로 전제를 뒤집음 | **반영**. producer DB 안의 `WHERE NOT EXISTS` 기준으로 재정의하고, **두 보장의 층위 분리**(발행 멱등 = producer DB / 실행 1건 = Payment fence) 명시 |
| 4 | P1 | `UNRESOLVED` 에서 나가는 전이가 없는데 reconciliation 이 해소한다고 서술 — 상태머신 자기모순. 무기한 미결 허용은 ADR-0012 D3 ④ 완화라 refine 판정과도 충돌 | **반영**. `UNRESOLVED → SUCCEEDED/FAILED` 전이 추가 + reconciliation 주체(payment-service·5분)·**조회 상한 24h**·수동 종결(감사 필드) 확정 → 미결 무기한 잔존이 계약상 없음 |
| 5 | P1 | 세 진입점이 `REQUESTED` 만 만들고 **PG 호출 주체가 없음**. 로컬 경로가 `@Transactional` 안에서 호출하면 crash matrix 전제가 깨지고 **PG 성공 후 롤백 시 유니크 행 소멸 → 재호출** 반례 성립 | **반영**. **dispatcher 신설** — 진입점은 `REQUESTED` 커밋만, dispatcher 가 `REQUESTED→CLAIMED` CAS 로 claim 한 뒤 유일하게 PG 호출. `CLAIMED` 상태·`claimed_at` lease 추가, crash matrix 에 (d) 칸 추가 |
| 6 | P1 | 환불이 `FAILED` 여도 Order 원장이 `RESOLVED`(해결됨)로 닫힘 — 원장이 거짓이 됨 | **반영**. 결과별 종착 표 신설 — `SUCCEEDED`=`RESOLVED` / `FAILED`=`REFUND_FAILED`(미해결 종착) / `UNRESOLVED`=전이 안 함 |
| 7 | P1 | `ALREADY_CANCELED` 즉시 영구 실패 처리가 D3 "조회로 진실 확정" 원칙과 충돌 — 이미 전액 취소된 건을 `APPROVED` 로 남기고 실패 이벤트 발행 | **반영**. 조회 후 분기(전액 취소=SUCCEEDED / 금액 불일치=FAILED / 조회 불가=UNRESOLVED) |
| 8 | P2 | 계획서 P5 가 요구한 **D4 종결방식 대안 3개 비교**가 Alternatives 에 없음 | **반영**. `Alternative E` 신설 — 전달 보장·원장 의미·장애 복구·운영 개입·표면 비용 5축 비교 |
| 9 | P2 | 신규 소비 경로의 Kafka retry/backoff/DLQ 전환 조건 미결정. D5 의 3회는 PG 재시도이지 Kafka 재시도가 아님 | **반영**. 기존 `FixedSequenceBackOff(1s,5s,30s)` 재사용 확정 + **두 재시도가 다른 층위**임을 D1·D5 양쪽에 명시 |

### 검증 메모 (GW-2)

**#5 가 가장 크다** — 초안은 "요청 consumer 중 insert 성공자가 PG 를 호출한다"고 암묵 전제했는데, 그러면 소비 트랜잭션이 외부 호출을 품게 되고 **PG 성공 후 롤백 시 fence 행이 사라져 fence 자체가 무효**가 된다. 진입점과 실행자를 분리(dispatcher)하니 crash matrix 의 (d) 칸이 "위험 아님"으로 바뀌었다 — 계약이 단순해진 게 아니라 **위험 구간이 줄었다**.

**#3 은 내 논증 오류다**(④-b #2, ④-a P0 에 이은 같은 패턴). 서로 다른 DB 의 제약을 하나의 보장처럼 서술했다 — DB-per-service 에서는 **어느 DB 의 제약인지**가 보장 범위를 규정한다.

**#4·#6 은 "종결"의 정의를 흐린 지점**이었다. 실패한 환불을 `RESOLVED` 로 닫고, 미해결을 나갈 길 없는 상태로 두는 것은 둘 다 원장을 신뢰할 수 없게 만든다.

---

## 2026-08-15 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/86)

- **TASKS.md**: 구현 ④ 행에 ④-c 착수 전 검증 결과(감지 3지점·Toss 취소 API 부재) + ADR-0018 ✅ + ④-c-1/④-c-2 재분할 기록. Task 상태 `🔄` 유지
- **PHASE4.md**: ADR 선행 판단 근거(④-a lease refine 과의 차이)·dispatcher 분리·보장 문구 재정의·결과별 종결·리뷰가 잡은 내 오류 2건·미충족 3건
- **ADR**: **ADR-0018 신규(Accepted)**. ADR-0012 는 refine 판정으로 **Status 무변경**
- **Layer 1**: 미갱신 — 코드가 없으므로 ④-c-1 이후
- 커밋 2개(ADR+인덱스 / 계획서+audit), 브랜치 `docs/adr0018-compensation-refund-contract`
