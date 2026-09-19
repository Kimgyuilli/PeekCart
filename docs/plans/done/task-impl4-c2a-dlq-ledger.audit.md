# ④-c-2 계획 리뷰 audit

## 2026-08-26 01:00 — 계획 리뷰 라운드 1
- 항목: 12건 (P0:0, P1:8, P2:4)
- 처리: **반영 12건 / 기각 0건**
- **뒤집힌 전제 3건** (전부 직접 검증 후 수용):
  1. `DeadLetterPublishingRecoverer` 가 consumer group 헤더를 안 붙인다 → **틀림**. `spring-kafka-3.3.14.jar` bytecode 실측: `HeadersToAdd.GROUP` 존재 + `whichHeaders = EnumSet.allOf(...)` 기본값. 초안의 §2.3 "핵심 발견"과 P1(커스텀 헤더 주입)을 통째로 삭제
  2. 원장 식별자 `(topic, eventId, group)` → **eventId 없는 DLQ 가 대표 입력**(`KafkaMessageParser:29` 이 eventId 누락·JSON 파싱 실패를 예외로 던짐). `(topic, partition, offset, group)` 으로 교체, eventId 는 nullable 보조키
  3. payload 만 보관 → `originalKey` 없이 재발행하면 파티션 순서가 깨져 **ADR-0012 D2/D4 와 충돌**
- 그 외: listener 실패 offset 시맨틱 미정의 · replay 진입점 부재 · 구독 매트릭스 미고정(ADR-0018 `.dlq` 3종 누락 위험) · 원장 무제한 증가 · 동시 INSERT rollback-only · payload 마스킹 · `:common` 배치 미결 · 알림 소유권 이전의 관측 공백
- 범위 변화: P8 → P12, §4 구독 매트릭스 신설. **P13 을 "완료" → "구현 완료 · EXPLAIN 증적 잔여" 로 하향**(부모 §5 는 실행계획을 요구하는데 기존 테스트는 limit 만 검증)
- raw: `.cache/codex-reviews/plan-c2-1787673382.json`

## 2026-08-26 01:09 — 계획 리뷰 라운드 2
- 항목: 8건 (P0:0, P1:7, P2:1)
- 처리: **반영 8건 / 기각 0건**
- **8건 중 7건이 라운드 1 수정이 만든 새 결함이다.** 수렴 규칙(새 계약 표면 무추가 + P1=0)대로 1라운드 종료를 안 한 것이 옳았다:
  1. **replay 2단 상태머신이 "중복 재발행 0" 을 보장 못 함** — 발행 성공 직후 사망 시 `REPLAY_REQUESTED` 만 남아 발행 여부 판별 불가 → **기존 `outbox_events` 재사용**(4서비스 이미 보유). ④-c-1a 의 "진입점/실행자 분리" 와 같은 패턴
  2. **마스킹·절단(P9) ↔ replay 원문 충실도(P2) 정면 충돌** → 역할 분리: 원장 payload = 진단용 / replay 원본 = 원본 토픽 좌표(retention 이내). 창 초과는 **복구 불가**로 명시
  3. **group 헤더 누락 시 소유권 판정 불능** — 4서비스가 전부 저장하거나 전부 skip → 원본 토픽 발행 서비스를 **단일 quarantine 소유자**로 지정 + `__unknown__` sentinel
  4. **"(topic,partition,offset,group) 은 전역 유일" 이 토픽 재생성 시 거짓** — offset 0 재사용으로 과거 행과 충돌, `INSERT IGNORE` 가 **새 실패를 조용히 폐기** → `cluster_id` + `topic_generation` 추가. (offset reset·파티션 재할당은 좌표 유지라 문제 없음 — 리뷰가 이 구분도 확인)
  5. **listener 실패 시맨틱이 "명시" 가 아니라 구현 시점으로 이연됨** + "DB 장애 offset 미커밋" 과 "poison record 무기한 비차단" 은 동시 만족 불가 → 예외 분류별 종착 3분기 표로 확정(§2.6-D)
  6. **`DLT_ORIGINAL_KEY` 는 존재하지 않음** — jar 실측으로 확인(`DLT_*` 13개 중 없음, `DLT_KEY_EXCEPTION_*` 는 역직렬화 예외용). 원본 key 는 DLQ `ConsumerRecord` 자신의 key → `DlqHeaders` 입력을 `Headers` → **`ConsumerRecord<?,?>`** 로 변경
  7. **적재 알림 at-least-once 가 구현 수단 없이 선언됨** → **best-effort 로 하향**. 내구적 신호는 원장 행 자체 (ADR-0018 D6 정합). Slack outbox 는 만들지 않음
  8. (P2) `OPEN` 보존 계약 자기모순 — "무제한 증가 불허" ↔ "cleanup 후 OPEN 항상 보존" → `OPEN` 무삭제 + **age/건수 경보**로 분리
