# ④-c-2a — DLQ 원장 적재 + quarantine + runbook

> 부모 계획: `docs/plans/task-impl4-choreography-saga.md` **P9 · P10 · P13(나머지)**
> 형제: **④-c-2b (replay 경로)** — `task-impl4-c2b-dlq-replay.md`, **ADR 선행 필요**
> 선행: ④-a([#84](https://github.com/Kimgyuilli/PeakCart/pull/84)) · ④-b([#85](https://github.com/Kimgyuilli/PeakCart/pull/85)) · ADR-0018([#86](https://github.com/Kimgyuilli/PeakCart/pull/86)) · ④-c-1a([#87](https://github.com/Kimgyuilli/PeakCart/pull/87)) · ④-c-1b([#88](https://github.com/Kimgyuilli/PeakCart/pull/88))
> 후속: ④-c-2b → ④-d(부모 P11·P12·P14·P15)
> 리뷰 이력: 계획 리뷰 3라운드 (12 + 8 + 10건, 전량 반영) — `task-impl4-c2a-dlq-ledger.audit.md`

---

## 0. 분할 근거

3라운드 리뷰 결함 10건 중 **6건이 replay 경로**에 몰렸고(#1·#2·#3·#4·#5·#7), 그 6건은 **ADR 사안**을 포함한다 — `notification-service` outbox 신설(ADR-0012 D1 변경) · replay 전용 outbox 표현 · 원본 좌표 reader · 토픽 retention/compaction 실계약.

replay 를 분리하면 **원장 적재는 지금 닫을 수 있다**. DLQ 유입이 영속 원장에 남는 것(§1 부정형 1~4)은 replay 없이 성립하며, 그것만으로도 "휘발성 알림뿐" 인 현 상태가 해소된다.

| PR | 범위 | 상태 |
|---|---|---|
| **④-c-2a** (본 계획) | 원장 적재 · quarantine · 실패 시맨틱 · 보존/경보 · runbook 조회·종결(ACKED/DISCARDED) | 착수 |
| **④-c-2b** | replay 경로 (outbox 표현 · 좌표 reader · 종결 실행자 · retention 실계약) | **ADR 선행** |

---

## 1. 명제

**DLQ 로 빠진 메시지가 "휘발성 알림 1회" 로 끝나지 않고, 조회·추적 가능한 영속 원장에 정확히 1행으로 남는다.**

부정형 — 아래가 하나라도 성립하면 미완이다.

1. DLQ 유입 건의 잔존 여부를 **DB 조회로 판정할 수 없다**
2. 한 원본 토픽을 여러 서비스가 소비하다 한 서비스만 실패했는데 **원장에 2행 이상**이 생기거나, **아무도 기록하지 않는다**
3. DLQ listener 자신의 실패가 **`topic.dlq.dlq` 를 만들거나, 원장에 쓰지 못한 레코드를 유실시킨다**
4. **eventId 나 origin 헤더가 없는 DLQ 입력**(JSON 파싱 실패 등)이 원장에 남지 않는다
5. 운영자가 원장 1건을 **runbook 절차만 보고 `ACKED`/`DISCARDED` 로 종결시킬 수 없다**

> `RESOLVED`(replay 성공에 의한 해소)는 ④-c-2b 범위다. 2a 의 종결은 확인(`ACKED`)과 포기(`DISCARDED`)까지다.

---

## 2. 배경 — 착수 전 코드 검증

### 2.1 현재 상태 (grep 실측)

| 구성요소 | 상태 | 근거 |
|---|---|---|
| DLQ **발행** | ✅ 존재 (4서비스) | `*KafkaConfig#kafkaErrorHandler` — `DeadLetterPublishingRecoverer` + `record.topic() + ".dlq"` |
| DLQ NewTopic 선언 | ✅ 존재 | 발행 서비스가 자기 토픽의 `.dlq` 소유. notification 은 순수 consumer 라 0건 |
| **consumer group 헤더** | ✅ **이미 자동 부착** | §2.3 |
| **outbox 인프라** | ⚠️ **3서비스만** | order·product·payment 보유, **notification 미보유**(`application.yml:58` "소비 전용(outbox 미소유)", ADR-0012 D1 정합) → **2a 는 outbox 를 쓰지 않는다** |
| DLQ **알림** | ⚠️ 휘발성 | error handler 가 `log.error` + `slackPort.send` |
| DLQ **원장** | ❌ 부재 | 테이블·엔티티·Repository·Flyway 0건 |
| DLQ **소비 listener** | ❌ 부재 (운영) | `payment-service/src/test/.../DlqIntegrationTest` 의 테스트 listener 1건뿐 |
| runbook | ❌ 부재 | `docs/runbooks/` 없음 |
| 토픽 `retention.ms`/`cleanup.policy` | ❌ **미설정** | `NewTopic` 선언·docker-compose·k8s 어디에도 없음. `app.idempotency.floor.kafka-topic-retention=7d` 는 **멱등 가드용 선언값**일 뿐 실제 브로커 설정이 아니다 → 2b 의 전제 (§8) |

### 2.2 P13 처분 — 구현 완료, 실행계획 증적만 잔여

| P13 요구 | 실측 | 처분 |
|---|---|---|
| `stock_reservations` 인덱스 | `V3__stock_reservation_lease.sql:13` `idx_stock_reservations_lease (status, expires_at)`. 판정 기준이 고정 TTL → lease 로 바뀌어 `reserved_at` 은 대상 아님 (V3 주석이 이미 기록) | 구현 완료 |
| sweeper 정렬 | `StockReservationJpaRepository:40` `ORDER BY r.expiresAt ASC` | 구현 완료 |
| sweeper batch 상한 | `StockReservationService:103` → `PageRequest.of(0, sweeperBatchSize)`, `@Scheduled(fixedDelay=60_000)` 1회당 1배치 | 구현 완료 |
| DLQ 원장 스키마 | 부재 | **본 PR** |

부모 §5 P13 은 *"인덱스 적용 후 sweeper 조회 **실행계획**"* 을 요구하고(`task-impl4-choreography-saga.md:176`), 기존 `StockReservationLeaseSweepIntegrationTest` 는 limit 만 검증한다 → **증적만 본 PR 에서 받는다**(§6).

### 2.3 정정 ① — consumer group 헤더는 이미 존재한다

> **초안 정정.** 초안은 "`DeadLetterPublishingRecoverer` 가 consumer group 헤더를 안 붙인다"를 핵심 발견으로 세웠다. **틀렸다.**

`spring-kafka-3.3.14.jar` (Boot 3.5.12) bytecode 실측:
```
HeaderNames$HeadersToAdd 에 GROUP 상수 존재
생성자: whichHeaders = EnumSet.allOf(HeadersToAdd.class)   ← 기본값이 전체
값 출처: ListenerExecutionFailedException#getGroupId()
헤더명: KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP = "kafka_dlt-original-consumer-group"
```
→ 커스텀 헤더·공용 recoverer 팩토리는 만들지 않는다.

**가정으로 두지 않는다.** `getGroupId()` 는 예외가 `ListenerExecutionFailedException` 으로 감싸졌을 때만 채워지는데, 현재 4서비스는 커스텀 recoverer 람다로 `dlqRecoverer.accept(record, exception)` 를 직접 호출한다 → **왕복 테스트로 확인**(P1).

문제의 실체는 유효하다 — `payment.completed` 를 3개 서비스가 각자 group 으로 소비한다:
```
order-svc-payment-completed-group          (OrderEventConsumer:87)
product-svc-payment-completed-group        (StockConfirmConsumer:29)
notification-svc-payment-completed-group   (NotificationConsumer:52)
```

### 2.4 정정 ② — `DLT_ORIGINAL_KEY` 는 존재하지 않는다

같은 jar 실측 — `DLT_*` 13개 중 **key 값 헤더가 없다**. `DLT_KEY_EXCEPTION_*` 3종은 key **역직렬화 예외** 정보다. 원본 key 는 recoverer 가 만든 **DLQ `ConsumerRecord` 자신의 key** 로 보존된다.
→ `DlqHeaders` 입력을 `Headers` 가 아니라 **`ConsumerRecord<?, ?>`** 로 정하고 `originalKey = record.key()`(P1). null key 허용 여부와 serializer 타입을 스키마에 명시한다.

### 2.5 원장 식별자

부모 §2.3-C 의 `originalTopic + eventId + failedConsumerGroup` 은 **DLQ 의 대표 입력을 저장하지 못한다.** `KafkaMessageParser:29-36` 실측 — eventId 누락과 JSON 파싱 실패가 곧 예외다:
```java
if (root.get("eventId") == null) throw new IllegalArgumentException(...);
} catch (JsonProcessingException e) { throw new IllegalArgumentException(...); }
```
기존 `DlqIntegrationTest` 도 실패 입력으로 `invalid-json-message` 를 쓴다.

**→ origin 을 두 종류로 나눈다 (3R #6).**

| 종류 | 조건 | 물리 식별자 |
|---|---|---|
| **RESOLVED_ORIGIN** | `DLT_ORIGINAL_TOPIC`/`PARTITION`/`OFFSET` 전부 판독 가능 | `(cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, failed_consumer_group)` |
| **DLQ_ORIGIN** | origin 헤더 누락·비수치 등 판독 불가 | **DLQ 레코드 자신의** `(cluster_id, topic_generation, dlq_topic, dlq_partition, dlq_offset, failed_consumer_group)` |

두 경우 모두 **6개 컬럼이 NOT NULL** 이다 — MySQL UNIQUE 는 nullable 컬럼에서 중복을 막지 못하므로, "판독 불가면 NULL" 은 같은 poison record 를 여러 행으로 만든다. `origin_kind` 컬럼으로 두 종류를 구분한다. `failed_consumer_group` 이 없으면 sentinel `__unknown__`(§2.6-C).

`cluster_id`·`topic_generation` 이 붙은 이유(2R #4): `(topic, partition, offset, group)` 은 **토픽 재생성 시 유일하지 않다** — 동명 재생성 시 offset 이 0부터 재사용되어 과거 행과 충돌하고, `INSERT IGNORE` 때문에 **새 실패가 정상 중복으로 조용히 폐기**된다. (단순 offset reset·파티션 재할당은 좌표가 유지되므로 정상 중복 제거로 동작한다.)

### 2.6 제약 / 트레이드오프

**A. replay 는 본 PR 범위 밖이다.** 원장은 `OPEN → ACKED → DISCARDED` 까지만 전이한다. `REPLAY_*`·`RESOLVED` 상태와 그 컬럼은 **④-c-2b 가 additive 마이그레이션으로 추가**한다. 2a 의 스키마는 그 확장을 막지 않도록 설계한다(상태를 enum 제약이 아닌 `VARCHAR` + 애플리케이션 검증).

> 2R 은 replay 를 2단 상태머신으로, 3R 은 그 대안인 outbox 재사용으로 각각 시도했고 **둘 다 "중복 발행 0" 을 보장하지 못한다**는 것이 실측으로 확인됐다(`OutboxPollingService:83-86` 이 broker ack 후 별도로 `PUBLISHED` 저장 → 사이에 사망 시 재발행). 같은 주장이 두 번 반증된 표면이므로 ADR 로 올린다.

**B. group 헤더가 없는 레코드의 소유자를 지정한다 (2R #3 + 자체 정정).**
group 으로 소유권을 판정하는데 group 이 없으면 4서비스가 모두 저장하거나(§1 부정형 2 전단) 모두 skip 한다(후단).
→ **원본 토픽을 발행하는 서비스**를 단일 quarantine 소유자로 지정한다(ADR-0011 producer-owns-topic 상 이미 그 `.dlq` 의 소유자라 규칙이 안정적이다. "구독자 중 사전순 첫 서비스" 같은 규칙은 구독자가 늘면 소유자가 바뀌어 과거 행과 불일치한다).
→ **단, 발행 서비스는 자기 토픽을 소비하지 않으므로 자기 `.dlq` 도 구독하지 않는다** — §4 소비 매트릭스에 `payment` × `payment.completed.dlq` 행이 없다. 지정된 소유자가 그 레코드를 볼 수 없다.
→ **quarantine 전용 구독을 별도 listener 로 추가한다**(§4 하단). group 헤더가 **있으면 skip**, 없으면 `__unknown__` 으로 적재.

**C. listener 실패는 예외 분류별로 종착을 가른다 (2R #5).**
"DB 장애에서 offset 미커밋" 과 "poison record 무기한 비차단" 은 durable 대체 저장소 없이 **동시에 만족할 수 없다**.

| 예외 분류 | 처리 | offset |
|---|---|---|
| 헤더 판독 불가 / malformed | `DLQ_ORIGIN` 행으로 최소 정보 저장 후 진행 | **커밋** |
| DB 장애 (일시) | 유한 backoff 재시도 | 미커밋 |
| DB 장애 (재시도 소진) | **container pause + 운영 경보** — 무유실 우선 | 미커밋 |

`ackAfterHandle`·`commitRecovered`·seek 동작·재기동 절차를 코드와 runbook 양쪽에 명시하고 **offset 을 직접 검사하는 테스트**를 둔다.

**D. 알림 보장을 정직하게 적는다 (2R #7).**
DB commit 과 Slack 은 한 트랜잭션이 아니므로 at-least-once 도 성립하지 않는다 — commit 후 Slack 전 사망이면 0회다. Slack outbox 는 본 PR 범위 밖이다.
→ **적재 알림 = best-effort**. **내구적 신호는 원장 행 자체**이며 그게 이 PR 의 존재 이유다(ADR-0018 D6 도 Slack 을 보조 신호로 규정). 라우팅 알림(error handler)은 **유지**하고 제거는 후속 PR 로 넘긴다 — 지금 제거하면 listener 미배선·DB 장애 시 알림과 원장이 동시에 사라진다.

**E. `OPEN` 은 자동 삭제하지 않되 무한 방치도 아니다 (2R #8).**
`OPEN` 은 **삭제하지 않는다**. 대신 ① age 경보(N일 초과 미결) ② 건수 상한 경보 ③ 종결 건만 archive/삭제. 장기 미결은 용량 문제가 아니라 **운영 SLA 문제**다.
**경보 계약을 프로퍼티로 고정한다 (3R #10)**: age 임계값 · count 상한 · 스케줄 주기 · cooldown(반복 억제) · 전달 채널. 임계값 없는 "경보 1회" 는 mock 호출 1회로 통과하는 false-green 이다. Slack 이 일부 서비스에서 no-op 일 수 있으므로 **actuator 조회 표면(backlog 건수 · oldest age)을 본 PR 에 포함**한다 — 메트릭 계약(④-d P11) 전까지의 최소 관측 수단이다.

**F. 원장은 서비스별 DB, `:common` 은 파싱만.** ADR-0012 D1 을 깨지 않는다. 엔티티·Repository·Flyway 는 **서비스별 소유**, `:common` 에는 파싱 유틸과 불변 값 객체 + **ownership 매핑 계약**만. 대가는 **runbook 이 4개 DB 를 조회**해야 한다는 것 — 통합 뷰는 만들지 않는다(관측 통합은 ④-d).

**G. 유니크 충돌을 JPA 에서 catch 하지 않는다.** ④-c-1a 선례 — flush 시점·rollback-only 때문에 성립하지 않고, `ON DUPLICATE KEY UPDATE id=id` 는 Connector/J found-rows 시맨틱 때문에 중복에도 1 을 반환한다. → **`INSERT IGNORE`**.

**H. 자동 재발행 금지 (부모 §2.3-B 승계).** `docs/04-design-deep-dive.md:445-466` 와 정합.

### 2.7 범위 밖

- **replay 경로 전부** — ④-c-2b (ADR 선행)
- saga 메트릭 / DLQ 유입 게이지 — ④-d (부모 P11)
- cross-service E2E — ④-d (부모 P12) · 단 §6 의 topology 계약은 본 PR (3R #9)
- saga-contract CI 게이트 — ④-d (부모 P14)
- Layer 1 동기화 · TASKS ④ 종결 · P13 정정 반영 — ④-d (부모 P15)
- 라우팅 알림 제거 · Slack outbox — 후속 PR (§2.6-D)

---

## 3. 작업 항목

- [x] **P1.** **표준 헤더 판독** — `:common` 에 `DlqHeaders` 파싱 유틸 + 불변 값 객체 `DlqOrigin`. 입력은 **`ConsumerRecord<?, ?>`**(§2.4). 필드: `originKind` · 좌표 6종 · consumerGroup · exceptionType · exceptionMessage · **`originalKey`(=`record.key()`)** · originalTimestamp. 표준 `KafkaHeaders.DLT_*` 만 읽는다. **현행 커스텀 recoverer 경로에서 `DLT_ORIGINAL_CONSUMER_GROUP` 이 실제로 채워지는지 왕복 테스트로 확인**하고, 비어 있으면 `HeaderNames` 보정을 추가한다.
- [x] **P2.** **ownership 매핑 계약** — `:common` 에 "어느 서비스가 어느 `.dlq` × group 을 소유하는가" 를 **단일 출처**로 둔다(§4 의 코드 표현). 소비 소유권과 quarantine 소유권을 함께 표현하고, 두 집합이 교차하지 않음을 계약 테스트로 강제한다(3R #9).
- [x] **P3.** **원장 도메인** — 서비스별 `DeadLetterRecord` 엔티티 + `DeadLetterRecordRepository`(domain) + `*JpaRepository`/`*RepositoryImpl`(infrastructure). `:common` 에 엔티티를 두지 않는다(§2.6-F).
  상태: `OPEN → ACKED → DISCARDED`. **`REPLAY_*`/`RESOLVED` 는 두지 않는다**(§2.6-A) — 단 상태 컬럼을 `VARCHAR` + 애플리케이션 검증으로 두어 2b 의 additive 확장을 막지 않는다.
  필드: `originKind` · 식별자 6종(§2.5) · `eventId`(nullable) · `originalKey` · `originalTimestamp` · `payload`(진단용) · `payloadTruncated` · `exceptionType` · `exceptionMessage` · `status` · `occurredAt` · `acknowledgedAt` · `acknowledgedBy` · `attemptCount` · `discardedAt` · `discardedBy` · `note`.
- [x] **P4.** **Flyway** — `dead_letter_records`. **UNIQUE `(cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, failed_consumer_group)`** — **6컬럼 전부 NOT NULL**(§2.5). 인덱스 `(status, occurred_at)` · `(event_id)`. payload/exceptionMessage **컬럼 상한과 truncation 마커**를 스키마에 고정. 버전: order `V6` · product `V5` · payment `V5` · notification `V3`.
- [x] **P5.** **`cluster_id` / `topic_generation` 운영 계약** (3R #8) — `@ConfigurationProperties` 클래스 + base 설정 필수값. `cluster-id` 누락 또는 **미등록 토픽의 generation 참조 시 fail-fast**(기본값 암묵 허용 금지). 4서비스 설정 드리프트를 계약 테스트로 검출. 토픽 삭제 전 generation bump 절차를 runbook·P11 에 넣는다.
- [x] **P6.** **DLQ 소비 경로** — 서비스별 `DeadLetterConsumer`. **§4 소비 매트릭스 전 행** 구독, `consumerGroup` 이 자기 소유(P2 매핑)가 아니면 skip. 전용 `ConcurrentKafkaListenerContainerFactory`(재-DLQ 없는 error handler), group `<svc>-svc-dlq-group`. 적재는 **`INSERT IGNORE`**(§2.6-G). 재실패는 새 좌표 행 + `attemptCount`.
- [x] **P7.** **quarantine 소비 경로** — 서비스별 `DeadLetterQuarantineConsumer`(별개 listener). **§4 quarantine 표** 구독, group `<svc>-svc-dlq-quarantine-group`. **group 헤더가 있으면 skip**, 없으면 `__unknown__` 으로 적재(§2.6-B). notification 은 발행 토픽이 없어 대상 아님.
- [x] **P8.** **listener 실패 시맨틱** — §2.6-C 표의 3분기를 구현. `ackAfterHandle`·`commitRecovered`·seek·container pause 배선과 재기동 절차 명시.
- [x] **P9.** **알림** — 라우팅 알림 유지 + 원장 신규 INSERT 시 적재 알림 추가, 보장은 **best-effort 로 명시**(§2.6-D). Slack 에는 **식별자와 runbook 링크만** — payload·exception 원문 금지(P11).
- [x] **P10.** **보존 · 경보 · 조회 표면 · parity** — 종결 건 archive/삭제 주기 + cleanup 스케줄러(기존 `ProcessedEventCleanupScheduler` 선례). `OPEN` 무삭제 + **age/건수 경보(임계값·주기·cooldown·채널을 프로퍼티로 고정)** + **actuator 조회 표면**(backlog 건수 · oldest age)(§2.6-E). 4서비스 스키마 parity 테스트.
- [x] **P11.** **민감정보 · 크기 정책** — payload 최대 크기 + `payloadTruncated` 마커, 민감 헤더 제외 목록, 마스킹 규칙, 원장 접근 권한. Slack 본문에서 원문 배제(P9).
- [x] **P12.** **runbook** — `docs/runbooks/dlq-recovery.md`. 상태머신(2a 범위), **4개 DB 조회 절차**(§2.6-F 의 대가 명시), container pause 재기동 절차(§2.6-C), generation bump 절차(P5), 담당자·SLA, `DISCARDED` 사유 기록 의무, **replay 는 ④-c-2b 이전까지 불가함을 명시**.
- [x] **P13.** **배포·롤백 순서** — 아래 §7 로 확정. smoke check 추가.
- [ ] **P14.** **검증** — §6 전부 그린 + 10모듈 테스트 0 실패 + lint 10종 그린.

---

## 4. 구독 매트릭스 (P2 매핑의 정본)

각 행을 **parameterized Kafka 왕복 테스트로 강제**한다. 대표 메시지 1건 통과로는 listener/topic 누락을 못 막는다.

> 3R 리뷰가 운영 코드의 **listener 21개**(Order 6 · Product 5 · Payment 5 · Notification 5)와 group 문자열까지 대조해 일치를 확인했다.

### 소비 구독 (자기 실패분)

| 서비스 | 구독할 `.dlq` | 자기 group |
|---|---|---|
| order | `payment.requested.dlq` · `payment.completed.dlq` · `payment.failed.dlq` · `payment.refunded.dlq` · `stock.reservation.result.dlq` · `product.updated.dlq` | `order-svc-*-group` 6종 |
| product | `order.created.dlq` · `order.cancelled.dlq` · `payment.completed.dlq` · `payment.failed.dlq` · `payment.refunded.dlq` | `product-svc-*-group` 5종 |
| payment | `order.created.dlq` · `order.cancelled.dlq` · `stock.reservation.result.dlq` · `stock.compensation.requested.dlq` · `order.compensation.requested.dlq` | `payment-svc-*-group` 5종 |
| notification | `order.created.dlq` · `order.cancelled.dlq` · `payment.completed.dlq` · `payment.failed.dlq` · `payment.refunded.dlq` | `notification-svc-*-group` 5종 |

> ADR-0018 신설 토픽의 `.dlq` 3종(`stock.compensation.requested` · `order.compensation.requested` · `payment.refunded`)이 포함돼 있다(`0018:218-221` 요구).

### quarantine 구독 (group 부재분 — 별개 listener)

group 헤더가 **있는** 레코드는 skip 하고 `__unknown__` 건만 적재한다.

| 서비스 | quarantine 구독 (자기가 발행한 토픽의 `.dlq` 전체) | group |
|---|---|---|
| order | `order.created.dlq` · `order.cancelled.dlq` · `order.compensation.requested.dlq` | `order-svc-dlq-quarantine-group` |
| product | `product.updated.dlq` · `stock.reservation.result.dlq` · `stock.compensation.requested.dlq` | `product-svc-dlq-quarantine-group` |
| payment | `payment.completed.dlq` · `payment.failed.dlq` · `payment.requested.dlq` · `payment.refunded.dlq` | `payment-svc-dlq-quarantine-group` |
| notification | — (발행 토픽 0개) | — |

> 두 집합은 **교집합이 없다** — 서비스는 자기가 발행한 토픽을 소비하지 않기 때문이다. P2 의 계약 테스트가 이 불변식을 강제한다.

---

## 5. 영향 범위

| 대상 | 파일 | 항목 |
|---|---|---|
| 공용 파싱 · 매핑 | `common/.../global/kafka/` (`DlqHeaders`·`DlqOrigin`·ownership 매핑) | P1·P2 |
| 서비스 Kafka 배선 | `{order,product,payment,notification}-service/.../infrastructure/kafka/*KafkaConfig.java` | P6·P7·P8·P9 |
| DLQ consumer | 각 서비스 `infrastructure/kafka/DeadLetter{,Quarantine}Consumer.java`(신설) | P6·P7 |
| 원장 도메인 | 각 서비스 `domain/model` · `domain/repository` · `infrastructure` | P3 |
| 마이그레이션 | order `V6` · product `V5` · payment `V5` · notification `V3` | P4 |
| 설정 | 각 서비스 `application.yml` + `@ConfigurationProperties` | P5·P10 |
| cleanup / 경보 / actuator | 각 서비스 `global/` | P10 |
| 테스트 | 각 서비스 `src/test` (+ 기존 `DlqIntegrationTest` 확장) | P14 |
| 문서 | `docs/runbooks/dlq-recovery.md`(신설) · `docs/04-design-deep-dive.md` DLQ 절 | P12·P13 |

---

## 6. 검증 방법

부모 §5 의 **공통 금지** 승계 — "listener 가 배선됐다" · "테이블이 존재한다" 를 수렴 검증으로 기록하지 않는다. **실패를 실제로 주입한 뒤 DB/offset 상태로** 확인한다.

| 항목 | 성공 기준 |
|---|---|
| P1 | 현행 recoverer 경로의 DLQ 레코드에 `kafka_dlt-original-consumer-group` 이 **실제 group id** 로 존재. `originalKey` 가 **`record.key()` 로 왕복**(비-null key 포함). 헤더 부재 시 fail-open 하지 않음 |
| P2 | 소비 소유권 집합 ∩ quarantine 소유권 집합 = ∅. 매핑에 없는 (topic, group) 조합은 **컴파일/테스트 단계에서 검출** |
| P3·P4 | 같은 식별자 2회 유입 → **1행**. **eventId 없는 메시지**(`invalid-json-message`)도 1행 |
| P4 malformed 분기 | **group 누락** / **topic 누락** / **partition·offset 비수치·누락** 각각에 대해 재유입 시 **1행** + offset **커밋**. `DLQ_ORIGIN` 행의 6컬럼이 모두 NOT NULL (3R #6) |
| P4 토픽 재생성 | **토픽 삭제 후 동명 재생성 → offset 0 재사용** 시 과거 행과 충돌하지 않고 새 행 (§2.5) |
| P5 | `cluster-id` 누락 또는 **미등록 토픽 generation 참조 시 부팅 실패**. 4서비스 설정 드리프트 검출. 동일 generation 재유입 = 1행 · bump 후 offset 재사용 = 새 행 |
| P6 | 자기 group 이면 1행 · **남의 group 이면 0행**(음성 대조). listener 가 예외를 던져도 **`topic.dlq.dlq` 미생성**. §4 소비 매트릭스 **전 행** 통과 |
| P6 동시성 | **barrier 로 동시 INSERT 강제** → 최종 1행 · 승자 정상 commit · **패자 no-op 이 rollback-only 를 만들지 않음** · offset 정상 처리 |
| P7 | **group 헤더 단독 누락** → 발행 서비스 1곳만 `__unknown__` 적재. **group 이 있는 레코드는 quarantine listener 가 skip**(소비 listener 와 이중 적재 0). §4 quarantine 표 전 행 통과 |
| P8 | §2.6-C 3분기 각각을 주입하고 **offset 을 직접 검사**: malformed → 커밋 + `DLQ_ORIGIN` 1행 / DB 일시 장애 → 미커밋 후 복구 시 정확히 1행 / 재시도 소진 → **container pause + 경보**, 미커밋 유지 |
| P9 | 라우팅 알림과 적재 알림이 각각 발생하고 의미가 구분됨. 중복 유입 시 적재 알림 0회. **Slack 본문에 payload·exception 원문 0**. commit 후 Slack 실패 시 **원장은 남고 작업은 실패하지 않음**(best-effort) |
| P10 | cleanup 후 종결 건만 제거, `OPEN` 보존. **age 임계값 초과 시 경보 1회 + cooldown 내 재발송 0**. actuator 가 backlog 건수·oldest age 를 반환. 4서비스 parity 그린 |
| P11 | 상한 초과 payload 가 `payloadTruncated` 마커와 함께 저장. 민감 헤더 제외 |
| P12 | runbook 절차대로 원장 1건을 `OPEN → ACKED → DISCARDED` 로 종결하는 리허설 1회, 각 단계 DB 증적. **직접 SQL 이 아니라 `DeadLetterEndpoint` 진입점만 사용** (3R #7 false-green 방어). 사유 없는 discard·actor 부재·재전이·미지 action 이 각각 거부/멱등 |
| P13 | 마이그레이션만 적용된 구버전 앱이 정상 기동. smoke check 그린 |
| P13(부모) | **실제 Flyway 적용 MySQL 에서 EXPLAIN 이 `idx_stock_reservations_lease` 사용** 확인 (§2.2) |
| P14 | 10모듈 테스트 0 실패 · lint 10종 그린 |

**"정확히 1곳" 불변식의 증명 범위 (3R #9 — 과장 금지).**
"4서비스 중 1곳만 적재" 는 전역 불변식이지만 cross-service E2E 는 ④-d 범위다. 본 PR 은 **(P2 ownership 매핑 계약 테스트) × (서비스별 실제 Kafka 왕복 테스트)** 로 증명하며, **4서비스를 동시 기동해 확인한 것이 아님을 완료 보고에 명시한다.** 잔여 갭은 ④-d P12 에서 닫는다.

**false-green 방어** (④-c-1a/1b 전례): listener 배선은 **직접 호출이 아니라 실제 Kafka 왕복**으로 고정한다. 마이그레이션 검증은 **SQL 복제본이 아니라 `V*.sql` 파일을 직접 읽어** 실행한다.

---

## 7. 배포 · 롤백 순서 (P13 확정)

### 배포

| 단계 | 내용 | 확인 |
|---|---|---|
| ① | 4서비스 **additive migration** 적용 (order `V6` · product `V5` · payment `V5` · notification `V3`) | Flyway 이력 4건 |
| ② | 원장 listener 배포 | `actuator/deadletter` 200 응답 |
| ③ | 적재 확인 | 의도적 실패 1건 주입 → `unresolved` 증가 |
| ④ | (후속 PR) 라우팅 알림 정리 | — |

**①과 ②를 나누는 이유**: 앱이 테이블 생성 전에 뜨면 DLQ listener 가 첫 레코드에서 실패하고,
`ackAfterHandle=false` 라 offset 이 커밋되지 않은 채 컨테이너가 정지한다(§2.6-C). 유실은 없지만
불필요한 인시던트다.

### 부분 배포 중 동작

| 상황 | 결과 |
|---|---|
| 마이그레이션만 적용, 구버전 앱 | **정상**. 구버전은 `dead_letter_records` 를 모르고 `ddl-auto: validate` 는 **추가 테이블을 문제 삼지 않는다** |
| 일부 서비스만 신버전 | **정상**. 원장은 서비스별 독립이라 배포된 서비스만 적재한다. 미배포 서비스의 실패는 기존처럼 로그·알림만 남는다 |
| 신버전 앱, 마이그레이션 미적용 | **DLQ listener 정지**. `dead_letter_records` 부재로 적재 실패 → 재시도 소진 → 컨테이너 정지 + 경보. 업무 listener 는 영향 없음 |

### 롤백

- **앱만 롤백**: 안전하다. 테이블을 남겨두면 재배포 시 그대로 쓴다. 구버전은 그 테이블을 보지 않는다
- **테이블 삭제**: 하지 않는다. 미결 원장이 사라지면 그 사실을 복구할 방법이 없다
- **`app.dead-letter.*` 설정 누락 상태로 배포**: `cluster-id` 가 비면 `@NotBlank` 로 **부팅 실패**(fail-fast).
  `topic-generations` 누락은 적재 시점 예외 → 컨테이너 정지(§7 runbook)

---

## 8. 완료 조건

### §1 부정형 대비 (구현 후)

| # | 부정형 (성립하면 미완) | 불성립 근거 |
|---|---|---|
| 1 | DLQ 유입의 잔존 여부를 DB 조회로 판정할 수 없다 | `dead_letter_records` 적재 + `actuator/deadletter` backlog 조회. 통합테스트가 실제 Kafka 왕복으로 적재 확인 |
| 2 | 한 서비스만 실패했는데 2행 이상이거나 아무도 기록하지 않는다 | 소비 경로는 `ownsConsumption`(자기 group), quarantine 경로는 `ownsQuarantine`(발행 서비스·group 부재분). 두 집합 교집합 ∅ 를 계약 테스트가 강제. 음성 대조(남의 group 0행) 4서비스 전부 |
| 3 | DLQ listener 실패가 `.dlq.dlq` 를 만들거나 레코드를 유실시킨다 | 전용 factory 에 `DeadLetterPublishingRecoverer` 미배선(`.dlq.dlq` 불가) + `ackAfterHandle=false`(offset 미커밋) + 재시도 소진 시 컨테이너 정지 |
| 4 | eventId·origin 헤더 없는 입력이 원장에 남지 않는다 | `DlqPayloads.extractEventId` 는 실패 시 null(예외 없음), origin 판독 실패는 `DLQ_ORIGIN` + DLQ 자신의 좌표. 6컬럼 NOT NULL 을 lint 가 강제 |
| 5 | 운영자가 runbook 만으로 `ACKED`/`DISCARDED` 로 종결할 수 없다 | runbook §4 + `DeadLetterEndpoint` 진입점. 리허설 테스트가 그 진입점만 사용 |



- §1 부정형 5개가 전부 불성립임을 §6 각 행으로 증명
- P1~P14 그린 + 음성 대조 포함
- §4 두 매트릭스 전 행이 테스트로 강제됨
- runbook 리허설 1회 완료 (P12), **운영 진입점만 사용**
- **미충족 명시**: replay(④-c-2b) · "정확히 1곳" 의 cross-service 증명(④-d P12) · 메트릭/E2E/CI 게이트/Layer 1 동기화(④-d) · 라우팅 알림 정리(후속 PR)

---

## 9. 미해결 / 후속

| # | 항목 | 처분 |
|---|---|---|
| 1 | **replay 경로 전부** | **④-c-2b — ADR 선행** (§0) |
| 2 | 토픽 `retention.ms`/`cleanup.policy` 미설정 | 2b 의 전제 — replay 창을 주장하려면 **실제 브로커 설정**이 먼저다 (§2.1) |
| 3 | DLQ 유입 게이지·메트릭 | ④-d (부모 P11) |
| 4 | "정확히 1곳" cross-service 증명 | ④-d (부모 P12) |
| 5 | 통합 DLQ 조회 뷰 | 만들지 않음 — 관측 통합은 ④-d |
| 6 | 라우팅 알림 제거 · Slack outbox | 후속 PR (§2.6-D) |
| 7 | 자동 재발행 | 영구 금지 (§2.6-H) |
| 8 | P13 정정 사실의 문서 반영 | ④-d (부모 P15) |
