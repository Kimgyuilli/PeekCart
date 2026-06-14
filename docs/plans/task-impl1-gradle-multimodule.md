# task-impl1-gradle-multimodule — 구현 ① Gradle 멀티모듈 전환 (ADR-0011)

> 선행 ADR: **ADR-0011** (SSOT, Accepted). 본 계획은 ADR-0011 §D1~D4 를 실제 코드/빌드로 전환한다.
> 분할: **3-PR** (PR1 스켈레톤+common → PR2 서비스 5개 분리 → PR3 Dockerfile/CI/k8s).
> 관련: ADR-0009(관측성 모듈 선결정), ADR-0010(5개 서비스 경계), ADR-0001(서비스 내부 4-Layered 유지).

## 1. 목표

단일 모듈 `peekcart` 를 **`common` + `peekcart-common-observability` + `peekcart-common-auth`(전환기 JWT 검증, ADR-0014) + 5개 서비스 모듈(user/product/order/payment/notification)** 의 8모듈 Gradle 멀티모듈로 전환한다. 모듈 경계로 "서비스 간 직접 호출 금지"(ADR-0001)를 컴파일 차원에서 물리 강제하고, 서비스별 `bootJar`→이미지 N개 빌드/CI/k8s 체계를 구축한다.

성공 기준:
- 5개 서비스 모듈이 각각 `bootJar` 산출 + 독립 부팅(`/actuator/health` 200)
- `:common`·`:peekcart-common-observability`·`:peekcart-common-auth`(ADR-0014) 외 서비스↔서비스 `project()` 의존 시 **빌드 실패**(검출 task)
- 전체 테스트 그린 (Testcontainers 통합 테스트 각 서비스 모듈에서 실행)
- CI image matrix 로 5개 이미지 빌드 + docker-health-smoke 통과

## 2. 배경 / 제약

### 현 빌드 구조 (감사 — 실제 인용)
- `settings.gradle`: `rootProject.name = 'peekcart'` (단일 모듈, include 없음)
- `build.gradle`: 단일 모듈, `org.springframework.boot 3.5.12`, 단일 `bootJar`(L17→`app.jar`), `jar.enabled=false`(L21), jacoco exclude 규칙(L101~118)
- 진입점: `src/main/java/com/peekcart/PeekcartApplication.java` (단일 `@SpringBootApplication`)
- 도메인: `com.peekcart.{user,product,order,payment,notification}` 각 4-Layered(presentation/application/domain/infrastructure)
- 공용: `com.peekcart.global.{entity,response,exception,kafka,config,filter,lock,cache,auth,jwt,security,outbox,idempotency,port}`
- 리소스: `application.yml`+`application-local.yml`+`application-k8s.yml`, Flyway `V1~V4`(단일 세트), `logback-spring.xml`
- 테스트 support: `src/test/.../support/{AbstractIntegrationTest,IntegrationTestConfig,ServiceTest,WithMockLoginUser,WithMockLoginUserSecurityContextFactory}` + `support/fixture/*Fixture`
- CI: `.github/workflows/ci.yml` 단일 이미지 `ghcr.io/${owner}/peekcart`, PR `docker build`+smoke(`scripts/docker-health-smoke.sh`), main push `:latest`/`:sha`
- k8s: `k8s/base/services/peekcart/deployment.yml`(단일), `k8s/overlays/gke/kustomization.yml` `images.newTag: latest`(L-016a 부채)

### common 경계 (ADR-0011 §D2 — 본 계획의 분류 입력)
- **common**: `global.entity`(Base*Entity)·`response`·`exception`·`kafka`(trace/parser/MDC)·`config.{RedisConfig,RedissonConfig,WebMvcConfig,OpenApiConfig}`·`filter.MdcFilter`·`lock.DistributedLockManager`·`cache.CachedPage`·KafkaConfig 의 producer/consumer factory·이벤트 DTO(`outbox.dto.*`, 위치만 common·스키마는 A3 non-authoritative)
- **peekcart-common-observability**: `config.MetricsConfig`(S1)·exposure base yml(S3)·`SecurityConfig` actuator 허용부(S4) — ADR-0009 소유, **재결정 안 함**
- **서비스 전속**(PR2 분배): User=`SecurityConfig` 인증부+`auth`/`jwt`/`security`(Gateway 는 A4) · Order/Payment=`outbox`+`ShedLockConfig`+`KafkaConfig` 토픽/DLQ 빈(order.*/payment.*) · Product=`CacheConfig`(S7) · Order/Payment/Notification=`idempotency`(ProcessedEvent, 소비자별 복제) · Notification=`port.SlackPort`