- **확인된 것**: §4 구독 매트릭스가 운영 코드의 **listener 21개**(Order 6 · Product 5 · Payment 5 · Notification 5)와 group 문자열까지 일치
- raw: `.cache/codex-reviews/plan-c2-r2.json`

## 2026-08-26 01:20 — 자체 정정 (3R 대기 중)
- §2.6-C quarantine 소유자 규칙이 **구현 불가**임을 §4 대조로 자체 발견. 발행 서비스는 자기 토픽을 소비하지 않아 자기 `.dlq` 를 구독하지 않는다 → 지정된 소유자가 그 레코드를 볼 수 없다.
- 수정: quarantine 전용 구독 표 + `P4b` 신설 (별개 listener, group 있으면 skip). 3R #9 가 이 수정이 정합함을 확인.

## 2026-08-26 01:26 — 계획 리뷰 라운드 3
- 항목: 10건 (P0:0, P1:7, P2:3) — **미수렴**
- **뒤집힌 전제 3건** (전부 코드로 직접 확인):
  1. **"4서비스 전부 outbox 보유" 가 거짓.** `notification-service` 에 `global/outbox/` 가 **없고**, `outbox_events` 마이그레이션도 없으며, `application.yml:58` 이 "소비 전용(outbox 미소유)" 라고 명시한다. ADR-0012 D1 도 Notification DB 에는 `processed_events` 만 둔다. **내 검증 실패** — `ls` 를 order-service 한 곳에만 돌리고 4서비스로 일반화했다
  2. **outbox 를 써도 "중복 발행 0" 은 여전히 거짓.** `OutboxPollingService:83-86` 이 broker ack 를 기다린 뒤 **별도로** `markPublished()`+`save()` 한다 → 그 사이 사망 시 `PENDING` 잔존 → 재발행. ADR-0018 도 outbox 를 at-least-once 로 규정. **같은 주장이 2R(#1 2단 상태머신)에 이어 두 번째로 반증됨**
  3. **`OutboxEvent` 모델이 replay 충실도를 표현 못 함.** `buildRecord` 가 `new ProducerRecord<>(eventType, null, aggregateId, payload)` — **partition 은 null 고정**, key 는 `aggregateId`(String 50자 상한), 헤더는 trace/user 뿐. 원본 partition 지정·임의 key·원본 헤더 보존 불가
- 그 외 P1: replay 원문을 읽을 **reader 컴포넌트 부재** + `kafka-topic-retention=7d` 는 멱등 가드용 선언값일 뿐 **실제 토픽에 `retention.ms`/`cleanup.policy` 미설정**(NewTopic·compose·k8s 전부) / P9 "마스킹되면 replay 거부" 가 §2.6-B(replay 원본=좌표)와 **자기모순** / malformed 시 topic·partition·offset 헤더까지 없으면 **물리 식별자 부재** / `REPLAY_REQUESTED → RESOLVED` **종결 실행자 미정의**(poller 는 원장을 모름)
- P2: `cluster_id`/`topic_generation` 운영 계약(프로퍼티·fail-fast·bump 순서) 부재 / "나머지 3곳 0행" 은 **cross-service 검증인데 E2E 는 범위 밖**이라 과장 / OPEN 경보 임계값·cooldown·전달수단 부재
- **확인된 것**: quarantine 매트릭스는 자체 정정 후 정합
- raw: `.cache/codex-reviews/plan-c2-r3.json`

## 2026-08-26 01:35 — 분할 결정 (3R 반영)
- 라운드별 건수 **12 → 8 → 10** — 감소하지 않음. 각 라운드의 수정이 새 계약 표면을 만들어 다음 라운드 결함을 낳는 패턴.
- 2R 시점에는 "replay 에 몰렸다"는 근거가 없었으나(8건 중 2건), **3R 은 10건 중 6건이 replay 경로**(#1·#2·#3·#4·#5·#7). 데이터가 바뀌었다.
- **"재발행 중복 0" 이 2라운드 연속 반증** — 2단 상태머신(2R)도, outbox 재사용(3R)도 같은 crash window 를 갖는다. 계획서 수정이 아니라 **설계 결정**이 필요한 표면.
- → **④-c-2a**(원장 적재, 본 계획, 즉시 착수) / **④-c-2b**(replay, ADR 선행) 로 분할.
  - 파일: `task-impl4-c2a-dlq-ledger.md` · `task-impl4-c2b-dlq-replay.md`
- 3R 결함의 2a 귀속분 4건 반영: `DLQ_ORIGIN` origin 종류 분리(#6, 6컬럼 NOT NULL) · `cluster_id`/`topic_generation` 운영 계약 fail-fast(#8) · "정확히 1곳" 증명 범위 축소 명시(#9) · OPEN 경보 임계값/cooldown/채널 + actuator 조회 표면(#10)
- 2a 는 `RESOLVED` 를 쓰지 않는다 — 종결은 `ACKED`/`DISCARDED` 까지. 상태 컬럼을 `VARCHAR` 로 두어 2b 의 additive 확장을 막지 않는다.

## 2026-08-26 08:40 — 구현 중 발견 (자체)

### 1. `topic-generations` 조회가 `DLQ_ORIGIN` 경로에서 깨졌다
계획서는 "키는 원본 토픽 이름" 이라고만 적었는데, `DLQ_ORIGIN` 행은 좌표로 **`.dlq` 토픽 이름**을 싣는다 → 미등록 토픽 예외. 통합테스트가 잡았다.
→ `.dlq` 는 원본과 함께 선언·재생성되므로 **원본의 세대를 따르도록** 했다(정확 일치 우선 → 없으면 `.dlq` 제거 후 재조회). 설정이 두 배가 되는 것을 피하면서, `.dlq` 만 따로 재생성한 경우 명시 항목으로 override 가능.

### 2. runbook 이 도메인 가드를 우회하도록 지시하고 있었다
초안 runbook §4 는 종결을 `UPDATE dead_letter_records SET status=...` 로 안내했다. 그러면 `DeadLetterRecord#discard` 의 **"사유 필수" 가드와 전이 규칙이 통째로 우회**되어, 내가 쓴 가드가 코드에만 있고 운영에는 없는 장식이 된다. P12 리허설도 "SQL 이 돌았다" 만 증명하는 false-green 이 됐을 것이다 — 3R #7 이 replay 에 대해 지적한 것과 **같은 구조**를 2a 종결 경로에서 내가 다시 만들었다.
→ `DeadLetterEndpoint` 에 `@WriteOperation` 종결 진입점 추가(인증 뒤, 도메인 메서드 경유, `actor` 필수). runbook §4 를 curl 로 교체하고, 리허설 테스트가 그 진입점만 호출하도록 했다.

### 3. quarantine 매트릭스 자체 정정 (3R 대기 중 발견 · 3R #9 가 정합 확인)
발행 서비스는 자기 토픽을 소비하지 않아 자기 `.dlq` 도 구독하지 않는다 → 지정된 quarantine 소유자가 그 레코드를 볼 수 없었다. quarantine 전용 구독(`P4b` → 최종 `P7`)을 별도 listener 로 신설.
