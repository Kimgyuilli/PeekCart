# 최종 개선안 보고서

> 작성: 2026-04-10
> 기준: review1.md, review2.md, codex-통합 리뷰.md 3건 + Codex 피드백 2회차 교차 검증
> 검증 방법: 모든 지적 사항을 실제 소스코드와 대조. 판단 근거에 파일:라인 명시

---

## 검증 과정 요약

| 단계 | 내용 |
|------|------|
| 1차 | review1(설정/모니터링 중심), review2(구조/설계 중심) 독립 리뷰 |
| 2차 | codex-통합 리뷰 — 두 리뷰의 교차 검증 + 우선순위 정리 |
| 3차 | 통합 보고서 — 코드 대조 후 유효/과장/해당없음 분류 (최종 보고서에 흡수, 원본 삭제) |
| 4차 | Codex 피드백 — 통합 보고서의 수정 범위 과소, 테스트 범위 부족, 기각 판단 과도 지적 |
| 5차 | Codex와의 토론 — 7개 영역 공격/방어. Codex의 Outbox consistency bug 주장에 대해 IdempotencyChecker 근거로 반론 |
| 최종 | 본 보고서 — 합의된 최종 판단 |

---

## 토론 결과: Codex 피드백 수용/반론 정리

### 수용한 피드백 (3건)

**1. Error Rate PromQL 수정 범위 확대**
- Codex 지적: 대시보드 JSON만이 아니라 `grafana-alerts.yml`도 동일한 0/0 위험
- 검증: `grafana-alerts.yml:46` — `($A / $B) * 100 > 5`에서 $B=0이면 NaN → alert 상태 NoData/Error
- **수용**: 수정 범위를 대시보드 JSON + alerts YAML 양쪽으로 확대

**2. 관측성 회귀 테스트 범위 강화**
- Codex 지적: `/actuator/prometheus`만 호출하면 actuator URI 메트릭만 생성. Grafana 알림은 `uri!~"/actuator.*"` 필터 사용 (`grafana-alerts.yml:63`)
- 검증: `SecurityConfig.java:41-42`에서 `/api/v1/products`, `/api/v1/products/**`가 PUBLIC_URLS → 인증 없이 호출 가능
- **수용**: 비즈니스 엔드포인트(`GET /api/v1/products`) 호출 후 해당 URI의 histogram bucket 존재를 검증하는 테스트로 설계 변경

**3. 관측성 계약 승격 — "기각" → "유효, 1차 봉합"으로 표현 조정**
- Codex 지적: 패키지 분리 문제로 축소한 것은 과도. 실제 계약이 5개 파일에 분산
- 검증: MetricsConfig.java(histogram) + application-k8s.yml(tags/actuator) + SecurityConfig.java:48(보안 허용) + servicemonitor.yml:16(scrape) + grafana-alerts.yml(PromQL 전제) — 5파일 분산 확인
- **수용**: "기각"이 아니라 "유효한 구조 문제이나, management 공통화 + 회귀 테스트로 1차 봉합. 전면 정리는 Phase 4 전 기술 부채"로 재분류

### 반론한 피드백 (2건)

**4. Outbox "publish/persist consistency bug" — 과대 평가**
- Codex 주장: Kafka send 성공 후 `save()` 실패 시 다음 poll에서 재발행 → 중복 publish/retry 손상
- 반론:
  - `findPendingEvents`는 `WHERE status = 'PENDING'`만 조회 (`OutboxEventJpaRepository.java:11`)
  - Kafka 성공 → `markPublished()` (in-memory status=PUBLISHED) → save 실패 → catch 진입 → line 44 save 재시도 → 성공 시 PUBLISHED로 저장, 다음 poll에서 조회 안 됨
  - line 44 save도 실패하면 DB에 PENDING 잔류 → 재발행 → 중복. 하지만 이것은 **outbox 패턴의 설계 의도**(at-least-once delivery)이며, 소비자 측 `IdempotencyChecker` (`global/idempotency/` 패키지, 14개 파일)가 정확히 이 시나리오를 처리
  - 따라서 "consistency bug"가 아니라 "at-least-once 설계의 정상 동작"
- **결론**: Outbox의 실제 버그는 **Slack send가 FAILED 상태 저장을 차단하는 것**(`OutboxPollingService.java:39-41`)으로 한정. Codex가 지적한 성공 경로 문제는 IdempotencyChecker로 이미 보호됨

