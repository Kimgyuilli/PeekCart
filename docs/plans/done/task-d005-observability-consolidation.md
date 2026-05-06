# task-d005-observability-consolidation — 관측성 계약 강제 메커니즘 격상 (D-005 잔여 리스크 해결)

> 작성: 2026-05-05
> 관련 Phase: Phase 3 잔여 부채 (Phase 4 MSA 분리 진입 전 처리)
> 관련 부채: D-005 (Observability) — `docs/TASKS.md` Tech Debt 표
> 선행 ADR: **ADR-0009** (관측성 계약 SSOT, Accepted 2026-05-04). 본 task 는 ADR-0009 §Decision 표 6번째 컬럼 ("검증 수단") 의 D5-V1~V6 action 을 1:1 매핑 (단, **D5-V6 는 라벨 invariant 만 부분 격상** — PromQL syntax check 는 §7 R1 트레이드오프로 비대상).
> 후속: 표의 "Phase 4 owner" 컬럼대로의 surface 물리적 이동은 Phase 4 멀티모듈 분리 task 에서 ADR-0009 인용 — 본 task 비대상.

## 1. 목표

ADR-0009 가 9 surface (S1~S5 + S6.a~d) 의 SSOT 위치를 결정만 박아두고 코드/매니페스트는 0건 변경한 상태다. 본 task 는 결정이 *위반되었을 때 자동 검출되도록* 강제 메커니즘을 격상한다 — lint script + 회귀 테스트 + CI 통합. **surface 의 물리적 위치는 변경하지 않으며, Phase 4 owner 컬럼대로의 이동은 Phase 4 task 가 수행**.

세부 목표 (ADR-0009 §Decision 표 6번째 컬럼 ↔ action id 매핑 — D5-V1~V5 는 1:1, D5-V6 는 부분 격상):

- **(D5-V1)** SSOT 위치 위반 정적 검증 — base `application.yml` 가 SSOT 인 키 (`management.metrics.tags.application`, `management.endpoints.web.exposure.include`) 가 다른 yaml 에 재선언됐는지 grep 기반 검출. ADR-0007 회색지대 키 (`probes.enabled`, `health.show-details`) 는 화이트리스트.
- **(D5-V2)** 중복 재선언/복제 정적 검증 — `MeterFilter` 정의 또는 `MeterRegistryCustomizer` 가 `MetricsConfig.java` 외 다른 클래스에 동일 의도로 선언됐는지 검출. application 태그 값 불일치 (`peekcart` 외 값) 도 검출.
- **(D5-V3)** S3 (actuator exposure) whitelist 정확도 격상 — 정확히 `health,prometheus` 만 노출되고 그 외 endpoint (`/actuator/info`, `/actuator/env`) 는 미노출 회귀 검증.
- **(D5-V4)** S4 `/actuator/health/**` 경로 보안 회귀 검증 — `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` 가 인증 없이 200 응답하는지 검증 (현재 `/actuator/prometheus` 만 검증).
- **(D5-V5)** S5 ServiceMonitor selector 매칭 정합성 검증 — `kubectl kustomize` 산출물에서 ServiceMonitor `spec.selector.matchLabels` 가 같은 namespace 의 Service `metadata.labels` 와 매칭되는지, `endpoints[].port: http` 가 Service `spec.ports[].name: http` 와 일치하는지 정적 검증.
- **(D5-V6, 부분)** S6 (Grafana alerts) PromQL 라벨 invariant lint — 4 alert 의 PromQL 에서 추출한 `application=` / `namespace=` / `service=` 라벨 값이 S2 (application.yml 의 태그 값) / S5 (ServiceMonitor namespace + Service name) 와 정확히 일치하는지 검증. **ADR-0009 §Decision S6 검증 수단 중 "PromQL syntax" 절반은 본 task 비대상** — §7 R1 의 도입 비용 트레이드오프 (`promtool promql format` 은 experimental 기능, CI 에 promtool 설치 추가 + Grafana ConfigMap 에서 expr 추출 후 별도 검증 비용을 본 detection 범위 밖) 사유로 기각. 잔여는 §8 후속 항목으로 등록.

본 task 는 **detection 강제 메커니즘만** 도입한다. 현 트리는 위반 0건이므로 lint 도입 후 즉시 green. 의도적 위반을 일시 주입한 negative test 로 lint 동작 자체를 PR 본문에서 1회 증빙한다.

## 2. 배경 / 제약

