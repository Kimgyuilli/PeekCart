# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| Phase 4 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase4.md` |
| 완료된 D- 행 | `docs/progress/TASKS-done.md` |
| Phase 4 설계·실행 로드맵 (ADR 시퀀싱·구현 순서) | `docs/progress/phase4-design-roadmap.md` |
| 진입 전 부채 해소 로드맵 (버킷 1 완결, 버킷 2/3 이관·게이트) | `docs/progress/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` · `PHASE4.md` · `PHASE5.md` |

---

## 현재 단계: Phase 5 — 수요 기반 (로드맵 없음, 2026-09-22~, [#134](https://github.com/Kimgyuilli/PeakCart/pull/134))

> **Phase 1~4 로 사전 로드맵은 끝났다.** Phase 5 는 순서표가 없다. 필요하다고 느낀
> 시점에 항목을 추가하고 그때 착수한다.
>
> **추적 축은 아래 §개발 부채 / 작업 표 하나다.** 기존 D- 번호를 그대로 이어 쓴다
> (다음 번호 = 이 표와 `docs/progress/TASKS-done.md` 의 최대 번호 + 1). "부채 해소"와 "새로 하고 싶은 것"을 표에서 구분하지 않는 것은
> 의도다 — 수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이고,
> 표를 나누면 어느 쪽에 적을지부터 정해야 해서 추가 비용이 생긴다.
>
> **항목 추가 규칙**: `영역` · `요약`(왜 필요한지가 요약에 들어간다) · `묶음`(발견 맥락 —
> 세션/PR/문서) · `상태`. 로드맵이 없으므로 **동기를 요약에 적어 두지 않으면 나중에
> 왜 적었는지 복원할 수 없다.** 착수 시 `/plan`, 머지 시 `/done`.
>
> **단계 종결 조건 없음.** Phase 4 처럼 순서표 소진으로 닫히지 않는다. 열린 표가 곧 현황이다.
>
> 열린 항목은 아래 표에 있다. 완료된 행은 `docs/progress/TASKS-done.md` 로 옮긴다.

지난 단계: Phase 4 는 `docs/progress/TASKS-archive-phase4.md` ·
Phase 1~3 은 `docs/progress/TASKS-archive-phase1-3.md`.

---

## 개발 부채 / 작업 (Tech Debt & Backlog)

