# ADR-0024: 관측성 canonical 집합 = 도메인 5 + 인프라 1 — gateway 편입과 세 집합의 분리 (ADR-0015 부분 무효화)

- **Status**: Accepted
- **Date**: 2026-09-14
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (구현 ③ PR4)

## Context

ADR-0015 는 5서비스 풀 분해 완료 상태를 기준으로 per-service 관측성 계약을 확정했다. 그 계약의 ground truth 는 **"5서비스"라는 단일 집합**이었고, 세 가지를 동시에 표현했다:

1. 메트릭 `application` 태그 값의 집합 (S2)
2. ServiceMonitor 가 매칭하는 K8s Service 의 `metadata.name` 집합 = `up{service=}` 라벨 값 (S6.d)
3. ServiceMonitor `metadata.name` 집합 (S5)

5서비스만 있을 때는 셋의 원소가 같았으므로 `scripts/observability-promql-lint.sh` 와 `scripts/servicemonitor-selector-lint.sh` 가 상수 하나(`EXPECTED_SERVICES` / `CANONICAL`)로 셋을 모두 강제할 수 있었다.

구현 ③ PR3a~PR3d 로 **gateway**(인프라 게이트웨이, 도메인 서비스 아님 — ADR-0010 §5 의 canonical 5 와 분리 취급)가 들어왔고, PR4 가 인증 관측성 S9(ADR-0009 §Decision S9 행, ADR-0013 D5)를 구현한다. S9 의 SSOT 소유자 절반이 **Gateway**(인증 필터·RateLimiter)이므로, gateway 메트릭이 수집되지 않으면 S9 는 "코드에는 있는데 볼 수 없는" 상태가 된다.

그런데 gateway 를 관측 대상에 넣으려는 순간 ADR-0015 의 전제 세 가지가 동시에 깨진다:

- **(a) 세 집합의 원소가 갈라진다.** gateway 의 `application` 태그는 `gateway`(= `spring.application.name`)인데, scrape 대상 Service 의 이름은 `gateway` 일 수 없다 — gateway 의 공개 Service 는 overlay 에서 NodePort/LoadBalancer 로 patch 되므로 관리 포트(8081)를 게시하면 `/actuator/prometheus` 가 인터넷에 노출된다(PR3b 결정 나). 따라서 scrape 전용 ClusterIP Service 를 **별도로** 두어야 하고 그 이름은 `gateway-metrics` 다. 한편 ServiceMonitor 파일은 per-service 1파일 규칙(`k8s/base/services/<dir>/servicemonitor.yml`)을 따르므로 디렉터리명 `gateway` 를 이름으로 갖는다.
- **(b) gateway 에는 S1/S2 가 아예 없다.** gateway 는 WebFlux 전용이라 `:common` 을 의존할 수 없고(servlet 유입), 그 격리 때문에 `peekcart-common-observability` 도 의존하지 않은 상태다 — `MetricsConfig`(S1 histogram bucket)가 적용되지 않고 `management.metrics.tags.application`(S2)도 선언돼 있지 않다. 이 상태로 S6.b(p95) alert 의 regex 에 gateway 를 넣으면 `_bucket` series 가 없어 **NaN** 이 되고, S6.a 에 넣어도 `application` 라벨이 없어 매칭이 0 이다.
- **(c) ADR-0015 §Decision 은 "regex 값 == 5서비스 ground truth"·"S5 = 5서비스 각 1파일"을 계약으로 고정**했다. 6 으로 넓히는 것은 사실 서술의 정정이 아니라 계약 변경이므로, README §14 상 Update Log 로 우회할 수 없다. PR3b 도 이 점 때문에 SM·alert·lint 6 확장을 "PR4 에서 ADR 과 함께" 로 명시 이연했다.

## Decision

**관측성 canonical 집합을 "도메인 5 + 인프라 1(gateway)" 로 확장하되, 단일 ground truth 를 세 집합으로 분리해 각각을 정확 일치로 강제한다.** gateway 는 `peekcart-common-observability` 를 의존해 S1/S2 를 도메인 서비스와 **같은 1개소 계약**으로 충족한다.

### D1 — 세 집합의 분리 (본 ADR 의 핵심)

