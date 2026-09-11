# ADR-0021: DLQ replay 재실패 상관 — 대조 축을 `record_kind` 에서 원장 앵커 + payload digest 로

- **Status**: Accepted
- **Date**: 2026-09-06
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga (④-c-2b-3)
- **관계**:
  - **Partially Supersedes** [ADR-0020](./0020-dlq-replay-contract.md) — §D5-4 의 **"판독 조건 = 한 트랜잭션 안의 원자 대조"** 항목 중 **`record_kind=REPLAY` 대조**와, 그에 딸린 **음성 테스트 목록의 `record_kind` 불일치 항목**. §D5-4 의 나머지(**대조의 정본은 원장이다** · **헤더 값 자체를 신뢰하지 않는다** · **수명 경쟁** · **실제 DLT group 대조** · **어긋나면 독립 행**)는 **그대로 유지**되며, 이 ADR 은 그 원칙들의 귀결이다
  - **Refines** ADR-0020 **§D8-3**(fence 의 `key`·`payload`·`eventId` byte-for-byte 요구) — 그 동일성을 **재실패 시점에 검증 가능한 형태**로 영속한다

---

## Context

ADR-0020 §D5-4 는 replay 재발행분이 **또 실패했을 때** 그것을 원래 사건(root)에 잇는 대조 절차를 정했다. 자식 적재 트랜잭션 안에서 ① 헤더의 attempt UUID 로 root 를 찾고 ② 잠근 뒤 ③ **ledger owner · 실제 DLT consumer group ↔ `last_replay_target_group` · `destination_topic` ↔ `origin_topic` · `record_kind=REPLAY`** 를 전부 대조하고 ④ 통과하면 자식을 잇고 root 를 재개방한다.

구현 ④-c-2b-3 착수 시점에 이 목록이 **두 곳에서 성립하지 않는다**는 것이 드러났다.

### 문제 1 — `record_kind=REPLAY` 는 대조 축이 될 수 없다

