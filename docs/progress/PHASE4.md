# Phase 4 진행 보고서 — MSA 분리

> Phase 4 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md`, 설계·실행 로드맵은 `docs/progress/phase4-design-roadmap.md` 참고

---

## Phase 4 목표

ADR-0002 의 "모놀리식 → MSA 진화" 4단계 중 최종 단계. 5개 서비스 분해 + Gateway + Choreography Saga + CQRS.

**Exit Criteria** (`07-roadmap §16`):
- [ ] 모든 서비스 독립 배포 및 정상 동작 확인
- [ ] Saga 보상 트랜잭션 플로우 검증 (결제 실패 → 주문 취소 → 재고 복구)
- [ ] Gateway 라우팅 및 JWT 인증 정상 동작
- [ ] 서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 확인

> 설계 단계(A1~A4) → 구현 단계(①~⑥). 상세 시퀀싱: `phase4-design-roadmap.md`.

---

## 작업 이력

### 2026-06-13 ~ 06-14

#### A1 — ADR-0010 서비스 분해 (설계)

**완료 항목**:
- **ADR-0010** 신규 (`docs/adr/0010-phase4-service-decomposition.md`, Status: Accepted) — §5(5개 풀 분해) 정본 비준, §4-5(3개 드리프트 목록) 정정. 5개 서비스 경계 표(D1) + 이벤트 토폴로지(D2, 토픽 4개) + Choreography Saga 체인(D3) + Phase 4 Exit Criteria coverage matrix(D4)
- 비준 시 추가 도식 정합 3건 (ADR-0010 §C4):
  - **F1** Notification DB 소유 확정 → `02-architecture.md §5` DataLayer 에 NotificationDB 추가, `05-data-design.md` 와 정합
  - **F2** 재고 차감 소유 트랜잭션 경계 충돌(Order 단일 트랜잭션 ↔ Product 재고 소유) 기록 → 재고 예약/차감 경계는 A3 위임
  - **F3** CQRS 로컬 캐시용 `product.updated`(Product→Order) 이벤트 필요 명시 → 스키마는 A3
- Layer 1 정합: `02-architecture.md §4-5`(5개로 정정 + `see ADR-0010`), `03-requirements.md §7-2`(Saga 재고 복구 주체 Order→Product 정정), `05-data-design.md`(Notification DB 정합 표기)
- `docs/adr/README.md` INDEX 행 추가

**설계 결정**: 서비스 경계 = §5 정본(5개). 근거·대안(Alt A 5개 vs Alt B 3개)은 ADR-0010.

**프로세스**: `/plan` 2회 Codex 리뷰(1차 5건, 2차 3건 전체 반영) → `/work` 구현 → `/ship` ([PR #44](https://github.com/Kimgyuilli/PeakCart/pull/44)). 계획서·audit: `docs/plans/task-adr0010-service-decomposition.md`.

**다음**: A2(멀티모듈 구조) — `common` 경계·의존 규칙. ADR-0010 §D1 의 5개 서비스 = 5개 모듈.

#### A2 — ADR-0011 멀티모듈 구조 (설계)

**완료 항목**:
- **ADR-0011** 신규 (`docs/adr/0011-phase4-multimodule-structure.md`, Accepted) — `common` + **`peekcart-common-observability`(ADR-0009 선결정)** + 5개 서비스 모듈. 모듈 레이아웃(D1) + class-level common 경계(D2) + 의존 규칙·위반 검출 필수(D3) + 빌드/테스트/이미지 계약(D4)
- 핵심 결정: 서비스는 `:common`+`:peekcart-common-observability` 만 의존, 서비스↔서비스 직접 의존 금지(CI 빌드 실패 검출). 이벤트 DTO 는 모듈 소유만, 스키마는 A3 위임(non-authoritative). Docker health smoke 서비스별 유지
- Layer 1 정합: `02-architecture.md §4-4`(관측성/5서비스 모듈 + `see ADR-0011`), §12(Phase 4 멀티모듈 포인터), `adr/README.md` INDEX

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 2건[ADR-0009 모듈 충돌 발견], 3차 1건[자기모순 cleanup] — 5→2→1 수렴) → `/work` 구현(diff 리뷰 2건) → `/ship` ([PR #45](https://github.com/Kimgyuilli/PeakCart/pull/45)). 계획서·audit: `docs/plans/task-adr0011-multimodule-structure.md`.

**다음**: A3(DB-per-service + 이벤트/Saga 계약) · A4(Gateway 보안) — 병렬 가능. 이후 구현 ①(멀티모듈 전환).

#### A3 — ADR-0012 DB-per-service + 이벤트/Saga 계약 (설계)

**완료 항목**:
- **ADR-0012** 신규 (`docs/adr/0012-phase4-db-event-saga-contract.md`, Accepted) — DB-per-service domain/infra 경계(D1) + 이벤트 스키마(D2: envelope `schemaVersion`·파티션키·`product.updated` 필드·`OrderCancelled` items 보강) + 재고 예약 Saga(D3) + 토픽×producer×consumer×group 매트릭스(D4) + retention=멱등성 창 상한(D5)
- 핵심 결정: F2 해소 — Product 재고 소유로 Order 직접 차감 불가 → **예약 모델**(`order.created → Product 예약 → stock.reservation.result → 결제`). 예약 실패 신호용 **신규 토픽 `stock.reservation.result`** 채택(옵션 B). ADR-0010 4토픽 → 6토픽 refine. retention ≥ max(topic retention, consumer 다운타임, DLQ 수동 재처리 창, backfill)
- Layer 1 정합: `05`(Product DB outbox/processed/예약 컬럼), `04`(§9-6 전략 A→예약 모델, §9-4 Saga, §16 product.updated), `03 §7-2`(예약 경계), `02 §5`(토폴로지 6토픽), `adr/README.md`
- 편입 부채: L-008/L-011(retention), L-020-2(consumer group 라벨)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 6건, 2차 1건, 3차 0건 — 6→1→0 수렴) → `/work` 구현(diff 리뷰 3건) → `/ship` ([PR #46](https://github.com/Kimgyuilli/PeakCart/pull/46)). 계획서·audit: `docs/plans/task-adr0012-db-event-saga-contract.md`.

**다음**: A4(Gateway 보안) — 마지막 설계 ADR. 이후 구현 ①(멀티모듈 전환).

#### A4 — ADR-0013 Gateway 보안 (설계, 마지막 설계 ADR)

**완료 항목**:
- **ADR-0013** 신규 (`docs/adr/0013-phase4-gateway-security.md`, Accepted) — RS256 전환(D1, Gateway 공개키 1차 검증·서비스 미재검증·JWKS·kid/alg allow-list·키 overlap) + 시크릿 저장소 3안(D2, Secret Manager 기본/KMS 격상) + Spring Cloud Gateway(D3, 검증 순서·헤더 신뢰 모델·route-class Rate Limit·fail-closed) + Reuse Detection(D4, `family_id` 이력 모델 + 탈취 containment) + 인증 실패 관측성(D5, ADR-0009 S9 surface 추가)
- 핵심 결정: 대칭키 공유 제거(RS256), reuse 감지 시 family 무효화 + access token `family_id` 클레임/family deny 로 이미 발급된 토큰까지 Gateway 차단
- Layer 1 정합: `04 §10-2/§9-2`, `03 §7-2`, `05 refresh_tokens`(family_id/status/grace_until), `02 §5`, `adr/0009`(S9 행 추가), `adr/README.md`
- 편입 보안 묶음: L-001(RS256)/L-002(시크릿)/L-003(Reuse Detection)/L-019(관측성)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 8건, 2차 1건, 3차 0건 — 8→1→0 수렴) → `/work` 구현(diff 리뷰 4건) → `/ship` ([PR #47](https://github.com/Kimgyuilli/PeakCart/pull/47)). 계획서·audit: `docs/plans/task-adr0013-gateway-security.md`.

**다음**: 🎯 초기 설계 ADR(A1~A4) 완료. **구현 단계 ①(Gradle 멀티모듈 전환)** 부터 — 실제 코드. (구현 ① PR2 착수 중 전환기 인증 보정 ADR-0014 추가 — 아래 A4.5)

#### A4.5 — ADR-0014 전환기 인증 검증 공유 모듈 (보정 ADR, 구현 ① PR2 중 발견) — ✅ [PR #50](https://github.com/Kimgyuilli/PeakCart/pull/50)

**배경**: 구현 ① PR2(서비스 분리) 착수 중, ADR-0011 §D2(auth=User전속)가 게이트웨이(ADR-0013, 구현 ③) 전제였음이 드러남. 게이트웨이 이전엔 5개 서비스 전부 JWT 검증 필요(Product 도 `AdminProductController @PreAuthorize` admin API).

**완료 항목**:
- **ADR-0014** 신규 (`docs/adr/0014-transitional-auth-module.md`, Accepted) — 전환기 JWT **검증** 전용 모듈 `peekcart-common-auth` 도입. D1 모듈 경계(검증 모듈 vs User 발급/blacklist write) + D1-b JwtProvider sign/verify 분리 + D1-c Blacklist/Deny Redis Contract + D2 전환기 결합(대칭키·블랙리스트 fail-closed)·게이트웨이 exit(제거/잔류 분리) + D3 의존규칙(5개 서비스 의존, 모듈 7→8)
- **ADR-0011 → Partially Superseded by ADR-0014** (§D1 토폴로지 확장 + §D2 검증 소유 + §D3 allowlist, 발급/User 소유는 유효)
- Layer 1 정합: `02-architecture §4-4`(모듈 목록 +auth), `adr/README`, 상위 `task-impl1` 계획(7→8모듈·PR2 인증 메모)

**핵심 결정**: 검증→`peekcart-common-auth`, 발급/블랙리스트 write→User, 블랙리스트 read=공유 Redis+fail-closed. "라이브러리 공유 ≠ 런타임 중앙화(게이트웨이가 그것)".

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 3건, 3차 1건 — 5→3→1, P0 전 라운드 0; 3차가 Product auth-free 오류 포착) → `/work`(ADR 작성). 계획서·audit: `docs/plans/task-adr0014-transitional-auth-module.md`.

**다음**: 구현 ① PR2a(Notification) — `peekcart-common-auth` 생성 + 첫 서비스 peel.

---

## 구현 단계 (①~⑥) — 코드

> 초기 설계 ADR(A1~A4, #44~#47) + 보정 ADR-0014(A4.5)가 SSOT. 각 구현 항목은 선행 ADR을 따라 PR 단위로 진행하며(구현 ①은 ADR-0011/ADR-0014), 세부 PR 분할은 `/plan` 착수 시 정의한다.

### (진행 중) ① Gradle 멀티모듈 전환 — 선행 ADR-0011

- 단일 모듈 → `common` + `peekcart-common-observability` + 5개 서비스 모듈 (ADR-0011 §D1)
- 의존 위반 검출 Gradle task(서비스↔서비스 금지), testFixtures 재배치, Dockerfile/CI matrix/k8s N개화
- 편입 부채: L-016a(gke `newTag` digest 고정), D-016(GHCR→AR image promotion 자동화)
- ⚠️ 대규모 리팩토링 — work diff 大, 실제 빌드/테스트 동반. **3-PR 분할 확정**(PR1 스켈레톤+common → PR2 서비스 5개 분리 → PR3 Dockerfile/CI/k8s). 계획서: `docs/plans/task-impl1-gradle-multimodule.md`.

#### PR1 — 멀티모듈 스켈레톤 + common/observability 추출 ✅ ([#48](https://github.com/Kimgyuilli/PeakCart/pull/48))

**완료 항목** (P1~P6):
- 루트 `build.gradle` 멀티모듈화(`allprojects`/`subprojects` 공통 설정: Spring BOM·Java 17·lombok·test platform), root 는 과도기 app 유지
- `settings.gradle` → `common` + `peekcart-common-observability` include
- `common` 모듈(`java-library`) — `entity`/`response`/`exception`/`kafka`/`filter.MdcFilter`/`lock`/`cache`/`outbox.dto`/`config.{RedisConfig,RedissonConfig}` 이동(전부 `git mv` R100, 패키지 경로 유지)
- `peekcart-common-observability` 모듈 — `MetricsConfig`(S1) 이동 (ADR-0009 인용)
- `java-test-fixtures` — 의존-깨끗 support 4종(`AbstractIntegrationTest`/`ServiceTest`/`WithMockLoginUser`*) 재배치
- 검증: `./gradlew build` BUILD SUCCESSFUL (3모듈 + 51 test 그린, Testcontainers 포함)

**구현 중 발견 → PR2 이연**: `WebMvcConfig`/`OpenApiConfig`(→auth/User)·`KafkaConfig` 팩토리(→`SlackPort`/Notification)·`SecurityConfig` S4·S3 yml·`IntegrationTestConfig`·`fixtures` 가 서비스 전속 코드에 컴파일 의존 → ADR-0011 §D3 "common→서비스 의존 금지" 불변식상 PR2 로 이연. 계획서 P3/P4/P5/P10 갱신.

**프로세스**: `/plan`(Codex 2회: 9건→4건, P0 0) → `/work`(diff 리뷰 1회 P2 1건, 자동 통과) → `/ship` ([PR #48](https://github.com/Kimgyuilli/PeakCart/pull/48)).

**다음**: PR2(서비스 5개 모듈 분리 — P7~P13).

#### PR2a-1 — common-auth 추출 + JWT 검증/발급 분리 ✅ ([#51](https://github.com/Kimgyuilli/PeakCart/pull/51))

> PR2a 가 예상보다 커서 **PR2a-1(common-auth 추출, 본 PR)** 와 PR2a-2(notification peel) 로 분할. 서비스 peel 없이 인증 검증 토대만 마련.

**완료 항목** (P7/P10 부분, ADR-0014 D1):
- `peekcart-common-auth` 모듈 신설 — 검증 primitives 9종(LoginUser/CurrentUser/resolver/TokenClaims/TokenParseException/JwtFilter/handler 2종/WebMvcConfig/OpenApiConfig) 이동(`git mv` R100)
- **`JwtProvider` → `JwtTokenVerifier`(common-auth) / `JwtTokenSigner`(root, User 발급) 분리** (D1-b) + `JwtAuthProperties`(`app.jwt.*`) 단일 설정 계약 → sign·verify 동일 secret 바인딩
- **`TokenBlacklistPort`(write, User) ↔ `TokenBlacklistLookupPort`(read, common-auth) 분리** + `RedisTokenBlacklistLookupAdapter`(`miss=pass`/`Redis 실패=fail-closed`, D1-c). jti/hash+namespace 마이그레이션은 PR2c 이연
- `JwtSecurityConfigurer` 재사용 기여 → root `SecurityConfig` 가 단일 `SecurityFilterChain` 생성, `AuthService` 검증 재배선
- root `build.gradle` `:peekcart-common-auth` 의존(전환기 4서비스 잔류)

**구현 중 발견 수정 (systemic, 멀티모듈)**:
- 라이브러리 모듈(boot 플러그인 부재)에 `-parameters` 미적용 → Spring 생성자 by-name DI(`RedisTemplate` 동명 빈 `NoUniqueBean`) 깨짐 → root `subprojects` 에 `-parameters` 추가
- 동일 모듈에 `junit-platform-launcher` 부재 → 테스트 실행 불가(`TestSuiteExecutionException`) → `subprojects` `testRuntimeOnly` 추가

**구현 중 발견 → PR2a-2 이연**: `SlackPort` 가 notification 도메인뿐 아니라 **root `OutboxPollingService`·`KafkaConfig.kafkaErrorHandler`(order/payment DLQ→Slack)** 에서도 사용되고 유일 구현체 `SlackNotificationClient` 가 notification 내부에 있음. plan T7/ADR-0011 §D2 "SlackPort→Notification 전속" 분류 오류 → PR2a-2 착수 시 `:common` 이동 vs 복제 결정 필요(ADR-0011 §D2 정정 동반).

**검증**: `./gradlew build` 그린 — **272 root tests + 7 common-auth tests**(new: RedisTokenBlacklistLookupAdapterTest 3 / JwtFilterTest 4).

**프로세스**: `/plan`(Codex 3회: 5→3 신규, P0 0, fail-closed 게이트 보강) → `/work`(diff 리뷰 1회 P1 1건 — common-auth 단위 회귀 추가) → `/ship` ([PR #51](https://github.com/Kimgyuilli/PeakCart/pull/51)).

**다음**: PR2a-2(notification peel) — **선결: SlackPort 경계 결정**. 이후 PR2b(Product)/PR2c(User)/PR2d(Order+Payment)/PR3.

#### PR2a-2a — SlackPort → :common 횡단 인프라 ✅ ([#52](https://github.com/Kimgyuilli/PeakCart/pull/52))

> PR2a-2(notification peel)의 선결 SlackPort 경계 결정을 별도 체크포인트로 분리. 서비스 peel 없음.

**완료 항목** (N1·N2):
- **`SlackPort`(인터페이스, `global.port` 경로 유지) + `SlackNotificationClient`(→ `com.peekcart.global.slack`) → `:common` 이동** — 횡단 인프라(root outbox/kafka DLQ alerting + notification 공용). client 는 notification 도메인 의존 0, `:common` 의 `spring-boot-starter-web`(api) `RestClient` 충족
- **ADR-0011 §D2 표 SlackPort 행 정정**(서비스 전속→common) + **Update Log** 추가 — 사실 오류 정정(`fix(adr):`), 신규 ADR 아님. plan T7/§D2 분류가 코드 현실(+SlackPort javadoc "횡단 관심사")과 어긋났던 것

**핵심 결정**: SlackPort 는 "Notification 전속"이 아니라 **횡단 인프라(common)**. SlackPort javadoc·실제 사용처(outbox/kafka DLQ)가 이미 횡단성을 증언. 인터페이스 패키지 경로 유지로 8개 사용처 무변경 → root 알림 경로 무회귀.

**검증**: `./gradlew build` 그린 (272 tests). Codex diff 리뷰 P0:0/P1:0/P2:1(계획 본문 모순 정정).

**프로세스**: `/plan` **3회**(SlackPort 경계 검증 — 2→2→2 신규, P0 0; ADR Update Log vs 신규 ADR 판단·KafkaConfig §D2 경계·회귀 게이트 구체화) → `/work`(N1/N2, diff 리뷰 P2 자동통과) → `/ship` ([PR #52](https://github.com/Kimgyuilli/PeakCart/pull/52)).

**다음**: PR2a-2b(notification peel, N3~N9) — 새 브랜치. notification-service 모듈/도메인 이동/KafkaConfig 분리/flyway/assertNoServiceProjectDeps/테스트/Dockerfile.

#### PR2a-2b — notification-service peel (첫 서비스 분리) ✅ ([#53](https://github.com/Kimgyuilli/PeakCart/pull/53))

> 단일 모놀리스(root)에서 **첫 마이크로서비스(`notification-service`)를 독립 모듈/`bootJar`/독립 부팅으로 분리**. 선행: ADR-0011 §D1~D4, ADR-0014(전환기 JWT 검증), ADR-0009 S4.

**완료 항목** (N3~N9):
- **N3·N4** `notification-service` 모듈(`bootJar`) + `settings.gradle` include · `NotificationApplication`(`com.peekcart` base, 공유 `global.*` 스캔) + 서비스 yml 3종(`application=notification-service`, `app.jwt.*`, flyway disabled) · 도메인 `git mv` · **공유 `db/migration`→`:common` 단일 소유**(전 모듈 classpath+테스트 fixture 접근)
- **N5** idempotency 복제(소비자 멱등성) · `NotificationKafkaConfig`(listener/error-handler+DLQ→`:common` SlackPort) · thin `NotificationSecurityConfig`(common-auth `JwtSecurityConfigurer` 배선) · **observability `ActuatorSecurityConfig`(ADR-0009 §Decision S4 단일 소유 실현)** + root SecurityConfig 정렬 · `IntegrationTestConfig`→`:common` testFixtures(root도 사용)
- **N6** root `flywayMigrateShared` task(Flyway 11.7.2 플러그인) — 과도기 공유 DB 단일 마이그레이션 실행 지점, 서비스 런타임 flyway disabled
- **N7** `assertNoServiceProjectDeps`(allowlist 외 project 의존 빌드 실패, doLast 평가) + 의도적 위반 재현
- **N8** notification 통합 3종(consumer 멱등성·보안 negative+blacklist+cross-module JWT+chain·관측성) + root 부팅 스모크(SlackPort=:common SlackNotificationClient) + `assertNoDuplicateGlobalFqcn`(게이트 i) + **root Idempotency/Dlq/Outbox 테스트 디커플**(NotificationConsumer peel → root-observable 효과 기준 재작성)
- **N9** Dockerfile COPY 컨텍스트 동기화 + 로컬 `docker build`(root 이미지) 검증

**핵심 결정**:
- **전환기 인증(ADR-0014)**: 검증은 `:peekcart-common-auth`, 각 서비스 thin SecurityConfig 가 `SecurityFilterChain` 1개 생성. cross-module JWT 는 동일 `app.jwt.secret`/HS256 계약(게이트 h).
- **actuator 단일 소유(ADR-0009 S4)**: `ActuatorSecurityConfig.mergedPublicUrls()` 가 actuator permitAll 유일 정의처 — 서비스/root 는 비즈니스 PUBLIC_URLS 만 선언(과허용 회귀/드리프트 차단). 코드 리뷰(N5)에서 "서비스별 actuator 재기재 금지" 지적 반영.
- **root 테스트 디커플**: NotificationConsumer 가 root 에서 peel → root Idempotency/Outbox/Dlq 통합테스트가 notification 도메인 의존하던 부분을 Payment/Order/Inventory + 테스트 전용 Kafka listener 로 재작성. notification 소비 검증은 notification-service 로 이관.

**검증**: `./gradlew build` 그린(8모듈, 264+ tests, Testcontainers) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` + 위반 재현 · 로컬 `docker build` 성공 · 게이트 a~j 충족.

