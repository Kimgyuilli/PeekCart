# ADR-0015: 관측성 per-service 계약 — 5서비스 분리 완료 상태로 SSOT 위치·검증 정정

- **Status**: Accepted
- **Date**: 2026-06-21
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (구현 ① PR3c)

## Context

ADR-0009(관측성 계약 SSOT)는 Phase 4 멀티모듈 분리를 **앞두고** 9개 surface(S1~S6.d)의 SSOT 위치와 "Phase 4 owner" 를 결정했다. §Decision 표는 이미 per-service 를 의도했다 — S2="각 서비스 모듈 `application.yml`, 값은 서비스 자기 이름", S5="각 서비스 own `services/<service-name>/servicemonitor.yml`", S6="shared/ 1파일, cross-service 는 `application=~"<svc1>|<svc2>|..."` 정규식", 후속 D5-V6="PromQL syntax + 입력 series 가정 lint".

그러나 ADR-0009 작성 시점은 **모놀리스(단일 `peekcart`)** 였으므로, 본문의 다음 부분은 *현 위치 서술*로서 모놀리스 전제에 묶여 있다:
- §Context "관측성 surface 의 현 위치" 서술 — root `src/main/resources/application.yml`, `application=peekcart`, `k8s/base/services/peekcart/servicemonitor.yml`
- §Decision 표 "현 SSOT(파일:라인)" 컬럼 — 위와 동일한 모놀리스 경로
- 회귀 검증 메커니즘(D5-V1/D5-V2)의 "단일 root `application.yml`" 전제 + `EXPECTED_APPLICATION_TAG="peekcart"` 단일값
- S5 단일 ServiceMonitor 경로(`base/services/peekcart/`)

