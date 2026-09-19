# task-adr0020-dlq-replay-contract — 계획 리뷰 audit

## 2026-09-01 — 계획 리뷰 라운드 1
- 항목: 13건 (P0:0, P1:9, P2:4)
- 처리: 반영 13건 / 기각 0건
- 뒤집힌 전제 (전부 코드로 직접 확인):
  - **quarantine = eventId 판독 불가** → 거짓. quarantine 조건은 `failed_consumer_group=='__unknown__'`(`DlqOrigin:41-63`)이고 eventId 는 `DeadLetterRecorder:57` 이 group 과 독립 추출. 좌표 출처(`DlqOriginKind`)는 제3의 축 → 금지 축을 5개 독립 조건으로 재작성
  - **replay 는 ADR-0011 producer-owns-topic 과 충돌** → 오지목. ADR-0011 D2:71 은 NewTopic 프로비저닝 소유. 발행 권한 SSOT 는 ADR-0012 D4 + ADR-0018
  - **Kafka 트랜잭션(EOS)이 exactly-once 발행 대안** → 성립 안 함. DB↔Kafka 원자성을 주지 않음 → CDC 를 실제 3안으로 교체
  - 내 검증 설계의 false-green 1건: P4-4 가 "관측해서 사실대로 적으면 통과" 였음 → acceptance criterion 으로 격상
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-1788250729.json

## 2026-09-01 — 계획 리뷰 라운드 2
- 항목: 9건 (P0:0, P1:5, P2:4)
- 처리: 반영 9건 / 기각 0건
- **1R 수정이 만든 새 결함 4건** (이 라운드의 최우선 체크가 실제로 잡아냄):
  - P7: `RESOLVED` 를 무조건 추가하면서 선택지 ②가 그것을 부정 — **직접 모순**. 게다가 ②가 `REPLAY_PUBLISHED`(broker ack)를 terminal 로 삼아 N9 가 막으려던 조기 종결을 이름만 바꿔 재도입 → 발행 lifecycle(축 A) ↔ 사건 resolution(축 B) 분리
  - P5 `replay_deadline` 을 "원장 기준이든 원본 발생시각이든" 으로 열어둠 → 기준시각을 ADR 결정으로 격상(N12)
  - P6 마이그레이션을 outbox 기준 "3~4 DB" 로만 적음 → 원장은 채택안과 무관하게 **항상 4 DB**(order V6·product V5·payment V5·notification V3)
  - P4 "유한 retention.bytes 면 7일 전에 지워진다" 단정 → 값이 파티션별 유입량보다 작을 때만 참. 파티션별 하한 ↔ 디스크 하한 별도 증명으로 분리
- 그 외: N8 과 §5 자기모순([구조]/[변이] 판정 분리) · replay 재실패의 이중 원장(N11) · ADR-0012 처분이 채택안 종속 · Kafka 사용 서비스 4(User 는 `KafkaAutoConfiguration` 제외) · 빌드 모듈 10(`settings.gradle`)
- 자체 발견: ADR-0018 이 프로비저닝 소유와 `1 topic = 1 producer` 를 한 논증에 결합(`0018:48`·`:161`), replay 가 그 결합을 끊는 첫 사례. `0018:230` 의 "같은 규칙으로 항목 추가 = 부분 무효화 아님" 선례와 대조 필요
- 2R 이 확인한 것: P5 금지축 5종은 서로 독립이며 replay 가능 집합이 공집합이 아님 · P4-4 acceptance 는 달성 가능(선언된 config 만 incremental SET)
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-r2.json

## 2026-09-01 — 계획 리뷰 라운드 3 (상한)
- 항목: 15건 (P0:0, P1:12, P2:3)
- 처리: 반영 15건 / 기각 0건
- **2R 수정이 만든 새 결함** (이 라운드 최우선 체크가 잡아냄):
  - 축 A/B 를 **선언으로만** 분리하고 물리 모델에서 다시 합침 — P5 는 소비 재실패를,
    P7 은 발행 실패를 같은 `REPLAY_FAILED` 로 보내는데 원장은 `status` 컬럼 하나뿐
    (`DeadLetterRecord:98-105`) → N13 신설, `publication_status`/`resolution_status` 물리 분리
  - 선택지 ②(`REPLAY_PUBLISHED` 를 terminal 도 non-unresolved 도 아니게)에 **나가는 전이 부재** —
    purge 는 `DISCARDED` 만 대상이라 성공한 사건이 감소하지 않고 oldest-age 가 영구 고정
  - `replay_deadline` 을 새 행에서 계산 → 재발행분이 다시 DLT 로 가면 timestamp 리셋되어
    실패할 때마다 안전창 연장 (`OutboxPollingService:119-124` 가 timestamp 미지정, `DlqHeaders:54-64`)
  - N11 상관키를 "보존한다" 로만 기술 — `DeadLetterRecorder:25-29` 는 application header 를
    **전혀 저장하지 않는다**. 단순 숫자 원장 ID 면 타 DB 동명 ID 오갱신·위조 전이 표면
  - 새 행 처분 "링크만" 선택 시 조상 해소 범위 미정 → `root_record_id` + 원자적 전파