### ADR-0009 §Decision 표 발췌 (본 task 가 다루는 컬럼)

| # | Surface | 현 SSOT (파일:라인) | 본 task action | 검증 수단 (격상 후) |
|---|---------|---------------------|----------------|---------------------|
| S1 | histogram bucket | `MetricsConfig.java:17-37` | D5-V2 보호 | 기존 `_bucket` substring + D5-V2 중복 재선언 검출 |
| S2 | metrics tags | `application.yml:38-40` | D5-V1 + D5-V2 보호 | 기존 `application="peekcart"` substring + D5-V1 위치 위반 + D5-V2 값 불일치 |
| S3 | exposure 화이트리스트 | `application.yml:33-37` | D5-V1 + D5-V3 격상 | D5-V1 위치 + D5-V3 `health,prometheus` 외 미노출 회귀 |
| S4 | actuator 보안 | `SecurityConfig.java:47-48` | D5-V4 격상 | D5-V4 `/actuator/health/**` 경로 200 회귀 |
| S5 | scrape 설정 | `servicemonitor.yml:6-20` | D5-V5 격상 | D5-V5 selector ↔ Service label/port 매칭 정적 검증 |
| S6.a~d | alerts | `grafana-alerts.yml:17-137` | D5-V6 격상 | D5-V6 라벨 invariant (PromQL 의 application/namespace/service 값 ↔ S2/S5) |

> 본 task 는 ADR-0009 §Decision 표 4번째 컬럼 ("본 task 변경") 이 모든 행에서 "없음" 인 상태를 유지한다 — surface 의 *위치* 는 변경 없음. 5번째 컬럼 ("Phase 4 owner") 이행도 비대상.

### 도구 선택 트레이드오프

- **bash + python3 inline (`pyyaml` 사용)** vs Gradle/Java 통합 vs 전용 lint 도구 (`promtool`, `ajv`)
- 채택: **bash + python3 inline + `kubectl kustomize`**. 이유: (a) lint 가 yaml/k8s 매니페스트 다중 파일을 횡단하므로 Java/Gradle 슬라이스로 묶기 부적합, (b) `scripts/timeout_wrapper.py` / `.claude/scripts/shared-logic.sh` 패턴과 정합 — 프로젝트는 이미 bash + python inline 채택, (c) `promtool` 의 `promql format` (PromQL parse) 은 experimental 기능 이고 Prometheus `check rules` 는 rules 파일 형식만 지원 (Grafana alert ConfigMap 과 schema 불일치 → expr 추출 후 별도 검증 필요) — 본 task detection 범위 대비 도입 비용 비대칭으로 ROI 낮음.
- 잔여: `pyyaml` 의존. macOS / GitHub Actions ubuntu runner 의 `python3` 표준 라이브러리에 미포함. 대안 (i) 정규식 + grep, (ii) `pip install pyyaml` CI 단계 추가. 채택: **(ii)** — 정규식은 다중 줄/들여쓰기에서 false positive 위험.

### 현 상태 ground truth (위반 0건)

```
$ grep -rn "MeterRegistryCustomizer\|MeterFilter" src/main/
 → MetricsConfig.java 만
$ grep -rn "application: peekcart\|application=peekcart" src/main/
 → application.yml:40 만
$ grep -rn "exposure" src/main/resources/
 → application.yml:33-37 만 (application-k8s.yml 에 재선언 없음)
```

→ lint 도입 후 즉시 green. negative test 는 PR 본문에서 임시 주입 → exit≠0 확인 → revert 형태로 1회 증빙.

### CI 단일 job 통합

`.github/workflows/ci.yml` 은 단일 `build` job 으로 `./gradlew build` 만 실행. lint 는 동일 job 의 새 step (`Run observability lints`) 으로 **`./gradlew build` 이전** 에 추가 — 별도 job 분리 시 checkout/setup 비용 중복. 정책 위반은 build (수분) 비용 부담 전 빠르게 fail. lint step 이 non-zero 종료 시 동일 job 의 후속 step 들은 (default `if: success()` 정책으로) 건너뛰어지므로 build/test 와 동급 fail. 단 `upload-artifact` 는 기존 `if: always()` 라 lint 실패 시에도 (그 시점까지의) 산출물을 업로드 — 의도적 유지 (디버깅 가치).

### 비대상