| 집합 | 정본 값 | 근거 |
|---|---|---|
| **A. `application` 메트릭 태그** | `gateway`, `notification-service`, `order-service`, `payment-service`, `product-service`, `user-service` (6) | 값 = 각 모듈의 `spring.application.name`. gateway 는 `-service` 접미사가 없다(인프라 컴포넌트, `scripts/image-contract-lint.sh` 의 `INFRA_SERVICES` 와 동일 취급) |
| **B. SM 이 매칭하는 Service `metadata.name`** (= `up{service=}`) | `gateway-metrics`, 도메인 5 (6) | ADR-0015 S6.d 의 정의("expected-service set = SM 이 매칭하는 K8s Service 의 `metadata.name` 집합")를 그대로 적용한 결과. gateway 공개 Service(8080 단독)는 SM 이 매칭하지 않으므로 집합에 없다 |
| **C. ServiceMonitor `metadata.name`** | `gateway`, 도메인 5 (6) | per-service 1파일 규칙의 디렉터리명을 따른다 |

**A ≠ B 를 허용하는 것이 결정의 실질이다.** 셋을 억지로 같게 만들려면 (i) 공개 Service 에 8081 을 게시하거나(관리 포트 외부 노출 — PR3b 가 막은 것) (ii) gateway 의 `application` 태그를 `gateway-metrics` 로 바꾸거나(태그가 Service 이름을 흉내내게 되어 `spring.application.name` 과 어긋남) 해야 한다. 둘 다 더 나쁘다.

lint 는 단일 상수 대신 **세 상수**를 갖고, 각 집합을 정확 일치(missing·extra 양쪽 위반)로 검사한다.

### D2 — gateway 의 S1/S2 편입

- gateway 는 `:peekcart-common-observability` 를 의존한다. 그 모듈의 노출 의존은 `spring-boot-starter-actuator` + `micrometer-core`(api) + `spring-tx`(implementation) 로 **servlet/MVC/JPA 가 없어** WebFlux 부팅을 깨지 않는다.
- 루트 가드 `assertGatewayHasNoServletDeps` 의 project 의존 allowlist 를 `:internal-token-contract` + `:peekcart-common-observability` 로 확장한다. 확장이 우회로가 되지 않도록 **allowlist 모듈의 무오염 검사((a2))를 두 모듈 모두에 적용**한다 — 금지 축은 servlet/web/JPA 계열 아티팩트이며, `:peekcart-common-observability` 는 `spring-tx` 를 정당하게 쓰므로 "Spring 전부 금지"가 아니라 **금지 목록 기반**이다(`:internal-token-contract` 는 종전대로 프레임워크 전면 금지 — 순수 Java 상수 모듈이라는 성질이 다르다).
- S1 MeterFilter 재선언 금지(ADR-0009 §Decision S1)는 그대로다. gateway 는 **import-only** 로 충족한다.
- S2 는 gateway 자기 `application.yml` 에 `management.metrics.tags.application: gateway` 로 선언한다(ADR-0015 S2 의 per-service 규칙 동일 적용).

### D3 — alert 계약의 확장

- **S6.a/S6.b**: `application=~` regex 를 집합 A(6값, 사전순)로. `by (application)` grouping 유지.
- **S6.d**: 집합 B 기준으로 `peekcart-scrape-absent-gateway-metrics` rule 을 추가한다(equality matcher, `absent(up{namespace="peekcart", service="gateway-metrics"}) or on() vector(0)`).
- **S6.c**: 변경 없음 — `count by (service)(up{namespace="peekcart"} == 0)` 는 집합에 의존하지 않고 새 target 을 자동 포함한다.
- ADR-0019 의 **식 정본 고정**은 신규 rule 에도 그대로 적용된다(식 변경 = 계약 변경).

### D4 — S9 구현 계약 (ADR-0009 §Decision S9 행의 구체화)

ADR-0009 는 S9 의 owner("Gateway + User")와 금지 규칙("이름 1개소")만 정했다. 본 ADR 이 실제 이름·태그·계측 지점을 확정한다.

| 메트릭 | owner | 태그 | 계측 지점 | 의미 |
|---|---|---|---|---|
| `auth.failure` | gateway | `reason`(bounded enum) | `GatewayAuthenticationFilter.reject()` **단일 지점** | 게이트웨이가 거부한 요청. 401(보안 판정)과 503(의존성 장애)을 `reason` 으로 구분 |
| `auth.ratelimit.rejected` | gateway | `route`(routeId) | `FailClosedRedisRateLimiter.isAllowed()` 의 allowed=false 분기 | **한도 초과(429) 전용**. Redis 장애(판정 불가)는 여기 들어오지 않는다 — 그건 `auth.failure{reason=rate_limiter_unavailable}` 이다 |
| `auth.forbidden` | gateway | `route` | 다운스트림 응답 status==403 관측(전역 post-filter) | 인가 실패. gateway 는 authz 를 하지 않으므로 값은 전부 리소스 서비스의 판정이다 |
| `auth.token.reuse.detected` | user-service | — | `AuthService.detectReuse()` 단일 수렴점 | refresh token reuse 감지 |
| `auth.logout` | user-service | — | `AuthService.logout()` | 로그아웃(family deny + refresh 무효화) |

