# task-adr0020-dlq-replay-contract — DLQ replay 계약 ADR-0020

> 작성: 2026-09-01 · **개정: 2026-09-01 (계획 리뷰 1R 13건 · 2R 9건 · 3R 15건 전량 반영)**
> **리뷰 상태: 미수렴 종료** — 3R 상한에서 P1 12건이 남은 채 사용자 판단으로 종료했다. 근거와 잔여 성격은 §정정 이력 말미.
> 관련 Phase: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga
> 선행: ADR-0011(모듈 경계·NewTopic 프로비저닝 소유), ADR-0012(D1 DB 경계·D4 토픽×producer 매트릭스·**D5 retention/재처리 대안**), ADR-0018(보상/환불 — outbox at-least-once, producer 규약)
> 입력: `docs/plans/task-impl4-c2b-dlq-replay.md` §2 (D1~D7) · `docs/plans/task-impl4-c2a-dlq-ledger.audit.md` · `docs/runbooks/dlq-recovery.md` §6
> 후속: 구현 **④-c-2b**(replay 경로 구현 — 본 ADR 확정 후 `/plan task-impl4-c2b-dlq-replay` 재실행)
> 관련 ADR: 신규 = **ADR-0020** (Proposed → Accepted) · ADR-0012 **D1/D4/D5 관계 판정**(무효화 여부는 채택안에 종속 — §2.2-11) · ADR-0018 producer 규약 관계 판정 · ADR-0011 은 관계 설명만(§2.2-6)

---

## 1. 목표 / 목적

**목적**: ④-c-2b(DLQ replay 경로)가 구현에 착수할 수 있도록, 두 라운드 연속 반증된 설계 표면을 **ADR-0020 으로 확정**한다. 본 task 의 산출물은 결정 문서이며, 코드 변경은 D4 의 선결 조건인 P4(브로커 retention 계약) 하나뿐이다.

**명제는 부정형으로 고정한다** — 다음 중 **하나라도 성립하면 미완**이다:

