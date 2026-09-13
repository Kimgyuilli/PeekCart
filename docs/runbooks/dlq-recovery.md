# Runbook — DLQ 원장 대응

> 대상: DLQ 로 빠진 메시지의 확인·추적·종결
> 구현: ④-c-2a (계획 `docs/plans/task-impl4-c2a-dlq-ledger.md`)
> **재발행(replay)은 아직 불가하다** — ④-c-2b 미구현. §6 참고

---

## 0. 30초 요약

| 상황 | 할 일 |
|---|---|
| `[DLQ 원장]` 알림을 받았다 | §2 로 건을 찾고 §3 으로 원인 분류 → §4 로 종결 |
| `[DLQ 미결]` 경보를 받았다 | §2.2 로 backlog 확인 → 오래된 건부터 §3 |
| `[DLQ listener 정지]` 알림을 받았다 | **§5 로 즉시 이동** — 적재가 멈춰 있다 |
| 원인을 고쳤고 메시지를 다시 처리하고 싶다 | **§6** — 현재는 자동 재발행 수단이 없다 |

---

## 1. 이 원장이 무엇인가

Kafka 소비가 재시도 후에도 실패하면 메시지는 `<원본토픽>.dlq` 로 간다. 그 사실을 서비스별 DB 의
`dead_letter_records` 에 **1행**으로 남긴 것이 이 원장이다.

**원장이 없던 시절의 문제**: 실패는 로그와 Slack 알림으로만 남았고 둘 다 휘발성이라
"지금 미결이 남아있는가" 에 답할 수 없었다.

### 상태

```
OPEN ──acknowledge──> ACKED ──discard──> DISCARDED (terminal)
  └────────────────── discard ──────────────┘
```

| 상태 | 뜻 |
|---|---|
| `OPEN` | 적재됨. 아직 사람이 보지 않았다 |
| `ACKED` | 확인함. **해소가 아니라 조사 중**이라는 뜻 |
| `DISCARDED` | 재처리하지 않기로 종결. **사유 기록 필수** |

> `RESOLVED`(재발행 성공으로 해소)는 ④-c-2b 가 추가한다. 지금은 없다.

---

## 2. 조회

### 2.1 어느 DB 를 봐야 하나

**DB-per-service 라 원장이 4곳에 흩어져 있다**(ADR-0012 D1). 통합 뷰는 만들지 않았다 —
관측 통합은 메트릭 표면(④-d)이 담당한다.

| 실패한 서비스 | DB |
|---|---|
| order | `peekcart_order` |
| product | `peekcart_product` |
| payment | `peekcart_payment` |
| notification | `peekcart_notification` |

**어느 서비스인지 모를 때**: 알림 본문의 `group=` 접두사를 본다 (`order-svc-...` → order).
`group=__unknown__` 이면 **원본 토픽을 발행하는 서비스**의 DB 다 (§3.3).

### 2.2 backlog 빠른 확인 (DB 접속 없이)

```bash
curl -s http://<service>:8080/actuator/deadletter
# {"unresolved":3,"oldestOccurredAt":"2026-08-25T10:12:33.123456","oldestAgeSeconds":98765}
```

### 2.3 미결 목록

```sql
SELECT id, origin_kind, origin_topic, origin_partition, origin_offset,
       failed_consumer_group, event_id, exception_type,
       LEFT(exception_message, 120) AS reason,
       attempt_count, status, occurred_at
  FROM dead_letter_records
 WHERE status IN ('OPEN', 'ACKED')
 ORDER BY occurred_at ASC
 LIMIT 50;
```

### 2.4 한 이벤트가 여러 서비스에서 실패했는지

DLQ 토픽은 공유라 한 메시지가 여러 group 에서 실패할 수 있다. 각 서비스 DB 에서:

```sql
SELECT * FROM dead_letter_records WHERE event_id = '<eventId>';
```

---

## 3. 원인 분류

### 3.1 `origin_kind` 를 먼저 본다

| 값 | 뜻 | 함의 |
|---|---|---|
| `RESOLVED_ORIGIN` | 원본 좌표를 안다 | 정상 경로. `origin_topic/partition/offset` 이 **원본 토픽**의 좌표다 |
| `DLQ_ORIGIN` | 원본 좌표를 모른다 | `kafka_dlt-original-*` 헤더가 없거나 깨졌다. 좌표는 **DLQ 토픽 자신의** 것이다 |

