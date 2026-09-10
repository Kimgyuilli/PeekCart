# audit — `task-impl4-c2b-dlq-replay` 계획 리뷰

> 계획서: `docs/plans/task-impl4-c2b-dlq-replay.md`
> 정정 내용(무엇이 왜 틀렸나)은 계획서 §11 에 있다. 여기에는 **라운드별 집계와 raw 경로**만 남긴다.

---

## 2026-09-02 — 계획 리뷰 라운드 1

- 항목: **16건** (P0:0, P1:15, P2:1)
- 처리: **반영 16건 / 기각 0건**
- 뒤집힌 전제:
  - deadline clamp 를 `now + clockSkewBudget` 로 적었으나 ADR-0020 §D5-3 은 **`now` 로 clamp** 를 명시 — 초안이 ADR 과 충돌
  - "`save` 스텁 예외 1회로 중복 발행 관측" — `OutboxPollingService:116` 이 **같은 객체를 다시 save** 하므로 재발행이 일어나지 않는다. 검증이 주장을 관측하지 못하는 설계였다
  - "E2E 무영향" — `scripts/e2e/saga_e2e.py:64-68` 이 Flyway **버전 집합 정확 일치**를 readiness 에서 강제한다
  - `outbox_event_id` 를 조건부 UPDATE 조건에 넣음 — auto-increment 라 INSERT 후에만 존재
- 구조 변화: PR 순서를 **뒤집었다** — 상관·재개방을 진입점보다 먼저 배포 (2b-3 ↔ 2b-4)
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788277701.json`

## 2026-09-02 — 계획 리뷰 라운드 2

- 항목: **14건** (P0:0, P1:9, P2:5) — 이 중 **8건이 1R 수정이 만든 새 결함**
- 처리: **반영 14건 / 기각 0건**
- 뒤집힌 전제:
  - `AND publication_status <> 'REQUESTED'` — SQL 에서 `NULL <> 'x'` 는 **UNKNOWN** 이라 `NULL` 행(절대다수)이 종결 불가가 된다. ADR §D6-2b 표와 정면 충돌
  - claim 이 publication 축만 검사 — **resolve 선점** 순서에서 `REQUESTED` + terminal 조합이 생긴다
  - `record_kind=REPLAY` 를 "attempt 기록 존재" 로 치환한 것이 **동치가 아니다**
  - outbox cleanup 이 replay 행을 지워 root 가 `REQUESTED` 에 **영구 고착**
  - V-1 partition 변이가 **검출되지 않는다**(같은 key → 같은 파티션)
- 확인된 것: 2b-3 선배포 순서는 성립 · P5/P15 는 root 잠금으로 직렬화되어 무한 전이 없음
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788278544-r2.json`

## 2026-09-02 — 계획 리뷰 라운드 3

- 항목: **10건** (P0:0, P1:7, P2:3) — 이 중 **6건이 2R 수정이 만든 새 결함**
- 처리: **반영 10건 / 기각 0건**
- 뒤집힌 전제:
  - claim 을 root 에 걸면 **두 번째 replay 가 구조적으로 불가능** — 첫 성공이 root 를 `PUBLISHED` 로 만들고 claim 은 `NULL`/`PUBLISH_FAILED` 만 받는다. V-16 의 "3회 replay" 가 성립하지 않았다
  - outbox **부재를 `PUBLISH_FAILED` 로 강등**하면 이미 발행된 사건을 실패로 감사 기록한다 (2R 이 넣은 fallback 을 3R 이 철회)
  - `replay_deadline` 의 **durable writer 가 없었다** — transient 계산으로도 검증이 green
  - V-2 는 상속 때문에 변이를 검출하지 못하고, V-6 은 소비 멱등이 깨지지 않아 관측이 성립하지 않는다
  - kill-switch 가 "즉시" 차단된다 — Spring 정적 설정은 재기동이 필요하다
- 구조 변화: **target row ↔ 상관 앵커(root) 분리** · 10토픽 초기 정책표를 계획서에 확정 · `scripts/replay-drain-preflight.sh` 신설 · ADR-0020 Update Log 기재 항목 추가
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788279282-r3.json`

---

## 수렴 판정

**3라운드 종료 시점에서 수렴하지 않았다.** 종료 조건은 *직전 라운드가 새 계약 표면을 추가하지 않았고 P1 = 0*
인데, 3R 은 P1 7건이었고 그 반영이 새 계약 표면(target row 모델 · deadline 영속 계약 · 초기 정책표 ·
preflight 스크립트 · ADR Update Log)을 다시 만들었다.

`/plan` 의 라운드 상한은 3회다. 건수 추세("16 → 14 → 10 이니 됐다")로 종료를 판정하지 않는다 —
3라운드 모두 **직전 라운드 수정이 만든 새 결함**을 실제로 잡아냈다.

**종료 결정 (2026-09-02, 사용자)**: 상한에서 종료하고 구현에 착수한다. **수렴해서 끝난 것이 아니라
상한에서 끊은 것**이며, 3R 반영이 만든 새 계약 표면 — target row ↔ 상관 앵커 분리(P21) ·
`replay_deadline` 영속 계약(P21 2b) · 10토픽 초기 정책표(P19) · `replay-drain-preflight.sh`(P24) —
은 **계획 리뷰를 거치지 않았다**. 이 표면들은 각 PR 의 **diff 리뷰가 첫 검토 지점**이 되므로,
해당 PR 본문과 진행 기록에 그 사실을 명시한다.

---

## 2026-09-02 — diff 리뷰 (PR ④-c-2b-1, P1~P7)

### 라운드 1 — 5건 (P0:0, P1:2, P2:3)
- 처리: **반영 5건 / 기각 0건**
- **리뷰가 내 코드의 실제 버그 2건을 잡았다.** 둘 다 뿌리가 같다 — **JPA 1차 캐시가 잠금 후 재검사를 무력화**한다:
  - `findPurgeable` 이 root 를 엔티티로 먼저 읽어 영속성 컨텍스트에 적재 → 뒤의 `SELECT ... FOR UPDATE` 는
    잠금만 얻고 **상태를 refresh 하지 않는다**. 재개방이 끼어들면 purge 가 캐시의 과거 terminal 상태로
    통과해 **살아 있는 incident 를 삭제**한다 → `findPurgeableRootIds`(id projection)
  - `transition` 도 같은 구조 → `findRootIdOf`(id projection). 두 요청이 OPEN 을 읽고 A 가 `RESOLVED` 를
    커밋하면 B 가 캐시의 OPEN 을 보고 **`DISCARDED` 로 덮어쓴다**
- 그 외: backlog 의 `publication` 이 `NULL`(요청 없음)을 누락 · replay-axis parity 가 정규화 해시라 주석 drift 통과 ·
  self-test 4 가 self-test 3 의 변조를 이어받아 **001 을 지워도 006 때문에 통과**하는 false-green
- 변이 **M6**(요청 행 엔티티 선읽기로 회귀) red 확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r1-1788341444.json`

