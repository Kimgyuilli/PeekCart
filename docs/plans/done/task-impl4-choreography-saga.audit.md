# task-impl4-choreography-saga — 리뷰 audit

## 2026-08-13 — GP-2 (loop 1)

- 리뷰 항목: 12건 (P0:2, P1:8, P2:2)
- 사용자 선택: [2] 전체 반영 — 단 #3 은 ④ 범위 밖 신규 부채(D-020)로 등재
- P0 무시 사유: 없음 (P0 2건 전량 반영)
- raw: `.cache/codex-reviews/plan-task-impl4-choreography-saga-1786552384.json`
- run_id: `plan:20260812T163304Z:c2036491-547e-4f3f-b005-365144d529e2:1`

### 반영 내역

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P0 | PAID_BUT_UNRESERVED 를 완료 보상으로 오판 — 실제는 marker+Slack, 자동 환불 없음(ADR-0012 D3 ④ 미이행) | §2.1 정정 + **P8 환불 요청 경로** 신설 |
| 2 | P0 | `reservedAt+30분` sweeper 는 oversell 유발 — 예약 확정 후 결제 미시작 `PENDING` 은 수명 상한 없음 | §2.3-A 재작성 + **P3**(상한 부재 해소)·**P4**(lease 계약) 로 분리, 고정 TTL 폐기 |
| 3 | P1 | Toss 승인 ↔ 로컬 커밋 불일치 (과금 잔존·롤백) | **범위 밖 판정** — §2.4 에 근거 명시 + **D-020** 신규 부채 등재(P15) |
| 4 | P1 | P4 "items 추가 안 함"이 ADR-0012 D2(items[] 추가 명문)와 충돌, ADR-0016 이 D2 유효 유지 | **P5 로 계약대로 `items[]` 추가**. 새 ADR 우회 폐기 |
| 5 | P1 | `payment.failed` 취소가 `order.cancelled` 미발행 → reason 4종 중 PAYMENT_FAILURE 발행 경로 부재 | §2.2-4 신설 + **P7**(발행 여부 선결정) · **P6**(진입점별 enum 전달) |
| 6 | P1 | "DLQ 를 아무도 보지 않는다" 부정확 — 라우팅 시점 log+Slack 기존재, 발행 서비스 4곳, 테스트 listener 1건 | §2.2-1 정정("재소비/영구 원장 0건"), 영향 파일에 notification 포함 |
| 7 | P1 | 공유 DLQ 토폴로지에서 failed consumer group 식별 불가 → 중복 기록·알림, 재-DLQ 재귀 위험 | §2.3-C 신설 + **P9** 식별자 `originalTopic+eventId+failedConsumerGroup` · 전용 factory |
| 8 | P1 | 수동 수렴 계약 공백 — runbook 이 P8(구) 문서 목록에 없음, retention 7일 창 규칙 부재 | **P10** runbook 신설(상태머신·재발행 규칙·SLA), §4 에 경로 고정 |
| 9 | P1 | "이벤트 계약 수준 검증으로 대체 가능"이 Exit Criteria 미충족 | **P12** 로 cross-service E2E 필수화, 대체 문구 삭제 |
| 10 | P1 | `@Version` 만으로 "결제 완료 우선" 미성립, 확률적 재현 음성으로 기각 불가 | §2.3-D/E 신설 + **P1** barrier/latch 결정적 재현 · **P2** 충돌 정책·양 순서 수렴 |
| 11 | P2 | DLQ 원장 마이그레이션 누락, `stock_reservations` (status,reserved_at) 인덱스 부재 | **P13** 신설(스키마·인덱스·배치 상한) |
| 12 | P2 | §5 "금지"가 prose 라 강제 불가 | **P14** saga-contract 게이트(매트릭스 + CI 실패 조건) |

---

## 2026-08-13 — GW-2 (loop 1, ④-a diff)

