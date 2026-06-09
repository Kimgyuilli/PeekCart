# ADR-0009: 관측성 계약 SSOT 결정

- **Status**: Accepted
- **Date**: 2026-05-04
- **Deciders**: 프로젝트 오너
- **관련 Phase**: 전체 (Phase 4 모듈 분리 진입 전 결정)

## Context

세션 C 종료 후 전반적 리뷰(2026-04-10)에서 D-005 "관측성 계약 5파일 분산" 이 등록되었다. 1차 봉합으로 P0-B (`management.endpoints.web.exposure.include` + `management.metrics.tags.application` 의 base `application.yml` 이동) 와 P1-D (`@AutoConfigureObservability` 회귀 테스트) 가 적용되었으나, "어느 surface 의 SSOT 가 어느 파일·레이어인가" 라는 결정 자체가 명문화되지 않은 상태로 남았다.

본 ADR 의 직접 동기는 **Phase 4 Gradle 멀티모듈 분리** 다. 모듈 경계가 그어지면 관측성 계약의 초기 9개 surface (S1~S6.d) 가 어느 모듈/서비스에 위치해야 하는지 결정해야 하며, 결정 부재 상태로 분리하면 D-005 의 분산이 모듈 경계를 따라 더 깊어진다. ADR-0007 (YAML 프로파일 병합 원칙) 의 일반 원칙만으로는 manifest 측 surface (S5/S6) 의 결정이 도출되지 않으므로, 관측성 도메인에 구체화한 별도 ADR 이 필요하다.

### 관측성 surface 의 현 위치와 의존 관계

| # | Surface | 현 SSOT (파일:라인) | 의존 surface | 변경 시 파급 |
|---|---------|---------------------|--------------|--------------|
| S1 | HTTP histogram bucket (p50/p95/p99 계산 가능성) | `src/main/java/com/peekcart/global/config/MetricsConfig.java:17-37` (`MeterRegistryCustomizer` + `MeterFilter`) | — | Grafana p95/p99 패널 + S6.b (latency alert) **만** 의존. S6.a (error-rate) 는 `_count` 사용으로 무관 |
| S2 | metrics tags (`application=peekcart`) | `src/main/resources/application.yml:38-40` (`management.metrics.tags.application`) | — | Micrometer 가 발행하는 앱 series (`http_server_requests_*` 등) 의 `application=` 라벨. Prometheus `up` series 는 ServiceMonitor scrape label 이 별도라 무관. 영향: S6.a / S6.b 의 `{application=...}` 필터 |
| S3 | actuator 노출 화이트리스트 | `src/main/resources/application.yml:33-37` (`management.endpoints.web.exposure.include: health,prometheus`) | — | `/actuator/prometheus` scrape 가능 여부, S4 와 짝. 회색지대 키 (`probes.enabled`, `health.show-details`) 는 `application-k8s.yml:14-19` 에 잔류 — ADR-0007 회색지대 분류 (k8s Probe 운영 기능). 본 task 는 예외 주석 부재를 수정하지 않음 (코드/매니페스트 변경 비대상) |
| S4 | actuator 보안 허용 (`/actuator/health/**`, `/actuator/prometheus` permitAll) | `src/main/java/com/peekcart/global/config/SecurityConfig.java:47-48` | — | scrape 응답 코드 (200 vs 401), K8s liveness/readiness Probe |
| S5 | scrape 설정 (label `release: kube-prometheus-stack` L9, selector/endpoints/namespaceSelector L10-20: interval 15s, port `name: http`) | `k8s/base/services/peekcart/servicemonitor.yml:6-20` | — | metric 수집 빈도, Helm Prometheus operator 매칭. **모든 S6 alert 가 S5 결손 시 발화 불가** (또는 S6.d 가 absent 발화) |
| S6.a | error-rate alert (5xx ratio > 5%) | `k8s/monitoring/shared/grafana-alerts.yml:17-52` (uid `peekcart-high-error-rate`, `_count` rate 비율) | S2, S5 | 5xx 비율 임계 — S1 histogram bucket 무관 |
| S6.b | latency alert (p95 > 2s) | `k8s/monitoring/shared/grafana-alerts.yml:53-83` (uid `peekcart-slow-response`, `histogram_quantile` on `_bucket`) | S1, S2, S5 | bucket series 부재 시 NaN, `_bucket` 으로 S1 직접 의존 |
| S6.c | target-down (`up == 0`) | `k8s/monitoring/shared/grafana-alerts.yml:84-110` (uid `peekcart-target-down`, `up{namespace,service}`) | S5 | scrape label 의존만 — S1/S2 무관 |
| S6.d | scrape-absent (series 부재) | `k8s/monitoring/shared/grafana-alerts.yml:111-137` (uid `peekcart-scrape-absent`, `absent(up{...})`) | S5 (scrape target 등록 자체) | ServiceMonitor selector 미스매치/네임스페이스·서비스 삭제 — S1/S2 무관 |
| S7 | cache hit/miss (`cache_gets_total`) | `src/main/java/com/peekcart/global/config/CacheConfig.java:70-75` (`RedisCacheManager.enableStatistics`) | — | 상품 캐시 적중률/미스율 측정. Phase 4 CQRS 로컬 캐시 및 Redis fallback(L-006) 판단의 선결 표면 |
| S8 | outbox pipeline (`outbox_backlog` gauge / `outbox_publish` timer) | `src/main/java/com/peekcart/global/outbox/OutboxPollingService.java:51-65` (생성자 backlog gauge 등록 + publish Timer), `:84-93` (발행 결과별 record) | — | 발행 backlog(지연)·처리량·성공/실패율 측정. 처리량 부채화(D-002 후속·L-006) 판단의 선결 표면. alert/dashboard 는 미도입(무관) |