### 라운드 2 — 5건 (P0:0, P1:2, P2:3) — **P1 2건 전부 1R 수정이 만든 새 결함**
- 처리: **반영 5건 / 기각 0건**
- 1R 수정이 만든 새 결함:
  - id projection 이 root 의 캐시 문제는 없앴지만 **비잠금 조회가 REPEATABLE READ 스냅샷을 먼저 연다**.
    root 는 current read 인데 자식은 스냅샷을 봐서, 앞선 트랜잭션이 root+자식을 종결한 뒤
    **root 에선 no-op 하면서 자식만 덮어쓰는** 상태가 된다 → `findChildrenForUpdate`
  - 신설 경합 테스트가 `Thread.sleep(500)` 으로 잠금 대기를 **추정** → 느린 CI 에서 결함 변이를 확률적으로 놓친다
    → InnoDB `LOCK WAIT` 실관측
- 그 외: backlog 5회 집계가 각각 별도 트랜잭션(합 불변식 미보장) · `CASE WHEN ... IS NULL` 의 NULL 분기 미검증 ·
  "원문 바이트 해시" 가 실제로는 text mode(개행 drift 통과)
- 변이 **M7**(자식 잠금 제거) · **M8**(CASE WHEN 제거) red 확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r2-1788343763.json`

### 라운드 3 — 2건 (P0:0, **P1:0**, P2:2)
- 처리: **반영 2건 / 기각 0건**
- `findChildrenForUpdate` 가 terminal 자식까지 잠가 대기 집합만 키움 → `status IN ('OPEN','ACKED')` 필터 ·
  `awaitLockWait()` 가 **컨테이너 전체 건수**만 봐 무관한 트랜잭션에도 latch 가 풀림 → 커넥션 id 로 대상 특정
- 리뷰가 확인해 준 것: P5·P15·purge 가 전부 root→자식 순서라 **잠금 순환 없음** · actuator readOnly 트랜잭션 ·
  Testcontainers 전용 root 접속 · 바이트 parity 해시에 결함 없음
- 변이 **M7b**(활성 필터 유지한 채 잠금만 제거) red 재확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r3-1788346508.json`

### 수렴
**3라운드에서 P0/P1 = 0 이고 반영한 2건이 새 계약 표면을 만들지 않았다**(잠금 범위 축소 · 테스트 관측 정밀화).
`/work` 의 재리뷰 조건은 "P0/P1 을 실제로 수정했을 때"이고 3R 은 P2 만 고쳤으므로 여기서 종료한다.

### 변이 검증 종합 (8종 전부 red → 복원 후 green)
M1 집계 root 조건 제거 · M2 `IS NULL` 분기 제거 · M3 purge 를 `COALESCE` 로 회귀 · M4 자식 id 의 root 정규화 제거 ·
M5 purge 가 자식을 남김 · M6 요청 행 엔티티 선읽기 · M7/M7b 자식 잠금 제거 · M8 `CASE WHEN` 제거.

> **M3 은 처음에 green 이었다** — purge 의 `COALESCE` 회귀 테스트가 잠금 후 인메모리 재검사에 가려 vacuous 했다.
> 쿼리 계약을 직접 단언해 red 로 만들었다. 계획 §1 **N17**(자기대조 금지)이 겨냥한 유형이 실제로 나왔고,
> **변이 검증이 없었으면 그대로 통과했다.**

---

## 2026-09-02 — /ship (PR ④-c-2b-1)