- 그 외: N11/N12 가 완료 게이트에 미연결 · `[측정]` 미정의 및 다수 행 유형 미표기(P4-7 이
  권한 실패를 주입하지 않고 통과) · §2.2-11 ↔ §2.3 모순 · `@Version` 과잉계약 · 목적지
  partition 불변식 부재(N14) · `retention 7d == dlq-replay-window 7d` 로 안전여유 0(N15) ·
  ADR-0012 **D5** 의 "새 eventId 발행" 대안과 충돌(`0012:98`, `StockReservationService` javadoc) ·
  **`hpx_plan_lint` 실제 실행 3건 실패**(필수 섹션 `목표/목적`·`영향 파일` 부재 + stable id 형식)
- 3R 이 확인한 것(반증 아님): 금지축 5종은 서로 독립이며 replay 가능 집합이 공집합 아님 ·
  N11 상관키와 `DLT_*` 배제는 본질적 충돌 아님 · §2.1 v~z 와 Spring Kafka incremental config
  경로는 직접 확인 범위에서 맞음
- 조치: 계획서 전면 재작성(lint 통과 구조로) — 명제 N1~N15 · §2.1 검증 30행(a~dd) ·
  P1~P8 stable id · §4 영향 파일 신설 · §6 검증 27행 전부 유형 표기 · §정정 이력 3라운드
- 검증: `hpx_plan_lint task-adr0020-dlq-replay-contract` → **OK**
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-r3.json

## 종료 판정 — 미수렴 종료 (사용자 판단)
- 라운드별: 13 → 9 → 15 (P1: 9 → 5 → 12). **매 라운드가 직전 라운드 수정이 만든 새 결함을 잡았다.**
- 수렴 조건(직전 라운드 새 계약 표면 무추가 + P1=0)을 채우지 못했다. 건수 추세로 종료를 판단하지 않았고,
  3R 상한 도달 시 사용자에게 선택지를 제시해 "전량 반영 후 종료 → ADR 작성" 을 받았다.
- 잔여의 성격: 계획서 자체의 모순·누락(축 물리분리 미이행·완료 게이트 미연결·유형 미표기·lint·
  §2.3 모순·D8 미등록)은 3R 개정에서 **전부 닫았다**. 남은 것은 상태 모델·상관키 구성·deadline 상속·
  root 전파처럼 **답 자체가 ADR 사안**인 표면이며, 계획서 역할은 그것을 결정 대상으로 등록하는 데까지다(§8-7).
- 관찰: 3R 지적의 무게중심이 계획서 구조에서 **내가 스케치한 잠정 답**으로 옮겨갔다. 계획서에 답을
  스케치할수록 리뷰가 그 스케치를 때리는 구조였다.

---

## 2026-09-01 — diff 리뷰 라운드 1
- 항목: 7건 (P0:0, P1:6, P2:1) · 처리: 반영 7건 / 기각 0건
- **자기 판정기준 불일치 1건**: ADR-0020 §D8-4 는 "규칙에 예외를 내는 것은 부분 무효화" 라고 판정해놓고
  ADR-0018 만 refine/Status 불변으로 뒀다. 근거로 "0018 의 규약은 새 도메인 이벤트 한정" 이라 적었으나
  **원문 `0018:49` 에 그런 한정이 없고**, 0018 Alternative A 의 기각 사유가 "규약 예외는 재논쟁을 부른다"
  인데 본 ADR 이 정확히 그 예외를 낸다 → ADR-0018 을 `Partially Superseded by ADR-0020` 으로 변경
- 그 외: `original_timestamp` NULL fallback 이 D1 보장 파괴 → replay 금지 · root 종결 후 늦은 자식 race
  → D6-2b 두 축 곱 전이표 + root 재개방 · "byte-for-byte" 범위 모순 → key/payload/eventId/timestamp 로 축소
  · `record_kind` nullable↔DEFAULT 충돌 → NULL=DOMAIN 해석 · "동작을 바꾸지 않는다" 거짓(retention.bytes -1→유한은
  동작 변경) + 구체값 확정 · runbook 의 "7일 넘기면 불가" 시간 단정 정정
- raw: .cache/codex-reviews/diff-task-adr0020-dlq-replay-contract-r1.json