- **`reason` 은 예외 message 가 아니라 enum 이다.** message 를 태그로 쓰면 사용자 입력 파편이 라벨이 되어 카디널리티가 폭발한다. 값 집합: `missing_token`·`malformed`·`bad_signature`·`expired`·`unknown_kid`·`alg_not_allowed`·`missing_exp`·`denied`·`internal_token_refused`·`dependency_unavailable`·`rate_limiter_unavailable`.
- **user-service 의 두 카운터는 커밋 이후에만 증가한다**(`CommitAwareMetrics`) — 원장 revoke 가 롤백됐는데 카운터만 남으면 alert 의 근거가 거짓이 된다.
- **403 을 서비스 5곳에 복제하지 않는다** — "이름 1개소"(ADR-0009 S9) 계약을 지키고, gateway 를 통과하지 않은 직접 경로는 애초에 NetworkPolicy 가 막는 축이다.

### D5 — ADR-0015 무효화 범위 (Partially Superseded by ADR-0024)

**무효화되는 것**: §Decision 정정 표의 "canonical 5 **정확 일치**" 전제에 한정한다 —
1. S5 의 "5서비스 각 1파일" → **도메인 5 + 인프라 1(gateway)**
2. S6.a/b 의 "regex 값 == **5서비스** ground truth" → **집합 A(6)**
3. S6.d 의 "5서비스 equality matcher rule 분할" → **집합 B(6)** 기준 분할
4. 검증 수단이 단일 상수(`EXPECTED_SERVICES`/`CANONICAL`)로 세 집합을 동시에 강제한다는 전제

**무효화되지 않는 것**: per-service SSOT 위치(S1 공유 owner·S2 자기 yml·S5 per-service 1파일 구조), S6.d 의 **정의**("expected-service set = SM 이 매칭하는 Service name 집합" — 본 ADR 은 이 정의를 뒤집는 게 아니라 그대로 적용해 `gateway-metrics` 를 얻는다), `by (application)` grouping, 무필터 금지·단일 equality 금지 규칙, ADR-0019 의 식 정본 고정.

## Alternatives Considered

### Alternative A: gateway 를 관측 대상에서 계속 제외 (현 상태 유지)
- **장점**: 변경 0. lint 3종·alert·dashboard 를 건드리지 않는다.
- **단점**: S9 SSOT 의 절반(Gateway 인증 필터·RateLimiter)이 수집되지 않는다 — 메트릭은 코드에 있는데 Prometheus 에 없다. 외부 진입점의 5xx·지연·인증 거부가 관측 사각지대로 남는다.
- **기각 사유**: PR4 의 목적 자체가 S9 다. 제외하면 "구현했으나 볼 수 없음" 상태를 계약으로 굳힌다.

### Alternative B: 세 집합을 억지로 일치시킨다
- **B-1 공개 Service 에 8081 을 함께 게시**: `up{service="gateway"}` 로 통일된다. 그러나 overlay 가 그 Service 를 NodePort/LoadBalancer 로 patch 하므로 관리 포트가 외부로 나간다 — PR3a 의 포트 분리와 PR3b 결정 나를 정면으로 되돌린다. **기각**.
- **B-2 gateway 의 `application` 태그를 `gateway-metrics` 로**: 태그가 `spring.application.name` 과 어긋나 "메트릭 태그 값 = 서비스 자기 이름"(ADR-0015 S2) 규칙이 깨지고, 로그·트레이스의 서비스 식별자와도 갈라진다. **기각**.