- **surface 의 물리적 이동/공통 모듈 추출**: ADR-0009 §Decision 표 5번째 컬럼 이행은 Phase 4 멀티모듈 분리 task 가 본 ADR 인용하여 수행.
- **`management.endpoint.health.show-details: never` 의 base 이동 결정**: ADR-0009 §회색지대 분류 의 후속 검토 항목. 본 task 는 D5-V1 화이트리스트로만 처리.
- **PromQL syntax check (D5-V6 의 절반)**: 트레이드오프 §7 R1.
- **실 k8s 클러스터에서의 ServiceMonitor 동작 검증**: D5-V5 는 정적 검증 한정 (Phase 3 환경 종결, 클러스터 미보유).
- **D-002 / D-008**: 본 task 와 무관.

## 3. 작업 항목

- [ ] **P1.** D5-V1 SSOT 위치 위반 정적 lint — `scripts/observability-ssot-lint.sh` 신규 (전반부)
- [ ] **P2.** D5-V2 중복 재선언/복제 정적 lint — 같은 스크립트 후반부 (P1 와 1파일)
- [ ] **P3.** D5-V3 actuator exposure whitelist 정확도 — `ObservabilityMetricsIntegrationTest` 에 method 1건 추가
- [ ] **P4.** D5-V4 `/actuator/health/**` 경로 보안 회귀 — 같은 클래스에 method 1건 추가
- [ ] **P5.** D5-V5 ServiceMonitor selector 매칭 정합성 — `scripts/servicemonitor-selector-lint.sh` 신규
- [ ] **P6.** D5-V6 PromQL 라벨 invariant lint — `scripts/observability-promql-lint.sh` 신규
- [ ] **P7.** CI 통합 — `.github/workflows/ci.yml` 에 의존성 설치 + kubectl 설치 + lint 실행 step 3건 추가
- [ ] **P8.** 문서 동기화 — TASKS.md D-005 행 + 완료 표 + PHASE3.md 엔트리

### P1 상세 — D5-V1 SSOT 위치 위반 정적 lint

신규 파일 `scripts/observability-ssot-lint.sh`. 전반부 — SSOT 위치 위반 검출.

검사 대상 키 (ADR-0009 §Decision 행 S2, S3 ↔ base `application.yml` SSOT):
- `management.metrics.tags.application`
- `management.endpoints.web.exposure.include`

알고리즘:
1. `python3` + `pyyaml` 으로 모든 `src/main/resources/application*.yml` 을 yaml 파싱
2. 각 파일에서 위 키 경로의 존재 여부 확인
3. base (`application.yml`) 외에 1건이라도 있으면 violation → stderr + exit 1
4. ADR-0007 회색지대 키 (`management.endpoint.health.probes.enabled`, `management.endpoint.health.show-details`, `logging.level.*`, `spring.jpa.show-sql`) 는 화이트리스트 — 검사 대상 외

출력 형식 (위반 시):
```
[D5-V1] SSOT location violation:
  key: management.metrics.tags.application
  base SSOT: src/main/resources/application.yml
  violating files: src/main/resources/application-k8s.yml
  → ADR-0009 §Decision S2: base application.yml SSOT only.
```

### P2 상세 — D5-V2 중복 재선언/복제 정적 lint

`scripts/observability-ssot-lint.sh` 의 후반부 (같은 스크립트 1파일 유지).

검사:
- (a) `MeterRegistryCustomizer<MeterRegistry>` 또는 `MeterFilter` 의 anonymous inner class 사용이 `src/main/java/com/peekcart/global/config/MetricsConfig.java` 외 다른 파일에 있는지 grep
- (b) yaml 파싱한 `management.metrics.tags.application` 의 *값* 이 `peekcart` 외 값으로 다른 파일에 있는지 (P1 의 위치 위반 검사보다 강함 — 같은 파일 내 override 도 검출)

출력 형식:
```
[D5-V2] Duplicate declaration:
  surface: S1 (MeterRegistryCustomizer for http.server.requests histogram)
  expected single owner: src/main/java/com/peekcart/global/config/MetricsConfig.java
  also found in: src/main/java/com/peekcart/.../OtherConfig.java:NN
  → ADR-0009 §Decision S1: 이동·복제 금지 규칙 위반.
```

### P3 상세 — D5-V3 actuator exposure whitelist 정확도

`src/test/java/com/peekcart/global/observability/ObservabilityMetricsIntegrationTest.java` 에 새 `@Test` 메서드 추가:

