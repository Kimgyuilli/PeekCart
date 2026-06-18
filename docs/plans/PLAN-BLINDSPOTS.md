# 계획 블라인드스팟 체크리스트 (compound)

> **무엇**: 과거 계획들이 *실제로* 놓쳐서 구현 단계에서 터진 패턴의 누적 목록.
> **언제 쓰나**: `/plan` Step 4 GP-1 이 구조 변경(모듈/경계/이동)을 감지하면, 본 목록의 각 항목을 계획서에서 다뤘는지 점검하고 plan-review(Step 7) 프롬프트에 입력으로 먹인다.
> **어떻게 자라나 (필터 — 기본값은 "여기 저장 안 함")**: 구현이 계획과 크게 달랐던 항목이 나오면, prose 로 적기 *전에* 3-질문 라우팅을 적용한다.
> 1. **반복되나** — 이 한 건이 아니라 미래 작업의 *한 부류*에 적용되나? · 2. **재도출 어렵나** — 다음 세션이 레포/깃/테스트에서 싸게 다시 알아낼 수 없나? · 3. **더 싼 자동검사로 못 바꾸나**.
> - 자동화 가능(3이 No) → **가드/픽스로** ("자동화 후보" 절에 백로그). prose 저장 안 함. ← 가장 강한 compounding
> - 1·2·3 **모두 Yes** → 비로소 `Bn` 한 줄(계획 규율) 또는 메모리.
> - 그 외(반복 안 함/재도출 쉬움) → **폐기**.
> **가지치기**: 자동검사로 승격된 항목은 `Bn` 에서 제거하고 "승격됨"으로 이동. 본 목록은 *아직 자동화 못 한, 반복되는 계획 규율*만 담는다(비대화 방지).

각 항목: **Trigger**(언제 적용) · **Check**(무엇을 확인) · **출처**.

---

## B1 — 역의존 스윕 (이동/추출/peel/rename)
- **Trigger**: 패키지·모듈·공용물을 옮기거나 서비스를 분리할 때.
- **Check**: 옮기는 대상을 **밖에서** 참조하는 모든 곳을 `grep -rn "<옮기는 FQCN/패키지>"`(대상 폴더 제외)로 뽑아, **각 인바운드 간선마다 "이동/디커플/유지" 처분을 계획서 §2 에 한 줄씩** 적었는가. **테스트는 컴파일러가 강제하므로 특히** 누락 금지.
- **출처**: PR2a-2b — root `IdempotencyIntegrationTest`/`OutboxKafkaIntegrationTest`/`DlqIntegrationTest` 가 떼어낸 notification 도메인을 검증 proxy 로 쓰고 있었음(미계획 3개 테스트 재작성).

## B1b — 역의존 스윕은 FQCN import 만으로 부족: string-level 결합도 쓸어라
- **Trigger**: 도메인/서비스를 peel 하는데 그 도메인이 URL prefix·캐시 이름·락 이름·이벤트 타입/aggregateType **문자열**로 다른 모듈에서 참조될 때.
- **Check**: `grep "com.peekcart.<domain>"`(FQCN import) 만으로는 **컴파일은 통과하지만 런타임에 깨지는** 결합을 놓친다. 옮기는 도메인의 **문자열 식별자**도 함께 스윕하라: REST 경로(`/api/v1/<domain>` — SecurityConfig permitAll·관측성/통합 테스트가 프록시로 사용), 캐시 이름(`"products"` 등 cache 메트릭 테스트), ShedLock/스케줄러 락 이름, 이벤트 토픽/aggregateType 리터럴. 특히 **테스트가 peel 대상 엔드포인트/캐시를 "메트릭·E2E 생성용 프록시"로 쓰는 경우** 컴파일러가 못 잡으므로 명시 점검.
- **출처**: task-impl-product-peel — FQCN grep 으로 src/main 0건·src/test 5건만 처분했으나, 빌드에서 `ObservabilityMetricsIntegrationTest`(`/api/v1/products`·`"products"` 캐시 프록시)·`ShedLockIntegrationTest`(`outboxPollingJob` 락 이름)·outbox probe aggregateType·product 통합테스트 `flyway.enabled` 4부류가 런타임 실패. peel 대상의 string-level 식별자 미스윕.