- PR: **[#100](https://github.com/Kimgyuilli/PeakCart/pull/100)** (머지 안 함)
- consistency precheck: **ok** (warnings 0) — 게이트 미노출
- 커밋 6개 (분류별 분리, mixed 0): `feat(deadletter)` · `test(deadletter)` · `chore(lint)` · `docs(impl4-c2b)` ×3
- 검증: **918 tests 0 failed** · parity lint 본체 + self-test 10종 · 변이 8종 red
- 갱신: `docs/TASKS.md`(④-c-2b 를 🔲 → 🔄 4분할, 2b-1 ✅ #100) · `docs/progress/PHASE4.md`(작업 이력 + 미충족 5건)
- Skipped findings: **없음** (계획 40건 · diff 12건 전량 반영, 기각 0)

## 2026-09-02 22:26 — 계획 리뷰 (④-c-2b-2) · **리뷰 미실행**

- **착수 전 코드 검증은 완료** — C-1~C-13 을 계획서 §5 "PR ④-c-2b-2 / 착수 전 코드 검증" 표에 기록.
  뒤집힌 전제 5건(C-3 범위 2→4곳 · **C-5 outbox parity lint 부재** · **C-6 DELETE+LIMIT alias 불가** ·
  C-8 `cleanup.cron` 미선언 · **C-12 ADR-0012 D1 표 미갱신**) → P9-b·P9-c 신설, P9·P12 정정, V-30~V-33·R8 추가.
- **Codex 리뷰 3회 시도 전부 실패**:
  1. `plan-task-impl4-c2b-dlq-replay-2b2-r1-1788355609` — 10분 조사 후 **호출측 타임아웃으로 중단**(결과 없음). exec 다수 관측.
  2. `plan-2b2-r1b-1788356291` — exec 6회(계획서·ADR 만 읽음) 후 `items: []`. summary 는 *"…대조하겠습니다"* 라는 **예고문**.
  3. `plan-2b2-r1c-1788356371` — **exec 0회**, 즉시 `items: []` + 동일한 예고문.
  → 실패 양상: `--output-schema` 응답을 **조사 착수 전에 확정**해 버린다. codex-cli 0.152.1.
- **처리**: 반영 0건 / 기각 0건 (리뷰 산출물 없음). **P0/P1 = 0 이 아니라 "미측정"이다** — 자동 통과로 간주하지 않는다.
- raw: `.cache/codex-reviews/plan-2b2-r1{,b,c}-*.json` (+ `.stderr`)

## 2026-09-02 22:52 — 계획 리뷰 (④-c-2b-2) 라운드 1 · **분할 재시도**

긴 단일 프롬프트가 조기 종료를 유발한다고 보고 A(코드 사실 반증) / B(누락·부작용)로 나눠 호출.

- **A 성공** (exec 30회) — 2건(P0:0, P1:0, **P2:2**), **전량 반영**:
  - A#1 → C-3 의 stale 주석 범위가 **4곳 → 6곳**. `notification-service/build.gradle:27-28`·`:31-32` 추가.
    그리고 `NotificationApplication:12` 는 **P9 를 기다릴 것 없이 이미 거짓**이다(`DeadLetterMaintenanceScheduler:58·114`
    가 이미 `@Scheduled` 2개를 얹었다) — 선재 결함으로 재분류하고 같은 PR 에서 고친다.
  - A#2 → **내 C-6 정정이 틀렸다**. "단일 테이블 DELETE 에 alias 를 못 붙인다" 는 거짓이고,
    MySQL 8.0.16+ 는 `DELETE FROM tbl [[AS] alias] … LIMIT` 을 지원한다.
    **`mysql:8.0.46` 컨테이너로 직접 실측**해 반증을 확인하고 매트릭스 4행을 계획서에 기록:
    `AS o`+LIMIT **성공** / 초안(alias 미선언 `o.id`) **ERROR 1054** / 정규명 **성공** / multi-table+LIMIT **ERROR 1064**.
    → 초안 SQL 이 깨진다는 **결론은 유지**되나 이유가 다르다(alias 를 선언한 적이 없을 뿐).
    정규명을 택하는 근거를 "문법 제약" → "4서비스 byte 동일 복제의 diff 최소화" 로 정정.
- **B 실패 2회** (`plan-2b2-B-*`: exec 36회 후 `items: []` · `plan-2b2-B2-*`: exec 35회 후 `items: []`).
  둘 다 summary 가 *"…대조 중입니다"* 라는 진행형 예고문 — A 와 동일 조건에서 B 만 재현되는 조기 종료.
  → **3회째 동일 재시도 대신 스윕을 직접 수행**하고 결과를 C-14 로 기록했다. 결론: **갱신 필요 표면 0건**
  (ci.yml·k8s·notification 프로파일 yml·promql lint·ShedLock 락 이름·runbook 전부 무영향, 근거는 C-14 에 인용).
  B 축의 나머지(V-30~V-33 의 false-green 저항성)는 **제3자 미검토로 남는다** — 미충족에 명시.
- 처리: 반영 2건 / 기각 0건. raw: `.cache/codex-reviews/plan-2b2-{A,B,B2}-*.json`

## 2026-09-03 00:20 — diff 리뷰 (④-c-2b-2) · **Codex 미실행 (usage limit)**

- **시도 3회 전부 산출물 없음**. 1·2회는 `items: []` + *"…대조하겠습니다"* 예고문, 3회에서 원인이 드러났다:
  `ERROR: You've hit your usage limit ... try again at 3:00 AM` (codex-cli 0.152.1).
  → **앞선 두 번의 빈 응답도 프롬프트 문제가 아니라 같은 원인**이었을 가능성이 높다. 계획 리뷰 A/B 단계의
  빈 응답도 같은 신호였던 것으로 재해석된다(A 만 성공한 것은 그 시점에 잔여 quota 가 있었기 때문).
- **처리**: 반영 0건 / 기각 0건. **P0/P1 = 0 이 아니라 미측정**이다.
- **대신 수행한 것 (제3자 리뷰의 대체가 아님을 명시)**:
  · 자체 리뷰에서 **실제 결함 1건 발견·수정** — `DeadLetterPublicationReconciler` 를 4서비스 byte 동일로
    복제해 놓고 `dead-letter-schema-parity-lint` 의 `java_files` 목록에 더하지 않았다. 목록에 없으면
    4벌이 갈라져도 아무 것도 실패하지 않는다 — ④-c-2b-1 이 glob 을 안 넓혀 겪은 것과 **같은 구멍**을
    신규 파일에서 재현한 것이다. 목록 추가 + self-test 9b 신설 + 변이(M-6) red 로 고정.
  · **변이 검사 12종 전부 red** (아래 검증 절 참조)
- raw: `.cache/codex-reviews/diff-2b2-r1{,b,c}-*.json` (+ `.stderr`)

### 검증 (④-c-2b-2)

**모듈별 테스트** — 전 모듈 0 실패. 로컬 전체 스위트가 한 번에 완주하지 못해(장시간 백그라운드 잡이
반복 중단) **모듈 단위로 나눠 실행**했고, 각 모듈의 결과는 변경 이후 시점의 것이다:

| 모듈 | tests | 실패 |
|---|---|---|
| common | 73 | 0 |
| order-service | 317 | 0 (신규 7) |
| product-service | 186 | 0 |
| payment-service | 168 | 0 |
| notification-service | 45 | 0 (신규 5) |
| peekcart-common-auth | 52 | 0 |
| user-service · gateway | 61 · 80 | 0 |

**변이 검사 12종 전부 red** (복원 시 green):

| # | 변이 | red 가 된 검증 |
|---|---|---|
| M-A | `isReplay()` 를 `!= DOMAIN` 으로 (NULL 이 replay 가 됨) | V-30 |
| M-B | reconciler 가 `PENDING` 도 종착 | V-33 |
| M-C | outbox 부재를 `PUBLISH_FAILED` 로 강등 | V-21d |
| M-D | cleanup 의 `NOT EXISTS` 제외 조건 제거 | V-21b |
| M-E | replay 경로가 `source_record_timestamp` 미탑재 | replay 좌표 |
| M-F | notification poller 의 실제 발행 제거 | **V-32 가 "배선됐다" 판정이 아님을 실증** |
| M-1~M-5 | lint 의 OUTBOX-PARITY-002/004/005/007/009 검사 각각 제거 | self-test 10~15 |
| M-6 | `java_files` 목록에서 reconciler 제거 | self-test 9b |

> **M-4 는 1차 시도에서 GREEN 으로 보고됐으나 실제로는 변이가 적용되지 않은 것**(내 변이 스크립트의
> anchor 불일치)이었다. 앵커를 고쳐 재실행하니 red. **변이 하네스 자체의 false-green** 이므로 기록한다.

**lint 7종 green**: `dead-letter-schema-parity`(self-test **17종**) · `kafka-subscription-contract` ·
`observability-promql` · `observability-ssot` · `ci-test-matrix` · `saga-contract-matrix` · `e2e-network-contract`.

**실행 중 정정 3건**:
1. `NotificationCleanupMatrixIntegrationTest` 가 "notification 은 outbox cleanup 부재" 를 단언하고 있었다 —
   ADR-0020 D2 가 바꾼 계약이라 갱신하고, "테이블만 있고 아무도 발행하지 않는" 상태가 통과하지 않도록
   poller·reconciler bean 검사를 더했다. **계획 §8 이 이 파일을 예상하지 못했다.**
2. at-least-once 단언이 "정확히 2개" 였는데 실측 3개 — 배경 poller 도 같은 행을 집어간다. ADR-0020 D1 은
   중복 수에 상한을 두지 않으므로 **계약이 말하지 않는 것을 테스트가 주장**하던 것이고 flaky 였다.
   `≥2 + 전부 동일 payload + offset 중복 없음` 으로 정정.
3. cleanup 2회 호출 사이에 `lockAtLeastFor(PT1M)` 를 만료시키지 않아 두 번째가 통째로 건너뛰어졌다 —
   "제외 조건이 계속 막고 있다" 와 "잡이 안 돌았다" 가 구분되지 않는 상태였다.

**미충족**:
1. **Codex diff 리뷰 미실행** (usage limit, 3:00 AM 리셋) — P0/P1 = 0 이 아니라 **미측정**
2. **로컬 전체 스위트 1회 완주 없음** — 모듈별 그린을 합산한 것이다. CI 에서 확인 필요
3. 신규 테스트는 order·notification 에만. product/payment 는 byte 동일 복제 + parity lint 로 대체
4. replay 행은 **fixture 로만** 생성 — 진입점은 ④-c-2b-4
5. 운영 클러스터 미적용 · E2E(`saga_e2e.py`) 로컬 미실행

### 추가 — parity lint `DLQ-PARITY-014` (계획에 없던 확대)

self-test 9b(“reconciler drift 를 잡는가”)는 **내가 기억한 그 파일 하나만** 본다. 다음 신규 복제본에는
아무 도움이 안 되므로, 목록 자체를 검사가 지키도록 `DLQ-PARITY-014` 를 넣었다 —
*지금 4벌이 byte 동일한데 `java_files` 에 없는 파일*을 위반으로 본다.

- **디렉토리 전체 일치를 요구하지 않는다**: `DeadLetterConsumer`/`KafkaConfig`/`QuarantineConsumer` 는
  토픽·group 이 서비스마다 정당하게 다르다. 첫 구현이 이 구분을 놓쳐 self-test 1 이 red 였고, 그것이
  설계를 정정하게 했다.
- **부수 발견**: 기존 미등록 복제본 2개(`DeadLetterContainerGuard`·`DeadLetterKafkaConfig`, ④-c-2a 산출물)를
  찾아내 목록에 편입했다. **계획에 없던 소폭 확대**이며, allowlist 로 덮는 대신 편입한 이유는 그것들이
  실제 복제 자산이기 때문이다("의도적으로 무방비" 라는 거짓을 기록하지 않는다).
- **fixture 오염 2건도 함께 고쳤다**: ① `seed_fixture` 가 per-service 파일까지 order 사본으로 채워
  fixture 안에서만 byte 동일해지던 것 ② `seed_fixture` 가 `$TMP` 를 지우지 않아 9c 가 심은 파일이
  뒤 케이스를 red 로 만들던 것. **fixture 가 현실을 왜곡하면 self-test 가 검사하는 대상이 현실이 아니다.**
- 변이 **M-7**(014 검사 제거) red · **M-8**(byte 동일 조건 제거 → per-service 파일 오탐) red.
  양방향을 다 잡는다. self-test **18종** 통과.

## 2026-09-03 01:10 — `/ship` (④-c-2b-2)

- **PR**: https://github.com/Kimgyuilli/PeakCart/pull/102
- **precheck**: `ok` (warnings 0) — 자동 통과
- **커밋 6개** (한 커밋 = 한 분류): `feat(outbox)` · `feat(deadletter)` · `test(outbox)` · `chore(lint)` ·
  `fix(adr)` · `docs(impl4-c2b)`. ADR 과 계획서는 별도 커밋.
- **갱신**: `docs/TASKS.md`(④ 행에 #102 + 범위 변화·미충족 기록) · `docs/progress/PHASE4.md`(작업 이력 + 미충족 7항목)
- **편입 부채**: 없음
- **머지하지 않았다.** **diff 리뷰가 미측정 상태**로 남아 있으므로 머지 전 리뷰를 권한다.

## 2026-09-03 16:40 — CI 실패 대응 (④-c-2b-2, PR #102)

- **CI**: `test (order-service)` 만 실패 — `317 tests, 1 failed` (`OutboxAtLeastOnceIntegrationTest:105`).
  lint·guards·나머지 5 test job pass. `gate` 실패는 전파, e2e/images/publish 는 그로 인한 skip.
- **운영 코드 결함 0.** 전부 내 테스트 하네스 결함이며 4건이었다. 상세·반증된 가설 4개·내가 틀렸던 추론
  2건은 계획서 §5 “CI 후속” 에 기록.
- **핵심 증거**: 실패 시 `brokerEndOffsets` 전부 0 → 소비 실패가 아니라 **1사이클 send 실패**(토픽 준비 경합).
- **계획 C-9 결정을 뒤집었다** — `@Scheduled` 리터럴 유지 → `fixedDelayString` 설정화. 배경 잡이 도는 상태에서는
  발행 횟수를 세는 테스트가 구조적으로 성립하지 않는다. 운영 기본값 5s 불변, 4서비스 복제 유지(parity green).
- **검증**: 두 클래스 동시 6회 연속 green(느린 실행 포함) · notification 2종 green · parity self-test 18종 green.
- **남은 것**: CI 재확인. Codex diff 리뷰는 여전히 **미측정**(usage limit).

## 2026-09-04 — CI green 확인 + 미충족 갱신 (④-c-2b-2, PR #102)

- **CI 전면 green**: test 6종 · lint · guards · gate · images 6종 · **e2e**. (`publish` 는 main 전용 skip.)
- **해소된 미충족 2건**:
  · ~~로컬 전체 스위트 1회 완주 없음~~ → CI 전 모듈 pass
  · ~~E2E 로컬 미실행~~ → CI **e2e pass**. `EXPECTED_MIGRATIONS` 를 실제 스택에서 대조하므로
    4 DB 마이그레이션·notification outbox 신설의 **실적용**이 확인됐다
- **남은 미충족**: Codex diff 리뷰 **미측정**(usage limit — CI green 이 리뷰를 대신하지 않는다) ·
  신규 테스트는 order·notification 만 · replay 행은 fixture 로만 · `NOT NULL` contract(R1) ·
  `05:177`(P24) · 운영 클러스터 미적용 · gateway 로컬 미재실행
- 반영처: PR #102 본문(`gh pr edit`) · `docs/TASKS.md` ④ 행 · `docs/progress/PHASE4.md` · 본 audit

## 2026-09-05 — 계획 리뷰 라운드 1 (④-c-2b-3 구간)
- 항목: 17건 (P0:0, P1:12, P2:5)
- 처리: 반영 17건 / 기각 0건
- 뒤집힌 전제: 착수 전 코드 검증(C-15~C-28)이 초안 전제 3건을 먼저 뒤집었고(pc-replay-* 상수 부재 · DlqHeaders allowlist 자료구조 부재 · replay 앵커 컬럼 미매핑), 리뷰가 **그 검증 표 자체를 4건 반증**했다:
  - C-18 "마이그레이션 0" → payload digest 컬럼 신설로 철회 (4서비스 additive)
  - C-24 "호출부 2곳" → 실측 44곳 · notification 에 quarantine consumer 없음 → 시그니처 확대 폐기, LedgerOwner 빈 주입
  - C-27 "누락은 자동으로 막힌다" → DLQ-PARITY-014 는 4벌이 이미 동일할 때만 신고 (과장)
  - P15 "기존 best-effort 계약" → 현재 record() 는 @Transactional 안에서 Slack 호출 (javadoc 이 이미 거짓)
- 구현했으면 false-green 이었을 것: insertIfAbsent 의 clearAutomatically 가 잠근 root 를 detach → 재개방 UPDATE 미발생 (#2) · attempt-id TOCTOU (#3)
- ADR 충돌 확인: ADR-0020 §D5-4 가 record_kind=REPLAY 대조를 요구하면서 같은 절에서 outbox 를 정본으로 쓸 수 없다고 적는다 → P14(f) 가 이 PR 에서 개정
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r1b.json

## 2026-09-05 — 계획 리뷰 라운드 2 (④-c-2b-3 구간)
- 항목: 9건 (P0:0, P1:6, P2:3) — 전부 1R 수정이 만든 새 표면을 겨냥
- 처리: 반영 9건 / 기각 0건
- 1R 수정이 만든 새 결함 6건:
  - digest 컬럼 신설하고 writer 미배정 → 실제 replay 에서 root digest 영구 NULL, 대조 축 9가 늘 통과 (fixture 때문에 green)
  - digest 마이그레이션 번호가 P23 backfill 과 충돌 (V10/V8/V8/V6 중복) → P23 을 V11/V9/V9/V7 로
  - 송신 allowlist 를 "부분집합" 으로 규정 → 헤더 0~3개짜리 REPLAY 통과, 발행 측에서 N11 파손
  - ADR 개정을 Update Log 로 하려 함 → adr/README.md:14 가 명시적으로 금지 (트레이드오프 변경은 새 ADR) → ADR-0021 신설 + ADR-0020 Partially Superseded
  - 롤백 drain 을 REQUESTED==0 으로 판정 → ack 시 PUBLISHED 로 바뀌므로 재시도 중/DLT 이동 중 레코드를 못 잡음 → 4조건으로 확대
  - "lint 본실행이 digest parity 를 강제" → 거짓. replay 축 검사는 glob 1파일만 보고 컬럼 하드코딩 → 최종 스키마 합성 기준으로 전환 (같은 구멍 3번째 재발)
- 남은 P2 3건: target-group 헤더 죽은 데이터(3자 대조) · V-19 축 중복(V-19a~m ID 배정) · Counter 트랜잭션 의미 미정의(CommitAwareMetrics + bounded reason)
- 반증되지 않은 것: P14(a)(c) 키 정본·판독 · P15(a) 실행 순서 재정의 · P15(f) LedgerOwner 빈 주입
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r2.json

## 2026-09-05 — 계획 리뷰 라운드 3 (④-c-2b-3 구간) — **미실행 (usage limit)**
- 항목: 0건 — **측정하지 못했다** (P1=0 이 아니라 미측정)
- 원인: Codex `usage limit` (재개 가능 시각 2026-09-07 14:45). 탐색은 끝냈으나 최종 JSON 미출력.
- 수렴 판정: **미달**. 2R 이 P1 6건이었고, 그 수정이 새 계약 표면을 또 만들었다
  (ADR-0021 신설 · parity lint 를 최종 스키마 합성 기준으로 전환 · 송신 4값 유효성 · Flyway 재배정 ·
   P21 digest writer + V-30 · group 3자 대조 · drain 4조건 preflight · V-19a~m · CommitAwareMetrics Counter).
  건수 추세로 종료 판정하지 않는다.
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r3.stderr (JSON 없음)

## 2026-09-06 — diff 리뷰 라운드 1 (④-c-2b-3a) — **미실행 (usage limit)**
- 항목: 0건 — **측정하지 못했다** (P0/P1=0 이 아니라 미측정)
- 원인: Codex `usage limit` (재개 2026-09-07 14:45). 계획 리뷰 1R/2R 이 한도를 소진했고 3R 부터 막혔다.
  **PR 분할이 이 문제를 해소하지 못했다** — 분할의 전제는 "각 조각이 한도 안에서 리뷰된다" 였는데,
  한도는 조각 크기가 아니라 이미 소진된 잔량에 걸렸다.
- 검증(리뷰 대체 불가, 별개로 수행): 1006 tests 0 실패(8모듈) · lint 15종 green ·
  parity self-test 23종 · 변이 1종(requireComplete 제거 → 거부 4종 red, 정확히 그 4종만)
- raw: .cache/codex-reviews/diff-c2b3a-r1.stderr (JSON 없음)

## 2026-09-06 — /ship (④-c-2b-3a)
- PR: https://github.com/Kimgyuilli/PeakCart/pull/103
- precheck: ok (warnings 0)
- 커밋 4개: 계획(검증표+2R 반영) · 계획(3a/3b 분할) · feat(P14 구현) · docs(검증 결과+리뷰 미실행)
- 갱신: TASKS.md ④ 행 · PHASE4.md 작업 이력 · 계획서 진행 상태(2b-2 를 ✅ 로 정정 — #102 머지 반영 누락분)
- 미충족(PR 본문 §미충족 6항): diff 리뷰 미측정 · 계획 3R 미측정 · digest writer 부재(2b-4) · 상관 로직 부재(2b-3b) · 운영 클러스터 미적용 · 로컬 e2e 미실행

## 2026-09-07 — 계획 리뷰 라운드 3 (④-c-2b-3b 구간) — **실행됨**
- 항목: 8건 (P0:0, P1:4, P2:4)
- 처리: 반영 8건 / 기각 0건
- 2R 수정이 만든 새 결함 (P1 4건 중 3건이 여기 해당):
  - **3자 group 대조를 도입하고 매트릭스를 안 늘렸다** — 축 3 은 독립 equality 가 둘인데 V-19d 는 한 행뿐이라
    어느 값을 바꾸느냐에 따라 equality 하나를 지워도 green → V-19d(3-①)/V-19d2(3-②) 로 분리
  - **drain 4조건이 문구에만 반영됐다** — 실행 항목 P24 와 V-28 은 여전히 `PENDING` 단일 조건이라
    2R 이 든 반례(ack 후 PUBLISHED + 소비 재시도 중)가 preflight 를 통과 → ⓐ~ⓓ 1:1 확장 + V-28 을 4 fixture 로
  - **V-30 ID 중복 배정** — 2R 이 P21 관통 검증을 V-30 으로 새로 만들며 기존 `record_kind IS NULL` 호환성과 충돌.
    `ADR-0021:101` 이 관통 쪽을 V-30 으로 참조하므로 **호환성 쪽을 V-34 로** 옮겨 ADR 개정 없이 해소
- 그 밖의 P1:
  - 음성 기대값이 "독립 root" 뿐 → 집계가 `root_record_id IS NULL` 도 root 로 세므로(`repository:89`)
    `assignSelfRoot()` 누락이 green. **`root_record_id == 자기 id` 단언**을 공통 계약으로 승격
  - `LedgerOwner` 빈 4개의 서비스 배선 검증 항목 부재 → parity lint 밖이라 아무도 값을 안 본다.
    4서비스 context 테스트에서 `LedgerOwner.service()` 대조를 별도 완료 조건으로
- P2 4건: Counter meter명/tag schema/결과별 delta 표 확정 · 알림을 결과별 4행으로 못박음(상관된 자식에 신규 알림 0회) ·
  V-30 재번호 · C-29 "조회 3종뿐" 과장 한정 + C-36 writer 소유권을 P19/P21/P15 로 분리
- 뒤집힌 전제: C-29(조회 3종뿐 — 과장, 핵심 결론은 유지) · C-36(deadline/policy writer 는 P19 가 아니라 P21)
- 수렴 판정: **미달** — P1 4건이었고 수정이 새 표면을 만들었다(V-19d2/n/o · Counter 스키마 · 알림 결과 표 ·
  preflight ⓐ~ⓓ · V-34). 4R 필요.
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3b-r3.json

## 2026-09-07 — 계획 리뷰 라운드 4 (④-c-2b-3b 구간)
- 항목: 9건 (P0:0, P1:8, P2:1) — **3R(4건) 대비 증가 = 발산**
- 처리: 반영 5건 / **범위 되돌림 4건** (사용자 결정)
- **3R 수정이 만든 새 결함이 8건 중 5건**:
  - #1 `V-19o`(digest 양쪽 NULL 양성 상관)가 **도달 불가** — ADR-0020 §D5-2 가 `event_id IS NULL` 을 replay
    금지축으로 정하고, 4서비스 `outbox_events.payload` 가 `TEXT NOT NULL` 이다 → V-19o 철회, 축 9 를 **필수 대조**로
  - #2 3R 이 V-19d2/n/o 를 추가하고 §6·완료 조건의 집계("13종", "V-19b~V-19j 9종", "V-1~V-28b")를 안 고쳤다
    → 명시 집합 15 ID(실행 16회, V-19h 양방향)로, V-29 범위는 표에서 유도
  - #3 attempt 변이 지점이 **셋**(로케이터·잠긴 root 대조·재조회 후 재확인)이라 상호 은폐 → **단계 3 단일 대조**로 확정.
    V-19b = 로케이터 탐색 실패, V-19m = 단계 3 비교. 단계 5 는 detach 복구 전용(TOCTOU 방어 아님)
  - #4 `V-34` 재번호가 **머지된 코드에 전파 안 됨** — `OutboxReplayPublicationIntegrationTest:44·122` 가 호환성
    테스트를 `V-30` 으로 부른다 → **재번호 방향을 뒤집었다**: 호환성 = `V-30` 원복, 아직 코드 없는 관통 = `V-35`.
    `ADR-0021:101` 참조는 Update Log 1줄 정정으로 처리(트레이드오프 변경 아님)
  - #9 duplicate 계측 근거가 실행 순서와 모순 — 대조는 INSERT 보다 먼저라 "대조를 하지 않으므로 0" 은 거짓
    → Counter 기준을 **`inserted == 1` 확정 상관 결과**로, 중복은 대조는 하되 계측·알림·재개방 생략
- **범위 되돌림 (P1 4건, 전부 P24 안)**: 3R #3 이 drain 4조건을 3b 에서 확장한 것이 **범위 위반**이었다.
  P24 는 2b-4 항목이고, 열자마자 3b 가 답할 수 없는 표면 넷이 딸려 나왔다 —
  #5 ⓐ 판정이 두 테이블(`outbox_events.record_kind` / `dead_letter_records.publication_status`)에 걸쳐 미정 ·
  #6 ⓓ 식 불가(`FixedSequenceBackOff` 에 `maxAttempts` 없음, 마지막 attempt 시각의 내구적 기준점도 없음) ·
  #7 preflight 를 배포 진입점에 연결하는 작업 부재(실제 명령은 `kubectl apply -k`) ·
  #8 P24 의 ADR-0020 Update Log 지시가 `adr/README.md:8-14` 및 ADR-0021 과 충돌.
  → **drain 조건의 판정식·진입점·ADR 처분을 2b-4 착수 시 결정**으로 이관. 3b 는 "4서비스 배포 후 진입점 활성화"
  순서 하나만 책임진다.
- 뒤집힌 전제: V-19o 도달 가능(거짓) · `FixedSequenceBackOff` 에 maxAttempts 존재(거짓) · V-30 이 계획서에만 있음(거짓)
- 수렴 판정: **미달**. 라운드 상한 3회를 넘겨 **사용자에게 확인** → "범위 되돌리고 5R" 채택.
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3b-r4.json

## 2026-09-07 — 계획 리뷰 라운드 5 (④-c-2b-3b 구간, 범위 되돌린 뒤)
- 항목: 6건 (P0:0, P1:5, P2:1) — 4R 9건(P1 8)에서 감소, 그러나 **수렴 아님**
- 처리: 반영 6건 / 기각 0건
- **5건이 4R 반영 자체의 결함**(= 내 편집이 덜 끝났거나 월권):
  - #1 축 9 를 "필수 대조" 로 바꾼 것이 **Accepted ADR 침범**. `ADR-0021 §D2:71` 이 "tombstone 은 digest 도 null,
    양쪽 null 이면 일치" 를 이미 결정했다. **도달 불가라는 사실이 결정의 의미를 바꿀 권한은 아니다**
    → null-safe 원복, `V-19o` 를 **계약 고정 테스트**로 복원(fixture 전용임을 명시).
    ADR-0021 §D2 ↔ ADR-0020 §D5-2 의 어긋남은 §미해결로 (새 ADR 필요, 3b 범위 밖)
  - #2 실행 순서 5 의 "attempt 를 한 번 더 확인" 문장을 **안 지웠다** — 4R #3 으로 단계 3 단일 대조를 확정해 놓고
    본문·축 표는 그대로라 V-19m 단독 red 가 다시 깨졌다 → 문장 삭제 + 축 1 비고를 "단계 3 유일 대조" 로
  - #5 P24 확장은 철회했으나 **P17 롤백 절의 4조건·자동 preflight 문장이 그대로 남아** 3b 경계를 다시 열고 있었다
    → 판정식·조건 수·진입점을 2b-4 로 넘기고, 3b 는 "REQUESTED==0 만으로 불충분" 이라는 사실까지만 보유
  - #6 V-30/V-35 배정은 맞으나 **ADR-0021:101 참조 정정에 담당이 없었다** → **P17-b** 신설(Update Log 1줄,
    호환성=V-30 불변 · 관통=V-35 · V-34 철회). 결정 변경이 아니라 참조 오기 정정이라 새 ADR 불요
- 신규 결함 2건 (4R 반영과 무관, 진짜 설계 갭):
  - #3 단계 5 재조회가 **current read 로 확정되지 않아** 재개방 race 가 남는다 — 일반 `findById` 면 단계 1 이 연
    REPEATABLE READ 스냅샷을 다시 읽어 **과거 OPEN 을 보고 재개방을 건너뛴다**(`repository:141-145` 가 같은 함정 경고)
    → `findByIdForUpdate` 명시 + **V-15c 신설**(로케이터 직후 타 트랜잭션이 root 종결 → 최종 OPEN 영속 단언)
  - #4 `V-19g`(eventId) fixture 가 **축 6·9 를 동시에 변이**한다 — 자식 eventId 는 payload 에서 뽑으므로
    (`DeadLetterRecorder:61`) payload 를 바꾸면 digest 도 바뀌어 eventId predicate 제거가 false-green
    → **root 의 `event_id` 컬럼만** 변이 + 실행 전 자식/root digest 동일 단언
- 뒤집힌 전제: "축 9 를 필수 대조로 바꿔도 된다"(거짓 — ADR 침범) · "P24 확장만 되돌리면 경계가 닫힌다"(거짓 — P17 잔존)
- 수렴 판정: **미달** (P1 5). 다만 4R 대비 P1 8→5 이고 **범위 이탈 지적은 #5 하나로 축소**됐다. 6R 진행.
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3b-r5.json

## 2026-09-07 — 계획 리뷰 라운드 6 — **중단 (사용자 지시)**
- 항목: 0건 — 실행 중 중단. **P1=0 이 아니라 미측정**이다.
- 사유: 사용자 판단 — "리뷰 라운드가 전체적으로 너무 많다". 수렴 조건(새 계약 표면 무추가 + P1=0)이
  아니라 **비용으로 종료**한다. 이 구간의 라운드 이력: 1R 17 · 2R 9 · 3R 8 · 4R 9 · 5R 6 (P1: -·6·4·8·5).
- 착수 시점의 계획서 상태: 5R 6건 전량 반영 완료. 5R 이 지적한 것 중 **미해소로 남는 것은 없다**.
  다만 5R 반영이 만든 새 표면(V-19o 복원 · V-15c 신설 · 단계 5 current read · P17-b)은 **검토되지 않았다**.
- 이 미측정분은 **3b diff 리뷰에서 함께 본다** — 계획 리뷰를 늘리는 대신 구현 후 실물 diff 로 확인하는 쪽으로 옮긴다.
- raw: 없음 (중단)

## 2026-09-10 — diff 리뷰 라운드 1 (④-c-2b-3b) — **실행됨**
- 항목: 8건 (P0:0, P1:5, P2:3)
- 처리: 반영 8건 / 기각 0건
- **리뷰가 실제 실패 2건을 잡았다.** 내가 "구현 완료" 로 보고하기 직전 상태에서 리뷰어가 테스트를 직접 돌렸고
  `DlqReplayCorrelationIntegrationTest` 25건 중 2건이 red 였다. 로컬 `./gradlew test` 는 XML 결과 파일
  쓰기 실패로 BUILD FAILED 만 남기고 **테스트 실패를 가리고 있었다** — 그 신호만 봤으면 놓쳤다.
  - #1 `V-19m`: fixture 를 recorder **진입 전에** 바꿔 로케이터가 root 를 못 찾았다 → `attempt_not_found`.
    TOCTOU 창을 만들지 못했고 단계 3 의 비교는 타지도 않았다
  - #2 `V-19o`: `payload=null` 인데 root `eventId` 는 `evt-1` 이라 **축 6 까지 동시에 어긋났다**
- 그 밖의 P1 3건 (전부 "테스트가 계약이 아니라 통과를 기술" 유형):
  - #3 `V-15c` 가 recorder 진입 전에 root 를 닫아 단계 1·2 가 **둘 다 terminal 을 봤다** → 단계 5 를
    일반 read 로 바꿔도 통과하는 vacuous 테스트였다
  - #4 `V-21c`(purge 경합)가 **아예 없었다** — 계획이 이 PR 필수로 지정한 항목
  - #5 rollback delta·V-16·duplicate 대조 실행 확인이 없었다
- P2 3건: P17 이 원장만 보고 DLT 헤더를 안 봄 + `deleteAll()` 부재 · 배선 테스트가 `new LedgerOwnerConfig()`
  직접 호출이라 스캔/중복/조건 오류를 못 잡음 · 계획서 §6 이 V-19o 철회 상태로 남아 본문과 충돌
- **seam 구현에서 두 번 막혔다**: Spring Data 리포지토리는 인터페이스 프록시라 `callRealMethod()` 가
  성립하지 않는다 → 알려진 값을 반환하는 seam 으로 전환. 그리고 로케이터를 stub 하자 **평문 읽기가 사라져
  consistent-read 스냅샷이 열리지 않았고** V-15c 가 다시 vacuous 해졌다(M12 가 green) →
  seam 이 `repository.count()` 로 평문 읽기를 재현하도록 고쳐 M12 가 red 로 전환됐다.
- 검증: **변이 16종 red** — M1(attempt) M2(owner) M3/M4(group 3자 각 equality) M5(root-id) M6(topic)
  M7/M8/M9(fingerprint 3축) M10(digest) M11(digest null-safe) M12(단계5 current read) M13(assignSelfRoot,
  13건 red) M14(purge 재검사) M15(CommitAwareMetrics 우회) M16(owner 오배선).
  **M3/M4 가 각각 V-19d/V-19d2 만 red** — 3R 이 지적한 "3자 대조에 행이 하나뿐" 이 실제로 해소됐다.
- raw: .cache/codex-reviews/diff-c2b3b-r1.json