- 리뷰 run: `work:...:1` **timeout(exit 124)** → GW-2b degraded(risk=high) → 사용자 선택 [재시도·예산 확대] → `work:...:2` ok
- 항목: 5건 (P0:1, P1:3, P2:1) · **5건 전량 반영**
- diff: `.cache/diffs/diff-task-impl4-choreography-saga-1786556518.patch` (1,701줄, single 모드)
- raw: `.cache/codex-reviews/diff-task-impl4-choreography-saga-1786604776.json`

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P0 | `ensureConfirmable` 은 일회성 검사일 뿐 fence 가 아니다 — 검사 통과 후 PG 호출 중 Order 만료취소→release→재판매→승인성공 이 성립 | **부분 반영 + 잔여 명시**. PG 전 "남은 lease > 마진(2m)" 요구(`PaymentApprovalProperties`, PAY-010)로 창 축소. 진짜 fence(`PAYMENT_IN_PROGRESS` CAS)는 ADR-0012 D3 재기록 필요 → 별도 PR. 계획 **§2.6 R-1** 로 미달성 명시 |
| 2 | P1 | CANCELLED 주문의 payment.completed 를 Slack+return 처리 — order-service SlackPort 는 no-op, processed_events 는 커밋돼 P8 재소비 불가 | **영속 원장 신설**. `order_compensations`(V4, `(order_id, reason)` 유니크) + 소비와 동일 트랜잭션 기록. 배포 의존성(④-a→④-c)은 **§2.6 R-2** |
| 3 | P1 | 마이그레이션이 기존 행을 NULL lease 로 남겨 legacy saga 는 수명 상한 미획득 (자체 발견과 동일 건, 범위 더 넓음 — NULL lease 결제는 만료검사 없이 승인) | **3개 마이그레이션에 backfill 추가**(orders/stock_reservations/payments, 동일 기준 +30m). 롤아웃 창 잔여는 **§2.6 R-3** |
| 4 | P1 | lease 테스트가 경합 순서를 주입하지 않아 #1 경로에서도 통과 | 마진 경계 3종(`LeaseApprovalMargin`) + 실 DB sweeper 경계 4종(`StockReservationLeaseSweepIntegrationTest`) 추가. **크로스서비스 순서 주입(승인↔취소↔sweeper)은 ④-d E2E 로 명시 이관** — 단일 모듈에서 재현 불가 |
| 5 | P2 | 낙관 락 테스트가 consumer 재시도·보상 분기를 실행하지 않음 | consumer 경로 통합테스트 추가(`OrderPaidButCancelledIntegrationTest`: 예외 없음·원장 OPEN·재소비 멱등) + 스케줄러 충돌 포기 정책 단위테스트 |

### 검증 메모 (GW-2)

반영 전 P0 을 직접 반증했다 — `confirmPayment` 가 `ensureConfirmable` 이후 **같은 트랜잭션**에서 Toss 를 호출하고, 그 시점 주문은 `payment.requested` 가 outbox→poller→Kafka 를 거치기 전이라 여전히 `PENDING` 이다. **지적이 정확하고 초안이 틀렸다.** #2 의 "SlackPort no-op" 도 PR3b 게이팅 결정과 대조해 확인했다.

**중요**: ④-a 는 계획 §1 명제를 이 경로에 대해 **달성하지 못한다**. 완화만 하고 잔여를 문서화한 것이며, 이를 "닫았다"로 기록하지 않는다.

### 검증 메모

