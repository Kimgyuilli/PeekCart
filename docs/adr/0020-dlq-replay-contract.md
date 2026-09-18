# ADR-0020: DLQ replay 계약 — 재발행 보장·발행 권한 예외·좌표 유효성·종결 축 분리

- **Status**: Partially Superseded by [ADR-0021](./0021-dlq-replay-correlation-anchor.md) · [ADR-0022](./0022-replay-entrypoint-rollout-and-drain.md)
  - **ADR-0022 가 무효화한 범위**: §D6-1 표의 `publication_status` **값 목록**(`PUBLISH_UNKNOWN` 추가) · §D6-2b 의 **두 축 곱 표**(같은 값의 행 추가) · §D6-4 의 **"전이 주체는 reconciler 1종으로 고정한다"**(운영자의 단방향 override 를 더해 **2종**). **I-1·I-2 와 §D6-2 의 "종결은 사람만" 은 유지**되며, §D6-4 의 `outbox_event_id` 연결·"관리 API 는 `REQUESTED` 까지만 만든다" 도 그대로다 — override 는 `REQUESTED` 를 **만드는** 것이 아니라 그것이 교착했을 때 **빠져나오는** 단방향 전이다.
  - **무효화된 범위**: §D5-4 의 **"판독 조건 = 한 트랜잭션 안의 원자 대조"** 중 **`record_kind=REPLAY` 대조**와, 같은 절 음성 테스트 목록의 **`record_kind` 불일치** 항목. `record_kind` 는 `outbox_events` 전속 컬럼이라 **같은 절이 배제한 수명 경쟁에 그대로 걸린다** — ADR-0021 이 그 축을 원장 앵커(`last_replay_attempt_id`·`last_replay_target_group`·`last_replay_payload_digest`) + fingerprint 대조로 대체한다.
  - **유지되는 범위**: §D5-4 의 나머지 전부(대조의 정본은 원장 · 헤더 불신 · 수명 경쟁 · 실제 DLT group 대조 · 어긋나면 독립 행) · §D3 · §D5-2 · §D6 · §D8. ADR-0021 은 이 원칙들의 **귀결**이지 반박이 아니다.
- **Date**: 2026-09-01
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga (④-c-2b)
- **관계**:
  - **Partially Supersedes** [ADR-0012](./0012-phase4-db-event-saga-contract.md) — **D1 Notification 행**(§D2) · **D4 producer 컬럼의 "그 토픽에 write 하는 유일한 서비스" 함의**(§D8)
  - **Partially Supersedes** [ADR-0018](./0018-compensation-refund-contract.md) — `1 topic = 1 producer` 규약(`0018:49`)의 **DLQ replay write 예외 범위**(§D8-4)
  - **Refines** ADR-0012 **D5**(TTL 이후 재처리 대안의 적용 범위 — §D5-3)
  - [ADR-0011](./0011-phase4-multimodule-structure.md) 은 **영향 없음** — D2 의 "토픽/DLQ 빈 = 발행 서비스 전속" 은 `NewTopic` **프로비저닝 소유**이며 replay 는 그것을 건드리지 않는다(§D8-1)

---

## Context

