# ADR-0011: Phase 4 Gradle 멀티모듈 구조 — common + 관측성 + 5개 서비스 모듈

- **Status**: Partially Superseded by ADR-0014
- **Decided**: 2026-06-14 (Proposed) → 2026-06-14 (Accepted)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리)

> **무효화 범위 (ADR-0014, 게이트웨이 이전 전환기)**: §D1 모듈 레이아웃에 `peekcart-common-auth` 추가 / §D2 `auth`/`jwt`/`security` 중 **검증 소유**만 전용 모듈로 변경(발급·AuthController·refresh·블랙리스트 write 등 User 저장소 소유는 **유효**) / §D3 서비스 허용 의존에 `:peekcart-common-auth` 추가. 그 외 §D1~D4 결정은 유효.

## Context

ADR-0010 이 Phase 4 서비스 경계를 5개(User/Product/Order/Payment/Notification)로 확정했다. 본 ADR 은 그 5개를 **Gradle 멀티모듈로 어떻게 물리 분리하는지**(모듈 레이아웃·`common` 경계·의존 규칙·빌드/테스트/이미지 계약)를 결정한다. 실제 전환(코드 이동·빌드 재구성)은 구현 ①(별도 task)이며, 본 ADR 은 그 전환의 SSOT 다.

### C1. 현 빌드 구조 (단일 모듈 — 실제 파일 인용)

- `settings.gradle`: `rootProject.name = 'peekcart'` (단일 모듈, include 없음)
- `build.gradle`: 단일 모듈, `org.springframework.boot 3.5.12`, 단일 `bootJar`(L17) → `app.jar`
- `Dockerfile`: 단일 이미지 — `COPY src/ src/` 전체 → `bootJar` → `build/libs/app.jar` (L5-16)
- `.github/workflows/ci.yml`: 단일 이미지 `ghcr.io/${owner}/peekcart`(L14), PR `docker build` + `Smoke Docker image (PR)`(`scripts/docker-health-smoke.sh` — compose 로 MySQL/Redis/Kafka 띄우고 `/actuator/health` 200 대기, L78-80), main push 시 `:latest`/`:sha`(L93-95)
- `k8s/base/services/peekcart/deployment.yml`: 단일 Deployment `ghcr.io/kimgyuilli/peekcart:latest`(L25)
- `k8s/overlays/gke/kustomization.yml`: `images.newTag: latest`(L37) — **L-016a digest 고정 부채**

### C2. 현 `com.peekcart.global.*` 패키지 (분류 입력)

`entity`(BaseEntity), `response`, `exception`, `config`(Cache/Kafka/Metrics/OpenApi/Redis/Redisson/Security/ShedLock/WebMvc), `kafka`(trace/parser/MDC), `auth`/`jwt`/`security`, `outbox`, `idempotency`(ProcessedEvent), `port`(SlackPort), `lock`(DistributedLockManager), `filter`(MdcFilter), `cache`(CachedPage). 테스트 공용 support: `src/test/.../support`(AbstractIntegrationTest, IntegrationTestConfig, ServiceTest, WithMockLoginUser, fixture).

### C3. 선행 ADR 제약

- **ADR-0010**: 5개 서비스 = 5개 서비스 모듈의 입력. 이벤트 토폴로지 토픽 4개, 스키마는 A3 위임
- **ADR-0009**: **`peekcart-common-observability` 모듈을 Phase 4 용으로 사전 결정**(L131: "Phase 4 plan 은 이 모듈을 자기 결정 항목에서 제외하고 본 ADR 인용"). S1(`MetricsConfig`)·S3(exposure base yml)·S4(`ActuatorSecurityConfig`)가 이 모듈 소유, S2(`application=` 태그)는 각 서비스 자기 yml, S7(cache)은 product-service, S8(outbox)은 발행 서비스 소유 (ADR-0009 §Decision L45-55)
- **ADR-0001**: 각 서비스 모듈 **내부**는 기존 4-Layered + DDD 유지
- **`docs/02-architecture.md §4-4`**: 모노레포(Gradle 멀티모듈) 채택 SSOT — 멀티레포 비대상 (ADR-0002 는 Phase 4 에 Gradle 멀티모듈이 포함된다는 진화 전략 근거)

## Decision

**`common` + `peekcart-common-observability`(ADR-0009 선결정) + 5개 서비스 모듈(User/Product/Order/Payment/Notification)** 의 Gradle 멀티모듈로 분리한다 (Alt A). 본 ADR 은 모듈 경계·의존 규칙·빌드/테스트/이미지 계약을 확정하며, 이벤트 DTO 는 **`common` 모듈 소유**로 확정(공유 위치)하되 스키마(필드·버전·파티션 키·retention)는 A3 위임(non-authoritative)으로 둔다. 관측성 모듈은 ADR-0009 결정을 **인용·반영만** 하고 재결정하지 않는다.