`DLQ_ORIGIN` 이 나오면 DLQ 발행 경로 자체가 의심스럽다 — 정상 `DeadLetterPublishingRecoverer`
경로라면 헤더가 항상 붙는다. 외부에서 `.dlq` 에 직접 쓴 메시지이거나 recoverer 설정이 바뀐 것이다.

### 3.2 `exception_type` 별 대응

| 예외 | 통상 원인 | 대응 |
|---|---|---|
| `IllegalArgumentException` + "eventId 필드가 없습니다" | 계약 위반 메시지 | 발행 측 수정. 이 메시지는 재처리해도 계속 실패 → `DISCARDED` |
| `IllegalArgumentException` + "역직렬화 실패" | 잘못된 JSON | 위와 동일 |
| `OptimisticLockingFailureException` | 동시성 경합 | 재처리하면 성공할 가능성이 높다 → ④-c-2b 대기 (§6) |
| DB/네트워크 예외 | 일시 장애 | 위와 동일 |
| 도메인 예외 (`ORD-xxx`, `PAY-xxx`) | 상태 불일치 | 도메인 상태를 먼저 확인. 이미 해소됐으면 `DISCARDED` |

### 3.3 `failed_consumer_group = '__unknown__'` 인 경우

group 헤더를 판독하지 못한 레코드다. **원본 토픽을 발행하는 서비스**가 단일 소유자로 적재한다
(quarantine 경로). 어느 서비스가 발행자인지는 `docs/plans/task-impl4-c2a-dlq-ledger.md` §4 참고.

이 건은 **누가 실패했는지 모른다**. 원본 메시지의 `event_id` 로 다른 서비스 DB 를 교차 조회해
(§2.4) 실제 소비 실패가 어디서 났는지 좁힌다.

---

## 4. 종결

> **직접 SQL 로 상태를 바꾸지 않는다.** `UPDATE ... SET status=...` 는 "사유 필수" 와 상태 전이
> 규칙을 통째로 우회한다. 종결은 아래 진입점으로만 한다 — 인증 뒤에 있고, 도메인 가드를 거치며,
> `acknowledged_by`/`discarded_by` 가 자동으로 남는다.

### 4.1 확인 (`OPEN → ACKED`)

조사를 시작했음을 남긴다. **해소가 아니다.**

```bash
curl -s -X POST http://<service>:8080/actuator/deadletter/<id> \
     -H 'Content-Type: application/json' \
     -d '{"action":"acknowledge","actor":"<담당자>"}'
# {"id":42,"status":"ACKED","changed":true}
```

`changed:false` 는 이미 그 상태라는 뜻이다 (에러가 아니다).

### 4.2 폐기 (`→ DISCARDED`)

**사유 없이 닫지 않는다.** 근거 없이 닫힌 원장은 "해결됨" 과 구분되지 않아 거짓말을 한다.
사유를 비우면 진입점이 거부한다.

```bash
curl -s -X POST http://<service>:8080/actuator/deadletter/<id> \
     -H 'Content-Type: application/json' \
     -d '{"action":"discard","actor":"<담당자>","reason":"<왜 재처리하지 않아도 되는가>"}'
# {"id":42,"status":"DISCARDED","changed":true}
```

**좋은 사유의 예**
- "발행 측 버그(#123) 수정 완료. 이 메시지는 스키마가 깨져 재처리 불가하며, 해당 주문은 수동 취소 처리함"
- "중복 이벤트. 동일 `eventId` 가 `order-svc-payment-completed-group` 에서 정상 처리됨(확인함)"

**나쁜 사유**: "오래됨", "확인함", "불필요"

### 4.3 보존

`DISCARDED` 는 `app.dead-letter.purge.retention`(기본 90일) 후 자동 삭제된다.
`OPEN`/`ACKED` 는 **자동 삭제되지 않는다** — 장기 미결은 용량 문제가 아니라 SLA 문제다.

---

## 5. DLQ listener 정지 복구

`[DLQ listener 정지]` 알림은 **원장 적재가 멈췄다**는 뜻이다. DLQ 메시지는 계속 쌓이지만 아무도
기록하지 않는다.

