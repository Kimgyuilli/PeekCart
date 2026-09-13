# ADR-0022: replay 개시 진입점의 개방 절차 — 도달 경로 · drain/롤백 계약 · `PUBLISH_UNKNOWN`

- **Status**: Proposed
- **Date**: 2026-09-13
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga (④-c-2b-4b)
- **관계**:
  - **Partially Supersedes** [ADR-0020](./0020-dlq-replay-contract.md)
    - **§D6-1 의 `publication_status` 값 목록** — `PUBLISH_UNKNOWN` 을 더한다 (§D4)
    - **§D6-2b 의 두 축 곱 표** — `PUBLISH_UNKNOWN` 행을 더한다 (§D4). **I-1·I-2 는 그대로 유지**된다
    - **§D6-4 의 "전이 주체는 reconciler 1종으로 고정한다"** — **2종**이 된다(reconciler + 운영자의 단방향 override). 같은 절의 나머지(`outbox_event_id` 연결 · 관리 API 는 `REQUESTED` 까지만 **만든다**)는 유지된다
  - **Refines** ADR-0020 **§D5-3**(멱등 안전창)·**§D1**(발행 crash window) — 그 값들을 **롤백 판정식의 입력**으로 사용한다. 안전창의 의미는 바꾸지 않는다
  - **관련** [ADR-0013](./0013-phase4-gateway-security.md) · [ADR-0017](./0017-gateway-signed-internal-token.md) — §D5 의 관리자 라우트는 그 신뢰 경계를 **그대로 따른다**(새 인증 경로를 만들지 않는다)
  - **관련** [ADR-0007](./0007-yaml-profile-merge-principle.md) — §D1 의 kill-switch 소유처, §D2 의 상한 소유처 판단 근거

---

## Context