### 제약 / 비대상
- **DB-per-service 는 구현 ②(ADR-0012) 비대상.** PR2 의 5개 서비스는 **공유 DB(단일 MySQL/스키마)** 를 바라보는 과도기 상태로 부팅한다. 서비스별 `db/migration/{service}/` 분리(ADR-0012 §30-32)는 **구현 ② 명시 제외** — ①은 공유 `V1~V4` 의 실행 위치만 배선한다.
- 관측성 모듈 경계/소유는 ADR-0009 결정을 **인용·반영만** 한다 (재결정 금지).
- 서비스 내부 패키지 구조(4-Layered)는 유지 — 이동만, 재설계 금지(surgical).

### ADR 관계
- ADR-0011 §D1(레이아웃)·§D2(common 경계)·§D3(의존 규칙)·§D4(빌드/테스트/이미지 계약) 1:1 도출.
- 편입 부채: **L-016a**(gke `newTag` digest 고정), **D-016**(GHCR→AR image promotion 자동화) — PR3.

## 3. 작업 항목

> 단일 연속 stable id(P1~). PR 경계는 소제목으로 구분.

### PR1 — 멀티모듈 스켈레톤 + common/observability 추출 (과도기: 단일 app 유지)

- [ ] **P1.** 루트 `build.gradle` 를 멀티모듈 빌드로 재구성: `subprojects`/`allprojects` 공통 설정(Spring Boot BOM, Java 17 toolchain, repositories, jacoco), 루트 `bootJar` 비활성. 공통 plugin/version 관리를 루트로 끌어올림.
- [ ] **P2.** `settings.gradle` 에 `include 'common'`, `include 'peekcart-common-observability'` 추가 (서비스 모듈은 PR2). 과도기: 기존 app 은 루트(또는 임시 단일 모듈)로 유지하며 두 신규 모듈을 의존.
- [ ] **P3.** `common` 모듈 생성(`java-library`, `bootJar` 없음) + ADR-0011 §D2 common 행 중 **서비스 의존 0 인 클래스만** 이동: `global.entity/response/exception/kafka`, `config.{RedisConfig,RedissonConfig}`, `filter.MdcFilter`, `lock.DistributedLockManager`, `cache.*`(CachedPage/HarnessSmokeTtl), 이벤트 DTO(`outbox.dto.*`). 패키지 경로 유지(`com.peekcart.global.*`).
  - **PR2 이연**(구현 중 발견 — 서비스 전속 코드 컴파일 의존): `WebMvcConfig`(`LoginUserArgumentResolver`=auth/User), `OpenApiConfig`(`LoginUser`=auth/User), `KafkaConfig` 팩토리(`kafkaErrorHandler`→`SlackPort`=Notification). ADR-0011 D3 "common→서비스 의존 금지" 불변식상 해당 서비스 전속 의존이 배치되는 PR2 에서 이동.
- [ ] **P4.** `peekcart-common-observability` 모듈 생성(plain jar) + ADR-0009 surface 이동: `config.MetricsConfig`(S1). ADR-0009 인용 주석 명시.
  - **PR2 이연**: exposure base yml(S3)·`SecurityConfig` actuator 허용부(S4) — S4 는 단일 `SecurityFilterChain` 빈이 jwt/auth(User) 전체에 의존, S3 yml 은 서비스별 yml 이 생기는 PR2 에서 분배.
- [ ] **P5.** `java-test-fixtures` 로 공용 test support 재배치(서비스 의존 0): `support/{AbstractIntegrationTest,ServiceTest,WithMockLoginUser,WithMockLoginUserSecurityContextFactory}` 를 `common` testFixtures 로 이동. app 은 `testImplementation(testFixtures(project(':common')))`.
  - **PR2 이연**: `IntegrationTestConfig`(`SlackPort` 의존)·`support/fixture/*`(도메인 모델 의존) 은 해당 서비스 모듈 test 로.
- [ ] **P6.** app 모듈 `build.gradle` 가 `:common`+`:peekcart-common-observability` 를 `implementation` 의존하도록 배선. 전체 빌드/테스트 그린 확인.

### PR2 — 5개 서비스 모듈 분리 (sub-PR 1~2개 서비스씩 vertical slice)

> **분할 확정**: 서비스를 1~2개씩 monolith(root app)에서 순차로 떼어낸다. 각 sub-PR 은 해당 서비스의 **수직 슬라이스**(모듈 골격 + main/yml + 도메인 이동 + 전속 global + 테스트)를 가져가며, 그 슬라이스가 P7~P13 의 해당 서비스 몫을 이행한다. 마지막 sub-PR 에서 root app/`src` 가 소멸한다. 각 sub-PR 은 독립 `/work`→리뷰→`/ship`.