> 여기서는 **열린 행**만 추적한다. 완료된 행은 `docs/progress/TASKS-done.md`(D-001~D-012 상세는 `TASKS-archive-phase1-3.md §개발 부채`)에 있다.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-059 | Payment / PG | **환불 재시도가 Toss `409 IDEMPOTENT_REQUEST_PROCESSING` 을 영구 실패로 닫는다** — Toss 는 같은 멱등키의 첫 요청이 처리 중일 때 재요청에 409 `IDEMPOTENT_REQUEST_PROCESSING` 을 돌려준다([멱등키 문서](https://docs.tosspayments.com/reference/using-api/idempotency-key)). `TossPaymentClient.toOutcome` 은 `ALREADY_CANCELED_PAYMENT` 외 4xx 를 전부 `PERMANENT_FAILURE` 로 분류하고, `RefundExecutor.execute` 는 같은 키로 최대 3회 재시도하므로 "1회차 타임아웃(`pg-timeout: 10s`) → 2회차 409" 에서 원장이 `FAILED`(종결, 재시도·reconciliation 없음)로 닫힌다. 첫 취소가 이후 성공하면 "환불됐는데 원장은 FAILED" — ADR-0018 D5 가 `ALREADY_CANCELED` 에서 피하려던 모양. 일회용 단위 테스트(MockRestServiceServer, 2026-10-10)로 확인: 409+`IDEMPOTENT_REQUEST_PROCESSING` → `PERMANENT_FAILURE`, 타임아웃→409 → `FAILED`(attempts=2). 대조 409+`ALREADY_CANCELED_PAYMENT` → 조회 경유 `SUCCEEDED`. 실 Toss 에서 10초 넘게 처리 중인 빈도는 미확인. 승인 경로는 1회 호출 + 로컬 fence(`PAY-013`)라 해당 없음 | 블로그 23편 작성 중 발견(2026-10-10) | 🔲 |
| D-060 | Payment / Saga | **결제 승인 ↔ 예약 lease 회수 사이에 fence 가 없다 (④-a R-1 재등재)** — `PaymentApprovalService.beginApproval` 은 `ensureConfirmable(leaseApprovalMargin)`(`app.payment.lease-approval-margin: 2m`)로 남은 lease 를 진입 시점에만 검사한다. 검사 후 PG 승인 사이에 lease 가 만료되면 Order 만료 취소 → Product release · 재고 재판매 → 승인 성립이 가능하다. 마진은 창을 줄일 뿐 fence 가 아니다(`PaymentApprovalProperties` 주석, `PLAN-BLINDSPOTS.md` B12). ④-a(#84)가 "ADR 선행 별도 PR"로 남겼으나 이후 추적되지 않았고, D-020(#107)도 마진 검사를 T1 에 그대로 옮겼다(ADR-0023 미언급). 근본 해결 후보 = 예약을 승인 전용 상태로 CAS 전이해 release/sweeper 가 회수하지 못하게 하는 것(saga 프로토콜 변경 → ADR 선행). 참고(코드 경로 추론, 미검증): ADR-0018 이후 이 경로의 끝은 Product `PAID_BUT_UNRESERVED` · Order `PAID_BUT_CANCELLED` 감지 → 자동 환불로 수렴할 것으로 보인다. saga-contract 매트릭스에 승인↔lease 경합 행 없음 | 블로그 23편 작성 중 재발견(2026-10-10) · 출처 ④-a 계획 §2.6 R-1 | 🔲 |
| D-061 | Testing / Saga | **환불 결과 3종 분기가 cross-service E2E 로 관측되지 않는다** — `saga_e2e.py` 시나리오 C 는 요청 토픽 → fence → dispatcher → stub → `SUCCEEDED` → `payment.refunded` outbox `PUBLISHED` 까지만 본다. 결과 3종(`SUCCEEDED`/`FAILED`/`UNRESOLVED`) × 회신 소비자 3곳(Order 원장 · Product 종결 컬럼 · Notification)의 종착과 stub ledger 순서 계약(transient `POST×3` · `ALREADY` `POST→GET` · reconcile `GET×1,POST×0`)은 JVM 통합테스트(매트릭스 9행)만 고정한다. 그 결과 실행 순서 계약의 `LINGERING_SCENARIOS` 는 `[]` 로 지킬 대상이 없다(`scripts/e2e/saga_e2e.py:969-972`). ④ 종결 미충족 7 · 계획 §9-10/12 이후 추적되지 않음 | 블로그 25편(로드맵 24편) 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 7 | 🔲 |
| D-062 | Testing / DLQ | **DLQ 재소비 시 `attempt_count` 증가가 E2E 로 검증되지 않는다** — 시나리오 D 는 원장 1행 + 식별자 6컬럼 non-null + `attempt_count=1` 까지만 본다(`scripts/e2e/saga_e2e.py:719,747`). 같은 DLT 레코드 재소비(DLQ consumer group offset rewind)에서 새 행 없이 `attempt_count=2` 가 되는지는 미검증 — 재발행은 offset 이 달라 다른 좌표가 되므로(`V6__dead_letter_records.sql` 6컬럼 UNIQUE) rewind 가 필요하고, 실행 중 group 정지가 다른 시나리오와 간섭해 미뤘다(계획 §9-13) | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 7 | 🔲 |
| D-063 | Observability | **saga · DLQ alert 의 실제 발화가 검증되지 않는다** — ADR-0015 가 정적 lint(PromQL syntax · 라벨 invariant · 식 정본 고정) 범위를 규정해, `saga.compensation.backlog` · `dlq.backlog` alert 와 이후 Outbox FAILED alert 는 실제 series 로 평가된 적이 없다. 실제 평가는 Prometheus/Grafana 기동 + fixture series 필요. ④-d-1 · ④-d-2b 미충족, D-030 미충족("실제 스택 alert 발화 시험(GKE 세션 필요)")에 남았으나 TASKS 에서 추적되지 않음 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-1 미충족 1 · PHASE5 D-030 | 🔲 |
| D-064 | Payment / PG | **Toss 실호출 경로(승인 · 취소 · 조회)가 실제 PG 에서 검증되지 않는다** — E2E 는 PG stub(`scripts/e2e/pg-stub/`)으로 계약 형태만 흉내내고, JVM 테스트는 `MockRestServiceServer` 로 모사한다. 실 API 스펙 변경 · 실호출 reconciliation · 웹훅 서명의 실 PG 관통은 미검증. D-020(완료)의 게이트로 "외부 PG 장애 주입 환경"이 언급됐으나 별도 추적 행이 없음. D-059(409 처리)의 실제 응답 형태 확인과도 묶인다 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 5 · 계획 §9-4 | 🔲 |
| D-065 | DLQ / Replay | **DLQ replay 가 개방된 적이 없고 개방 전제 검증 다섯 가지가 남아 있다** — kill-switch `APP_DEAD_LETTER_REPLAY_ENABLED` 는 4개 ConfigMap 모두 `"false"` 이며 계획 §9 4단계(닫힌 채 배포 → P22 증적 → 게이트웨이 관리자 라우트 → 개방 → 리허설)는 미실행. 미검증·미수행: ① 게이트웨이 경유 진입점 실제 스택 관통(ADMIN 토큰 → `/api/v1/admin/deadletter/*` → `ROLE_ADMIN`, ROLE_USER 403 — V-40, 라우트 계약과 엔드포인트 가드를 각각만 관측) ② `scripts/replay-drain-preflight.sh` 의 수집 계층(`kubectl exec` 기반 DB·Kafka 조회, 판정 계층만 fixture 관통) ③ `record_kind`·`root_record_id` `NOT NULL` contract 마이그레이션(계획 §10 R1, 그때까지 N16 은 코드 경로로만 강제) ④ 원장 `original_timestamp` NULL 비율(P22, 운영 표본 0 → 미측정, 처분은 ADR-0022 §D6 분기표) ⑤ GKE 실적용(계획 §10 R2 — `ALTER_CONFIGS` 권한·실제 좌표 읽기). 개방 세션에서 함께 처리될 묶음 | 블로그 25편 작성 중 재확인(2026-10-12) · 출처 PHASE4 ④-c-2b-4a 미충족 2·4 · ④-c-2b-4b 미충족 · 계획 §9·§10 R1/R2 | 🔲 |
| D-066 | Product / Cache | **상품 목록 API 에 정렬 계약이 없고, 캐시 키가 정렬을 포함하지 않는다** — `ProductController.getProducts` 의 `@ParameterObject @PageableDefault(size = 20) Pageable` 에 `sort` 가 없어 `ORDER BY` 없는 offset 질의가 나간다(구현 ⑥ 이전 주문 목록과 같은 모양 — 응답 순서는 MySQL 실행계획이 정하고, 동률·플랜 변경 시 페이지 경계 누락·중복 가능). `ProductCacheService.getProductList` 의 주석 `"Sort 미지원 — Controller에서 고정 정렬만 사용 (@PageableDefault)"` 과 달리 고정 정렬이 없다. 또 `@Cacheable` 키가 `'list:' + categoryId + ':' + pageNumber + ':' + pageSize` 라 클라이언트가 `?sort=` 를 넣으면 그 정렬 결과가 **공용 키에 적재돼 TTL(10m) 동안 다른 요청에 서빙**된다 — **양방향 실측**(2026-10-13, 블로그 작성 중 일회성 `@SpringBootTest`+MockMvc 탐침, 커밋 안 함): 캐시 비운 뒤 `?size=10&sort=price,desc` → 이어서 `?size=10` 이 가격 내림차순으로 응답했고, 역순(정렬 없음 먼저 → `sort=price,desc`)이면 정렬 요청이 정렬 안 된 결과를 받았다. 또 `?sort=noSuchField` 는 `PropertyReferenceException` 이 포괄 핸들러로 가서 **500 SYS-001** 이다(D-067 과 같은 뿌리). 구현 ⑥ 이 "캐시 키가 페이지 번호에 결합" 사유로 상품 목록을 범위 밖에 뒀고(⑥ 미충족 3), 정렬 부재는 계획 V6 의 "같은 모양" 외에 추적되지 않았다 | 블로그 27편(로드맵 26편) 작성 중 발견(2026-10-13) · 출처 PHASE4 구현 ⑥ 미충족 3 · 계획 `task-impl6-cursor-pagination.md` V6 | 🔲 |
| D-067 | Common / Error | **`GlobalExceptionHandler` 에 `MethodArgumentTypeMismatchException` · `HandlerMethodValidationException` 핸들러가 없다** — 포괄 `@ExceptionHandler(Exception.class)` 가 `DefaultHandlerExceptionResolver` 보다 먼저 잡아 **500 SYS-001** 이 된다. 구현 ⑥ 이 V11(계획 1R)로 확인하고 주문 목록만 `String` 수동 파싱으로 우회했으며 "부채 후보(D-021 후보)"로 남겼으나 등록되지 않았다(D-021 은 다른 항목이 사용). `@PathVariable Long id` 를 쓰는 컨트롤러 5곳(`ProductController` · `AdminProductController` · `PaymentController` · `OrderController` · `CartController`)에서 `/.../abc` 류 요청이 500 이 될 수 있다(코드 경로 추론, 미실측). `common` 모듈이라 5서비스 영향 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-13) · 출처 PHASE4 구현 ⑥ "500 이 될 뻔한 두 경로" · 미충족 2 | 🔲 |
| D-068 | Testing / Infra | **MySQL 이미지가 부동 태그 `mysql:8.0` 이다** — `SharedContainers`(`common/src/testFixtures`) · `docker-compose.yml:3` · `k8s/base/infra/mysql/mysql.yml:48`. 패치 핀은 `OrderCursorQueryPlanTest` 의 `mysql:8.0.46` 하나뿐이다. 실행계획(`used_key_parts`·filesort) 같은 옵티마이저 버전 종속 계약과 `DATETIME(6)` 경계·DDL 문법 실측이 이미지 갱신 시 조용히 바뀔 수 있다. 구현 ⑥ 이 "부채 후보(D-022 후보)"로 남겼으나 등록되지 않았다(D-022 는 다른 항목이 사용) | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-13) · 출처 PHASE4 구현 ⑥ 미충족 7 · 계획 §5-6 | 🔲 |
| D-069 | Product / Cache | **Redis 장애 시 cache stampede · DB 커넥션 풀 포화 완화 기구가 없다** — `ResilientCacheErrorHandler` 가 get/put 실패를 삼켜 캐시 미스로 흘리므로, Redis 장애 중에는 같은 상품 조회가 매번 DB 를 친다(`ProductCacheFallbackIntegrationTest` V5: 5회 조회 → `findById` 5회, 정상 1회). 동시 요청에서는 전부 DB 로 몰려 Hikari 풀을 압박할 수 있으나 bulkhead · `@Cacheable(sync = true)` · rate limit 어느 것도 없다. 구현 ⑤ 가 "허용 동시성/SLO 를 먼저 정해야 설계가 결정된다"며 인지·문서화·감시까지만 하고 승격(계획 M7)했으나 TASKS 에서 추적되지 않았다. 동시 부하에서의 실측도 없음 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 3 · 계획 `task-impl5-cqrs-cache-fallback.md` §6 M7 | 🔲 |
| D-070 | Observability / Cache | **`cache_fallback_total` 에 alert 규칙이 없다** — 메트릭(`cache.fallback{cache, operation}`)은 노출되지만 k8s/monitoring 설정에 이를 쓰는 alert 규칙이 0건이고, `docs/runbooks/redis-cache-fallback.md` 의 수동 감시 계약(`rate(cache_fallback_total{operation="get"}[5m]) > 0` 10분 지속 → Redis 장애, `increase(cache_fallback_total{operation="evict"}[5m]) >= 1` → stale 창 즉시 확인)만 있다. evict 실패는 TTL(상세 30m / 목록 10m) 동안 stale 가격·판매중단 상품을 노출하므로 사람이 보지 않으면 모른다. 구현 ⑤ 미충족 2 가 "④ 미충족 #3 과 동일 게이트"로 남겼으나 D-063 은 saga · DLQ alert 만 다룬다 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 2 · 계획 P7(e) | 🔲 |
| D-071 | Order / Cart | **장바구니 조회에 상품 정보(이름·가격·상태) 조합이 없다** — `CartItemDto=(id, productId, quantity)` 이고 `CartQueryService.getCart` 는 조합하지 않는다. ADR-0012 §D2 가 `product.updated` 7 필수필드를 "장바구니 조회를 담을 수 있도록" 정했고 `05-data-design.md:121` 도 "아직 없습니다"라고 적지만 작업으로 추적되지 않는다. 선행: `product_price_cache` 에 `name`/`status` 컬럼 추가 + `ProductPriceCacheConsumer` 확장 + DTO/응답 확장 + "캐시 미수신 상품의 표시 정책" 결정. 상품 목록 커서 전환(D-066 과 같은 캐시 표면)과 묶일 수 있다 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 1 · 계획 §6 M1 · ADR-0012 Update Log 2026-08-30 | 🔲 |