### Alternative C: gateway 모듈에 MeterFilter 를 재선언 (의존 추가 없이 S1 충족)
- **장점**: 의존 그래프에 예외를 만들지 않는다. WebFlux 격리 규칙이 `:internal-token-contract` 단일 예외로 유지된다.
- **단점**: ADR-0009 §Decision S1 의 "서비스 모듈에 `MeterRegistryCustomizer` 재선언 금지"를 정면으로 어긴다. bucket 정책이 두 곳에 생기면 한쪽만 바뀌는 드리프트가 열리고, 그 드리프트는 p95 패널이 서비스마다 다르게 보일 때까지 조용하다.
- **기각 사유**: allowlist 항목 1개 추가(+ 무오염 검사 확장)가 계약 위반보다 싸다. `peekcart-common-observability` 가 servlet 을 물면 빌드가 실패하므로 예외가 방치되지도 않는다.

### Alternative D: gateway 전용 alert/dashboard 축을 따로 둔다 (도메인 5 는 그대로)
- **장점**: ADR-0015 를 건드리지 않는다.
- **단점**: error-rate/latency 는 서비스 종류와 무관한 공통 임계인데 rule 이 둘로 갈라진다. 신규 인프라 컴포넌트마다 축이 하나씩 늘고, "전체 5xx" 를 보려면 두 패널을 합쳐 읽어야 한다.
- **기각 사유**: ADR-0015 가 Alternative B(per-service rule 복제)를 기각한 것과 같은 이유 — `by (application)` 단일 rule 이 더 단순하다. 축을 가르는 비용이 ADR 1개를 쓰는 비용보다 크다.

## Consequences

### 긍정적 영향
- 외부 진입점(gateway)의 5xx·p95·인증 거부가 도메인 서비스와 **같은 rule·같은 대시보드**에서 보인다.
- 세 집합이 분리되면서 lint 의 검사 의미가 명확해진다 — 지금까지는 "5" 라는 숫자가 우연히 세 가지를 동시에 맞히고 있었고, 어느 하나가 달라지는 순간 오탐할 예정이었다.
- S9 의 메트릭 이름·태그가 ADR 에 고정되어 "이름 1개소" 규칙이 검증 가능한 형태가 된다.

### 부정적 영향 / 트레이드오프
- **신규 컴포넌트 추가 비용이 늘었다**: 집합 A/B/C 세 곳 + alert regex + scrape-absent rule + dashboard 변수를 동기해야 한다(종전 5→6 한 곳이 아니다). lint 가 전부 정확 일치라 누락은 CI 에서 red 로 드러난다.
- **WebFlux 격리 예외가 2개로 늘었다**. 무오염 검사가 방어하지만, "gateway 가 의존해도 되는 모듈" 목록이 길어지면 그 자체가 압력이 된다.
- gateway 관리 포트(8081)는 여전히 네임스페이스 내부에서 NetworkPolicy 로 좁혀져 있지 않다 — gateway Pod 는 `component: gateway` 라 기존 NP(`component: backend`)의 대상이 아니다. 본 ADR 은 이 상태를 바꾸지 않으며, 좁히려면 ADR-0013 D3 신뢰 경계의 확장이 필요하다.

### 후속 결정에 미치는 영향
- 향후 인프라 컴포넌트(예: 별도 BFF, 이벤트 게이트웨이)를 관측 대상에 넣을 때 본 ADR 의 A/B/C 분리를 그대로 따른다 — 새 ADR 없이 집합에 원소를 추가한다.
- auth failure **rate** alert 는 baseline 부재로 본 ADR 범위 밖이다(reuse 감지만 "발생 자체가 사건"이라 alert 화). 기저율이 쌓이면 별도 결정으로 올린다.
- 실 클러스터 scrape 증적(SM 이 실제로 8081 을 긁는지)은 PR3d-b-2 와 같은 세션에서 확인한다.

## References
- ADR-0009 (관측성 계약 SSOT — §Decision S9 행이 본 ADR D4 의 상위 계약)
- ADR-0015 (per-service 계약 — 본 ADR 이 canonical 5 정확일치 범위를 Partially Supersede)
- ADR-0019 (alert 식 정본 고정 — 신규 rule 에도 적용)
- ADR-0010 §5 (도메인 5 경계) · ADR-0013 D3/D5 (Gateway 보안·관측성) · ADR-0017 (내부 토큰)
- 계획서: `docs/plans/task-impl3-pr4-auth-observability.md`
- 코드: `scripts/observability-promql-lint.sh`, `scripts/servicemonitor-selector-lint.sh`, `scripts/gateway-exposure-lint.sh`, `build.gradle`(`assertGatewayHasNoServletDeps`), `k8s/base/services/gateway/`, `k8s/monitoring/shared/grafana-alerts.yml`