### 왜 정지시키는가

DLQ listener 가 DB 장애 등으로 반복 실패할 때 선택지는 둘뿐이다:
- offset 을 커밋하고 넘어간다 → **원장에 못 쓴 레코드가 영구 유실**
- 커밋하지 않는다 → 파티션이 막힌다

**무유실을 택했다.** `ackAfterHandle=false` 라 offset 이 커밋되지 않고, 재시도(1s→5s→30s)가
소진되면 DLQ 계열 컨테이너를 정지하고 사람을 부른다. 정지된 컨테이너는 재기동 시 **같은 offset 부터**
다시 읽으므로 그 사이 메시지도 유실되지 않는다(Kafka retention 이내).

### 절차

1. **근본 원인 확인** — 알림의 `exception=` 를 본다. 대개 DB 접속 불가
   ```bash
   curl -s http://<service>:8080/actuator/health | jq '.components.db'
   ```
2. **원인 해소** — DB 복구 등
3. **재기동** — 컨테이너만 다시 켜는 API 는 없다. 서비스 파드를 재시작한다
   ```bash
   kubectl rollout restart deployment/<service> -n <ns>
   ```
4. **적재 재개 확인**
   ```bash
   curl -s http://<service>:8080/actuator/deadletter
   ```
   `unresolved` 가 증가하면 밀린 DLQ 를 소화하고 있는 것이다.

> 정지된 컨테이너의 listener id 는 `dlq-` 로 시작한다. 일반 업무 listener 는 정지되지 않으므로
> **DLQ 적재만 멈추고 서비스는 계속 동작한다.**

---

## 6. 재발행 (replay)

원장에서 원본 메시지를 **그대로** 다시 발행한다. 계약은 [ADR-0020](../adr/0020-dlq-replay-contract.md) ·
[ADR-0021](../adr/0021-dlq-replay-correlation-anchor.md) · [ADR-0022](../adr/0022-replay-entrypoint-rollout-and-drain.md).

### 6.0 먼저 — 열려 있는가

replay 는 **kill-switch 뒤에 있고 기본값은 닫힘**이다(`app.dead-letter.replay.enabled=false`).

```bash
# 닫혀 있으면 accepted=false 와 "replay 진입점이 꺼져 있다" 사유가 돌아온다.
curl -s -X POST "$GW/api/v1/admin/deadletter/order/42" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d 'action=replay&actor=야간당직'
```

여는 절차(**즉시 반영되지 않는다** — 정적 설정이라 롤링 재기동이 필요하다):

1. `kubectl -n peekcart edit configmap order-service-config` → `APP_DEAD_LETTER_REPLAY_ENABLED: "true"`
2. `kubectl -n peekcart rollout restart deploy/order-service` 후 `rollout status` 로 수렴 확인
3. 위 curl 로 **열린 것을 확인**한다

> 설정 변경과 재기동 사이의 요청은 코드가 막지 못한다 — **운영 규율**이다(ADR-0022 §D1).

### 6.1 접근 경로

`/actuator/deadletter/**` 는 **게이트웨이 관리자 라우트를 통해서만** 도달한다(ADR-0022 §D5):

| 목적 | 경로 |
|---|---|
| backlog 요약 | `GET  {gateway}/api/v1/admin/deadletter/{order\|product\|payment\|notification}` |
| 전이 | `POST {gateway}/api/v1/admin/deadletter/{service}/{id}` |

- **ADMIN 토큰이 필요하다.** `replay` 와 `publication-unknown` 은 서비스가 `ROLE_ADMIN` 을 직접 검사한다.
- **`{id}` 는 숫자만 받는다.** 다른 문자는 라우트가 매칭하지 않아 404 다 — actuator 의 다른 엔드포인트로
  새지 않게 하는 통제이므로 우회 경로를 찾지 말 것.
- Pod 에 직접 port-forward 해서 부를 수 없다 — 인증 주체를 세울 수 없어 401 이다.

### 6.2 개시

```bash
curl -s -X POST "$GW/api/v1/admin/deadletter/order/42" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d 'action=replay&actor=야간당직'
# {"id":42,"rootId":42,"accepted":true,"targetId":42,"attemptId":"...","rejections":[]}
```