### D1. 모듈 레이아웃

```
settings.gradle:
  rootProject.name = 'peekcart'
  include 'common'
  include 'peekcart-common-observability'   // ADR-0009 선결정
  include 'user-service'
  include 'product-service'
  include 'order-service'
  include 'payment-service'
  include 'notification-service'
```

| 모듈 | 산출물 | build.gradle 책임 |
|---|---|---|
| (root) | — | 공통 plugin/version 관리(Spring Boot BOM, Java 17), subprojects 공통 설정. `bootJar` 비활성 |
| `common` | plain jar (`java-library`) | 공유 라이브러리. `bootJar` 없음, `java-test-fixtures` 로 공용 테스트 support 제공 |
| `peekcart-common-observability` | plain jar | ADR-0009 소유 surface (MetricsConfig/ActuatorSecurityConfig/exposure base). 본 ADR 위치만 확정 |
| `*-service` (5개) | `bootJar` → 이미지 | 각 서비스 실행 단위. `:common` + `:peekcart-common-observability` 의존 |

### D2. `common` 경계 (class/role 단위)

| 현 위치 | 분류 | 비고 |
|---|---|---|
| `global.entity`(Base*Entity), `global.response`, `global.exception` | **common** | 순수 공유 |
| `global.kafka`(trace/parser/MDC interceptor), `config.RedisConfig`/`RedissonConfig`/`WebMvcConfig`/`OpenApiConfig`, `global.filter.MdcFilter`, `global.lock.DistributedLockManager`, `global.cache.CachedPage` | **common** (인프라/유틸 공유) | 연결·직렬화·MDC·페이지네이션 등 |
| `config.MetricsConfig`(S1), exposure base yml(S3), `config.SecurityConfig` 의 actuator 허용부(S4) | **peekcart-common-observability** | ADR-0009 소유 (재결정 안 함) |
| `config.SecurityConfig` 비즈니스 인증부, `global.auth`/`jwt`/`security` | **서비스 전속** (User; Gateway 인증은 A4) | A4 와 경계 |
| `global.outbox`, `config.ShedLockConfig` | **서비스 전속** (Order/Payment — 발행자) | outbox 폴링 소유 |
| `config.CacheConfig`(S7) | **서비스 전속** (Product — 캐시 owner) | ADR-0009 S7 |
| `global.idempotency`(ProcessedEvent) | **서비스별 복제** (Order/Payment/Notification 소비자) | 멱등성은 소비자별 |
| `global.port.SlackPort` (+ `SlackNotificationClient` 구현) | **common** (횡단 인프라) | outbox/kafka DLQ alerting + notification 도메인 공용. 분류 정정 — Update Log 참조 |
| `config.KafkaConfig` 토픽/DLQ 빈 | **발행 서비스 전속** (Order: order.*, Payment: payment.*) | producer/consumer factory 는 common |
| 이벤트 DTO (`global.outbox.dto.*`) | **common** | A2 는 공유 **위치만 common 으로 확정**(`02-architecture.md §4-4` 정합). 필드/버전/파티션 키/retention 은 A3 non-authoritative |

### D3. 의존 규칙

- 서비스 모듈은 **`:common` + `:peekcart-common-observability` 만** `implementation` 의존
- **서비스 모듈 간 직접 의존 금지** — 도메인 간 통신은 이벤트(Kafka)만 (ADR-0001 "도메인 간 직접 호출 금지"의 모듈 차원 물리 강제)
- **위반 검출은 필수 계약** (구현 ① 에서 구현):
  1. 서비스 `build.gradle` 에 `project(':common')`·`project(':peekcart-common-observability')` 외 `project(...)` 의존 금지
  2. CI 에서 검증 Gradle task(예: `assertNoServiceProjectDeps`) — 위반 시 **빌드 실패**
  3. (선택) ArchUnit 패키지 의존 테스트
- 이벤트 계약 공유는 D2 의 "이벤트 DTO" 행을 따르되 스키마는 A3

### D4. 빌드 / 테스트 / 이미지 계약

- **빌드**: 서비스 모듈만 `bootJar` → 이미지 N개. `common`/`peekcart-common-observability` 는 plain jar
- **테스트**: 공용 test support(AbstractIntegrationTest 등)는 `common` 의 `java-test-fixtures` 로 제공, 서비스는 `testImplementation(testFixtures(project(':common')))`. Testcontainers 통합 테스트는 각 서비스 모듈에서 실행. CI test-report artifact path 를 `**/build/reports/` 로 일반화
- **이미지**: CI image job 을 서비스 matrix 화. **Docker health smoke 유지** — 현 `scripts/docker-health-smoke.sh`(compose MySQL/Redis/Kafka + `/actuator/health` 200)를 서비스별 이미지에 대해 유지(서비스마다 필요한 infra 조합·포트 충돌 회피·실패 시 빌드 실패). L-016a(`images.newTag` digest 고정)·D-016(GHCR→AR image promotion 자동화)을 구현 ① 행동 지침으로
- 위 계약의 **실제 구현은 구현 ①**, 본 ADR 은 계약만 확정