- **N1.** `task-impl4-c2b-dlq-replay.md` §2 의 D1~D7 **및 본 계획이 신설한 D8**(발행 권한 예외, §2.2-3) 중 어느 하나라도 ADR-0020 에서 **선택지·채택·기각 사유·Consequences 없이** 적혀 있거나 미결로 남아 있다.
- **N2.** 재발행 보장 수준이 **"중복 발행 0" 을 다시 주장**한다. (두 번 반증된 주장이다.)
- **N3.** replay 발행이 **원본 토픽의 producer 가 아닌 서비스**에서 일어나는데, **ADR-0012 D4 매트릭스의 producer 컬럼**(= `1 topic = 1 producer` 의 실제 SSOT, §2.2-6)과의 관계를 처분하지 않았다.
- **N4.** replay 가 **공유 토픽의 모든 consumer group 에 재전달**된다는 사실과 그 무해성의 근거·한계(§2.2-4·§2.2-9)를 계약으로 정의하지 않았다.
- **N5.** 브로커 `retention.ms`·`cleanup.policy`·`retention.bytes` 가 선언되지 않은 채 남아 있거나, 선언은 했는데 **기존 토픽에 실제로 적용되는 경로**가 없다.
- **N6.** replay **금지 축이 독립 조건으로 분리되지 않았다** — `event_id` 부재 · `failed_consumer_group='__unknown__'` · `origin_kind='DLQ_ORIGIN'` · 좌표 무효 · 정책 금지 · **`original_timestamp` 부재**(6개). (`payloadTruncated` 를 금지 사유로 쓰면 위반이다.)
- **N7.** ADR-0012 D1/D4/D5 를 바꾸는데 그 처분이 Status 에 반영되지 않았거나, **기존 superseder(ADR-0016)를 덮어써서 무효화 이력이 소실**된다(§2.2-8).
- **N8.** **실행 표면**(코드·설정·운영 주장)의 검증 행 중 **변이를 가하지 않고 판정되는 것**이 남아 있다. — *문서 결정 항목은 실패 주입 대상이 아니라 **구조 검토** 대상이다. 판정 유형은 §6 머리말이 정의하며, **모든 행이 정확히 하나의 유형을 달아야 한다**(3R #7).*
- **N9.** **broker ack 만으로 사건이 종결**된다. — *상태명을 바꾸는 것으로는 회피되지 않는다. ack 상태를 terminal 로 삼거나 unresolved 집계에서 빼면 같은 조기 종결이다.*
- **N10.** `REPLAY_*` 상태를 추가하면서 **미결 backlog·age 경보·purge 쿼리가 그 상태를 어떻게 취급하는지**를 정하지 않았다. 현 쿼리는 상태 집합을 리터럴로 박고 있어(§2.1-p) 새 상태가 **관측에서 사라지고 영원히 정리되지 않는다**.
- **N11.** **재발행은 성공했으나 업무 consumer 가 다시 실패한 경로**가 원래 원장 행과 연결되지 않는다(§2.2-12).
- **N12.** `replay_deadline` 의 **기준시각 출처·계산식·상속 규칙**이 결정되지 않았다(§2.2-13).
- **N13.** 원장의 **단일 `status` 컬럼**이 *발행 실패*와 *소비 재실패* 를 같은 값으로 표현한다(§2.2-15). 축을 선언으로만 나누고 물리 모델에서 합치면 분리한 것이 아니다.
- **N14.** replay 발행 fence 에 **destination topic/partition 불변식**이 없다(§2.2-16). 임의 partition 주입이 가능하면 key 단위 순서 축까지 잃는다.
- **N15.** 안전 여유가 **설정으로 강제되지 않는다**(§2.2-17). 현재 `retention: 7d == dlq-replay-window: 7d` 이고 validator 는 `retention ≥ max(floor)` 만 본다 — 광고한 7일 창이 skew·지연 여유 없이 성립한다고 주장하게 된다.

---

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (2026-09-01) — ADR 문구가 아니라 현재 코드 기준

| # | 검증 대상 | 결과 |
|---|---|---|
| a | outbox 발행의 crash window | **확인**. `order-service/.../OutboxPollingService.java:83` 이 `kafkaTemplate.send(...).get(timeout)` 으로 broker ack 를 받고, `:85-86` 이 `markPublished(); save(event)` 를 **별도로** 실행한다. 사이에서 사망하면 `PENDING` 인 채 재발행된다. product/payment 동일 |
| b | `OutboxEvent` 의 표현력 | **replay 를 표현하지 못한다**. 컬럼 = `aggregate_type(50)·aggregate_id(50)·event_type(50)·event_id(36,unique)·payload(TEXT)·status·retry_count·last_attempted_at·created_at·published_at·trace_id·user_id`. `buildRecord()`(`:119-125`) = `new ProducerRecord<>(eventType, null, aggregateId, payload)` — 토픽=eventType 고정 · partition=null 고정 · key=aggregateId(NOT NULL,50자) · **timestamp 미지정** · 헤더는 trace/user 둘뿐 |
| c | notification 의 outbox | **없다**. `global/` = `config·deadletter·idempotency`. 마이그레이션 `V1·V2·V3` 에 `outbox_events` 부재. ADR-0012 D1 표(`0012:47`)가 Notification 에 `processed_events` 만 할당 |
| d | 좌표 reader | **없다**. `assign(`·`.seek(` 를 쓰는 production 코드 0건 |
| e | `AdminClient`/`KafkaAdmin` 직접 사용 | **0건**. 토픽은 `NewTopic` 빈으로만 선언 |
| f | 토픽 config 선언 | **0건**. `retention.ms`·`cleanup.policy`·`retention.bytes` 전역 grep 히트 0. `.config(...)` 호출 없음 |
| g | 브로커 레벨 설정 | **없다**. compose·k8s 에 `KAFKA_LOG_RETENTION_*` 부재 |
| h | **실효 retention** | 실효값은 미정의가 아니라 **Apache 기본값**이다 — `apache/kafka:3.8.1` 의 `/opt/kafka/config/server.properties:105` = `log.retention.hours=168`(7d), `log.retention.bytes` 주석 처리(=-1), `cleanup.policy` 기본 `delete`. **오늘도 사실상 7일 보존이며, 문제는 값이 아니라 그것이 계약이 아니라는 것** |
| i | `KafkaAdmin` 이 기존 토픽 config 를 고치는가 | **기본은 안 고친다**. `spring-kafka-3.3.14` 바이트코드에 `private boolean modifyTopicConfigs;`(초기화 없음=false) + `setModifyTopicConfigs(boolean)`, `createOrModifyTopics` 가 그 플래그로 분기. Boot 키 `spring.kafka.admin.modify-topic-configs` 존재 → **선언만으로는 신규 토픽에만 붙는다** |
| j | 원장 상태 집합 | `DeadLetterStatus` = `OPEN·ACKED·DISCARDED`. DB 는 `status VARCHAR(30)` + 애플리케이션 검증이라 값 추가에 마이그레이션이 필요 없다(2a 가 남긴 확장점) |
| k | 원장 식별자 | `dead_letter_records` = 좌표 6컬럼(NOT NULL) + `origin_kind·event_id(nullable,36)·original_key·original_timestamp(nullable)·payload·payload_truncated·attempt_count`·감사필드. **replay 컬럼 없음** |
| l | 운영 진입점 | `DeadLetterEndpoint` = `@Endpoint(id="deadletter")` · `@ReadOperation backlog()` · `@WriteOperation transition(@Selector id, action, actor, reason)`. 2a 가 이미 write 진입점을 만들어 뒀다 |
| m | 멱등 창 선언 | `app.idempotency.floor.{kafka-topic-retention, max-consumer-downtime, dlq-replay-window, backfill-replay-window}` + `IdempotencyRetentionProperties.isRetentionAtLeastFloor()` fail-fast. **브로커에는 아무것도 설정하지 않는다** |
| n | 공유 DLQ 토폴로지 | `payment.completed`·`payment.failed`·`payment.refunded` 를 order·product·notification **3서비스**가, `order.created`·`order.cancelled` 를 product·payment·notification **3서비스**가 각자 group 으로 소비 |
| o | **quarantine 의 실제 조건** (1R #3 — **내 추론 반증**) | quarantine 은 `failed_consumer_group == '__unknown__'` 이다(`DlqOrigin:41-63`). **`eventId` 와 무관** — `DeadLetterRecorder:57` 이 group 과 독립적으로 payload 에서 추출한다. 좌표 출처는 제3의 축(`DlqOriginKind.RESOLVED_ORIGIN` / `DLQ_ORIGIN` = `.dlq` 자신의 좌표) → **세 축이 독립** |
| p | **원장 조회·정리 쿼리의 상태 집합** | `DeadLetterRecordJpaRepository:77-95` 가 `status IN ('OPEN','ACKED')` 를 JPQL 리터럴로 박았고 purge 대상은 `status='DISCARDED'` 뿐(`:90-96`). `DeadLetterMetrics`·`DeadLetterMaintenanceScheduler` 가 같은 쿼리를 쓴다 → 새 상태는 **미결 집계에서도 purge 대상에서도 동시에 빠진다** |
| q | 원장의 동시성 제어 | **`@Version` 없음**(`DeadLetterRecord`) |
| r | `processed_events` 정리 기준 | `ProcessedEventCleanupScheduler:41` = 로컬 `now - retention` 무조건 삭제. `ProcessedEvent.create():46` 의 `processedAt` 도 **각 consumer 서비스 로컬 시각** |
| s | Kafka 저장 용량 | `k8s/base/infra/kafka/kafka.yml:1-11` — `kafka-pvc` requests **1Gi** |
| t | `1 topic = 1 producer` 의 SSOT (1R #4 — **내 지목 오류**) | ADR-0011 D2(`:71`)는 **NewTopic 프로비저닝 소유**다. 발행 권한을 고정하는 것은 **ADR-0012 D4 producer 컬럼**(`0012:76-84`)과 ADR-0018 |
| u | 기존 무효화 이력 | **ADR-0011 은 이미 `Partially Superseded`(ADR-0014)**, **ADR-0012 도 이미 `Partially Superseded`(ADR-0016)**. 단일 superseder 로 덮어쓰면 이력이 사라진다 |
| v | Kafka 를 쓰는 서비스 수 (2R #5) | **4다, 5가 아니다**. `UserApplication:20` = `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)` |
| w | 빌드 대상 모듈 수 (2R #8) | **10이다, 8이 아니다**. `settings.gradle:6-22`. 숫자 게이트는 두 모듈을 빠뜨려도 통과한다 |
| x | `dead_letter_records` 의 소재 (2R #3) | **4개 DB 전부** — `order V6`·`product V5`·`payment V5`·`notification V3`. **원장 스키마 변경은 채택안과 무관하게 항상 4 DB**(outbox 는 3, notification outbox 채택 시 4) |
| y | 멱등 이력의 기준시각 (2R #4) | 다른 group 의 성공 시각은 **분리된 DB 안**이라 조회 불가. `DeadLetterRecord.originalTimestamp` 는 **nullable**(`:83-84`) |
| z | 파티션 수 | 업무 토픽 `partitions(3)` · `.dlq` `partitions(1)` · `replicas(1)`. 용량 하한은 **파티션별 × 파티션 수**로 합산해야 한다 |
| **aa** | **application header 의 보존과 미저장** (3R #4) | `DeadLetterPublishingRecoverer` 가 application header 를 보존함은 통합테스트로 확인돼 있다(`payment-service/.../DlqIntegrationTest.java:147-173`). 그러나 **`DeadLetterRecorder` 는 application header 를 전혀 저장하지 않는다**(`:25-29`) — 상관키를 실어 보내도 **현재 코드는 그것을 읽지 않는다** |
| **bb** | **재발행 레코드의 timestamp** (3R #3) | 현 poller 는 `ProducerRecord` 에 timestamp 를 지정하지 않는다(`OutboxPollingService:119-124`). 재발행분이 다시 DLT 로 가면 원장이 읽는 `DLT_ORIGINAL_TIMESTAMP`(`DlqHeaders:54-64`)는 **재발행 시각**이다 |
| **cc** | **안전 여유가 0** (3R #8) | 4서비스 전부 `retention: 7d` 이고 `dlq-replay-window: 7d`(예: `order-service/.../application.yml:75-81`). validator 는 `retention ≥ max(floor)` 만 본다(`IdempotencyRetentionProperties:43-47`) → **등호가 허용되어 skew·지연 여유가 없다** |
| **dd** | **ADR-0012 D5 의 재처리 대안** (3R #9) | `0012:98` — *"대안: TTL 이후 수동 재처리는 **새 eventId 발행** 또는 운영자 중복 확인 절차"*. 본 계획은 **동일 eventId 필수 + deadline 이후 금지**라 이 대안과 정면으로 만난다. 코드에도 그 전제가 남아 있다 — `StockReservationService` javadoc *"DLQ 재발행(새 eventId)"* |

### 2.2 검증이 만든 범위 변화 / 결정 표면

1. **D4 의 서술이 절반 틀렸다 (→ P4).** 선언 기준 참, 실효 기준 거짓(§2.1-h). P4 는 *동작 변경이 아니라 계약 명문화*다. 이 구분을 흐리면 "설정했다" 가 **아무 것도 안 바뀐 채 성공처럼** 보인다.
2. **`modify-topic-configs` 없이는 P4 가 신규 클러스터에서만 참이다 (→ P4).**
3. **replay 는 발행 권한 계약을 필연적으로 넘는다 (→ P3, 신규 결정 **D8**).** 소비 경로 원장의 `origin_topic` 은 **정의상 남의 토픽**이다. 자기 발행 토픽의 행은 quarantine 경로에만 생기는데 그건 §2.2-5 때문에 금지 대상이다 → 그대로 적용하면 replay 가능 집합이 사실상 공집합이 된다.
4. **replay 는 실패하지 않은 group 까지 재전달한다 (→ P5).** 억제 수단은 `processed_events (event_id, consumer_group)` 뿐 → **동일 `eventId` 보존이 필수 불변식**.
5. **금지 축은 5개 독립 조건이다 (→ P5, §정정 이력 ③).**
6. **P3 의 충돌 대상은 ADR-0011 이 아니라 ADR-0012 D4 + ADR-0018 이다 (§2.1-t).** ADR-0011 Status 는 불변.
7. **`RESOLVED` 가 false-green 이 되기 쉽다 (→ P7).** broker ack 는 *재발행 성공*만 증명한다.
8. **Status 판정은 복수 superseder 를 보존해야 한다 (→ P8, §2.1-u).**
9. **replay 는 순서를 복원하지 않는다 (→ P5).** 재발행분은 로그 **끝**에 붙고, 실패했던 group 은 멱등 행이 없어 반드시 실행한다 — 도메인 상태는 이미 앞서 있다.
10. **멱등 안전창이 좌표 창과 다르다 (→ P5, §2.1-r).**
11. **ADR 처분은 채택안에 종속된다 (→ P8, 2R #9).** P2 가 위임안이면 D1 은 안 바뀌고, P3 이 위임안이면 D4 도 안 바뀐다. 확정 사실로 쓰면 안 된다.
12. **replay 후 재실패가 원장을 갈라놓는다 (→ P5·P7, N11).** `DeadLetterRecorder:54-69` 가 새 좌표로 별도 행을 `OPEN` 으로 만들고, 상관키를 읽는 경로가 없다(§2.1-aa) → 원래 행은 종결처럼 보인다.
13. **`replay_deadline` 의 기준시각과 상속이 함정이다 (→ P5, N12).** 다른 group 의 삭제 시각은 그 서비스 로컬 기준이라 조회 불가하고, `originalTimestamp` 는 nullable 이며, **재발행분이 다시 DLT 로 가면 timestamp 가 리셋된다**(§2.1-bb) — 실패할 때마다 안전창이 연장되는 표면이 생긴다.
14. **ADR-0018 이 두 개념을 결합해 논증했다 (→ P3, 자체 발견).** `0018:48`·`:161` 이 프로비저닝 소유와 `1 topic = 1 producer` 를 한 논증에 묶었다 — **replay 가 그 결합을 처음 끊는다**. 한편 `0018:230` 은 *"기존 결정을 부정하지 않고 같은 규칙으로 항목을 더하는 것은 부분 무효화가 아니다"* 라는 선례를 남겼고, replay 예외는 *항목 추가*가 아니라 *규칙에 예외를 내는 것*이라 이 선례가 그대로 적용되지 않는다.
15. **축 A/B 를 선언으로만 나누면 물리 모델에서 다시 합쳐진다 (→ P7, N13, 3R #1).** 2R 수정이 만든 결함이다. P5 는 *소비 재실패*를, P7 은 *발행 실패*를 같은 `REPLAY_FAILED` 로 보내는데 원장은 `status` 컬럼 **하나**뿐이다(`DeadLetterRecord:98-105`) — 같은 값이 상반된 두 사실을 가리킨다.
16. **fence 에 목적지 불변식이 없다 (→ P3·P5·P6, N14, 3R #13).** P3 예외는 `eventId`·key·payload 동일만 요구하는데 P6 는 **임의 `destination_partition`** 을 표현한다. 다른 partition 으로 발행하면 같은 key 의 순서 축을 잃고, 원장 소유 서비스가 허용 범위를 넘어 임의 주입할 수 있다.
17. **안전 여유가 설정으로 강제되지 않는다 (→ P4·P5, N15, §2.1-cc).** `retention == dlq-replay-window` 라 여유가 0이다. 실질 공집합은 아니지만(즉시 DLQ 건은 통과) **광고한 7일 창 전체는 보장 불가능**하다.
18. **동일 eventId 강제가 ADR-0012 D5 의 대안과 충돌한다 (→ P8, 3R #9, §2.1-dd).** D5 는 TTL 이후 수동 재처리에 *새 eventId* 를 허용한다. 본 계약은 그것을 금지·축소한다 — 별도 관계 판정이 필요하고, 코드 주석·PHASE4 서술 정정도 따라온다.

### 2.3 제약 / 트레이드오프

- **본 task 는 문서 + 최소 계약 설정만 변경한다.** replay 컬럼·마이그레이션·poller 분기·좌표 reader·관리 API·관측 회귀는 ④-c-2b 다. ADR-0018 전례와 동일하되 **P4 만 예외로 코드를 건드린다** — D4 의 선결 조건이라, ADR 이 "7일 안의 좌표는 읽을 수 있다" 를 전제하려면 그 전제가 먼저 참이어야 한다.
- **신규 ADR 이 필요한 이유는 ADR-0012 Status 변경과 별개다 (3R #11).** 두 번 반증된 설계 표면(D1~D7)과 발행 권한 예외(D8)를 확정하는 것 자체가 ADR 사안이다. **ADR-0012 D1/D4/D5 를 실제로 무효화하는지는 채택안에 종속**(§2.2-11)이며, 위임안을 택하면 Status 는 불변이다. 두 논점을 섞지 않는다.
- **자동 재발행은 영구 금지**(부모 §2.3-B · `docs/04-design-deep-dive.md:445-466`).
- **검증 환경**: 다중 브로커·토픽 재생성·compaction hole·운영 클러스터 ACL 은 단일 브로커 Testcontainers 에서 재현되지 않는다.

---

## 3. 작업 항목

> 각 항목의 산출물은 **ADR-0020 의 해당 절**이다. 결론만 적지 않고 **선택지 · 채택 · 기각 사유 · Consequences** 를 쓴다(N1).

- [x] **P1.** **D1 — 재발행 보장 수준.**
  - **비교축을 두 종류로 가른다**: *구조 변경 대안*(crash window 를 실제로 줄이는가) = ① 현행 폴링 outbox + at-least-once / ② **CDC(log-based outbox, Debezium)** — poller 의 별도 save 를 제거하나 새 인프라 의존. *비해결 대조군* = **Kafka 트랜잭션** — 단일 레코드 outbox 에서 이 창을 **줄이지 않는다**(소비자 가시성 제어일 뿐). "축소 수단" 목록에 넣지 않고 왜 답이 아닌지를 기록한다
  - 비용은 코드 사실대로(§2.1-v·x): `isolation.level` 전파는 **order·product·payment·notification 4서비스**(User 는 Kafka 미사용) · poller 는 **기존 3개 + P2 채택 시 1개** 조건부
  - **최종 보장 문구는 publication at-least-once 로 고정**하고 "중복 발행 0" 을 삭제한다(N2)

- [x] **P2.** **D2 — notification-service 의 outbox.**
  - ① *notification 에 `outbox_events` 신설 → ADR-0012 D1 개정* / ② *replay 지휘 위임 → DB 간 durable command 채널*
  - ②의 범위를 축소 기술하지 않는다 — §2.2-3 아래에서 이는 **4서비스 전부에 걸린 채널** 신설이다
  - 채택안이 D1 을 실제로 바꾸는지 판정해 P8 로 넘긴다(§2.2-11)

- [x] **P3.** **D8(신규) — replay 발행 주체와 `1 topic = 1 producer`.**
  - 대상 ADR 을 정확히 지목한다 — **ADR-0012 D4 producer 컬럼 + ADR-0018**. ADR-0011 D2 는 프로비저닝 소유라 Status 불변(§2.1-t)
  - **프로비저닝 소유 ≠ 발행 권한** 을 용어로 분리하되, **ADR-0018 이 그 둘을 결합해 논증했고 replay 가 그 결합을 끊는 첫 사례**임을 명시한다(§2.2-14). 결합을 끊는 것이 결정 내용이지 용어 정리가 아니다
  - ① *발행 권한 규약의 **명시적 예외**(원장 소유 서비스가 자기 행에 대해서만)* / ② *producer 서비스 위임*
  - **예외의 fence 를 목적지까지 못박는다 (N14, §2.2-16)**: `destination_topic == origin_topic` · `destination_partition == 검증된 origin_partition` · `key`/`payload`/`eventId` 는 원본 레코드와 **byte-for-byte 동일** · `record_kind=REPLAY` 표식 · 소유 위반·목적지 변경 시 **발행 거부**

- [x] **P4.** **D4-a — 브로커 retention 계약을 실제로 고정** (*유일한 코드 변경*).
  - **현재 실효값을 런타임 관측으로 확정**한다 — `AdminClient.describeConfigs` 로 **ADR-0020 §D4-1 이 계약화한 7개 config 전부**의 값과 `ConfigSource` 를 증적에 남긴다: `retention.ms` · `cleanup.policy` · `retention.bytes` · `segment.bytes` · `segment.ms` · `message.timestamp.type` · `message.timestamp.before.max.ms`
  - 업무·`.dlq` 토픽 전부의 `NewTopic` 에 `.config(RETENTION_MS/CLEANUP_POLICY/RETENTION_BYTES)` 선언. **값은 ADR-0020 §D4-1 이 확정했다** — `retention.ms=7d`(업무·`.dlq` 동일) · `cleanup.policy=delete` · `retention.bytes` 업무 **8 MiB** · `.dlq` **4 MiB**/파티션 · `segment.bytes` **4/2 MiB** · `segment.ms` **1d** · `message.timestamp.type=CreateTime` · `message.timestamp.before.max.ms` **= `app.idempotency.retention` 에서 유도**(현재 9d; 하한 7d 5m 은 §D4-3 fail-fast 가 강제) (도메인 토픽 정상상태 약 **420 MiB**, hard bound 아님; PVC 안전성 **미증명**). `retention.ms` 는 `app.idempotency.floor.kafka-topic-retention` 과 **같은 출처에서 유도**한다
  - **동작 변경 여부를 나눠 보고한다** — `retention.ms`/`cleanup.policy` 명문화는 **실효 불변**(이미 Apache 기본값), **`retention.bytes` 유한값은 동작 변경**(현재 `-1`)
  - **기존 토픽 반영 경로** — `spring.kafka.admin.modify-topic-configs=true`. ADR-0007 상 동작 규약이므로 **base 또는 Java Config**(프로파일 금지)
  - **미선언 config 의 처분**을 관측하고 acceptance 로 고정한다(§6 P4-4)
  - **용량 트레이드오프 — 두 하한을 각각 증명한다**(§2.1-s·z): ① 파티션별 = 토픽·파티션별 최악 유입 × 7일 ≤ `retention.bytes` ② 디스크 = ①을 **모든 파티션 × 복제수**로 합산 + segment/인덱스 여유 ≤ PVC usable. ①을 만족해도 ②가 깨지면 브로커가 죽는다. **"유한 `retention.bytes` + 디스크 경보" 는 보장 수단이 아니다**(경보는 용량을 늘리지 않는다 — 운영 보조). 결론은 PVC 증설 또는 **best-effort 강등** 중 하나
  - **안전 여유를 설정으로 강제한다 (N15, §2.2-17)**: `retention ≥ dlqReplayWindow + clockSkewBudget + cleanupSafetyBudget` fail-fast. **값은 ADR-0020 §D4-3 확정** — `clockSkewBudget=5m` · `cleanupSafetyBudget=1d` · **`retention` 7d → 9d**(4서비스 전부). 현재 등호(7d==7d)는 이 규칙에서 red 다
  - **배포 게이트**: 로컬 성공은 운영 Kafka 의 `ALTER_CONFIGS` 권한·기존 dynamic override·서비스별 배포를 증명하지 않는다. 서비스 배포 순서 · 권한 실패 시 중단 조건 · 이전 dynamic config 복구 명령 · 롤백 검증을 적는다
  - ~~`.dlq` retention 이 원본과 같아야 하는지 결정~~ → **ADR-0020 §D4-1 이 확정**(`retention.ms` 동일 7d · `retention.bytes` 는 절반 — `.dlq` 는 예외 경로라 유입이 적다)

- [x] **P5.** **D4-b + D5 — 좌표 유효성 · 금지 정책 · 재적용 의미론.**
  - **좌표 검증**: replay 전 `AdminClient` 로 `cleanup.policy`·`retention.ms`·beginning/end offset 조회 → 범위 밖이면 종결. 읽은 뒤 **반환 offset == 요청 offset** 검증(compaction hole 에서 seek 은 다음 레코드를 준다)
  - **금지 축 6개 독립 조건 (N6)**: ① `event_id IS NULL` ② `failed_consumer_group='__unknown__'` ③ `origin_kind='DLQ_ORIGIN'`(좌표가 `.dlq` 자신 → **replay 하면 `.dlq` 내용이 원본 토픽에 주입된다**) ④ 좌표 무효 ⑤ 토픽/eventType 별 `replayPolicy`(`payloadTruncated` 와 **독립 컬럼**). 조합별 처분도 표로
  - **멱등 안전창과 기준시각 (N12, §2.2-13)**: 보수적 계산식 · 신뢰할 timestamp 출처와 순위 · `originalTimestamp` nullable 처분 · 미래값 거부 · clock skew 여유. **`replay_deadline` 은 루트 사건에서 1회 계산해 모든 자식 행·attempt 가 상속하고, 재계산을 금지한다**(§2.1-bb — 안 하면 실패할 때마다 창이 연장된다). 대안으로 replay outbox 에 `source_record_timestamp` 를 두어 원본 timestamp 를 보존
  - **모든 group 재전달 불변식 (N4)**: 무해성의 근거는 오직 `processed_events (event_id, consumer_group)` → **동일 `eventId` 보존 필수**
  - **순서 역전과 재적용 (§2.2-9)**: `replayPolicy` 에 상태 사전조건 · 늦은 이벤트 허용 여부
  - **재실패의 귀착 (N11, §2.2-12)**: ① **replay 상관키**가 *발행 헤더 → 업무 실패 → DLT 헤더 → 원장* 까지 보존되는 계약 ② 원래 행과 연결하는 규칙 ③ 새 DLQ 행의 처분. **③이 "링크만" 이면 조상 해소 범위가 미정이므로**(3R #5) `root_record_id` 로 canonical incident root ↔ replay attempt 를 나누고 **성공 시 root 와 활성 자식의 종결을 원자적으로 전파**한다. 보존/병합/링크 각 안에 **backlog 카디널리티와 성공 후 잔여 unresolved 행 수**를 비교축으로 둔다
  - **상관키 헤더 계약 (N11 보안면, 3R #4, §2.1-aa)**: 표준 `DLT_*` 는 계속 제외하되 **replay correlation header 를 allowlist 에 명시**한다. 값은 *전역 UUID attempt ID + ledger owner + target consumer group + root incident ID* 를 담거나 서버가 검증 가능한 opaque token 으로 하고, **현재 group/owner 가 일치할 때만 읽는다** — 단순 숫자 원장 ID 만 실으면 모든 서비스가 같은 replay 를 받는 구조에서 **다른 DB 의 동명 ID 를 잘못 갱신하거나 위조 헤더로 임의 행을 전이**시킬 수 있다

- [x] **P6.** **D3 — replay 레코드의 표현.**
  - ① *`outbox_events` additive 컬럼 + poller kind 분기* / ② *별도 `replay_outbox` + 별도 poller*(poller·스케줄러·메트릭·retention 잡이 2벌)
  - 필요한 표현: `record_kind` · `destination_topic` · **nullable 임의 key** · `destination_partition` · header allowlist · raw payload · **`source_record_timestamp`**(P5 deadline 상속용)
  - **추적키 분리**: outbox 행의 `event_id`(unique, 발행 추적) ↔ **재발행 대상 payload 의 `eventId`**(보존 필수) 를 서로 다른 컬럼으로
  - **마이그레이션 표를 둘로 가른다 (§2.1-x)**: *outbox* = 현재 **3 DB**, P2 ① 채택 시 **4 DB** / *원장(`dead_letter_records`)* = **항상 4 DB**(order V6·product V5·payment V5·notification V3). 컬럼별 **expand → deploy → 검증 → (선택) contract** · 기존 행의 기본 의미 · nullable→NOT NULL 시점 · 구버전 호환
  - **fence 컬럼은 조건부다 (3R #12)**: `@Version` 은 P7 이 세 fence 안 중 그것을 택했을 때만 필요하다. **원장 공통 컬럼과 채택안 조건부 컬럼을 분리**하고 각 안의 DDL·잠금 비용을 대조한다

- [x] **P7.** **D6 + D7 — 상태 축 · 종결 실행자 · 관측 회귀 · 운영 진입점.**
  - **두 축을 물리적으로 분리한다 (N13, §2.2-15)** — 선언만으로는 안 된다. *발행 축* = outbox 상태 또는 별도 `publication_status` / *사건 축* = `resolution_status`. 최소한 **`PUBLISH_FAILED` 와 `CONSUMPTION_FAILED` 를 구분**하고 **두 축의 곱에 대한 전이표·불변식**을 만든다
  - **`RESOLVED` 의 근거 (N9)**: ① *업무 consumer 가 상관키를 받아 **비즈니스 처리와 같은 트랜잭션에서** 성공 확인 기록 → `RESOLVED` 존재* / ② *소비 성공 확인 없음*
  - **선택지 ②에는 나가는 전이가 있어야 한다 (3R #2)** — 현재 purge 는 `DISCARDED` 만 대상이고 미결 행은 삭제하지 않는 계약이라(`Repository:90-96`, `MaintenanceScheduler:20-23`), ②를 그대로 두면 **성공한 사건이 감소하지 않아 oldest-age 가 영구 고정**된다. ②를 채택 가능하게 두려면 `REPLAY_PUBLISHED → DISCARDED/MANUALLY_RESOLVED` 수동 종결 절차(증거·권한·SLA·purge 시점)를 정의하고, 그 증거를 만들 방침이 아니면 **②를 기각하고 소비 성공 확인을 필수로 한다**
  - **관측·정리 회귀 (N10)**: 새 상태별 **unresolved / terminal / purge 분류표**를 만들고 `countUnresolved`·`findOldestUnresolvedOccurredAt`·`findStaleUnresolved`·purge 쿼리·`DeadLetterMetrics`·`DeadLetterMaintenanceScheduler`·actuator·경보·runbook 쿼리 변경을 2b 산출물로 명시. 회귀 테스트는 `REPLAY_FAILED`·장기 `REPLAY_REQUESTED` 만으로 부족하며 **②에서 `REPLAY_PUBLISHED` 가 backlog·age 에 계속 잡히는지**가 핵심 단언이다
  - **종결 주체**: 원장 ↔ outbox 를 잇는 `outbox_event_id` · 전이 주체 1종 · outbox `FAILED` 소진 시 처분
  - **동시 요청 fence (§2.1-q)**: 조건부 상태 UPDATE / 행 잠금 / `@Version` 중 하나로 **outbox 행이 하나만 생성**됨을 계약
  - **진입점 1종 확정**: 운영 CLI vs Actuator `@WriteOperation`(§2.1-l). 인증·권한·트랜잭션 경계 포함. **직접 SQL 상태 변경 금지** — 2a 에서 runbook 이 도메인 가드를 우회하도록 지시하던 결함이 실제로 났다

- [x] **P8.** **ADR 작성 · Status 판정 · 문서 배선.**
  - `docs/adr/0020-dlq-replay-contract.md` (`docs/adr/template.md` 준수) + `docs/adr/README.md` 인덱스 행
  - **ADR-0012 — 처분은 채택안 종속 (§2.2-11)**: D1 은 P2 ①일 때만, D4 는 P3 ①일 때만 바뀐다. 위임안이면 **Status 불변**. 무효화한다면 **ADR-0016·ADR-0020 두 superseder 를 모두 보존**하는 표기(N7)
  - **ADR-0012 D5 관계 판정 (N7, §2.2-18)**: D5 가 허용한 *"TTL 이후 새 eventId 발행"* 대안을 본 계약이 **금지·축소·refine 하는지** 명시적으로 판정한다. 판정 결과에 따라 `StockReservationService` javadoc 의 *"DLQ 재발행(새 eventId)"* 서술과 `docs/progress/PHASE4.md` 의 같은 서술 정정을 2b 영향 파일에 포함
  - **ADR-0018**: `:48`·`:161` 과의 관계 판정. **`:230` 선례**(같은 규칙으로 항목 추가 = 부분 무효화 아님)와 대조하되, replay 예외는 *규칙에 예외를 내는 것*이라 그대로 적용되지 않음을 논증한다
  - **ADR-0011**: Status 불변, 관계 설명만
  - `task-impl4-c2b-dlq-replay.md` §2 를 "ADR-0020 이 확정함" 으로 갱신하고 §4 착수 조건 1·2 를 닫는다
  - `docs/runbooks/dlq-recovery.md` §6 은 그대로 두고 링크만 계획서 §2 → ADR-0020 으로 교체
  - `docs/04-design-deep-dive.md:445-466` 에 `(see ADR-0020)` 참조 추가

---

## 4. 영향 파일

| 대상 | 경로 | 항목 |
|---|---|---|
| **신규 ADR** | `docs/adr/0020-dlq-replay-contract.md` | P8 |
| ADR 인덱스 | `docs/adr/README.md` | P8 |
| ADR Status/관계 | `docs/adr/0012-phase4-db-event-saga-contract.md`(D1/D4/D5) · `0018-compensation-refund-contract.md` · `0011-phase4-multimodule-structure.md`(관계 설명만) | P8 |
| 계획서 갱신 | `docs/plans/task-impl4-c2b-dlq-replay.md` §2·§4 | P8 |
| runbook | `docs/runbooks/dlq-recovery.md` §6 (링크만) | P8 |
| Layer 1 | `docs/04-design-deep-dive.md:445-466` | P8 |
| **토픽 config (코드)** | `order-service/.../OrderKafkaConfig.java` · `product-service/.../ProductKafkaConfig.java` · `payment-service/.../PaymentKafkaConfig.java` | P4 |
| **admin 설정 (코드)** | 4서비스 base `application.yml` 또는 Java Config (`spring.kafka.admin.modify-topic-configs`) | P4 |
| **retention 여유 규칙 (코드)** | `common/.../IdempotencyRetentionProperties.java` + 4서비스 `application.yml` 의 `app.idempotency.*` | P4 |
| 검증 (신규 테스트) | P4-2/3/4 용 Testcontainers 통합테스트 (소유 모듈은 P4 착수 시 결정) | P4 |
| 증적 | `docs/progress/evidence/adr0020-topic-config-<date>.md` | P4 |

> **2b(범위 밖)에서 바뀔 파일**은 여기 넣지 않는다 — 원장 4 DB 마이그레이션·`DeadLetterRecord`·`DeadLetterRecorder`·`DeadLetterRecordJpaRepository`·`DeadLetterMetrics`·`DeadLetterMaintenanceScheduler`·`DeadLetterEndpoint`·outbox poller·`StockReservationService` javadoc·`docs/progress/PHASE4.md`. ADR Consequences 에 목록으로 남긴다.

---

## 5. PR 분할

| PR | 범위 | 분할 근거 |
|---|---|---|
| **④-c-2b-adr** | P1·P2·P3·P5·P6·P7·P8 | 문서만. 리뷰 축 = 결정의 정합성 |
| **④-c-2b-0** | P4 | 유일한 코드 변경. 리뷰 축 = *배포된 브로커에 실제로 적용되는가* |

순서 고정: ADR 이 값을 정하고 → P4 가 고정한다. 뒤집으면 P4 가 근거 없는 숫자를 박는다. P4 를 2b 본체로 미루지 않는 이유는 2b 의 `/plan` 이 "7일 안의 좌표는 읽힌다" 를 **전제로** 쓰이기 때문이다.

---

## 6. 검증 방법

> **모든 행은 유형을 정확히 하나 단다 (N8).**
> - **[구조]** — 문서 결정. **선택지 / 채택 / 기각 사유 / Consequences 구조가 갖춰졌는가**로 판정. 실패 주입 대상이 아니다.
> - **[변이]** — 실행 표면(코드·설정·운영 주장). **실제 mutation 을 가해 red 가 뜨는지**로 판정. red 조건 없는 [변이] 행은 미완이다.
> - **[측정]** — 판정이 아니라 다른 행의 **대조 기준**을 만드는 관측. **다른 [변이] 행이 그 기준을 소비할 때만** 허용한다. 단독 [측정] 행은 검증이 아니다.

| ID | 유형 | 검증 | red 조건 / 변이 |
|---|---|---|---|
| V-P1 | [구조] | 보장 문구에 "중복 발행 0" 이 없고, Kafka 트랜잭션이 *비해결 대조군*으로 분리됐으며, 비용 수치가 §2.1-v·x 와 일치(4서비스 · poller 3+조건부 1) | exactly-once 주장 시 N2 위반. 트랜잭션을 "축소 수단" 목록에 넣으면 2R #5 재발 |
| V-P2 | [구조] | ②의 범위가 4서비스 채널로 기술 · 채택안이 D1 을 바꾸는지 판정 | 축소 기술이면 되돌린다 |
| V-P3 | [구조] | 프로비저닝 소유 ↔ 발행 권한 분리 · **ADR-0018 이 둘을 결합했고 replay 가 끊는다는 판정**(§2.2-14) · 채택안·기각 사유·Consequences · **목적지 fence(topic/partition/key/payload/eventId)** | 결합 사실 없이 "대상 ADR 정정" 으로 끝나면 미충족. 목적지 fence 누락 시 N14 위반 |
| V-P4-1 | [측정] | 기준선 — `describeConfigs` 로 업무 토픽 1 + `.dlq` 1 의 **7개 config 전부**(§D4-1) 값과 `ConfigSource` 를 증적에 기록 | *(V-P4-2/3 이 이 값을 대조 기준으로 소비한다)* |
| V-P4-2 | [변이] | 신규 토픽 경로 — 없는 토픽명으로 `NewTopic` 선언·기동 → **7개 config 각각**의 선언값 + `DYNAMIC_TOPIC_CONFIG` 단언 | **config 를 하나씩 제거·변조할 때마다 각각 red** 여야 한다. 3개만 단언하면 `segment.*`·`message.timestamp.*` 미적용이 green 으로 통과한다(3R #2) |
| V-P4-3 | [변이] | 기존 토픽 경로 — 지속되는 단일 Testcontainers 브로커에 ① 옛 config 로 토픽 생성 → ② 새 선언 + `modify-topic-configs=false` 컨텍스트 → **옛 값 유지 단언** → ③ `true` 컨텍스트 순차 기동 → **7개 전부** 새 값 단언 | ②가 새 값을 보이면 테스트 무효 → 즉시 실패 |
| V-P4-4 | [변이] | 미선언 config 처분 — `NewTopic` 에 없는 config 를 dynamic 으로 심고 `true` 기동 → **보존되면 pass** | **삭제되면 fail 이며 red 를 유지한다** — (a) 모든 dynamic config 를 선언적으로 소유하거나 (b) modify 전략을 교체할 때까지. "사실대로 적으면 통과" 는 판정이 아니다 |
| V-P4-5 | [변이] | 브로커 선언값과 `kafka-topic-retention` 이 갈라지면 기동 실패/lint red | 한쪽만 바꿔 red 확인 |
| V-P4-6 | [변이] | **안전 여유 규칙** — `retention ≥ dlqReplayWindow + clockSkewBudget + cleanupSafetyBudget` fail-fast | **현재 값(7d==7d)으로 기동 시 red** 여야 한다. 통과하면 규칙이 등호를 막지 못한 것(N15) |
| V-P4-7 | [구조] | 용량 판정 — 파티션별 하한과 디스크 하한이 **각각** 산정됐는가. 못 대면 보장 문구가 best-effort 로 낮아졌는가. 디스크 경보가 보장 수단이 아닌 운영 보조로 분류됐는가 | 하나라도 누락이면 미충족 |
| V-P4-8 | [변이] | 배포 게이트 — 운영 적용 시 **`ALTER_CONFIGS` 권한 실패를 실제로 주입**해 배포가 중단되고 롤백 명령이 동작하는지 확인 | 권한을 뺀 자격증명으로 적용 시도 → 중단·명확한 실패 메시지가 안 나오면 red. *운영 적용 자체는 §8-4 게이트* |
| V-P5-a | [구조] | 금지 축 **6종**이 독립 조건 + 조합 표 · `payloadTruncated` 가 금지 사유에 **없음** · `original_timestamp IS NULL` 이 금지 축에 있고 **`occurred_at` fallback 이 없는가** | `payloadTruncated` 가 남거나 fallback 이 살아 있으면 N6 위반 |
| V-P5-b | [구조] | `origin_kind='DLQ_ORIGIN'` 금지 명시 | 누락 시 `.dlq` 내용이 원본 토픽에 주입되는 경로가 열린다 |
| V-P5-c | [구조] | `replay_deadline` — **단일 계산식**(`original_timestamp + window`, fallback 없음) · NULL 은 금지 축 6 · **미래값은 `now + clockSkewBudget` 까지 허용하고 `now` 로 clamp** · **루트 1회 계산 + 자식 상속 + 재계산 금지** | 누락 시 N12 위반. "미래값 무조건 거부" 와 `clockSkewBudget` 을 함께 적으면 모순(3R #7) |
| V-P5-d | [구조] | 모든 group 재전달 불변식(동일 `eventId` 필수) + 순서 역전/늦은 이벤트 처분 | 누락 시 N4 위반 |
| **V-N11** | [구조] | 재실패 귀착 — 상관키 보존 경로(발행→업무 실패→DLT→원장) · 원래 행 연결 규칙 · 새 행 처분 · **`root_record_id` 로 root↔attempt 분리와 원자적 전파** · 보존/병합/링크의 backlog 카디널리티 비교 | 링크만 택하고 조상 해소 범위가 없으면 미충족(3R #5) |
| **V-N11-s** | [구조] | 상관키 헤더 — allowlist · **대조 정본이 원장(`last_replay_attempt_id`)이고 outbox 가 아님**(수명 경쟁) · 한 트랜잭션 안에서 owner·**실제 DLT group**·root·destination topic·kind 전부 대조 · 음성 테스트 7종 + 수명 경쟁 테스트 | outbox 를 정본으로 삼으면 3R #4 재발. 실제 DLT group 대조가 없으면 group 바꿔치기가 통과한다(3R #3) |
| **V-N12** | [구조] | deadline 경계 규칙이 **null · 과거 · 미래 · skew · DLQ 지연** 각각에 대해 처분을 갖는가 · **재실패 시 비연장** 단언이 2b 검증 항목으로 명시됐는가 | 하나라도 누락이면 N12 위반 |
| V-P6 | [구조] | 추적키 2종이 다른 컬럼 · **outbox(3~4 DB) ↔ 원장(항상 4 DB) 표 분리** · 컬럼별 expand→contract · **fence 컬럼이 채택안 조건부**(`@Version` 을 무조건 필수로 쓰지 않음) | 원장 변경을 outbox 표에 섞으면 2R #3 재발. `@Version` 무조건이면 3R #12 재발 |
| V-P7-a | [구조] | **두 축이 물리적으로 분리**됐는가(`publication_status` ↔ `resolution_status`, 또는 `PUBLISH_FAILED` ≠ `CONSUMPTION_FAILED`) + 두 축 곱의 전이표·불변식 | 단일 `status` 로 둘을 표현하면 N13 위반 |
| V-P7-b | [구조] | `RESOLVED` 근거가 ack 가 아님 · 상태 집합이 채택안 조건부 · **②를 남긴다면 `REPLAY_PUBLISHED` 의 수동 종결 절차(증거·권한·SLA·purge)가 있는가** | ②에 나가는 전이가 없으면 3R #2 위반 |
| V-P7-c | [구조] | 새 상태별 unresolved/terminal/purge 분류표 + 쿼리·메트릭·경보·runbook 변경 목록 · **`REPLAY_PUBLISHED` 가 backlog·age 에 계속 잡히는 단언** · **`root_record_id` 전환 구간 계약**(expand 는 `IS NULL OR = id` → backfill → 무결성 검증 → contract) | 누락 시 N10 위반. 전환 계약 없이 `root_record_id = id` 를 바로 걸면 **기존 미결이 전부 0으로 사라진다**(3R #1) |
| V-P7-d | [구조] | 동시 요청 fence 계약 · 진입점 1종 확정 · 직접 SQL 금지 문구 | 어느 하나 누락이면 미충족 |
| V-P8-a | [구조] | ADR-0012 처분이 **채택안 종속**(위임안이면 Status 불변) · 무효화 시 **ADR-0016·ADR-0020 두 superseder 보존** · **D5 새-eventId 대안 판정** · ADR-0018 `:230` 선례 대조 · ADR-0011 Status 불변 | D5 판정 누락 시 N7 위반(§2.2-18) |
| V-P8-b | [변이] | 문서 배선 정적검사 — `grep -n "task-impl4-c2b-dlq-replay.md.*§2" docs/runbooks/dlq-recovery.md` 무히트 · README 인덱스 행 존재 · 2b 계획서 §4 조건 1·2 종결 | 히트하거나 누락이면 red |
| V-lint | [변이] | `hpx_plan_lint task-adr0020-dlq-replay-contract` OK (`.claude/scripts/lib/sync.sh:27-46` — 필수 섹션 + `- [ ] **P1.**` 연속 stable id) | 3R #10 이 실제 실행으로 3건 실패를 확인했다. red 면 미완 |
| V-build | [변이] | **`settings.gradle` 에 포함된 전 subproject 빌드 그린**(현재 10개 — §2.1-w, 설정 파일과 대조) · lint 전종 그린 (P4 가 `NewTopic` 을 건드리므로 `dead-letter-schema-parity-lint`·토픽 계약 테스트 회귀) | 모듈 목록 하드코딩으로 두 모듈을 빠뜨려도 통과하면 2R #8 재발 |

---

## 7. 완료 조건

1. §1 의 **N1~N15** 가 전부 거짓임을 §6 의 유형별 판정으로 보일 수 있다.
2. `docs/adr/0020-dlq-replay-contract.md` 가 **D1~D7 및 D8** 전부에 대해 선택지·채택·기각 사유·Consequences 를 갖는다.
3. ADR-0012(D1/D4/**D5**, 복수 superseder 보존)·ADR-0018 의 Status/관계가 **채택안에 맞게** 판정·갱신되고 README 가 반영한다. ADR-0011 Status 불변.
4. P4 의 retention 계약이 기존 토픽에서 적용됨을 V-P4-3 의 red→green 으로 확인했고, 미선언 config 처분이 V-P4-4 acceptance 로 고정됐으며, **안전 여유 규칙이 현재 값(7d==7d)에서 red** 임을 V-P4-6 으로 확인했고, 운영 적용은 배포 게이트(V-P4-8 + §8-4)로 명시됐다.
5. `hpx_plan_lint` OK · `settings.gradle` 전 subproject 빌드 그린.
6. **부모 계획 `task-impl4-c2b-dlq-replay.md` §4 의 착수 조건 1·2·3 이 모두 충족**되어(3 = 2a 머지, 이미 충족) **조건 4(`/plan` 재실행)를 실행할 수 있다.**

---

## 8. 미해결 (범위 밖 · 처분)

1. **replay 구현 일체** — 컬럼·4 DB 마이그레이션·poller kind 분기·좌표 reader·상관키 보존 통합테스트·관리 API·관측 회귀·리허설. **④-c-2b** 소관.
2. **다중 브로커·복제 상황의 좌표 유효성** — 단일 브로커에서 재현 불가. `replicas(1)` 범위에서만 검증하고 한계를 Consequences 에 명시.
3. **compaction hole 실재현** — 전 토픽 `cleanup.policy=delete` 로 고정되면 발생하지 않는다. 좌표 검증 로직은 유지(정책이 바뀌면 조용히 깨지는 전제라서). 실재현 미수행.
4. **GKE 실브로커 적용** — 로컬 compose/Testcontainers 로만 검증한다. 실클러스터 적용·`ALTER_CONFIGS` 권한 확인은 구현 ③ PR3d-b-2 GKE 세션에 합류시킨다.
5. **`.dlq` 토픽 partition 수(현재 1)** — 변경은 재생성을 요구하고 `topic_generation` 계약을 건드린다.
6. **PVC 증설 실행** — V-P4-7 이 필요하다고 판정하면 매니페스트 변경은 인프라 변경으로 분리한다(용량 산정 근거를 ADR 에 남기는 것까지가 본 task).
7. **3R 잔여 P1 12건 중 설계 사안** — 상태 모델·상관키·deadline 상속·root 전파는 본 계획이 **결정 대상으로 등록**(P5·P7·N11~N14)했을 뿐 답을 확정하지 않았다. 답은 ADR 본문의 Alternatives/Consequences 에서 낸다.

---

## 정정 이력 (계획 리뷰 1R 13건 · 2R 9건 · 3R 15건 — 전량 반영)

초안이 무엇을 틀렸는지 남긴다. 조용히 고치지 않는다.

### 1R — 내 전제 3개가 반증됐다

| 초안의 진술 | 반증 | 처분 |
|---|---|---|
| "quarantine 행은 **정의상 `eventId` 판독 불가**" | **거짓**. quarantine 조건은 `failed_consumer_group=='__unknown__'`(`DlqOrigin:41-63`)이고 `eventId` 는 `DeadLetterRecorder:57` 이 group 과 독립 추출. 좌표 출처는 제3의 축 | §2.1-o · 금지 축을 **5개 독립 조건**으로 · N6 재작성 |
| "replay 는 **ADR-0011** producer-owns-topic 과 충돌" | **오지목**. ADR-0011 D2:71 은 NewTopic 프로비저닝 소유. 발행 권한 SSOT 는 **ADR-0012 D4 + ADR-0018** | §2.1-t · P3/P8 대상 교체 · ADR-0011 Status 불변 |
| D1 선택지 ②를 "**Kafka 트랜잭션(EOS)**" 으로 두고 exactly-once 대안처럼 제시 | **성립 안 함**. Kafka 내부 원자성이며 `OutboxPollingService:83-86` 의 DB↔Kafka 경계를 없애지 못한다 | P1 재작성 — 비해결 대조군으로 분리, CDC 를 실제 대안으로 |
| P4-4 를 "**사실대로 적으면**" 통과로 설계 | **자기 명제 N8 위반**. 위험한 결과도 green | acceptance criterion 으로 격상 |
| §2.1-a 라인 `85-88` · §2.1-b 컬럼 목록 | crash window 는 **83-86** · `last_attempted_at`·`created_at` 누락 | 표 수정 |

### 2R — **1R 수정이 만든 새 결함 4건**

| 1R 이 만든 것 | 반증 | 처분 |
|---|---|---|
| P7 이 `RESOLVED` 를 **무조건 추가**하면서 선택지 ②는 *`RESOLVED` 를 두지 않는다* 고 적음 | **직접 모순**. 게다가 ②가 broker ack 상태를 terminal 로 삼아 N9 가 막으려던 조기 종결을 이름만 바꿔 재도입 | 발행 lifecycle(축 A) ↔ 사건 resolution(축 B) 분리 · 상태 집합을 채택안 조건부로 |
| `replay_deadline` 을 "원장 기준이든 원본 발생시각이든" 으로 열어둠 | 기준시각 선택이 곧 안전성이다 | N12 신설 · 보수적 계산식을 ADR 결정으로 격상 |
| 마이그레이션을 outbox 기준 "3~4 DB" 로만 기술 | 원장은 채택안과 무관하게 **항상 4 DB** | 표를 둘로 분리(§2.1-x) |
| P4 "유한 `retention.bytes` 면 7일 전에 지워진다" 단정 | 값이 파티션별 유입량보다 작을 때만 참 | 파티션별 하한 ↔ 디스크 하한 별도 증명 · 경보는 보장 수단 아님 |
| N8 과 §5 가 자기모순(문서 결정에 실패 주입 요구) | — | [구조]/[변이]/[측정] 분리 |
| 코드 사실 | Kafka 사용 서비스 **4**(User 는 `KafkaAutoConfiguration` 제외) · 빌드 모듈 **10**(`settings.gradle`) | §2.1-v·w · §6 전역 행 교체 |

### 3R — **2R 수정이 만든 새 결함**

| 2R 이 만든 것 | 반증 | 처분 |
|---|---|---|
| 축 A/B 를 **선언으로만** 분리 | **물리 모델에서 다시 합쳐졌다** — P5 는 *소비 재실패*를, P7 은 *발행 실패*를 같은 `REPLAY_FAILED` 로 보내는데 원장은 `status` 컬럼 하나뿐(`DeadLetterRecord:98-105`) | N13 신설 · `publication_status` ↔ `resolution_status` 물리 분리 · `PUBLISH_FAILED` ≠ `CONSUMPTION_FAILED` |
| 선택지 ②를 "`REPLAY_PUBLISHED` 를 terminal 도 non-unresolved 도 아니게" | **나가는 전이가 없다**. purge 는 `DISCARDED` 만 대상이라 성공한 사건이 감소하지 않고 **oldest-age 가 영구 고정**된다 | 수동 종결 절차를 정의하거나 ②를 기각 |
| `replay_deadline` 을 새 행에서 계산 | 재발행분이 다시 DLT 로 가면 `DLT_ORIGINAL_TIMESTAMP` 가 **재발행 시각**이라(§2.1-bb) 실패할 때마다 창이 연장된다 | 루트 1회 계산 + 자식 상속 + 재계산 금지 · `source_record_timestamp` |
| N11 상관키를 "보존한다" 로만 기술 | `DeadLetterRecorder` 는 **application header 를 전혀 저장하지 않는다**(§2.1-aa). 단순 숫자 ID 면 위조·타 DB 오갱신 표면 | allowlist·값 구성·owner/group 일치 판독 계약 추가 |
| 새 행 처분을 "보존/병합/**링크만**" 중 택일 | 링크만이면 **조상 해소 범위가 미정** — 자식을 재replay 할수록 미결이 누적 | `root_record_id` · 원자적 전파 · 안별 backlog 카디널리티 비교 |
| N11·N12 를 신설하고 **완료 조건에 연결 안 함** | §7 이 N1~N10 만 요구 | N1~N15 로 · V-N11/V-N11-s/V-N12 신설 |
| `[구조]/[변이]` 2종이라 쓰고 `[측정]` 을 정의 없이 사용 · 다수 행에 유형 미표기 | P4-7 이 "명시됐는가" 만 봐서 권한 실패를 주입하지 않고 통과 | 3종 정의 + 허용 조건 · **전 행에 유형 부여** · V-P4-8 신설 |
| §2.2-11(채택안 종속)과 §2.3(D1·D4 를 바꾸므로) 모순 | — | §2.3 조건화 · 신규 ADR 필요성을 Status 변경과 분리 |
| P6 가 `@Version` 을 4 DB 필수로 단정 | P7 은 fence 3안 중 택일이고 앞 둘은 version 컬럼이 불필요 | 공통 컬럼 ↔ 채택안 조건부 컬럼 분리 |
| P3 fence 가 `eventId`·key·payload 만 | P6 는 **임의 `destination_partition`** 을 표현 — 순서 축 상실·임의 주입 | N14 · 목적지 불변식(topic/partition byte-for-byte) |
| `retention` 여유를 "길게 잡는다" 로만 | 4서비스 전부 **7d == 7d** 이고 validator 는 `≥ max(floor)` 만 본다(§2.1-cc) | N15 · `retention ≥ window + skew + cleanup` fail-fast · V-P4-6 |
| 동일 eventId 강제의 ADR-0012 **D5** 관계 누락 | D5 는 *"새 eventId 발행"* 을 대안으로 허용(`0012:98`)하고 코드 주석에도 남아 있다 | §2.2-18 · P8 에 D5 판정 추가 |
| 계획서가 저장소 lint 를 통과하지 못함 | `hpx_plan_lint` 실행 결과 3건 실패 — 필수 섹션(`목표/목적`·`영향 파일`) 부재 + stable id 형식(`### P1.` ≠ `- [ ] **P1.**`) | 문서 구조 재작성 · §4 신설 · V-lint |
| D2'(발행 권한 예외)가 N1 구조 게이트 밖 | N1 은 D1~D7 만 요구 | **D8 로 승격**하고 N1·§7 에 포함 |

### 종료 판정

**수렴하지 않았다.** 라운드별 건수는 13 → 9 → 15(P1 9 → 5 → 12)이고, 매 라운드가 **직전 라운드 수정이 만든 새 결함**을 잡았다. 종료 조건(직전 라운드 새 계약 표면 무추가 + P1 = 0)을 채우지 못한 채 **3R 상한에서 사용자 판단으로 종료**했다.

잔여의 성격은 두 갈래다. **계획서 자체의 모순·누락**(축 물리 분리 미이행 · 완료 게이트 미연결 · 유형 미표기 · lint · §2.3 모순 · D8 등록)은 이 개정에서 **전부 닫았다**. 남은 것은 **상태 모델·상관키 구성·deadline 상속·root 전파처럼 답 자체가 ADR 사안인 표면**이며, 계획서는 그것들을 *결정 대상으로 등록*하는 데까지가 역할이다(§8-7). 그 답은 ADR-0020 본문의 Alternatives/Consequences 에서 낸다 — 계획서에 잠정 답을 스케치할수록 리뷰가 그 스케치를 때리는 구조였다는 것이 3R 의 관찰이기도 하다.
