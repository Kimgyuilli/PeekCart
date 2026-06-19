## 2026-06-18 15:40 — GP-2 (loop 1)
- 리뷰 항목: 7건 (P0:0, P1:5, P2:2)
- 사용자 선택: [2] 전체 반영 (7건 모두 파일·라인 인용으로 코드 확증)
  - #1(P1) Redis '조건부'는 ADR-0014 위반 — 5개 서비스 전부 common-auth Redis blacklist fail-closed(0014:66-75), `RedisTokenBlacklistLookupAdapter` 가 RedisTemplate 필수 생성자 의존(:18-39). → P1 build.gradle data-redis 무조건 고정, P5/P12 Redis 무조건, §2 B6 Redis bullet 정정, §5 부팅 시 blacklist adapter RedisTemplate 기동 검증.
  - #2(P1) payment-service Toss 필수 프로퍼티 누락 — `toss.payments.secret-key`/`webhook-secret` @Value 필수(`TossPaymentClient:21`·`WebhookService:27-30`), root yml(:70~)에만 존재. → P10 에 Toss placeholder 이관, P12 PaymentApplicationTests stub 없이 부팅(누락 시 실패), §2 B6 Toss bullet 신설.
  - #3(P1) root 삭제 후 Kafka NewTopic owner 공백 — root `KafkaConfig`(7토픽+DLQ)가 유일 생성자(`grep NewTopic` 확인), product/user/notification 은 listener-only(전환기 root 소유 전제). → order-service 를 전환기 NewTopic owner 로 확정(root 세트 복제, B5 migrator 와 동일 서비스로 일원화), P3/P5/P11 명시, §2 B6 Kafka bullet, §5 토픽 생성 검증.
  - #4(P1) B5 cold-start 순서 미봉합 — payment/product(validate·flyway disabled)가 빈 DB에서 order보다 먼저 뜨면 validate 실패. 단, 이 ordering 의존은 peel 신규 도입 아님(현재도 notification/user/product 가 root 먼저 마이그레이션 후 validate-부팅). → P13 정정: order-service 가 root migrator 역할 승계 + cold-start 검증(빈 DB→order 마이그레이션→타 서비스 validate-부팅) P17, 런타임 순서/readiness 형식화는 PR3. §2 B5 에 선결사실+처분 택1 명시.
  - #5(P1) 신규 Application 에 @EnableScheduling 누락 — root/product 진입점엔 존재(확인), Order `OrderTimeoutScheduler`(@Scheduled 2, ShedLock 잡 orderTimeoutCancelJob·orderReservationTimeoutJob) + outbox poller(@Scheduled) 무력화 위험. → P2/P10 @EnableScheduling 명시, P2 OrderTimeoutScheduler 이동 명시, P14 ShedLockIntegrationTest 잡 이름 기준 재작성, §2 B6 @EnableScheduling bullet.
  - #6(P2) 3-poller 공유DB 검증 harness 부재 — 서비스↔서비스 의존 금지(ADR-0011 §D3)라 단일모듈 3-classpath 불가. → §5 PR-b 검증 2단 구체화: (1) 모듈별 allowlist query + ShedLock 이름 disjoint 단언, (2) black-box docker compose 3-bootJar 토픽별 1회 발행(PR3 공유).
  - #7(P2) 테스트 수량 stale(12+/5+ → 실제 order 16·payment 9, `find` 확인). → B1 표 #3/#4, P6/P14 실수치 + find 전수대조 지시.
- 검증: hpx_plan_lint OK (반영 후 재확인, P1~P17 연속)
- raw: .cache/codex-reviews/plan-task-impl-order-payment-peel-1781797022.json
- run_id: plan:20260618T153632Z:8a9870a0-490c-47bb-b86f-0577f85e509e:1

## 2026-06-18 15:52 — GP-2 (loop 2, 사용자 재리뷰 요청)
- 리뷰 항목: 3건 (P0:0, P1:2, P2:1) — 1차 반영이 노출/유발한 후속 결함 집중
- run_id mismatch: Codex 가 "arch-review-2" 반환(요구 run_id 불일치, §7-5-A) — 내용은 파일·라인 인용으로 확증되어 채택, finalize 에 mismatch 표기
- 사용자 선택: [2] 전체 반영 (3건 모두 코드/ADR 라인 확증)
  - #1(P1) PR-a 가 root poller 를 `PAYMENT` 로 좁히는데 `ObservabilityMetricsIntegrationTest` 의 outbox publish-timer probe 가 여전히 `"ORDER"` aggregateType row 저장(`:84-85`, root 발행 의존) → 미발행→timer 미증가→실패(B1b 재발). 부수: 1차 P7 의 "/api/v1/orders→/payments" 지시가 stale(실제 HTTP 는 이미 `/actuator/health` `:61`). → P7/B1 #8 정정: probe aggregateType `ORDER`→`PAYMENT`, HTTP 무변. PR-b 이동 시 `PAYMENT` 유지.
  - #2(P1) 1차의 "order-service 가 전 토픽(14) 단독 NewTopic owner" 결정이 ADR-0011 §71(토픽=발행 서비스 전속)·ADR-0012 D4(producer 별 분산)와 충돌 + SPOF, "새 ADR 불필요" 전제 깸. DLQ 구조 확인: 메인 7 + 토픽별 `.dlq` 7 = 14 bean. → producer-owns-topic 분산으로 정정: order=order.*(4)·payment=payment.*(6)·product=product.updated/stock.reservation.result(4). **product-service 가 현재 listener-only(root 무임승차) → root 소멸 시 자기 토픽 생성자 부재 → PR-b 에서 product-service 에 NewTopic 4 bean 신설(P11·§4 수정)**. §2 B6·P3·P5·P11·§5 갱신.
  - #3(P2) Layer 1 드리프트 — `02-architecture.md` §5 가 아직 "6토픽"(`:99`·`:124`), ADR-0012 D4 refine 이 7번째 `payment.requested` 추가 + 02 §5 6→7 갱신 지시. 계획 영향 파일에 Layer 1 동기화 없음. → P18 신설(02-arch §5 6→7 + payment.requested + NewTopic producer 분산 반영), §4 수정(PR-b)에 추가.