### 자동 회귀 검증의 현 범위

`src/test/java/com/peekcart/global/observability/ObservabilityMetricsIntegrationTest.java` (`@SpringBootTest` + `@AutoConfigureObservability`, P1-D 도입) 가 검증하는 surface:

- **검증 중**: S1 (histogram `_bucket` 노출 확인, L60-62), S2 (`application="peekcart"` 태그, L56-58), S3 happy path (`/actuator/prometheus` 200, L50-51 — exposure 의 prometheus 항목 검증), S4 happy path (no-auth `restTemplate.getForEntity` 200, L50-51 — permitAll 검증), S7 (`cache_gets_total` hit/miss + 중복 시계열 부재, D-014/L-005), S8 (`outbox_backlog` status 시계열 + `outbox_publish` result=success 집계, D-014/L-009)
- **미검증 잔여 공백**: S3 의 정확한 whitelist 형태 (예: `health` 누락 / 추가 endpoint 추가는 검출 불가), S4 의 `/actuator/health/**` 경로 (현 테스트는 prometheus 만 호출), S5 (k8s integration 범위), S6.a~d PromQL 입력 series 정합 (Prometheus 미실행 환경)

## Decision

**Alt B 채택 — surface 별 SSOT 위치를 ADR 표로 명시 (현 위치 유지)**. 본 ADR 은 코드/매니페스트 통합을 직접 수행하지 않으며, 후속 task `task-d005-observability-consolidation` 이 본 표에 따라 강제 메커니즘을 코드로 격상한다.

### Surface 별 강제 표 (6 컬럼, 모든 행 필수 채움 — "TBD"/"추후 검토" 금지)