거부되면 `rejections` 에 **사유가 전부** 담긴다(6 금지축은 서로 독립이라 하나씩 고치게 하지 않는다).
대표 사유와 조치:

| 사유 | 뜻 | 조치 |
|---|---|---|
| `[안전창]` | `replay_deadline`(원본 timestamp + 7d) 이 지났다 | replay 불가 — §6.5 우회로 |
| `[축5] 정책이 금지한다` | 해당 토픽이 replay deny (예: `payment.requested` — 늦은 재적용이 이중 과금) | 우회로 |
| `[좌표]` | 브로커에 원본 레코드가 없다(retention·compaction) | 우회로 |
| `발행 결과가 미확정이다(REQUESTED)` | 앞선 replay 가 아직 종착하지 않았다 | 기다린다. 영원히 안 끝나면 §6.4 |

### 6.3 재발행 후

- **발행 성공은 사건 해소가 아니다.** `publication_status=PUBLISHED` 여도 backlog 에 남는다.
- 재발행분이 **또 실패하면** 자식 행이 생기고 root 가 재개방된다 — 종결은 root id 로 한다(자식 id 로
  들어와도 root 로 정규화된다).
- 소비까지 확인했으면 `action=resolve` + **무엇을 보고 확인했는지**를 사유로 남긴다.

### 6.4 `REQUESTED` 가 끝나지 않을 때 — `publication-unknown`

원장이 가리키는 outbox 행이 **사라지면** reconciler 는 강등하지 않고 그대로 둔다 — 부재는 실패의 증거가
아니기 때문이다(발행됐는데 행만 지워졌을 수 있다). 그 결과 `REQUESTED` 가 스스로 해소되지 않고,
**사건 종결도 롤백도 막힌다**. 탈출구는 하나다.

**먼저 확인한다** (확인 없이 옮기면 이 전이는 의미가 없다):

```bash
# 표적 group 이 그 offset 을 소비했는가
kubectl -n peekcart exec deploy/kafka -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group order-svc-payment-completed-group
```

```bash
curl -s -X POST "$GW/api/v1/admin/deadletter/order/42" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d 'action=publication-unknown&actor=야간당직&reason=broker offset 확인 — 소비 흔적 없음'
# {"id":42,"rootId":42,"released":1,"rejections":[]}
```

- **사유는 필수다** — 다음 사람이 무엇을 확인했는지 원장에서 읽을 수 있어야 한다.
- **outbox 행이 아직 있으면 거부된다.** 그때는 reconciler 가 스스로 종착시키므로 기다린다.
- 옮긴 뒤 **재발행은 열리지 않는다**(발행 여부를 모르는 건을 다시 싣지 않는다). 사건 종결은 가능하다.

### 6.5 replay 가 불가능할 때의 우회

1. **도메인 상태를 직접 교정한다** — 대개 이게 더 빠르고 안전하다
2. **상류에서 다시 발행한다** — 새 `eventId` 가 부여되므로 `processed_events` 멱등에 걸리지 않는다
   (ADR-0012 D5 가 허용한 경로이며, replay 계약의 우회가 아니라 **별도 경로**다)
3. 처리 후 원장을 `DISCARDED` + 사유로 종결한다 (§4.2)

> **재발행 가능 여부를 시간만으로 판정하지 않는다**(ADR-0020 §D4-2·§D5-1). ① `replay_deadline` 이내이고
> ② 발행 직전 좌표 검증에서 원본 레코드가 **실제로 조회**돼야 한다. `retention.bytes` 가 유한값이라
> 7일 이내에도 크기 기반으로 이미 삭제됐을 수 있다.

---

## 6-R. replay 를 연 뒤의 롤백 절차

**replay 행이 하나라도 생긴 뒤에는 구 이미지로 그냥 내려갈 수 없다.** `record_kind='REPLAY'` 행은
replay-aware poller 만 올바르게 발행한다 — 구 poller 는 그 행의 sentinel(`__replay__`)을 **토픽 이름으로**
써서 발행을 깨뜨린다.

### 순서

1. **진입점을 닫는다** — ConfigMap `APP_DEAD_LETTER_REPLAY_ENABLED: "false"` + 롤링 재기동.
   재기동이 끝나기 전까지는 요청이 여전히 통과할 수 있다(운영 규율).