## B2 — ADR 타깃 ≠ 현재 코드
- **Trigger**: 계획이 ADR 의 목표 위치/구성요소를 인용할 때("X 는 모듈 Y 가 소유").
- **Check**: 그 타깃이 **이미 코드에 존재하는지** 1줄로 확인(파일 존재/grep). 없으면(또는 "이연" 주석이면) "만든다"를 **명시 작업 항목으로 승격**. ADR 은 목표(should), 코드는 현재(is) — 둘을 합치지 말 것.
- **출처**: PR2a-2b — N5 가 "actuator 는 observability S4 기여로 합친다"고 전제했으나 `ActuatorSecurityConfig` 가 아직 없었음(S1만 존재, S4 "PR2 이연").

## B3 — 공유 테스트 인프라 소유처
- **Trigger**: test config / fixture / 테스트 base / mock 빈을 서비스로 옮긴다고 적을 때.
- **Check**: 그게 **여러 모듈에서 쓰이는지** 확인. 둘 이상이면 단일 서비스가 아니라 **`:common` testFixtures**(공유)로 가야 한다.
- **출처**: PR2a-2b — `IntegrationTestConfig` 를 "notification test 로" 적었으나 root 테스트도 사용 → `:common` testFixtures.

## B4 — 추상 의도 금지, 구체 메커니즘 강제
- **Trigger**: 계획 항목이 "…만 배선 / …위치만 / …처리" 처럼 추상으로 끝날 때.
- **Check**: 정확한 **파일/경로/배선**을 명시했는가. 구체화하는 행위 자체가 숨은 의존을 끄집어낸다.
- **출처**: PR2a-2b — "공유 V1~V4 실행 위치만 배선" 이 정작 "어디 둬야 서비스 테스트가 읽나"라는 결정을 비워둠 → 구현 중 `db/migration`→`:common` 이동 결정.

## B5 — 공유 리소스의 물리적 위치
- **Trigger**: 여러 모듈이 공유하는 리소스(스키마/마이그레이션/정적 자원)를 다룰 때.
- **Check**: **모든 소비자**(런타임 + 모듈별 테스트 fixture)가 클래스패스/빌드 컨텍스트로 닿을 수 있는 단일 위치를 정했는가. (Dockerfile COPY 컨텍스트도 — [[project_multimodule_dockerfile_context]])
- **출처**: PR2a-2b — `db/migration` 이 root 에 있으면 분리된 notification 테스트가 못 읽음 → `:common` 단일 소유로 결정.

## B6 — 새 서비스가 `:common` 스캔으로 떠안는 횡단 빈 / 비전이 스타터
- **Trigger**: 새 서비스 모듈 생성/peel (진입점이 `com.peekcart.*` component-scan + `:common` 의존).
- **Check**: 새 서비스는 `com.peekcart.*` 를 스캔하므로 **`:common` 의 모든 `@Component`/`@Configuration` 을 떠안는다**. (a) **필수 `@Value`/`@ConfigurationProperties` 를 가진 :common 빈**(예: `SlackNotificationClient` `${slack.webhook.url}`, `KafkaConfig`/auto-config)이 그 서비스에서 **미사용**이면 → 부팅 실패(`PlaceholderResolutionException`/미사용 인프라 eager 연결) 또는 더미 설정 강요. 처분(**`@ConditionalOnProperty` 로 사용 모듈만 활성** / autoconfig `exclude` / `@Value` default)을 계획서에 명시. (b) **`:common` 이 `api` 로 노출하지 않는 스타터**(validation 등)는 web/jpa 와 달리 전이 안 되므로 서비스 `build.gradle` 에 **명시 선언**.
- **출처**: PR2b — user-service 가 `SlackNotificationClient`(@Value 필수)로 `PlaceholderResolution` 부팅 실패 → `@ConditionalOnProperty` · `KafkaAutoConfiguration` exclude · `spring-boot-starter-validation` 누락으로 @Valid 미작동 500.