## 2026-09-01 — diff 리뷰 라운드 2
- 항목: 6건 (P0:0, P1:5, P2:1) · 처리: 반영 6건 / 기각 0건
- **1R 수정이 만든 새 결함 (전부)**:
  - NULL 을 금지로 바꾸면서 **바로 앞 문장의 `occurred_at` fallback 을 남겨** 정반대 두 규칙이 공존.
    "5개 축" 표기와 6행 표 불일치 → 구조 검증이 축 누락을 못 잡는 상태
  - **용량 산정이 불건전**했다 — `retention.bytes` 는 닫힌 세그먼트만 지우는데 파티션 상한처럼 썼다.
    이미지 기본 `log.segment.bytes=1GiB`(`/opt/kafka/config/kraft/server.properties:128` 직접 확인),
    `offsets.topic.num.partitions` 미설정(기본 50) → 560MiB 산정 무효
  - `source_record_timestamp` 를 싣는 것만으로 보존 미보장(`LogAppendTime` 덮어쓰기·`before.max.ms` 거부)
  - root 재개방 + 자식 OPEN → incident 당 미결 2행. "누적되지 않는다" 가 거짓
  - 평문 상관 헤더 + owner/group 일치 검사로는 위조 방지 안 됨
  - contract 의 `NOT NULL DEFAULT 'DOMAIN'` 이 명시적 kind 계약을 다시 약화
- raw: .cache/codex-reviews/diff-task-adr0020-dlq-replay-contract-r2.json

## 2026-09-01 — diff 리뷰 라운드 3 (상한)
- 항목: 7건 (P0:0, P1:5, P2:2) · 처리: 반영 7건 / 기각 0건
- **2R 수정이 만든 새 결함 — 반대 방향 false-green 포함**:
  - **자식 과다집계를 고치다 기존 미결을 0으로 만들 뻔했다.** `root_record_id` 는 additive 라
    ④-c-2a 가 적재한 기존 행이 전부 NULL 인데 `root_record_id = id` 를 바로 걸면 **기존 미결이 전량 탈락**
    → expand(`IS NULL OR = id`) → backfill → 무결성 검증 → contract 순서 + 배포 전후 건수 동일 회귀 테스트
  - "420 MiB 로 bound" 가 여전히 과대주장 — retention 검사 주기·`file.delete.delay.ms`·index/timeindex·
    대형 batch 미포함 → **hard bound 주장 철회, 정상상태 목표치로 하향**
  - 상관 대조의 정본을 outbox 로 뒀는데 **수명 경쟁에서 진다**(PUBLISHED 는 7d 후 삭제, 미결 root 는 무기한)
    → 정본을 원장(`last_replay_attempt_id`)으로 이동
  - D3 컬럼 목록에 `replay_root_record_id`·`target_consumer_group` 이 없어 **대조할 정본이 부재**.
    실제 DLT group 대조 없이는 group 바꿔치기가 통과
  - 미래 timestamp 무조건 거부 ↔ `clockSkewBudget=5m` 모순 → `now + skew` 까지 허용하고 `now` 로 clamp
  - P4 기준선이 3개 config 만 검증 → `segment.*`·`message.timestamp.*` 미적용이 green 통과 → 7개 전부 + 개별 mutation
  - 축 6(NULL 금지)의 실제 영향 미측정 → NULL 비율 증적을 2b 산출물로 요구
- raw: .cache/codex-reviews/diff-task-adr0020-dlq-replay-contract-r3.json

## diff 리뷰 종료 판정 — 미수렴 (상한 도달)
- 라운드별: 7 → 6 → 7 (P1: 6 → 5 → 5). **매 라운드가 직전 라운드 수정이 만든 새 결함을 잡았다.**
- 스킬 상한 3회에 도달해 종료했다. 수렴 조건(새 표면 무추가 + P1=0)은 채우지 못했다.
- 잔여의 성격: 3R 지적은 전부 **ADR 문구·계약 정밀도** 였고 코드 산출물이 아니다. 실제 구현(④-c-2b)이
  이 계약들을 코드로 옮길 때 다시 검증된다 — 특히 `root_record_id` 전환 계약과 상관 대조 트랜잭션 경계.
- **이 PR 은 문서 전용(10파일 전부 .md)이라 런타임 회귀 위험이 없다**는 점이 상한 종료를 감당 가능하게 한다.

## 2026-09-01 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/98 (신규 생성, base=main)
- consistency precheck: **ok** (warnings 0) — 게이트 미노출
- 커밋: 3개 (재커밋 없음). **`a4ddf9d` 가 ADR·계획서·runbook·Layer1 혼재** — 스킬 규칙(ADR/계획서 별도 커밋)
  이탈이나 이력 재작성 대신 PR 본문 미충족 7번에 사실로 남겼다