**프로세스**: `/plan` 3회(N3~N9 보강 — JWT 키 `app.jwt.*` 정정·actuator S4 소유권·게이트 i/c 매핑·게이트 j root 회귀; 2차 타임아웃→3차 lean 재시도) → `/work`(split 리뷰 c1~c3 aggregate=ok, P1:3/P2:3 → 수용 3 dep-check 평가시점·PUBLIC_PATHS private·테스트 .get() / 보류 3 기존 패턴) → `/ship`([PR #53](https://github.com/Kimgyuilli/PeakCart/pull/53), 6 커밋).

**다음**: PR2b(Product) / PR2c(User, 발급 owner) / PR2d(Order+Payment, root 소멸) / PR3(Dockerfile per-service·CI matrix·k8s N개화).

---

## PR2b — user-service peel (independent, 발급 owner) — [PR #55](https://github.com/Kimgyuilli/PeakCart/pull/55)

> 두 번째 서비스 분리. 선행: ADR-0011 §D1~D4, ADR-0014(발급=User/검증=common-auth), ADR-0009 S2/S4.
> **peel 순서 정정(roadmap §2)**: Product 가 Order 의 동기 `ProductPort` 빈에 묶여 ① 단독 peel 불가 → independent 한 User 를 먼저 분리, Order/Product/Payment 는 ②/④/⑤ 교차 사가 클러스터로 이연(ADR-0010 F2·ADR-0012 D3, 새 ADR 불필요).

**완료 항목** (U1~U9):
- **U1·U2·U3** `user-service` 모듈(`bootJar`, `KafkaAutoConfiguration` 제외) · `UserApplication` + yml 3종(`application=user-service`, `app.jwt.*`, flyway disabled) · `com.peekcart.user.*` 도메인 + 발급 issuer global(`JwtTokenSigner`/`TokenIssuer`/`TokenBlacklistPort` write) `git mv`(`com.peekcart.global.*` 패키지 유지, 중복 FQCN 금지) · **root 발급 경로 완전 제거**
- **U4** thin `UserSecurityConfig`(common-auth `JwtSecurityConfigurer` + `@EnableMethodSecurity` + `PasswordEncoder` 빈 이관) · root `SecurityConfig` 에서 `passwordEncoder()`·`/api/v1/auth/**` 라우트 제거
- **U5** 블랙리스트 namespace 마이그레이션 — 신키 `auth:blacklist:<sha256hex>`(원문 미저장, ADR-0014 D1-c) write + 전환기 **dual-read**(legacy `bl:<token>` access TTL 동안 read) · `TokenHasher`(:common-auth 단일 소유)
- **U6·U7** 과도기 flyway disabled(공유 `flywayMigrateShared` 재사용) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` 에 user-service 포함
- **U8** user-service 통합(관측성 S2/S4 · 보안 negative: 미인증 401·공개 endpoint 201·신키/legacy blacklist hit·cross-module JWT·chain 1개·actuator permitAll) + common-auth dual-read 단위 4종 + **root 테스트 디커플**(User 도메인 peel → Outbox/Idempotency 통합테스트 users 행 native SQL 시드, `UserFixture`→user-service)
- **U9** Dockerfile COPY 컨텍스트 동기화 + 로컬 `docker build`(root) 검증

**핵심 결정**:
- **`:common` 횡단 빈 처분(blindspot B6)**: `com.peekcart.*` 스캔으로 새 서비스가 :common @Component 를 떠안음 → `SlackNotificationClient`(필수 `@Value`)는 `@ConditionalOnProperty(slack.webhook.url)` 로 Slack 사용 모듈만 로드, `KafkaAutoConfiguration` exclude, 비전이 starter(`validation`)는 명시 선언. (`PLAN-BLINDSPOTS.md` B6 추가)
- **U5 token-hash dual-read**: jti 미도입(현 토큰에 jti 부재 → 발급/검증/claims 변경 회피) → `token-hash` 고정. read 측 dual-read 로 마이그레이션 중 legacy 차단 토큰 누출 차단.

**검증**: `./gradlew build` 그린(root 234·user 40·notification 20·common-auth 8, 0 fail, Testcontainers) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` · 로컬 `docker build` · 게이트 a~j 충족.

**프로세스**: `/plan`(Codex 2회: PasswordEncoder 누락 P0·U5 dual-read·U1 Kafka 봉합·신키 token-hash 고정 등 13건 반영) → `/work`(Codex diff 1회 P0/P1:0, P2 #1 SlackNotificationClient 조건부·#2a 공개 endpoint 테스트 반영) → `/ship`([PR #55](https://github.com/Kimgyuilli/PeakCart/pull/55), 7 커밋).

**다음**: Order/Product/Payment 사가 클러스터(②/④/⑤ 교차) / PR3(Dockerfile per-service·CI matrix·k8s N개화).

---

## 사가 클러스터 strangler-1 — 재고 예약/복구 이벤트화 — [PR #56](https://github.com/Kimgyuilli/PeakCart/pull/56)

> Order/Product/Payment 사가 클러스터의 첫 strangler 단계(②/④ 교차, peel 없음). 선행: ADR-0010 F2(재고 차감 경계), ADR-0012 D3/D4(예약 choreography·`stock.reservation.result`·§50 payload·§65 all-or-nothing·§46 envelope). **새 ADR 불필요** — 기존 ADR 가 결정 보유, 바뀌는 건 실행·임시 의미뿐.
> **임시 호환 단계**: D3 최종 2-phase 모델이 아니라 D3 경로를 여는 임시 단계. `reserved=true`="이미 차감됨". 단가는 임시 동기 `getUnitPrice`(strangler-2 에서 `product.updated` 캐시로 대체), Payment charge 예약 게이트는 strangler-3.

**완료 항목** (P1~P8):
- **P1** Order 동기 재고변경 전면 제거 — `ProductPort.decreaseStockAndGetUnitPrice`→`getUnitPrice`(read-only), `restoreStock` 인터페이스 제거. createOrder 차감 제거, cancel/cancelExpired/handlePaymentFailed 복구 제거(이벤트로 이관).
- **P2** 계약 — `:common` `StockReservationResultPayload`/`ReservedItemPayload`, `KafkaEventEnvelope` `schemaVersion`(4-인자 호환 생성자 + 누락→v1 정규화 `@JsonCreator`), `KafkaConfig` `stock.reservation.result`(+dlq) 토픽.
- **P3** 예약 원장 — `stock_reservations`(V5) orderId 상태머신(RESERVED/CANCEL_REQUESTED/RELEASED/FAILED) + `order_id`/`source_event_id` UNIQUE, `RESERVED→RELEASED` 원자 CAS(JPQL @Modifying).
- **P4** `ProductOutboxEventPublisher`(공유 outbox 재사용, aggregateId=orderId, MdcSnapshot trace 전파).
- **P5** Product 예약 consumer(`order.created`) — tombstone skip + **all-or-nothing**(전 품목 선검사 후 일괄 차감, race 시 PRD-002 전파→롤백→재시도 수렴) + 멱등.
- **P6** Product release consumer(`order.cancelled`+`payment.failed`) — `RESERVED→RELEASED` CAS 가드 복구(double-release 방지) + cancel-before-create tombstone.
- **P7** Order 결과 consumer(`stock.reservation.result`) — reserved=false→취소+`order.cancelled`(이미 CANCELLED 면 멱등 no-op), reserved=true→`reservationConfirmedAt`(V6) 기록 / `OrderTimeoutScheduler` 예약 미확정 PENDING 수렴(조기취소 방지).
- **P8** 단위(StockReservationService·OrderEventConsumer 가드·envelope) + 서비스레벨 통합(happy·all-or-nothing·취소순서·double-release·cancel-before-create, 실 DB CAS/마이그레이션) + **root 테스트 디커플**(`OutboxKafkaIntegrationTest` payment.failed→취소만, `DlqIntegrationTest` order.created 다중 consumer DLQ).

**핵심 결정**:
- **예약 원장 = orderId 상태머신 테이블** (2차 plan 리뷰 P0#1·P1#2·P1#4 수렴): `processed_events` 만으론 cross-topic 순서(취소 선도착)·서로 다른 eventId 의 double-release 를 못 막음 → tombstone + 원자 CAS 로 해소.
- **all-or-nothing = 선검사 후 일괄 차감**(REQUIRES_NEW 곡예 회피): 부분 차감은 race 시 PRD-002 전파로 전체 롤백, 재시도 시 선검사가 차단.
- **빈 items 거부**(work 리뷰 c3:1)·**reserved=false on already-CANCELLED no-op**(work 리뷰 c1:1)·**누락 schemaVersion→v1 정규화**(c1:3).

**검증**: `./gradlew build` 전체 그린(Testcontainers 포함).

**프로세스**: `/plan`(Codex 2회: 1차 8건[P0×2 스코핑 구멍] → 전면 스코프 확장, 2차 5건[원장 상태머신 수렴] 전체 반영) → `/work`(split diff 리뷰 c1~c3 aggregate=ok, P1:4/P2:3 → 수용 4: cancel-guard·빈items·schemaVer·표적테스트 / 반려 2: chunk false-positive·DLQ 의도적 트레이드오프) → `/ship`([PR #56](https://github.com/Kimgyuilli/PeakCart/pull/56), 5 커밋).

**다음**: strangler-2(단가 `product.updated` 로컬 캐시 → `getUnitPrice` 제거) → strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트, pay-before-result) → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-2 — 단가 로컬 캐시 CQRS — [PR #57](https://github.com/Kimgyuilli/PeakCart/pull/57)

> 사가 클러스터 두 번째 strangler 단계(⑤ CQRS 로컬 캐시 교차, peel 없음). strangler-1 이 임시로 남긴 동기 `ProductPort.getUnitPrice`(주문 트랜잭션 내 Product 단가 동기 read)를 choreography CQRS 로 대체. 선행: ADR-0012 ⑤(Product 변경 이벤트 구독·Order 내 캐시)·:47/:48(파티션키=productId·필수 7필드)·:46(envelope)·D5(retention), ADR-0010 F3. **새 ADR 불필요**.
> 범위 결정: **단가만**. `verifyProductExists`(장바구니 검증)는 동기 `ProductPort` 로 의도적 잔존(완전 제거는 strangler-3/Product peel).

**완료 항목** (P1~P5):
- **P1** `product.updated` 계약+발행 — `:common` `ProductUpdatedPayload`(ADR-0012:48 필수 7필드 + 순서키 `version`), Product `@Version`, `ProductOutboxEventPublisher.publishProductUpdated`(aggregateId=productId·status 매핑·`ProductRepository.saveAndFlush` 후 version), `ProductCommandService` create/update/delete(discontinue) 발행, `KafkaConfig` `product.updated`(+dlq) 토픽.
- **P2** Order 로컬 가격 캐시 — Flyway V7(`product_price_cache`(product_id PK·unit_price·source_version·updated_at) + `products.version` ALTER + seed), `ProductPriceCache` 엔티티 + repository(`order/domain`) + JPA/Impl(`order/infrastructure`).
- **P3** `ProductPriceCacheConsumer`(`product.updated`, group `order-svc-product-updated-group`) — `IdempotencyChecker` 멱등 + `version` stale-skip, price·version 만 소비.
- **P4** `ProductPort.getUnitPrice` 제거(`verifyProductExists` 잔존), `ProductPortAdapter` 동일. `OrderCommandService` 가 `ProductPriceCacheRepository` 로 단가 read, 미스 시 `ORD-007`(동기 fallback 없음).
- **P5** 단위(발행자 7필드+version·status 매핑, OrderCommandService 캐시 read·미스, ProductCommandService 발행) + 통합(Testcontainers: create→발행→캐시·flush 경계 v0→v1·stale-skip·멱등·schemaVersion 호환·**e2e relay→@KafkaListener→createOrder OrderItem 스냅샷**·캐시미스 ORD-007).

**핵심 결정**:
- **순서 키 = Product `@Version` monotonic version** (plan 라운드2 #1): `OutboxEvent.eventId`(랜덤 UUID v4)는 사전순≠인과순서라 tie-breaker 부적합 → `@Version` 채택. 파티션키=productId(in-order) + version 비교 stale-skip.
- **flush 경계** (plan 라운드3 #1): `@Version` 은 flush 시 증가 → payload version 은 `saveAndFlush` 후 `getVersion()` 으로 읽음(seed=0 ↔ 첫 이벤트=0 충돌 회귀 방지, 통합테스트로 가드).
- **원자 upsert** (work 리뷰 #1): 2-step update→insert 의 `save()` UK 위반이 catch 밖(commit)에서 터져 "높은 version 만 적용"이 깨짐 → `INSERT ... ON DUPLICATE KEY UPDATE ... IF(:version>source_version,…)` 단일 원자 문장으로 교체.
- **payload = ADR-0012:48 7필드 전체**(축소 금지, status `ON_SALE→ACTIVE` 등 매핑), Order 캐시는 price·version 만 소비.
- **캐시 미스 = ORD-007 명시 실패**: seed + create-발행으로 정상경로 미스 제거, 잔여 경합만 명시적 실패(seam 완전 제거 — 동기 fallback 두면 getUnitPrice 잔존).

**검증**: `./gradlew test` 전체 BUILD SUCCESSFUL(가격캐시 통합 7/7). `getUnitPrice` 제거는 인터페이스 컴파일 가드(ArchUnit 인프라 부재).

**프로세스**: `/plan`(Codex 3회: 1차 4건[payload 계약 위반·tie-breaker·문구·retention] → 2차 4건[랜덤UUID→@Version·status 매핑·discontinue·seed] → 3차 1건[@Version flush 경계] 전체 반영) → `/work`(diff 리뷰 1회 P1:1/P2:1 → #1 원자 upsert·#2 e2e 테스트 전체 반영) → `/ship`([PR #57](https://github.com/Kimgyuilli/PeakCart/pull/57), 5 커밋). 부산물: PLAN-BLINDSPOTS B7(버전-가드 upsert 원자성 + @Version flush 경계).

**다음**: strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트, `verifyProductExists` 처리) → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-3 — 2-phase 예약 확정/해제 + 결제 게이트 — [PR #58](https://github.com/Kimgyuilli/PeakCart/pull/58)

> 사가 클러스터 세 번째 strangler 단계(④ Saga commit/보상 교차, peel 없음). 선행: ADR-0010 F2, ADR-0012 D3/④(payment.completed→예약 확정, commit-실패=환불 요청+운영 알림). **새 ADR 불필요**.
> 범위 결정: **2-phase 확정/해제 + 결제 게이트만**. `verifyProductExists` 캐시 전환·Product peel 은 후속.

**완료 항목** (P1~P7):
- **P1** 원장 `CONFIRMED` 종결 상태 + `confirmed_at`/`compensated_at` 컬럼(Flyway V8). `status VARCHAR(20)` 라 enum 추가는 DDL 불요.
- **P2** `markConfirmedIfReserved`(RESERVED→CONFIRMED)·`markCompensatedIfAbsent`(보상 1회성) 원자 CAS — `markReleasedIfReserved` 미러.
- **P3** `StockReservationService.confirm(orderId)` 상태 분기: RESERVED→CONFIRMED commit / CONFIRMED 멱등 no-op / **원장 없음=transient → throw(consumer 재시도)** / RELEASED·CANCEL_REQUESTED·FAILED → 보상.
- **P4** commit-실패(PAID_BUT_UNRESERVED) 보상 — 원장 `compensated_at` `orderId` 1회성 marker + 최초 1회 `SlackPort` 운영 알림(자동 환불 미존재 → 수동).
- **P5** `StockConfirmConsumer`(`payment.completed`, group `product-svc-payment-completed-group`) → confirm. release 와 의미 분리 위해 별도 consumer.
- **P6** 결제 게이트 — `Order.markPaymentRequested()`(전이불가 ORD-003 → 미확정 ORD-008(409) → 통과 시 `paymentRequestedAt` 기록), `OrderPortAdapter` 교체. 타임아웃 기준 `orderedAt`→`paymentRequestedAt`(V9 + 기존 PAYMENT_REQUESTED 행 backfill, `findExpiredPaymentRequested` null 폴백).
- **P7** 단위(confirm 분기·게이트 ORD-008/003·결제 게이트 전파) + 통합(payment.completed→CONFIRMED·확정후 release 보호·역순 race 보상·보상 멱등·confirm×2+release×1 동시성 수렴·consumer e2e·JPQL null 폴백).

**핵심 결정**:
- **race 를 막지 않고 검출+보상** (plan 라운드1 P0): confirm(`payment.completed`)과 release(`order.cancelled`/`payment.failed`)는 별도 토픽이라 무순서. confirm-우선 가정 대신, 확정 시점에 원장이 RELEASED 등이면 commit-실패로 검출해 보상으로 수렴(ADR-0012 ④). CONFIRMED 종결성으로 확정 후 지연 release 는 CAS 자연 no-op(판매분 보호).
- **타임아웃 기준 = paymentRequestedAt** (plan 라운드1 P1): `orderedAt` 기준은 생성 15분 경과 주문 결제 시 진행 중 취소 race → 결제 요청 시점 기준 전환 + 기존 행 backfill/null 폴백으로 마이그레이션 회귀 방지.
- **보상 멱등 = 원장 compensated_at CAS** (plan 라운드2 P1): 신규 테이블 대신 `orderId` 1회성 컬럼 CAS — DLQ 재발행(새 eventId, `processed_events` 우회) 에도 알림 1회.
- **게이트 분류**: 전이 검사(ORD-003 영구) 우선 → 예약 확정 검사(ORD-008 409 retryable). HttpStatus 가 곧 API 재시도 계약.

**검증**: `./gradlew test` 전체 BUILD SUCCESSFUL. 동시성(confirm×2+release×1)이 `CONFIRMED+복구0+보상0` 또는 `RELEASED+복구1+보상1` 한쪽으로만 수렴 확인.

**프로세스**: `/plan`(Codex 2회: 1차 5건[P0×2 ADR-0012 ④ 보상경로·cross-topic race / P1×2 타임아웃 race·검증 / P2 ORD 분류] 전체 반영 → 2차 4건[V9 backfill·confirm retry 의미·보상 멱등 키·동시성 테스트] 전체 반영) → `/work`(diff 리뷰 1회: 1차 180s 타임아웃→480s 재시도, aggregate=ok P0:0/P1:0/P2:3 테스트 갭 전체 반영) → `/ship`([PR #58](https://github.com/Kimgyuilli/PeakCart/pull/58), 5 커밋).

**다음**: `verifyProductExists` 캐시 전환 → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-4 — `verifyProductExists` 로컬 캐시화 — [PR #61](https://github.com/Kimgyuilli/PeakCart/pull/61)

> 사가 클러스터 마지막 strangler 단계(⑤ 로컬 캐시 활용, peel 없음). Order→Product 에 유일하게 남은 동기 호출(`ProductPort.verifyProductExists`, 장바구니 추가 검증)을 제거해 Order↔Product production 동기 결합을 0 으로 만든다. 선행: ADR-0010 F2(동기 결합 지목), ADR-0012 ⑤(로컬 캐시 CQRS). **새 ADR 불필요** — roadmap §58.
> 범위 결정: **seam 제거만**. Product→Order+Payment peel(② DB 분리 동반)·full `product_cache`(name/status/stock)와 장바구니 조회 CQRS 조합은 후속.

**완료 항목** (P1~P5):
- **P1** `ProductPriceCacheRepository.existsByProductId` 추가(`@Id`=productId → `existsById` 위임). 별도 스키마 변경 없음.
- **P2** `CartCommandService.addItem` 재배선: `ProductPort.verifyProductExists` → 로컬 캐시 존재성. 캐시 미스(미존재 or `product.updated` 전파 전)는 신규 `ORD-009`(409, 재시도) 로 거절. `ProductPort` 의존 제거.
- **P3** `ProductPort`(order.application.port) + `ProductPortAdapter`(product.infrastructure.adapter) 삭제 → 두 패키지 디렉토리 소멸.
- **P4** 단위(히트 성공/미스 ORD-009)·통합(캐시미스→addItem ORD-009 재작성, 캐시히트 e2e 회귀 가드)·컨트롤러(409/code/message 단언) 테스트.
- **P5** `assertNoOrderProductSourceCoupling` custom Gradle source-scan 가드(src/main 한정) + `check` 연결.

**핵심 결정**:
- **존재성 = 로컬 가격 캐시 존재성**: `product_price_cache`(strangler-2, V7 이 기존 상품 전량 seed)에 있으면 주문 가능. cold-start gap 없음. addItem 의 eventual-consistency 창은 `createOrder` 의 가격 미스(ORD-007) 창과 대칭 — 새 위험 아님.
- **ORD-009 (외부 계약 변경)**: addItem 미스 응답이 404(PRD-001)→409(ORD-009, 재시도 시맨틱). ORD-007(가격)과 분리. 컨트롤러 슬라이스 테스트로 계약 고정 + `04-design-deep-dive` 에러코드 표 반영.
- **경계 가드 = source-scan (ArchUnit 미도입)**: 기존 `assertNoServiceProjectDeps` 패턴 따라 새 의존성 없이 `src/main` order↔product 상호 참조 금지. `src/test` 의 합법적 Product 타입 시드는 제외(production 한정).

**검증**: `./gradlew assertNoOrderProductSourceCoupling test` BUILD SUCCESSFUL(7m20s). seam 잔존(src/main+src/test)·order↔product FQCN 경계 전부 0건 → Order↔Product production 동기 결합 소멸(Product peel 선행조건 충족).

**프로세스**: `/plan`(Codex 2회: 1차 4건[P1×2 ORD-009 외부계약+CartControllerTest·grep 검증 강화 / P2×2 BLINDSPOTS 처분·peel-ready 스코프] 전체 반영 → 2차 2건[ArchUnit→source-scan 가드 전환·production 한정] B안 반영) → `/work`(diff 리뷰 1회: aggregate=ok, P0:0/P1:0/P2:1 — 계획 P2 범위 내 04-design 누락분 반영) → `/ship`([PR #61](https://github.com/Kimgyuilli/PeakCart/pull/61), 5 커밋).

**다음**: Product→Order+Payment peel(② DB 분리 동반·V7 seed → product.updated replay 전환) / PR3.

---

## Product peel — product-service 모듈 분리 (첫 *발행* 서비스) — [PR #62](https://github.com/Kimgyuilli/PeakCart/pull/62)

> 사가 클러스터 strangler 완결(Order↔Product 동기 결합 0) 이후 Product 도메인을 독립 `product-service` 모듈로 peel. notification(#53)·user(#55) peel 선례를 잇는 **첫 *발행* 서비스 분리**. 선행 ADR-0010 F2·ADR-0011·ADR-0012·ADR-0014. **새 ADR 불필요**.
> 범위 결정(사용자 게이트): **모듈 peel 만. DB 물리 분리는 범위 외** — user/notification 선례대로 공유 root DB·Flyway runtime disabled 유지, FK 유지. 실 DB-per-service(FK drop·별 datasource)는 5개 서비스 peel 후 ②로 일괄.

**완료 항목** (P1~P8):
- **P1~P2** product-service 모듈 신설(settings/build.gradle) + Product 도메인 `git mv`(이력 보존) + ProductApplication(@EnableScheduling).
- **P3** root 전속 `global.outbox` 실행 7종 + `global.idempotency` 5종 + `ShedLockConfig` **복제**(`:common`은 payload DTO만 보유 → 발행 서비스 전속, notification idempotency 복제 선례), `CacheConfig` 이관.
- **P4** 공유 DB poller 소유권 분리: `OutboxEventJpaRepository.findPendingEvents(aggregateTypes,…)`+`countByStatusAndAggregateTypeIn` allowlist, `@SchedulerLock name=${app.outbox.lock-name}` → root=`ORDER,PAYMENT`/`rootOutboxPollingJob`, product=`PRODUCT`/`productOutboxPollingJob`. 발행 경로 + backlog gauge 양쪽 분리.
- **P5** product-service 횡단 배선: `ProductSecurityConfig`(@EnableMethodSecurity, @PreAuthorize 구동)·`ProductKafkaConfig`·Cache/Redis/Slack·application*.yml.
- **P6~P7** Product 테스트 13개+ProductFixture 이관(B3 단일 소비자), root 통합테스트 4종 디커플(native-insert 시드·payload 직접 주입·findPendingEvents 시그니처), 관측성 products-cache 검증 product-service 이관.
- **P8** 경계 가드: `assertNoDuplicateGlobalFqcn` *-service 동적 구성·`assertNoOrderProductSourceCoupling` 스캔 경로 갱신.

**핵심 결정**:
- **outbox/idempotency 복제 vs 공통 이관**: 복제 채택(notification 선례·guard 가 다른 앱 classpath 공존 허용). 공통 추상화는 root 까지 건드리는 큰 리팩터라 peel 범위 밖.
- **공유 DB poller 경합 차단**: 복제 poller 가 같은 `outbox_events` 를 보므로 aggregateType allowlist + ShedLock 이름 분리로 자기 도메인 이벤트만 발행·집계(Codex 2차 plan-review P1).
- **DB 분리 범위 외**: 물리 분리는 5개 서비스 peel 후 일괄(②)이 FK drop/baseline 을 한 번에 정리해 안전.

**검증**: 전체 멀티모듈 `./gradlew build` BUILD SUCCESSFUL(7m45s, fresh) + 가드 3종 통과(assertNoDuplicateGlobalFqcn 동적 product-service 포함). product-service bootJar 산출.

**프로세스**: `/plan`(Codex 2회: 1차 4건[P1×3 outbox 실행세트 복제 누락·테스트 7→13·가드 하드코딩 / P2 검증 강화] 전체 반영 → 2차 1건[공유 DB poller 경합] 반영) → `/work`(빌드 2회 적발: string-level 결합 4부류[락이름·URL·캐시·aggregateType] + 이관 통합테스트 flyway → 수정 / diff 리뷰 P2 2건[gauge 소유권 누수·발행 검증 갭] 반영) → `/ship`([PR #62](https://github.com/Kimgyuilli/PeakCart/pull/62), 4 커밋). 부산물: PLAN-BLINDSPOTS B1b(역의존 스윕은 FQCN import 외 string-level 식별자도 쓸어라).

**다음**: Order↔Payment 동기 결합 제거(strangler) → Order+Payment peel(root app 소멸) → PR3.

## 사가 클러스터 strangler-5 — Order↔Payment 동기 결합 제거 (peel 선행) — [PR #63](https://github.com/Kimgyuilli/PeakCart/pull/63)

> Order+Payment peel 의 **선행 strangler**. Product 가 ProductPort 동기 빈으로 peel 불가였던 것과 동형으로, **Payment 가 Order 의 동기 빈 `OrderPort`**(`verifyOrderOwner`·`transitionToPaymentRequested`)에 묶여 두-모듈 peel 불가였다. 모놀리스 안에서 seam 을 이벤트+payment-로컬 상태로 제거해 Order↔Payment src/main 상호 참조 0 달성. 실제 peel·root 소멸은 후속 PR-B. 선행 ADR-0010·ADR-0012·ADR-0014, **새 ADR 불필요**(GP-1 — ADR-0012 §D4 refine 으로 흡수).

**완료 항목** (P1~P8):
- **P1** Flyway expand-contract: V10(payments user_id/version/ready, nullable+backfill)·V11(orders payment_requested_pending)·V12(payment_cancellations). user_id NOT NULL 은 후속 V13+(코드 배포·lag 0 확인 후).
- **P2** Seam 1: `verifyOrderOwner` → `Payment.verifyOwner()`(payment-로컬 userId, PAY-007). `order.created` payload 의 userId 를 Payment 에 저장.
- **P3** Seam 2: `transitionToPaymentRequested` → `payment.requested` 이벤트 발행(+KafkaConfig topic/DLQ). Order 가 소비해 PAYMENT_REQUESTED 전이.
- **P4** 게이트 payment-로컬 복원: reserve→pay(`stock.reservation.result`→`ready_for_payment`, PAY-008)·취소(`order.cancelled`→`cancelBeforePayment`, PAY-009)·`@Version` 동시성·선도착 수렴(order pending marker + payment_cancellations marker)·APPROVED-후-취소 SlackPort 알림(§D3 ④).
- **P5~P6** `OrderPort`/`OrderPortAdapter` 삭제 + 가드 `assertNoOrderPaymentSourceCoupling` 신설.
- **P7** ADR-0012 §D4 refine: payment.requested 행 + order.cancelled Payment consumer + 게이트 이전 노트.
- **P8** 단위/슬라이스 테스트(도메인 게이트·consumer·발행·이벤트 역전·동시성).

**핵심 결정**:
- **게이트 2조건 payment-로컬 분해**: 동기 `markPaymentRequested`(PENDING + reservationConfirmedAt) 게이트를 reserve(ready 플래그)·취소(로컬 status) 2조건으로 복원. 잔여 lag race 는 §D3 ④ 보상 수렴(회귀 0 아닌 "수렴").
- **이벤트 역전 누수 0**: payment.requested 선도착 → order 영속 marker(confirmReservation 수렴); order.cancelled 선도착 → payment_cancellations 영속(Payment 생성 시 CANCELLED 적용) — DLQ 초과에도 silent-charge 누수 0(throw-retry 만으로 불충분, work 2차 리뷰 발견).
- **DB 분리 범위 외**: peel·root 소멸·DB 물리 분리는 후속.

**검증**: `./gradlew :build` BUILD SUCCESSFUL(통합+가드 3종, 4회 그린). order↔payment src/main 상호 참조 0(신규 가드).

**프로세스**: `/plan`(Codex 3회: 1차 6건[P0 비동기창·reserve→pay·D4·backfill 등] → 2차 4건[V11/V12 expand-contract·선도착·CAS·D4 group] → 3차 3건[marker 영속·V11 경계·topic/DLQ] 전체 반영) → `/work`(diff 리뷰 2회: 1차 4건[역전·보상연결·Flyway순서·consumer테스트] → 2차 2건[**영속 cancellation marker**·계획서 정정]) → `/ship`([PR #63](https://github.com/Kimgyuilli/PeakCart/pull/63), 4 커밋). 부산물: PLAN-BLINDSPOTS **B9**(이벤트 역전 게이트 누수 — 종료/취소 이벤트가 생성 이벤트 선도착 시 영속 marker 필요).

**다음**: Order+Payment peel(root app 소멸, 본 strangler 계획서를 입력으로) → PR3(Dockerfile/CI/k8s). 이후 ② DB 물리 분리 일괄.

---

## Order peel PR-a — order-service 모듈 분리 ([#64](https://github.com/Kimgyuilli/PeakCart/pull/64))

> 마지막 두 도메인 Order/Payment peel 의 2 PR 중 **PR-a (Order peel)**. root 는 Payment+global 유지·계속 부팅. 선행 ADR-0010/0011/0012/0014 (새 ADR 불필요 — 경계/구조/이벤트 결정 보유).

**완료 항목** (P1~P8):
- **P1·P2** `order-service` 모듈 신설 + order 도메인(`com.peekcart.order.*`) 43파일 → order-service(git mv). `OrderApplication`(@EnableScheduling — outbox poller·OrderTimeoutScheduler 구동).
- **P3** outbox 실행세트 7 + idempotency 5 + ShedLockConfig 를 root→order-service **byte-identical 복제**(root 는 payment 위해 유지, product peel 선례). `OrderKafkaConfig` — **producer-owns-topic**: `order.created`/`order.cancelled`(+`.dlq`) NewTopic 4 + listener factory/error-handler.
- **P4** root outbox poller allowlist `ORDER,PAYMENT`→`PAYMENT`(order 자가발행 → 공유 DB 3 poller disjoint: order/payment/product).
- **P5** `OrderSecurityConfig`(검증 전용·발급 아님) + data-redis **무조건**(ADR-0014 common-auth blacklist fail-closed) + `OrderApplicationTests` 부팅 스모크.
- **P6** order 테스트 16 + OrderFixture + OutboxPollingServiceTest 이관. presentation 테스트 `SecurityConfig`→`OrderSecurityConfig`.
- **P7** root 통합테스트 4개 payment-observable 재작성 — `OutboxKafka`/`Idempotency`(cross-service 결합 제거, KafkaTemplate 직접 produce)·`ObservabilityMetrics` probe `ORDER`→`PAYMENT`·`RootContextBootSmoke` `/api/v1/orders`→`/api/v1/payments`.
- **P8** 가드(`assertNoOrderPaymentSourceCoupling`·`assertNoOrderProductSourceCoupling`) order 스캔경로 → order-service.

**핵심 결정**:
- **producer-owns-topic (NewTopic 분산)**: 1차 plan 의 "order-service 전 토픽 단독 owner" 가 ADR-0011/0012 발행-서비스-전속 원칙과 충돌(plan 2차 리뷰) → order=order.*·payment=payment.*·product=product.* 로 분산. PR-b 에서 payment/product NewTopic 신설.
- **root 테스트 디커플 = payment-observable**: order↔payment 소비 플로우가 cross-service 가 되어 단일 root 컨텍스트로 검증 불가 → root 통합테스트는 payment 발행/소비로 재작성, order-specific 검증은 order-service 로 이관(메모리 `service_peel_root_test_decouple` 선례).
- **DB 미분리**: 모듈 경계만. root 소멸·DB 물리분리는 PR-b/②.

**검증**: `:order-service:test`(132+3) · `:test`(root payment+global) · 가드 4종(`assertNoDuplicateGlobalFqcn` order-service 자동편입·복제 FQCN 중복 0) · 전체 compile — 전부 그린. order-service 독립 컨텍스트 부팅(Redis blacklist·단일 SecurityFilterChain·Kafka listener 4종).

**프로세스**: `/plan`(Codex 2회: 1차 7건[Redis 무조건·Toss·NewTopic owner·B5 cold-start·@EnableScheduling·3-poller harness·테스트 수량] → 2차 3건[ObservabilityMetrics probe·NewTopic producer 분산·02-arch 토픽 동기화] 전체 반영) → `/work`(diff 리뷰 1회 2건[OutboxPollingServiceTest allowlist·OrderApplicationTests 스모크] 반영) → `/ship`([PR #64](https://github.com/Kimgyuilli/PeakCart/pull/64), 5 커밋). 부산물: PLAN-BLINDSPOTS **B10**(@SpringBootTest 통합테스트를 flyway-disabled 서비스 모듈로 옮기면 per-test flyway override 필요 — SchemaManagementException).

**다음**: **PR-b — Payment peel + root 해체**(P9~P18: payment-service 분리·root src 삭제·boot앱→aggregator·잔여 global 테스트 rehome·B5 런타임 마이그레이션 소유권·product-service NewTopic 신설) → PR3(Dockerfile/CI/k8s).

---

## Payment peel + root 해체 PR-b — 5개 서비스 풀 분해 완료 ([#65](https://github.com/Kimgyuilli/PeekCart/pull/65))

> Order/Payment peel 의 2 PR 중 **PR-b (마지막)**. payment 도메인을 payment-service 로 떼고 **root 모놀리스 app 을 해체**한다. ADR-0010 §5 의 5개 서비스 풀 분해 달성 — root 는 빌드/가드 aggregator 로 전환.

**완료 항목** (P9~P18):
- **P9·P10** `payment-service` 모듈 + payment 도메인 26파일 → payment-service(git mv). `PaymentApplication`(@EnableScheduling) + yml(PAYMENT allowlist·Toss placeholder 이관).
- **P11** outbox/idempotency/ShedLock 복제. `PaymentKafkaConfig`(payment.* NewTopic 6). **product-service NewTopic 4 신설**(root 무임승차 해소). **root `global/*`·`PeekcartApplication`·`src/` 전체 삭제** → root src 0 files.
- **P12** `PaymentSecurityConfig`(webhook 공개) + `PaymentApplicationTests` 부팅 스모크.
- **P13** **order-service Flyway 런타임 migrator 승계**(B5 — root 역할 인수).
- **P14·P15** payment 테스트 9 + 통합 5(payment-observable·ShedLock `paymentOutboxPollingJob`·ObservabilityMetrics `application=payment-service`) → payment-service. 유닛 5(SUT=:common) → `:common` test. RootContextBootSmoke 삭제. 전 @SpringBootTest flyway override(B10).
- **P16** **root build.gradle: boot앱 → aggregator**(bootJar disabled·런타임 deps 제거·가드 payment 경로 retarget).
- **P18** `02-architecture.md` 토픽 6→7(`payment.requested`)·producer-owns-topic 동기화.

**핵심 결정**:
- **root = 빌드 aggregator**: 5개 서비스 독립 모듈화로 root src 가 비어, bootJar 비활성·런타임 deps 제거. `./gradlew build` = 5 서비스 bootJar 산출 + root SKIPPED.
- **공유 스키마 migrator 승계(B5 신규 블라인드스팟)**: root app 소멸로 런타임 Flyway 적용 주체 상실 → order-service 가 승계(기존에도 타 서비스는 root 선마이그레이션 의존). cold-start 순서/readiness 는 PR3.
- **producer-owns-topic 완성**: product-service 가 root 무임승차하던 자기 토픽 생성 책임 인수(NewTopic 4).
- **root 단일 이미지 사망**: root app 소멸로 Dockerfile(`COPY src/`·`app.jar`)·CI Docker smoke 가 깨짐 → 제거(메모리 multimodule_dockerfile_context 예측). per-service 이미지는 PR3.

**검증**: `:common:test`·`:order-service:test`(migrator)·`:payment-service:test`(9+통합5+스모크)·`:product-service:test`(NewTopic) 그린 · 가드 4종(5 서비스·FQCN 중복 0) · `build -x test` = 5 서비스 bootJar·root SKIPPED · root src 0 files.

**프로세스**: `/work`(diff 리뷰 1회 2건[**root Dockerfile/CI 사망 봉합**·02-arch payment.* 정정] 반영) → `/ship`([PR #65](https://github.com/Kimgyuilli/PeekCart/pull/65), 7 커밋). 부산물: PLAN-BLINDSPOTS **B10**(@SpringBootTest 를 flyway-disabled 서비스 모듈로 이동 시 per-test flyway override 필요).

**다음**: 구현 ① 잔여 = **PR3(Dockerfile/CI/k8s — per-service 이미지·CI 매트릭스)**. 이후 ② DB 물리 분리(order-service 전환기 migrator 정리)·③~⑥.

## PR3a — 서비스별 Dockerfile + CI 이미지 + image-contract-lint ([#66](https://github.com/Kimgyuilli/PeakCart/pull/66))

> 구현 ① PR3(배포 표면 per-service 재구성) 의 첫 조각. 단일 `peekcart` 전제의 이미지/CI 를 서비스별로 재구성한다. k8s 매니페스트는 PR3b, 관측성 재설계+ADR-0015 는 PR3c 후속. 계획서 `docs/plans/task-impl1-pr3-dockerfile-ci-k8s.md`.

**완료 항목** (P1·P2·P3 — 3축 단일 계획 중 PR3a):
- **P1** 단일 `Dockerfile` + `ARG SERVICE`(멀티모듈 COPY 8모듈·`:${SERVICE}:bootJar`·base 이미지 digest 고정 L-016a). 5개 서비스 `docker build` 검증.
- **P2** `ci.yml` 이미지: `images`(build/smoke·contents:read) + `publish`(main push 한정·packages:write) **job 분리**. smoke 이미지를 `docker save`→artifact→`load` 로 publish 전달(재빌드 0). `docker-health-smoke.sh` 공유 스키마 선행 마이그레이션 훅(flyway 11.7.2@digest 이미지).
- **P3** `image-contract-lint.sh` per-service(D-015): canonical 5서비스 고정·`images`/`publish` matrix 일치·서비스별 base/gke 3-way. 전환기는 `IMAGE_CONTRACT_TRANSITION=1` SUSPENDED.

**핵심 결정**:
- **단일 Dockerfile + ARG**(B5): 5 서비스를 1 Dockerfile 로 — COPY 표류를 단일 지점화. settings.gradle 모듈 변경 시 동기 + 로컬 docker build 검증(memory: multimodule_dockerfile_context).
- **smoke 마이그레이션(Codex GP-2)**: 비-order 서비스(Flyway disabled+validate)가 빈 DB 에서 죽지 않도록 smoke 가 앱 전에 공유 스키마(V1~V12)를 적용. **마이그레이션 정본 = 공식 flyway Docker 이미지** — root gradle `flywayMigrateShared` 가 깨져 있음(flyway 플러그인 mysql DB 플러그인 미해석). 런타임 migrator(order-service, Spring Boot Flyway)는 정상.
- **D-015 canonical 앵커**: lint ground-truth 를 CI matrix 가 아닌 고정 5서비스로 — CI matrix 를 자기 ground-truth 로 쓰면 "서비스 축소 false-green" 순환. images↔publish matrix 드리프트도 검출.
- **digest(L-016a/D-016)**: base·flyway 이미지 digest 고정 + publish 가 push 후 registry digest 산출(비면 실패).

**검증**: 5/5 `docker build` · notification-service smoke(profile k8s·flyway V1~V12·`/actuator/health` 200) · image-contract-lint 두 모드(차단/SUSPENDED)·canonical matrix 일치 · kustomize-namespace·servicemonitor-selector lint 그린(k8s 미변경).

**프로세스**: `/plan`(Codex 리뷰 5건[smoke 마이그레이션·ADR-0015 신규·alert lint·secret 표·cold-start initContainer] 반영, GP-1 으로 ADR-0009→ADR-0015 supersede 결정) → `/work`(diff 리뷰 **3 라운드** 수렴: image-contract-lint false-green 을 checked==0→부분-매니페스트→canonical matrix 드리프트 3중으로 봉합, ci.yml job 분리, digest 강제) → `/ship`([PR #66](https://github.com/Kimgyuilli/PeakCart/pull/66), 2 커밋).

**후속 부채**: `flywayMigrateShared` gradle 태스크 수복(또는 폐기) — D- 승격 검토. cold-start initContainer·k8s per-service 매니페스트는 PR3b, 관측성 재설계·ADR-0015 작성은 PR3c.

**다음**: **PR3b**(k8s base/overlays per-service: 5 Deployment/Service/ConfigMap/Secret/ServiceMonitor·initContainer cold-start·image-contract-lint 전환기 flag 제거) → **PR3c**(관측성 per-service: alert by-clause·dashboard `$application` 변수·observability lint 2종 재활성·ADR-0015 작성+ADR-0009 Partially Superseded).

## PR3b — k8s base/overlays per-service 재구성 ([#67](https://github.com/Kimgyuilli/PeakCart/pull/67))

> 구현 ① PR3 둘째 조각. 단일 `peekcart` 를 전제하던 k8s 배포 표면을 5서비스 per-service 로 재구성한다. 관측성 alert/dashboard 재설계·ADR-0015 는 PR3c. 선행 ADR-0004/0005/0006/0007/0010/0014(새 ADR 불필요).

**완료 항목** (P4~P9·P14):
- **P4·P9** `k8s/base/services/<svc>/{deployment(+Service)·configmap·secret·servicemonitor}` ×5. 비-order 4서비스 deployment 에 `wait-for-order-migration` initContainer(curl digest 고정, `order-service` readiness 폴링 — 공유 DB 전환기 cold-start ordering). `base/kustomization` 20 리소스.
- **P5** ConfigMap/Secret per-service. product configmap `PEEKCART_CACHE_ENABLED`(D-002 토글, product 전용). **Slack 게이팅**: `:common SlackFallbackConfig` no-op 빈(`@ConditionalOnMissingBean`+`@ConditionalOnProperty(slack.noop-fallback.enabled)`) ↔ real(`@ConditionalOnProperty(slack.webhook.url)`) 상호배타. notification=k8s no-default webhook fail-fast, product/order/payment=base noop-fallback. payment Toss k8s no-default fail-fast.
- **P6·P7** minikube/gke overlay per-service strategic-merge patch ×10씩. gke `images[]` 5 entry(AR rewrite)·**order-service 단일 HPA**(GP-2 #4·로드맵 §16, 5균일 기각). gke README per-service 갱신.
- **P8** `servicemonitor-selector-lint` canonical **count==5 강제**(0개 vacuous-green 차단)·`image-contract-lint` **full 5/5**(ci.yml `IMAGE_CONTRACT_TRANSITION` 제거).
- **P14** D-016 `promote-images.sh`(GHCR→AR 승격·crane/docker·AR digest 산출+`kustomize edit set image @digest` 명령 출력·dry-run/help).

**핵심 결정**:
- **Slack presence-based 함정 제거(GP-2 loop1~3 핫스팟)**: `@ConditionalOnProperty(name=...)` 가 placeholder 기본값에 항상 매치돼 fail-fast·no-op 둘 다 깨지던 것을 base yml 기본값 정리 + 명시 property 상호배타로 봉합. notification fail-fast(silent 알림 유실 방지)·나머지 no-op. ADR-0007 정합(noop-fallback=base 동작정책·webhook/Toss=프로파일 자격증명).
- **자격증명 fail-fast(work GW-2 #2/#3)**: committed Secret 에 SLACK/TOSS placeholder 미포함(operator/external 주입) → 렌더 산출에 stub 누출 0. `docker-health-smoke.sh` 가 그 주입을 dummy 런타임 값으로 시뮬레이션(렌더엔 안 샘).
- **DB 인프라 secret 분리(work GW-2 P0)**: 단일 `peekcart-secret` 분해로 MySQL `secretKeyRef` dangling → `infra/mysql/secret.yml`(mysql-secret). B1 스윕이 놓친 infra→app-secret 간선(→ PLAN-BLINDSPOTS B1b 반영).
- **cold-start = order-service Boot Flyway 정본**(깨진 root `flywayMigrateShared` 재사용 금지). DB 미분리(② 이연) — initContainer 는 전환기 처분.

**검증**: `kubectl kustomize` minikube/gke 렌더(5 Deployment/Service/ConfigMap/Secret/ServiceMonitor·1 HPA)·lint 3종(namespace·image-contract full 5/5·servicemonitor count==5)·**notification+payment 이미지 build+smoke**(fail-fast+dummy e2e 200)·`./gradlew build` 전체(5서비스+통합테스트) 그린·`SlackPortConfigTest` 4 케이스·promote help/dry-run.

**프로세스**: `/plan`(Codex 3 loop: 7→4→2건 수렴, Slack 게이팅 반복 핫스팟·B6 함정²·B1b 신설) → `/work`(diff 리뷰 2 loop: 1차 P0:1[mysql-secret]+P1:3[SLACK/TOSS placeholder·promote digest]+P2:1 → 2차 0건 수렴) → `/ship`([PR #67](https://github.com/Kimgyuilli/PeakCart/pull/67), 5 커밋). 부산물: PLAN-BLINDSPOTS **B6 함정²**(presence-based 조건+기본값)·**B1b**(infra→공유리소스 이름 간선).

**후속 부채**: PR3c(관측성 per-service 재설계+ADR-0015 작성+ADR-0009 Partially Superseded)·D-016 full lint-digest 강제(렌더 산출 @sha256 필수)·`flywayMigrateShared` 수복.

**다음**: **PR3c** → 구현 ① 종료 → ② 서비스별 DB 물리 분리(order-service 전환기 migrator·initContainer 정리).

## PR3c — 관측성 per-service 재설계 + ADR-0015 ([#68](https://github.com/Kimgyuilli/PeakCart/pull/68))

> 구현 ① PR3 **마지막 조각**. 단일 `application=peekcart`/`service=peekcart` 를 전제하던 관측성 표면(alert/dashboard/observability lint 2종)을 per-service 로 재설계하고 ADR-0015 로 명문화. 본 PR 머지로 **구현 ① PR3(배포 표면 per-service) 전체 종료**.

**완료 항목** (P1~P6):
- **P1** **ADR-0015 신규**(`docs/adr/0015-observability-per-service-contract.md`) + ADR-0009 `Partially Superseded by ADR-0015`(Status 헤더만, 본문 불변) + README INDEX + CLAUDE.md SSOT 줄 보강. **범위 정정(Codex plan #2)**: ADR-0009 §Decision "Phase 4 owner" 컬럼은 이미 per-service 결정제 → ADR-0015 는 뒤집기 아닌 **현-위치 서술/D5-V1·V2 모놀리스 전제/S5 단일경로 정정 + 비준**(무효화 범위 명시).
- **P2** `grafana-alerts.yml` 8 rule per-service: high-error-rate/slow-response = 5서비스 정확일치 regex `application=~"..."` + `by (application)`(ratio 보존), target-down = `count by (service)`, scrape-absent = **5서비스 equality matcher rule 분할**(`absent()` by-clause 불가, ground truth = k8s Service `metadata.name`). annotation per-service 식별(`{{ $labels.application/service }}`).
- **P3** api-jvm·kafka-lag dashboard `$application` **custom 5서비스 변수**(Codex work #1 — `up{}` 엔 application 라벨 부재로 `label_values(up)` 빈 드롭다운 버그 → custom 고정) + panel query `application=~"$application"`. pod-resources 는 namespace 기반(대상 외).
- **P4** `observability-ssot-lint.sh` per-service: 5서비스 `<svc>-service/application.yml` 정본(`EXPECTED_SERVICES` 고정) + D5-V2 태그값=모듈명 + MeterFilter owner=`peekcart-common-observability/.../MetricsConfig.java`.
- **P5** `observability-promql-lint.sh` 재작성: application set(5)·Service name set(5) ground truth, `by(application)` coverage 강제(단일 equality 실패), 필수 alert uid 8개 존재 검증, scrape-absent namespace 검사, **promtool PromQL syntax**(미설치 시 exit 2, balance 대체 금지).
- **P6** `ci.yml` lint 2종 재활성 + promtool 설치 step + 단일 `peekcart` 라벨 sweep 가드(escaped-quote + service 양쪽).

**핵심 결정**:
- **ADR-0015 = 비준, 뒤집기 아님**: ADR-0009 §Decision per-service owner 결정은 유효. 무효화는 모놀리스 현-위치 서술(root yml·`base/services/peekcart`·`application=peekcart` 회귀검증)·D5-V1/V2 단일 yml 전제·S5 단일 경로로 한정(README immutable 정합).
- **dashboard 변수 = custom 고정**(work #1): `up{}` series 의 라벨은 scrape 메타(namespace/service/pod)뿐 — `application` 은 Micrometer 앱 메트릭 전용. `label_values(up, application)` 은 빈 드롭다운 런타임 버그 → 5서비스 custom 고정(ADR-0015 정본 일치).
- **lint false-green 3중 봉합**(work #2/#3/#4): `EXPECTED_SERVICES` 5 정본 == 발견집합 선검증(glob 축소 차단)·필수 alert uid 존재 검증(rule 삭제 차단)·scrape-absent namespace 검사(타 NS 가림 차단).
- **scrape-absent ground truth = Service `metadata.name`**(plan #2·work #2): `up{service=}` 라벨은 selector app 값 아닌 Service 이름 의미.

**검증**: lint 5종 그린 + **negative test 6종**(단일 equality·regex 집합 불일치·PromQL syntax 깨짐·필수 uid 부재·scrape-absent namespace 부재·서비스 정본 누락 → 전부 exit≠0, false-green 차단) + sweep clean(escaped+service) + alert YAML/dashboard JSON 파싱. Java/gradle 입력 무변경 → build 불변(회귀 0).

**프로세스**: `/plan`(Codex 2 loop: 5→3건 수렴 — scrape-absent absent() 제약·ADR 범위 정정·PromQL syntax·coverage·sweep) → `/work`(diff 리뷰 1 loop 4건[P1:3 dashboard 변수 버그·lint 필수uid·namespace + P2:1 정본고정] 전부 반영, negative test 자체검증) → `/ship`([PR #68](https://github.com/Kimgyuilli/PeakCart/pull/68), 4 커밋). 부산물: PLAN-BLINDSPOTS **B11**(escaped-quote/형제라벨 sweep false-green).

**다음**: 🎯 **구현 ① PR3 전체 종료**(이미지/CI #66 · k8s #67 · 관측성 #68) → 구현 ② 서비스별 DB 물리 분리(order-service 전환기 migrator·cold-start initContainer 정리). 후속 비차단: D-016 full lint-digest·`flywayMigrateShared` 수복·alert delivery(L-004).