| sub-PR | 서비스 | 전속 global (PR1 이연분 포함) | 핵심 주의 |
|---|---|---|---|
| **PR2a** | Notification | `port.SlackPort` · `idempotency`(복제) · `KafkaConfig` 팩토리(`kafkaErrorHandler`→Slack) · `IntegrationTestConfig` | NotificationController `@CurrentUser` 인증 필요. **`peekcart-common-auth` 모듈 생성·재사용 시작점(ADR-0014)** + peel 메커니즘 확립 |
| **PR2b** | Product | `CacheConfig`(S7) · `idempotency`(소비 시) | public product API permitAll + `AdminProductController @PreAuthorize` → `:peekcart-common-auth` 의존 |
| **PR2c** | User | 발급 전속: `TokenIssuer`/JWT sign · `AuthController`/refresh · 블랙리스트 **write**(Redis) · 서비스 SecurityConfig(자기 PUBLIC_URLS) | 검증은 `:peekcart-common-auth`(ADR-0014). User=발급 owner |
| **PR2d** | Order + Payment | `outbox` · `ShedLockConfig` · `KafkaConfig` 토픽/DLQ(order.*/payment.*) · `idempotency`(복제) | Saga 결합 → 함께. root app 소멸 |

> **⚠️ 전환기(게이트웨이 이전) 인증 모델 (ADR-0014 확정)**: 게이트웨이(구현 ③) 이전엔 **5개 서비스 전부** JWT 검증이 필요(Product 도 admin API). 검증 primitives(`JwtFilter`/verify/`LoginUser`/resolver/handler/blacklist read)는 **`peekcart-common-auth` 전용 모듈** 소유, **발급(sign/`TokenIssuer`/`AuthController`)·블랙리스트 write 는 User 전속**. 블랙리스트는 공유 Redis read + fail-closed. 각 서비스는 thin SecurityConfig 로 common-auth `JwtFilter` 배선 + 자기 PUBLIC_URLS. `common-auth` 는 PR2a 에서 생성·이후 재사용. 게이트웨이 도입 시 검증 로직 제거·헤더 변환 잔류(ADR-0014 D2-c). 상세: ADR-0014.
> **⚠️ Dockerfile 컨텍스트**: 멀티모듈 변경이므로 각 sub-PR 에서 Dockerfile COPY 컨텍스트 동기화 + 로컬 `docker build` 검증 필수(PR1 회귀 재발 방지).

> **⚠️ PR2a 분할 (구현 중 결정 — 2026-06-15)**: PR2a 가 예상보다 커서 **PR2a-1(common-auth 추출 + JWT verify/sign 분리)** ✅ 와 **PR2a-2(Notification peel)** 🔲 로 분할한다.
> - **PR2a-1 (완료, 본 PR)**: T1·T2·T4·T7(JwtSecurityConfigurer/WebMvc/OpenApi)·인증 분리 전부. `peekcart-common-auth` 모듈 + `JwtTokenVerifier`/`JwtTokenSigner` 분리 + `TokenBlacklistLookupPort`(fail-closed) + root `SecurityConfig`/`AuthService` 재배선. **추가 발견 수정**: 라이브러리 모듈(boot 플러그인 부재)에 `-parameters` 미적용 → Spring 생성자 by-name DI 깨짐 → root `subprojects` 에 `-parameters` 추가(systemic). 검증: `./gradlew build` 그린(272 tests). 서비스 peel 없음 — 4서비스 root 잔류.
> - **PR2a-2 (이연)**: T3·T5·T6·T8·T9·T10·T11(notification-service 모듈/도메인 이동/flywayMigrateShared/assertNoServiceProjectDeps/테스트/Dockerfile). **선결 결정 필요 — SlackPort 경계**: `SlackPort` 가 notification 도메인뿐 아니라 **root 의 `OutboxPollingService`·`KafkaConfig.kafkaErrorHandler`(order/payment DLQ→Slack)** 에서도 사용되고 유일 구현체 `SlackNotificationClient` 가 notification 내부에 있음. plan T7/ADR-0011 §D2 의 "SlackPort→Notification 전속" 분류가 틀림. PR2a-2 착수 시 SlackPort+client 의 `:common` 이동(권장) vs 서비스별 복제를 결정해야 함(ADR-0011 §D2 정정 동반).

#### PR2a 실행 세부 (Notification peel + `peekcart-common-auth` 생성) — P7~P13 의 Notification 몫 매핑

> 본 sub-PR 은 `peekcart-common-auth` 모듈을 **최초 생성**하고 Notification 서비스를 root app 에서 떼어낸다. 핵심 리스크는 **인증 검증/발급 분리**(ADR-0014 D1-b)와 **첫 peel 후 root app 이 여전히 컴파일·부팅**되어야 한다는 점. 아래 T-항목은 위 P7~P13 의 Notification 슬라이스를 구체화한 것이며, stable id 는 P7~P13 을 그대로 사용한다(`/work` 추적 단위).
>
> **감사된 현 패키지 인벤토리**(`src/main/java/com/peekcart/global/`):
> - `auth/`: `CurrentUser`·`LoginUser`·`LoginUserArgumentResolver`·`TokenClaims`·`TokenParseException`·`TokenBlacklistPort`(검증) / `TokenIssuer`(발급)
> - `jwt/`: `JwtFilter`(검증) · `JwtProvider`(**sign+verify 혼재 → 분리 필요, D1-b**)
> - `security/`: `JwtAuthenticationEntryPoint`·`JwtAccessDeniedHandler`(검증)
> - `config/`: `SecurityConfig`(thin 화 대상) · `WebMvcConfig`(resolver 등록) · `OpenApiConfig`(LoginUser 의존) · `KafkaConfig`(`kafkaErrorHandler`→SlackPort, PR1 이연 팩토리)
> - `port/SlackPort` · `idempotency/*`(ProcessedEvent 외 4종)