| # | Surface | 현 SSOT (파일:라인) | 본 task 변경 | Phase 4 owner (모듈/서비스) | 이동·복제 금지 규칙 | 검증 수단 |
|---|---------|---------------------|--------------|------------------------------|---------------------|-----------|
| S1 | histogram bucket | `MetricsConfig.java:17-37` | 없음 | `peekcart-common-observability` 모듈의 `MetricsConfig` (각 서비스 모듈이 import-only) | MeterFilter 정의는 공통 모듈 1개소. 서비스 모듈에 `MeterRegistryCustomizer` 재선언 금지 | `ObservabilityMetricsIntegrationTest` L60-62 (`_bucket` substring assertion) |
| S2 | metrics tags (`application=`) | `application.yml:38-40` | 없음 | 각 서비스 모듈의 `application.yml` (값은 서비스 자기 이름 — 예: `order-service`). 공통 모듈에 default `application=` 박지 않음 | base `application.yml` 1개소만. 어느 환경 프로파일 (`application-{k8s,local,test}.yml`) 에도 재선언 금지 (ADR-0007 적용) | `ObservabilityMetricsIntegrationTest` L56-58 (Phase 4 분리 시 각 서비스 자체 회귀 테스트로 복제됨, 본 ADR 은 메커니즘만 결정) |
| S3 | exposure 화이트리스트 | `application.yml:33-37` | 없음 | 공통 base `application.yml` (`peekcart-common-observability` 모듈이 resource 로 제공, 각 서비스가 import) | base 1개소만. 환경 프로파일에 추가 endpoint override 시 ADR-0007 예외 주석 (`# [ADR-0007 exception]`) 필수 | happy path 자동 (테스트 L50-51, 200 응답). 정확한 whitelist (예: `info` 추가/삭제 검출) 는 **미검증 → 후속 D5-V3** |
| S4 | actuator 보안 허용 | `SecurityConfig.java:47-48` | 없음 | `peekcart-common-observability` 모듈의 `ActuatorSecurityConfig` (각 서비스 `SecurityFilterChain` 에 합쳐짐) | actuator allow-list 는 공통 1개소. 각 서비스 `SecurityConfig` 는 비즈니스 인증 정책만 다룸 | `/actuator/prometheus` happy path 자동 (테스트 L50-51). `/actuator/health/**` 경로 + 비즈니스 endpoint 보안 회귀 = **미검증 → 후속 D5-V4** |
| S5 | scrape 설정 | `k8s/base/services/peekcart/servicemonitor.yml:6-20` | 없음 | 각 서비스 own (`k8s/base/services/<service-name>/servicemonitor.yml`). selector 는 자기 namespace/service 만 | per-service 1파일. namespace 단위 ServiceMonitor 1개에 다수 서비스 selector 묶기 금지 (ownership 모호) | k8s integration 필요 — Phase 3 환경 미보유. **미검증 → 후속 D5-V5** (kustomize 빌드 + selector 매칭 단위 테스트) |
| S6.a | error-rate alert | `grafana-alerts.yml:17-52` | 없음 | `k8s/monitoring/shared/grafana-alerts.yml` (cross-service. ADR-0006 의 monitoring 환경 분리 위치 유지) | shared/ 1파일. 서비스별 alert 복제 금지. cross-service 패널은 `application=~"<svc1>\|<svc2>\|..."` 정규식 사용 | Prometheus 미실행 환경에서 검증 불가. **미검증 → 후속 D5-V6** (PromQL syntax + 입력 series 가정 lint) |
| S6.b | latency alert | `grafana-alerts.yml:53-83` | 없음 | 동상 (S6.a) | 동상 | 동상 (D5-V6) |
| S6.c | target-down | `grafana-alerts.yml:84-110` | 없음 | 동상 (S6.a) | 동상. 단 S5 ownership 변경 시 selector 동기 필수 (D5-V5 와 짝) | 동상 (D5-V6) |
| S6.d | scrape-absent | `grafana-alerts.yml:111-137` | 없음 | 동상 (S6.a) | 동상 | 동상 (D5-V6) |
| S7 | cache hit/miss | `CacheConfig.java:70-75` | D-014/L-005: `RedisCacheManager.enableStatistics()` | Phase 4 `product-service` 의 캐시 설정 (상품 캐시 owner). 공통 관측성 모듈은 cache meter 수동 재등록을 소유하지 않음 | 캐시 hit/miss 계측은 cache owner 서비스 1개소. Spring Boot `CacheMetricsAutoConfiguration` 자동 바인딩 사용, `CacheMetricsRegistrar` 수동 중복 바인딩 금지 | `ObservabilityMetricsIntegrationTest` D-014/L-005 케이스 (`cache_gets_total` hit/miss + `cache_manager="cacheManager"` 단일 시계열 검증) |
| S8 | outbox pipeline | `OutboxPollingService.java:51-65,84-93` | D-014/L-009: `outbox.backlog` gauge(`status=pending\|failed`) + `outbox.publish` Timer(`result=success\|failure`) | Phase 4 발행 주체 서비스(outbox 소유자 — `order-service`/`payment-service`). 공통 관측성 모듈은 outbox meter 를 소유하지 않음(발행 코드와 동일 위치) | outbox 계측은 발행 서비스 폴링 컴포넌트 1개소. cache(S7)와 달리 Spring Boot 자동 바인딩 대상이 아니므로 수동 등록하되, 동일 meter 이름의 중복 등록 금지(`outbox.publish` 는 result 태그로만 분기) | `ObservabilityMetricsIntegrationTest` D-014/L-009 케이스 (`outbox_backlog` status 2시계열 + `outbox_publish_seconds_count{result="success"}` 집계 검증) |

