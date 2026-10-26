# 완료 계획서 인덱스

`scripts/plans-index.sh` 가 생성한다. 손으로 고치지 않는다.

계획서 169개. 진행 중인 계획서는 `docs/plans/` 루트에 있다.
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
| [task-impl1-gradle-multimodule](./task-impl1-gradle-multimodule.md) | 구현 ① Gradle 멀티모듈 전환 (ADR-0011) | [#52](https://github.com/Kimgyuilli/PeakCart/pull/52) | 2026-06-15 | 있음 |
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
| [task-impl3-spring-cloud-gateway](./task-impl3-spring-cloud-gateway.md) | 구현 ③ Spring Cloud Gateway (RS256 + Reuse Detection + S9) | [#76](https://github.com/Kimgyuilli/PeakCart/pull/76) | 2026-07-24 | 있음 |
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
| [task-impl3-pr3d-b2-cluster-session](./task-impl3-pr3d-b2-cluster-session.md) | 구현 ③ PR3d-b-2 + PR4 잔여: GKE 클러스터 세션 | [#109](https://github.com/Kimgyuilli/PeakCart/pull/109) | 2026-09-15 | 있음 |

## 부채 · 측정 (123)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [D-051](./D-051.md) | D-051 | [#163](https://github.com/Kimgyuilli/PeakCart/pull/163) |  |  |
| [D-054](./D-054.md) | D-054 | [#164](https://github.com/Kimgyuilli/PeakCart/pull/164) |  |  |
| [D-078-product-actions](./D-078-product-actions.md) | D-078 product barrier Actions 재현 | [#218](https://github.com/Kimgyuilli/PeakCart/pull/218) |  | 있음 |
| [D-087-profile-cache](./D-087-profile-cache.md) | D-087 profile export cache 후속 | [#216](https://github.com/Kimgyuilli/PeakCart/pull/216) |  | 있음 |
| [task-d002-rescope](./task-d002-rescope.md) | D-002 재정의 (착수 전 코드 검증) | [#117](https://github.com/Kimgyuilli/PeakCart/pull/117) |  |  |
| [task-d027-rationale-split](./task-d027-rationale-split.md) | task-d027-rationale-split | [#158](https://github.com/Kimgyuilli/PeakCart/pull/158) |  |  |
| [task-d028-topic-describe-setup-race](./task-d028-topic-describe-setup-race.md) | task-d028-topic-describe-setup-race | [#126](https://github.com/Kimgyuilli/PeakCart/pull/126) |  |  |
| [task-d010-outbox-trace-context](./task-d010-outbox-trace-context.md) | Outbox trace context 영속화 + Producer 헤더 전파 |  | 2026-05-01 |  |
| [task-d011-harness-hardening](./task-d011-harness-hardening.md) | `/plan`·`/work` 공용 shell helper 4건 정비 |  | 2026-05-02 | 있음 |
| [task-d005-observability-consolidation](./task-d005-observability-consolidation.md) | 관측성 계약 강제 메커니즘 격상 (D-005 잔여 리스크 해결) | [#32](https://github.com/Kimgyuilli/PeakCart/pull/32) | 2026-05-06 | 있음 |
| [D-055](./D-055.md) | D-055 | [#165](https://github.com/Kimgyuilli/PeakCart/pull/165) | 2026-08-12 |  |
| [task-d002a-cache-speedup-session](./task-d002a-cache-speedup-session.md) | D-002a 캐시 배속 격리 측정 (GKE 세션) | [#117](https://github.com/Kimgyuilli/PeakCart/pull/117) | 2026-09-14 |  |
| [task-d020-approval-reconciliation](./task-d020-approval-reconciliation.md) | D-020 — 결제 승인 경계: 승인 원장 + 멱등키 + 조회 기반 reconciliation | [#107](https://github.com/Kimgyuilli/PeakCart/pull/107) | 2026-09-14 | 있음 |
| [task-d025-inventory-lock-boundary](./task-d025-inventory-lock-boundary.md) | D-025 재고 락 경계 (분산 락이 커밋을 감싸지 못한다) | [#123](https://github.com/Kimgyuilli/PeakCart/pull/123) | 2026-09-18 | 있음 |
| [task-d026-detail-stock-cache-boundary](./task-d026-detail-stock-cache-boundary.md) | D-026 상품 상세 재고 조회 캐시 경계 | [#124](https://github.com/Kimgyuilli/PeakCart/pull/124) | 2026-09-18 | 있음 |
| [task-d026-d002a-read-ceiling-session](./task-d026-d002a-read-ceiling-session.md) | 이번 세션 한정 — 사용자 지시(2026-09-20) "코덱스 리뷰 돌리지 마세요". | [#129](https://github.com/Kimgyuilli/PeakCart/pull/129) | 2026-09-20 | 있음 |
| [task-d029-mysql-cpu-base-promotion](./task-d029-mysql-cpu-base-promotion.md) | 사용자 지시(2026-09-22, `.cache/codex-off`) — Codex 리뷰 미호출. | [#131](https://github.com/Kimgyuilli/PeakCart/pull/131) | 2026-09-22 | 있음 |
| [task-d033-e2e-parallel](./task-d033-e2e-parallel.md) | D-033 — e2e 시나리오와 음성 대조군 병렬 실행 | [#140](https://github.com/Kimgyuilli/PeakCart/pull/140) | 2026-09-25 | 있음 |
| [task-d035-notification-container-singleton](./task-d035-notification-container-singleton.md) | task-d035-notification-container-singleton | [#145](https://github.com/Kimgyuilli/PeakCart/pull/145) | 2026-09-26 | 있음 |
| [task-d035-payment-container-singleton](./task-d035-payment-container-singleton.md) | task-d035-payment-container-singleton | [#146](https://github.com/Kimgyuilli/PeakCart/pull/146) | 2026-09-26 | 있음 |
| [task-d035-user-container-singleton](./task-d035-user-container-singleton.md) | task-d035-user-container-singleton | [#144](https://github.com/Kimgyuilli/PeakCart/pull/144) | 2026-09-26 | 있음 |
| [task-d043-ci-release-gate](./task-d043-ci-release-gate.md) | D-043 — CI 최종 게이트와 이미지 승격 경계 | [#142](https://github.com/Kimgyuilli/PeakCart/pull/142) | 2026-09-26 | 있음 |
| [task-d035-product-container-singleton](./task-d035-product-container-singleton.md) | task-d035-product-container-singleton | [#147](https://github.com/Kimgyuilli/PeakCart/pull/147) | 2026-09-27 | 있음 |
| [task-d040-local-test-scope](./task-d040-local-test-scope.md) | task-d040-local-test-scope | [#148](https://github.com/Kimgyuilli/PeakCart/pull/148) | 2026-09-27 |  |
| [task-d044-test-seed-input](./task-d044-test-seed-input.md) | task-d044-test-seed-input | [#149](https://github.com/Kimgyuilli/PeakCart/pull/149) | 2026-09-27 | 있음 |
| [task-d046-cache-timeout-bound](./task-d046-cache-timeout-bound.md) | task-d046-cache-timeout-bound | [#150](https://github.com/Kimgyuilli/PeakCart/pull/150) | 2026-09-27 |  |
| [task-d030-outbox-dlq-alert-split](./task-d030-outbox-dlq-alert-split.md) | task-d030-outbox-dlq-alert-split | [#155](https://github.com/Kimgyuilli/PeakCart/pull/155) | 2026-09-28 | 있음 |
| [task-d041-fault-injection-boundary](./task-d041-fault-injection-boundary.md) | task-d041-fault-injection-boundary | [#151](https://github.com/Kimgyuilli/PeakCart/pull/151) | 2026-09-28 | 있음 |
| [task-d042-verification-responsibility](./task-d042-verification-responsibility.md) | task-d042-verification-responsibility | [#153](https://github.com/Kimgyuilli/PeakCart/pull/153) | 2026-09-28 | 있음 |
| [task-d047-jvm-gap-coverage](./task-d047-jvm-gap-coverage.md) | D-047 — e2e 만 보는 로직 3건을 JVM 테스트로 채운다 | [#154](https://github.com/Kimgyuilli/PeakCart/pull/154) | 2026-09-28 | 있음 |
| [D-056](./D-056.md) | D-056 | [#166](https://github.com/Kimgyuilli/PeakCart/pull/166) | 2026-09-29 | 있음 |
| [D-058](./D-058.md) | D-058 | [#167](https://github.com/Kimgyuilli/PeakCart/pull/167) | 2026-09-29 | 있음 |
| [task-d027-grade-audit](./task-d027-grade-audit.md) | D-027 ③ — 등급 사후 검증 | [#159](https://github.com/Kimgyuilli/PeakCart/pull/159) | 2026-09-29 | 있음 |
| [task-d048-e2e-stack-startup](./task-d048-e2e-stack-startup.md) | task-d048-e2e-stack-startup | [#156](https://github.com/Kimgyuilli/PeakCart/pull/156) | 2026-09-29 |  |
| [D-050](./D-050.md) | main 코드 주석의 계획서 내부 식별자 제거 | [#169](https://github.com/Kimgyuilli/PeakCart/pull/169) | 2026-09-30 | 있음 |
| [D-052](./D-052.md) | TASKS.md 완료 항목 아카이브 + 완료 시 이동 규칙 | [#170](https://github.com/Kimgyuilli/PeakCart/pull/170) | 2026-09-30 | 있음 |
| [D-053](./D-053.md) | Layer 1 문서의 Phase 1·2 설계 아카이브 | [#171](https://github.com/Kimgyuilli/PeakCart/pull/171) | 2026-09-30 | 있음 |
| [D-057](./D-057.md) | `plan.md` · `work.md` 공통 리뷰 규칙의 단일 문서화 | [#172](https://github.com/Kimgyuilli/PeakCart/pull/172) | 2026-09-30 | 있음 |
| [D-059](./D-059.md) | 환불 처리 중 응답의 재시도 분류 | [#174](https://github.com/Kimgyuilli/PeakCart/pull/174) | 2026-10-14 |  |
| [D-060](./D-060.md) | 승인과 예약 lease 회수 사이의 fence | [#175](https://github.com/Kimgyuilli/PeakCart/pull/175) | 2026-10-14 | 있음 |
| [D-060-followup](./D-060-followup.md) | D-060 followup — PR #175 기반 보호 승인 복구 보강 | [#176](https://github.com/Kimgyuilli/PeakCart/pull/176) | 2026-10-17 | 있음 |
| [D-061](./D-061.md) | D-061 환불 결과와 호출 순서 E2E | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-062](./D-062.md) | D-062 동일 DLT 좌표 재소비 E2E | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-063](./D-063.md) | 무료 로컬 실제 Grafana/Prometheus alert 평가 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-066](./D-066.md) | D-066 상품 정렬과 캐시 키 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-067](./D-067.md) | D-067 요청 타입 및 메서드 검증 오류 응답 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-068](./D-068.md) | D-068 MySQL 패치 버전 고정 | [#177](https://github.com/Kimgyuilli/PeakCart/pull/177) | 2026-10-17 | 있음 |
| [D-069](./D-069.md) | D-069 상품 cache miss의 DB 진입 제한 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-070](./D-070.md) | cache fallback 자동 alert | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-071](./D-071.md) | 장바구니 상품 read model 조합 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-072](./D-072.md) | D-072 캐시 hit의 MySQL 세션 명령 제거 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-073](./D-073.md) | 원본 측정·코드 이력 정정 | [#177](https://github.com/Kimgyuilli/PeakCart/pull/177) | 2026-10-17 | 있음 |
| [D-074](./D-074.md) | ADR-0027 Alternative A 제목 정정 | [#176](https://github.com/Kimgyuilli/PeakCart/pull/176) | 2026-10-17 |  |
| [D-076](./D-076.md) | Kafka 오류 핸들러 jitter 일관성 | [#180](https://github.com/Kimgyuilli/PeakCart/pull/180) | 2026-10-17 | 있음 |
| [D-077](./D-077.md) | 현재 재고 동시성과 비동기 취소 설명 | [#177](https://github.com/Kimgyuilli/PeakCart/pull/177) | 2026-10-17 | 있음 |
| [D-079](./D-079.md) | 측정 overlay 기본 렌더 복구 | [#176](https://github.com/Kimgyuilli/PeakCart/pull/176) | 2026-10-17 | 있음 |
| [D-080](./D-080.md) | D-080 통합 테스트 기동 비용 서술 정정 | [#177](https://github.com/Kimgyuilli/PeakCart/pull/177) | 2026-10-17 | 있음 |
| [D-081](./D-081.md) | 수동 리스너 기동의 할당 대기 (D-082 공동 구현) | [#176](https://github.com/Kimgyuilli/PeakCart/pull/176) | 2026-10-17 | 있음 |
| [D-082](./D-082.md) | 자율 writer 수명 lint (D-081 공동 구현) | [#176](https://github.com/Kimgyuilli/PeakCart/pull/176) | 2026-10-17 | 있음 |
| [D-083](./D-083.md) | D-083 Kafka cleanup 실패 전파 | [#177](https://github.com/Kimgyuilli/PeakCart/pull/177) | 2026-10-17 | 있음 |
| [task-d002-bc-session](./task-d002-bc-session.md) | D-002 잔여 3축 측정 세션 (b' / c + a 천장 분리) | [#119](https://github.com/Kimgyuilli/PeakCart/pull/119) | 2026-10-17 | 있음 |
| [D-049](./D-049.md) | D-049 | [#168](https://github.com/Kimgyuilli/PeakCart/pull/168) | 2026-10-21 | 있음 |
| [D-064-merchant-order-id](./D-064-merchant-order-id.md) | D-064 canonical PG 주문 식별자 | [#196](https://github.com/Kimgyuilli/PeakCart/pull/196) | 2026-10-21 | 있음 |
| [D-064-pg-contract](./D-064-pg-contract.md) | D-064 무료 PG 계약 보정 | [#193](https://github.com/Kimgyuilli/PeakCart/pull/193) | 2026-10-21 | 있음 |
| [D-065-access-denied-mvc](./D-065-access-denied-mvc.md) | D-065 MVC 권한 거부 응답 보존 | [#211](https://github.com/Kimgyuilli/PeakCart/pull/211) | 2026-10-21 | 있음 |
| [D-065-collector](./D-065-collector.md) | D-065 collector 수집 실패 차단 | [#189](https://github.com/Kimgyuilli/PeakCart/pull/189) | 2026-10-21 | 있음 |
| [D-065-constraints](./D-065-constraints.md) | D-065 constraints — 로컬 분류·backfill 안전성 검증 계획 | [#202](https://github.com/Kimgyuilli/PeakCart/pull/202) | 2026-10-21 | 있음 |
| [D-065-dlq-diagnostic](./D-065-dlq-diagnostic.md) | D-065 DLQ 첫 테스트 실패 진단 | [#205](https://github.com/Kimgyuilli/PeakCart/pull/205) | 2026-10-21 | 있음 |
| [D-065-lineage-writer](./D-065-lineage-writer.md) | D-065 DLQ 원장의 최초 INSERT lineage 보장 | [#201](https://github.com/Kimgyuilli/PeakCart/pull/201) | 2026-10-21 | 있음 |
| [D-065-signed-gateway](./D-065-signed-gateway.md) | D-065 signed gateway — 닫힌 replay 진입점의 실제 인증 체인 | [#210](https://github.com/Kimgyuilli/PeakCart/pull/210) | 2026-10-21 | 있음 |
| [D-065-signed-gateway-execution](./D-065-signed-gateway-execution.md) | D-065 signed gateway 실행 계획 | [#210](https://github.com/Kimgyuilli/PeakCart/pull/210) | 2026-10-21 | 있음 |
| [D-075](./D-075.md) | D-075: outbox 로컬 병목 분해 (부분 분석) | [#200](https://github.com/Kimgyuilli/PeakCart/pull/200) | 2026-10-21 | 있음 |
| [D-078](./D-078.md) | D-078: 캐시 이름별 수집 공백의 로컬 보완 (부분 작업) | [#199](https://github.com/Kimgyuilli/PeakCart/pull/199) | 2026-10-21 | 있음 |
| [D-085](./D-085.md) | D-085: CI gate 실패 전파 의미 검사 | [#185](https://github.com/Kimgyuilli/PeakCart/pull/185) | 2026-10-21 | 있음 |
| [D-087](./D-087.md) | D-087: Docker Gradle 구간의 실제 CI profile 보존 | [#209](https://github.com/Kimgyuilli/PeakCart/pull/209) | 2026-10-21 | 있음 |
| [D-090](./D-090.md) | D-090: Outbox FAILED 알림 실행 계약 검사 | [#186](https://github.com/Kimgyuilli/PeakCart/pull/186) | 2026-10-21 | 있음 |
| [D-094](./D-094.md) | D-094: Redis 조회 상한 실패 진단 정정 | [#198](https://github.com/Kimgyuilli/PeakCart/pull/198) | 2026-10-21 |  |
| [D-097](./D-097.md) | D-097: 실제 앱 런타임 프로젝트 산출물의 global FQCN 검사 | [#187](https://github.com/Kimgyuilli/PeakCart/pull/187) | 2026-10-21 | 있음 |
| [D-098](./D-098.md) | D-098: 완료 행·계획 이동 보존 검사 | [#203](https://github.com/Kimgyuilli/PeakCart/pull/203) | 2026-10-21 | 있음 |
| [D-099](./D-099.md) | D-099: harness 완료 아카이브 건강 검사 | [#204](https://github.com/Kimgyuilli/PeakCart/pull/204) | 2026-10-21 | 있음 |
| [D-103](./D-103.md) | D-103: 리뷰 결과 렌더러 수용 계약 | [#206](https://github.com/Kimgyuilli/PeakCart/pull/206) | 2026-10-21 | 있음 |
| [D-105](./D-105.md) | D-105: ship 재개 조회 실패 구분 | [#184](https://github.com/Kimgyuilli/PeakCart/pull/184) | 2026-10-21 | 있음 |
| [task-d031-ci-graph-parallelization](./task-d031-ci-graph-parallelization.md) | D-031 — CI 워크플로 그래프 직렬화 해소 | [#135](https://github.com/Kimgyuilli/PeakCart/pull/135) | 2026-10-21 | 있음 |
| [task-d032-integration-test-container-singleton](./task-d032-integration-test-container-singleton.md) | D-032 — 통합 테스트 컨테이너 모듈 싱글톤 전환 (order-service) | [#138](https://github.com/Kimgyuilli/PeakCart/pull/138) | 2026-10-21 | 있음 |
| [task-d037-branch-protection](./task-d037-branch-protection.md) | D-037 — main 필수 체크를 실제 CI gate 에 연결 | [#141](https://github.com/Kimgyuilli/PeakCart/pull/141) | 2026-10-21 | 있음 |
| [task-d039-gradle-setup](./task-d039-gradle-setup.md) | task-d039-gradle-setup | [#157](https://github.com/Kimgyuilli/PeakCart/pull/157) | 2026-10-21 |  |
| [D-065-collector-adapter](./D-065-collector-adapter.md) | D-065 actual DB/Kafka collector adapter 검증 | [#212](https://github.com/Kimgyuilli/PeakCart/pull/212) | 2026-10-22 | 있음 |
| [D-065-flyway-contract](./D-065-flyway-contract.md) | D-065 실제 Flyway migration·재시작 경계 검증 | [#207](https://github.com/Kimgyuilli/PeakCart/pull/207) | 2026-10-22 | 있음 |
| [D-078-retry-load](./D-078-retry-load.md) | D-078: finite Kafka inventory retry experiment | [#221](https://github.com/Kimgyuilli/PeakCart/pull/221) | 2026-10-22 | 있음 |
| [D-078-session-prep](./D-078-session-prep.md) | D-078 과금 세션 사전 준비 (부분 작업) | [#225](https://github.com/Kimgyuilli/PeakCart/pull/225) | 2026-10-22 | 있음 |
| [D-084-signed-recovery](./D-084-signed-recovery.md) | D-084 — 실제 signed ADMIN 분산 복구 체인 | [#214](https://github.com/Kimgyuilli/PeakCart/pull/214) | 2026-10-22 | 있음 |
| [D-086](./D-086.md) | CI 시간 이력 사실 정정 | [#190](https://github.com/Kimgyuilli/PeakCart/pull/190) | 2026-10-22 |  |
| [D-088](./D-088.md) | D-088: lint CI 배선과 외부 생존 대조 | [#213](https://github.com/Kimgyuilli/PeakCart/pull/213) | 2026-10-22 | 있음 |
| [D-091](./D-091.md) | D-091 | [#191](https://github.com/Kimgyuilli/PeakCart/pull/191) | 2026-10-22 |  |
| [D-092-slack-split](./D-092-slack-split.md) | D-092 P2: 사용자와 운영 Slack webhook 분리 | [#219](https://github.com/Kimgyuilli/PeakCart/pull/219) | 2026-10-22 | 있음 |
| [D-093](./D-093.md) | D-093: 실제 alert 초기 평가 준비 대기 | [#188](https://github.com/Kimgyuilli/PeakCart/pull/188) | 2026-10-22 | 있음 |
| [D-095](./D-095.md) | D-095 | [#191](https://github.com/Kimgyuilli/PeakCart/pull/191) | 2026-10-22 |  |
| [D-096](./D-096.md) | D-096 | [#192](https://github.com/Kimgyuilli/PeakCart/pull/192) | 2026-10-22 |  |
| [D-100](./D-100.md) | D-100: Flyway 체크섬 근거 버전 정정 | [#194](https://github.com/Kimgyuilli/PeakCart/pull/194) | 2026-10-22 |  |
| [D-101](./D-101.md) | D-101: 현재 아키텍처 트리와 역사 참조 정정 | [#195](https://github.com/Kimgyuilli/PeakCart/pull/195) | 2026-10-22 |  |
| [D-102](./D-102.md) | D-102: 테스트 주석의 의미와 출처 정리 | [#197](https://github.com/Kimgyuilli/PeakCart/pull/197) | 2026-10-22 | 있음 |
| [D-104](./D-104.md) | D-104: 하네스 실측 근거의 모집단과 정정 | [#208](https://github.com/Kimgyuilli/PeakCart/pull/208) | 2026-10-22 | 있음 |
| [D-078-capture-prep](./D-078-capture-prep.md) | D-078 세션 캡처 도구와 세션 범위 확장 (부분 작업) | [#228](https://github.com/Kimgyuilli/PeakCart/pull/228) | 2026-10-23 | 있음 |
| [D-078-latency-metrics](./D-078-latency-metrics.md) | D-078 README 보강 지표 A~E 와 지연 원자료 도구 (부분 작업) | [#229](https://github.com/Kimgyuilli/PeakCart/pull/229) | 2026-10-23 | 있음 |
| [D-078-session-dashboard](./D-078-session-dashboard.md) | D-078 측정 세션 대시보드와 캡처 기준 (부분 작업) | [#226](https://github.com/Kimgyuilli/PeakCart/pull/226) | 2026-10-23 | 있음 |
| [D-078-scheduler-capacity](./D-078-scheduler-capacity.md) | D-078 실제 Boot scheduler의 유한 capacity 검사 | [#220](https://github.com/Kimgyuilli/PeakCart/pull/220) | 2026-10-24 | 있음 |
| [D-078-session-plan](./D-078-session-plan.md) | D-078 GKE 측정 세션 계획 (부분 작업) | [#230](https://github.com/Kimgyuilli/PeakCart/pull/230) | 2026-10-24 | 있음 |
| [D-089](./D-089.md) | D-089: 부분 publish 관측과 복구 계약 | [#234](https://github.com/Kimgyuilli/PeakCart/pull/234) | 2026-10-24 | 있음 |
| [D-089-stale-state](./D-089-stale-state.md) | D-089 stale 판정 — main 이 expected 를 지난 뒤의 publication-state | [#234](https://github.com/Kimgyuilli/PeakCart/pull/234) | 2026-10-24 | 있음 |
| [D-092](./D-092.md) | D-092: 운영 알림 전달과 Outbox 복구 경계 | [#235](https://github.com/Kimgyuilli/PeakCart/pull/235) | 2026-10-24 | 있음 |
| [D-092-ops-closure](./D-092-ops-closure.md) | D-092-ops-closure: Slack webhook Secret 교체와 FAILED 재발행 소비쌍 조건 | [#235](https://github.com/Kimgyuilli/PeakCart/pull/235) | 2026-10-24 | 있음 |
| [D-106-remeasure-prereq](./D-106-remeasure-prereq.md) | D-106 재측정 선행 수정 — ⑨③④⑧⑪ | [#232](https://github.com/Kimgyuilli/PeakCart/pull/232) | 2026-10-24 | 있음 |
| [D-106-topic-config-race](./D-106-topic-config-race.md) | D-106 ⑩ — 토픽 생성 경합으로 선언 config 가 적용되지 않는 문제 | [#237](https://github.com/Kimgyuilli/PeakCart/pull/237) | 2026-10-24 | 있음 |
| [D-107-recovery-lab-topics](./D-107-recovery-lab-topics.md) | D-107-recovery-lab-topics | [#238](https://github.com/Kimgyuilli/PeakCart/pull/238) | 2026-10-24 |  |
| [D-065-contract-migration](./D-065-contract-migration.md) | D-065 ③ — replay 판별자 NOT NULL contract migration (코드 몫) | [#244](https://github.com/Kimgyuilli/PeakCart/pull/244) | 2026-10-25 | 있음 |
| [D-075-outbox-throughput](./D-075-outbox-throughput.md) | D-075 outbox 발행 처리율 기본값 처분 (+ D-106 ⑥) | [#242](https://github.com/Kimgyuilli/PeakCart/pull/242) | 2026-10-25 | 있음 |
| [D-078-remeasure-plan](./D-078-remeasure-plan.md) | D-078 GKE 재측정 세션 계획 (D-106 ①⑦⑤ 설계 확정) | [#246](https://github.com/Kimgyuilli/PeakCart/pull/246) | 2026-10-25 | 있음 |
| [D-106-topic-guard-throttle](./D-106-topic-guard-throttle.md) | D-106 ⑩·② — 측정 전 토픽 상태 가드와 CPU 스로틀 패널 | [#239](https://github.com/Kimgyuilli/PeakCart/pull/239) | 2026-10-25 | 있음 |
| [D-107-deploy-topics](./D-107-deploy-topics.md) | 배포 래퍼의 kafka 선행 기동과 토픽 사전 생성 | [#241](https://github.com/Kimgyuilli/PeakCart/pull/241) | 2026-10-25 | 있음 |
| [D-064](./D-064.md) | D-064 Toss 테스트 환경 실호출 검증: 승인·조회·취소와 중복 응답 | [#249](https://github.com/Kimgyuilli/PeakCart/pull/249) | 2026-10-26 | 있음 |
| [D-108](./D-108.md) | D-108 마지막 재측정: 수신 상한 대조와 D-078 ⑤⑥ 측정 | [#250](https://github.com/Kimgyuilli/PeakCart/pull/250) | 2026-10-26 | 있음 |
| [D-116-loadtest](./D-116-loadtest.md) | 측정 산출물 정리 (D-116 P1) | [#251](https://github.com/Kimgyuilli/PeakCart/pull/251) | 2026-10-26 | 있음 |
| [D-116-tooling](./D-116-tooling.md) | 도구 경로와 계획 폴더 정비 (D-116 P2) | [#252](https://github.com/Kimgyuilli/PeakCart/pull/252) | 2026-10-26 | 있음 |

## 기타 (16)

| 계획서 | 내용 | PR | 날짜 | audit |
|---|---|---|---|---|
| [task-phase5-baseline](./task-phase5-baseline.md) | task-phase5-baseline | [#134](https://github.com/Kimgyuilli/PeakCart/pull/134) |  |  |
| [task-publish-diagnostics](./task-publish-diagnostics.md) | Publish 실패 출력 보존 | [#179](https://github.com/Kimgyuilli/PeakCart/pull/179) |  |  |
| [task-hpa-manifest](./task-hpa-manifest.md) | HPA 매니페스트 작성 |  | 2026-04-21 | 있음 |
| [task-jmeter-to-k6](./task-jmeter-to-k6.md) | 부하 테스트 도구 JMeter → k6 전환 | [#26](https://github.com/Kimgyuilli/PeakCart/pull/26) | 2026-04-24 | 있음 |
| [task-loadtest-session-c](./task-loadtest-session-c.md) | Phase 3 세션 C 실행 + Task 3-4 / 3-5 마무리 |  | 2026-04-30 | 있음 |
| [task-adr-observability-ssot](./task-adr-observability-ssot.md) | 관측성 계약 SSOT ADR-0009 작성 | [#30](https://github.com/Kimgyuilli/PeakCart/pull/30) | 2026-05-04 | 있음 |
| [task-ci-test-matrix](./task-ci-test-matrix.md) | CI `build` job 분해 — lint / test 매트릭스 / guards / gate | [#97](https://github.com/Kimgyuilli/PeakCart/pull/97) | 2026-09-01 | 있음 |
| [task-user-key-rotation-local-drill](./task-user-key-rotation-local-drill.md) | User 도메인 키 회전 실증 (로컬 드릴) | [#116](https://github.com/Kimgyuilli/PeakCart/pull/116) | 2026-09-15 |  |
| [task-ops-hardening-d022-d023-d024](./task-ops-hardening-d022-d023-d024.md) | 운영 표면 하드닝 (D-024 · D-022 · D-023) | [#121](https://github.com/Kimgyuilli/PeakCart/pull/121) | 2026-09-18 |  |
| [task-codex-review-render](./task-codex-review-render.md) | task-codex-review-render | [#128](https://github.com/Kimgyuilli/PeakCart/pull/128) | 2026-09-20 | 있음 |
| [task-phase4-closure](./task-phase4-closure.md) | task-phase4-closure | [#133](https://github.com/Kimgyuilli/PeakCart/pull/133) | 2026-09-22 |  |
| [task-debt-20261021](./task-debt-20261021.md) | 개발 부채 실행 기록 — 2026-10-21 | [#221](https://github.com/Kimgyuilli/PeakCart/pull/221) | 2026-10-22 |  |
| [ci-critical-path](./ci-critical-path.md) | CI 임계경로 단축 — 문서 전용 skip, images 의 lint 비대기, e2e 3러너 | [#231](https://github.com/Kimgyuilli/PeakCart/pull/231) | 2026-10-24 | 있음 |
| [digest-open-rows](./digest-open-rows.md) | digest-open-rows | [#240](https://github.com/Kimgyuilli/PeakCart/pull/240) | 2026-10-25 |  |
| [sync-context-cost](./sync-context-cost.md) | sync-context-cost | [#247](https://github.com/Kimgyuilli/PeakCart/pull/247) | 2026-10-25 |  |
| [task-debt-orchestration](./task-debt-orchestration.md) | 남은 부채 병렬 처리 — root 조율 · 독립 PR 검증 · 무료 CI 우선 | [#181](https://github.com/Kimgyuilli/PeakCart/pull/181) | 2026-10-26 | 있음 |

