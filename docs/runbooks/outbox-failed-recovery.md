# Runbook — Outbox `FAILED` 발행 재개

> 대상: 발행 서비스 DB의 `outbox_events.status='FAILED'` 인 DOMAIN 이벤트
> 제외: `record_kind='REPLAY'` 행은 [DLQ replay runbook](dlq-recovery.md)과 ADR-0020 절차를 따른다.
> 이 문서는 실행 승인이 아니다. production 재개 조건이 모두 충족되기 전에는 조회와 격리 fixture 검증만 한다.

## 0. 판단 요약

Outbox poller는 `PENDING`만 읽고 최대 재시도 뒤 행을 `FAILED`로 둔다. `FAILED`는 자동 재시도되지 않지만, Kafka publish timeout은 broker가 레코드를 받았는지 모를 수 있다. 따라서 FAILED라는 상태만으로 “미발행”이라고 결론 내리지 않는다. event별 모든 소비자의 `eventId` 중복 처리 증거를 확인할 수 없으면 재개하지 않고 담당자에게 넘긴다.

| 상황 | 처분 |
|---|---|
| broker/topic 또는 소비자가 아직 복구 중 | 중단; 원인 복구와 안정성 확인 후 처음부터 재평가 |
| 같은 payload `eventId`의 outbox 행이 1개가 아님 | 중단; 수동으로 하나를 고르지 말고 사건 담당자에게 이관 |
| 어느 consumer group이 처리했는지 알 수 없거나 멱등성이 증명되지 않음 | 재발행 금지; 중복 side effect 위험을 사건 기록에 남김 |
| topic 소비쌍에 DENY·미등록이 있거나 `created_at`이 중복 억제 창(§2) 밖 | 재발행 금지; 늦은 첫 전달 또는 중복 처리 위험을 사건 기록에 남기고 이관 |
| 격리 fixture에서 조건부 SQL이 정확히 한 행만 바꿈 | 이 결과만으로 production 실행을 승인하지 않음; §5 승인 게이트 필요 |

## 1. 대상 DB와 사전 승인

DB-per-service이므로 Outbox 행의 소유 서비스 DB 한 곳만 조회한다.

| 소유 서비스 | DB |
|---|---|
| order | `peekcart_order` |
| product | `peekcart_product` |
| payment | `peekcart_payment` |
| notification | `peekcart_notification` |

실행 전에 incident/ticket에 서비스, `outbox_events.id`, `event_id`, `event_type`, topic, payload digest, 기존 `retry_count`, `last_attempted_at`, 조회 시각과 승인자를 기록한다. 운영자와 별도 검토자가 대상 행과 소비자 증거를 대조해야 한다. 한 번에 한 행만 다룬다.

## 2. 읽기 전용 진단

먼저 상태와 payload 안의 ID를 읽는다. 바인드 변수를 사용하는 DB client를 쓰고, payload 전문은 incident 접근 제어가 되는 저장소에만 남긴다.

```sql
SELECT id, aggregate_type, aggregate_id, event_type, event_id,
       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.eventId')) AS payload_event_id,
       SHA2(payload, 256) AS payload_sha256,
       status, retry_count, created_at, last_attempted_at, published_at, record_kind
  FROM outbox_events
 WHERE id = :id AND event_id = :event_id;
```

같은 payload `eventId`가 다른 행에 중복으로 들어 있지 않은지도 확인한다. 값이 1이 아니면 아래 UPDATE를 실행하지 않는다.

```sql
SELECT COUNT(*) AS payload_event_rows
  FROM outbox_events
 WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.eventId')) = :payload_event_id;
```

다음 조건을 모두 만족하지 않으면 중단한다.