`record_kind` 는 `outbox_events` 에만 있는 컬럼이다(ADR-0020 §D3, 구현 ④-c-2b-2 [#102](https://github.com/Kimgyuilli/PeakCart/pull/102)). 그런데 발행에 성공한 outbox 행은 `PUBLISHED` 가 되고 **retention 후 cleanup 이 삭제**한다. 미결 root 원장은 무기한 남고 DLQ 적재는 지연될 수 있으므로, **재실패가 도착했을 때 그 `record_kind` 를 읽을 행이 이미 없을 수 있다.**

이것은 새로 발견된 위험이 아니다. **ADR-0020 §D5-4 자신이 바로 그 이유로 "대조의 정본은 원장이다 (outbox 가 아니다)" 를 정했다** — "outbox 를 정본으로 삼으면 **수명 경쟁에서 진다**". 같은 절이 그 결론을 내려놓고 대조 목록에는 outbox 전속 컬럼을 남겼다. **ADR 내부의 충돌이다.**

### 문제 2 — 남은 대조 축만으로는 조작된 payload 를 거르지 못한다

`record_kind` 를 빼면 대조는 `attempt UUID` · `owner` · `group` · `topic` · `root-id` 로 좁아진다. 여기에 좌표성 fingerprint(`event_id` · `original_key` · `original_timestamp`)를 더해도, **payload 자체를 묶는 값이 하나도 없다.**

그래서 다음이 성립한다 — 같은 `eventId` · key · timestamp 를 싣고 **본문만 바꾼** 메시지를 같은 업무 토픽에 발행하면, 그것이 실패해 DLT 로 갈 때 **모든 대조를 통과해 남의 사건에 자식으로 붙고, 종결된 root 를 재개방**시킨다.

ADR-0020 §D5-4 는 이 경로를 명시적으로 경계했다:

> **헤더 값 자체를 신뢰하지 않는다.** 이 값들은 비밀이 아니고, 원본 producer 도 같은 업무 토픽에 application header 를 쓸 수 있다 — owner/group 일치 검사만으로는 **조작된 메시지가 실패해 DLT 로 갈 때 임의 root 연결·재개방을 유발**할 수 있다.

`record_kind` 는 (성립했다면) 이 경계를 지키는 축이었다. 그것을 제거하면서 **대체 축을 두지 않으면 ADR-0020 이 막으려던 것이 그대로 열린다.**

또한 §D8-3 의 fence 는 replay 발행에 `key`·`payload`·`eventId` **byte-for-byte 동일**을 요구한다. 그 요구가 **발행 시점에만** 검사되고 재실패 시점에는 확인할 수 없다면, 계약이 절반만 존재하는 셈이다.

### 대조에 쓸 수 없는 것들

- **원장의 `payload` 컬럼** — `app.dead-letter.payload.max-length`(기본 8000자)로 **잘려 저장**된다(`DlqPayloads.truncate`). 절단분 비교는 상한 밖 변조를 통과시키므로 "byte-for-byte" 를 주장할 수 없다. 원장 payload 는 애초에 **진단용**으로 규정돼 있다.
- **outbox 의 어떤 컬럼도** — 문제 1과 같은 이유로 전부 수명 경쟁에서 진다.

---

## Decision

### D1. 대조 축을 **원장 앵커 + fingerprint + payload digest** 로 확정한다

ADR-0020 §D5-4 의 `record_kind=REPLAY` 대조를 삭제하고, 아래를 대조 목록의 정본으로 삼는다. **전부 일치해야 상관하며, 하나라도 어긋나면 독립 root 행으로 적재한다**(§D5-4 의 이 규칙은 유지).

| # | 축 | 자식(재실패 입력) | root(원장 앵커) |
|---|---|---|---|
| 1 | attempt | 헤더 `pc-replay-attempt-id` | `last_replay_attempt_id` |
| 2 | owner | **적재하는 서비스 자신** | 헤더 `pc-replay-ledger-owner` |
| 3 | group | 실제 DLT consumer group **≡** 헤더 `pc-replay-target-group` | `last_replay_target_group` |
| 4 | root-id | 헤더 `pc-replay-root-id` | root 행의 id |
| 5 | topic | `origin_topic` | `origin_topic` |
| 6 | eventId | payload 에서 추출한 `eventId` | `event_id` |
| 7 | key | `original_key` (**null-safe**) | `original_key` |
| 8 | timestamp | `original_timestamp` | `original_timestamp` |
| 9 | **payload digest** | `sha256(payload 전문)` | **`last_replay_payload_digest`** |

**축 3 은 3자 대조다.** 헤더를 빼고 "실제 group ↔ 원장 앵커" 두 값만 비교하면 `pc-replay-target-group` 헤더가 **지워도 결과가 같은 죽은 데이터**가 된다 — 계약에 있는 헤더가 아무것도 지키지 않는 상태를 만들지 않는다.

**축 5 는 `destination_topic ↔ origin_topic` 대조와 같은 비교다.** replay 는 `destination_topic == origin_topic` 을 fence 로 강제하므로(§D8-3), 자식의 `origin_topic` 이 root 와 같은지 보는 것이 그 대조를 포함한다.

### D2. `last_replay_payload_digest` 를 원장 root 에 영속한다

- **값**: 재발행 대상 payload **전문**(절단 전)의 **SHA-256 hex**. 컬럼은 `VARCHAR(64) NULL`, **DEFAULT 없음**.
- **writer 는 replay 진입점 하나다** — 진입점이 원본 토픽 좌표에서 실제로 읽어온 레코드로 계산해, attempt 앵커(`last_replay_attempt_id`·`last_replay_target_group`)와 **같은 트랜잭션·같은 UPDATE** 로 기록한다. 셋이 갈라지면 대조가 부분적으로만 성립한다.
- **tombstone(payload 없음)은 digest 도 null 이고, 양쪽 null 이면 일치**로 본다 — `original_key` 의 null-safe 규칙과 같다.
- **nullable 인 이유**: 롤링 배포 중 구버전 writer 의 INSERT 를 살린다(ADR-0020 §D3 의 additive 규칙 그대로). **DEFAULT 를 두지 않는 이유**: 기본값이 있으면 "앵커를 기록하지 않았다" 와 "빈 digest" 가 같아 보여 누락이 조용히 삼켜진다.

### D3. digest 는 **인증이 아니라 오상관 방지**다

이 값은 비밀이 아니고 서명도 아니다. 업무 토픽에 쓸 수 있는 주체는 digest 를 맞춰 붙일 수도 있다. 그런데도 이것이 필요한 이유는 **막으려는 것이 공격이 아니라 잘못된 연결**이기 때문이다 — 같은 `eventId` 를 가진 서로 다른 본문이 한 사건으로 합쳐지면 원장이 거짓을 기록하고, 종결된 사건이 되살아난다.

**더 강한 경계가 필요해지면 ADR-0020 §D5-4 말미가 남긴 격상 경로(서버 검증 가능한 서명/MAC opaque token)를 따른다.** 현 단계에서 채택하지 않는 이유도 그때와 같다: 로컬 DB 대조가 오상관 방지라는 목표에 대해 같은 보장을 주면서 키 관리가 없다.

### D4. 음성 테스트 목록을 교체한다

ADR-0020 §D5-4 의 음성 테스트 목록에서 **`record_kind` 불일치**를 **payload digest 불일치**로 바꾸고, D1 의 9축을 **각각 독립적으로 변이시키는** 형태로 요구한다. "여러 축을 한꺼번에 바꾼 케이스" 는 어떤 대조 조건을 지워도 red 가 되므로 **개별 조건의 존재를 증명하지 못한다**.

요구: 9축 각각 단일 변이 → 전부 독립 root · 양성 대조군 1종 → 상관 · 대조 조건을 하나 제거하면 **정확히 그 축의 케이스만** red.

---

## Consequences

### 좋아지는 것

- 대조가 **outbox 수명과 완전히 독립**해진다. cleanup·retention·reconciler 중단 어느 것도 상관을 깨지 못한다 — ADR-0020 §D5-4 가 원래 원했던 성질이 이제 실제로 성립한다.
- §D8-3 의 payload 동일성 요구가 **발행 시점과 재실패 시점 양쪽에서** 검증된다.
- ADR-0020 내부 충돌(정본은 원장이라면서 대조는 outbox 컬럼)이 해소된다.

### 비용

- **4서비스 additive 마이그레이션 1개**(order V10 · product V8 · payment V8 · notification V6). DB-per-service 라 같은 ALTER 를 4벌 복제하며, `dead-letter-schema-parity-lint.sh` 가 최종 스키마로 4벌 동일성을 강제한다.
- 진입점의 claim 트랜잭션이 **payload 전문을 한 번 더 읽고 해시**한다. 대상 레코드는 이미 좌표 reader 가 읽어 온 것이므로 추가 I/O 는 없고 비용은 해시 1회다.
- **digest 가 기록되지 않으면 상관이 전부 실패한다** — 축 9가 항상 불일치가 되기 때문이다. 그래서 writer 누락은 "조용한 통과" 가 아니라 "전부 갈라짐" 으로 드러난다. 다만 **fixture 로 digest 를 심는 테스트만 있으면 그 사실이 관측되지 않으므로**, 진입점을 실제로 거치는 관통 테스트를 별도로 요구한다(계획서 V-30).

### 한계 (인정하고 넘어가는 것)

- **전환 구간**: ④-c-2b-3a 배포 후 ~ 2b-4 진입점 활성화 전까지는 어떤 root 도 앵커를 갖지 않는다. 이 구간에 상관은 **일어나지 않으며**, 그것이 정상이다(replay 자체가 없다).
- **구버전 root**: 앵커 3종이 NULL 인 기존 행은 상관 대상이 아니다. 그 행들에 대한 replay 는 2b-4 이후 새로 개시되며 그때 앵커가 기록된다.
- **다른 group 의 재실패는 상관되지 않는다.** replay 는 업무 토픽에 실리므로 그 토픽을 구독하는 모든 group 이 다시 소비한다. 표적이 아닌 group 의 실패는 축 3에서 걸러져 **독립 root** 가 된다 — 오류가 아니라 사실에 맞는 결과다(replay 는 한 group 을 표적한 행위이고, 다른 group 의 실패는 다른 사건이다). 발행 자체를 좁히는 것은 §D5-2/§D8-3 의 fence 소관이다.

---

## Alternatives Considered

| 대안 | 왜 채택하지 않았나 |
|---|---|
| `record_kind=REPLAY` 를 그대로 두고 outbox 를 조회 | ADR-0020 §D5-4 가 이미 반증했다 — `PUBLISHED` outbox 는 retention 후 삭제되고 미결 root 는 무기한 남는다. 정상 attempt 가 대조에 실패해 "재실패 N회에도 backlog 1" 이 깨진다 |
| `record_kind` 를 원장 행에도 복제 | 그 값은 **자식 행에 대한 사실**인데, 자식은 재실패로 새로 만들어지는 행이라 그 값을 스스로 채울 근거가 헤더밖에 없다. 조작 가능한 입력을 원장에 옮겨 적는 것일 뿐 대조력이 늘지 않는다 |
| 원장의 `payload` 컬럼을 직접 비교 | **잘려 저장된다**(기본 8000자). 상한 밖 변조를 통과시키므로 §D8-3 의 byte-for-byte 를 주장할 수 없다. 상한을 없애면 원장이 메시지 저장소가 되어 진단용이라는 규정과 충돌한다 |
| 서명/MAC opaque token | 같은 보장(오상관 방지)에 **키 관리·회전·배포**가 붙는다. ADR-0020 §D5-4 가 남긴 격상 경로를 그대로 유지하되, 지금 필요한 것은 인증이 아니라 오상관 방지다(D3) |
| digest 를 헤더로만 싣고 원장에 두지 않음 | 헤더는 조작 가능하다. 자기 자신을 증명하는 값이 되어 아무것도 대조하지 않는다 |

---

## References

- [ADR-0020](./0020-dlq-replay-contract.md) — DLQ replay 계약 (§D5-4 를 본 ADR 이 부분 무효화, §D3·§D6-2b·§D8-3 은 유지)
- [ADR-0012](./0012-phase4-db-event-saga-contract.md) — DB-per-service (4벌 복제의 근거)
- 계획서: `docs/plans/task-impl4-c2b-dlq-replay.md` §PR ④-c-2b-3 (P14·P15)
- 코드: `common/.../kafka/ReplayHeaders.java` · `common/.../kafka/DlqHeaders.java` · `*/global/deadletter/DeadLetterRecord.java` · `scripts/dead-letter-schema-parity-lint.sh`

---

## Update Log

### 2026-09-08 — 검증 ID 참조 정정 (④-c-2b-3b P17-b)

§D2 의 "진입점을 실제로 거치는 관통 테스트를 별도로 요구한다(계획서 **V-30**)" 에서 **V-30 은 잘못된 참조**였다.
그 ID 는 ④-c-2b-2 에서 **이미 머지된** `OutboxReplayPublicationIntegrationTest`(`:44·122`)가
`record_kind IS NULL` 호환성 테스트 이름으로 쓰고 있다 — 계획서에서 같은 ID 가 두 곳에 배정돼 있었다.

- `record_kind IS NULL` 호환성 = **`V-30`** (불변 — 코드가 이미 그렇게 부른다)
- P21 진입점 관통(digest writer 검증) = **`V-35`**
- `V-34` = **철회**(미사용 ID)

**결정은 바뀌지 않았다.** digest writer 가 진입점 하나라는 §D2 도, 관통 테스트를 요구한다는 것도 그대로다 —
바뀐 것은 그 테스트를 가리키는 **번호**뿐이다. 그래서 `adr/README.md:14` 가 새 ADR 을 요구하는
"트레이드오프·결정 변경" 에 해당하지 않는다.