구현 ④-c-2b-4a([#105](https://github.com/Kimgyuilli/PeakCart/pull/105))가 DLQ replay 의 **개시 진입점**을 만들었다. 좌표 reader · 6 금지축 적격성 · fence · 조건부 claim · 상관 앵커가 전부 섰고, **kill-switch `app.dead-letter.replay.enabled` 의 기본값을 `false`** 로 두어 배포해도 열리지 않게 했다.

남은 것은 **그 스위치를 여는 일**이다. 그런데 여는 행위는 되돌릴 수 없는 성질을 셋 만든다.

### 문제 1 — replay 행이 생긴 뒤의 롤백은 안전하지 않다

`outbox_events.record_kind='REPLAY'` 인 행은 **replay-aware poller 만** 올바르게 발행한다. 구버전 poller 는 그 행의 `event_type` 자리에 든 sentinel(`__replay__`)을 **토픽 이름으로** 써서 발행을 깨뜨린다. 즉 **replay 를 한 번이라도 개시한 뒤 구 이미지로 내려가면 발행 경로가 손상**된다.

"배포 전에 확인한다" 를 runbook 에만 적으면 그것은 검증이 아니다 — 문서는 프로세스 기동을 막지 못한다. **무엇이 끝났는지를 판정하는 식**과 **그 판정을 배포 경로에 붙이는 수단**이 함께 필요하다.

### 문제 2 — 진입점에 도달할 경로가 없다

4a 의 ADMIN 가드는 정확하지만, **그 가드에 도달할 방법이 없다**:

- 도메인 5서비스는 gateway 와 달리 **관리 포트를 분리하지 않아** actuator 가 앱 포트(8080)에 함께 있다
- 그런데 gateway 라우트는 `/api/v1/**` 뿐이다 (`gateway/src/main/resources/application.yml`)
- 리소스 서비스는 `HeaderTrustSecurityConfigurer` 를 통해 **게이트웨이가 서명한 내부 토큰에서만** 인증 주체를 세운다 (ADR-0017)

따라서 `kubectl port-forward` 로 직접 호출하면 `Authentication` 이 null 이고, **ROLE_ADMIN 검사에 닿기 전에 인증에서 끊긴다**. 이 성질은 ④-c-2a 가 만든 기존 엔드포인트(acknowledge/resolve/discard) 전체의 것이며 4a 의 회귀가 아니다. 그러나 **kill-switch 를 여는 시점에는 반드시 답해야 한다** — 열었는데 아무도 부를 수 없으면 ④ 는 "종결" 이 아니다.

### 문제 3 — `REQUESTED` 가 고착되면 롤백이 영구 차단된다

reconciler 는 원장의 `outbox_event_id` 가 가리키는 outbox 행이 **사라진 경우 강등하지 않고 그대로 둔다**(`DeadLetterPublicationWorker`). 이것은 의도된 fail-closed 다 — 부재는 실패의 증거가 아니기 때문이다(발행됐는데 행만 지워졌을 수 있다).

그러나 drain 판정 ⓐ' 가 `publication_status='REQUESTED'` 인 행이 0 이기를 요구하므로, **그 상태는 스스로 0 이 될 수 없고 롤백이 영구 차단**된다. I-1 때문에 사건 종결도 막힌다. 이것은 fail-closed 가 아니라 **탈출구 없는 교착**이다.

---

## Decision

### D1. kill-switch 는 **닫힌 채로 배포되고, 여는 데 재기동이 든다**

- 소유처는 각 서비스의 **base `application.yml`** 이다 — 런타임 동작 규약이지 환경별 연결 정보가 아니다(ADR-0007). 프로파일에 두지 않는다.
- 기본값은 **`false`**. 새 Pod 가 Ready 가 되는 순간 replay 가 열리면 backfill·증적 수집·리허설보다 **API 가 먼저 열린다**. 닫힌 채로 배포하고 준비가 끝난 뒤 의도적으로 여는 편이 되돌리기 비용이 낮다.
- k8s 에서 여는 수단은 `ConfigMap` 의 `APP_DEAD_LETTER_REPLAY_ENABLED` 키다(4개 도메인 서비스). Deployment 가 `envFrom` 으로 읽는다.
- **즉시 반영되지 않는다.** Spring 의 정적 설정이므로 **ConfigMap 변경 → 롤링 재기동 → 반영 확인** 3단계가 필요하다. 그 사이의 요청 차단은 **운영 규율**이며 코드 강제가 아니다. runbook 이 이 한계를 그대로 적는다.

### D2. 롤백 안전의 판정은 **drain 4조건**이고, 앵커는 `last_replay_settled_at` 이다

구 이미지로 내려가기 전에 넷을 **모두** 만족해야 한다. 4개 서비스 DB **각각**에서 판정한다.

| | 조건 | 판정 |
|---|---|---|
| **ⓐ** | 미발행 replay 잔여 | `outbox_events` 에 `record_kind='REPLAY' AND status='PENDING'` 인 행이 **0** |
| **ⓐ'** | 미확정 발행 잔여 | `dead_letter_records.publication_status='REQUESTED'` 인 행이 **0** |
| **ⓑⓒ** | 소비 완료 | `last_replay_target_group` 에 등장한 group + 대응 `.dlq` intake group 의 **lag = 0** |
| **ⓓ** | 재시도 소진 | `MAX(last_replay_settled_at) + 상한 < NOW()`. **NULL 이면 replay 이력 없음 → 충족** |

- **ⓐ 의 정본은 `outbox_events` 단독**이다 — 구 poller 가 집는 행 집합이 정확히 그것이다(`PENDING` 만 폴링하고 `FAILED` 는 손대지 않는다).
- **ⓐ' 를 따로 둔다.** outbox 행이 강제 삭제된 경우 `REQUESTED` 만이 유일한 미확정 증거다. 이 조건은 reconciler 주기(5s)만큼 늦게 0 이 되지만, **어긋나는 방향이 fail-closed**(게이트가 더 오래 막힌다)라 안전하다.
- **ⓓ 의 기준점은 `last_replay_settled_at`** 이다. reconciler 가 발행 축을 종착시킬 때 **`PUBLISHED`·`PUBLISH_FAILED` 양쪽 다** DB 시각으로 찍는다. `PUBLISH_FAILED` 에도 찍는 이유는 그것이 "발행되지 않았다" 가 아니라 **"발행 여부를 모른다"** 이기 때문이다(§D1 crash window).
- **상한의 식**: `Σbackoff(36s) + attempts × handlerBudget + DLT publish/intake 상한 + clockSkewBudget(5m) + margin`.
  `attempts` 를 곱하는 것은 backoff 사이마다 handler 가 다시 실행되기 때문이고, `clockSkewBudget` 을 더하는 것은 앵커 기록 시각과 비교 기준(`NOW()`)이 갈릴 수 있기 때문이다.
- **상한의 소유처는 preflight 스크립트의 명명 상수**다. 배포 절차의 값이지 앱 런타임 정책이 아니므로 앱 yml 에 둘 근거가 없고(ADR-0007), 스크립트가 앱 yml 을 파싱하면 배포 도구가 런타임 설정 포맷에 묶인다. 대신 **드리프트를 lint 가 잡는다** — `FixedSequenceBackOff` 리터럴(4벌)과 `clock-skew-budget`(4벌 yml)을 스크립트 상수와 교차 대조해 어긋나면 실패시킨다.
- **`handlerBudget` 은 코드로 강제되지 않는 선언값이다** — consumer handler 에 timeout 설정이 없다. 그래서 **시간 추론 하나에 판정을 걸지 않는다**: ⓑⓒ 는 `lag=0` 을 **상한 간격을 두고 2회 연속** 관측해야 통과한다.
- **DB·브로커 접속 실패는 fail-closed** 다(배포를 막는다).

### D3. preflight 의 강제 수단은 **배포 래퍼**이며, **우회 가능함을 명시**한다

- `scripts/replay-drain-preflight.sh` 가 판정하고, `scripts/deploy-overlay.sh` 가 `preflight → kubectl apply -k` 순으로 감싼다.
- **감싸는 범위는 앱 overlay 배포뿐이다.** monitoring/shared 매니페스트 적용은 서비스 이미지를 바꾸지 않아 이 위험과 무관하다.
- **"레포 전체에서 raw `kubectl apply -k` 0건" 은 계약으로 세우지 않는다.** `apply -k` 는 immutable ADR(0005/0006)·`docs/learning/`·`PHASE3.md`·아카이브에도 있고 이력은 고칠 수 없으므로 그 lint 는 영원히 실패한다. 대신 **운영 절차 문서 2개**(`docs/02-architecture.md` 배포 순서 · `k8s/overlays/gke/README.md`)만 래퍼를 가리키도록 하고, lint 도 그 둘만 본다.
- **운영자가 래퍼를 건너뛰고 직접 `kubectl apply -k` 를 치면 우회된다.** 이것은 알려진 한계이며, 검증은 "래퍼가 exit≠0 이다" 만 주장하고 "우회 불가" 를 주장하지 않는다.
- in-cluster initContainer 게이트는 진짜 강제지만, 4서비스 × in-cluster DB 자격증명 + mysql 클라이언트 이미지 + **정상 배포마다 드는 상시 비용** 때문에 채택하지 않았다. CD 파이프라인이 생기면 그 지점이 더 나은 자리다.

### D4. `PUBLISH_UNKNOWN` — 교착의 **단방향 탈출구**

`publication_status` 에 다섯 번째 값을 둔다. 전이는 **운영자의 명시적 요청**(`action=publication-unknown`, actor·사유 필수)으로만 일어난다.

| `publication_status` \ `status` | `OPEN` / `ACKED` | `RESOLVED` / `DISCARDED` |
|---|---|---|
| `PUBLISH_UNKNOWN` | 정상 — **미결이며 재발행 불가** | **허용** (사람이 판단해 종결) |

- **`REQUESTED` 에서만 들어올 수 있다.** 다른 상태에서의 진입은 거부한다.
- **outbox 행이 실제로 부재할 때만 허용한다.** 행이 남아 있으면 reconciler 가 스스로 종착시키므로, 그때도 옮길 수 있게 하면 **reconciler 와 경쟁하는 두 번째 종착 경로**가 생긴다 — 원장의 종결 경로를 하나로 묶어온 계약(④-c-2a)이 무너진다. 이 좁힘이 **탈출구와 우회로를 가른다**.
- **재요청은 열리지 않는다.** claim 조건은 `publication_status IS NULL OR 'PUBLISH_FAILED'` 인 **allow-list** 이므로 새 값은 자동으로 default-deny 다. 발행 여부를 모르는 건을 다시 발행하면 **중복 소비 위험을 모르는 채로 감수**하는 것이 된다.
- **사건 종결은 허용한다.** I-1 이 금지하는 것은 `REQUESTED` 이며, `PUBLISH_UNKNOWN` 은 "확인했고 더 기다릴 근거가 없다" 는 사람의 판정이 이미 들어간 상태다. 막으면 교착이 상태만 바꿔 유지된다.
- **drain ⓐ' 에서 빠지지만 ⓓ 앵커는 찍는다** — 발행 여부를 모르므로 소비 재시도가 끝났다고 가정할 수 없다.
- **감사는 원장에 영속한다** — `publication_override_by` · `publication_override_reason`. 로그에만 남기면 프로세스 로그가 사라진 뒤 "무엇을 확인하고 옮겼는가" 를 복원할 수 없고, 그러면 이 action 은 존재 이유(감사)를 잃는다. 시각은 같은 순간 찍는 `last_replay_settled_at` 이 갖는다.

### D5. 진입점의 운영 도달 경로 — **게이트웨이 관리자 라우트**

- 4개 도메인 서비스에 대해 `/api/v1/admin/deadletter/{service}/**` → `RewritePath` → 업스트림 `/actuator/deadletter/**` 라우트를 둔다. 기존 `product-admin` 라우트와 같은 형태다.
- **새 인증 기구를 만들지 않는다.** 게이트웨이가 서명한 내부 토큰이 그대로 전달되고, **권한 판정은 `DeadLetterEndpoint` 의 기존 `ROLE_ADMIN` 검사**가 진다. 게이트웨이로 권한을 옮기면 판정이 리소스 소유 서비스 밖으로 나가고, 라우트를 우회하는 경로가 생겼을 때 가드가 함께 사라진다.
- **경로 predicate 를 좁힌다.** rewrite 결과가 `/actuator/` 아래 다른 엔드포인트로 새면 이 라우트가 곧 actuator 전면 노출이다. `..`·인코딩 변형이 `/actuator/env` 등에 닿지 않아야 한다.
- rate limiter 는 `deny-empty-key: true` 인 `userKeyResolver` 를 쓴다 — 미인증 요청은 게이트웨이에서 먼저 끊긴다.

### D6. P22 측정의 **수용 기준을 분기표로 선기록**한다

ADR 본문은 immutable 이므로(`adr/README.md`), 측정 결과를 나중에 본문에 적을 수 없다. 결과별 처분을 **미리** 정한다.

`original_timestamp` 가 NULL 인 원장 행은 replay deadline(§D5-3)을 계산할 수 없어 **replay 불가**다. 그 비율이 가용성 손실이다.

| 실제 원장 집계 결과 | 처분 |
|---|---|
| **표본 0건** (미결 원장이 없다) | **"미측정" 으로 기록**한다. 0건을 "NULL 0%" 로 적지 않는다. 운영 표본이 쌓인 뒤 재평가하며, 그 재평가는 **새 ADR** 사유가 아니라 증적 갱신이다 |
| NULL 비율 **< 10%** | 수용한다. 해당 건은 수동 재처리(ADR-0012 D5 경로)로 넘기고 runbook 이 그 분기를 적는다 |
| NULL 비율 **≥ 10%** | 수용하지 않는다. `original_timestamp` 를 채우지 못하는 원인(DLQ 헤더 유실 경로)을 **선행 과제로 승격**하고, 그때까지 replay 는 "timestamp 가 있는 건 한정" 으로 운영한다 |

---

## Alternatives Considered

### Alternative A: 롤백 판정을 `replay_deadline` 으로 (기각)
ADR-0020 §D5-3 이 그 컬럼을 `original_timestamp + dlq-replay-window`(7d 멱등 안전창)로 정의하고 **재계산을 금지**한다. drain 용으로 덮으면 7d 안전창이 초 단위로 축소된다. 의미가 다른 두 값을 한 컬럼에 담으려 한 것이 오류였다.

### Alternative B: 롤백 판정을 `outbox_events.created_at` 으로 (기각)
① 행이 강제 삭제되면 **부재가 fail-open** 이다 — 부재는 실패의 증거가 아닌데 "없으니 끝났다" 로 읽힌다. ② INSERT 시각이지 **발행 시각이 아니다** — 적체된 `PENDING` 이 preflight 직전에 발행되면 기준시각이 이미 과거다.

### Alternative C: ⓑⓒ 를 시간 추론 하나로 흡수 (기각)
컨슈머가 정지·지연된 상태에서는 deadline 만 지나고 소비는 일어나지 않는다. 판정이 **vacuous** 해지면서 정확히 막으려던 "늦은 DLT 의 독립 root" 를 통과시킨다. 실제 lag 을 본다.

### Alternative D: 고착 `REQUESTED` 를 reconciler 가 자동 강등 (기각)
outbox 행 부재를 `PUBLISH_FAILED` 로 읽으면 **발행됐을 수도 있는 건을 실패로 오분류**한다. 그 위에서 재요청이 열리면 중복 발행이 된다. 사람의 확인을 요구하는 편이 사실에 맞는다.

### Alternative E: 진입점 도달을 common-auth 의 2차 인증으로 (기각)
actuator 경로 한정으로 ADMIN JWT 직접 검증을 추가하면 port-forward 직접 호출이 가능해진다. 그러나 **신뢰 경계가 둘이 된다** — ADR-0017 이 "게이트웨이가 서명한 assertion" 하나로 좁혀둔 것을 되돌리고, 그 두 번째 경로는 게이트웨이의 rate limit·헤더 strip·감사 밖에 있다.

### Alternative F: 도달 경로 없이 한계로만 기록 (기각)
kill-switch 를 열어도 부를 수 없으므로 ④ 가 종결되지 않는다. "진입점은 있으나 도달 불가" 는 계약이 절반만 존재하는 상태이며, 4a 가 이미 그 상태다.

### Alternative G: in-cluster initContainer 로 drain 강제 (기각 — D3)
진짜 강제지만 정상 배포마다 상시 비용이 들고 4서비스 × DB 자격증명을 배포 경로에 끌어들인다. CD 파이프라인이 생기기 전까지는 래퍼가 비용 대비 합리적이다.

---

## Consequences

### 좋아지는 것
- replay 를 개시한 뒤에도 **롤백이 판정 가능한 절차**를 갖는다. 판정은 문서가 아니라 스크립트가 한다.
- 고착 `REQUESTED` 에 **감사 가능한 탈출구**가 생긴다. 교착이 상태만 바꿔 남지 않는다.
- 진입점이 **운영에서 실제로 호출 가능**해지고, 그 권한 판정은 여전히 리소스 소유 서비스가 진다.

### 나빠지거나, 감수하는 것
- **배포 절차가 길어진다.** 앱 overlay 배포가 래퍼를 거쳐야 하고 preflight 가 DB·브로커에 접속한다. 접속 실패는 fail-closed 라 **인프라 장애 시 배포가 막힌다** — 의도한 방향이다.
- **래퍼는 우회 가능하다.** raw `kubectl apply -k` 를 직접 치면 preflight 가 돌지 않는다. 운영 규율에 기대는 부분이 남는다.
- **`handlerBudget` 은 코드로 강제되지 않는다.** consumer handler timeout 이 없으므로 그 값은 선언일 뿐이다. ⓑⓒ 2회 연속 관측이 이 약점을 보완하지만 제거하지는 못한다.
- **kill-switch 는 즉시 닫히지 않는다.** 롤링 재기동 사이의 요청은 운영 규율로만 막힌다.
- **관리자 라우트가 actuator 를 외부 경로에 연결한다.** 경로 predicate 가 좁음에 의존하므로, 그 predicate 는 회귀 검사 대상이다.
- **`PUBLISH_UNKNOWN` 은 사건을 해소하지 않는다.** 원장에 "발행 여부를 끝내 모른다" 는 행이 영구히 남을 수 있다 — 그것이 사실이므로 그대로 남긴다.

### 확인 방법
- drain 4조건을 **하나씩만** 위반한 실행이 각각 위반 조건을 정확히 지목하며 래퍼가 exit≠0 (계획 §6 V-37)
- `PUBLISH_UNKNOWN` 전이 후 **재claim 이 거부되고 종결은 허용**되며, outbox 행이 남아 있으면 전이 자체가 거부됨 (V-39)
- ADMIN 만 게이트웨이 경유로 진입점에 도달하고, rewrite 가 다른 actuator 엔드포인트로 새지 않음 (V-40)
- 상한 상수가 `FixedSequenceBackOff`·`clock-skew-budget` 과 갈라지면 lint 가 실패 (V-41)
