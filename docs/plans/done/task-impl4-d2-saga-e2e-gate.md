# ④-d-2 — cross-service saga E2E · 계약 게이트 · ④ 종결

> 부모 계획: `docs/plans/task-impl4-choreography-saga.md` **P12 · P14 · P15**
> 형제: ④-d-1 (P11 관측성) — `task-impl4-d1-saga-metrics.md`, ✅ [#91](https://github.com/Kimgyuilli/PeakCart/pull/91)
> 리뷰 이력: `task-impl4-d1-saga-metrics.audit.md` (d-1/d-2 공통) · `task-impl4-d2-saga-e2e-gate.audit.md`
> **④-d-2 전체가 끝나면 구현 ④ 가 종결된다.** 이 문서는 두 PR 의 공통 스펙이며, **④-d-2a 는 P1~P9 까지**다 — ④ 종결은 ④-d-2b(P20) 소관이다(§6 분할 주석).
> 이전 판(2026-08-26 "범위 정의")은 §9 에 정정 이력으로 보존한다.

---

## 1. 목표/목적 — 명제 (부정형)

아래 중 **하나라도 성립하면 이 작업은 미완이다.**

- **N1.** saga 의 한 체인이 4서비스 각자의 DB·Kafka 를 실제로 왕복하지 않은 채 "cross-service 로 검증했다" 고 기술된다.
- **N2.** E2E 실행이 CI 러너에서 외부 PG(Toss) 로 나가는 경로가 하나라도 열려 있다. **네트워크 수준에서 불가능하지 않고 "빈이 없으니 안 부른다" 로만 막혀 있으면 성립한다.**
- **N3.** 계약 매트릭스에 등재된 행 중 **실행 증적이 없는 행**이 있는데 CI 가 통과한다.
- **N4.** 매트릭스에서 행을 삭제하거나 테스트를 `@Disabled` 로 만들었을 때 CI 가 통과한다.
- **N5.** 증적 파서가 `missing`·`failure`·`error`·`skipped`·`duplicate`·`stale` 중 어느 하나라도 통과시킨다.
- **N6.** E2E compose 를 서로 다른 project 이름으로 2개 동시 기동했을 때 이름·포트가 충돌한다.
- **N7.** 시나리오가 요구하는 토픽/consumer group 중 일부만 준비된 상태에서 E2E 가 "준비 완료" 로 진행한다.
- **N8.** 부모 P12 가 요구한 **예약 실패 체인**이 시나리오에 없다.
- **N9.** ④ 종결 선언에 §8 미충족 목록이 빠져 있다.
- **N10.** 환불 체인이 `REQUESTED` 에서 끊긴 채 부모 P12 를 닫았다고 기술된다.
- **N11.** §4 의 음성 대조군이 **CI 에서 상시 실행되지 않고** 일회성 audit 증적으로만 남는다.
- **N12.** 매트릭스에 등재된 스케줄러 계약(timeout 3종·sweeper)의 `@Scheduled`/`@SchedulerLock` 을 지워도 CI 가 통과한다.
- **N13.** 시나리오들이 **run 고유 식별자 없이** 같은 stack 위에서 존재·건수 단언을 하여, 앞 시나리오의 잔여 행이 뒤 시나리오를 만족시킬 수 있다.
- **N14.** 매트릭스 `expected` 와 manifest 값의 대조가 **문법이 고정되지 않아** 구현자마다 다른 표현이 통과한다.
- **N15.** 시작 이벤트가 서비스의 실제 publisher 를 지나지 않는다(SQL INSERT seed).
- **N16.** E2E 를 위해 **운영 코드에 새 진입점/스위치가 추가**된다(seed runner·발행 스위치 등).
- **N17.** egress 차단 검증에 **양성 대조군이 없어** canary 가 죽어도 통과한다.

---

## 2. 배경 — 착수 전 코드 검증

**계획의 전제는 ADR 이 아니라 현재 코드다.** 초안 작성 전에 전부 직접 확인했다.

| # | 확인 대상 | 결과 | 근거 |
|---|---|---|---|
| V1 | `RefundDispatcher` 비활성화 프로퍼티 | **부재 확인** — `@Scheduled(fixedDelayString="${app.refund.dispatch-interval-ms}")` 만 있고 `@ConditionalOnProperty` 없음. `dispatch-enabled` 전역 grep 히트 0 | `RefundDispatcher.java:38-40` |
| V2 | **PG 호출 스케줄러가 1개인가** | **거짓 — 2개다.** `RefundReconciliationScheduler` 도 같은 `RefundExecutor` 로 Toss **조회**를 부른다. 이전 판 D1 은 dispatcher 만 지목 → **범위 확대** | `RefundReconciliationScheduler.java:39-41` |
| V3 | `TossPaymentClient` baseUrl | **하드코딩 확인** — `builder.baseUrl("https://api.tosspayments.com/v1")` | `TossPaymentClient.java:40` |
| V4 | `docker-compose.yml` 격리 | `container_name: peekcart-mysql\|redis\|kafka` + 호스트 포트 3306/6379/9092 고정 확인 | `docker-compose.yml:4,8,15,18,24,36` |
| V5 | **compose 에 앱 서비스가 있는가** | **없다 — 인프라 3개(mysql/redis/kafka)뿐이다.** 이전 판 D4 는 "격리" 만 요구했으나 실제로는 **앱 서비스 정의 자체를 신설**해야 한다 → **범위 확대** | `docker-compose.yml` 전문 |
| V6 | CI 이미지가 PR 에 전달되는가 | **안 된다 — 두 단계 모두 막혀 있다.** `Save image for publish` 와 `Upload image artifact` **둘 다** `if: github.event_name == 'push'`. upload 조건만 지우면 PR 에 `image.tar.gz` 파일 자체가 없다 | `ci.yml:147-149,151-153` |
| V7 | test artifact glob | `path: build/reports/` — **루트만**. 멀티모듈이라 실제 산출은 `<module>/build/reports/`·`<module>/build/test-results/` | `.github/workflows/ci.yml` build 잡 |
| V8 | JUnit XML 키 구조 | **`testsuite@name` 은 classname 이 아니라 클래스 레벨 `@DisplayName`** 이다(`name="가격 캐시 CQRS 소비자 통합 테스트"`). FQCN 은 **`testcase@classname`** 에 있다(중첩 클래스는 `Outer$Inner`). `testcase@name` 은 메서드 `@DisplayName`, 없으면 `method()` | `ProductPriceCacheSagaIntegrationTest.xml:2-4` vs `HarnessSmokeTtlTest.xml:2-3` |
| V9 | consumer group 정본 | **업무 consumer 는 9개 클래스**(8 아님). group 상수는 `private static final` 이고 **값이 다음 줄에 있는 경우가 있어**(`CompensationRequestConsumer.java:34-37`) 단순 grep 이 놓친다. DLQ group 은 상수가 아니라 annotation literal 이다 | `@KafkaListener` 보유 파일 전수 |
| V14 | **group/토픽 대조 정본이 이미 있는가** | **있다 — `DlqTopology`.** 서비스별 구독 21종(토픽+group 쌍)과 quarantine 소유 `.dlq` 집합을 `static` 맵으로 보유하고, 계약 테스트가 실제 값과 대조한다. **grep 이 아니라 이것을 정본으로 써야 한다** | `common/.../DlqTopology.java:34-84` |
| V16 | outbox 행이 publisher 직렬화를 담는가 | **아니다 — 이미 직렬화된 문자열이다.** `buildRecord()` 가 `event.getPayload()` 를 그대로 `ProducerRecord` 에 싣는다. DTO 조립·`writeValueAsString` 은 `*OutboxEventPublisher` 구간이라 **SQL INSERT seed 로는 우회된다** | `OutboxPollingService.java:119-122` · `PaymentOutboxEventPublisher.java:53-61,82-94` |
| V17 | `DlqTopology` 의 group 의미 | **업무 실패 소유자 group**(`order-svc-payment-failed-group`)이지 DLQ intake listener group 이 아니다. 실제 intake 는 `order-svc-dlq-group` 등 **annotation literal** 이며 quarantine 은 또 별개다 | `DlqTopology.java:40-68` vs `DeadLetterConsumer.java:35-46` |
| V18 | 환불 조회 경로 | `ALREADY_CANCELED` 는 즉시 `verifyByQuery` → `GET /payments/{paymentKey}` 를 부르고, reconciliation 은 **항상 조회를 먼저** 한다. stub 에 조회 API 가 없으면 이 분기가 도달 불가 | `RefundExecutor.java:44-46,57-78` |
| V19 | Toss 설정 소유 | base `application.yml:140-143` 이 `secret-key`/`webhook-secret` 을 placeholder 기본값으로 두고, `application-k8s.yml:27-31` 이 기본값 없이 강제(fail-fast). **`base-url` 은 환경별로 다르지 않은 단일 endpoint** 라 ADR-0007 판단 기준상 base 소유다 | `application.yml`·`application-k8s.yml` |
| V20 | 스케줄러 lock 상수 | 4개 메서드 전부 `fixedDelay=60_000` + `lockAtLeastFor=PT30S`. **기동 직후 빈 작업으로 선발화하면 ShedLock 이 30초 잡아** 짧게 override 한 다음 주기도 실행되지 않는다 | `OrderTimeoutScheduler.java:39-40,57-58,77-78` · `StockReservationLeaseSweeper.java:30-31` |
| V22 | `orders.cancel_reason` 컬럼 | **없다.** `OrderCancelReason`(④-b)은 `publishOrderCancelled(order, reason)` 의 **발행 인자**이지 영속 필드가 아니다 → 취소 사유 단언은 orders 행이 아니라 **`outbox_events` 의 `order.cancelled` payload** 로 해야 한다 | `V1__init_order.sql:28-46` (cancel_reason 부재) |
| V21 | HTTP 진입점으로 saga 를 시작할 수 있는가 | **가능하다.** `DUAL_ACCEPT` 모드에서 평문 `X-User-*` 로 인증되고(`InternalTokenAuthenticationFilter:44-46,62`), `POST /api/v1/orders`·`POST /api/v1/payments/confirm` 이 실제 진입점이다. 승인 실패는 `catch (Exception)` 이 `payment.fail()`+`publishPaymentFailed()` 를 한 트랜잭션에서 수행한다 | `PaymentController.java:43-55` · `PaymentCommandService.java:42-66` |
| V15 | DLQ 원장 식별자 | `uk_dead_letter_records_origin` = `(cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, failed_consumer_group)`. **같은 payload 를 재발행하면 offset 이 달라져 다른 좌표가 된다** — "같은 좌표 2회" 는 재발행으로 만들 수 없다 | `V6__dead_letter_records.sql:23-29,53-55` |
| V10 | 업무 토픽 집합 | **10종** — `order.created`·`order.cancelled`·`order.compensation.requested`·`payment.requested`·`payment.completed`·`payment.failed`·`payment.refunded`·`product.updated`·`stock.reservation.result`·`stock.compensation.requested` (+ 각 `.dlq`) | `@KafkaListener topics` 전수 |
| V11 | HTTP 로 saga 를 구동할 수 있는가 | **간단히는 불가** — `internal-token.mode: SIGNED_ONLY` 가 기본이라 서비스 REST 는 gateway 서명 `X-Internal-Auth` 없이 401. gateway 를 세우면 내부 토큰 **개인키**가 필요 | `order-service/application.yml:59-63`, `InternalTokenProperties.java:53` |
| V12 | E2E 전용 프로파일 | 없음 — `local`/`k8s` 뿐. ADR-0007 상 프로파일은 연결 정보만 소유 | `*/src/main/resources/application-*.yml` |
| V13 | ④-d-1 alert 라벨 계약 확장 | **완료** — `observability-promql-lint --self-test` 6종이 CI 에 배선됨. 이전 판 D6 는 이미 해소 | `.github/workflows/ci.yml`, #91 |

### 2.1 범위 변화

- **확대 4건**: V2(스케줄러 2개) · V5(compose 앱 서비스 신설) · V6(CI 이미지 저장 단계도 함께 개방) · **R1 P0 수용 — PG stub 도입으로 환불 체인 전구간을 이번 범위에 넣는다**.
- **축소 1건**: V13 — 이전 판 D6(신규 alert 라벨 계약)는 ④-d-1 이 이미 처리했다. 이 PR 은 그 위에 매트릭스 게이트만 얹는다.
- **결정 변경 (R1 #1 P0)**: 이전 판은 환불 체인을 `REQUESTED` 까지만 보고 "부모 P12 를 완전히 닫지 못한다" 고 자인하면서 ④ 를 종결하려 했다. **자기모순이다** — ADR-0018 §D4 는 회신으로 Order·Product·Notification 원장을 닫도록 결정했고, 부모 P12 는 그 종결까지를 요구한다. `toss.payments.base-url` 을 설정화하고 **로컬 PG stub** 을 두어 dispatcher→PG→`payment.refunded`→3소비자 종결까지 E2E 로 닫는다. `base-url` 설정화의 D-020 이연은 철회한다.
- **구동 방식 결정 (V11·V16·V21 · R1 #2 · R2 #1 · R3 #1 · [구현 중 재정정])**: **실제 HTTP 진입점을 쓴다.**
  R3 #1 이 지적한 seed runner 는 애초에 성립하지 않았다 — "운영 bootJar 에 넣지 않는 E2E 전용 source set" 은 **그 코드가 컨테이너 안에서 실행될 수 없다**는 뜻이다(컨테이너는 bootJar 를 돈다). main 에 넣으면 R3 #1 의 보안 지적이 그대로 살아난다. 즉 두 선택지가 모두 막혀 있었다.
  대신 **`app.internal-token.mode=DUAL_ACCEPT`**(기존 운영 지원 전환기 모드, `InternalTokenAuthenticationFilter:62`)로 4서비스를 띄우고 runner 가 평문 `X-User-*` 헤더로 **진짜 컨트롤러**를 호출한다: `POST /api/v1/cart/items` → `POST /api/v1/orders` → `POST /api/v1/payments/confirm`.
  이게 seed runner 보다 **엄격하게 낫다** — 새 운영 코드 0, 새 모듈 0, 새 보안 스위치 0이면서 컨트롤러·도메인 전이·publisher 직렬화·outbox·poller 를 **전부** 지난다. `payment.failed` 는 합성 이벤트가 아니라 `PaymentCommandService:58-62` 가 실제로 발행한 것이다.
  대가: E2E 가 `SIGNED_ONLY` 를 검증하지 않는다(→ §9-5, 구현 ③ GKE smoke 소관). gateway·user-service 는 여전히 제외한다.

---

## 3. 작업 항목

> **ID 는 등장 순서 = 번호 순서**다(`hpx_plan_lint` 가 강제). R1/R2 판의 번호는 §9 에 매핑을 남긴다.

### 안전장치

- [x] **P1.** **PG 호출 표면 통제 (V1·V2·V3·V19 · R3 #8 · diff 2R #4).**
  **[구현 중 폐기] `app.refund.dispatch-enabled` / `reconcile-enabled` 스위치는 만들지 않는다.**
  초안이 그것을 요구한 이유는 "E2E 에서 PG 호출 표면을 끈다" 였는데, P2(stub)+P3(`internal: true`)로 설계가 바뀌면서 **E2E 는 두 잡을 켠 채 stub 으로 돌린다**(환불 체인 전구간이 시나리오 C 의 대상이므로 꺼서는 안 된다). 즉 스위치를 **아무도 쓰지 않게 됐다.**
  그런데 그것은 환경변수 하나로 운영 스케줄러 빈을 조용히 없애는 스위치이고, ADR-0018 은 dispatcher/reconciliation 을 미결 환불 수렴의 필수 구성요소로 규정한다 — 오설정 시 health 는 정상인 채 `REQUESTED`/`CLAIMED`/`UNRESOLVED` 가 영구 적체된다. 쓰이지도 않는 운영 위험을 남길 이유가 없다(N16 · CLAUDE.md §2).
  **남는 것은 `toss.payments.base-url` 설정화뿐이다** — `TossPaymentClient` 생성자의 리터럴을 제거하고 `application-k8s.yml` 이 운영 URL 을, local 이 개발 endpoint 를, E2E 가 stub URL 을 **각각 명시 주입**한다.
  *R2 #7 을 기각했다가 R3 #8 로 철회했다*: "환경 불변 단일 값" 이라는 내 전제를 **내 계획서가 반증했다**(운영과 stub 으로 값이 갈린다). ADR-0007 Decision 표는 연결 정보를 프로파일 허용으로 분류한다.
  base 기본값은 **도달 불가 sentinel**(`localhost:9`, discard 포트)이다. 단, **base 의 기본 활성 프로파일이 `local`** 이라 아무 것도 지정하지 않은 부팅은 sentinel 이 아니라 local 의 운영 URL 을 쓴다(구현 중 테스트가 반증) — sentinel 은 "local 도 k8s 도 아닌 프로파일" 의 안전망이다.
  **[CI 실패 후 재정정]** 초안은 `application-k8s.yml` 이 `${TOSS_BASE_URL}` 을 **기본값 없이 강제**해 운영 fail-fast 를 하게 했는데, 그러면 **k8s 프로파일로 뜨는 모든 경로가 값 주입 전까지 부팅에 실패한다** — `docker-health-smoke.sh` 와 실제 k8s 배포가 그렇게 깨졌다(`Could not resolve placeholder 'TOSS_BASE_URL'`). k8s 매니페스트가 `TOSS_*` 를 비워 두는 건 **자격증명**이기 때문이고 `base-url` 은 endpoint 다 — 같은 파일이 `datasource.url`·`redis.host` 를 리터럴로 선언한다. → `${TOSS_BASE_URL:https://api.tosspayments.com/v1}` 로 바꿔 **누락될 값 자체를 없앴다**(env override 는 유지).

- [x] **P2.** **로컬 PG stub (R1 #1 · R2 #8 · R3 #12).** `POST /payments/{key}/cancel` 과 `GET /payments/{paymentKey}` **둘 다** 구현한다 — `ALREADY_CANCELED` 분기와 reconciliation 이 **항상 조회를 먼저** 부르기 때문에(V18) 조회가 없으면 그 경로가 도달 불가다.
  응답은 전역 모드가 아니라 **paymentKey 별 불변 script**. 지원: 취소 성공 · 4xx 거절 · transient 반복 · `ALREADY_CANCELED_PAYMENT` × 조회 3분기(전액 취소 성공 · `cancels[].cancelAmount` 금액 불일치 · 조회 실패) · 타임아웃.
  **script 별 예상 ledger 를 순서까지 열거한다**: 성공 `POST×1` / transient 소진 `POST×3`(`RefundExecutor:35-54` 의 `maxAttempts`) / `ALREADY` `POST×1 → GET×1` / reconciliation 성공 `GET×1, POST×0`. 각 단언의 **관측 시간창과 reconciliation 실행 여부**도 고정한다.
  **[구현 중 정정 — #92 후속 리뷰 #2]** 초안은 승인(`confirm`)을 "미구현 500" 으로 뒀는데, **P6 이 `POST /api/v1/payments/confirm` 을 실제 HTTP 진입점으로 요구**하므로 성립하지 않는다 — confirm 이 항상 500 이면 시나리오 A 의 `PAY-005`(PG 실패)와 가드 거부를 구분할 수 없고, 승인 성공을 HTTP 로 만들 길도 사라진다(P8 잔여분이 `payments` SQL seed 에 영구히 묶인다). → confirm 도 **paymentKey 접두사 script** 를 따른다(`e2e-ok-*`→200 · `e2e-cfail-*`→500). 초안이 지키려던 "미구현 표면이 조용히 통과하지 않는다" 는 **`STUB_UNSCRIPTED_KEY` 500** 이 담당한다 — 표면이 아니라 **키** 단위로 막는 쪽이 오타까지 잡는다.

### E2E 하네스

- [x] **P3.** **`docker-compose.e2e.yml` 신설 (V4·V5 · R2 #6).** ① 인프라 3종에서 `container_name` 제거·호스트 포트 미노출 ② **앱 서비스 4개 신설**(order/product/payment/notification), 태그는 `${PEEKCART_IMAGE_TAG}` ③ 연결 정보는 새 프로파일 yml 이 아니라 **환경변수** 주입(ADR-0007) ④ refund 스케줄러는 **켠 채** stub 으로 향한다(환불 체인을 닫아야 한다) ⑤ **앱·인프라·stub 을 `internal: true` 네트워크 하나에만** 부착(양성 대조군 `egress-control` 은 `profiles: [control]` 로 기본 기동에서 빠지며, 이를 **쓰는** 검사는 P16 소관이다 — d2a 는 정의와 정적 계약까지만 갖는다) — 모든 앱이 오직 그 네트워크에만 붙음을 **compose 정적 계약**으로 검사하고, `internal: true` 삭제·보조 외부 네트워크 부착을 self-test 가 실패시킨다 ⑥ 인프라 `healthcheck` + 앱 `depends_on: {condition: service_healthy}` ⑦ 매 실행 새 volume(P4 가 판정) ⑧ user-service·gateway 미포함.
- [x] **P4.** **cold start 판정 (R2 #5 · R3 #4).** `flyway_schema_history` 검사만으로는 warm reuse 를 못 가린다(직전 성공 volume 이 그대로 만족). 그리고 **warm datadir 에서는 `docker-entrypoint-initdb.d` 가 아예 재실행되지 않으므로**(`scripts/mysql-init/01-*.sql:2-4` 가 "첫 부팅 시 실행" 을 명시) "기존 marker 면 init 이 실패" 분기는 **도달하지 않는다.**
  → ① 기동 전 해당 project 의 volume 이 존재하면 실패 ② **E2E 메타데이터 스키마/테이블을 init 단계에서 만들고 현재 `run_id` 를 적는다**(정적 `.sql` 로는 run_id 주입이 불가하므로 **`.sh` init 자산**을 쓴다) ③ **readiness 가 `stored_run_id == current_run_id` 를 검사** — warm volume 은 옛 marker 불일치로 실패한다. 순서는 `스키마 생성 → marker 생성 → 앱/Flyway 시작` 으로 고정하고, marker 테이블은 **Flyway 관리 대상이 아님**을 명시한다(앱 테이블 DDL 은 Flyway 전용이라는 규칙과 구분).
  그 위에 별도 조건으로 4개 DB 의 `flyway_schema_history` **`success=1` 전량 + 최신 버전 적용**(migration 완전성)을 readiness 에 넣는다.
- [x] **P5.** **readiness 정본 (V9·V10·V14·V17 · R2 #4 · R3 #3).** 두 개념을 분리하되 **정본을 복제하지 않는다**:
  ① **업무 listener readiness** — `DlqTopology.consumptionSubscriptions()` 에서 **유도**한다(`.dlq` suffix 제거로 원본 토픽, group 은 그대로). 21쌍이 이미 그 안에 있으므로 **새 정본을 만들면 이중 정본이 되어 양쪽을 함께 잘못 고치면 각자의 자기대조가 모두 통과한다.**
  ② **DLQ intake 4 · quarantine 3 group** — 기존 모델에 정말 없는 값이라 이것만 상수로 신설하고, **`@KafkaListener` annotation 이 그 상수를 직접 참조**하게 한다(literal 중복 제거).
  준비 판정: 업무 토픽 10종 + P9 가 쓰는 `.dlq` 토픽 + 시나리오별 required listener group. group 은 존재가 아니라 **`active member ≥ 1` 그리고 `assigned partition ≥ 1`**.

### 시나리오 (부모 P12)

- [x] **P6.** **시나리오 A — 결제 실패 체인 (V16·V21 · R3 #2).** runner 가 **실제 HTTP 진입점**을 순서대로 호출한다: 상품 seed → `product.updated` 로 order-service 단가 캐시 적재 → `POST /api/v1/cart/items` → `POST /api/v1/orders`(→ `order.created` → 예약 성공) → `POST /api/v1/payments/confirm`.
  **결제 실패는 주입이 아니라 실제 실패다** — stub 의 `confirm` script 가 5xx 를 돌려주면 `PaymentCommandService:58-62` 의 catch 가 `payment.fail()` + `publishPaymentFailed()` 를 **같은 트랜잭션**에서 수행한다. 합성 이벤트가 아니고, 운영 코드도 전혀 손대지 않는다.
  기대: `orders.status=CANCELLED` + **`order.cancelled` outbox payload 의 `reason=PAYMENT_FAILED`**(V22 — orders 에 `cancel_reason` 컬럼이 없다) · `stock_reservations.status=RELEASED` · `inventories.stock` 원복 · **Payment 는 해당 Outbox `PUBLISHED`**, **Order/Product/Notification 은 각 정확한 consumer group 의 `processed_events` 1행** (R3 #2 — `payment.failed` 소비자는 3곳이고 **Payment 는 자기 이벤트를 소비하지 않아 `processed_events` 행이 생기지 않는다**) · notification DB 행.
- [x] **P7.** **시나리오 B — 예약 실패 체인 (부모 P12 명시 요구).** 재고 부족 seed → runner 가 `POST /api/v1/orders` 호출 → poller 가 `order.created` 발행 → `stock.reservation.result(success=false)` → `orders.status=CANCELLED` + `order.cancelled` payload 의 `reason=RESERVATION_FAILED`(V22) · 예약 원장 잔여 RESERVED 0 · notification 행.
- [x] **P8.** **시나리오 C — 환불 체인 전구간 (R1 #1).** 트리거 2경로(Product marker · Order 보상 원장) → 요청 토픽 2종 → `payment_refunds` **1행 fence** → **dispatcher 가 stub 호출 → `SUCCEEDED` + `payments.status=REFUNDED`** → `payment.refunded` 회신 → **Order `RESOLVED` · Product 종결 컬럼 3개 · Notification `PAYMENT_REFUNDED`**. 두 경로 동시 투입에도 1행 수렴.
  **결과 3종 분기**: 4xx → `FAILED` → Order `REFUND_FAILED`+`failure_code` · `APPROVED` 유지 / 타임아웃 → `UNRESOLVED` → **어느 소비자도 전이하지 않음**.
  **[④-d-2a 실제 범위 — diff 리뷰 #1]** 이 PR 이 실증한 구간은 **요청 2경로 → fence 1행 → dispatcher → stub → `SUCCEEDED` → `payments=REFUNDED` → `payment.refunded` outbox `PUBLISHED`** 까지다. **회신 소비 3곳의 종결**(Order `RESOLVED` · Product 종결 컬럼 3개 · Notification `PAYMENT_REFUNDED`)과 **결과 3종 분기**는 Order/Product 의 선행 원장 seed 가 필요해 ④-d-2b 로 넘긴다(§9-11). 지금 상태로는 그 세 소비자가 전부 no-op 이어도 시나리오 C 가 통과한다.
- [x] **P9.** **시나리오 D — DLQ intake (V15).** 역직렬화 불가 레코드 주입 → `.dlq` 경유 → `dead_letter_records` 1행 + 식별자 6컬럼 non-null.
  **중복 판정은 재발행으로 만들 수 없다**(offset 이 달라져 다른 좌표다). **DLQ consumer group 의 offset 을 명시적으로 rewind** 해 같은 DLT 레코드를 재소비시키고, 동일 6컬럼 키에서 새 행 없이 **`attempt_count=2`** 를 본다.
- [x] **P10.** **시나리오 격리 (R2 #3 · R3 #5 · N13).** 모든 시나리오에 `scenario_id` 기반 **고유 `orderId`/`eventId`/`paymentKey`** 를 부여하고 **모든 DB 단언을 그 키 + 정확한 consumer group 에 결부**한다.
  **"배경 스케줄러 간섭 0" 은 단언하지 않는다** (R3 #5) — `UNRESOLVED` 는 reconciliation 의 **명시적 후보**라(`PaymentRefundService:196-199`) 다음 시나리오 동안 generation·last_error·stub ledger 가 바뀌는 게 **정상 동작**이다. 대신 ① **terminal 시나리오를 먼저, `UNRESOLVED` 시나리오를 마지막에 실행하고 즉시 teardown** 하는 순서를 계약으로 고정 ② 판정은 "다른 시나리오 행이 현재 시나리오의 단언을 만족시키지 못한다" 는 **키 기반 검증**으로 한정한다.

### 계약 게이트 (부모 P14)

- [x] **P11.** **매트릭스 정본** `docs/plans/fixtures/saga-contract-matrix.tsv` — 열: `id` · `evidence_type(jvm|e2e)` · `path` · `fault` · `expected` · `evidence_key`. 필수 행: refund result 3종 × 소비자 3곳 · crash matrix 4칸 · `payment.failed` 수렴 · 예약 실패 · timeout 3종 · sweeper · DLQ intake · 스케줄러 배선(P17).
  **`expected` 문법을 기계적으로 고정한다**(N14): **canonical JSON object** — key 사전순 · 숫자/문자열 타입 구분 · `null` 명시 · 배열은 **각 원소의 canonical JSON 문자열로 정렬** 후 비교. 자유 문장 금지. 매트릭스 자체를 JSON Schema 로 검증한다.
- [x] **P12.** **`scripts/saga-contract-matrix-lint.sh` 신설.** 3분기 — `--structure` · `--jvm-evidence` · `--e2e-evidence`. `--structure` 는 열 유효성·중복 id 에 더해 **`path` 가 실제 존재하는 파일/클래스를 가리키는지**, `fault` 가 비어 있지 않은지, **`expected` 가 P11 문법을 만족하는지**, `evidence_key` 유일성을 검사한다. **required-ID 정본은 lint 안에** 둔다(N4).
- [x] **P13.** **JVM 증적 키 (V8 · R1 #6).** 키 = **`testcase@classname`** + `testcase@name` 안의 안정 contract ID `[SAGA-xxx]`. **`testsuite@name` 은 클래스 `@DisplayName` 이라 키로 쓸 수 없다.** self-test 에 클래스 `@DisplayName` 유무 · 중첩 `Outer$Inner` · 동일 ID 중복 fixture 를 넣는다.
- [x] **P14.** **E2E manifest 대조 구조 (R2 #10 · R3 #7).** manifest 에 **`evidence: {<evidence_key>: {actual: <canonical JSON>}}`** 구조를 두고 매트릭스의 각 `evidence_key` 와 **정확히 1:1** 대조한다 — 한 시나리오에 여러 evidence_key 가 있으므로 시나리오 단위 top-level 비교는 성립하지 않고, `run_id`/`started_at` 같은 메타필드 때문에 전체 객체 동등 비교도 항상 실패한다.
  **exact equality 로 고정**(subset 아님)하고, 메타필드는 `evidence` 밖에 둔다. 배열 정렬 규칙은 P11 과 동일.
- [x] **P15.** **파서 self-test.** fixture 로 `missing`·`failure`·`error`·`skipped`·`duplicate`·`stale`(다른 commit sha) 각각 **non-zero** + 구조 훼손 6종 + P13 의 3종 + **같은 의미의 다른 표현(키 순서·공백)은 통과 / 타입 불일치(`"3"` vs `3`)는 non-zero**.
- [x] **P16.** **음성 대조군 상시 실행 (R1 #8 · R2 #2 · R3 #10 · N11·N17).** `scripts/saga-e2e-smoke.sh --negative-control` 로 매 CI 실행. 최소 집합:
  ① **poller 정지 → 시나리오 A 실패**(시작 이벤트가 실제 poller 를 지나는지 검출하는 **유일한** 대조군) ② product-service 정지 → A 실패 ③ 재고 충분 → B 실패 ④ required group 1개 제거 → readiness 실패 ⑤ compose project 2개 동시 기동 성공 ⑥ **egress 차단 양·음 대조** — CI 러너에 임시 TCP 서버를 띄우고 **비격리 control 컨테이너에서는 연결 성공**, internal-only payment 컨테이너에서는 **실패**함을 한 테스트로 묶는다(실패만 보면 canary 가 죽어도 통과한다).
- [x] **P17.** **스케줄러 배선 계약 (V20 · R2 #9 · R3 #11 · N12).** timeout 3종·sweeper 의 현재 테스트는 `@InjectMocks` 객체를 직접 호출해 **`@Scheduled` 를 지워도 통과**한다. 주기를 **Order/Product 각각 base 소유의 타입 안전한 scheduler properties** 로 빼고(ADR-0007 — 동작 정책), 운영 기본 주기·lock 불변식을 테스트로 고정한 뒤 **실제 Spring scheduling 발화 후 DB 상태를 기다리는 통합 테스트**를 추가한다.
  **V20 대응은 결정적이어야 한다**(R3 #11): `lockAtLeastFor=PT30S` 라 기동 직후 빈 작업 선발화가 30초를 잡는다 → seed 를 컨텍스트 기동 **전**에 끝내는 initializer 로 고정하고, **seed-after-start fixture 는 latch 로 "첫 발화가 빈 작업으로 lock 을 잡은 뒤 다음 발화가 timeout" 을 강제**해 타이밍 의존을 제거한다.

### CI 배선

- [x] **P18.** **CI (V6·V7 · R1 #4·#5 · R3 #6).** ① `images` matrix 는 **연속 6개 그대로 유지**한다 — 4개로 줄이면 `image-contract-lint.sh:44-48,74-90` 의 canonical 대조가 실패한다(파서가 연속 `- item` 행만 읽는다). `Save`/`Upload` 조건을 **`github.event_name == 'push' || contains(saga4, matrix.service)` 단일 조건식**으로 교체 + `if-no-files-found: error` ② `e2e` 잡 신설 — `needs: images`, **saga 4개 artifact 정확히** download + `docker load`(개수 검증) + `docker compose -f docker-compose.e2e.yml -p e2e-${{ github.run_id }} up -d --wait` + 시나리오 4종 + P16 + manifest ③ `if: always()` 로 compose logs + manifest + duration 업로드 후 `down -v --remove-orphans` ④ build 잡 = `--structure`+`--jvm-evidence`, e2e 잡 = `--structure`+`--e2e-evidence` ⑤ test artifact glob 을 `*/build/test-results/**`·`*/build/reports/**` 로 확대 ⑥ 매트릭스 게이트는 `./gradlew build` **뒤**.
- [x] **P19.** **실행 예산 (R2 #11).** 앱별 **heap/컨테이너 메모리 상한**, readiness·시나리오·음성 대조군 각각의 **절대 timeout**, e2e 잡 **전체 시간 예산**을 명시한다. **재시도는 인프라 기동에만** 허용하고 상태 단언 실패는 재시도하지 않는다. 구간별 duration 을 artifact 에 남긴다.

### 문서 (부모 P15)

- [x] **P20.** **문서 동기화 + ④ 종결 (R1 #13).** `TASKS.md` ④ ✅ + L-013 처분 + **D-020 신규 등재** · `PHASE4.md` 이력 · Layer 1(`02`/`03`/`04`) saga 흐름·토픽 10종·payload 정정 · ADR-0012 ④ 산출물 대비 실제 범위 차이 · §9 미충족 전량 명시.
  **`task-impl4-d2-saga-e2e-gate.audit.md` 생성/갱신을 이 항목이 소유한다** — §5 각 실패 주입의 **명령·exit code·증적 artifact 링크**를 기록한다. audit 파일 부재를 완료 조건이 실패시킨다.

---

## 4. 영향 파일

| 경로 | 처분 |
|---|---|
| ~~`RefundProperties.java` · `RefundDispatcher.java` · `RefundReconciliationScheduler.java`~~ | **변경 없음** — enabled 스위치는 diff 리뷰 2R #4 로 폐기했다(P1 참조). 두 스케줄러는 상시 배선을 유지한다 |
| `payment-service/.../toss/TossPaymentClient.java` | 수정 — baseUrl 리터럴 제거 |
| `payment-service/src/main/resources/application.yml` · `application-k8s.yml` · `application-local.yml` | 수정 — `base-url` 주입 (P1) |
| `docker-compose.e2e.yml` | **신설** (P3) |
| `scripts/e2e/pg-stub/` | **신설** — PG stub (P2) |
| `scripts/mysql-init/*.sh` | **신설** — run_id marker (P4) |
| `scripts/saga-e2e-smoke.sh` | **신설** — readiness·시나리오 4종·`--negative-control` (P5~P10·P16) |
| `common/.../DlqTopology.java` | 수정 — `consumptionSubscriptions()` 노출 + DLQ intake/quarantine group 상수 (P5) |
| `*/infrastructure/kafka/*Consumer.java` · `global/deadletter/*Consumer.java` | 수정 — group literal → 상수 참조 (P5) |
| `*-service/src/e2e/` (신규 source set) | **신설** — seed runner (P6·P7) |
| `order-service/.../OrderTimeoutScheduler.java` · `product-service/.../StockReservationLeaseSweeper.java` | 수정 — 주기 프로퍼티화 (P17) |
| `docs/plans/fixtures/saga-contract-matrix.tsv` | **신설** (P11) |
| `scripts/saga-contract-matrix-lint.sh` | **신설** (P12) |
| `.github/workflows/ci.yml` | 수정 — 조건식·`e2e` 잡·artifact glob (P18) |
| `docs/TASKS.md` · `docs/progress/PHASE4.md` · `docs/02`/`03`/`04` · audit | 수정 (P20) |

---

## 5. 검증 방법

**"존재한다"·"배선됐다" 는 검증이 아니다.** 항목별로 실패를 주입하고 DB/종료코드로 확인한다.
**모든 행이 CI 상시**다 — R3 #11 로 마지막 `✗` 행(V20 타이밍)을 latch 기반 결정적 테스트로 승격했다.

| 항목 | 실패 주입 | 기대 |
|---|---|---|
| P1 | 운영 소스에 스케줄러 비활성 스위치 존재 | **없어야 한다** — grep `dispatch-enabled`/`reconcile-enabled` 히트 0 |
| P1 | base `base-url` 선언 확인 | 도달 불가 sentinel(`localhost:9`) — 실 PG 호스트가 아님 |
| P1 | 기본 부팅(프로파일 미지정) | **local 의 운영 URL** 이 이긴다(사실 고정 — 초안 진술 반증) |
| P1 | base-url 을 안 주는 프로파일로 기동 | sentinel 이 해석된다 |
| P1 | `k8s` 프로파일에서 `TOSS_BASE_URL` 미주입 / 주입 | **실 PG endpoint 로 해석**(배포가 주입을 기다리지 않는다) / env 값이 이김 |
| P1 | `docker-health-smoke.sh payment-service` | k8s 프로파일 부팅 성공 — 필수 env 추가가 배포 경로를 깨지 않았음 |
| P1 | 앰비언트 `TOSS_BASE_URL`·`TOSS_PAYMENTS_BASE_URL`·`SPRING_PROFILES_ACTIVE` 를 띄운 채 실행 | 결과가 **바뀌지 않는다**(환경 격리). CI 는 `SPRING_PROFILES_ACTIVE=test` 로 빌드한다 |
| P2 | stub 에 `confirm` 요청 | script 를 따른다 — `e2e-ok-*`→200 · `e2e-cfail-*`→500 |
| P2 | stub 에 **미등록 키** 로 confirm/cancel/조회 | **500 `STUB_UNSCRIPTED_KEY`** — 오타 난 키가 조용히 성공하지 않는다 |
| P2 | `ALREADY_CANCELED` script | `GET /payments/{key}` 가 실제 호출됨을 ledger 로 단언. 조회 3분기가 서로 다른 종착 |
| P2 | script 별 ledger 대조 | 성공 `POST×1` / transient 소진 `POST×3` / `ALREADY` `POST×1→GET×1` / reconcile `GET×1,POST×0` — **순서까지** |
| P3 | 같은 compose 를 project 2개로 동시 기동 | 둘 다 정상. 하나 down 해도 다른 쪽 생존 |
| P3 | `internal: true` 제거 / 보조 외부 network 부착 | 정적 계약 self-test non-zero |
| P3 | stub 을 내리고 실행 | dispatcher 실패 — **base-url 배선 검증이며 N2 증명이 아니다**(이름 분리) |
| P4 | 기존 volume 재사용 | 기동 전 volume 검사 실패. 통과시켜도 **`stored_run_id` 불일치**로 readiness 실패 |
| P4 | init 을 정적 `.sql` 로 되돌림 | run_id 주입 불가로 marker 부재 → readiness 실패 |
| P4 | migration 하나를 실패시킴 | `success=0` 으로 readiness 실패 |
| P5 | `DlqTopology` 를 실제 `@KafkaListener` 와 어긋나게 함 | 대조 실패 non-zero |
| P5 | DLQ intake/quarantine group 상수 **참조**를 교환 | lint non-zero (self-test 2종) |
| P5 | 상수의 **리터럴 값**만 교환 | 참조 이름은 그대로라 lint 는 통과 → **`PeekcartService` 정본 대조 JVM 계약 테스트**가 실패 |
| P5 | listener readiness 정본을 `DlqTopology` 밖에 복제 | 이중 정본 검사 non-zero |
| P6 | poller 를 끔 | outbox `PENDING` 잔류 · `processed_events`·전이 없음 → 실패 |
| P6 | 결제 승인을 stub 성공 script 로 바꿈 | `payment.failed` 가 안 나와 시나리오 **실패**(음성 대조군) |
| P6 | 운영 모듈에 E2E 전용 빈/엔드포인트 추가 | `git diff` 상 `*/src/main` 에 E2E 스위치 0 — N16 은 diff 리뷰가 판정한다 |
| P6 | Payment 에 `processed_events` 1행을 기대 | **기대 자체가 틀렸다** — 그 단언을 넣으면 실패해야 한다(R3 #2) |
| P6 | product-service 정지 | 제한 시간 초과 실패 — vacuous-pass 아님 |
| P7 | 재고를 충분히 seed | `success=false` 미발생으로 시나리오 **실패** |
| P8 | 두 트리거 경로 동시 투입 | `payment_refunds` 정확히 1행 |
| P8 | stub 을 4xx / 타임아웃으로 전환 | Order `REFUND_FAILED`+`failure_code` / **전이 없음**. 셋이 다른 종착 |
| P9 | DLQ group offset rewind 후 재소비 | 새 행 없이 `attempt_count=2`. 6컬럼 중 하나라도 null 이면 실패 |
| P10 | 단언을 `scenario_id` 없는 전역 건수로 되돌림 | 앞 시나리오 잔여로 **통과해 버린다** → 그 통과를 self-test 가 실패로 잡는다 |
| P10 | `UNRESOLVED` 시나리오를 마지막이 아닌 위치로 | 실행 순서 계약 검사 non-zero |
| P12 | 매트릭스 행 삭제 / 없는 id 추가 | 둘 다 non-zero |
| P12 | `path` 를 없는 파일로 / `expected` 를 자유 문장으로 | 둘 다 non-zero |
| P13 | 등재 테스트 `@Disabled` / display name 에서 ID 제거 | 둘 다 non-zero |
| P13 | 클래스 `@DisplayName` 이 있는 fixture | `testsuite@name` 을 키로 쓰면 못 찾는다 — `testcase@classname` 사용을 고정 |
| P14 | manifest 에 임의 상태 + `result=success` | `evidence_key` 1:1 대조 불일치로 non-zero |
| P14 | `evidence` 에 `run_id` 를 섞음 | exact equality 위반 non-zero |
| P14 | 키 순서만 다르게 / `"3"` vs `3` | 전자는 **통과**, 후자는 non-zero |
| P15 | fixture 6종 + 구조 6종 + P13 3종 | 전부 non-zero. 정상 fixture 만 zero |
| P16 | canary 서버를 내림 | **양성 대조군(control 컨테이너)이 실패**하므로 non-zero — 실패만 보는 false-green 차단(N17) |
| P17 | `@Scheduled` 또는 `@SchedulerLock` 제거 | 계약 테스트 non-zero. 통합 테스트는 발화 없어 타임아웃 |
| P17 | seed-after-start fixture | latch 로 첫 발화가 lock 을 잡고 다음 발화가 timeout — **타이밍 의존 없이 결정적** |
| P18 | artifact 1개를 빠뜨린 채 e2e 진입 | download 개수 검증 실패 |
| P18 | `images` matrix 를 4개로 줄임 | `image-contract-lint` canonical 대조 non-zero |
| P18 | e2e 잡에서 `--jvm-evidence` 실행 | 없는 evidence type 이 조용히 제외되지 않고 명시적 실패 |
| P19 | 시나리오 timeout 을 무한대로 / 상태 단언에 재시도 부착 | 둘 다 예산 검사 non-zero |
| P20 | audit 파일 삭제 | 완료 조건 검사 실패 |

---

## 6. 완료 조건

> **PR 분할 (2026-08-28, 사용자 승인)**: **④-d-2a = P1~P9**(안전장치·stub·격리 compose·cold start·readiness 정본·시나리오 4종) / **④-d-2b = P10~P20**(시나리오 격리 강화·계약 매트릭스·matrix lint·JVM 증적 키·manifest 1:1 대조·파서 self-test·음성 대조군 CI 상시·스케줄러 배선 계약·CI 배선·실행 예산·문서 동기화·**④ 종결**).
> 아래 조건은 **④-d-2 전체**의 것이며, ④-d-2a 의 완료 판정은 §3 P1~P9 체크박스 + §5 표 중 해당 행 + audit 증적이다.


1. §1 의 N1~N17 이 **전부 거짓**.
2. `./gradlew build` 그린 · lint **12종**(신규 `saga-contract-matrix-lint`) + 각 self-test 그린.
3. CI 에서 `e2e` 잡이 시나리오 4종 + **P16 음성 대조군 6종**을 통과하고 manifest·compose logs·duration artifact 가 남으며 **P19 시간 예산 안에** 끝난다.
3b. **`hpx_plan_lint` 통과** — 필수 섹션 6종 + stable id 등장 순서 P1~P20 (R3 #9).
4. 매트릭스 게이트가 build·e2e 두 단계에서 각각 자기 evidence type 을 검사한다.
5. **환불 체인이 stub 을 경유해 3소비자 종결까지 도달**했고, 결과 3종이 서로 다른 종착으로 갈렸다.
6. §5 의 실패 주입 표가 전부 실제로 실행되어 기대 결과를 냈고, **`task-impl4-d2-saga-e2e-gate.audit.md` 에 명령·exit code·artifact 링크가 기록**돼 있다(false-green 자백 포함).
7. `TASKS.md` ④ = ✅ 이고 §9 미충족이 그 자리에 명시돼 있다.

## 7. 승계 — 1R 에서 확정된 것

| 항목 | 결정 |
|---|---|
| E2E 형태 | **스크립트**(`scripts/saga-e2e-smoke.sh`). `e2e-tests` Gradle 모듈은 ADR-0011 D1 모듈 목록 개정 사안이라 배제 |
| ~~환불 체인 범위~~ | ~~트리거 구간까지~~ — **R1 P0 로 철회**. PG stub(P15) 도입으로 dispatcher→PG→회신→3소비자 종결까지 닫는다 (§2.1) |
| CI 이미지 전달 | `images` artifact 를 PR 에서도 **저장·업로드** → `e2e` 잡이 download + `docker load` (V6 — 두 단계 모두) |
| 매트릭스 게이트 | lint 내부 required-ID 정본과 매트릭스 id 집합 **정확 일치** |
| CI 실행 순서 | policy lint 는 `./gradlew build` 보다 먼저 → 매트릭스 게이트는 build 뒤 |
| E2E SQL | 스키마 재대조 통과 — `peekcart_*`·`inventories.stock`·`refund_result`·group 문자열 전부 실제와 일치 |

---

## 8. 왜 d-1 과 나뉘었나

계획 리뷰 2R 9건의 영역 분포: **E2E 하네스·시나리오 5 · 매트릭스 게이트 3 · 메트릭/alert 1.**
메트릭(P11)은 E2E 인프라에 의존하지 않고 지적도 1건이라 ④-d-1 로 먼저 냈다. 건수 추세가 아니라 **영역 분포**가 근거다.

---

## 9. ④ 종결 시 명시할 미충족

1. **alert 발화 미검증** — ADR-0015:72-74 가 정적 lint 범위를 규정. 실제 평가는 Prometheus 기동 + fixture 필요.
2. **DLQ replay 미구현** — ④-c-2b(ADR 선행 D1~D7).
3. **매트릭스 lint 는 "테스트가 옳은가" 를 못 본다** — 실행 여부와 `expected` 대조까지다. 구조적 한계.
4. **PG stub 은 Toss 가 아니다** — 계약 형태만 흉내낸다. 실 API 스펙 변경·실호출 reconciliation 은 **D-020** 소관이며, 이 PR 은 `base-url` 설정화까지만 흡수한다.
4b. **시나리오는 한 stack 을 공유한다** — 시나리오별 새 stack 이 가장 안전하지만 실행 예산(P19) 때문에 실행 순서 계약 + 키 기반 판정(P10)으로 대체했다.
5. **gateway 인증 경로는 미검증** — E2E 는 실제 HTTP 진입점(`/api/v1/cart/items`·`/api/v1/orders`·`/api/v1/payments/confirm`)을 **지난다**. 다만 `DUAL_ACCEPT` 평문 헤더를 쓰므로 **gateway 서명 내부 토큰과 `SIGNED_ONLY` 는 검증되지 않는다**(구현 ③ GKE smoke 소관). user-service 도 미포함이다.
6. **user-service·gateway 는 E2E 에서 제외** — saga 체인에 참여하지 않는다.
7. **부하·동시성 시나리오 없음** — 단건 체인의 정확성만 본다.
8. **시나리오 C 는 요청 토픽부터 시작한다** — 트리거 **감지**(Product marker · Order 보상 원장)는 "결제완료가 이미 취소된 주문에 도착" 하는 경합이라 HTTP 로 결정적 재현이 불가능하다(`Payment.ensureConfirmable:181-192` 가 PENDING 아닌 결제의 승인을 거부). 감지 → 요청 발행이 같은 트랜잭션이라는 계약은 ④-c-1b 통합테스트가 덮는다. E2E 가 cross-service 로 증명하는 구간은 **요청 토픽 → fence → dispatcher → PG(stub) → `payment.refunded` outbox `PUBLISHED`** 까지다. 회신 소비 3곳의 종결은 §9-10 대로 미충족이다.
9. **4종 연속 실행이 불안정하다 (실측)** — 시나리오는 각각 실제 스택에서 통과했고(A 다회 · B 단독/A직후 · C · D), 4종을 한 번에 돌리면 뒤쪽 시나리오가 consumer 지연으로 타임아웃하는 경우가 있다. 관측: 단일 브로커에 group 28개가 붙은 상태에서 `order.created`·`product.updated` 소비가 수십 초~수 분 지연. 완화로 **자동생성 토픽 1파티션 고정 · 앱 순차 기동 · 시나리오 상한 180s** 를 넣었으나 제거하지 못했다. 근본 대응은 **P10(시나리오 격리)·P19(실행 예산)** 이며 후속 PR 소관이다.
10. **환불 회신 소비 3곳의 종결 미실증** — 시나리오 C 는 `payment.refunded` 발행까지만 본다. Order `RESOLVED`/`REFUND_FAILED` · Product 종결 컬럼 3개 · Notification `PAYMENT_REFUNDED` 와 결과 3종 분기(4xx·타임아웃)는 선행 원장 seed 가 필요해 ④-d-2b 소관이다. 계약 자체는 ④-c-1b 통합테스트가 덮는다.
11. **운영 kill switch 없음(의도)** — 환불 스케줄러를 끄는 프로퍼티는 만들지 않았다. 필요하다면 ADR + readiness/경보 계약을 먼저 세운 뒤 별도로 도입한다.
12. **stub ledger 계약을 성공 script 에만 적용** — `POST×1`+Idempotency-Key 는 단언하나 transient 소진 `POST×3` · `ALREADY` `POST→GET` · reconciliation `GET×1,POST×0` 는 미실행(계획 P2 의 순서 계약 일부). ④-d-2b.
13. **시나리오 D 의 `attempt_count=2` 미검증** — 재발행은 offset 이 달라져 다른 좌표가 되므로(V15) DLQ consumer group offset rewind 가 필요하고, 그건 실행 중 group 을 멈춰야 해서 다른 시나리오와 간섭한다. 1행 + 식별자 6컬럼 non-null 까지만 본다.

## 10. 정정 이력

**2026-08-27 — 이전 판("범위 정의", 2026-08-26)의 진술 2건이 코드 검증에서 뒤집혔다.**

1. **D1 이 `RefundDispatcher` 하나만 지목했다** → `RefundReconciliationScheduler` 도 같은 `RefundExecutor` 로 Toss 조회를 부른다(`:39-41`). dispatcher 만 끄면 **reconcile 경로로 여전히 나간다** — 프로퍼티를 2개로 확대(P1).
2. **D4 가 compose "격리" 만 요구했다** → `docker-compose.yml` 에는 **앱 서비스 정의가 아예 없다**(인프라 3종뿐). 격리 이전에 order/product/payment/notification 4개를 신설해야 한다(P2).
3. D6(신규 alert 라벨 계약)은 ④-d-1 #91 이 이미 처리 완료 → 이 계획에서 삭제(V13).
4. 이전 판 §3 의 "test artifact glob 은 `build/reports/` 하나뿐" 은 **사실로 확인**됐다(V7).

**2026-08-27 — Codex 계획 리뷰 R1(13건: P0 1 · P1 9 · P2 3) 전량 반영. 내 진술 3건이 추가로 반증됐다.**

5. **V8 이 틀렸다** — `testsuite@name` 이 classname 이라고 적었으나 **클래스 레벨 `@DisplayName`** 이다. 처음 열어본 XML 이 마침 클래스 `@DisplayName` 이 없어 FQCN 으로 보였을 뿐이고, 있는 파일(`ProductPriceCacheSagaIntegrationTest.xml:2`)에서는 `name="가격 캐시 CQRS 소비자 통합 테스트"` 다. **키를 그대로 뒀으면 파서가 등재 테스트를 전부 못 찾고, 그게 "증적 없음" 이 아니라 매칭 실패로 조용히 흘렀을 수 있다.**
6. **V9 의 consumer 클래스 수가 틀렸다**(8 → **9**). 그리고 `GROUP_*` grep 대조는 값이 다음 줄에 있는 상수(`CompensationRequestConsumer.java:34-37`)와 annotation literal 인 DLQ group 을 놓친다 — **이미 존재하는 `DlqTopology` 를 정본으로 쓰지 않은 것이 설계 실수였다**(V14).
7. **P13 이 CI 를 절반만 고쳤다** — `Upload` 앞의 `Save image for publish` 도 push 전용이라(`ci.yml:147-149`), 조건을 upload 에서만 지우면 PR 에 파일 자체가 없다.

**P0 수용 — ④ 종결의 자기모순을 stub 으로 해소했다.** 이전 판은 §8-1 에서 "부모 P12 를 완전히 닫지 못했다" 고 자인하면서 같은 문서에서 ④ 를 종결했다. `base-url` 설정화의 D-020 이연을 철회하고 PG stub(P15)을 넣어 환불 체인 전구간을 닫는다.

**신규 작업 항목 4개**: P15(PG stub) · P16(음성 대조군 CI 상시) · P17(스케줄러 배선 계약) · P18(Flyway cold start). 명제도 N10~N12 로 확장했다.

**2026-08-27 — Codex 계획 리뷰 R2(11건: P1 10 · P2 1). 10건 반영 · 1건 기각. 지적 대부분이 "R1 수정이 만든 새 결함" 이었다.**

8. **R1 의 outbox seed 결정이 그 자체로 틀렸다** — "outbox 에 넣으면 producer 직렬화까지 검증된다" 고 적었으나, `buildRecord()` 는 `event.getPayload()` **문자열을 그대로 싣는다**(V16). DTO 조립·`writeValueAsString` 은 `*OutboxEventPublisher` 구간이라 SQL INSERT seed 로는 여전히 우회된다. **R1 이 "Kafka 직접 발행 우회" 를 고치면서 한 칸 아래로 옮겼을 뿐이었다** → seed runner 가 실제 publisher 를 호출하도록 재정정.
9. **R1 이 `DlqTopology` 를 잘못된 의미로 확대 적용했다** — 그 클래스의 group 은 **업무 실패 소유자** group 이지 DLQ intake listener group 이 아니다(V17). readiness 정본으로 쓰면 `order-svc-dlq-group` 계열이 통째로 빠진다 → 두 개념을 분리(P3).
10. **R1 이 상시 승격한 음성 대조군 목록에서 정작 가장 중요한 것이 빠졌다** — poller 정지 대조군은 시작 이벤트 경로를 검출하는 **유일한** 수단인데 `CI 상시=✗` 로 뒀다. N11 과 정면으로 모순 → P16 필수 집합으로 승격.
11. **P18 의 cold start 판정식이 warm reuse 를 구별하지 못했다** — 직전 실행에서 전부 성공한 volume 은 `flyway_schema_history` 조건을 그대로 만족한다 → volume 존재 검사 + `run_id` marker 로 교체.
12. **N2 검증이 false-green 이었다** — "stub 을 내리면 실패" 는 `internal: true` 가 삭제된 상태에서도 똑같이 실패한다. base-url 배선만 증명하고 network 격리는 증명하지 않는다 → 정적 계약 + 컨테이너 canary TCP 로 분리.
13. **P15 stub 이 `GET /payments/{key}` 를 빠뜨렸다** — `ALREADY_CANCELED` 와 reconciliation 이 **항상 조회를 먼저** 부른다(V18). 조회 없이는 crash matrix 절반이 도달 불가다.
14. 그 외: 시나리오 간 상태 오염(P19 신설·N13) · `expected` 문법 미고정(P8·N14) · ShedLock 선발화(V20·P17) · 실행 예산(P20).

**기각 1건 — R2 #7 (`base-url` 을 필수값으로).** ADR-0007 의 판단 기준은 "환경마다 **달라야** 하는 값인가" 이고, 실 Toss endpoint 는 **환경 불변의 단일 값**이다(`secret-key` 가 프로파일로 갈린 건 값이 환경마다 다른 자격증명이기 때문, V19). base 기본값을 유지하고 `application-k8s.yml` 이 덮지 않음을 테스트로 고정한다. "실 PG 로 새는 것" 의 실효 방어는 프로퍼티 필수화가 아니라 **R2 #6 의 network-level 강제**이며, 그쪽은 전량 반영했다.

**신규 작업 항목 2개**: P19(시나리오 격리) · P20(실행 예산). 명제 N13~N15 추가. 총 20개 항목.

**2026-08-27 — Codex 계획 리뷰 R3(12건: P1 10 · P2 2) 전량 반영. R2 수정이 만든 새 결함이 다시 다수였다.**

15. **R2 가 `DlqTopology` 를 철회하면서 반대 방향으로 과잉교정했다** — 업무 구독 21쌍은 **이미 `DlqTopology` 안에 있고** `.dlq` suffix 제거로 원본 토픽을 유도할 수 있다. 신설 정본에 복제하면 **이중 정본이 되어 양쪽을 함께 잘못 고치면 각자의 자기대조가 모두 통과한다.** → 업무는 유도, DLQ intake/quarantine group 만 신설(P5).
16. **R2 의 `run_id` marker 가 도달 불가였다** — warm datadir 에서는 `docker-entrypoint-initdb.d` 자체가 재실행되지 않아(`scripts/mysql-init/01-*.sql:2-4` 가 "첫 부팅 시" 를 명시) "기존 marker 면 실패" 분기에 **들어가지 않는다.** 게다가 현재 자산이 정적 `.sql` 뿐이라 run_id 주입 경로도 없었다 → readiness 가 `stored_run_id == current_run_id` 를 검사하고 `.sh` init 으로 교체(P4).
17. **R2 의 seed runner 가 운영 이미지에 발행 스위치를 심는다** — `@ConditionalOnProperty` 하나로 인증·업무 진입점을 우회해 합성 saga 이벤트를 발행할 수 있다 → E2E 전용 source set 분리(N16). 더불어 `publishPaymentFailed()` 는 상태를 바꾸지 않으므로 runner 가 `payment.fail()` 과 한 트랜잭션으로 묶어야 실제 경로와 같아진다.
18. **P10(격리)이 P3(스케줄러 상시 활성)과 충돌했다** — `UNRESOLVED` 는 reconciliation 의 **명시적 후보**라(`PaymentRefundService:196-199`) 다음 시나리오 동안 값이 바뀌는 게 정상이다. "간섭 0" 단언은 정상 동작을 실패로 만든다 → 실행 순서 계약 + 키 기반 판정으로 교체.
19. **P18/P19 가 서로 모순됐다** — "PR 에서도 조건 전부 제거"(6개 업로드)와 "PR 은 4개만"이 충돌하고, matrix 를 4개로 줄이면 `image-contract-lint:44-48` canonical 대조가 실패한다 → matrix 6 유지 + 단일 조건식으로 통합.
20. **`payment.failed` 의 `processed_events` 를 4서비스로 적었다** — 소비자는 Order/Product/Notification **3곳**이고 **Payment 는 자기 이벤트를 소비하지 않는다**(`DlqTopology:40-68`, ADR-0012:82). Payment 쪽 기대는 Outbox `PUBLISHED` 다.
21. **egress canary 가 false-green 이었다** — "연결 실패" 만 보면 canary 서버가 죽어도 통과한다 → **양·음 대조를 한 테스트로** 묶음(N17).
22. **`expected` ↔ manifest 대조 자료구조가 없었다** — 한 시나리오에 evidence_key 가 여럿이라 시나리오 단위 비교가 성립하지 않고, 메타필드 때문에 전체 동등 비교는 항상 실패한다 → `evidence: {<key>: {actual}}` 1:1 + exact equality(P14).
23. **작업 항목 ID 가 실제 lint 를 위반하고 있었다** — `.claude/scripts/lib/sync.sh:38-44` 의 `hpx_plan_lint` 는 **등장 순서 = P1..Pn** 을 강제하고 `목표/목적`·`영향 파일` 섹션을 필수로 본다. R1/R2 에서 신규 항목을 뒤에 붙인 결과 `P1,P15,P2,P18,...` 이 됐다 → 등장 순서대로 전면 재번호 + §4 영향 파일 신설. **lint 를 직접 실행해 통과를 확인했다(20 items).**
24. V20 타이밍 행이 유일한 `CI 상시 ✗` 였고 기대 결과가 "불안정해짐" 이라 증명력이 없었다 → latch 기반 결정적 fixture 로 승격(P17). **이제 §5 에 `✗` 행이 없다.**

**기각 철회 1건 — R2 #7 을 R3 #8 로 수용했다.** `base-url` 을 "환경 불변 단일 값" 이라며 base 소유로 남겼는데, **내 계획서 자체가 운영 URL 과 stub URL 로 값을 다르게 쓴다.** ADR-0007 Decision 표는 연결 정보를 프로파일 허용으로 분류한다. 내 기각 논거는 내 계획에 의해 반증됐다 → 기본값 없이 fail-fast + 프로파일별 명시 주입(P1).

### 번호 매핑 (R2 판 → 최종)

`P1→P1` · `P15→P2` · `P2→P3` · `P18→P4` · `P3→P5` · `P4→P6` · `P5→P7` · `P6→P8` · `P7→P9` · `P19→P10` · `P8→P11` · `P9→P12` · `P10→P13` · `P11→P14` · `P12→P15` · `P16→P16` · `P17→P17` · `P13→P18` · `P20→P19` · `P14→P20`