- 결과가 정확히 한 행이며 `status='FAILED'`, `published_at IS NULL`이다.
- `record_kind`가 `NULL`(legacy domain) 또는 `DOMAIN`이다. `REPLAY`는 이 절차에서 금지한다.
- `payload_event_id`가 `event_id`와 같다. 해당 DB에서 같은 payload `eventId`를 가진 행도 정확히 하나다.
- event type에 등록된 모든 consumer group과 그 핸들러를 열거했다. 각 consumer가 **같은 payload `eventId`**로 이미 처리된 메시지를 중복 억제하는 코드/테스트 근거가 있다. 근거가 없거나 소비 여부가 불명확하면 중단한다.
- `created_at`이 지금부터 `app.idempotency.floor.dlq-replay-window`(현재 7d) 안이다. consumer의 중복 억제는 `processed_events`(`event_id`, `consumer_group`) 행이 남아 있는 동안만 성립한다. 보존기간 `app.idempotency.retention`이 이 창에 시계 오차·정리 지연 여유를 더한 값 이상인지는 네 서비스가 부팅할 때 검사한다(`IdempotencyRetentionProperties`, ADR-0020 §D4-3). 재시도 중 어느 시도가 broker에 닿았는지 모르므로 마지막 시도가 아니라 행이 생긴 시각으로 잰다.
- topic의 모든 소비쌍이 늦은 첫 전달을 견딘다. 이전 발행이 broker에 닿지 않았다면 재발행은 모든 consumer group에 대한 늦은 첫 전달이다. DLQ replay는 같은 위험을 소비쌍 정책 `ReplayPolicyRegistry`(default-deny, ADR-0020 §D5-2 축 5)로 거른다. §2-1에서 DENY이거나 미등록인 소비쌍이 하나라도 있으면 중단한다. PRECONDITION 쌍은 그 소비 서비스의 판정기(`OrderReplayPrecondition`, `PaymentReplayPrecondition`)와 같은 기준을 그 서비스 DB의 현재 상태로 읽기 전용 대조해 통과해야 한다. 대조할 수 없으면 중단한다.
- broker와 대상 topic이 복구되어 있고, consumer가 정상 처리 가능한 상태다. publish 결과가 모호할 수 있다는 사실을 incident에 기록한다.

### 2-1. topic별 소비쌍 판정

정본은 `ReplayPolicyRegistry`(소비쌍 정책)와 `DlqTopology`(발행 소유)다. 아래 표는 두 파일에서 유도한 것이며 `scripts/d092-ops-fixture.py`가 CI에서 대조한다. 실행할 때는 표가 아니라 두 파일을 다시 확인한다. notification은 발행하는 업무 topic이 없다.

| topic | 발행 | 소비쌍 | 재발행 |
|---|---|---|---|
| `order.created` | order | product DENY, payment DENY, notification ALLOW | 금지 |
| `order.cancelled` | order | product ALLOW, payment ALLOW, notification ALLOW | 가능 |
| `order.compensation.requested` | order | payment ALLOW | 가능 |
| `payment.requested` | payment | order DENY, product DENY | 금지 |
| `payment.completed` | payment | order ALLOW, product ALLOW, notification ALLOW | 가능 |
| `payment.failed` | payment | order ALLOW, product ALLOW, notification ALLOW | 가능 |
| `payment.refunded` | payment | order ALLOW, product ALLOW, notification ALLOW | 가능 |
| `payment.approval-recovery.requested` | payment | product DENY | 금지 |
| `payment.approval-recovery.execute` | payment | product DENY | 금지 |
| `stock.reservation.result` | product | order PRECONDITION, payment PRECONDITION | 사전조건 |
| `stock.approval.result` | product | payment DENY | 금지 |
| `stock.compensation.requested` | product | payment ALLOW | 가능 |
| `stock.approval-recovery.snapshot` | product | payment DENY | 금지 |
| `stock.approval-recovery.result` | product | payment DENY | 금지 |
| `product.updated` | product | order ALLOW | 가능 |

`가능`도 §2의 나머지 조건을 모두 만족해야 한다. `금지` topic의 FAILED 행은 이 절차로 다루지 않고 사건 담당자에게 넘긴다.

## 3. 격리 fixture의 조건부 재개 문장

아래 `UPDATE`는 재개 절차의 대상 문장이다. production에서 실행하기 전에 이 저장소 변경과 별개로 DB 소유자 승인, 유지보수 창, 별도 검토자 승인이 필요하다. CI/로컬 검증은 격리 fixture만 사용한다.

