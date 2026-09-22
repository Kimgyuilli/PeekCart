# 완료 계획서 인덱스

`scripts/plans-index.sh` 가 생성한다. 손으로 고치지 않는다.

계획서 50개. 진행 중인 계획서는 `docs/plans/` 루트에 있다.
아카이브 기준은 PR 머지 여부다 (`scripts/plans-archive.sh`).

## ADR 설계 (7)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [task-adr0010-service-decomposition](./task-adr0010-service-decomposition.md) | Phase 4 서비스 분해 ADR-0010 작성 |  | 2026-06-14 | 있음 |
| [task-adr0011-multimodule-structure](./task-adr0011-multimodule-structure.md) | Phase 4 멀티모듈 구조 ADR-0011 작성 |  | 2026-06-14 | 있음 |
| [task-adr0012-db-event-saga-contract](./task-adr0012-db-event-saga-contract.md) | Phase 4 DB-per-service + 이벤트/Saga 계약 ADR-0012 |  | 2026-06-14 | 있음 |
| [task-adr0013-gateway-security](./task-adr0013-gateway-security.md) | Phase 4 Gateway 보안 ADR-0013 |  | 2026-06-14 | 있음 |
| [task-adr0014-transitional-auth-module](./task-adr0014-transitional-auth-module.md) | 전환기 인증 검증 공유 모듈 ADR-0014 | [#50](https://github.com/Kimgyuilli/PeakCart/pull/50) | 2026-06-14 | 있음 |
| [task-adr0018-compensation-refund-contract](./task-adr0018-compensation-refund-contract.md) | 보상/환불 트리거 계약 ADR-0018 | [#86](https://github.com/Kimgyuilli/PeakCart/pull/86) | 2026-08-15 | 있음 |
| [task-adr0020-dlq-replay-contract](./task-adr0020-dlq-replay-contract.md) | DLQ replay 계약 ADR-0020 | [#99](https://github.com/Kimgyuilli/PeakCart/pull/99) | 2026-09-01 | 있음 |

## 구현 (23)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [task-impl1-gradle-multimodule](./task-impl1-gradle-multimodule.md) | 구현 ① Gradle 멀티모듈 전환 (ADR-0011) | [#55](https://github.com/Kimgyuilli/PeakCart/pull/55) | 2026-06-15 | 있음 |
| [task-impl-saga1-stock-reservation](./task-impl-saga1-stock-reservation.md) | 사가 클러스터 strangler 1: 재고 예약/복구 이벤트화 (ADR-0010 F2 · ADR-0012 D3) | [#56](https://github.com/Kimgyuilli/PeakCart/pull/56) | 2026-06-16 | 있음 |
| [task-impl-saga3-2phase-payment-gate](./task-impl-saga3-2phase-payment-gate.md) | 2-phase 예약 확정/해제 + Payment charge 예약 게이트 |  | 2026-06-16 | 있음 |
| [task-impl-saga2-unit-price-cache](./task-impl-saga2-unit-price-cache.md) | 사가 클러스터 strangler 2: 단가 로컬 캐시 (ADR-0012 ⑤ CQRS · L-006) | [#57](https://github.com/Kimgyuilli/PeakCart/pull/57) | 2026-06-17 | 있음 |
| [task-impl-product-peel](./task-impl-product-peel.md) | Product 서비스 모듈 peel | [#62](https://github.com/Kimgyuilli/PeakCart/pull/62) | 2026-06-18 | 있음 |
| [task-impl-strangler4-verify-product-cache](./task-impl-strangler4-verify-product-cache.md) | `verifyProductExists` 캐시화 (strangler-4) | [#61](https://github.com/Kimgyuilli/PeakCart/pull/61) | 2026-06-18 | 있음 |
| [task-impl-order-payment-decouple](./task-impl-order-payment-decouple.md) | Order↔Payment 동기 결합 제거 (peel 선행 strangler) | [#63](https://github.com/Kimgyuilli/PeakCart/pull/63) | 2026-06-19 | 있음 |
| [task-impl-order-payment-peel](./task-impl-order-payment-peel.md) | Order/Payment 서비스 peel + root app 해체 | [#65](https://github.com/Kimgyuilli/PeakCart/pull/65) | 2026-06-19 | 있음 |
| [task-impl1-pr3-dockerfile-ci-k8s](./task-impl1-pr3-dockerfile-ci-k8s.md) | PR3: 서비스별 Dockerfile / CI / k8s 재구성 | [#67](https://github.com/Kimgyuilli/PeakCart/pull/67) | 2026-06-20 | 있음 |
| [task-impl1-pr3c-observability](./task-impl1-pr3c-observability.md) | PR3c 관측성 per-service 재설계 + ADR-0015 | [#68](https://github.com/Kimgyuilli/PeakCart/pull/68) | 2026-06-21 | 있음 |
| [task-impl2-db-per-service](./task-impl2-db-per-service.md) | 구현 ② 서비스별 DB 분리 (Flyway 독립 + 물리 스키마 분리) | [#72](https://github.com/Kimgyuilli/PeakCart/pull/72) | 2026-07-04 | 있음 |
| [task-impl3-spring-cloud-gateway](./task-impl3-spring-cloud-gateway.md) | 구현 ③ Spring Cloud Gateway (RS256 + Reuse Detection + S9) | [#108](https://github.com/Kimgyuilli/PeakCart/pull/108) | 2026-07-24 | 있음 |
| [task-impl3-pr3d-internal-token](./task-impl3-pr3d-internal-token.md) | 구현 ③ PR3d 재정의: Gateway 서명 내부 토큰 | [#83](https://github.com/Kimgyuilli/PeakCart/pull/83) | 2026-08-13 | 있음 |
| [task-impl4-choreography-saga](./task-impl4-choreography-saga.md) | 구현 ④ Choreography Saga — 잔여 수렴 갭 종결 | [#87](https://github.com/Kimgyuilli/PeakCart/pull/87) | 2026-08-14 | 있음 |
| [task-impl4-c1-refund-path](./task-impl4-c1-refund-path.md) | 구현 ④-c-1: 환불 요청 경로 (ADR-0018 이행) | [#88](https://github.com/Kimgyuilli/PeakCart/pull/88) | 2026-08-25 | 있음 |
| [task-impl4-c2a-dlq-ledger](./task-impl4-c2a-dlq-ledger.md) | ④-c-2a — DLQ 원장 적재 + quarantine + runbook | [#88](https://github.com/Kimgyuilli/PeakCart/pull/88) | 2026-08-26 | 있음 |
| [task-impl4-d1-saga-metrics](./task-impl4-d1-saga-metrics.md) | ④-d-1 — saga 관측성 (메트릭 · alert) | [#91](https://github.com/Kimgyuilli/PeakCart/pull/91) | 2026-08-26 | 있음 |
| [task-impl4-d2-saga-e2e-gate](./task-impl4-d2-saga-e2e-gate.md) | ④-d-2 — cross-service saga E2E · 계약 게이트 · ④ 종결 | [#93](https://github.com/Kimgyuilli/PeakCart/pull/93) | 2026-08-30 | 있음 |
| [task-impl5-cqrs-cache-fallback](./task-impl5-cqrs-cache-fallback.md) | 구현 ⑤ — CQRS 로컬 캐시 (범위 재확정 + Redis 조회 fallback, L-006) | [#94](https://github.com/Kimgyuilli/PeakCart/pull/94) | 2026-08-30 | 있음 |
| [task-impl6-cursor-pagination](./task-impl6-cursor-pagination.md) | 구현 ⑥ — 주문 내역 Cursor 페이지네이션 | [#96](https://github.com/Kimgyuilli/PeakCart/pull/96) | 2026-08-30 | 있음 |
| [task-impl4-c2b-dlq-replay](./task-impl4-c2b-dlq-replay.md) | ④-c-2b — DLQ replay 경로 (구현) | [#106](https://github.com/Kimgyuilli/PeakCart/pull/106) | 2026-09-13 | 있음 |
| [task-impl3-pr4-auth-observability](./task-impl3-pr4-auth-observability.md) | 구현 ③ PR4: 인증 관측성 S9 + HS512 잔재 제거 | [#108](https://github.com/Kimgyuilli/PeakCart/pull/108) | 2026-09-14 | 있음 |
| [task-impl3-pr3d-b2-cluster-session](./task-impl3-pr3d-b2-cluster-session.md) | 구현 ③ PR3d-b-2 + PR4 잔여: GKE 클러스터 세션 | [#115](https://github.com/Kimgyuilli/PeakCart/pull/115) | 2026-09-15 | 있음 |

## 부채 · 측정 (12)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [task-d002-rescope](./task-d002-rescope.md) | D-002 재정의 (착수 전 코드 검증) | [#117](https://github.com/Kimgyuilli/PeakCart/pull/117) |  |  |
| [task-d028-topic-describe-setup-race](./task-d028-topic-describe-setup-race.md) | task-d028-topic-describe-setup-race | [#126](https://github.com/Kimgyuilli/PeakCart/pull/126) |  |  |
| [task-d010-outbox-trace-context](./task-d010-outbox-trace-context.md) | Outbox trace context 영속화 + Producer 헤더 전파 |  | 2026-05-01 |  |
| [task-d011-harness-hardening](./task-d011-harness-hardening.md) | `/plan`·`/work` 공용 shell helper 4건 정비 |  | 2026-05-02 | 있음 |
| [task-d005-observability-consolidation](./task-d005-observability-consolidation.md) | 관측성 계약 강제 메커니즘 격상 (D-005 잔여 리스크 해결) | [#32](https://github.com/Kimgyuilli/PeakCart/pull/32) | 2026-05-06 | 있음 |
| [task-d002a-cache-speedup-session](./task-d002a-cache-speedup-session.md) | D-002a 캐시 배속 격리 측정 (GKE 세션) | [#117](https://github.com/Kimgyuilli/PeakCart/pull/117) | 2026-09-14 |  |
| [task-d020-approval-reconciliation](./task-d020-approval-reconciliation.md) | D-020 — 결제 승인 경계: 승인 원장 + 멱등키 + 조회 기반 reconciliation | [#107](https://github.com/Kimgyuilli/PeakCart/pull/107) | 2026-09-14 | 있음 |
| [task-d002-bc-session](./task-d002-bc-session.md) | D-002 잔여 3축 측정 세션 (b' / c + a 천장 분리) | [#119](https://github.com/Kimgyuilli/PeakCart/pull/119) | 2026-09-17 | 있음 |
| [task-d025-inventory-lock-boundary](./task-d025-inventory-lock-boundary.md) | D-025 재고 락 경계 (분산 락이 커밋을 감싸지 못한다) | [#123](https://github.com/Kimgyuilli/PeakCart/pull/123) | 2026-09-18 | 있음 |
| [task-d026-detail-stock-cache-boundary](./task-d026-detail-stock-cache-boundary.md) | D-026 상품 상세 재고 조회 캐시 경계 | [#124](https://github.com/Kimgyuilli/PeakCart/pull/124) | 2026-09-18 | 있음 |
| [task-d026-d002a-read-ceiling-session](./task-d026-d002a-read-ceiling-session.md) | 이번 세션 한정 — 사용자 지시(2026-09-20) "코덱스 리뷰 돌리지 마세요". | [#129](https://github.com/Kimgyuilli/PeakCart/pull/129) | 2026-09-20 | 있음 |
| [task-d029-mysql-cpu-base-promotion](./task-d029-mysql-cpu-base-promotion.md) | 사용자 지시(2026-09-22, `.cache/codex-off`) — Codex 리뷰 미호출. | [#131](https://github.com/Kimgyuilli/PeakCart/pull/131) | 2026-09-22 | 있음 |

## 기타 (8)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [task-hpa-manifest](./task-hpa-manifest.md) | HPA 매니페스트 작성 |  | 2026-04-21 | 있음 |
| [task-jmeter-to-k6](./task-jmeter-to-k6.md) | 부하 테스트 도구 JMeter → k6 전환 | [#26](https://github.com/Kimgyuilli/PeakCart/pull/26) | 2026-04-24 | 있음 |
| [task-loadtest-session-c](./task-loadtest-session-c.md) | Phase 3 세션 C 실행 + Task 3-4 / 3-5 마무리 |  | 2026-04-30 | 있음 |
| [task-adr-observability-ssot](./task-adr-observability-ssot.md) | 관측성 계약 SSOT ADR-0009 작성 | [#30](https://github.com/Kimgyuilli/PeakCart/pull/30) | 2026-05-04 | 있음 |
| [task-ci-test-matrix](./task-ci-test-matrix.md) | CI `build` job 분해 — lint / test 매트릭스 / guards / gate | [#97](https://github.com/Kimgyuilli/PeakCart/pull/97) | 2026-09-01 | 있음 |
| [task-user-key-rotation-local-drill](./task-user-key-rotation-local-drill.md) | User 도메인 키 회전 실증 (로컬 드릴) | [#116](https://github.com/Kimgyuilli/PeakCart/pull/116) | 2026-09-15 |  |
| [task-ops-hardening-d022-d023-d024](./task-ops-hardening-d022-d023-d024.md) | 운영 표면 하드닝 (D-024 · D-022 · D-023) | [#121](https://github.com/Kimgyuilli/PeakCart/pull/121) | 2026-09-18 |  |
| [task-codex-review-render](./task-codex-review-render.md) | task-codex-review-render | [#128](https://github.com/Kimgyuilli/PeakCart/pull/128) | 2026-09-20 | 있음 |

