# ④-d-2 계획 리뷰 audit

## 2026-08-27 13:59 — 계획 리뷰 라운드 1
- 항목: 13건 (P0:1, P1:9, P2:3)
- 처리: 반영 13건 / 기각 0건
- 뒤집힌 전제:
  - V8 — `testsuite@name` 은 classname 이 아니라 클래스 `@DisplayName`. FQCN 은 `testcase@classname`
  - V9 — 업무 consumer 클래스 8 → **9**. group 상수가 다음 줄에 있는 케이스로 grep 대조 불가
  - V6 — `Save image for publish` 도 push 전용이라 upload 조건만 제거하면 PR 에 파일 자체가 없음
- 결정: P0(④ 종결 자기모순) → PG stub 도입으로 환불 체인 전구간을 범위에 넣고 ④ 종결 유지 (사용자 승인)
- 신규 항목: P15(PG stub) · P16(음성 대조군 상시) · P17(스케줄러 배선) · P18(Flyway cold start)
- raw: `.cache/codex-reviews/plan-task-impl4-d2-saga-e2e-gate-1787806325.json`

## 2026-08-27 — 계획 리뷰 라운드 2
- 항목: 11건 (P0:0, P1:10, P2:1)
- 처리: 반영 10건 / 기각 1건
- 기각: R2 #7(`base-url` 필수값화) — "환경 불변 단일 값" 근거. **R3 에서 철회됨**
- 뒤집힌 전제:
  - V16 — outbox 행은 이미 직렬화된 문자열. SQL INSERT seed 는 publisher 직렬화를 **여전히 우회**
  - V17 — `DlqTopology` 의 group 은 업무 실패 소유자 group 이지 DLQ intake listener group 이 아님
  - V18 — `ALREADY_CANCELED`·reconciliation 이 `GET /payments/{key}` 를 항상 먼저 호출
  - V20 — `lockAtLeastFor=PT30S` 선발화가 짧은 override 를 무효화
  - R1 이 상시 승격한 음성 대조군에서 **가장 중요한 poller 정지 대조군이 빠져 있었음**(N11 과 모순)
  - P18 cold start 판정식이 warm reuse 를 구별 못 함
- 신규 항목: P19(시나리오 격리) · P20(실행 예산)

## 2026-08-27 — 계획 리뷰 라운드 3 (최종)
- 항목: 12건 (P0:0, P1:10, P2:2)
- 처리: 반영 12건 / 기각 0건 (R2 기각 1건은 **철회**)
- 뒤집힌 전제:
  - R2 #7 기각 논거가 **내 계획서 자체에 의해 반증** — 운영 URL 과 stub URL 로 값이 갈리므로 `base-url` 은 환경별 연결 정보
  - `DlqTopology` 철회가 과잉교정 — 업무 구독 21쌍은 이미 그 안에 있어 신설 시 **이중 정본**
  - `run_id` marker 분기가 **도달 불가** — warm datadir 은 initdb 스크립트를 재실행하지 않음
  - `payment.failed` 소비자는 3곳. **Payment 는 자기 이벤트를 소비하지 않아** `processed_events` 행이 생기지 않음
  - P18/P19 상호 모순 + `image-contract-lint` 가 matrix 6 을 강제
  - **`hpx_plan_lint` 실제 위반** — 등장 순서 P1..Pn 강제 + `목표/목적`·`영향 파일` 필수 섹션
- 조치: 전면 재번호 P1~P20(등장 순서), §4 영향 파일 신설, `✗` 검증 행 0으로, lint 직접 실행 통과
- 수렴 판정: **P1 = 0 이 아니므로 상한(3회) 도달로 종료.** 잔여 위험은 §9 미충족 + 이 audit 에 기록

## 2026-08-28 — 구현 (④-d-2a: P1~P9) · 실제 스택 실행 증적

**실행 결과 (docker compose E2E, 실제 4서비스 + Kafka + MySQL + PG stub)**
- readiness: **통과** — run marker 대조 · flyway 6/5/5/3 success · 앱 4 health · 토픽 20 · consumer group 28 (active member ≥ 1, 할당 partition ≥ 1)
- 시나리오 A(결제 실패 체인): **통과** (다회) — `payment_status=FAILED` · `order_status=CANCELLED` · `cancel_reason=PAYMENT_FAILED`(outbox payload) · `reservation=RELEASED` · `stock_restored=5` · `processed_events` order/product/notification 각 1 · `payment.failed`/`order.cancelled` outbox `PUBLISHED` · 알림 2행
- 시나리오 B(예약 실패 체인): **통과** (단독 실행 · A 직후 실행 각 1회) — `order_status=CANCELLED` · `cancel_reason=RESERVATION_FAILED` · `reserved_remaining=0`
- 시나리오 C(환불 체인 전구간): **통과** — fence `refund_rows=1` · `refund_status=SUCCEEDED` · `payment_status=REFUNDED` · `payment.refunded` outbox `PUBLISHED` · stub 취소 POST **정확히 1회** · Idempotency-Key 전송
- 시나리오 D(DLQ intake): **통과** — 원장 1행 · 식별자 6컬럼 non-null · `failed_consumer_group=order-svc-payment-failed-group`
- **4종 연속 실행은 불안정** — 계획 §9-9 에 미충족으로 기록