**5. MDC 표준화 우선순위 상향 — 부분 수용**
- Codex 주장: logback-spring.xml이 traceId/userId/orderId 3개 필드를 기대하지만 MdcFilter는 HTTP 경로만 커버. Kafka Consumer는 orderId만 수동 추가. 우선순위를 올려야 함
- 검증: `MdcFilter.java` — traceId + userId (HTTP만). Kafka Consumer — orderId만 (`OrderEventConsumer.java:48,70`, `NotificationConsumer.java:63,87`, `PaymentEventConsumer.java:42`). traceId/userId는 Kafka 경로에서 미설정
- 반론: 이 불일치가 **운영 장애를 일으키지는 않음**. JSON 로그에서 해당 MDC 키가 없으면 단순히 필드가 누락될 뿐, 예외나 데이터 손상은 없음. 세션 C 부하 테스트에도 영향 없음
- **부분 수용**: 즉시 수정 대상은 아니지만, 기술 부채에서 "낮음 → 중간"으로 상향. Kafka 경로에서 traceId/userId 부재는 로그 추적성을 제한하므로 Phase 4 전 정리 대상

---

## 최종 개선 액션

### P0: 즉시 수정 (세션 C 전)

#### A. Outbox 실패 경로 — Slack 예외 격리

**파일**: `src/main/java/com/peekcart/global/outbox/OutboxPollingService.java:39-41`
**문제**: `slackPort.send()` 예외 시 line 44 `save(event)` 미도달 → FAILED 상태 영속화 실패
**대조**: DLQ 경로(`KafkaConfig.java:88-91`)는 동일 패턴을 try/catch로 보호 — 처리 철학 불일치
**수정**: `slackPort.send()`를 try/catch로 감싸고 실패 시 `log.warn()` 처리
**규모**: 3줄 변경

#### B. management 설정 공통화

**파일**: `application.yml`, `application-k8s.yml`
**문제**: `management.endpoints.web.exposure.include`와 `management.metrics.tags.application`이 k8s 프로파일에만 존재 → 로컬에서 메트릭 사전 검증 불가
**수정**:
- `application.yml`로 이동: `management.endpoints.web.exposure.include: health,prometheus` + `management.metrics.tags.application: peekcart`
- `application-k8s.yml`에 잔류: `management.endpoint.health.probes.enabled: true` + `management.endpoint.health.show-details: never` (K8s probe 전용)
**규모**: 2파일 수정

#### C. TASKS.md Phase 표기 수정

**파일**: `docs/TASKS.md:8`
**문제**: `## 현재 Phase: Phase 1 — 모놀리식 구현`으로 표기. 실제 활성 작업은 Phase 3
**수정**: 최상단 Phase 표기를 Phase 3으로 변경, 또는 "현재 Phase" 헤더를 Phase 3 섹션으로 이동
**규모**: 1줄 변경

### P1: 단기 개선 (세션 C 전 권장)

#### D. 관측성 회귀 테스트 추가

**위치**: `src/test/java/com/peekcart/global/config/` (신규)
**설계** (Codex 토론 반영):
1. `@SpringBootTest` + `TestRestTemplate`
2. 비즈니스 엔드포인트 호출: `GET /api/v1/products` (SecurityConfig PUBLIC_URLS, 인증 불요)
3. `/actuator/prometheus` 응답에서 검증:
   - `http_server_requests_seconds_bucket{uri="/api/v1/products"}` 존재 (non-actuator URI histogram)
   - `application="peekcart"` 태그 존재
4. histogram 분포 형태(bucket 값, quantile 정확도)는 **검증하지 않음** — 운영 환경 의존성이 높아 불안정 테스트 원인
**규모**: 1클래스 신규 생성

#### E. Error Rate PromQL NaN 가드

**파일**: `k8s/monitoring/shared/api-jvm-dashboard.json:57` + `k8s/monitoring/shared/grafana-alerts.yml:46`
**문제**: 트래픽 없는 구간에서 분모=0 → NaN
**수정**:
- 대시보드: `sum(rate(...{status=~"5.."}[5m])) / (sum(rate(...[5m])) > 0) * 100`
- 알림: `($B > 0) and (($A / $B) * 100 > 5)`
- `noDataState` 설정은 선택적 후속 조치 (scrape 실패 대응), NaN 가드와는 별개
**규모**: 2파일 수정 + dashboards-configmap.yml 동기화

#### F. 대시보드 JSON SSOT 단일화

**파일**: `k8s/monitoring/shared/` 하위 3개 standalone JSON + `dashboards-configmap.yml`
**문제**: 동일 대시보드가 standalone JSON과 ConfigMap inline에 이중 존재
**수정**: standalone JSON을 SSOT로 유지. ConfigMap은 JSON 파일을 참조하는 구조로 전환 (kustomize configMapGenerator 또는 빌드 스크립트). 또는 standalone JSON을 제거하고 ConfigMap만 유지
**규모**: 구조 결정 후 실행. E번(PromQL 수정) 시점에 함께 처리하면 동기화 누락 방지

---

### 기술 부채 기록 (TASKS.md Tech Debt 추가)

#### D-005: 관측성 계약 분산 (중간)