> **본 task (`task-adr-observability-ssot`) 당시 S1~S6.d 변경 컬럼 = 모든 행 "없음"**. S7 이후 행은 후속 task 에서 추가된 surface 이므로 해당 task 의 변경 내용을 기록한다. 기존 surface 의 물리적 이동은 여전히 후속 task 범위다.

### ADR-0007 / ADR-0006 과의 관계

- **Extends ADR-0007** (Supersede 아님): ADR-0007 의 "동작 정책 → Java Config" 일반 원칙을 관측성 도메인의 9 surface 에 구체화. ADR-0007 자체는 유효.
- **ADR-0006 위치 유지**: S5 는 앱 레포지토리 측 (`base/services/peekcart/`), S6 은 monitoring 스택 측 (`monitoring/shared/`) 으로 분담 유지. ADR-0006 의 monitoring 환경 분리 결정에 모순 없음.
- **회색지대 분류 (ADR-0007 감사표 기준)**:
  - `management.endpoint.health.probes.enabled` (`application-k8s.yml:17-18`): ADR-0007 "예외 허용 — k8s Probe 운영 기능"
  - `management.endpoint.health.show-details: never` (`application-k8s.yml:19`): ADR-0007 "후속 검토 (base 기본값 권장)" — **닫힌 예외가 아님**. 본 ADR 은 이 항목을 재결정하지 않으며, 처리 방향은 ADR-0007 감사표 그대로 (후속 검토 대상으로 잔류).

### 회귀 강제 메커니즘 (정의만, 코드는 후속 task)

본 ADR 의 §Decision 표 6번째 컬럼 ("검증 수단") 이 다음 두 종류로 분기한다:

1. **자동 검증 중 — 동작 회귀 한정** (S1/S2/S3 happy path/S4 happy path): `ObservabilityMetricsIntegrationTest` 가 *계약 동작* 회귀를 검출 — `_bucket` 노출 / `application=peekcart` 태그 부여 / `/actuator/prometheus` 200 응답. **위치/복제 위반 (예: 동일 키를 다른 파일에 재선언) 은 검출 불가** — 동작이 동일하면 테스트 통과. 위치 위반은 (a) 본 ADR §Decision 표 기반 수동 리뷰, (b) 후속 task 의 정적 검증 (예: `application=` 키가 base `application.yml` 외에 재선언되었는지 grep) 으로 보완
2. **미검증** (S3 whitelist 정확도, S4 health 경로, S5, S6): 후속 task 의 action id (D5-V3 ~ D5-V6) 에서 격상

후속 task `task-d005-observability-consolidation` 의 범위는 본 ADR 의 표를 1:1 source 로 사용하되 **두 갈래로 분리** 한다:

- **현 모놀리스 범위** — 6개 후속 action 으로 한정:
  - **D5-V1**: SSOT 위치 위반 정적 검증 (예: 본 ADR §Decision 표가 base `application.yml` 로 지정한 키가 다른 파일에 재선언되었는지 grep)
  - **D5-V2**: 중복 재선언/복제 정적 검증 (예: `MeterFilter` 또는 `application=` 태그가 둘 이상의 파일에 동일 의도로 선언되었는지 검출)
  - **D5-V3**: S3 (actuator exposure) whitelist 정확도 격상 — 정확한 노출 endpoint 집합 회귀 검증
  - **D5-V4**: S4 (`/actuator/health/**`) 경로 보안/접근 회귀 검증
  - **D5-V5**: S5 (ServiceMonitor) selector 매칭 정합성 검증 (k8s integration)
  - **D5-V6**: S6 (Grafana alerts) PromQL syntax + 입력 series 가정 lint
