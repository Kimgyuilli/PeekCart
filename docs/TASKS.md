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
| D-061 | Testing / Saga | **환불 결과 3종 분기가 cross-service E2E 로 관측되지 않는다** — `saga_e2e.py` 시나리오 C 는 요청 토픽 → fence → dispatcher → stub → `SUCCEEDED` → `payment.refunded` outbox `PUBLISHED` 까지만 본다. 결과 3종(`SUCCEEDED`/`FAILED`/`UNRESOLVED`) × 회신 소비자 3곳(Order 원장 · Product 종결 컬럼 · Notification)의 종착과 stub ledger 순서 계약(transient `POST×3` · `ALREADY` `POST→GET` · reconcile `GET×1,POST×0`)은 JVM 통합테스트(매트릭스 9행)만 고정한다. 그 결과 실행 순서 계약의 `LINGERING_SCENARIOS` 는 `[]` 로 지킬 대상이 없다(`scripts/e2e/saga_e2e.py:969-972`). ④ 종결 미충족 7 · 계획 §9-10/12 이후 추적되지 않음 | 블로그 25편(로드맵 24편) 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 7 | 🔲 |
| D-062 | Testing / DLQ | **DLQ 재소비 시 `attempt_count` 증가가 E2E 로 검증되지 않는다** — 시나리오 D 는 원장 1행 + 식별자 6컬럼 non-null + `attempt_count=1` 까지만 본다(`scripts/e2e/saga_e2e.py:719,747`). 같은 DLT 레코드 재소비(DLQ consumer group offset rewind)에서 새 행 없이 `attempt_count=2` 가 되는지는 미검증 — 재발행은 offset 이 달라 다른 좌표가 되므로(`V6__dead_letter_records.sql` 6컬럼 UNIQUE) rewind 가 필요하고, 실행 중 group 정지가 다른 시나리오와 간섭해 미뤘다(계획 §9-13) | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 7 | 🔲 |
| D-063 | Observability | **saga · DLQ alert 의 실제 발화가 검증되지 않는다** — ADR-0015 가 정적 lint(PromQL syntax · 라벨 invariant · 식 정본 고정) 범위를 규정해, `saga.compensation.backlog` · `dlq.backlog` alert 와 이후 Outbox FAILED alert 는 실제 series 로 평가된 적이 없다. 실제 평가는 Prometheus/Grafana 기동 + fixture series 필요. ④-d-1 · ④-d-2b 미충족, D-030 미충족("실제 스택 alert 발화 시험(GKE 세션 필요)")에 남았으나 TASKS 에서 추적되지 않음 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-1 미충족 1 · PHASE5 D-030 | 🔲 |
| D-064 | Payment / PG | **Toss 실호출 경로(승인 · 취소 · 조회)가 실제 PG 에서 검증되지 않는다** — E2E 는 PG stub(`scripts/e2e/pg-stub/`)으로 계약 형태만 흉내내고, JVM 테스트는 `MockRestServiceServer` 로 모사한다. 실 API 스펙 변경 · 실호출 reconciliation · 웹훅 서명의 실 PG 관통은 미검증. D-020(완료)의 게이트로 "외부 PG 장애 주입 환경"이 언급됐으나 별도 추적 행이 없음. D-059(409 처리)의 실제 응답 형태 확인과도 묶인다 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 5 · 계획 §9-4 | 🔲 |
| D-065 | DLQ / Replay | **DLQ replay 가 개방된 적이 없고 개방 전제 검증 다섯 가지가 남아 있다** — kill-switch `APP_DEAD_LETTER_REPLAY_ENABLED` 는 4개 ConfigMap 모두 `"false"` 이며 계획 §9 4단계(닫힌 채 배포 → P22 증적 → 게이트웨이 관리자 라우트 → 개방 → 리허설)는 미실행. 미검증·미수행: ① 게이트웨이 경유 진입점 실제 스택 관통(ADMIN 토큰 → `/api/v1/admin/deadletter/*` → `ROLE_ADMIN`, ROLE_USER 403 — V-40, 라우트 계약과 엔드포인트 가드를 각각만 관측) ② `scripts/replay-drain-preflight.sh` 의 수집 계층(`kubectl exec` 기반 DB·Kafka 조회, 판정 계층만 fixture 관통) ③ `record_kind`·`root_record_id` `NOT NULL` contract 마이그레이션(계획 §10 R1, 그때까지 N16 은 코드 경로로만 강제) ④ 원장 `original_timestamp` NULL 비율(P22, 운영 표본 0 → 미측정, 처분은 ADR-0022 §D6 분기표) ⑤ GKE 실적용(계획 §10 R2 — `ALTER_CONFIGS` 권한·실제 좌표 읽기). 개방 세션에서 함께 처리될 묶음 | 블로그 25편 작성 중 재확인(2026-10-12) · 출처 PHASE4 ④-c-2b-4a 미충족 2·4 · ④-c-2b-4b 미충족 · 계획 §9·§10 R1/R2 | 🔲 |
| D-066 | Product / Cache | **상품 목록 API 에 정렬 계약이 없고, 캐시 키가 정렬을 포함하지 않는다** — `ProductController.getProducts` 의 `@ParameterObject @PageableDefault(size = 20) Pageable` 에 `sort` 가 없어 `ORDER BY` 없는 offset 질의가 나간다(구현 ⑥ 이전 주문 목록과 같은 모양 — 응답 순서는 MySQL 실행계획이 정하고, 동률·플랜 변경 시 페이지 경계 누락·중복 가능). `ProductCacheService.getProductList` 의 주석 `"Sort 미지원 — Controller에서 고정 정렬만 사용 (@PageableDefault)"` 과 달리 고정 정렬이 없다. 또 `@Cacheable` 키가 `'list:' + categoryId + ':' + pageNumber + ':' + pageSize` 라 클라이언트가 `?sort=` 를 넣으면 그 정렬 결과가 **공용 키에 적재돼 TTL(10m) 동안 다른 요청에 서빙**된다 — **양방향 실측**(2026-10-13, 블로그 작성 중 일회성 `@SpringBootTest`+MockMvc 탐침, 커밋 안 함): 캐시 비운 뒤 `?size=10&sort=price,desc` → 이어서 `?size=10` 이 가격 내림차순으로 응답했고, 역순(정렬 없음 먼저 → `sort=price,desc`)이면 정렬 요청이 정렬 안 된 결과를 받았다. 또 `?sort=noSuchField` 는 `PropertyReferenceException` 이 포괄 핸들러로 가서 **500 SYS-001** 이다(D-067 과 같은 뿌리). 구현 ⑥ 이 "캐시 키가 페이지 번호에 결합" 사유로 상품 목록을 범위 밖에 뒀고(⑥ 미충족 3), 정렬 부재는 계획 V6 의 "같은 모양" 외에 추적되지 않았다 | 블로그 27편(로드맵 26편) 작성 중 발견(2026-10-13) · 출처 PHASE4 구현 ⑥ 미충족 3 · 계획 `task-impl6-cursor-pagination.md` V6 | 🔲 |
| D-067 | Common / Error | **`GlobalExceptionHandler` 에 `MethodArgumentTypeMismatchException` · `HandlerMethodValidationException` 핸들러가 없다** — 포괄 `@ExceptionHandler(Exception.class)` 가 `DefaultHandlerExceptionResolver` 보다 먼저 잡아 **500 SYS-001** 이 된다. 구현 ⑥ 이 V11(계획 1R)로 확인하고 주문 목록만 `String` 수동 파싱으로 우회했으며 "부채 후보(D-021 후보)"로 남겼으나 등록되지 않았다(D-021 은 다른 항목이 사용). `@PathVariable Long id` 를 쓰는 컨트롤러 5곳(`ProductController` · `AdminProductController` · `PaymentController` · `OrderController` · `CartController`)에서 `/.../abc` 류 요청이 500 이 될 수 있다(코드 경로 추론, 미실측). `common` 모듈이라 5서비스 영향 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-13) · 출처 PHASE4 구현 ⑥ "500 이 될 뻔한 두 경로" · 미충족 2 | 🔲 |
| D-069 | Product / Cache | **Redis 장애 시 cache stampede · DB 커넥션 풀 포화 완화 기구가 없다** — `ResilientCacheErrorHandler` 가 get/put 실패를 삼켜 캐시 미스로 흘리므로, Redis 장애 중에는 같은 상품 조회가 매번 DB 를 친다(`ProductCacheFallbackIntegrationTest` V5: 5회 조회 → `findById` 5회, 정상 1회). 동시 요청에서는 전부 DB 로 몰려 Hikari 풀을 압박할 수 있으나 bulkhead · `@Cacheable(sync = true)` · rate limit 어느 것도 없다. 구현 ⑤ 가 "허용 동시성/SLO 를 먼저 정해야 설계가 결정된다"며 인지·문서화·감시까지만 하고 승격(계획 M7)했으나 TASKS 에서 추적되지 않았다. 동시 부하에서의 실측도 없음 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 3 · 계획 `task-impl5-cqrs-cache-fallback.md` §6 M7 | 🔲 |
| D-070 | Observability / Cache | **`cache_fallback_total` 에 alert 규칙이 없다** — 메트릭(`cache.fallback{cache, operation}`)은 노출되지만 k8s/monitoring 설정에 이를 쓰는 alert 규칙이 0건이고, `docs/runbooks/redis-cache-fallback.md` 의 수동 감시 계약(`rate(cache_fallback_total{operation="get"}[5m]) > 0` 10분 지속 → Redis 장애, `increase(cache_fallback_total{operation="evict"}[5m]) >= 1` → stale 창 즉시 확인)만 있다. evict 실패는 TTL(상세 30m / 목록 10m) 동안 stale 가격·판매중단 상품을 노출하므로 사람이 보지 않으면 모른다. 구현 ⑤ 미충족 2 가 "④ 미충족 #3 과 동일 게이트"로 남겼으나 D-063 은 saga · DLQ alert 만 다룬다 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 2 · 계획 P7(e) | 🔲 |
| D-071 | Order / Cart | **장바구니 조회에 상품 정보(이름·가격·상태) 조합이 없다** — `CartItemDto=(id, productId, quantity)` 이고 `CartQueryService.getCart` 는 조합하지 않는다. ADR-0012 §D2 가 `product.updated` 7 필수필드를 "장바구니 조회를 담을 수 있도록" 정했고 `05-data-design.md:121` 도 "아직 없습니다"라고 적지만 작업으로 추적되지 않는다. 선행: `product_price_cache` 에 `name`/`status` 컬럼 추가 + `ProductPriceCacheConsumer` 확장 + DTO/응답 확장 + "캐시 미수신 상품의 표시 정책" 결정. 상품 목록 커서 전환(D-066 과 같은 캐시 표면)과 묶일 수 있다 | 블로그 27편(로드맵 26편) 작성 중 재확인(2026-10-14) · 출처 PHASE4 구현 ⑤ 미충족 1 · 계획 §6 M1 · ADR-0012 Update Log 2026-08-30 | 🔲 |
| D-072 | Product / DB | **캐시 적중 상품 조회도 MySQL 에 명령 5개를 보낸다** — `ProductQueryService` 의 클래스 레벨 `@Transactional(readOnly = true)` 가 캐시 호출(`ProductCacheService`)을 감싸므로, 캐시가 전부 적중해도 트랜잭션 시작 시 물리 커넥션을 잡고 `SET SESSION TRANSACTION READ ONLY` → `SET autocommit=0` → `COMMIT` → `SET autocommit=1` → `SET SESSION TRANSACTION READ WRITE` 를 왕복한다(MySQL 8.0.46 · Hibernate 6.6.44 · Connector/J 9.6.0, general_log 실측. 클래스 레벨 선언 제거 변이에서 0개). ADR-0026 Context · d002a 증적 정정 · `ProductDetailQueryCostIntegrationTest` javadoc 의 "쿼리가 0이면 물리 커넥션을 잡지 않는다" 는 틀렸고, 그 계약 테스트는 Hibernate `Statistics`(PreparedStatement 수)만 세서 이 왕복을 보지 못한다. ADR-0027 '모르는 채 결정한 것 3'(캐시 miss 0 인 list ON 이 MySQL CPU 한도에 반응 — 원본 `cpu.stat` 차분상 500m 조건 캐시 ON 에서도 MySQL ≈506m 사용·스로틀, 2000m 에서 1.2코어)의 유력 원인이나 클러스터에서 인과는 미확인. 선택지: ① 캐시 경로를 트랜잭션 밖으로(조회 서비스 메서드 단위 선언 — 변이에서 적중 시 0개. 단 `ProductCacheService` 도 클래스 레벨 `readOnly` 이고 적중 시 트랜잭션을 안 여는 것은 `CacheInterceptor` 가 `TransactionInterceptor` 바깥에 있기 때문인데, 두 advisor order 가 모두 `Integer.MAX_VALUE`(미지정)라 Spring 이 순서를 보장하지 않는다 → `@EnableCaching(order = …)` 명시 필요, 2026-10-16 프록시 advisor 목록으로 확인) · ② `hibernate.connection.provider_disables_autocommit=true` + Hikari `auto-commit=false`(로컬 실측 5 → 3개, `SET autocommit` 2개 제거. 전 커넥션 기본 상태 변경이라 트랜잭션 밖 JDBC 경로 영향 미확인) · ③ 계약 테스트를 서버측 계수(general_log/`Com_*`)로 보강 | 블로그 28편(로드맵 27편) 작성 중 발견(2026-10-15, 글 작성 중 실험 E1) · 출처 ADR-0026 Context · ADR-0027 Consequences 3 · evidence `d026-d002a-read-ceiling-20260920.md` §미해결 2 | 🔲 |
| D-075 | Saga / Outbox | **outbox 발행 처리율 천장이 추적되지 않는다** — D-002 측정(#120)이 비동기 예약 사가의 천장을 자원이 아니라 **폴링 설정**으로 지목했다: `app.outbox.polling.delay` 기본 5s × `batch-size: 100` = 이론 상한 20건/초(실측 14.3~16.3, MySQL CPU 4배에 +14%). 완화(500ms/500건) 시 77.3건/초로 오르나 그때도 어느 Pod 도 CPU 한도에 닿지 않는 **새 천장**이 남았고, 이벤트당 ~13ms 가 순차 동기 발행(블로킹 `.get()`, `publish-timeout 6s`)과 정합적이라는 관찰만 있다(미분리). 현재 order · product · payment 3서비스 모두 `batch-size: 100` · delay 기본 5s 그대로이고, 2분 부하가 남긴 사가를 비우는 데 9.2분(552초)이 걸린 기록이 있다. 처분(설정 상향 / 비동기 배치 발행 / 수용) 미결 | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 `evidence/d002bc-gke-20260917.md` §② · PHASE4 "D-002 종결" | ⏸ 보류 — 2026-10-17 사용자 결정. 목표 TPS·최대 backlog 수용 기준 미정, 구현·실험 미수행 |
| D-076 | Kafka / Retry | **product 외 4개 error handler 의 재시도에 jitter 가 없다** — ADR-0025 D2 는 jitter 를 "재고 전용이 아니라 공용 error handler 의 성질"로 정했으나 `JitteredSequenceBackOff(0.5, 1s, 5s, 30s)` 는 `ProductKafkaConfig` 에만 물렸다. `OrderKafkaConfig` · `PaymentKafkaConfig` · `NotificationKafkaConfig` · `DeadLetterKafkaConfig`(`peekcart-common-messaging`)는 여전히 `FixedSequenceBackOff(1_000, 5_000, 30_000)` 이라, 동시에 실패한 소비자가 같은 순간에 함께 재시도하는 lockstep 을 안고 있다(D-025 미충족 "나머지 4개 서비스의 같은 배선은 범위 밖") | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 PHASE4 D-025 미충족 · ADR-0025 D2 | 🔲 |
| D-078 | Performance / Measurement | **D-002 계열 측정 잔여가 추적되지 않는다** — ADR-0027 이 "모르는 채 결정"으로 적고 TASKS 에 행이 없는 항목: ① 읽기 경로 list 의 MySQL CPU 배속 미확정(되돌림 +11.5% 게이트 실패, 블록 B 산포 44.5%, 블록 워밍업에 list 경로 미포함) ② MySQL 2000m 에서 남은 새 천장(detail OFF 904 rps, MySQL 스로틀 0.1s) 정체 미분리 ③ `product`(단수) 캐시 미측정(하네스가 `productStock`·`products` 만 분해) ④ `requests.cpu` 처분 근거인 노드 레벨 CPU 경합 미측정(ADR-0027 Alternative B 재개 조건 — 캐시 ON 경로가 MySQL CPU 에 반응한 원인은 D-072 가 유력 후보) ⑤ jitter 도입 후 부하 하 낙관락 충돌률·DLQ 유입 재측정 없음(ADR-0025 비관적 락 승격 게이트의 입력) ⑥ 스케줄러 풀 4 의 부하 하 적정성 미측정(D-024 미충족) ⑦ #129 세션 익일 Billing 콘솔 확인 미확인 ⑧ base `limits.cpu` 2000m 을 base 경로로 렌더한 매니페스트를 실제 클러스터에 적용한 적 없음(D-029 검증은 렌더 수준, PR #131 미충족 1) | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 ADR-0027 Consequences · PHASE4 D-024/D-025/D-029 미충족 | 🔲 |
| D-084 | Payment / Operations | **보호 승인 장기 미결의 증빙 기반 복구 경로가 없다** — PG 호출 전 중단 후 영구 404는 UNRESOLVED/APPROVAL_HELD를 유지한다. 기존 코드가 수동 FAILED로 닫은 보호 원장도 자동 복구하지 않는다. ADR-0035의 read-only 진단 후 PG·예약·환불 대사를 수행하고, 권한·증빙·감사 및 재고 1회 복구를 검증하는 운영 복구 절차/진입점이 필요하다. 시간 경과나 단순 운영자 확인만으로 해제하지 않는다 | D-060 후속 보강 [#176](https://github.com/Kimgyuilli/PeekCart/pull/176) · ADR-0035 Limits/Rollout diagnostics | 🔲 |
