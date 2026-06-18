## 2026-06-18 10:20 — GP-1 (ADR precheck)
- 신호: 구조 변경(seam 제거) + 신규 토픽 `payment.requested`(ADR-0012 §D4 6토픽 SSOT 의 7번째) + ORD-008 게이트 재구성(§D3) → ADR-0012 SSOT 터치.
- 사용자 선택: [refine] ADR-0012 §D4/§D3 의 refine 으로 본 PR 안에서 처리(새 ADR 미작성). 선례: ADR-0012 §78 이 ADR-0010 토폴로지를 새 ADR 없이 refine.
- 반영: 계획서 P7 = ADR-0012 §D4 refine 작업항목.

## 2026-06-18 10:20 — GP-2 (loop 1)
- 사전 결정(사용자 게이트): peel 시퀀싱 = strangler 먼저(본 PR = OrderPort 동기 seam 제거), 두-모듈 peel+root 소멸은 후속 PR-B.
- 리뷰 항목: 6건 (P0:1, P1:3, P2:2)
- 사용자 선택: [전체 반영] (6건 모두 코드로 확인됨 — 타당)
  - #1(P0) "과금-후-취소 회귀 없음" 과장 = 비동기 창 미봉합. 코드 확인: `Order.markPaymentRequested()`(Order.java:120,123)가 (a)PENDING + (b)`reservationConfirmedAt!=null`(reserved=true) 둘 다를 결제 *시작 전* 동기 강제. 비동기화 시 둘 다 Toss 이후로 밀림. → §2 게이트 재배치 재작성(2조건 payment-로컬 복원), 잔여 lag race 는 ADR-0012 §D3 ④ 보상(환불+알림) 수렴, "회귀 0"→"수렴" 약화. §1/트레이드오프/P4 반영.
  - #2(P1) reserve→pay(ADR-0012 §D3) 보존 불명확. 코드 확인: payment confirm 경로에 reservation 체크 0(현재는 markPaymentRequested 동기 throw 가 강제). → P4 에 payment 가 `stock.reservation.result`(§D4 payment-svc-stock-result-group 기등재) 소비→로컬 readyForPayment, confirm 게이트. P8 에 reserved 전/후 케이스.
  - #3(P1) §D4 refine 불완전 — payment 가 order.cancelled 신규 소비인데 P7 은 payment.requested 행만. → P7 (b) order.cancelled 행에 Payment consumer + payment-svc-order-cancelled-group 추가.
  - #4(P1) payments.user_id backfill 추상적. → P1 구체화(nullable→orders 조인 backfill→null 0 검증→NOT NULL) + docs/05 반영.
  - #5(P2) B1 스윕 증거 부족. → B1 에 rg 명령 + 현재 결과(3 call sites+adapter+interface+test 2, 그 외 0) 명시.
  - #6(P2) 멱등/순서 테스트 협소. → P8 에 order.cancelled 중복·역전/지연 도착·DLQ replay 케이스.
- 검증: hpx_plan_lint OK (반영 후 재확인, P1~P8 연속)
- raw: .cache/codex-reviews/plan-task-impl-order-payment-decouple-1781778039.json
- run_id: plan:20260618T102007Z:f47777c9-efdf-4554-bc31-d08391892534:1

## 2026-06-18 10:32 — GP-2 (loop 2, 사용자 재리뷰 요청)
- 리뷰 항목: 4건 (P0:0, P1:3, P2:1) — 1차 반영이 만든 후속 결함(반복 아님)
- 사용자 선택: [전체 반영] (4건 모두 타당)
  - #1(P1) V10 한방 NOT NULL = 배포 순서 위험(구버전 consumer 는 Payment.create(orderId,amount), V1 payments user_id 부재). → P1 을 expand-contract V10(nullable+backfill+모니터)/V11(코드 배포 후 NOT NULL, lag 0·null 0 확인)로 분리. §4/§6 반영.
  - #2(P1) payment.requested 가 Order 의 stock.reservation.result 보다 선도착 가능(별도 group) → markPaymentRequested 가 reservationConfirmedAt null 에 ORD-008 throw(Order.java:123) → DLQ → 결제 시작됐는데 앵커 전이 유실. → P4 에 선도착 수렴(DLQ 아님, order 로컬 pending marker → stock result 도착 시 수렴), P8 케이스 추가.
  - #3(P1) 신규 CANCELLED 가 approve/fail 과 last-write-wins 경합(@Version/CAS 부재, Payment.java:23-47). → P4 에 상태머신 닫기(cancelBeforePayment PENDING/ready 전용, APPROVED/FAILED no-op+보상, @Version/CAS), APPROVED-후-order.cancelled 는 APPROVED 유지+§D3 ④ 보상. §2 트레이드오프·P8 반영.
  - #4(P2) D4 refine order.cancelled 행 ellipsis → 정확한 group 3종(product/payment/notification-svc-order-cancelled-group) 명기. P7 반영.
- 검증: hpx_plan_lint OK (반영 후, P1~P8 연속)
- raw: .cache/codex-reviews/plan-task-impl-order-payment-decouple-1781778796.json
- run_id: plan:20260618T103205Z:5215b2d4-8277-48cb-ad6b-a603b28c6612:2