- **Phase 4 분리 시점 범위**: 표의 "Phase 4 owner" 컬럼대로 surface 를 신규 모듈/서비스로 이전. 본 작업은 Phase 4 task 의 모듈 생성 의존성을 가지므로 본 후속 task 가 직접 수행하지 않으며 Phase 4 plan 에서 본 ADR 을 인용

## Alternatives Considered

다섯 비교축 통일: (1) 변경 범위 / (2) ADR-0007 정합성 / (3) Phase 4 분리 비용 / (4) 검증 가능성 / (5) 채택·기각 사유.

### Alternative A: Java Code SSOT 단일화 (`ObservabilityConfig` 통합)

- **변경 범위**: S1, S2 (default), S3, S4 의 코드 표현을 `ObservabilityConfig` 한 클래스로 모음. S5/S6 은 manifest 측 그대로 유지하되 코드 contract 와 일관 검증
- **ADR-0007 정합성**: ✅ "동작 정책 → Java Config" 원칙 강화
- **Phase 4 분리 비용**: 중간 — 공통 모듈 위치 (`peekcart-common-observability`) 결정 필요. 다만 본 ADR 도 같은 모듈을 가리키므로 차이 없음
- **검증 가능성**: 높음 — 모든 SSOT 가 코드라 IDE 탐색·타입 안전·테스트 격상 용이
- **기각 사유**: 본 task 범위 초과. 코드 통합 자체가 위험·검증 비용을 동반하는 별도 변경이라 ADR 결정과 코드 변경을 한 task 에 묶으면 ADR 본문 품질이 코드 변경 압박에 의해 깎인다. ADR 결정 → 후속 task 코드 통합 으로 분리하면 둘 다 상대 부담 없음

### Alternative B: Surface 별 SSOT 명시 (현 위치 유지) — **채택**

- **변경 범위**: ADR 1개 + 인덱스/참조 동기화. 코드/매니페스트 변경 0건
- **ADR-0007 정합성**: ✅ 일반 원칙을 도메인 구체화. ADR-0007 적용 결과를 surface 표로 가시화
- **Phase 4 분리 비용**: 낮음 — 표의 "Phase 4 owner" 컬럼이 모듈 경계 결정의 source. 분리 시점에 표를 보고 따르기만 하면 됨
- **검증 가능성**: 중간 — S1/S2/S3 happy path/S4 happy path 는 자동 강제 (현 회귀 테스트 활용), 나머지는 후속 task 의 6 action (D5-V1~V6) 으로 격상 경로 명시
- **채택 사유**: 결정과 구현을 분리하여 각 단계의 품질을 독립 보장. 후속 task 는 D5-V1~V6 검증/강제 메커니즘 격상에 한정 — 코드 통합 (Alt A 의 `ObservabilityConfig` 신설 등 물리적 이동) 은 Phase 4 멀티모듈 분리 task 가 본 ADR 을 인용하여 수행. 본 ADR 단일 산출물이 후속 두 task 의 정의 source 가 됨

### Alternative C: 자동 생성 (Java contract → kustomize generator)

- **변경 범위**: S1~S4 코드 + S5/S6 manifest 까지 단일 코드 source 에서 생성. kustomize plugin 또는 Gradle task 신규
- **ADR-0007 정합성**: ✅ 단, ADR-0007 의 "YAML 은 연결 정보" 범위를 manifest 까지 확장 해석 필요
- **Phase 4 분리 비용**: 높음 — generator 자체의 모듈/빌드 통합 부담. Phase 4 진입 전 도입 시 모듈 분리와 generator 도입을 병행하게 되어 변경 동시성 ↑
- **검증 가능성**: 매우 높음 — 모든 surface 가 단일 source 에서 도출되므로 contract 일관성 자동 보장
- **기각 사유**: Phase 4 진입 전 도입 부담 과다. Phase 5+ (또는 OpenTelemetry 도입 시) 재검토 후보

## Consequences

### 긍정적 영향