1. **(P7+common-auth 생성)** `peekcart-common-auth` 모듈 골격(`java-library`, `bootJar` 없음) + `settings.gradle` include. build.gradle: `:common` 의존 + spring-security/jjwt/spring-data-redis(검증·블랙리스트 read). **검증 primitives 이동**(`git mv`, 패키지 경로 `com.peekcart.global.*` 유지): `auth/{CurrentUser,LoginUser,LoginUserArgumentResolver,TokenClaims,TokenParseException,TokenBlacklistPort}` + `jwt/JwtFilter` + `security/{JwtAuthenticationEntryPoint,JwtAccessDeniedHandler}`.
   - **(GP-2 #1, D1-c blacklist read 계약)** common-auth 는 **`TokenBlacklistLookupPort`(read-only)** 와 그 **read-only Redis adapter** 를 소유(현 `TokenBlacklistPort` 를 read/write 로 분리 — read=common-auth, write=root/User). 조회 시맨틱 명시: **`miss=pass`(차단 아님) / `Redis 조회 실패=fail-closed`(요청 거부)**.
   - **(PR2a 범위 결정 — D1-c 이행 분할)** PR2a 는 **fail-closed/miss 시맨틱만** 지금 이행하고, **jti/토큰 hash + `auth:blacklist:` namespace 마이그레이션은 PR2c(User peel)로 이연**한다. 사유: 키스킴/hash 변경은 User 의 reuse-detection write 경로(`addGracePeriod`/`consumeGracePeriod` by 토큰값)까지 건드려 PR 경계(Notification peel)를 깨므로, 블랙리스트 write owner(User)가 분리되는 PR2c 에서 함께 처리. PR2a read 어댑터는 현 `bl:<token>` 스킴을 읽되 fail-closed 를 추가. ADR-0014 D1-c 결정 자체는 유효(이행 시점만 PR2a/PR2c 분할).
2. **(P7, D1-b 분리)** `JwtProvider` sign/verify 분리: **verify 절반(파싱·서명검증·만료검증) → common-auth**, **sign 절반(토큰 생성) → root 잔류**(User 발급 owner, PR2c 까지 root `global.jwt`). `TokenIssuer`·`AuthController`·블랙리스트 **write** 구현은 **root 잔류**(User 전속). common-auth 는 `TokenBlacklistLookupPort` read 계약만 보유(블랙리스트 write 미보유).
   - **(GP-2 3차 #2, split-package/중복 FQCN 방지)** `JwtProvider` 를 **`JwtTokenSigner`(root `global.jwt`) / `JwtTokenVerifier`(common-auth `global.jwt`)** 로 **대체하고 기존 `JwtProvider` FQCN 제거**(rename, 양쪽 잔류 금지). `com.peekcart.global.jwt/auth` 패키지는 root·common-auth 가 분점하되 **동일 FQCN 클래스가 두 jar 에 중복 존재 금지**(classpath 우선순위로 sign/verify 오로딩 위험). 완료 게이트에서 중복 FQCN 부재 검사.
   - **(GP-2 3차 #1, 서명 키/알고리즘 단일 계약)** sign(root)·verify(common-auth)가 **동일 외부 설정**(`jwt.secret`/`jwt.algorithm`, 전환기 대칭키 HS256, ADR-0014 D2-a)을 바인딩하도록 **단일 설정 계약**(예: common 의 `JwtAuthProperties`) 명시 — signer/verifier 가 서로 다른 property/env 를 읽는 드리프트 차단. 완료 게이트에 **root signer 발급 토큰을 common-auth verifier 가 검증하는 cross-module 회귀** 추가.
3. **(P7)** `notification-service` 모듈 골격(`bootJar`) + `settings.gradle` include + build.gradle: `:common`+`:peekcart-common-observability`+`:peekcart-common-auth` 의존.
4. **(P7, peel 불변식)** **root app build.gradle 에 `:peekcart-common-auth` 의존 추가** — root 의 user/product/order/payment 컨트롤러가 `@CurrentUser`/`JwtFilter` 를 계속 쓰므로 검증 모듈 의존 필수. **root app 컴파일·`/actuator/health` 부팅 그린 유지가 PR2a 완료의 필수 게이트.**
5. **(P8)** `NotificationApplication`(`@SpringBootApplication`) + `notification-service/src/main/resources/application.yml`(+local/k8s), ADR-0009 S2 `application=notification-service` 태그. `PeekcartApplication` 은 root 잔류(PR2d 소멸).
6. **(P9)** `com.peekcart.notification.*`(presentation/application/domain/infrastructure) → notification-service 모듈로 `git mv`(4-Layered 유지).
7. **(P10)** Notification 전속 global 이동: `port.SlackPort`(+`infrastructure/slack` 구현은 도메인과 함께) · `idempotency/*` 복제(소비자 멱등성) · `KafkaConfig` 의 `kafkaErrorHandler`→SlackPort 팩토리(PR1 이연분, Notification 소비 경로). **thin SecurityConfig 신설**: notification-service 가 common-auth `JwtFilter` 배선 + 자기 PUBLIC_URLS(+actuator permitAll S4). `WebMvcConfig`(resolver 등록)·`OpenApiConfig`(LoginUser 의존)는 common-auth 로(서비스 의존 0 충족) — root/타 서비스도 재사용.
   - **(GP-2 #2, D3 경계)** common-auth 의 `WebMvcConfig`/`OpenApiConfig` 는 **`LoginUser`/resolver + 공통 OpenAPI schema/customizer 만** 포함한다. **서비스 패키지 스캔·서비스 컨트롤러·서비스별 PUBLIC_URLS 의존 금지**(ADR-0011 §D3). 서비스별 SecurityConfig·OpenAPI 그룹/스캔 설정은 각 서비스 모듈에 잔류 — 이 경계를 깨면 `assertNoServiceProjectDeps`(T9)/리뷰에서 차단.
   - **(GP-2 3차 #3, SecurityFilterChain 소유 단일화)** 보안 bean 소유 모델 **1개로 고정**: **common-auth = `JwtFilter`/entryPoint/accessDeniedHandler + chain configurer(빈 자체 생성 금지)** 제공, **각 서비스(및 root app)가 정확히 1개의 `SecurityFilterChain` 빈을 생성**하여 그 configurer 로 JwtFilter 를 배선. `JwtFilter` 중복 등록·broad chain 중복 금지. PR2a 동안 root app 의 기존 SecurityConfig 도 이 모델로 정렬(JwtFilter 정의는 common-auth 1곳). 완료 게이트에서 **모듈별 SecurityFilterChain 수·JwtFilter 등록 1회** 검증.
8. **(P11)** 과도기 Flyway: notification-service `spring.flyway.enabled=false`. **root Gradle task `flywayMigrateShared` 최초 신설**(공유 V1~V4 단일 실행 지점). Testcontainers fixture 가 1회 마이그레이션. (마이그레이션 전용 모듈 금지 — root task.)
9. **(P12)** `assertNoServiceProjectDeps` Gradle task **최초 신설**: 각 서비스 프로젝트 configuration 순회 → allowlist(`:common`,`:peekcart-common-observability`,`:peekcart-common-auth`,`testFixtures(project(':common'))`) 외 `ProjectDependency` 시 빌드 실패. **의도적 위반 추가→실패 재현** 케이스 포함.
10. **(P13)** testFixtures 배선(`testImplementation(testFixtures(project(':common')))`) + `IntegrationTestConfig`(SlackPort 의존) → notification-service test 이동. notification-service Testcontainers 통합 테스트 + `ObservabilityMetricsIntegrationTest`(application= 태그/histogram `_bucket`/`/actuator/prometheus` 200/health permitAll/exposure whitelist) + **보안 negative**(미인증 시 NotificationController 401/403, health·prometheus 만 permitAll).
   - **(GP-2 #3, blacklist fail-closed 회귀)** 보안 negative 에 ADR-0014 D1-c/D2-b 핵심 결정 검증 추가: **(i) blacklisted token 요청 거부**(블랙리스트 hit→401/403), **(ii) Redis blacklist 조회 실패 시 fail-closed**(요청 거부), **(iii) Redis miss 는 통과**(정상 인증 흐름). 이 3 케이스가 없으면 차단 토큰 경로가 깨져도 완료 게이트를 통과하므로 필수.
11. **(P7, Dockerfile)** Dockerfile COPY 컨텍스트에 `peekcart-common-auth`+`notification-service` 모듈 소스 추가, 로컬 `docker build` 검증(PR1 회귀 재발 방지, [[project_multimodule_dockerfile_context]]).

**PR2a 완료 게이트**: (a) `./gradlew build` 그린(common+observability+auth+notification-service+root app), (b) root app 여전히 부팅, (c) notification-service 독립 `bootJar`+`/actuator/health` 200, (d) `assertNoServiceProjectDeps` 그린 + 위반 재현, (e) **로컬 docker build — root app 기존 이미지 빌드 성공 + notification-service 이미지/`bootJar` 대상 빌드 성공**(GP-2 #5, 산출 이미지 범위 명시), (f) **(GP-2 #4)** `./gradlew flywayMigrateShared` 실행 성공 + notification-service Testcontainers fixture 가 서비스 Flyway disabled 상태에서 공유 `V1~V4` 를 1회 적용함을 확인, (g) **(GP-2 #3)** blacklist hit 거부·Redis 실패 fail-closed·miss 통과 회귀 그린, (h) **(GP-2 3차 #1)** root signer 발급 토큰을 common-auth verifier(notification-service)가 검증하는 cross-module 회귀 그린(동일 `jwt.secret`/`jwt.algorithm` 바인딩 확증), (i) **(GP-2 3차 #2)** root app·common-auth 산출물 간 `com.peekcart.global.*` 동일 FQCN 중복 부재(`JwtProvider` 제거 확인), (j) **(GP-2 3차 #3)** root·notification-service 각각 `SecurityFilterChain` 1개·`JwtFilter` 등록 1회 + root business endpoint 미인증 거부·actuator permitAll 회귀.

- [ ] **P7.** (각 sub-PR 의 해당 서비스 몫) 서비스 모듈 골격 생성(ADR-0011 §D1 정확한 모듈명: `user-service`, `product-service`, `order-service`, `payment-service`, `notification-service`, 각 `bootJar`): `settings.gradle` include, 각 `build.gradle` 가 `:common`+`:peekcart-common-observability`(+ 전 서비스가 `:peekcart-common-auth`, see ADR-0014) 의존. **마지막 sub-PR(PR2d)에서 루트 app/`src` 제거.**
- [ ] **P8.** 각 서비스에 `@SpringBootApplication` main 클래스 + 서비스별 `application.yml`(+local/k8s profile, ADR-0009 S2 `application=` 태그 자기 yml) 생성. `PeekcartApplication` 분해.
- [ ] **P9.** 도메인 패키지 이동: `com.peekcart.{user,product,order,payment,notification}.*` → 각 서비스 모듈(4-Layered 구조 유지).
- [ ] **P10.** 서비스 전속 global 분배(§D2): User=`SecurityConfig`(인증부+actuator S4)+`auth`(+`WebMvcConfig`,`OpenApiConfig`)/`jwt`/`security` · Order/Payment=`outbox`+`ShedLockConfig`+`KafkaConfig`(토픽/DLQ 빈 + PR1 이연 팩토리) · Product=`CacheConfig`(S7) · Order/Payment/Notification=`idempotency`(ProcessedEvent 복제) · Notification=`port.SlackPort`. PR1 이연분(WebMvcConfig/OpenApiConfig/KafkaConfig 팩토리/SecurityConfig S4/IntegrationTestConfig/fixtures) 포함.
- [ ] **P11.** 과도기 DB/Flyway 전략 — **단일 확정**: 5개 서비스 전부 `spring.flyway.enabled=false`. 공유 `V1~V4` 는 **root Gradle task `flywayMigrateShared`** 가 단일 실행 지점(**마이그레이션 전용 모듈 신설 금지** — root task 로 처리; 모듈 토폴로지는 8모듈[common+observability+auth+5서비스], see ADR-0014). 모든 환경이 동일 runner 를 호출: (Testcontainers) 테스트 fixture 가 1회 마이그레이션 → (compose/CI smoke) infra up → `flywayMigrateShared`(또는 동일 runner 이미지) → 서비스 up → (k8s) Job/initContainer 가 동일 마이그레이션 → 서비스. `flyway_schema_history` 동시 쓰기 경합 원천 차단. 스키마 서비스별 분리는 ②.
- [ ] **P12.** 의존 위반 검출 Gradle task(`assertNoServiceProjectDeps`) — 문자열 스캔이 아니라 각 서비스 프로젝트의 **모든 관련 configuration**(`implementation`/`api`/`runtimeOnly`/`testImplementation` 등)을 순회하며 `ProjectDependency` 를 평가, allowlist(`:common`, `:peekcart-common-observability`, `:peekcart-common-auth`(ADR-0014), `testFixtures(project(':common'))` 허용) 외 프로젝트 의존 시 **빌드 실패**. 루트 convention/플러그인 주입 의존도 포착. CI 에 **의도적 위반 추가→빌드 실패** 재현 테스트 포함. (선택) ArchUnit 패키지 의존 테스트.
- [ ] **P13.** testFixtures 의존 배선(서비스별 `testImplementation(testFixtures(project(':common')))`) + 각 서비스 모듈에서 Testcontainers 통합 테스트 실행 확인. **관측성 회귀 테스트 서비스별 복제/파라미터화**(ADR-0009 §45-48): 서비스별 `ObservabilityMetricsIntegrationTest` — `application=` 태그 값, histogram `_bucket`, `/actuator/prometheus` 200, `/actuator/health/**` permitAll, exposure whitelist 정확도 검증. **보안 negative 회귀**(ADR-0009 S4 §47-48,81-82 — P4 actuator 분리로 인한 SecurityFilterChain 병합 과허용 방지): 미인증 시 대표 비즈니스 endpoint 401/403, `/actuator/health/**`·`/actuator/prometheus` 만 permitAll 확인. 5개 모듈 각각 그린.

### PR3 — Dockerfile / CI matrix / k8s N개화

- [ ] **P14.** Dockerfile 서비스별화: 각 서비스 `bootJar`→이미지(서비스별 Dockerfile 또는 build-arg 파라미터화). `scripts/docker-health-smoke.sh` 를 서비스별 이미지에 대해 유지(필요 infra 조합·포트 충돌 회피) — **서비스별 포트·`SPRING_PROFILES_ACTIVE`·시크릿 더미값 매트릭스**를 P16 의 k8s env 표면과 동일하게 연결.
- [ ] **P15.** CI image job 을 **서비스 matrix** 로 전환: PR `docker build`+smoke 를 5개 서비스로, main push `:latest`/`:sha` 를 서비스별 repo 로. test-report artifact path 를 `**/build/reports/` 로 일반화.
- [ ] **P16.** k8s base/overlays N개화: `k8s/base/services/{service}/` 5개 서비스 세트 — `deployment.yml`/`service.yml`/`configmap.yml`/`secret.yml`(또는 external secret 참조)/`servicemonitor.yml`(02-arch §375-380 4종+monitor). 각 Deployment 에 `SPRING_PROFILES_ACTIVE=k8s`, env/`envFrom`(datasource/Redis/Kafka/JWT/Slack 등 서비스별 필요분), `containerPort`/service port, **liveness/readiness/startup probe**(02-arch §34-35) 명시. overlays(gke/minikube) patch 정합. **L-016a** — gke `images.newTag` 를 digest 고정으로.
- [ ] **P17.** **D-016** — GHCR→AR image promotion 자동화(5개 이미지). 문서/스크립트 반영.
- [ ] **P18.** ADR-0009 S5/S6 k8s 관측성 표면 반영(재결정 아님, Phase 4 owner 적용): 서비스별 `servicemonitor.yml` 생성(namespace 단위 묶음 금지, selector/port/namespace 정합) + Grafana shared alert 의 `application=~"..."` cross-service 정규식 업데이트(ADR-0009 §49-53).
- [ ] **P19.** 문서 동기화(각 PR 머지 시): `docs/TASKS.md`(구현 ① 상태 `🔄`→`✅`+PR 링크), `docs/progress/PHASE4.md`(실제 빌드/테스트 이력). **`docs/02-architecture.md` Phase 4 패키지 트리(§454-466)·전환표(§514-516) 직접 갱신 필수** — 현재 What 서술(`com.peekcart.common.*` 경로, outbox/인증 이동)이 ADR-0011 §D1~D2 결과물(패키지 경로 `com.peekcart.global.*` 유지, outbox=Order/Payment 전속, idempotency 소비자별 복제, observability 별도 모듈)과 상충 → ADR 결과물 기준으로 정합("Layer 1=What" 직접 반영, 포인터로 대체 금지).

## 4. 영향 파일

**PR1**: `build.gradle`(루트 재구성), `settings.gradle`(include), 신규 `common/build.gradle`·`peekcart-common-observability/build.gradle`, `common/src/main/java/com/peekcart/global/**`(이동), `common/src/testFixtures/java/com/peekcart/support/**`(이동), `peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java`(이동), base yml exposure fragment.

**PR2**: `settings.gradle`(6 include — `{user,product,order,payment,notification}-service` + `peekcart-common-auth`), **`peekcart-common-auth/build.gradle` + `peekcart-common-auth/src/main/java/com/peekcart/global/{auth,jwt,security}/**`(검증 primitives 이동, ADR-0014 D1) + `common-auth` testFixtures(WithMockLoginUser* 재배치 시)**, `*-service/build.gradle`×5(전 서비스 `:peekcart-common-auth` 의존), `*-service/src/main/java/com/peekcart/{domain}/**`(이동)×5, `*-service/src/main/java/.../<Service>Application.java`×5, `*-service/src/main/resources/application*.yml`×5, 서비스 전속 global 이동(outbox/shedlock/cache/idempotency/port + User=발급 sign/AuthController/blacklist write), **공유 `V1~V4` 실행 위치 배선**(서비스별 `db/migration/{service}/` 재배치는 ② 제외), `assertNoServiceProjectDeps` gradle 로직, `*-service/src/test/**`(이동, `ObservabilityMetricsIntegrationTest` 포함).

**PR3**: `Dockerfile`(서비스별), `scripts/docker-health-smoke.sh`, `.github/workflows/ci.yml`(matrix), `k8s/base/services/{service}/**`×5(Deployment/Service/`servicemonitor.yml`), `k8s/monitoring/shared/grafana-alerts.yml`(application 정규식), `k8s/base/kustomization.yml`, `k8s/overlays/{gke,minikube}/kustomization.yml`(digest 고정), image promotion 스크립트/문서.

## 5. 검증 방법

### 자동
- `./gradlew build` — 전 모듈 컴파일 + 테스트 그린 (PR1: app+2모듈 / PR2: 8모듈 = common+observability+auth+5서비스, see ADR-0014).
- `./gradlew assertNoServiceProjectDeps` — 서비스↔서비스 의존 0 (의도적 위반 추가 시 빌드 실패 재현).
- 각 서비스 `./gradlew :{service}:bootJar` 산출물 생성.
- CI: 5개 이미지 `docker build` + `docker-health-smoke`(`/actuator/health` 200).

### 수동
- 각 서비스 컨테이너 단독 부팅 → `/actuator/health` 200, `/actuator/prometheus` 노출(관측성 모듈 반영 확인).
- `kustomize build k8s/overlays/gke` 렌더 + 5개 Deployment digest 참조 확인.
- 의존 그래프(`./gradlew :{service}:dependencies`)에서 다른 서비스 모듈 미포함 확인.

## 6. 완료 조건

- [ ] 8개 모듈(common+observability+auth+5서비스, see ADR-0014) 빌드/테스트 그린
- [ ] 서비스↔서비스 의존 위반이 빌드 실패로 검출됨(재현 테스트 포함)
- [ ] 5개 서비스 독립 이미지 빌드 + smoke 통과 (CI matrix 그린)
- [ ] k8s 5개 Deployment + gke digest 고정(L-016a 해소)
- [ ] D-016 image promotion 자동화 반영
- [ ] ADR-0011 §D1~D4 와 결과물 1:1 정합, Layer 1 문서/TASKS/progress 동기화

## 7. 트레이드오프 및 결정 근거

- **3-PR 분할**: ADR-0011 §D4 "PR 분할 권장(common 추출 → 서비스 순차)" 채택. common 추출(PR1)을 먼저 그린화해 서비스 분리(PR2) diff 의 리스크를 격리. CI/k8s(PR3)는 코드 안정화 후.
- **과도기 공유 DB**: DB-per-service 는 ②(ADR-0012). ①에서 동시 분리 시 PR2 diff 폭증·롤백 위험 → 공유 DB 부팅으로 모듈 분리와 DB 분리를 디커플. Flyway 동시 부팅 충돌은 P11 에서 단일 실행 지점/baseline 으로 봉합.
- **이벤트 DTO common 위치**: ADR-0011 §D2 — 위치만 common, 스키마는 A3(ADR-0012) non-authoritative. 본 계획은 위치 이동만.
- **`common` 비대화**(ADR-0011 §113 부정 영향): 인프라/유틸 집중 위험 → §D2 class-level 경계 준수 + 주기적 경계 리뷰로 완화.
- **CI matrix / image promotion 운영 부담**(§115): 이미지 N개·빌드 시간 증가 → matrix 캐시·변경 모듈만 빌드 등 후속 최적화 여지.
- **`processed_events` 서비스별 복제**(§116): 멱등성 소비자별 복제로 중복 코드 → 공통 추상은 common, 테이블은 서비스 DB 분리 원칙 유지.

## 8. Phase 4 Exit Criteria coverage / 후속 (Out-of-Scope)

본 task(구현 ①)가 닫는 Exit Criteria(`07-roadmap §16`)는 **"모든 서비스 독립 배포"의 빌드/배포 전제**(모듈 분리·서비스별 bootJar/이미지·독립 부팅)뿐이다. 나머지는 후속 task owner:

| Exit Criteria | Owner |
|---|---|
| 모든 서비스 독립 배포·정상 동작 | 구현 ①(빌드/배포 전제) + ② DB 분리 |
| Saga 보상 트랜잭션 플로우 | 구현 ④ Choreography Saga |
| Gateway 라우팅·JWT 인증 | 구현 ③ Gateway(ADR-0013) |
| 직접 호출 없이 이벤트+로컬 캐시 데이터 조합 | 구현 ⑤ CQRS 로컬 캐시 |

Out-of-Scope:
- 구현 ② DB-per-service(ADR-0012): 스키마/Flyway 서비스별 분리, 공유 DB 해소.
- 구현 ③ Gateway(ADR-0013): User 서비스 인증 + Spring Cloud Gateway.
- 구현 ④ Choreography Saga(ADR-0012): 보상 트랜잭션 플로우.
- 구현 ⑤ CQRS 로컬 캐시(ADR-0012): product.updated 기반 로컬 캐시.
- D-002 격리 재측정(Order Service 분리 후).