구현 ④-c-2a([#90](https://github.com/Kimgyuilli/PeakCart/pull/90))가 DLQ 원장(`dead_letter_records`)을 만들어 **미결 상태를 영속화**했다. 그러나 **원장에서 메시지를 다시 발행하는 수단은 없다** — `docs/runbooks/dlq-recovery.md` §6 이 "재발행 — 현재 불가" 와 우회 절차를 안내하고 있다.

replay 를 계획서 수정만으로 닫을 수 없었던 이유는, **같은 주장이 두 라운드 연속 반증됐기** 때문이다. "재발행하되 중복 발행 0" 을:

- 2R: `REPLAY_REQUESTED → 발행 → REPLAY_PUBLISHED` 2단 상태머신 → **발행 직후 사망 시 발행 여부 판별 불가**로 반증
- 3R: 기존 `outbox_events` 재사용 → `OutboxPollingService:83-86` 이 broker ack 후 **별도로** `PUBLISHED` 를 저장하므로 같은 crash window 존재

두 번 반증된 표면은 설계 결정 사안이다. 본 ADR 은 ④-c-2b 착수에 필요한 **D1~D7**(`docs/plans/done/task-impl4-c2b-dlq-replay.md` §2)과, 계획 검증 중 드러난 **D8**(발행 권한 예외)을 확정한다.

### C1. 착수 전 코드 사실 (검증 완료)

| 사실 | 근거 |
|---|---|
| outbox 발행에 구조적 crash window 가 있다 | `OutboxPollingService:83` 이 ack 를 받고 `:85-86` 이 상태를 **별도로** 저장 |
| `OutboxEvent` 는 replay 를 표현하지 못한다 | `buildRecord():119-125` = `new ProducerRecord<>(eventType, null, aggregateId, payload)` — 토픽=eventType 고정 · partition=null · key=`aggregateId`(NOT NULL,50자) · **timestamp 미지정** · 헤더 trace/user 뿐 |
| notification 에 outbox 가 없다 | `global/` = `config·deadletter·idempotency`, 마이그레이션 `V1·V2·V3`. ADR-0012 D1 표(`:47`)가 `processed_events` 만 할당 |
| 좌표를 읽는 컴포넌트가 없다 | `assign(`·`.seek(` production 사용 0건 |
| 브로커 retention 이 **선언**돼 있지 않다 | `.config(...)` 호출 0건, `KAFKA_LOG_RETENTION_*` 부재. **단 실효값은 Apache 기본값 7일**(`apache/kafka:3.8.1` 의 `server.properties:105` = `log.retention.hours=168`, `retention.bytes` 주석 처리, `cleanup.policy` 기본 `delete`) — 문제는 값이 아니라 **그것이 계약이 아니라는 것** |
| `NewTopic` 선언만으로는 기존 토픽이 안 바뀐다 | `spring-kafka-3.3.14` `KafkaAdmin` 의 `private boolean modifyTopicConfigs;`(기본 false)가 `createOrModifyTopics` 를 분기 |
| DLQ 토픽은 **공유**다 | `payment.completed`·`payment.failed`·`payment.refunded` 를 order·product·notification 3서비스가 각자 group 으로 소비 |
| quarantine 조건은 `eventId` 와 무관하다 | `failed_consumer_group == '__unknown__'`(`DlqOrigin:41-63`). `eventId` 는 `DeadLetterRecorder:57` 이 group 과 **독립** 추출. 좌표 출처(`DlqOriginKind`)는 제3의 축 |
| 원장은 상태 집합을 쿼리에 **리터럴로** 박았다 | `DeadLetterRecordJpaRepository:77-95` 의 `status IN ('OPEN','ACKED')`, purge 는 `status='DISCARDED'`(`:90-96`) |
| 원장은 application header 를 저장하지 않는다 | `DeadLetterRecorder:25-29`. `DeadLetterPublishingRecoverer` 가 header 를 보존함은 확인됐다(`payment-service/.../DlqIntegrationTest:147-173`) |
| 멱등 이력의 시각은 서비스 로컬이다 | `ProcessedEvent.create():46`·`ProcessedEventCleanupScheduler:41`. 다른 group 의 성공 시각은 **분리된 DB 안** |
| 안전 여유가 0이다 | 4서비스 전부 `retention: 7d` == `dlq-replay-window: 7d`, validator 는 `retention ≥ max(floor)` 만 본다(`IdempotencyRetentionProperties:43-47`) |

### C2. 무엇이 결정을 강제하는가

1. **공유 토픽에 다시 넣으면 실패하지 않은 group 도 다시 받는다.** 억제 수단은 `processed_events (event_id, consumer_group)` 뿐이다.
2. **소비 경로 원장의 `origin_topic` 은 정의상 남의 토픽이다.** order 의 소비 원장은 `payment.*`·`product.updated`·`stock.reservation.result` 뿐이고, 자기 발행 토픽의 행은 quarantine 경로에만 생긴다.
3. **broker ack 는 재발행 성공만 증명한다.** 실패했던 consumer 의 업무 처리 성공은 증명하지 않는다.
4. **재발행분이 다시 실패하면 새 좌표로 별도 원장 행이 생긴다.** 상관키를 읽는 경로가 없으면 원래 행은 종결처럼 보인다.

---

## Decision

**DLQ replay 를 "원장 소유 서비스가, 사람이 개시할 때만, 기록된 좌표의 원본 레코드를 **원본 토픽·원본 파티션**에 **`key`·`payload`·`eventId`·원본 timestamp 를 보존한 채** 재전달하는 행위" 로 정의하고, 그 보장 수준을 publication at-least-once 로 고정한다. 사건의 종결은 재발행 성공이 아니라 사람의 명시적 판정으로만 이루어진다.**

> **동일성의 범위**: 레코드 *전체* byte-for-byte 가 아니다. **헤더는 예외**다 — 표준 `DLT_*` 는 제거하고 replay 상관 헤더를 더한다(§D3). 헤더까지 동일하게 두면 재실패 시 원본 좌표가 오염되고, 상관 헤더 없이는 재실패를 원래 사건에 잇지 못한다(§D5-4).

세부 결정은 D1~D8 이다.

---

### D1. 재발행 보장 수준 = **publication at-least-once + 소비 효과 멱등**

- **보장 문구**: 재발행은 **at-least-once** 다. 중복 발행은 **일어날 수 있는 것으로 전제**하며, 소비 효과가 1회임은 **동일 `eventId` + `processed_events (event_id, consumer_group)`** 가 보장한다.
- **"중복 발행 0" 을 계약에서 삭제한다.** DB 커밋과 broker ack 를 원자적으로 묶는 수단이 우리 스택에 없다.
- 재발행 실패의 재시도는 기존 outbox 재시도 정책(`retryCount`/`maxRetry`)을 따르고, 소진 시 원장의 발행 축이 `PUBLISH_FAILED` 가 된다(§D6).
- **검증 방향**: "중복 발행 0" 을 단언하지 않는다. 대신 **broker ack 직후 DB 저장 실패를 주입**해 *실제로 중복 발행이 일어나고 소비 효과는 1회* 임을 확인한다.

> `attempt_count` 는 **원장의 DLQ 유입 횟수**이고 replay 재시도 횟수가 아니다. 둘을 같은 컬럼에 담지 않는다.

### D2. notification-service 에 `outbox_events` 를 **신설한다** (ADR-0012 D1 개정)

- Notification DB 에 `outbox_events` 와 poller·스케줄러를 **기존 3서비스와 동일한 형태로 복제**한다.
- ADR-0012 D1 표의 Notification 행이 `processed_events` → **`processed_events, outbox_events`** 로 바뀐다.
- **구현은 ④-c-2b 소관.** 산출물: Flyway 1개 · `global/outbox` 패키지 복제 · poller 빈 · ShedLock 잡 등록 · outbox retention 잡 매트릭스에 notification 추가(구현 ② PR3 의 표 갱신).

### D3. replay 레코드는 `outbox_events` 의 **additive 컬럼 + poller kind 분기**로 표현한다

- 신설 컬럼(전부 nullable): `record_kind` · `destination_topic` · `destination_partition` · `record_key` · `source_record_timestamp` · `replay_target_event_id` · `replay_headers`(allowlist JSON) · **`replay_root_record_id`** · **`target_consumer_group`**.
  뒤의 두 개가 없으면 §D5-4 의 상관 대조가 **비교할 정본을 갖지 못한다** — 헤더가 주장하는 root/group 을 무엇과 맞춰볼지가 없다.
- **`record_kind` 의 NULL 의미와 전환 순서 (expand→contract)**: DB 기본값을 두지 않고 **`NULL` 을 `DOMAIN` 으로 해석**한다. 배포 중 구버전 writer 가 만든 행도 자동으로 도메인 경로로 간다.
  1. **expand** — 컬럼 추가(nullable), poller 는 `record_kind IS NULL OR record_kind='DOMAIN'` → 도메인 분기
  2. **deploy** — 전 인스턴스가 신버전이 되어 구버전 writer 가 사라짐을 확인
  3. **backfill** — 기존 행을 `DOMAIN` 으로 채움
  4. **contract(선택)** — **`NOT NULL` 만** 전환한다. **`DEFAULT 'DOMAIN'` 을 두지 않는다** — 기본값을 두면 신버전 writer 가 discriminator 를 빠뜨려도 DB 가 조용히 `DOMAIN` 으로 분류해, 누락을 실패시키려던 명시적 kind 계약이 다시 약해진다. 신버전 writer 는 `DOMAIN` 또는 `REPLAY` 를 **항상 명시**하며, 누락 INSERT 가 실패함을 테스트로 고정한다
  이 순서를 지키지 않고 `NOT NULL` 을 먼저 걸면 롤링 배포 중 구버전 INSERT 가 깨진다.
- poller 는 `record_kind` 로 분기해 `ProducerRecord(topic, partition, timestamp, key, payload, headers)` 를 조립한다. 도메인 경로의 `buildRecord()` 는 변경하지 않는다.
- **replay 레코드는 `source_record_timestamp` 를 ProducerRecord timestamp 로 싣는다.** 현 poller 는 timestamp 를 지정하지 않아 재발행분의 timestamp 가 *재발행 시각*이 되는데, 그러면 재실패 시 원장이 읽는 `DLT_ORIGINAL_TIMESTAMP` 가 원본이 아니게 되어 안전창 계산이 오염된다(§D5-3).
- **추적키를 분리한다**:
  - `outbox_events.event_id` = **replay attempt 의 UUID**(기존 unique 제약 유지 — 발행 추적용)
  - `replay_target_event_id` = **재발행 대상 payload 안의 `eventId`**(보존 필수, §D5-2)
  - 같은 컬럼에 담으면 unique 제약이 "같은 레코드의 2회 replay" 를 사고로 막거나 도메인 이벤트와 충돌한다.
- **헤더 allowlist**: 표준 `DLT_*` 헤더는 **싣지 않는다**(재실패 시 원본 좌표가 오염된다). 대신 §D5-4 의 replay 상관 헤더만 싣는다.

### D4. 브로커 retention 을 **계약으로 선언**하고, "7일 좌표 가용" 은 **best-effort 로 강등**한다

#### D4-1. 선언과 적용 경로
업무·`.dlq` 토픽 전부의 `NewTopic` 에 아래를 **명시 선언**한다.

| config | 업무 토픽 | `.dlq` 토픽 | 근거 |
|---|---|---|---|
| `retention.ms` | **7d** | **7d** (동일) | `app.idempotency.floor.kafka-topic-retention` 과 **같은 출처에서 유도**. `.dlq` 를 짧게 두면 원장이 좌표를 들고 있어도 진단 원문이 먼저 사라진다 |
| `cleanup.policy` | **delete** | **delete** | compact 는 좌표 hole 을 만든다(§D5-1) |
| `retention.bytes` | **8 MiB / 파티션** | **4 MiB / 파티션** | 아래 산정식. `.dlq` 는 예외 경로라 유입이 적다 |
| `segment.bytes` | **4 MiB** | **2 MiB** | **필수다.** `retention.bytes` 는 **닫힌 세그먼트만** 지우고 active segment 는 그 위에 얹힌다. 이미지 기본값이 **1 GiB**(`/opt/kafka/config/kraft/server.properties:128`)라 선언하지 않으면 파티션 하나가 PVC 전체를 넘길 수 있다 |
| `segment.ms` | **1d** | **1d** | 유입이 적어 크기로 롤되지 않는 파티션도 세그먼트를 닫아 삭제 대상이 되게 한다 |
| `message.timestamp.type` | **CreateTime** | (동일) | **replay 가 원본 timestamp 를 싣는 계약(§D3)의 전제.** `LogAppendTime` 이면 broker 가 append 시각으로 **덮어써** 안전창 계산이 오염된다. 기본값이 `CreateTime` 이지만 **의존하는 순간 계약이 되어야** 한다 |
| `message.timestamp.before.max.ms` | **`app.idempotency.retention` 에서 유도**(현재 9d) | (동일) | 과거 timestamp 를 실은 replay 발행이 **거부되지 않도록**. 짧으면 적격 replay 가 `PUBLISH_FAILED` 가 된다. 하한은 `dlq-replay-window + clockSkewBudget`(7d 5m)이며 §D4-3 의 fail-fast 가 `retention` 이 그 하한 이상임을 이미 강제하므로, **리터럴을 따로 두지 않고 `retention` 에서 유도**한다 — 두 곳에 적으면 floor 가 바뀔 때 갈라진다 |

**산정식** — 파티션의 최악 점유는 `retention.bytes` 가 아니라 **`retention.bytes + segment.bytes`** 다. `retention.bytes` 는 **닫힌 세그먼트만** 삭제 대상으로 삼고 active segment 는 그 위에 쌓이기 때문이다.

```
Σ_도메인토픽( 파티션수 × (retention.bytes + segment.bytes) ) × replicas
  + 내부토픽(__consumer_offsets · KRaft metadata) + 인덱스 여유
  ≤ PVC usable
```

현재 값 대입 — 업무 10토픽 × 3파티션 × (8+4) MiB = **360 MiB**, `.dlq` 10토픽 × 1파티션 × (4+2) MiB = **60 MiB**, `replicas(1)` → **약 420 MiB**.

> **이 420 MiB 는 hard bound 가 아니라 정상상태 목표치다.** 아래가 계산에 포함돼 있지 않다 — ① retention 검사는 주기적이라(`log.retention.check.interval.ms` 기본 5분) 그 사이 닫힌 세그먼트가 더 쌓일 수 있다 ② 삭제 대상 파일은 `file.delete.delay.ms` 동안 디스크에 남는다 ③ `.index`/`.timeindex` 오버헤드 ④ 세그먼트 크기를 넘는 큰 record batch. **유입률 상한이 없으므로 hard bound 를 주장하지 않는다.**

- 기존 토픽 반영을 위해 **`spring.kafka.admin.modify-topic-configs=true`** 를 켠다. ADR-0007 상 이는 *환경별 연결정보가 아니라 동작 규약*이므로 **base `application.yml` 또는 Java Config** 에 둔다(프로파일 금지).
- **동작 변경 여부를 분리해 기록한다** — 뭉뚱그리면 완료 보고가 false-green 이 된다:
  - `retention.ms=7d`·`cleanup.policy=delete` 명문화 → **실효 동작 불변**. 이미 Apache 기본값이 같다(C1). 바뀌는 것은 *계약이 되고 검증 대상이 된다*는 사실뿐이다
  - **`retention.bytes` 유한값 적용 → 동작 변경이다.** 현재는 `-1`(무제한)이므로, 시간 만료 전에 **크기 기반 삭제가 새로 생긴다**. 이것이 §D4-2 의 best-effort 강등과 직결된다

#### D4-2. 용량 — 보장하지 않는다
`kafka-pvc` 는 **1Gi** 이고 업무 토픽은 3파티션·`.dlq` 는 1파티션·`replicas(1)` 이다. "7일 보존" 을 보장하려면 두 하한을 **각각** 증명해야 한다 — ① 파티션별 최악 유입 × 7일 ≤ `retention.bytes` ② ①을 모든 파티션 × 복제수로 합산 + segment/인덱스 여유 ≤ PVC usable. **현재 트래픽 실측이 없어 ②를 증명할 수 없다.**

따라서 **"7일 안의 좌표는 반드시 읽을 수 있다" 를 보장으로 선언하지 않는다.** 대신:
- `retention.bytes` + `segment.bytes` 선언으로 도메인 토픽 점유를 **정상상태 약 420 MiB 로 억제**하고(hard bound 아님 — 위 단서),
- **replay 직전 좌표 검증(§D5-1)을 필수 절차로 만든다** — 좌표가 없으면 replay 불가로 종결한다.
- 디스크 사용률 경보는 **운영 보조**이지 보장 수단이 아니다(경보는 용량을 늘리지 않는다).

> **PVC 1 GiB 안전성은 증명되지 않았다 — "디스크 고갈을 막는다" 고 주장하지 않는다.**
> bound 되는 것은 **도메인 토픽뿐**이다. `__consumer_offsets` 는 `offsets.topic.num.partitions` 를 어디서도 설정하지 않아 **기본 50파티션**이고(compose·k8s 확인), KRaft metadata 로그도 포함되지 않았다. 이들을 bound 하려면 브로커 설정 변경이 필요한데 **파티션 수 변경은 기존 클러스터에 소급되지 않아 재생성을 요구**한다.
> → **내부 토픽 bound 와 PVC 증설 여부는 §Consequences 의 후속 항목으로 남긴다.** ADR 은 도메인 토픽 bound 까지만 결정하고, 전체 안전성은 **미증명 상태임을 명시**한다. 이것이 §D4-2 가 "보장" 이 아니라 "best-effort" 인 두 번째 이유다.

#### D4-3. 안전 여유를 설정으로 강제한다
현재 `retention: 7d` == `dlq-replay-window: 7d` 이고 validator 는 `retention ≥ max(floor)` 만 본다 — **등호가 통과하므로 skew·지연 여유가 0**이다. 규칙을 바꾼다:

```
retention ≥ dlqReplayWindow + clockSkewBudget + cleanupSafetyBudget
```

`clockSkewBudget`(서비스 간 시계 오차 여유)과 `cleanupSafetyBudget`(정리 잡 주기·지연 여유)을 신설하고 fail-fast 로 검사한다.

| 값 | 결정 | 근거 |
|---|---|---|
| `dlq-replay-window` | **7d** (유지) | 기존 floor |
| `clockSkewBudget` | **5m** | 서비스 간 시계 오차. `processed_events.processed_at` 이 각 서비스 로컬 시각이라(C1) 창 경계에서 어긋난다 |
| `cleanupSafetyBudget` | **1d** | `ProcessedEventCleanupScheduler` 는 일 1회(`0 30 3 * * *`)라 최대 1일 지연 |
| **`retention`** | **7d → 9d** | `7d + 1d + 5m = 8d 5m` 이상이어야 한다. 여유를 둬 9d |

현재 값(`retention: 7d`)은 이 규칙에서 **red 여야 한다** — 그래야 규칙이 등호를 실제로 막았음이 증명된다.

### D5. 좌표 유효성 · replay 금지 정책 · 재적용 의미론

#### D5-1. 좌표 검증 (replay 전 필수)
`AdminClient` 로 `cleanup.policy`·`retention.ms`·beginning/end offset 을 조회하고:
- 요청 좌표가 `[beginning, end)` 밖이면 **replay 불가로 종결**
- 읽은 뒤 **반환 레코드의 offset == 요청 offset** 을 검증한다 (compaction hole 에서 seek 은 *다음* 레코드를 준다 — 검증하지 않으면 **엉뚱한 메시지를 재발행**한다)

#### D5-2. 금지 축 — 6개 **독립** 조건
| # | 조건 | 이유 |
|---|---|---|
| 1 | `event_id IS NULL` | 동일 `eventId` 가 없으면 **실패하지 않은 group 의 멱등 억제가 불가능**하다(C2-1) |
| 2 | `failed_consumer_group = '__unknown__'` | 어느 group 이 실패했는지 모르므로 replay 대상 판정 자체가 불가 |
| 3 | `origin_kind = 'DLQ_ORIGIN'` | 좌표가 **`.dlq` 자신**이라 원본 레코드를 읽을 수 없다. 그대로 replay 하면 **`.dlq` 내용이 원본 토픽에 주입**된다 |
| 4 | 좌표 무효 | `topic_generation` 불일치 · offset 범위 밖 · offset 불일치 반환(D5-1) |
| 5 | `replay_policy` 정책 금지 | 토픽/eventType 별 정책. **`payload_truncated` 와 독립 컬럼**이다 |
| 6 | `original_timestamp IS NULL` | 안전창의 기준시각을 세울 수 없다. `occurred_at` 으로 대체하면 다른 group 의 멱등 행이 이미 삭제됐을 수 있어 **§D1 의 소비 효과 1회 보장이 깨진다**(§D5-3) |

> **축 6 의 실제 영향은 측정되지 않았다.** 네 DB 모두 `original_timestamp` 가 nullable 이고, 현 통합테스트(`DlqIntegrationTest`)는 정상 `DeadLetterPublishingRecoverer` 유입에서 **timestamp 헤더나 원장 저장값을 단언하지 않는다** — NULL 비율을 아무도 모른다. ④-c-2b 는 ① 서비스별 원장의 **전체 / `RESOLVED_ORIGIN` / replay 후보** 각각에 대해 `original_timestamp IS NULL` 건수·비율을 증적으로 남기고 ② 실제 DLT 통합테스트에서 **timestamp 가 원장까지 저장됨을 고정**한다. NULL 비율이 높게 나오면 backfill 가능성 또는 **가용성 손실 수용 기준**을 ADR Update Log 에 기록한다.

> **`payload_truncated` 는 금지 사유가 아니다.** replay 원본은 원장의 진단 사본이 아니라 **기록된 좌표의 원본 레코드**이므로, 진단 사본의 변형 여부는 재전달 충실도와 무관하다.
> 세 축(`event_id` / `failed_consumer_group` / `origin_kind`)은 **서로 독립**이며 조합이 가능하다 — 예컨대 `eventId` 가 있으면서 quarantine 인 행, `eventId` 가 없으면서 `RESOLVED_ORIGIN` 인 행이 모두 실재한다.

#### D5-3. 멱등 안전창과 `replay_deadline`
- 원장에 **`replay_deadline`** 을 두고 요청 시 `now < replay_deadline` 을 강제한다.
- **계산식은 하나다**: `replay_deadline = original_timestamp + dlq-replay-window`. **`occurred_at` fallback 은 두지 않는다** — `occurred_at`(DLQ 적재시각)은 원본보다 늦으므로 **DLQ 도달이 지연된 사건에서 창을 부당하게 늘리고**, 그 사이 다른 group 의 멱등 행이 이미 삭제됐을 수 있다.
- 따라서 **`original_timestamp` 가 NULL 이면 replay 를 금지한다**(§D5-2 축 6). 대체 기준을 쓰면 실패하지 않았던 group 이 다시 처리해 **§D1 과 §D5-4 의 "소비 효과 1회" 보장이 그 경로에서 깨지기** 때문이다. 보장을 유지하는 쪽을 택했고, NULL 행은 runbook 우회(상류 재발행·도메인 상태 직접 교정)로 간다.
- **미래 timestamp 는 `clockSkewBudget` 까지 허용한다** — `original_timestamp ≤ now + clockSkewBudget` 이면 정상으로 보고, 초과분만 거부한다. 무조건 거부하면 **producer 시계가 몇 초 앞선 직후 발생한 DLQ 를 즉시 replay 할 때 금지**되어, §D4-3 이 채택한 5분 예산이 deadline 판정에는 적용되지 않는 모순이 된다. 허용된 미래값은 **`now` 로 clamp 해 deadline 을 계산**한다(창을 늘려주지 않는다).
- **`replay_deadline` 은 루트 사건에서 1회 계산하고 모든 자식 행·attempt 가 상속한다. 재계산을 금지한다.** 재발행분이 다시 DLT 로 가면 `DLT_ORIGINAL_TIMESTAMP` 는 **재발행 시각**이므로(현 poller 는 timestamp 를 지정하지 않는다), 새 행에서 다시 계산하면 **실패할 때마다 안전창이 연장된다**. 이를 위해 replay outbox 는 `source_record_timestamp` 로 원본 timestamp 를 보존한다(§D3).
- **ADR-0012 D5 와의 관계(refine)**: D5 는 *"TTL 이후 수동 재처리는 새 `eventId` 발행 또는 운영자 중복 확인 절차"* 를 대안으로 허용한다(`0012:98`). 본 ADR 의 replay 는 **`replay_deadline` 안에서만, 동일 `eventId` 로만** 동작하므로 두 경로는 **배타적**이다. D5 의 대안은 **창 밖 사건의 운영 우회로 유효하게 남되, 그것은 ADR-0020 의 replay 가 아니며 원장 상태로 추적되지 않는다.** (`StockReservationService` javadoc 의 "DLQ 재발행(새 eventId)" 서술은 이 우회 경로를 가리키는 것으로 정정 표기한다.)

#### D5-4. 재적용 의미론과 재실패의 귀착
- **모든 group 재전달 불변식**: 재발행은 그 토픽의 **모든** consumer group 에 전달된다. 무해성의 근거는 오직 `processed_events (event_id, consumer_group)` 이므로 **동일 `eventId` 보존이 필수**다.
- **순서는 복원되지 않는다.** 재발행분은 로그 **끝**에 붙고, 실패했던 group 은 멱등 행이 없어 반드시 실행하는데 도메인 상태는 이미 앞서 있다. 따라서 `replay_policy` 는 **상태 사전조건**과 **늦은 이벤트 허용 여부**를 토픽/eventType 별로 담는다.
- **재실패는 별도 원장 행을 만든다** — 재발행분이 또 실패하면 `DeadLetterRecorder` 가 **새 좌표**로 행을 `OPEN` 으로 적재한다. 이를 원래 사건과 잇기 위해:
  - 원장에 **`root_record_id`** 를 두어 **canonical incident root ↔ replay attempt** 를 나눈다. root 행의 `root_record_id` 는 자기 자신이다.
  - 종결은 **root 와 활성 자식에 원자적으로 전파**한다. 그래야 자식을 반복 replay 해도 미결 행이 누적되지 않는다.
  - **"링크만" 은 채택하지 않는다** — 조상 해소 범위가 정의되지 않아 backlog 카디널리티가 단조 증가한다.
- **replay 상관 헤더**: `DeadLetterRecorder` 는 현재 application header 를 저장하지 않으므로(C1), 저장 경로를 신설하고 **allowlist 에 replay 상관 헤더를 명시**한다. 값은 *attempt UUID · ledger owner(서비스) · target consumer group · root incident id* 를 담는다.
  - **헤더 값 자체를 신뢰하지 않는다.** 이 값들은 비밀이 아니고, 원본 producer 도 같은 업무 토픽에 application header 를 쓸 수 있다 — owner/group 일치 검사만으로는 **조작된 메시지가 실패해 DLT 로 갈 때 임의 root 연결·재개방을 유발**할 수 있다.
  - **대조의 정본은 원장이다 (outbox 가 아니다).** replay 요청 시 **root 원장 행에 `last_replay_attempt_id`·`last_replay_target_group` 을 함께 기록**하고, 재실패 적재는 그것과 대조한다. outbox 를 정본으로 삼으면 **수명 경쟁에서 진다** — `outbox_events` 의 `PUBLISHED` 행은 retention 후 삭제되는데 **미결 root 원장은 무기한 남고 DLQ 적재는 지연·중지될 수 있어**, 정상 attempt 가 대조에 실패해 독립 incident 로 갈라진다(그러면 "재실패 N회에도 backlog 1" 이 깨진다).
  - **판독 조건 = 한 트랜잭션 안의 원자 대조**: `DeadLetterRecorder` 는 자식 적재 트랜잭션 안에서 ① 헤더의 `attempt UUID` 가 **현재 서비스 원장의 `last_replay_attempt_id`** 와 일치하는 root 를 찾고 ② 그 root 를 잠근 뒤 ③ **ledger owner(현재 서비스)** · **실제 DLT consumer group** ↔ `last_replay_target_group` · `destination_topic` ↔ `origin_topic` · `record_kind=REPLAY` 를 **전부** 대조하고 ④ 통과하면 자식 INSERT 와 root 재개방(§D6-2b I-2)까지 같은 트랜잭션에서 수행한다.
  - **실제 DLT group 을 대조에 넣는 것이 핵심이다** — 한 서비스가 여러 업무 group 을 갖는 현 `DlqTopology` 에서, 유효한 attempt UUID 만 재사용한 **group/root 바꿔치기**는 owner 검사만으로는 통과한다.
  - 하나라도 어긋나면 **상관을 무시하고 독립 원장 행으로 적재**한다(정보를 버리지 않되 잘못 잇지 않는다).
  - 더 강한 경계가 필요해지면 **서버 검증 가능한 서명/MAC opaque token** 으로 격상한다. 현 단계에서 채택하지 않는 이유는 로컬 DB 대조가 같은 보장을 주면서 키 관리가 없기 때문이다.
  - **음성 테스트를 요구한다**: 위조 attempt UUID · 타 서비스 소유 attempt · 존재하지 않는 attempt · `record_kind` 불일치 · **유효 attempt UUID + 다른 consumer group** · **유효 attempt UUID + 다른 root** · **다른 destination topic** — 전부 **상관되지 않고 독립 행이 되어야** 한다.
  - **수명 경쟁 테스트**: outbox `PUBLISHED` cleanup 을 먼저 돌린 뒤 지연된 DLQ 적재를 주입해 **같은 root 로 상관되고 backlog 가 1 로 유지**되는지 확인한다.

### D6. 상태를 **두 축으로 물리 분리**하고, 종결은 **사람만** 한다

#### D6-1. 두 축
단일 `status` 컬럼으로는 *발행 실패*와 *소비 재실패*가 같은 값이 되어 상반된 사실을 가리킨다. 컬럼을 나눈다.

| 축 | 컬럼 | 값 | 근거 |
|---|---|---|---|
| **발행** | `publication_status` (신설, nullable) | `NULL`(요청 없음) · `REQUESTED` · `PUBLISHED` · `PUBLISH_FAILED` | outbox 상태와 broker ack |
| **사건** | `status` (기존, resolution 축으로 확정) | `OPEN` · `ACKED` · `RESOLVED`(신규) · `DISCARDED` | 사람의 판정 |

- **소비 재실패는 상태값이 아니다** — 새 원장 행(자식)의 **존재 자체**가 그 사실이다(§D5-4). 별도 `CONSUMPTION_FAILED` 를 두지 않는다.
- `status` 는 `VARCHAR(30)` 이라 `RESOLVED` 추가에 마이그레이션이 필요 없다(2a 가 남긴 확장점). `publication_status` 는 additive 컬럼이다.

#### D6-2. 종결은 broker ack 로 하지 않는다
- **`REPLAY_PUBLISHED`(= `publication_status=PUBLISHED`)는 terminal 이 아니고, unresolved 집계에서도 빠지지 않는다.** 발행 성공은 사건 해소가 아니다(C2-3).
- **소비 성공 확인은 만들지 않는다.** 업무 consumer 가 상관키를 받아 비즈니스 트랜잭션 안에서 성공을 기록하게 하려면 4서비스 consumer 를 침습해야 하고, 운영 관심사를 도메인 트랜잭션에 끌어들인다. 채택하지 않는다.
- 대신 **종결은 운영자의 명시적 전이**로만 이루어진다: `RESOLVED`(해소 확인) 또는 `DISCARDED`(재처리 안 함). **둘 다 사유 기록이 필수**이며, `RESOLVED` 는 *무엇을 근거로 해소를 확인했는지*(도메인 상태 조회 결과 등)를 남긴다.
- 이 결정은 **자동 재발행 영구 금지**와 정합한다 — replay 는 사람이 개시하고 사람이 닫는다.

#### D6-2b. 두 축의 곱 — 전이 불변식과 늦은 자식 race
축을 나눈 것만으로는 부족하다. **어느 발행 상태에서 사건을 닫을 수 있는지**와 **닫은 뒤 자식이 도착하면 어떻게 되는지**를 못박지 않으면 물리 분리가 race 에서 무너진다.

| `publication_status` \ `status` | `OPEN` / `ACKED` | `RESOLVED` / `DISCARDED` |
|---|---|---|
| `NULL` (요청 없음) | 정상 — 미결 | **허용** (replay 없이 종결) |
| `REQUESTED` | 정상 — 발행 진행 중 | **금지** — 아래 I-1 |
| `PUBLISHED` | 정상 — **발행됐어도 미결**(§D6-2) | **허용** (사람이 해소 확인) |
| `PUBLISH_FAILED` | 정상 — 재요청 가능 | **허용** (재처리 포기) |

**I-1. `publication_status = REQUESTED` 인 행은 resolution 을 terminal 로 전이시킬 수 없다.** 발행이 진행 중인데 사건을 닫으면 **terminal 사건이 나중에 발행되는** 상태가 된다. 관리 API 는 이 전이를 거부한다.

**I-2. 늦은 자식은 root 를 재개방한다.** 발행분의 소비 실패가 진행 중일 때 운영자가 root 를 `RESOLVED` 로 닫으면, 그 커밋 뒤 `DeadLetterRecorder` 가 새 `OPEN` 자식을 만들 수 있다. "root 와 활성 자식에 원자적 전파"(§D5-4)는 **그 시점에 존재하는** 자식만 닫으므로 이 race 를 막지 못한다. 규칙:
- 자식 삽입 시 `root_record_id` 로 **root 행을 잠그고**(`SELECT ... FOR UPDATE`) root 의 상태를 검사한다
- root 가 terminal 이면 **root 를 `OPEN` 으로 재개방**하고 재개방 사유·시각을 기록한다
- **삽입 거부와 자식 terminal 상속은 채택하지 않는다** — 전자는 실제 실패 정보를 버리고, 후자는 미결을 종결로 위장한다. 늦게 도착한 소비 실패는 *사건이 끝나지 않았다는 증거*이므로 재개방이 사실에 맞는다
- 재개방은 운영 알림 대상이다 — 사람이 닫은 것을 시스템이 되돌리는 유일한 경로라 조용히 일어나면 안 된다

#### D6-3. 관측·정리 회귀 (필수)
현 쿼리는 상태 집합을 JPQL 리터럴로 박고 있어(C1) 새 값이 **미결 집계에서도 purge 대상에서도 동시에 빠진다**. ④-c-2b 는 다음을 함께 갱신한다.

**집계 단위는 행이 아니라 incident(= root) 다.** 재실패마다 자식 행이 늘어나므로 행 단위로 세면 **backlog·oldest-age 가 incident 수보다 계속 부풀고**, §D5-4 의 "미결 행이 누적되지 않는다" 가 거짓이 된다.

| 행 | unresolved 로 세는가 | terminal | purge 대상 |
|---|---|---|---|
| **root** (`root_record_id = id`) — `OPEN` · `ACKED` | ✅ | ✕ | ✕ |
| **root** — `publication_status ∈ {REQUESTED, PUBLISHED, PUBLISH_FAILED}` 이고 `OPEN`/`ACKED` | ✅ (발행 축과 무관하게 계속 미결) | ✕ | ✕ |
| **root** — `RESOLVED` · `DISCARDED` | ✕ | ✅ | ✅ (`resolved_at`/`discarded_at` + retention) |
| **자식** (`root_record_id <> id`) | **✕ — 진단용** | root 를 따른다 | root 와 함께 |

- `countUnresolved`·`findOldestUnresolvedOccurredAt`·`findStaleUnresolved` 는 **root 만** 센다. 그래야 incident 1건 = backlog 1건이다.
- **⚠️ 전환 구간 계약 (필수)**: `root_record_id` 는 additive 컬럼이라 **④-c-2a 가 이미 적재한 기존 행은 NULL** 이다. 조건을 곧바로 `root_record_id = id` 로 걸면 **기존 미결이 전부 집계에서 탈락해 backlog 가 0 으로 보인다** — 자식 과다집계를 고치다 정확히 반대 방향의 false-green 을 만드는 경로다. 순서를 못박는다:
  1. **expand** — 컬럼 추가(nullable). 집계 조건은 **`root_record_id IS NULL OR root_record_id = id`**
  2. **deploy** — 구버전 writer 소멸 확인
  3. **backfill** — 기존 행을 `root_record_id = id` 로 채우고 **무결성 검증**(NULL 잔여 0)
  4. **contract** — `NOT NULL` 전환 후 조건을 `root_record_id = id` 로 단순화
  **배포 전후로 기존 미결 건수가 동일함을 회귀 테스트로 고정한다.**
- 자식은 **조회·진단용**이며 독립 종결되지 않는다. purge 도 root 종결 시 함께 이루어진다.
- 기대 카디널리티를 회귀 테스트로 고정한다 — *최초 실패 1건 → backlog 1* · *replay 후 재실패 → 여전히 1* · *재실패 N회 → 여전히 1* · *root 종결 → 0*.

대상: `countUnresolved`·`findOldestUnresolvedOccurredAt`·`findStaleUnresolved`·purge 쿼리 · `DeadLetterMetrics` · `DeadLetterMaintenanceScheduler` · `DeadLetterEndpoint` · 경보 · runbook 쿼리.

> 회귀 테스트의 **핵심 단언은 "`PUBLISHED` 인 행이 backlog·age 에 계속 잡힌다"** 이다. `PUBLISH_FAILED`·장기 `REQUESTED` 만 검사하면 §D6-2 의 조기 종결 금지가 통과해 버린다.

#### D6-4. 발행 ↔ 원장 연결과 동시 요청 fence
- 원장에 **`outbox_event_id`** 를 두어 발행 축을 outbox 행과 잇는다. 전이 주체는 **reconciler**(outbox 상태를 폴링해 `publication_status` 전이) 1종으로 고정한다 — 관리 API 는 `REQUESTED` 까지만 만든다.
- outbox 가 `FAILED` 로 소진되면 `publication_status = PUBLISH_FAILED` 가 되고 **재요청이 허용**된다(사건 축은 계속 미결).
- **동시 요청 fence**: 같은 원장 행에 동시 replay 요청이 와도 **outbox 행이 하나만 생성**되어야 한다. `publication_status` 에 대한 **조건부 UPDATE**(`WHERE publication_status IS NULL OR publication_status = 'PUBLISH_FAILED'`)로 fence 한다. `@Version` 은 추가하지 않는다 — 조건부 UPDATE 로 충분하고 원장 4 DB 에 컬럼을 늘리지 않는다.

### D7. 운영 진입점 = **Actuator `@WriteOperation`** (기존 `deadletter` 엔드포인트 확장)

- ④-c-2a 가 이미 `DeadLetterEndpoint` 에 `@ReadOperation backlog()` · `@WriteOperation transition(id, action, actor, reason)` 을 만들어 뒀고, runbook 이 curl 기반이다. replay 는 **그 표면의 확장**으로 간다(`action=replay`).
- **운영 CLI 는 기각한다** — 별도 배포·인증·감사 표면이 하나 더 생기고, 이미 있는 진입점과 권한 모델이 갈라진다.
- **직접 SQL 상태 변경을 금지한다.** ④-c-2a 에서 runbook 이 `UPDATE ... SET status` 를 안내해 `discard()` 의 "사유 필수" 가드를 우회시킨 결함이 실제로 났다. 리허설·runbook 은 **공개 진입점만** 사용한다.
- **자동 재발행 금지**(불변) — 진입점은 사람이 개시한다.

### D8. replay 발행은 `1 topic = 1 producer` 의 **명시적 예외**다 (ADR-0012 D4 부분 무효화)

#### D8-1. 두 개념을 분리한다
- **프로비저닝 소유** — 누가 `NewTopic` 을 선언하는가. ADR-0011 D2(`:71`) 소관. **replay 는 이것을 건드리지 않는다** → ADR-0011 은 영향 없음.
- **발행 권한** — 누가 그 토픽에 write 하는가. ADR-0012 D4 producer 컬럼과 ADR-0018 이 고정한다.

ADR-0018 은 이 둘을 **한 논증 안에서 결합**해 사용했다 — *"producer 가 2개가 되어 `NewTopic` 프로비저닝 소유자와 payload 스키마 소유가 모호해진다"*(`0018:48`·`:161`). **replay 는 그 결합을 끊는 첫 사례**다: 프로비저닝 소유자는 그대로 두고 발행 권한만 예외를 낸다.

#### D8-2. 예외가 불가피한 이유
소비 경로 원장의 `origin_topic` 은 **정의상 남이 발행한 토픽**이다(C2-2). "원본 토픽 producer 만 발행한다" 를 replay 에 그대로 적용하면 replay 가능 집합은 자기 발행 토픽의 행 — 즉 **quarantine 행** — 으로 축소되는데, 그 행들은 §D5-2 ②③ 때문에 오히려 금지 대상이다. **결과적으로 replay 가능 집합이 공집합이 된다.**

#### D8-3. 예외의 fence
replay 발행은 다음을 **전부** 만족할 때만 허용한다. 하나라도 어긋나면 **발행을 거부**한다.

| fence | 내용 |
|---|---|
| 주체 | **원장 소유 서비스**가, **자기 원장 행**에 대해서만 |
| 목적지 토픽 | `destination_topic == origin_topic` |
| 목적지 파티션 | `destination_partition == 검증된 origin_partition` — 다른 파티션에 넣으면 **같은 key 의 순서 축을 잃고** 임의 주입 표면이 된다 |
| 내용 | `key`·`payload`·`eventId` 가 원본 레코드와 **byte-for-byte 동일** |
| 표식 | `record_kind = REPLAY` |
| 성격 | **새 도메인 이벤트를 만들지 않는다** — payload 변경 금지 |

#### D8-4. ADR-0012 D4 의 처분 = **부분 무효화**
D4 매트릭스의 producer 컬럼은 두 가지를 함께 함의했다 — ① *이 토픽의 이벤트를 만드는 서비스* ② *이 토픽에 write 하는 유일한 서비스*. **①은 유효하고 ②는 replay 로 깨진다.** 따라서:
- **무효화 범위 = ②의 함의뿐이다.** 도메인 이벤트 생성 주체로서의 producer 지정은 전부 유효하다.
- **ADR-0018 도 같은 범위로 부분 무효화된다.** 초안은 이를 refine(Status 불변)으로 뒀으나 **자기 기준에 어긋난다** — `0018:49` 의 결정은 *"`1 topic = 1 producer` 규약 유지"* 이고 **"새 도메인 이벤트에만" 이라는 한정이 원문에 없다**. 게다가 ADR-0018 Alternative A 의 기각 사유가 *"규약 예외는 그 자체로 후속 의사결정마다 재논쟁을 부른다"* 인데, 본 ADR 이 정확히 그 예외를 낸다. 규칙에 예외를 내는 것은 부분 무효화라는 아래 판정을 ADR-0018 에도 동일하게 적용한다 → **`Partially Superseded by ADR-0020`**, 무효화 범위 = **DLQ replay 의 write 예외**에 한정.
- ADR-0018 `:230` 의 선례(*"기존 결정을 부정하지 않고 같은 규칙으로 항목을 더하는 것은 부분 무효화가 아니다"*)는 **여기 적용되지 않는다** — replay 는 *항목 추가*가 아니라 *규칙에 예외를 내는 것*이기 때문이다. 그래서 refine 이 아니라 부분 무효화로 판정한다.

---

## Alternatives Considered

### Alternative A: Kafka 트랜잭션으로 exactly-once 발행 (D1)
- **장점**: 이론상 중복 발행 제거처럼 보인다.
- **단점**: **성립하지 않는다.** Kafka 트랜잭션은 *Kafka 내부 쓰기와 offset 커밋*의 원자성이며, `OutboxPollingService:83-86` 의 "broker ack ↔ DB save" 경계를 없애지 못한다. 소비자 가시성(`read_committed`)을 제어할 뿐이다.
- **기각 사유**: crash window 를 줄이지 않으므로 D1 의 **대안이 아니라 비해결 대조군**이다. 비용만 남는다 — `isolation.level` 을 Kafka consumer 가 있는 **4서비스**(order·product·payment·notification. User 는 `KafkaAutoConfiguration` 제외라 무관)에 전파해야 하고 단일 브로커 e2e 에 영향을 준다.

### Alternative B: CDC(log-based outbox, Debezium) (D1)
- **장점**: poller 의 "ack 후 별도 save" 자체가 사라진다 — crash window 를 **실제로** 줄이는 유일한 안이다.
- **단점**: Kafka Connect 클러스터·커넥터 운영·스키마 관리·k8s 매니페스트·관측이 새로 붙는다. binlog 권한과 DB 설정도 따라온다.
- **기각 사유**: replay 는 **사람이 개시하는 저빈도 작업**이다. crash window 로 생기는 중복 발행 1~2건은 `processed_events` 가 흡수하며, 그 비용은 CDC 인프라 도입 비용보다 압도적으로 작다. 트래픽이 커져 도메인 outbox 자체가 병목이 되면 재검토한다.

### Alternative C: replay 지휘를 producer 서비스에 위임 (D2·D8)
- **장점**: `1 topic = 1 producer` 를 문자 그대로 지킬 수 있고, notification 에 outbox 를 만들지 않아도 된다.
- **단점**: **notification 만의 문제가 아니다.** 소비 경로 원장은 4서비스 전부 남의 토픽을 가리키므로(C2-2), 위임하려면 **4서비스에 걸친 DB 간 durable command 채널**(요청 토픽·fence·회신·멱등)을 새로 만들어야 한다. DB-per-service 경계를 넘는 새 결합이 생기고, 원장 소유자와 발행 주체가 갈라져 종결 추적이 한 단계 더 꼬인다.
- **기각 사유**: 규약 하나를 문자 그대로 지키려고 **새 분산 프로토콜**을 도입하는 거래다. D8-3 의 fence 로 예외의 오용을 막는 편이 표면이 훨씬 작다.

### Alternative D: 별도 `replay_outbox` 테이블 + 별도 poller (D3)
- **장점**: 도메인 outbox 행에 항상 NULL 인 컬럼이 붙지 않는다. 관심사가 물리적으로 분리된다.
- **단점**: poller·스케줄러·메트릭(`outbox.publish`·`outbox.backlog`)·retention 정리 잡이 **전부 2벌**이 된다. 4서비스 × 2벌이다.
- **기각 사유**: replay 는 저빈도이고 발행 메커니즘은 도메인 경로와 동일하다. 인프라를 2벌로 만드는 비용이 NULL 컬럼 몇 개보다 크다.

### Alternative E: 소비 성공 확인으로 `RESOLVED` 를 자동 전이 (D6)
- **장점**: 사건 종결이 자동화되고 운영 부담이 준다. `RESOLVED` 가 가장 강한 의미를 갖는다.
- **단점**: 업무 consumer 4서비스가 replay 상관키를 읽어 **비즈니스 트랜잭션과 같은 커밋**에 성공을 기록해야 한다. 운영 관심사가 도메인 트랜잭션에 침투하고, 상관키 전파 경로(발행→소비→기록)가 모든 consumer 에 생긴다.
- **기각 사유**: replay 는 저빈도 수동 작업인데 그것 때문에 **모든 정상 소비 경로**에 코드를 심는 거래다. §D6-2 의 "사람이 닫는다" 로도 조기 종결은 막히며(발행 성공이 종결이 아니므로), 비용이 훨씬 작다. 자동 종결이 필요할 만큼 replay 빈도가 오르면 재검토한다.

### Alternative F: `payload_truncated` 를 replay 금지 사유로 사용 (D5)
- **장점**: 컬럼이 이미 있어 구현이 없다.
- **기각 사유**: **자기모순이다.** replay 원본은 원장의 진단 사본이 아니라 기록된 좌표의 원본 레코드이므로, 사본의 변형 여부는 재전달 충실도와 무관하다. 실제 위험은 `event_id` 부재·quarantine·`DLQ_ORIGIN` 좌표이며 이 셋은 서로 독립이다(§D5-2).

### Alternative G: `@Version` 으로 동시 replay 요청 fence (D6-4)
- **장점**: JPA 관용 패턴이고 다른 동시성에도 재사용된다.
- **기각 사유**: 원장 **4 DB 전부에** 컬럼과 마이그레이션이 필요한데, 막으려는 것은 "같은 행에 outbox 를 두 번 만들지 않는다" 하나뿐이다. `publication_status` 조건부 UPDATE 로 같은 보장을 얻으면서 스키마 변경이 없다.

---

## Consequences

### 긍정적 영향
- ④-c-2b 가 **착수 가능해진다** — `docs/plans/done/task-impl4-c2b-dlq-replay.md` §4 착수 조건 1(D1~D7 확정)이 닫힌다.
- 두 번 반증된 "중복 발행 0" 이 **계약에서 제거**되고, 보장 문구가 실제 스택으로 지킬 수 있는 수준(publication at-least-once)으로 내려온다.
- 발행 축과 사건 축이 물리적으로 갈라져 **"발행했으니 끝" 이라는 조기 종결이 구조적으로 불가능**해진다.
- 브로커 retention 이 **검증 대상**이 된다. 지금까지는 Apache 기본값에 암묵적으로 의존하고 있었고 아무도 그것을 확인하지 않았다.
- replay 금지 축이 독립 조건으로 분리되어, **`.dlq` 내용을 원본 토픽에 주입하는 사고 경로**(§D5-2 ③)가 명시적으로 막힌다.

### 부정적 영향 / 트레이드오프
- **"7일 좌표 가용" 을 보장하지 못한다.** 유입량 실측이 없고, **PVC 1 GiB 안전성도 증명되지 않았다**(§D4-2 — 도메인 토픽만 420 MiB 로 bound 되고 `__consumer_offsets` 50파티션·KRaft metadata 는 bound 밖). 좌표가 없으면 replay 는 불가로 종결되며 runbook 우회로 간다.
- **`original_timestamp` 가 없는 원장 행은 replay 할 수 없다**(§D5-2 축 6). 안전창의 기준시각을 세울 수 없어 §D1 보장이 깨지기 때문이다. 그 행들은 runbook 우회로만 처리된다 — 보장을 지키는 대가로 **replay 가능 집합이 줄었다**.
- **`retention.bytes` 유한값 적용은 동작 변경이다**(현재 `-1`). 시간 만료 전 크기 기반 삭제가 새로 생기며, 이것이 §D4-2 강등의 직접 원인이다.
- **자식 원장 행은 backlog 에 잡히지 않는다**(§D6-3) — 집계 단위가 incident 이므로. 개별 재실패의 가시성은 root 를 열어봐야 얻어진다.
- **종결이 수동이다.** `RESOLVED` 는 사람이 근거와 함께 전이시켜야 하며, 그만큼 운영 부담이 남는다. backlog 가 자동으로 줄지 않는다.
- **발행 권한 규약에 예외가 생겼다.** D8-3 의 fence 가 코드로 강제되지 않으면 이 예외는 "아무나 아무 토픽에 쓴다" 로 번질 수 있다. fence 위반 시 발행 거부는 **테스트로 고정해야 한다**.
- **notification 에 outbox 인프라가 늘어난다**(poller·스케줄러·retention 잡). 소비 전용 서비스라는 단순함을 잃는다.
- **원장 컬럼이 늘어난다** — `publication_status`·`outbox_event_id`·`replay_deadline`·`root_record_id`·상관 헤더 저장. **4 DB 전부**(order V6·product V5·payment V5·notification V3 계열)에 additive 마이그레이션이 필요하다.
- **replay 는 순서를 복원하지 않는다.** 늦게 도착한 과거 이벤트를 현재 상태에 재적용하는 위험은 `replay_policy` 의 상태 사전조건으로 관리할 뿐 제거되지 않는다.

### 후속 결정에 미치는 영향
- **④-c-2b(구현)** 산출물: 4 DB additive 마이그레이션 · notification outbox 신설 · poller kind 분기 · 좌표 reader(`AdminClient` + assign/seek/offset 일치 검증) · 상관 헤더 저장·판독 · `DeadLetterEndpoint` replay 액션 · reconciler · **§D6-3 관측·purge 회귀** · runbook §6 재작성 · `StockReservationService` javadoc 정정 · `docs/progress/PHASE4.md` 의 "새 eventId" 서술 정정.
- **④-c-2b-0(선행 코드 PR)**: `NewTopic` config 선언 · `modify-topic-configs` · retention 여유 fail-fast 규칙 + 4서비스 설정 조정 · `describeConfigs` 검증 테스트.
- **D-020(PG reconciliation)** 과는 무관하다 — 본 ADR 은 Kafka 레코드의 재전달만 다루고 외부 PG 상태 정합은 다루지 않는다.
- **내부 토픽 bound 와 PVC 증설**은 본 ADR 이 결정하지 않은 후속이다(§D4-2). `offsets.topic.num.partitions` 축소는 **기존 클러스터에 소급되지 않아 재생성을 요구**하므로 별도 인프라 결정이 필요하다. 트래픽 실측 + 내부 토픽 bound 가 갖춰지면 **§D4-2 를 보장으로 승격**할 수 있다.
- **`message.timestamp.*` 가 계약에 편입**됐다(§D4-1). 이후 토픽 설정 변경은 replay 의 timestamp 보존 전제를 깨지 않는지 확인해야 한다.
- replay 빈도가 유의미하게 오르면 **Alternative E(소비 성공 확인)** 를 재검토한다.

---

## References

- 계획서: `docs/plans/done/task-adr0020-dlq-replay-contract.md` (명제 N1~N15 · 코드 검증 30행 · 리뷰 3라운드 정정 이력)
- 입력: `docs/plans/done/task-impl4-c2b-dlq-replay.md` §2 (D1~D7) · `docs/plans/done/task-impl4-c2a-dlq-ledger.audit.md`
- runbook: `docs/runbooks/dlq-recovery.md` §6
- 선행 ADR: [ADR-0011](./0011-phase4-multimodule-structure.md) D2 · [ADR-0012](./0012-phase4-db-event-saga-contract.md) D1/D4/D5 · [ADR-0018](./0018-compensation-refund-contract.md)
- 코드: `OutboxPollingService:83-125` · `DlqOrigin:41-63` · `DlqTopology` · `DeadLetterRecorder:25-69` · `DeadLetterRecordJpaRepository:77-96` · `DeadLetterEndpoint` · `IdempotencyRetentionProperties:43-47` · `ProcessedEventCleanupScheduler:41`
- 선행 PR: ④-c-2a [#90](https://github.com/Kimgyuilli/PeakCart/pull/90)