```java
@Test
@DisplayName("actuator exposure 화이트리스트가 정확히 health, prometheus 만 노출한다 (D5-V3)")
void actuatorExposure_whitelistsExactlyHealthAndPrometheus() {
    // health: 200 (whitelisted)
    assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    // prometheus: 200 (whitelisted)
    assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    // info: 404 (not whitelisted)
    assertThat(restTemplate.getForEntity("/actuator/info", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    // env: 404
    assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
}
```

> Spring Boot 기본 동작: exposure include 에 없는 endpoint 는 actuator 자체에서 404. SecurityConfig (S4) 의 permitAll 과 무관하게 actuator 가 endpoint 자체를 비활성화. 따라서 401 이 아닌 404 가 정상.

### P4 상세 — D5-V4 `/actuator/health/**` 경로 보안 회귀

같은 클래스에 새 `@Test` 메서드 추가. **probes.enabled 활성화 방식은 `@TestPropertySource` 로 고정** (`application-test.yml` 추가는 비채택 — ADR-0007/0009 회색지대 재결정 회피):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")
@Testcontainers
@DisplayName("관측성 계약 회귀 테스트 (D-001/D-005 재발 방지)")
class ObservabilityMetricsIntegrationTest extends AbstractIntegrationTest {
    // ... 기존 필드 + 메서드