```sql
UPDATE outbox_events
   SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL
 WHERE id = :id
   AND event_id = :event_id
   AND status = 'FAILED'
   AND published_at IS NULL
   AND (record_kind IS NULL OR record_kind = 'DOMAIN')
   AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.eventId')) = event_id;
```

운영 실행이 별도로 승인된 경우에도 동일 DB transaction 안에서 진행한다.

1. 대상 서비스의 모든 Outbox poller를 maintenance 절차로 멈추고, 현재 발행 작업이 끝났으며 ShedLock 보유가 만료/해제됐음을 확인한다. 이를 확인하지 못하면 중단한다.
2. `START TRANSACTION` 후 §2 조회를 `FOR UPDATE`로 다시 수행한다. 사전 증거와 다른 필드가 하나라도 있으면 `ROLLBACK`한다.
3. 위 조건부 `UPDATE`를 exact `id`와 `event_id`에 바인딩해 실행한다. 영향을 받은 행 수가 **정확히 1**이고 §2 중복 조회가 여전히 1일 때만 commit한다. 0 또는 1 초과, 중복 수 변경, 확인 불능이면 즉시 rollback하고 재시도하지 않는다.
4. commit 직후 행 상태가 `PENDING`, event id/payload digest가 기록한 값과 같음을 읽어 확인한다. poller를 재개하고 한 이벤트의 결과를 관찰한다.

사전 중복 조회와 대상 행 잠금만으로는 다른 행에 같은 JSON `eventId`를 삽입하는 동시 writer를 막지 못한다. 조회 전부터 commit 후 검증까지 해당 outbox에 같은 payload `eventId`를 만들 수 있는 producer를 모두 멈춰 population을 동결한다. writer를 식별하거나 멈췄음을 확인할 수 없으면 실행하지 않는다. 동결 상태에서 중복 수를 transaction 안에서 다시 확인하고, 사후 감시에서도 중복 또는 불확실성이 보이면 추가 재개를 중단하고 이관한다.

`retry_count`를 0으로 되돌리기 때문에 이전 횟수는 반드시 incident에 보존한다. 앱 상태 전이 메서드나 batch 재개 경로는 이 작업에서 추가하지 않는다.

## 4. 결과 확인과 중단

행이 `PUBLISHED`가 되면 publish 완료로 기록하되 소비자 처리가 완료됐다고 단정하지 않는다. 해당 eventId별 consumer 원장/처리 결과로 후속 효과를 확인한다. 다시 `FAILED`가 되거나 결과가 불명확하면 같은 행을 반복해서 재개하지 않는다. broker 로그, consumer 상태, 기존 DB effect를 대조한 뒤 사건 담당자에게 넘긴다.

incident에 실행자/검토자, 승인 번호, 전후 상태, event ID와 payload SHA-256, 변경 행 수, 시각, poller 중지/재개, consumer 대조 결과를 기록한다. Secret이나 payload 원문은 기록하지 않는다.

## 5. 무료 검증과 외부 재개 조건

무료 검증은 두 계층이다. SQLite fixture는 행 상태/record kind/ID 가드와 runbook 절차의 논리 대조만 한다. notification-service CI의 MySQL 8 integration test는 runbook과 같은 UPDATE/JSON 함수를 실제 MySQL에서 실행해 한 행의 상태 변경과 duplicate payload 사전 거부를 확인한다. 두 검증 모두 동시 writer 제어·운영 잠금 절차·production 승인·실제 Kafka 전달을 증명하지 않는다.

§2-1 판정표와 중복 억제 창 값은 fixture가 `ReplayPolicyRegistry`·`DlqTopology`·네 서비스 `application.yml`과 대조한다. 이 대조는 표가 코드와 같다는 것만 보이며, 각 consumer가 늦은 전달을 견딘다는 근거를 새로 만들지 않는다.

Production 재개는 모두 별도 충족되어야 한다: DB 소유자 승인과 유지보수 창, 별도 검토자, broker/topic 및 consumer 복구 확인, event별 모든 consumer의 `eventId` 멱등성 근거, §2의 중복 억제 창과 소비쌍 판정, 단일행 transaction 및 사후 관찰 담당자. 하나라도 불충분하면 실행을 보류한다.