반영 전 4건을 직접 재확인(#4 ADR-0012 D2 원문 · #5 `OrderEventConsumer#handlePaymentFailed` · #2 `OrderJpaRepository` 2쿼리 · #6 `kafkaErrorHandler` Slack + notification DLQ) — **전부 Codex 가 정확하고 초안이 틀렸다**. 분할 아티팩트 0건(single 파일 리뷰).

작업 항목 P8 → P15 로 증가(8 → 15).

---

## 2026-08-13 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/84)

- **TASKS.md**: 구현 ④ 상태 `🔲` → `🔄` (④-a 완료, b/c/d 대기) + ④-a 요약·미충족 명시 · **L-013 보류 → 해소**(실측 근거 기록) · **D-020 신규 등재**(결제 승인↔로컬 커밋 불일치, ④ 범위 밖)
- **PHASE4.md**: ④-a 작업 이력 추가 — 범위 재확정 근거 / lease 전환 결정 / 알림≠종료상태 / P0 가 뒤집은 결론(fence 부재) / R-1~R-4 미충족
- **ADR**: 신규 없음. ADR-0012 상태 변경 없음(D2/D3 위임 범위 내 refine). **R-1 fence 는 ADR-0012 D3 재기록이 필요하므로 착수 시 새 ADR 선행**
- **Layer 1**: 미갱신 — 계획 P15(④-d) 소관으로 이연(05 신규 컬럼·04 saga 흐름)
- 커밋 9개(p1~p8 + docs), 브랜치 `feat/impl4-a-reservation-lease`

---

## 2026-08-14 14:56 — GW-2 (loop 1) — ④-b 이벤트 계약

- 리뷰 run: `work:20260814T114621Z:7f13f5ef-e868-4a79-81f0-03bb78f51440:1` (single, 686줄 / 12파일, exit 0)
- 항목: 2건 (P0:0, P1:0, P2:2) — **자동 통과 조건이었으나 2건 전량 반영**
- diff: `.cache/diffs/diff-task-impl4-choreography-saga-1786707325.patch`
- raw: `.cache/codex-reviews/diff-task-impl4-choreography-saga-1786708013.json`

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P2 | `handlePaymentFailed` 가 `order.cancel()` 을 무조건 호출 — 사용자/타임아웃/예약실패 취소가 선커밋됐으면 `ORD-002` 로 트랜잭션이 롤백돼 DLQ 행. 같은 클래스의 `reserved=false` 경로는 이미 CANCELLED 를 no-op 처리하는데 규약이 어긋난다 | **반영**. `CANCELLED` 면 정상 처리로 종료(no-op) + 단위테스트 추가. P7 로 발행이 붙으면서 이 경합의 대가가 "중복 발행"이 아니라 "DLQ"로 커지므로 형제 경로와 규약을 맞췄다 |
| 2 | P2 | P7 의 "동일 트랜잭션 Outbox" 요구(계획 §5.2)가 실제로는 미검증 — `@InjectMocks` 단위테스트는 Spring 프록시도 DB 도 없어 `@Transactional` 을 떼도 통과하는 false-green | **반영**. `OrderCancelledEventContractIntegrationTest` 신설 — 성공 시 `CANCELLED`+Outbox 1행 동시 커밋·payload `items[]`/`reason` 계약 확인, `@MockitoSpyBean` 으로 Outbox 저장 실패를 주입해 주문 상태·`processed_events` 까지 전량 롤백 확인 |

### 검증 메모 (GW-2)

두 건 다 **지적이 정확했다**. #1 은 기존 코드의 무조건 `cancel()` 이 남긴 경합이고(P7 이전에도 존재), #2 는 내가 추가한 단위테스트가 계획서가 요구한 검증을 실제로 수행하지 못한다는 지적이다 — ④-a 의 "내 검증도구 false-green" 패턴이 또 나왔다(누적 4회째: PR3d-a 3건, b-1 3건, ④-a, ④-b).

### P7 결정 기록

`payment.failed` 취소도 `order.cancelled` 를 **발행**한다(ADR-0010 D3-4 복원). 무해성 근거 3가지를 코드로 확인: Product 는 예약 원장 `RESERVED→RELEASED` CAS 라 두 번째 release 가 no-op · Payment 는 자기 상태가 `FAILED` 라 `cancelBeforePayment()` 가 no-op(APPROVED 오탐 없음) · Notification 은 `reason=PAYMENT_FAILED` 를 스킵해 중복 알림 차단. **중복 부작용을 차단하는 장치가 P6 의 `reason` 이므로 P6 없이 P7 을 먼저 넣으면 성립하지 않는다.**

---

## 2026-08-14 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/85)

- **TASKS.md**: 구현 ④ 행에 ④-b 완료 요약 추가(P5·P6·P7·리뷰 2건·617 테스트), ④-c/d 대기 유지. Task 상태는 `🔄` 유지
- **PHASE4.md**: ④-b 이력 추가 — P7 결정 근거(기능 필요가 아니라 계약 단순성)·P6→P7 의존 순서·하위호환 방향(침묵보다 알림)·GW-2 #2 가 반증한 false-green·미충족 3건
- **ADR**: 신규 없음, 상태 변경 없음. P5/P6 은 ADR-0012 D2 이행, P7 은 ADR-0010 D3-4 복원이라 둘 다 기존 결정의 범위 내
- **Layer 1**: 미갱신 — P15(④-d) 이연(`02`/`03`/`04` payload 표기 드리프트를 미충족 1로 명시)
- 커밋 5개(p1~p5), 브랜치 `feat/impl4-b-cancel-event-contract`