    @Test
    @DisplayName("/actuator/health/** 가 인증 없이 200 응답한다 (D5-V4 — K8s liveness/readiness Probe 의존)")
    void actuatorHealth_noAuthRequired() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
```

> 회귀 의도: `application-k8s.yml:17-18` 의 `probes.enabled: true` 가 SecurityConfig (S4) 의 PUBLIC_URLS `/actuator/health/**` 패턴과 함께 동작. 둘 중 하나가 깨지면 K8s liveness/readiness Probe 가 502/401 로 fail → Pod restart loop.
>
> **고정 사유**: `application-test.yml` 에 management 정책을 추가하면 ADR-0007 의 test profile 예외 범위가 (현재 JWT 만 — `0007:97`) 확장되며, ADR-0009 §회색지대 분류의 "재결정하지 않음" 약속도 우회. `@TestPropertySource` 는 단일 테스트 클래스 한정 주입이라 회색지대를 흔들지 않음.

### P5 상세 — D5-V5 ServiceMonitor selector 매칭 정합성

신규 파일 `scripts/servicemonitor-selector-lint.sh`. `kubectl kustomize` 를 양 overlay (`minikube`, `gke`) 로 빌드 → 산출물에서 ServiceMonitor 와 Service 를 추출 → 매칭 검증.

알고리즘:
1. `kubectl kustomize k8s/overlays/minikube/` (그리고 `gke/`) 출력을 python3 으로 yaml 파싱 (multi-doc)
2. 각 ServiceMonitor 마다:
   - `spec.namespaceSelector.matchNames` 의 ns 들 과 `spec.selector.matchLabels` 와 매칭되는 Service 가 산출물에 존재하는지 (label 정확히 일치하는 Service 가 있어야 함)
   - 매칭된 Service 의 `spec.ports[].name` 에 ServiceMonitor `spec.endpoints[].port` 값이 포함되는지
3. 미매칭 시 violation + exit 1

`kubectl` 미존재 환경 (e.g. 로컬에 kubectl 미설치) 대비: `command -v kubectl` 체크 후 미존재 시 skip 메시지 + exit 0 (lint 가 환경 의존으로 spurious 실패하지 않게). CI ubuntu runner 는 `kubectl` 설치 step 추가 (P7).

### P6 상세 — D5-V6 PromQL 라벨 invariant lint

신규 파일 `scripts/observability-promql-lint.sh`.

**Required-label matrix** (alert uid 별 — ADR-0009 §Context 표 L23-26 의 의존 surface 정의에서 도출):

| Alert uid | 필수 라벨 | 의존 surface | 부재 시 의미 |
|-----------|-----------|---------------|--------------|
| `peekcart-high-error-rate` (S6.a) | `application` | S2 | application 라벨 부재 시 multi-service 분리 후 cross-service 합산으로 오발화 |
| `peekcart-slow-response` (S6.b) | `application` | S2 | 동상 (S6.a) |
| `peekcart-target-down` (S6.c) | `namespace`, `service` | S5 | scrape target 식별 불가 → `up==0` 조건이 cluster 전체 target 대상으로 확대 |
| `peekcart-scrape-absent` (S6.d) | `namespace`, `service` | S5 | `absent()` 가 항상 false → 알림 자체 무력화 |

알고리즘:
1. `k8s/monitoring/shared/grafana-alerts.yml` 을 python3 + pyyaml 으로 파싱 → ConfigMap data 의 `alerts.yaml` 문자열 → 다시 yaml 파싱 → 각 rule 의 `uid` 와 `data[].model.expr` (PromQL 문자열들) 추출
2. 각 rule 의 PromQL 에 대해 위 matrix 의 **필수 라벨 presence 검증** 먼저:
   - matrix 가 요구하는 라벨이 PromQL 에 *전혀 없으면* violation (`required label absent`)
3. presence 통과 후 각 라벨의 **value 일치 검증**:
   - `application="VAL"` → S2 ground truth (`application.yml` 의 `management.metrics.tags.application`) 와 일치
   - `namespace="VAL"` → S5 ServiceMonitor 의 `metadata.namespace` 와 일치
   - `service="VAL"` → S5 ServiceMonitor `spec.selector.matchLabels.app` (= Service name) 와 일치
4. matrix 외 라벨 (예: S6.a/b 의 PromQL 에서 `namespace=` 가 *추가* 로 있다면) 은 검사 안 함 — alert 별 의존 surface 외 라벨은 자유.
5. 불일치/부재 시 violation + exit 1. 모든 rule 의 모든 라벨을 누적 검사 후 마지막에 통합 보고 (early exit 안 함 — negative test 시 한 번에 모든 violation 가시화).

출력 형식 (예):
```
[D5-V6] PromQL label invariant violation:
  rule uid: peekcart-high-error-rate
  PromQL: sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  required label absent: application
  → ADR-0009 §Context S6.a: application 의존 (S2). label 부재는 cross-service 오발화 위험.
```

PromQL syntax check (전체 expression parse) 는 비대상 — 트레이드오프 §7 R1.

### P7 상세 — CI 통합

`.github/workflows/ci.yml` 의 `steps:` 에 새 step 3 개 추가 (위치: `chmod +x gradlew` 다음, **`./gradlew build` 이전**):

```yaml
- name: Install lint dependencies
  run: |
    python3 -m pip install --user pyyaml

- name: Set up kubectl (for ServiceMonitor lint)
  uses: azure/setup-kubectl@v4

- name: Run observability lints
  run: |
    bash scripts/observability-ssot-lint.sh
    bash scripts/servicemonitor-selector-lint.sh
    bash scripts/observability-promql-lint.sh
```

> 위치 사유: 정책 위반을 build (수분) 비용 부담 전 빠르게 fail. lint step 의 non-zero 종료 시 후속 `./gradlew build` 등은 default `if: success()` 정책으로 skip, 동일 job 이 fail 처리됨. `upload-artifact` 는 기존 `if: always()` 라 lint 실패 시에도 (build 미실행 → reports 비어있음) 빈 artifact 업로드 — 디버깅 가치 유지.

### P8 상세 — 문서 동기화

- `docs/TASKS.md` 의 D-005 행 우선순위 컬럼 `중간 — 1차 봉합 완료, 잔여 리스크` → **`해결됨`** (취소선 + bold). 영향 컬럼에 본 task 식별자 + 6 action 격상 결과 1줄 추가.
- `docs/TASKS.md` "완료된 작업" 표 새 행 (날짜 = task 종결일).
- `docs/progress/PHASE3.md` 새 엔트리 — 6 action 별 산출물 명시.
- `docs/02-architecture.md` 변경 **없음** — `scripts/` 디렉토리는 §12 패키지 구조 비대상 (`scripts/timeout_wrapper.py` 가 §12 미언급 상태로 존재).
- `CLAUDE.md` 변경 **없음** — ADR-0009 참조 라인은 ADR task 에서 추가됨.
- `bash docs/consistency-hints.sh` exit 0 통과.

## 4. 영향 파일

### 신규

- `scripts/observability-ssot-lint.sh` (P1 + P2)
- `scripts/servicemonitor-selector-lint.sh` (P5)
- `scripts/observability-promql-lint.sh` (P6)

### 수정

- `src/test/java/com/peekcart/global/observability/ObservabilityMetricsIntegrationTest.java` (P3 + P4: 2 method 추가 + 클래스 레벨 `@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")` 추가)
- `.github/workflows/ci.yml` (P7: 3 step 추가)
- `docs/TASKS.md` (P8: D-005 행 + 완료 표)
- `docs/progress/PHASE3.md` (P8: 새 엔트리)

### 명시적 비변경 (ADR-0009 §Decision 표 4번째 컬럼 = "없음" 부동성 검증 대상)

- `src/main/java/com/peekcart/global/config/MetricsConfig.java` (S1)
- `src/main/resources/application.yml` (S2 + S3 base)
- `src/main/resources/application-k8s.yml` (S2/S3 회색지대 키 — 수정 안 함)
- `src/main/java/com/peekcart/global/config/SecurityConfig.java` (S4)
- `k8s/base/services/peekcart/servicemonitor.yml` (S5)
- `k8s/monitoring/shared/grafana-alerts.yml` (S6.a~d)

## 5. 검증 방법

### 자동

- `./gradlew test` — 244 + 신규 2건 (P3 + P4 = 2 method) = **246건 통과**, 실패 0건
- `bash scripts/observability-ssot-lint.sh` exit 0 (현 트리 위반 0건)
- `bash scripts/servicemonitor-selector-lint.sh` exit 0 (양 overlay kustomize 빌드 + 매칭 OK)
- `bash scripts/observability-promql-lint.sh` exit 0 (4 PromQL 의 라벨 일치)
- `bash docs/consistency-hints.sh` exit 0
- GitHub Actions `build` job 통과 (lint step 포함)

### 수동 (PR 본문 1회 증빙 — negative test, detector branch 단위)

각 lint 의 detection 동작을 *모든 detector branch 별로* 의도적 위반 주입 → exit 1 확인 → revert 형태로 PR 본문 §검증 섹션에 명령 + exit code 첨부:

- **D5-V1** (2 branch — 두 키 각각):
  - (a) `application-k8s.yml` 에 `management.metrics.tags.application: foo` 임시 주입 → ssot-lint exit 1 → revert
  - (b) `application-k8s.yml` 에 `management.endpoints.web.exposure.include: health` 임시 주입 → ssot-lint exit 1 → revert
- **D5-V2** (2 branch — Java 중복 + yaml 값 불일치):
  - (a) 임시 `OtherMetricsConfig.java` 에 `MeterRegistryCustomizer` 추가 → ssot-lint exit 1 → revert
  - (b) **base `src/main/resources/application.yml` 의 `application: peekcart` → `application: other` 임시 변경** → ssot-lint exit 1 → revert (값 불일치 detector branch 1:1 증빙. "다른 yaml 에 추가" 변형은 D5-V1 location violation 도 동시 유발하므로 D5-V2 단독 증빙 부적절 — D5-V1 case 로만 잔존)
- **D5-V5** (2 branch — selector + port):
  - (a) ServiceMonitor `selector.matchLabels.app: peekcart` → `app: bogus` 임시 변경 → selector-lint exit 1 → revert
  - (b) ServiceMonitor `endpoints[0].port: http` → `port: bogus` 임시 변경 → selector-lint exit 1 → revert
- **D5-V6** (총 5 branch — value mismatch 3 + label absence 2 family):
  - **value mismatch** (matrix 의 라벨 *값* 불일치):
    - (a) `grafana-alerts.yml` 의 `application="peekcart"` → `application="other"` 임시 변경 → promql-lint exit 1 (value mismatch on application) → revert
    - (b) `namespace="peekcart"` → `namespace="other"` 임시 변경 → promql-lint exit 1 (value mismatch on namespace) → revert
    - (c) `service="peekcart"` → `service="other"` 임시 변경 → promql-lint exit 1 (value mismatch on service) → revert
  - **label absence** (alert uid 의 필수 라벨 자체 제거):
    - (d) S6.a (`peekcart-high-error-rate`) 의 PromQL 에서 `application="peekcart",` 부분을 통째로 제거 → promql-lint exit 1 (`required label absent: application` for uid `peekcart-high-error-rate`) → revert. S6.b 도 동일 검증 — (d) 한 케이스로 application 부재 family 대표.
    - (e) S6.c (`peekcart-target-down`) 의 PromQL 에서 `namespace="peekcart"` 또는 `service="peekcart"` 중 하나 제거 → promql-lint exit 1 (`required label absent: namespace` or `service` for uid `peekcart-target-down`) → revert. S6.d 도 동일 — (e) 한 케이스로 namespace/service 부재 family 대표.
- **D5-V3 / D5-V4** (통합 테스트 자동 회귀): negative 별도 PR 본문 증빙 불필요. 회귀는 각 `@Test` 메서드의 assertion 으로 자동 강제. 의도적 위반 (예: `exposure.include: health,prometheus,info` 변경 → `/actuator/info` 가 200) 시 `./gradlew test` 가 자동 fail — 회귀 자체가 detector. 단 PR 본문 §검증 부록에 한 줄 "P3/P4 는 통합 테스트 assertion 으로 회귀 자동 강제" 명시.

### 일관성 (ADR-0009 ↔ plan 매핑)

- ADR-0009 §Decision 표의 D5-V1~V6 6건 ↔ 본 plan §3 P1~P6 의 1:1 매핑이 §1 매핑 표로 표현됨
- ADR-0009 §Decision 표 4번째 컬럼 ("본 task 변경") 의 모든 값 = "없음" → §4 명시적 비변경 목록과 일치

## 6. 완료 조건

다음 모두 충족 시 task 완료:

1. **P1~P8 8 항목 모두 체크** (§3 작업 항목 체크박스)
2. **테스트**: `./gradlew test` 246건 통과 (244 + 2 신규)
3. **lint positive**: 3 lint script 가 현 트리에서 exit 0
4. **lint negative (detector branch 단위, 총 11건)**: D5-V1 (2 branch) + D5-V2 (2 branch) + D5-V5 (2 branch) + D5-V6 (5 branch — value mismatch 3 + label absence 2 family 대표) = 11 negative case 모두 exit 1 동작이 PR 본문에 명령 + exit code 형태로 첨부됨. D5-V3/V4 는 통합 테스트 assertion 으로 자동 회귀 강제 — negative 증빙 불필요 (PR 본문 §검증 부록에 1줄 명시)
5. **CI**: GitHub Actions `build` job 통과 (lint step 포함)
6. **문서**: TASKS.md D-005 행 우선순위 = `해결됨`, "완료된 작업" 표 새 행, PHASE3.md 엔트리 추가
7. **부동성**: ADR-0009 §Decision 표 4번째 컬럼 ("본 task 변경") 의 모든 값이 "없음" 그대로 — §4 명시적 비변경 파일 6건 변경 0건 (`git diff --stat` 으로 확인)
8. **일관성**: `bash docs/consistency-hints.sh` exit 0
9. **D5-V6 부분 격상 잔여**: PromQL syntax check (ADR-0009 §Decision S6 검증 수단의 절반) 가 §8 후속 항목으로 등록됨 — 본 task 의 미이행 잔여로 plan §8 + PHASE3 엔트리에 명시
10. **PR**: GitHub PR 생성 + main 머지

## 7. 트레이드오프 및 결정 근거

### R1. PromQL syntax check 부재 (D5-V6 의 절반만)

- ADR-0009 §Decision S6 검증 수단이 "PromQL syntax + 입력 series 가정 lint" 인데 본 task 는 후자 (라벨 invariant) 만 강제, 전자는 비대상.
- 이유 (도구 부재가 아닌 도입 비용 트레이드오프): `promtool promql format <expr>` 명령은 공식 문서에 존재하나 **experimental 기능** (`--experimental` flag 필수). 채택 시 (a) CI runner 에 promtool 설치 step 추가, (b) `grafana-alerts.yml` 의 ConfigMap data 에서 PromQL expr 을 추출 → 각 expr 마다 promtool 호출, (c) experimental flag 의존 — Prometheus 버전 변경 시 lint 깨질 위험. `promtool check rules` 는 Prometheus rules 파일 형식만 지원하여 Grafana alert ConfigMap 과 schema 다름. Python `prometheus_client` 는 metrics 파싱 전용으로 expression parse 미지원. 본 task 의 detection 격상 범위 (라벨 invariant) 대비 syntax 도입 비용 비대칭.
- 기각 결과: syntax 오류는 Grafana 가 alert 생성 시점에 fail → 운영에서 즉시 발각. invariant lint 가 더 *조용한 실패* (라벨 오타) 를 잡으므로 우선순위 높음.
- 향후 재검토 트리거: Phase 4 OpenTelemetry 도입 시 alert 수가 증가하면 syntax check 도입 ROI 재평가.

### R2. D5-V5 의 정적 검증 한정 — 실 cluster 동작 미보장

- `kubectl kustomize` 산출물 + label 매칭 정적 검증만 수행. Prometheus operator 가 ServiceMonitor CRD 를 실제로 watch 하여 scrape config 를 생성하는 동작은 검증 안 됨.
- 이유: Phase 3 종결, GKE 클러스터 미보유, kind/minikube CI 통합은 본 task 비용 초과 (CI runtime 5~10분 추가 + Helm chart pull 비용).
- 보완: ServiceMonitor 의 스키마/필수 field 를 lint 가 함께 검증. 운영 기능 fail 은 Phase 3 부하 테스트 (2026-04-29) 에서 1회 검증됨.

### R3. negative test 가 PR 본문 1회 증빙에 의존 — 영구 자동화 부재

- D5-V1/V2/V5/V6 의 negative case 자체는 회귀 테스트로 변환되지 않음 (CI 가 negative 를 자동 주입하지 않음).
- 이유: Bats 회귀 테스트 (D-011 패턴) 도입 시 비용 vs 강제력 — lint 자체가 단순한 grep + yaml 파싱이라 회귀가능성 낮음. PR 본문 증빙으로 "도입 시점 동작 정상" 만 확정.
- 보완: lint script 의 violation 메시지가 안정적 형식 (`[D5-VN] ...`) 으로 출력되도록 작성 → 후속에서 Bats 도입 시 grep 만으로 회귀 가능.

### R4. python3 + pyyaml 의존

- CI ubuntu runner 는 python3 기본 제공, pyyaml 은 `pip install --user` 로 도입. macOS 개발 환경도 Homebrew python3 + pip 정상.
- 잔여: GitHub Actions 캐싱 부재로 매 PR 마다 `pip install pyyaml` 실행 (~5초). 비용 미미.

### R5. ADR-0009 §Decision 표와 본 plan §1 의 매핑 drift

- ADR 본문이 향후 (예: S7 신규 surface) 추가되면 본 plan §1 의 매핑 표가 stale 해짐.
- 보완: 본 task 종결 후 ADR-0009 가 immutable 원칙대로 본문 정정 시 `## Update Log` 통과 — 본 plan 의 매핑은 task 시점 snapshot 으로 frozen. 후속 surface 추가는 *새 후속 task* 에서 ADR-0009 직접 인용.

### R6. ADR-0007 회색지대 화이트리스트의 미명문화 위험

- D5-V1 lint 가 `probes.enabled` / `show-details` 를 화이트리스트 처리하는데, 화이트리스트는 lint script 내부에 hard-coded.
- 보완: script 상단 주석으로 ADR-0007 회색지대 분류 + ADR-0009 §회색지대 분류 인용. 화이트리스트 변경 시 ADR 갱신 의무 명시.

## 8. 후속 (Out-of-Scope)

- **Phase 4 멀티모듈 분리 task** (별도): ADR-0009 §Decision 표 5번째 컬럼 ("Phase 4 owner") 이행. S1/S3/S4 → `peekcart-common-observability` 모듈, S2 → 각 서비스 own (값 = 서비스 이름), S5 → per-service ServiceMonitor 분할. 본 task 의 lint 는 분리 시 `application=` 값 multi-service 화이트리스트로 확장 필요 (현재 `peekcart` 단일 → Phase 4 시 `order-service|payment-service|...`). 본 task 가 도입한 lint 는 "확장 지점" 만 명확히 하면 됨.
- **`management.endpoint.health.show-details` 의 base 이동**: ADR-0009 §회색지대 분류 의 후속 검토 항목. 별도 ADR 또는 ADR-0007 갱신 여부는 추후 판단.
- **PromQL syntax check 도입 (ADR-0009 §Decision S6 미이행 잔여)**: 본 task 가 D5-V6 의 라벨 invariant 절반만 격상하고 syntax 절반은 §7 R1 사유 (도입 비용 비대칭 — `promtool promql format` 의 experimental 기능 의존 + CI 설치 step + expr 추출 비용) 로 기각 — ADR-0009 §Decision S6 검증 수단 대비 부분 미이행. 재검토 트리거: (i) Phase 4 OpenTelemetry 도입으로 alert 수 증가, (ii) `promtool promql format` 의 experimental → stable 승격, 또는 (iii) D5-V6 lint 가 1차 sanity 로 부족함을 입증하는 운영 인시던트 발생 시 별도 task 로 격상.
- **ServiceMonitor 실 cluster 동작 검증**: Phase 4 GKE 재가동 시점에 스모크 테스트로 추가 검토.

## 9. 브랜치

`chore/task-d005-observability-consolidation` — `chore` 접두사 이유: detection 강제 메커니즘만 추가, 동작/도메인 변경 0건 (D-011 harness-hardening 패턴 동일).