2. **drain 판정을 돌린다**:
   ```bash
   bash scripts/replay-drain-preflight.sh
   ```
   4개 서비스 DB **각각**에서 아래 4조건이 전부 충족돼야 한다(ADR-0022 §D2):

   | | 조건 |
   |---|---|
   | ⓐ | `outbox_events` 에 `record_kind='REPLAY' AND status='PENDING'` 인 행이 0 |
   | ⓐ' | `dead_letter_records.publication_status='REQUESTED'` 인 행이 0 |
   | ⓑⓒ | 표적 group + `.dlq` intake group 의 lag 가 **2회 연속** 0 |
   | ⓓ | 마지막 `last_replay_settled_at` 이후 상한(기본 546s)이 지났다 |

   - ⓐ' 가 줄지 않으면 §6.4 의 `publication-unknown` 으로 교착을 먼저 푼다.
   - **DB·브로커 접속 실패는 통과가 아니라 차단**이다(fail-closed).
3. **4조건이 충족될 때까지 replay-aware poller 를 유지한다.** 이 단계를 건너뛰면 ⓐ 의 행이 구 poller 에
   집혀 발행이 깨진다.
4. 그 다음에만 구 이미지로 배포한다:
   ```bash
   scripts/deploy-overlay.sh k8s/overlays/gke
   ```

> **래퍼는 우회 가능하다.** `kubectl apply -k` 를 직접 치면 preflight 가 돌지 않는다(ADR-0022 §D3).
> 부득이 건너뛸 때는 `DRAIN_PREFLIGHT_SKIP_REASON="..."` 을 주어 **사유가 로그에 남게** 한다.

---

## 7. 토픽 재생성 시 — generation bump

토픽을 삭제하고 같은 이름으로 다시 만들면 **offset 이 0부터 재사용**된다. 그러면 원장의 물리 식별자
`(cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, failed_consumer_group)`
가 과거 행과 충돌하고, 적재가 `INSERT IGNORE` 라 **새 실패가 정상 중복처럼 조용히 폐기된다.**

### 절차 (순서 중요)

1. 해당 토픽을 소비·발행하는 **모든 서비스**의 `application.yml` 에서 세대를 올린다
   ```yaml
   app:
     dead-letter:
       topic-generations:
         payment.completed: 2    # 1 → 2
   ```
   > 한 서비스라도 빠뜨리면 그 서비스만 옛 세대로 적재해 원장이 갈라진다.
2. **먼저 배포**한다
3. 그다음 토픽을 삭제·재생성한다

미등록 토픽을 참조하면 적재 시점에 `IllegalStateException` 이 나고 DLQ listener 가 정지한다(§5) —
조용히 기본값으로 떨어지지 않는다. 설정 누락을 늦게라도 반드시 알게 하려는 의도다.

---

## 8. 담당 · SLA

| 항목 | 기준 |
|---|---|
| `[DLQ listener 정지]` | **즉시** 대응. 적재가 멈춘 상태다 |
| `[DLQ 원장]` 신규 적재 | 영업일 기준 1일 내 `ACKED` |
| `[DLQ 미결]` age 경보 | 기본 24시간 초과 시 발생. 발생 시 당일 내 분류 |
| backlog 경보 | 기본 50건 초과. 개별 대응이 아니라 **공통 원인**을 찾는다 |

임계값은 `app.dead-letter.alert.*` 에서 조정한다.

---

## 9. 알려진 한계

1. **replay 불가** (§6) — ④-c-2b
2. **4개 DB 를 따로 조회**해야 한다 (§2.1) — 통합 뷰는 만들지 않았다
3. **Slack 알림은 best-effort** — DB commit 과 Slack 호출이 한 트랜잭션이 아니라 commit 후 사망하면
   알림이 0회다. 내구적 신호는 원장 행 자체이며 §2.2 로 언제든 조회한다
4. **cooldown 은 인스턴스 로컬** — replica 마다 독립이고 재기동 시 초기화된다
5. **메트릭 없음** — ④-d(부모 P11)에서 추가한다. 그때까지는 §2.2 actuator 가 유일한 조회 표면이다