- 갱신: `docs/TASKS.md` ④ 행의 ④-c-2b 항목(ADR-0020 확정·PR 링크·남은 P4) · `docs/progress/PHASE4.md` 신규 절
- 계획서 체크박스: P1·P2·P3·P5·P6·P7·P8 = `[x]`, **P4 는 의도적 미체크**(§5 PR 분할상 ④-c-2b-0 소관)
- 머지하지 않았다

---

## 2026-09-01 — P4 (PR ④-c-2b-0) diff 리뷰 라운드 1
- 항목: 5건 (P0:0, P1:3, P2:2) · 처리: 반영 5건 / 기각 0건
- **내 검증 도구가 자기대조였다 (P1 #2)** — `KafkaTopicConfigsTest` 가 SUT 상수를 그대로 기대값으로
  읽어, 업무 8/4 → 10/2 MiB 로 바꿔도 "절반 관계"·"총 420 MiB" 가 모두 유지되어 green 이었다.
  ADR 본문 값을 테스트에 **리터럴로 다시 적어 독립 정본**을 만들었다. 변이 실측으로 red 확인.
- **budget 0 이면 규칙이 무력화된다 (P1 #3)** — `@NotNull` 만 걸어서 `clockSkewBudget=0`·
  `cleanupSafetyBudget=0` 이면 `required == dlqReplayWindow` 가 되어 **D4-3 이 제거하려던
  7d == 7d 가 다시 부팅**된다. 양수 검증(`isBudgetsPositive`) 추가.
- **메커니즘 테스트가 YAML 배선을 증명하지 못했다 (P1 #1)** — 테스트가 `setModifyTopicConfigs()` 를
  직접 부르므로 base YAML 의 키를 지워도 green. 서비스별 YAML 배선 테스트를 따로 추가했다.
- **리플렉션이 전수가 아니었다 (P2 #4)** — `@Bean` 여부를 안 보고 `getDeclaredMethods` 만 썼다.
  `@Bean` 확인 + `getMethods()` 로 바꾸고, 수집기를 우회하는 `KafkaAdmin.NewTopics` 묶음 선언을
  금지하는 테스트를 추가했다. 놓치는 형태를 javadoc 에 명시.
- **기준선이 7종 중 4종만 단언했다 (P2 #5)** — 업무·dlq 각각 생성해 7종 값 + 정확한
  `ConfigSource(DEFAULT_CONFIG)` 를 전부 단언하도록 확장. 실측 기본값:
  `retention.ms=7d` · `cleanup.policy=delete` · `retention.bytes=-1` · `segment.bytes=1GiB` ·
  `segment.ms=7d` · `message.timestamp.type=CreateTime` · `before.max.ms=Long.MAX_VALUE`
- **변이 실측 4종 전부 red → 복원 후 green**:
  (a) 토픽 하나에서 `.configs(...)` 제거 → 계약 테스트 3건 FAILED
  (b) 안전여유 규칙을 등호 허용으로 되돌림 → validator 3건 FAILED
  (c) YAML 에서 `modify-topic-configs` 제거 → 배선 테스트 FAILED
  (d) 8/4 → 10/2 MiB(합 동일) → 독립 정본 대조 FAILED
- 계획 이탈 1건(구현 전 계획·ADR 을 먼저 수정): `message.timestamp.before.max.ms` 를 리터럴 8d 가
  아니라 **`app.idempotency.retention` 에서 유도**. 두 곳에 적으면 floor 변경 시 갈라진다.
- raw: .cache/codex-reviews/diff-adr0020-p4-r1.json

## 2026-09-01 — /ship (P4 · PR ④-c-2b-0)
- PR: https://github.com/Kimgyuilli/PeekCart/pull/99 (신규, base=main)
- #98 머지 후 origin/main 에 리베이스 — 스택 해소
- consistency precheck: **ok** (warnings 0)
- 커밋: 3개 — `feat(kafka)` / `test(kafka)` / `docs(adr0020)`. **분류 분리 지켰다**
  (직전 /ship 의 미충족 7번 "커밋 분류 혼재" 재발 방지 — push 전에 re-split 했다)
- 검증: **764 테스트 0 실패**(5모듈) · `hpx_plan_lint` OK · 변이 4종 red 실측
- 계획서 체크박스: **P1~P8 전부 [x]**
- 갱신: `docs/TASKS.md` ④ 행 ④-c-2b-0 항목 · `docs/progress/PHASE4.md` 신규 절
- 미충족: diff 리뷰 2R 미실행 · PVC 안전성 미증명 · 운영 클러스터 미적용
- 머지하지 않았다