**발견**: 리뷰 종합 (2026-04-10)
**설명**: 관측성 계약이 5개 파일에 분산. MetricsConfig.java(histogram 활성화), application.yml(metrics tags, actuator 노출 — P0-B로 공통화 예정), SecurityConfig.java(actuator 보안 허용), servicemonitor.yml(scrape path), grafana-alerts.yml(PromQL label 전제). 개별 파일은 정확하더라도 전체 계약의 일관성이 자동 보장되지 않음
**1차 봉합**: P0-B(management 공통화) + P1-D(회귀 테스트)로 핵심 계약 깨짐 조기 탐지 가능
**완전 해결**: Phase 4 MSA 전환 전 관측성 설정을 단일 설계 축으로 정리 (ObservabilityConfig 등). 현재는 파일 수가 적어 패키지 분리는 과도
**우선순위**: 중간 — 1차 봉합 완료 후 잔여 리스크

#### D-006: YAML 프로파일 병합 원칙 미명문화 (중간)

**발견**: review1 (2026-04-10)
**설명**: `spring.kafka`가 base와 프로파일에 분산. 현재는 프로파일에 `bootstrap-servers`만 override하여 안전하지만, 향후 프로파일에 `spring.kafka.producer.xxx` 추가 시 D-001 재발 가능
**영향**: 구조적 재발 위험. D-001 보고서의 "YAML vs Java Config 분리 원칙"이 프로젝트 전체에 일관 적용되지 않은 상태
**조치**: CLAUDE.md 또는 ADR에 원칙 명문화 — "환경별 YAML은 연결 정보(host, port, URL)만 override. 동작 정책(serializer, ack mode, metrics tags 등)은 base YAML 또는 Java Config"
**우선순위**: 중간

#### D-007: Kafka Consumer MDC 불완전 (중간)

**발견**: Codex 피드백 (2026-04-10)
**설명**: `logback-spring.xml:17-19`가 traceId/userId/orderId 3개 MDC 키를 기대. HTTP 경로는 `MdcFilter`가 traceId + userId 설정. Kafka Consumer 경로는 orderId만 수동 설정 (`OrderEventConsumer.java:48`, `PaymentEventConsumer.java:42`, `NotificationConsumer.java:63`). Kafka 경로에서 traceId/userId 부재 → JSON 로그에서 해당 필드 누락
**영향**: 운영 장애는 아님 (필드 누락 시 JSON에서 키 자체가 빠질 뿐). 하지만 Kafka 소비 경로의 로그 추적성이 HTTP 경로 대비 제한적
**조치**: Kafka Consumer 공통 helper/decorator에서 이벤트 payload의 eventId를 traceId로, 발행자 userId를 MDC에 설정
**우선순위**: 중간 — Phase 4 전 정리 권장

#### D-008: Grafana datasource UID 하드코딩 (낮음)

**발견**: review1 (2026-04-10)
**설명**: 모든 대시보드와 알림에서 `"uid": "prometheus"` 하드코딩. kube-prometheus-stack Helm 기본값과 일치하는 한 문제 없으나, Helm 업그레이드/재설치 시 UID 변경 가능성
**우선순위**: 낮음 — 현재 환경에서 동작. Helm 업그레이드 시 확인

---

## 보류 항목 (현 단계에서 조치 불필요)

| 항목 | 출처 | 보류 사유 |
|------|------|-----------|
| @ConfigurationProperties 타입화 | codex-통합 리뷰 | 설정 키 수 적고 오류 시 기동 시 즉시 발견. Phase 4 MSA 전환 시 도입 |
| Outbox 발행 처리량 확장성 | review2 | 순차 발행은 현 규모에서 합리적. at-least-once + IdempotencyChecker로 정합성 확보. 중기 검토 |
| Outbox 성공 경로 중복 발행 | Codex 5차 | outbox 패턴의 at-least-once 설계 의도. `IdempotencyChecker`(14파일)가 소비자 측 중복 차단. consistency bug 아님 |
| Alertmanager / Secret 관리 | review1 | 포트폴리오 맥락. 면접 대비 설명 포인트로 인지 |

---

## 실행 순서 요약

```
세션 C 전 (즉시)
├── P0-A: Outbox slackPort try/catch (3줄)
├── P0-B: management 설정 → application.yml 이동 (2파일)
├── P0-C: TASKS.md Phase 표기 수정 (1줄)
├── P1-D: 관측성 회귀 테스트 추가 (1클래스)
└── P1-E: Error Rate PromQL NaN 가드 (2파일 + ConfigMap 동기화)

세션 C 이후 정리
└── P1-F: 대시보드 JSON SSOT 단일화 (구조 결정 후)

기술 부채 기록
├── D-005: 관측성 계약 분산 (중간)
├── D-006: YAML 병합 원칙 명문화 (중간)
├── D-007: Kafka Consumer MDC 불완전 (중간)
└── D-008: Grafana datasource UID 하드코딩 (낮음)
```
