# ADR-0008: Outbox Trace Context Propagation

- **Status**: Accepted
- **Date**: 2026-05-01
- **Deciders**: PeekCart 백엔드 (단일 결정자)
- **관련 Phase**: Phase 3 잔여 부채 (Phase 4 MSA 분리 진입 전 처리)

## Context

D-007 옵션 B (`MdcRecordInterceptor` 헤더 우선순위 도입) 작업에서 Consumer 측은 Kafka 헤더 (`X-Trace-Id`, `X-User-Id`) → payload `eventId` fallback → UUID fallback 순서로 forward-compatible 한 인프라가 정립되었다. 그러나 Outbox 패턴 경유로 발행되는 production 메시지는 다음 두 가지 이유로 trace context 가 끊긴다.

1. `OutboxPollingService.pollAndPublish()` 가 `@Scheduled` 별도 스레드에서 실행되어 호출 시점의 MDC 가 비어 있다. 폴링 스레드에서 `MDC.get("traceId")` 는 항상 null.
2. `kafkaTemplate.send(topic, key, value)` 3-인자 시그니처를 사용해 `ProducerRecord.headers()` 경로가 없다. Consumer 측 `MdcRecordInterceptor` 가 의존하는 헤더 키 `X-Trace-Id` / `X-User-Id` 가 발행되지 않아 fallback (eventId) 으로만 동작.

결과적으로 동일 HTTP 요청이 트리거한 Outbox 이벤트도 Consumer 로그에서 새로운 traceId(payload eventId)로 나타나고, HTTP 요청 → Outbox 저장 → Kafka publish → Consumer 처리 → DLQ 의 5 단계 로그를 단일 traceId 로 묶을 수 없다.

Phase 4 MSA 분리 + cross-service tracing (OpenTelemetry / micrometer-tracing) 도입 시 표준 헤더 (`traceparent`) 를 추가만 하면 동작하도록, **모놀리스 단계에서 Outbox 측 trace 인프라를 선결**하는 것이 비용 효율이다. Outbox 컬럼은 그대로 활용되고 헤더 키만 표준화될 뿐이라 후속 작업과 자연스럽게 연결된다.

## Decision

**Outbox 이벤트 저장 시점 (원본 HTTP 요청의 트랜잭션 컨텍스트) 에 MDC 의 `traceId` / `userId` 를 엔티티 컬럼 (`outbox_events.trace_id`, `user_id`) 으로 영속화하고, `OutboxPollingService` 가 ProducerRecord 빌드 시 `KafkaTraceHeaders.TRACE_ID` / `USER_ID` 헤더에 영속된 값을 주입한다.**

세부 결정:
- **MDC 캡처 책임은 Publisher 측 (`OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`)** 가 가진다. `OutboxEvent.create(...)` 시그니처는 명시 인자 (`String traceId, String userId`) 로 받아 도메인 횡단 엔티티가 SLF4J 에 직접 의존하지 않는다 (옵션 a).
- **MDC 캡처 헬퍼 `global/kafka/MdcSnapshot`** 정적 헬퍼를 도입해 `MdcSnapshot.current()` 한 줄 호출로 traceId/userId 를 캡처한다. 단위 테스트 가능 (MDC 미설정 시 둘 다 null).
- **헤더 부재 정책**: traceId/userId 가 null 이면 헤더 자체를 추가하지 않는다 (빈 문자열 헤더 추가 금지). Consumer 측 `MdcRecordInterceptor.headerValue()` 의 `value.isBlank() ? null` 분기와 정합.
- **인덱스 미생성**: trace 기반 조회는 사후 ad-hoc 분석용. row insert/update 비용 회피 우선. 빈도 높아지면 후속 ADR 로 분리.

## Alternatives Considered

### Alternative A: 컬럼 추가 + MDC 캡처 + 헤더 주입 (선택)
- **장점**: 기존 Consumer 측 헤더 우선순위 인프라 (D-007 옵션 B) 와 정합. payload envelope 스키마 불변. Phase 4 OpenTelemetry 도입 시 `traceparent` 한 단계만 추가하면 forward-compat.
- **단점**: outbox_events row size 증가 (`trace_id` ≤ 64자 + `user_id` ≤ 64자). MDC 직접 의존 위치 1곳 신규 (Publisher).
- **채택 사유**: 4가지 대안 중 가장 작은 변경 + 기존 인프라 재활용 + Phase 4 forward-compat.