## 2026-06-18 11:26 — GP-2 (loop 3, 사용자 재리뷰 요청 — attempt 3 상한)
- 리뷰 항목: 3건 (P0:0, P1:3, P2:0) — loop2 반영이 남긴 완결성 공백(반복 아님)
- 사용자 선택: [전체 반영 + 종료]
  - #1(P1) order pending marker(loop2 도입)가 영속 모델/마이그레이션으로 안 닫힘(Order 필드는 reservationConfirmedAt/paymentRequestedAt 뿐, Order.java:55-59). → P4 에 V12 orders 컬럼 + Order 필드/markPaymentRequestedPending + confirmReservation 수렴 + CANCELLED/reserved=false 정리, 영향파일에 Order.java+V12, P8 DB 영속 검증.
  - #2(P1) V11 NOT NULL PR 경계 충돌(P1 "동일/후속" 열림 ↔ 영향파일 V11 포함 ↔ 완료조건 V11 요구). → 본 PR = V10+코드+null 모니터링까지(single-deploy), V11 은 후속 PR/운영 게이트로 분리. P1·§4·§6 일관화.
  - #3(P1) 신규 topic/DLQ 생성 누락 — KafkaConfig 가 TopicBuilder 로 topic+DLQ 선언(KafkaConfig.java:33-58,65-90)인데 영향파일에 KafkaConfig 없음. → P3 에 payment.requested/.dlq NewTopic, 영향파일·§5 토픽생성/DLQ 회귀.
- 검증: hpx_plan_lint OK (반영 후, P1~P8 연속)
- raw: .cache/codex-reviews/plan-task-impl-order-payment-decouple-<ts3>.json
- run_id: plan:20260618T112656Z:a446c575-f476-42bc-9cdc-639bfdb4ad40:3
- 비고: attempt 3 = §7-6 권장 상한 도달. 추가 리뷰는 비용 확인 필요.

## 2026-06-18 12:06 — GW-2 (work loop 1)
- 리뷰 run: work:20260618T120610Z:fa9be076-7c76-4da1-9b71-3a8262110cc8:1 (single, diff 1271줄)
- 항목: 4건 (P0:0, P1:3, P2:1) — 사용자: [전체 반영 후 재리뷰]
  - #1(P1) order.cancelled 가 order.created 선도착 시 findByOrderId().ifPresent() no-op + idempotency 기록 → CANCELLED marker 유실(실버그). → handleOrderCancelled 를 orElseThrow(PAY_003) 재시도-throw 로 변경(stock.result 와 동형), PaymentEventConsumerTest 역전 케이스 추가.
  - #2(P1) APPROVED-후-취소 log.warn 만 → 보상 미연결. → SlackPort 주입, 운영 알림 발행(환불 트리거는 ④). 테스트로 알림 발행 검증.
  - #3(P1) V12 먼저 배포 + 후속 V11(NOT NULL) = Flyway out-of-order 충돌. → marker V12→V11 당김, 후속 NOT NULL=V12+, V10 주석 수정.
  - #4(P2) consumer 테스트 누락 → PaymentEventConsumerTest 신설(stock.result ready·order.cancelled cancel/approved-alert/missing-retry·order.created userId), OrderEventConsumerTest payment.requested 3케이스, PaymentOutboxEventPublisherTest publishPaymentRequested.
- 빌드: 1차 ./gradlew :build BUILD SUCCESSFUL(5m38s, 통합+가드 포함). 반영 후 단위 그린 + 재빌드 진행.
- diff: .cache/diffs/diff-task-impl-order-payment-decouple-1781784329.patch
- raw: .cache/codex-reviews/diff-task-impl-order-payment-decouple-1781784357.json

## 2026-06-18 14:36 — GW-2 (work loop 2, 사용자 재리뷰 요청)
- 리뷰 run: work:20260618T143612Z:fa9be076-7c76-4da1-9b71-3a8262110cc8:2 (single, diff 1541줄). 1차 4건 반영 정확성 확인됨(throw-retry/멱등·APPROVED 유지·V10→V11 순서).
- 항목: 2건 (P0:0, P1:1, P2:1)
  - #1(P1) order.cancelled 선도착 throw-retry 가 Kafka retry window 내에서만 수렴 — window 초과 시 DLQ → 이후 order.created+reserved=true 면 confirm 통과(silent charge). 사용자 선택: [영속 cancellation marker 추가].
    → V12 payment_cancellations 테이블 + PaymentCancellation 엔티티/리포(3파일) + handleOrderCancelled(미존재 시 marker 영속) + handleOrderCreated(생성 직후 marker 있으면 CANCELLED 적용+삭제). PaymentEventConsumerTest 에 marker 영속/적용 케이스. → DLQ 초과에도 누수 0.
  - #2(P2) 계획서 마이그레이션 번호 드리프트(marker=V12/NOT NULL=V11 거꾸로) → 계획서 §2/P1/P4/P8/§4/§5/§6 일괄 정정(V10 payments·V11 orders marker·V12 payment_cancellations·V13+ NOT NULL).
- 빌드: 단위 그린 + 전체 ./gradlew :build BUILD SUCCESSFUL(4m20s, 통합+가드).
- diff: .cache/diffs/diff-task-impl-order-payment-decouple-1781793372.patch
- raw: .cache/codex-reviews/diff-task-impl-order-payment-decouple-1781793391.json
- 비고: attempts_by_command.work=2. 3차는 §7-6 권장 상한.

## 2026-06-19 00:05 — /ship --execute ([PR #63](https://github.com/Kimgyuilli/PeakCart/pull/63))
- precheck: ok(0 warnings, GS-1 자동통과). drift=all_live.
- 커밋 4개: p1 feat(payment) seam 제거+게이트+migration · p2 test(payment) · p3 docs(adr) ADR-0012 §D4 refine · p4 docs(plan) 계획서·audit·B9. (+ done docs(progress) 커밋)
- push origin/feat/task-impl-order-payment-decouple → PR #63 생성.
- /done: TASKS.md 구현① 체인에 strangler-5 ✅ 추가, PHASE4.md strangler-5 엔트리, roadmap §4.3 갱신. ADR-0012 §D4 refine 은 p3 커밋에 포함.
- state archive + lock 해제.
