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

---

## 구현 ② 서비스별 DB 분리 — PR1 (교차 도메인 FK 드롭) — [#69](https://github.com/Kimgyuilli/PeakCart/pull/69)

> 구현 ①(5서비스 풀 분해) 종료 후, 5서비스가 단일 공유 DB(`mysql/peekcart`)를 쓰는 상태를 DB-per-service 로 닫는 작업의 1단계. 물리 스키마 분리(PR2) 전에 교차 도메인 FK 를 먼저 제거하는 저위험 선행 단계. (ADR-0012 §D1)

**작업**:
- **P1** `V13__drop_cross_domain_fks.sql` — 교차 도메인 FK 6개 드롭(`fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`). user_id/product_id/order_id 컬럼은 유지(ID 참조 보존). 동일 스키마 내 FK(refresh_tokens/addresses→users·products→categories·inventories→products·cart_items→carts·order_items→orders)는 유지.
- **P2** 소유 경계 검증 — 5서비스 전부 자기 소유표만 `@Table` 매핑(교차 `@Table`/`@SecondaryTable` 0, nativeQuery 는 order 자기 `product_price_cache` 뿐).
- **P3** `./gradlew build test` 8모듈 BUILD SUCCESSFUL(9m46s) — FK 드롭 후 통합테스트 회귀 0.

