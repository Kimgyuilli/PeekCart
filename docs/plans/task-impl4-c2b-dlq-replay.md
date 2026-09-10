# ④-c-2b — DLQ replay 경로 (구현)

> 부모 계획: `docs/plans/task-impl4-choreography-saga.md` **P9 · P10 · P13(나머지)**
> 형제: **④-c-2a (원장 적재)** — `task-impl4-c2a-dlq-ledger.md` ([#90](https://github.com/Kimgyuilli/PeakCart/pull/90))
> 선행: **[ADR-0020](../adr/0020-dlq-replay-contract.md)** ([#98](https://github.com/Kimgyuilli/PeakCart/pull/98)) · **④-c-2b-0** 브로커 retention 실설정 ([#99](https://github.com/Kimgyuilli/PeakCart/pull/99))
> 상태: **계획 확정** (2026-09-02, 리뷰 1R 16건 전량 반영). 착수 조건 4개 전부 종료 — 부록 A.3 참조.
> 범위 분리 이력(D1~D8 이 왜 ADR 사안이었나)은 **부록 A** 에 보존한다.

---

## 1. 명제 — 무엇이 성립하면 **미완**인가

계약의 정본은 ADR-0020 이다. 이 계획은 그 계약이 **코드로 강제되는지**만 판정한다.
아래 중 **하나라도 성립하면 ④-c-2b 는 미완**이다.

| # | 미완 조건 | 대응 ADR |
|---|---|---|
| **N1** | 재발행이 원본과 다른 토픽·파티션·key·payload·`eventId`·timestamp 로 나갈 수 있다 | D3 · D8-3 |
| **N2** | 기록된 좌표를 읽지 않고, 혹은 **요청 offset ≠ 반환 offset** 인 채로 재발행할 수 있다 | D5-1 |
| **N3** | §D5-2 의 6 금지축(`event_id` NULL · `__unknown__` group · `DLQ_ORIGIN` · 좌표 무효 · 정책 금지 · `original_timestamp` NULL) 중 하나라도 통과해 발행에 도달한다 | D5-2 |
| **N4** | `replay_deadline` 이 재실패마다 재계산되어 안전창이 연장된다 | D5-3 |
| **N5** | `publication_status = PUBLISHED` 인 root 가 backlog·oldest-age 집계에서 빠진다 | D6-2 · D6-3 |
| **N6** | `publication_status = REQUESTED` 인 행을 `RESOLVED`/`DISCARDED` 로 닫을 수 있다 | D6-2b I-1 |
| **N7** | 종결된 root 에 늦은 자식이 도착했는데 root 가 재개방되지 않는다(또는 자식 적재가 거부·상속된다) | D6-2b I-2 |
| **N8** | 재실패 자식이 backlog 를 증가시킨다 — 재실패 N회에 backlog 가 1 을 넘는다 | D6-3 |
| **N9** | `root_record_id` 도입이 **기존 미결 행을 집계에서 탈락**시킨다 (배포 전후 미결 건수 불일치) | D6-3 전환 구간 |
| **N10** | 위조·타 서비스·다른 group·다른 root·다른 destination topic 의 상관 헤더가 **root 에 상관**된다 | D5-4 |
| **N11** | outbox `PUBLISHED` cleanup 이 먼저 돈 뒤 도착한 지연 DLQ 적재가 **독립 incident 로 갈라진다** | D5-4 수명 경쟁 |
| **N12** | 같은 원장 행에 동시 replay 요청이 와서 **outbox 행이 2개 이상** 생긴다 | D6-4 fence |
| **N13** | 종결이 broker ack 로 자동 수행된다 — 사람의 명시 전이 없이 `RESOLVED` 가 된다 | D6-2 |
| **N14** | replay 를 SQL 직접 변경이나 새 CLI 로 개시할 수 있다 (진입점이 `deadletter` 엔드포인트 1종이 아니다) | D7 |
| **N15** | notification 이 replay 를 지휘할 수 없다 — outbox 부재로 자기 원장 행의 재발행이 불가능하다 | D2 |
| **N16** | `record_kind` 를 명시하지 않는 신버전 writer 경로가 존재한다 (**코드 경로 강제** — DB `NOT NULL` contract 는 §10 R1 로 이연, 완료 조건 1 참조) | D3 expand→contract |
| **N17** | 검증이 **관측한 값을 그대로 기대값으로 적어** 통과한다 (자기대조) | ④-c-2b-0 diff 리뷰 1R 재발 방지 |

> **N17 을 명제에 올린 이유**: 직전 PR(#99)에서 `KafkaTopicConfigsTest` 가 SUT 상수를 기대값으로 읽어
> 8/4 → 10/2 MiB 변이에도 green 이었다. 같은 결의 실수가 ADR-0020 계획서(`task-adr0020-dlq-replay-contract.md` §3 **P4-4**)의 리뷰 단계에서도 나왔다 —
> **한 세션에서 두 번**. 이 계획의 모든 단언은 ADR 본문 값을 **리터럴로 독립 기재**하고 변이로 red 를 확인한다.

---

## 2. 배경 — 착수 전 코드 검증

**전제는 ADR 이 아니라 현재 코드다.** 아래는 계획 초안 작성 **전에** 직접 확인한 결과다.
(구조 변경 — 모듈 경계 이동·peel·rename — 은 **없다**. notification 의 `global/outbox` 는 기존 3서비스 코드의
**복제 추가**이고 옮겨지는 대상이 없으므로 `PLAN-BLINDSPOTS.md` B1 역의존 스윕 대상이 아니다.)

| # | 확인한 사실 | 근거 (직접 확인) | 계획에 미치는 영향 |
|---|---|---|---|
| V1 | outbox 8파일이 order/product/payment 에서 **byte 동일**하다. 단 `OutboxEventStatus` 만 다르다 — order/product 에는 `BACKFILL` 이 있고 payment 에는 없다 | `diff` 8종 실행 결과 | replay 컬럼·kind 분기를 **4벌**(notification 포함)에 동일 적용. `OutboxEventStatus` 는 손대지 않는다 |
| V2 | `buildRecord()` 는 `new ProducerRecord<>(eventType, null, aggregateId, payload)` — **토픽=eventType 고정 · partition=null · timestamp 미지정 · 헤더는 trace/user 2종** | `OutboxPollingService:119-125` | D3 분기 필요. 도메인 경로는 **불변**으로 유지 |
| V3 | 발행 crash window 는 실재한다 — `:83` ack, `:85-86` 별도 save | `OutboxPollingService:83-86` | D1 at-least-once. "중복 발행 0" 단언 금지 |
| V4 | `outbox_events.event_type` 은 **VARCHAR(50) NOT NULL**, `aggregate_id` **NOT NULL(50)**, `payload` **NOT NULL** | `V1__init_order.sql:69-81` · `OutboxEvent:22-35` | replay 행도 이 3개를 채워야 한다. **tombstone(payload NULL) replay 는 이 스키마로 표현 불가** → §10 미해결 R3 |
| V5 | 가장 긴 토픽명은 `stock.compensation.requested`(28자) | `DlqTopology:91` | `event_type`(50) 에 destination topic 을 넣어도 넘치지 않는다. 단 **정본은 `destination_topic` 컬럼**이고 `event_type` 은 중복 기재가 되므로 넣지 않는다(P10) |
| V6 | notification 에 outbox 가 **없다** — `global/` = `config·deadletter·idempotency` 뿐, 마이그레이션 V1~V3 | `find` · `ls db/migration` | D2 신설. Flyway 번호는 **V5**(V4 는 P1 이 쓴다) |
| V7 | notification 은 **ShedLock·validation·`KafkaTemplate<String,String>` 을 이미 갖췄다** | `build.gradle`(shedlock-spring/jdbc-template·starter-validation) · `ShedLockConfig` · `shedlock` 테이블(V2) · `NotificationKafkaConfig:34` | outbox 신설에 **새 의존성·새 인프라가 없다**. ADR 이 우려한 "인프라 증가" 는 poller·스케줄러 빈 3개 + 테이블 1개로 한정된다 |
| V8 | notification 은 `EnableScheduling` 이 이미 켜져 있고 `NewTopic` 을 **하나도 선언하지 않는다** | `NotificationApplication:14` · `grep NewTopic` | poller 가 즉시 돈다. replay 발행 대상 토픽은 전부 남의 프로비저닝 — **D8 예외의 가장 순수한 사례** |
| V9 | `DlqHeaders.parse()` 는 표준 `DLT_*` 만 읽고 **application 헤더를 일절 읽지 않는다**. `DlqOrigin` 에 헤더 필드가 없다 | `DlqHeaders:39-66` · `DlqOrigin:25-36` | D5-4 상관 헤더는 **판독 경로 신설**이 필요하다 (P14) |
| V10 | `DeadLetterRecorder.record()` 는 `insertIfAbsent` 1회 + 중복 시 `incrementAttempt` — **root 개념도 상관 대조도 없다** | `DeadLetterRecorder:52-87` | P3(self-root) · P15(원자 대조) 이 이 메서드를 재작성한다 |
| V11 | 집계 3종이 **JPQL 문자열 리터럴**로 상태를 박고 있다 — `status IN ('OPEN','ACKED')` | `DeadLetterRecordJpaRepository:78·82·86` | `RESOLVED` 추가만으로는 부족하고 **root 조건**까지 같은 3곳을 고쳐야 한다 |
| V12 | `findPurgeable` 은 `status = 'DISCARDED' AND discarded_at < :threshold` 만 본다 | `:94-96` | `RESOLVED` + `resolved_at` 를 **함께** 넣지 않으면 종결분이 영구 잔존한다 |
| V13 | `dlq.backlog`·`dlq.oldest.age` 게이지가 **같은 repository 메서드를 재사용**한다 | `DeadLetterMetrics:26-33` | 쿼리를 root-only 로 바꾸면 메트릭·actuator 가 **자동으로 함께** 바뀐다. 갈라질 위험이 없다 |
| V14 | Grafana alert 는 `sum by (application)(dlq_backlog{...})` 로 **메트릭 이름만** 참조한다 | `k8s/monitoring/shared/grafana-alerts.yml:292-303` | **alert 식 변경은 불필요**하다(ADR-0019 식 고정과 무충돌). 다만 *의미*가 행 → incident 로 바뀌므로 주석·runbook 갱신은 필요 |
| V15 | `status` 는 `VARCHAR(30)`, `attempt_count`/`occurred_at` 등 나머지는 NOT NULL | `V6__dead_letter_records.sql:43-50` | `RESOLVED` 추가에 마이그레이션 불필요(2a 확장점 유효). 신규 컬럼은 전부 nullable additive |
| V16 | Flyway 최신 번호: order **V7**(cursor index) · product V5 · payment V5 · notification V3 | `ls db/migration` | ADR Consequences 의 "order V6·product V5·payment V5·notification V3 계열" 서술은 **④-c-2a 가 쓴 번호**를 가리킨 것이고, 이번 신규는 **order V8 · product V6 · payment V6 · notification V4** 다 |
| V17 | `AdminClient` 를 **프로덕션 코드에서 쓰는 곳이 없다**. 테스트 1곳뿐 | `grep AdminClient` → `KafkaTopicConfigMechanismIntegrationTest` 만 | P18 좌표 reader 는 신규 표면이다. 그 테스트가 참조 구현이 된다 |
| V18 | `processed_events` 는 `(event_id, consumer_group)` UNIQUE + `processed_at` 로컬 시각 | `ProcessedEvent:16-33` | D1 "소비 효과 1회" 검증은 이 테이블의 **행 수**로 판정한다 |
| V19 | `findPendingEvents` 는 `status='PENDING' ORDER BY created_at ASC` — **kind 무관** | `OutboxEventJpaRepository:16` | replay 행도 같은 큐에 들어간다. 별도 poller 불필요(ADR Alternative D 기각과 정합) |
| V20 | `docs/05-data-design.md:177` 이 "소비 전용 notification 은 `processed_events` 만" 이라고 **명시**한다 | 해당 줄 | D2 로 거짓이 된다 → P24 문서 정정 대상 |

### 2.1 검증이 계획을 바꾼 곳

- **V16** — ADR 이 적은 마이그레이션 번호는 신규 번호가 아니었다. order 는 그 사이 `V7__orders_cursor_index.sql`(구현 ⑥ [#96](https://github.com/Kimgyuilli/PeakCart/pull/96))이 추가됐다. 번호를 그대로 썼다면 Flyway 충돌로 부팅이 깨졌다.
- **V7** — notification outbox 신설 비용이 ADR 추정보다 **작다**. ShedLock·validation·KafkaTemplate 이 이미 있어 새 의존성이 0이다. "소비 전용의 단순함을 잃는다"는 트레이드오프는 유효하나, 인프라 증분은 테이블 1 + 빈 3 이다.
- **V14** — ADR §D6-3 이 "경보" 를 갱신 대상에 넣었으나, 실제 alert 는 메트릭 이름만 참조하므로 **식 변경이 없다**. ADR-0019 의 식 정본 고정과 충돌하지 않는다. 갱신 대상은 문서(의미 변화)뿐이다.
- **V4** — `payload NOT NULL` 이라 **tombstone 레코드의 replay 는 현 스키마로 불가능**하다. ADR 은 이를 다루지 않았다. 범위 밖으로 두고 §10 R3 에 남긴다.
- **V5** — `event_type` 에 destination topic 을 넣으면 정본이 둘이 된다. `record_kind=REPLAY` 행의 `event_type` 은 **`__replay__` sentinel** 로 고정하고, 발행 토픽은 `destination_topic` 만 읽는다(P10).

---

## 3. ADR 선행 판단 — **불필요**

- 새 외부 의존성: 없다 (AdminClient 는 이미 클래스패스에 있는 kafka-clients 의 API)
- 아키텍처 경계 변경: 없다 (notification outbox 는 **ADR-0020 D2 가 이미 결정**)
- 새 환경/인프라: 없다 (k8s 매니페스트·GCP 리소스 무변경)

---

## 4. PR 분할

규모(P1~P25 · 4 DB · 5모듈)와 **ADR-0020 D3/D6-3 이 요구하는 expand → deploy → backfill 순서** 때문에
한 PR 로 묶을 수 없다. 배포 경계가 곧 PR 경계다.

| PR | 제목 | P 항목 | 배포 후 성립하는 것 |
|---|---|---|---|
| **④-c-2b-1** | 원장 축 확장 + incident 집계 정정 | P1~P7 | 두 축 분리·`RESOLVED`·root-only 집계·종결 전파 (**replay 는 아직 불가**) |
| **④-c-2b-2** | 발행 표면 — outbox replay kind + notification outbox | P8~P13 | replay 레코드를 **발행할 수 있는** poller (진입점 없음 → replay 행이 생기지 않는다) |
| **④-c-2b-3a** | 상관 표면 — 헤더 계약 · 매핑 · ADR-0021 · lint | P14 | 헤더가 **정의·강제·판독**되고 앵커가 매핑된다 (아직 상관하지 않는다) |
| **④-c-2b-3b** | 원자 상관 + 재개방 | P15~P17 | 재실패가 root 로 수렴하는 경로가 **먼저** 선다 (아직 replay 개시 불가) |
| **④-c-2b-4** | 좌표 reader + 진입점 + fence + backfill + 문서 | P18~P25 | replay **개시 가능** — 그 시점에 상관·재개방이 이미 배포돼 있다 |

> **순서 정정 (리뷰 1R #2)**: 초안은 진입점(2b-3)을 상관·재개방(2b-4)보다 **먼저** 열었다.
> 그러면 2b-3 만 배포된 구간에서 재발행분이 재실패할 때 **독립 incident 로 갈라져** ADR-0020 §D5-4·§D6-3 을
> 정면으로 위반한다. 대안으로 "endpoint 를 플래그로 잠갔다가 나중에 연다" 도 있었으나, 그것은
> **계약을 지키는 코드 대신 운영 규율에 기대는 것**이라 채택하지 않았다. 상관 경로를 먼저 세우면
> replay 가 열리는 순간 이미 계약이 성립한다.

> **2b-3 을 3a/3b 로 재분할했다 (2026-09-06, 사용자 승인)**. 이유는 두 가지다.
> ① **범위** — 계획 리뷰 2라운드가 2b-3 에 ADR 신설(ADR-0021) · 4서비스 digest 마이그레이션 ·
> parity lint 재구조화 · 송신 측 검증 · Counter 2종을 더했다. P14 하나가 이미 앞선 PR 한 개 분량이다.
> ② **리뷰 예산** — 계획 3R 이 Codex `usage limit` 으로 미실행이라 **수렴 조건(P1=0 + 새 표면 무추가)이
> 미달인 채로 남았다**. 한 덩어리로 구현하면 diff 리뷰도 같은 한도에 걸려 #102 의 "리뷰 미측정" 이 반복된다.
> 자르면 각 PR 이 한도 안에서 리뷰된다.
>
> **3a 가 단독 배포돼도 안전하다** — 판독은 아무도 보내지 않는 헤더를 읽는 no-op 이고, 매핑·컬럼은 additive 이며,
> 송신 검증은 `record_kind='REPLAY'` 행에만 걸리는데 그 행을 만드는 진입점이 아직 없다.
> **ADR-0021 이 코드보다 한 PR 앞서는 것은 의도된 순서다** — 이 저장소의 규약이 "새 결정 시 ADR 을 먼저"다
> (2b-2 의 ADR-0012 D1 표가 **뒤늦게** 따라간 것이 오히려 예외였다).

**되돌리기**: 2b-1~2b-3b 는 앞 PR 만 배포된 상태에서 정지해도 기존 동작이 유지된다 — 신규 컬럼은 전부
nullable 이고, `record_kind IS NULL → DOMAIN` 해석이 구버전 writer 를 흡수하며, 진입점이 없으면 replay 행
자체가 생기지 않는다. **2b-4 배포 이후는 다르다** — replay 행이 존재하는 상태의 롤백은 P24 의 별도 절차를 따른다.

---

## 5. 작업 항목

### 진행 상태

| PR | 항목 | 상태 |
|---|---|---|
| 2b-1 | P1 · P1-b · P2 · P3 · P4 · P5 · P6 · P7 | ✅ **완료** — diff 리뷰 3R(12건 전량 반영, 3R P1=0) · 918 tests 0 failed · 변이 8종 red |
| 2b-2 | P8 · **P9 · P9-b · P9-c** · P10 ~ P13 | ✅ **완료** [#102](https://github.com/Kimgyuilli/PeakCart/pull/102) — 902 tests 0 실패(모듈별) · 변이 14종 red · lint 7종 green(parity self-test 18종) · **Codex diff 리뷰 미실행(quota)** |
| 2b-3a | **P14** | ✅ **완료** [#103](https://github.com/Kimgyuilli/PeakCart/pull/103) — ADR-0021 신설 · 4서비스 digest 마이그레이션 · 송신 allowlist 강제 · parity lint 최종 스키마 전환(self-test 18→23종) · 1006 tests 0 실패 · lint 15종 · 변이 1종 red · **계획 3R·diff 리뷰 미실행(quota)** |
| 2b-3b | **P15 · P16 · P17 · P17-b** | ✅ **완료** [#104](https://github.com/Kimgyuilli/PeakCart/pull/104) — 6단계 실행 순서(detach 회피 + 단계 5 current read) · 9축 대조(group 3자) · `reopen()` · `LedgerOwner` 빈 주입 · afterCommit 결과별 알림 4행 · Counter 2종(CommitAware, reason enum bounded) · ADR-0021 Update Log(V-35 재배정). 계획 리뷰 3R~5R 전량 반영(**4R 발산은 3b 경계 이탈이 원인 → P24 를 2b-4 로 되돌림**), **6R 사용자 지시로 중단(미측정)**. diff 리뷰 1R 8건 전량 반영(**실제 실패 2건 검출**). 변이 16종 red · lint 15종 · parity self-test 23종 · **전 모듈 1043 tests 0 실패**. **미충족**: #103 재리뷰 스킵 · 계획 수렴 미달 · `V-19o` fixture 전용 |

| 2b-4 | P18 ~ P25 | 🔲 |

### PR ④-c-2b-1 — 원장 축 확장 + incident 집계 정정

**P1.** `dead_letter_records` additive 마이그레이션 **4 DB** (order **V8** · product **V6** · payment **V6** · notification **V4**).
전부 nullable. 컬럼: `root_record_id BIGINT` · `publication_status VARCHAR(20)` · `outbox_event_id BIGINT` ·
`replay_deadline DATETIME(6)` · `replay_policy VARCHAR(120)`(정책 식별자+버전+판정을 담으므로 40 은 부족 — 구현 중 상향) · `last_replay_attempt_id VARCHAR(36)` ·
`last_replay_target_group VARCHAR(120)` · `resolved_at DATETIME(6)` · `resolved_by VARCHAR(120)` ·
`reopened_at DATETIME(6)` · `reopened_reason VARCHAR(500)`.
인덱스: `idx_dlr_root (root_record_id, status)` — root-only 집계용 · `idx_dlr_attempt (last_replay_attempt_id)` — P15 대조용.
**FK 는 걸지 않는다** — 자기참조 FK 는 자식 INSERT 와 root 잠금 순서를 한 트랜잭션 안에서 뒤집을 수 있고, 4 DB 에 같은 제약을 복제하는 비용 대비 얻는 게 없다.

**P2.** `DeadLetterStatus.RESOLVED` 추가 + `isTerminal()` = `DISCARDED || RESOLVED`.
`DeadLetterRecord.resolve(actor, evidence)` — **근거 문자열 필수**(공백이면 `IllegalArgumentException`),
`resolved_at`/`resolved_by`/`note` 기록. 이미 terminal 이면 `false`.

**P3.** 신규 적재 행의 **self-root** 설정 — `DeadLetterRecorder.record()` 가 `insertIfAbsent` 로 1을 받으면
같은 트랜잭션에서 그 행을 조회해 `root_record_id = id` 로 채운다.
> `INSERT ... SET root_record_id = LAST_INSERT_ID()` 를 쓰지 않는 이유: `INSERT IGNORE` 가 건너뛴 경우
> `LAST_INSERT_ID()` 는 **직전 성공 INSERT 의 값**을 그대로 돌려줘 **남의 행을 root 로 지목**한다.
> 조회 경로는 이미 존재한다(`findByClusterIdAnd...`, V10).

**P4.** 집계·purge 를 **incident(root) 단위**로 전환. `DeadLetterRecordJpaRepository`:
- `countUnresolved` · `findOldestUnresolvedOccurredAt` · `findStaleUnresolved` →
  `(root_record_id IS NULL OR root_record_id = id) AND status IN ('OPEN','ACKED')` — **expand 조건**(D6-3 1단계)
- `findPurgeable` → root 조건 + `status IN ('DISCARDED','RESOLVED')` + 종결 시각을
  **`CASE WHEN status='DISCARDED' THEN discarded_at WHEN status='RESOLVED' THEN resolved_at END < :threshold`**
  로 고른다.
  > **`COALESCE(discarded_at, resolved_at)` 을 쓰지 않는다 (리뷰 1R #10)**: `DISCARDED` → 재개방 → `RESOLVED` 를
  > 거친 root 는 **두 시각을 모두** 갖는다. `COALESCE` 는 항상 과거의 `discarded_at` 을 골라
  > **새 종결의 보존기간이 지나기 전에 삭제**한다. 감사 시각은 지우지 않고(재개방 이력이 사라지므로),
  > 대신 **현재 상태에 해당하는 시각**만 본다.
- purge 는 root 삭제 시 **자식을 함께** 삭제한다(`deleteByRootRecordId`). 자식 단독 purge 경로는 만들지 않는다
- **purge 도 root 를 `FOR UPDATE` 로 잠그고 상태·cutoff 를 재검사한 뒤 같은 트랜잭션에서 삭제한다 (2R #5)** —
  초안은 조회 후 삭제였다. 그 사이 P15 가 root 를 재개방하고 자식을 넣으면 **먼저 조회한 purge 가
  살아 있는 incident 와 새 자식을 지운다**. P5·P15 는 root 잠금으로 직렬화되는데 purge 만 그 규약 밖에 있었다

**P5.** **종결의 root 정규화·전파** (ADR §D5-4 "root 와 활성 자식에 원자적 전파" · 리뷰 1R #6).
> **구현 중 추가된 계약 (diff 리뷰 1R·2R)**: 요청 행과 purge 후보를 **엔티티가 아니라 id 로** 읽는다.
> 엔티티로 읽으면 영속성 컨텍스트에 적재되어 뒤의 `SELECT ... FOR UPDATE` 가 **잠금만 얻고 상태를
> refresh 하지 않고**, 자식은 잠그지 않으면 REPEATABLE READ **스냅샷**을 봐서 root 는 no-op 하면서
> 자식만 덮어쓴다. 그래서 `findRootIdOf`/`findPurgeableRootIds`(id projection) + `findChildrenForUpdate`
> (활성 자식만 `PESSIMISTIC_WRITE`)가 계약이다.
- 종결 요청의 대상 id 가 **자식이면 canonical root 로 정규화**한다(자식 단독 종결은 미결을 종결로 위장한다)
- root 를 `SELECT ... FOR UPDATE` 로 잠근 뒤 root 와 **활성 자식 전부**를 같은 트랜잭션에서 전이한다
- `acknowledge`/`resolve`/`discard` 셋 다 이 경로를 쓴다 — 하나라도 빠지면 축이 갈라진다

**P6.** `DeadLetterEndpoint` — `action=resolve` 추가(근거 필수) + `backlog()` 응답에 `publication` 축 요약 추가.
**I-1 가드는 여기 넣지 않는다** — `publication_status='REQUESTED'` 를 만드는 주체가 2b-4 에 생기므로
2b-1 의 가드는 vacuous 하다. I-1 은 **P21 과 같은 PR**에서 원자 조건으로 넣는다.

**P1-b.** (구현 중 추가) `dead-letter-schema-parity-lint` 를 **신규 마이그레이션까지** 확장하고
**java 복제 parity** 를 함께 검사한다.
> **P25 에서 앞당긴 이유**: 신규 파일을 만드는 PR 이 그 파일의 parity 를 검사하지 않으면 2b-2·2b-3 세 PR 동안
> 4벌이 갈라져도 아무 것도 실패하지 않는다. 기존 lint 는 `V*__dead_letter_records.sql` 만 보므로 신규 파일이
> **glob 에 걸리지 않아 조용히 통과**한다.
> java 7파일은 현재 4서비스에서 **byte 동일**이며(구현 전 확인), 이 PR 이 그 7파일을 전부 고친다 —
> 한 벌만 고치는 실수를 정적으로 막는다. P25 에는 `outbox_events` parity 와 진입점 단일성 검사가 남는다.

**P7.** 회귀 테스트 + E2E 게이트 갱신.
- 기존 미결 행(= `root_record_id IS NULL`)이 마이그레이션 **전후로 같은 건수**로 집계된다 (N9)
- `publication_status='PUBLISHED'` 인 root 가 backlog·oldest-age 에 **계속 잡힌다** (N5 — ADR 이 지목한 핵심 단언)
- `RESOLVED` 는 backlog 에서 빠지고 purge 대상이 된다. **`DISCARDED` → 재개방 → `RESOLVED` 교차 전이**의 purge 경계
- 자식 id 로 종결 요청 시 root 로 정규화되어 root+자식이 함께 전이된다
> **테스트 배치**: 신규 회귀 테스트는 **order-service** 에 둔다. `global/deadletter` java 7파일은 4서비스에서
> **byte 동일**이고 그 동일성을 P1-b lint 가 강제하므로, 4벌 테스트 복제는 같은 코드를 네 번 도는 비용만 든다.
> 나머지 3서비스는 **기존 원장 테스트가 회귀 없이 통과**하는 것으로 확인한다.
- **`scripts/e2e/saga_e2e.py:64-68` 의 `EXPECTED_MIGRATIONS` 를 `order 1~8 · product 1~6 · payment 1~6 · notification 1~4` 로 갱신**한다
  (리뷰 1R #14 — 이 상수는 **버전 집합 정확 일치**를 readiness 에서 강제하므로, 갱신하지 않으면 E2E 가 먼저 red 가 된다)

### PR ④-c-2b-2 — 발행 표면

#### 착수 전 코드 검증 (2026-09-02) — 계획이 바뀐 곳

> 원칙: 전제는 ADR 이 아니라 **현재 코드**다. 아래는 P8~P13 초안의 진술을 직접 grep/파일 확인한 결과다.

| # | 초안의 진술 | 확인 결과 | 처분 |
|---|---|---|---|
| C-1 | `OutboxPollingService:83-86`(ack→`markPublished`→save) · `:116`(실패 경로 재save) · `:119-124`(`event_type` 을 토픽으로) | **전부 정확**. `buildRecord` 는 `new ProducerRecord<>(event_type, null, aggregate_id, payload)` + trace/user 헤더 2종 | 유지 |
| C-2 | `OutboxEventJpaRepository:31-33` 이 `status='PUBLISHED' AND published_at < cutoff` 를 **무조건** 삭제 | 정확 (native `DELETE ... LIMIT :limit`) | 유지 |
| C-3 | 거짓이 되는 javadoc **2곳**(`OutboxEventCleanupScheduler:23` · `OutboxRetentionProperties:18-19`) | 정확하나 **4곳이 더 있다**(리뷰 A #1 로 2곳 추가) — `notification-service/application.yml`(“소비 전용(outbox 미소유)”) · `notification-service/build.gradle:27-28`(“cleanup 소유(processed only)”) · `build.gradle:31-32`(“소비 전용이라 outbox poller 가 없어”) · `NotificationApplication:12`. **`NotificationApplication:12` 는 P9 를 기다릴 것 없이 이미 거짓이다** — `DeadLetterMaintenanceScheduler:58·114` 가 이미 `@Scheduled` 2개를 갖는데 javadoc 은 `@EnableScheduling` 을 processed_events cleanup 전용으로 서술한다 | **P9 를 6곳으로 확대** |
| C-4 | `global/outbox` 8파일 복제 · `OutboxEventStatus` 는 payment 판본(`BACKFILL` 없음) | 정확. 8파일 중 **7개가 order↔product↔payment byte 동일**이고 `OutboxEventStatus` 만 갈린다(order/product 에 `BACKFILL` 있음) | 유지 |
| C-5 | “3서비스와 스키마 동일 — **parity lint 가 대조한다**” | **거짓**. `dead-letter-schema-parity-lint.sh` 는 `dead_letter_records` 전용이다 — `CREATE TABLE dead_letter_records` 추출 · `V*__dead_letter_records.sql`/`V*__dead_letter_replay_axis.sql` glob · `global/deadletter` java 9파일. **`outbox_events` 를 대조하는 검사는 존재하지 않는다** | **P9-b 신설** — 대조기를 만들지 않으면 notification 판본이 갈라져도 아무 것도 실패하지 않는다 |
| C-6 | P12 의 cleanup 제외 조건을 `NOT EXISTS (… d.outbox_event_id = o.id …)` 로 표기 | **그대로는 실행되지 않는다** — 다만 이유는 초안 정정이 처음 적은 것과 다르다(리뷰 A #2 가 반증, `mysql:8.0.46` 실측으로 확인). 실제 원인은 **alias `o` 를 선언한 적이 없다**는 것뿐이다 | **P12 SQL 정정** — 정규명 `outbox_events.id` (또는 `AS o` 선언). 아래 실측 매트릭스 참조 |
| C-7 | (초안 무언급) reconciler 는 `publication_status='REQUESTED'` 원장 행을 조회한다 | 해당 컬럼에 **인덱스가 없다**(V8 은 `(root_record_id, status)`·`(last_replay_attempt_id)` 만 생성) | **인덱스를 추가하지 않는다** — `dead_letter_records` 는 DLQ 유입량에 유계이고, 같은 컬럼을 스캔하는 `countUnresolvedByPublicationStatus`(2b-1)가 이미 무인덱스로 돈다. 4 DB 마이그레이션 + parity lint glob 확장 비용이 이득을 넘는다. **§10 R8 로 이연** |
| C-8 | P9 가 notification base yml 에 `cleanup.cron` 키를 선언 | `app.outbox.cleanup.cron` 은 **order/product/payment 어느 yml 에도 선언돼 있지 않다**(`@Scheduled` 인라인 기본값 `0 45 3 * * *` 만) | **선언하지 않는다** — notification 에만 선언하면 4벌이 갈라진다. P9 의 키 목록에서 제거 |
| C-9 | (초안 무언급) `OutboxPollingScheduler` 는 `@Scheduled(fixedDelay = 5000)` **리터럴**이다 | ④-d-2b P17 의 “주기를 타입 안전 properties 로, 인라인 기본값 금지” 계약은 **`app.scheduler.*`·`app.refund.*` 에만** 적용됐다(실측: outbox·deadletter·idempotency 스케줄러는 전부 인라인 유지) | ~~복제본도 그대로 둔다~~ → **CI 실패가 이 판단을 뒤집었다 (아래 “CI 후속” 참조)**. 주기를 `fixedDelayString` 으로 뺐다 — 배경 잡이 도는 상태에서는 발행 횟수를 세는 테스트가 성립하지 않는다. 운영 기본값 5s 는 불변 |
| C-10 | (초안 무언급) notification 에 poller 를 배선할 수 있는가 | `KafkaTemplate<String,String>`(DLQ recoverer 가 이미 사용) · `SlackPort` · `@EnableScheduling` · `shedlock` 테이블 **전부 존재** | 신규 인프라 0 |
| C-11 | (초안 무언급) sentinel/원장 id 가 컬럼 폭에 들어가는가 | 3서비스 `outbox_events` DDL byte 동일 · `event_type VARCHAR(50)`(`__replay__` 수용) · `aggregate_id VARCHAR(50)`(원장 id 문자열 수용) | 유지 |
| C-12 | (초안 무언급) ADR-0012 D1 표 갱신 | `0012:53` 이 아직 `| Notification | notifications | processed_events |` 이다. ADR-0020 D2 가 개정을 지시했으나 **미반영** | **P9-c 신설** — 이 PR 이 표를 참으로 만드는 순간이므로 Update Log 를 같은 PR 에서 단다 |
| C-13 | (초안 무언급) notification 이 **남의 토픽에 발행**하게 되는 근거 | ADR-0020 **D8**(`1 topic = 1 producer` 명시적 예외 · 프로비저닝 소유와 발행 권한 분리). `NewTopic` 소유는 그대로이므로 ADR-0011 D2 는 무영향 | P9·P11 에 근거 명시 |
| C-14 | (무언급) notification 이 outbox 를 갖게 되면 갱신이 필요한 **운영 표면**이 있는가 | **없다 — 스윕으로 확인**. `.github/workflows/ci.yml`·`k8s/**` 에 outbox 참조 0건(설정이 이미지 안 base yml 소유) · notification `application-k8s.yml`/`application-local.yml` 에 outbox·shedlock 키 0건(ADR-0007 정합) · `observability-promql-lint.sh` 의 유일한 outbox 문자열은 self-test 치환용이고 per-service alert 목록이 아니다 · ShedLock 락 이름은 3서비스가 각각 다른 값을 선언하고 있어 `notificationOutboxPollingJob` 추가에 충돌 없다(DB-per-service 라 `shedlock` 테이블 자체가 분리) · `docs/runbooks/dlq-recovery.md:213` 의 outbox 서술은 서비스 중립이라 참으로 남는다 | **범위 확대 없음**. 갱신 대상은 계획에 이미 있는 `EXPECTED_MIGRATIONS`(notification `1~5`) · `05:177`(P24) · ADR-0012 D1(P9-c) 뿐이다 |

**P8.** `outbox_events` additive 마이그레이션 **3 DB** (order **V9** · product **V7** · payment **V7**). 전부 nullable:
`record_kind VARCHAR(10)` · `destination_topic VARCHAR(120)` · `destination_partition INT` ·
`record_key VARCHAR(255)` · `source_record_timestamp BIGINT` · `replay_target_event_id VARCHAR(36)` ·
`replay_headers TEXT` · `replay_root_record_id BIGINT` · `target_consumer_group VARCHAR(120)`.
**`DEFAULT` 를 두지 않는다** (D3 — 기본값은 discriminator 누락을 조용히 삼킨다).
`EXPECTED_MIGRATIONS` → `order 1~9 · product 1~7 · payment 1~7 · notification 1~5`.

**P9.** notification outbox 신설 (D2). Flyway **V5** 로 `outbox_events` 를 **replay 컬럼 포함 상태로** 생성
(3서비스 `V1__init_*.sql` 의 DDL + P8 의 additive 컬럼을 합친 최종 형태 — 대조기는 P9-b 가 만든다) +
`global/outbox` 8파일 복제(`OutboxEventStatus` 는 payment 판본 = `BACKFILL` 없음, 나머지 7파일은 **byte 동일**) +
`OutboxRetentionProperties` 활성화. `@Scheduled` 인라인 기본값은 **그대로 복제한다** (C-9).
설정키 `app.outbox.*`(`retention` · `lock-name: notificationOutboxPollingJob` · `polling.*`)는
**`notification-service/src/main/resources/application.yml` (base) 소유**다 — 런타임 동작 정책이므로 ADR-0007 상
프로파일 배치가 금지된다. 프로파일에 중복·override 가 없음을 바인딩 테스트로 고정한다.
**`cleanup.cron` 은 선언하지 않는다 (C-8)** — 3서비스 어디에도 없는 키다.
`docs/05-data-design.md:177` 의 "notification 은 `processed_events` 만" 서술은 P24 에서 정정한다.
**소스 javadoc/주석 계약은 같은 PR 에서 고친다 (2R #13 · C-3 으로 6곳 확대)** — 이 PR 이 머지되는 순간
**거짓이 되는** 진술 5곳: `OutboxEventCleanupScheduler:23`("notification/user 제외") ·
`OutboxRetentionProperties:18-19`("product/order/payment 만 활성화") ·
`notification-service/application.yml`("notification 은 소비 전용(outbox 미소유) → outbox retention 없음") ·
`notification-service/build.gradle:27-28`("notification 은 cleanup 소유(processed only)") ·
`build.gradle:31-32`("notification 은 소비 전용이라 outbox poller 가 없어 미보유였음").
**+ 이미 거짓인 것 1곳**: `NotificationApplication:12` 가 `@EnableScheduling` 을 processed_events cleanup 전용으로
서술하는데 `DeadLetterMaintenanceScheduler:58·114` 의 `@Scheduled` 2개가 이미 그 위에 얹혀 있다(④-c-2a 이후).
**선재 결함이지만 같은 문장을 이 PR 이 또 건드리므로 함께 고친다** — 남겨두면 다음 사람이 "이 PR 이 만든 stale"
로 오독한다.
**발행 권한 근거는 ADR-0020 D8** — notification 은 자기가 프로비저닝하지 않은 토픽에 write 하게 된다.
`NewTopic` 소유는 옮기지 않으므로 ADR-0011 D2 는 무영향이다 (C-13).

#### CI 후속 (2026-09-03) — 테스트 3종의 결함 4건

PR [#102] 의 CI 에서 `OutboxAtLeastOnceIntegrationTest` 가 실패했다. **운영 코드 결함이 아니라 전부 내 테스트
하네스 결함**이었고, 원인을 확정하기까지 가설 4개가 실측으로 반증됐다.

| 가설 | 판정 |
|---|---|
| Mockito 재스터빙이 배경 `@Scheduled` 호출과 경합 | **참** — CI 실패(`RuntimeException at :105`)의 원인. 다만 이것만으로는 로컬 실패가 남았다 |
| 배경 스케줄러가 같은 행을 집어간다 | 껐는데도 실패 → **단독 원인 아님** |
| 첫 발행이 6s 타임아웃을 넘긴다 | `publish-timeout=30s` 로도 실패 → **반증** |
| 앱과 drain 이 서로 다른 Kafka 컨테이너를 본다 | 부트스트랩 주소 일치 실측 → **반증** |

**확정된 원인**: 실패 시 `brokerEndOffsets` 가 **전부 0** 이었다 — 소비를 못 한 게 아니라 **1사이클의 send 가
실제로 실패**한 것이다. 컨테이너가 여럿 뜬 느린 실행에서 **토픽 생성이 끝나기 전에 첫 send 가 나가** 메타데이터
대기로 타임아웃한다.

**도중에 내가 틀렸던 추론 2건도 남긴다**:
- *“`hasMessage("DB down")` 이 통과했으니 send 는 성공했다”* — **거짓**. send 가 실패해도
  `handlePublishFailure` 끝의 save 가 같은 예외를 덮어써 밖으로 나간다. 이 착각이 진단을 한 라운드 지연시켰다.
- *“배경 잡을 껐다”* — **거짓**. `fixedDelay` 는 **간격**만 정하고 첫 실행은 기동 직후 그대로 일어난다
  (로그의 `[scheduling-1] Unexpected error occurred in scheduled task` 가 증거).

**수정 4건** (세 테스트 클래스 공통):
1. **`awaitTopicReady`** — 측정 전 토픽·리더 준비 대기. 이 테스트가 재는 것은 재발행이지 토픽 프로비저닝이 아니다
2. **측정을 소비 → broker end offset 으로 전환** — group 조인·리밸런스·fetch 타이밍이라는 변수를 제거
3. **1사이클 ack 를 명시적으로 단언** — 이 단언이 없어 첫 발행이 조용히 사라져도 통과하고 **마지막 개수
   단언에서 엉뚱하게** 터졌다. 실제로 이 단언이 원인을 특정했다
4. `drain()` 을 `subscribe` → `assign` + `seekToBeginning`, Mockito 재스터빙 → `AtomicBoolean` 단일 answer

**검증**: 두 클래스 동시 실행 **6회 연속 통과**(6m20s·8m15s 짜리 느린 실행 포함 — 예전에 실패하던 것이 정확히
그 구간이다). notification 2종·parity lint(self-test 18종) green.

**P9-b.** `outbox_events` parity 대조기 (C-5). `dead-letter-schema-parity-lint.sh` 를 확장한다 —
신규 스크립트를 만들지 않는 이유는 대조 대상·판단 근거("4개 모듈에 흩어져 한 모듈의 테스트가 다른 모듈을 볼 수 없다")가
같고, CI 배선이 이미 있기 때문이다. 비교 축 3개:
1. **최종 스키마 동일성** — order/product/payment 는 `V1__init_*.sql` 의 `CREATE TABLE outbox_events` + P8 의
   `ALTER TABLE outbox_events`, notification 은 P9 의 `CREATE TABLE outbox_events`. **한 벌씩 다른 파일에서 오므로
   원문 해시가 아니라 "컬럼명 → 타입·nullability" 집합**으로 정규화해 비교한다(replay 축 ALTER 는 원문 해시였다 —
   그쪽은 4벌이 서로의 사본이라 성립했지만 여기서는 성립하지 않는다).
2. **P8 신설 9컬럼이 4서비스 전부에 존재하고 전부 nullable** (`DEFAULT` 절 부재도 함께 — D3)
3. **`global/outbox` java 7파일 byte 동일** + `OutboxEventStatus` 는 **order/product 동일 · payment/notification 동일**
   (2집합 계약을 lint 가 명시적으로 인코딩한다 — “전부 동일” 로 걸면 기존 상태가 곧바로 red 다)
4. **목록 자체를 검사한다 (구현 중 추가)** — `global/deadletter` 에서 *지금 4벌이 byte 동일한데 `java_files`
   목록에 없는* 파일을 `DLQ-PARITY-014` 로 잡는다. 구현 중 `DeadLetterPublicationReconciler` 를 4벌 복제해
   놓고 목록에 더하는 것을 잊었고, 그것은 **아무 것도 실패시키지 않아 드러나지 않았다** — ④-c-2b-1 의
   glob 미확장과 같은 구멍이 신규 파일에서 재발한 것이다. 사람의 기억 대신 검사가 목록을 지킨다.
   디렉토리 전체를 요구하지 않는 이유: `DeadLetterConsumer`/`KafkaConfig`/`QuarantineConsumer` 는 토픽·group 이
   서비스마다 정당하게 다르다. **이 검사가 기존 미등록 복제본 2개(`DeadLetterContainerGuard`·
   `DeadLetterKafkaConfig`, ④-c-2a 산출물)도 찾아내 목록에 편입**했다 — 계획에 없던 소폭 확대다

**P9-c.** ADR-0012 D1 표 갱신 (C-12). `0012:53` 의 Notification 행을
`processed_events` → `processed_events, outbox_events` 로 고치고 **Update Log** 에 근거(ADR-0020 D2)를 남긴다.
ADR 본문 수정은 Update Log 를 동반해야 하며 커밋은 `fix(adr):` 로 낸다(⑤ 의 ADR-0012 갱신 선례).
**이 PR 에서 하는 이유**: 표를 참으로 만드는 변경이 이 PR 이고, P24 로 미루면 3개 PR 동안 ADR 이 거짓을 말한다.

**P10.** `OutboxEvent.replay(...)` 팩토리 — `record_kind='REPLAY'` · `destination_topic`/`destination_partition`/
`record_key`/`source_record_timestamp`/`replay_target_event_id`/`replay_headers`/`replay_root_record_id`/
`target_consumer_group` 을 채우고, `event_type` 은 **`__replay__` sentinel**, `aggregate_type='DLQ_REPLAY'`,
`aggregate_id` = 원장 행 id 문자열(V4 의 NOT NULL 충족).
도메인 팩토리 `create(...)` 는 `record_kind='DOMAIN'` 을 **명시**한다.
> **sentinel 을 destination topic 으로 대체하지 않는 이유**: 구버전 poller 는 `event_type` 을 그대로
> 목적지 토픽으로 쓴다(`OutboxPollingService:119-124`). destination topic 을 넣어두면 롤백 시 구 poller 가
> **원장 id 를 key 로 업무 토픽에 조용히 발행**한다 — 파티션이 틀리고 fence 도 안 거친 발행이다.
> sentinel 이면 존재하지 않는 토픽으로 향해 **눈에 띄게 실패**한다. 롤백 절차는 P24 가 별도로 못박는다.

**P11.** poller kind 분기 — `buildRecord()` 를 `record_kind` 로 갈라
`new ProducerRecord<>(destinationTopic, destinationPartition, sourceRecordTimestamp, recordKey, payload, headers)`
를 조립한다. 헤더는 `replay_headers` allowlist JSON 에서만 만든다(표준 `DLT_*` **미탑재**).
**도메인 경로의 기존 `buildRecord()` 는 한 줄도 바꾸지 않는다.**

**P12.** reconciler — `publication_status` 전이 주체 1종. `REQUESTED` 인 원장 행의 `outbox_event_id` 로
outbox 상태를 조회해 `PUBLISHED`/`PUBLISH_FAILED` 로 전이한다. `@Scheduled` + `@SchedulerLock`.
**관리 API 는 `REQUESTED` 까지만 만든다**(D6-4).

**outbox cleanup 과의 경쟁을 닫는다 (2R #6)** — 현 cleanup 은 `status='PUBLISHED' AND published_at < cutoff` 인
행을 **무조건** 지운다(`OutboxEventJpaRepository:31-33`). reconciler 가 retention 이상 멈추면 replay outbox 가
먼저 삭제되고, root 는 **`REQUESTED` 에 영구 고착**된다 — I-1 때문에 종결도 못 한다. 둘을 함께 막는다:
1. cleanup 에서 **연결된 root 가 아직 `REQUESTED` 인 replay 행을 제외**한다 (원장과 outbox 는 **같은 서비스 DB**다).
   **상관 참조를 정규명으로 쓴다 (C-6)** — 초안의 `d.outbox_event_id = o.id` 는 **alias `o` 를 선언한 적이 없어서**
   깨진다. `mysql:8.0.46`(프로젝트 이미지) 실측 매트릭스:

   | 형태 | 결과 |
   |---|---|
   | `DELETE FROM outbox_events AS o … WHERE … o.id … LIMIT 1` | **성공** (1행 삭제) — 단일 테이블 DELETE 는 8.0.16+ 부터 alias 를 받는다 |
   | `DELETE FROM outbox_events … WHERE … o.id … LIMIT 1` (초안) | **ERROR 1054** `Unknown column 'o.id' in 'where clause'` |
   | `DELETE FROM outbox_events … WHERE … outbox_events.id … LIMIT 1` (채택) | **성공** (1행 삭제) |
   | `DELETE o FROM outbox_events o … LIMIT 1` | **ERROR 1064** — multi-table 형식은 `LIMIT` 을 지원하지 않는다 |

   즉 `AS o` 선언도 유효한 선택지다. **정규명을 택하는 이유는 문법 제약이 아니라** 이 파일이 4서비스 byte 동일
   복제라 diff 를 최소로 두기 위함이다. 세 번째 행의 실행에서 `publication_status='REQUESTED'` 로 연결된 행이
   실제로 **살아남는 것**까지 함께 확인했다:
   ```sql
   DELETE FROM outbox_events
    WHERE status = 'PUBLISHED' AND published_at < :cutoff
      AND NOT EXISTS (SELECT 1 FROM dead_letter_records d
                       WHERE d.outbox_event_id = outbox_events.id
                         AND d.publication_status = 'REQUESTED')
    LIMIT :limit
   ```
   이 파일은 4서비스 byte 동일 복제이므로 **notification 판본을 포함해 4벌을 함께 고친다**(P9-b 축 3이 강제한다)
2. **outbox 부재를 `PUBLISH_FAILED` 로 추론하지 않는다 (3R #3)** — 행 부재는 실패의 증거가 아니다.
   **이미 발행된 행**이 레거시 cleanup·수동 삭제·정합성 결함으로 사라져도 똑같이 관측된다. 자동 강등하면
   **발행된 사건을 "실패" 로 감사 기록하고 재요청까지 열어준다**. ADR §D6-4 는 outbox 가 **실제 `FAILED` 로
   소진된 경우에만** 그 전이를 정의한다.
   → 부재는 **fail-closed 경보 + 운영자 판정 대상**으로 둔다. 1의 제외 조건이 정상 경로에서 부재를 만들지
   않으므로, 부재가 관측되면 그것 자체가 **계약 위반 신호**다. 이 상태의 종결 경로는 §10 **R7** 로 남긴다

**P13.** D1 검증 — **실제 DB + Kafka 통합테스트**로 at-least-once 를 관측한다.
> **리뷰 1R #11 이 초안을 반증했다**: "`save` 스텁 예외 1회" 로는 중복 발행이 일어나지 않는다.
> `OutboxPollingService:85-86` 이 `markPublished()` 로 **인메모리 상태를 이미 PUBLISHED 로 바꾼 뒤** save 하고,
> 실패하면 `handlePublishFailure` 가 `:116` 에서 **같은 객체를 다시 save** 한다 — 두 번째 save 가 성공하면
> `PUBLISHED` 가 그대로 저장되어 재발행이 없다. 초안의 검증은 **주장을 관측하지 못하는 설계**였다.

따라서: 해당 사이클의 **두 save 를 모두 실패**시키고 → DB 재조회로 행이 `PENDING` 임을 확인 → 장애 복구 후
다음 poll 실행 → **broker 에 서로 다른 2개 레코드**가 있고 **같은 `eventId` 의 `processed_events` 증가분이 1**임을 확인한다.

### PR ④-c-2b-3 — 재실패 상관 + 재개방 (**3a / 3b 로 분할**)

> **3a = P14** (헤더 계약 · 판독 · 매핑 · digest 마이그레이션 · ADR-0021 · parity lint 확장 · javadoc)
> **3b = P15~P17** (원자 대조 · 재개방 · 검증 매트릭스 · DLT 계약 · 배포/롤백 순서)
> 아래 착수 전 코드 검증 표(C-15~C-28)는 **양쪽 공통**이다.

#### 착수 전 코드 검증 (2026-09-05) — 계획이 바뀐 곳

> 원칙: 전제는 ADR 이 아니라 **현재 코드**다. 아래는 P14~P17 초안의 진술을 직접 grep/파일·바이트코드 확인한 결과다.

| # | 초안의 진술 | 확인 결과 | 처분 |
|---|---|---|---|
| C-15 | 재발행 레코드에 실은 `pc-replay-*` 커스텀 헤더가 **재실패 시 DLT 레코드까지 살아남는다** (P14 의 대전제) | **참 — 바이트코드로 확인**. `spring-kafka-3.3.14` 의 `DeadLetterPublishingRecoverer.accept()` 는 `new RecordHeaders(record.headers().toArray())` 로 **원본 헤더 전량을 복사한 뒤** `addAndEnhanceHeaders` 로 `DLT_*` 를 얹는다(`javap -c` 오프셋 170~192). `stripPreviousExceptionHeaders`(기본 true)는 **예외 헤더만** 지운다 | 유지 — P14 성립 |
| C-16 | 발행 측(P11)이 `pc-replay-*` allowlist 4종을 싣는다 | **거짓 — 그런 상수는 존재하지 않는다**. `grep -rn "pc-replay"` 결과는 **계획서 2줄뿐**이고 코드 0건이다. P11 이 만든 것은 `replay_headers` **JSON 을 그대로 헤더로 푸는 일반 메커니즘**(`OutboxPollingService:169-181`)이고, 어떤 키를 넣을지는 정하지 않았다 | **P14 를 확대** — 키 정본(상수 클래스)을 이 PR 이 신설한다. 발행 측은 진입점(2b-4)이 이 상수로 JSON 을 만든다 |
| C-17 | `DlqHeaders.parse()` 의 "V9 화이트리스트 구조" 를 유지해 allowlist 키만 읽는다 | 표현이 부정확했다 — `DlqHeaders` 에는 allowlist **자료구조가 없다**. 표준 `KafkaHeaders.DLT_*` 상수 6개를 **개별 호출로 읽는 코드**일 뿐이다(`parse():43-70`). "화이트리스트" 는 `DeadLetterRecorder` javadoc 의 **정책 서술**이다 | **표현 정정** — 구조를 유지하는 게 아니라 같은 방식(상수 개별 판독)으로 4종을 더 읽는다 |
| C-18 | root 에 상관 앵커 컬럼이 있다 | 정확. V8(order)/V6·V6/V4 가 `last_replay_attempt_id VARCHAR(36)` · `last_replay_target_group VARCHAR(120)` · `reopened_at DATETIME(6)` · `reopened_reason VARCHAR(500)` 을 이미 만들었고 `idx_dead_letter_records_attempt` 도 있다 | ~~**마이그레이션 0**~~ → **정정 (리뷰 1R #1)**: 기존 앵커만으로는 **payload 변조를 걸러내지 못한다**. `last_replay_payload_digest` 1컬럼을 additive 로 더한다 — order **V10** · product **V8** · payment **V8** · notification **V6**. 근거는 P14(e) |
| C-19 | (초안 무언급) 그 컬럼들이 **엔티티에 매핑돼 있는가** | **아니다**. `DeadLetterRecord` 는 `rootRecordId`·`publicationStatus`·`outboxEventId`·`resolvedAt/By` 만 매핑했다. `lastReplayAttemptId`·`lastReplayTargetGroup`·`reopenedAt`·`reopenedReason` 은 **미매핑**(`replayDeadline`·`replayPolicy` 는 2b-4 소관이라 이 PR 도 매핑하지 않는다) | **P14 에 매핑 4종 추가** |
| C-20 | P15 가 `SELECT ... FOR UPDATE` 로 root 를 잠근다 | 잠금 API 는 이미 있다 — `findByIdForUpdate`(PESSIMISTIC_WRITE). **다만 attempt-id 로 root 를 찾는 조회가 없다** | **P15 에 조회 1종 추가**. 2b-1 이 `findRootIdOf` 에 남긴 계약(**엔티티가 아니라 id 만** 돌려줘야 `FOR UPDATE` 가 stale 인스턴스를 보지 않는다)을 그대로 따른다 |
| C-21 | 자식을 root 에 잇는 수단이 있다 | 정확. `DeadLetterRecord.linkToRoot(Long)` 이 2b-1 산출물로 **이미 존재하나 운영 코드에서 호출되는 곳이 0** 이다(현재 호출자는 `DeadLetterIncidentAggregationIntegrationTest:359` 하나) | 유지 — 이 PR 이 첫 운영 호출자다 |
| C-22 | 재개방 전이가 있다 | **없다**. `DeadLetterRecord` 에 `reopen(...)` 이 없고 `DeadLetterTransitionService` 는 `acknowledge`/`resolve`/`discard` 3종뿐이다 | **P15 에 `reopen()` 신설** |
| C-23 | purge 가 재개방과 안전하게 겹친다 | 정확 — `findPurgeableRootIds` 가 이미 `COALESCE` 를 피하고 **현재 상태에 해당하는 시각만** 본다(`status='DISCARDED' AND discarded_at<t` OR `status='RESOLVED' AND resolved_at<t`). 재개방이 `resolved_at`/`discarded_at` 을 지우지 않아도 `status='OPEN'` 이면 purge 대상에서 빠진다 | 유지 — **V-21c 검증을 이 PR 에서 수행**(2b-1 이 이연) |
| C-24 | `DeadLetterRecorder` 가 ledger owner(현재 서비스)를 안다 | **모른다**. 이 파일은 4서비스 **byte 동일 복제**라 `PeekcartService` 를 하드코딩할 수 없다. 서비스 정체성을 가진 것은 per-service 인 `DeadLetterConsumer`/`DeadLetterQuarantineConsumer` 의 `private static final PeekcartService SELF` 다 | ~~`record(DlqOrigin, PeekcartService self)` 로 시그니처 확대 — 호출부 2곳~~ → **정정 (리뷰 1R #13)**: 호출부는 **2곳이 아니라 실측 44곳**(main+test)이고, **notification 에는 quarantine consumer 가 없어** 서비스마다 수가 다르다. 인자로 받으면 호출자가 소유자를 고를 수 있어 신뢰 경계도 약해진다 → **`LedgerOwner` 빈 주입**, `record(DlqOrigin)` 시그니처 **불변**. 상세는 P15(f) |
| C-25 | fingerprint 4값이 재발행분에서 원본과 같게 유지된다 | 참 — P11 이 `destinationTopic`·`sourceRecordTimestamp`·`recordKey`·원본 `payload` 를 그대로 싣는다(`buildReplayRecord:155-167`). `event_id` 는 payload 에서 뽑으므로 동일. `message.timestamp.type` 은 `CreateTime`(Apache 기본, ④-c-2b-1 P4 실측) 이라 producer timestamp 가 보존된다 | 유지 |
| C-26 | P17 대상인 `DlqIntegrationTest` 가 `original_timestamp` 를 단언하지 않는다 | 정확. 해당 파일에 `originalTimestamp`/`TIMESTAMP` 문자열 **0건**. 다만 이 테스트는 **payment-service 에만** 있다(`DlqIntegrationTest` 는 1벌) | 유지 — P17 은 payment 판본 1곳 |
| C-27 | (초안 무언급) 신규 복제 파일이 parity 목록에서 누락될 위험 | ~~자동으로 막힌다~~ → **과장이었다 (리뷰 1R #14)**. `DLQ-PARITY-014`(`:183-201`)는 **네 벌의 해시가 이미 전부 같을 때만** 누락을 신고한다. 복제 의도인 파일이 **생성 시점부터 한 벌이라도 다르면** 서비스별 파일로 오인돼 그대로 통과한다 | **신규 상수 클래스는 `common` 에 둔다**(1벌) — 4벌 복제를 늘리지 않는다. 이번에 고치는 `DeadLetterRecord`/`DeadLetterRecordJpaRepository`/`DeadLetterRecorder` 가 `java_files` 목록에 **이미 있음**을 확인했고, **완료 조건에 parity lint 본실행 + `--self-test` 를 명시**한다 |
| C-28 | (초안 무언급) 재발행분이 **대상 group 이 아닌 다른 group** 에서도 재실패할 수 있다 | 그렇다. replay 는 업무 토픽에 실리므로 그 토픽을 구독하는 **모든** group 이 다시 소비한다. `stock.reservation.result` 처럼 다수 구독 토픽이 실재한다 | **의도된 동작으로 명시** — P16 의 음성 케이스 "유효 attempt + 다른 group" 이 정확히 이 경우이고, **독립 root** 가 맞다. replay 는 한 group 을 표적한 행위이고 다른 group 의 실패는 다른 사건이다. 발행 자체를 막는 것(fence)은 2b-4 소관 |

#### 착수 전 코드 검증 (2026-09-07) — 3b 재검증 (#103 머지 **후** 코드)

> C-15~C-28 은 **#103 머지 전** 실측이다. 3b 는 그 위에서 시작하므로 P15~P17 이 딛는 전제를
> 현재 `main`(`6032047`) 코드로 다시 확인했다. **뒤집힌 전제 1건 · 신규 1건.**

| # | 전제 | 재검증 결과 | 계획 변경 |
|---|---|---|---|
| C-29 | C-20(attempt-id → root id 조회 부재) 유지 | **유지**. ~~조회는 3종뿐~~ → **과장 (3R #8)**: 이 repository 에는 좌표 조회(`:75`) · backlog/age(`:89`) · purge · publication 조회가 더 있다. 정확히는 **root 전이·상관에 관계된 조회**가 `findRootIdOf(id)`(`:136`) · `findChildrenForUpdate(rootId)`(`:154`) · `findByIdForUpdate(id)`(`:164`) 이고, **attempt-id 를 인자로 받는 조회는 0건**이다(핵심 결론 불변) | P15(1) 조회 1종 신설 그대로 |
| C-30 | C-22(`reopen()` 부재) 유지 | **유지**. `DeadLetterTransitionService` 는 여전히 `acknowledge`/`resolve`/`discard` 3종(`:38·43·48`), 엔티티 전이는 `assignSelfRoot`/`linkToRoot`/`resolve`/`settlePublication`/`acknowledge`/`discard`. **다만 `reopened_at`/`reopened_reason` 컬럼은 2b-1 이 이미 만들어 뒀다**(`DeadLetterRecord:203·206`) — 마이그레이션 불필요, 매핑도 이미 있다 | P15(d) 는 **DDL 없이** 전이 메서드만 신설 |
| C-31 | C-24(`LedgerOwner` 부재 · 호출부 44곳) 유지 | **유지**. `LedgerOwner` 타입 **0건**(동명 식별자는 `DlqOrigin.replayLedgerOwner` 필드뿐). `record(` 호출부 **44곳** 재실측 일치. `PeekcartService SELF` 상수는 **7곳** — order/product/payment 는 `DeadLetterConsumer`+`DeadLetterQuarantineConsumer` 2벌, **notification 은 `DeadLetterConsumer` 1벌**(quarantine 없음)이 확인됨 | P15(f) 빈 주입 그대로. `record(DlqOrigin)` 시그니처 불변 |
| C-32 | P15(g) 의 "`notifyBestEffort` 가 트랜잭션 안에 있다" 유지 | **유지하되 줄번호가 밀렸다**. 3a 가 javadoc 을 늘려 `@Transactional record()` 는 `:56`, `notifyBestEffort` 호출은 `:100`, 정의는 `:108` 이다(계획서 본문의 `:52·93` 은 3a 이전 좌표) | P15(g) 내용 불변 — 참조 좌표만 갱신 |
| C-33 | P15(h) 가 Counter 를 더할 `DeadLetterMetrics` 가 있다 | 참. 4서비스에 존재하나 **Gauge 2종 전용**이다 — `dlq.backlog` · `dlq.oldest.age`(`:30·34`). **Counter 는 0건**이고 `MeterRegistry` 를 생성자에서만 쓰고 **필드로 보관하지 않는다** | P15(h) 는 Counter 신설과 함께 **레지스트리 필드 보관**이 선행. 4벌 복제 파일이므로 `java_files` parity 목록 등재 확인 필요 |
| C-34 | `CommitAwareMetrics` 가 재사용 가능하다 | 참. `peekcart-common-observability` 에 1벌(`global/metrics/CommitAwareMetrics.java`), 현재 사용처는 `OrderSagaMetrics`·`ProductSagaMetrics` 2곳 | P15(h) 그대로 — **`DeadLetterMetrics` 는 4서비스 전부**라 order/product 선례를 payment/notification 에도 편다 |
| C-35 | (신규) P17 대상 파일의 **경로**가 계획서 진술과 같다 | **다르다**. `DlqIntegrationTest` 는 `payment-service/src/test/java/com/peekcart/global/**kafka**/` 에 있다(계획서 문맥은 `deadletter` 패키지를 암시). `originalTimestamp` 단언 **0건**은 C-26 그대로 | P17 대상 경로를 `global/kafka/DlqIntegrationTest.java` 로 고정 |
| C-36 | (신규) `replay_deadline`/`replay_policy` 를 3b 가 쓰는가 | **엔티티에 매핑이 없다** — 컬럼은 V8(`:30·33`)에 있으나 `DeadLetterRecord` 에 필드 0건. §D6-4 표는 이 둘을 canonical root 소유로 적었다 | **3b 범위 밖으로 명시** — 소유권을 **셋으로 나눠 적는다 (3R #8 정정)**: **적격성 계산/조회 = P19** · **영속 writer = P21 의 root UPDATE**(초안은 이 둘을 P19 로 뭉쳐 적었다) · **자식 상속 = P21 이 확장하는 P15 INSERT 경로**. 3b 의 9축 대조는 이 둘을 보지 않으므로 매핑 부재가 3b 를 막지 않는다 |

**미충족으로 착수한다 (사용자 지시, 2026-09-07)**: 위 §"착수 조건" 의 **3a diff 포함 재리뷰를 수행하지 않았다**.
#102·#103 의 P0/P1 은 **0 이 아니라 미측정** 상태로 남는다. 3b 자체의 diff 리뷰는 정상 수행한다.

**이 PR 은 4서비스 additive 마이그레이션 1종을 동반한다** (C-18 정정) — `last_replay_payload_digest`.
`EXPECTED_MIGRATIONS` → `order 1~10 · product 1~8 · payment 1~8 · notification 1~6`.


#### ④-c-2b-3a — P14

**P14.** replay 상관 표면 — **키 정본 · 송신 강제 · 판독 · 매핑** 4층을 이 PR 이 함께 연다.

**(a) 키 정본 (C-16 — 초안이 "P11 이 이미 싣는다" 고 적었으나 코드에 0건이었다).**
`common` 에 `ReplayHeaders` 상수 클래스를 신설한다 — `pc-replay-attempt-id`(UUID) ·
`pc-replay-ledger-owner`(`PeekcartService.prefix()`) · `pc-replay-target-group` · `pc-replay-root-id`.
**`common` 에 두는 이유**: `global/deadletter` 에 두면 4벌 복제 자산이 하나 더 늘고, 발행 측(2b-4 진입점)과
판독 측이 **같은 문자열을 두 곳에 적게** 된다. 갈라지면 모든 상관이 조용히 실패한다.
이 클래스가 **허용 키 집합(`Set<String> ALLOWED`)을 함께 노출**한다 — (b) 가 그것으로 강제한다.

**(b) 송신 강제 (리뷰 1R #4 — 신설).** `OutboxPollingService.replayHeaders()` 는 현재 `replay_headers` JSON 의
**모든 키를 그대로 발행**한다(`:169-181`). C-16 은 그 사실을 적어놓고도 P14 를 판독 측에만 걸어,
**임의 application 헤더·표준 `DLT_*` 주입 표면이 그대로 남아 있었다** — ADR §D5-4 의 "allowlist 에 replay 상관
헤더를 명시" 계약 위반이다. `buildReplayRecord` 가 헤더를 싣기 전에 검증한다:
- **키 집합이 `ReplayHeaders.ALLOWED` 와 정확히 같아야 한다.** 부분집합 허용은 **불충분하다 (리뷰 2R #3)**:
  `replayHeaders()` 는 JSON 이 null/blank 면 **빈 Map** 을 돌려주고 `addHeaderIfPresent` 는 값이 null/blank 면
  **조용히 헤더를 생략**한다(`:169-185`). 부분집합만 요구하면 **헤더 0~3개짜리 REPLAY 가 그대로 발행**되고,
  재실패 시 상관 축이 없어 독립 incident 로 갈라진다 — N11 을 발행 측에서 깨는 경로다.
- **네 값이 전부 유효해야 한다** — non-blank · `attempt-id` 는 UUID 파싱 가능 · `root-id` 는 양의 정수 ·
  `ledger-owner` 는 `PeekcartService.prefix()` 집합의 원소. 하나라도 어긋나면 `IllegalStateException`
  (삼키지 않고 실패시켜 재시도/`PUBLISH_FAILED` 로 드러낸다).
- `KafkaHeaders.DLT_*` 접두사 키는 **명시적으로 거부**한다. 실리면 재실패 시 원본 좌표가 덮여 상관 정본이 사라진다.
- 이 파일은 `global/outbox` **4벌 byte 동일 복제**이므로 notification 판본을 포함해 4벌을 함께 고친다.

**(c) 판독 (C-17 정정).** `DlqOrigin` 에 상관 필드 4개(`replayAttemptId`·`replayLedgerOwner`·
`replayTargetGroup`·`replayRootId`)를 추가하고 `DlqHeaders.parse()` 가 그 4키를 **UTF-8 문자열로** 읽는다.
초안은 "V9 의 화이트리스트 구조 유지" 라고 적었으나 `DlqHeaders` 에 allowlist **자료구조는 없다** —
표준 `KafkaHeaders.DLT_*` 상수를 개별 호출로 읽는 코드이고, 4종도 같은 방식으로 더한다.
**판독 실패는 예외가 아니다** — 없으면 null 이고, null 이면 상관하지 않는다(기존 §2.6-C 계약 유지).
`pc-replay-root-id` 는 문자열로 읽어 파싱 실패 시 null 로 떨어뜨린다(숫자가 아닌 조작 값에 예외를 던지면
**DLQ 적재 자체가 막혀 유실**된다 — 조작 가능한 입력이 적재를 실패시켜서는 안 된다).

**(d) 매핑 + 마이그레이션 (C-19 · C-18 **정정**).** `DeadLetterRecord` 에 `lastReplayAttemptId`·
`lastReplayTargetGroup`·`reopenedAt`·`reopenedReason` 4컬럼을 매핑한다(V8 이 이미 만든 컬럼 — 마이그레이션 불필요).
**신규 `lastReplayPayloadDigest` 도 함께 매핑한다 (리뷰 2R #1)** — 1R 수정이 컬럼 DDL 만 적고 **엔티티 매핑과
writer 를 배정하지 않아**, 실제 replay 에서 root digest 가 영원히 `NULL` 로 남아 P15 의 대조 축 9가
**늘 통과하는(= 아무것도 검사하지 않는)** 상태가 될 뻔했다. writer 는 P21 이 배정한다.
**여기에 신규 컬럼 1종이 더 붙는다** — `last_replay_payload_digest VARCHAR(64)`(SHA-256 hex).
근거는 (e). 4서비스 additive 마이그레이션 **order V10 · product V8 · payment V8 · notification V6**
(**P23 backfill 을 V11/V9/V9/V7 로 밀어냈다 — 리뷰 2R #2**: 1R 이 배정한 번호가 P23 과 정확히 겹쳐,
두 파일이면 Flyway duplicate-version 으로 부팅이 깨지고 한 파일로 합치면 2b-3/2b-4 의 독립 배포 경계가 사라진다),
**nullable · DEFAULT 없음**(2b-1 이 replay 축 9컬럼에 세운 규칙 그대로).
`EXPECTED_MIGRATIONS` → `order 1~10 · product 1~8 · payment 1~8 · notification 1~6`.
`replayDeadline`·`replayPolicy` 는 매핑하지 않는다 — 읽는 주체가 2b-4 에 생긴다.

**(e) 왜 payload digest 가 필요한가 (리뷰 1R #1 — 초안 반증).**
초안의 fingerprint 4값(`event_id`·`original_key`·`original_timestamp`·`origin_topic`)에는
**payload 동일성을 묶는 값이 없다**. 같은 `eventId`·key·timestamp 를 실은 **변조 payload** 가 그 토픽에서
실패하면 대조를 전부 통과해 **남의 사건에 자식으로 붙고 종결된 root 를 재개방**한다.
ADR §D5-4 가 "헤더 값 자체를 신뢰하지 않는다 … 조작된 메시지가 임의 root 연결·재개방을 유발" 이라고
명시적으로 경계한 바로 그 경로다. §D8-3 의 fence 도 `key`·`payload`·`eventId` **byte-for-byte 동일**을 요구한다.
- **원장의 `payload` 컬럼으로 대조하지 않는 이유**: 그 값은 `maxLength=8000` 으로 **잘려 저장**된다
  (`DlqPayloads.truncate` · `DeadLetterProperties.Payload:118`). 절단분 비교는 상한 밖 변조를 통과시키므로
  "byte-for-byte" 를 주장할 수 없다.
- 따라서 **replay 요청 시점에 재발행 대상 payload 전문의 SHA-256 을 root 에 기록**하고(2b-4 진입점),
  재실패 적재는 `sha256(origin.payload())` 와 대조한다. 절단과 무관하게 정확하다.
- **digest 는 비밀이 아니다** — 이것은 인증이 아니라 **오상관 방지**다. 업무 토픽에 쓸 수 있는 주체는
  이미 더 나쁜 일을 할 수 있고, ADR 이 그 경우의 격상 경로(서명/MAC)를 §D5-4 말미에 이미 남겨 두었다.
- **`origin.payload()` 가 null 인 경우(tombstone)**: digest 도 null 로 두고 **양쪽 null 이면 일치**로 본다
  (`original_key` 와 같은 null-safe 규칙).

**(f) 신규 ADR 로 §D5-4 의 대조 범위를 supersede 한다 (리뷰 1R #5 발견 · 2R #4 방식 정정).**
ADR-0020 §D5-4 의 대조 목록은 `record_kind=REPLAY` 를 요구하는데, `record_kind` 는 **`outbox_events` 에만
있고 `PUBLISHED` cleanup 으로 먼저 사라진다** — 같은 §D5-4 가 "outbox 를 정본으로 삼으면 수명 경쟁에서
진다" 고 적은 **그 이유로 대조 축으로 쓸 수 없다**. ADR 내부가 스스로 충돌한다.
대체안은 **원장 앵커(`last_replay_attempt_id` + `last_replay_target_group` + `last_replay_payload_digest`) +
fingerprint 대조**다.

> **~~Update Log + `fix(adr):`~~ 는 규약 위반이다 (2R #4).** `docs/adr/README.md:11-14` 는 Update Log 를
> **사실 오류(파일명·Phase 귀속·수치)에만** 허용하고, `:14` 가 **"의사결정의 트레이드오프 변경, 대안 추가,
> Consequences 재해석은 본문 정정이 아니라 새 ADR 작성 사유 (Update Log 로 우회 금지)"** 라고 못박는다.
> 신규 컬럼과 대조 알고리즘 도입은 명백히 후자다. 1R 수정이 2b-2 P9-c 선례(그건 표의 **사실** 정정이었다)를
> 잘못 유추했다.

따라서 **ADR-0021 (`dlq-replay-correlation-anchor`)** 을 신설한다:
- ADR-0021 이 ADR-0020 §D5-4 의 **"판독 조건 = 한 트랜잭션 안의 원자 대조"** 항목과 그 **음성 테스트 목록**만
  무효화한다. §D5-4 의 나머지(원장이 정본 · 헤더 불신 · 수명 경쟁)는 **유지**된다 — ADR-0021 은 그 원칙의 귀결이다.
- ADR-0020 Status → **`Partially Superseded by ADR-0021`**, Status 줄 바로 아래에 **무효화 범위** 명시.
- `docs/adr/README.md` 인덱스에 ADR-0021 행 추가 + ADR-0020 Status 갱신.
- 커밋은 `docs(adr):` (신규 ADR) 과 Status 변경 — `fix(adr):` 를 쓰지 않는다.

**이 PR 에서 하는 이유**: 코드가 정본과 갈라진 채로 머지되면 2b-4 까지 두 PR 동안 ADR 이 거짓을 말한다.

> **대조의 정본은 원장이다 (outbox 가 아니다)** — 위 (f) 가 그 원칙의 귀결이다.
> **fingerprint 가 실제로 보존됨은 C-25 로 확인했다** — P11 의 `buildReplayRecord` 가 topic·timestamp·key·payload 를
> 그대로 싣고, `message.timestamp.type=CreateTime` 이라 producer timestamp 가 broker 에 덮이지 않는다.
> **커스텀 헤더가 재실패 시 DLT 까지 살아남는 것은 C-15 로 확인했다**(`DeadLetterPublishingRecoverer` 가
> 원본 헤더 전량을 복사한 뒤 `DLT_*` 를 얹는다 — 바이트코드 실측).

**(f-2) parity lint 확장 (리뷰 2R #6 — 신설).**
1R 은 "완료 조건에 lint 본실행을 명시" 하는 것으로 digest parity 가 강제된다고 적었으나 **거짓이다**.
현 lint 의 replay 축 검사는 `V*__dead_letter_replay_axis.sql` **glob 이 서비스당 정확히 1개**인지 보고
(`:125-143`), 검사 컬럼 목록도 **하드코딩**돼 있다(`:155-159`). 신규 digest 를 **별도 V10/V8/V8/V6 파일**에
넣으면 그 파일은 glob 에 걸리지 않아 **본실행도 `--self-test` 도 전부 green** 이다 — ④-c-2b-1 의 glob 미확장,
④-c-2b-2 의 목록 누락에 이어 **같은 구멍의 세 번째 재발**이다.
→ 검사를 **파일 glob 기준에서 최종 스키마 기준으로 바꾼다**: 서비스별 `db/migration/V*.sql` 을 **번호 순으로
합성**해 `dead_letter_records` 의 최종 컬럼 집합(이름 → 타입 · nullability · DEFAULT 유무)을 만들고 4서비스를 대조한다
(2b-2 P9-b 가 `outbox_events` 에 이미 쓴 방식 — 생성 경로가 서비스마다 다를 때 원문 해시가 성립하지 않는다는
같은 이유다). `last_replay_payload_digest` 의 **존재 · `VARCHAR(64)` · nullable · DEFAULT 없음**까지 검사한다.
**self-test 변이 4종**: 한 서비스 누락 · 타입 변경 · `NOT NULL` · `DEFAULT` 추가 → 각각 red.

**(g) javadoc 정정 (리뷰 1R #17).** P14 는 `DlqOrigin` 에 **application 헤더**를 처음 들여온다.
그래서 지금의 서술이 거짓이 된다 — `DeadLetterRecorder:25-29` 의 "`DlqOrigin` 이 표준 `DLT_*` 에서 뽑은 값만
담으므로 application 헤더는 애초에 원장에 들어오지 않는다" · `DlqOrigin` 의 `@param` 목록(현재 10필드) ·
`DlqHeaders` 클래스 주석. 셋을 **"표준 `DLT_*` 와 replay allowlist 4종만 판독하고 그 밖의 application 헤더는
제외한다"** 로 고친다.

**3a 완료 조건**: 4서비스 digest 마이그레이션 적용 · `DlqHeadersTest` 확장(정상/부재/malformed/중복 last-value/무관
헤더 무시) · 송신 거부 테스트(키 집합 불일치 · `DLT_*` · 4값 무효) · **parity lint 본실행 + `--self-test`
(신규 변이 4종 포함)** · `saga_e2e.py` `EXPECTED_MIGRATIONS` `10/8/8/6` · ADR-0021 신설 + ADR-0020 Status 변경 +
README 인덱스 갱신. **상관 로직은 이 PR 에 없다** — 판독한 4필드를 아직 아무도 쓰지 않는다.

---

#### ④-c-2b-3b — P15~P17

> **착수 조건**: 3a 머지 ✅(#103, `6032047`) + ~~3a diff 를 포함한 재리뷰 선행~~ → **사용자 지시로 스킵(2026-09-07)**. #102·#103 의 P0/P1 은 **미측정**으로 남는다 — 아래 재검증 표 말미 참조.

**P15.** `DeadLetterRecorder` 원자 대조. 순서를 **초안에서 바꿨다** — 아래 (a) 가 이유다.

**(a) 실행 순서 (리뷰 1R #2 — 초안 반증).**
초안은 "root 잠금 → 대조 → 자식 INSERT + 재개방" 이었다. 그러면 **재개방이 저장되지 않는다**:
`insertIfAbsent` 는 `@Modifying(clearAutomatically = true, flushAutomatically = true)` 라
(`DeadLetterRecordJpaRepository:28`) INSERT 직후 **영속성 컨텍스트를 비우고**, 앞서 잠가 둔 root 인스턴스가
**detach** 된다. 그 뒤의 `reopen()` 은 dirty checking 대상이 아니어서 UPDATE 가 나가지 않는다 —
자식 연결만 보는 테스트라면 **green 인 채로 재개방이 사라진다**.
채택한 순서(잠금 순서 규약은 그대로 지킨다 — root 를 **가장 먼저** 잠근다):

1. `origin.replayAttemptId` 로 **root id 만** 조회한다(엔티티 아님 — C-20). **이 조회는 로케이터다** — 4R #3.
2. `findByIdForUpdate(rootId)` 로 root 를 잠근다. **DB 행 잠금은 트랜잭션 끝까지 유지되므로 이후의
   컨텍스트 clear 와 무관하다.**
3. 대조(아래 (b))를 수행한다.
4. 자식 `insertIfAbsent` → 컨텍스트가 비워진다.
5. **root 와 자식을 다시 읽는다** — root 는 **`findByIdForUpdate(rootId)`(current read)** 로 읽는다(5R #3).
   이미 잠금을 쥐고 있어 **대기하지 않는다**. **attempt 재확인은 하지 않는다**(5R #2 — 대조는 단계 3 한 곳).
6. `child.linkToRoot(root.id)` + root 가 terminal 이면 `root.reopen(...)`.

> **attempt 는 정확히 한 번만 대조한다 (4R #3 — 3R 이 만든 결함 정정).**
> 3R 은 attempt 변이를 1-①(조회 predicate)/1-②(잠금 후 재확인) 둘로 나눴는데, 위 순서대로면 **관측점이 셋**이 된다
> — 조회(1) · 잠긴 root 대조(3, 표의 축 1) · 재조회 후 재확인(5). 그러면 **어느 하나를 지워도 남은 둘이 잡아서**
> "predicate 하나를 지우면 정확히 그 행만 red" 가 attempt 축에서 성립하지 않는다.
> **대조는 단계 3 한 곳으로 고정한다**:
> - **단계 1 (로케이터)**: attempt-id → root id. 검증은 **repository 반환값과 "못 찾으면 후속 잠금을 호출하지 않음"**
>   으로 한다(대조 결과가 아니라 **탐색 실패**다). → `V-19b`
> - **단계 3 (유일한 대조)**: 잠근 root 의 `lastReplayAttemptId` 와 헤더를 **여기서만** 비교한다. → `V-19m`
> - **단계 5 는 재확인을 하지 않는다** — 단계 3 이 이미 **행 잠금을 쥔 뒤** 읽은 값이라 그 사이 바뀔 수 없다.
>   재조회는 (a) 의 detach 복구가 목적이지 TOCTOU 방어가 아니다.
>
> **그래서 (c) 의 TOCTOU 는 "잠금 전 조회에만 의존하지 않는다" 로 충족된다** — 잠금 후 대조(단계 3)가 그 창을 닫는다.

> **단계 5 의 재조회는 반드시 current read 여야 한다 (5R #3 — 신규).**
> 단계 4 의 `clearAutomatically` 로 컨텍스트가 비워진 뒤 단계 5 가 **일반 `findById`** 를 쓰면,
> MySQL REPEATABLE READ 에서 **단계 1 이 연 스냅샷**을 다시 읽을 수 있다 — 그 사이 다른 트랜잭션이 root 를
> terminal 로 닫았어도 **과거의 `OPEN` 을 보고 재개방을 건너뛴다**. 이 repository 는 같은 함정을 이미 경고한다
> (`DeadLetterRecordJpaRepository:141-145` — 2b-1 이 `findRootIdOf` 를 id projection 으로 만든 이유).
> → 단계 5 의 root 재조회는 **`findByIdForUpdate(rootId)`** 로 한다(행 잠금은 이미 우리 것이라 대기 없음).
> **검증(V-15c 신설)**: 로케이터 반환 **직후** 다른 트랜잭션이 root 를 `RESOLVED` 로 닫고 commit → recorder 재개 →
> 최종적으로 `status='OPEN'`·`reopened_at` 이 **별도 트랜잭션 재조회에서** 관측된다.
> 단계 5 를 일반 `findById` 로 바꾸면 red. **기존 V-15 는 순차 유입만 봐서 이 race 를 검출하지 못한다.**

**`clearAutomatically` 를 떼는 선택지는 채택하지 않는다** — 그 플래그는 ④-c-1a 가 native INSERT 와 JPA 캐시의
불일치를 막으려고 붙인 것이고, 이 PR 이 그 판단을 뒤집을 근거가 없다. **재조회가 국소적이고 비용이 없다.**

**(b) 대조 항목.** 잠근 root 에 대해 **전부** 일치해야 한다. 하나라도 어긋나면 상관하지 않고 **독립 root 행**으로 적재한다.

| # | 축 | 자식(입력) | root(원장) | 비고 |
|---|---|---|---|---|
| 1 | attempt | 헤더 `pc-replay-attempt-id` | `last_replay_attempt_id` | **잠금 후 유일 대조(단계 3)** — (c) (5R #2) |
| 2 | owner | 현재 서비스 | 헤더 `pc-replay-ledger-owner` | 원장 소유 서비스에서만 상관 |
| 3 | group | `origin.failedConsumerGroup` (실제 DLT group) **=== 헤더 `pc-replay-target-group`** | `last_replay_target_group` | **3자 대조 (리뷰 2R #7)** — 헤더를 빼고 둘만 비교하면 `pc-replay-target-group` 판독이 **죽은 데이터**가 되어(지워도 결과 동일) 4헤더 계약 중 하나가 아무것도 지키지 않는다. 세 값이 전부 같아야 한다 |
| 4 | root-id | 헤더 `pc-replay-root-id` | `root.id` | |
| 5 | topic | `origin.originTopic` | `root.originTopic` | fingerprint 겸 destination 대조 (리뷰 1R #7 — 초안은 이 둘을 별개 축으로 셌으나 **같은 비교**다) |
| 6 | eventId | `origin` payload 의 `eventId` | `root.eventId` | null-safe |
| 7 | key | `origin.originalKey` | `root.originalKey` | **null-safe** (둘 다 NULL 이면 일치) |
| 8 | timestamp | `origin.originalTimestamp` | `root.originalTimestamp` | |
| 9 | **payload digest** | `sha256(origin.payload())` | `last_replay_payload_digest` | P14(e). **null-safe** — ADR-0021 §D2 가 "양쪽 null 이면 일치" 로 결정했다(5R #1). 그 상태의 **도달 가능성은 별개 문제**이고 아래 주석에 남긴다 |

**(c) TOCTOU (리뷰 1R #3 · 4R #3 정정).** 초안은 잠금 **전** 조회에서만 attempt-id 를 봤다. 그 사이 2b-4 가 같은 root 에
**새 attempt 를 기록**하면 **오래된 attempt 의 재실패가 최신 attempt 인 것처럼** 상관된다.
**잠금 후 대조(단계 3)가 그 창을 닫는다** — ~~단계 5 의 재확인~~ 이 아니다(3R 이 관측점을 셋으로 늘린 것을 철회).
`V-19m` 은 **단계 3 의 attempt 비교를 제거했을 때만** red 다. 조회 자체도 **canonical root 로 한정**한다(자식 행에 앵커가 실릴 일은 없지만, 조건을 좁혀 둔다).

**(d) 재개방.** `status='OPEN'` + `reopened_at`/`reopened_reason` 기록. **기존 `resolved_at`/`discarded_at` 은
지우지 않는다** — 감사 이력이고, purge 는 P4 의 `CASE` 로 현재 상태에 해당하는 시각만 본다(C-23 실측).

**(e) 중복 유입(`inserted == 0`)에서는 상관·재개방을 하지 않는다.** 이미 있는 행은 첫 적재 때 상관 판정이 끝났고,
재전달마다 재개방하면 운영자가 닫은 root 를 **broker 재전달만으로 다시 여는 경로**가 생긴다.
기존 계약대로 `attempt_count` 만 올린다.

**(f) ledger owner 는 주입한다 (C-24 **정정** · 리뷰 1R #13).**
초안은 `record(DlqOrigin, PeekcartService)` 로 시그니처를 넓히고 "호출부 2곳" 이라고 적었다. **둘 다 틀렸다** —
실측 `record(` 호출부는 main/test 합계 **44곳**이고, **notification 에는 quarantine consumer 가 없어**
서비스마다 호출부 수가 다르다(`DlqTopology` 의 quarantine 매핑은 3서비스뿐). 게다가 owner 를 인자로 받으면
**호출자가 매번 소유자를 고를 수 있어** 내부 API 의 신뢰 경계가 약해진다.
→ `common` 에 불변 `LedgerOwner(PeekcartService service)` 를 두고 **서비스별 `@Configuration` 이 빈으로 제공**하며
`DeadLetterRecorder` 는 생성자로 주입받는다. `record(DlqOrigin)` 시그니처가 **불변**이라 44개 호출부가 그대로다.
그 config 클래스는 서비스마다 값이 달라 `DLQ-PARITY-014` 에 걸리지 않는다(그 검사는 4벌이 **byte 동일할 때만** 신고한다).

> **그래서 배선을 따로 검증한다 (3R #4 — 초안 누락).** parity lint 를 벗어난다는 말은 **아무도 값을 안 본다**는
> 뜻이기도 하다. 네 빈이 각각 `ORDER`/`PRODUCT`/`PAYMENT`/`NOTIFICATION` 인지 확인하는 항목이 없었고,
> 한 서비스만 잘못 배선되면 **그 서비스의 정상 replay 가 전부 `owner_mismatch` 로 독립 incident** 가 된다 —
> 상관이 조용히 0이 되는 것이라 backlog=1 계약이 그 서비스에서만 깨진다.
> **4서비스 각각의 context 테스트에서 주입된 `LedgerOwner.service()` 를 기대 `PeekcartService` 와 대조**한다.
> 정본은 이미 서비스별로 존재하는 `DeadLetterConsumer.SELF`(`:30~31`)이므로 그 값과 맞춘다.
> **V-19a 를 한 모듈에서만 수행하더라도 이 4서비스 배선 검증은 별도 완료 조건**으로 둔다.
**`DeadLetterProperties` 에 owner 키를 두지 않는다** — 코드에 이미 있는 사실을 설정에 복제하면
둘이 어긋날 때 **설정이 이겨서 남의 사건에 자식을 붙인다**.

**(g) 알림은 commit 후로 옮긴다 (리뷰 1R #6 — 초안 반증).**
초안은 "기존 계약(best-effort)을 그대로 따른다" 고 적었으나 **현재 코드는 그 계약을 지키지 않는다**:
`record()` 는 `@Transactional` 메서드 **안에서** `notifyBestEffort()` 를 호출한다(`DeadLetterRecorder:56·100` — 3a 이후 좌표, C-32).
즉 **commit 실패 전에 Slack 이 먼저 나갈 수 있다** — javadoc 이 서술하는 "commit 후" 와 다르다.
`TransactionSynchronizationManager` 의 `afterCommit` 으로 옮긴다(④-d-1 의 `CommitAwareMetrics` 선례).
**callback 예외는 격리한다** — ④-d-1 3R #2 에서 `afterCommit` 예외가 호출자에게 전파돼 **이미 커밋된 이벤트를
listener 가 재처리**한 전례가 있다.

**(g-2) 알림을 결과별로 못박는다 (3R #6 — 초안 누락).**
현재 알림은 **신규 INSERT 마다** "신규 미결 1건" 을 보낸다(`DeadLetterRecorder:97`). (g) 는 그 호출을 afterCommit 으로
옮기고 (h) 는 재개방 문구를 "추가" 한다고만 적어, **상관된 자식에도 backlog 가 늘지 않았는데 신규-incident 알림이
나가거나**, terminal root 재개방 시 **기존 알림과 재개방 알림이 함께** 나가는 구현이 그대로 가능하다.
V-15 는 terminal 재개방의 callback 1회만 보므로 **이미 `OPEN` 인 root 에 붙은 자식의 잘못된 신규 알림을 검출하지 못한다**.

| 결과 | 신규 미결 알림 | 재개방 알림 |
|---|---|---|
| 독립 root INSERT (상관 실패·최초 실패) | **1회** | 0 |
| terminal(`RESOLVED`/`DISCARDED`) root 에 상관 | **0회** | **1회** |
| 이미 `OPEN`/`ACKED` 인 root 에 상관 | **0회** | 0 |
| 중복 유입 (`inserted == 0`) | **0회** | 0 |

각 경우에 대해 **메시지 종류와 afterCommit 호출 수**를 단언한다.
`record()` 반환값 javadoc 의 "신규 적재면 true (**알림 발송함**)" 표현도 실제 의미에 맞게 고친다 —
상관에 성공한 자식은 신규 적재(`true`)지만 신규 미결 알림을 보내지 않는다.

**(h) 재개방 알림·관측 (리뷰 1R #16 — 신설).** ADR §D6-2b I-2 는 **"재개방은 운영 알림 대상"** 을 명시한다 —
사람이 닫은 것을 시스템이 되돌리는 유일한 경로다. 현재 알림 문구는 "신규 미결 1건" 하나뿐이라
재개방이 **신규 적재와 구분되지 않는다**. (g) 의 afterCommit 경로에 **재개방 전용 문구**(root-id · attempt-id ·
group · 직전 상태)를 추가하고, `DeadLetterMetrics` 에 **재개방 Counter** 와 **상관 결과 Counter** 를 더한다.
상관 실패에 관측 표면이 없으면 **N11 파손과 "다른 group 이라 독립 사건" (C-28)이 운영에서 구분되지 않는다.**

**두 Counter 도 `CommitAwareMetrics` 를 쓴다 (리뷰 2R #9).** 1R 은 Slack 만 afterCommit 으로 옮기고
Counter 의 트랜잭션 의미를 정의하지 않아, **트랜잭션 안에서 증가시켜 rollback 된 상관까지 세도 green** 이었다 —
④-d-1 3R #1 이 정확히 그 결함이었고 `CommitAwareMetrics` 가 그때 만들어졌다. callback 예외 격리도 같다(3R #2).
**`reason` 태그는 bounded 리터럴 집합으로 못박는다** — `no_attempt_header` · `attempt_not_found` ·
`owner_mismatch` · `group_mismatch` · `root_id_mismatch` · `topic_mismatch` · `fingerprint_mismatch` ·
`digest_mismatch` · `attempt_changed`. **헤더 값·group 명 등 입력에서 온 문자열을 태그로 쓰지 않는다**
(cardinality 폭발 — ADR-0015). 테스트는 commit/rollback/callback 예외 3종과 함께
**meter 검색으로 허용 집합 밖 태그가 생기지 않음**을 단언한다.

**Counter 계약을 구현 가능한 수준까지 닫는다 (3R #5 — 초안은 `reason` 리터럴만 나열).**
meter 명·tag key·성공 값·중복 유입·최초 DLT 의 계측 여부가 전부 미정이라, **서로 다른 의미의 Counter 를 만들어도
"허용 집합 밖 태그 없음" 테스트는 green** 이었다. `DeadLetterMetrics` 는 현재 Gauge 2종뿐이고 `MeterRegistry` 를
필드로 들고 있지도 않다(C-33)—레지스트리 보관이 선행이다.

| meter | tag | 값 | 증가 시점 |
|---|---|---|---|
| `dlq.correlation` | `result` | `correlated` · `independent` | **신규 INSERT 가 확정된**(`inserted == 1`) 상관 결과 1건당 1 — 4R #9 |
| | `reason` | `result=independent` 일 때만: 아래 bounded 집합 · `result=correlated` 면 **`none`** | |
| `dlq.reopened` | — (태그 없음) | | root 가 **terminal → OPEN** 으로 바뀐 경우에만 **1** |

- **`reason` bounded 집합**: `no_attempt_header` · `attempt_not_found` · `owner_mismatch` · `group_mismatch` ·
  `root_id_mismatch` · `topic_mismatch` · `fingerprint_mismatch` · `digest_mismatch` · `attempt_changed`.
  **문자열 인자가 아니라 enum/switch 로 제한한다** — 리터럴 나열만으로는 호출부가 임의 문자열을 넣는 것을 못 막는다.
- **최초 DLT(replay 주장 없음)도 계측한다** — `result=independent, reason=no_attempt_header`. 빼면 분모가 사라져
  "상관 실패율" 이 정의되지 않는다.
- **중복 유입(`inserted == 0`)은 두 Counter 모두 0** — 단, ~~"대조 자체를 하지 않으므로"~~ 는 **틀린 근거였다 (4R #9)**.
  P15 실행 순서는 대조(단계 3)를 INSERT(단계 4)보다 **먼저** 하므로, 중복인지 알기 전에 **대조는 이미 계산된다**.
  "대조 판정 1회당 1" 과 "duplicate 0" 을 문자 그대로는 동시에 구현할 수 없다.
  → 계약을 고친다: **중복에도 root 잠금·대조는 평가되지만, 영속 신규 사건이 아니므로 계측·알림·재개방을 생략한다.**
  Counter 증가 기준은 **`inserted == 1` 이 확정된 상관 결과**다.
  duplicate fixture 에서 **root 잠금·대조가 실제로 실행됐음**과 **delta·알림이 모두 0** 임을 함께 단언한다.
- **이미 `OPEN` 인 root 에 상관**: `dlq.correlation{result=correlated}` **1**, `dlq.reopened` **0**.
- 검증은 결과별 **delta 표**로 한다 — `correlated` / `independent` / `reopened` / `duplicate` 각각에 대해
  **commit 후 delta** 와 **rollback 후 delta 0** 을 단언한다.

**P16.** 대조 축 ↔ 음성 fixture **1:1 매트릭스** (리뷰 1R #7·#11 — 초안 재작성).
초안의 "8종" 은 독립 축이 아니었다. `다른 destination topic` 과 fingerprint 의 `origin_topic` 불일치는 **같은 비교**이고,
fingerprint 불일치가 "셋 중 하나" 로만 적혀 있어 **각 조건을 지워도 red 가 되지 않는다**.
아래는 P15(b) 표의 **9축을 각각 독립 변이**시킨다 — 대조 조건을 하나 제거하면 **정확히 그 행만** red 다.

**각 행은 정확히 한 입력만 바꾼다** — 그래야 "대조 조건 하나를 지우면 정확히 그 행만 red" 가 성립한다
(리뷰 2R #8: 1R 표는 `타 서비스 소유 attempt` 가 축 1+2 를 동시에 바꿔 이 성질이 깨져 있었고,
§6 의 V-19 는 여전히 "8종" 이라 완료 조건의 범위가 계획서와 어긋났다).

> **기대값의 형태를 먼저 못박는다 (3R #2).** 음성 케이스를 "독립 root" 로만 적으면 **신규 행의
> `root_record_id` 가 NULL 이어도 통과한다**. 집계 쿼리가 전환 호환성 때문에 `IS NULL` 을 root 로 함께 세기
> 때문이다(`DeadLetterRecordJpaRepository:89` — `(r.rootRecordId IS NULL OR r.rootRecordId = r.id)`).
> 그러면 상관 실패 분기에서 `assignSelfRoot()`(`DeadLetterRecorder:75`) 호출을 빠뜨려도 **행 존재·backlog
> 단언이 전부 green** 이고, ADR 의 "root 행은 자기 id 를 가진다" 계약만 조용히 깨진다.
> - **음성(독립 root) 공통 단언**: 신규 행이 존재 · `root_record_id IS NOT NULL` · **`root_record_id == 자기 id`** · backlog **2**
> - **양성(V-19a) 공통 단언**: `child.root_record_id == 원본 root.id` · **자기 root 가 아님**(`!= child.id`) · backlog **1**

**(1) 단일 equality 변이** — 대조 predicate 하나를 제거하면 **정확히 그 행만** red 다.

| V-ID | 케이스 | 변이 predicate | 기대 |
|---|---|---|---|
| **V-19a** | 정상 상관 (양성 대조군) | — (전 축 일치) | 자식이 root 에 연결, backlog **1** |
| **V-19b** | attempt-id 불일치 — **로케이터 탐색 실패** | 단계 1 `WHERE last_replay_attempt_id = :headerAttemptId` | 독립 root. **root 를 못 찾고 종료** — 후속 잠금이 호출되지 않음을 함께 단언 |
| **V-19c** | owner 불일치 | 2 | 독립 root |
| **V-19d** | group — **실제 DLT group 만** 변이 (header == root) | 3-① `origin.failedConsumerGroup == 헤더 target-group` | 독립 root — **오류가 아니라 정상 경로다 (C-28)** |
| **V-19d2** | group — **root 앵커만** 변이 (실제 == header) | 3-② `헤더 target-group == root.lastReplayTargetGroup` | 독립 root |
| **V-19e** | root-id 불일치 | 4 | 독립 root |
| **V-19f** | topic 불일치 | 5 | 독립 root |
| **V-19g** | eventId 불일치 — **root 의 `event_id` 컬럼만** 변이 | 6 | 독립 root. **자식 payload 를 건드리지 않는다 (5R #4)** — 자식 eventId 는 payload 에서 추출되므로(`DeadLetterRecorder:61`) payload 를 바꾸면 **digest(축 9)도 함께 어긋나** eventId predicate 를 지워도 digest 가 잡아 false-green 이 된다. 실행 전 **자식 digest == root digest** 를 별도 단언한다 |
| **V-19h** | key 불일치 (**null↔값 양방향**) | 7 | 독립 root |
| **V-19i** | timestamp 불일치 | 8 | 독립 root |
| **V-19j** | **payload digest 불일치** (eventId·key·ts 동일, payload 만 변조) | 9 | 독립 root — **ADR §D5-4 조작 경계** |

> **group 을 두 행으로 쪼갠 이유 (3R #1)**. 축 3 은 3자 대조라 **독립 equality 가 둘**이다
> (실제 group == 헤더, 헤더 == root 앵커). 1R/2R 표는 "group 불일치" 한 행뿐이어서 **어느 값을 바꾸느냐에 따라
> equality 하나를 지워도 green** 이었다 — 2R 이 3자 대조를 도입하면서 매트릭스를 함께 늘리지 않은 결함이다.
> **V-19d 는 3-①을, V-19d2 는 3-②를 각각 단독으로 검출**한다.

**(2) null-safe 양성 케이스 (3R #1 — 초안 누락).** 축 7·9 는 "둘 다 NULL 이면 일치" 가 계약인데
(`ADR-0021 §D2` · `original_key` 규칙) 음성(null↔값)만 있었다.
**null-safe 를 단순 `equals` 로 바꾸면 아래 두 행이 red** 다.

| V-ID | 케이스 | 기대 |
|---|---|---|
| **V-19n** | key 가 **자식·root 양쪽 NULL** (나머지 축 일치) | **상관된다**(양성) |
| **V-19o** | digest 가 **양쪽 NULL** (나머지 축 일치) | **상관된다**(양성) — `ADR-0021 §D2` 계약 고정. **fixture 전용**(위 주석: 운영 경로에서는 도달 불가) |

> **digest 는 ADR 대로 null-safe 로 구현하되, 그 상태가 도달 불가임을 기록한다 (4R #1 → 5R #1 정정).**
> 4R 을 반영하며 축 9 를 "필수 대조" 로 바꿨는데 **그게 월권이었다** — `ADR-0021 §D2` 는 Accepted 상태로
> "tombstone 은 digest 도 null 이고, **양쪽 null 이면 일치**" 를 이미 결정했다(`0021-...:71`).
> 도달 불가라는 **사실**만으로 계획서가 Accepted 결정의 **의미**를 바꿀 수는 없다.
> → **구현은 ADR 대로 null-safe** 로 하고, `V-19o` 는 **그 계약을 고정하는 테스트**로 유지한다(양성).
>
> **다만 이 경로는 현재 도달 불가능하다** — ① ADR-0020 §D5-2 가 `event_id IS NULL` 을 replay **금지축**으로 정하고
> (`0020-dlq-replay-contract.md:166-174`), tombstone 은 payload 에서 eventId 를 못 뽑아 **축 6 부터 어긋난다**
> ② 4서비스 `outbox_events.payload` 가 전부 **`TEXT NOT NULL`** 이라 tombstone 재발행 행 자체가 저장되지 않는다.
> 즉 `V-19o` 는 **fixture 로만 성립하는 계약 고정**이고 운영 경로 검증이 아니다. ADR-0021 §D2 와 ADR-0020 §D5-2 의
> 이 어긋남(=D2 가 D5-2 가 막아둔 상태를 규정한다)은 **§미해결에 남긴다** — 해소하려면 새 ADR 이 필요하고 3b 범위 밖이다.

**(3) 시나리오 케이스** (단일축 변이가 아니므로 별도 ID 로 분리한다):

| V-ID | 케이스 | 기대 |
|---|---|---|
| **V-19k** | 타 서비스 원장의 attempt-id 를 그대로 재사용 (축 1+2 동시) | 독립 root. 축 2 만 지워도 축 1 이 잡아야 하므로 **V-19b·V-19c 를 대체하지 않는다** |
| **V-19l** | root 에 앵커 자체가 없음 (= replay 가 아닌 최초 실패) | 독립 root. **회귀 방지용** — 기존 경로가 상관 코드 추가로 바뀌지 않았음을 고정 |
| **V-19m** | TOCTOU — 로케이터 조회 후 **잠금 전** root 에 새 attempt 기록 (축 1, **단계 3 대조**) | 독립 root. **단계 3 의 attempt 비교를 빼면 이 행만 red** |

> **attempt 축의 변이 지점 (3R #1 → 4R #3 정정)**. 3R 은 이를 "조회 predicate / 잠금 후 재확인" 둘로 나눴으나
> 실행 순서상 관측점이 **셋**이 되어 상호 은폐가 생겼다. P15 실행 순서를 **단계 3 단일 대조**로 확정했으므로,
> `V-19b` 는 **로케이터 탐색 실패**(대조가 아니다)를, `V-19m` 은 **단계 3 의 attempt 비교**를 각각 단독 관측한다.

**상태 전이 케이스** (초안 누락 — §6 의 V-15·V-15b·V-16 을 P16 에 배정한다):

| 케이스 | 기대 | 대응 |
|---|---|---|
| `RESOLVED` root 에 늦은 자식 | root 재개방(`OPEN`), `resolved_at` **유지**, `reopened_at` 기록 | V-15 |
| `DISCARDED` root 에 늦은 자식 | 위와 동일, `discarded_at` 유지 | V-15 |
| 재개방 commit 후 알림 callback 실패 | 원장 재개방 **유지**, 알림 **0회 허용**, 소비 트랜잭션 **재처리 없음** | V-15b · P15(g) |
| **같은 DLT 재전달**(중복) | `attempt_count` 만 +1, **닫힌 root 는 재개방되지 않는다** | P15(e) |
| 3회 재실패(자식 3, fixture) | 행 4(root+자식3), **backlog = 1**, root 종결 시 0 | V-16 (진입점을 도는 전체 루프는 2b-4) |
| **재개방 영속 확인** | commit **후 별도 트랜잭션에서 재조회**해 `status='OPEN'`·`reopened_at` 이 **DB 에 있음**을 단언 | P15(a) — 이 단언이 없으면 detach 결함이 green 이다 |

**헤더 판독 경로 고정 (리뷰 1R #8).** 위 매트릭스가 `DlqOrigin` 을 **fixture 로 직접 만들면**
`ReplayHeaders` 문자열·UTF-8 판독·파싱 실패 정책이 전부 깨져도 green 이다. `common` 의 `DlqHeadersTest` 에
**정상 판독 · 부재 · malformed UUID/root-id · 중복 헤더 last-value · 무관 application 헤더 무시** 를 추가하고,
`DlqIntegrationTest` 에서 **실제 DLT 를 거친 4헤더 판독**을 1회 관통 검증한다.

**송신 거부 고정 (리뷰 1R #4).** `replay_headers` 에 allowlist 밖 키 · `DLT_*` 키가 있으면
**발행이 거부**되는 것을 테스트한다(4서비스 parity 는 lint 가 강제).

**수명 경쟁 N11 (리뷰 1R #9 — 초안 보강).** 초안은 "cleanup 을 먼저 돌린 뒤" 상관만 단언해,
**ShedLock 때문에 잡이 아예 안 돌아도 통과**한다. 기존 `OutboxReplayPublicationIntegrationTest:223-230` 이
lock 을 만료시키고 삭제를 직접 확인하는 방식을 따른다:
① replay outbox 행 id 가 **DB 에서 사라졌음**을 별도 단언 → ② root 의 attempt/group/digest 앵커는 **남아 있음** 확인
→ ③ 지연 DLQ 적재 주입 → ④ 자식 수 · 자식 `root_record_id` · **독립 root 부재** · backlog **1** 을 각각 단언.

**V-21c 경합 (C-23 · 리뷰 1R #10 — 초안 보강).** 2b-1 이 "재개방 경로가 2b-3 에 생기므로 검증도 그 PR 소관" 으로
이연한 행이다. 초안대로 **재개방 후 purge 를 호출하면** root 는 애초에 후보에서 빠져 **race 를 시험하지 않는
vacuous 테스트**가 된다(실제 purge 는 `findPurgeableRootIds` 직후 root 를 잠근다 —
`DeadLetterMaintenanceScheduler:71-76·92-101`). repository spy/테스트 seam 으로 **후보 조회 반환 직후 latch 로 멈추고**,
다른 트랜잭션이 root 잠금·재개방·자식 삽입을 commit 한 뒤 purge 를 재개한다.
**purge 가 stale id 를 실제로 쥐고 있었다는 것**과 **최종적으로 root·자식이 살아남는 것**을 모두 단언한다.

**P17-b (문서 산출물, 5R #6 — 신규 배정).** `ADR-0021` **Update Log 1줄** — V-ID 매핑 정정.
`ADR-0021:101` 이 P21 진입점 관통 검증을 "계획서 V-30" 으로 부르는데, `V-30` 은 ④-c-2b-2 에서 **이미 머지된**
`OutboxReplayPublicationIntegrationTest:44·122` 의 `record_kind IS NULL` 호환성 테스트 이름이다.
계획서만 고치면 ADR 과 갈라지므로 **ADR 쪽 참조를 함께 정정**한다 — 담당이 배정돼 있지 않았다.
- **매핑**: 호환성 = **`V-30`**(불변, 코드가 이미 그렇게 부른다) · P21 관통 = **`V-35`** · `V-34` = **철회**(미사용)
- **새 ADR 이 아니라 Update Log 인 이유**: 트레이드오프·결정 변경이 아니라 **참조 번호 오기 정정**이다.
  `adr/README.md:14` 가 금지하는 것은 *결정 변경*의 Update Log 우회다.

**P17.** DLT 계약 고정 — `DlqIntegrationTest`(payment-service 1벌 · `global/kafka/`, C-26·C-35)에
**정상 DLT 유입에서 `original_timestamp` 가 원장까지 저장됨**을 단언으로 추가한다(현재 미단언, ADR C1).
**non-null 단언으로는 부족하다 (리뷰 1R #12)** — 재발행 시각이나 엉뚱한 헤더 값을 저장해도 green 이다.
기존 테스트는 `kafkaTemplate.send(topic, key, value)` 로 timestamp 를 지정하지 않으므로,
**고정 timestamp 를 실은 `ProducerRecord`** 로 발행하고 **DLT 헤더 값과 원장 `original_timestamp` 가 그 값과
정확히 같은지**를 단언한다.
**이것은 계약 고정이지 NULL 비율 측정이 아니다** — 측정은 P22 소관이다.
fingerprint 대조가 이 값에 의존하므로, 이 값이 조용히 NULL 이 되면 **모든 상관이 실패해 사건이 갈라진다**.

**배포·롤백 순서 (리뷰 1R #15 — 초안 누락).**
2b-3 은 **소비 측**만 바꾸고 진입점은 2b-4 에 있다. 그래서 순서가 계약이다:
- **활성화**: **4서비스 전부** 2b-3 배포 완료 → 그 다음 2b-4 진입점 활성화. 한 서비스라도 구버전이면
  그 서비스의 재실패가 `pc-replay-*` 를 무시하고 **독립 root** 로 적재돼 backlog=1 보장이 그 자리에서 깨진다.
- **롤백**: ① replay 진입점 **비활성화** → ② **drain 완료** → ③ 그 다음 2b-3 롤백.
  역순으로 하면 발행된 재실패분이 상관되지 않는 창이 생긴다.
  > **drain 의 판정식·조건 수·preflight 진입점은 2b-4 가 정의한다 (5R #5 — 3b 경계).**
  > `publication_status='REQUESTED' == 0` 하나로는 불충분하다는 것(2R #5: broker ack 시점에 `PUBLISHED` 로
  > 바뀌므로 **소비 재시도 중**이거나 **DLT 이동 중**인 레코드를 못 잡는다)까지가 **3b 가 아는 사실**이고,
  > 그 이상(무엇을 몇 개 재는가·어떻게 강제하는가)은 진입점·정본·배포 wrapper 를 쥔 **2b-4 소관**이다.
  > 4R #5~#8 이 이 확장을 3b 에서 열었을 때 답할 수 없는 표면 넷을 만들어냈다.
- 2b-3 단독 배포는 안전하다 — 앵커를 쓰는 주체가 없어 상관 경로가 아예 타지지 않는다.

### PR ④-c-2b-4 — 좌표 reader + 진입점 + fence + backfill + 문서

**P18.** `OriginalRecordReader` (신규, `common`). `AdminClient` 로 `cleanup.policy`·`retention.ms` describe +
`beginning/end offset` 조회 → 요청 좌표가 `[beginning, end)` 밖이면 **불가**. 이어서 전용 consumer 로
`assign → seek → poll` 하고 **반환 레코드의 offset == 요청 offset** 을 검증한다(compaction hole 방어).
consumer group 을 만들지 않도록 `assign` 만 쓰고 offset 을 커밋하지 않는다.

**P19.** `ReplayEligibility` — §D5-2 **6 금지축을 독립 조건으로** 평가하고 **전부** 사유를 모아 반환한다
(첫 번째에서 멈추면 운영자가 한 번에 한 축만 본다).

- **`replay_deadline` 계산 (정정 — 리뷰 1R #1)**:
  1. `original_timestamp > now + clockSkewBudget` 이면 **거부**(허용 한도를 넘는 미래값)
  2. 통과분은 **`now` 로 clamp**: `replay_deadline = min(original_timestamp, now) + dlqReplayWindow`
  > 초안은 `min(original_timestamp, now + clockSkewBudget) + window` 였고, 이는 **4분 미래인 timestamp 에
  > 안전창을 4분 연장**한다. ADR §D5-3 은 "허용된 미래값은 `now` 로 clamp 해 계산한다(창을 늘려주지 않는다)"로
  > 명시돼 있다 — 초안이 ADR 과 충돌했다.
  3. root 에서 1회 계산하고 자식·재시도는 **상속**한다. 재계산 경로를 만들지 않는다 (N4)
  4. **만료 강제 (2R #3)** — `now >= replay_deadline` 이면 **거부**한다. 초안은 식과 상속만 정의하고
     ADR §D5-3 의 필수 조건인 `now < replay_deadline` 강제를 작업·검증 어디에도 넣지 않았다 —
     **만료된 사건을 발행해도 전부 green** 이었다
- **`replay_policy` 는 default-deny 레지스트리다 (정정 — 리뷰 1R #9)**: 초안은 "정책이 비어 있으면 허용" 이었으나,
  그러면 ADR §D5-4 가 `replay_policy` 에 담기로 한 **늦은 이벤트 위험이 아무 데서도 관리되지 않는다**.
  코드 레지스트리에 **업무 토픽 10종의 정책을 명시**하고 **미등록 토픽·eventType 은 거부**한다.
  정책은 *허용 여부* + *상태 사전조건*을 담고, 사전조건이 있는 토픽은 도메인 상태 조회를 거쳐 갈린다.
  - **초기 정책표를 여기서 확정한다 (3R #5)** — 2R 은 "2b-4 착수 전 확정" 으로 미뤘는데, 그러면 계획이
    정책 축을 **여전히 비워 둔 채** 완료 판정으로 간다. 업무 토픽 10종(`DlqTopology` 의 발행 토픽 전수):

    | 토픽 | 초기 판정 | 상태 사전조건 |
    |---|---|---|
    | `order.created` | **deny** | 주문 생성은 하류 예약·결제를 연쇄 개시한다. 늦은 재적용이 이미 취소된 주문의 재고를 다시 잡는다 |
    | `order.cancelled` | **allow** | 취소는 멱등 종결이고 늦게 도착해도 상태를 되돌리지 않는다 |
    | `order.compensation.requested` | **allow** | 보상 요청은 원장(`order_compensations`)이 종결 축을 따로 갖는다 |
    | `product.updated` | **allow** | 가격 캐시 갱신. 늦은 값은 다음 갱신이 덮는다 |
    | `stock.reservation.result` | **allow + 사전조건** | **주문이 아직 그 예약 결과를 기다리는 상태인지**를 도메인 조회로 확인한다 (← 실제 adapter 를 갖는 토픽) |
    | `stock.compensation.requested` | **allow** | 보상 요청. 원장이 종결 축 소유 |
    | `payment.requested` | **deny** | 외부 PG 승인을 유발할 수 있다. 늦은 재적용은 이중 과금 경로다 |
    | `payment.completed` | **allow** | 완료 통지. 소비 측이 전부 멱등 |
    | `payment.failed` | **allow** | 실패 통지. 멱등 |
    | `payment.refunded` | **allow** | 환불 통지. `payment_refunds` 가 종결 축 소유 |

    **미등록 토픽·eventType 은 거부**한다(default-deny). 이 표는 **초기값**이며 운영 경험에 따른 정밀화는 §10 R4.
  - **판정 결과를 allow·deny **양쪽 다** 기록한다 (3R #5)** — deny 는 P19 에서 걸러져 claim(P21)에 도달하지
    않으므로, claim 에만 기록하면 **거부 이력이 남지 않는다**. 판정은 claim 과 **독립된 감사 write** 로 남기고
    (root 의 `replay_policy` = *정책 식별자 + 버전 + 판정*), allow 인 경우 자식이 상속한다

**P20.** D8 fence — 발행 직전 최종 검사. `destination_topic == origin_topic` · `destination_partition ==
검증된 origin_partition` · `key`·`payload`·`eventId` 가 **reader 가 읽은 원본과 byte 동일** · `record_kind=REPLAY` ·
**요청 서비스 == 원장 소유 서비스**(타 서비스 원장 행 요청은 거부). 하나라도 어긋나면 outbox 행을 만들지 않는다.

**P21.** `DeadLetterEndpoint` `action=replay` — `POST /actuator/deadletter/{id}` body `{"action":"replay","actor":"..."}`.

**발행 축의 대상 행(target)과 상관 앵커(root)를 분리한다 (3R #1)** — 이것이 없으면 **두 번째 replay 가 불가능**하다.
첫 replay 가 성공하면 P12 가 root 의 `publication_status` 를 `PUBLISHED` 로 올리는데, claim 은
`NULL`/`PUBLISH_FAILED` 만 허용하므로 재실패 후 재요청이 **거부**된다 — V-16 의 "3회 replay" 가 성립하지 않았다.

| 축 | 어느 행에 쓰나 |
|---|---|
| `publication_status` · `outbox_event_id` | **target row** = incident 의 **가장 최근 활성 행**(자식이 없으면 root, 있으면 최신 `OPEN` 자식) |
| `last_replay_attempt_id` · `last_replay_target_group` · **`last_replay_payload_digest`** · `replay_deadline` · `replay_policy` | **canonical root** (상관 앵커는 하나여야 P15 가 찾을 수 있다) |

**잠금 순서는 canonical root → target child 로 고정**한다(P5·P15·purge 와 같은 진입 순서라 순환이 없다).
**I-1 은 incident 단위로 본다** — root 또는 **활성 자식 중 하나라도** `REQUESTED` 면 종결을 거부한다.

**트랜잭션 순서를 못박는다 (리뷰 1R #3·#4)** — 한 트랜잭션 안에서:
1. root 를 `SELECT ... FOR UPDATE` 로 잠그고 이어서 target row 를 잠근 뒤 적격성(P19)·fence(P20) 검사
2. **조건부 claim UPDATE (target row)** — `SET publication_status='REQUESTED' WHERE id=:target
   AND (publication_status IS NULL OR publication_status='PUBLISH_FAILED')
   **AND status IN ('OPEN','ACKED')`**
   → 영향 행 0 이면 **거부하고 즉시 반환**(아직 outbox 를 만들지 않았으므로 orphan 이 없다)
2b. **root 앵커 UPDATE** — `SET last_replay_attempt_id=:attempt, last_replay_target_group=:group,
   **last_replay_payload_digest=:digest**, replay_policy=:policy,
   replay_deadline=COALESCE(replay_deadline, :calculated) WHERE id=:root`
   > **digest 는 P18 이 원본 토픽에서 실제로 읽어온 payload 전문의 SHA-256 이다 (2b-3 리뷰 2R #1)** —
   > 원장의 절단된 `payload` 컬럼이 아니다. 이 UPDATE 가 **digest 의 유일한 writer** 이고, 여기서 빠지면
   > root digest 가 영원히 `NULL` 이라 P15 의 대조 축 9가 **늘 통과해 아무것도 검사하지 않는다**.
   > **2b-3 은 이 값을 fixture 로만 심는다** — 진입점을 거친 실제 기록·상관은 아래 **V-35** 가 관통 검증한다.
   > **`COALESCE` 가 계약이다 (3R #4)**: 초안은 "root 에서 1회 계산하고 상속" 이라고만 적고 **그 값을 어디에
   > 영속하는지 정의하지 않았다** — 구현자가 매 요청마다 transient 계산해도 V-11·V-13b 가 green 이었다.
   > 첫 claim 이 값을 박고 이후 요청은 **덮어쓰지 않는다**. 자식 INSERT(P15)는 root 의 값을 **복사**한다.
   > **사건 상태 조건이 필수다 (2R #2)**: publication 축만 보면 resolve 가 먼저 잠금을 얻어 `RESOLVED` 를
   > 커밋한 뒤 replay 가 `publication_status IS NULL` 조건으로 `REQUESTED` 를 기록해 ADR 이 금지한
   > **`REQUESTED` + terminal 조합**이 만들어진다. 초안은 replay 가 먼저인 순서만 막았다.
3. outbox INSERT (`OutboxEvent.replay(...)`)
4. root 에 `outbox_event_id` 기록
> 초안은 `outbox_event_id` 를 조건부 UPDATE 에 넣었는데, 그 값은 **auto-increment 라 INSERT 후에야 존재**한다.
> 그러면 outbox 를 먼저 만들 수밖에 없고, 경합 패자가 영향 행 0 을 정상 응답으로 돌려주면
> **먼저 만든 outbox 가 남아 두 번 발행**된다. claim 을 먼저 하고 id 를 나중에 채우는 순서로 바꾼다.
> 또한 초안에는 **`last_replay_attempt_id`/`last_replay_target_group` 을 쓰는 주체가 없었다** — P15 의 대조가
> 비교할 정본을 갖지 못했다. claim UPDATE 가 그 둘을 함께 기록한다.

**I-1 가드도 원자화한다 (리뷰 1R #5)**: `resolve`/`discard` 의 종결 UPDATE 자체에
**`AND (publication_status IS NULL OR publication_status <> 'REQUESTED')`** 를 넣는다.
순차 검사(`findById` → 엔티티 전이, 현 `DeadLetterEndpoint:68-84`)만 두면 resolve 가 NULL 을 읽은 뒤
replay 가 `REQUESTED` 를 커밋하고, 그 다음 resolve 가 terminal 을 저장할 수 있다.
> **`<> 'REQUESTED'` 만 쓰면 안 된다 (2R #1)**: SQL 에서 `NULL <> 'REQUESTED'` 는 **UNKNOWN** 이라
> `publication_status IS NULL` 인 행 — 즉 **replay 를 한 번도 요청하지 않은 절대다수** — 이 종결되지 않는다.
> ADR §D6-2b 표는 `NULL` 에서 종결을 **허용**한다. 초안 조건은 그 표와 정면으로 어긋났다.

**P22.** `original_timestamp` NULL 비율 **실측 증적** (정정 — 리뷰 1R #13).
ADR §D5-2 가 미측정이라고 지적한 것은 **네 실제 원장 DB 의 기존 행 분포**다. fixture 를 세는 것은 자기대조라
가용성 손실을 측정하지 못한다. 서비스별 원장에 **read-only 집계 SQL** 을 실행해 *전체 / `RESOLVED_ORIGIN` /
replay 후보* 각각의 **분자·분모·기준시각**을 `docs/progress/evidence/` 에 남긴다.
**운영 데이터가 0건이면 "표본 0 — 미측정" 이라고 그대로 기록한다** (0건을 "NULL 0%" 로 적지 않는다).

**P23.** backfill 마이그레이션 (D3·D6-3 3단계) — **order V11 · product V9 · payment V9 · notification V7**
(리뷰 2R #2 로 **V10/V8/V8/V6 에서 밀어냈다** — 그 번호는 2b-3 P14(d) 의 digest 마이그레이션이 쓴다).
`UPDATE dead_letter_records SET root_record_id = id WHERE root_record_id IS NULL` ·
`UPDATE outbox_events SET record_kind='DOMAIN' WHERE record_kind IS NULL` + **NULL 잔여 0 검증**(잔여가 있으면 실패).
재실행 안전(idempotent)해야 한다. `EXPECTED_MIGRATIONS` → `order 1~11 · product 1~9 · payment 1~9 · notification 1~7`.
집계 조건의 `IS NULL` 분기는 **남겨둔다** — `NOT NULL` contract 는 이번 범위가 아니다(§10 R1).

**P24.** 문서 + **replay 개방 후 롤백 절차**(신규 — 리뷰 1R #15).
- `docs/runbooks/dlq-recovery.md` **§6 재작성**("재발행 — 현재 불가" → 절차·금지축·fence·재개방)
- **§6-R 롤백 절차**: ① 진입점 차단 → ② **drain 4조건 preflight**(아래) → ③ 4조건이 모두 만족될 때까지
  **replay-aware poller 를 유지** → ④ 그 후에만 구버전 이미지 복귀.
  **③ 을 건너뛰면 구 poller 가 `event_type='__replay__'` 를 토픽으로 써서 발행이 깨진다**(P10 sentinel 근거)

> **drain 4조건의 실행·검증은 2b-4 로 이관한다 (3R #3 → 4R #5·#6·#7·#8 로 방향 정정).**
> 3R 은 "2R 이 drain 조건을 넷으로 늘려 적고 실행 항목 P24 는 `PENDING` 하나만 검사한다" 는 지적을 받아
> **P24 를 여기서 확장했다. 그게 범위 위반이었다** — P24 는 2b-4 항목이고, 3b(소비 측)에서 그 설계를 열자
> 검토되지 않은 표면 넷이 한꺼번에 딸려 나왔다. 4R 이 그 넷을 전부 P1 으로 잡았다:
>
> | 4R | 3b 에서 확장하며 드러난 미결 |
> |---|---|
> | #5 | ⓐ 판정이 **한 테이블에 없다** — `record_kind` 는 `outbox_events` 소유, `publication_status` 는 `dead_letter_records` 소유다. 어느 쪽을 drain 기준으로 삼을지가 미정 |
> | #6 | ⓓ 식이 **불가능**하다 — `FixedSequenceBackOff` 에 `maxAttempts` 가 없다(`:12-28`, 배열 소진 후 `STOP`). `sum(backoff) × maxAttempts` 는 정책을 중복해 곱한다. **마지막 attempt 시각의 내구적 기준점도 없다**(root 는 attempt-id 만 갖고 outbox `created_at` 은 cleanup 으로 사라진다) |
> | #7 | preflight 를 **배포 진입점에 연결하는 작업이 없다**. 실제 명령은 `kubectl apply -k`(`02-architecture.md:416`)라 운영자가 raw apply 하면 스크립트가 안 불린다 — "절차를 강제한다" 는 주장이 성립 안 함 |
> | #8 | P24 의 "ADR-0020 Update Log 에 기록" 지시가 `adr/README.md:8-14` 와 충돌. 그 계약 변경은 **ADR-0021 이 이미 부분 무효화**로 처리했다 |
>
> 넷 다 **3b 가 답할 수 없는 질문**이다(진입점·정본·배포 wrapper가 전부 2b-4 소관). 그래서 **3b 는 drain 조건을
> "넷" 이라고 규정만 하고**, ⓐ의 테이블 소유·ⓓ의 식과 기준시각·preflight 진입점·ADR 처분은 **2b-4 계획 착수 시
> 결정한다**. 아래 §미해결에 이관 항목으로 남긴다.
>
> **3b 가 지금 지는 책임은 하나다**: 이 PR 은 **소비 측만** 바꾸므로, 배포는 **4서비스 전부 3b 를 받은 뒤에야
> 2b-4 진입점을 켠다**. 한 서비스라도 구버전이면 그 서비스의 재실패가 `pc-replay-*` 를 무시하고 독립 root 가 된다.
- **절차를 강제하는 수단을 함께 만든다 (2R #9)** — runbook 은 프로세스 기동을 막지 못하므로 문서만으로는
  V-28 이 false-green 이다:
  1. **kill-switch** `app.dead-letter.replay.enabled`(기본 true, base `application.yml` 소유 — ADR-0007) — false 면
     `action=replay` 가 거부된다. **즉시 반영되지 않는다 (3R #7)** — Spring 정적 설정이라 ConfigMap 갱신 +
     **롤링 재기동**이 필요하다(현 `k8s/base/services/*/configmap.yml` 은 `SPRING_PROFILES_ACTIVE` 만 담고
     Deployment 가 `envFrom` 으로 읽는다). runbook 은 "설정 변경 → 재기동 → 반영 확인" 3단계를 그대로 적는다.
     그 사이의 요청 차단은 **운영 규율**이지 코드 강제가 아님을 명시한다
  2. **pre-deploy preflight** `scripts/replay-drain-preflight.sh`(신규) — **구 이미지 배포 명령 앞단**에서
     4 DB 를 조회해 `record_kind='REPLAY' AND status='PENDING'` 이 하나라도 있으면 **exit≠0**.
     - 배포 대상 이미지가 **replay-aware 인지 판별**해, aware 면 통과시키고 **구 이미지만** 막는다
     - DB 접속·권한 실패는 **fail-closed**(막는다). 4개 격리 DB 각각의 접속 정보가 필요하다
     - `scripts/rollout-convergence-gate.sh` 는 **배포 후** 수렴 게이트라 이 역할을 대신할 수 없다
- **ADR-0020 Update Log 기재 (3R #2)** — D5-4 의 상관 대조 항목 `record_kind=REPLAY` 를 **root 의 durable
  fingerprint 4값 대조로 대체**한 것은 계약 변경이다(수명 경쟁 때문에 outbox 를 정본으로 쓸 수 없다는 D5-4
  자신의 논거에서 따라 나온다). ADR 본문은 immutable 이므로 **Update Log 에 대체 사유와 범위를 남긴다**
- `StockReservationService` javadoc 의 "DLQ 재발행(새 eventId)" 을 **ADR-0012 D5 우회 경로**로 정정
- `docs/progress/PHASE4.md` 동일 서술 정정 · `docs/05-data-design.md`(notification outbox·신규 컬럼) ·
  `docs/02-architecture.md` notification 패키지 트리 · `grafana-alerts.yml` 주석(집계 단위 = incident, V14)

**P25.** lint 확장 2종.
- `dead-letter-schema-parity-lint` — 4 DB `dead_letter_records` 신규 컬럼 + 4 DB `outbox_events` replay 컬럼 parity
- **진입점 단일성 검사**(N14) — replay 를 개시하는 코드가 `DeadLetterEndpoint` 밖에 없고, runbook·리허설 스크립트에
  `UPDATE dead_letter_records SET status` 류 직접 상태 변경이 **0건**임을 정적으로 검사한다
  (④-c-2a 에서 실제로 났던 결함이다)

---

## 6. 검증 방법

**원칙**: "존재한다"·"배선됐다" 는 검증이 아니다. **실패를 주입하고 DB/상태로 확인**한다.
모든 기대값은 ADR-0020 본문 값을 **리터럴로 독립 기재**한다 (N17).

| # | 대상 | 주입할 실패 | 확인 지점 |
|---|---|---|---|
| **V-1** | N1 | poller 가 `destination_partition` 을 무시하도록 변이. **fixture 는 원본을 key 의 기본 hash 파티션과 다른 파티션에 기록**한다 (2R #8 — 같은 key 를 보존하므로 기본 partitioner 가 원본과 같은 파티션을 골라 변이가 검출되지 않는다) | 발행된 레코드의 partition ≠ 원본 → **red**. 복원 시 green |
| **V-2** | N1 | `source_record_timestamp` 미탑재로 변이 | **발행된 Kafka 레코드의 timestamp == 원본 timestamp** 를 직접 비교(3R #6 — deadline 연장으로는 검출되지 않는다. P19 가 자식 재계산을 금지해 상속되므로 timestamp 를 빼도 deadline 이 늘지 않는다). 추가로 재실패 DLT 의 `DLT_ORIGINAL_TIMESTAMP` == root 값 |
| **V-3** | N1 | `destination_topic` 을 다른 업무 토픽으로 변이 | P20 fence 가 거부. fence 제거 시 잘못된 토픽으로 발행되어 red |
| **V-4** | N1 | `record_key` bytes 1바이트 변이 | fence 가 원본 대조에서 거부 |
| **V-5** | N1 | `payload` bytes 1바이트 변이 | fence 가 거부 |
| **V-6** | N1 | **원장 `event_id` 만** 원본 payload 의 eventId 와 다르게 만든 fixture (2R #10) | fence 가 **요청 자체를 거부**한다. eventId 대조를 제거하면 요청이 승인되고 **outbox 행이 생긴다** → red. (3R #9 — 소비 멱등은 여기서 깨지지 않는다. reader 가 읽은 **원본 payload** 를 그대로 발행하므로 소비자가 보는 eventId 는 원본 그대로다. 멱등 보존은 별도 테스트 소관) |
| **V-7** | N2 | compaction hole 흉내 — 요청 offset 에 레코드가 없는 상태 구성 후 seek | reader 가 다음 레코드를 반환 → **offset 불일치로 거부**. 검증 제거 시 엉뚱한 메시지가 발행됨을 red 로 확인 |
| **V-8** | N3 | 6 금지축을 **각각 1개씩** 만족하는 원장 행 6개 | 각 행이 발행에 도달하지 않고 반환 사유가 **해당 축을 정확히 지목**. 축 하나를 검사에서 빼면 그 케이스만 red |
| **V-9** | N3 | `replay_policy` **미등록** 토픽으로 요청 | default-deny 로 거부 (P19 정정분) |
| **V-10** | N4 | `original_timestamp = now + 4m` / `now + 6m` 두 케이스, 고정 Clock | 4m → deadline == `now + 7d`(clamp 적용, 연장 없음) · 6m → **거부**. clamp 를 `now+skew` 로 되돌리면 4m 케이스가 `now+7d+4m` 이 되어 red |
| **V-11** | N4 | 재발행 → 재실패 → 자식 생성 → 재요청 | ① root 의 `replay_deadline` 이 **DB 에 영속**되고 2회차 claim 이 그 값을 **덮어쓰지 않는다**(`COALESCE`) ② 자식이 root 값을 **복사**했다. 두 단언을 분리한다 — 합치면 transient 계산으로도 green (3R #4) |
| **V-12** | N5 | root 를 `publication_status='PUBLISHED'` 로 두고 backlog 조회 | `dlq.backlog` == 1, `dlq.oldest.age` > 0. 조기 종결 코드를 넣으면 0 이 되어 red |
| **V-13** | N6 | `publication_status` **네 상태 전수**(`NULL`·`PUBLISHED`·`PUBLISH_FAILED`·`REQUESTED`)에 `resolve`/`discard` | 앞 셋은 **성공**, `REQUESTED` 만 거부. 조건을 `<> 'REQUESTED'` 로 되돌리면 **`NULL` 행이 종결 불가**가 되어 red (2R #1) |
| **V-13b** | N4 | `now = deadline - 1ms` / `= deadline` / `= deadline + 1ms` 고정 Clock | 앞은 허용, 뒤 둘은 **거부**. 만료 검사를 제거하면 red (2R #3) |
| **V-14** | N6 | replay 와 resolve 를 **latch 로 두 순서 각각** 실행 — ① replay 선점 ② **resolve 선점** | 어느 순서에서도 `REQUESTED` + terminal 조합이 생기지 않는다. claim 의 `status IN ('OPEN','ACKED')` 를 빼면 **② 에서만** red (2R #2) |
| **V-15** | N7 | root 를 `RESOLVED` 로 닫은 **직후** 늦은 자식 유입 | root 가 `OPEN` 으로 재개방 + `reopened_at` 기록. 자식은 **삭제·거부되지 않는다**. 알림은 **commit 후 callback 1회 시도**만 단언한다 |
| **V-15b** | N7 | 재개방 commit 직후 **알림 callback 실패**(또는 프로세스 중단) | 원장의 재개방은 **유지**되고 알림 **0회가 허용**된다 (3R #8 — 기존 best-effort 계약 `DeadLetterRecorder:20-23` 과 정합. "정확히 1회" 를 요구하면 계약이 두 개가 된다) |
| **V-16** | N8 | 같은 사건을 **3회** replay → 3회 재실패 (**첫 발행이 성공한 뒤의 2·3회차 포함**) | 원장 행 4(root+자식3), **backlog = 1**. root 종결 → 0. target row 분리를 되돌리면 **2회차 claim 이 거부되어** red (3R #1) |
| **V-17** | N8 | 자식 id 로 `resolve` 요청 | root 로 정규화되어 root + 활성 자식이 함께 종결. 자식만 닫히면 red |
| **V-18** | N9 | 마이그레이션 **전** 미결 3건 적재 → 마이그레이션 → 집계 | 전후 건수 동일(3). 조건을 `root_record_id = id` 로 곧바로 바꾸면 0 이 되어 red |
| **V-15c** | N10 | **재개방 current read 경합**(5R #3) — 로케이터가 root id 를 반환한 **직후** 다른 트랜잭션이 root 를 `RESOLVED` 로 닫고 커밋한다 | 최종 `status='OPEN'`·`reopened_at` 이 **별도 트랜잭션 재조회**에서 관측된다. P15 단계 5 의 `findByIdForUpdate` 를 일반 `findById` 로 바꾸면 red (**seam 이 평문 읽기를 한 번 해야 성립한다** — 진짜 로케이터가 non-locking SELECT 로 여는 consistent-read 스냅샷을 stub 이 없애면 단계 5 가 최신을 보게 되어 변이가 관측되지 않는다) |
| **V-19a~V-19o** | N10 | 대조 축 ↔ fixture 매트릭스(P16). **명시 집합 16개 ID** (4R #2 로 집계를 명시 집합으로 바꿨고, 5R #1 이 `V-19o` 를 복원해 다시 갱신): **양성 3** = `V-19a`(전 축 일치) · `V-19n`(key 양쪽 NULL) · `V-19o`(digest 양쪽 NULL, **fixture 전용** — ADR-0021 §D2 계약 고정) / **단일 equality 음성 10** = `V-19b·c·d·d2·e·f·g·h·i·j` / **시나리오 음성 3** = `V-19k·l·m` | 양성 3종만 상관되고 나머지 13종은 전부 **독립 root 행**. **대조 predicate 를 하나 빼면 정확히 그 행만** red. **ID 수 ≠ fixture 실행 수** — `V-19h` 는 null↔값 **양방향 2회**를 돌리므로 실행은 17회다 |
| **V-20** | N10 | **소유 fence 는 아키텍처 테스트로 고정한다** (2R #12 — endpoint 는 숫자 id 만 받고 자기 datasource 만 조회하므로 "타 서비스 행 id" 라는 입력이 표현되지 않는다. 같은 숫자가 로컬에 있으면 로컬 행이 선택될 뿐이다) | `DeadLetterEndpoint` 가 타 서비스 datasource·repository 에 접근하지 않음을 정적으로 검사 |
| **V-21** | N11 | outbox cleanup 선행 실행 후 지연 DLQ 적재 | 같은 root 상관 + backlog 1 |
| **V-21b** | N11 | **reconciler 장기 중단 → cleanup 실행 → reconciler 복구** | cleanup 이 해당 replay outbox 를 **건너뛴다**(제외 조건). 행이 남아 있어 reconciler 복구 시 정상 전이. 제외 조건을 빼면 red (2R #6) |
| **V-21d** | N11 | outbox 행을 **강제로 삭제**한 뒤 reconciler 실행 | **`PUBLISH_FAILED` 로 강등되지 않고 자동 재요청도 열리지 않는다**. fail-closed 경보만 발생 (3R #3 — OR 조건 하나로 묶으면 정상 제외 경로만 통과해도 green 이 되어 강등 분기를 전혀 실행하지 않는다) |
| **V-21c** | N8 | purge 가 root 를 **조회한 뒤 삭제 전에** 재개방을 끼워 넣는다 | 재개방된 root 와 새 자식이 **삭제되지 않는다**. purge 의 `FOR UPDATE` + 상태 재검사를 빼면 red (2R #5). **재개방 경로가 2b-3 P15 에 생기므로 이 행의 검증도 2b-3 에서 수행한다** — 2b-1 은 잠금·재검사 코드만 넣고, 그 코드 자체는 이 시점에 직접 관측되지 않는다(미충족으로 명시) |
| **V-22** | N12 | 같은 원장 행에 replay 요청 **동시 2회** | outbox 행 정확히 1개, 한쪽 거부. **orphan outbox 0** 을 DB 조회로 확인 |
| **V-23** | N13 | broker ack 성공 후 아무 조작 없이 대기 | `status` 가 `OPEN`/`ACKED` 유지. 자동 `RESOLVED` 없음 |
| **V-24** | N14 | 진입점 단일성 lint (P25) | replay 개시 코드가 `DeadLetterEndpoint` 밖에 있거나 runbook 에 직접 상태 변경 SQL 이 있으면 red |
| **V-25** | N15 | notification 원장 행 1건으로 replay 개시 | notification `outbox_events` 에 replay 행 생성 → 원본 토픽으로 발행 |
| **V-26** | N16 | `record_kind` 를 채우지 않는 **팩토리 우회 INSERT** | 아키텍처 테스트가 실패한다. **런타임 DB 제약이 아님을 명시**(§10 R1) |
| **V-27** | D1 | ack 후 해당 사이클의 **두 save 모두** 실패 주입 (실제 DB+Kafka) | 행이 `PENDING` 으로 남고, 복구 후 다음 poll 에서 **broker 레코드 2개** + 같은 `eventId` 의 `processed_events` 증가분 **1** |
| **V-28** | 롤백 | **2b-4** — drain 조건을 위반한 상태에서 **구 이미지 배포 명령을 실제로 실행** | **preflight 게이트가 배포를 실패시킨다**(문서 절차 확인이 아니다 — 2R #9). **조건의 수·판정식·preflight 진입점은 2b-4 착수 시 확정한다**(4R #5~#8 — 3b 는 답할 수 없다). kill-switch 로 진입점을 끈 뒤 drain 이 끝나면 통과 |
| **V-28b** | N3 | `replay_policy` **allow 판정** 기록 | root 에 *정책 식별자+버전+판정* 이 남고 자식이 상속한다 |
| **V-28c** | N3 | `replay_policy` **deny 판정**(`order.created` 등) | **거부 이력이 남는다**. claim 에만 기록하는 구조로 되돌리면 deny 는 claim 에 도달하지 않아 **아무 기록도 없어** red (3R #5) |
| **V-30** | D3 | `record_kind IS NULL` 인 outbox 행(구버전 writer 흉내)을 fixture 로 넣고 poll | **도메인 경로로 발행된다**. 분기를 `record_kind = 'DOMAIN'` 로만 좁히면 그 행이 영원히 미발행으로 남아 red. expand 단계의 NULL 해석(D3)을 직접 관측하는 유일한 행 |
| **V-31** | C-5 | parity 대조기(P9-b) **self-test** — ① notification `outbox_events` 에서 컬럼 1개 제거 ② P8 신설 컬럼 1개에 `DEFAULT 'DOMAIN'` 부여 ③ 신설 컬럼 1개를 `NOT NULL` 로 ④ `OutboxEvent.java` 한 벌만 1바이트 변경 ⑤ `OutboxEventStatus` 를 order 판본으로 notification 에 복사 | 4종은 red, ⑤는 **green**(2집합 계약). 대조 축을 하나 빼면 해당 fixture 가 통과해 self-test 가 red. **정상 트리에서 lint 가 green 임도 함께 확인**한다 — 항상 red 인 lint 는 검사가 아니다 |
| **V-32** | D2 | notification `outbox_events` 에 **도메인 행 1건을 fixture 로 직접 INSERT** 후 poller 사이클 실행 (실제 DB + Kafka) | broker 에 해당 레코드가 도착하고 행이 `PUBLISHED` 로 전이. **poller 빈 배선을 지우면 red**. (replay 행은 진입점이 없어 이 PR 에서 만들 수 없으므로, 발행 표면이 실제로 도는지는 도메인 행으로 관측한다 — V-25 로 미루면 이 PR 이 "배선됐다" 수준의 판정으로 끝난다) |
| **V-33** | D6-4 | 원장 행 `publication_status='REQUESTED'` + 연결된 outbox 행을 **① `PUBLISHED` ② `FAILED` ③ `PENDING`** 세 상태로 두고 reconciler 실행 | ①→`PUBLISHED` ②→`PUBLISH_FAILED` ③→**전이 없음**(`REQUESTED` 유지). ③을 빼고 "REQUESTED 가 아니면 전이" 로 되돌리면 **발행 중인 건이 조기 종결**되어 red |
| **V-35** | N10 | **2b-4** — P21 claim 을 실제로 거쳐 root 에 digest 가 기록된 뒤, 그 재발행분이 재실패해 P15 가 상관한다 | fixture 주입 없이 **진입점→발행→재실패→상관** 전 구간이 한 번 돈다. 2b-3 은 digest 를 fixture 로만 심으므로 **writer 누락(2R #1)이 이 행 없이는 관측되지 않는다** |
| **V-29** | N17 | **변이 목록 전수** — §6 이 지목한 각 변이(**V-1~V-35 전체**, `V-19a~V-19o` 16 ID·17 실행 포함). 범위를 리터럴로 적지 않고 **이 표에서 유도**한다 (4R #2 — 초안의 `V-1~V-28b` 는 V-28c·V-30~V-35·신규 V-19 변형을 전부 빠뜨렸다) | 각 변이가 red → 복원 후 green. 변이 목록과 red 테스트 id 를 PR 본문에 **열거**한다. 자기대조 0건 |

**모듈별 그린 기준**: 각 PR 에서 `common` + 변경된 서비스 모듈 전체 테스트 0 실패 +
**`scripts/e2e/saga_e2e.py` 그린**(각 PR 이 `EXPECTED_MIGRATIONS` 를 함께 갱신하므로 이 게이트가 실제로 돈다).

---

## 7. 완료 조건

1. §1 의 **N1~N17 이 전부 거짓**임이 §6 의 **V-1~V-35**(V-13b·V-15b·**V-19a~V-19o**·V-21b·V-21c·V-21d·V-28b·V-28c·**V-35** 포함)로 확인된다.
   > **ID 충돌 해소 (3R #7 → 4R #4 로 방향 정정)**: `V-30` 이 **두 곳에 배정**돼 있었다 — ① `record_kind IS NULL` 호환성 · ② P21 진입점 관통.
   > 3R 은 ①을 `V-34` 로 옮겼는데 **그게 틀렸다** — ①은 ④-c-2b-2 에서 **이미 머지된 테스트**가 `V-30` 이라는 이름으로
   > 들고 있다(`OutboxReplayPublicationIntegrationTest:44·122`). 계획서만 고치면 코드와 갈라진다.
   > **아직 코드가 없는 ②를 `V-35` 로 옮긴다.** `ADR-0021:101` 이 ②를 "계획서 V-30" 으로 참조하므로
   > **ADR-0021 Update Log 에 참조 번호 정정 1줄**이 필요하다(트레이드오프 변경이 아니라 참조 오타 정정이므로
   > `adr/README.md:14` 의 새-ADR 요구에 걸리지 않는다).
   > 범위 표현도 `V-1~V-30` → **`V-1~V-35`** 로 정정한다(초안은 V-31~V-33 을 빠뜨리고 있었다).
   단 **N16 은 "런타임 DB 제약" 이 아니라 "코드 경로 강제" 로 축소 판정**한다 — `NOT NULL` contract 를 이번 범위에서
   제외했으므로(§10 R1), 팩토리를 우회한 직접 INSERT 는 DB 가 막지 못한다. 이 한계를 완료 보고에 명시한다
2. 4 PR 전부 머지되고, 각 PR 의 diff 리뷰가 **P1 = 0 이며 직전 라운드가 새 계약 표면을 추가하지 않았다**
   (수렴 판정 — 건수 추세로 종료하지 않는다)
3. `docs/runbooks/dlq-recovery.md` §6 이 **실행 가능한 절차**로 재작성되고 **§6-R 롤백 절차**를 포함하며,
   리허설이 **공개 진입점만** 사용한다 (직접 SQL 상태 변경 0 — ④-c-2a 재발 방지, P25 lint 로 강제)
4. `dead-letter-schema-parity-lint` 가 4 DB 신규 컬럼 parity 를 강제한다.
   **각 PR 은 `bash scripts/dead-letter-schema-parity-lint.sh` 본실행과 `--self-test` 를 모두 통과해야 한다**
   (리뷰 1R #14 — `DLQ-PARITY-014` 는 4벌이 **이미 동일할 때만** 목록 누락을 신고하므로, 목록에 이미 있는 파일을
   고칠 때는 본실행이 유일한 방어선이다)
5. `original_timestamp` NULL 비율 증적이 **실제 원장 집계**로 남는다(표본 0이면 "미측정" 으로 기록).
   비율이 높으면 **가용성 손실 수용 기준**을 ADR-0020 Update Log 에 기록한다

---

## 8. 영향 범위

| 모듈 | 변경 |
|---|---|
| `common` | `DlqOrigin`(+상관 4필드) · `DlqHeaders`(allowlist 판독) · `OriginalRecordReader`(신규) · `ReplayEligibility`+정책 레지스트리(신규) |
| `order-service` | 마이그레이션 **V8 · V9 · V10 · V11** · outbox 4파일 · deadletter 6파일 · reconciler |
| `product-service` | 마이그레이션 **V6 · V7 · V8 · V9** · 동일 |
| `payment-service` | 마이그레이션 **V6 · V7 · V8 · V9** · 동일 |
| `notification-service` | 마이그레이션 **V4 · V5 · V6 · V7** · **outbox 8파일 신설**(7파일 byte 동일 · `OutboxEventStatus` 는 payment 판본) · deadletter 6파일 · reconciler · yml `app.outbox.*` |

> **단계별 `EXPECTED_MIGRATIONS`** (리뷰 2R #2): 2b-1 후 `8/6/6/4` → 2b-2 후 `9/7/7/5` →
> **2b-3 후 `10/8/8/6`** → 2b-4 후 `11/9/9/7`. 갱신은 **4회**다(2b-1·2b-2·**2b-3**·2b-4).
| `user-service` | **무변경** (Kafka consumer 없음) |
| scripts | `scripts/e2e/saga_e2e.py` `EXPECTED_MIGRATIONS` **4회 갱신**(2b-1 · 2b-2 · **2b-3** · 2b-4) · lint 2종 + **`dead-letter-schema-parity-lint.sh` 에 `outbox_events` 축 확장**(P9-b) |
| docs | runbook §6 + §6-R · 05-data-design · 02-architecture · PHASE4 · grafana-alerts 주석 · **ADR-0012 D1 표 + Update Log**(P9-c, 2b-2 에서 선행) |

---

## 9. 배포 순서

1. **2b-1 배포** → 신규 컬럼 nullable, 기존 미결 집계 불변(V-18) 확인
2. **2b-2 배포** → 구버전 writer 소멸 확인 (`record_kind IS NULL` 신규 유입이 멈추는지)
3. **2b-3 배포** → 재실패 상관·재개방 경로 성립. **replay 는 아직 개시 불가**(진입점 없음)
4. **2b-4 배포 + backfill 실행** → NULL 잔여 0 확인 → 첫 replay 를 **리허설**로 수행하고 증적을 남긴다.
   `NOT NULL` contract 는 수행하지 않는다(§10 R1)

1~3 단계는 앞 단계만 배포된 상태에서 정지 가능하다. **4 단계 이후의 롤백은 P24 §6-R 절차를 따른다** —
replay 행이 남은 채 구버전으로 내려가면 안 된다.

---

## 10. 미해결 / 후속

| # | 항목 | 처분 |
|---|---|---|
| **R1** | `record_kind`·`root_record_id` 의 `NOT NULL` **contract 단계** | 이번 범위 밖. backfill 후 별도 마이그레이션 1개. 그때까지 **N16 은 코드 경로(팩토리·아키텍처 테스트)로만 강제**되고 DB 는 막지 않는다 — 완료 조건 1에 명시 |
| **R2** | 운영 클러스터(GKE) 실적용 — `ALTER_CONFIGS` 권한·실제 좌표 읽기 | ③ PR3d-b-2 GKE 세션에 합류 (④-c-2b-0 미충족 3과 동일 처분) |
| **R3** | **tombstone(payload NULL) 레코드의 replay** | 불가. `outbox_events.payload NOT NULL`(V4). 현재 tombstone 을 쓰는 토픽이 없으므로 스키마를 바꾸지 않는다. 필요해지면 컬럼 nullable 화 |
| **R4** | `replay_policy` 정책의 **장기 운영 정밀화** | 레지스트리·default-deny·기록/상속은 P19·P21 이 만든다. **초기 정책표(토픽별 allow/deny + 사전조건)는 2b-4 착수 전에 확정**하며, 최소 1개 토픽은 실제 도메인 상태 조회를 거친다(2R #7). 이연되는 것은 *운영 경험에 따른 정밀화*뿐이며 "전부 deny" 나 "검사 없는 allow" 로 시작하지 않는다 |
| **R5** | 소비 성공 확인 자동 종결 | ADR Alternative E 기각. replay 빈도가 오르면 재검토 |
| **R6** | `__consumer_offsets`·KRaft metadata bound, PVC 증설 | ADR §D4-2 후속 인프라 결정 |
| **R8** | `dead_letter_records.publication_status` **인덱스** | 추가하지 않는다 (C-7). 이 테이블은 DLQ 유입량에 유계이고 같은 컬럼을 스캔하는 `countUnresolvedByPublicationStatus`(2b-1)가 이미 무인덱스로 돈다. 4 DB 마이그레이션 + parity glob 확장 비용이 이득을 넘는다. **원장 행 수가 경보 `scan-limit`(100) 규모를 상시 넘기면 재검토** |
| **R7** | **outbox 행이 사라진 `REQUESTED` root 의 종결 경로** | 정상 경로에서는 cleanup 제외 조건이 부재를 만들지 않으므로 이 상태는 **계약 위반 신호**다. 자동 강등은 발행을 실패로 오분류하므로 채택하지 않았다(3R #3). 실제로 발생하면 **ADR-0020 Update Log 로 `PUBLISH_UNKNOWN` 축 추가를 결정**한다 — 상태값 신설은 ADR 사안이다 |

---

## 11. 정정 이력 — 계획 리뷰 3라운드 (2026-09-02, 40건 전량 반영)

### 11.1 1라운드 — 16건 전량 반영

초안이 **틀렸던 것**만 남긴다. 라운드별 증적은 `task-impl4-c2b-dlq-replay.audit.md`.

| 초안의 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| deadline = `min(ts, now + clockSkewBudget) + window` | ADR §D5-3 은 허용된 미래값을 **`now` 로 clamp** 하라고 명시한다. 초안 식은 4분 미래 timestamp 에 창을 4분 **연장**한다 | P19 — 거부 조건과 clamp 를 분리 |
| 2b-3 에서 진입점을 열고 2b-4 에서 상관을 붙인다 | 그 사이 구간의 재실패가 **독립 incident 로 갈라져** D5-4·D6-3 을 위반한다 | §4 — 상관(2b-3)을 진입점(2b-4)보다 **먼저** |
| 조건부 UPDATE 에 `outbox_event_id` 를 함께 넣는다 | auto-increment 라 **INSERT 후에만 존재**한다. outbox 를 먼저 만들면 경합 패자의 orphan 이 남는다 | P21 — claim → INSERT → id 기록 순서 |
| P15(현 P19)의 대조가 `last_replay_attempt_id` 를 읽는다 | **그 값을 쓰는 주체가 계획에 없었다**. 정상 재실패도 상관에 실패한다 | P21 claim UPDATE 가 attempt·group 을 함께 기록 |
| I-1 을 순차 검사로 강제 | resolve 가 NULL 을 읽은 뒤 replay 가 `REQUESTED` 를 커밋하면 terminal 이 덮인다 | P21 — 종결 UPDATE 에 `publication_status <> 'REQUESTED'` |
| 종결은 단일 엔티티 `resolve()` | ADR 은 **root + 활성 자식 원자 전파**와 자식 단독 종결 금지를 요구한다. 자식 id 처리도 없었다 | P5 신설 |
| purge 는 `COALESCE(discarded_at, resolved_at)` | `DISCARDED` → 재개방 → `RESOLVED` root 는 두 시각을 다 갖고, COALESCE 가 **과거 시각을 골라 조기 삭제**한다 | P4 — 상태별 `CASE` |
| backfill 을 "마이그레이션" 이라 적고 버전 미배정 | 앞 PR 마이그레이션은 이미 적용돼 수정 불가. 새 버전이 필요하다 | P23 — order V10 · product V8 · payment V8 · notification V6 |
| N16 "누락 INSERT 가 실패한다" + contract 이연 | 컬럼이 nullable 인 채로는 **DB INSERT 가 성공**한다. 완료 조건과 양립 불가 | 완료 조건 1 — N16 을 **코드 경로 강제**로 축소하고 한계 명시 |
| `replay_policy` 는 비워두고 첫 운영 사례에서 채운다 | 그러면 D5-4 의 늦은 이벤트 위험이 **아무 데서도 관리되지 않는다** | P19 — 레지스트리 + **default-deny** |
| D1 검증 = `save` 스텁 예외 1회 | poller 는 실패 후 **같은 객체를 다시 save** 한다(`:116`) — 두 번째가 성공하면 재발행이 없다. 초안 검증은 주장을 관측하지 못한다 | P13 — 두 save 모두 실패 + 실제 DB/broker 대조 |
| V-1~V-18 이 N1~N17 을 닫는다 | N1 의 topic·key·payload·eventId, N14, D8 소유 fence 에 **대응 행이 없었다**. "모든 기대값 변이" 는 목록이 없어 공백을 못 메운다 | §6 — V-1~V-29 로 확장, 변이 목록 열거 의무화 |
| NULL 비율을 통합테스트 fixture 로 산출 | ADR 이 미측정이라 한 것은 **실제 원장 분포**다. fixture 를 세는 것은 자기대조 | P22 — 실제 원장 read-only 집계, 표본 0 이면 "미측정" |
| E2E 게이트 무영향 | `saga_e2e.py:64-68` 이 **버전 집합 정확 일치**를 강제한다. 갱신 없으면 readiness 가 먼저 red | P7·P8·P23 — 각 PR 이 `EXPECTED_MIGRATIONS` 동반 갱신 |
| "되돌리기 = 앞 단계에서 정지 가능" | replay 행이 생긴 뒤 롤백하면 구 poller 가 `__replay__` 를 토픽으로 쓴다 | P24 §6-R 롤백 절차 신설 |
| notification `app.outbox.*` 위치 미명시 | ADR-0007 상 런타임 정책은 base 소유이고 프로파일 배치가 금지된다 | P9 — base `application.yml` 소유 명시 + 바인딩 테스트 |
### 11.2 2라운드 — 14건 전량 반영 (1R 수정이 만든 새 결함 8건 포함)

| 1R 수정이 만든 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| 종결 UPDATE 에 `AND publication_status <> 'REQUESTED'` | SQL 에서 `NULL <> 'REQUESTED'` 는 **UNKNOWN** — `publication_status IS NULL` 인 **절대다수 행이 종결 불가**가 된다. ADR §D6-2b 표는 NULL 에서 종결을 허용한다 | P21 — `(IS NULL OR <> 'REQUESTED')` + 네 상태 전수 테스트(V-13) |
| claim 조건에 publication 축만 검사 | resolve 가 먼저 커밋하면 replay 가 그 뒤에 `REQUESTED` 를 기록해 **terminal + REQUESTED** 조합이 생긴다. 1R 은 replay 선점 순서만 막았다 | P21 — `AND status IN ('OPEN','ACKED')` + 양방향 latch 테스트(V-14) |
| deadline 식과 상속만 정의 | ADR §D5-3 의 **`now < replay_deadline` 강제**가 작업·검증 어디에도 없었다 — 만료 사건을 발행해도 green | P19 4항 + V-13b 경계 3종 |
| `record_kind=REPLAY` 를 "attempt 기록 존재" 로 치환 | **동치가 아니다** — 유효 attempt 가 살아 있는 동안 같은 헤더를 붙인 도메인 레코드가 owner/group/topic 대조를 통과한다 | P14 — root 의 durable fingerprint(`event_id`·`original_key`·`original_timestamp`·`origin_topic`) 대조 + 음성 8종(V-19) |
| purge = 조회 후 자식 동반 삭제 | 조회와 삭제 사이에 P15 가 재개방하면 **살아 있는 incident 와 새 자식을 지운다**. P5·P15 만 root 잠금으로 직렬화되고 purge 는 규약 밖이었다 | P4 — purge 도 `FOR UPDATE` + 상태 재검사 (V-21c) |
| reconciler 가 outbox 상태를 나중에 조회 | cleanup 이 `PUBLISHED` outbox 를 무조건 지운다(`OutboxEventJpaRepository:31-33`). reconciler 가 retention 이상 멈추면 **root 가 `REQUESTED` 에 영구 고착**되고 I-1 때문에 종결도 못 한다 | P12 — cleanup 제외 조건 + outbox 부재 시 `PUBLISH_FAILED` 강등 (V-21b) |
| `replay_policy` 초기값을 "보수적으로 deny 쪽" (R4) | 전부 deny 면 V-25·첫 리허설이 불가능하고, 검사 없는 allow 면 D5-4 계약 미구현이다. 게다가 **P1 이 만든 컬럼을 아무도 쓰지 않았다** | P19·P21 — 초기 정책표 확정 + 판정 기록·상속 (V-28b), R4 재작성 |
| V-1 = partition 무시 변이 | replay 는 **같은 key 를 보존**하므로 기본 partitioner 가 원본과 같은 파티션을 골라 **변이가 검출되지 않는다** | V-1 — fixture 를 기본 hash 파티션과 다른 파티션에 기록 |
| 롤백 절차를 runbook 에만 기재 | runbook 은 **프로세스 기동을 막지 못한다** — V-28 이 문서 확인이라 false-green | P24 — kill-switch(`app.dead-letter.replay.enabled`) + **배포 preflight 게이트**, V-28 은 배포 명령 실패를 확인 |
| V-6 = payload 안 eventId 변이 | payload bytes 도 함께 바뀌어 **V-5 가 먼저 거부**한다. eventId 대조를 제거해도 green | V-6 — 원장 `event_id` 만 다른 fixture |
| 재개방 + Slack 알림을 "같은 트랜잭션" | 외부 webhook 은 rollback 대상이 아니다. 기존 계약(`DeadLetterRecorder:20-23`)이 이미 **0회 가능**을 명시한다 | P15 — commit 후 best-effort 로 완화 |
| V-20 = 타 서비스 원장 행 id 로 요청 | endpoint 는 숫자 id 만 받고 **자기 datasource 만** 조회한다 — 그 입력이 표현되지 않는다 | V-20 — 아키텍처 테스트로 전환 |
| notification outbox 복제 | `OutboxEventCleanupScheduler:23`·`OutboxRetentionProperties:18-19` 의 "notification 제외" javadoc 이 **머지 즉시 거짓**이 된다 | P9 — 같은 PR 에서 javadoc 계약 갱신 |
| 재번호 후 상호참조 | `P4-4` 는 이 문서에 없는 항목(ADR-0020 계획서의 것)이었고, 부모 범위에서 **P13 이 누락**됐다 | 머리말·N17 주석 교정 |

> **2R 이 확인해 준 것**: 2b-3(상관) 선배포 순서는 성립한다 — attempt 기록이 아직 없는 상태는 **정상적인 불일치**로
> 처리되어 독립 행이 될 뿐이다. P5(전파)와 P15(재개방)도 root 잠금으로 직렬화되어 무한 전이가 생기지 않는다.

### 11.3 3라운드 — 10건 전량 반영 (2R 수정이 만든 새 결함 6건 포함)

| 2R 수정이 만든 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| claim 은 root 에 `publication_status` 를 건다 | 첫 replay 성공 후 root 가 `PUBLISHED` 가 되는데 claim 은 `NULL`/`PUBLISH_FAILED` 만 허용한다 — **두 번째 replay 가 구조적으로 불가능**했다. V-16 의 "3회 replay" 가 성립하지 않았다 | P21 — **target row(최신 활성 행) ↔ 상관 앵커(root) 분리**, 잠금 순서 root→child, I-1 을 incident 단위로 |
| deadline 을 "root 에서 1회 계산하고 상속" | **어디에 영속하는지 정의가 없었다**. 매 요청 transient 계산이나 fixture 직접 주입으로도 V-11·V-13b 가 green | P21 2b — `replay_deadline=COALESCE(replay_deadline, :calculated)` + 자식 복사, V-11 을 두 단언으로 분리 |
| outbox 부재 시 `PUBLISH_FAILED` 로 강등 | 행 부재는 **실패의 증거가 아니다**. 이미 발행된 행이 사라져도 같은 관측이라 **발행을 실패로 감사 기록하고 재요청까지 연다**. ADR §D6-4 는 실제 `FAILED` 소진에만 그 전이를 준다 | P12 — 강등 철회, fail-closed 경보 + 운영자 판정, 종결 경로는 §10 **R7**. V-21b/V-21d 분리 |
| fingerprint 대조로 `record_kind` 를 대체 | **P15 의 실제 대조 목록에 그 4값이 없었고**, ADR 계약 변경을 어디에도 기록하지 않았다 | P15 — 4값 명시(+`original_key` null-safe) · P24 — **ADR-0020 Update Log 기재** |
| `replay_policy` 초기 정책표는 "2b-4 착수 전 확정" | 계획이 정책 축을 **비워 둔 채** 완료 판정으로 간다. 게다가 deny 는 P19 에서 걸러져 claim 에 도달하지 않으므로 **거부 이력이 남지 않았다** | P19 — **10토픽 초기 정책표를 이 문서에 확정** + allow/deny **양쪽** 감사 write. V-28c 추가 |
| kill-switch 가 "설정 1개로 즉시" 차단 | Spring 정적 설정은 **재기동 없이 바뀌지 않는다**. preflight 도 대상 배포 명령이 불명확했다(`rollout-convergence-gate.sh` 는 **배포 후** 게이트) | P24 — 재기동 포함 3단계 절차 명시 + `scripts/replay-drain-preflight.sh` 신설(이미지 capability 판별·4 DB·fail-closed) |
| V-2 = timestamp 미탑재 → deadline 연장 관측 | P19 가 자식 재계산을 금지해 **상속되므로 timestamp 를 빼도 deadline 이 늘지 않는다** — 변이 미검출 | V-2 — 발행 레코드 timestamp 직접 비교 |
| V-6 = eventId fence 통과 시 멱등 억제가 깨짐 | reader 가 읽은 **원본 payload** 를 그대로 발행하므로 소비자가 보는 eventId 는 원본 그대로다 — 그 관측은 일어나지 않는다 | V-6 — "요청 승인 + outbox 행 생성" 으로 red 판정 |
| V-15 = 재개방 시 "알림 1회" | P15 를 best-effort 로 완화해 놓고 검증은 **정확히 1회**를 요구했다 — 계약이 둘이 됐다 | V-15 = commit 후 1회 시도 · **V-15b** = 알림 0회 허용 |
| §11 이 audit 파일을 인용 | 그 파일이 **저장소에 없었다** | `task-impl4-c2b-dlq-replay.audit.md` 생성 |

> **3R 이 확인해 준 것**: P5·P15·purge 는 전부 canonical root 를 먼저 잠그므로 **잠금 순환이 없다**.
> 다만 3R #1 이 target child 를 도입했으므로 **root → target child 순서를 명문화**했다(P21).

---

## 12. 정정 이력 — ④-c-2b-3 계획 리뷰 1라운드 (2026-09-05, 17건 전량 반영)

착수 전 코드 검증(C-15~C-28)이 이미 초안 전제 3건을 뒤집은 뒤에 돌린 라운드다.
그런데도 **P1 이 12건 나왔고, 그중 4건은 검증 표 자신이 틀렸다는 지적**이다.

### 12.1 내 검증 표를 반증한 것 (4건)

| # | 내가 적은 것 | 반증 |
|---|---|---|
| #1 | fingerprint 4값이면 상관 정본으로 충분 | **payload 동일성을 묶는 값이 없다**. 같은 eventId·key·ts 를 실은 변조 payload 가 전 축을 통과해 **남의 사건을 재개방**한다. ADR §D5-4 가 경계한 바로 그 경로이고 §D8-3 은 payload byte-for-byte 를 요구한다 → **digest 컬럼 신설**, C-18 "마이그레이션 0" 철회 |
| #13 | C-24 "호출부 2곳" | **실측 44곳**(main+test). **notification 에는 quarantine consumer 가 없어** 서비스마다 수도 다르다 → 시그니처 확대 폐기, `LedgerOwner` **빈 주입** |
| #14 | C-27 "신규 복제 누락은 자동으로 막힌다" | **과장**. `DLQ-PARITY-014` 는 4벌이 **이미 byte 동일할 때만** 신고한다 — 생성 시점부터 갈라진 복제본은 서비스별 파일로 오인돼 통과한다 |
| #6 | P15 "기존 best-effort 계약을 따른다" | **현재 코드가 그 계약을 안 지킨다**. `record()` 는 `@Transactional` **안에서** Slack 을 부른다(`DeadLetterRecorder:52·93`) — javadoc 의 "commit 후" 가 이미 거짓 → afterCommit 이전 |

### 12.2 구현했으면 false-green 이 됐을 것 (2건)

- **#2 detach** — 초안 순서(잠금 → 대조 → INSERT + 재개방)는 `insertIfAbsent` 의
  `clearAutomatically=true` 가 **잠근 root 를 detach** 시켜 **재개방 UPDATE 가 나가지 않는다**.
  자식 연결만 보는 테스트는 green 이다. → 순서 재정의(P15(a)) + **commit 후 별도 트랜잭션 재조회 단언**
- **#3 TOCTOU** — 잠금 **전** 조회에서만 attempt-id 를 봐서, 그 사이 2b-4 가 새 attempt 를 기록하면
  **오래된 attempt 의 재실패가 최신인 것처럼** 상관된다. → 잠금 후 재확인

### 12.3 검증 설계가 주장을 관측하지 못한 것 (4건)

- **#7** P16 "8종" 이 독립 축이 아니었다 — `다른 destination topic` 과 fingerprint 의 `origin_topic` 은
  **같은 비교**이고, fingerprint 불일치가 "셋 중 하나" 라 각 조건을 지워도 red 가 안 된다 → **9축 1:1 매트릭스**
- **#9** N11 이 "cleanup 을 먼저 돌린 뒤" 만 요구해 **ShedLock 으로 잡이 안 돌아도 통과**한다
  → outbox 행 **삭제 자체를 단언**(기존 `OutboxReplayPublicationIntegrationTest:223-230` 방식)
- **#10** V-21c 가 경합 지점을 정의하지 않아, 재개방 후 purge 를 부르면 **후보에서 빠질 뿐** race 를 시험하지 않는
  vacuous 테스트가 된다 → **후보 조회 직후 latch**, stale id 보유까지 단언
- **#12** P17 이 non-null 만 요구해 **재발행 시각을 저장해도 green** → 고정 timestamp 정확 일치 단언

### 12.4 누락 (7건)

#4 송신 측 allowlist 미강제(임의 헤더·`DLT_*` 주입 표면 존속) · #5 ADR-0020 §D5-4 의 `record_kind=REPLAY`
대조와 코드의 의도적 불일치를 같은 PR 에서 개정하지 않음 · #8 `ReplayHeaders` 판독 경로 미고정
(fixture 로 `DlqOrigin` 을 만들면 헤더 문자열이 깨져도 green) · #11 P16 에 중복 유입·양 terminal 재개방·
V-15/V-15b/V-16 미배정 · #15 배포·롤백 순서 부재(2b-4 선행 시 backlog=1 즉시 파손) ·
#16 재개방 알림·상관 실패 관측 표면 부재(ADR §D6-2b 필수) · #17 javadoc 이 거짓이 됨.

**#5 는 ADR 내부 충돌을 드러냈다** — §D5-4 는 `record_kind=REPLAY` 대조를 요구하면서 같은 절에서
"outbox 를 정본으로 삼으면 수명 경쟁에서 진다" 고 적는다. `record_kind` 는 `outbox_events` 에만 있으므로
**요구된 대조 축이 요구된 이유로 쓸 수 없다.** P14(f) 가 이 PR 에서 개정한다.

---

### 12.5 2라운드 — 9건 전량 반영 (**1R 수정이 만든 새 결함 6건**)

1R 이 17건을 반영하며 만든 8개 신규 표면(digest 컬럼·ADR 개정·송신 allowlist·`LedgerOwner` 빈·P15 순서 재정의·
afterCommit 알림/Counter·P16 재작성·롤백 순서)을 겨냥한 라운드다. **P1 6 · P2 3.**

| # | 1R 수정이 만든 결함 | 반증 |
|---|---|---|
| #1 | digest 컬럼을 신설하고 **writer 를 배정하지 않았다** | P14(d)는 기존 4필드만 매핑하고, 2b-4 P21 의 root 앵커 UPDATE 에도 digest 가 없다. 실제 replay 에서 root digest 가 **영원히 NULL** → 대조 축 9가 **늘 통과**. 2b-3 이 fixture 로 심으므로 **green 인 채로 검사만 사라진다** → P14(d) 매핑 + P21 writer + **V-30**(진입점 관통) |
| #2 | digest 마이그레이션에 **V10/V8/V8/V6** 배정 | P23 backfill 이 **정확히 같은 번호**를 쓰고 있었다. 두 파일이면 Flyway duplicate-version 으로 부팅이 깨지고, 합치면 2b-3/2b-4 독립 배포 경계가 사라진다 → P23 을 **V11/V9/V9/V7** 로, `EXPECTED_MIGRATIONS` 갱신 **3회 → 4회** |
| #3 | 송신 allowlist 를 **부분집합**으로 규정 | `replayHeaders()` 는 JSON 이 비면 **빈 Map**, `addHeaderIfPresent` 는 blank 값을 **조용히 생략**한다 — 헤더 0~3개짜리 REPLAY 가 통과해 **발행 측에서 N11 을 깬다** → **정확 일치 + 4값 유효성** |
| #4 | ADR-0020 을 **Update Log** 로 개정 | `adr/README.md:14` 가 **"트레이드오프 변경·대안 추가는 Update Log 로 우회 금지, 새 ADR 작성 사유"** 로 못박는다. 2b-2 P9-c 선례는 표의 **사실** 정정이라 유추가 틀렸다 → **ADR-0021 신설 + ADR-0020 `Partially Superseded`** |
| #5 | 롤백 drain 을 `REQUESTED == 0` 으로 판정 | 그 값은 **broker ack 시 `PUBLISHED` 로 바뀐다** — 이미 발행됐지만 소비 재시도 중이거나 DLT 이동 중인 레코드를 못 잡는다. 그 상태로 롤백하면 늦은 DLT 가 독립 root 가 된다 → **4조건(REQUESTED 0 · target group lag 0 · DLQ intake lag 0 · 재시도 상한 경과)** |
| #6 | "완료 조건에 lint 본실행 명시" 로 digest parity 가 강제된다 | **거짓**. lint 의 replay 축 검사는 `V*__dead_letter_replay_axis.sql` **glob 1개**만 보고 컬럼 목록도 하드코딩이다 — **별도 V10 파일은 본실행·self-test 모두 green**. ④-c-2b-1(glob 미확장)·2b-2(목록 누락)에 이은 **같은 구멍 세 번째 재발** → 검사를 **최종 스키마 합성 기준**으로 전환(P14(f-2)) |

**남은 3건(P2)**: #7 `pc-replay-target-group` 헤더가 상관 판단에 **쓰이지 않아** 죽은 데이터였다(지워도 결과 동일)
→ **3자 대조** · #8 P16 의 "9축 1:1" 이 축 중복으로 성립하지 않고 §6 V-19 는 "8종" 으로 남아 있었다
→ **V-19a~V-19m 고유 ID 배정** · #9 신규 Counter 의 트랜잭션 의미 미정의(트랜잭션 안에서 올려 rollback 분까지 세도
green) → **`CommitAwareMetrics` 명시 + bounded reason 리터럴 9종**.

> **이 라운드가 확인해 준 것**: P14(a)(c) 키 정본·판독, P15(a) 실행 순서 재정의, P15(f) `LedgerOwner` 빈 주입은
> 반증되지 않았다. P14.~P17. id 규약도 준수 판정.

---

## 부록 A — 범위 분리 이력 (2026-08 ~ 09)

### A.1 왜 분리됐나

④-c-2 계획 리뷰 3라운드에서 결함 10건 중 **6건이 replay 경로**에 몰렸고(#1·#2·#3·#4·#5·#7),
그 중 다수가 **계획 리뷰만으로 닫히지 않는 결정 사안**이었다.

**같은 주장이 두 라운드 연속 반증됐다** — "재발행 중복 0" 을:
- 2R: `REPLAY_REQUESTED → 발행 → REPLAY_PUBLISHED` 2단 상태머신으로 시도 → **발행 직후 사망 시 발행 여부 판별 불가**로 반증
- 3R: 기존 `outbox_events` 재사용으로 시도 → **`OutboxPollingService:83-86` 이 broker ack 후 별도로 `PUBLISHED` 를 저장**하므로 같은 crash window 존재

두 번 반증된 표면은 계획서 수정이 아니라 **설계 결정**이 필요했다 → [ADR-0020](../adr/0020-dlq-replay-contract.md).

### A.2 ADR 이 확정해야 했던 것 (D1~D7) — **결정 당시의 문제 진술로 보존**

| 항목 | 문제 | ADR-0020 대응 |
|---|---|---|
| **D1** 재발행 보장 수준 | 일반 DB outbox 로는 exactly-once 발행이 불가능하다 | §D1 — publication at-least-once + 소비 효과 멱등 |
| **D2** notification outbox | notification 에는 outbox 가 없고 ADR-0012 D1 이 `processed_events` 만 할당했다 | §D2 — 신설(ADR-0012 D1 개정) |
| **D3** replay 레코드 표현 | `OutboxEvent` 는 토픽·partition·임의 key·헤더를 표현하지 못한다 | §D3 — additive 컬럼 + poller kind 분기 |
| **D4** replay 원본 출처 | 좌표를 읽는 컴포넌트가 없고, 브로커 retention 이 선언돼 있지 않았다 | §D4·§D5-1 — 계약 선언(④-c-2b-0) + 좌표 검증 |
| **D5** 금지 정책 근거 | `payload_truncated` 를 금지 사유로 쓰는 것은 자기모순이다 | §D5-2 — 독립 6축 |
| **D6** 종결 실행자 | 원장↔outbox 를 잇는 것이 없고 종결 주체가 미정이었다 | §D6 — 두 축 물리 분리 + 사람만 종결 |
| **D7** 운영 진입점 | CLI vs Actuator 미정 | §D7 — `deadletter` 엔드포인트 확장 1종 |
| **D8** (계획 검증 중 발견) | 소비 경로 원장의 `origin_topic` 은 정의상 남의 토픽이라 `1 topic = 1 producer` 를 넘는다 | §D8 — 명시적 예외 + fence |

### A.3 착수 조건 — **전부 종료 (2026-09-02)**

1. D1~D7 ADR 확정 → ✅ [ADR-0020](../adr/0020-dlq-replay-contract.md) ([#98](https://github.com/Kimgyuilli/PeakCart/pull/98))
2. 브로커 retention 실설정 → ✅ ④-c-2b-0 ([#99](https://github.com/Kimgyuilli/PeakCart/pull/99))
3. ④-c-2a 머지 → ✅ ([#90](https://github.com/Kimgyuilli/PeakCart/pull/90))
4. `/plan task-impl4-c2b-dlq-replay` 재실행 → ✅ 이 문서 (§1~§10)
