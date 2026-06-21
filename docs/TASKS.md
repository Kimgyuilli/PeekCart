# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| Phase 4 설계·실행 로드맵 (ADR 시퀀싱·구현 순서) | `docs/progress/phase4-design-roadmap.md` |
| 진입 전 부채 해소 로드맵 (버킷 1 완결, 버킷 2/3 이관·게이트) | `docs/progress/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` |

---

## 현재 단계: Phase 4 — MSA 분리

> 서비스 경계 정본 = §5(5개 풀 분해) 확정. **초기 설계 ADR(A1~A4) 완료 (#44~#47) → 구현 단계.** 구현 ① PR2 착수 중 전환기 인증 보정 **ADR-0014(A4.5)** 추가(`peekcart-common-auth`, ADR-0011 부분 무효화). 상세: `docs/progress/phase4-design-roadmap.md`.
> 착수 시 상태를 `🔄`, 머지 시 `✅` 로 갱신하고 PR/ADR 링크를 단다. 구현 각 항목의 세부 PR 분할은 해당 항목 `/plan` 착수 시 정의한다.

### 설계 (A1~A4 ✅ 완료 · A4.5 보정 ADR)

| 순서 | ADR | 작업 | 편입 부채 | 상태 |
|---|---|---|---|---|
| A1 | ADR-0010 | 서비스 분해 — §5 비준 + §4-5 정정, 5개 서비스 계약 명문화 (+ F1/F2/F3 정합) | — | ✅ [#44](https://github.com/Kimgyuilli/PeakCart/pull/44) |
| A2 | ADR-0011 | 멀티모듈 구조 (`common`+관측성+5서비스, 의존 규칙·빌드/테스트/이미지 계약) | L-016a, D-016 | ✅ [#45](https://github.com/Kimgyuilli/PeakCart/pull/45) |
| A3 | ADR-0012 | DB-per-service + 이벤트/Saga 계약 (재고 예약·`stock.reservation.result`·retention) | L-008/011, L-020-2 | ✅ [#46](https://github.com/Kimgyuilli/PeakCart/pull/46) |
| A4 | ADR-0013 | Gateway 보안 (RS256·Gateway 검증·Rate Limit·Reuse Detection·S9 관측성) | 보안 묶음 L-001/002/003/019 | ✅ [#47](https://github.com/Kimgyuilli/PeakCart/pull/47) |
| A4.5 | ADR-0014 | 전환기 인증 검증 공유 모듈 `peekcart-common-auth` (게이트웨이 이전, ADR-0011 부분 무효화) — 구현 ① PR2 중 발견 | — | ✅ [#50](https://github.com/Kimgyuilli/PeakCart/pull/50) |

### 구현 (ADR 선행 후 PR 단위) ← 현재 focus

> **구현 ① ✅ 완료 (선행 ADR-0011/ADR-0014, [#48~#68])** — PR1 스켈레톤+common → PR2 서비스 분리(5 peel·root 소멸) → PR3 Dockerfile/CI(#66)·k8s(#67)·관측성(#68). **다음 focus = 구현 ②(서비스별 DB 물리 분리).** 계획서: `docs/plans/task-impl1-gradle-multimodule.md`.
> **⚠️ peel 순서 정정 (2026-06-15)**: Product 가 Order 의 동기 빈(`ProductPort`)에 묶여 ① 단독 peel 불가(부팅 실패) — independent 한 **User 를 PR2b 로 먼저** 떼고, Order/Product/Payment 는 ②(DB)/④(Saga)/⑤(캐시)를 교차한 사가 클러스터로 함께 분리한다(ADR-0010 F2·ADR-0012 D3, 새 ADR 불필요). 상세: `phase4-design-roadmap.md §2`.

| 순서 | 작업 | 선행 ADR | 편입 부채 | 상태 |
|---|---|---|---|---|
| ① | Gradle 멀티모듈 전환 (PR1 ✅ [#48](https://github.com/Kimgyuilli/PeakCart/pull/48) · PR2a-1 ✅ [#51](https://github.com/Kimgyuilli/PeakCart/pull/51) common-auth 추출+JWT verify/sign 분리 · PR2a-2a ✅ [#52](https://github.com/Kimgyuilli/PeakCart/pull/52) SlackPort→:common+ADR-0011 §D2 정정 · PR2a-2b ✅ [#53](https://github.com/Kimgyuilli/PeakCart/pull/53) notification-service peel(첫 서비스 분리)+ActuatorSecurityConfig(S4)+공유스키마→:common · PR2b ✅ [#55](https://github.com/Kimgyuilli/PeakCart/pull/55) user-service peel(발급 owner·blacklist token-hash dual-read·SlackNotificationClient @ConditionalOnProperty) · **사가 클러스터 strangler-1 ✅ [#56](https://github.com/Kimgyuilli/PeakCart/pull/56) 재고 예약/복구 이벤트화(예약 원장 상태머신·all-or-nothing·CAS 복구, ADR-0010 F2·ADR-0012 D3)** · **strangler-2 ✅ [#57](https://github.com/Kimgyuilli/PeakCart/pull/57) 단가 로컬 캐시 CQRS(product.updated 발행·@Version 순서키·원자 upsert stale-skip·getUnitPrice 동기 seam 제거, ADR-0012 ⑤·L-006)** · **strangler-3 ✅ [#58](https://github.com/Kimgyuilli/PeakCart/pull/58) 2-phase 예약 확정(CONFIRMED)/보상 + 결제 게이트(ORD-008·paymentRequestedAt 타임아웃)(ADR-0012 D3/④)** · **strangler-4 ✅ [#61](https://github.com/Kimgyuilli/PeakCart/pull/61) `verifyProductExists` 로컬 캐시화(ProductPort 동기 seam 제거·캐시 미스 ORD-009·order↔product src 결합 금지 가드)(ADR-0010 F2·ADR-0012 ⑤) → Order↔Product production 동기 결합 0** · **Product peel ✅ [#62](https://github.com/Kimgyuilli/PeakCart/pull/62) product-service 모듈 분리(첫 *발행* 서비스: outbox/idempotency/ShedLock 복제·공유 DB poller 소유권 분리 aggregateType allowlist·ProductSecurityConfig)+root 테스트 디커플(ADR-0010/0011/0012/0014, DB 물리분리는 ② 이연)** · **strangler-5 Order↔Payment 동기 결합 제거 ✅ [#63](https://github.com/Kimgyuilli/PeakCart/pull/63) `OrderPort` seam 제거(verifyOrderOwner→payment-로컬 userId·transitionToPaymentRequested→`payment.requested` 이벤트)+reserve→pay/취소 게이트 payment-로컬 복원·@Version·이벤트 역전 영속 marker(ADR-0012 §D4 refine)+가드 `assertNoOrderPaymentSourceCoupling` → Order↔Payment src 동기 결합 0** · **Order peel PR-a ✅ [#64](https://github.com/Kimgyuilli/PeakCart/pull/64) order-service 모듈 분리(order 도메인+16테스트 이관·outbox/idempotency/ShedLock 복제·OrderKafkaConfig producer-owns NewTopic(order.*)·OrderSecurityConfig·data-redis 무조건(ADR-0014 blacklist)·root poller PAYMENT 좁힘·root 통합테스트 payment-observable 디커플·OrderApplicationTests 부팅 스모크, ADR-0010/0011/0012/0014)** · **Payment peel + root 해체 PR-b ✅ [#65](https://github.com/Kimgyuilli/PeekCart/pull/65) payment-service 모듈 분리(마지막 도메인)+root app 해체(global/app/src 삭제·bootJar→aggregator)+order-service Flyway migrator 승계(B5)+product-service NewTopic 소유+global 테스트 rehome(:common 유닛·payment-service 통합)+root 단일 이미지 제거(PR3 이연), ADR-0010/0011/0012/0014) → 5개 서비스 풀 분해 완료(root app 소멸)** · **PR3a ✅ [#66](https://github.com/Kimgyuilli/PeakCart/pull/66) 서비스별 Dockerfile(단일+ARG SERVICE·멀티모듈 COPY·base digest 고정 L-016a)+CI 이미지 매트릭스(images build/smoke·publish main push job 분리·save/load·digest 강제)+image-contract-lint per-service(canonical 5·images/publish matrix 일치·전환기 SUSPENDED 게이트)+smoke 공유스키마 선행 마이그레이션(flyway 이미지). 후속부채: flywayMigrateShared 깨짐(Docker flyway 정본 우회)** · **PR3b ✅ [#67](https://github.com/Kimgyuilli/PeakCart/pull/67) k8s base/overlays per-service 재구성(5서비스 deployment/cm/secret/servicemonitor·비-order initContainer order-service readiness gate·minikube/gke overlay patch×10·gke images[]5·order-service 단일 HPA)+Slack real↔no-op 게이팅(SlackFallbackConfig·notification fail-fast/product·order·payment no-op·presence-based+placeholder 함정 제거)+자격증명 fail-fast(SLACK/TOSS committed secret 제거·smoke 런타임 주입)+mysql-secret 분리(infra→secret dangling)+servicemonitor count==5·image-contract full 5/5·promote-images(D-016)(ADR-0004/0005/0006/0007/0010/0014). 후속: PR3c 관측성+ADR-0015·full lint-digest** · **PR3c ✅ [#68](https://github.com/Kimgyuilli/PeakCart/pull/68) 관측성 per-service 재설계(grafana alert 8 rule by-clause+regex·scrape-absent 5 equality·dashboard $application custom 변수·observability lint 2종 per-service 재작성+CI 재활성+promtool syntax+sweep 가드)+ADR-0015 신규·ADR-0009 Partially Superseded(ADR-0006/0009/0010/0015). negative test 6종 false-green 차단** → **구현 ① PR3 전체 종료(이미지/CI #66·k8s #67·관측성 #68)**) | A2·A4.5 | L-016a, D-016 | ✅ |
| ② | 서비스별 DB 분리 (PR1 ✅ [#69](https://github.com/Kimgyuilli/PeakCart/pull/69) 교차 도메인 FK 6개 드롭(`fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`, 컬럼 유지·ID 참조 대체)·소유 경계 검증(5서비스 자기 테이블만 매핑)·8모듈 그린, ADR-0012 D1 — 물리 분리 선행 저위험 단계 · 계획서 `task-impl2-db-per-service.md` PR1/PR2/PR3 분할) · **PR2 ✅ [#71](https://github.com/Kimgyuilli/PeakCart/pull/71)** Flyway per-service 통합 베이스라인(5× `V1__init_<svc>.sql`·`:common/db/migration` 소멸·flywayMigrateShared 제거)+물리 스키마 분리(1 인스턴스+5 스키마 `peekcart_<svc>`+계정/격리 GRANT)+datasource per-schema(ADR-0007)+outbox allowlist 제거(스키마 분리로 자연 소유권·B8b)+k8s mysql init ConfigMap(.sh·비밀번호 Secret env·literal 금지)·per-svc secret 분화·initContainer 게이트 제거+compose/smoke mysql init 전환+통합테스트 cross-domain 시드 제거·cleanDatabase 스키마 적응형+**ADR-0016 신규**·ADR-0012 Partially Superseded·Layer1 동기화(8모듈 그린, GRANT 최소권한 DROP 미부여) · **다음 PR3** retention 스케줄러(D5·L-008/011) | A3 | L-008/011 | 🔄 |
| ③ | Spring Cloud Gateway | A4 | 보안 묶음 | 🔲 |
| ④ | Choreography Saga | A3 | — | 🔲 |
| ⑤ | CQRS 로컬 캐시 | A3 | L-006 (L-005 선결 완료) | 🔲 |
| ⑥ | Cursor 페이지네이션 | — | — | 🔲 |
| — | D-002 격리 재측정 | — | D-002 (분리 후) | 🔄 추적 |

---

## 진입 전 부채 해소 (버킷 1) — ✅ 완료 (아카이브)

> 9개 PR 전부 완료(Tier A · D-012~D-015 · D-017~D-019, PR #37~#43). 상세 PR 테이블·시퀀스는 `docs/progress/phase4-prep-debt-roadmap.md §2`(SSOT)에 보존.

---

## 개발 부채 (Tech Debt)

> 해결 완료(D-001~D-012) 상세는 아카이브(`TASKS-archive-phase1-3.md §개발 부채`) 보존. 여기서는 **live + 신규**만 추적.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-002 | Performance | 캐시 TPS ×2.31(목표 ×3 미달). 1차 병목 CPU 확증, 2차 후보(MySQL 풀 / Redis 락 contention) 미분리 | Phase 4 Order Service 분리 후 격리 재측정 | 🔄 추적 |
| D-012 | CI / Deliverable | CI 가 품질 게이트가 아니다 — PR Docker build·smoke 부재, branch protection 미설정, NS 누출 lint 부재 | 버킷 1 (L-014/015/017) | ✅ 완료 |
| D-013 | Resilience | 발행 경로 resilience 갭 — DLQ 발행 미확정(유실), outbox `.get()` 타임아웃 부재와 polling cycle 상한 미정의(워커 잠식) | 버킷 1 (L-010/012) | ✅ 완료 |
| D-014 | Observability | 선결 측정 표면 부재 — 캐시 적중률 / outbox 파이프라인 메트릭 | 버킷 1 선택 (L-005/009) | ✅ 완료 |
| D-015 | Deploy/CI | CI push image repo(`peekcart`) ↔ K8s base/GKE 참조(`peakcart`) 계약 불일치 → GHCR→AR 복사·base 배포 실패 가능 | 버킷 1 (도식검토) | ✅ 완료 |
| D-017 | Observability | Grafana alert rule 존재하나 Slack contact point/provisioning 부재 → "alert→Slack" 경로 미완성. 범위 정정(②)으로 봉합, delivery 는 L-004 이관 | 버킷 1 (도식검토) | ✅ 완료 |
| D-018 | Docs | `loadtest/reports/2026-04-29/REPORT.md` Redis PVC 1Gi ↔ 현 매니페스트 512Mi 드리프트 | 버킷 1 (도식검토) | ✅ 완료 |
| D-019 | Testing | `OutboxKafkaIntegrationTest.orderCancelled_e2e` CI 간헐 실패 → **(a) 타이밍 flake 확정**(프로덕션 회귀 아님). D-013 producer 타임아웃 타이트화로 콜드 스타트 첫 발행이 실패하면 단발 poll 테스트는 재폴링이 없어 PENDING 고착 → `await` 타임아웃. 프로덕션은 스케줄러 재발행으로 자가치유. 테스트만 `pollUntilPublished` 로 수정(하드닝 유지) | 버킷 1 마무리분 (D-013 여파) | ✅ 완료 |

### 해결 완료 (아카이브 참조)

D-001(✅), D-005(✅), D-006(✅), D-007(✅), D-008(✅), D-009(✅), D-010(✅), D-011(✅), D-012(✅) · D-003(Won't Fix) · D-004(운영지식) — 상세: `docs/progress/TASKS-archive-phase1-3.md §개발 부채`.

---

## Phase 4 — MSA 분리 (예정)

> 로드맵 §3(버킷 2) 이관 부채를 각 Phase 4 task 에 편입. 착수 시 D- 승격 또는 task 항목 흡수.

| 순서 | 작업 | 편입 부채 |
|---|---|---|
| 1 | Gradle 멀티모듈 전환 | L-016(a) digest 고정, L-020(2) consumer group 라벨, D-002 격리 재측정 |
| 2 | 서비스별 DB 분리 | L-008/L-011 retention(보존기간=멱등성 창 상한 결정) |
| 3 | Spring Cloud Gateway | **보안 묶음** L-001/L-002/L-003/L-019 (RS256 전환 + KMS/Vault + Reuse Detection + 인증 관측성) |
| 4 | Choreography Saga | — |
| 5 | CQRS 로컬 캐시 | L-006 Redis fallback (L-005 선결) |
| 6 | Cursor 페이지네이션 | — |
| — | 운영 관측성 | L-004 Slack 채널 재설계 |

상세: `docs/07-roadmap-portfolio.md §16` · 로드맵 §3.

---

## 보류 (측정 후 결정)

> 게이트: **17편 후속 부하 세션** 실측. 나오면 모놀리스 단계 선제 승격, 아니면 Phase 4 분리 시 자연해소(L-007)/필수화(L-013).

| ID | 영역 | 측정 게이트 |
|---|---|---|
| L-007 | 주문 *생성* 경로 "락 ⊃ 트랜잭션" 불변식 + 재고 차감 retry 정책 미정 | 동일-상품 경합 시 재고 차감 `PRD-004`/`OptimisticLockingFailureException` 응답률 유의 |
| L-013 | 주문 *상태 전이* 동시성(`Order @Version` 부재) | payment.completed/failed ↔ 타임아웃 동시 적용 시 상태 모순 실측 |

상세·승격 시 동반 결정: 로드맵 §4.
