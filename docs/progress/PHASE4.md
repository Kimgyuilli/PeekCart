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