## Alternatives Considered

### Alternative A: `common` + (관측성) + 5개 서비스 모듈 — **채택**
- **장점**: 모듈 = 배포 단위 = 서비스로 단순. ADR-0009 관측성 모듈만 예외로 추가. common 비대화는 D2 class-level 경계로 통제
- **단점**: common 이 인프라/유틸을 다수 포함 → 응집도 관리 필요
- **채택 사유**: 가장 단순하고 ADR-0009/0010 과 정합. 전환 비용 최소

### Alternative B: `common` 2계층 (`common` + `domain-common`)
- **장점**: 공유 VO 와 인프라 유틸 분리
- **단점**: Phase 4 초기엔 공유 도메인 VO 가 적어 과분할. 모듈 수 증가
- **기각 사유**: 현 시점 이득 대비 복잡도 과다. 필요 시 후속 분리 가능

### Alternative C: 기능별 라이브러리 모듈 다수 (event-contract, security-lib, web-common …)
- **장점**: 세밀한 재사용 경계
- **단점**: Phase 4 진입과 동시에 모듈 N+ 폭증 → 빌드/의존 그래프 복잡. 과설계
- **기각 사유**: Phase 4 초기 부담 과다. Phase 5+ 재검토 후보

## Consequences

### 긍정적 영향
- 구현 ①(멀티모듈 전환)의 SSOT — settings/build 분리, 패키지 이동, Dockerfile/CI matrix/k8s N개화가 본 ADR 에서 1:1 도출
- 모듈 경계로 "서비스 간 직접 호출 금지"(ADR-0001)를 컴파일 차원에서 물리 강제
- ADR-0009 관측성 모듈을 인용·반영 → Phase 4 분리 시 관측성 surface 재분산 방지

### 부정적 영향 / 트레이드오프
- `common` 비대화 위험 — 인프라/유틸이 몰릴 수 있어 D2 경계 + 주기적 점검 필요
- 빌드 복잡도·이미지 N개 운영 — CI matrix, image promotion(D-016) 자동화 부담
- 멱등성(`processed_events`) 서비스별 복제 — 중복 코드 발생(공통 추상은 common, 테이블은 서비스 DB)

### 후속 결정에 미치는 영향
- **구현 ①**: 본 ADR 의 D1~D4 를 실제 코드/빌드로. L-016a/D-016 적용
- **A3**: 이벤트 DTO 스키마/네이밍/retention 확정 (D2 "이벤트 DTO" 행의 non-authoritative 해소)
- **A4**: User 서비스 인증 + Gateway (D2 의 auth/jwt/security 전속부)

## References
- ADR-0001(4-Layered+DDD), ADR-0002(Phase 4 Gradle 멀티모듈 포함), ADR-0009(`peekcart-common-observability` 선결정 — §Decision L45-55, L131), ADR-0010(5개 서비스 경계)
- `docs/02-architecture.md §4-4`(모노레포 — Layer 1), §12(패키지 구조)
- `docs/progress/phase4-design-roadmap.md §1 A2`
- 빌드/CI 파일: `settings.gradle`, `build.gradle`, `Dockerfile`, `.github/workflows/ci.yml`(L14,78-95), `scripts/docker-health-smoke.sh`, `k8s/overlays/gke/kustomization.yml`(L37)

## Update Log

- **2026-06-15** (구현 ① PR2a-2): §D2 표의 `global.port.SlackPort` 분류를 **"서비스 전속(Notification)" → "common(횡단 인프라)"** 으로 정정. 사유: `SlackPort` 는 notification 도메인뿐 아니라 `global.outbox.OutboxPollingService`·`config.KafkaConfig.kafkaErrorHandler`(order/payment DLQ→Slack alerting)에서도 사용되며, 유일 구현 `SlackNotificationClient` 는 notification 도메인 의존이 0(RestClient+webhook 설정)인 횡단 인프라다. `SlackPort` 자체 javadoc 도 "Outbox, Notification 등 여러 도메인에서 사용하는 횡단 관심사이므로 global 에 위치한다" 로 명시하고 있어, 최초 분류가 코드 현실을 반영하지 못한 **사실 오류**였다. 트레이드오프/대안 변경이 아닌 분류 정정이므로 본문 정정(Update Log)으로 처리한다. §D3 의존 규칙(서비스→`:common` 허용)에는 영향 없음. 구현: `SlackPort`+`SlackNotificationClient` → `:common`(`global.port`/`global.slack`).