- **D-005 잔여 리스크 차단 경로 확정**: 본 ADR 의 9 surface 표가 후속 task 의 1:1 작업 항목 source 가 된다. 결정 부재 상태로 Phase 4 모듈 분리에 진입하는 시나리오를 차단
- **Phase 4 모듈 경계 사전 결정**: 표의 "Phase 4 owner" 컬럼이 멀티모듈 분리 시 관측성 계약의 위치를 미리 박아둠. 분리 시점에 ad-hoc 결정으로 surface 가 다시 흩어지는 것을 방지
- **ADR-0007 의 도메인 구체화**: 일반 원칙(YAML vs Java Config) 만으로는 도출되지 않는 manifest 측 surface (S5/S6) 의 분담을 명시적으로 결정. 후속 도메인별 SSOT ADR 작성 시 본 ADR 이 패턴 참조
- **회귀 테스트 격상 경로 명시화**: S3 whitelist, S4 health 경로, S5 selector, S6 PromQL 의 4 공백이 D5-V3~V6 action id 로 가시화되어 후속 task plan 작성 비용 ↓

### 부정적 영향 / 트레이드오프

- **결정-구현 분리로 인한 동기화 부담**: 본 ADR 과 후속 task 는 의도적으로 분리되어 있어, ADR 변경 시 후속 task plan 재정렬이 필요. 양 문서 동기화 비용은 ADR 본문 품질로 흡수 (후속 task 가 ADR 표를 source 로 인용)
- **자동 강제 부재 영역 잔존**: 본 ADR 자체는 4 공백 (D5-V3~V6) 을 명시만 하고 격상 코드를 도입하지 않음. 후속 task 까지의 시간 동안 SSOT 위반은 수동 리뷰에 의존
- **ADR 양의 증가**: ADR-0007 + ADR-0009 의 관계가 일반-구체 의 두 단계 구조라 신규 진입자가 두 ADR 을 동시 참조해야 함. CLAUDE.md 의 §관측성 계약 단락이 두 ADR 을 함께 가리킴으로써 부분 완화

### 후속 결정에 미치는 영향

- **`task-d005-observability-consolidation`** (즉시 후속, 현 모놀리스 범위): 본 ADR 표 6번째 컬럼이 plan §3 작업 항목 source. 범위 = D5-V1 (위치 위반 정적 검증) + D5-V2 (복제 정적 검증) + D5-V3~V6 (미검증 동작 공백 격상) — 총 6 action. **Phase 4 owner 컬럼에 명시된 모듈/서비스 위치로의 물리적 이동은 본 후속 task 가 수행하지 않는다** — Phase 4 멀티모듈 분리 task 의 모듈 생성 작업에 의존하므로, 본 ADR 을 Phase 4 plan 에서 인용하여 그쪽에서 처리
- **Phase 4 모듈 분해 결정**: Phase 4 task 의 Gradle 멀티모듈 구조 결정 시, 관측성 모듈 (`peekcart-common-observability`) 의 존재가 본 ADR 에서 사전 결정됨. Phase 4 plan 은 이 모듈을 자기 결정 항목에서 제외하고 본 ADR 인용
- **OpenTelemetry 도입 (Phase 4+ 후보)**: trace surface 는 본 ADR 비대상. trace 추가 시 별도 ADR 작성. ADR-0008 (Outbox trace context) + 본 ADR + 신규 trace ADR 의 3축으로 관측성 전체 계약 형성

## References

- D-005 등록: `docs/TASKS.md` 개발 부채 표 D-005 행
- 1차 봉합: 리뷰 종합 (2026-04-10) P0-B (`management` base 이동), P1-D (`@AutoConfigureObservability` 회귀 테스트). `docs/progress/PHASE3.md` 2026-04-12 엔트리
- ADR-0006: Monitoring 스택 환경 분리 (S5/S6 위치 분담의 base)
- ADR-0007: YAML 프로파일 병합 원칙 (본 ADR 의 일반 원칙)
- ADR-0008: Outbox Trace Context Propagation (trace surface 의 별도 처리, 본 ADR 비대상)
- D-001 봉합 커밋: `715bcfa` (`MetricsConfig.java` 도입)
- 회귀 테스트: `src/test/java/com/peekcart/global/observability/ObservabilityMetricsIntegrationTest.java`
- CLAUDE.md `§설정/YAML 프로파일 규칙` 끝의 1줄 참조 ("관측성 계약 SSOT — see ADR-0009")
- 후속 task: `docs/plans/task-d005-observability-consolidation.md` (본 task 종결 후 작성)