**실행이 잡아낸 결함 (전부 내 코드/기대의 오류, 운영 코드 아님)**
1. `categories` 에 `created_at` 컬럼 없음 — seed SQL 오류
2. `payments` 에 `updated_at` 컬럼 없음 — seed SQL 오류
3. 장바구니 담기 응답이 200 이 아니라 **201**
4. envelope 필드가 `data` 가 아니라 **`payload`** — 초안이 `.get("data", payload)` 로 조용히 fallback 해 `reason=None` 이 나왔고, 이는 **계약 위반처럼 보였다**(실제로는 파서 오류). 엄격 실패로 교체
5. `dead_letter_records.origin_topic` 은 `.dlq` 가 아니라 **원본 토픽**(`DLT_ORIGINAL_TOPIC`)
6. **`wait_price_cached` 가 vacuous wait** — group 이름만 보고 아무 행이나 있으면 통과해, 앞 시나리오가 남긴 행으로 **기다리지 않고 즉시 통과**했다(R2 #3 이 경고한 시나리오 간 오염이 실제로 발생). `product_id` 로 키잉해 수정
7. **가드 거부와 결제 실패를 구분하지 못했다** — `ready_for_payment` 가 서기 전에 승인을 불러 `PAY-008` 을 맞고도 "승인 실패" 로 넘겼다. 그 경로는 Toss 를 부르지도 않아 `payment.failed` 가 발행되지 않는다. `PAY-005` 명시 단언 추가
8. `stock_before` 를 주문 **후**에 읽어 비동기 예약 차감의 전/후가 불확정 → 초기 재고 기준으로 교체
9. **`TOSS_BASE_URL` 이 `application-local.yml` 의 리터럴을 이기지 못했다** — dispatcher 가 실제로 `api.tosspayments.com` 을 호출했고 **`internal: true` 가 `UnknownHostException` 으로 막았다**. 격리가 없었으면 실 PG 로 나갔다. → `TOSS_PAYMENTS_BASE_URL`(프로퍼티 직접 override)로 교체. **N2 network-level 강제의 라이브 증거이기도 하다**
10. readiness health 폴링이 `URLError` 를 안 잡아 일시적 DNS 실패 하나로 죽었다
11. `docker compose ... down` 이 `E2E_RUN_ID` 를 요구해 **정리 절차가 실패** → 잔여 스택 13개가 이후 실행과 자원을 다퉜다. compose 기본값 부여 + 강제는 wrapper 로 이동

**환경 튜닝**: 자동생성 토픽 1파티션 고정(3파티션 × 20토픽 × 28group 메타데이터 부하로 소비 3분 지연 실측) · 앱 **순차 기동**(동시 기동 시 order-service 가 360s healthcheck 창을 넘김) · 시나리오 상한 180s

**검증**: 8모듈 **800 테스트 0 실패**(792 → 신규 8) · lint **13종**(신규 `e2e-network-contract-lint` self-test 9 · `kafka-subscription-contract-lint` self-test 7 · pg-stub self-test 19)

**분할**: 사용자 승인으로 ④-d-2a(P1~P9) / ④-d-2b(P10~P20 + ④ 종결)로 나눔

## 2026-08-28 — diff 리뷰 라운드 1
- 항목: 10건 (P0:0, P1:3, P2:7)
- 처리: 반영 10건 / 기각 0건
- 주요: `.pyc` 커밋 · 시나리오 B 가 outbox 존재만 확인 · 시나리오 D 가 행수/attempt_count 미단언 ·
  flyway 검사가 '성공 1건 이상' 이라 최신 누락 미검출 · base-url 테스트가 YAML 문자열만 검사 ·
  DLQ lint 가 `DlqTopology.` 접두사만 확인 · reconcile 주기 override 가 `lockAtLeastFor=PT30S` 와 모순
- **뒤집힌 전제**: base 의 `spring.profiles.active` 가 `local` 이라 **기본 부팅은 sentinel 이 아니라
  local 의 운영 URL 을 쓴다**. 새로 붙인 ConfigData 테스트가 내 진술을 반증했다.
- raw: `.cache/codex-reviews/diff-d2a-r1.json`

## 2026-08-28 — diff 리뷰 라운드 2
- 항목: 5건 (P0:0, P1:4, P2:1)
- 처리: 반영 5건 / 기각 0건
- **1R 수정이 만든/남긴 새 결함 3건**:
  1. 1R 에서 추가한 시나리오 B 알림 대기가 **여전히 vacuous** — 같은 시나리오의 `order.created`
     소비가 만든 `ORDER_CREATED` 행으로 즉시 만족됐다(A 의 user=100 은 배제했지만 B 자신의 선행 행).
     A 도 동일 → **`type` 으로 키잉**(`PAYMENT_FAILED` / `ORDER_CANCELLED`)
  2. 1R 에서 붙인 `@Nested Resolution` 테스트가 **앰비언트 `TOSS_BASE_URL` 을 읽어** 환경에 따라
     통과/실패가 갈렸다(리뷰어가 `TOSS_BASE_URL=http://ambient.example:7777` 로 재현). systemEnvironment
     에서 그 키를 제거하는 initializer 로 격리 → 같은 환경변수를 띄우고 재실행해 통과 확인
  3. 1R 의 "정확 상수명 동등 비교" 도 **리터럴 값만 교환하면 통과**한다(참조 이름은 그대로).
     그리고 `PeekcartService.dlqListenerGroup()`/`quarantineListenerGroup()` 이 **이미 정본으로 존재**했다
     — 내 새 상수는 중복 정본이었고, 이는 R3 #3 에서 지적받은 실수의 재현이다.
     → `DlqListenerGroupContractTest` 로 상수 ↔ 정본 정합을 전 enum 에 대해 고정
- **P1 스위치 폐기(2R #4)**: `dispatch-enabled`/`reconcile-enabled` 는 설계가 stub+internal network 로
  바뀌면서 **아무도 쓰지 않게 됐는데**, 환경변수 하나로 운영 스케줄러 빈을 조용히 없애는 위험만 남았다
  (ADR-0018 은 두 잡을 미결 환불 수렴의 필수 구성요소로 규정). 프로퍼티·`@ConditionalOnProperty`·토글
  테스트를 전부 제거하고 P1 을 `base-url` 설정화로 축소했다.
- raw: `.cache/codex-reviews/diff-d2a-r2.json`

### 리뷰 수정 후 재실행 증적 (2026-08-28)
- `./gradlew test` **BUILD SUCCESSFUL** (11m 3s) · **804 테스트 0 실패**
- lint: `e2e-network-contract`(self-test 9) · `kafka-subscription-contract`(self-test **9**) ·
  pg-stub self-test 19 · 기존 10종 그린
- `TOSS_BASE_URL=http://ambient.example:7777 ./gradlew :payment-service:test --tests '*TossBaseUrlContractTest*' --rerun-tasks` → **BUILD SUCCESSFUL** (환경 격리 확인)
- E2E 재실행: **A 통과**(`notification_payment_failed=1` — type 키잉이 실제로 값을 잡았다) ·
  **B 통과**(`order_cancelled_outbox=PUBLISHED`, `notification_order_cancelled=1`, `stock_intact=1`) ·
  **C 통과** · **D 통과**(`rows=1`, `attempt_count=1`)
- **4종 연속 실행은 여전히 불안정** — 이번에도 C 직후 D 가 타임아웃했고 단독 재실행은 통과했다(§9-9)

## 2026-08-29 — diff 리뷰 라운드 3 (1차 시도: Codex 사용량 한도로 미실행 → 한도 해제 후 재실행)
- 1차 시도는 `You've hit your usage limit` 로 종료했다. 그 사이 R3 프롬프트에서 "의심하라" 고 지정했던 5개 지점을 **직접 점검**했다:
  - (a) `stripAmbientBaseUrl` 부작용 — 평범한 `MapPropertySource` 로 교체하면 **환경변수 완화 매핑이 사라져** 다른 프로퍼티 해석까지 바뀐다 → `SystemEnvironmentPropertySource` 로 복원. unchecked cast 도 제거
  - (b) 스위치 제거 후 죽은 코드 — `dispatchEnabled`/`reconcileEnabled`/`@ConditionalOnProperty` 잔존 0, `@Scheduled`/`@SchedulerLock` import 는 정상 유지
  - (c) quarantine null 분기 — `PeekcartService` 4값 전부 매핑이 있어 현재는 안전하나, **서비스 추가 시 조용한 NPE** 가 되므로 "모든 서비스가 소유 매핑을 갖는다" 계약 테스트를 추가
  - (d) type 키잉 ↔ `NotificationType` — `PAYMENT_FAILED`/`ORDER_CANCELLED` 실재 확인. 시나리오 A 는 `order.cancelled(reason=PAYMENT_FAILED)` 를 Notification 이 스킵하므로(④-b) `PAYMENT_FAILED` 키잉이 유일하게 의미 있는 조건이고, B(user 200)와 사용자도 다르다
  - (e) 계획서 정합 — §6 완료 조건이 ④-d-2 **전체**의 것이라 분할 주석을 추가
### 라운드 3 (재실행)
- 항목: 4건 (P0:0, P1:1, P2:3) · 처리: 반영 4건 / 기각 0건
- **3R 수정이 만든 새 결함 1건 (P1)**: 앰비언트 환경 격리를 `SystemEnvironmentPropertySource` 로 복원하자
  **완화 매핑 때문에 `TOSS_PAYMENTS_BASE_URL` 이 다시 유입**됐다 — 하필 **E2E compose 가 쓰는 이름**이다.
  리뷰어가 `TOSS_PAYMENTS_BASE_URL=http://ambient-relaxed.example:8888` 로 재현해 Resolution 5건 중 4건 실패.
  → 이름 열거를 버리고 **정규화 키**(대문자·`_`·`.`·`-` 제거) 비교로 교체하고 `systemProperties` 도 함께 거른다.
  같은 재현 조건 + `-Dtoss.payments.base-url=...` 으로 재실행해 통과 확인.
- 문서 정합 3건: 영향 파일 표가 폐기된 스위치를 여전히 지시 · 상단 "이 PR 이 ④ 를 종결" 이 §6 분할과 충돌 ·
  §9-5 가 "HTTP 표면을 지나지 않는다" 로 적혀 있으나 실제로는 진입점을 지난다(미검증은 gateway/`SIGNED_ONLY`) ·
  §9-8 종점을 `payment.refunded` outbox 까지로 정정 · `TossPaymentClient` Javadoc 의 "기본값이 없다" 정정
- **확인된 것**: A/B 의 `type`+`user` 조합은 겹치지 않는다 · DLQ 상수 **리터럴 값 교환**은 `PeekcartService`
  정본 대조에서 실제로 실패한다(2R #3 수정이 유효)
- raw: `.cache/codex-reviews/diff-d2a-r3.json`

### 수렴 판정
- 3라운드에서 **P0 0 · P1 1**(그 1건도 3R 수정이 만든 것) → 상한 3회 도달로 종료. **P1 = 0 은 아니다.**
- 남은 위험: 3R 수정(정규화 키 필터·문서 정정)을 제3자 리뷰로 확인하지 못했다.
  ④-d-2b 착수 시 **이 diff 를 포함해** 1라운드를 먼저 돌린다.

### 최종 검증 (2026-08-29)
- `./gradlew test` **BUILD SUCCESSFUL** (21m 12s) · **813 테스트 0 실패**
- lint **13종** 그린 — 신규 `e2e-network-contract`(self-test 9) · `kafka-subscription-contract`(self-test 9) + 기존 11종, pg-stub self-test 19
- `TOSS_BASE_URL` 앰비언트 주입 상태에서도 계약 테스트 통과(환경 격리)
- E2E: **A · B · C · D 각각 통과**(강화된 단언). **4종 연속 실행은 불안정**(§9-9)

## 2026-08-29 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/92)
- `docs/TASKS.md` 구현 ④ 행: **④-d-2a ✅ #92** 등재 + ④-d-2b 🔲 대기(P10~P20 + ④ 종결, 착수 시 #92 diff 포함 재리뷰 선행). ④ 자체는 **🔄 유지** — 종결은 d-2b 소관
- `docs/progress/PHASE4.md`: ④-d-2a 이력 추가(분할 근거·설계 2회 뒤집힘·정본 복제 3건 중 1건 놓침·실행이 반증한 전제·리뷰 3라운드·미충족 5건)
- **ADR**: 신규/상태 전환 없음 — 이 PR 은 새 아키텍처 결정을 만들지 않았다. `base-url` 소유는 ADR-0007 기존 결정의 적용이고, 스위치 폐기는 ADR-0018 을 **따른** 것이다
- **Layer 1(01~07) 동기화**: 계획 P20 소관이라 **④-d-2b 로 이연**(이 PR 범위 밖)

## 2026-08-29 — CI 실패 수정 (#92)
- **실패**: `payment-service:test` 의 `[SAGA-P1-BASEURL-BOOT] 기본 활성 프로파일 local 에서는 local 의 endpoint 가 이긴다` (162 중 1건)
- **원인**: CI 는 `SPRING_PROFILES_ACTIVE: test` 로 빌드하는데(`.github/workflows/ci.yml:104-106`) 그 테스트가 **앰비언트 활성 프로파일에 의존**했다. `test` 프로파일에는 `base-url` 선언이 없어 base sentinel 이 해석되고, "local 이 이긴다" 단언이 깨진다.
- **성격**: diff 리뷰 3R #1(앰비언트 `TOSS_BASE_URL` 의존)과 **같은 부류**다. base-url 계열 키는 걷어냈으면서 **활성 프로파일은 걷어내지 않았다.**
- **수정**: 격리 initializer 의 제거 대상에 `spring.profiles.active` 를 추가. 프로파일이 필요한 테스트는 각자 `withPropertyValues` 로 명시한다.
- **재발 방지 관점**: 로컬에서 `./gradlew test` 만 돌리고 **CI 의 실제 명령(`./gradlew build` + `SPRING_PROFILES_ACTIVE=test`)을 재현하지 않은 것**이 누락 지점이다. 이번엔 그 조합으로 재현 → 수정 → 재검증했다.
- **검증**: `SPRING_PROFILES_ACTIVE=test ./gradlew build --no-daemon` → **BUILD SUCCESSFUL (20m 32s)** · CI lint 블록 전량(self-test 포함) 그린 · `SPRING_PROFILES_ACTIVE=test TOSS_BASE_URL=... TOSS_PAYMENTS_BASE_URL=...` 동시 주입에서도 통과

## 2026-08-29 — CI 실패 2차 (#92, images 잡)
- **실패**: `images (payment-service)` health smoke — `Could not resolve placeholder 'TOSS_BASE_URL' in value "${TOSS_BASE_URL}" <-- "${toss.payments.base-url}"`
- **원인**: 1차 수정으로 `build` 가 통과하자 **그 뒤에 도는 `images` 잡이 처음으로 실행**됐고, 거기서 P1 이 만든 결합이 드러났다. `application-k8s.yml` 에 기본값 없는 `${TOSS_BASE_URL}` 을 넣어 **k8s 프로파일로 뜨는 모든 경로**가 값 주입 전까지 부팅 실패한다 — `docker-health-smoke.sh:105` 가 `SPRING_PROFILES_ACTIVE=k8s` 로 띄우고 `TOSS_SECRET_KEY`/`TOSS_WEBHOOK_SECRET` 만 주입한다.
- **판단 오류**: k8s 매니페스트가 `TOSS_*` 를 비워 두는 건 그것이 **자격증명**이기 때문이다(`secret.yml:12-15` 가 명시). `base-url` 은 endpoint 이고, 같은 파일이 `datasource.url`·`redis.host` 를 **리터럴로 선언**한다 — 규약을 잘못 읽고 자격증명 쪽에 붙였다.
- **수정**: `base-url: ${TOSS_BASE_URL:https://api.tosspayments.com/v1}` — **누락될 값 자체를 없앴다**(env override 유지). 계약 테스트도 "미주입 시 부팅 실패" → "미주입이어도 실 PG 로 해석" 으로 정정.
- **영향 범위 재점검**: 실제 k8s 배포도 같은 이유로 깨졌을 것이다(operator 가 `TOSS_BASE_URL` 을 주입할 이유가 없었다). 이번 수정이 그것도 함께 해소한다.
- **검증**: `bash scripts/docker-health-smoke.sh payment-service:smoke` → **passed** (CI 와 같은 스크립트) · `SPRING_PROFILES_ACTIVE=test ./gradlew build` → **BUILD SUCCESSFUL (8m 52s)** · 프로파일+base-url env 동시 주입에서도 계약 테스트 통과

---

# ④-d-2b (P10~P20 + ④ 종결)

## 2026-08-29 — 선행 재리뷰 (#92 diff, 계획이 요구한 착수 조건)
- 대상: `git diff 85dd363 275cda3` (3137줄) — 머지된 #92 **전체**
- 사유: #92 는 diff 리뷰 3라운드를 받았으나 **3R 수정 자체가 제3자 검토를 받지 못했다**(계획 §9-1)
- 결과: **5건 (P0:0 · P1:0 · P2:5)** · 반영 4 / 계획서 정정 1 / 기각 0
- **3R 수정 4건은 전부 유효 판정** — 앰비언트 환경 격리(정규화 키)·알림 `type` 키잉·DLQ enum 정합 계약에서 새 결함 없음. 착수 조건 해소.
- 처리:
  1. `e2e-network-contract-lint.sh` — `services` 비어있음만 검사해 **`payment-service` 를 지운 fixture 가 exit 0**. 위반이 준 게 아니라 **검사 대상이 줄어든 것** → `REQUIRED` 집합 대조 + self-test 삭제 대조군 2종
  2. `saga_e2e.py` 시나리오 C — docstring 은 "동시 투입" 인데 `send().get()` 직렬 → `publish_together()` 한 배치 flush. **소비 동시성까지는 보장 못 하므로** docstring 을 실제 보장 수준으로 낮춤(진짜 경합 증명은 ④-c-1b JVM 통합테스트)
  3. Flyway readiness — 성공 **개수**만 비교 → **버전 집합** 정확 대조
  4. 문서 3곳 정정 — `ce7d75a` 가 k8s 를 `${TOSS_BASE_URL:https://...}` 로 바꿨는데 `TossPaymentClient` Javadoc·`application.yml` 주석·`TossBaseUrlContractTest` Javadoc 이 **여전히 "기본값 없이 강제해 fail-fast"** 로 반대 안내
  5. **기각 → 계획서 정정**: "stub `confirm` 을 계획대로 500 으로" 는 **P6 을 깨뜨린다**(P6 이 `POST /api/v1/payments/confirm` 을 실제 진입점으로 요구, confirm 이 항상 500 이면 가드 거부 `PAY-008` 과 PG 실패 `PAY-005` 를 구분 불가). 초안 문장이 P6 확정 전에 쓰였다 → 계획 §3 P2·§5 를 정정하고 "조용한 통과 차단" 은 `STUB_UNSCRIPTED_KEY` 가 **키 단위로** 담당함을 명시
- raw: `.cache/codex-reviews/diff-pr92-precheck-*.json`

## 2026-08-29 — P10 시나리오 격리
- **false-green 2건 실측**:
  1. 시나리오 A 의 `processed_events` 단언이 **`consumer_group` 만** 조회 — 이 테이블은 `(event_id, consumer_group)` UNIQUE 라 **앞 시나리오가 남긴 행**이 조건을 만족시킨다. 세 소비자가 전부 no-op 이어도 통과했다 → 발행 outbox envelope 의 `eventId` 로 키잉
  2. 시나리오 D 가 `origin_topic='payment.failed'` 로만 조회 — **시나리오 A 가 같은 토픽에 실제 `payment.failed` 를 흘린다** → poison 본문에 marker 를 심고 `payload LIKE` 로 키잉(`DLT_ORIGINAL_KEY` 부재는 #92 실측)
- 시나리오 C 의 `order_id` 를 `time.time()` → `sid + RUN_ID` CRC 유도로 결정화
- 실행 순서 계약: "배경 스케줄러 간섭 0" 은 **단언하지 않음**(`UNRESOLVED` 는 reconciliation 의 명시적 후보라 값이 바뀌는 게 정상) → ① 키 결부(주 방어) ② `LINGERING_SCENARIOS` 를 꼬리에 고정. 정본은 `saga_e2e.py` 하나이고 smoke 가 import 해 **스택 기동 전에** 검사(`pymysql` 지연 임포트로 전환)
- 검증: 순서 self-test 9종 · 위반 순서 `d,a` 가 기동 전에 차단됨

## 2026-08-29 — P11~P15 매트릭스 게이트
- 매트릭스 정본 `docs/plans/fixtures/saga-contract-matrix.tsv` — 26행 · 6열 · `expected` 는 canonical JSON object
- `scripts/saga-contract-matrix-lint.sh` 3분기 + **required-ID 정본을 lint 안에**(N4 — 매트릭스가 유일 입력이면 행 삭제가 검사 대상만 줄여 통과)
- 증적 키 = **`testcase@classname` + `[SAGA-xxx]`**(`testsuite@name` 은 클래스 `@DisplayName` 으로 덮여 키가 될 수 없다). 기존 테스트 **19개 태깅** + **Notification `UNRESOLVED` 테스트 신설**(3×3 중 유일한 부재)
- manifest 를 `evidence[key] = {actual: …}` 로 재구성하고 진단값은 `diagnostics` 로 분리 — exact equality 를 쓰되 메타필드로 항상 실패하지 않도록
- **self-test 36종 전량 통과**: 구조 훼손·canonical 위반·필수행 삭제·missing/failure/error/skipped/duplicate·`testsuite@name` 무관·중첩 `Outer$Inner`·키 순서 무관/타입 불일치(`"1"` vs `1`)·subset 금지·stale commit

## 2026-08-29 — P17 스케줄러 배선
- 기존 타임아웃/sweeper 테스트는 `@InjectMocks` 객체를 **직접 호출**해 `@Scheduled` 를 지워도 통과했다
- 주기·lock 을 base 소유 타입 안전 properties 로 분리(`OrderSchedulerProperties`/`StockSchedulerProperties`, ADR-0007 동작 정책) + placeholder 에 **인라인 기본값 금지**(base 선언이 사라져도 조용히 도는 경로 차단)
- **결정성은 계획 원안과 다르게 잡았다.** 원안의 "컨텍스트 기동 **전** seed initializer" 는 **성립하지 않는다** — 앱 테이블 스키마를 Flyway 가 컨텍스트 기동 **중**에 만들기 때문이다. 대신 주기·lock 하한만 짧게 덮어 반복 발화가 seed 를 잡게 하고, **운영 기본값은 별도 properties 계약 테스트**가 고정한다(한 테스트에 두 관심사를 넣으면 둘 중 하나는 반드시 거짓이 된다)
- **변이 검사로 false-green 아님을 실증**: `@Scheduled` 한 줄 제거 → Order 2건·Product 2건 전부 FAILED, 원복 후 통과
- 구현 중 실측 2건: `categories` 에 `created_at` 컬럼 없음 / 회수는 재고 복구까지 한 트랜잭션이라 `inventories` 행이 없으면 **RELEASED 전이까지 롤백**되어 배선이 끊긴 것처럼 보인다

## 2026-08-29 — P16 음성 대조군 (실제 스택)
- 명령: `E2E_RUN_ID=nc4-… bash scripts/saga-e2e-smoke.sh --negative-control` → **exit 0 · 7종 전량 통과**
- **양성 대조군이 제 설계 결함 3건을 연속 반증했다**:
  1. 컨테이너 안 `host.docker.internal`/`ip` 로 주소를 못 구해 **HOST_ADDR 빈 값**
  2. 프로브가 `python3` 를 썼는데 **payment-service 이미지에 python3 가 없다**(실측) → 음성이 "격리돼서" 가 아니라 **"명령이 없어서"** 통과했다 — 정확히 이 대조군이 막으려던 false-green
  3. 표적을 호스트 프로세스로 두니 Docker Desktop 에서 브리지 게이트웨이가 VM 안 주소라 **비격리 컨테이너조차 닿지 못했다** → 표적을 컨테이너(`egress-canary`)로 이동
- 스크립트 자체 결함 2건: `external-control` 이 `profiles: [control]` 이라 미생성 → `docker network inspect` 실패가 **`pipefail` 때문에 대입문에서 스크립트를 죽였다** / `docker compose run` 은 `--profile` 을 받지 않는다(top-level 플래그) → `COMPOSE_PROFILES`
- 판정은 **rc 를 특정**한다 — "아무 비정상 종료" 로 받으면 127(도구 없음)이 다시 통과한다
- `egress-canary` 추가가 기존 lint 를 느슨하게 만든 것도 잡았다("대조군이 하나라도 보이면 통과") → 집합 전체 요구, self-test 12종
- **증명 범위**: internal-only 앱이 다른 네트워크 호스트에 닿지 못한다. 인터넷 egress 차단 자체의 증거는 #92 의 `UnknownHostException` 라이브 관측이 따로 있다

## 2026-08-29 — P18/P19, 그리고 #92 미충족 #2 의 근본 원인
- P18: `images` 매트릭스 6개 유지(줄이면 `image-contract-lint` 파서가 깨진다 — 통과 확인) · Save/Upload 단일 조건식으로 **PR 에서도 saga 4개 업로드** + `if-no-files-found: error` · `e2e` 잡 신설(artifact 정확히 4개 검증 후 `docker load`) · `if: always()` 증적 업로드 후 `down -v` · build=`--structure`+`--jvm-evidence` / e2e=`--structure`+`--e2e-evidence` · 게이트는 `gradlew build` **뒤** · test artifact glob `*/build/**`
- P19: 구간별 절대 상한 + `durations.tsv` + "재시도는 인프라 기동에만"
- **가설 3개가 실측으로 반증됐다**: `local` 프로파일 디버그 로깅(기동·A 는 빨라졌으나 B 는 그대로) / 토픽 파티션 late discovery 사전 생성(`생성 성공 1`, B 여전히 189초) / 소비 없이 유실(운영 버그 — `processed_events` 2행 확인으로 **유실 아님**)
- **실제 원인은 내가 쓴 코드의 버그였다.** `while read` 루프 안의 `docker compose exec -T` 가 **루프의 stdin(토픽 목록)을 통째로 삼켜** 첫 반복 뒤 루프가 끝났다 → 20종 중 1종만 생성. **같은 버그로 검증 루프도 1건만 돌아 "대조 실패 0" 이 vacuous 였다** — 검사가 스스로를 속였다
- `</dev/null` 추가 후: `토픽 20종 — 생성 성공 20 · 대조 실패 0`, `scenario:b` **162~189초(실패/경계) → 21s → 10s → 14s**
- 원리: 소비자가 토픽 생성 **전에** 구독하면 파티션 0만 보고, 나머지 파티션의 메시지는 메타데이터 갱신(~200초) 뒤에야 소비된다 — 그래서 **두 번째 시나리오만** 느렸다
- 재발 방지: **`생성 성공 == 선언 개수`를 계약으로 강제**. 토픽 파티션 정본은 `--emit-topics` 로 `TopicBuilder` 선언에서 유도(정본 복제 회피)
- **실행 증적 (§5 실패 주입)**:
  | 명령 | exit | 결과 |
  |---|---|---|
  | `saga-e2e-smoke.sh --self-test` | 0 | 순서 계약 9종 |
  | `e2e-network-contract-lint.sh --self-test` | 0 | 12종 |
  | `saga-contract-matrix-lint.sh --self-test` | 0 | 36종 |
  | `saga-contract-matrix-lint.sh --structure` | 0 | 26행 |
  | `kafka-subscription-contract-lint.sh --self-test` | 0 | 9종 |
  | `pg-stub/self-test.py` | 0 | 19종 |
  | `saga-e2e-smoke.sh --negative-control` (nc4) | 0 | 7종 |
  | `saga-e2e-smoke.sh` (fix / v1 / v2) | 0 / 0 / 0 | 시나리오 4종 **3회 연속** |
  | `@Scheduled` 제거 변이 (order / product) | 1 / 1 | 배선 테스트가 잡는다 |
  | 증적 artifact | — | `.cache/e2e/<run_id>/` (manifest·durations·compose.log) |

## 2026-08-29 — diff 리뷰 라운드 1 (④-d-2b)
- 항목: **5건 (P0:0 · P1:4 · P2:1)** · 반영 5 / 기각 0
- **전부 게이트가 스스로를 속이는 경로였다**:
  1. **(P1)** e2e `expected` 의 의미가 매트릭스에만 있다 — 매트릭스와 `saga_e2e.py` 의 actual 을 **함께** `{"refund_rows":1}` 로 줄이면 `--structure` 도 `--e2e-evidence` 도 통과하며 `payment_status`·`refund_status`·outbox 계약이 사라진다. required-ID 를 lint 밖에 둔 것과 같은 논리가 **관측 키에도** 필요했다 → `REQUIRED_E2E_KEYS` 정본 추가
  2. **(P1)** duplicate 가 **같은 결과**로 반복되면 통과했다(P15·N5 는 non-zero 를 요구). e2e 쪽도 `manifests[sid]` 대입이 같은 시나리오의 성공 manifest 를 조용히 덮어썼다 → outcome 을 리스트로 모아 2건 이상이면 실패, manifest 중복도 실패
  3. **(P1)** 배선 테스트가 `PAYMENT_REQUESTED` 주문 하나만 seed 해 **타임아웃 3종 중 1종만** 검증했다. 나머지 둘은 `PENDING` 전용 조회라 그 fixture 를 집지 않는다 — **`OrderTimeoutScheduler:61`·`:83` 의 `@Scheduled` 를 지워도 통과**했다. audit 의 "한 줄 제거" 주장도 첫 잡만 증명한 것이었다 → PENDING 계열 fixture 2종 추가
  4. **(P1)** readiness 음성 대조군이 앱을 정지시킨 뒤 `check_readiness()` 를 부르는데, readiness 는 health(3) → group(5) 순서라 **health 에서 먼저 죽어 group 검사가 실행되지 않았다**. 게다가 모든 `Exception` 을 성공으로 처리해 Kafka/DB 오류까지 '감지 성공' 이 됐다 → `skip_health=True` 신설 + 실패 사유가 `consumer group` 인지 특정
  5. **(P2)** `BUDGET_CONTROL` 을 선언만 하고 쓰지 않아 대조군의 유일한 상한이 CI 잡 45분이었다 → 워치독으로 실제 적용
- **변이 검사 3회로 #3 해소를 실증**: `cancel-expired-delay`·`unconfirmed-reservation-delay`·`lease-expiry-delay` 의 `@Scheduled` 를 각각 제거 → 전부 exit 1
- raw: `.cache/codex-reviews/diff-d2b-r1-*.json`

## 2026-08-29 — diff 리뷰 라운드 2 (④-d-2b)
- 항목: **4건 (P0:0 · P1:3 · P2:1)** · 반영 4 / 기각 0
- **`skip_health` 기본 경로와 스케줄러 fixture 3종의 교차 조건에서는 결함 없음** 확인(1R 수정 4건 중 2건은 새 결함을 만들지 않았다)
- **1라운드 수정이 만든 새 결함 1건 + 기존 비대칭 3건**:
  1. **(P1 · 실제 CI 결함)** CI 가 정상 실행과 음성 대조군을 **같은 `E2E_RUN_ID`·출력 디렉터리**로 돌린다. 대조군은 시나리오 A 를 일부러 실패시키므로 그 failure manifest 가 앞서 만든 success 를 덮어쓰고 `--e2e-evidence` 가 `result=failure` 로 실패한다. **내 로컬 검증이 둘을 서로 다른 run_id 로 따로 돌려서 이 조합을 재현하지 않았다** → 대조군 증적을 `negative-control/` 하위로 격리 + CI 에서 대조군에 별도 run_id 부여(project 결합 제거)
  2. **(P1)** 워치독이 명시적 말단 2곳에서만 정리되고 teardown 에 연결되지 않았다 — `set -e` 로 중간에 죽으면 백그라운드 sleep 이 살아남아 나중에 저장된 `$$` 로 TERM 을 보낸다(PID 재사용 시 무관한 프로세스) → 센티넬 파일 + `stop_watchdog()` 를 teardown 이 소유
  3. **(P1)** `REQUIRED_IDS` 를 **누락만** 검사해 임의의 `SAGA-*` 행 추가가 통과했다(계획 §7 은 정확 일치를 요구) → 초과 ID 도 실패
  4. **(P2)** `REQUIRED_E2E_KEYS.get(id)` 가 None 이면 조용히 건너뛰어, 새 e2e 행을 등록 없이 추가하면 1R 에서 막은 축소 우회가 **그 행에서 부활**한다 → e2e 행 전량 등록 강제 + 역방향(등록만 있고 행이 없음)도 검사
- **실행으로 #1 해소 검증**: 같은 `E2E_RUN_ID` 로 정상 실행 → 음성 대조군 순차 실행 후 최상위 manifest 4종 전부 `success` 유지, 대조군 failure 는 `negative-control/manifest-a.json` 에 격리, `--e2e-evidence` **OK**
- 새 검사 4종 전부 self-test 대조군 동반(총 **40종**)
- raw: `.cache/codex-reviews/diff-d2b-r2-*.json`

## 2026-08-29 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/93)
- `docs/TASKS.md`: 구현 ④ **🔄 → ✅**, ④-d-2b 요약 + PR 링크 등재. `L-013` 은 #84 에서 이미 해소, `D-020` 은 이미 등재돼 추가 작업 없음
- `docs/progress/PHASE4.md`: ④-d-2b 이력 + PR 링크. **ADR-0012 ④ 산출물 대비 실제 범위 차이** 절 추가(ADR 은 immutable 이라 Layer 3 에 기록)
- Layer 1: `02` 토픽 수 **7 → 10** 정정(ADR-0018 보상/환불 3종 누락) · `06` 에 cross-service saga E2E·음성 대조군·계약 게이트 계층 등재
- **ADR**: 신규/상태 전환 없음 — 이 PR 은 새 아키텍처 결정을 만들지 않았다. properties 소유는 ADR-0007 적용, 게이트 설계는 ADR-0015/0019 의 연장이다
- 커밋 11개 (`3f03922`..`4dbec88`)

## 2026-08-30 — CI 실패 수정 (#93, `4a7d9ae`)
- **실패**: `:order-service:test` — `OrderCancelledEventContractIntegrationTest > Outbox 저장 실패 주입 …` (219 중 1건) · `org.mockito.exceptions.misusing.UnfinishedStubbingException at :113`
- **원인**: context-wide `@MockitoSpyBean OutboxEventRepository` 를 **5초마다 도는** `OutboxPollingScheduler` 가 함께 호출한다(`OutboxPollingService:71` 의 `findPendingEvents`). Mockito 의 `willThrow(...).given(spy).save(...)` 는 **두 단계**라, 그 사이에 poller 스레드가 spy 를 건드리면 예외가 난다. **코드가 아니라 주사위가 결정하는 실패**다.
- **이 PR 의 변경과 무관하다** — diff 에 이 테스트도 poller 도 없고, 로컬 `SPRING_PROFILES_ACTIVE=test ./gradlew build` 는 그린이었으며 main CI 도 그린이었다. ④-b(#85)가 이 테스트를 만든 이래 잠복해 있던 경합이다.
- **게이트 실패 11건은 부수 증상**이었다 — build 가 order-service 에서 멈춰 product/payment 테스트가 실행되지 않았고, 매트릭스 게이트는 `if: always()` 라 그 상태에서 돌아 "증적 없음" 을 신고했다. **게이트는 오작동한 게 아니라 "이 계약을 증명할 테스트가 돌지 않았다" 를 정확히 잡은 것**이다. 다만 로그 맨 아래라 원인처럼 보였다.
- **수정**: 같은 패턴이 **3개 클래스**에 있어 전부 `@MockitoBean OutboxPollingScheduler` 로 닫았다 — 하나만 고치면 나머지가 계속 주사위를 굴린다. 뒤 두 개(`OrderCompensationRefund`·`StockCompensationRefund`)에는 **두 번째 경합**도 있었다: backfill 검사가 `countByStatus("PENDING")` 을 단언하는데 실제 poller 가 그 행을 집어 `PUBLISHED` 로 바꿀 수 있다. 발행이 필요한 검사는 `kafkaTemplate.send` 로 직접 넣으므로 poller 에 의존하지 않는다.
- **검증**: 세 클래스 실행 통과 · `SPRING_PROFILES_ACTIVE=test ./gradlew build` **BUILD SUCCESSFUL (19m 37s)** · 매트릭스 게이트 `--structure`/`--jvm-evidence` OK

## 2026-08-30 — CI 전체 그린 ([run 33260424768](https://github.com/Kimgyuilli/PeekCart/actions/runs/33260424768))
- `build` · `images` 6종 · **`e2e`** 전부 success
- **`e2e` 잡이 GitHub 러너에서 처음 완주했다** — 계획 §6 조건 3 및 PR 미충족 1번 해소:
  - artifact 4개 download → `loaded 4 images`
  - `토픽 20종 — 생성 성공 20 · 대조 실패 0`
  - 시나리오 a/b/c/d 전부 통과 (`cancel_reason=PAYMENT_FAILED`/`RESERVATION_FAILED` 확인)
  - **음성 대조군 전량 통과** (별도 run_id `gh-…-nc` 로 project·증적 분리)
  - `OK — --structure · 26행` · `OK — --e2e-evidence · 26행`
- **조건 4 실측**: `--jvm-evidence` 는 build 잡, `--e2e-evidence` 는 e2e 잡에서 **각각 자기 evidence type 만** 검사
- **구간별 소요** (예산 대비 여유): infra-up 26s · topic-precreate 65s · app-up 18s×4 · readiness 2s · scenario a/b/c/d **18/14/8/38s** · e2e 잡 전체 **15분 41초**(상한 45분)
- **`scenario:b` 14초** — 로컬에서 162~189초로 실패하던 시나리오다. 토픽 사전 생성 수정이 CI 에서도 유효함이 확인됐다