구현 ①(PR2 series ~ PR3b, #48~#67)에서 5서비스 풀 분해가 완료되어 root app 이 소멸했다(Payment peel #65). 그 결과 위 "현 위치" 서술은 **실제 코드와 불일치**한다:
- 메트릭 태그 `application=<svc>-service` 는 이미 5서비스 각 `application.yml` 에 존재(`management.metrics.tags.application`).
- MeterFilter/MeterRegistryCustomizer 단일 owner 는 `peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java` 로 이동.
- ServiceMonitor 는 `k8s/base/services/<svc>-service/servicemonitor.yml` 5개로 분할(PR3b).
- alert/dashboard 는 여전히 `application="peekcart"`/`service="peekcart"` 단일값 하드코딩(미정정).
- observability-ssot-lint / observability-promql-lint 2종은 삭제된 root yml·단일 servicemonitor 경로를 가정하여 CI 에서 비활성 상태.

ADR 본문은 immutable(README §8/§14)이므로 ADR-0009 본문을 직접 고칠 수 없다. "현 위치/검증 메커니즘의 모놀리스 전제"를 5서비스 분리 완료 상태로 **정정**하고, alert/lint 의 per-service 재설계 계약을 명문화하기 위해 본 ADR 을 신규 작성한다.

## Decision

**ADR-0009 의 per-service 의도(§Decision owner 컬럼)를 유지·실현하되, 모놀리스 전제에 묶인 "현 위치 서술·검증 메커니즘·S5 경로"를 5서비스 분리 완료 상태로 정정한다.** 본 ADR 은 ADR-0009 §Decision 의 per-service owner 결정을 뒤집지 않으며(그 결정은 유효), 그 결정이 가리키던 미래 상태를 현재 코드 계약으로 확정한다.

### per-service 관측성 계약 (정정 표)

| # | Surface | 정정된 SSOT 위치 (현 코드) | per-service 계약 | 검증 |
|---|---------|----------------------------|-------------------|------|
| S1 | histogram bucket / MeterFilter | `peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java` | 단일 공유 owner. 서비스 모듈은 import-only, `MeterFilter`/`MeterRegistryCustomizer` 재선언 금지 | `observability-ssot-lint.sh` D5-V2(owner=common-observability 외 선언 검출) |
| S2 | metrics tags (`application=`) | 5서비스 각 `<svc>-service/src/main/resources/application.yml` (`management.metrics.tags.application`) | 값 = **서비스 자기 이름** `<svc>-service`(예: `order-service`). 단일 `peekcart` 값 폐기. 어느 프로파일 yml 에도 재선언 금지(ADR-0007) | `observability-ssot-lint.sh` D5-V1(프로파일 재선언)·D5-V2(값=디렉터리명 일치) |
| S5 | scrape 설정 (ServiceMonitor) | 5서비스 각 `k8s/base/services/<svc>-service/servicemonitor.yml` | per-service 1파일. selector `app=<svc>-service`, namespace `peekcart`(5서비스 공유 NS — ADR-0006 유지) | `servicemonitor-selector-lint.sh` D5-V5(canonical 5 집합·SM↔Service 매칭) |
| S6.a/b | error-rate / latency alert | `k8s/monitoring/shared/grafana-alerts.yml`(cross-service 1파일, ADR-0006 위치 유지) | 단일 `application="peekcart"` 필터 폐기 → **5서비스 정확일치 regex `application=~"notification-service\|order-service\|payment-service\|product-service\|user-service"` + `by (application)` grouping**. 무필터+by 단독 금지(PeakCart 외 앱 유입), 단일 서비스 equality 금지(coverage 누락) | `observability-promql-lint.sh` D5-V6(라벨 invariant + regex 집합=5서비스 ground truth + PromQL syntax) |
| S6.c | target-down (`up == 0`) | 동상(shared) | `service="peekcart"` 폐기 → `count by (service)(up{namespace="peekcart"} == 0)`(namespace 필터 유지, 서비스별 평가) | 동상(D5-V6 namespace 필터 + by(service)) |
| S6.d | scrape-absent (series 부재) | 동상(shared) | `absent()` 는 by-clause 불가 → **5서비스 equality matcher rule 분할**(각 `absent(up{namespace="peekcart", service="<service-name>"})`). expected-service set = **SM 가 매칭하는 K8s Service 의 `metadata.name` 집합**(`up{service=}` 의 `service` 라벨은 Service 이름 의미, selector app 값 아님) | 동상(D5-V6 equality 집합=매칭 Service name 집합 1:1) |

> S3(actuator exposure)·S4(actuator 보안)·S7(cache)·S8(outbox)·S9(auth)는 본 ADR 정정 대상 아님 — ADR-0009 §Decision owner 컬럼대로 유효(S1/S2/S5/S6 의 "현 위치 서술"만 모놀리스→5서비스 정정).

### ADR-0009 무효화 범위 (Partially Superseded by ADR-0015)

ADR-0009 본문은 불변. **무효화되는 것은 "현 위치 서술·검증 메커니즘의 모놀리스 전제"에 한정**한다:
1. §Context "관측성 surface 의 현 위치" 서술 중 root `src/main/resources/application.yml`·`application=peekcart`·`k8s/base/services/peekcart/servicemonitor.yml` 경로
2. §Decision 표 "현 SSOT(파일:라인)" 컬럼의 모놀리스 경로(S1/S2/S5/S6 행)
3. 자동 회귀 검증의 `application=peekcart` 단일값 전제(테스트·D5-V1/D5-V2 의 단일 root yml 가정)
4. S5 단일 ServiceMonitor 경로(`base/services/peekcart/`)

**무효화되지 않는 것**: §Decision 표의 "Phase 4 owner" 컬럼(per-service owner 결정 자체) — 본 ADR 이 그 결정을 실현·확정한다.

## Alternatives Considered

### Alternative A: ADR-0009 본문 직접 수정 (Update Log)
- **장점**: 단일 ADR 유지, 추적 간단.
- **단점**: README §8/§14 immutable 원칙 위반. Update Log 예외는 "사실 오류(파일명/수치)" 정정용이지, alert/lint 의 per-service 재설계라는 **계약 수준 변경**을 우회하는 데 쓸 수 없다(README §14 "트레이드오프 변경·Consequences 재해석은 새 ADR").
- **기각 사유**: 본 변경은 D5-V6 alert lint 를 prose 정의 → 실제 구현(coverage+syntax)으로 격상하고 alert 표현 방식을 바꾸는 계약 변경이므로 신규 ADR 정당.

### Alternative B: alert 를 5서비스 rule 복제 (per-service 별도 rule)
- **장점**: 서비스별 임계 차등 가능.
- **단점**: 신규 서비스마다 rule 5종 추가 필요, lint 부담 증가, error-rate/latency 는 서비스 공통 임계로 충분.
- **기각 사유**: error-rate/latency/target-down 은 `by` grouping 단일 rule 로 5서비스 자동 평가(신규 서비스 자동 포함)가 더 단순. scrape-absent 만 `absent()` 제약상 5 equality rule 채택(부분 절충).

## Consequences

### 긍정적 영향
- alert/dashboard/lint 가 실제 5서비스 코드와 정합. observability lint 2종 재활성으로 관측성 계약이 다시 CI 게이트화.
- `by (application)` + 5서비스 정확일치 regex 로 신규 서비스 자동 포함(regex 갱신 필요)과 외부 앱 유입 차단을 동시 달성.

### 부정적 영향 / 트레이드오프
- 신규 서비스 추가 시 alert regex 5-set·scrape-absent equality rule·lint ground truth 동기 필요(자동 생성으로 완화하되 수동 갱신 잔존 가능).
- PromQL 정적 lint 는 syntax + 라벨 invariant 만 검증 — Grafana `__expr__`(threshold/reduce) 의미 평가·실제 series 존재·발화 임계 동작은 정적 검증 대상 외(Prometheus/Grafana 실행 환경 필요).

### 후속 결정에 미치는 영향
- 구현 ②(서비스별 DB 물리 분리) 후 각 서비스 자가 마이그레이션 → 본 ADR 의 namespace 공유 전제는 유지(DB 분리와 무관).
- 운영 alert delivery(Slack/PagerDuty)는 L-004(Phase 4)로 이관 — 본 ADR 은 alert 규칙 평가까지만.

## References
- ADR-0009 (관측성 계약 SSOT — 본 ADR 이 Partially Supersede)
- ADR-0006 (Monitoring 스택 환경 분리 — S5/S6 위치 분담 유지)
- ADR-0010/0011 (5서비스 분해·멀티모듈)
- 계획서: `docs/plans/task-impl1-pr3c-observability.md`
- `scripts/observability-ssot-lint.sh`, `scripts/observability-promql-lint.sh`, `k8s/monitoring/shared/grafana-alerts.yml`