- 항목 추가: P17 → P18 신설(P1~P18 연속 확인)
- 검증: hpx_plan_lint OK
- raw: .cache/codex-reviews/plan-task-impl-order-payment-peel-1781797666.json
- run_id(요구): plan:20260618T154714Z:8a9870a0-490c-47bb-b86f-0577f85e509e:2 (Codex 반환: "arch-review-2", mismatch)

## 2026-06-19 17:05 — GW-2 (work loop 1, PR-a)
- 리뷰 run: work:20260619T075631Z:eb9389db-3fbc-4d8a-a1a6-9375b31864e7:1 (single, diff 2216줄 — 대부분 git-mv 이동/byte-identical 인프라 복제)
- 항목: 2건 (P0:0, P1:0, P2:2) — 자동 통과 임계 충족, 둘 다 반영(타당)
  - #1(P2) order-service OutboxPollingServiceTest 가 SUT 를 List.of("ORDER","PAYMENT") 로 생성 → 실제 allowlist 는 ORDER 라 소유권 회귀 못 잡음. → List.of("ORDER") 로 수정(application.yml 일치).
  - #2(P2) 플랜 P5 가 요구한 OrderApplicationTests 부팅 스모크 부재(서비스 선례엔 없으나 PR-a 성공기준 고정 필요). → OrderApplicationTests 신설(Testcontainers MySQL/Redis/Kafka 컨텍스트 부팅 + RedisTokenBlacklistLookupAdapter/RedisTemplate·단일 SecurityFilterChain·ConcurrentKafkaListenerContainerFactory 단언). 부팅 시 consumer 4종 기동 확인.
- 검증: :order-service:test 전체 그린(132+3) · :test(root) 그린 · 가드 4종 그린 · 전체 compile 그린
- diff: .cache/diffs/diff-task-impl-order-payment-peel-1781855750.patch
- raw: .cache/codex-reviews/diff-task-impl-order-payment-peel-1781855819.json

## 2026-06-19 08:17 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/64)
- TASKS.md 구현 ① 행에 Order peel PR-a ✅ [#64] 추가 (구현 ① 은 PR-b 미완으로 🔄 유지)
- PHASE4.md: Order peel PR-a 이력 엔트리 (P1~P8·핵심결정·검증·B10)
- ship: 5 커밋(feat order / test order / chore build / test root / docs) + docs(progress) 1 커밋, push, PR #64

## 2026-06-19 11:47 — GW-2 (work loop 1, PR-b)
- 리뷰 run: work:20260619T113924Z:5cdb3f9c-f8c3-4a8e-9641-b8d6d90f5397:1 (single, diff 1188줄 — 대부분 root global 삭제 + git-mv 이동)
- 항목: 2건 (P0:0, P1:1, P2:1) — 둘 다 반영(타당)
  - #1(P1) root 해체(src 삭제·bootJar disabled)로 root Dockerfile(COPY src/·app.jar)이 깨지고 CI PR Docker build/smoke 가 PR 게이트를 막음(메모리 multimodule_dockerfile_context 예측). → root Dockerfile 삭제 + ci.yml Docker 블록(PR build/smoke·GHCR push) 제거(PR3 노트). per-service 이미지는 PR3.
  - #2(P2) 02-arch 동기화 문장 \"Product 가 payment.* 소비\" 가 payment.requested 까지 포함하는 듯 읽힘(실제 product 는 payment.completed/failed 만, payment.requested 는 order 만). → \"payment.completed/payment.failed\" 로 좁힘(ADR-0012 D4·실제 listener 정합).
- 검증: :common:test·:order-service:test·:payment-service:test·:product-service:test 그린 · 가드 4종(payment-service 편입) · build -x test(5 서비스 bootJar 산출·root SKIPPED) · src 소멸(0)
- diff: .cache/diffs/diff-task-impl-order-payment-peel-1781869144.patch
- raw: .cache/codex-reviews/diff-task-impl-order-payment-peel-1781869189.json