## B7 — 버전 가드 upsert 는 단일 원자 문장 + flush 경계
- **Trigger**: CQRS read-model / 로컬 캐시에 "더 높은 version/timestamp 일 때만 갱신"(stale-skip upsert)을 적을 때, 또는 `@Version`·`@UpdateTimestamp` 값을 이벤트 payload 에 실어 발행할 때.
- **Check**: (a) upsert 를 `update→exists→save` 2-step 으로 적으면 **`save()` 의 UK/PK 위반이 메서드가 아니라 트랜잭션 flush/commit 시점에 터져 catch 를 우회**한다 — 낮은 version 이 먼저 커밋되면 "높은 version 만 적용" 이 깨진다. **MySQL `INSERT … ON DUPLICATE KEY UPDATE … IF(:v > source_version, …)` 단일 원자 문장**으로 명시하라. (b) `@Version` 은 **flush 시점에 증가**하므로 payload 의 version 은 반드시 **`saveAndFlush` 후 `getVersion()`** 으로 읽어야 한다(flush 전 읽으면 seed=0 ↔ 첫 이벤트=0 충돌로 첫 갱신 누락). 계획에 메커니즘을 추상("upsertIfNewer 비교")이 아닌 구체로 못박아라(→ B4).
- **출처**: strangler-2(task-impl-saga2-unit-price-cache) — plan 은 stale-skip·flush 경계를 적었으나 *upsert 메커니즘* 을 비워둠 → 구현이 2-step race 로 작성, diff 리뷰(GW-2 #1)가 catch-밖 commit 위반 지적 → 원자 upsert 로 교체. strangler-3·향후 read-model 에 재발 가능.

## B8 — `:common` 가정 금지: producer 전속 실행 인프라는 모듈과 함께 안 따라온다
- **Trigger**: *발행*(outbox) 또는 *멱등*(idempotency) 또는 스케줄러(ShedLock) 를 쓰는 도메인을 서비스로 peel 할 때.
- **Check**: 그 도메인이 import 하는 `com.peekcart.global.{outbox,idempotency,config}` 가 **`:common` 에 있는지 root `src/main` 전속인지** grep 으로 확인(`find common/src/main -path "*global/outbox*"` vs `find src/main -path ...`). `:common` 에는 보통 **payload DTO 만** 있고 **실행 세트**(`OutboxEvent*`·`OutboxPollingService/Scheduler`·`ProcessedEvent*`·`IdempotencyChecker`·`ShedLockConfig`)는 root 전속이다 → 모듈만 떼면 **컴파일 실패**. 처분(서비스로 **복제** vs `:common` 이관)을 계획서에 명시하고, 복제면 ShedLock 등 전이 안 되는 의존성도 build.gradle 에 추가. consume-only 서비스는 idempotency 만, *발행* 서비스는 outbox 실행 세트까지 필요.
- **Check (복제 시 후속)**: outbox poller 를 복제하면서 **공유 DB 전환기**(DB-per-service 전)라면, root·신규 서비스 두 poller 가 같은 `outbox_events` 를 본다. 기존 쿼리가 `status='PENDING'` 만 필터하고 ShedLock 이름이 단일이면 → 소유권 붕괴(한쪽이 전체 발행) 또는 중복 발행. **poller 별 eventType/aggregateType allowlist + ShedLock 이름 분리**를 계획에 명시(스키마 변경 불요 — `OutboxEvent` 에 컬럼 기존재). DB 가 실제 분리되면 자연 해소되나 전환기엔 필수.
- **출처**: task-impl-product-peel — plan 이 "`src/main/.../product/**` 만 이동, `:common` 가 global 커버" 로 전제했으나 `ProductOutboxEventPublisher`/consumer 가 root 전속 `global.outbox`/`idempotency` 에 의존(plan-review 1차 P1 #1). 복제 후엔 공유 DB poller 경합(2차 P1 #1) — root/product 두 poller 가 같은 PENDING 행 경합. notification 은 consume-only 라 idempotency 만 복제했고, product 는 첫 *발행* 서비스 peel. order/payment peel 에 재발.

## B9 — 이벤트 역전(reorder) 게이트 누수: 종료/취소 이벤트가 생성 이벤트보다 선도착
- **Trigger**: 어떤 도메인이 다른 도메인의 이벤트로 로컬 게이트/플래그/상태를 세팅하는데, 그 게이트가 **별도 토픽의 "생성" 이벤트로 만들어진 로컬 엔티티**에 의존할 때(예: order.created 로 Payment 생성 → order.cancelled 로 취소 게이트). 서로 다른 토픽은 **같은 파티션 키여도 소비 순서 보장 없음**(consumer group 별 독립).
- **Check**: "취소/종료 이벤트가 생성 이벤트보다 **선도착**하면?" 을 명시 점검. 생성 엔티티가 아직 없으면 `findById().ifPresent()` 는 **조용히 no-op** 되고 멱등 체커가 처리완료로 기록 → 게이트 영구 유실. **throw-재시도만으로는 부족**: Kafka retry window(backoff) 초과 시 DLQ 로 빠지고, 이후 생성+준비 이벤트가 도착하면 게이트 없이 통과(돈/보안이면 silent 사고). 처분: **aggregate id 기준 영속 marker(별 테이블/컬럼)** 를 두고 **생성 시점(create 핸들러)에서 marker 를 적용**해, 선도착이 DLQ 로 빠져도 누수 0. throw-retry 는 안전 방향(준비 플래그처럼 미설정이 "차단"인 경우)에만 허용.
- **출처**: task-impl-order-payment-decouple — Payment 가 order.cancelled 로 취소 게이트를 두는데, order.created 선후 역전 시 Payment 미존재 → throw-retry 가 DLQ 초과 시 누수(work GW-2 loop2 P1#1). `payment_cancellations` 영속 marker + handleOrderCreated 적용으로 봉합. 반대로 reserve-게이트(ready 플래그)는 미설정=차단이라 throw-retry 로 충분(비대칭 주의).

---

## 자동 검사로 승격된 항목 (참고 — 더 이상 수동 점검 불필요)
- 서비스↔서비스 project 의존 금지 → `assertNoServiceProjectDeps` (PR2a-2b).
- 동일 FQCN 모듈 중복 / `JwtProvider` 잔존 금지 → `assertNoDuplicateGlobalFqcn` (PR2a-2b).

## 자동화 후보 (승격 대기 — 가드/픽스로 만들 것, prose 규율 아님)
> 3-질문 필터에서 "자동화 가능"으로 라우팅된 것들. 만들어지면 위 "승격됨" 으로 이동.
- **ship: partition 커밋 후 untracked 0 검사** — 디렉토리 pathspec 으로 add 하면 모듈 루트 파일(`*-service/build.gradle`)을 놓칠 수 있음. `/ship` Step 4 말미에 "staged 외 추적 대상 잔여 0" assert. (출처 PR2a-2b, PR2b/c/d 반복)
- **ship: drift 디텍터 rename 처리** — `hpx_diff_absorption_status` 가 rename 많은 diff 에서 커밋 0건인데 `partially_live` 오판. `git status --porcelain` 의 `R old -> new` 양쪽을 매칭하도록 수정. (출처 PR2a-2b, rename-heavy peel 반복)