### Alternative B: payload JSON envelope (`KafkaEventEnvelope`) 에 `traceContext` 필드 임베드
- **장점**: 별도 컬럼 불필요, 메시지 자체에 trace 가 따라감.
- **단점**: envelope 스키마 변경 → Consumer payload 파서 수정 필요 → 기존 메시지 호환성 깨짐. payload 본문 비대화. 헤더 우선순위 인프라 (`MdcRecordInterceptor`) 와 별도 경로.
- **기각 사유**: 호환성 깨짐 + 별도 경로 도입은 D-007 인프라 활용 가치를 무효화.

### Alternative C: 별도 `outbox_trace_context` 테이블 분리
- **장점**: 메인 테이블 row size 영향 최소화.
- **단점**: 폴링 쿼리에 JOIN 필요 → 복잡도 증가. 단일 도메인 가시성 가치 대비 운영 부담 과다.
- **기각 사유**: 분석 빈도가 낮은 컬럼 2개 분리는 오버엔지니어링.

### Alternative D: OpenTelemetry / W3C TraceContext (`traceparent`) 즉시 도입
- **장점**: 표준 헤더 즉시 사용. Phase 4 cross-service tracing 과 자연스럽게 연결.
- **단점**: 모놀리스 단일 컨텍스트에서는 sampling, baggage, span context propagation 등 OTel 인프라 도입 비용이 가시성 가치 대비 과다. micrometer-tracing 의존성 + Tempo/Jaeger 백엔드 결정이 별도 ADR 필요.
- **기각 사유**: Phase 4 MSA 분리 + micrometer-tracing 도입과 묶음 작업이 자연스러움. 본 ADR 은 그 시점에 헤더 우선순위에 `traceparent` 한 단계 추가만으로 forward-compat 유지 가능.

## Consequences

### 긍정적 영향
- HTTP 요청 → Outbox 저장 → Kafka publish → Consumer 처리 → DLQ 5단계 로그를 단일 traceId 로 묶어 end-to-end 추적 가능.
- DLQ 분석 시 원본 요청 식별 가능 (`DeadLetterPublishingRecoverer` 가 헤더 자동 복사).
- Phase 4 OpenTelemetry 도입 시 `MdcRecordInterceptor` 헤더 우선순위에 `traceparent` 한 단계 추가만으로 forward-compat 유지 — 본 ADR 은 그 시점에 "Partially Superseded" 가 아닌 "Accepted" 유지 (영속 컬럼 자체는 그대로 활용).

### 부정적 영향 / 트레이드오프
- `outbox_events` row size 증가 (≤ 128 bytes 가산). insert 빈도가 높은 테이블이라 minor 성능 영향 가능. 별도 인덱스를 만들지 않아 update 비용은 변동 없음.
- Publisher 2곳에서 `MdcSnapshot.current()` 호출 중복. Helper 화로 한 줄 호출이지만 추가 호출부는 향후 Outbox 사용처가 늘어날 때마다 명시적 추가 필요.
- 도메인 횡단 엔티티 (`global/outbox/`) 는 MDC 와 분리되어 있지만 Publisher 가 SLF4J `MDC` API 에 의존 (간접) — `MdcSnapshot` 으로 캡슐화됨.

### 후속 결정에 미치는 영향
- Phase 4 진입 시 `MdcRecordInterceptor` 헤더 우선순위에 `traceparent` (W3C TraceContext) 추가 + `MdcFilter` 를 OpenTelemetry tracer 와 결합. 별도 ADR 로 진행.
- 향후 Outbox 사용처가 늘어날 때 `MdcSnapshot.current()` 호출 패턴이 표준화됨 — 동일 task 내 도입한 helper.
- 추적 대상 컬럼이 늘어나면 (예: `correlation_id`) 동일 패턴으로 컬럼 추가 + 헤더 매핑 추가.

## References

- 계획서: `docs/plans/done/task-d010-outbox-trace-context.md`
- 선행 작업: D-007 옵션 B (`docs/TASKS.md` Tech Debt 표 행 D-007)
- 관련 ADR: ADR-0001 (4-Layered + DDD), ADR-0002 (모놀리식 → MSA)
- 관련 코드: `src/main/java/com/peekcart/global/kafka/{KafkaTraceHeaders,MdcRecordInterceptor}.java`, `src/main/java/com/peekcart/global/outbox/{OutboxEvent,OutboxPollingService}.java`
- 관련 마이그레이션: `src/main/resources/db/migration/V4__outbox_trace_context.sql`