**핵심 결정**:
- DB-per-service 에서 교차 도메인 FK 는 유지 불가(타 스키마 참조) → 물리 분리 전 선제거. 무결성은 이벤트 + 로컬 캐시(ADR-0012 D2/D3)로 이미 대체됨(strangler #56~#63 수용) → DB-level 교차 FK 는 중복 안전장치라 제거 안전.
- forward-only 단일 마이그레이션(V13, 전환기 order-service migrator 적용). 물리 스키마 분리·Flyway 모듈화는 PR2 이연.

**프로세스**: `/plan`(Codex 2 loop: 5→5건 수렴 — ADR-0012 D1 표↔코드 드리프트(stock_reservations/payment_cancellations)·전환 모드·B1b k8s 리소스명 스윕·D5 fail-fast·물리격리 판정 SQL / 거버넌스: stock_reservations 모델변경은 PR2 ADR-0016 분리) → `/work`(diff 리뷰 0건 자동통과) → `/ship`([PR #69](https://github.com/Kimgyuilli/PeakCart/pull/69), 2 커밋). consistency precheck warnings(ADR-0016 미존재 = PR2 산출물 선참조) 사유 기록 후 진행.

**다음**: PR2 — Flyway per-service 모듈화 + 물리 스키마 분리(1 인스턴스 + 5 스키마: `peekcart_<svc>`·계정 격리) + k8s mysql init/secret 분화 + CI smoke 전환 + B5/B8b 전환기 잔재 제거 + ADR-0016 신설/ADR-0012 Partially Superseded(P14). 이후 PR3 retention 스케줄러(D5).

---

## 구현 ② 서비스별 DB 분리 — PR2 (Flyway per-service + 물리 스키마 분리) — [#71](https://github.com/Kimgyuilli/PeakCart/pull/71)

> PR1(교차 FK 드롭) 후, 단일 공유 DB 를 **1 MySQL 인스턴스 + 5 스키마(`peekcart_<svc>`)** 로 물리 분리. 각 서비스가 자기 스키마·자기 계정·자기 Flyway 이력으로 자기 테이블만 소유 (ADR-0012 §D1 · ADR-0016).

**작업 (P4~P14)**:
- **P4** 5× `<svc>/src/main/resources/db/migration/V1__init_<svc>.sql` 통합 베이스라인 — 공유 V1~V13 누적 결과를 서비스별 자기 테이블 최종 형태로 분배(교차 FK 제외, 동일 스키마 FK 만 유지). order `product_price_cache` seed(`INSERT…SELECT FROM products`)는 cross-DB 라 제거(product.updated replay 로 대체).
- **P5/P6/P7** 5서비스 `flyway.enabled:true`(order B5 전환기 특권 소멸) · datasource `…/peekcart_<svc>`+계정 분화(ADR-0007) · `:common/db/migration` 소멸 + `build.gradle` flyway 플러그인/`flywayMigrateShared` 제거.
- **P8** outbox poller `aggregate-types` allowlist 제거(3 발행 서비스) — 스키마 분리로 소유권 자연 보장 → `findPendingEvents`/`countByStatus` 자기 스키마 전체로 단순화(B8b). `ProductOutboxOwnershipIntegrationTest` 재작성.
- **P9** k8s mysql init ConfigMap(`.sh` — 5 DB/계정/격리 GRANT, **비밀번호 Secret env·ConfigMap literal 금지**) + `mysql-secret` 5 pw + 5 per-svc secret DB 자격 분화 + base kustomization 등록.
- **P10/P11/P12** 전환 데이터 폐기(compose down -v) · 비-order initContainer order-readiness 게이트 제거(독립 마이그레이션) · smoke flyway 이미지 스텝 제거 + compose mysql init SQL.
- **P13** 통합테스트 cross-domain 시드 제거(notification/order×2/payment: 실제 행 시드→임의 ID 참조, FK 제거로 불요) + 공유 `cleanDatabase()` 스키마 적응형(information_schema 동적 조회·flyway_schema_history/shedlock 제외·FK_CHECKS finally 복구).
- **P14** **ADR-0016 신규**(예약=별도 `stock_reservations`·Payment=`payment_cancellations`, D1/D3 재기록) + ADR-0012 `Partially Superseded by ADR-0016` + README 인덱스 + `05-data-design`/`02-architecture` Layer1 동기화.

**핵심 결정**:
- **마이그레이션 consolidation**: 그린필드(보존 prod 이력 없음)라 13 교차 마이그레이션을 끌지 않고 서비스별 단일 `V1__init_<svc>.sql`. `ddl-auto:validate` 부팅 정합.
- **비밀번호 위치**(GW-2): init ConfigMap 엔 비밀 없는 DDL 골격, 비밀번호는 Secret env entrypoint(.sh). GRANT 는 자기 스키마 격리 + **최소권한**(DROP 미부여 — Flyway baseline 은 CREATE/ALTER/INDEX/REFERENCES+DML 로 충분).
- **거버넌스**: D1/D3 드리프트는 사실 오류가 아닌 결정 변경 → update-log 우회 금지(README:11-14) → 신규 ADR-0016 + Partially Superseded.

**검증**: `./gradlew build test` 8모듈 BUILD SUCCESSFUL(10m, 2회 — GW-2 fix 후 재검증). 물리 격리 판정 SQL·k8s rollout 검증은 PR2 머지 후(P15-k8s, Test plan 미체크).

**프로세스**: `/plan`(Codex 2 loop: PR2/PR3 재검토 5→2건 — ADR 거버넌스 전파·P9 ConfigMap 비밀번호·k8s rollout 검증·README 영향파일·Dockerfile assert·P9-sweep 처분표) → `/work`(diff split 3 chunks aggregate=ok, 4건[P1:1 GRANT DROP·P2:3 cleanDatabase 세션누수/ADR 위치/Order ERD] 전부 반영) → `/ship`([PR #71](https://github.com/Kimgyuilli/PeakCart/pull/71), 8 커밋). consistency precheck ok(ADR-0016 실재).

**다음**: PR3 — retention/cleanup 스케줄러(`processed_events`/`outbox_events`, ShedLock 잡) + floor fail-fast 가드(D5 식 = kafka-retention/consumer-downtime/dlq-replay/backfill max). L-008/011 종결. (인스턴스 물리 분리·k8s 실배포 검증은 후속.)

---

## 구현 ② 서비스별 DB 분리 — PR3 (retention/cleanup 스케줄러) — [#72](https://github.com/Kimgyuilli/PeakCart/pull/72)

> PR2(물리 스키마 분리) 후, `processed_events`(멱등성 창)·`outbox_events`(PUBLISHED)의 무한 증가를 ShedLock 배치 스케줄러로 닫는다. 보존기간이 D5 floor 미만이면 부팅 실패(fail-fast). ADR-0012 §D5 구현 → **L-008/011 종결**.

**작업 (P16~P19)**:
- **P16** `processed_events` retention 스케줄러(product/order/payment/notification). `common` 단일 typed `IdempotencyRetentionProperties` + **`@AssertTrue` cross-field**(4 `Duration` floor `max ≤ retention`) → 소유 서비스만 `@EnableConfigurationProperties` 활성(**user 누출 0** — `@ConfigurationPropertiesScan` 부재). floor 키 4종 base.
- **P17** `outbox_events` cleanup 스케줄러(product/order/payment). predicate `status='PUBLISHED' AND published_at < cutoff` — **PENDING/FAILED/`published_at IS NULL` 자연 보존**(유실 금지).
- **배치 삭제 계약**: `cutoff` 실행 시작 1회 계산 + `cleanup.batch-size`×`max-batches-per-run` 반복(unbounded DELETE 방지)·per-batch `@Transactional`(리포지토리 native `DELETE … LIMIT`). V2 마이그레이션에 삭제 기준 컬럼 인덱스(`processed_at`·`(status, published_at)`).
- **서비스×잡 매트릭스(물리 배치)**: 잡 클래스를 소유 서비스 모듈에만 둠(기존 `global/*` 복제 패턴). processed=4·outbox=3·**user=0**(구조적 부재).
- **notification 신규 ShedLock 인프라**: 소비 전용이라 미보유였음 → `shedlock-spring`/`-jdbc-template` dep + `ShedLockConfig` + `@EnableScheduling` + `shedlock` 테이블(V2).
- **P18** 테스트: floor 검증·max·fail-fast(`ApplicationContextRunner`)·base-only 배치 가드·processed/outbox 다중 batch 삭제·PENDING/FAILED/NULL/미만료 보존·**5서비스 매트릭스**(product/order/payment=both, notification=processed only, user=cleanup·retention props bean 0).
- **P19** `./gradlew build test` 8모듈 BUILD SUCCESSFUL(9m16s) + 신규 통합테스트 그린(3m6s).

**핵심 결정**:
- **floor = 교차필드 불변식**(Codex work 2회차 P1): 단순 필드 제약이 아니라 `max(4창) ≤ retention` → `@AssertTrue` cross-field. 구현 위치를 common 단일 typed properties 로 고정(5서비스 중복 정의·Duration 파싱 드리프트 차단).
- **bean 활성화 = 소유 서비스 물리 배치**(택1 확정): auto-config/`@ConditionalOnProperty` 대신 클래스 물리 부재로 매트릭스 성립(user=0 자연).
- **replay 정책**: TTL 만료 후 동일 eventId 재처리는 멱등 보장 밖 → 새 eventId/운영자 중복 확인(§2 트레이드오프).

**프로세스**: `/plan`(Codex 2 loop: PR3 재검토 5→3건 — 매트릭스/outbox FAILED 보존/kafka-retention SSOT → 2회차 floor 구현위치·대량삭제 배치·활성화 메커니즘, 전부 반영) → `/work`(diff single 리뷰 1 loop, 0 P0/0 P1/**3 P2**[5서비스 매트릭스·outbox 다중 batch·base-only 정적 가드] 자동통과분 전부 반영·검증) → `/ship`([PR #72](https://github.com/Kimgyuilli/PeakCart/pull/72), 3 커밋). consistency precheck ok.

**다음**: 구현 ③ Spring Cloud Gateway(선행 ADR-0013). 후속 비차단: 인스턴스 물리 분리(URL 교체 가역 승격)·k8s 실배포 rollout 검증·D-002 격리 재측정.

---

## 구현 ③ Spring Cloud Gateway — PR1 (RS256/JWKS dual-validation) — [#73](https://github.com/Kimgyuilli/PeakCart/pull/73)

> ADR-0013 D1/D2 의 크립토 기반. HS256 **대칭키** 공유 → RS256 **비대칭키**: User 만 개인키 서명, 모든 서비스가 공개키(kid)로 검증. Gateway 중앙 검증·header-trust 는 PR3, 본 PR 은 서비스 in-process 검증을 유지하며 RS256 을 얹는다(전환기 dual-validation).

**작업 (P1~P5)**:
- **P1** `JwtKeyProperties`(`app.jwt.rs256`) + `PemKeyLoader`(PKCS#8/SPKI PEM→RSA) + `RsaPublicKeyRegistry`(kid→공개키, JWKS 원본). common-auth `@EnableConfigurationProperties` 확장.
- **P2** `JwtTokenSigner`(User 전속) RS256 서명 + JWT 헤더 `kid`. 발급은 RS256 단일.
- **P3** `JwtTokenVerifier` dual-validation — jjwt `keyLocator` 로 헤더 `alg`/`kid` 검사: RS256 → registry kid 선택(미등록 거부), 전환기 **HS512** fallback(레거시 alg 정확 한정, 기본 off), 그 외(none/HS256/HS384) allow-list 거부.
- **P4** User JWKS endpoint `/.well-known/jwks.json`(kty/use/alg/kid/n/e, base64url 선행0 트리밍) + permitAll.
- **P5** 테스트: verifier 8종(RS256 왕복·미등록 kid·위조·kid부재·HS512 on/off·HS256 거부·none)·JWKS 스키마·서명 latency p50/p95.

**핵심 결정**:
- **fallback = HS512 정확 한정**(Codex work P1 #1): 512bit 시크릿이라 레거시 토큰은 실제 HS512(HS256 아님, plan-verify-against-code) → allow-list 과확장(`startsWith("HS")`) 방지.
- **fallback 기본 off**(Codex work P1 #2): base yml `hs256-fallback-enabled: false`(RS256 단일). 전환 배포·전환 테스트만 명시 활성화(bounded), PR4 에서 제거.
- **개인키 산출물 비포함**(Codex work P1 #3, ADR-0013 D2): 개인키를 main resources 에서 제거 → 테스트는 `:common` testFixtures(test-scope), 공개키는 `:common` main(비밀 아님, JWKS 공개). 로컬 dev=gitignored 파일 마운트, k8s CSI=PR3.
- 기존 HS 서명 통합테스트는 dual-validation 으로 재작성 없이 그린(fallback opt-in 전환창 시뮬레이션).

**검증**: `./gradlew build test` 8모듈 BUILD SUCCESSFUL(회귀 0).

**프로세스**: `/plan`(Codex 2 loop: 6→2 수렴 — JWKS 운영조건·refresh 데이터 처분·NetworkPolicy 검증·ADR immutable(S9 기존재)·B11·KMS latency → 2차 P6 근거·B5 키위치) → `/work`(PR1 diff single 리뷰 1 loop, 4건[P1×3 보안 posture·P2×1 테스트] 전량 반영·재검증) → `/ship`([PR #73](https://github.com/Kimgyuilli/PeakCart/pull/73), 3 커밋 docs/feat/test). consistency precheck ok.

**다음**: PR2 Refresh Token Reuse Detection(D4 — family_id/status 상태전이·family deny Redis) → PR3 Gateway 모듈+header-trust(D3) → PR4 관측성 S9+HS 제거(D5).

---

## 구현 ③ Spring Cloud Gateway — PR2 (Refresh Token Reuse Detection) — [#74](https://github.com/Kimgyuilli/PeakCart/pull/74)

> ADR-0013 D4. 삭제 기반 rotation 은 탈취된 refresh token 재사용을 감지 못한다. `family_id`/`status` 상태전이로 전환해 reuse 를 감지하고, 감지 시 family 전체 무효화 + Redis family deny 로 이미 발급된 access token 까지 차단한다. 전환기(Gateway PR3 이전)라 검증은 리소스 서비스 in-process 유지.

**작업 (P6~P10)**:
- **P6** `V2` 마이그레이션: 평문 `token`(+unique) 드롭 → `token_hash`(sha256, unique)·`family_id`·`status`(ACTIVE/ROTATED/REVOKED)·`grace_until`·`replaced_by_token_id`. 그린필드라 기존 row 전량 무효화. CHAR→VARCHAR(Hibernate validate 는 String 을 VARCHAR 로 기대).
- **P7** `RefreshToken` 상태전이 엔티티(+`RefreshTokenStatus`) 평문 미저장. 동시성 전이는 조건부 벌크 UPDATE(affected rows)로 원자성 — `rotateActive`/`consumeGraceOnce`/`forceRotate`/`revokeFamily`/`revokeAllByUserId`(삭제 기반 `deleteByToken` affected 판정을 상태전이로 이전).
- **P8** `AuthService.refresh` 재작성: ACTIVE 원자 로테이션(grace 부여)·ROTATED grace 1회 소비 vs 초과 reuse·REVOKED reuse. reuse 진입 3경로(grace 초과·forceRotate miss·REVOKED)를 `detectReuse`(family revoke+deny) 단일화. Redis grace(`addGracePeriod`/`consumeGracePeriod`) 제거→DB `grace_until`.
- **P9** `TokenIssuer.issue` 시그니처에 `familyId`→access token `family_id` claim. Redis `auth:deny:family:<id>` write(User)/read(전환기 common-auth `JwtFilter`+`RedisTokenBlacklistLookupAdapter`, PR3 Gateway 이관). family_id 부재 레거시 토큰은 family deny 미조회(blacklist 만).
- **P10** 단위 10종 + 통합 6종(Testcontainers MySQL/Redis): 마이그레이션 스키마·grace 원자성·동시 로테이션 1건만·grace 성공 ACTIVE 1개·forceRotate miss 결정적 family 무효화·reuse→revoke→deny.

**핵심 결정**:
- **reuse 무효화 커밋 보존**: 요청은 USR-004 거부하되 family 무효화는 커밋 필수. `REQUIRES_NEW` 는 outer refresh 트랜잭션 행 락과 **self-deadlock**(lock wait timeout) → 폐기. `RefreshTokenReuseException` + `@Transactional(noRollbackFor)` 로 무효화만 커밋, 다른 거부 경로(동시 loser INSERT·만료) 롤백 유지.
- **grace 성공 ACTIVE 1개**: 평문 미저장이라 첫 replacement 를 반환 불가 → 새 발급 + 기존 replacement force-rotate. `forceRotate != 1`(replacement 가 이미 전이됨)이면 ACTIVE 2개 위험 → 보수적 family revoke(방금 INSERT 한 새 토큰도 함께 REVOKED, noRollbackFor 커밋).
- **Redis deny 격리**: `detectReuse` 내 `denyFamily` try/catch — Redis 실패가 DB revoke 를 롤백시키지 않음. deny 미기록은 access TTL bounded, blacklist read fail-closed 로 최종 안전.
- **family_id 부재 계약**: claim 부재 ≠ Redis 조회 실패 → blacklist 만 검사(`auth:deny:family:null` 오조회·NPE·레거시 전면 401 방지).

**검증**: `:peekcart-common-auth:test :user-service:test` BUILD SUCCESSFUL(회귀 0). order-service 전체빌드 실패는 컨테이너 리소스 경합(단독 그린, D-019 계열).

**프로세스**: `/plan`(Codex 3 loop: 4→2→2 — grace 원자성/deny 키 계약/token_hash unique → 전환기 enforcement(a)안·ACTIVE 1개 불변식 → family_id 부재 계약·force-rotation 비순환, 전량 반영) → `/work`(diff single 2 loop: 3 P1[forceRotate 가드·Redis 격리·REVOKED 합류] → 1 P2[결정적 회귀테스트] 전량 반영. REQUIRES_NEW→noRollbackFor 전환은 통합테스트 self-deadlock 실측으로 확정) → `/ship`([PR #74](https://github.com/Kimgyuilli/PeakCart/pull/74), 3 커밋 feat/test/docs). consistency precheck ok.

**다음**: PR3 Gateway 모듈 + header-trust 전환(D3, family deny read 를 Gateway 로 이관) → PR4 관측성 S9 + HS512 fallback 제거(D5).

---

## 구현 ③ Spring Cloud Gateway — PR3a (Gateway shadow 배포) — [#75](https://github.com/Kimgyuilli/PeakCart/pull/75)

> ADR-0013 D3. PR3 는 단일 PR 로 실행 불가 — 롤아웃 ④(header-trust 배포)와 ⑤(Authorization 중단·verifier 삭제)를 **하나의 이미지로 구분 배포할 수 없고 역순 롤백도 불가능**하다. 계획에 **PR3a~d 실행 분할**을 신설하고 그 첫 단계를 수행. Gateway 는 검증하되 `Authorization` 을 **그대로 전달**해 구버전 서비스(`JwtFilter`)와 병행 동작한다.

**작업 (P11~P13·P16)**:
- **P11** `gateway` 모듈(WebFlux) 신설 + **라우트 정본** 확정 — placeholder 금지, 실 controller prefix 대조: auth/users→user, products/**admin**/products→product, **cart(단수)**/orders→order, payments→payment, notifications→notification. JWKS(`/api/v1` 밖)·swagger·api-docs 는 외부 라우트 미노출.
- **P12** reactive 인증 필터 3단계: 외부 `X-User-*` **항상 strip**(공개 경로 포함) → 서명/만료(RS256 via **User JWKS 정본**, 전환기 HS512) → blacklist/family deny(PR2 키 계약 그대로) → 신뢰 헤더 주입(+ 검증된 userId 를 exchange attribute 로). **응답 행렬**: 401(서명·만료·exp부재·unknown kid·deny) / 429(초과) / 503(JWKS·Redis 장애) / readiness=false(cold start usable key 0).
- **P13** route-class별 RateLimiter — 인증 후 **검증된** userId, 인증 전/공개 IP.
- **P16** Dockerfile gateway COPY + CI matrix 6 + canonical **도메인 5(ADR-0010 §5) / 인프라 1** 분리(`image-contract-lint`·`promote-images`).

**핵심 결정**:
- **gateway ≠ `:common` 소비자**: `common/build.gradle` 이 `spring-boot-starter-web`(servlet)·JPA·Kafka 를 **`api` 로 전이 노출** → 의존 시 WebFlux 런타임에 MVC 유입(Boot 가 MVC 로 부팅). 응답 DTO 는 gateway-local, 가드(`assertGatewayHasNoServletDeps`, `check` 연결)+`GatewayReactiveBootstrapTest` 로 이중 고정.
- **JWKS 는 merge 아닌 snapshot 교체**: `put` 누적이면 User 가 침해 키를 내려도 재시작 전까지 계속 수용 → 성공·비어있지 않은 응답만 통째 교체, 실패/빈 응답에만 LKG 유지.
- **fail-closed RateLimiter 자체 구현**: SCG 기본 `RedisRateLimiter` 는 Redis/Lua 오류를 삼켜 `allowed=true`(fail-**open**) → ADR-0013 D3 미충족. 고정 윈도우 카운터로 대체하고 오류 전파 → 503. `deny-empty-key` 는 빈 키만 처리라 대체 불가.
- **readiness ≠ health**: JWKS 미확보를 `HealthIndicator` DOWN 으로 내면 루트 `/actuator/health` 까지 503 → **이미지 스모크가 통과 불가**(스모크 망에 user-service 없음). liveness(프로세스 생존)/readiness(트래픽 수용) 분리, 스모크는 gateway 한정 liveness.
- **actuator 관리 포트(8081) 분리**: gateway 는 외부 진입점이라 8080 동일 포트면 `/actuator/prometheus` 가 라우트 없이도 직접 노출.
- **인증 필터 order = -100**: 라우트 필터(order 1..n)보다 **먼저** 실행돼야 RateLimiter 가 검증 전 위조 `X-User-Id` 를 키로 쓰지 않는다.

**검증**: `./gradlew clean build` BUILD SUCCESSFUL(14m6s, 가드 2종 실행 확인) · gateway 테스트 **56건 0 실패** · `docker build SERVICE=gateway` OK(539MB) · `docker-health-smoke.sh gateway:ci` passed · `image-contract-lint` matrix 6/6.

**프로세스**: `/plan`(Codex 3 loop: timeout → 13건 → 8건, 전량 반영. loop2 가 자기모순 3건 적발 — conformance 대상이 같은 PR 에서 삭제됨·family-less 계약 충돌·JWKS 기대값 404/200 불일치) → `/work`(diff 2536L>2000 → **3-chunk split 전량 리뷰**, 15건→중복제거 12건[P1 10/P2 5] 전량 반영. 필터 순서 역전·`exp` 부재 무기한 토큰·폐기 kid 잔존·Redis fail-open 이 실제 보안 결함) → `/ship`([PR #75](https://github.com/Kimgyuilli/PeakCart/pull/75), 4 커밋). consistency precheck ok.

**후속(명시)**: gateway k8s 매니페스트 부재로 `IMAGE_CONTRACT_TRANSITION=1` 재설정 — **PR3b 에서 매니페스트 추가 후 제거 필수**(full 6/6). 계정 차원 rate limit 미구현(body-caching 필요, `?email` 쿼리 성분은 회피 가능해 제거하고 IP 단독으로 축소). conformance golden vector 미구현(servlet 가드가 `:common` testFixtures 의존을 막아 리소스 파일로 재설계 필요).

**다음**: PR3b(k8s gateway + NetworkPolicy + ClusterIP 환원 + ServiceMonitor) → PR3c(header-trust 전환 + ADR-0014 D2-c exit) → PR3d(Authorization 중단 + verifier 삭제) → PR4(관측성 S9 + HS512 제거).

---

## 구현 ③ Spring Cloud Gateway — PR3b (gateway k8s 배포 표면) — [#76](https://github.com/Kimgyuilli/PeakCart/pull/76)

> ADR-0013 D3. PR3a 가 gateway 모듈을 코드로 완성했으나 k8s 매니페스트가 없어 배포 표면이 비어 있었고, 그 때문에 `IMAGE_CONTRACT_TRANSITION=1` 전환기 게이트가 CI 에 남아 있었다(5/6 SUSPENDED). 본 PR 은 gateway 를 클러스터에 올릴 수 있게 만들고 그 꼬리를 닫는다. **트래픽 전환은 하지 않는다** — 5서비스 직접 경로는 canary 롤백 경로라 살려 둔다(제거는 PR3c). **ADR 변경 없음.**

**작업 (P24~P30)**:
- **P24·P26** base `k8s/base/services/gateway/{deployment(+Service),configmap}.yml` + overlay(minikube NodePort **30080** — 구 단일앱 진입점 승계 / gke Internal LB · deployment patch×2 · `images[]` **6번째** · **gateway HPA** minReplicas 2).
- **P25** `gateway/src/main/resources/application-k8s.yml` 신설 = k8s 연결값(업스트림 5·JWKS·Redis) **단독 소유**(ADR-0007). 라우트 uri 는 리스트 요소라 프로파일 override 불가 → base placeholder 를 `${app.gateway.upstream.<svc>-uri}` **정규 계층형 키**로 교정(환경변수 표기법을 프로퍼티 이름으로 고착시키던 형태 제거). JWKS 는 스칼라라 placeholder 없이 프로파일이 직접 override.
- **P27** `IMAGE_CONTRACT_TRANSITION` 제거 → **full 6/6** + **`scripts/gateway-exposure-lint.sh` 신설**(+ CI policy step 등록, self-test 포함).
- **P28·P29** 롤아웃 runbook(계획 §7 — 배포 전 digest/revision 기록·probe 4종·canary 3단계 임계·**역순 rollback**)·gke README 외부 노출 절.
- **P30** `K8sProfileConnectionPropertiesTest` 신설(연결키 8종 origin + 라우트 9개 placeholder 연결).

**핵심 결정**:
- **ServiceMonitor 미생성(결정 가)**: `servicemonitor-selector-lint` canonical 은 도메인 5 **정확 일치**이고 ADR-0015 S5/S6.d 가 그 집합을 계약으로 고정 → gateway SM 을 넣으면 selector-lint + scrape-absent 5 equality + alert regex 5-set 이 동시에 깨져 **ADR 계약 변경** 동반. 관측성 6 확장은 PR4 에서 ADR 과 일괄. PR4 계약 선고정: `gateway-metrics` Service labels `{app: gateway, monitoring-role: metrics}` ↔ SM matchLabels 동일 두 키(공용 `app=gateway` 만 쓰면 SM 이 public Service 까지 매칭해 lint 실패).
- **Service 8080 단일·probe 8081(결정 나/다)**: overlay 가 이 Service 를 NodePort/LB 로 patch 하므로 8081 을 함께 선언하면 **LB 가 `/actuator/prometheus` 를 게시** — PR3a 의 포트 분리가 k8s 층에서 무효화된다. probe 는 `management.server.port` 를 따라 8081(도메인 5서비스는 8080이라 복사 시 함정).
- **Secret 미생성(결정 라)**: 소비 비밀 0(RS256 개인키=user-service 전용·HS512 off). 빈 Secret 대신 lint 가 **이름이 아니라 참조**로 부재 강제.
- **gateway HPA = 단일 HPA 원칙의 명시적 예외(결정 마)**: 도메인 확장 정책이 아니라 인프라 가용성 — 단일 진입점 replica 1 은 인증 경로 전체 SPOF.
- **`observability-promql-lint` 정정**: S6.d "SM 이 매칭하는 Service" 를 `deployment.yml` **전체 glob** 으로 근사하고 있어 SM 없는 인프라 Service 추가 시 오탐 → **계약이 아니라 구현 근사의 문제**라 ADR 변경 없이 SM matchLabels 기반으로 수정.
- **렌더 성공 ≠ 계약 검증**: `port: 8080/targetPort: 8081`·Job/CronJob/직접 Pod(hostPort 8081)는 `kubectl kustomize` 를 전부 통과 → 전용 lint 필요. self-test 는 조작 입력 **13종이 의도한 검사에** 걸리는지 **진단 문자열까지 대조**(non-zero 여부만 보면 다른 위반에 걸려도 통과).

**검증**: CI policy lint **7종 전부 그린**(namespace·image-contract **full 6/6**·gateway-exposure·self-test 13/13·servicemonitor **5 유지**·observability-ssot·observability-promql) · `kubectl kustomize` 양 overlay 렌더(양성+음성 3종) · `./gradlew build` BUILD SUCCESSFUL(gateway **66건 0 실패**·가드 5종) · `docker build SERVICE=gateway` + `docker-health-smoke.sh gateway:ci` passed · 신규 테스트 음성 확인(프로파일 키 오타 → BUILD FAILED).

**프로세스**: `/plan`(Codex **3 loop**: timeout→8건→7건→4건, 전량 반영. GP-1 에서 SM/관측성 확장을 PR4 로 이연 결정. loop2 가 내 lint 스펙의 실제 구멍[targetPort]을, loop3 이 우회 경로[CronJob/hostPort]와 내가 만든 문서 SSOT 붕괴를 적발 — attempts 4/3 은 사용자 승인으로 상한 초과) → `/work`(코드 723줄 single 리뷰, **7건[P1:3/P2:4] 전량 반영**. **CI red 를 리뷰어가 발견** — 로컬에서 lint 4종만 돌리고 관측성 2종을 건너뛴 누락) → `/ship`([PR #76](https://github.com/Kimgyuilli/PeakCart/pull/76), 6 커밋). consistency precheck ok.

**미확보(명시)**: 실 클러스터 canary 증적 — runbook 은 작성했으나 미실행. 상시 클러스터 부재로 실 probe(보호/공개/spoof·오류율)는 **PR3c 의 GKE 보안 smoke 세션에서 NetworkPolicy 음성·양성과 함께 1회** 수행한다. **렌더 성공을 canary 통과로 기록하지 않음** → PR3c 진입 조건("100% 전환 유지")은 본 PR 머지로 충족되지 않는다.

**다음**: PR3c(5서비스 ClusterIP 환원 + NetworkPolicy + header-trust 전환 + ADR-0014 D2-c exit + **GKE 보안 smoke = canary 증적 합류**) → PR3d(Authorization 중단·verifier 삭제) → PR4(관측성 S9 + `gateway-metrics`/SM + lint 6 + HS512 제거).

---

## 구현 ③ Spring Cloud Gateway — PR3c (header-trust 전환 + ClusterIP 환원 + NetworkPolicy) — [#77](https://github.com/Kimgyuilli/PeakCart/pull/77)

> ADR-0013 D3. PR3a/PR3b 로 Gateway 를 클러스터에 올린 뒤, 리소스 서비스가 JWT 를 재검증하는 대신 Gateway 가 검증 후 주입한 `X-User-*` 신뢰 헤더로 인증하도록 전환한다. 동시에 5서비스 직접 노출을 제거(ClusterIP)하고 NetworkPolicy 로 gateway 경유를 강제해 header-trust 의 spoofing 을 봉쇄한다. **servlet verifier 삭제(ADR-0014 D2-c exit)·Authorization 전달 중단은 PR3d** — PR3c 는 구 컴포넌트를 잔존시켜 rollback 안전창을 남긴다.

**작업 (P31~P38)**:
- **P31** common-auth `HeaderAuthenticationFilter`(servlet `OncePerRequestFilter`) + `HeaderTrustSecurityConfigurer`. **3-state 계약**: 세 헤더 부재→anonymous 통과 / `X-User-Id`(양의 정수)·`X-User-Role`(USER/ADMIN) 각 1개(+familyId 전환기 선택)→인증 / 형식오류(부분·blank·중복·비숫자·미허용 role)→401(entrypoint, 500·anonymous fallback 아님). `LoginUser(userId,role,familyId)` + resolver(authority→role, details→familyId) + testFixtures(role/familyId).
- **P32** 5 SecurityConfig `jwtSecurityConfigurer`→`headerTrustSecurityConfigurer` 스왑(공통 정책·`@EnableMethodSecurity`·PUBLIC_URLS 불변). `AuthController.logout`→`authService.logout(userId, familyId)` = family deny(non-null 시) + `revokeAllByUserId`(`jwtTokenVerifier` 의존 제거·고아 import/field 정리).
- **P33** 테스트: `HeaderAuthenticationFilterTest` 3-state 13종 + `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` Bearer→`X-User-*` 재작성(configurer·MdcFilter parity·actuator permitAll) + `AuthServiceTest` logout family/family-less.
- **P34** overlay service patch 10개(minikube NodePort 5·gke Internal LB 5) 삭제 → base ClusterIP 환원 + 5 base Deployment `strategy.rollingUpdate.maxUnavailable:0`.
- **P35** `k8s/base/networkpolicy.yml` — **ingress-only**·`podSelector{component:backend}`(5서비스 선택·gateway `component:gateway` 자동 제외)·ingress ①gateway `{app:gateway}` ②monitoring NS Prometheus scrape 예외(둘 다 TCP 8080). `networkpolicy-contract-lint.sh`(고정 5 Deployment 이름 식별·peer+포트 결합, self-test 8종).
- **P36** PR3c 무중단 rollout runbook(계획 §8 — 안전 순서: NetworkPolicy/ClusterIP 선행→header-trust rollout, 역순 rollback).
- **P37** `gke-security-smoke.sh`(`--barrier`: enforcement hard-fail[Dataplane V2 OR networkPolicy.enabled]·non-gateway 차단·gateway 공개 200·Prometheus up·직접경로 도달불가 / default: barrier+canary+증적).
- **P38** 검증: 전체 빌드 9모듈 BUILD SUCCESSFUL · CI lint 7종 그린(image-contract full 6/6·servicemonitor 5 유지·gateway-exposure 13/13·**networkpolicy-contract 8/8**) · 렌더 양성(5 ClusterIP·gateway NodePort/LB).

**핵심 결정**:
- **configurer 스왑 = 필터만 교체, 공통 정책 전부 보존**(Codex diff #6): csrf/STATELESS/entryPoint/accessDeniedHandler/MdcFilter 순서 유지 — 누락 시 401/403·MDC traceId 계약이 조용히 달라진다.
- **NetworkPolicy 는 podSelector 기반**(Codex diff #3/#4): vanilla NP 는 peer 를 ServiceAccount 로 선택할 수 없다 → SA 기반 허용 폐기·Gateway SA PR3c 미도입. `component:backend` 로 5서비스만 선택.
- **안전 순서**: NetworkPolicy/ClusterIP 를 header-trust rollout 보다 먼저(구 이미지 Bearer 검증이라 위조 `X-User-*` 무효). gateway 의 Authorization 전달은 PR3c 내내 유지(rollback 안전창).
- **검증 도구 false-green 차단**(Codex diff #1/#2/#5): lint 는 보호 대상을 고정 Deployment 이름으로 식별해 라벨 드리프트를 잡고 peer 를 포트와 결합 검사. smoke barrier 는 kubectl-run 실패↔curl 결과 분리(`000000` 버그·kubectl 실패 오판 제거)·직접경로는 HTTP 응답 오면 실패. self-test 로 각 시나리오 재현·차단.

**프로세스**: `/plan`(Codex 3 loop: timeout→10건→4건 전량 반영. NetworkPolicy SA 미지원·barrier 실행화·configurer parity 등) → `/work`(diff single 리뷰 1 loop, **6건[P1:4/P2:2] 전량 반영** — 내 검증 도구 자체의 false-green 이 핵심. np-lint self-test 8/8 이 Codex 지적 시나리오 재현·차단) → `/ship`([PR #77](https://github.com/Kimgyuilli/PeakCart/pull/77), 4 커밋 feat(auth)/test(auth)/feat(k8s)/docs). consistency precheck ok.

**미확보(명시)**: **GKE 실 클러스터 보안 smoke 증적** — `gke-security-smoke.sh` 는 작성 완료했으나 실 클러스터 barrier(enforcement·직접경로 차단·spoof)·canary 실행은 라이브 클러스터 단계라 본 PR 미포함. **렌더/lint 성공을 canary 통과로 기록하지 않는다**(계획 §6/P38). PR3c 완료 필수 게이트이며 **PR3d 진입 전 확보 필수**.

**다음**: PR3d(**재정의 — 아래 ADR-0017 채택 참조**) → PR4(관측성 S9 + `gateway-metrics`/SM + lint 6 + HS512 제거). **선행: GKE 보안 smoke 증적 확보.**

---

## 구현 ③ Spring Cloud Gateway — PR3d 재정의 (ADR-0017 채택: Gateway 서명 내부 토큰) — 2026-07-25

> **결정만 기록(구현 전).** PR3c 리뷰에서 "평문 header-trust 는 NetworkPolicy 단일 통제(single control)라 근본적 방어가 아니다"를 짚고, defense-in-depth 로 격상하기로 **ADR-0017(Accepted, 경로 A)** 신설. PR3d 의 정의가 "평문 header-trust 굳히기 + verifier 삭제" → "Gateway 서명 내부 토큰(`X-Internal-Auth`) 격상"으로 바뀐다.

**핵심**:
- **평문 → 서명 assertion**: Gateway 가 `X-User-*`(평문) 대신 자기 개인키로 서명한 짧은 수명 내부 JWT(`X-Internal-Auth`, TTL≤30s)를 주입. 리소스 서비스는 Gateway 공개키로 **서명·iss(`peekcart-gateway`)·kid·exp 핀 검증** 후 claims 에서 신원 추출. 평문 신뢰 헤더 폐기 → **스푸핑 표면 0**.
- **신뢰 경계 이중화**: 신원 위조에 NetworkPolicy(네트워크) AND 서명키(암호) 동시 돌파 필요. NetworkPolicy 단독 실패(CNI 미enforce·라벨 드리프트·파드 컴프로마이즈)로 위조 불가.
- **verifier 용도 변경(삭제 아님)**: `RsaPublicKeyRegistry`/`PemKeyLoader`/`JwtKeyProperties`(공개키)는 내부 토큰 검증기로 재활용(기존 크립토 ~80% 재사용). 삭제 대상은 사용자 토큰 검증 필터(`JwtFilter`/`JwtTokenVerifier`)·서비스 측 blacklist lookup(deny 는 Gateway 소유). ADR-0014 D2-c exit 은 여전히 성립.
- **경로 A**: PR3c 가 GKE 증적 미확보 = 평문 header-trust 미배포 → 평문을 실 클러스터에 굳히지 않고 서명 assertion 을 header-trust rollout 으로 직행(dual-accept 경유). GKE 보안 smoke 는 서명 상태에서 1회 수행하며 위조 `X-Internal-Auth`·평문 직접주입 차단을 barrier 에 추가.
- **기각 대안**: 평문 유지(단일 통제) / HMAC 공유비밀(서비스 1개 컴프로마이즈=위조, blast radius) / mTLS(메시 인프라 과대, Phase 5+) / 원본 JWT 재검증(중복·지연 회귀).

**산출물**: ADR-0017(Accepted)·`docs/plans/task-impl3-pr3d-internal-token.md`(P1~P10 정본 — loop3 에서 P9/P10 추가, 상위 문서 P1~P8 표기는 2026-08-08 정정)·상위 계획 PR3d 행·P14 처분표 대체 표기.

**다음**: 새 브랜치에서 초안 `/plan`(Codex 리뷰 루프) → PR3d `/work`+`/ship`. **선행 게이트 불변: GKE 보안 smoke 증적(위조 서명 차단 포함).**

---

## 구현 ③ Spring Cloud Gateway — PR3c GKE 보안 smoke 게이트 실행 (증적 확보) — 2026-08-08 — [#79](https://github.com/Kimgyuilli/PeakCart/pull/79)

> **코드 변경 없음 — 실행/증적 세션.** PR3c([#77](https://github.com/Kimgyuilli/PeakCart/pull/77))가 "렌더/lint 성공을 canary 통과로 기록하지 않는다"며 미확보로 남긴 실 클러스터 barrier 를 1회 수행했다. PR3d 진입 조건이던 선행 게이트가 해제된다.

**환경**: 신규 GCP 프로젝트 `peekcart-gate`(조직 하위) · GKE `peekcart-loadtest`(asia-northeast3-a, e2-standard-4×3, **Dataplane V2**) · loadgen VM `peekcart-loadgen`(e2-small, 동일 VPC) · 이미지 GHCR→AR 승격 6개 digest 고정(D-016/L-016a) · kube-prometheus-stack. 세션 종료 후 `loadtest/cleanup.sh` + orphan PD 4건 수동 삭제 → **잔여 리소스 0**.

**결과**: `gke-security-smoke.sh` **barrier 5/5 + canary 3/3 전부 통과**.

| 검사 | 결과 |
|---|---|
| (1) enforcement | `datapathProvider=ADVANCED_DATAPATH` |
| (2) non-gateway Pod → order-service | 차단(`000`) |
| (3) gateway 공개 경로 | `200` |
| (4) Prometheus scrape | `up=5`(monitoring 예외 동작) |
| (5) 직접 경로 5개 | 전부 도달불가 |
| canary | 공개 `200` / 보호 무토큰 `401` / spoof `X-User-*` `401` |

**핵심 결정**:
- **검사(5) 는 그대로 돌리면 vacuous-green**: 새 클러스터엔 직접 경로가 처음부터 없어 아무 IP 5개나 넣어도 전부 `000` 이고, 모든 LB 가 **Internal** 이라 VPC 밖에서 실행하면 무엇을 넣든 `000` 이다(스크립트의 개수 검증만으로는 의미가 확보되지 않는다). → **3상태 측정으로 양성 대조군을 만든다**: ①LB有·NP無=**200**(검사가 도달을 감지함) → ②LB有·NP有=`000`(NP 단독 효과) → ③ClusterIP·NP有=`000`(표면 제거). ①→③ 직행이면 LB 삭제와 NP 적용이 동시에 일어나 무엇이 `000` 을 만들었는지 분리되지 않아 ②를 끼웠다.
- **직접 경로는 PR3c(3ed4fb4)가 삭제한 service patch 를 git 에서 복원해 실제로 띄운 주소**(합성 NodePort 아님) — 그래야 "표면 제거"가 임시 오브젝트가 아닌 계약 변경에 대한 증명이 된다. 복원 overlay 는 `k8s/overlays/gke-probe-state1/`(operator-local, `.git/info/exclude`).
- **smoke 실행 위치 = VPC 내부 VM**: gateway·5서비스 LB 가 전부 Internal 이라 노트북 실행은 검사(3) 이 `000` 으로 실패하고 검사(5) 는 무조건 통과한다.

**증적**: `docs/progress/evidence/pr3c-gke-smoke-20260808-1445.md`(스크립트 출력 + 3상태 addendum + 배포 편차).

**배포 편차(증적에 명시)**:
1. 조직 정책으로 기본 컴퓨트 SA 자동 IAM 부여가 꺼져 AR pull 403 → `roles/artifactregistry.reader`, loadgen VM 용 `roles/container.developer` 수동 부여.
2. **user-service RS256 개인키를 k8s Secret 으로 마운트**(ADR-0013 D2 의 Secret Manager+CSI 아님) — 게이트 검증 대상과 무관한 부팅 전제로 판단. **PR3d P5 CSI 계약은 본 증적으로 미충족**.
3. `SLACK_WEBHOOK_URL`·`TOSS_SECRET_KEY`·`TOSS_WEBHOOK_SECRET` 은 `docker-health-smoke.sh` placeholder 런타임 주입(실 자격증명 아님, 외부 연동 미검증).

**발견된 결함(PR3d 흡수)**: `gke-security-smoke.sh` 증적 헤더 `- canary:` 가 항상 `n/a` — `CANARY_RESULT` 가 `tee` 파이프라인 서브셸에서 설정돼 부모 셸로 전파되지 않는다. 실제 값은 로그 블록에 보존되어 본 증적은 온전. PR3d P10 이 같은 스크립트를 확장하므로 그때 수정한다.

**다음**: **PR3d 착수 가능**(선행 게이트 해제). `docs/plans/task-impl3-pr3d-internal-token.md` P1~P10 → `/work`. PR3d P10 ②(signed-only crypto barrier)도 위조 401 을 주장하려면 **정상 서명 200 양성 대조군**이 같은 이유로 필요하다.

---

## 구현 ③ Spring Cloud Gateway — PR3d-a: Gateway 서명 내부 토큰 (코드) — 2026-08-12 — [#80](https://github.com/Kimgyuilli/PeakCart/pull/80)

> PR3d 를 **PR3d-a(코드) / PR3d-b(키배포·클러스터)** 로 분할 확정하고(계획서 §9), 그중 a 를 구현했다. 평문 `X-User-*` 신뢰가 사라지고 Gateway 가 서명한 `X-Internal-Auth` 만 인증 근거가 된다(ADR-0017).

**분할 기준 = "클러스터 없이 그린이 되는가"**. 단일 PR 로 두면 코드가 CSI 인프라 준비를 기다리고, 반대로 lint 를 먼저 짜면 검사 대상 매니페스트가 없어 vacuous-green 이 된다. PR3a(이미지·코드)→PR3b(k8s 표면)와 같은 축이다.

**착수 전 코드 검증이 계획 전제 3건을 뒤집었다** (§9.1):
- **`JwtFilter` 는 PR3c 이후 이미 미배선**(5서비스 전부 `HeaderTrustSecurityConfigurer`) → 계획이 P0급으로 다룬 loop2 #1("④~⑤ 구간 verifier 활성 시 Bearer 로 Gateway 우회") 위험이 성립하지 않는다. P6 삭제는 순수 dead-code 제거 → 롤아웃 비결합.
- 같은 이유로 loop3 #3(rollback 전용 호환 이미지) **전제 소멸** — 되돌릴 대상은 직전 릴리스(PR3c) 이미지다. §7 rollback 행렬은 b 착수 시 재작성.
- **user-service 개인키의 k8s 매니페스트가 아예 없다**(GKE 세션의 ad-hoc Secret 이 유일) → 이 상태로 P7 key-ownership lint 를 만들면 검사 대상이 없어 **vacuous-green**. b 의 P5 에 user 키 CSI 정본화를 포함시켰다.

**핵심 결정**:
- **이름 계약 단일 출처**: gateway(WebFlux)와 common-auth(servlet)는 서로 의존 불가(B6)라 양쪽 리터럴이 곧 drift 원천 → 프레임워크 의존 0 인 `internal-token-contract` 모듈에 issuer/claim/헤더 이름을 한 번만 정의. 루트 가드에 allowlist 예외 1건을 열되 **계약 모듈 자신의 project/Spring 의존을 금지하는 (a2) 검사**를 함께 추가해 예외가 우회로가 되지 않게 했다.
- **키 도메인 분리(D3)**: Gateway 공개키를 `app.jwt.rs256.public-keys` 에 넣지 않는다 — `JwkController` 가 레지스트리를 통째로 JWKS 게시하므로 내부 신뢰 앵커가 노출된다. 강제는 **kid 가 아니라 SPKI DER SHA-256 fingerprint** 로 한다(같은 키를 다른 kid 로 넣는 우회 차단). lint + 5서비스 통합테스트 이중.
- **교차모듈 conformance**: 두 모듈을 잇는 단일 테스트가 불가능 → 공유 fixture 에 **커밋된 계약 토큰**을 두고 발행측/검증측이 각각 고정. 한쪽만 바뀌면 반대편이 깨진다.
- **fail-fast/fail-closed**: 키 로딩 실패·빈 키셋·범위 위반은 부팅 거부. family-less 는 발행 거부(401). `InternalTokenModeInvariant` 가 부팅 시 필터 구성을 검사(사용자 verifier 부활·체인 0개 차단).

**프로세스**: `/work`(diff 6천 줄 → **split 3 chunk 리뷰**, 24건). **분할 아티팩트 10건 기각** — chunk 를 나눠 독립 리뷰하니 다른 chunk 의 파일을 "패치에 없어 컴파일 불가(P0)"로 판정했다. full build 그린 + 파일별 chunk 소속 대조로 반증. **실제 결함 7건 전량 반영**, 그중 3건이 내가 만든 검증 도구 자체의 false-green:
1. 직접경로 Bearer 거부 테스트가 깨진 문자열 → verifier 가 부활해도 통과하는 **vacuous-negative** → 유효 access token 발급 + 4서비스에 검증키를 **일부러 등록**해 회귀 시 실제로 깨지게 함
2. 부팅 불변식이 SecurityFilterChain 0개일 때 조기 return → **fail-open** → 위반 처리
3. 키 도메인 검사가 fixture 키 1개만 대조 → 두 레지스트리 **fingerprint 집합 서로소** 검사로 교체

나머지 4건: lint 서비스 단위 검사(ITKO-006), 양성 대조군을 구체 상태로 고정(405/200/404 — "401 아님"은 403·5xx 도 그린), RS384/RS512 거부·경계값(±1s)·키 회전 overlap 테스트, 서명 지연 baseline.

**검증**: 10모듈 그린 · **575 테스트 0 실패** · 가드 5종 · lint self-test 7/7 · 서명 p95 RSA-2048 **1.80ms** / RSA-3072 **3.00ms**(예산 10/25ms, 측정 전 확정).

**미충족(명시)**:
1. **k8s gateway 매니페스트는 배포 불가** — 개인키 CSI 마운트(P5) 부재로 fail-fast 기동 거부. CI 는 apply 하지 않고 클러스터도 0이라 실효 비용 0. PR3a→PR3b 전환기와 동일 취급 — **렌더 그린을 배포 가능으로 기록하지 않는다.**
2. **부하 하 event-loop lag 미측정** — 마이크로벤치로는 불가. PR3d-b 부하 세션 이연(계획서 §9.2 명시). 초과 시 P2 (b) bounded scheduler + 포화 503.
3. ~~**Layer 1 동기화(02 / 04 §10-2) 이연**~~ → **별도 docs PR 로 해소**(아래 항목).

**다음**: **PR3d-b**(P5 CSI 키배포[user 키 정본화 포함]·P7 나머지·P8 회전 runbook·P10 GKE 2단 barrier·§7 롤아웃) → PR4(관측성 S9). **진입 조건: GKE 재기동 + Secrets Store CSI Driver 설치** — 비용상 PR4 와 같은 클러스터 세션으로 묶기를 권장.

---

## 구현 ③ Spring Cloud Gateway — PR3d-a 후속: Layer 1 동기화 (docs) — 2026-08-12 — [#81](https://github.com/Kimgyuilli/PeakCart/pull/81)

> PR3d-a 의 미충족 #3(Layer 1 동기화 이연)을 해소했다. 클러스터 비의존이라 PR3d-b 를 기다릴 이유가 없어 별도 docs PR 로 떼어냈다.

**변경**:
- **`04-design-deep-dive.md` §10-2** — "Gateway 가 평문 헤더로 전달 / 내부 서비스는 헤더 값을 신뢰" 기술을 폐기하고 서명 assertion 으로 교체. 외부 `X-User-*`·`Authorization` strip → Gateway 개인키 서명 `X-Internal-Auth` 단일 주입(사용자 Authorization 미전달) → 서비스는 서명·iss·kid·exp/iat·수명상한 검증 후 인증 주체 확립. 보안 전제를 "NetworkPolicy 가 신뢰 경계를 보장"에서 **defense-in-depth(NetworkPolicy AND 서명)** 으로 정정 — NP 우회만으로는 인증을 통과할 수 없다. **키 도메인 분리** 절 신설(Gateway 공개키는 User JWKS 에 미투입, 강제는 kid 가 아닌 SPKI DER SHA-256 fingerprint).
- **`02-architecture.md`** — §4-4 모듈 목록에 `internal-token-contract` 추가 + `peekcart-common-auth` 역할을 "전환기 JWT 검증"→"내부 토큰 검증"(ADR-0014 D2-c 종료)으로 정정 · Phase 4 다이어그램 Gateway 노드에 내부 토큰 발행 명시 · §12 Phase 4 트리의 `api-gateway/`(가상 이름·`JwtAuthFilter`)를 실제 `gateway/` 모듈 구조로 교체하고 `peekcart-common-auth`/`internal-token-contract` 추가 · k8s `services/api-gateway/`→`gateway/` · Phase 1→4 전환표 "인증 처리" 행 정정.

**남긴 것**: `00-lagacy.md` 의 동일 문구 3곳은 아카이브라 손대지 않았다. 02 §12 트리의 여타 누락(`peekcart-common-observability`, NetworkPolicy 매니페스트 등)은 ADR-0017 표면이 아니라 범위 밖.

**다음**: 변동 없음 — **PR3d-b**(GKE 재기동 + Secrets Store CSI Driver 설치 선행) → PR4(관측성 S9).

---

## 구현 ③ Spring Cloud Gateway — PR3d-b 분할 확정 (계획) — 2026-08-12 — [#82](https://github.com/Kimgyuilli/PeakCart/pull/82)

> §9.3 의 PR3d-b 를 **b-1(코드·매니페스트) / b-2(클러스터 증적)** 로 재분할했다. 분할 축은 §9 와 동일 — "클러스터 없이 그린이 되는가". 계획서 §10 신설, 코드 변경 없음.

**착수 전 grep 검증 6건 — 5건 확인, 1건 뒤집힘** (§9.1 이 전제 3건을 뒤집은 전례에 따라 b 착수 전에도 동일 검증):

- **V6 (뒤집힘) §7 은 라이브 무중단 전환이 아니다** — 클러스터 잔여 0. 구 gateway 이미지도 트래픽도 없으므로 ②~④ 는 마이그레이션이 아니라 **fresh deploy 리허설**.
  - **결정: 단계 전부 리허설 실행**. 생략하면 전환 절차·수렴 판정식(§8 loop3 #1)·rollback 행렬이 한 번도 실행 안 된 문서로 남고 `DUAL_ACCEPT` 가 미실증이라 §7 ⑥ 삭제 근거가 약해진다. P8 회전 overlap 이 같은 "선배포 → 수렴 → 전환" 메커니즘을 재사용하므로 비용도 회수된다. 증적에는 **리허설임을 명시**한다("무중단 전환 실증"이 아니라 "절차 재현"). 실트래픽 하 전환은 미검증.
- **V1** `k8s/` 에 CSI·SPC **0건**, `secretKeyRef` 는 mysql 뿐 → gateway·user 개인키 매니페스트 **둘 다 부재**. **매니페스트가 lint 보다 먼저**여야 P7 key-ownership lint 가 vacuous-green 을 피한다(PR3d-a 에서 3번 밟은 함정).
- **V2** `Mode.DUAL_ACCEPT` 는 `InternalTokenAuthenticationFilter:62` 에 실제 구현됨 → §7 ② 가능. baked 기본값이 `SIGNED_ONLY` 라 ② 는 ConfigMap override 왕복(이미지 재빌드 아님).
- **V3** `gke-security-smoke.sh:170` `{ ... } | tee` 서브셸 → `CANARY_RESULT` 미전파 재현(PR3c 흡수분, b-1 수정).
- **V4** `gateway-exposure-lint.sh:211-225` 가 "승인 CSI 정확히 1개" 교체 지점. `initContainers 0개` 계약과 충돌 없음.
- **V5** `local-keys/` gitignored, 커밋된 `.pem` 은 testFixtures 4 + 공개키 2.

**파생 정정**: §8 loop3 #3 의 "verifier + 내부토큰 필터 공존 rollback 전용 호환 이미지"는 §9.1(verifier 삭제 완료)로 **전제 소멸** — 되돌릴 대상은 직전 릴리스 이미지. 행렬은 b-1 에서 재작성.

**다음**: **PR3d-b-1** 착수(진입 조건 없음) → **b-2**(GKE 재기동 + CSI Driver + Secret Manager 키 등록) → PR4(관측성 S9).

---

## 구현 ③ Spring Cloud Gateway — PR3d-b-1: 키 배포 매니페스트 · 소유 경계 lint · 롤아웃 게이트 — 2026-08-13 — [#83](https://github.com/Kimgyuilli/PeakCart/pull/83)

> 계획서 §10.2. PR3d-a 가 남긴 "개인키가 클러스터에 도달할 경로 없음"(미충족 #1)과 PR3c 의 "user 개인키 ad-hoc k8s Secret" 편차를 **클러스터 없이 그린이 되는 범위**로 해소했다. 실 키 주입·롤아웃 실행·barrier 증적은 b-2.

**순서 제약을 먼저 지켰다** — 매니페스트가 lint 보다 먼저다(§10.1 V1). 반대로 하면 검사 대상이 없어 P7 lint 가 vacuous-green 이 된다. PR3d-a 에서 세 번 밟은 함정이라 착수 순서 자체를 계획에 못박고 시작했다.

**핵심 결정**:
- **개인키는 etcd 를 경유하지 않는다** — k8s Secret 은 base64 일 뿐이고 `kubectl get secret` 권한이면 원문이 나온다. 내부 토큰 개인키는 NetworkPolicy 우회 시 마지막 방어선이라 노출 = 전 서비스 위조다. Secret Manager → CSI → 노드 tmpfs 직접 투영(ADR-0013 D2·ADR-0017 D2). `secretObjects`·`nodePublishSecretRef` 는 이 이점을 되돌리므로 lint 가 금지한다.
- **공개키는 ConfigMap** — 비밀이 아닌데도 이미지 베이크를 안 쓴 이유는 **회전**이다. overlap 은 리스트가 1→2→1 로 변하는 일이라 이미지에 고정하면 회전이 빌드 파이프라인에 묶여 "선배포 → 수렴 → 전환"(§11) 순서를 지킬 수 없다. 인덱스 env 로 ConfigMap 편집 + 재시작만으로 끝낸다.
- **두 lint 의 검사 대상이 반대다** — exposure-lint 는 gateway **한 워크로드의 노출 표면**을, workload-key-ownership-lint 는 **"누가 그 키를 가져갔나"** 를 본다. 후자를 신설한 이유가 이것이다(같은 스크립트에 넣으면 전제[kubectl]와 실패 원인이 섞인다).

**설계 가정 1건이 테스트로 뒤집혔다**: 착수 시 Spring 이 리스트를 **인덱스 단위 병합**한다고 보고 "이미지 기본값 항상 1개" 규약을 세우려 했으나 `InternalTokenPropertiesBindingTest` 가 반증 — 리스트는 **최고 우선순위 소스가 통째로 대체**한다. 실제 동작이 더 안전하다(ConfigMap 이 신뢰 kid 집합의 단일 출처 → 회전 ⑤가 실제 폐기로 이어진다). 렌더·lint 로는 안 잡히고 b-2 실 클러스터에서야 드러났을 지점이라 §11.2 도 정정했다.

**프로세스**: `/work` single 리뷰(1,569줄). **분할 아티팩트 0건** — PR3d-a 가 3 chunk 로 나눠 24건 중 10건이 "다른 chunk 파일이 없어 컴파일 불가" 류 오판이었던 것과 대비된다. **11건 전량 실제 결함, 전량 반영**. 그중:
1. **내 검증도구 false-green 3건** — SPC 를 **이름으로만** 승인해 `resourceName` 만 상대 키로 바꾸는 우회가 열려 있었고(이 PR 의 존재 이유가 키 도메인 분리인데), 소유자를 이름으로만 판정해 `Job/gateway` 가 통과했고, 개인키 탐지가 이름 정규식이라 `bundle.pem` 에 PKCS#8 담으면 미탐이었다.
2. **내 논증 오류 1건** — "Pod 200 = 서명 주입" 은 다운스트림이 SIGNED_ONLY 일 때만 참인데 §7 ③ 구간은 DUAL_ACCEPT 다. 평문만 주입해도 200 이라 "평문 주입 0" 게이트가 false-green 이었다 → 전제를 관측으로 확인 후 아니면 거부.
3. **내 판단 오류 1건** — P10 ② "직접경로 Bearer 거부"를 "클러스터 밖에서 불가능"이라 빼고 사유를 주석에 적었는데, **gateway Pod 안에서는 가능**하다(NetworkPolicy 가 허용한 유일 peer).
4. self-test 판정을 `grep -qF`(ID 존재) → **ID×기대 횟수 multiset**(변이 35→49종). `APP_INTERNALTOKEN_MODE` ConfigMap 배치는 ADR-0007 위반(동작 규약의 프로파일 유출)이라 제거.

**추가 발견**: `rollout-convergence-gate.sh` 의 python 블록이 `\"` 이스케이프 때문에 **파싱조차 안 되는 상태**였다. 클러스터가 없어 실행될 일이 없었던 탓에 Codex 도 놓쳤고, b-2 첫 실행에서 터졌을 코드다.

**검증**: lint 10종 · **self-test 69종**(exposure 25·workload 24·np 8·itko 7·dockerfile 5) · `kustomize build` 3종 · 10모듈 빌드+테스트 · 임베디드 python 4블록 compile + 양/음성.

**미충족(명시)**:
1. **실 클러스터 미적용** — Secret Manager 에 키가 없다. 렌더 그린을 배포 가능으로 기록하지 않는다(PR3a→PR3b 와 동일 취급).
2. **barrier ② 는 gateway 경유 경로 중심** — 서비스 측 서명 검증 자체는 통합테스트 소관. 단 gateway Pod 내부 직접경로 Bearer 거부는 검증한다.
3. **minikube 도 CSI Driver 없이는 gateway 부팅 불가**(base 에 SPC 포함). PR3d-a 이전에도 개인키가 없어 마찬가지였으므로 회귀는 아니다.
4. 부하 하 event-loop lag 미측정(PR3d-a 승계).

**다음**: **PR3d-b-2** — 진입 조건 GKE 재기동 + Secrets Store CSI Driver 설치 + Secret Manager 키 등록. PR4(관측성 S9)와 같은 클러스터 세션 권장.

---

## 구현 ④ Choreography Saga — ④-a: 예약 lease · 주문 상태 전이 낙관 락 — 2026-08-13 — [#84](https://github.com/Kimgyuilli/PeakCart/pull/84)

> 계획서 `task-impl4-choreography-saga.md` P1~P4·P13(일부). **착수 전 코드 검증이 범위 자체를 바꿨다** — ADR-0012 §Consequences 가 지정한 ④ 산출물 4개 중 2개(예약/확정/복구 consumer·`stock.reservation.result`)는 strangler-1~5 에서 이미 완료였고, 실질 잔여는 "saga 를 만드는 것"이 아니라 **이미 도는 saga 가 미결 종료 상태를 남기는 구멍**이었다.

**L-013 은 게이트대로 실측하고 승격했다.** TASKS 보류 항목은 "실측 후 승격"이 조건이라, `@Version` 을 먼저 넣지 않고 재현부터 했다. 두 `EntityManager` 가 커밋 전 같은 스냅샷을 읽도록 강제한 **결정적** 재현(확률적 재현의 음성은 기각 근거로 못 쓴다) 결과 **양방향 lost update**: 취소 선커밋 → 최종 `PAYMENT_COMPLETED`(취소 유실), 결제 선커밋 → 최종 `CANCELLED`(과금된 주문이 취소로 표시). `OrderStatus.canTransitionTo` 는 각 트랜잭션의 *스냅샷* 기준이라 전이 가드를 통과하면서 결과가 틀린다 — 가드로는 막을 수 없는 종류다.

**핵심 결정**:
- **고정 TTL sweeper 를 폐기하고 lease 계약으로 전환** — 초안은 `reserved_at + 30분` 을 Product 가 혼자 판정하게 했는데, `OrderJpaRepository` 의 두 만료 조회가 **"예약은 확정됐으나 결제를 시작하지 않은 `PENDING`"** 구간을 아무도 안 잡는다(하나는 `reservationConfirmedAt IS NULL` 만, 다른 하나는 `PAYMENT_REQUESTED` 만). 그 구간의 재고를 sweeper 가 회수하면 살아있는 주문의 재고를 뺏는 **oversell** 이다. → 만료 시각을 Product 가 부여해 **saga 참여자가 공유**하고, Order 가 먼저 취소하고, Payment 는 만료 lease 승인을 거부하고, sweeper 는 만료+유예 후에만 회수한다. **정상 경로에서 sweeper 회수 건수는 0이어야 정상**이며 0이 아니면 그 자체가 알림 대상이다.
- **알림은 종료 상태의 근거가 못 된다** — 취소된 주문에 결제 완료가 도착하는 경로를 처음엔 `SlackPort` 알림 + `return` 으로 처리했다. 그런데 order-service 의 `SlackPort` 는 배포 구성상 **no-op**(PR3b 게이팅)이고, 소비가 커밋되면 `processed_events` 때문에 **같은 이벤트를 다시 소비할 수 없다** → 환불 구현(P8)이 입력을 잃는다. 소비와 같은 트랜잭션에 `order_compensations` 원장을 남기는 것으로 교체했다.
- **`@Version` 만으로 수렴 규칙이 서지 않는다** — 낙관 락은 "먼저 커밋한 쪽이 이긴다"일 뿐이다. 진 쪽 처리를 명시했다: 타임아웃 취소는 **재시도 포기**(결제가 이겼다는 뜻), 결제 완료는 `ORD-003` DLQ 대신 **보상 원장**.

**리뷰가 내 결론을 뒤집었다 (P0)**: 계획서에 "P4 로 oversell 이 닫혔다"고 적었으나 **틀렸다**. `PaymentCommandService.confirmPayment` 는 `ensureConfirmable()` 이후 **같은 트랜잭션 안에서** PG 를 호출하고, 그 시점 주문은 `payment.requested` 가 outbox→poller→Kafka 를 거치기 전이라 여전히 `PENDING` 이다 → 검사 통과 → 만료 취소 → release → 재고 복구 → 타 주문 재예약 → 승인 성공. **진입 시점 검사는 fence 가 아니다.** 승인 마진(남은 lease > 2분)으로 창을 줄였을 뿐이고, 진짜 fence(예약을 승인 전용 상태로 CAS 전이)는 saga 프로토콜 변경이라 ADR 선행 별도 PR 로 분리했다. 계획 §1 명제는 이 경로에서 **미달성**이며 §2.6 R-1 로 명시했다. 이 함정은 `PLAN-BLINDSPOTS.md` **B12** 로 승격.

**프로세스**: `/work` single 리뷰(1,701줄) — 1차 **timeout(exit 124)** 으로 GW-2b degraded(risk=high) → 예산 600s→1500s 확대 재시도 → 5건(P0 1·P1 3·P2 1) **전량 반영**. `/ship` dry-run 에서 drift `partially_live` 가 떴으나 실제 drift 아님(브랜치 커밋 0 + `scheduler/` 디렉토리 통째 untracked 라 `git status` 가 디렉토리로 접어 보고) → `add -N` 후 재캡처로 `all_live` 정상화.

**검증**: 10모듈 **604 테스트 0 실패**(신규 17) · lint 10종 그린 · 마이그레이션 4종(order V3/V4·product V3·payment V3, legacy backfill 포함).

**미충족(명시)**:
1. **R-1 fence 미구현** — 승인↔회수 경합 창이 마진 이내로 남는다. ADR 선행 후 별도 PR.
2. **R-2 배포 의존성** — ④-a 단독 배포 시 `order_compensations` 가 `OPEN` 으로 쌓이기만 하고 환불은 수동. ④-c(P8)를 같은 릴리스 주기에 배포해야 한다.
3. **R-3 롤아웃 창** — 구 Product 가 발행한 메시지는 lease 필드 부재로 만료 판정 제외. Product 선배포로 닫힌다.
4. 크로스서비스 경합 순서 주입(승인↔취소↔sweeper)은 단일 모듈 재현 불가 → ④-d E2E 이관.

**신규 부채**: **D-020**(결제 승인 ↔ 로컬 커밋 불일치 — Toss 승인 후 커밋 실패 시 과금 잔존·롤백, 웹훅 reconciliation 부재). GW-1 리뷰 #3 에서 발견했으나 PG reconciliation 표면이라 ④ 범위 밖으로 분리.

**다음**: **④-b**(이벤트 계약 — `OrderCancelledPayload` `items[]`(ADR-0012 D2 명문)·`reason` enum·`payment.failed` 취소의 `order.cancelled` 발행 여부 결정) → ④-c(환불 요청 경로·DLQ 원장·runbook) → ④-d(관측·E2E·saga-contract 게이트·문서).

---

## 구현 ④ Choreography Saga — ④-b: order.cancelled 이벤트 계약 — 2026-08-14 — [#85](https://github.com/Kimgyuilli/PeakCart/pull/85)

> 계획서 `task-impl4-choreography-saga.md` P5·P6·P7. ④-a 가 "상태 수렴 + lease"였다면 ④-b 는 **계약**이다 — ADR 이 명문화했으나 코드가 이행하지 않은 두 항목(`items[]`, `payment.failed` 취소의 발행)을 닫고, 취소 사유를 payload 에 넣어 소비자가 추론을 그만두게 했다.

**P7 은 결정이 먼저였다.** 계획서가 "발행 여부를 먼저 결정하라"고 요구한 항목이라, 코드를 쓰기 전에 두 대안의 결과를 대조했다. ADR-0010 D3-4 는 발행을 명문화했지만, ADR-0012 D3 refine 이후 Product 는 `payment.failed` 를 직접 소비해 release 하므로 **기능적으로는 발행하지 않아도 구멍이 없다**. 그래서 근거는 "필요해서"가 아니라 **계약의 단순성**이다 — `order.cancelled` 를 *모든* 취소의 단일 lifecycle 이벤트로 두면, 취소를 알고 싶은 소비자가 경로별로 다른 토픽을 구독할 필요가 없다.

**대신 중복 부작용을 소비자별로 실증했다**: Product 는 예약 원장 `RESERVED→RELEASED` CAS 라 두 번째 release 가 no-op, Payment 는 자기 상태가 `FAILED` 라 `cancelBeforePayment()` 가 no-op(APPROVED 오탐 알림이 뜨지 않는지 확인), Notification 은 `reason=PAYMENT_FAILED` 를 스킵. **세 번째가 P6 에 의존하므로 P6 없이 P7 만 넣으면 중복 알림이 그대로 나간다** — 두 항목의 순서가 우연이 아니다.

**하위호환 방향을 명시했다.** 구 메시지(`reason` 부재)와 **모르는 값**은 둘 다 알림 발송으로 처리한다. 스킵을 기본값으로 두면 미래에 사유가 추가될 때 알림이 조용히 사라진다 — 하위호환에서 안전한 쪽은 침묵이 아니라 알림이다.

**리뷰가 내 테스트를 반증했다 (GW-2 #2)**: 계획 §5.2 는 P7 에 "동일 트랜잭션 Outbox 증명"을 요구하는데, 내가 추가한 단위 테스트는 `@InjectMocks` 객체를 직접 호출해 **Spring 프록시도 DB 트랜잭션도 없다** — `@Transactional` 을 떼도, 주문 저장과 Outbox 저장이 분리돼도 계속 통과하는 false-green 이었다. `OrderCancelledEventContractIntegrationTest` 로 교체: 성공 시 `CANCELLED` + Outbox 1행 동시 커밋 + payload 계약, `@MockitoSpyBean` 으로 Outbox 저장을 실패시켜 주문 상태와 `processed_events` 까지 전량 롤백됨을 확인한다. **내 검증도구가 false-green 이었던 게 누적 4회째**(PR3d-a·b-1·④-a·④-b).

**#1(P2)도 반영**: `handlePaymentFailed` 가 `order.cancel()` 을 무조건 호출해, 사용자·타임아웃 취소가 선커밋된 경합에서 `ORD-002` 로 롤백돼 DLQ 로 갔다. P7 이전에도 있던 결함이지만 **발행이 붙으면서 대가가 "중복 발행"에서 "DLQ"로 커졌다**. 형제 경로(`reserved=false`)가 이미 쓰던 no-op 규약으로 맞췄다.

**검증**: 10모듈 **617 테스트 0 실패**(신규 10) · lint 10종 그린(매니페스트 미변경) · 마이그레이션 없음(payload-only 변경).

**미충족(명시)**:
1. **Layer 1 문서 드리프트** — `02`/`03`/`04` 의 `order.cancelled` payload 표기는 아직 구 계약. P15(④-d) 소관으로 이연(④-a 와 동일 취급).
2. **R-2 배포 의존성 유지** — 본 PR 은 ④-a 의 원장→환불 의존성을 바꾸지 않는다.
3. **크로스서비스 중복 발행 실증은 단위/통합 대조까지** — 실제 Kafka 위에서 Product/Payment/Notification 이 동시에 두 이벤트를 받는 순서 주입은 ④-d E2E(P12) 소관.

**다음**: **④-c**(P8 환불 요청 경로 · P9/P10 DLQ 원장+runbook · P13 나머지) → ④-d(관측·E2E·saga-contract 게이트·문서).

---

## 구현 ④ Choreography Saga — ④-c 선행: ADR-0018 보상/환불 트리거 계약 — 2026-08-15 — [#86](https://github.com/Kimgyuilli/PeakCart/pull/86)

> ④-c(P8 환불 경로) 착수 전 코드 검증이 **범위와 선행 조건을 둘 다 바꿨다.** 계획서 P8 은 Product 의 `PAID_BUT_UNRESERVED` 하나만 지목했지만, 실제로는 감지가 **3지점**이고 셋의 영속성이 제각각이며(Order=영속 원장 / Product=marker / **Payment=Slack·로그뿐**), 환불을 실행할 수단(`TossPaymentClient` 취소 API)이 아예 없었다. 새 토픽 + 새 상태머신이 필요한데 ADR-0012 D4 매트릭스에는 환불 토픽이 없다 → **ADR 선행**으로 분기.

**왜 ADR 을 먼저 했나.** ④-a 의 lease 계약은 *기존 토픽에 필드 추가*라 refine 으로 갔다. 이번은 **새 토픽 + 크로스서비스 상태머신**이라 D4 의 토픽 열거를 벗어난다 — 같은 취급을 할 수 없다는 것이 판단 기준이었다.

**핵심 결정**:
- **감지 3지점 → Payment 단일 실행자 → 회신으로 원장 종결.** 요청 토픽은 producer 별로 2개(공용 토픽 1개는 `NewTopic` 소유자와 payload 소유가 모호해져 기각) + 회신 1개
- **진입점과 실행자를 분리했다(리뷰 #5)** — 세 진입점은 `REQUESTED` 커밋만, `REQUESTED→CLAIMED` CAS 로 claim 한 **dispatcher 만 PG 호출**. 소비 트랜잭션 안에서 외부를 호출하면 **PG 성공 후 롤백 시 fence 행이 사라져 fence 자체가 무효**가 된다. 즉시성을 포기하고 crash 안전성을 얻는 교환
- **보장 문구를 다시 썼다(계획 리뷰 P0)** — "환불 API 호출 1회"는 로컬 수단으로 **달성 불가능한 조건**이다. 계약은 **"동일 논리 환불 1건"**을 보장하고, crash matrix 3칸이 전부 "PG 조회로 진실 확정"으로 수렴하므로 조회 API 가 필수 구성요소가 된다
- **종결의 의미를 결과별로 갈랐다(리뷰 #6)** — 환불이 실패했는데 원장이 `RESOLVED` 로 닫히면 원장이 거짓말한다. `FAILED`=`REFUND_FAILED`, `UNRESOLVED`=전이 안 함 + reconciliation(5분, 조회 상한 24h) → 수동 종결
- **`ALREADY_CANCELED` 는 실패가 아니다(리뷰 #7)** — 이전 호출 성공 후 응답 유실이거나 외부 수동 취소일 수 있어 이미 목표 상태일 가능성이 높다. 즉시 FAILED 로 닫으면 실제로 환불된 결제를 `APPROVED` 로 남긴다

**리뷰가 잡은 내 오류 2건**:
1. **감지 지점 개수**(GP-2 #2) — `PaymentEventConsumer.handleOrderCancelled` 의 APPROVED 분기를 빠뜨렸다. 단순 누락이 아니라 대안 지형을 바꾼다(Payment 가 이미 알고 있으므로 "Order 미발행" 안이 성립 — 다만 Order 경로만 Outbox 보장을 잃어 기각)
2. **backfill 멱등 근거**(GW-2 #3) — Payment DB 의 유니크 제약으로 Product/Order 의 재발행을 막는다고 썼다. **DB-per-service 에서는 어느 DB 의 제약인지가 보장 범위를 규정한다.** ④-a P0(진입 검사≠fence), ④-b #2(단위테스트≠트랜잭션 증명)에 이어 **내가 세운 보장의 적용 범위를 실제보다 넓게 서술한 세 번째 사례**

**ADR-0012 처분 = refine (Status 무변경)**: D3 ④가 "환불 트리거"를 이미 예고했고, D4 의 기존 7토픽은 하나도 무효화되지 않으며 그 규약(group 명명·파티션 키·1 topic 1 producer)을 그대로 따른다. ADR-0016 무효화 범위와도 겹치지 않는다.

**미충족(명시)**:
1. **Toss 취소 API 실호출 불가** — 승인된 실거래가 필요. ④-c-1 검증은 클라이언트 계약 + 상태머신/fence/crash 복구 테스트로 한정되며 외부 PG 장애 주입은 **D-020 과 같은 게이트**에 묶인다
2. **잔여 위험** — PG 멱등키를 써도 "PG 성공 + 조회 실패" 동시 장애 구간은 사람 손을 요구한다. 계약은 그 구간을 없애는 게 아니라 **보이게 만든다**(backlog·최장 age 게이지)
3. **구현 계획서 동기화 이연** — P8 선행 표기·④-c 재분할은 ④-c-1 착수 시(GP-2 #6 부분 반영)

**다음**: **④-c-1**(P8 구현) → ④-c-2(P9·P10·P13) → ④-d(P11·P12·P14·P15).

---

## 구현 ④ Choreography Saga — ④-c-1a: 환불 실행 엔진 — 2026-08-15 — [#87](https://github.com/Kimgyuilli/PeakCart/pull/87)

> ADR-0018(#86)이 확정한 계약의 실행 엔진. payment-service 안에서 닫히는 절반이며, 크로스서비스 트리거·회신 소비는 ④-c-1b 다.

**핵심 구조 결정은 "진입점과 실행자의 분리"다.** 소비 트랜잭션 안에서 PG 를 호출하면 **성공 후 로컬 롤백 시 fence 행이 사라져 fence 자체가 무효**가 된다 — 다음 트리거가 다시 호출한다. 그래서 진입점은 `REQUESTED` 를 커밋만 하고, `REQUESTED→CLAIMED` CAS 로 claim 한 dispatcher 만 PG 를 부른다. 즉시성을 포기하고 crash 안전성을 얻는 교환이며, 그 대가로 스케줄러가 2개 늘었다.

**보장 문구는 "PG 호출 1회"가 아니라 "동일 논리 환불 1건"이다**(ADR-0018 D3). 로컬 fence 는 동시성만 직렬화하고, crash matrix 4칸은 전부 **PG 조회로 진실을 확정**하는 것으로 수렴한다 — 그래서 조회 API 는 선택이 아니라 필수 구성요소가 됐다.

**실측이 구현을 한 번 뒤집었다**: fence 삽입을 `ON DUPLICATE KEY UPDATE id=id` 로 짰는데 **중복에서도 1을 반환**했다. MySQL Connector/J 기본이 affected-rows 가 아니라 **found-rows** 시맨틱이라 no-op 업데이트가 1로 보고된다 → 두 진입점이 모두 fence 승자로 판정. `INSERT IGNORE` 로 교체해 0/1 로 갈랐다. "유니크 위반을 catch 해서 no-op" 은 JPA 에서 아예 성립하지 않는다(flush 시점·rollback-only)는 것도 함께 못박았다.

**리뷰 2라운드 18건 전량 반영**:
- **라운드 1 (10건)** — 가장 큰 건은 **stale CLAIMED 를 dispatcher 가 조회 없이 재호출**하던 것. ADR 이 요구한 "재호출 전 조회" 경로가 코드에 아예 없었다. 그리고 **내 테스트가 그 잘못된 구현을 정당화하고 있었다** — "stale claim 이 재claim 된다"를 성공 기준으로 적었는데 계약은 그 반대다. 테스트가 계약이 아니라 구현을 기술하면 이렇게 굳는다.
- **라운드 2 (8건)** — **2건이 라운드 1 수정이 만든 새 결함**이다. ① generation fencing token 을 넣었지만 읽기를 잠그지 않아 검사↔커밋 TOCTOU 창이 남았고 ② UNRESOLVED 를 claim 해도 상태가 그대로라 **조회·재호출이 진행 중인 건을 운영자가 수동 종결**할 수 있었다(이후 취소가 성공해도 이미 terminal 이라 미반영 = "환불은 됐는데 원장은 FAILED"). **재리뷰를 돌리지 않았으면 둘 다 남았다.**

**검증**: 10모듈 **651 테스트 0 실패**(신규 30) · lint 10종 그린 · 마이그레이션 V4(NULL 가드를 모든 DDL 앞에 — MySQL DDL 은 롤백되지 않아 뒤에 두면 재배포가 불가능해진다).

**미충족(명시)**:
1. **R-2 미해소** — 회신 소비가 ④-c-1b 다. 계획 §6 이 "1a 완료 보고에 이 사실을 명시"하도록 조건화해 둔 항목이다.
2. **rollout gate** — 1a 는 회신을 발행하되 소비자가 없다. 1a/1b 를 같은 릴리스 주기에 배포해야 하고, 지연 시 Kafka retention 을 넘기면 안 된다.
3. **Toss 실호출 미검증** — 계약 테스트로 대체. "실제 환불이 된다"를 검증했다고 기록하지 않는다(D-020 과 같은 게이트).
4. 크로스서비스 E2E 는 ④-d(부모 P12).

**다음**: **④-c-1b**(요청 토픽 2개·트리거 발행 2경로·backfill·회신 소비 3곳·Notification·문서 — R-2 해소) → ④-c-2(DLQ 원장·runbook).

---

## 구현 ④ Choreography Saga — ④-c-1b: 크로스서비스 환불 계약 — 2026-08-25 — [#88](https://github.com/Kimgyuilli/PeekCart/pull/88)

> ④-c-1a 가 payment-service 안에서 닫은 실행 엔진에 **트리거와 회신**을 붙여 R-2 를 닫는다.
> ④-a 가 남긴 "`order_compensations` 가 `OPEN` 으로 쌓이기만 하고 환불은 수동" 이 여기서 해소된다.

**세 진입점이 하나의 fence 로 수렴한다.** 요청 토픽 2종(`stock.compensation.requested` · `order.compensation.requested`)과 Payment 로컬 감지는 전부 `payment_refunds.order_id` UNIQUE 에 삽입만 시도하고, 중복은 예외가 아니라 정상 no-op 이다. 그래서 **cross-topic 순서에 의존하는 로직이 코드에 없다** — ADR-0018 D1 이 "순서 무보장"을 명시했고, 수렴 책임을 상태머신과 fence 에만 둔 결과다. 요청 2종 × 로컬 감지의 선후·중복 조합 전수를 통합테스트로 고정했다.

**멱등 근거의 층위를 분리했다.** backfill 재실행이 추가 발행 0 인 것은 **producer DB 안의 `NOT EXISTS`(aggregate_type + aggregate_id + event_type)** 가 보장하고, 환불 실행이 1건인 것은 Payment DB 의 fence 가 보장한다. DB-per-service 에서 Payment 의 UNIQUE 는 Product/Order 가 Outbox 행을 다시 만드는 것을 막지 못한다 — GW-2 라운드에서 지적받은 "내가 세운 보장의 적용 범위를 실제보다 넓게 서술" 사례를 이번엔 계약 주석과 SQL 양쪽에 못박았다.

**backfill SQL 을 2단계로 나눈 이유**: eventId 가 `outbox_events.event_id` 와 payload 안 두 자리에 **같은 값**으로 들어가야 하는데 한 SELECT 에서 `UUID()` 를 두 번 부르면 값이 갈라진다. 파생 테이블은 옵티마이저가 머지해 같은 문제가 재발할 수 있어, 컬럼에 먼저 확정한 뒤(`payload='{}'`) 그 값을 읽어 envelope 을 조립한다. 두 단계 사이에서 실패해도 재실행이 안전하다 — 1단계는 `NOT EXISTS` 로 건너뛰고 2단계가 이어 채운다.

**종착이 둘인 이유(D4)**: `RESOLVED` 는 "환불 완료"를 뜻한다. 환불이 실패했는데 원장을 해결됨으로 닫으면 **그 원장은 거짓말을 한다** → `REFUND_FAILED`(+`failure_code`) 를 신설해 "닫혔지만 해결되지 않음"을 구분한다. Product 도 같은 규칙이며, `compensated_at` 은 감지 marker 이지 종결 표시가 아니라서 종결 컬럼 3개를 따로 뒀다. `UNRESOLVED` 는 **어느 소비자도 전이하지 않는다**.

**Notification 은 성공만 알린다(D6)** — 실패·미확정은 내부 미결이며 사용자에게 전가하지 않는다. 운영이 해소한 뒤 성공 알림으로 수렴한다.

**감지와 요청은 같은 트랜잭션이다.** Product 는 marker CAS 와 요청 Outbox 를, Order 는 보상 원장 저장과 요청 Outbox 를 함께 커밋한다. `detectedAt` 은 원장에 기록된 시각을 그대로 실어 두 기록이 같은 사실임을 코드로 고정했다(marker 타임스탬프를 호출자가 넘기도록 `markCompensatedIfAbsent` 시그니처 변경). Outbox 실패 주입 시 감지 기록까지 롤백되는 것을 `@MockitoSpyBean` 으로 판정한다 — ④-b 에서 단위 mock 이 트랜잭션 경계를 증명하지 못한 전례를 따른 것이다.

**검증**: 10모듈 **697 테스트 0 실패**(신규 통합테스트 45건 — Order 15 · Product 14 · Payment 14 · Notification 2 추가, 마이그레이션 3종 적용) · lint 10종 그린. 신규 listener 5개(요청 2 · 회신 3)는 **실제 Kafka 왕복 테스트로 배선을 고정**하고, 순서·중복 조합 전수는 직접 호출로 빠르게 돌린다.

**Codex 리뷰 3 chunk 14건 → 13건 반영·1건 기각**(기각은 분할 아티팩트). 가장 큰 건은 **이 PR 의 존재 이유를 되돌리는 P0** 였다 — `payment.refunded` 가 `payment.completed` 보다 먼저 도착하면 소비자는 원장이 없어 회신을 no-op 하고 `processed_events` 로 봉인하는데, 뒤늦게 만든 `OPEN` 원장의 요청은 fence 에 흡수돼 **회신을 영영 못 받는다**. "세 진입점이 하나의 fence 로 수렴한다"를 계약으로 적어놓고 **fence 가 요청을 흡수한 뒤 회신이 없는 경우**를 보지 않은 것이다. 종결된 fence 에 요청이 오면 회신을 재발행하도록 고쳤고, 그 과정에서 **가드 순서 때문에 1차 수정이 실패**했다(환불 성공 후 `payments` 는 `REFUNDED` 라 기존 `APPROVED` 가드가 먼저 걸린다 — 테스트가 잡았다).

두 번째는 **backfill 2단계 분리가 만든 새 노출**이다. 재실행 안전성만 따지고 *두 문장 사이에 다른 프로세스가 있다*는 것을 계산에 넣지 않아, 롤링 배포 중 구 poller 가 `payload='{}'` 를 발행하고 `PUBLISHED` 로 봉인할 수 있었다 — 요청 영구 유실이다. 1단계를 발행 대상이 아닌 `BACKFILL` 상태로 두고 2단계가 payload 완성과 `PENDING` 전환을 **한 UPDATE 에서** 수행하게 바꿨다.

나머지도 대부분 **fail-open 과 false-green** 이었다: 요청 소비가 `reason`/`detectedAt` 없이 금전 동작을 개시(+`orderId` null→0 축약), Notification 이 `asLong()` 강제 변환으로 사용자 0 에게 0원 알림, 종결 전이가 사유 코드 없이 미해결을 영구 고정, 그리고 **테스트가 계약이 아니라 구현을 기술한 2건** — 필수 필드 부재를 "관용"으로 고정한 테스트와, 마이그레이션 SQL **복제본**을 검증 대상으로 삼은 backfill 테스트다. 후자는 ④-c-1a 라운드1 과 같은 구조다(검증 대상과 검증 도구가 같은 출처여야 한다) → 테스트가 V4/V5 파일을 직접 읽어 DML 을 실행하도록 바꿔 SSOT 를 하나로 만들었다. listener 배선도 직접 호출만으로는 group/factory 가 틀려도 통과해, 3서비스에 실제 Kafka 왕복 테스트를 더했다.

**미충족(명시)**:
1. **크로스서비스 E2E 는 ④-d(부모 P12)** — 본 작업은 서비스별 통합테스트까지다. 부모 계획 P12/P14 에 환불 체인과 결과·crash 매트릭스를 **요구사항으로 등재**했다(실행은 ④-d)
2. **Toss 실호출 미검증** — ④-c-1a 와 동일 게이트(D-020)
3. **rollout gate 유지** — 1a/1b 는 같은 릴리스 주기에 배포한다. 1b 지연 시 회신이 쌓이는 기간이 Kafka retention 을 넘으면 안 된다
4. **DLQ 원장·runbook 은 ④-c-2** — DLQ 유입은 종결이 아니라 그 원장의 입력이다

**다음**: **④-c-2**(부모 P9·P10·P13 나머지 — DLQ 원장·runbook) → ④-d(부모 P11·P12·P14·P15).

---

## 구현 ④ Choreography Saga — ④-c-2a: DLQ 원장 적재 — 2026-08-26 — [#90](https://github.com/Kimgyuilli/PeakCart/pull/90)

> 부모 P9·P10·P13(나머지). **계획 리뷰 3라운드에서 ④-c-2 가 2a/2b 로 갈렸다** — replay 는 ADR 선행(④-c-2b).

**계획 리뷰가 세 라운드에서 30건을 냈고 전량 반영했다(12 → 8 → 10, 감소하지 않음).** 각 라운드의 수정이 새 계약 표면을 만들고 그게 다음 라운드 결함이 되는 패턴이었다. 3R 에서 10건 중 6건이 replay 경로에 몰렸고 그중 다수가 ADR 사안이라(notification outbox 신설 = ADR-0012 D1 변경 · replay 전용 outbox 표현 · 좌표 reader · 브로커 retention 실계약) 거기서 잘랐다. **2R 때는 "replay 에 몰렸다"는 근거가 없었다(8건 중 2건)** — 데이터가 바뀌어서 분할한 것이지 건수 추세로 판단한 게 아니다.

**"재발행 중복 0" 이 두 라운드 연속 반증됐다.** 2R 은 2단 상태머신(`REPLAY_REQUESTED` → 발행 → `REPLAY_PUBLISHED`)을 깼고 — 발행 직후 사망 시 발행 여부를 판별할 수 없다 — 그 대안으로 내놓은 기존 outbox 재사용도 3R 에서 같은 crash window 를 가진 것으로 실측 확인됐다(`OutboxPollingService:83-86` 이 broker ack 후 **별도로** `markPublished()`+`save()`). 같은 주장이 두 번 반증된 표면은 계획서 수정이 아니라 설계 결정이라 ADR 로 올렸다.

**계획의 핵심 전제 세 개가 리뷰로 뒤집혔고, 전부 코드/바이너리로 직접 확인했다**:
1. "`DeadLetterPublishingRecoverer` 가 consumer group 헤더를 안 붙인다" → **거짓**. `spring-kafka-3.3.14` bytecode 에 `HeadersToAdd.GROUP` 이 있고 `whichHeaders = EnumSet.allOf(...)` 가 기본값이다. 커스텀 헤더 주입 작업을 통째로 삭제했다
2. 식별자 `(topic, eventId, group)` → **eventId 없는 메시지가 DLQ 의 대표 입력**이다(`KafkaMessageParser:29` 이 eventId 누락·JSON 파싱 실패를 예외로 던진다). 좌표 기반으로 교체
3. `originalKey` 를 `DLT_*` 헤더에서 읽기 → **`DLT_ORIGINAL_KEY` 는 존재하지 않는다**(13개 중 없음). 원본 key 는 DLQ 레코드 자신의 key 다

**식별자가 6컬럼인 이유는 둘 다 "조용한 유실" 이다.** ① DLQ 토픽은 공유라 `payment.completed` 를 3서비스가 각자 group 으로 소비한다 — group 없이는 누가 미결인지 답하지 못한다. ② `(topic, partition, offset)` 은 **토픽 재생성 시 유일하지 않다** — offset 0 재사용으로 과거 행과 충돌하고 `INSERT IGNORE` 가 새 실패를 정상 중복처럼 폐기한다 → `cluster_id` + `topic_generation`. 그리고 **6컬럼 전부 NOT NULL** 이다. 판독 불가에 NULL 을 쓰면 MySQL UNIQUE 가 NULL 끼리 충돌시키지 않아 같은 poison record 가 여러 행이 된다 → `DLQ_ORIGIN` 종류를 두고 DLQ 자신의 좌표를 쓴다.

**quarantine 소유자 규칙은 내가 3R 대기 중 자체 반증했다.** "group 부재분은 원본 토픽 발행 서비스가 소유" 로 정했는데, **발행 서비스는 자기 토픽을 소비하지 않아 자기 `.dlq` 도 구독하지 않는다** — 지정된 소유자가 그 레코드를 볼 수 없었다. quarantine 전용 listener 를 별도로 신설했고, 두 소유권 집합이 교차하지 않음(서비스는 자기 발행 토픽을 소비하지 않으므로)을 계약 테스트로 고정했다.

**listener 실패는 "유실 ↔ poison record" 딜레마다.** durable 대체 저장소 없이 둘 다 만족할 수 없어 **무유실을 택했다** — `ackAfterHandle=false`(기본값 true 면 recoverer 후 offset 이 커밋돼 원장에 못 쓴 레코드가 영구 유실) + 유한 backoff + 소진 시 DLQ 계열 컨테이너 정지. 업무 listener 는 영향받지 않는다.

**알림 보장을 낮췄다.** 초안은 "신규 INSERT 당 정확히 1회" 였으나 DB commit 과 Slack 은 한 트랜잭션이 아니라 commit 후 사망하면 0회다 → **best-effort** 로 적고, 라우팅 알림도 유지했다(지금 제거하면 listener 미배선·DB 장애 시 알림과 원장이 동시에 사라진다). 내구적 신호는 원장 행 자체이며 `actuator/deadletter` 로 조회한다.

**구현 중 자체 발견 2건**:
1. `topic-generations` 조회가 `DLQ_ORIGIN` 경로에서 깨졌다 — 그 행은 좌표로 `.dlq` 이름을 싣는데 키는 원본 토픽이었다. 통합테스트가 잡았다 → `.dlq` 는 원본 세대를 따르게 했다
2. **runbook 이 내 도메인 가드를 우회하도록 지시하고 있었다.** 종결을 `UPDATE ... SET status` 로 안내해 `discard()` 의 "사유 필수" 가드가 코드에만 있고 운영에는 없는 장식이 될 뻔했다. **3R #7 이 replay 리허설에 대해 지적한 false-green 구조를 2a 종결 경로에서 내가 그대로 재현한 것** → `DeadLetterEndpoint` 에 `@WriteOperation` 진입점을 만들고 runbook 을 curl 로 교체, 리허설 테스트가 그 진입점만 호출하도록 했다

**검증**: 8모듈 **751 테스트 0 실패**(신규 54) · lint **11종**(신규 `dead-letter-schema-parity-lint`, self-test 4종) 그린. 부모 P13 은 "구현 완료·실행계획 증적 잔여" 로 판정하고 EXPLAIN 증적을 받았다(기존 sweep 테스트는 limit·경계만 봤다).

**미충족(명시)**:
1. **replay 전부** — ④-c-2b(ADR 선행). runbook 에 "현재 재발행 불가" 와 우회 절차를 명시
2. **"정확히 1곳" 은 cross-service 증명이 아니다** — (ownership 매핑 계약) × (서비스별 실제 Kafka 왕복)까지이며 4서비스 동시 기동으로 확인하지 않았다. ④-d 부모 P12
3. 메트릭·E2E·CI 게이트·Layer 1 동기화 — ④-d
4. 라우팅 알림 제거 · 브로커 `retention.ms`/`cleanup.policy` 미설정(2b 전제)

**다음**: ④-c-2b(ADR 선행) 또는 ④-d.

---

## 구현 ④ Choreography Saga — ④-d-1: saga 관측성 — 2026-08-26 — [#91](https://github.com/Kimgyuilli/PeakCart/pull/91)

> 부모 P11. **④-d 는 계획 리뷰 2라운드에서 d-1(관측성)/d-2(E2E·게이트·종결)로 분할됐다.**
> **이 PR 은 ④ 를 종결하지 않는다** — 종결은 ④-d-2 소관이다.

**분할 근거는 리뷰 결함의 영역 분포다.** 2R 9건이 E2E 하네스 5 · 매트릭스 게이트 3 · 메트릭 1 로 갈렸고, P11 은 E2E 인프라에 의존하지 않는다. 1R 시점에는 이런 근거가 없었고 2R 에서 데이터가 생겨 나눴다 — 건수 추세로 판단한 게 아니다.

**계획 리뷰가 전제 4건을 뒤집었다**(전부 코드/바이너리로 직접 확인): §4 E2E SQL 이 실제 스키마와 불일치(`inventories.quantity`→`stock`, `refund_status`→`refund_result`) — 첫 줄에서 실패할 쿼리였다 / **매트릭스 게이트가 논리적으로 불가능** — 매트릭스가 기대 행의 유일한 입력이면 행 삭제는 검사 대상만 줄여 통과한다 / `TossPaymentClient` 가 baseUrl 하드코딩이라 환불 성공 체인 E2E 도달 불가 / CI 이미지 artifact 업로드가 `push` 한정이라 **PR 러너에는 이미지가 하나도 없다**.

**메트릭 설계의 축은 "실제 전이가 일어났을 때만 올린다" 다.** 예약·복구·확정·보상은 전부 멱등 경로를 갖는다 — CAS 패자·중복 marker·double-release 는 정상 no-op 이다. 그때도 올리면 메트릭이 실제 사건 수를 부풀리고 **alert 임계값이 무의미해진다.** 타임아웃 취소를 `reason` 3종으로 가른 것도 같은 이유다(합치면 "취소가 늘었다" 까지만 알고 어느 잡인지는 다시 로그를 읽어야 한다). 보상은 Counter 와 Gauge 를 함께 뒀다 — 누적 Counter 로는 잔량을 못 세서 **alert 를 만들 데이터 series 가 없다**.

### diff 리뷰 3라운드 13건 전량 반영 (5 → 5 → 3)

**각 라운드가 직전 라운드 수정이 만든 결함을 잡았다** — 2R 5건 중 3건, 3R 3건 중 1건.

1. **Counter 가 커밋 전에 증가**(1R) — 계측 지점이 전부 `@Transactional` 안이라 이후 커밋이 실패하면 DB 는 롤백되고 카운터만 남는다. 내 계획 §4 의 계약을 스스로 깬 것 → `CommitAwareMetrics`(afterCommit 이연).
2. **그 `afterCommit` 예외가 호출자에게 전파**(2R) — 그 시점엔 DB 가 이미 커밋돼 되돌릴 수 없는데 호출자는 커밋 실패로 받는다. `OrderEventConsumer` 같은 Kafka listener 안이면 **이미 커밋된 이벤트를 재처리**한다. **관측성 실패가 비즈니스 처리 결과를 바꾸는 것**은 메트릭 값이 틀리는 것보다 나쁘다 → try/catch 격리.
3. **`0 * metric` 우회**(2R) — 1R 에서 넣은 "메트릭 이름 집합 정확 일치" 를 통과하면서 Grafana 의 `$A > 0` 은 영원히 거짓이다. 이름·라벨 집합 검사로는 구조적으로 막을 수 없다 → 식 정본 고정. 그런데 **신규 2종만 고정해 기존 4종에 같은 우회가 잔존**(3R) → 전체로 확장 (see ADR-0019).

**false-green 3건을 자백해 둔다**: 타임아웃 테스트가 실제 잡 대신 메트릭 객체를 직접 호출해 **스케줄러에서 계측을 지워도 통과**했다 / `CommitAwareMetrics` 자체 테스트가 없어 **구현을 즉시 증가 방식으로 되돌려도 전부 통과**했다(1R 수정의 핵심이 아무것도 고정되지 않았다) / DLQ 메트릭 계약이 order 에만 있어 다른 3서비스에서 Gauge 가 사라져도 아무 테스트도 실패하지 않았다.

**검증**: 8모듈 **792 테스트 0 실패**(리뷰 전 770 → 신규 22) · lint 11종 · **promql self-test 10종** · dead-letter self-test 4종.

**미충족(명시)**:
1. **alert 발화 미검증** — ADR-0015 가 정적 lint 범위를 규정했고 실제 평가는 Grafana/Prometheus 기동 + fixture 가 필요하다
2. **식 정확 일치는 문자열 비교이지 의미 분석이 아니다** — 정본 자체를 무력화된 식으로 고치면 통과한다. 그 지점의 방어는 코드 리뷰다 (ADR-0019 §Consequences)
3. **④ 종결 아님** — P12 E2E · P14 게이트 · P15 문서는 ④-d-2
4. Grafana 대시보드 패널 · 토픽별 DLQ 분해 메트릭(카디널리티 이유로 의도적 제외)

**다음**: ④-d-2(선결 D1~D8 — 특히 `RefundDispatcher` 비활성화 프로퍼티가 없어 E2E 가 CI 에서 실제 Toss 운영 API 를 호출하는 문제) → ④ 종결.

---

## 구현 ④ Choreography Saga — ④-d-2a: cross-service saga E2E 하네스 — 2026-08-28 — [#92](https://github.com/Kimgyuilli/PeekCart/pull/92)

> 부모 P12. **④-d-2 는 계획 리뷰 3라운드(13→11→12건) 후 사용자 승인으로 2a/2b 로 분할**했다.
> **이 PR 은 ④ 를 종결하지 않는다** — 종결은 ④-d-2b(P20) 소관이다.

**분할 근거는 규모와 "실행 없이 완료 선언 불가" 다.** 계획이 P1~P20 이고 P1~P9 만으로 이미 2600줄이었다. 더 중요한 건, 시나리오를 **한 번도 실행하지 않은 상태**로 "cross-service E2E 를 세웠다" 고 보고하면 이 프로젝트가 반복해 겪은 false-green 그 자체가 된다는 점이었다. 그래서 2a 의 완료 조건에 **실제 스택 실행 증적**을 넣었다.

### 설계가 두 번 뒤집혔다

**seed runner 는 애초에 성립하지 않았다.** 계획 리뷰 3R 이 "E2E 전용 runner 를 운영 bootJar 에 넣지 말라" 고 했는데, 그러면 **컨테이너 안에서 실행될 수 없다**(컨테이너는 bootJar 를 돈다). main 에 넣으면 인증·업무 진입점을 우회하는 발행 스위치가 된다 — **두 선택지가 모두 막혀 있었다.** 대신 `app.internal-token.mode=DUAL_ACCEPT`(기존 운영 지원 전환기 모드)로 4서비스를 띄우고 runner 가 평문 `X-User-*` 로 **진짜 컨트롤러**를 호출한다. 새 운영 코드 0·새 모듈 0·새 스위치 0이면서 컨트롤러→도메인 전이→publisher 직렬화→outbox→poller 를 전부 지난다. 시나리오 A 의 `payment.failed` 는 합성이 아니라 stub 의 승인 5xx 로 `PaymentCommandService:58-62` 의 catch 가 실제로 발행한 것이다.

**계획이 요구한 kill switch 는 폐기했다.** `dispatch-enabled`/`reconcile-enabled` 를 만든 이유는 "E2E 에서 PG 호출 표면을 끈다" 였는데, stub + `internal: true` 로 설계가 바뀌면서 **E2E 는 두 잡을 켠 채 돌린다**(환불 체인 전구간이 시나리오 C 의 대상이므로 꺼서는 안 된다). 즉 **아무도 쓰지 않는데** 환경변수 하나로 운영 스케줄러 빈을 조용히 없애는 위험만 남았다 — ADR-0018 은 dispatcher/reconciliation 을 미결 환불 수렴의 필수 구성요소로 규정한다. 오설정 시 health 는 정상인 채 `REQUESTED`/`CLAIMED`/`UNRESOLVED` 가 영구 적체된다.

### 정본 복제를 두 번 피했고, 한 번은 놓쳤다

업무 구독 21쌍은 **`DlqTopology` 에서 유도**한다 — 별도 상수로 복제하면 정본이 둘이 되고 양쪽을 함께 잘못 고치면 각자의 자기대조가 모두 통과한다. 그런데 **DLQ listener group 은 `PeekcartService.dlqListenerGroup()` 이 이미 정본이었는데** 내가 `DlqTopology` 에 상수를 신설해 중복 정본을 만들었다(diff 2R #3 이 지적). annotation 이 컴파일 상수를 요구해 상수 자체는 남기되, **전 enum 정합 계약 테스트**로 묶었다. 리터럴 값만 교환해도 잡힌다.

### 실행이 전제를 계속 반증했다

시나리오를 실제로 돌리기 전까지는 전부 추정이었다. 실행이 잡아낸 11건은 **전부 하네스 오류**였고 운영 코드 결함은 0이다: `categories`/`payments` seed SQL 컬럼 · 장바구니 응답 201 · **envelope 필드가 `data` 가 아니라 `payload`**(조용한 fallback 이 `reason=None` 을 만들어 계약 위반처럼 보이게 했다) · `dead_letter_records.origin_topic` 은 `.dlq` 가 아니라 원본 토픽 · **`wait_price_cached` 가 group 이름만 보고 앞 시나리오 행으로 즉시 통과**(계획 R2 #3 이 경고한 오염이 실제로 발생) · **가드 거부(`PAY-008`)와 PG 실패(`PAY-005`)를 구분하지 못했다**(전자는 Toss 를 부르지도 않아 `payment.failed` 가 없다).

**하나는 유익한 부작용이었다.** `TOSS_BASE_URL` 이 `application-local.yml` 의 리터럴을 못 이겨 dispatcher 가 실제로 `api.tosspayments.com` 을 호출했고, **`internal: true` 가 `UnknownHostException` 으로 막았다.** 격리가 없었으면 CI 러너가 실 PG 로 나갔을 것이다 — 네트워크 수준 차단(N2)의 라이브 증거다.

**환경 튜닝 3건도 실측으로 나왔다**: 자동생성 토픽 3파티션 × 20토픽 × 28 group 의 메타데이터 부하로 소비가 **3분 지연** → 1파티션 고정 / 4 JVM 동시 기동이 360s healthcheck 창을 넘김 → **순차 기동** / `docker compose down` 이 `E2E_RUN_ID` 를 요구해 **정리 절차가 실패**, 잔여 스택 13개가 이후 실행과 자원을 다퉜다.

### diff 리뷰 3라운드 19건 전량 반영 (10 → 5 → 4)

**매 라운드가 직전 수정의 결함을 잡았다.** 2R 5건 중 3건이 1R 수정이 남긴 false-green이고, **3R 의 P1 1건은 3R 수정 자신이 만든 것**이다 — 앰비언트 환경 격리를 `SystemEnvironmentPropertySource` 로 복원하자 **완화 매핑 때문에 `TOSS_PAYMENTS_BASE_URL` 이 재유입**됐다. 하필 E2E compose 가 쓰는 이름이라, 그 변수를 export 한 개발자 환경에서 계약 테스트 5건 중 4건이 깨진다(리뷰어가 재현). 이름 열거를 버리고 **정규화 키** 비교로 교체했다.

1R 에서 추가한 알림 대기가 2R 에서 **여전히 vacuous** 로 판정된 것도 같은 패턴이다 — A 의 `user=100` 은 배제했지만 **B 자신의 `order.created` 가 만든 `ORDER_CREATED` 행**이 조건을 만족시켰다. `type` 으로 키잉해 해소했다.

**검증**: 8모듈 **813 테스트 0 실패** · lint **13종**(신규 `e2e-network-contract-lint` self-test 9 · `kafka-subscription-contract-lint` self-test 9) · pg-stub self-test 19 · **E2E 실제 스택에서 시나리오 A/B/C/D 각각 통과**.

**미충족(명시)**:
1. **수렴 미달** — 3R 이 P1 1건으로 끝났고(그 1건도 3R 수정이 만든 것) 상한 3회로 종료했다. **3R 수정 자체는 제3자 리뷰를 받지 못했다** → ④-d-2b 착수 시 #92 diff 를 포함해 1라운드 선행
2. **4종 연속 실행 불안정** — 각각은 통과하나 연속 실행 시 뒤쪽이 consumer 지연으로 타임아웃. 완화(1파티션·순차 기동·상한 180s)는 넣었으나 제거 못 함. 근본 대응은 P10/P19
3. 환불 회신 3소비자 종결 · 결과 3종 분기 · stub ledger 순서 계약 · DLQ `attempt_count=2` 미검증
4. gateway 서명 토큰/`SIGNED_ONLY` 미검증 (구현 ③ GKE smoke 소관)
5. **④ 미종결** — P20 소관

**다음**: ④-d-2b (P10~P20 + ④ 종결).

---

## 구현 ④ Choreography Saga — ④-d-2b: 계약 게이트 · 음성 대조군 · ④ 종결 — 2026-08-29 — [#93](https://github.com/Kimgyuilli/PeekCart/pull/93)

> 부모 P10~P20. **이 PR 이 구현 ④ 를 종결한다.**

### 착수 조건부터 지켰다

④-d-2a 는 "3R 수정 자체가 제3자 리뷰를 받지 못했다" 를 미충족으로 남기고 **다음 PR 착수 시 #92 diff 를 포함한 1라운드**를 조건으로 걸었다. 그대로 먼저 돌렸다 — 3137줄, **5건(P0/P1 0)**. **3R 수정 4건은 전부 유효 판정**이라 조건은 해소됐고, 나온 5건은 3R 과 무관한 잔여였다.

그중 하나는 **기각하고 계획서를 고쳤다.** 리뷰는 "stub `confirm` 을 계획대로 500 으로 되돌리라" 고 했는데, 그러면 P6 이 깨진다 — P6 은 `POST /api/v1/payments/confirm` 을 **실제 HTTP 진입점**으로 요구하고, confirm 이 항상 500 이면 시나리오 A 가 가드 거부(`PAY-008`)와 PG 실패(`PAY-005`)를 구분할 수 없다. 초안 문장이 P6 확정 **전에** 쓰인 것이고, 지키려던 "조용한 통과 차단" 은 이미 `STUB_UNSCRIPTED_KEY` 가 **표면이 아니라 키 단위로** 담당한다.

### 게이트를 세우자 게이트가 스스로를 속이는 방식이 세 번 드러났다

**첫째, 검사 대상 축소.** 격리 lint 가 `services` 가 비어 있지 않은지만 봐서 **`payment-service` 를 지운 fixture 가 exit 0** 이었다. 위반이 줄어든 게 아니라 검사할 것이 줄어든 것이다. 같은 함정이 매트릭스에도 있어서 **required-ID 정본을 lint 안**에 뒀다(계획 N4). 나중에 `egress-canary` 를 추가하자 "대조군이 하나라도 보이면 통과" 가 다시 느슨해져 집합 전체를 요구하도록 고쳤다.

**둘째, 남의 행이 내 단언을 만족시키기.** 시나리오 A 의 `processed_events` 단언이 `consumer_group` 만 조회했다. 이 테이블은 `(event_id, consumer_group)` UNIQUE 이므로 **앞 시나리오가 남긴 행**이 조건을 만족시킨다 — 세 소비자가 전부 no-op 이어도 통과했다. 시나리오 D 도 `origin_topic='payment.failed'` 로만 조회했는데 **A 가 같은 토픽에 실제 이벤트를 흘린다**. 전자는 발행 outbox 의 `eventId`, 후자는 poison 본문에 심은 marker 로 키잉했다(`DLT_ORIGINAL_KEY` 는 #92 실측대로 없다).

**셋째, 도구 부재를 격리로 오인하기.** 이건 **양성 대조군이 없었으면 영원히 초록**이었다. egress 음성 프로브가 `python3` 를 쓰는데 **payment-service 이미지에 python3 가 없다** — 연결이 막혀서가 아니라 명령이 없어서 실패했다. 양성 대조를 함께 두자 그 사실이 즉시 드러났고, 함께 주소 유도 실패와 "Docker Desktop 에서 브리지 게이트웨이는 VM 안 주소" 까지 세 건이 연달아 반증됐다. 최종 판정은 **rc 를 특정**한다(0 이면 격리 붕괴, 127 이면 대조 자체가 무의미).

### 스케줄러는 "돈다는 사실" 이 고정되어 있지 않았다

타임아웃 3종과 sweeper 의 기존 테스트는 `@InjectMocks` 객체의 메서드를 **직접 호출**한다. 잡의 로직은 검증하지만 `@Scheduled` 를 통째로 지워도 전부 통과한다. sweeper 는 특히 위험하다 — 안전망이라 평소 회수 건수가 0이고, 배선이 끊겨도 지표상 아무 일도 일어나지 않는다.

주기·lock 을 base 소유 타입 안전 properties 로 빼고(ADR-0007 — 동작 정책), **직접 호출 없이 실제 발화로 DB 가 바뀌는 것**을 보는 통합 테스트를 넣었다. **변이 검사로 실증했다**: `@Scheduled` 한 줄을 지우자 Order 2건·Product 2건이 모두 FAILED 였고 원복 후 통과했다.

**계획 원안의 결정성 방안은 성립하지 않았다.** "컨텍스트 기동 **전** seed initializer" 를 쓰라고 했는데, 앱 테이블 스키마는 Flyway 가 컨텍스트 기동 **중**에 만든다. 대신 주기와 lock 하한만 짧게 덮어 반복 발화가 seed 를 잡게 하고, **운영 기본값은 별도 properties 계약 테스트**가 고정한다 — 한 테스트에 두 관심사를 넣으면 둘 중 하나는 반드시 거짓이 된다(빠른 주기로 덮으면 운영값을 못 보고, 운영값을 쓰면 결정적이지 않다).

### #92 가 남긴 "연속 실행 불안정" 의 원인은 내 코드였다

④-d-2a 는 시나리오 4종이 각각은 통과하나 **연속 실행하면 뒤쪽이 타임아웃**한다고 적고 근본 대응을 P10/P19 로 미뤘다. P19 의 구간별 계측이 병목을 특정해줬다: `scenario:b` 가 단독 13초인데 A 다음에는 **180~220초**였다.

**가설 세 개가 실측으로 연달아 반증됐다.** ① `local` 프로파일의 `show-sql`·DEBUG 로깅 — 기동과 A 는 빨라졌지만 B 는 그대로 ② 토픽 파티션 late discovery — 사전 생성을 넣었는데 B 는 여전히 189초 ③ 이벤트 유실(운영 버그) — `processed_events` 2행 확인으로 **유실이 아니라 순수 지연**임이 확정.

**실제 원인은 내가 쓴 사전 생성 코드의 버그였다.** `while read` 루프 안에서 `docker compose exec -T` 를 부르면 그 명령이 **루프의 stdin(토픽 목록)을 통째로 삼켜** 첫 반복 뒤 루프가 끝난다. 20종 중 1종만 생성됐고, **같은 버그로 검증 루프도 1건만 돌아 "대조 실패 0" 이 vacuous** 였다 — 게이트가 스스로를 속인 네 번째 사례다. `</dev/null` 을 붙이자:

```
토픽 20종 — 생성 성공 20 · 대조 실패 0
scenario:b  162~189초(실패/경계) → 21s → 10s → 14s
```

**시나리오 4종이 3회 연속 전량 통과**했다. 원리는 확인된 대로다 — 소비자가 토픽 생성 **전에** 구독하면 파티션 0만 보고, 나머지 파티션의 메시지는 메타데이터 갱신(약 200초) 뒤에야 소비된다. 그래서 **두 번째 시나리오만** 느렸다. 재발 방지로 `생성 성공 == 선언 개수` 를 계약으로 강제했고, 토픽 파티션 정본은 `TopicBuilder` 선언에서 `--emit-topics` 로 유도해 정본 복제를 피했다.

### 검증

- 8모듈 전체 테스트 · lint **16종**(신규 `saga-contract-matrix-lint` 포함) · 매트릭스 self-test **36종** · 순서 계약 self-test **9종** · 격리 lint self-test **12종** · 구독 계약 self-test 9종 · pg-stub self-test 19종
- **실제 스택**: 시나리오 4종 **3회 연속 통과** · 음성 대조군 **7종 통과**
- **변이 검사**: `@Scheduled` 제거 시 배선 테스트 4건이 실패함을 확인

### 미충족 (④ 종결 시점에 명시)

1. ~~`e2e` 잡이 GitHub 러너에서 실행된 적 없다~~ → **해소** ([run 33260424768](https://github.com/Kimgyuilli/PeekCart/actions/runs/33260424768)) — 시나리오 4종·음성 대조군 전량 통과, `--jvm-evidence`(build)와 `--e2e-evidence`(e2e)가 각각 자기 단계에서 실행됨. 잡 소요 15분 41초. **`scenario:b` 14초** — 로컬에서 162~189초로 실패하던 시나리오다
2. **replay 경로 미구현** — ④-c-2b 는 여전히 ADR 선행 대기(D1~D7)
3. **alert 발화 미검증** — ADR-0015 가 정적 lint 범위를 규정한다(④-d-1 승계)
4. **gateway 서명 토큰/`SIGNED_ONLY` 미검증** — E2E 는 `DUAL_ACCEPT` 평문 헤더를 쓴다(구현 ③ GKE smoke 소관)
5. **Toss 실호출 미검증** — D-020 게이트(외부 PG 장애 주입 환경)
6. **egress 대조가 증명하는 것은 "다른 네트워크 호스트에 닿지 못한다"** 까지다. 인터넷 egress 차단 자체의 증거는 #92 의 `UnknownHostException` 라이브 관측이다
7. 환불 결과 3종 분기의 **E2E** 관측(JVM 통합테스트로는 3×3 전부 고정됨) · stub ledger 순서 계약 · DLQ `attempt_count=2`

### ADR-0012 ④ 산출물 대비 실제 범위

ADR-0012 §Consequences 는 구현 ④ 의 산출물을 **4개**로 규정했다 — 예약/확정/복구 consumer · `stock.reservation.result` · `OrderCancelledPayload` 보강 · 타임아웃 스케줄러. 실제 진행은 이 목록과 두 방향으로 어긋났고, 둘 다 의도된 것이다.

**앞 2개는 ④ 착수 전에 이미 끝나 있었다.** 예약/확정/복구 consumer 와 `stock.reservation.result` 는 구현 ① 의 strangler-1~5(#56·#57·#58·#61)가 사가 클러스터를 떼어내며 함께 만들었다. ④ 착수 시 코드 검증에서 이를 확인하고 **실질 잔여를 "이미 도는 saga 의 미결 종료 상태 닫기" 로 재확정**했다(TASKS ④ 행에 기록).

**대신 ADR-0012 가 예상하지 않은 표면이 두 개 늘었다.** ① **보상/환불 트리거 계약** — §D3 ④ 가 "환불 요청 + 운영 알림으로 수렴" 이라고만 적은 지점을, 감지 3지점(Product marker · Order 영속 원장 · Payment 비영속)과 fence·dispatcher 분리·결과별 종착까지 구체화하려면 새 결정이 필요해 **ADR-0018** 을 선행했다(ADR-0012 처분 = refine). ② **DLQ 원장** — 미결을 상태로 남기려면 실패 레코드의 물리 좌표가 필요했다(④-c-2a). replay 는 여전히 ④-c-2b 로 남아 있고 **ADR 선행 대기**(D1~D7)다.

즉 ADR-0012 의 4개 산출물은 전부 충족됐고, ④ 의 실제 범위는 그보다 **넓다**. 그 확장분의 근거는 ADR-0012 본문이 아니라 ADR-0018·ADR-0019 에 있다.

**다음**: 구현 ⑤ CQRS 로컬 캐시 (또는 ④-c-2b ADR 선행).

---

## 구현 ⑤ — CQRS 로컬 캐시 (L-006 Redis fallback) — [#94](https://github.com/Kimgyuilli/PeakCart/pull/94)

### 범위 재확정 — ⑤ 의 본체는 구현 ① 안에서 이미 끝나 있었다

착수 전 코드 검증에서 로드맵이 ⑤ 에 귀속시킨 4표면을 전수 대조했다. 구현 ④ 와 같은 패턴이다.

| 표면 | 코드 사실 | 처분 |
|---|---|---|
| `product.updated` 발행 | `ProductOutboxEventPublisher:68` — 7 필수필드 + `version`, 파티션키 `productId`, Outbox 경유 | ✅ strangler-2 (#57) |
| Order 로컬 캐시 구독·갱신 | `ProductPriceCacheConsumer` + `product_price_cache`. 멱등 `processed_events`, stale-skip `source_version` | ✅ strangler-2 (#57) |
| 동기 호출 제거 | `ProductPort` 소멸. `OrderCommandService`(ORD-007) · `CartCommandService.addItem`(ORD-009) 모두 캐시 경유 | ✅ strangler-4 (#61) |
| **Redis 캐시 fallback (L-006)** | `CacheErrorHandler` 구현체 **0건** | 🔲 → **본 PR** |
| 장바구니 조회 상품정보 조합 | `CartItemDto=(id,productId,quantity)` — 조합 없음 | 🔲 별도 task 승격 |

코드 주석이 이미 `"CQRS ⑤, strangler-2"` 로 자기 귀속을 적어둔 상태였다.

### ADR-0012 ⑤ 산출물 대비 실제 범위

ADR-0012 `:124-127` 은 구현 ⑤ 의 산출물을 `"product.updated 발행/소비 + product_cache"` 로 규정했다. **이 목록은 충족됐다** — 다만 실제 테이블명은 `product_price_cache` 이고, Order 는 payload 7필드 중 `price`/`version` 만 적재한다.

어긋난 지점은 `§D2:53` 이다. 7 필수필드가 `"Order product_cache/장바구니 조회 충족"` 이라 적었는데, **장바구니 조합 소비처가 만들어지지 않았다.**

이 차이를 어떻게 닫을지가 계획 리뷰 2·3라운드의 쟁점이었다. 초안은 *"payload 필드 계약에 대한 진술이고 그 계약은 지켜졌으므로 정정할 사실 오류가 없다"* 고 판정했으나, **이 논거는 철회했다** — `:53` 은 소비처를 명시한 문장이 맞고, "필드 계약일 뿐"이라는 축소는 결론(ADR 무변경)에 맞춰 끌어온 재해석이었다.

**처분: `docs/adr/README.md` §원칙의 Update Log 경로.** 바뀐 것은 결정(이벤트로 동기 호출 대체 · 7 필수필드 · 파티션 키)이 아니라 **테이블명과 소비 범위라는 사실 진술**이므로, 새 ADR(`:14` — 트레이드오프 변경·Consequences 재해석)이 아니라 본문 정정 + `## Update Log` + `fix(adr):` 커밋이 맞다. Status·ADR 개수는 변경하지 않았다.

이는 ④ 가 같은 상황을 `PHASE4.md` 기록만으로 닫았던 것과 **다른 선택**이다. ④ 는 ADR 진술 자체에 오류가 없었고(산출물 4개가 전부 충족), ⑤ 는 진술에 오류가 있다.

### 무엇을 만들었나

- **`ResilientCacheErrorHandler`** — get/put 은 조용히 삼키고(→ 캐시 미스 → DB), evict/clear 는 삼키되 **WARN + `cache_fallback_total{cache,operation}`**. evict 실패는 DB 커밋 후 TTL(상세 30m / 목록 10m) 만료까지 stale 창을 열기 때문이다
- **`CacheConfig implements CachingConfigurer`** — Spring 은 `CacheErrorHandler` 를 `CachingConfigurer` 빈에서만 수집한다(`AbstractCachingConfiguration#setConfigurers`). 맨 `@Bean` 은 조용히 무시된다. `cacheManager()` 는 오버라이드하지 않아 `@ConditionalOnProperty` 2빈 구조와 S7 라벨을 보존
- **Redis 타임아웃 500ms / connect 300ms** (base 소유, ADR-0007) — 유계 타임아웃이 없으면 무응답 Redis 에서 Lettuce 기본 60s 를 기다린 뒤에야 fallback 이 불려 fail-open 이 무의미하다
- **runbook** `docs/runbooks/redis-cache-fallback.md` — 복구 = TTL 만료 대기, 즉시 해소는 SCAN + UNLINK(`KEYS` 금지 — 단일 스레드 Redis 를 블록해 게이트웨이·JWT 블랙리스트까지 멈춘다), 배포는 타임아웃+핸들러 원자 배포, 감시 임계 PromQL 고정

### 계획 리뷰가 뒤집은 전제

- **`cacheManager()` 오버라이드와 `@Bean` 이름 혼동** — `cache_manager` 라벨은 `@Bean` 메서드 이름에서 오고 오버라이드는 빈 이름을 바꾸지 않는다. 결론은 유지하되 근거를 교체
- **put 실패 "무해" 단정** — 캐시가 안 채워져 후속 요청이 전부 DB 를 친다. Redis 장애의 DB 전이이고, 동시 요청에서는 stampede 다
- **1s 타임아웃 + 2s 상한이 산술적으로 불가** — `@Cacheable` 1요청이 get 실패 → DB → put 실패로 timeout 을 **2회** 소비한다
- **`hpx_plan_lint` 가 zsh 에서 실행 불가** — `sync.sh` 의 `local path=` 가 zsh 의 `path`↔`PATH` 연동을 건드려 PATH 를 계획서 경로로 덮는다. 선언만 고치면 참조(`$path`)가 남아 더 확실히 깨진다

### 검증

- **Toxiproxy 장애 주입 7종** — 연결 거부(`Proxy#disable()`)와 무응답(downstream `timeout(_,0)` toxic)을 **다른 기구로** 분리했다. 컨테이너 정지는 거부만 재현하고 되돌릴 수 없어 순서 의존을 만든다
- **V0 이 하네스 자체를 먼저 고정한다** — backend Redis 에 `@ServiceConnection` 을 남기면 앱이 프록시를 우회해 **전 시나리오가 false-green** 이 된다. `@DynamicPropertySource` 로 프록시에 배선하고, 실제 경유를 첫 단언으로 확인
- **put 실패의 DB 전이 실증** — 장애 중 동일 상품 5회 조회 시 `findById` 5회(스파이), 양성 대조군(정상 캐시)은 1회
- **변이 검사** — `errorHandler()` 오버라이드를 맨 `@Bean` 으로 바꾸면 **7건 중 6건 FAILED**. 정상 경로인 양성 대조군만 통과했다. 바이트코드로 세운 전제가 런타임에서 확인된 지점

### 리뷰 라운드가 서로를 고쳤다

이 PR 의 리뷰는 3라운드 모두 **직전 라운드 수정이 만든 결함**을 잡았다.

- 1R → 2R: 예외 허용 범위를 좁히려다 Lettuce 최상위 `RedisException` 을 통째로 허용
- 2R → 3R: 그 축소가 이번엔 **너무 좁아** in-flight 연결 종료를 5xx 로 되돌렸다. lettuce-core 6.6.0 `DefaultEndpoint` 는 연결이 끊긴 뒤 들어온 명령에 **원인 없는 bare `RedisException`** 을 만들고(바이트코드 확인), Spring 은 여기에 `IOException` 을 붙이지 않는다. 2R 의 우려("`RedisException` 을 허용하면 명령 거부·interrupt 가 함께 들어온다")는 **deny 를 먼저 보는 구조에서 성립하지 않았다** — 서버 오류 계열은 전부 `RedisCommandExecutionException` 하위라 deny 한 줄로 막힌다
- 2R → 3R: lint 의 주석 제외가 라인을 통째로 버려 `/* note */ @Bean MeterFilter ...` 우회를 열었다. 주석 토큰만 제거하도록 고쳤고, 그 과정에서 sed 구분자 `:` 가 `grep -n` 접두사의 `:` 와 충돌해 치환이 통째로 죽는 자체 결함도 변이 검사가 잡았다

교훈은 **"좁히는 수정도 결함을 만든다"** 는 것이다. 넓힌 것만 위험하다고 보고 축소를 안전하다고 취급하면, 2R→3R 같은 회귀를 놓친다.

### diff 리뷰가 잡은 내 버그

`@Configuration` 에 `MeterRegistry` 를 **직접 주입**한 것이 `MeterRegistryCustomizer` 적용 **전에** 레지스트리를 생성시켜, 이후 모든 메트릭에서 `application=product-service` 태그(ADR-0009 S2)가 사라졌다. 기존 `ProductObservabilityMetricsIntegrationTest` 가 이를 잡았다 — 관측성 계약 회귀 테스트가 **다른 작업의 실수를 잡은 첫 사례**다. `ObjectProvider` 지연 해석으로 수정.

### 미충족 (⑤ 종결 시점에 명시)

1. **장바구니 조회 상품정보 조합 미구현** — `product_price_cache` 에 `name`/`status` 추가 + consumer/DTO 확장 + "캐시 미수신 상품의 표시 정책" 결정이 선행돼야 한다. 별도 task
2. **`cache_fallback_total` alert 발화 미검증** — ADR-0015 가 정적 lint 범위를 규정(④ 미충족 #3 과 동일 게이트). 그 전까지는 runbook §2.1 의 **수동 감시 계약**
3. **cache stampede / DB pool 포화 완화 기구 없음** — 인지·문서화·감시까지만 했다. bulkhead·`@Cacheable(sync)`·rate limit 은 허용 동시성/SLO 를 먼저 정해야 설계가 결정된다
4. **무응답 상한(1.5s)은 로컬 Docker 기준 실측** — 클러스터에서의 재측정은 미실시
5. **`local path` 동일 패턴이 `common.sh:20`·`audit.sh:8`·`state.sh` 에 잔존** — 본 PR 범위 밖이라 손대지 않았다
6. **`StockCompensationRefundIntegrationTest:273` flake 관측** — 전체 스위트 1회 실행에서 Kafka 왕복 listener 배선 테스트가 실패했고 재실행에서 통과했다(최종 실행은 전 모듈 그린). 본 PR 이 손대지 않은 경로라 원인 규명은 하지 않았다 — 재발 시 ④ 계열로 다룬다
7. ~~diff 리뷰 라운드 3 미실행~~ → **해소.** Codex 재설치 후 실행했고 P1 2건이 나왔다 — **둘 다 라운드 2 수정이 만든 새 결함**이다. 상세는 audit 참고

**다음**: 구현 ⑥ Cursor 페이지네이션 (또는 ④-c-2b DLQ replay — ADR 선행 대기). → **⑥ 완료 ([#96](https://github.com/Kimgyuilli/PeakCart/pull/96))**

---

## 구현 ⑥ — 주문 내역 Cursor 페이지네이션 ([#96](https://github.com/Kimgyuilli/PeakCart/pull/96))

### 문제가 성능이 아니었다

로드맵은 이것을 "offset 성능 개선"으로 잡았다. 착수 전 코드 검증에서 다른 것이 나왔다 —
`OrderController:52` 의 `@PageableDefault(size = 20)` 에 **`sort` 가 없었다.** 응답 순서는
MySQL 이 정하는 미정의 순서였고, 클라이언트가 `?sort=` 로 임의 컬럼 정렬을 넣을 수도 있었다.
**offset 페이징으로서의 결과 정합성조차 없던 상태**다.

그래서 이 작업의 1차 가치는 성능이 아니라 **결정적 순서 계약의 최초 확립**이 됐고, 명제에
"동률 `ordered_at` 이 페이지 경계에서 누락·중복되면 미완"을 넣었다.

### 실측이 결정한 것들

- **인덱스에 `id` 를 명시하지 않았다.** InnoDB 세컨더리 인덱스가 PK 를 암묵 부착한다는 전제를
  실행계획으로 확인했다 — 2컬럼 `(user_id, ordered_at)` 인덱스에 대해 커서 질의의
  `used_key_parts` 가 **`[user_id, ordered_at, id]` 3개**다. 계획이 걸어둔 "반증되면 `id` 명시"
  조건은 발생하지 않았다.
- **검사 행 수**: cursor `[20, 20, 20]` vs offset `[40, 420, 920]` (깊이 20/400/900). 깊이 900 에서 46배.
- **`DATETIME(6)` 상한은 `.999999`.** 계획 리뷰 4R 이 문서 근거로 `.499999` 를 채택했는데,
  5R 에서 MySQL 8.0.46 에 직접 INSERT/SELECT 해보니 `.499999`·`.500000`·`.999999` 가 **전부
  원형 저장**된다. 문서의 `.499999` 는 *컬럼보다 많은 자릿수를 넣어 반올림할 때*의 경계였다.
  **그대로 갔으면 정상 데이터를 400 으로 거부했다.**
- **`CREATE INDEX` 의 `ALGORITHM`/`LOCK` 사이에 쉼표를 쓸 수 없다** (`ERROR 1064`). 계획 리뷰
  2R 이 "문법 유효"로 확인해준 항목인데 실행에서 뒤집혔다. 쉼표 형식은 `ALTER TABLE` 전용이다.

### 500 이 될 뻔한 두 경로

둘 다 `GlobalExceptionHandler` 의 포괄 `@ExceptionHandler(Exception.class)` 가 선점해 생긴다.

1. `@Min`/`@Max` 를 메서드 파라미터에 걸면 `HandlerMethodValidationException` 이 **500** 이 된다
   (핸들러 부재). → 검증을 애플리케이션 경계로 옮겼다.
2. `size` 를 `int` 로 바인딩하면 `size=abc`·오버플로가 `MethodArgumentTypeMismatchException`
   으로 역시 **500**. → `String` 으로 받아 직접 파싱한다.

공통 핸들러 보강은 5서비스 영향이라 범위 밖으로 두고 **부채로 남겼다**.

### 계획 리뷰가 수렴하지 않았다

5라운드를 돌렸고 건수는 12 → 10 → 8 → 6 → 6 이었지만, **"직전 라운드 수정이 만든 새 결함"이
5 → 5 → 3 → 5** 로 꺾이지 않았다. 항상 *고친 쪽*이 아니라 **고치면서 건드린 인접 경로**에서
나왔다 — timezone 을 고치며 정밀도를, `@Min/@Max` 를 걷어내며 타입 변환을, 정밀도를 고치며
날짜 유효성을.

5R #1 은 성격이 달랐다. **리뷰 루프 자체가 4R 에서 오류를 주입했고**(문서 논리로 `.499999` 수용),
그것을 잡은 것은 리뷰가 아니라 **실측**이었다. 이후로는 사실 주장을 채택하기 전에 컨테이너에서
직접 확인했다.

수렴 조건(P1=0 + 새 표면 무추가)을 채우지 못한 채 사용자 판단으로 종료했다. 남은 결함이 구조·계약이
아니라 **배선 디테일**(슬라이스 어노테이션·트랜잭션 가시성)로 옮겨갔고, 그 종류는 문서 논리가 아니라
실행해봐야 판정되기 때문이다.

### diff 리뷰 — 5건 전부 "내 테스트가 false-green"

1라운드 5건이 모두 검증 도구 결함이었다: 계획이 요구한 SQL 결과 동등성 미구현(비어있지 않은지만 봄) ·
`examinedRows` 가 트리 전체를 합산해 스캔 노드가 없어도 통과 · `used_key_parts` 미단언 ·
규모 축소 미기록 · 기존 인덱스 1개 누락.

2라운드는 **1R 수정이 만든 새 결함 2건**을 잡았다 — 동등성을 보강하며 `runForIds` 가 SELECT
목록 순서에 의존하게 됐고(JPQL 은 `SELECT o` 라 순서가 계약이 아니다), 인덱스 단언을 강화하며
`containsExactlyInAnyOrder` 가 "이 6개만 존재" 과잉 계약이 됐다. P1=0 으로 수렴.

### 미충족

1. **전 모듈 스위트 로컬 미완주** — 기존 통합 클래스 15개 동시 실행 시 `ContainerLaunchException`.
   **코드 실패가 아니라 자원**(free 메모리 ~65MB, 타 세션 컨테이너 8개 상주). CI 로 이관했고,
   **CI 그린 확인 전에는 머지하지 않는다.** 로컬에서는 배치 1 191 tests 0 실패 · 신규 통합/실행계획
   개별 그린 · lint 14종 PASS.
2. **`GlobalExceptionHandler` 타입 변환 핸들러 부재** — 선재 갭. `common` 이라 5서비스 영향. 부채 후보.
3. **상품 목록은 offset 유지** — `ProductCacheService` 캐시 키가 `pageNumber`/`pageSize` 에
   결합돼 있고 구현 ⑤(#94)가 방금 그 표면을 고정했다. 별도 task (⑤ 미충족 #1 과 같은 표면).
4. **벽시계 성능 미측정** — 구조 지표가 평탄 vs 선형으로 결정적이라 철회.
5. **성능 측정 규모 축소** (20,000 → 5,000행/5사용자). 단일 사용자로 두면 `user_id` 가 비선택적이라
   옵티마이저가 full index scan 을 고른다(실측). 축소가 검출력을 낮추지 않음은 실패 주입으로 확인.
6. **롤아웃 창의 이중 계약** — `maxSurge:1` 이라 신·구 Pod 동시 노출 구간이 있다. 확인된 소비자 0,
   blue/green 은 5서비스 공통 배포 계약 변경이라 미채택.
7. **MySQL 이미지 핀은 실행계획 테스트만** — 나머지는 부동 태그 `mysql:8.0`. 부채 후보.

**다음**: ③ PR3d-b-2(GKE 세션) + PR4 관측성 S9, 또는 ④-c-2b DLQ replay(ADR 선행).


---

## ADR-0020 — DLQ replay 계약 (④-c-2b 선행) — 2026-09-01 — [#98](https://github.com/Kimgyuilli/PeakCart/pull/98)

> ④-c-2a(#90) 이후 남은 "replay 경로 미구현" 의 **선행 ADR**. 문서 전용 PR 이며 코드 변경은 0이다
> (P4 브로커 retention 적용은 후속 **④-c-2b-0**).

### 원 계획의 전제가 절반 틀렸다

`task-impl4-c2b-dlq-replay.md` §2 D4 는 "브로커에 `retention.ms`·`cleanup.policy` 가 **어디에도 설정돼 있지 않다**" 고 적었다.
**선언 기준으로는 참이지만 실효 기준으로는 거짓이다** — `apache/kafka:3.8.1` 의 `/opt/kafka/config/kraft/server.properties`
가 `log.retention.hours=168`(7일)이다. 즉 오늘도 사실상 7일 보존이며, 문제는 값이 아니라 **그것이 계약이 아니라는 것**이다.
따라서 P4 는 *동작을 바꾸는 작업이 아니라 계약을 명문화하는 작업*이고, 이 구분을 흐리면 "설정했다" 는 완료 보고가
**아무 것도 안 바뀐 채 성공처럼** 보인다.

### 결정 하나가 늘었다 — D8

D1~D7 은 원 계획이 세운 목록이다. 계획 검증에서 **소비 경로 원장의 `origin_topic` 은 정의상 남이 발행한 토픽**이라는
사실이 드러났다 — order 의 소비 원장은 `payment.*`·`product.updated`·`stock.reservation.result` 뿐이고, 자기 발행 토픽의
행은 quarantine 경로에만 생긴다. `1 topic = 1 producer` 를 replay 에 그대로 적용하면 replay 가능 집합이 quarantine 행으로
축소되는데 그 행들은 금지 축 ②③ 때문에 오히려 금지 대상이라 **공집합이 된다.** 그래서 발행 권한 예외를 D8 로 세웠다.

### 두 곳은 의도적으로 약하게 결정했다

- **"7일 좌표 가용" 을 보장하지 않고 best-effort 로 뒀다.** 유입량 실측이 없고 PVC 1GiB 안전성도 증명되지 않는다 —
  도메인 토픽만 정상상태 ~420 MiB 로 억제되고 `__consumer_offsets` 50파티션·KRaft metadata 는 bound 밖이다.
- **그 420 MiB 도 hard bound 가 아니다.** retention 검사 주기·`file.delete.delay.ms`·index/timeindex·대형 batch 가
  계산에 없다. 3R 지적을 받고 "bound" 주장을 철회하고 정상상태 목표치로 낮췄다.

### 리뷰가 반증한 것 — 내 진술 5건

1. **"quarantine 행은 정의상 `eventId` 판독 불가"** → 거짓. quarantine 조건은 `failed_consumer_group=='__unknown__'`
   (`DlqOrigin:41-63`)이고 `eventId` 는 `DeadLetterRecorder:57` 이 group 과 **독립** 추출한다. 좌표 출처(`DlqOriginKind`)는
   제3의 축 → 금지 축이 **6개 독립 조건**이 됐고, `origin_kind='DLQ_ORIGIN'` 행을 replay 하면 **`.dlq` 내용이 원본
   토픽에 주입**되는 사고 경로가 드러났다
2. **"replay 는 ADR-0011 producer-owns-topic 과 충돌"** → 오지목. ADR-0011 D2:71 은 `NewTopic` **프로비저닝 소유**다
3. **"Kafka 트랜잭션이 exactly-once 발행 대안"** → 성립 안 함. DB↔Kafka 경계를 없애지 못한다
4. **ADR-0018 을 refine 으로 둔 것** → **자기 판정기준 불일치.** §D8-4 는 "규칙에 예외를 내면 부분 무효화" 라고
   판정해놓고 0018 만 제외했고, 근거로 쓴 "새 도메인 이벤트 한정" 은 **원문 `0018:49` 에 없는 말**이었다
5. **용량 산정** → `retention.bytes` 를 파티션 상한처럼 썼는데 Kafka 는 **닫힌 세그먼트만** 지운다.
   이미지 기본 `log.segment.bytes=1GiB` 직접 확인

### 내가 만든 false-green 3건

- **P4-4 를 "관측해서 사실대로 적으면 통과"** 로 설계했다 — 미선언 config 가 삭제되는 위험한 결과도 green
- **축 A/B 분리를 선언으로만** 하고 단일 `status` 컬럼에서 재결합했다(`DeadLetterRecord:98-105`)
- **반대 방향 false-green** — 자식 과다집계를 고치려 `root_record_id = id` 를 걸었는데 그 컬럼은 additive 라
  ④-c-2a 가 적재한 **기존 행이 전부 NULL** 이다. 그대로면 기존 미결이 전량 탈락해 **backlog 가 0 으로 보인다**

### 검증

문서 전용(비-마크다운 변경 0)이라 `./gradlew test` 를 돌리지 않았다. `hpx_plan_lint` OK · 문서 배선 정적검사 5/5 ·
ADR 상대링크 무결성 PASS · consistency precheck ok(warnings 0).

### 미충족

1. **리뷰 미수렴(양쪽)** — 계획 13→9→15, diff 7→6→7. 3R 잔여는 전부 ADR 문구·계약 정밀도이며 코드 산출물이 아니다.
   실제 구현이 계약을 코드로 옮길 때 다시 검증된다 — 특히 `root_record_id` 전환 계약과 상관 대조 트랜잭션 경계
2. **P4 미이행(의도)** — 브로커 retention 코드 적용은 ④-c-2b-0
3. **PVC 1GiB 안전성 미증명** · **`original_timestamp` NULL 비율 미측정**(네 DB 모두 nullable 이고 현
   `DlqIntegrationTest` 가 timestamp 저장을 단언하지 않는다) — 둘 다 2b 산출물
4. **`retention.bytes` 유한값은 동작 변경** (현재 `-1`). `retention.ms`/`cleanup.policy` 명문화만 실효 불변
5. **커밋 분류 혼재** — `a4ddf9d` 가 ADR 본문·계획서·runbook·Layer 1 을 한 커밋에 담았다. 스킬 규칙에서 벗어나며
   이력 재작성 대신 사실대로 남겼다

**다음**: ④-c-2b-0 (P4 브로커 retention 코드) → ④-c-2b (replay 구현). 또는 ③ PR3d-b-2(GKE 세션) + PR4 관측성 S9.

---

## ④-c-2b-0 — 브로커 retention 계약 실설정 — 2026-09-01 — [#99](https://github.com/Kimgyuilli/PeakCart/pull/99)

> ADR-0020 §D4 가 확정한 값을 코드로 옮긴다. **④-c-2b(replay 구현)의 마지막 착수 조건**이었다.

### "설정돼 있지 않다" 는 절반만 참이었다

원 계획(`task-impl4-c2b-dlq-replay.md` §2 D4)은 브로커 retention 이 어디에도 설정돼 있지 않다고 적었다.
**선언 기준으로는 참, 실효 기준으로는 거짓**이다 — `log.retention.hours=168`(7일)이 이미 실효값이었다.
그래서 이 작업은 *동작을 바꾸는 게 아니라 계약을 명문화하는 것*이고, 그 구분을 흐리면 "설정했다" 가
아무 것도 안 바뀐 채 성공처럼 보인다. **config 마다 동작 변경 여부를 나눠 기록**했다 —
`retention.ms`·`cleanup.policy`·`message.timestamp.type` 은 실효 불변, `retention.bytes`·`segment.*`·
`before.max.ms` 는 동작 변경이다. 전자는 V-P4-1 기준선 테스트가 **런타임 관측으로 승격**시켰다.

### segment.bytes 를 빠뜨렸으면 계산이 무의미했다

`retention.bytes` 만 선언하는 것으로 파티션 점유를 정했다고 생각했는데, Kafka 는 **닫힌 세그먼트만**
지우고 active segment 는 그 위에 얹힌다. 이미지 기본 `log.segment.bytes` 가 **1 GiB** 라
(`/opt/kafka/config/kraft/server.properties:128` 직접 확인) 선언하지 않으면 파티션 하나가 PVC 전체를
넘길 수 있었다. 실질 점유식을 `retention.bytes + segment.bytes` 로 바꾸고 `segment.bytes`·`segment.ms` 를
계약에 넣었다.

### 등호가 여유를 다 먹고 있었다

기존 `retention >= floor.max()` 는 등호를 허용해서 `retention 7d` 와 `dlq-replay-window 7d` 가 둘 다
통과했다. 그 상태에서는 시계 오차와 정리 잡 지연만큼 창 끝이 먼저 잘려 **광고한 replay 창이 실제로
보장되지 않는다**. `retention >= dlqReplayWindow + clockSkew + cleanupSafety` 로 바꾸고 4서비스를 9d 로
올렸다. 리뷰가 잡아준 것 — **budget 이 0이면 required 가 dlqReplayWindow 로 내려가** 제거하려던
7d == 7d 가 그대로 다시 부팅된다. 양수 검증을 추가했다.

### 리뷰가 잡은 내 자기대조

`KafkaTopicConfigsTest` 가 **SUT 의 상수를 그대로 기대값으로 읽고** 있었다. 업무 8/4 → 10/2 MiB 로
바꿔도 합이 같아 "절반 관계" 도 "총 420 MiB" 도 유지되어 green 이었다. ADR 본문 값을 테스트에
리터럴로 다시 적어 독립 정본을 만들었고 변이로 red 를 확인했다. **계획 리뷰 때 P4-4 를 "관측해서
사실대로 적으면 통과" 로 설계했던 것과 같은 결의 실수**다 — 이 세션에서 두 번 나왔다.

### 검증

**764 테스트 0 실패**(common 73 · order 297 · product 186 · payment 168 · notification 40, 35분 33초).
변이 4종 전부 의도대로 red → 복원 후 green: 토픽 하나의 `.configs(...)` 제거 · 안전여유 등호 복귀 ·
YAML `modify-topic-configs` 제거 · 8/4→10/2 MiB.

### 미충족

1. **diff 리뷰 2R 미실행** — P1 을 실제로 고쳤으므로 재리뷰 대상인데 돌리지 않았다. 이 세션의 다른
   리뷰들이 매번 "직전 라운드 수정이 만든 새 결함" 을 잡아왔으므로 **한 라운드가 비어 있다**.
   변이 4종 red 확인이 부분적 대체다
2. **PVC 1 GiB 안전성 여전히 미증명** — 도메인 토픽만 정상상태 약 420 MiB 로 억제된다.
   `__consumer_offsets`(기본 50파티션)·KRaft metadata 는 bound 밖이고, 420 MiB 자체도 hard bound 가
   아니다(retention 검사 주기·`file.delete.delay.ms`·index/timeindex·대형 batch 미포함)
3. **운영 클러스터 미적용** — 로컬 Testcontainers 로만 검증했다. `ALTER_CONFIGS` 권한·기존 dynamic
   override·배포 순서는 미증명. V-P4-8(권한 실패 주입)과 실적용은 **③ PR3d-b-2 GKE 세션**에 합류
4. **`retention.bytes` 유한값은 동작 변경** — 트래픽이 산정치를 넘으면 7일 이내에도 좌표가 사라질 수
   있다. 그래서 replay 는 발행 직전 좌표 검증(ADR §D5-1)을 필수로 거친다
5. **`offsets.topic.num.partitions` 축소·PVC 증설 미수행** — 기존 클러스터에 소급되지 않아 재생성 필요.
   별도 인프라 결정

**다음**: **④-c-2b (replay 구현)** — 착수 조건이 전부 닫혔다. 또는 ③ PR3d-b-2(GKE 세션) + PR4 관측성 S9.

---

## ④-c-2b-1 — DLQ 원장 replay 축 + incident 집계 정정 — 2026-09-02 — [#100](https://github.com/Kimgyuilli/PeakCart/pull/100)

> ADR-0020 의 4분할 구현 중 첫 번째. **축을 열고 집계를 고칠 뿐 replay 를 실행하지 않는다.**

### 진입점을 상관보다 나중에 열기로 순서를 뒤집었다

초안은 2b-3 에서 `action=replay` 를 열고 2b-4 에서 재실패 상관을 붙이려 했다. 계획 리뷰가 반증했다 —
그 사이 구간에서 재발행분이 다시 실패하면 **독립 incident 로 갈라져** ADR-0020 §D5-4·§D6-3 을
정면으로 위반한다. "endpoint 를 플래그로 잠갔다가 나중에 연다" 는 대안은 **계약을 지키는 코드 대신
운영 규율에 기대는 것**이라 채택하지 않았다. 상관 경로를 먼저 세우면 replay 가 열리는 순간
이미 계약이 성립한다.

### 반대 방향의 false-green 이 같은 표면에 있었다

집계를 행 단위로 두면 재실패마다 backlog 가 부풀어 "재실패 N회에도 미결 1건" 이 거짓이 된다.
그런데 조건을 `root_record_id = id` 로만 걸면 **④-c-2a 가 적재한 기존 행이 전부 탈락해 backlog 가 0**
으로 보인다 — 과다집계를 고치다 정확히 반대편으로 넘어간다. `IS NULL` 분기를 함께 두는 것이
expand 단계의 계약이고, 배포 전후 미결 건수가 같음을 회귀로 고정했다.

### diff 리뷰가 잡은 실제 버그 — JPA 1차 캐시가 잠금 재검사를 무력화한다

두 건의 뿌리가 같았다:
- 후보·요청 행을 **엔티티로** 먼저 읽으면 영속성 컨텍스트에 적재되어, 뒤의 `SELECT ... FOR UPDATE` 가
  **DB 잠금은 얻되 이미 관리 중인 인스턴스를 refresh 하지 않는다**. 잠금을 기다리는 동안 다른
  트랜잭션이 커밋한 종결을 못 보고 **캐시의 과거 상태로 전이**한다 → id projection 으로 전환
- 자식을 잠그지 않으면 REPEATABLE READ **스냅샷**을 본다. root 는 current read 라 최신인데 자식만
  과거를 봐서, 앞선 트랜잭션이 root+자식을 종결한 뒤 **root 에선 no-op 하면서 자식만 덮어쓴다**
  → `findChildrenForUpdate`(활성 자식만 배타 잠금)

둘 다 **2라운드 리뷰가 1라운드 수정에서 찾아낸 것**이다(첫 번째는 1R, 두 번째는 1R 수정이 만든 새 결함).

### 변이 검증이 내 테스트의 false-green 을 잡았다

변이 8종 중 **M3(purge 기준을 `COALESCE` 로 회귀)이 처음에 green** 이었다. 잠금 후 인메모리 재검사가
어차피 걸러내서 쿼리를 되돌려도 통과했다 — 테스트가 두 방어선 중 하나만 보고 있었다. 쿼리 계약을
직접 단언해 red 로 만들었다. 계획 §1 N17 이 겨냥한 자기대조 유형이 실제로 나왔고,
**변이 검증이 없었으면 그대로 통과했다.**

부수로 두 가지를 배웠다 — ShedLock 락 행은 **지우면 오히려 영구히 잠긴다**(`StorageBasedLockProvider`
가 JVM registry 로 락 이름을 기억해 이후 UPDATE 만 시도하므로 행이 없으면 0행 갱신 = "It's locked").
만료시켜야 한다. 그리고 `information_schema.INNODB_TRX` 는 `PROCESS` 권한이 필요해 컨테이너 앱
계정으로 못 읽는다 — 경합 테스트의 잠금 대기 관측은 root 커넥션을 따로 연다.

### 검증

**918 테스트 0 실패**(common 73 · order 310 · product 186 · payment 168 · notification 40 · user 61 ·
gateway 80, 32분 28초). parity lint 본체 + **self-test 10종** 통과. 변이 8종 전부 red → 복원 green.

리뷰: 계획 3라운드 40건 + diff 3라운드 12건, **전량 반영 · 기각 0**. diff 3R 에서 P0/P1 = 0 으로 수렴.

### 미충족

1. **V-21c(purge 잠금 후 재검사의 2-트랜잭션 경합)를 직접 관측하지 못했다** — 재개방 경로가
   2b-3 P15 에 생기므로 검증도 그 PR 소관이다. 이 PR 은 잠금·재검사 코드만 넣었다
2. **신규 테스트는 order-service 에만** 있다. java 9파일이 4서비스 byte 동일이고 parity lint 가
   그것을 강제하므로 4벌 복제는 같은 코드를 네 번 도는 비용만 든다
3. **`publication_status` 를 쓰는 주체가 없다**(reconciler=2b-2, claim=2b-4). 자식 행 생성 경로도
   없다(2b-3) — 테스트가 fixture 로 그 DB 상태를 구성한다
4. **`NOT NULL` contract 는 범위 밖**(계획 §10 R1). backfill(2b-4) 이후 별도 마이그레이션이며,
   그때까지 `record_kind` 명시 계약은 코드 경로로만 강제되고 DB 는 막지 않는다
5. **운영 클러스터 미적용** — 로컬 Testcontainers 로만 검증했다

**다음**: ④-c-2b-2 (발행 표면 — outbox replay kind · notification outbox 신설 · reconciler, 계획 P8~P13)

## ④-c-2b-2 — 발행 표면 (PR [#102](https://github.com/Kimgyuilli/PeakCart/pull/102))

replay 레코드를 **발행할 수 있는** 표면을 만든다. 진입점은 없다(④-c-2b-4) — 이 PR 만으로는 replay 행이
생기지 않는다. 순서를 이렇게 잡은 이유는 진입점을 재실패 상관(2b-3)보다 먼저 열면 그 구간의 재발행 실패가
독립 incident 로 갈라져 ADR-0020 §D5-4·§D6-3 을 위반하기 때문이다.

### 결정

**`record_kind` 의 nullable 과 DEFAULT 부재는 이유가 다르다.** nullable 은 롤링 배포 중 구버전 writer 의
INSERT 를 살리기 위함이고, DEFAULT 부재는 `DEFAULT 'DOMAIN'` 을 두면 신버전 writer 가 판별자를 빠뜨려도
DB 가 조용히 도메인으로 분류하기 때문이다. **읽기는 `null` 을 `DOMAIN` 으로 관대하게 해석하고 쓰기는 항상
명시**한다 — 비대칭이 의도된 것이다.

**`event_type` 에 sentinel `__replay__` 를 넣는다.** 구버전 poller 는 `event_type` 을 그대로 목적지 토픽으로
쓰므로, 실제 업무 토픽 이름이 거기 있으면 롤백 시 원장 id 를 key 로 fence 없이 발행된다.

**reconciler 는 outbox 행 부재를 실패로 추론하지 않는다.** 부재는 실패의 증거가 아니다 — 이미 발행된 행이
cleanup·수동 삭제로 사라져도 똑같이 관측된다. 자동 강등하면 발행된 사건을 "실패" 로 감사 기록하고 재요청까지
열어 같은 메시지를 두 번 낸다. 대신 cleanup 이 `REQUESTED` root 연결분을 제외하고, 그럼에도 부재가 관측되면
그것 자체를 계약 위반 신호로 둔다(계획 §10 R7).

### 착수 전 코드 검증이 뒤집은 전제

`docs/plans/task-impl4-c2b-dlq-replay.md` §5 의 C-1~C-14 가 정본. 핵심 2건:

- **C-5** — "parity lint 가 대조한다" 는 **거짓**이었다. 기존 lint 는 `dead_letter_records` 전용이고
  `outbox_events` 를 보는 검사가 없었다 → P9-b 신설
- **C-6** — 계획 리뷰 A#2 가 **내 정정을 다시 반증**했다. "단일 테이블 DELETE 에 alias 불가" 는 거짓이고
  MySQL 8.0.16+ 는 허용한다. `mysql:8.0.46` 컨테이너로 직접 실측해 4행 매트릭스를 남겼다 — 초안 SQL 이
  깨진다는 **결론은 유지되나 이유가 달랐다**(alias 를 선언한 적이 없을 뿐)

### 구현 중 자체 발견

**`DeadLetterPublicationReconciler` 를 4벌 복제해 놓고 parity lint 목록에 더하지 않았다.** 목록에 없으면
4벌이 갈라져도 아무 것도 실패하지 않는다 — **④-c-2b-1 의 glob 미확장과 같은 구멍의 재발**이다. 개별
self-test 로 때우면 "내가 기억한 그 파일" 만 지켜지므로 **`DLQ-PARITY-014`(지금 4벌 byte 동일인데 목록에
없는 파일)로 목록 자체를 검사**하게 했고, 그 검사가 **기존 미등록 복제본 2개**(`DeadLetterContainerGuard`·
`DeadLetterKafkaConfig`, ④-c-2a 산출물)까지 찾아내 편입했다.

**내 fixture 가 현실을 왜곡하고 있었다 (2건)** — `seed_fixture` 가 per-service 파일까지 order 사본으로 채워
fixture 안에서만 byte 동일해지던 것, `$TMP` 를 지우지 않아 앞 케이스가 뒤 케이스를 red 로 만들던 것.
**fixture 가 현실을 왜곡하면 self-test 가 검사하는 대상이 현실이 아니게 된다.**

**at-least-once 단언이 과했다** — "정확히 2개" 인데 실측 3개(배경 poller 도 같은 행을 집어간다). ADR-0020
§D1 은 중복 수에 상한을 두지 않으므로 계약이 말하지 않는 것을 테스트가 주장하던 flaky 였다.

### 검증

**902 tests 0 실패** (common 73 · order 317 · product 186 · payment 168 · notification 45 ·
common-auth 52 · user 61). 로컬 전체 스위트가 반복 중단돼 **모듈별로 나눠 실행**했다.
**변이 14종 전부 red** · lint 7종 green(parity self-test 10종 → **18종**).

**최종 CI 전면 green** — test 6종 · lint · guards · gate · images 6종 · **e2e**. e2e 가
`EXPECTED_MIGRATIONS`(order 1~9 · product 1~7 · payment 1~7 · notification 1~5)를 실제 스택에서
대조하므로 **4 DB 마이그레이션과 notification outbox 신설의 실적용**이 확인됐다.

### CI 후속 — 테스트 하네스 결함 4건 (운영 코드 결함 0)

최초 push 의 CI 에서 `test (order-service)` 가 실패했다. **원인은 전부 이 PR 이 새로 만든 테스트의
결함**이었고, 확정까지 가설 4개가 실측으로 반증됐다(재스터빙 경합=참이나 부분적 · 배경 잡 단독 원인
아님 · 30s 타임아웃으로도 실패 · 컨테이너 불일치 반증).

**결정적 증거**: 실패 시 `brokerEndOffsets` 가 **전부 0** — 소비를 못 한 것이 아니라 **1사이클의 send 가
실제로 실패**했다. 컨테이너가 여럿 뜬 느린 실행에서 토픽 생성이 끝나기 전에 첫 send 가 나가 타임아웃한다.

수정 4건: Mockito 재스터빙 → `AtomicBoolean` 단일 answer(**CI 실패의 직접 원인**) · `awaitTopicReady` ·
측정을 소비 → **broker end offset** · **1사이클 ack 명시적 단언**(이 단언이 없어 첫 발행이 조용히
사라져도 통과했고, 실제로 이 단언이 원인을 특정했다).

**진단 중 내가 틀렸던 추론 2건**: ① `hasMessage("DB down")` 은 send 성공의 증거가 아니다 —
`handlePublishFailure` 끝의 save 가 같은 예외를 덮어쓴다(이 착각이 진단을 한 라운드 지연시켰다)
② `fixedDelay` 는 **간격**만 정하고 첫 실행을 막지 못한다(`[scheduling-1]` ERROR 로그가 증거).

**계획 C-9 판단을 뒤집었다** — `@Scheduled` 리터럴 유지 → `fixedDelayString` 설정화. 배경 잡이 도는
상태에서는 발행 횟수를 세는 테스트가 구조적으로 성립하지 않는다. 운영 기본값 5s 불변.

**검증**: 두 클래스 동시 실행 **6회 연속 통과**(6m20s·8m15s 짜리 느린 실행 포함 — 예전에 실패하던 구간이
정확히 그것이다). 1회 통과로 넘어간 것이 이 결함을 CI 까지 보낸 직접적 원인이다.

### 미충족

1. **Codex diff 리뷰 미실행** — `usage limit`. **P0/P1 = 0 이 아니라 미측정**이다.
   이번 PR 이 새로 만든 계약 표면(reconciler · `DLQ-PARITY-014` · `OutboxEventStatus` 2집합)이 적지 않고,
   **CI green 이 리뷰를 대신하지 않는다**
2. 신규 테스트는 order·notification 에만 (product/payment 는 byte 동일 복제 + parity lint)
3. **replay 행은 fixture 로만 생성** — 진입점·fence 6종·좌표 검증은 ④-c-2b-4
4. `record_kind` **`NOT NULL` contract 미수행**(계획 §10 R1) — backfill 이후 별도 마이그레이션
5. `docs/05-data-design.md:177` 정정은 P24 소관 · **운영 클러스터 미적용**
6. gateway 는 로컬 미재실행(무관 모듈, CI 에서는 pass)

> **해소된 항목**: ~~로컬 전체 스위트 완주 없음~~·~~E2E 로컬 미실행~~ → CI 전 모듈 pass + e2e pass 로 해소.

**다음**: ④-c-2b-3 (재실패 상관 + 재개방, 계획 P14~P17)

---

## ④-c-2b-3a — replay 상관 표면 (PR [#103](https://github.com/Kimgyuilli/PeakCart/pull/103))

**2026-09-06** · 계획서 `docs/plans/task-impl4-c2b-dlq-replay.md` §PR ④-c-2b-3a (P14)

### ADR 이 스스로와 충돌하고 있었다

ADR-0020 §D5-4 는 재실패 상관의 대조 항목에 `record_kind=REPLAY` 를 넣었다. 그런데 `record_kind` 는
`outbox_events` 전속 컬럼이고, 발행에 성공한 행은 `PUBLISHED` 가 되어 retention 후 cleanup 이 지운다.
미결 root 원장은 무기한 남고 DLQ 적재는 지연될 수 있으니, **재실패가 도착했을 때 그 값을 읽을 행이 이미 없다.**

이건 새로 발견한 위험이 아니다. **같은 절이 정확히 그 이유로 "대조의 정본은 원장이다 (outbox 가 아니다)" 를
정했다** — "outbox 를 정본으로 삼으면 수명 경쟁에서 진다". 결론을 내려놓고 대조 목록에는 outbox 전속 컬럼을
남긴 것이다. **요구된 대조 축이, 요구된 그 이유로 쓸 수 없었다.**

### 축을 빼는 것만으로는 안 됐다

`record_kind` 를 제거하면 대조는 attempt·owner·group·topic·root-id + 좌표 fingerprint 로 좁아진다.
여기에 **payload 를 묶는 값이 하나도 없다.** 같은 `eventId`·key·timestamp 를 싣고 본문만 바꾼 메시지가
그 토픽에서 실패하면 전 축을 통과해 **남의 사건에 자식으로 붙고 종결된 root 를 재개방**한다 —
§D5-4 가 "조작된 메시지가 임의 root 연결·재개방을 유발" 이라고 경계한 바로 그 경로다.

원장의 `payload` 컬럼으로는 대조할 수 없다. **8000자로 잘려 저장**되므로 절단분 비교는 상한 밖 변조를
통과시키고, §D8-3 의 "byte-for-byte 동일" 을 주장할 수 없다. 그래서 절단 전 전문의 SHA-256 을
`last_replay_payload_digest` 로 따로 영속한다(4 DB additive).

**Update Log 가 아니라 새 ADR(ADR-0021) 로 했다.** `adr/README.md:14` 가 "트레이드오프 변경·대안 추가는
본문 정정이 아니라 새 ADR 작성 사유 (Update Log 로 우회 금지)" 로 못박는다. 2b-2 P9-c 선례는 표의 **사실**
정정이라 적용되지 않는데, 계획 1R 이 그것을 잘못 유추했고 2R 이 반증했다.

### 쓰기는 엄격하게, 읽기는 관대하게

발행 측은 키 집합 **정확 일치**를 요구한다. 부분집합 허용으로는 부족한데, `replayHeaders()` 가 JSON 이 비면
빈 Map 을 돌려주고 `addHeaderIfPresent` 가 blank 값을 조용히 생략해서 **헤더 0~3개짜리 replay 가 그대로
발행**되기 때문이다 — 발행 측에서 상관 계약을 깨는 경로다.

판독 측은 반대다. `pc-replay-root-id` 가 숫자가 아니어도 예외를 던지지 않고 null 로 떨어뜨린다.
**조작 가능한 헤더 하나가 DLQ 적재 자체를 막으면 실패 사실이 유실**된다. 판독 실패는 "상관하지 않음" 이지
"적재하지 않음" 이 아니다. 이 비대칭이 의도다.

### 같은 구멍의 세 번째

parity lint 의 replay 축 검사는 `V*__dead_letter_replay_axis.sql` **한 파일**만 보고 컬럼 목록도
하드코딩이었다. digest 를 별도 V10 파일에 넣으면 **본실행도 `--self-test` 도 전부 green** 이다.
④-c-2b-1 의 glob 미확장, ④-c-2b-2 의 java 목록 누락에 이어 **세 번째로 같은 형태**다.
파일 단위를 버리고 `db/migration` 전체를 번호 순으로 합성한 **최종 스키마**를 대조하도록 바꿨다.
기존 검사는 남겼다 — 그쪽은 "4벌이 서로의 사본" 이라는 더 강한 성질을 지킨다. self-test 18 → **23종**.

신규 4종의 요점은 **replay 축 glob 밖의 파일**을 건드린다는 것이다. 그중 3종은 4서비스를 **함께**
변이시켜, 서비스 간 스키마 차이 검사(016)가 아니라 해당 검사 자신이 red 를 만든 것을 증명한다 —
016 에 업혀 가는 검사는 검사가 아니다.

### 착수 전 검증이 뒤집은 것, 그리고 리뷰가 그 검증을 뒤집은 것

착수 전 코드 검증에서 초안 전제 3건이 반전됐다: `pc-replay-*` 상수는 **코드에 0건**(계획서 2줄이 전부) ·
`DlqHeaders` 에 allowlist **자료구조 없음**(표준 상수를 개별 호출로 읽는 코드일 뿐) · V8 이 만든 앵커 컬럼
**미매핑**. 반대로 대전제는 확인됐다 — 커스텀 헤더가 재실패 시 DLT 까지 살아남는지를
`spring-kafka-3.3.14` **바이트코드로** 봤고, `accept()` 가 `new RecordHeaders(record.headers().toArray())` 로
원본 헤더 전량을 복사한 뒤 `DLT_*` 를 얹는다.

**그런데 계획 리뷰가 그 검증 표 자체를 4건 반증했다** — C-18 "마이그레이션 0"(digest 필요) ·
C-24 "호출부 2곳"(**실측 44곳**, notification 엔 quarantine consumer 자체가 없다) ·
C-27 "누락은 자동으로 막힌다"(`DLQ-PARITY-014` 는 4벌이 **이미 동일할 때만** 신고한다) ·
"기존 best-effort 계약"(`record()` 는 `@Transactional` **안에서** Slack 을 부른다 — javadoc 이 이미 거짓이었다).

2R 9건은 **전부 1R 수정이 만든 결함**이었다. 그중 가장 무거운 것은 digest 컬럼을 신설해 놓고
**writer 를 배정하지 않은 것**이다 — 실제 replay 에서 root digest 가 영구 NULL 이 되어 대조 축 9가
늘 통과하는데, 2b-3 테스트가 fixture 로 digest 를 심으므로 **green 인 채로 검사만 사라진다**.

### 변이가 잡은 것은 운영 코드가 아니라 내 테스트였다

`requireComplete` 호출을 제거하는 변이에서 거부 4종 외에 **무관한 테스트까지 red** 였다. 원인은 같은 토픽을
쓰는 다른 테스트의 `allSatisfy` 가 함께 깨진 것 — 거부돼야 할 레코드가 발행되자 그것까지 검사 대상이 됐다.
record key 로 걸러 격리했고, 재변이 시 **정확히 4종만** red 다.

### 검증

**1006 tests 0 실패** (common 92 · common-auth 52 · user 61 · gateway 80 · order 322 · product 186 ·
payment 168 · notification 45) · **lint 15종** green · parity self-test **23종** · saga-contract self-test green.

### 미충족

1. **계획 3R · diff 리뷰 둘 다 미실행** (Codex `usage limit`, 재개 2026-09-07 14:45).
   **P0/P1 = 0 이 아니라 미측정**이고, #102 에 이어 **2개 PR 연속**이다.
   **PR 을 3a/3b 로 자른 것이 이 문제를 해소하지 못했다** — 분할의 전제는 "각 조각이 한도 안에서
   리뷰된다" 였는데, 한도는 조각 크기가 아니라 **계획 1R/2R 이 이미 소진한 잔량**에 걸렸다.
   → 2b-3b 착수 시 **#103 diff 를 포함해 재리뷰**한다(#92→#93 선례).
2. **digest 의 writer 가 없다** — 의도된 범위(2b-4 P21). 3a 만 배포된 상태에서는 어떤 root 도 앵커를
   갖지 않고 상관이 일어나지 않는다(replay 자체가 없으므로 정상).
3. **상관·재개방 로직 없음** — 판독한 4필드를 아직 아무도 쓰지 않는다(2b-3b P15).
4. 운영 클러스터 미적용 · `05-data-design.md` 원장 스키마 서술 정정은 P24 · 로컬 e2e 미실행
   (CI 의 `saga_e2e.py` 가 `EXPECTED_MIGRATIONS` `10/8/8/6` 으로 4 DB 실적용을 확인한다).

**다음**: ④-c-2b-3b (원자 대조 + 재개방, 계획 P15~P17)

---

## ④-c-2b-3b — 원자 상관 + 재개방 (PR [#104](https://github.com/Kimgyuilli/PeakCart/pull/104))

**2026-09-10** · 계획서 `docs/plans/task-impl4-c2b-dlq-replay.md` §PR ④-c-2b-3b (P15·P16·P17·P17-b)

3a 가 세운 상관 **표면** 위에 **판정과 전이**를 얹었다. 진입점(claim·digest writer)은 2b-4 소관이라
이 PR 은 **소비 측만** 바꾼다 — 앵커를 쓰는 주체가 없으면 상관 경로가 아예 타지 않아 단독 배포가 안전하다.

### 실행 순서를 초안에서 바꾼 것이 이 PR 의 중심이다

초안은 "root 잠금 → 대조 → 자식 INSERT + 재개방" 이었다. 그러면 **재개방이 저장되지 않는다**:
`insertIfAbsent` 가 `@Modifying(clearAutomatically)` 라 INSERT 직후 컨텍스트를 비우고 앞서 잠근 root 가
**detach** 된다. 그 뒤의 `reopen()` 은 dirty checking 대상이 아니라 UPDATE 가 나가지 않는다 —
**자식 연결만 보는 테스트라면 green 인 채로 재개방이 사라진다.**

채택한 순서: 로케이터(id projection) → root `FOR UPDATE` → **9축 대조(유일)** → 자식 INSERT →
**current read 재조회** → 연결·재개방.

- **단계 5 는 `findByIdForUpdate` 여야 한다.** 일반 `findById` 면 REPEATABLE READ 에서 단계 1 이 연
  스냅샷을 다시 읽어, 그 사이 닫힌 root 를 `OPEN` 으로 보고 **재개방을 건너뛴다**.
- **attempt 대조는 단계 3 한 곳뿐이다.** 계획 3R 이 이를 "조회 predicate / 잠금 후 재확인" 둘로 나눴는데
  실행 순서상 관측점이 **셋**이 되어 서로 은폐했다(4R #3). 로케이터는 **탐색**이지 대조가 아니다.
- **group 3자 대조.** 헤더를 빼고 둘만 비교하면 `pc-replay-target-group` 판독이 **죽은 데이터**가 되어
  4헤더 계약 중 하나가 아무것도 지키지 않는다.
- **digest 는 절단 전 전문**의 SHA-256. 원장 `payload` 는 `maxLength` 로 잘려 저장돼 그 값으로 대조하면
  **상한 밖 변조를 통과**시킨다. null-safe 는 ADR-0021 §D2 결정 그대로 유지했다.

### 그 밖의 결정

- **`LedgerOwner` 빈 주입** — 이 파일은 4서비스 byte 동일 복제라 하드코딩 불가. 인자로 받으면
  **호출자가 소유자를 고를 수 있어** 신뢰 경계가 약해진다. `record(DlqOrigin)` 시그니처 불변(호출부 44곳 그대로).
  parity lint 밖이라 **오배선의 증상이 실패가 아니라 침묵**이다 → `LedgerOwnerWiringTest` ×4 로 배선을 따로 고정.
- **알림을 afterCommit + 결과별 4행으로.** 기존 코드는 `@Transactional` 안에서 Slack 을 불러
  **commit 실패 전에 알림이 먼저 나갈 수 있었고**, 상관된 자식에도 "신규 미결 1건" 을 보내
  backlog 가 늘지 않았는데 늘었다고 보고했다.
- **Counter 2종은 `CommitAwareMetrics` 경유.** 트랜잭션 안에서 올리면 rollback 된 상관까지 세도 green 이다.
  `reason` 은 enum bounded — 입력 문자열이 태그가 되면 cardinality 가 터진다(ADR-0015).
- **ADR-0021 Update Log(P17-b)** — §D2 가 관통 검증을 `V-30` 으로 참조했으나 그 ID 는 ④-c-2b-2 에서
  **이미 머지된** `OutboxReplayPublicationIntegrationTest`(`:44·122`)가 쓰고 있었다. 관통=`V-35`,
  호환성=`V-30` 유지, `V-34` 철회. 결정이 아니라 **참조 번호 정정**이라 새 ADR 대상이 아니다.

### 리뷰에서 배운 것

**계획 리뷰 4R 이 P1 8건으로 발산했다.** 원인은 리뷰 부족이 아니라 **3R 반영이 3b 경계를 넘어
2b-4 의 P24 를 확장한 것**이었다 — 열자마자 3b 가 답할 수 없는 표면 넷이 딸려 나왔고
(`FixedSequenceBackOff` 에 `maxAttempts` 가 없어 계획의 식 자체가 불가였다), 4R 의 P1 4건이 전부 거기 있었다.
**drain 판정식·preflight 진입점을 통째로 2b-4 로 되돌리자** 5R 에서 범위 이탈 지적이 1건으로 줄었다.

**5R 6건 중 5건이 4R 반영 자체의 결함**이었다. 특히 축 9 를 "필수 대조" 로 바꾼 것이
**Accepted `ADR-0021 §D2` 침범**이었다 — 도달 불가라는 *사실*이 결정의 *의미*를 바꿀 권한은 아니다.

**diff 리뷰 1R 이 실제 테스트 실패 2건을 잡았다.** 로컬 `gradle test` 가 XML 쓰기 실패로 `BUILD FAILED`
만 남기고 **테스트 실패를 가리고 있었다** — 그 신호만 보고 "인프라 실패, 테스트는 통과" 로 보고했었다.
나머지 P1 3건도 *"테스트가 계약이 아니라 통과를 기술"* 유형이었다(`V-15c` vacuous · `V-21c` 부재 ·
rollback/V-16 누락).

**seam 구현에서 두 번 막혔다.** ① Spring Data 리포지토리는 인터페이스 프록시라 `callRealMethod()` 가
성립하지 않는다 → 알려진 값을 반환하는 seam 으로 전환. ② 로케이터를 stub 하자 **평문 읽기가 사라져
consistent-read 스냅샷이 열리지 않았고** `V-15c` 가 다시 vacuous 해졌다(M12 가 green) → seam 이
`repository.count()` 로 그 읽기를 재현하게 고쳐 M12 가 red 로 전환됐다. **변이 검사를 안 돌렸으면
"고쳤다" 고 잘못 보고했을 지점이다.**

### 검증

`DlqReplayCorrelationIntegrationTest` 28건 · `LedgerOwnerWiringTest` ×4 · payment `DlqIntegrationTest`
전부 green. **전 모듈 스위트 1043 tests 0 실패**(8모듈). lint 15종 green(parity 본실행 + self-test 23종). **변이 16종 red** — M3/M4 가 각각
`V-19d`/`V-19d2` 만 red 라 3자 대조 매트릭스 분리가 실효임이 확인된다. M13(assignSelfRoot 제거)은
13건 red — 집계가 `root_record_id IS NULL` 도 root 로 세므로 그 단언이 없으면 전부 green 이었다.

**구현 중 자체 발견**: `LedgerOwnerConfig` 는 per-service 인데 parity self-test fixture 가 order 사본을
4벌 복사해 **fixture 안에서만 byte 동일**해져 `DLQ-PARITY-014` 가 오탐했다. fixture 의 per-service 목록에
등재해 해소. `git stash` 로 baseline 을 볼 때 **untracked 신규 파일이 남아 판정이 오염**됐던 것도
겪었다 — `-u` 를 붙이고서야 선재 결함이 아니라 내 변경의 fallout 임이 드러났다.

### 미충족

1. **#103 diff 재리뷰 스킵**(사용자 지시) — #102·#103 의 P0/P1 은 **0 이 아니라 미측정**으로 남는다.
2. **계획 리뷰 수렴 미달** — 6R 중단(사용자 지시)으로 P1=0 을 확인하지 못했다. 5R 반영이 만든 새 표면
   (`V-19o` 복원 · `V-15c` · 단계 5 current read · `P17-b`)은 계획 리뷰를 거치지 않고 diff 리뷰로만 검증됐다.
3. ~~**로컬 전 모듈 스위트 완주 미관측**~~ → **해소 (PR 생성 후, 2026-09-10)**.
   `./gradlew test --continue` **BUILD SUCCESSFUL (1h 25m)** · **1043 tests · 0 failures · 0 errors**.
   동시 실행 두 번에서 났던 `NotificationOutboxIntegrationTest`(`ContainerLaunchException`)와
   `gateway` `InternalTokenIssuer` RSA p95 실패는 **자원 경합이 맞았다**(완주 실행에서 둘 다 통과).
   PR 은 이것이 미측정인 상태에서 열었고 본문에 그렇게 적었다 — 지금은 관측됐고 본문도 정정했다.
4. **`V-19o` 는 fixture 전용** — ADR-0021 §D2(tombstone null-safe)와 ADR-0020 §D5-2(`event_id IS NULL`
   금지축) + `payload TEXT NOT NULL` 이 어긋난다. 해소는 새 ADR 이 필요하고 범위 밖.
5. **진입점 관통 미검증** — 앵커를 fixture 로만 심는다. 전 구간은 `V-35`(2b-4).
6. **drain 판정식·preflight 진입점 미정**(2b-4 이관) · **`replay_deadline`/`replay_policy` 엔티티 매핑 없음**
   (컬럼은 V8, writer/reader 는 2b-4) · **`NOT NULL` contract·backfill**(2b-4 P23) · **운영 클러스터 미적용**

**다음**: ④-c-2b-4 (진입점 + 좌표 reader + fence + backfill, 계획 P18~P24). 착수 조건이던 drain 4조건의
판정식·preflight 진입점·ADR 처분은 **2026-09-11 에 코드 검증 후 확정**했다 — 계획서 §10.1.
요지: ⓐ 정본 = `outbox_events` 단독(`publication_status` 는 terminal 이 아니고 reconciler 5s 지연으로
어긋난다) · ⓓ = `sum(backoff)=36s`(4서비스 8곳 전부 `FixedSequenceBackOff(1_000,5_000,30_000)`, `maxAttempts`
부재라 곱하면 중복) + 기준점은 **원장 신규 컬럼 `last_replay_settled_at`**(reconciler 가 settle 시 기록).
**후보 2개가 계획 리뷰에서 연속 P0 로 반증됐다** — `replay_deadline` 은 ADR-0020 §D5-3 의 7d 멱등
안전창과 의미 충돌(1R), `outbox_events.created_at` 은 ① 강제 삭제 시 부재가 fail-open ② INSERT 시각이
발행 시각이 아님(2R). drain 조건도 **ⓐ' `publication_status='REQUESTED'=0` 이 복원**됐다 ·
ⓑⓒ 는 Kafka lag 실측 유지(시간 하나로 흡수하면 컨슈머 정지 시 vacuous) · preflight 는 래퍼 스크립트 +
**우회 가능이라는 한계 명시**(CD 부재 — `ci.yml` deploy job 0건) · **ADR-0022 신설**, ADR-0020 Update Log
기재 지시는 취소(`adr/README.md:13` 이 계약 변경의 Update Log 우회를 금지).
